package com.cs3219.foc.credit.service;

import static org.assertj.core.api.Assertions.*;

import com.cs3219.foc.credit.config.CreditProperties;
import com.cs3219.foc.credit.exception.*;
import com.cs3219.foc.credit.model.dto.*;
import com.cs3219.foc.credit.model.dto.event.*;
import com.cs3219.foc.credit.model.entity.ReservationState;
import com.cs3219.foc.credit.repository.CreditAccountRepository;
import com.cs3219.foc.credit.security.AuthenticatedUserResolver;
import com.cs3219.foc.credit.security.AuthenticationContextService;
import com.cs3219.foc.credit.security.CurrentUserService;
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
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.context.event.EventListener;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringJUnitConfig(CreditSettlementPersistenceTests.TestConfig.class)
@TestPropertySource(properties = "credits.initial-allocation=15")
@EnabledIfEnvironmentVariable(named = "FOC_TEST_DATABASE_URL", matches = ".+")
class CreditSettlementPersistenceTests {
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

    @Autowired
    private Results results;

    @BeforeEach
    void clearResults() {
        results.events.clear();
    }

    static class Results {
        final CopyOnWriteArrayList<Object> events = new CopyOnWriteArrayList<>();

        @EventListener
        public void on(CreditRefundedEvent event) {
            events.add(event);
        }

        @EventListener
        public void on(CreditPaidEvent event) {
            events.add(event);
        }
    }

    private ErrandCancelledEvent cancelled(UUID user, ReserveCreditRequest r) {
        return new ErrandCancelledEvent(
                UUID.randomUUID(),
                1,
                OffsetDateTime.now(),
                new ErrandCancelledEvent.Payload(r.errandId(), user, r.amount()));
    }

    private ErrandExpiredEvent expired(UUID user, ReserveCreditRequest r) {
        return new ErrandExpiredEvent(
                UUID.randomUUID(),
                1,
                OffsetDateTime.now(),
                new ErrandExpiredEvent.Payload(r.errandId(), user, r.amount()));
    }

    private ErrandCompletedEvent completed(UUID user, ReserveCreditRequest r, UUID courier) {
        return new ErrandCompletedEvent(
                UUID.randomUUID(),
                1,
                OffsetDateTime.now(),
                new ErrandCompletedEvent.Payload(r.errandId(), user, r.amount(), courier));
    }

    private ReserveCreditRequest reserved(UUID user, long amount) {
        var r = request(amount);
        assertThat(reserve.reserve(user, r).outcome()).isEqualTo(ReservationState.RESERVED);
        return r;
    }

    private void assertRefunded() {
        assertThat(results.events.getLast()).isInstanceOf(CreditRefundedEvent.class);
    }

    private void assertPaid() {
        assertThat(results.events.getLast()).isInstanceOf(CreditPaidEvent.class);
    }

    @Test
    void cancellationAndExpiryRefundOnceIncludingDifferentEvents() throws Exception {
        var user = fund(100);
        var r = reserved(user, 80);
        var cancel = cancelled(user, r);
        concurrent(
                () -> settlement.releaseReservedCredits(cancel),
                () -> settlement.releaseReservedCredits(expired(user, r)));
        settlement.releaseReservedCredits(cancel);
        assertThat(creditAccountService.getBalance(user)).isEqualTo(new CreditBalanceResponse(100, 0));
        assertThat(count("credit_transaction_log")).isEqualTo(3);
        assertThat(jdbc.queryForList("SELECT transaction_type FROM credit_transaction_log", String.class))
                .containsExactlyInAnyOrder("RESERVE", "REFUND", "REFUND");
        assertThat(reserve.reserve(user, r).outcome()).isEqualTo(ReservationState.RESERVED);
    }

    @Test
    void repeatedPayoutPaysOnce() {
        var user = fund(100);
        var courier = fund(7);
        var other = fund(0);
        var r = reserved(user, 80);
        var paid = completed(user, r, courier);
        settlement.transferReservedCredits(paid);
        settlement.transferReservedCredits(paid);
        assertPaid();
        assertThat(((CreditPaidEvent) results.events.getLast()).payload().operationId())
                .isEqualTo(paid.eventId());
        settlement.transferReservedCredits(completed(user, r, courier));
        assertPaid();
        assertThat(creditAccountService.getBalance(user)).isEqualTo(new CreditBalanceResponse(20, 0));
        assertThat(creditAccountService.getBalance(courier)).isEqualTo(new CreditBalanceResponse(87, 0));
        assertThat(creditAccountService.getBalance(other)).isEqualTo(new CreditBalanceResponse(0, 0));
        assertThat(count("credit_transaction_log")).isEqualTo(2);
    }

