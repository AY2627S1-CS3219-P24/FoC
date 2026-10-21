package com.cs3219.foc.credit.integration;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import com.cs3219.foc.credit.CreditServiceApplication;
import com.cs3219.foc.credit.model.dto.ReserveCreditRequest;
import com.cs3219.foc.credit.model.dto.event.ErrandCancelledEvent;
import com.cs3219.foc.credit.model.dto.event.ErrandCompletedEvent;
import com.cs3219.foc.credit.model.dto.event.ErrandExpiredEvent;
import com.cs3219.foc.credit.service.*;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.SubscribeRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.DeleteQueueRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SetQueueAttributesRequest;
import tools.jackson.databind.json.JsonMapper;

@EnabledIfEnvironmentVariable(named = "FOC_TEST_DATABASE_URL", matches = ".+")
@Testcontainers
class SettlementMessagingTests {
    @Container
    static final GenericContainer<?> FLOCI = new GenericContainer<>(DockerImageName.parse("floci/floci:2.3.0-compat"))
            .withExposedPorts(4566)
            .withEnv("AWS_REGION", "ap-southeast-1");

    @Test
    void directSettlementRetriesParkInDlqAndRegistryRecoversAfterRestart() throws Exception {
        var suffix = UUID.randomUUID().toString().replace("-", "");
        var schema = "settlement_broker_" + suffix;
        var creditQueueName = "foc-credit-settle-" + suffix;
        var url = System.getenv("FOC_TEST_DATABASE_URL");
        var sql = new JdbcTemplate(new DriverManagerDataSource(
                url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                System.getenv("FOC_TEST_DATABASE_USERNAME"),
                System.getenv("FOC_TEST_DATABASE_PASSWORD")));
        var endpoint = URI.create("http://" + FLOCI.getHost() + ":" + FLOCI.getMappedPort(4566));
        var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
        var root = Path.of(System.getProperty("foc.root"));
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
            var orderTopic = sns.createTopic(request -> request.name("foc-order-settle-" + suffix))
                    .topicArn();
            var creditTopic = sns.createTopic(request -> request.name("foc-credit-settle-" + suffix))
                    .topicArn();
            var creditDlqUrl = sqs.createQueue(CreateQueueRequest.builder()
                            .queueName(creditQueueName + "-dlq")
                            .build())
                    .queueUrl();
            var creditDlqArn = queueArn(sqs, creditDlqUrl);
            var creditQueueUrl = sqs.createQueue(CreateQueueRequest.builder()
                            .queueName(creditQueueName)
                            .attributes(Map.of(
                                    QueueAttributeName.VISIBILITY_TIMEOUT,
                                    "2",
                                    QueueAttributeName.REDRIVE_POLICY,
                                    "{\"deadLetterTargetArn\":\"" + creditDlqArn + "\",\"maxReceiveCount\":\"1\"}"))
                            .build())
                    .queueUrl();
            var creditQueueArn = queueArn(sqs, creditQueueUrl);
            var orderQueueUrl = sqs.createQueue(CreateQueueRequest.builder()
                            .queueName("foc-order-settle-" + suffix)
                            .build())
                    .queueUrl();
            var orderQueueArn = queueArn(sqs, orderQueueUrl);
            allow(sqs, creditQueueUrl, creditQueueArn, "\"" + orderTopic + "\"");
            allow(sqs, orderQueueUrl, orderQueueArn, "\"" + creditTopic + "\"");
            subscribe(
                    sns,
                    orderTopic,
                    creditQueueArn,
                    "{\"eventType\":[\"ErrandCancelledEvent\",\"ErrandExpiredEvent\",\"ErrandCompletedEvent\"]}");
            subscribe(sns, creditTopic, orderQueueArn, "{\"eventType\":[\"CreditSettlementResultEvent\"]}");
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
                    "--spring.cloud.aws.region.static=ap-southeast-1",
                    "--spring.cloud.aws.credentials.access-key=test",
                    "--spring.cloud.aws.credentials.secret-key=test",
                    "--spring.cloud.aws.sns.endpoint=" + endpoint,
                    "--spring.cloud.aws.sqs.endpoint=" + endpoint,
                    "--spring.cloud.aws.sqs.listener.poll-timeout=2s",
                    "--events.messaging.topic-arn=" + creditTopic,
                    "--events.messaging.queue-name=" + creditQueueName,
                    "--events.publication.retry-delay=100ms",
                    "--spring.modulith.events.staleness.published=0s",
                    "--spring.modulith.events.staleness.processing=0s",
                    "--spring.modulith.events.staleness.resubmitted=0s"));
            var owner = UUID.randomUUID();
            var courier = UUID.randomUUID();
            UUID recoveryId;
            ErrandCancelledEvent failedRelease;
            try {
                try (var context = start(args)) {
                    var creditAccountService = context.getBean(CreditAccountService.class);
                    creditAccountService.initializeAccount(owner);
                    sql.update("UPDATE credit_accounts SET total_balance=100 WHERE user_id=?", owner);
                    var reserve = context.getBean(CreditReservationService.class);
                    var a = cancelled(reserve, owner, 20);
                    var b = new ErrandExpiredEvent(
                            UUID.randomUUID(),
                            1,
                            OffsetDateTime.now(),
                            new ErrandExpiredEvent.Payload(reserved(reserve, owner, 20), owner, 20));
                    var c = new ErrandCompletedEvent(
                            UUID.randomUUID(),
                            1,
                            OffsetDateTime.now(),
                            new ErrandCompletedEvent.Payload(reserved(reserve, owner, 40), owner, 40, courier));
                    var invalidAmounts = List.of("1.9", "1.0", "1e2", "9223372036854775808", "-9223372036854775809");
                    var body = JsonMapper.builder().build().writeValueAsString(a);
                    for (var amount : invalidAmounts) {
                        publishBody(
                                sns,
                                orderTopic,
                                ErrandCancelledEvent.TYPE,
                                body.replace("\"amount\":20", "\"amount\":" + amount));
                    }
                    await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(depth(sqs, creditDlqUrl))
                            .isEqualTo(invalidAmounts.size()));
                    assertThat(creditAccountService.getBalance(owner).reservedBalance())
                            .isEqualTo(80);
                    assertThat(sql.queryForObject("SELECT count(*) FROM credit_transaction_log", Long.class))
                            .isEqualTo(3);
                    publish(sns, orderTopic, ErrandCancelledEvent.TYPE, a);
                    publish(sns, orderTopic, ErrandExpiredEvent.TYPE, b);
                    publish(sns, orderTopic, ErrandCompletedEvent.TYPE, c);
                    await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                        assertThat(sql.queryForObject(
                                        "SELECT count(*) FROM credit_reservations WHERE state='REFUNDED'", Long.class))
                                .isEqualTo(2);
                        assertThat(depth(sqs, creditDlqUrl)).isEqualTo(invalidAmounts.size() + 1);
                    });
                    assertThat(creditAccountService.getBalance(owner).reservedBalance())
                            .isEqualTo(40);
                    assertThat(sql.queryForObject("SELECT count(*) FROM credit_transaction_log", Long.class))
                            .isEqualTo(5);
                    creditAccountService.initializeAccount(courier);
                    var parked = sqs.receiveMessage(ReceiveMessageRequest.builder()
                                    .queueUrl(creditDlqUrl)
                                    .maxNumberOfMessages(10)
                                    .waitTimeSeconds(5)
                                    .messageAttributeNames("All")
                                    .build())
                            .messages();
                    var completed = parked.stream()
                            .filter(message -> ErrandCompletedEvent.TYPE.equals(
                                    message.messageAttributes().get("eventType").stringValue()))
                            .findFirst()
                            .orElseThrow();
                    sqs.sendMessage(SendMessageRequest.builder()
                            .queueUrl(creditQueueUrl)
                            .messageBody(completed.body())
                            .messageAttributes(completed.messageAttributes())
                            .build());
                    for (var message : parked) {
                        sqs.deleteMessage(DeleteMessageRequest.builder()
                                .queueUrl(creditDlqUrl)
                                .receiptHandle(message.receiptHandle())
                                .build());
                    }
                    await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(
                                    creditAccountService.getBalance(courier).availableBalance())
                            .isEqualTo(55));
                    publish(sns, orderTopic, ErrandCompletedEvent.TYPE, c);
                    await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(sql.queryForObject(
                                    "SELECT count(*) FROM event_publication WHERE completion_date IS NULL", Long.class))
                            .isZero());
                    assertThat(sql.queryForObject("SELECT count(*) FROM credit_transaction_log", Long.class))
                            .isEqualTo(6);
                    var d = cancelled(reserve, owner, 10);
                    var service = context.getBean(CreditSettlementService.class);
                    sql.execute("ALTER TABLE event_publication ADD CONSTRAINT fail_publication CHECK(FALSE) NOT VALID");
                    try {
                        assertThatThrownBy(() -> service.releaseReservedCredits(d))
                                .isInstanceOf(RuntimeException.class);
                        assertThat(creditAccountService.getBalance(owner).reservedBalance())
                                .isEqualTo(10);
                        assertThat(sql.queryForObject(
                                        "SELECT count(*) FROM credit_transaction_log WHERE operation_id=?",
                                        Long.class,
                                        d.eventId()))
                                .isZero();
                    } finally {
                        sql.execute("ALTER TABLE event_publication DROP CONSTRAINT fail_publication");
                    }
                    failedRelease = d;
                }
                var offline = new ArrayList<>(args);
                offline.removeIf(arg -> arg.startsWith("--spring.cloud.aws.sns.endpoint="));
                offline.add("--spring.cloud.aws.sns.endpoint=http://127.0.0.1:1");
                try (var context = start(offline)) {
                    context.getBean(CreditSettlementService.class).releaseReservedCredits(failedRelease);
                    recoveryId = UUID.fromString(sql.queryForObject(
                            "SELECT serialized_event::jsonb->>'eventId' FROM event_publication WHERE serialized_event::jsonb->'payload'->>'operationId'=?",
                            String.class,
                            failedRelease.eventId().toString()));
                    await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(sql.queryForObject(
                                    "SELECT count(*) FROM event_publication WHERE serialized_event::jsonb->>'eventId'=? AND completion_date IS NULL",
                                    Long.class,
                                    recoveryId.toString()))
                            .isEqualTo(1));
                    assertThat(context.getBean(CreditAccountService.class)
                                    .getBalance(owner)
                                    .reservedBalance())
                            .isZero();
                }
                try (var context = start(args)) {
                    await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(sql.queryForObject(
                                    "SELECT count(*) FROM event_publication WHERE completion_date IS NULL", Long.class))
                            .isZero());
                    assertThat(sql.queryForObject("SELECT count(*) FROM credit_transaction_log", Long.class))
                            .isEqualTo(8);
                    assertThat(context.getBean(CreditAccountService.class)
                                    .getBalance(owner)
                                    .availableBalance())
                            .isEqualTo(60);
                    var json = JsonMapper.builder().build();
                    var recovered = false;
                    for (var attempt = 0; attempt < 10 && !recovered; attempt++) {
                        for (var message : sqs.receiveMessage(ReceiveMessageRequest.builder()
                                        .queueUrl(orderQueueUrl)
                                        .maxNumberOfMessages(10)
                                        .waitTimeSeconds(2)
                                        .build())
                                .messages()) {
                            var result = json.readTree(message.body());
                            var eventId = result.get("eventId").asString();
                            if (eventId.equals(recoveryId.toString())) {
                                recovered = true;
                                var outcome =
                                        result.get("payload").get("outcome").asString();
                                assertThat(outcome).isEqualTo("REFUNDED");
                            }
                        }
                    }
                    assertThat(recovered).isTrue();
                }
            } finally {
                sqs.deleteQueue(
                        DeleteQueueRequest.builder().queueUrl(creditQueueUrl).build());
                sqs.deleteQueue(
                        DeleteQueueRequest.builder().queueUrl(creditDlqUrl).build());
                sqs.deleteQueue(
                        DeleteQueueRequest.builder().queueUrl(orderQueueUrl).build());
                sql.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    private String queueArn(SqsClient sqs, String queueUrl) {
        return sqs.getQueueAttributes(GetQueueAttributesRequest.builder()
                        .queueUrl(queueUrl)
                        .attributeNames(QueueAttributeName.QUEUE_ARN)
                        .build())
                .attributes()
                .get(QueueAttributeName.QUEUE_ARN);
    }

    private void allow(SqsClient sqs, String queueUrl, String queueArn, String sourceArns) {
        sqs.setQueueAttributes(SetQueueAttributesRequest.builder()
                .queueUrl(queueUrl)
                .attributes(Map.of(
                        QueueAttributeName.POLICY,
                        "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"Service\":\"sns.amazonaws.com\"},\"Action\":\"sqs:SendMessage\",\"Resource\":\""
                                + queueArn + "\",\"Condition\":{\"ArnEquals\":{\"aws:SourceArn\":[" + sourceArns
                                + "]}}}]}"))
                .build());
    }

    private void subscribe(SnsClient sns, String topicArn, String queueArn, String filter) {
        sns.subscribe(SubscribeRequest.builder()
                .topicArn(topicArn)
                .protocol("sqs")
                .endpoint(queueArn)
                .attributes(Map.of(
                        "RawMessageDelivery", "true", "FilterPolicyScope", "MessageAttributes", "FilterPolicy", filter))
                .build());
    }

    private int depth(SqsClient sqs, String queueUrl) {
        return Integer.parseInt(sqs.getQueueAttributes(GetQueueAttributesRequest.builder()
                        .queueUrl(queueUrl)
                        .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES)
                        .build())
                .attributes()
                .getOrDefault(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES, "0"));
    }

    private UUID reserved(CreditReservationService service, UUID owner, long amount) {
        var request = new ReserveCreditRequest(UUID.randomUUID(), UUID.randomUUID(), amount);
        assertThat(service.reserve(owner, request).outcome()).isEqualTo("RESERVED");
        return request.errandId();
    }

    private ErrandCancelledEvent cancelled(CreditReservationService service, UUID owner, long amount) {
        return new ErrandCancelledEvent(
                UUID.randomUUID(),
                1,
                OffsetDateTime.now(),
                new ErrandCancelledEvent.Payload(reserved(service, owner, amount), owner, amount));
    }

    private void publish(SnsClient sns, String topicArn, String eventType, Object event) throws Exception {
        publishBody(sns, topicArn, eventType, JsonMapper.builder().build().writeValueAsString(event));
    }

    private void publishBody(SnsClient sns, String topicArn, String eventType, String body) {
        sns.publish(PublishRequest.builder()
                .topicArn(topicArn)
                .message(body)
                .messageAttributes(Map.of(
                        "eventType",
                        MessageAttributeValue.builder()
                                .dataType("String")
                                .stringValue(eventType)
                                .build()))
                .build());
    }

    private ConfigurableApplicationContext start(List<String> args) {
        return new SpringApplicationBuilder(CreditServiceApplication.class).run(args.toArray(String[]::new));
    }
}
