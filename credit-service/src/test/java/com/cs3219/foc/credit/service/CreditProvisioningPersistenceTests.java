package com.cs3219.foc.credit.service;

import static org.assertj.core.api.Assertions.*;

import com.cs3219.foc.credit.model.dto.CreditBalanceResponse;
import com.cs3219.foc.credit.model.dto.event.UserCreatedEvent;
import com.cs3219.foc.credit.repository.CreditAccountRepository;
import com.cs3219.foc.credit.repository.CreditProvisioningInboxRepository;
import jakarta.persistence.EntityManagerFactory;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
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

@SpringJUnitConfig(CreditProvisioningPersistenceTests.TestConfig.class)
@EnabledIfEnvironmentVariable(named = "FOC_TEST_DATABASE_URL", matches = ".+")
class CreditProvisioningPersistenceTests {
    private static final String SCHEMA =
            "credit_inbox_test_" + UUID.randomUUID().toString().replace("-", "");

    @Autowired
    private CreditProvisioningService provisioning;

    @Autowired
    private CreditAccountService accounts;

    @Autowired
    private CreditAccountRepository accountRepository;

    @Autowired
    private CreditProvisioningInboxRepository inbox;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("TRUNCATE credit_provisioning_inbox, credit_accounts CASCADE");
    }

    @AfterAll
    static void dropSchema() throws Exception {
        try (var connection = DriverManager.getConnection(
                        System.getenv("FOC_TEST_DATABASE_URL"),
                        System.getenv("FOC_TEST_DATABASE_USERNAME"),
                        System.getenv("FOC_TEST_DATABASE_PASSWORD"));
                var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }

    private UserCreatedEvent event(UUID userId) {
        return new UserCreatedEvent(
                UUID.randomUUID(), "UserCreatedEvent", 1, OffsetDateTime.now(), new UserCreatedEvent.Payload(userId));
    }

    @Test
    void acceptanceIsDurableAndSeparateFromAccountCreation() {
        var event = event(UUID.randomUUID());
        provisioning.accept(event);
        assertThat(inbox.count()).isEqualTo(1);
        assertThat(accountRepository.count()).isZero();
        assertThat(inbox.findById(event.eventId()).orElseThrow().getProcessedAt())
                .isNull();
        assertThat(provisioning.processNext()).isTrue();
        assertThat(accounts.getBalance(event.payload().userId())).isEqualTo(new CreditBalanceResponse(0, 0));
        assertThat(inbox.findById(event.eventId()).orElseThrow().getProcessedAt())
                .isNotNull();
    }

    @Test
    void duplicateEventAndDifferentEventForSameUserPreserveBalances() {
        var event = event(UUID.randomUUID());
        provisioning.accept(event);
        provisioning.accept(event);
        assertThat(inbox.count()).isEqualTo(1);
        provisioning.processNext();
        jdbc.update(
                "UPDATE credit_accounts SET available_balance = 25, reserved_balance = 10 WHERE user_id = ?",
                event.payload().userId());
        provisioning.accept(event); // Redelivery after commit must not reprocess.
        assertThat(provisioning.processNext()).isFalse();
        provisioning.accept(event(event.payload().userId()));
        provisioning.processNext();
        assertThat(accounts.getBalance(event.payload().userId())).isEqualTo(new CreditBalanceResponse(25, 10));
        assertThat(accountRepository.count()).isEqualTo(1);
    }

    @Test
    void accountAndCompletionRollBackTogetherAndPendingWorkResumes() {
        var event = event(UUID.randomUUID());
        provisioning.accept(event);
        jdbc.execute(
                "ALTER TABLE credit_provisioning_inbox ADD CONSTRAINT simulate_completion_failure CHECK (processed_at IS NULL)");
        try {
            assertThatThrownBy(() -> provisioning.processNext()).isInstanceOf(RuntimeException.class);
            assertThat(accountRepository.count()).isZero();
            assertThat(inbox.findById(event.eventId()).orElseThrow().getProcessedAt())
                    .isNull();
        } finally {
            jdbc.execute("ALTER TABLE credit_provisioning_inbox DROP CONSTRAINT simulate_completion_failure");
        }
        assertThat(provisioning.processNext()).isTrue();
        assertThat(accountRepository.count()).isEqualTo(1);
    }

    @Test
    void invalidContractAndConflictingIdentityCannotCreateAccounts() {
        var event = event(UUID.randomUUID());
        provisioning.accept(event);
        var conflicting = new UserCreatedEvent(
                event.eventId(),
                event.eventType(),
                1,
                event.occurredAt(),
                new UserCreatedEvent.Payload(UUID.randomUUID()));
        assertThatThrownBy(() -> provisioning.accept(conflicting)).isInstanceOf(IllegalArgumentException.class);
        var unsupported =
                new UserCreatedEvent(UUID.randomUUID(), "OtherEvent", 2, OffsetDateTime.now(), event.payload());
        assertThatThrownBy(() -> provisioning.accept(unsupported)).isInstanceOf(IllegalArgumentException.class);
        assertThat(inbox.count()).isEqualTo(1);
        assertThat(accountRepository.count()).isZero();
    }

    @Test
    void concurrentWorkersAndDuplicateAcceptanceCreateOneAccount() throws Exception {
        var event = event(UUID.randomUUID());
        var otherEvent = event(event.payload().userId());
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                provisioning.accept(event);
                return true;
            });
            var second = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                provisioning.accept(event);
                return true;
            });
            assertThat(first.get(20, TimeUnit.SECONDS)).isTrue();
            assertThat(second.get(20, TimeUnit.SECONDS)).isTrue();
            assertThat(inbox.count()).isEqualTo(1);
            provisioning.accept(otherEvent);
            var a = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return provisioning.processNext();
            });
            var b = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return provisioning.processNext();
            });
            assertThat(a.get(20, TimeUnit.SECONDS)).isTrue();
            assertThat(b.get(20, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(accountRepository.count()).isEqualTo(1);
        assertThat(accounts.getBalance(event.payload().userId())).isEqualTo(new CreditBalanceResponse(0, 0));
    }

    @Test
    void upgradeChangesOnlyDefaultAndPreservesExistingBalances() {
        var schema = SCHEMA + "_upgrade";
        var url = System.getenv("FOC_TEST_DATABASE_URL");
        var source = new DriverManagerDataSource(
                url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                System.getenv("FOC_TEST_DATABASE_USERNAME"),
                System.getenv("FOC_TEST_DATABASE_PASSWORD"));
        var sql = new JdbcTemplate(source);
        var location =
                "filesystem:" + System.getProperty("foc.root") + "/credit-service/src/main/resources/db/migration";
        try {
            Flyway.configure()
                    .dataSource(source)
                    .schemas(schema)
                    .defaultSchema(schema)
                    .locations(location)
                    .target("1")
                    .load()
                    .migrate();
            var existing = UUID.randomUUID();
            sql.update("INSERT INTO credit_accounts (user_id) VALUES (?)", existing);
            Flyway.configure()
                    .dataSource(source)
                    .schemas(schema)
                    .defaultSchema(schema)
                    .locations(location)
                    .load()
                    .migrate();
            var fresh = UUID.randomUUID();
            sql.update("INSERT INTO credit_accounts (user_id) VALUES (?)", fresh);
            assertThat(sql.queryForObject(
                            "SELECT available_balance FROM credit_accounts WHERE user_id = ?", Long.class, existing))
                    .isEqualTo(100);
            assertThat(sql.queryForObject(
                            "SELECT available_balance FROM credit_accounts WHERE user_id = ?", Long.class, fresh))
                    .isZero();
        } finally {
            sql.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @TestConfiguration
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = CreditAccountRepository.class)
    @Import({CreditProvisioningService.class, CreditAccountService.class})
    static class TestConfig {
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
