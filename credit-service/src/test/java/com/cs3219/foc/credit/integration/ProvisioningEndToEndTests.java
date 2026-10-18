package com.cs3219.foc.credit.integration;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;

import com.cs3219.foc.credit.CreditServiceApplication;
import com.cs3219.foc.user.UserServiceApplication;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.ConnectionFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@EnabledIfEnvironmentVariable(named = "FOC_TEST_DATABASE_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "FOC_TEST_RABBIT_HOST", matches = ".+")
class ProvisioningEndToEndTests {
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
            app.startCredit();
            app.awaitPublishedUsers(3);
            var registeredToken = app.login(registered);
            app.awaitZeroBalance(registeredToken);
            app.awaitZeroBalance(app.login(existing));
            app.awaitZeroBalance(app.login(admin));
            assertThat(app.balance(null, "").statusCode()).isEqualTo(401);

            app.creditSql.update("UPDATE credit_accounts SET total_balance = 9 WHERE user_id = ?", registered);
            app.stopCredit();
            var createdWhileStopped = app.register();
            var stored = app.userSql.queryForObject(
                    "SELECT serialized_event FROM event_publication WHERE serialized_event::jsonb->'payload'->>'userId' = ?",
                    String.class,
                    registered.toString());
            app.redeliver(stored);
            app.awaitPublishedUsers(4);
            app.startCredit();
            app.awaitZeroBalance(app.login(createdWhileStopped));
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
            app.broker.offline();
            app.startUser();
            var whileBrokerDown = app.register();
            assertThat(app.userSql.queryForObject(
                            "SELECT count(*) FROM event_publication WHERE listener_id = 'user-created-rabbit' AND completion_date IS NULL",
                            Long.class))
                    .isEqualTo(1);
            app.user.close();
            app.user = null;
            app.broker.online();
            app.startUser();
            app.startCredit();
            app.awaitPublishedUsers(1);
            app.awaitZeroBalance(app.login(whileBrokerDown));

            app.database.offline();
            var whileDatabaseDown = app.register();
            app.awaitPublishedUsers(2);
            await().atMost(Duration.ofSeconds(30))
                    .untilAsserted(() -> assertThat(app.dlqDepth()).isEqualTo(1));
            assertThat(app.creditSql.queryForObject(
                            "SELECT count(*) FROM credit_accounts WHERE user_id = ?", Long.class, whileDatabaseDown))
                    .isZero();
            app.database.online();
            app.replayDlq();
            app.awaitZeroBalance(app.login(whileDatabaseDown));
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
        private final String destination = "foc.credit.e2e." + suffix;
        private final String queue = destination + ".credit-provisioning";
        private final String databaseUrl = System.getenv("FOC_TEST_DATABASE_URL");
        private final Path privateKey;
        private final Path publicKey;
        private final ConnectionFactory rabbitFactory = new ConnectionFactory();
        private final TcpSwitch broker;
        private final TcpSwitch database;
        private final JdbcTemplate userSql;
        private final JdbcTemplate creditSql;
        private ConfigurableApplicationContext user;
        private ConfigurableApplicationContext credit;
        private int userPort;
        private int creditPort;

        Applications(Path directory) throws Exception {
            var rabbitHost = System.getenv("FOC_TEST_RABBIT_HOST");
            int rabbitPort = Integer.parseInt(System.getenv().getOrDefault("FOC_TEST_RABBIT_PORT", "5672"));
            rabbitFactory.setHost(rabbitHost);
            rabbitFactory.setPort(rabbitPort);
            rabbitFactory.setUsername(System.getenv("FOC_TEST_RABBIT_USERNAME"));
            rabbitFactory.setPassword(System.getenv("FOC_TEST_RABBIT_PASSWORD"));
            broker = new TcpSwitch(rabbitHost, rabbitPort);
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
                    "--spring.rabbitmq.host=127.0.0.1",
                    "--spring.rabbitmq.port=" + broker.port(),
                    "--spring.rabbitmq.username=" + System.getenv("FOC_TEST_RABBIT_USERNAME"),
                    "--spring.rabbitmq.password=" + System.getenv("FOC_TEST_RABBIT_PASSWORD"),
                    "--spring.rabbitmq.connection-timeout=1s",
                    "--spring.cloud.stream.binding-retry-interval=1"));
        }

        void startUser() {
            var args = arguments("user-service", userSchema, databaseUrl);
            args.addAll(List.of(
                    "--auth.private-key-location=" + privateKey.toUri(),
                    "--auth.public-key-location=" + publicKey.toUri(),
                    "--avatar.directory=" + privateKey.getParent().resolve("avatars"),
                    "--events.publication.confirm-timeout=1s",
                    "--events.publication.retry-delay=100ms",
                    "--spring.modulith.events.staleness.published=1s",
                    "--spring.modulith.events.staleness.processing=1s",
                    "--spring.modulith.events.staleness.resubmitted=1s",
                    "--spring.modulith.events.staleness.check-interval=100ms",
                    "--spring.cloud.stream.bindings.userCreated-out-0.destination=" + destination));
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
                    "--spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:" + userPort
                            + "/api/.well-known/jwks.json",
                    "--spring.cloud.stream.bindings.userCreated-in-0.destination=" + destination,
                    "--spring.cloud.stream.bindings.userCreated-in-0.consumer.back-off-initial-interval=100",
                    "--spring.cloud.stream.bindings.userCreated-in-0.consumer.back-off-max-interval=200"));
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
            return UUID.fromString(JSON.readTree(response.body()).get("id").asText());
        }

        String login(UUID id) throws Exception {
            // Email verification has separate tests; balance access uses a verified-user fixture.
            userSql.update("UPDATE users SET email_verified = true WHERE id = ?", id);
            var email = userSql.queryForObject("SELECT email FROM users WHERE id = ?", String.class, id);
            var response = post("/auth/login", "{\"email\":\"" + email + "\",\"password\":\"password123\"}");
            assertThat(response.statusCode()).isEqualTo(200);
            return JSON.readTree(response.body()).get("accessToken").asText();
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

        void awaitZeroBalance(String token) {
            await().atMost(Duration.ofSeconds(40)).untilAsserted(() -> {
                var response = balance(token, "");
                assertThat(response.statusCode()).isEqualTo(200);
                JsonNode body = JSON.readTree(response.body());
                assertThat(body.get("availableBalance").asLong()).isZero();
                assertThat(body.get("reservedBalance").asLong()).isZero();
            });
        }

        void awaitPublishedUsers(int expected) {
            await().atMost(Duration.ofSeconds(40)).untilAsserted(() -> assertThat(userSql.queryForObject(
                            "SELECT count(*) FROM event_publication WHERE listener_id = 'user-created-rabbit' AND completion_date IS NOT NULL",
                            Long.class))
                    .isEqualTo(expected));
        }

        int dlqDepth() throws Exception {
            return depth(queue + ".dlq");
        }

        void redeliver(String serialized) throws Exception {
            try (var connection = rabbitFactory.newConnection();
                    var channel = connection.createChannel()) {
                channel.confirmSelect();
                channel.basicPublish(
                        destination,
                        "",
                        new AMQP.BasicProperties.Builder()
                                .contentType("application/json")
                                .deliveryMode(2)
                                .build(),
                        serialized.getBytes(StandardCharsets.UTF_8));
                channel.waitForConfirmsOrDie(5000);
            }
        }

        void replayDlq() throws Exception {
            try (var connection = rabbitFactory.newConnection();
                    var channel = connection.createChannel()) {
                var message = channel.basicGet(queue + ".dlq", false);
                assertThat(message).isNotNull();
                channel.confirmSelect();
                channel.basicPublish(destination, "", message.getProps(), message.getBody());
                channel.waitForConfirmsOrDie(5000);
                channel.basicAck(message.getEnvelope().getDeliveryTag(), false);
            }
        }

        private int depth(String name) throws Exception {
            try (var connection = rabbitFactory.newConnection();
                    var channel = connection.createChannel()) {
                return channel.queueDeclarePassive(name).getMessageCount();
            }
        }

        @Override
        public void close() throws Exception {
            stopCredit();
            if (user != null) user.close();
            broker.close();
            database.close();
            try (var connection = rabbitFactory.newConnection();
                    var channel = connection.createChannel()) {
                channel.queueDelete(queue);
                channel.queueDelete(queue + ".dlq");
                channel.exchangeDelete(destination);
                channel.exchangeDelete(destination + ".dlx");
            } finally {
                userSql.execute("DROP SCHEMA IF EXISTS " + userSchema + " CASCADE");
                creditSql.execute("DROP SCHEMA IF EXISTS " + creditSchema + " CASCADE");
            }
        }
    }

    /** Drops real TCP connections to exercise recovery without controlling the shared CI broker/database. */
    private static final class TcpSwitch implements AutoCloseable {
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
