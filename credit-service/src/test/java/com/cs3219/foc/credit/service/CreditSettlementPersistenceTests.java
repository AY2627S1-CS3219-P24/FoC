package com.cs3219.foc.credit.service;

import static org.assertj.core.api.Assertions.*;

import com.cs3219.foc.credit.exception.*;
import com.cs3219.foc.credit.model.dto.*;
import com.cs3219.foc.credit.model.dto.event.*;
import com.cs3219.foc.credit.repository.CreditAccountRepository;
import jakarta.persistence.EntityManagerFactory;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.context.event.EventListener;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringJUnitConfig(CreditSettlementPersistenceTests.TestConfig.class)
@EnabledIfEnvironmentVariable(named = "FOC_TEST_DATABASE_URL", matches = ".+")
class CreditSettlementPersistenceTests {
    private static final String SCHEMA =
            "credit_rewards_test_" + UUID.randomUUID().toString().replace("-", "");

    @Autowired
    private CreditReservationService reserve;

    @Autowired
    private CreditSettlementService settlement;

    @Autowired
    private CreditAccountService accounts;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
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
        accounts.initializeAccount(user);
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

    @Autowired
    private Results results;

    @BeforeEach
    void clearResults() {
        results.events.clear();
    }

    static class Results {
        final CopyOnWriteArrayList<CreditSettlementResultEvent> events = new CopyOnWriteArrayList<>();

        @EventListener
        public void on(CreditSettlementResultEvent event) {
            events.add(event);
        }
    }

    private ErrandSettlementEvent event(UUID user, ReserveCreditRequest r, String type, UUID courier) {
        return new ErrandSettlementEvent(
                UUID.randomUUID(),
                type,
                1,
                OffsetDateTime.now(),
                new ErrandSettlementEvent.Payload(
                        UUID.randomUUID(), r.operationId(), r.errandId(), user, r.amount(), courier));
    }

    private ReserveCreditRequest reserved(UUID user, long amount) {
        var r = request(amount);
        assertThat(reserve.reserve(user, r).outcome()).isEqualTo("RESERVED");
        return r;
    }

    private String outcome() {
        return results.events.getLast().payload().outcome();
    }

    @Test
    void cancellationAndExpiryRefundOnceIncludingDifferentOperations() throws Exception {
        var user = fund(100);
        var r = reserved(user, 80);
        var cancel = event(user, r, "ErrandCancelledEvent", null);
        concurrent(
                () -> settlement.settle(cancel), () -> settlement.settle(event(user, r, "ErrandExpiredEvent", null)));
        settlement.settle(cancel);
        assertThat(accounts.getBalance(user)).isEqualTo(new CreditBalanceResponse(100, 0));
        assertThat(count("credit_transaction_log")).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT transaction_type FROM credit_transaction_log", String.class))
                .containsExactlyInAnyOrder("RESERVE", "REFUND");
        assertThat(reserve.reserve(user, r).outcome()).isEqualTo("RESERVED");
    }

    @Test
    void payoutTransfersOnceAndRejectsDifferentCourierOrTerminalEffect() {
        var user = fund(100);
        var courier = fund(7);
        var other = fund(0);
        var r = reserved(user, 80);
        var paid = event(user, r, "ErrandCompletedEvent", courier);
        settlement.settle(paid);
        settlement.settle(paid);
        assertThat(outcome()).isEqualTo("PAID");
        settlement.settle(event(user, r, "ErrandCompletedEvent", courier));
        assertThat(outcome()).isEqualTo("PAID");
        settlement.settle(event(user, r, "ErrandCompletedEvent", other));
        assertThat(outcome()).isEqualTo("TERMINAL_CONFLICT");
        settlement.settle(event(user, r, "ErrandCancelledEvent", null));
        assertThat(outcome()).isEqualTo("TERMINAL_CONFLICT");
        assertThat(accounts.getBalance(user)).isEqualTo(new CreditBalanceResponse(20, 0));
        assertThat(accounts.getBalance(courier)).isEqualTo(new CreditBalanceResponse(87, 0));
        assertThat(accounts.getBalance(other)).isEqualTo(new CreditBalanceResponse(0, 0));
        assertThat(count("credit_transaction_log")).isEqualTo(2);
    }

