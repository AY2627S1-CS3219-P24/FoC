package com.cs3219.foc.credit.service;

import static org.assertj.core.api.Assertions.*;

import com.cs3219.foc.credit.config.CreditRewardProperties;
import com.cs3219.foc.credit.model.dto.CreditBalanceResponse;
import com.cs3219.foc.credit.model.dto.event.ErrandSettlementEvent;
import com.cs3219.foc.credit.model.dto.event.ErrandSubmittedEvent;
import com.cs3219.foc.credit.repository.CreditAccountRepository;
import jakarta.persistence.EntityManagerFactory;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringJUnitConfig(CreditSettlementPersistenceTests.TestConfig.class)
@EnabledIfEnvironmentVariable(named = "FOC_TEST_DATABASE_URL", matches = ".+")
class CreditSettlementPersistenceTests {
    private static final String SCHEMA =
            "credit_settle_test_" + UUID.randomUUID().toString().replace("-", "");

    @Autowired
    private CreditReservationService reserve;

    @Autowired
    private CreditSettlementService service;

    @Autowired
    private CreditAccountService accounts;

    @Autowired
    private CreditResultOutboxService outbox;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute(
                "TRUNCATE credit_accounts, credit_reserve_operations, credit_settlement_operations, credit_result_outbox CASCADE");
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

