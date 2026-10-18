package com.cs3219.foc.order.integration;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import com.cs3219.foc.credit.CreditServiceApplication;
import com.cs3219.foc.order.OrderServiceApplication;
import com.rabbitmq.client.ConnectionFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import tools.jackson.databind.json.JsonMapper;

@EnabledIfEnvironmentVariable(named = "FOC_TEST_DATABASE_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "FOC_TEST_RABBIT_HOST", matches = ".+")
class ErrandFundingIntegrationTests {
    @Test
    void createsThroughHttpBeforeCreditStartsThenOpensOrRejectsThroughBroker() throws Exception {
        try (var apps = new Apps()) {
            apps.startOrder();
            var response = apps.create(apps.owner, "funded");
            assertThat(response.statusCode()).isEqualTo(202);
            var id = apps.id(response);
            assertThat(apps.state(apps.owner, id)).isEqualTo("PENDING_CREDIT");
            assertThat(apps.id(apps.create(apps.owner, "funded"))).isEqualTo(id);
            apps.awaitCount(apps.orderSql, "errand_submission_outbox WHERE published_at IS NOT NULL", 1);
            // Order's producer provisioned the durable Credit queue before Credit ever started.
            assertThat(apps.depth(apps.input + ".credit-reservations")).isEqualTo(1);
            apps.stopOrder();
            apps.startCredit();
            apps.awaitCount(apps.creditSql, "credit_result_outbox WHERE published_at IS NOT NULL", 1);
            assertThat(apps.depth(apps.output + ".order-credit-results")).isEqualTo(1);
            apps.startOrder();
            apps.awaitState(apps.owner, id, "OPEN");
            var retry = apps.create(apps.owner, "funded");
            assertThat(retry.statusCode()).isEqualTo(200);
            assertThat(apps.id(retry)).isEqualTo(id);
            var rejected = apps.create(apps.unfunded, "unfunded");
            assertThat(rejected.statusCode()).isEqualTo(202);
            apps.awaitState(apps.unfunded, apps.id(rejected), "CREDIT_REJECTED");
            assertThat(apps.creditSql.queryForObject(
                            "SELECT total_balance - (SELECT COALESCE(SUM(amount), 0) FROM credit_reservations r WHERE r.user_id = a.user_id AND r.state = 'RESERVED') FROM credit_accounts a WHERE user_id = ?",
                            Long.class,
                            apps.owner))
                    .isEqualTo(20);
            assertThat(apps.creditSql.queryForObject(
                            "SELECT COALESCE(SUM(amount), 0) FROM credit_reservations WHERE user_id = ? AND state = 'RESERVED'",
                            Long.class,
                            apps.owner))
                    .isEqualTo(80);
            assertThat(apps.creditSql.queryForObject("SELECT count(*) FROM credit_reservations", Long.class))
                    .isEqualTo(1);
            assertThat(apps.orderSql.queryForObject("SELECT count(*) FROM errands", Long.class))
                    .isEqualTo(2);
            assertThat(apps.creditSql.queryForObject("SELECT count(*) FROM credit_reserve_operations", Long.class))
                    .isEqualTo(2);
            // Lost result confirm: the original result is published again, not a new operation.
            apps.creditSql.update(
                    "UPDATE credit_result_outbox SET published_at = NULL, next_attempt_at = CURRENT_TIMESTAMP WHERE outcome = 'RESERVED'");
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(apps.creditSql.queryForObject(
                            "SELECT attempt_count FROM credit_result_outbox WHERE outcome = 'RESERVED'", Integer.class))
                    .isGreaterThanOrEqualTo(2));
            apps.awaitState(apps.owner, id, "OPEN");
            assertThat(apps.orderSql.queryForObject(
                            "SELECT count(*) FROM credit_reservation_result_inbox WHERE outcome = 'RESERVED'",
                            Long.class))
                    .isEqualTo(1);
        }
    }

    @Test
    void brokerOutageKeepsHttpCreationPendingAndRestartResumesWithoutUserRetry() throws Exception {
        try (var apps = new Apps()) {
            apps.startOrder();
            apps.startCredit();
            apps.broker.offline();
            var response = apps.create(apps.owner, "outage");
            assertThat(response.statusCode()).isEqualTo(202);
            var id = apps.id(response);
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(apps.orderSql.queryForObject(
                            "SELECT count(*) FROM errand_submission_outbox WHERE attempt_count > 0 AND published_at IS NULL",
                            Long.class))
                    .isEqualTo(1));
            assertThat(apps.state(apps.owner, id)).isEqualTo("PENDING_CREDIT");
            assertThat(apps.creditSql.queryForObject(
                            "SELECT COALESCE(SUM(amount), 0) FROM credit_reservations WHERE user_id = ? AND state = 'RESERVED'",
                            Long.class,
                            apps.owner))
                    .isZero();
            apps.stopOrder();
            apps.broker.online();
            apps.startOrder();
            apps.awaitState(apps.owner, id, "OPEN");
            assertThat(apps.orderSql.queryForObject("SELECT count(*) FROM errands", Long.class))
                    .isEqualTo(1);
            assertThat(apps.creditSql.queryForObject("SELECT count(*) FROM credit_reservations", Long.class))
                    .isEqualTo(1);
            assertThat(apps.creditSql.queryForObject(
                            "SELECT COALESCE(SUM(amount), 0) FROM credit_reservations WHERE user_id = ? AND state = 'RESERVED'",
                            Long.class,
                            apps.owner))
                    .isEqualTo(80);
        }
    }

    private static final class Apps implements AutoCloseable {
        final String suffix = UUID.randomUUID().toString().replace("-", "");
        final String orderSchema = "order_e2e_" + suffix;
        final String creditSchema = "order_credit_e2e_" + suffix;
        final String input = "foc.submission.test." + suffix;
        final String output = "foc.reservation.test." + suffix;
        final UUID owner = UUID.randomUUID();
        final UUID unfunded = UUID.randomUUID();
        final Path root = Path.of(System.getProperty("foc.root"));
        final String databaseUrl = System.getenv("FOC_TEST_DATABASE_URL");
        final ConnectionFactory rabbit = new ConnectionFactory();
        final ThreadPoolExecutor rabbitExecutor = (ThreadPoolExecutor) Executors.newCachedThreadPool();
        final HttpClient http = HttpClient.newHttpClient();
        final JsonMapper json = JsonMapper.builder().build();
        final JdbcTemplate orderSql;
        final JdbcTemplate creditSql;
        final TcpSwitch broker;
        ConfigurableApplicationContext order;
        ConfigurableApplicationContext credit;

        Apps() throws Exception {
            rabbit.setHost(System.getenv("FOC_TEST_RABBIT_HOST"));
            rabbit.setPort(Integer.parseInt(System.getenv().getOrDefault("FOC_TEST_RABBIT_PORT", "5672")));
            rabbit.setUsername(System.getenv("FOC_TEST_RABBIT_USERNAME"));
            rabbit.setPassword(System.getenv("FOC_TEST_RABBIT_PASSWORD"));
            broker = new TcpSwitch(rabbit.getHost(), rabbit.getPort());
            orderSql = sql(orderSchema);
            creditSql = sql(creditSchema);
            // Credit account fixtures exist before its first process starts; production starts at zero.
            Flyway.configure()
                    .dataSource(creditSql.getDataSource())
                    .schemas(creditSchema)
                    .defaultSchema(creditSchema)
                    .locations("filesystem:" + root.resolve("credit-service/src/main/resources/db/migration"))
                    .load()
                    .migrate();
            creditSql.update(
                    "INSERT INTO credit_accounts(user_id,total_balance) VALUES (?,100),(?,0)", owner, unfunded);
        }

        JdbcTemplate sql(String schema) {
            return new JdbcTemplate(new DriverManagerDataSource(
                    databaseUrl + (databaseUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                    System.getenv("FOC_TEST_DATABASE_USERNAME"),
                    System.getenv("FOC_TEST_DATABASE_PASSWORD")));
        }

        List<String> args(String module, String schema) {
            return new ArrayList<>(List.of(
                    "--spring.config.location="
                            + root.resolve(module + "/src/main/resources/application.yaml")
                                    .toUri(),
                    "--spring.main.banner-mode=off",
                    "--logging.level.root=WARN",
                    "--server.port=0",
                    "--spring.datasource.url=" + databaseUrl + (databaseUrl.contains("?") ? "&" : "?")
                            + "currentSchema=" + schema,
                    "--spring.datasource.username=" + System.getenv("FOC_TEST_DATABASE_USERNAME"),
                    "--spring.datasource.password=" + System.getenv("FOC_TEST_DATABASE_PASSWORD"),
                    "--spring.flyway.default-schema=" + schema,
                    "--spring.flyway.locations=filesystem:" + root.resolve(module + "/src/main/resources/db/migration"),
                    "--spring.rabbitmq.host=127.0.0.1",
                    "--spring.rabbitmq.port=" + broker.port(),
                    "--spring.rabbitmq.username=" + rabbit.getUsername(),
                    "--spring.rabbitmq.password=" + rabbit.getPassword(),
                    "--spring.rabbitmq.connection-timeout=1s"));
        }

        ConfigurableApplicationContext start(Class<?> application, List<String> args, boolean authenticatedHttp) {
            return new SpringApplicationBuilder(application)
                    .initializers(context -> {
                        context.getBeanFactory().addBeanPostProcessor(new BeanPostProcessor() {
                            @Override
                            public Object postProcessBeforeInitialization(Object bean, String name) {
                                if (bean instanceof CachingConnectionFactory factory)
                                    factory.setExecutor(rabbitExecutor);
                                return bean;
                            }
                        });
                        if (authenticatedHttp) {
                            // Test-only JWT decoder: exercises HTTP authentication and subject propagation without User
                            // Service.
                            context.getBeanFactory().registerSingleton("integrationJwtDecoder", (JwtDecoder)
                                    token -> Jwt.withTokenValue(token)
                                            .header("alg", "test")
                                            .subject(token)
                                            .issuedAt(Instant.now())
                                            .expiresAt(Instant.now().plusSeconds(60))
                                            .build());
                        }
                    })
                    .run(args.toArray(String[]::new));
        }

        void startOrder() {
            var args = args("order-service", orderSchema);
            args.addAll(List.of(
                    "--spring.cloud.stream.bindings.errandSubmitted-out-0.destination=" + input,
                    "--spring.cloud.stream.bindings.creditReservationResult-in-0.destination=" + output,
                    "--order.credit.retry-delay=100ms",
                    "--order.credit.max-retry-delay=1s",
                    "--order.credit.publish-interval=100",
                    "--order.credit.interval=100"));
            order = start(OrderServiceApplication.class, args, true);
        }

        void startCredit() {
            var args = args("credit-service", creditSchema);
            args.addAll(List.of(
                    "--spring.cloud.function.definition=errandSubmitted",
                    "--spring.cloud.stream.bindings.errandSubmitted-in-0.destination=" + input,
                    "--spring.cloud.stream.bindings.creditResult-out-0.destination=" + output,
                    "--credit.rewards.retry-delay=100ms",
                    "--credit.rewards.max-retry-delay=1s",
                    "--credit.rewards.interval=100",
                    "--credit.rewards.publish-interval=100"));
            credit = start(CreditServiceApplication.class, args, false);
        }

        void stopOrder() {
            if (order != null) {
                order.close();
                order = null;
            }
        }

        URI uri(String path) {
            return URI.create("http://127.0.0.1:"
                    + ((WebServerApplicationContext) order).getWebServer().getPort() + "/api/orders" + path);
        }

        HttpResponse<String> create(UUID user, String key) throws Exception {
            return http.send(
                    HttpRequest.newBuilder(uri(""))
                            .timeout(Duration.ofSeconds(10))
                            .header("Authorization", "Bearer " + user)
                            .header("Idempotency-Key", key)
                            .header("Content-Type", "application/json")
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            "{\"pickup\":\"COM3\",\"destination\":\"UTown\",\"description\":\"Collect lunch\",\"reward\":80}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        UUID id(HttpResponse<String> response) {
            return UUID.fromString(json.readTree(response.body()).get("id").asText());
        }

        String state(UUID user, UUID id) throws Exception {
            var response = http.send(
                    HttpRequest.newBuilder(uri("/" + id))
                            .timeout(Duration.ofSeconds(10))
                            .header("Authorization", "Bearer " + user)
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            return json.readTree(response.body()).get("status").asText();
        }

        void awaitState(UUID user, UUID id, String state) {
            await().atMost(Duration.ofSeconds(40))
                    .untilAsserted(() -> assertThat(state(user, id)).isEqualTo(state));
        }

        void awaitCount(JdbcTemplate sql, String table, long count) {
            await().atMost(Duration.ofSeconds(30))
                    .untilAsserted(() -> assertThat(sql.queryForObject("SELECT count(*) FROM " + table, Long.class))
                            .isEqualTo(count));
        }

        int depth(String queue) throws Exception {
            try (var c = rabbit.newConnection();
                    var ch = c.createChannel()) {
                return ch.queueDeclarePassive(queue).getMessageCount();
            }
        }

        @Override
        public void close() throws Exception {
            try {
                stopOrder();
                if (credit != null) credit.close();
                await().atMost(Duration.ofSeconds(10))
                        .until(() -> rabbitExecutor.getActiveCount() == 0
                                && rabbitExecutor.getQueue().isEmpty());
            } finally {
                broker.close();
                rabbitExecutor.close();
                http.close();
                try (var c = rabbit.newConnection();
                        var ch = c.createChannel()) {
                    ch.queueDelete(input + ".credit-reservations");
                    ch.queueDelete(input + ".credit-reservations.retry");
                    ch.queueDelete(output + ".order-credit-results");
                    ch.queueDelete(output + ".order-credit-results.retry");
                    for (var exchange : List.of(input, input + ".dlx", output, output + ".dlx"))
                        ch.exchangeDelete(exchange);
                } finally {
                    orderSql.execute("DROP SCHEMA IF EXISTS " + orderSchema + " CASCADE");
                    creditSql.execute("DROP SCHEMA IF EXISTS " + creditSchema + " CASCADE");
                }
            }
        }
    }
    /** Drops real TCP connections to exercise recovery without controlling the shared CI broker/database. */
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
