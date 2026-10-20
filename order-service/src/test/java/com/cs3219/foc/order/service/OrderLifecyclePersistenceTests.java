package com.cs3219.foc.order.service;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.cs3219.foc.order.config.OrderProperties;
import com.cs3219.foc.order.exception.CreditAccountPendingException;
import com.cs3219.foc.order.exception.OrderConflictException;
import com.cs3219.foc.order.infrastructure.client.*;
import com.cs3219.foc.order.mapper.ErrandMapper;
import com.cs3219.foc.order.model.dto.*;
import com.cs3219.foc.order.model.dto.event.*;
import com.cs3219.foc.order.repository.ErrandRepository;
import com.cs3219.foc.order.security.*;
import jakarta.persistence.EntityManagerFactory;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mapstruct.factory.Mappers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@SpringJUnitConfig(OrderLifecyclePersistenceTests.TestConfig.class)
@EnabledIfEnvironmentVariable(named = "FOC_TEST_DATABASE_URL", matches = ".+")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrderLifecyclePersistenceTests {
    private static final String SCHEMA =
            "orders_test_" + UUID.randomUUID().toString().replace("-", "");
    private static final Instant START = Instant.parse("2030-01-01T00:00:00Z");

    @Autowired
    private OrderService service;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private SupplierClient suppliers;

    @Autowired
    private CreditClient credit;

    @Autowired
    private UserClient users;

    @Autowired
    private TestClock clock;

    @Autowired
    private Facts facts;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private JdbcTemplate jdbc;
    private UUID requester;

    @BeforeEach
    void prepare() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("TRUNCATE errands, event_publication");
        clock.time.set(START);
        facts.events.clear();
        reset(suppliers, users, credit);
        doAnswer(call -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                            .isFalse();
                    return null;
                })
                .when(credit)
                .requireAccountReady();
        when(credit.reserve(any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isFalse();
            ReserveCreditRequest request = call.getArgument(0);
            assertThat(request.operationId()).isEqualTo(request.errandId());
            assertThat(jdbc.queryForObject("SELECT status FROM errands WHERE id=?", String.class, request.errandId()))
                    .isEqualTo("CREATING");
            return new CreditReservationResponse("RESERVED");
        });
        when(suppliers.get(any()))
                .thenAnswer(call -> new SupplierPickupDto(call.getArgument(0), "Cafe", "COM1", "01", "Lobby", true));
        when(users.find(any())).thenAnswer(call -> ((Set<UUID>) call.getArgument(0))
                .stream().collect(Collectors.toMap(id -> id, id -> new UserBriefDto(id, "User"))));
        requester = UUID.randomUUID();
        as(requester);
    }

    @AfterEach
    void clearActor() {
        SecurityContextHolder.clearContext();
    }

    @AfterAll
    static void cleanupSchema() throws Exception {
        try (var c = DriverManager.getConnection(
                        System.getenv("FOC_TEST_DATABASE_URL"),
                        System.getenv("FOC_TEST_DATABASE_USERNAME"),
                        System.getenv("FOC_TEST_DATABASE_PASSWORD"));
                var st = c.createStatement()) {
            st.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }

    private static void as(UUID id, String... roles) {
        var authorities = Arrays.stream(roles.length == 0 ? new String[] {"USER"} : roles)
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(id.toString(), "n/a", authorities));
    }

    private CreateErrandRequest request() {
        return new CreateErrandRequest(UUID.randomUUID(), UUID.randomUUID(), "COM2", "Lunch", null, 5, 1);
    }

    private UUID open() {
        as(requester);
        var created = service.create(request());
        assertThat(created.status().name()).isEqualTo("OPEN");
        return created.errandId();
    }

    private long count(Class<?> type) {
        return facts.events.stream().filter(type::isInstance).count();
    }

    private CreditSettlementResultEvent pendingSettlement(boolean payout) {
        UUID id = open();
        UUID courier = payout ? UUID.randomUUID() : null;
        if (payout) {
            as(courier);
            service.accept(id);
            service.pickUp(id);
            as(requester);
            service.complete(id);
        } else {
            service.cancel(id);
        }
        UUID operationId = jdbc.queryForObject("SELECT settlement_event_id FROM errands WHERE id=?", UUID.class, id);
        return new CreditSettlementResultEvent(
                UUID.randomUUID(),
                "CreditSettlementResultEvent",
                1,
                OffsetDateTime.now(clock),
                new CreditSettlementResultEvent.Payload(
                        operationId,
                        id,
                        requester,
                        5,
                        payout ? "PAID" : "REFUNDED",
                        payout ? "PAYOUT" : "REFUND",
                        courier));
    }

    @Test
    void unreadyRequesterDoesNotCreateAndCanReuseTheRequestAfterProvisioning() {
        var req = request();
        doThrow(new CreditAccountPendingException()).when(credit).requireAccountReady();
        assertThatThrownBy(() -> service.create(req)).isInstanceOf(CreditAccountPendingException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM errands", Long.class))
                .isZero();
        assertThat(facts.events).isEmpty();
        verifyNoInteractions(suppliers);
        doNothing().when(credit).requireAccountReady();
        var created = service.create(req);
        reset(credit);
        doThrow(new CreditAccountPendingException()).when(credit).requireAccountReady();
        assertThat(service.create(req).errandId()).isEqualTo(created.errandId());
        verifyNoInteractions(credit);
        assertThat(facts.events).isEmpty();
    }

    @Test
    void unreadyCourierDoesNotClaimTheOrderAndCanAcceptAfterProvisioning() {
        var id = open();
        var courier = UUID.randomUUID();
        as(courier);
        doThrow(new CreditAccountPendingException()).when(credit).requireAccountReady();
        assertThatThrownBy(() -> service.accept(id)).isInstanceOf(CreditAccountPendingException.class);
        assertThat(service.get(id).status().name()).isEqualTo("OPEN");
        assertThat(service.get(id).courier()).isNull();
        doNothing().when(credit).requireAccountReady();
        assertThat(service.accept(id).status().name()).isEqualTo("ACCEPTED");
        assertThat(service.get(id).courier().id()).isEqualTo(courier);
    }

    @Test
    void creationIsDurableAndNormalizesNotesWithoutRevalidatingExistingSupplier() {
        var req = request();
        var first = service.create(req);
        assertThat(first.status().name()).isEqualTo("OPEN");
        assertThat(facts.events).isEmpty();
        when(suppliers.get(any())).thenThrow(new AssertionError("Accepted requests must not query Supplier again"));
        var replay = new CreateErrandRequest(
                req.createRequestId(),
                req.supplierId(),
                req.deliveryLocation(),
                req.description(),
                "",
                req.creditReward(),
                req.expiryMinutes());
        assertThat(service.create(replay)).isEqualTo(first);
        verify(credit, times(1)).reserve(any());
        assertThatThrownBy(() -> service.create(new CreateErrandRequest(
                        req.createRequestId(), req.supplierId(), "Changed", req.description(), "", 5, 1)))
                .isInstanceOf(OrderConflictException.class);
    }

    @Test
    void simultaneousSameRequestInsertsAndReservesOnce() throws Exception {
        var barrier = new CyclicBarrier(2);
        when(suppliers.get(any())).thenAnswer(call -> {
            barrier.await(10, TimeUnit.SECONDS);
            return new SupplierPickupDto(call.getArgument(0), "Cafe", "COM1", null, null, true);
        });
        var req = request();
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<CreateErrandResponse> submit = () -> {
                as(requester);
                return service.create(req);
            };
            var a = executor.submit(submit);
            var b = executor.submit(submit);
            assertThat(a.get(20, TimeUnit.SECONDS).errandId())
                    .isEqualTo(b.get(20, TimeUnit.SECONDS).errandId());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM errands", Long.class))
                .isEqualTo(1);
        verify(credit, times(1)).reserve(any());
    }

    @Test
    void inactiveSupplierIsRejectedEvenForAdmin() {
        as(requester, "ADMIN");
        when(suppliers.get(any()))
                .thenAnswer(call -> new SupplierPickupDto(call.getArgument(0), "Cafe", "COM1", null, null, false));
        assertThatThrownBy(() -> service.create(request())).isInstanceOf(ResponseStatusException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM errands", Long.class))
                .isZero();
        verify(credit, never()).reserve(any());
    }

    @Test
    void ownerAndAdminFindInternalCreationRecordsButMarketplaceDoesNot() {
        var pendingRequest = request();
        doThrow(new RestClientException("Connection lost")).when(credit).reserve(any());
        assertThatThrownBy(() -> service.create(pendingRequest)).isInstanceOf(RestClientException.class);
        var pending = jdbc.queryForObject(
                "SELECT id FROM errands WHERE create_request_id=?", UUID.class, pendingRequest.createRequestId());
        doReturn(new CreditReservationResponse("INSUFFICIENT_BALANCE"))
                .when(credit)
                .reserve(any());
        var failed = service.create(request()).errandId();
        assertThat(service.mine("requester", PageRequest.of(0, 20)).items())
                .extracting(ErrandDto::id)
                .containsExactlyInAnyOrder(pending, failed);
        var outsider = UUID.randomUUID();
        as(outsider);
        assertThat(service.list(null, PageRequest.of(0, 20)).items()).isEmpty();
        assertThatThrownBy(() -> service.get(pending)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.list(requester, PageRequest.of(0, 20)))
                .isInstanceOf(AccessDeniedException.class);
        as(outsider, "ADMIN");
        assertThat(service.list(requester, PageRequest.of(0, 20)).items()).hasSize(2);
        assertThat(service.get(failed).creationFailureCode()).isEqualTo("INSUFFICIENT_BALANCE");
    }

    @Test
    void onlyOneCourierCanAcceptAndOwnOrderCannotBeAccepted() throws Exception {
        var id = open();
        assertThatThrownBy(() -> service.accept(id)).isInstanceOf(AccessDeniedException.class);
        var barrier = new CyclicBarrier(2);
        var successes = new AtomicInteger();
        var conflicts = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Void> accept = () -> {
                as(UUID.randomUUID());
                barrier.await(10, TimeUnit.SECONDS);
                try {
                    service.accept(id);
                    successes.incrementAndGet();
                } catch (OrderConflictException expected) {
                    conflicts.incrementAndGet();
                }
                return null;
            };
            var a = executor.submit(accept);
            var b = executor.submit(accept);
            a.get(20, TimeUnit.SECONDS);
            b.get(20, TimeUnit.SECONDS);
        }
        assertThat(successes.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(1);
        assertThat(service.get(id).status().name()).isEqualTo("ACCEPTED");
    }

    @Test
    void acceptAndCancelHaveOnlyOneWinningTransition() throws Exception {
        var id = open();
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var accept = executor.submit(() -> {
                as(UUID.randomUUID());
                barrier.await(10, TimeUnit.SECONDS);
                try {
                    service.accept(id);
                } catch (OrderConflictException expected) {
                }
                return true;
            });
            var cancel = executor.submit(() -> {
                as(requester);
                barrier.await(10, TimeUnit.SECONDS);
                try {
                    service.cancel(id);
                } catch (OrderConflictException expected) {
                }
                return true;
            });
            accept.get(20, TimeUnit.SECONDS);
            cancel.get(20, TimeUnit.SECONDS);
        }
        var state = service.get(id).status().name();
        assertThat(state).isIn("ACCEPTED", "CANCELLED");
        assertThat(count(ErrandCancelledEvent.class)).isEqualTo(state.equals("CANCELLED") ? 1 : 0);
    }

    @Test
    void pickupThenRequesterCompletionIsIdempotentAndPreservesSettlement() {
        var id = open();
        assertThatThrownBy(() -> service.complete(id)).isInstanceOf(OrderConflictException.class);
        var courier = UUID.randomUUID();
        as(courier);
        service.accept(id);
        as(requester);
        assertThatThrownBy(() -> service.pickUp(id)).isInstanceOf(AccessDeniedException.class);
        as(courier);
        service.pickUp(id);
        assertThatThrownBy(() -> service.complete(id)).isInstanceOf(AccessDeniedException.class);
        as(requester);
        service.complete(id);
        var completed = facts.events.stream()
                .filter(ErrandCompletedEvent.class::isInstance)
                .map(ErrandCompletedEvent.class::cast)
                .findFirst()
                .orElseThrow();
        SecurityContextHolder.clearContext();
        var result = new CreditSettlementResultEvent(
                UUID.randomUUID(),
                "CreditSettlementResultEvent",
                1,
                OffsetDateTime.now(clock),
                new CreditSettlementResultEvent.Payload(
                        completed.eventId(), id, requester, 5, "PAID", "PAYOUT", courier));
        service.handleSettlementResult(result);
        service.handleSettlementResult(result);
        as(requester);
        assertThat(service.complete(id).creditState().name()).isEqualTo("SETTLED");
        assertThat(count(ErrandCompletedEvent.class)).isEqualTo(1);
        as(UUID.randomUUID(), "ADMIN");
        assertThatThrownBy(() -> service.complete(id)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void lifecycleActionsSucceedWithoutLookingUpUserNames() {
        var completedId = open();
        var cancelledId = open();
        reset(users);
        when(users.find(any())).thenThrow(new AssertionError("Lifecycle actions must not query names"));

        as(UUID.randomUUID());
        assertThat(service.accept(completedId).errandId()).isEqualTo(completedId);
        assertThat(service.pickUp(completedId).status().name()).isEqualTo("PICKED_UP");
        as(requester);
        var completed = service.complete(completedId);
        assertThat(completed.status().name()).isEqualTo("COMPLETED");
        assertThat(completed.creditState().name()).isEqualTo("SETTLEMENT_PENDING");
        assertThat(service.complete(completedId)).isEqualTo(completed);
        var cancelled = service.cancel(cancelledId);
        assertThat(cancelled.errandId()).isEqualTo(cancelledId);
        assertThat(cancelled.status().name()).isEqualTo("CANCELLED");
        assertThat(cancelled.creditState().name()).isEqualTo("RELEASE_PENDING");
        assertThat(service.cancel(cancelledId)).isEqualTo(cancelled);
        verifyNoInteractions(users);
        assertThat(count(ErrandCompletedEvent.class)).isEqualTo(1);
        assertThat(count(ErrandCancelledEvent.class)).isEqualTo(1);
    }

    @Test
    void concurrentCompletionPublishesOnlyOneFact() throws Exception {
        var id = open();
        as(UUID.randomUUID());
        service.accept(id);
        service.pickUp(id);
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Void> complete = () -> {
                as(requester);
                barrier.await(10, TimeUnit.SECONDS);
                service.complete(id);
                return null;
            };
            var a = executor.submit(complete);
            var b = executor.submit(complete);
            a.get(20, TimeUnit.SECONDS);
            b.get(20, TimeUnit.SECONDS);
        }
        assertThat(count(ErrandCompletedEvent.class)).isEqualTo(1);
    }

    @Test
    void cancellationAndRefundAreIdempotent() {
        var id = open();
        service.cancel(id);
        service.cancel(id);
        var cancelled = facts.events.stream()
                .filter(ErrandCancelledEvent.class::isInstance)
                .map(ErrandCancelledEvent.class::cast)
                .findFirst()
                .orElseThrow();
        var refund = new CreditSettlementResultEvent(
                UUID.randomUUID(),
                "CreditSettlementResultEvent",
                1,
                OffsetDateTime.now(clock),
                new CreditSettlementResultEvent.Payload(
                        cancelled.eventId(), id, requester, 5, "REFUNDED", "REFUND", null));
        service.handleSettlementResult(refund);
        service.handleSettlementResult(refund);
        assertThat(service.cancel(id).creditState().name()).isEqualTo("RELEASED");
        assertThat(count(ErrandCancelledEvent.class)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void mismatchedSettlementResultsDoNotConfirmFunds(boolean payout) {
        var valid = pendingSettlement(payout);
        var original = valid.payload();
        var before = service.get(original.errandId());
        int eventCount = facts.events.size();
        for (String mismatch :
                List.of("operationId", "requesterId", "amount", "courierId", "settlementType", "outcome")) {
            var result = new CreditSettlementResultEvent(
                    UUID.randomUUID(),
                    "CreditSettlementResultEvent",
                    1,
                    OffsetDateTime.now(clock),
                    new CreditSettlementResultEvent.Payload(
                            mismatch.equals("operationId") ? UUID.randomUUID() : original.operationId(),
                            original.errandId(),
                            mismatch.equals("requesterId") ? UUID.randomUUID() : original.requesterId(),
                            mismatch.equals("amount") ? original.amount() + 1 : original.amount(),
                            mismatch.equals("outcome") ? "UNEXPECTED" : original.outcome(),
                            mismatch.equals("settlementType") ? "UNEXPECTED" : original.settlementType(),
                            mismatch.equals("courierId") ? UUID.randomUUID() : original.courierId()));
            assertThatThrownBy(() -> service.handleSettlementResult(result))
                    .as("payout=%s, mismatch=%s", payout, mismatch)
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(service.get(original.errandId())).isEqualTo(before);
            assertThat(facts.events).hasSize(eventCount);
        }
        service.handleSettlementResult(valid);
        assertThat(service.get(original.errandId()).creditState().name()).isEqualTo(payout ? "SETTLED" : "RELEASED");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void businessConflictsLeaveSettlementPendingWithoutPublishingAnotherFact(boolean payout) {
        var valid = pendingSettlement(payout);
        var original = valid.payload();
        var before = service.get(original.errandId());
        int eventCount = facts.events.size();
        for (String outcome : List.of("RESERVATION_MISMATCH", "NO_RESERVATION", "TERMINAL_CONFLICT")) {
            var conflict = new CreditSettlementResultEvent(
                    UUID.randomUUID(),
                    "CreditSettlementResultEvent",
                    1,
                    OffsetDateTime.now(clock),
                    new CreditSettlementResultEvent.Payload(
                            original.operationId(),
                            original.errandId(),
                            original.requesterId(),
                            original.amount(),
                            outcome,
                            original.settlementType(),
                            original.courierId()));
            assertThatCode(() -> service.handleSettlementResult(conflict)).doesNotThrowAnyException();
            assertThat(service.get(original.errandId())).isEqualTo(before);
            assertThat(facts.events).hasSize(eventCount);
        }
        service.handleSettlementResult(valid);
        assertThat(service.get(original.errandId()).creditState().name()).isEqualTo(payout ? "SETTLED" : "RELEASED");
    }

    @Test
    void expiryWaitsForInFlightAcceptanceAndDoesNotExpireTheAcceptedOrder() throws Exception {
        UUID id = open();
        UUID courier = UUID.randomUUID();
        // This test adds an outer transaction only to hold the row lock after acceptance returns.
        doNothing().when(credit).requireAccountReady();
        var acceptedButUncommitted = new CountDownLatch(1);
        var allowCommit = new CountDownLatch(1);
        var acceptingBackend = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var acceptance = executor.submit(() -> {
                as(courier);
                try {
                    // Keep the real acceptance transaction open while expiry sees the previous OPEN row.
                    new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                        service.accept(id);
                        acceptingBackend.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                        acceptedButUncommitted.countDown();
                        try {
                            assertThat(allowCommit.await(10, TimeUnit.SECONDS)).isTrue();
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(exception);
                        }
                    });
                } finally {
                    SecurityContextHolder.clearContext();
                }
            });
            try {
                assertThat(acceptedButUncommitted.await(10, TimeUnit.SECONDS)).isTrue();
                clock.time.set(START.plusSeconds(61));
                var expiry = executor.submit(service::expireOpenOrders);
                // Observe a PostgreSQL lock wait, rather than merely starting two threads together.
                await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                                "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid)))",
                                Boolean.class,
                                acceptingBackend.get()))
                        .isTrue());
                allowCommit.countDown();
                acceptance.get(10, TimeUnit.SECONDS);
                expiry.get(10, TimeUnit.SECONDS);
            } finally {
                allowCommit.countDown();
            }
        }
        var order = service.get(id);
        assertThat(order.status().name()).isEqualTo("ACCEPTED");
        assertThat(order.creditState().name()).isEqualTo("RESERVED");
        assertThat(order.courier().id()).isEqualTo(courier);
        assertThat(count(ErrandExpiredEvent.class)).isZero();
    }

    @Test
    void expiryRejectsLateAcceptanceAndDoesNotExpireAcceptedOrPickedUpOrders() {
        var accepted = open();
        var expired = open();
        var courier = UUID.randomUUID();
        as(courier);
        service.accept(accepted);
        clock.time.set(START.plusSeconds(61));
        assertThatThrownBy(() -> service.accept(expired)).isInstanceOf(OrderConflictException.class);
        SecurityContextHolder.clearContext();
        service.expireOpenOrders();
        as(courier);
        service.pickUp(accepted);
        SecurityContextHolder.clearContext();
        service.expireOpenOrders();
        as(requester);
        assertThat(service.get(accepted).status().name()).isEqualTo("PICKED_UP");
        assertThat(service.get(expired).status().name()).isEqualTo("EXPIRED");
        assertThat(count(ErrandExpiredEvent.class)).isEqualTo(1);
    }

    @Test
    void synchronousReservationFinishingAfterExpiryIsReleasedWithoutOpening() {
        doAnswer(call -> {
                    clock.time.set(START.plusSeconds(61));
                    return new CreditReservationResponse("RESERVED");
                })
                .when(credit)
                .reserve(any());
        var req = request();
        var created = service.create(req);
        assertThat(created.status().name()).isEqualTo("EXPIRED");
        assertThat(service.get(created.errandId()).creditState().name()).isEqualTo("RELEASE_PENDING");
        assertThat(service.create(req)).isEqualTo(created);
        verify(credit, times(1)).reserve(any());
        assertThat(count(ErrandExpiredEvent.class)).isEqualTo(1);
    }

    @Test
    void insufficientBalanceIsReturnedImmediatelyAndItsDecisionIsStable() {
        doReturn(new CreditReservationResponse("INSUFFICIENT_BALANCE"))
                .when(credit)
                .reserve(any());
        var req = request();
        var created = service.create(req);
        assertThat(created.status().name()).isEqualTo("CREATE_FAILED");
        assertThat(created.creationFailureCode()).isEqualTo("INSUFFICIENT_BALANCE");
        assertThat(service.get(created.errandId()).creditState()).isNull();
        assertThat(service.create(req)).isEqualTo(created);
        verify(credit, times(1)).reserve(any());
        assertThat(facts.events).isEmpty();
    }

    @Test
    void uncertainHttpResultRemainsCreatingAndReplayDoesNotReserveAgain() {
        doThrow(new RestClientException("Response lost")).when(credit).reserve(any());
        var req = request();
        assertThatThrownBy(() -> service.create(req)).isInstanceOf(RestClientException.class);
        var pending = service.create(req);
        assertThat(pending.status().name()).isEqualTo("CREATING");
        assertThat(service.get(pending.errandId()).creditState()).isNull();
        assertThat(pending.creationFailureCode()).isNull();
        verify(credit, times(1)).reserve(any());
        assertThat(facts.events).isEmpty();
    }

    @Test
    void successfulReservationFollowedByDatabaseFailureIsNotMistakenForADuplicate() {
        var req = request();
        jdbc.execute("ALTER TABLE errands ADD CONSTRAINT reject_open CHECK(status <> 'OPEN') NOT VALID");
        try {
            assertThatThrownBy(() -> service.create(req)).isInstanceOf(DataIntegrityViolationException.class);
            assertThat(service.create(req).status().name()).isEqualTo("CREATING");
            verify(credit, times(1)).reserve(any());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM errands", Long.class))
                    .isEqualTo(1);
        } finally {
            jdbc.execute("ALTER TABLE errands DROP CONSTRAINT reject_open");
        }
    }

    @Test
    void invalidReservationResponseDoesNotDeclareCreationFailed() {
        var responses =
                Arrays.asList(null, new CreditReservationResponse(null), new CreditReservationResponse("UNKNOWN"));
        for (CreditReservationResponse response : responses) {
            doReturn(response).when(credit).reserve(any());
            var req = request();
            assertThatThrownBy(() -> service.create(req)).isInstanceOf(RestClientException.class);
            assertThat(service.create(req).status().name()).isEqualTo("CREATING");
        }
        assertThat(facts.events).isEmpty();
    }

    @Test
    void databaseRejectsInvalidBaseFields() {
        var id = open();
        assertThatThrownBy(() -> jdbc.update("UPDATE errands SET credit_reward=-1 WHERE id=?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE errands SET courier_id=requester_id WHERE id=?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE errands SET expires_at=created_at WHERE id=?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(service.get(id).status().name()).isEqualTo("OPEN");
    }

    static class TestClock extends Clock {
        final AtomicReference<Instant> time = new AtomicReference<>(START);

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant(), zone);
        }

        @Override
        public Instant instant() {
            return time.get();
        }
    }

    static class Facts {
        final List<Object> events = new CopyOnWriteArrayList<>();

        @EventListener
        public void completed(ErrandCompletedEvent e) {
            events.add(e);
        }

        @EventListener
        public void cancelled(ErrandCancelledEvent e) {
            events.add(e);
        }

        @EventListener
        public void expired(ErrandExpiredEvent e) {
            events.add(e);
        }
    }

    @org.springframework.boot.test.context.TestConfiguration
    @EnableTransactionManagement
    @EnableConfigurationProperties(OrderProperties.class)
    @EnableJpaRepositories(basePackageClasses = ErrandRepository.class)
    @Import({
        OrderService.class,
        CurrentUserService.class,
        AuthenticationContextService.class,
        AuthenticatedUserResolver.class
    })
    static class TestConfig {
        @Bean
        CreditClient creditClient() {
            return mock(CreditClient.class);
        }

        @Bean
        TestClock clock() {
            return new TestClock();
        }

        @Bean
        Facts facts() {
            return new Facts();
        }

        @Bean
        SupplierClient supplierClient() {
            return mock(SupplierClient.class);
        }

        @Bean
        UserClient userClient() {
            return mock(UserClient.class);
        }

        @Bean
        ErrandMapper mapper() {
            return Mappers.getMapper(ErrandMapper.class);
        }

        @Bean
        DataSource dataSource() {
            var url = System.getenv("FOC_TEST_DATABASE_URL");
            var source = new DriverManagerDataSource(
                    url + (url.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA,
                    System.getenv("FOC_TEST_DATABASE_USERNAME"),
                    System.getenv("FOC_TEST_DATABASE_PASSWORD"));
            Flyway.configure()
                    .dataSource(source)
                    .schemas(SCHEMA)
                    .defaultSchema(SCHEMA)
                    .locations("filesystem:" + System.getProperty("foc.root")
                            + "/order-service/src/main/resources/db/migration")
                    .load()
                    .migrate();
            return source;
        }

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(source);
            factory.setPackagesToScan("com.cs3219.foc.order.model.entity");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate"));
            return factory;
        }

        @Bean
        JpaTransactionManager transactionManager(EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }
    }
}
