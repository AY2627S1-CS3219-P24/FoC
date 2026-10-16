package com.cs3219.foc.credit.integration;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import com.cs3219.foc.credit.CreditServiceApplication;
import com.cs3219.foc.credit.infrastructure.messaging.producer.CreditResultEventPublisher;
import com.cs3219.foc.credit.model.dto.event.ErrandSubmittedEvent;
import com.cs3219.foc.credit.service.CreditReservationService;
import com.cs3219.foc.credit.service.CreditResultOutboxService;
import com.rabbitmq.client.AMQP;
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
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;

@EnabledIfEnvironmentVariable(named = "FOC_TEST_DATABASE_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "FOC_TEST_RABBIT_HOST", matches = ".+")
class ReservationMessagingTests {
    @Test
    void consumesErrandAndRecoversCommittedResultAfterBrokerOutageAndServiceRestart() throws Exception {
        var suffix = UUID.randomUUID().toString().replace("-", "");
        var schema = "reservation_broker_" + suffix;
        var input = "foc.errand.test." + suffix;
        var output = "foc.credit.result.test." + suffix;
        var queue = output + ".order-credit-results";
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
        // Keep callback threads alive across both contexts; channel-close callbacks can
        // submit more work after Spring has started destroying a connection factory.
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
                    "--spring.cloud.function.definition=errandSubmitted",
                    "--spring.cloud.stream.bindings.errandSubmitted-in-0.destination=" + input,
                    "--spring.cloud.stream.bindings.creditResult-out-0.destination=" + output,
                    "--credit.provisioning.enabled=false",
                    "--credit.rewards.retry-delay=100ms",
                    "--credit.rewards.publish-interval=100",
                    "--credit.rewards.interval=100"));
            var user = UUID.randomUUID();
            var event = new ErrandSubmittedEvent(
                    UUID.randomUUID(),
                    "ErrandSubmittedEvent",
                    1,
                    OffsetDateTime.now(),
                    new ErrandSubmittedEvent.Payload(UUID.randomUUID(), UUID.randomUUID(), user, 80));
            UUID resultId;
            try {
                var paused = new ArrayList<>(args);
                paused.add("--credit.rewards.enabled=false");
                try (var context = startCredit(paused, rabbitExecutor)) {
                    sql.update("INSERT INTO credit_accounts(user_id,available_balance) VALUES (?,100)", user);
                    channel.confirmSelect();
                    channel.basicPublish(
                            input,
                            input,
                            new AMQP.BasicProperties.Builder()
                                    .contentType("application/json")
                                    .deliveryMode(2)
                                    .build(),
                            JsonMapper.builder().build().writeValueAsBytes(event));
                    channel.waitForConfirmsOrDie(5000);
                    await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(
                                    sql.queryForObject("SELECT count(*) FROM credit_reserve_inbox", Long.class))
                            .isEqualTo(1));
                    broker.offline();
                    assertThat(context.getBean(CreditReservationService.class).processNext())
                            .isTrue();
                    var outbox = context.getBean(CreditResultOutboxService.class);
                    var delivery = outbox.claimNext().orElseThrow();
                    resultId = delivery.event().eventId();
                    assertThatThrownBy(() -> context.getBean(CreditResultEventPublisher.class)
                                    .publish(delivery.event()))
                            .isInstanceOf(RuntimeException.class);
                    outbox.retryLater(delivery, "BrokerUnavailable");
                    assertThat(sql.queryForObject(
                                    "SELECT published_at IS NULL FROM credit_result_outbox", Boolean.class))
                            .isTrue();
                    assertThat(sql.queryForObject(
                                    "SELECT reserved_balance FROM credit_accounts WHERE user_id = ?", Long.class, user))
                            .isEqualTo(80);
                }
                broker.online();
                try (var context = startCredit(args, rabbitExecutor)) {
                    await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(sql.queryForObject(
                                    "SELECT published_at IS NOT NULL FROM credit_result_outbox", Boolean.class))
                            .isTrue());
                    var received = channel.basicGet(queue, true);
                    assertThat(received).isNotNull();
                    assertThat(received.getProps().getDeliveryMode()).isEqualTo(2);
                    var body = JsonMapper.builder().build().readTree(received.getBody());
                    assertThat(body.get("eventId").asText()).isEqualTo(resultId.toString());
                    assertThat(body.get("payload").get("outcome").asText()).isEqualTo("RESERVED");
                    assertThat(body.get("payload").get("amount").asLong()).isEqualTo(80);
                    assertThat(body.get("payload").get("operationId").asText())
                            .isEqualTo(event.payload().operationId().toString());
                    assertThat(sql.queryForObject(
                                    "SELECT available_balance FROM credit_accounts WHERE user_id = ?",
                                    Long.class,
                                    user))
                            .isEqualTo(20);
                }
            } finally {
                // Both contexts are closed. Drain their channel-close and confirm callbacks
                // before the test-owned executor is shut down; do not ignore background failures.
                await().atMost(Duration.ofSeconds(10))
                        .until(() -> rabbitExecutor.getActiveCount() == 0
                                && rabbitExecutor.getQueue().isEmpty());
                channel.queueDelete(input + ".credit-reservations");
                channel.queueDelete(input + ".credit-reservations.retry");
                channel.queueDelete(queue);
                channel.exchangeDelete(input);
                channel.exchangeDelete(input + ".dlx");
                channel.exchangeDelete(output);
                sql.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    private ConfigurableApplicationContext startCredit(List<String> args, ThreadPoolExecutor rabbitExecutor) {
        return new SpringApplicationBuilder(CreditServiceApplication.class)
                .initializers(context -> context.getBeanFactory().addBeanPostProcessor(new BeanPostProcessor() {
                    @Override
                    public Object postProcessBeforeInitialization(Object bean, String beanName) {
                        if (bean instanceof CachingConnectionFactory factory) {
                            factory.setExecutor(rabbitExecutor);
                        }
                        return bean;
                    }
                }))
                .run(args.toArray(String[]::new));
    }
}
