package com.cs3219.foc.credit.integration;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import com.cs3219.foc.credit.infrastructure.messaging.producer.CreditResultEventPublisher;
import com.cs3219.foc.credit.model.dto.event.ErrandSettlementEvent;
import com.cs3219.foc.credit.model.dto.event.ErrandSubmittedEvent;
import com.cs3219.foc.credit.service.CreditReservationService;
import com.cs3219.foc.credit.service.CreditResultOutboxService;
import com.cs3219.foc.credit.service.CreditSettlementService;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ConnectionFactory;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;

@EnabledIfEnvironmentVariable(named = "FOC_TEST_DATABASE_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "FOC_TEST_RABBIT_HOST", matches = ".+")
class SettlementMessagingTests {
    @Test
    void settlementsRecoverThroughBrokerAndServiceRestartWithMissingCourier() throws Exception {
        var suffix = UUID.randomUUID().toString().replace("-", "");
        var schema = "settlement_broker_" + suffix;
        var cancelled = "foc.cancel.test." + suffix;
        var expired = "foc.expire.test." + suffix;
        var completed = "foc.complete.test." + suffix;
        var output = "foc.settlement.result.test." + suffix;
        var reserveOutput = "foc.reserve.unused.test." + suffix;
        var queue = output + ".order-credit-settlements";
        var url = System.getenv("FOC_TEST_DATABASE_URL");
        var sql = new JdbcTemplate(new DriverManagerDataSource(
                url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                System.getenv("FOC_TEST_DATABASE_USERNAME"),
                System.getenv("FOC_TEST_DATABASE_PASSWORD")));
        var rabbit = new ConnectionFactory();
        rabbit.setHost(System.getenv("FOC_TEST_RABBIT_HOST"));
        rabbit.setPort(Integer.parseInt(System.getenv().getOrDefault("FOC_TEST_RABBIT_PORT", "5672")));
        rabbit.setUsername(System.getenv("FOC_TEST_RABBIT_USERNAME"));
        rabbit.setPassword(System.getenv("FOC_TEST_RABBIT_PASSWORD"));
        var root = Path.of(System.getProperty("foc.root"));
        try (var rabbitExecutor = (ThreadPoolExecutor) Executors.newCachedThreadPool();
                var broker = new ProvisioningEndToEndTests.TcpSwitch(rabbit.getHost(), rabbit.getPort());
                var connection = rabbit.newConnection();
                var channel = connection.createChannel()) {
            var args = new ArrayList<>(List.of(
                    "--spring.config.location="
                            + root.resolve("credit-service/src/main/resources/application.yaml")
                                    .toUri(),
                    "--spring.main.banner-mode=off",
                    "--logging.level.root=WARN",
                    "--server.port=0",
                    "--spring.datasource.url=" + url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                    "--spring.datasource.username=" + System.getenv("FOC_TEST_DATABASE_USERNAME"),
                    "--spring.datasource.password=" + System.getenv("FOC_TEST_DATABASE_PASSWORD"),
                    "--spring.flyway.default-schema=" + schema,
                    "--spring.flyway.locations=filesystem:"
                            + root.resolve("credit-service/src/main/resources/db/migration"),
                    "--spring.rabbitmq.host=127.0.0.1",
                    "--spring.rabbitmq.port=" + broker.port(),
                    "--spring.rabbitmq.username=" + rabbit.getUsername(),
                    "--spring.rabbitmq.password=" + rabbit.getPassword(),
                    "--spring.rabbitmq.connection-timeout=1s",
                    "--spring.cloud.function.definition=errandCancelled;errandExpired;errandCompleted",
                    "--spring.cloud.stream.bindings.errandCancelled-in-0.destination=" + cancelled,
                    "--spring.cloud.stream.bindings.errandExpired-in-0.destination=" + expired,
                    "--spring.cloud.stream.bindings.errandCompleted-in-0.destination=" + completed,
                    "--spring.cloud.stream.bindings.creditSettlementResult-out-0.destination=" + output,
                    "--spring.cloud.stream.bindings.creditResult-out-0.destination=" + reserveOutput,
                    "--credit.rewards.retry-delay=100ms",
                    "--credit.rewards.publish-interval=100",
                    "--credit.rewards.settlement-interval=100"));
            var owner = UUID.randomUUID();
            var courier = UUID.randomUUID();
            UUID failedResult;
            ErrandSettlementEvent completion;
            try {
                var paused = new ArrayList<>(args);
                paused.add("--credit.rewards.enabled=false");
                try (var context = ReservationMessagingTests.startCredit(paused, rabbitExecutor)) {
                    sql.update("INSERT INTO credit_accounts(user_id,total_balance) VALUES (?,100)", owner);
                    var reserve = context.getBean(CreditReservationService.class);
                    var refund = reserveAndSettle(reserve, owner, 20, "ErrandCancelledEvent", null);
                    var expiry = reserveAndSettle(reserve, owner, 10, "ErrandExpiredEvent", null);
                    completion = reserveAndSettle(reserve, owner, 40, "ErrandCompletedEvent", courier);
                    sql.update("UPDATE credit_result_outbox SET published_at = CURRENT_TIMESTAMP");
                    channel.confirmSelect();
                    publish(channel, cancelled, refund);
                    publish(channel, expired, expiry);
                    publish(channel, completed, completion);
                    await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(
                                    sql.queryForObject("SELECT count(*) FROM credit_settlement_inbox", Long.class))
                            .isEqualTo(3));
                    var settle = context.getBean(CreditSettlementService.class);
                    for (int i = 0; i < 3; i++) assertThat(settle.processNext()).isTrue();
                    assertThat(sql.queryForObject(
                                    "SELECT COALESCE(SUM(amount), 0) FROM credit_reservations WHERE user_id = ? AND state = 'RESERVED'",
                                    Long.class,
                                    owner))
                            .isEqualTo(40);
                    assertThat(sql.queryForObject(
                                    "SELECT outcome FROM credit_settlement_operations WHERE courier_id = ?",
                                    String.class,
                                    courier))
                            .isEqualTo("PENDING");
                    // The refunds committed. Lose Rabbit connectivity before their result publication.
                    broker.offline();
                    var outbox = context.getBean(CreditResultOutboxService.class);
                    var delivery = outbox.claimNext().orElseThrow();
                    failedResult = delivery.event().eventId();
                    assertThatThrownBy(() -> context.getBean(CreditResultEventPublisher.class)
                                    .publish(delivery.event()))
                            .isInstanceOf(RuntimeException.class);
                    outbox.retryLater(delivery, "BrokerUnavailable");
                }
                // Duplicate delivery queues durably while Credit is stopped. Provision the missing recipient.
                publish(channel, completed, completion);
                sql.update("INSERT INTO credit_accounts(user_id) VALUES (?)", courier);
                assertThat(sql.queryForObject(
                                "SELECT COALESCE(SUM(amount), 0) FROM credit_reservations WHERE user_id = ? AND state = 'RESERVED'",
                                Long.class,
                                owner))
                        .isEqualTo(40);
                broker.online();
                try (var context = ReservationMessagingTests.startCredit(args, rabbitExecutor)) {
                    await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(sql.queryForObject(
                                    "SELECT count(*) FROM credit_result_outbox WHERE settlement_operation_id IS NOT NULL AND published_at IS NOT NULL",
                                    Long.class))
                            .isEqualTo(3));
                    assertThat(sql.queryForObject(
                                    "SELECT total_balance - (SELECT COALESCE(SUM(amount), 0) FROM credit_reservations r WHERE r.user_id = a.user_id AND r.state = 'RESERVED') FROM credit_accounts a WHERE user_id = ?",
                                    Long.class,
                                    owner))
                            .isEqualTo(60);
                    assertThat(sql.queryForObject(
                                    "SELECT COALESCE(SUM(amount), 0) FROM credit_reservations WHERE user_id = ? AND state = 'RESERVED'",
                                    Long.class,
                                    owner))
                            .isZero();
                    assertThat(sql.queryForObject(
                                    "SELECT total_balance - (SELECT COALESCE(SUM(amount), 0) FROM credit_reservations r WHERE r.user_id = a.user_id AND r.state = 'RESERVED') FROM credit_accounts a WHERE user_id = ?",
                                    Long.class,
                                    courier))
                            .isEqualTo(40);
                    assertThat(sql.queryForObject(
                                    "SELECT count(*) FROM credit_settlement_inbox WHERE processed_at IS NULL",
                                    Long.class))
                            .isZero();
                    var ids = new ArrayList<String>();
                    var outcomes = new ArrayList<String>();
                    for (int i = 0; i < 3; i++) {
                        var received = channel.basicGet(queue, true);
                        assertThat(received).isNotNull();
                        assertThat(received.getProps().getDeliveryMode()).isEqualTo(2);
                        var body = JsonMapper.builder().build().readTree(received.getBody());
                        ids.add(body.get("eventId").asText());
                        outcomes.add(body.get("payload").get("outcome").asText());
                        if ("PAID".equals(body.get("payload").get("outcome").asText())) {
                            assertThat(body.get("payload").get("courierId").asText())
                                    .isEqualTo(courier.toString());
                            assertThat(body.get("payload").get("amount").asLong())
                                    .isEqualTo(40);
                        }
                    }
                    assertThat(ids).contains(failedResult.toString());
                    assertThat(outcomes).containsExactlyInAnyOrder("REFUNDED", "REFUNDED", "PAID");
                }
            } finally {
                await().atMost(Duration.ofSeconds(10))
                        .until(() -> rabbitExecutor.getActiveCount() == 0
                                && rabbitExecutor.getQueue().isEmpty());
                for (var input : List.of(cancelled, expired, completed)) {
                    channel.queueDelete(input + ".credit-settlements");
                    channel.queueDelete(input + ".credit-settlements.retry");
                    channel.exchangeDelete(input);
                    channel.exchangeDelete(input + ".dlx");
                }
                channel.queueDelete(queue);
                channel.exchangeDelete(output);
                sql.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    private ErrandSettlementEvent reserveAndSettle(
            CreditReservationService reserve, UUID owner, long amount, String type, UUID courier) {
        var p = new ErrandSubmittedEvent.Payload(UUID.randomUUID(), UUID.randomUUID(), owner, amount);
        reserve.accept(new ErrandSubmittedEvent(UUID.randomUUID(), "ErrandSubmittedEvent", 1, OffsetDateTime.now(), p));
        assertThat(reserve.processNext()).isTrue();
        return new ErrandSettlementEvent(
                UUID.randomUUID(),
                type,
                1,
                OffsetDateTime.now(),
                new ErrandSettlementEvent.Payload(
                        UUID.randomUUID(), p.operationId(), p.errandId(), owner, amount, courier));
    }

    private void publish(Channel channel, String destination, ErrandSettlementEvent event) throws Exception {
        channel.basicPublish(
                destination,
                destination,
                new AMQP.BasicProperties.Builder()
                        .contentType("application/json")
                        .deliveryMode(2)
                        .build(),
                JsonMapper.builder().build().writeValueAsBytes(event));
        channel.waitForConfirmsOrDie(5000);
    }
}
