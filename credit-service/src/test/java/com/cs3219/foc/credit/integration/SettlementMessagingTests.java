package com.cs3219.foc.credit.integration;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import com.cs3219.foc.credit.CreditServiceApplication;
import com.cs3219.foc.credit.model.dto.ReserveCreditRequest;
import com.cs3219.foc.credit.model.dto.event.ErrandSettlementEvent;
import com.cs3219.foc.credit.service.*;
import com.rabbitmq.client.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;

@EnabledIfEnvironmentVariable(named = "FOC_TEST_DATABASE_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "FOC_TEST_RABBIT_HOST", matches = ".+")
class SettlementMessagingTests {
    @Test
    void directSettlementRetriesParkInDlqAndRegistryRecoversAfterRestart() throws Exception {
        var suffix = UUID.randomUUID().toString().replace("-", "");
        var schema = "settlement_broker_" + suffix;
        var cancelled = "foc.cancel.test." + suffix;
        var expired = "foc.expire.test." + suffix;
        var completed = "foc.complete.test." + suffix;
        var output = "foc.settlement.test." + suffix;
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
        try (var executor = (ThreadPoolExecutor) Executors.newCachedThreadPool();
                var broker = new ProvisioningEndToEndTests.TcpSwitch(rabbit.getHost(), rabbit.getPort());
                var connection = rabbit.newConnection();
                var channel = connection.createChannel()) {
            var args = new ArrayList<>(List.of(
                    "--spring.config.location="
                            + root.resolve("credit-service/src/main/resources/application.yaml")
                                    .toUri(),
                    "--spring.main.banner-mode=off",
                    "--spring.cloud.aws.s3.enabled=false",
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
                    "--events.publication.confirm-timeout=1s",
                    "--events.publication.retry-delay=100ms",
                    "--spring.modulith.events.staleness.published=0s",
                    "--spring.modulith.events.staleness.processing=0s",
                    "--spring.modulith.events.staleness.resubmitted=0s"));
            for (var binding : List.of("errandCancelled", "errandExpired", "errandCompleted")) {
                args.add("--spring.cloud.stream.bindings." + binding + "-in-0.consumer.back-off-initial-interval=100");
                args.add("--spring.cloud.stream.bindings." + binding + "-in-0.consumer.back-off-max-interval=200");
            }
            var owner = UUID.randomUUID();
            var courier = UUID.randomUUID();
            UUID recoveryId;
            try {
                try (var context = start(args, executor)) {
                    var accounts = context.getBean(CreditAccountService.class);
                    accounts.initializeAccount(owner);
                    sql.update("UPDATE credit_accounts SET total_balance=100 WHERE user_id=?", owner);
                    var reserve = context.getBean(CreditReservationService.class);
                    var a = reserved(reserve, owner, 20, "ErrandCancelledEvent", null);
                    var b = reserved(reserve, owner, 20, "ErrandExpiredEvent", null);
                    var c = reserved(reserve, owner, 40, "ErrandCompletedEvent", courier);
                    channel.confirmSelect();
                    var invalidAmounts =
                            List.of("1.9", "1.0", "1e2", "0", "-1", "9223372036854775808", "-9223372036854775809");
                    var body = JsonMapper.builder().build().writeValueAsString(a);
                    for (var amount : invalidAmounts) {
                        channel.basicPublish(
                                cancelled,
                                cancelled,
                                new AMQP.BasicProperties.Builder()
                                        .contentType("application/json")
                                        .deliveryMode(2)
                                        .build(),
                                body.replace("\"amount\":20", "\"amount\":" + amount)
                                        .getBytes(StandardCharsets.UTF_8));
                    }
                    channel.waitForConfirmsOrDie(5000);
                    await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(
                                    channel.queueDeclarePassive(cancelled + ".credit-settlements.dlq")
                                            .getMessageCount())
                            .isEqualTo(invalidAmounts.size()));
                    assertThat(accounts.getBalance(owner).reservedBalance()).isEqualTo(80);
                    assertThat(sql.queryForObject("SELECT count(*) FROM credit_transaction_log", Long.class))
                            .isEqualTo(3);
                    publish(channel, cancelled, a);
                    publish(channel, expired, b);
                    publish(channel, completed, c);
                    await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                        assertThat(sql.queryForObject(
                                        "SELECT count(*) FROM credit_reservations WHERE state='REFUNDED'", Long.class))
                                .isEqualTo(2);
                        assertThat(channel.queueDeclarePassive(completed + ".credit-settlements.dlq")
                                        .getMessageCount())
                                .isEqualTo(1);
                    });
                    assertThat(accounts.getBalance(owner).reservedBalance()).isEqualTo(40);
                    assertThat(sql.queryForObject("SELECT count(*) FROM credit_transaction_log", Long.class))
                            .isEqualTo(5);
                    accounts.initializeAccount(courier);
                    var parked = channel.basicGet(completed + ".credit-settlements.dlq", false);
                    assertThat(parked).isNotNull();
                    channel.basicPublish(completed, completed, parked.getProps(), parked.getBody());
                    channel.waitForConfirmsOrDie(5000);
                    channel.basicAck(parked.getEnvelope().getDeliveryTag(), false);
                    await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(
                                    accounts.getBalance(courier).availableBalance())
                            .isEqualTo(55));
                    publish(channel, completed, c); // Acknowledging a duplicate must not transfer credits again.
                    await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(sql.queryForObject(
                                    "SELECT count(*) FROM event_publication WHERE completion_date IS NULL", Long.class))
                            .isZero());
                    assertThat(sql.queryForObject("SELECT count(*) FROM credit_transaction_log", Long.class))
                            .isEqualTo(6);
                    // Registry writes are part of the same transaction as settlement and its audit row.
                    var d = reserved(reserve, owner, 10, "ErrandCancelledEvent", null);
                    var service = context.getBean(CreditSettlementService.class);
                    sql.execute("ALTER TABLE event_publication ADD CONSTRAINT fail_publication CHECK(FALSE) NOT VALID");
                    try {
                        assertThatThrownBy(() -> service.settle(d)).isInstanceOf(RuntimeException.class);
                        assertThat(accounts.getBalance(owner).reservedBalance()).isEqualTo(10);
                        assertThat(sql.queryForObject(
                                        "SELECT count(*) FROM credit_transaction_log WHERE operation_id=?",
                                        Long.class,
                                        d.payload().operationId()))
                                .isZero();
                    } finally {
                        sql.execute("ALTER TABLE event_publication DROP CONSTRAINT fail_publication");
                    }
                    broker.offline();
                    service.settle(d);
                    recoveryId = UUID.fromString(sql.queryForObject(
                            "SELECT serialized_event::jsonb->>'eventId' FROM event_publication WHERE serialized_event::jsonb->'payload'->>'operationId'=?",
                            String.class,
                            d.payload().operationId().toString()));
                    await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(sql.queryForObject(
                                    "SELECT count(*) FROM event_publication WHERE serialized_event::jsonb->>'eventId'=? AND completion_date IS NULL",
                                    Long.class,
                                    recoveryId.toString()))
                            .isEqualTo(1));
                    assertThat(accounts.getBalance(owner).reservedBalance()).isZero();
                }
                broker.online();
                try (var context = start(args, executor)) {
                    await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(sql.queryForObject(
                                    "SELECT count(*) FROM event_publication WHERE completion_date IS NULL", Long.class))
                            .isZero());
                    assertThat(sql.queryForObject("SELECT count(*) FROM credit_transaction_log", Long.class))
                            .isEqualTo(8);
                    assertThat(context.getBean(CreditAccountService.class)
                                    .getBalance(owner)
                                    .availableBalance())
                            .isEqualTo(60);
                    boolean recovered = false;
                    var json = JsonMapper.builder().build();
                    GetResponse received;
                    while ((received = channel.basicGet(queue, true)) != null) {
                        assertThat(received.getProps().getDeliveryMode()).isEqualTo(2);
                        var result = json.readTree(received.getBody());
                        if (result.get("eventId").asText().equals(recoveryId.toString())) {
                            recovered = true;
                            assertThat(result.get("payload").get("outcome").asText())
                                    .isEqualTo("REFUNDED");
                        }
                    }
                    assertThat(recovered).isTrue();
                }
            } finally {
                await().atMost(Duration.ofSeconds(10))
                        .until(() -> executor.getActiveCount() == 0
                                && executor.getQueue().isEmpty());
                for (var input : List.of(cancelled, expired, completed)) {
                    channel.queueDelete(input + ".credit-settlements");
                    channel.queueDelete(input + ".credit-settlements.dlq");
                    channel.exchangeDelete(input);
                    channel.exchangeDelete(input + ".dlx");
                }
                channel.queueDelete(queue);
                channel.exchangeDelete(output);
                sql.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    private ErrandSettlementEvent reserved(
            CreditReservationService service, UUID owner, long amount, String type, UUID courier) {
        var request = new ReserveCreditRequest(UUID.randomUUID(), UUID.randomUUID(), amount);
        assertThat(service.reserve(owner, request).outcome()).isEqualTo("RESERVED");
        return new ErrandSettlementEvent(
                UUID.randomUUID(),
                type,
                1,
                OffsetDateTime.now(),
                new ErrandSettlementEvent.Payload(
                        UUID.randomUUID(), request.operationId(), request.errandId(), owner, amount, courier));
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

    private ConfigurableApplicationContext start(List<String> args, ThreadPoolExecutor executor) {
        return new SpringApplicationBuilder(CreditServiceApplication.class)
                .initializers(context -> context.getBeanFactory().addBeanPostProcessor(new BeanPostProcessor() {
                    @Override
                    public Object postProcessBeforeInitialization(Object bean, String name) {
                        if (bean instanceof CachingConnectionFactory factory) factory.setExecutor(executor);
                        return bean;
                    }
                }))
                .run(args.toArray(String[]::new));
    }
}