    @Test
    void missingCourierRollsBackThenQueueRedeliveryCanSucceed() {
        var user = fund(100);
        var courier = UUID.randomUUID();
        var r = reserved(user, 80);
        var paid = completed(user, r, courier);
        assertThatThrownBy(() -> settlement.transferReservedCredits(paid))
                .isInstanceOf(CreditAccountPendingException.class);
        assertThat(results.events).isEmpty();
        assertThat(count("credit_transaction_log")).isEqualTo(1);
        assertThat(creditAccountService.getBalance(user)).isEqualTo(new CreditBalanceResponse(20, 80));
        creditAccountService.initializeAccount(courier);
        settlement.transferReservedCredits(paid);
        assertThat(creditAccountService.getBalance(courier)).isEqualTo(new CreditBalanceResponse(95, 0));
    }

    @Test
    void settlementBeforeReservationRetries() {
        var user = fund(100);
        var r = request(80);
        var cancel = cancelled(user, r);
        assertThatThrownBy(() -> settlement.releaseReservedCredits(cancel))
                .isInstanceOf(ReservationPendingException.class);
        reserve.reserve(user, r);
        settlement.releaseReservedCredits(cancel);
        assertRefunded();
        assertThat(creditAccountService.getBalance(user)).isEqualTo(new CreditBalanceResponse(100, 0));
    }

    @Test
    void recipientWriteFailureRollsBackDebitStateAndAudit() {
        var user = fund(100);
        var courier = fund(0);
        var paid = completed(user, reserved(user, 80), courier);
        jdbc.execute("ALTER TABLE credit_accounts ADD CONSTRAINT fail_recipient CHECK(user_id <> '" + courier
                + "'::uuid OR total_balance=0)");
        try {
            assertThatThrownBy(() -> settlement.transferReservedCredits(paid)).isInstanceOf(RuntimeException.class);
            assertThat(creditAccountService.getBalance(user)).isEqualTo(new CreditBalanceResponse(20, 80));
            assertThat(creditAccountService.getBalance(courier)).isEqualTo(new CreditBalanceResponse(0, 0));
            assertThat(count("credit_transaction_log")).isEqualTo(1);
            assertThat(results.events).isEmpty();
        } finally {
            jdbc.execute("ALTER TABLE credit_accounts DROP CONSTRAINT fail_recipient");
        }
        settlement.transferReservedCredits(paid);
        assertPaid();
    }

    @Test
    void auditFailureRollsBackRefund() {
        var user = fund(100);
        var cancel = cancelled(user, reserved(user, 80));
        jdbc.execute("ALTER TABLE credit_transaction_log ADD CONSTRAINT fail_refund CHECK(transaction_type='RESERVE')");
        try {
            assertThatThrownBy(() -> settlement.releaseReservedCredits(cancel)).isInstanceOf(RuntimeException.class);
            assertThat(creditAccountService.getBalance(user)).isEqualTo(new CreditBalanceResponse(20, 80));
            assertThat(count("credit_transaction_log")).isEqualTo(1);
        } finally {
            jdbc.execute("ALTER TABLE credit_transaction_log DROP CONSTRAINT fail_refund");
        }
        settlement.releaseReservedCredits(cancel);
        assertThat(creditAccountService.getBalance(user)).isEqualTo(new CreditBalanceResponse(100, 0));
    }

    @Test
    void oppositePaymentsMoveBothBalances() {
        var a = fund(100);
        var b = fund(100);
        var r = reserved(a, 80);
        var s = reserved(b, 30);
        settlement.transferReservedCredits(completed(a, r, b));
        settlement.transferReservedCredits(completed(b, s, a));
        assertThat(creditAccountService.getBalance(a)).isEqualTo(new CreditBalanceResponse(50, 0));
        assertThat(creditAccountService.getBalance(b)).isEqualTo(new CreditBalanceResponse(150, 0));
        assertThat(count("credit_transaction_log")).isEqualTo(4);
    }

    @TestConfiguration
    @EnableConfigurationProperties(CreditProperties.class)
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = CreditAccountRepository.class)
    @Import({
        CreditReservationService.class,
        CreditAccountService.class,
        CreditSettlementService.class,
        CreditTransactionLogListener.class,
        CurrentUserService.class,
        AuthenticationContextService.class,
        AuthenticatedUserResolver.class
    })
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
