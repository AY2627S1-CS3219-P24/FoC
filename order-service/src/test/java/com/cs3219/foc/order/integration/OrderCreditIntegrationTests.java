package com.cs3219.foc.order.integration;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import com.cs3219.foc.credit.CreditServiceApplication;
import com.cs3219.foc.credit.service.CreditAccountService;
import com.cs3219.foc.order.OrderServiceApplication;
import com.cs3219.foc.order.infrastructure.client.CreditClient;
import com.cs3219.foc.order.model.dto.CreditReservationResponse;
import com.cs3219.foc.order.model.dto.event.ErrandCancelledEvent;
import com.cs3219.foc.order.model.dto.event.ErrandCompletedEvent;
import com.cs3219.foc.order.model.dto.event.ErrandExpiredEvent;
import com.cs3219.foc.order.service.OrderService;
import com.rabbitmq.client.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "FOC_TEST_DATABASE_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "FOC_TEST_RABBIT_HOST", matches = ".+")
class OrderCreditIntegrationTests {
    private final String suffix = UUID.randomUUID().toString().replace("-", "");
    private final String orderSchema = "order_e2e_" + suffix;
    private final String creditSchema = "credit_e2e_" + suffix;
    private final UUID supplier = UUID.randomUUID();
    private final JsonMapper json = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newHttpClient();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final List<com.cs3219.foc.credit.model.dto.event.CreditSettlementResultEvent> settlementResults =
            new CopyOnWriteArrayList<>();
    private final Set<UUID> handledSettlementResults = ConcurrentHashMap.newKeySet();
    private final List<UUID> terminalEventOrders = new CopyOnWriteArrayList<>();
    private final AtomicReference<ReservationPause> reservationPause = new AtomicReference<>();
    private final Map<String, String> destinations = Map.of(
            "errandCompleted",
            "completed",
            "errandCancelled",
            "cancelled",
            "errandExpired",
            "expired",
            "creditSettlementResult",
            "settled");
    private ConfigurableApplicationContext order;
    private ConfigurableApplicationContext credit;
    private JdbcTemplate orders;
    private JdbcTemplate credits;
    private HttpServer directory;
    private Connection connection;
    private Channel channel;
    private String baseUrl;