    @Test
    void missingCourierRollsBackThenQueueRedeliveryCanSucceed() {
        var user = fund(100);
        var courier = UUID.randomUUID();
        var r = reserved(user, 80);
        var paid = event(user, r, "ErrandCompletedEvent", courier);
        assertThatThrownBy(() -> settlement.settle(paid)).isInstanceOf(CreditAccountPendingException.class);
        assertThat(results.events).isEmpty();
        assertThat(count("credit_transaction_log")).isEqualTo(1);
        assertThat(accounts.getBalance(user)).isEqualTo(new CreditBalanceResponse(20, 80));
        accounts.initializeAccount(courier);
        settlement.settle(paid);
        assertThat(accounts.getBalance(courier)).isEqualTo(new CreditBalanceResponse(95, 0));
    }

    @Test
    void settlementBeforeReservationRetriesAndRejectedReservationHasNoEffect() {
        var user = fund(100);
        var r = request(80);
        var cancel = event(user, r, "ErrandCancelledEvent", null);
        assertThatThrownBy(() -> settlement.settle(cancel)).isInstanceOf(IllegalStateException.class);
        reserve.reserve(user, r);
        settlement.settle(cancel);
        assertThat(outcome()).isEqualTo("REFUNDED");
        var rejected = request(200);
        reserve.reserve(user, rejected);
        settlement.settle(event(user, rejected, "ErrandCancelledEvent", null));
        assertThat(outcome()).isEqualTo("NO_RESERVATION");
        assertThat(accounts.getBalance(user)).isEqualTo(new CreditBalanceResponse(100, 0));
        assertThat(count("credit_transaction_log")).isEqualTo(2);
    }

    @Test
    void mismatchedReservationCannotMoveCredits() {
        var user = fund(100);
        var r = reserved(user, 80);
        var wrong = new ReserveCreditRequest(UUID.randomUUID(), r.errandId(), 80);
        settlement.settle(event(user, wrong, "ErrandCancelledEvent", null));
        assertThat(outcome()).isEqualTo("RESERVATION_MISMATCH");
        settlement.settle(
                event(user, new ReserveCreditRequest(r.operationId(), r.errandId(), 70), "ErrandCancelledEvent", null));
        assertThat(outcome()).isEqualTo("RESERVATION_MISMATCH");
        settlement.settle(event(UUID.randomUUID(), r, "ErrandCancelledEvent", null));
        assertThat(outcome()).isEqualTo("RESERVATION_MISMATCH");
        assertThat(accounts.getBalance(user)).isEqualTo(new CreditBalanceResponse(20, 80));
        assertThat(count("credit_transaction_log")).isEqualTo(1);
    }

    @Test
    void correctedRejectedOperationPublishesDistinctResultIdentity() {
        var user = fund(100);
        var r = reserved(user, 80);
        var corrected = event(user, r, "ErrandCancelledEvent", null);
        var rejected = new ErrandSettlementEvent(
                UUID.randomUUID(),
                corrected.eventType(),
                1,
                corrected.occurredAt(),
                new ErrandSettlementEvent.Payload(
                        corrected.payload().operationId(), UUID.randomUUID(), r.errandId(), user, 80, null));

        settlement.settle(rejected);
        var rejectedResult = results.events.getLast();
        assertThat(rejectedResult.payload().outcome()).isEqualTo("RESERVATION_MISMATCH");
        assertThat(accounts.getBalance(user)).isEqualTo(new CreditBalanceResponse(20, 80));

        settlement.settle(corrected);
        var correctedResult = results.events.getLast();
        assertThat(correctedResult.payload().outcome()).isEqualTo("REFUNDED");
        assertThat(correctedResult.payload().operationId())
                .isEqualTo(rejectedResult.payload().operationId());
        assertThat(correctedResult.eventId()).isNotEqualTo(rejectedResult.eventId());
        assertThat(accounts.getBalance(user)).isEqualTo(new CreditBalanceResponse(100, 0));
        assertThat(count("credit_transaction_log")).isEqualTo(2);
    }

