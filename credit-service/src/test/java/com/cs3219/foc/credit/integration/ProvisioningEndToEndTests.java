package com.cs3219.foc.credit.integration;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;

import com.cs3219.foc.credit.CreditServiceApplication;
import com.cs3219.foc.user.UserServiceApplication;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mail.javamail.JavaMailSender;
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
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityRequest;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.DeleteQueueRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SetQueueAttributesRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@EnabledIfEnvironmentVariable(named = "FOC_TEST_DATABASE_URL", matches = ".+")
@Testcontainers
class ProvisioningEndToEndTests {
    @Container
    static final GenericContainer<?> FLOCI = new GenericContainer<>(DockerImageName.parse("floci/floci:2.3.0-compat"))
            .withExposedPorts(4566)
            .withEnv("AWS_REGION", "ap-southeast-1");

    @TempDir
    Path temporaryDirectory;

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    void provisionsRegisteredUsersAcrossServiceStartupAndDuplicateDelivery() throws Exception {
        try (var app = new Applications(temporaryDirectory)) {
            app.startUser();
            var existing = app.register();
            var admin = app.register();
            app.userSql.update("UPDATE users SET roles = ARRAY['ADMIN']::text[] WHERE id = ?", admin);
            var registered = app.register();
            var stored = app.capture(registered);
            app.startCredit();
            app.awaitPublishedUsers();
            var registeredToken = app.login(registered);
            app.awaitInitialBalance(registeredToken);
            app.awaitInitialBalance(app.login(existing));
            app.awaitInitialBalance(app.login(admin));
            assertThat(app.balance(null, "").statusCode()).isEqualTo(401);

            app.creditSql.update("UPDATE credit_accounts SET total_balance = 9 WHERE user_id = ?", registered);
            app.stopCredit();
            var createdWhileStopped = app.register();
            app.redeliver(stored);
            app.awaitPublishedUsers();
            app.startCredit();
            app.awaitInitialBalance(app.login(createdWhileStopped));
            var response = app.balance(registeredToken, "?userId=" + admin);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(JSON.readTree(response.body()).get("availableBalance").asLong())
                    .isEqualTo(9);
            assertThat(app.creditSql.queryForObject("SELECT count(*) FROM credit_accounts", Long.class))
                    .isEqualTo(4);
        }
    }

    @Test
    void publicationRecoversAfterRestartAndConsumerFailuresParkUntilReplay() throws Exception {
        try (var app = new Applications(temporaryDirectory)) {
            app.startUser("http://127.0.0.1:1");
            var whileBrokerDown = app.register();
            assertThat(app.userSql.queryForObject(
                            "SELECT count(*) FROM event_publication WHERE listener_id = 'user-created' AND completion_date IS NULL",
                            Long.class))
                    .isEqualTo(1);
            app.user.close();
            app.user = null;
            app.startUser();
            app.startCredit();
            app.awaitPublishedUsers();
            app.awaitInitialBalance(app.login(whileBrokerDown));

            app.database.offline();
            var whileDatabaseDown = app.register();
            app.awaitPublishedUsers();
            await().atMost(Duration.ofSeconds(30))
                    .untilAsserted(() -> assertThat(app.dlqDepth()).isEqualTo(1));
            assertThat(app.creditSql.queryForObject(
                            "SELECT count(*) FROM credit_accounts WHERE user_id = ?", Long.class, whileDatabaseDown))
                    .isZero();
            app.database.online();
            app.replayDlq();
            app.awaitInitialBalance(app.login(whileDatabaseDown));
            assertThat(app.creditSql.queryForObject("SELECT count(*) FROM credit_accounts", Long.class))
                    .isEqualTo(2);
        }
    }

    @TestConfiguration
    static class MailConfig {
        @Bean
        JavaMailSender mailSender() {
            return mock(JavaMailSender.class);
        }
    }