    @BeforeAll
    void startServices() throws Exception {
        orders = sql(orderSchema);
        credits = sql(creditSchema);
        directory = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        directory.setExecutor(executor);
        directory.createContext("/api/", exchange -> {
            try (exchange) {
                if (exchange.getRequestHeaders().getFirst("Authorization") == null) {
                    exchange.sendResponseHeaders(401, -1);
                    return;
                }
                Object result;
                if (exchange.getRequestURI().getPath().startsWith("/api/suppliers/")) {
                    // The real Supplier response has additional fields; the client consumes its pickup subset.
                    result = Map.of(
                            "id",
                            supplier,
                            "name",
                            "Campus food",
                            "building",
                            "COM3",
                            "floor",
                            "1",
                            "locationDescription",
                            "Stall 2",
                            "active",
                            true,
                            "openingHours",
                            List.of());
                } else {
                    var ids = URLDecoder.decode(
                            exchange.getRequestURI().getRawQuery().substring(4), StandardCharsets.UTF_8);
                    result = Arrays.stream(ids.split(","))
                            .map(id -> Map.of("id", id, "name", "Student " + id.substring(0, 4)))
                            .toList();
                }
                var body = json.writeValueAsBytes(result);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        directory.start();
        var rabbit = new ConnectionFactory();
        rabbit.setHost(System.getenv("FOC_TEST_RABBIT_HOST"));
        rabbit.setPort(Integer.parseInt(System.getenv().getOrDefault("FOC_TEST_RABBIT_PORT", "5672")));
        rabbit.setUsername(System.getenv("FOC_TEST_RABBIT_USERNAME"));
        rabbit.setPassword(System.getenv("FOC_TEST_RABBIT_PASSWORD"));
        connection = rabbit.newConnection(executor);
        channel = connection.createChannel();
        channel.confirmSelect();
        var creditArgs = arguments("credit", creditSchema);
        creditArgs.add("--spring.cloud.function.definition=errandCancelled;errandExpired;errandCompleted");
        credit = start(CreditServiceApplication.class, creditArgs);
        var orderArgs = arguments("order", orderSchema);
        orderArgs.addAll(List.of(
                "--orders.expiry-scan-delay=1h",
                "--services.credit-url=http://127.0.0.1:"
                        + ((WebServerApplicationContext) credit).getWebServer().getPort() + "/api",
                "--services.supplier-url=http://127.0.0.1:"
                        + directory.getAddress().getPort() + "/api",
                "--services.user-url=http://127.0.0.1:" + directory.getAddress().getPort() + "/api"));
        order = start(OrderServiceApplication.class, orderArgs);
        baseUrl = "http://127.0.0.1:"
                + ((WebServerApplicationContext) order).getWebServer().getPort() + "/api/orders";
    }

    @Test
    void httpLifecycleReservesPaysRefundsAndPublishesAtomically() throws Exception {
        var requester = account(100);
        var courier = account(15);
        var key = UUID.randomUUID();
        var body = creationBody(key, 5);
        var created = request("POST", "", requester, body, 200);
        var id = UUID.fromString(created.get("errandId").asText());
        assertThat(created.get("status").asText()).isEqualTo("OPEN");
        awaitOrder(id, "OPEN", "RESERVED");
        assertThat(balance(requester, "reserved_balance")).isEqualTo(5);
        assertThat(credits.queryForObject(
                        "SELECT operation_id FROM credit_reservations WHERE errand_id=?", UUID.class, id))
                .isEqualTo(id);
        assertThat(request("POST", "", requester, body, 200).get("errandId").asText())
                .isEqualTo(id.toString());
        request("POST", "", requester, creationBody(key, 6), 409);
        var details = request("GET", "/" + id, courier, null, 200);
        assertThat(details.at("/pickup/address").asText()).isEqualTo("COM3, 1, Stall 2");
        assertThat(details.at("/requester/name").asText()).startsWith("Student ");
        var accepted = request("POST", "/" + id + "/accept", courier, null, 200);
        assertThat(accepted.size()).isEqualTo(3);
        assertThat(accepted.get("errandId").asText()).isEqualTo(id.toString());
        assertThat(accepted.get("status").asText()).isEqualTo("ACCEPTED");
        assertThat(accepted.get("creditState").asText()).isEqualTo("RESERVED");
        request("POST", "/" + id + "/complete", requester, null, 409);
        request("POST", "/" + id + "/pick-up", courier, null, 200);
        request("POST", "/" + id + "/complete", courier, null, 403);
        request("POST", "/" + id + "/complete", requester, null, 200);
        awaitOrder(id, "COMPLETED", "SETTLED");
        request("POST", "/" + id + "/complete", requester, null, 200);
        assertThat(balance(requester, "total_balance")).isEqualTo(95);
        assertThat(balance(courier, "total_balance")).isEqualTo(20);

        // Re-deliver completion after payment and wait for the duplicate result's Order transaction to finish.
        var previousResults = settlementResults.stream()
                .map(com.cs3219.foc.credit.model.dto.event.CreditSettlementResultEvent::eventId)
                .collect(Collectors.toSet());
        UUID operationId = orders.queryForObject("SELECT settlement_event_id FROM errands WHERE id=?", UUID.class, id);
        publish(
                "errandCompleted",
                new ErrandCompletedEvent(
                        operationId,
                        "ErrandCompletedEvent",
                        1,
                        OffsetDateTime.now(),
                        new ErrandCompletedEvent.Payload(id, requester, 5, courier)));
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            var repeatedResult = settlementResults.stream()
                    .filter(result -> result.payload().operationId().equals(operationId)
                            && !previousResults.contains(result.eventId()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Credit has not emitted the duplicate result"));
            assertThat(handledSettlementResults).contains(repeatedResult.eventId());
        });
        awaitOrder(id, "COMPLETED", "SETTLED");
        assertThat(credits.queryForObject(
                        "SELECT count(*) FROM credit_transaction_log WHERE errand_id=?", Long.class, id))
                .isEqualTo(2);
        assertThat(balance(requester, "total_balance")).isEqualTo(95);
        assertThat(balance(courier, "total_balance")).isEqualTo(20);

        var cancelled = createOpen(requester, 7);
        request("POST", "/" + cancelled + "/cancel", requester, null, 200);
        awaitOrder(cancelled, "CANCELLED", "RELEASED");
        request("POST", "/" + cancelled + "/cancel", requester, null, 200);
        var expired = createOpen(requester, 8);
        orders.update("UPDATE errands SET expires_at=created_at + interval '1 millisecond' WHERE id=?", expired);
        order.getBean(OrderService.class).expireOpenOrders();
        awaitOrder(expired, "EXPIRED", "RELEASED");
        assertThat(balance(requester, "reserved_balance")).isZero();
        assertThat(balance(requester, "total_balance")).isEqualTo(95);

        // Retry the original operation after Credit commits but Order finalization fails.
        var uncertainKey = UUID.randomUUID();
        UUID uncertainId;
        orders.execute("ALTER TABLE errands ADD CONSTRAINT reject_open CHECK(status <> 'OPEN') NOT VALID");
        try {
            JsonNode failure = request("POST", "", requester, creationBody(uncertainKey, 1), 500);
            uncertainId =
                    orders.queryForObject("SELECT id FROM errands WHERE create_request_id=?", UUID.class, uncertainKey);
            assertThat(failure.get("code").asText()).isEqualTo("ORDER_CREATION_ERROR");
            assertThat(failure.get("errandId").asText()).isEqualTo(uncertainId.toString());
            awaitOrder(uncertainId, "CREATING", null);
            assertThat(credits.queryForObject(
                            "SELECT state FROM credit_reservations WHERE errand_id=?", String.class, uncertainId))
                    .isEqualTo("RESERVED");
        } finally {
            orders.execute("ALTER TABLE errands DROP CONSTRAINT reject_open");
        }
        var simultaneous = new CyclicBarrier(2);
        JsonNode recovery = request("GET", "/" + uncertainId, requester, null, 200);
        assertThat(recovery.get("createRequestId").asText()).isEqualTo(uncertainKey.toString());
        assertThat(recovery.has("expiryMinutes")).isFalse();
        Callable<JsonNode> retry = () -> {
            simultaneous.await(10, TimeUnit.SECONDS);
            return request("POST", "/" + uncertainId + "/retry-creation", requester, null, 200);
        };
        var retryA = executor.submit(retry);
        var retryB = executor.submit(retry);
        assertThat(retryA.get(20, TimeUnit.SECONDS).get("errandId").asText()).isEqualTo(uncertainId.toString());
        assertThat(retryB.get(20, TimeUnit.SECONDS).get("errandId").asText()).isEqualTo(uncertainId.toString());
        awaitOrder(uncertainId, "OPEN", "RESERVED");
        assertThat(orders.queryForObject(
                        "SELECT count(*) FROM errands WHERE create_request_id=?", Long.class, uncertainKey))
                .isEqualTo(1);
        assertThat(credits.queryForObject(
                        "SELECT count(*) FROM credit_reservations WHERE errand_id=?", Long.class, uncertainId))
                .isEqualTo(1);
        assertThat(credits.queryForObject(
                        "SELECT count(*) FROM credit_transaction_log WHERE errand_id=?", Long.class, uncertainId))
                .isEqualTo(1);
        assertThat(balance(requester, "total_balance")).isEqualTo(95);
        assertThat(balance(requester, "reserved_balance")).isEqualTo(1);
        request("POST", "/" + uncertainId + "/cancel", requester, null, 200);
        awaitOrder(uncertainId, "CANCELLED", "RELEASED");
        assertThat(balance(requester, "reserved_balance")).isZero();
        request("POST", "", requester, body.replace("\"creditReward\":5", "\"creditReward\":1.5"), 400);
        assertNoPendingPublicationsOrDeadLetters();
    }

    @Test
    void unreadyUsersAreRejectedBeforeAdmissionAndCanProceedAfterProvisioning() throws Exception {
        var newUser = UUID.randomUUID();
        var body = creationBody(UUID.randomUUID(), 5);
        assertThat(request("POST", "", newUser, body, 409).get("code").asText()).isEqualTo("CREDIT_ACCOUNT_PENDING");
        assertThat(orders.queryForObject("SELECT count(*) FROM errands WHERE requester_id=?", Long.class, newUser))
                .isZero();
        assertThat(orders.queryForObject("SELECT count(*) FROM event_publication", Long.class))
                .isZero();
        assertThat(credits.queryForObject(
                        "SELECT count(*) FROM credit_reservations WHERE user_id=?", Long.class, newUser))
                .isZero();
        credit.getBean(CreditAccountService.class).initializeAccount(newUser);
        var waiting = UUID.fromString(
                request("POST", "", newUser, body, 200).get("errandId").asText());
        awaitOrder(waiting, "OPEN", "RESERVED");

        var courier = UUID.randomUUID();
        assertThat(request("POST", "/" + waiting + "/accept", courier, null, 409)
                        .get("code")
                        .asText())
                .isEqualTo("CREDIT_ACCOUNT_PENDING");
        awaitOrder(waiting, "OPEN", "RESERVED");
        assertThat(orders.queryForObject("SELECT courier_id FROM errands WHERE id=?", UUID.class, waiting))
                .isNull();
        credit.getBean(CreditAccountService.class).initializeAccount(courier);
        credits.update("UPDATE credit_accounts SET total_balance=0 WHERE user_id=?", courier);
        request("POST", "/" + waiting + "/accept", courier, null, 200);
        request("POST", "/" + waiting + "/pick-up", courier, null, 200);
        request("POST", "/" + waiting + "/complete", newUser, null, 200);
        awaitOrder(waiting, "COMPLETED", "SETTLED");
        assertThat(balance(courier, "total_balance")).isEqualTo(5);
        assertThat(balance(newUser, "total_balance")).isEqualTo(10);
        assertThat(balance(newUser, "reserved_balance")).isZero();
        var poor = account(1);
        var rejected = UUID.fromString(request("POST", "", poor, creationBody(UUID.randomUUID(), 2), 200)
                .get("errandId")
                .asText());
        awaitOrder(rejected, "CREATE_FAILED", null);
        assertThat(orders.queryForObject(
                        "SELECT creation_failure_code FROM errands WHERE id=?", String.class, rejected))
                .isEqualTo("INSUFFICIENT_BALANCE");
        assertNoPendingPublicationsOrDeadLetters();
    }

    @Test
    void completionAndSettlementResultPublicationsRecoverWithoutRepeatingPayment() throws Exception {
        UUID requester = account(100);
        UUID courier = account(0);
        // Warm up both dynamic producer bindings before removing routes.
        UUID warmup = createOpen(requester, 1);
        request("POST", "/" + warmup + "/accept", courier, null, 200);
        request("POST", "/" + warmup + "/pick-up", courier, null, 200);
        request("POST", "/" + warmup + "/complete", requester, null, 200);
        awaitOrder(warmup, "COMPLETED", "SETTLED");
        String completedDestination = destination("errandCompleted");
        String completedQueue = completedDestination + "." + group("errandCompleted");
        String resultDestination = destination("creditSettlementResult");
        String resultQueue = resultDestination + "." + group("creditSettlementResult");
        UUID id = createOpen(requester, 5);
        request("POST", "/" + id + "/accept", courier, null, 200);
        request("POST", "/" + id + "/pick-up", courier, null, 200);
        try {
            channel.queueUnbind(completedQueue, completedDestination, "#");
            request("POST", "/" + id + "/complete", requester, null, 200);
            awaitFailedPublication(orders, "order-completed-rabbit", id);
            awaitOrder(id, "COMPLETED", "SETTLEMENT_PENDING");
            assertThat(credits.queryForObject(
                            "SELECT state FROM credit_reservations WHERE errand_id=?", String.class, id))
                    .isEqualTo("RESERVED");

            channel.queueUnbind(resultQueue, resultDestination, "#");
            channel.queueBind(completedQueue, completedDestination, "#");
            awaitFailedPublication(credits, "credit-settlement-rabbit", id);
            assertThat(credits.queryForObject(
                            "SELECT state FROM credit_reservations WHERE errand_id=?", String.class, id))
                    .isEqualTo("PAID");
            awaitOrder(id, "COMPLETED", "SETTLEMENT_PENDING");

            channel.queueBind(resultQueue, resultDestination, "#");
            awaitOrder(id, "COMPLETED", "SETTLED");
            assertThat(credits.queryForObject(
                            "SELECT count(*) FROM credit_transaction_log WHERE errand_id=?", Long.class, id))
                    .isEqualTo(2);
            assertThat(balance(requester, "total_balance")).isEqualTo(94);
            assertThat(balance(requester, "reserved_balance")).isZero();
            assertThat(balance(courier, "total_balance")).isEqualTo(6);
            assertNoPendingPublicationsOrDeadLetters();
        } finally {
            channel.queueBind(completedQueue, completedDestination, "#");
            channel.queueBind(resultQueue, resultDestination, "#");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACCEPTED", "COMPLETED", "CANCELLED", "EXPIRED"})
    void lateHttpCreationPreservesTheStateReachedAfterRetry(String targetStatus) throws Exception {
        UUID requester = account(100);
        UUID courier = account(0);
        UUID key = UUID.randomUUID();
        String body = creationBody(key, 5);
        var pause = new ReservationPause();
        reservationPause.set(pause);
        Future<JsonNode> original = executor.submit(() -> request("POST", "", requester, body, 200));
        try {
            // Pause only after the real Credit HTTP call commits, while the original Order request is still open.
            assertThat(pause.waiting.await(10, TimeUnit.SECONDS)).isTrue();
            UUID id = orders.queryForObject("SELECT id FROM errands WHERE create_request_id=?", UUID.class, key);
            assertThat(request("POST", "", requester, body, 200).get("errandId").asText())
                    .isEqualTo(id.toString());
            if (targetStatus.equals("ACCEPTED") || targetStatus.equals("COMPLETED")) {
                request("POST", "/" + id + "/accept", courier, null, 200);
                if (targetStatus.equals("COMPLETED")) {
                    request("POST", "/" + id + "/pick-up", courier, null, 200);
                    request("POST", "/" + id + "/complete", requester, null, 200);
                }
            } else if (targetStatus.equals("CANCELLED")) {
                request("POST", "/" + id + "/cancel", requester, null, 200);
            } else {
                orders.update("UPDATE errands SET expires_at=created_at + interval '1 millisecond' WHERE id=?", id);
                order.getBean(OrderService.class).expireOpenOrders();
            }
            String expectedCreditState =
                    switch (targetStatus) {
                        case "ACCEPTED" -> "RESERVED";
                        case "COMPLETED" -> "SETTLED";
                        default -> "RELEASED";
                    };
            awaitOrder(id, targetStatus, expectedCreditState);
            Map<String, Object> advanced = orders.queryForMap("SELECT * FROM errands WHERE id=?", id);
            long terminalEvents =
                    terminalEventOrders.stream().filter(id::equals).count();
            long requesterBalance = balance(requester, "total_balance");
            long courierBalance = balance(courier, "total_balance");
            long reservedBalance = balance(requester, "reserved_balance");

            pause.release.countDown();
            JsonNode lateResponse = original.get(10, TimeUnit.SECONDS);
            assertThat(lateResponse.get("errandId").asText()).isEqualTo(id.toString());
            assertThat(lateResponse.get("status").asText()).isEqualTo(targetStatus);
            assertThat(orders.queryForMap("SELECT * FROM errands WHERE id=?", id))
                    .isEqualTo(advanced);
            assertThat(terminalEventOrders.stream().filter(id::equals).count()).isEqualTo(terminalEvents);
            assertThat(terminalEvents).isEqualTo(targetStatus.equals("ACCEPTED") ? 0 : 1);
            assertThat(credits.queryForObject(
                            "SELECT count(*) FROM credit_reservations WHERE errand_id=?", Long.class, id))
                    .isEqualTo(1);
            assertThat(credits.queryForObject(
                            "SELECT count(*) FROM credit_transaction_log WHERE errand_id=?", Long.class, id))
                    .isEqualTo(targetStatus.equals("ACCEPTED") ? 1 : 2);
            assertThat(balance(requester, "total_balance")).isEqualTo(requesterBalance);
            assertThat(balance(courier, "total_balance")).isEqualTo(courierBalance);
            assertThat(balance(requester, "reserved_balance")).isEqualTo(reservedBalance);
            assertNoPendingPublicationsOrDeadLetters();
        } finally {
            pause.release.countDown();
            try {
                original.get(10, TimeUnit.SECONDS);
            } finally {
                reservationPause.compareAndSet(pause, null);
            }
        }
    }

    private static class ReservationPause {
        final CountDownLatch waiting = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean claimed = new AtomicBoolean();
    }

    private void awaitFailedPublication(JdbcTemplate database, String listenerId, UUID errandId) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(database.queryForObject(
                        "SELECT count(*) FROM event_publication WHERE listener_id=? AND serialized_event::jsonb->'payload'->>'errandId'=? AND status='FAILED' AND completion_date IS NULL",
                        Long.class,
                        listenerId,
                        errandId.toString()))
                .isEqualTo(1));
    }

    private UUID account(long amount) {
        var id = UUID.randomUUID();
        credit.getBean(CreditAccountService.class).initializeAccount(id);
        credits.update("UPDATE credit_accounts SET total_balance=? WHERE user_id=?", amount, id);
        return id;
    }

    private long balance(UUID id, String column) {
        // Both column arguments are constants in this test, never user input.
        if (column.equals("reserved_balance")) {
            return credit.getBean(CreditAccountService.class).getBalance(id).reservedBalance();
        }
        return credits.queryForObject("SELECT " + column + " FROM credit_accounts WHERE user_id=?", Long.class, id);
    }

    private UUID createOpen(UUID requester, long amount) throws Exception {
        var id = UUID.fromString(request("POST", "", requester, creationBody(UUID.randomUUID(), amount), 200)
                .get("errandId")
                .asText());
        awaitOrder(id, "OPEN", "RESERVED");
        return id;
    }

    private String creationBody(UUID key, long amount) {
        return json.writeValueAsString(Map.of(
                "createRequestId",
                key,
                "supplierId",
                supplier,
                "deliveryLocation",
                "COM1",
                "description",
                "Lunch",
                "creditReward",
                amount,
                "expiryMinutes",
                10));
    }

    private JsonNode request(String method, String path, UUID actor, String body, int expected) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + actor)
                .header("Content-Type", "application/json")
                .method(
                        method,
                        body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build();
        var result = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(result.statusCode())
                .as("%s %s: %s", method, path, result.body())
                .isEqualTo(expected);
        return json.readTree(result.body());
    }

    private void awaitOrder(UUID id, String status, String creditState) {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            var row = orders.queryForMap("SELECT status, credit_state FROM errands WHERE id=?", id);
            assertThat(row.get("status")).isEqualTo(status);
            assertThat(row.get("credit_state")).isEqualTo(creditState);
        });
    }