    @Test
    void completedOperationIdCannotBeReusedForAnotherReservation() {
        var user = fund(100);
        var r = reserved(user, 40);
        var second = reserved(user, 40);
        var cancel = event(user, r, "ErrandCancelledEvent", null);
        settlement.settle(cancel);
        var changed = new ErrandSettlementEvent(
                UUID.randomUUID(),
                cancel.eventType(),
                1,
                cancel.occurredAt(),
                new ErrandSettlementEvent.Payload(
                        cancel.payload().operationId(), second.operationId(), second.errandId(), user, 40, null));
        assertThatThrownBy(() -> settlement.settle(changed)).isInstanceOf(IllegalArgumentException.class);
        var wrongReservation = new ErrandSettlementEvent(
                UUID.randomUUID(),
                cancel.eventType(),
                1,
                cancel.occurredAt(),
                new ErrandSettlementEvent.Payload(
                        cancel.payload().operationId(), UUID.randomUUID(), r.errandId(), user, 40, null));
        assertThatThrownBy(() -> settlement.settle(wrongReservation)).isInstanceOf(IllegalArgumentException.class);
        assertThat(accounts.getBalance(user)).isEqualTo(new CreditBalanceResponse(60, 40));
    }

    @Test
    void recipientWriteFailureRollsBackDebitStateAndAudit() {
        var user = fund(100);
        var courier = fund(0);
        var paid = event(user, reserved(user, 80), "ErrandCompletedEvent", courier);
        jdbc.execute("ALTER TABLE credit_accounts ADD CONSTRAINT fail_recipient CHECK(user_id <> '" + courier
                + "'::uuid OR total_balance=0)");
        try {
            assertThatThrownBy(() -> settlement.settle(paid)).isInstanceOf(RuntimeException.class);
            assertThat(accounts.getBalance(user)).isEqualTo(new CreditBalanceResponse(20, 80));
            assertThat(accounts.getBalance(courier)).isEqualTo(new CreditBalanceResponse(0, 0));
            assertThat(count("credit_transaction_log")).isEqualTo(1);
            assertThat(results.events).isEmpty();
        } finally {
            jdbc.execute("ALTER TABLE credit_accounts DROP CONSTRAINT fail_recipient");
        }
        settlement.settle(paid);
        assertThat(outcome()).isEqualTo("PAID");
    }

    @Test
    void auditFailureRollsBackRefund() {
        var user = fund(100);
        var cancel = event(user, reserved(user, 80), "ErrandCancelledEvent", null);
        jdbc.execute("ALTER TABLE credit_transaction_log ADD CONSTRAINT fail_refund CHECK(transaction_type='RESERVE')");
        try {
            assertThatThrownBy(() -> settlement.settle(cancel)).isInstanceOf(RuntimeException.class);
            assertThat(accounts.getBalance(user)).isEqualTo(new CreditBalanceResponse(20, 80));
            assertThat(count("credit_transaction_log")).isEqualTo(1);
        } finally {
            jdbc.execute("ALTER TABLE credit_transaction_log DROP CONSTRAINT fail_refund");
        }
        settlement.settle(cancel);
        assertThat(accounts.getBalance(user)).isEqualTo(new CreditBalanceResponse(100, 0));
    }

    @Test
    void concurrentRefundAndPaymentHaveOneTerminalEffect() throws Exception {
        var user = fund(100);
        var courier = fund(0);
        var r = reserved(user, 80);
        concurrent(
                () -> settlement.settle(event(user, r, "ErrandCancelledEvent", null)),
                () -> settlement.settle(event(user, r, "ErrandCompletedEvent", courier)));
        assertThat(results.events.stream().map(e -> e.payload().outcome()).toList())
                .contains("TERMINAL_CONFLICT");
        assertThat(accounts.getBalance(user).reservedBalance()).isZero();
        assertThat(accounts.getBalance(user).availableBalance()
                        + accounts.getBalance(courier).availableBalance())
                .isEqualTo(100);
        assertThat(count("credit_transaction_log")).isEqualTo(2);
    }

    @Test
    void oppositePaymentsUseConsistentAccountLockOrder() throws Exception {
        var a = fund(100);
        var b = fund(100);
        var r = reserved(a, 80);
        var s = reserved(b, 30);
        concurrent(
                () -> settlement.settle(event(a, r, "ErrandCompletedEvent", b)),
                () -> settlement.settle(event(b, s, "ErrandCompletedEvent", a)));
        assertThat(accounts.getBalance(a)).isEqualTo(new CreditBalanceResponse(50, 0));
        assertThat(accounts.getBalance(b)).isEqualTo(new CreditBalanceResponse(150, 0));
        assertThat(count("credit_transaction_log")).isEqualTo(4);
    }

    @TestConfiguration
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = CreditAccountRepository.class)
    @Import({CreditReservationService.class, CreditAccountService.class, CreditSettlementService.class})
    static class TestConfig {
        @Bean
        Results results() {
            return new Results();
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