    private static final class Applications implements AutoCloseable {
        private final Path root = Path.of(System.getProperty("foc.root"));
        private final String suffix = UUID.randomUUID().toString().replace("-", "");
        private final String userSchema = "users_e2e_" + suffix;
        private final String creditSchema = "credits_e2e_" + suffix;
        private final String databaseUrl = System.getenv("FOC_TEST_DATABASE_URL");
        private final URI endpoint = URI.create("http://" + FLOCI.getHost() + ":" + FLOCI.getMappedPort(4566));
        private final Path privateKey;
        private final Path publicKey;
        private final SnsClient sns;
        private final SqsClient sqs;
        private final String userTopicArn;
        private final String creditTopicArn;
        private final String queueUrl;
        private final String dlqUrl;
        private final TcpSwitch database;
        private final JdbcTemplate userSql;
        private final JdbcTemplate creditSql;
        private ConfigurableApplicationContext user;
        private ConfigurableApplicationContext credit;
        private int userPort;
        private int creditPort;

        Applications(Path directory) throws Exception {
            var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
            sns = SnsClient.builder()
                    .endpointOverride(endpoint)
                    .region(Region.AP_SOUTHEAST_1)
                    .credentialsProvider(credentials)
                    .build();
            sqs = SqsClient.builder()
                    .endpointOverride(endpoint)
                    .region(Region.AP_SOUTHEAST_1)
                    .credentialsProvider(credentials)
                    .build();
            userTopicArn = sns.createTopic(request -> request.name("foc-user-e2e-" + suffix))
                    .topicArn();
            creditTopicArn = sns.createTopic(request -> request.name("foc-credit-e2e-" + suffix))
                    .topicArn();
            dlqUrl = sqs.createQueue(CreateQueueRequest.builder()
                            .queueName("foc-credit-e2e-" + suffix + "-dlq")
                            .build())
                    .queueUrl();
            var dlqArn = arn(dlqUrl);
            queueUrl = sqs.createQueue(CreateQueueRequest.builder()
                            .queueName("foc-credit-e2e-" + suffix)
                            .attributes(Map.of(
                                    QueueAttributeName.VISIBILITY_TIMEOUT,
                                    "5",
                                    QueueAttributeName.REDRIVE_POLICY,
                                    "{\"deadLetterTargetArn\":\"" + dlqArn + "\",\"maxReceiveCount\":\"1\"}"))
                            .build())
                    .queueUrl();
            var queueArn = arn(queueUrl);
            sqs.setQueueAttributes(SetQueueAttributesRequest.builder()
                    .queueUrl(dlqUrl)
                    .attributes(Map.of(
                            QueueAttributeName.REDRIVE_ALLOW_POLICY,
                            "{\"redrivePermission\":\"byQueue\",\"sourceQueueArns\":[\"" + queueArn + "\"]}"))
                    .build());
            allowSns(queueUrl, queueArn, "\"" + userTopicArn + "\"");
            sns.subscribe(SubscribeRequest.builder()
                    .topicArn(userTopicArn)
                    .protocol("sqs")
                    .endpoint(queueArn)
                    .attributes(Map.of(
                            "RawMessageDelivery", "true",
                            "FilterPolicyScope", "MessageAttributes",
                            "FilterPolicy", "{\"eventType\":[\"UserCreatedEvent\"]}"))
                    .build());
            var databaseUri = URI.create(databaseUrl.substring("jdbc:".length()));
            database = new TcpSwitch(databaseUri.getHost(), databaseUri.getPort() < 0 ? 5432 : databaseUri.getPort());
            userSql = migrate("user-service", userSchema);
            creditSql = migrate("credit-service", creditSchema);
            var generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            var keys = generator.generateKeyPair();
            privateKey = directory.resolve("private.pem");
            publicKey = directory.resolve("public.pem");
            pem(privateKey, "PRIVATE KEY", keys.getPrivate().getEncoded());
            pem(publicKey, "PUBLIC KEY", keys.getPublic().getEncoded());
        }

