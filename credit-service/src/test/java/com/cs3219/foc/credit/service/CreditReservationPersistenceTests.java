package com.cs3219.foc.credit.service;

import static org.assertj.core.api.Assertions.*;

import com.cs3219.foc.credit.config.CreditProperties;
import com.cs3219.foc.credit.exception.*;
import com.cs3219.foc.credit.model.dto.*;
import com.cs3219.foc.credit.model.dto.event.*;
import com.cs3219.foc.credit.repository.CreditAccountRepository;
import com.cs3219.foc.credit.security.AuthenticatedUserResolver;
import com.cs3219.foc.credit.security.AuthenticationContextService;
import com.cs3219.foc.credit.security.CurrentUserService;
import jakarta.persistence.EntityManagerFactory;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

@SpringJUnitConfig(CreditReservationPersistenceTests.TestConfig.class)
@TestPropertySource(properties = "credits.initial-allocation=15")
@EnabledIfEnvironmentVariable(named = "FOC_TEST_DATABASE_URL", matches = ".+")
class CreditReservationPersistenceTests {
    private static final String SCHEMA =
            "credit_rewards_test_" + UUID.randomUUID().toString().replace("-", "");

    @Autowired
    private CreditReservationService reserve;

    @Autowired
    private CreditSettlementService settlement;

    @Autowired
    private CreditAccountService creditAccountService;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private TestClock clock;

