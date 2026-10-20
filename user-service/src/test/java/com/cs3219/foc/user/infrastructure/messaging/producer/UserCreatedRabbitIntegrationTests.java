package com.cs3219.foc.user.infrastructure.messaging.producer;

import static org.assertj.core.api.Assertions.*;

import com.cs3219.foc.user.config.EventPublicationProperties;
import com.cs3219.foc.user.model.dto.event.UserCreatedEvent;
import com.rabbitmq.client.ConnectionFactory;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.json.JsonMapper;

/** Uses the production binding configuration and a real broker, without starting a consumer. */
@EnabledIfEnvironmentVariable(named = "FOC_TEST_RABBIT_HOST", matches = ".+")
class UserCreatedRabbitIntegrationTests {
    private ConfigurableApplicationContext startPublisher(String destination) {
        return new SpringApplicationBuilder(TestConfig.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--spring.main.banner-mode=off",
                        "--logging.level.root=WARN",
                        "--spring.rabbitmq.host=" + System.getenv("FOC_TEST_RABBIT_HOST"),
                        "--spring.rabbitmq.port=" + System.getenv().getOrDefault("FOC_TEST_RABBIT_PORT", "5672"),
                        "--spring.rabbitmq.username=" + System.getenv("FOC_TEST_RABBIT_USERNAME"),
                        "--spring.rabbitmq.password=" + System.getenv("FOC_TEST_RABBIT_PASSWORD"),
                        "--spring.cloud.stream.bindings.userCreated-out-0.destination=" + destination);
    }

    @Test
    void confirmsPersistentDeliveryToExistingQueueAndDetectsUnroutableMessages() throws Exception {
        var destination = "foc.user-created.test." + UUID.randomUUID();
        var queue = destination + ".credit-provisioning";
        var event = UserCreatedEvent.of(UUID.randomUUID(), UUID.randomUUID(), OffsetDateTime.now());
        var factory = new ConnectionFactory();
        factory.setHost(System.getenv("FOC_TEST_RABBIT_HOST"));
        factory.setPort(Integer.parseInt(System.getenv().getOrDefault("FOC_TEST_RABBIT_PORT", "5672")));
        factory.setUsername(System.getenv("FOC_TEST_RABBIT_USERNAME"));
        factory.setPassword(System.getenv("FOC_TEST_RABBIT_PASSWORD"));
        try (var connection = factory.newConnection();
                var channel = connection.createChannel()) {
            try {
                channel.exchangeDeclare(destination, "topic", true, false, null);
                channel.queueDeclare(queue, true, false, false, null);
                channel.queueBind(queue, destination, "#");
                try (var context = startPublisher(destination)) {
                    context.getBean(UserCreatedEventPublisher.class).publish(event);
                }
                // Publisher shutdown must not delete the durable queue or its message.
                var declared = channel.queueDeclarePassive(queue);
                assertThat(declared.getConsumerCount()).isZero();
                assertThat(declared.getMessageCount()).isEqualTo(1);
                channel.exchangeDeclare(destination, "topic", true, false, null);
                var received = channel.basicGet(queue, true);
                assertThat(received).isNotNull();
                assertThat(received.getProps().getDeliveryMode()).isEqualTo(2);
                var body =
                        JsonMapper.builder().build().readTree(new String(received.getBody(), StandardCharsets.UTF_8));
                assertThat(body.get("eventId").asText())
                        .isEqualTo(event.eventId().toString());
                assertThat(body.get("schemaVersion").asInt()).isEqualTo(1);
                assertThat(body.get("payload").get("userId").asText())
                        .isEqualTo(event.payload().userId().toString());
                assertThat(body.get("payload").size()).isEqualTo(1);

                try (var context = startPublisher(destination)) {
                    var publisher = context.getBean(UserCreatedEventPublisher.class);
                    publisher.publish(event); // Rebind after a process restart.
                    assertThat(channel.basicGet(queue, true)).isNotNull();
                    channel.queueUnbind(queue, destination, "#");
                    assertThatThrownBy(() -> publisher.publish(event))
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("unroutable");
                    assertThat(channel.basicGet(queue, true)).isNull();
                    channel.queueBind(queue, destination, "#");
                    publisher.publish(event);
                    assertThat(channel.basicGet(queue, true)).isNotNull();
                }
            } finally {
                channel.queueDelete(queue);
                channel.exchangeDelete(destination);
            }
        }
    }

    @Configuration
    @EnableAutoConfiguration(
            exclude = {
                DataSourceAutoConfiguration.class,
                HibernateJpaAutoConfiguration.class,
                DataJpaRepositoriesAutoConfiguration.class,
                FlywayAutoConfiguration.class
            },
            excludeName = {
                "org.springframework.modulith.events.jdbc.JdbcEventPublicationAutoConfiguration",
                "org.springframework.modulith.events.config.EventPublicationAutoConfiguration",
                "org.springframework.modulith.events.config.EventExternalizationAutoConfiguration"
            })
    @EnableConfigurationProperties(EventPublicationProperties.class)
    @Import(UserCreatedEventPublisher.class)
    static class TestConfig {}
}