        private String arn(String url) {
            return sqs.getQueueAttributes(GetQueueAttributesRequest.builder()
                            .queueUrl(url)
                            .attributeNames(QueueAttributeName.QUEUE_ARN)
                            .build())
                    .attributes()
                    .get(QueueAttributeName.QUEUE_ARN);
        }

        private void allowSns(String url, String queueArn, String sourceArns) {
            sqs.setQueueAttributes(SetQueueAttributesRequest.builder()
                    .queueUrl(url)
                    .attributes(Map.of(
                            QueueAttributeName.POLICY,
                            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"Service\":\"sns.amazonaws.com\"},\"Action\":\"sqs:SendMessage\",\"Resource\":\""
                                    + queueArn + "\",\"Condition\":{\"ArnEquals\":{\"aws:SourceArn\":[" + sourceArns
                                    + "]}}}]}"))
                    .build());
        }

        private static void pem(Path path, String type, byte[] bytes) throws IOException {
            Files.writeString(
                    path,
                    "-----BEGIN " + type + "-----\n"
                            + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(bytes) + "\n-----END " + type
                            + "-----\n");
        }

        private String schemaUrl(String url, String schema) {
            return url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        }

        private JdbcTemplate migrate(String module, String schema) {
            var source = new DriverManagerDataSource(
                    schemaUrl(databaseUrl, schema),
                    System.getenv("FOC_TEST_DATABASE_USERNAME"),
                    System.getenv("FOC_TEST_DATABASE_PASSWORD"));
            Flyway.configure()
                    .dataSource(source)
                    .schemas(schema)
                    .defaultSchema(schema)
                    .locations("filesystem:" + root.resolve(module + "/src/main/resources/db/migration"))
                    .load()
                    .migrate();
            return new JdbcTemplate(source);
        }

        private ArrayList<String> arguments(String module, String schema, String url) {
            return new ArrayList<>(List.of(
                    "--spring.config.location="
                            + root.resolve(module + "/src/main/resources/application.yaml")
                                    .toUri(),
                    "--spring.main.banner-mode=off",
                    "--logging.level.root=WARN",
                    "--server.port=0",
                    "--spring.datasource.url=" + schemaUrl(url, schema),
                    "--spring.datasource.username=" + System.getenv("FOC_TEST_DATABASE_USERNAME"),
                    "--spring.datasource.password=" + System.getenv().getOrDefault("FOC_TEST_DATABASE_PASSWORD", ""),
                    "--spring.datasource.hikari.connection-timeout=2000",
                    "--spring.datasource.hikari.validation-timeout=1000",
                    "--spring.flyway.default-schema=" + schema,
                    "--spring.flyway.locations=filesystem:" + root.resolve(module + "/src/main/resources/db/migration"),
                    "--spring.cloud.aws.region.static=ap-southeast-1",
                    "--spring.cloud.aws.credentials.access-key=test",
                    "--spring.cloud.aws.credentials.secret-key=test",
                    "--spring.cloud.aws.sqs.endpoint=" + endpoint,
                    "--spring.cloud.aws.s3.enabled=false"));
        }

        void startUser() {
            startUser(endpoint.toString());
        }

        void startUser(String snsEndpoint) {
            var args = arguments("user-service", userSchema, databaseUrl);
            args.addAll(List.of(
                    "--auth.private-key-location=" + privateKey.toUri(),
                    "--auth.public-key-location=" + publicKey.toUri(),
                    "--spring.cloud.aws.sns.endpoint=" + snsEndpoint,
                    "--events.messaging.topic-arn=" + userTopicArn,
                    "--events.publication.retry-delay=100ms",
                    "--spring.modulith.events.staleness.published=1s",
                    "--spring.modulith.events.staleness.processing=1s",
                    "--spring.modulith.events.staleness.resubmitted=1s",
                    "--spring.modulith.events.staleness.check-interval=100ms"));
            user = new SpringApplicationBuilder(UserServiceApplication.class, MailConfig.class)
                    .run(args.toArray(String[]::new));
            userPort = user.getEnvironment().getProperty("local.server.port", Integer.class);
        }