    private ErrandSubmittedEvent event(UUID user, long amount) {
        return new ErrandSubmittedEvent(
                UUID.randomUUID(),
                "ErrandSubmittedEvent",
                1,
                OffsetDateTime.now(),
                new ErrandSubmittedEvent.Payload(UUID.randomUUID(), UUID.randomUUID(), user, amount));
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

    private ErrandSubmittedEvent reserved(UUID user, long amount) {
        var e = event(user, amount);
        reserve.accept(e);
        reserve.processNext();
        // These tests inspect settlement results independently of the already-tested reserve result.
        jdbc.update("UPDATE credit_result_outbox SET published_at = CURRENT_TIMESTAMP");
        return e;
    }

    private ErrandSettlementEvent settlement(ErrandSubmittedEvent e, String type, UUID courier) {
        var p = e.payload();
        return new ErrandSettlementEvent(
                UUID.randomUUID(),
                type,
                1,
                OffsetDateTime.now(),
                new ErrandSettlementEvent.Payload(
                        UUID.randomUUID(), p.operationId(), p.errandId(), p.requesterId(), p.amount(), courier));
    }

    private void accept(ErrandSettlementEvent e) {
        service.accept(e, e.eventType());
    }

    private void process(ErrandSettlementEvent e) {
        accept(e);
        assertThat(service.processNext()).isTrue();
    }

    private String outcome(ErrandSettlementEvent e) {
        return jdbc.queryForObject(
                "SELECT outcome FROM credit_settlement_operations WHERE operation_id = ?",
                String.class,
                e.payload().operationId());
    }

    private void retryNow() {
        jdbc.update(
                "UPDATE credit_settlement_inbox SET next_attempt_at = CURRENT_TIMESTAMP WHERE processed_at IS NULL");
    }

    @Test
    void cancellationAndExpiryRefundExactlyOnceIncludingDifferentOperationIds() throws Exception {
        var owner = fund(100);
        var r = reserved(owner, 80);
        var cancellation = settlement(r, "ErrandCancelledEvent", null);
        concurrent(() -> accept(cancellation), () -> accept(cancellation));
        service.processNext();
        process(new ErrandSettlementEvent(
                UUID.randomUUID(), cancellation.eventType(), 1, cancellation.occurredAt(), cancellation.payload()));
        process(settlement(r, "ErrandExpiredEvent", null));
        assertThat(accounts.getBalance(owner)).isEqualTo(new CreditBalanceResponse(100, 0));
        assertThat(jdbc.queryForList("SELECT outcome FROM credit_settlement_operations", String.class))
                .containsOnly("REFUNDED");
        var second = reserved(owner, 30);
        process(settlement(second, "ErrandExpiredEvent", null));
        assertThat(accounts.getBalance(owner)).isEqualTo(new CreditBalanceResponse(100, 0));
        assertThat(jdbc.queryForList("SELECT state FROM credit_reservations", String.class))
                .containsOnly("REFUNDED");
    }

    @Test
    void payoutUsesAssignedCourierAndReplaysStableResultWithoutSecondTransfer() {
        var owner = fund(100);
        var courier = fund(7);
        var r = reserved(owner, 80);
        var e = settlement(r, "ErrandCompletedEvent", courier);
        process(e);
        var result = outbox.claimNext().orElseThrow();
        assertThat(result.event().eventType()).isEqualTo("CreditSettlementResultEvent");
        assertThat(result.event().payload().outcome()).isEqualTo("PAID");
        // Simulate a lost publisher acknowledgement, then a duplicate business request.
        jdbc.update(
                "UPDATE credit_result_outbox SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE event_id = ?",
                result.event().eventId());
        var recovered = outbox.claimNext().orElseThrow();
        assertThat(recovered.event()).isEqualTo(result.event());
        outbox.markPublished(recovered);
        process(new ErrandSettlementEvent(UUID.randomUUID(), e.eventType(), 1, e.occurredAt(), e.payload()));
        assertThat(outbox.claimNext().orElseThrow().event()).isEqualTo(result.event());
        process(settlement(r, "ErrandCompletedEvent", courier));
        var otherCourier = fund(0);
        var conflict = settlement(r, "ErrandCompletedEvent", otherCourier);
        process(conflict);
        assertThat(outcome(conflict)).isEqualTo("TERMINAL_CONFLICT");
        assertThat(accounts.getBalance(owner)).isEqualTo(new CreditBalanceResponse(20, 0));
        assertThat(accounts.getBalance(courier)).isEqualTo(new CreditBalanceResponse(87, 0));
        assertThat(accounts.getBalance(otherCourier)).isEqualTo(new CreditBalanceResponse(0, 0));
    }

    @Test
    void missingCourierRemainsPendingWithReservedFundsThenResumes() {
        var owner = fund(100);
        var courier = UUID.randomUUID();
        var e = settlement(reserved(owner, 80), "ErrandCompletedEvent", courier);
        process(e);
        assertThat(outcome(e)).isEqualTo("PENDING");
        assertThat(accounts.getBalance(owner)).isEqualTo(new CreditBalanceResponse(20, 80));
        assertThat(count("credit_result_outbox")).isEqualTo(1); // reserve result only
        accounts.initializeAccount(courier);
        retryNow();
        service.processNext();
        assertThat(outcome(e)).isEqualTo("PAID");
        assertThat(accounts.getBalance(courier)).isEqualTo(new CreditBalanceResponse(80, 0));
    }

    @Test
    void settlementBeforeReservationWaitsAndRejectedReservationFinishesWithoutRetrying() {
        var owner = fund(100);
        var r = event(owner, 80);
        var e = settlement(r, "ErrandCancelledEvent", null);
        process(e);
        assertThat(outcome(e)).isEqualTo("PENDING");
        reserve.accept(r);
        retryNow();
        service.processNext();
        assertThat(outcome(e)).isEqualTo("PENDING");
        reserve.processNext();
        retryNow();
        service.processNext();
        assertThat(outcome(e)).isEqualTo("REFUNDED");
        assertThat(accounts.getBalance(owner)).isEqualTo(new CreditBalanceResponse(100, 0));
        var rejected = event(owner, 120);
        var expired = settlement(rejected, "ErrandExpiredEvent", null);
        process(expired);
        reserve.accept(rejected);
        reserve.processNext();
        retryNow();
        service.processNext();
        assertThat(outcome(expired)).isEqualTo("NO_RESERVATION");
        assertThat(service.processNext()).isFalse();
        process(new ErrandSettlementEvent(
                UUID.randomUUID(), expired.eventType(), 1, expired.occurredAt(), expired.payload()));
        assertThat(outcome(expired)).isEqualTo("NO_RESERVATION");
        assertThat(accounts.getBalance(owner)).isEqualTo(new CreditBalanceResponse(100, 0));
    }

    @Test
    void mismatchedReservationAndChangedOperationDataCannotMoveFunds() {
        var owner = fund(100);
        var r = reserved(owner, 80);
        var good = settlement(r, "ErrandCancelledEvent", null);
        var p = good.payload();
        for (var altered : List.of(
                new ErrandSettlementEvent.Payload(
                        UUID.randomUUID(), p.reservationOperationId(), p.errandId(), owner, 70, null),
                new ErrandSettlementEvent.Payload(
                        UUID.randomUUID(), p.reservationOperationId(), p.errandId(), UUID.randomUUID(), 80, null),
                new ErrandSettlementEvent.Payload(
                        UUID.randomUUID(), UUID.randomUUID(), p.errandId(), owner, 80, null))) {
            var bad = new ErrandSettlementEvent(UUID.randomUUID(), good.eventType(), 1, good.occurredAt(), altered);
            process(bad);
            assertThat(outcome(bad)).isEqualTo("RESERVATION_MISMATCH");
        }
        accept(good);
        var changed = new ErrandSettlementEvent(
                UUID.randomUUID(),
                good.eventType(),
                1,
                good.occurredAt(),
                new ErrandSettlementEvent.Payload(
                        p.operationId(), p.reservationOperationId(), p.errandId(), owner, 1, null));
        assertThatThrownBy(() -> accept(changed)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.accept(good, "ErrandCompletedEvent"))
                .isInstanceOf(IllegalArgumentException.class);
        var reusedId = new ErrandSettlementEvent(
                good.eventId(),
                good.eventType(),
                1,
                good.occurredAt(),
                settlement(r, good.eventType(), null).payload());
        assertThatThrownBy(() -> accept(reusedId)).isInstanceOf(IllegalArgumentException.class);
        assertThat(accounts.getBalance(owner)).isEqualTo(new CreditBalanceResponse(20, 80));
    }

    @Test
    void failedRecipientWriteRollsBackDebitAndAllSettlementRecords() {
        var owner = fund(100);
        var courier = fund(0);
        var e = settlement(reserved(owner, 80), "ErrandCompletedEvent", courier);
        accept(e);
        jdbc.execute("ALTER TABLE credit_accounts ADD CONSTRAINT fail_recipient CHECK (user_id <> '" + courier
                + "'::uuid OR total_balance = 0)");
        try {
            assertThatThrownBy(() -> service.processNext()).isInstanceOf(RuntimeException.class);
            assertThat(accounts.getBalance(owner)).isEqualTo(new CreditBalanceResponse(20, 80));
            assertThat(accounts.getBalance(courier)).isEqualTo(new CreditBalanceResponse(0, 0));
            assertThat(outcome(e)).isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("SELECT state FROM credit_reservations", String.class))
                    .isEqualTo("RESERVED");
            assertThat(jdbc.queryForObject("SELECT processed_at IS NULL FROM credit_settlement_inbox", Boolean.class))
                    .isTrue();
            assertThat(count("credit_result_outbox")).isEqualTo(1);
        } finally {
            jdbc.execute("ALTER TABLE credit_accounts DROP CONSTRAINT fail_recipient");
        }
        service.processNext();
        assertThat(accounts.getBalance(owner)).isEqualTo(new CreditBalanceResponse(20, 0));
        assertThat(accounts.getBalance(courier)).isEqualTo(new CreditBalanceResponse(80, 0));
    }

