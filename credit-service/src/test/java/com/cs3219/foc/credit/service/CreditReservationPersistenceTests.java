package com.cs3219.foc.credit.service;

import static org.assertj.core.api.Assertions.*;

import com.cs3219.foc.credit.config.CreditRewardProperties;
import com.cs3219.foc.credit.model.dto.CreditBalanceResponse;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringJUnitConfig(CreditReservationPersistenceTests.TestConfig.class)
@EnabledIfEnvironmentVariable(named = "FOC_TEST_DATABASE_URL", matches = ".+")
class CreditReservationPersistenceTests {
    private static final String SCHEMA =
            "credit_reserve_test_" + UUID.randomUUID().toString().replace("-", "");

    @Autowired
    private CreditReservationService service;

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
                "TRUNCATE credit_accounts, credit_reserve_operations, credit_reserve_inbox, credit_reservations, credit_result_outbox CASCADE");
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

    private ErrandSubmittedEvent redelivery(ErrandSubmittedEvent event) {
        return new ErrandSubmittedEvent(UUID.randomUUID(), event.eventType(), 1, event.occurredAt(), event.payload());
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
    void concurrentReservationsCannotOverspendAndConserveCredits() throws Exception {
        var user = fund(100);
        service.accept(event(user, 80));
        service.accept(event(user, 80));
        concurrent(() -> service.processNext(), () -> service.processNext());
        assertThat(accounts.getBalance(user)).isEqualTo(new CreditBalanceResponse(20, 80));
        assertThat(count("credit_reservations")).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT outcome FROM credit_reserve_operations", String.class))
                .containsExactlyInAnyOrder("RESERVED", "INSUFFICIENT_BALANCE");
        assertThat(count("credit_result_outbox")).isEqualTo(2);
    }

    @Test
    void duplicateEventsAndOperationsReserveOnlyOnceUnderConcurrency() throws Exception {
        var user = fund(100);
        var e = event(user, 80);
        concurrent(() -> service.accept(e), () -> service.accept(e));
        service.accept(redelivery(e));
        concurrent(() -> service.processNext(), () -> service.processNext());
        service.accept(e);
        service.accept(redelivery(e));
        service.processNext();
        assertThat(accounts.getBalance(user)).isEqualTo(new CreditBalanceResponse(20, 80));
        assertThat(count("credit_reservations")).isEqualTo(1);
        assertThat(count("credit_reserve_operations")).isEqualTo(1);
        assertThat(count("credit_result_outbox")).isEqualTo(1);
    }

    @Test
    void changedOperationDataAndReusedErrandOrEventAreRejected() {
        var user = fund(100);
        var e = event(user, 80);
        var p = e.payload();
        service.accept(e);
        service.processNext();
        for (var altered : List.of(
                new ErrandSubmittedEvent.Payload(p.operationId(), p.errandId(), user, 70),
                new ErrandSubmittedEvent.Payload(p.operationId(), UUID.randomUUID(), user, 80),
                new ErrandSubmittedEvent.Payload(p.operationId(), p.errandId(), UUID.randomUUID(), 80),
                new ErrandSubmittedEvent.Payload(UUID.randomUUID(), p.errandId(), user, 80))) {
            var changed = new ErrandSubmittedEvent(UUID.randomUUID(), e.eventType(), 1, e.occurredAt(), altered);
            assertThatThrownBy(() -> service.accept(changed)).isInstanceOf(IllegalArgumentException.class);
        }
        var reusedEvent = new ErrandSubmittedEvent(
                e.eventId(), e.eventType(), 1, e.occurredAt(), event(user, 10).payload());
        assertThatThrownBy(() -> service.accept(reusedEvent)).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("credit_reserve_operations")).isEqualTo(1);
        assertThat(accounts.getBalance(user)).isEqualTo(new CreditBalanceResponse(20, 80));
    }

    @Test
    void insufficientBalanceRemainsRejectedEvenAfterFundingAndRetry() {
        var user = fund(0);
        var e = event(user, 80);
        service.accept(e);
        service.processNext();
        var original = outbox.claimNext().orElseThrow();
        assertThat(original.event().payload().outcome()).isEqualTo("INSUFFICIENT_BALANCE");
        outbox.markPublished(original);
        jdbc.update("UPDATE credit_accounts SET total_balance = 100 WHERE user_id = ?", user);
        service.accept(redelivery(e));
        service.processNext();
        assertThat(accounts.getBalance(user)).isEqualTo(new CreditBalanceResponse(100, 0));
        assertThat(count("credit_reservations")).isZero();
        assertThat(outbox.claimNext().orElseThrow().event()).isEqualTo(original.event());
        assertThat(count("credit_result_outbox")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT outcome FROM credit_reserve_operations", String.class))
                .isEqualTo("INSUFFICIENT_BALANCE");
    }

    @Test
    void unprovisionedAccountStaysPendingWithoutBlockingOtherUsers() {
        var missing = UUID.randomUUID();
        service.accept(event(missing, 80));
        service.processNext();
        assertThat(jdbc.queryForObject("SELECT outcome FROM credit_reserve_operations", String.class))
                .isEqualTo("PENDING");
        assertThat(count("credit_result_outbox")).isZero();
        var funded = fund(100);
        // Defer the missing account while proving another user's work can finish.
        jdbc.update("UPDATE credit_reserve_inbox SET next_attempt_at = CURRENT_TIMESTAMP + INTERVAL '1 hour'");
        var other = event(funded, 10);
        service.accept(other);
        service.processNext();
        assertThat(accounts.getBalance(funded)).isEqualTo(new CreditBalanceResponse(90, 10));
        accounts.initializeAccount(missing);
        jdbc.update("UPDATE credit_accounts SET total_balance = 100 WHERE user_id = ?", missing);
        jdbc.update(
                "UPDATE credit_reserve_inbox SET next_attempt_at = CURRENT_TIMESTAMP WHERE operation_id IN (SELECT operation_id FROM credit_reserve_operations WHERE requester_id = ?)",
                missing);
        service.processNext();
        assertThat(accounts.getBalance(missing)).isEqualTo(new CreditBalanceResponse(20, 80));
    }

    @Test
    void completionFailureRollsBackBalancesReservationResultAndOutbox() {
        var user = fund(100);
        service.accept(event(user, 80));
        jdbc.execute("ALTER TABLE credit_reserve_inbox ADD CONSTRAINT fail_completion CHECK (processed_at IS NULL)");
        try {
            assertThatThrownBy(() -> service.processNext()).isInstanceOf(RuntimeException.class);
            assertThat(accounts.getBalance(user)).isEqualTo(new CreditBalanceResponse(100, 0));
            assertThat(count("credit_reservations")).isZero();
            assertThat(count("credit_result_outbox")).isZero();
            assertThat(jdbc.queryForObject("SELECT outcome FROM credit_reserve_operations", String.class))
                    .isEqualTo("PENDING");
        } finally {
            jdbc.execute("ALTER TABLE credit_reserve_inbox DROP CONSTRAINT fail_completion");
        }
        service.processNext();
        assertThat(accounts.getBalance(user)).isEqualTo(new CreditBalanceResponse(20, 80));
    }

    @Test
    void committedResultSurvivesFailureAndExpiredLeaseWithStableEventIdentity() {
        var user = fund(100);
        service.accept(event(user, 80));
        service.processNext();
        var first = outbox.claimNext().orElseThrow();
        assertThat(outbox.claimNext()).isEmpty();
        outbox.retryLater(first, "BrokerUnavailable");
        jdbc.update("UPDATE credit_result_outbox SET next_attempt_at = CURRENT_TIMESTAMP");
        var retry = outbox.claimNext().orElseThrow();
        assertThat(retry.event()).isEqualTo(first.event());
        // Simulate process death/lost confirm; another worker recovers the expired lease.
        jdbc.update("UPDATE credit_result_outbox SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second'");
        var recovered = outbox.claimNext().orElseThrow();
        assertThat(recovered.event()).isEqualTo(first.event());
        outbox.markPublished(retry); // Stale worker cannot mark the current lease complete.
        assertThat(jdbc.queryForObject("SELECT published_at IS NULL FROM credit_result_outbox", Boolean.class))
                .isTrue();
        outbox.markPublished(recovered);
        assertThat(outbox.claimNext()).isEmpty();
        assertThat(accounts.getBalance(user)).isEqualTo(new CreditBalanceResponse(20, 80));
    }

    @Test
    void invalidAmountDoesNotCreateWorkAndDatabaseAlsoRejectsIt() {
        var user = fund(100);
        assertThatThrownBy(() -> service.accept(event(user, 0))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.accept(event(user, -1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO credit_reserve_operations(operation_id,errand_id,requester_id,amount) VALUES (?,?,?,-1)",
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        user))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("credit_reserve_operations")).isZero();
    }

    @TestConfiguration
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = CreditAccountRepository.class)
    @Import({CreditReservationService.class, CreditAccountService.class, CreditResultOutboxService.class})
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