        void startCredit() throws Exception {
            var uri = URI.create(databaseUrl.substring("jdbc:".length()));
            var proxyUrl = "jdbc:"
                    + new URI(
                            uri.getScheme(),
                            uri.getUserInfo(),
                            "127.0.0.1",
                            database.port(),
                            uri.getPath(),
                            uri.getQuery(),
                            uri.getFragment());
            var args = arguments("credit-service", creditSchema, proxyUrl);
            args.addAll(List.of(
                    "--spring.cloud.aws.sns.endpoint=" + endpoint,
                    "--spring.cloud.aws.sqs.listener.poll-timeout=2s",
                    "--spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:" + userPort
                            + "/api/.well-known/jwks.json",
                    "--events.messaging.topic-arn=" + creditTopicArn,
                    "--events.messaging.queue-name=" + "foc-credit-e2e-" + suffix));
            credit = new SpringApplicationBuilder(CreditServiceApplication.class).run(args.toArray(String[]::new));
            creditPort = credit.getEnvironment().getProperty("local.server.port", Integer.class);
        }

        void stopCredit() {
            if (credit != null) {
                credit.close();
                credit = null;
            }
        }

        UUID register() throws Exception {
            var response = post(
                    "/auth/register",
                    "{\"email\":\"" + UUID.randomUUID()
                            + "@u.nus.edu\",\"name\":\"Test\",\"password\":\"password123\"}");
            assertThat(response.statusCode()).isEqualTo(201);
            var body = JSON.readTree(response.body());
            return UUID.fromString(body.get("id").asString());
        }

        String login(UUID id) throws Exception {
            // Email verification has separate tests; balance access uses a verified-user fixture.
            userSql.update("UPDATE users SET email_verified = true WHERE id = ?", id);
            var email = userSql.queryForObject("SELECT email FROM users WHERE id = ?", String.class, id);
            var response = post("/auth/login", "{\"email\":\"" + email + "\",\"password\":\"password123\"}");
            assertThat(response.statusCode()).isEqualTo(200);
            var body = JSON.readTree(response.body());
            return body.get("accessToken").asString();
        }