    @Test
    void inboxCompletionFailureRollsBackRefundAndResult() {
        var owner = fund(100);
        var e = settlement(reserved(owner, 80), "ErrandCancelledEvent", null);
        accept(e);
        jdbc.execute("ALTER TABLE credit_settlement_inbox ADD CONSTRAINT fail_completion CHECK (processed_at IS NULL)");
        try {
            assertThatThrownBy(() -> service.processNext()).isInstanceOf(RuntimeException.class);
            assertThat(accounts.getBalance(owner)).isEqualTo(new CreditBalanceResponse(20, 80));
            assertThat(outcome(e)).isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("SELECT state FROM credit_reservations", String.class))
                    .isEqualTo("RESERVED");
            assertThat(count("credit_result_outbox")).isEqualTo(1);
        } finally {
            jdbc.execute("ALTER TABLE credit_settlement_inbox DROP CONSTRAINT fail_completion");
        }
        service.processNext();
        assertThat(accounts.getBalance(owner)).isEqualTo(new CreditBalanceResponse(100, 0));
    }

    @Test
    void concurrentRefundAndPayoutProduceOnlyOneTerminalEffect() throws Exception {
        var owner = fund(100);
        var courier = fund(0);
        var r = reserved(owner, 80);
        accept(settlement(r, "ErrandCancelledEvent", null));
        accept(settlement(r, "ErrandCompletedEvent", courier));
        concurrent(() -> service.processNext(), () -> service.processNext());
        var outcomes = jdbc.queryForList("SELECT outcome FROM credit_settlement_operations", String.class);
        assertThat(outcomes).contains("TERMINAL_CONFLICT");
        assertThat(outcomes).containsAnyOf("REFUNDED", "PAID");
        var a = accounts.getBalance(owner);
        var b = accounts.getBalance(courier);
        assertThat(a.reservedBalance()).isZero();
        assertThat(a.availableBalance() + b.availableBalance()).isEqualTo(100);
        assertThat(b.availableBalance()).isIn(0L, 80L);
    }

    @Test
    void oppositeDirectionPayoutsLockAccountsInSameOrder() throws Exception {
        var a = fund(100);
        var b = fund(100);
        var first = reserved(a, 80);
        var second = reserved(b, 30);
        accept(settlement(first, "ErrandCompletedEvent", b));
        accept(settlement(second, "ErrandCompletedEvent", a));
        concurrent(() -> service.processNext(), () -> service.processNext());
        assertThat(accounts.getBalance(a)).isEqualTo(new CreditBalanceResponse(50, 0));
        assertThat(accounts.getBalance(b)).isEqualTo(new CreditBalanceResponse(150, 0));
        assertThat(jdbc.queryForList("SELECT outcome FROM credit_settlement_operations", String.class))
                .containsOnly("PAID");
    }

    @TestConfiguration
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = CreditAccountRepository.class)
    @Import({
        CreditReservationService.class,
        CreditSettlementService.class,
        CreditAccountService.class,
        CreditResultOutboxService.class
    })
    static class TestConfig {
        @Bean
        CreditRewardProperties properties() {
            return new CreditRewardProperties(
                    10,
                    10,
                    Duration.ofSeconds(5),
                    Duration.ofSeconds(30),
                    Duration.ofMillis(10),
                    Duration.ofSeconds(1));
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