    private void publish(String binding, Object event) throws Exception {
        channel.basicPublish(
                destination(binding),
                destination(binding),
                new AMQP.BasicProperties.Builder()
                        .contentType("application/json")
                        .deliveryMode(2)
                        .build(),
                json.writeValueAsBytes(event));
        channel.waitForConfirmsOrDie(5000);
    }

    private void assertNoPendingPublicationsOrDeadLetters() {
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(orders.queryForObject("SELECT count(*) FROM event_publication", Long.class))
                    .isZero();
            assertThat(credits.queryForObject("SELECT count(*) FROM event_publication", Long.class))
                    .isZero();
            for (var binding : destinations.keySet()) {
                assertThat(channel.queueDeclarePassive(destination(binding) + "." + group(binding) + ".dlq")
                                .getMessageCount())
                        .isZero();
            }
        });
    }

    private String destination(String binding) {
        return "foc.order.test." + suffix + "." + destinations.get(binding);
    }

    private String group(String binding) {
        return switch (binding) {
            case "creditSettlementResult" -> "order-credit-settlements";
            default -> "credit-settlements";
        };
    }

    private JdbcTemplate sql(String schema) {
        var url = System.getenv("FOC_TEST_DATABASE_URL");
        return new JdbcTemplate(new DriverManagerDataSource(
                url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                System.getenv("FOC_TEST_DATABASE_USERNAME"),
                System.getenv("FOC_TEST_DATABASE_PASSWORD")));
    }

    private ArrayList<String> arguments(String service, String schema) {
        var root = Path.of(System.getProperty("foc.root"));
        var url = System.getenv("FOC_TEST_DATABASE_URL");
        var args = new ArrayList<>(List.of(
                "--spring.config.location="
                        + root.resolve(service + "-service/src/main/resources/application.yaml")
                                .toUri(),
                "--spring.main.banner-mode=off",
                "--logging.level.root=WARN",
                "--events.publication.confirm-timeout=1s",
                "--events.publication.retry-delay=200ms",
                "--spring.modulith.events.staleness.published=0s",
                "--spring.modulith.events.staleness.processing=0s",
                "--spring.modulith.events.staleness.resubmitted=0s",
                "--server.port=0",
                "--spring.datasource.url=" + url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                "--spring.datasource.username=" + System.getenv("FOC_TEST_DATABASE_USERNAME"),
                "--spring.datasource.password=" + System.getenv("FOC_TEST_DATABASE_PASSWORD"),
                "--spring.flyway.default-schema=" + schema,
                "--spring.flyway.locations=filesystem:"
                        + root.resolve(service + "-service/src/main/resources/db/migration"),
                "--spring.rabbitmq.host=" + System.getenv("FOC_TEST_RABBIT_HOST"),
                "--spring.rabbitmq.port=" + System.getenv().getOrDefault("FOC_TEST_RABBIT_PORT", "5672"),
                "--spring.rabbitmq.username=" + System.getenv("FOC_TEST_RABBIT_USERNAME"),
                "--spring.rabbitmq.password=" + System.getenv("FOC_TEST_RABBIT_PASSWORD")));
        destinations
                .keySet()
                .forEach(binding -> args.add("--spring.cloud.stream.bindings." + binding
                        + (binding.startsWith("errand") == service.equals("order") ? "-out-0" : "-in-0")
                        + ".destination=" + destination(binding)));
        return args;
    }

    private ConfigurableApplicationContext start(Class<?> application, List<String> args) {
        return new SpringApplicationBuilder(application)
                .initializers(context -> {
                    if (application == OrderServiceApplication.class) {
                        context.addApplicationListener(event -> {
                            if (event instanceof PayloadApplicationEvent<?> payload) {
                                switch (payload.getPayload()) {
                                    case ErrandCompletedEvent completed ->
                                        terminalEventOrders.add(
                                                completed.payload().errandId());
                                    case ErrandCancelledEvent cancelled ->
                                        terminalEventOrders.add(
                                                cancelled.payload().errandId());
                                    case ErrandExpiredEvent expired ->
                                        terminalEventOrders.add(
                                                expired.payload().errandId());
                                    default -> {}
                                }
                            }
                        });
                    }
                    if (application == CreditServiceApplication.class) {
                        context.addApplicationListener(event -> {
                            if (event instanceof PayloadApplicationEvent<?> payload
                                    && payload.getPayload()
                                            instanceof
                                            com.cs3219.foc.credit.model.dto.event.CreditSettlementResultEvent result) {
                                settlementResults.add(result);
                            }
                        });
                    }
                    context.getBeanFactory()
                            .registerSingleton("testJwtDecoder", (JwtDecoder) token -> Jwt.withTokenValue(token)
                                    .header("alg", "none")
                                    .subject(token)
                                    .claim("roles", List.of("USER"))
                                    .build());
                    context.getBeanFactory().addBeanPostProcessor(new BeanPostProcessor() {
                        @Override
                        public Object postProcessBeforeInitialization(Object bean, String name) {
                            if (bean instanceof CachingConnectionFactory factory) factory.setExecutor(executor);
                            return bean;
                        }

                        @Override
                        @SuppressWarnings("unchecked")
                        public Object postProcessAfterInitialization(Object bean, String name) {
                            if (application == OrderServiceApplication.class && bean instanceof CreditClient client) {
                                CreditClient controlled = spy(client);
                                doAnswer(call -> {
                                            CreditReservationResponse response =
                                                    (CreditReservationResponse) call.callRealMethod();
                                            ReservationPause pause = reservationPause.get();
                                            if (pause != null && pause.claimed.compareAndSet(false, true)) {
                                                pause.waiting.countDown();
                                                assertThat(pause.release.await(30, TimeUnit.SECONDS))
                                                        .isTrue();
                                            }
                                            return response;
                                        })
                                        .when(controlled)
                                        .reserve(any());
                                return controlled;
                            }
                            if (application == OrderServiceApplication.class && name.equals("creditSettlementResult")) {
                                var consumer =
                                        (Consumer<com.cs3219.foc.order.model.dto.event.CreditSettlementResultEvent>)
                                                bean;
                                return (Consumer<com.cs3219.foc.order.model.dto.event.CreditSettlementResultEvent>)
                                        event -> {
                                            consumer.accept(event);
                                            handledSettlementResults.add(event.eventId());
                                        };
                            }
                            return bean;
                        }
                    });
                })
                .run(args.toArray(String[]::new));
    }

    @AfterAll
    void cleanup() throws Exception {
        if (order != null) order.close();
        if (credit != null) credit.close();
        if (directory != null) directory.stop(0);
        if (channel != null && channel.isOpen()) {
            for (var binding : destinations.keySet()) {
                channel.queueDelete(destination(binding) + "." + group(binding));
                channel.queueDelete(destination(binding) + "." + group(binding) + ".dlq");
                channel.exchangeDelete(destination(binding));
                channel.exchangeDelete(destination(binding) + ".dlx");
            }
            channel.close();
        }
        if (connection != null) connection.close();
        if (orders != null) orders.execute("DROP SCHEMA IF EXISTS " + orderSchema + " CASCADE");
        if (credits != null) credits.execute("DROP SCHEMA IF EXISTS " + creditSchema + " CASCADE");
        executor.shutdown();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
}
