package com.cs3219.foc.order.integration;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import com.cs3219.foc.credit.CreditServiceApplication;
import com.cs3219.foc.credit.service.CreditAccountService;
import com.cs3219.foc.order.OrderServiceApplication;
import com.cs3219.foc.order.model.dto.event.ErrandCreationStartedEvent;
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
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
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
    private final List<com.cs3219.foc.credit.model.dto.event.CreditReservationResultEvent> reservationResults =
            new CopyOnWriteArrayList<>();
    private final Set<UUID> handledReservationResults = ConcurrentHashMap.newKeySet();
    private final Map<String, String> destinations = Map.of(
            "errandCreationStarted",
            "creation",
            "errandCompleted",
            "completed",
            "errandCancelled",
            "cancelled",
            "errandExpired",
            "expired",
            "creditReservationResult",
            "reserved",
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
        creditArgs.add(
                "--spring.cloud.function.definition=errandCreationStarted;errandCancelled;errandExpired;errandCompleted");
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
        var created = request("POST", "", requester, body, 202);
        var id = UUID.fromString(created.get("errandId").asText());
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

        // Re-deliver creation after payment: Credit returns its original decision; Order stays completed.
        var previousResults = reservationResults.stream()
                .map(com.cs3219.foc.credit.model.dto.event.CreditReservationResultEvent::eventId)
                .collect(Collectors.toSet());
        var createdAt = OffsetDateTime.parse(details.get("createdAt").asText());
        publish(
                "errandCreationStarted",
                new ErrandCreationStartedEvent(
                        id,
                        "ErrandCreationStartedEvent",
                        1,
                        createdAt,
                        new ErrandCreationStartedEvent.Payload(id, requester, 5, createdAt.plusSeconds(4))));
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            var repeatedResult = reservationResults.stream()
                    .filter(result ->
                            result.payload().operationId().equals(id) && !previousResults.contains(result.eventId()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Credit has not emitted the duplicate result"));
            // The real result is published after Credit commits; the test consumer signals after Order commits.
            assertThat(handledReservationResults).contains(repeatedResult.eventId());
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

        // A registry insert failure rolls back the order itself and sends no creation message.
        var rollbackKey = UUID.randomUUID();
        orders.execute("ALTER TABLE event_publication ADD CONSTRAINT reject_publication CHECK(FALSE) NOT VALID");
        try {
            request("POST", "", requester, creationBody(rollbackKey, 1), 500);
            assertThat(orders.queryForObject(
                            "SELECT count(*) FROM errands WHERE create_request_id=?", Long.class, rollbackKey))
                    .isZero();
        } finally {
            orders.execute("ALTER TABLE event_publication DROP CONSTRAINT reject_publication");
        }
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
                request("POST", "", newUser, body, 202).get("errandId").asText());
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
        var rejected = UUID.fromString(request("POST", "", poor, creationBody(UUID.randomUUID(), 2), 202)
                .get("errandId")
                .asText());
        awaitOrder(rejected, "CREATE_FAILED", null);
        assertThat(orders.queryForObject(
                        "SELECT creation_failure_code FROM errands WHERE id=?", String.class, rejected))
                .isEqualTo("INSUFFICIENT_BALANCE");
        assertNoPendingPublicationsOrDeadLetters();
    }

    @Test
    void creationAndReservationResultPublicationsRecoverWithoutRepeatingTheReservation() throws Exception {
        UUID requester = account(100);
        // Establish both dynamic producer bindings before temporarily removing their routes.
        createOpen(requester, 1);
        String creationDestination = destination("errandCreationStarted");
        String creationQueue = creationDestination + "." + group("errandCreationStarted");
        String resultDestination = destination("creditReservationResult");
        String resultQueue = resultDestination + "." + group("creditReservationResult");
        try {
            channel.queueUnbind(creationQueue, creationDestination, "#");
            UUID id = UUID.fromString(request("POST", "", requester, creationBody(UUID.randomUUID(), 5), 202)
                    .get("errandId")
                    .asText());
            awaitFailedPublication(orders, "order-creation-rabbit", id);
            awaitOrder(id, "CREATING", null);
            assertThat(credits.queryForObject(
                            "SELECT count(*) FROM credit_reservations WHERE errand_id=?", Long.class, id))
                    .isZero();

            // Let Order recover its publication, while Credit's successful result cannot yet reach Order.
            channel.queueUnbind(resultQueue, resultDestination, "#");
            channel.queueBind(creationQueue, creationDestination, "#");
            awaitFailedPublication(credits, "credit-reservation-rabbit", id);
            assertThat(credits.queryForObject(
                            "SELECT state FROM credit_reservations WHERE errand_id=?", String.class, id))
                    .isEqualTo("RESERVED");
            awaitOrder(id, "CREATING", null);

            // Both services' ordinary recovery jobs retry the saved events after the routes return.
            channel.queueBind(resultQueue, resultDestination, "#");
            awaitOrder(id, "OPEN", "RESERVED");
            assertThat(credits.queryForObject(
                            "SELECT count(*) FROM credit_reservations WHERE errand_id=?", Long.class, id))
                    .isEqualTo(1);
            assertThat(credits.queryForObject(
                            "SELECT count(*) FROM credit_transaction_log WHERE errand_id=?", Long.class, id))
                    .isEqualTo(1);
            assertThat(balance(requester, "total_balance")).isEqualTo(100);
            assertThat(balance(requester, "reserved_balance")).isEqualTo(6);
            assertNoPendingPublicationsOrDeadLetters();
        } finally {
            channel.queueBind(creationQueue, creationDestination, "#");
            channel.queueBind(resultQueue, resultDestination, "#");
        }
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
        var id = UUID.fromString(request("POST", "", requester, creationBody(UUID.randomUUID(), amount), 202)
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
            case "errandCreationStarted" -> "credit-reservations";
            case "creditReservationResult" -> "order-credit-reservations";
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
                    if (application == CreditServiceApplication.class) {
                        context.addApplicationListener(event -> {
                            if (event instanceof PayloadApplicationEvent<?> payload
                                    && payload.getPayload()
                                            instanceof
                                            com.cs3219.foc.credit.model.dto.event.CreditReservationResultEvent result) {
                                reservationResults.add(result);
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
                            if (application == OrderServiceApplication.class
                                    && name.equals("creditReservationResult")) {
                                var consumer =
                                        (Consumer<com.cs3219.foc.order.model.dto.event.CreditReservationResultEvent>)
                                                bean;
                                return (Consumer<com.cs3219.foc.order.model.dto.event.CreditReservationResultEvent>)
                                        event -> {
                                            consumer.accept(event);
                                            handledReservationResults.add(event.eventId());
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
