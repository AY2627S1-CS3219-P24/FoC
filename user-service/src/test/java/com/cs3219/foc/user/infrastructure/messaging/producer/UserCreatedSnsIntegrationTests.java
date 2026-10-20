package com.cs3219.foc.user.infrastructure.messaging.producer;

import static org.assertj.core.api.Assertions.*;

import com.cs3219.foc.user.config.EventMessagingProperties;
import com.cs3219.foc.user.model.dto.event.UserCreatedEvent;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.SubscribeRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SetQueueAttributesRequest;
import tools.jackson.databind.json.JsonMapper;

/** Publishes through the production SNS client to Floci, without starting a consumer. */
@Testcontainers
class UserCreatedSnsIntegrationTests {
    @Container
    static final GenericContainer<?> FLOCI = new GenericContainer<>(DockerImageName.parse("floci/floci:2.3.0-compat"))
            .withExposedPorts(4566)
            .withEnv("AWS_REGION", "ap-southeast-1");

    @Test
    void publishesTheEventContractToTheSubscribedQueue() throws Exception {
        var suffix = UUID.randomUUID().toString().replace("-", "");
        var endpoint = URI.create("http://" + FLOCI.getHost() + ":" + FLOCI.getMappedPort(4566));
        var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
        try (var sns = SnsClient.builder()
                        .endpointOverride(endpoint)
                        .region(Region.AP_SOUTHEAST_1)
                        .credentialsProvider(credentials)
                        .build();
                var sqs = SqsClient.builder()
                        .endpointOverride(endpoint)
                        .region(Region.AP_SOUTHEAST_1)
                        .credentialsProvider(credentials)
                        .build()) {
            var topicArn = sns.createTopic(request -> request.name("foc-user-it-" + suffix))
                    .topicArn();
            var queueName = "foc-user-it-" + suffix;
            var queueUrl = sqs.createQueue(
                            CreateQueueRequest.builder().queueName(queueName).build())
                    .queueUrl();
            var queueArn = sqs.getQueueAttributes(GetQueueAttributesRequest.builder()
                            .queueUrl(queueUrl)
                            .attributeNames(QueueAttributeName.QUEUE_ARN)
                            .build())
                    .attributes()
                    .get(QueueAttributeName.QUEUE_ARN);
            sqs.setQueueAttributes(SetQueueAttributesRequest.builder()
                    .queueUrl(queueUrl)
                    .attributes(Map.of(
                            QueueAttributeName.POLICY,
                            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"Service\":\"sns.amazonaws.com\"},\"Action\":\"sqs:SendMessage\",\"Resource\":\""
                                    + queueArn + "\",\"Condition\":{\"ArnEquals\":{\"aws:SourceArn\":\"" + topicArn
                                    + "\"}}}]}"))
                    .build());
            sns.subscribe(SubscribeRequest.builder()
                    .topicArn(topicArn)
                    .protocol("sqs")
                    .endpoint(queueArn)
                    .attributes(Map.of(
                            "RawMessageDelivery", "true",
                            "FilterPolicyScope", "MessageAttributes",
                            "FilterPolicy", "{\"eventType\":[\"UserCreatedEvent\"]}"))
                    .build());
            var event = UserCreatedEvent.of(UUID.randomUUID(), UUID.randomUUID(), OffsetDateTime.now());
            try (var context = startPublisher(endpoint, topicArn)) {
                context.getBean(SnsUserCreatedEventPublisher.class).publish(event);
            }
            var received = sqs.receiveMessage(ReceiveMessageRequest.builder()
                            .queueUrl(queueUrl)
                            .maxNumberOfMessages(1)
                            .waitTimeSeconds(10)
                            .messageAttributeNames("All")
                            .build())
                    .messages()
                    .getFirst();
            assertThat(received.messageAttributes().get("eventType").stringValue())
                    .isEqualTo(UserCreatedEvent.TYPE);
            assertThat(received.messageAttributes().get("eventId").stringValue())
                    .isEqualTo(event.eventId().toString());
            var body = JsonMapper.builder().build().readTree(received.body());
            var eventId = event.eventId().toString();
            var userId = event.payload().userId().toString();
            assertThat(body.get("eventId").asString()).isEqualTo(eventId);
            assertThat(body.has("eventType")).isFalse();
            assertThat(body.get("schemaVersion").asInt()).isEqualTo(1);
            assertThat(body.get("payload").get("userId").asString()).isEqualTo(userId);
            assertThat(body.get("payload").size()).isEqualTo(1);

            try (var context = startPublisher(endpoint, topicArn)) {
                context.getBean(SnsUserCreatedEventPublisher.class).publish(event);
            }
            var again = sqs.receiveMessage(ReceiveMessageRequest.builder()
                            .queueUrl(queueUrl)
                            .maxNumberOfMessages(1)
                            .waitTimeSeconds(10)
                            .build())
                    .messages()
                    .getFirst();
            var againBody = JsonMapper.builder().build().readTree(again.body());
            assertThat(againBody.get("eventId").asString())
                    .isEqualTo(event.eventId().toString());
        }
    }

    private ConfigurableApplicationContext startPublisher(URI endpoint, String topicArn) {
        return new SpringApplicationBuilder(TestConfig.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--spring.main.banner-mode=off",
                        "--logging.level.root=WARN",
                        "--spring.cloud.aws.region.static=ap-southeast-1",
                        "--spring.cloud.aws.credentials.access-key=test",
                        "--spring.cloud.aws.credentials.secret-key=test",
                        "--spring.cloud.aws.sns.endpoint=" + endpoint,
                        "--spring.cloud.aws.sqs.endpoint=" + endpoint,
                        "--spring.cloud.aws.s3.enabled=false",
                        "--events.messaging.topic-arn=" + topicArn);
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
    @EnableConfigurationProperties(EventMessagingProperties.class)
    @Import(SnsUserCreatedEventPublisher.class)
    static class TestConfig {}
}