    @Autowired
    private ReservationResults results;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        clock.time.set(Instant.parse("2030-01-01T00:00:00Z"));
        results.events.clear();
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("TRUNCATE credit_accounts CASCADE");
    }

    @AfterAll
    static void dropSchema() throws Exception {
        try (var c = DriverManager.getConnection(
                        System.getenv("FOC_TEST_DATABASE_URL"),
                        System.getenv("FOC_TEST_DATABASE_USERNAME"),
                        System.getenv("FOC_TEST_DATABASE_PASSWORD"));
                var s = c.createStatement()) {
            s.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }

    private UUID fund(long amount) {
        var user = UUID.randomUUID();
        creditAccountService.initializeAccount(user);
        jdbc.update("UPDATE credit_accounts SET total_balance = ? WHERE user_id = ?", amount, user);
        return user;
    }

    private ReserveCreditRequest request(long amount) {
        return new ReserveCreditRequest(UUID.randomUUID(), UUID.randomUUID(), amount);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private void concurrent(Runnable first, Runnable second) throws Exception {
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                first.run();
                return true;
            });
            var b = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                second.run();
                return true;
            });
            assertThat(a.get(20, TimeUnit.SECONDS)).isTrue();
            assertThat(b.get(20, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void concurrentReservationsCannotOverspend() throws Exception {
        var user = fund(100);
        concurrent(() -> reserve.reserve(user, request(80)), () -> reserve.reserve(user, request(80)));
        assertThat(creditAccountService.getBalance(user)).isEqualTo(new CreditBalanceResponse(20, 80));
        assertThat(jdbc.queryForList("SELECT state FROM credit_reservations", String.class))
                .containsExactlyInAnyOrder("RESERVED", "INSUFFICIENT_BALANCE");
        assertThat(count("credit_transaction_log")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT total_balance FROM credit_accounts", Long.class))
                .isEqualTo(100);
    }

    @Test
    void concurrentDuplicateRequestsReserveAndAuditOnce() throws Exception {
        var user = fund(100);
        var request = request(80);
        concurrent(() -> reserve.reserve(user, request), () -> reserve.reserve(user, request));
        assertThat(reserve.reserve(user, request).outcome()).isEqualTo("RESERVED");
        assertThat(creditAccountService.getBalance(user)).isEqualTo(new CreditBalanceResponse(20, 80));
        assertThat(count("credit_reservations")).isEqualTo(1);
        assertThat(count("credit_transaction_log")).isEqualTo(1);
    }

    @Test
    void changedRequestOrReusedOperationCannotReserveAgain() {
        var user = fund(100);
        var other = fund(100);
        var request = request(80);
        reserve.reserve(user, request);
        assertThatThrownBy(() ->
                        reserve.reserve(user, new ReserveCreditRequest(request.operationId(), request.errandId(), 70)))
                .isInstanceOf(ReservationConflictException.class);
        assertThatThrownBy(() -> reserve.reserve(other, request)).isInstanceOf(ReservationConflictException.class);
        assertThatThrownBy(() ->
                        reserve.reserve(user, new ReserveCreditRequest(UUID.randomUUID(), request.errandId(), 80)))
                .isInstanceOf(ReservationConflictException.class);
        assertThatThrownBy(() ->
                        reserve.reserve(user, new ReserveCreditRequest(request.operationId(), UUID.randomUUID(), 80)))
                .isInstanceOf(ReservationConflictException.class);
        assertThat(count("credit_transaction_log")).isEqualTo(1);
    }

    @Test
    void rejectedDecisionRemainsRejectedAfterFunding() {
        var user = fund(0);
        var request = request(80);
        assertThat(reserve.reserve(user, request).outcome()).isEqualTo("INSUFFICIENT_BALANCE");
        jdbc.update("UPDATE credit_accounts SET total_balance=100 WHERE user_id=?", user);
        assertThat(reserve.reserve(user, request).outcome()).isEqualTo("INSUFFICIENT_BALANCE");
        assertThat(creditAccountService.getBalance(user)).isEqualTo(new CreditBalanceResponse(100, 0));
        assertThat(count("credit_transaction_log")).isZero();
    }

    @Test
    void missingAccountFailsWithoutPersistingReservationAndCanBeRetried() {
        var user = UUID.randomUUID();
        var request = request(10);
        assertThatThrownBy(() -> reserve.reserve(user, request)).isInstanceOf(CreditAccountPendingException.class);
        assertThat(count("credit_reservations")).isZero();
        creditAccountService.initializeAccount(user);
        jdbc.update("UPDATE credit_accounts SET total_balance=20 WHERE user_id=?", user);
        assertThat(reserve.reserve(user, request).outcome()).isEqualTo("RESERVED");
    }

    @Test
    void failedAuditInsertRollsBackReservation() {
        var user = fund(100);
        var request = request(80);
        jdbc.execute("ALTER TABLE credit_transaction_log ADD CONSTRAINT fail_audit CHECK(FALSE)");
        try {
            assertThatThrownBy(() -> reserve.reserve(user, request)).isInstanceOf(RuntimeException.class);
            assertThat(count("credit_reservations")).isZero();
            assertThat(creditAccountService.getBalance(user)).isEqualTo(new CreditBalanceResponse(100, 0));
        } finally {
            jdbc.execute("ALTER TABLE credit_transaction_log DROP CONSTRAINT fail_audit");
        }
        assertThat(reserve.reserve(user, request).outcome()).isEqualTo("RESERVED");
    }

    @Test
    void balanceSumsOnlyActiveReservations() {
        var user = fund(100);
        var first = request(20);
        var second = request(30);
        reserve.reserve(user, first);
        reserve.reserve(user, second);
        reserve.reserve(user, request(90));
        assertThat(creditAccountService.getBalance(user)).isEqualTo(new CreditBalanceResponse(50, 50));
        settlement.releaseReservedCredits(new ErrandCancelledEvent(
                UUID.randomUUID(),
                "ErrandCancelledEvent",
                1,
                OffsetDateTime.now(),
                new ErrandCancelledEvent.Payload(first.errandId(), user, 20)));
        assertThat(creditAccountService.getBalance(user)).isEqualTo(new CreditBalanceResponse(70, 30));
        assertThat(reserve.reserve(user, first).outcome()).isEqualTo("RESERVED");
        assertThat(count("credit_transaction_log")).isEqualTo(3);
    }

    private ErrandCreationStartedEvent creation(UUID user, long amount, Duration remaining) {
        var now = OffsetDateTime.now(clock);
        var errandId = UUID.randomUUID();
        return new ErrandCreationStartedEvent(
                errandId,
                "ErrandCreationStartedEvent",
                1,
                now,
                new ErrandCreationStartedEvent.Payload(errandId, user, amount, now.plus(remaining)));
    }

    @Test
    void readyAccountCreationReservesOnceAndReplaysItsResult() {
        var user = fund(15);
        var event = creation(user, 5, Duration.ofSeconds(30));
        reserve.reserveForCreation(event);
        clock.time.set(clock.instant().plusSeconds(40));
        reserve.reserveForCreation(event);
        assertThat(results.events).extracting(e -> e.payload().outcome()).containsExactly("RESERVED", "RESERVED");
        assertThat(results.events).extracting(e -> e.payload().operationId()).containsOnly(event.eventId());
        assertThat(count("credit_reservations")).isEqualTo(1);
        assertThat(count("credit_transaction_log")).isEqualTo(1);
        assertThat(creditAccountService.getBalance(user)).isEqualTo(new CreditBalanceResponse(10, 5));
    }

    @Test
    void missingAccountInAnAdmittedMessageIsAnErrorEvenAfterCutoff() {
        for (var remaining : List.of(Duration.ofSeconds(30), Duration.ZERO)) {
            var event = creation(UUID.randomUUID(), 5, remaining);
            assertThatThrownBy(() -> reserve.reserveForCreation(event))
                    .isInstanceOf(CreditAccountPendingException.class);
        }
        assertThat(results.events).isEmpty();
        assertThat(count("credit_reservations")).isZero();
        assertThat(count("credit_transaction_log")).isZero();
    }

    @Test
    void healthyAccountWithQueuedExpiredRequestDoesNotReserve() {
        var user = fund(100);
        var event = creation(user, 5, Duration.ZERO);
        reserve.reserveForCreation(event);
        assertThat(results.events.getFirst().payload().outcome()).isEqualTo("CREATION_TIMEOUT");
        assertThat(count("credit_reservations")).isZero();
        assertThat(creditAccountService.getBalance(user)).isEqualTo(new CreditBalanceResponse(100, 0));
    }

    @Test
    void admissionBeforeCutoffCanCommitAfterCutoff() {
        var user = fund(100);
        var event = creation(user, 5, Duration.ofMillis(1));
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            reserve.reserveForCreation(event);
            clock.time.set(clock.instant().plusSeconds(1));
        });
        assertThat(results.events.getFirst().payload().outcome()).isEqualTo("RESERVED");
        assertThat(count("credit_reservations")).isEqualTo(1);
    }

    @Test
    void waitingForAccountLockPastCutoffTimesOutNewReservation() throws Exception {
        var user = fund(100);
        var event = creation(user, 5, Duration.ofSeconds(30));
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var holder = executor.submit(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                    jdbc.queryForObject(
                            "SELECT user_id FROM credit_accounts WHERE user_id=? FOR UPDATE", UUID.class, user);
                    locked.countDown();
                    try {
                        assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException e) {
                        throw new IllegalStateException(e);
                    }
                });
            });
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            var waiting = executor.submit(() -> reserve.reserveForCreation(event));
            try {
                assertThatThrownBy(() -> waiting.get(200, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
                clock.time.set(clock.instant().plusSeconds(31));
            } finally {
                release.countDown();
            }
            holder.get(10, TimeUnit.SECONDS);
            waiting.get(10, TimeUnit.SECONDS);
        }
        assertThat(results.events.getFirst().payload().outcome()).isEqualTo("CREATION_TIMEOUT");
        assertThat(count("credit_reservations")).isZero();
    }

    @Test
    void replayAfterCutoffWaitsForUncommittedOriginalDecision() throws Exception {
        var user = fund(100);
        var event = creation(user, 5, Duration.ofSeconds(30));
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var holder = executor.submit(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                    reserve.reserve(
                            user,
                            new ReserveCreditRequest(
                                    event.eventId(), event.payload().errandId(), 5));
                    locked.countDown();
                    try {
                        assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException e) {
                        throw new IllegalStateException(e);
                    }
                });
            });
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            clock.time.set(clock.instant().plusSeconds(31));
            var waiting = executor.submit(() -> reserve.reserveForCreation(event));
            try {
                assertThatThrownBy(() -> waiting.get(200, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
            } finally {
                release.countDown();
            }
            holder.get(10, TimeUnit.SECONDS);
            waiting.get(10, TimeUnit.SECONDS);
        }
        assertThat(results.events.getFirst().payload().outcome()).isEqualTo("RESERVED");
        assertThat(count("credit_transaction_log")).isEqualTo(1);
    }

    static class TestClock extends Clock {
        final AtomicReference<Instant> time = new AtomicReference<>();

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

    static class ReservationResults {
        final List<CreditReservationResultEvent> events = new CopyOnWriteArrayList<>();

        @org.springframework.context.event.EventListener
        public void on(CreditReservationResultEvent event) {
            events.add(event);
        }
    }

    @TestConfiguration
    @EnableConfigurationProperties(CreditProperties.class)
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = CreditAccountRepository.class)
    @Import({
        CreditReservationService.class,
        CreditAccountService.class,
        CreditSettlementService.class,
        CurrentUserService.class,
        AuthenticationContextService.class,
        AuthenticatedUserResolver.class
    })
    static class TestConfig {
        @Bean
        TestClock clock() {
            return new TestClock();
        }

        @Bean
        ReservationResults results() {
            return new ReservationResults();
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
                            + "/credit-service/src/main/resources/db/migration")
                    .load()
                    .migrate();
            return source;
        }

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(source);
            factory.setPackagesToScan("com.cs3219.foc.credit.model.entity");
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
