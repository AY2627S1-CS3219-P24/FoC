package com.cs3219.foc.order.integration;

import static org.assertj.core.api.Assertions.*;

import com.cs3219.foc.credit.CreditServiceApplication;
import com.cs3219.foc.order.OrderServiceApplication;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.oauth2.jwt.*;
import tools.jackson.databind.json.JsonMapper;

@EnabledIfEnvironmentVariable(named = "FOC_TEST_DATABASE_URL", matches = ".+")
class ErrandFundingIntegrationTests {
    @Test
    void creationReturnsImmediateDecisionAndDuplicateRequestsReserveOnce() throws Exception {
        try (var apps = new Apps()) {
            var created = apps.create("key");
            assertThat(created.statusCode()).isEqualTo(201);
            assertThat(apps.json.readTree(created.body()).get("status").asText())
                    .isEqualTo("OPEN");
            var duplicate = apps.create("key");
            assertThat(duplicate.statusCode()).isEqualTo(201);
            assertThat(apps.json.readTree(duplicate.body()).get("id"))
                    .isEqualTo(apps.json.readTree(created.body()).get("id"));
            var rejected = apps.create("another");
            assertThat(rejected.statusCode()).isEqualTo(422);
            assertThat(apps.json.readTree(rejected.body()).get("creditOutcome").asText())
                    .isEqualTo("INSUFFICIENT_BALANCE");
            assertThat(apps.count("credit_transaction_log")).isEqualTo(1);
            assertThat(apps.creditSql.queryForObject(
                            "SELECT sum(amount) FROM credit_reservations WHERE state='RESERVED'", Long.class))
                    .isEqualTo(80);
            assertThat(apps.creditSql.queryForObject("SELECT total_balance FROM credit_accounts", Long.class))
                    .isEqualTo(100);
        }
    }

    @Test
    void lostCreditResponseAndOrderRestartRetrySameCommittedReservation() throws Exception {
        try (var apps = new Apps()) {
            apps.discardResponse.set(true);
            assertThat(apps.create("lost-response").statusCode()).isEqualTo(503);
            assertThat(apps.count("credit_reservations")).isEqualTo(1);
            assertThat(apps.count("credit_transaction_log")).isEqualTo(1);
            var operation = apps.orderSql.queryForObject("SELECT reservation_operation_id FROM errands", UUID.class);
            apps.order.close();
            apps.startOrder();
            assertThat(apps.create("lost-response").statusCode()).isEqualTo(201);
            assertThat(apps.orderSql.queryForObject("SELECT reservation_operation_id FROM errands", UUID.class))
                    .isEqualTo(operation);
            assertThat(apps.count("credit_reservations")).isEqualTo(1);
            assertThat(apps.count("credit_transaction_log")).isEqualTo(1);
        }
    }

    @Test
    void unavailableCreditReturnsFailureAndSameKeyCanBeRetried() throws Exception {
        try (var apps = new Apps()) {
            apps.available.set(false);
            assertThat(apps.create("retry").statusCode()).isEqualTo(503);
            assertThat(apps.count("credit_reservations")).isZero();
            apps.available.set(true);
            assertThat(apps.create("retry").statusCode()).isEqualTo(201);
            assertThat(apps.count("credit_transaction_log")).isEqualTo(1);
        }
    }

    private static class Apps implements AutoCloseable {
        final String suffix = UUID.randomUUID().toString().replace("-", "");
        final String orderSchema = "order_http_" + suffix;
        final String creditSchema = "credit_http_" + suffix;
        final String databaseUrl = System.getenv("FOC_TEST_DATABASE_URL");
        final Path root = Path.of(System.getProperty("foc.root"));
        final UUID owner = UUID.randomUUID();
        final HttpClient http = HttpClient.newHttpClient();
        final JsonMapper json = JsonMapper.builder().build();
        final AtomicBoolean available = new AtomicBoolean(true);
        final AtomicBoolean discardResponse = new AtomicBoolean();
        final JdbcTemplate orderSql = sql(orderSchema);
        final JdbcTemplate creditSql = sql(creditSchema);
        final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        final HttpServer proxy;
        final ConfigurableApplicationContext credit;
        ConfigurableApplicationContext order;

        Apps() throws Exception {
            var args = args("credit-service", creditSchema);
            args.add("--spring.cloud.function.definition=");
            args.add("--spring.cloud.stream.function.autodetect=false");
            for (var binding : List.of("userCreated", "errandCancelled", "errandExpired", "errandCompleted"))
                args.add("--spring.cloud.stream.bindings." + binding + "-in-0.consumer.auto-startup=false");
            credit = start(CreditServiceApplication.class, args);
            creditSql.update("INSERT INTO credit_accounts(user_id,total_balance) VALUES (?,100)", owner);
            proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            proxy.setExecutor(executor);
            proxy.createContext("/api/credits/reservations", exchange -> {
                try {
                    if (!available.get()) {
                        exchange.sendResponseHeaders(503, -1);
                        return;
                    }
                    var request = HttpRequest.newBuilder(
                                    URI.create("http://127.0.0.1:" + port(credit) + "/api/credits/reservations"))
                            .header(
                                    "Authorization",
                                    exchange.getRequestHeaders().getFirst("Authorization"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofByteArray(
                                    exchange.getRequestBody().readAllBytes()))
                            .build();
                    var response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
                    // Credit committed, but Order never receives that success response.
                    if (discardResponse.getAndSet(false)) {
                        exchange.sendResponseHeaders(502, -1);
                        return;
                    }
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(response.statusCode(), response.body().length);
                    exchange.getResponseBody().write(response.body());
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IOException(ex);
                } finally {
                    exchange.close();
                }
            });
            proxy.start();
            startOrder();
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
                    "--spring.modulith.events.jdbc.schema-initialization.enabled=false"));
        }

        ConfigurableApplicationContext start(Class<?> type, List<String> args) {
            return new SpringApplicationBuilder(type)
                    .initializers(context -> context.getBeanFactory()
                            .registerSingleton("integrationJwtDecoder", (JwtDecoder) token -> Jwt.withTokenValue(token)
                                    .header("alg", "test")
                                    .subject(token)
                                    .issuedAt(Instant.now())
                                    .expiresAt(Instant.now().plusSeconds(60))
                                    .build()))
                    .run(args.toArray(String[]::new));
        }

        void startOrder() {
            var args = args("order-service", orderSchema);
            args.add("--order.credit.base-url=http://127.0.0.1:"
                    + proxy.getAddress().getPort() + "/api");
            order = start(OrderServiceApplication.class, args);
        }

        int port(ConfigurableApplicationContext context) {
            return ((WebServerApplicationContext) context).getWebServer().getPort();
        }

        HttpResponse<String> create(String key) throws Exception {
            return http.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port(order) + "/api/orders"))
                            .timeout(Duration.ofSeconds(15))
                            .header("Authorization", "Bearer " + owner)
                            .header("Idempotency-Key", key)
                            .header("Content-Type", "application/json")
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            "{\"pickup\":\"COM3\",\"destination\":\"UTown\",\"description\":\"Lunch\",\"reward\":80}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        long count(String table) {
            return creditSql.queryForObject("SELECT count(*) FROM " + table, Long.class);
        }

        @Override
        public void close() {
            if (order != null) order.close();
            credit.close();
            proxy.stop(0);
            executor.close();
            http.close();
            orderSql.execute("DROP SCHEMA IF EXISTS " + orderSchema + " CASCADE");
            creditSql.execute("DROP SCHEMA IF EXISTS " + creditSchema + " CASCADE");
        }
    }
}