        private HttpResponse<String> post(String route, String body) throws Exception {
            return HTTP.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + userPort + "/api" + route))
                            .timeout(Duration.ofSeconds(10))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> balance(String token, String query) throws Exception {
            var request = HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + creditPort + "/api/credits/me" + query))
                    .timeout(Duration.ofSeconds(10));
            if (token != null) request.header("Authorization", "Bearer " + token);
            return HTTP.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
        }

        void awaitInitialBalance(String token) {
            await().atMost(Duration.ofSeconds(40)).untilAsserted(() -> {
                var response = balance(token, "");
                assertThat(response.statusCode()).isEqualTo(200);
                JsonNode body = JSON.readTree(response.body());
                assertThat(body.get("availableBalance").asLong()).isEqualTo(15);
                assertThat(body.get("reservedBalance").asLong()).isZero();
            });
        }

        void awaitPublishedUsers() {
            await().atMost(Duration.ofSeconds(40)).untilAsserted(() -> assertThat(userSql.queryForObject(
                            "SELECT count(*) FROM event_publication WHERE listener_id = 'user-created'", Long.class))
                    .isZero());
        }

        String capture(UUID userId) {
            return await().atMost(Duration.ofSeconds(20))
                    .until(
                            () -> {
                                var messages = sqs.receiveMessage(ReceiveMessageRequest.builder()
                                                .queueUrl(queueUrl)
                                                .maxNumberOfMessages(10)
                                                .waitTimeSeconds(1)
                                                .build())
                                        .messages();
                                String stored = null;
                                for (var message : messages) {
                                    if (message.body().contains(userId.toString())) stored = message.body();
                                    sqs.changeMessageVisibility(ChangeMessageVisibilityRequest.builder()
                                            .queueUrl(queueUrl)
                                            .receiptHandle(message.receiptHandle())
                                            .visibilityTimeout(0)
                                            .build());
                                }
                                return stored;
                            },
                            value -> value != null);
        }

        int dlqDepth() {
            return Integer.parseInt(sqs.getQueueAttributes(GetQueueAttributesRequest.builder()
                            .queueUrl(dlqUrl)
                            .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES)
                            .build())
                    .attributes()
                    .getOrDefault(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES, "0"));
        }

        void redeliver(String serialized) {
            sqs.sendMessage(SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(serialized)
                    .messageAttributes(Map.of(
                            "eventType",
                            MessageAttributeValue.builder()
                                    .dataType("String")
                                    .stringValue("UserCreatedEvent")
                                    .build()))
                    .build());
        }

        void replayDlq() {
            var message = sqs.receiveMessage(ReceiveMessageRequest.builder()
                            .queueUrl(dlqUrl)
                            .maxNumberOfMessages(1)
                            .waitTimeSeconds(5)
                            .messageAttributeNames("All")
                            .build())
                    .messages()
                    .getFirst();
            sqs.sendMessage(SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(message.body())
                    .messageAttributes(message.messageAttributes())
                    .build());
            sqs.deleteMessage(DeleteMessageRequest.builder()
                    .queueUrl(dlqUrl)
                    .receiptHandle(message.receiptHandle())
                    .build());
        }

        @Override
        public void close() throws Exception {
            stopCredit();
            if (user != null) user.close();
            database.close();
            sqs.deleteQueue(DeleteQueueRequest.builder().queueUrl(queueUrl).build());
            sqs.deleteQueue(DeleteQueueRequest.builder().queueUrl(dlqUrl).build());
            sns.close();
            sqs.close();
            userSql.execute("DROP SCHEMA IF EXISTS " + userSchema + " CASCADE");
            creditSql.execute("DROP SCHEMA IF EXISTS " + creditSchema + " CASCADE");
        }
    }

    /** Proxies the database so a test can drop credit's connections without stopping Postgres. */
    static final class TcpSwitch implements AutoCloseable {
        private final ServerSocket listener;
        private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
        private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        private final AtomicBoolean available = new AtomicBoolean(true);

        TcpSwitch(String host, int port) throws IOException {
            listener = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            threads.submit(() -> {
                while (!listener.isClosed()) {
                    try {
                        var incoming = listener.accept();
                        if (!available.get()) {
                            incoming.close();
                            continue;
                        }
                        var outgoing = new Socket(host, port);
                        sockets.add(incoming);
                        sockets.add(outgoing);
                        threads.submit(() -> relay(incoming, outgoing));
                        threads.submit(() -> relay(outgoing, incoming));
                    } catch (IOException exception) {
                        if (!listener.isClosed()) throw new UncheckedIOException(exception);
                    }
                }
            });
        }

        private void relay(Socket from, Socket to) {
            try (from;
                    to) {
                from.getInputStream().transferTo(to.getOutputStream());
            } catch (IOException ignored) {
                /* Deliberate connection loss or normal shutdown. */
            } finally {
                sockets.remove(from);
                sockets.remove(to);
            }
        }

        int port() {
            return listener.getLocalPort();
        }

        void offline() {
            available.set(false);
            sockets.forEach(this::closeSocket);
        }

        void online() {
            available.set(true);
        }

        private void closeSocket(Socket socket) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }

        @Override
        public void close() throws IOException {
            listener.close();
            offline();
            threads.shutdownNow();
            threads.close();
        }
    }
}
