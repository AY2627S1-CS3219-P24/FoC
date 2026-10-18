package com.cs3219.foc.credit.service;

import static org.assertj.core.api.Assertions.*;

import com.cs3219.foc.credit.infrastructure.messaging.consumer.UserCreatedConsumer;
import com.cs3219.foc.credit.model.dto.CreditBalanceResponse;
import com.cs3219.foc.credit.model.dto.event.UserCreatedEvent;
import com.cs3219.foc.credit.repository.CreditAccountRepository;
import jakarta.persistence.EntityManagerFactory;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
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
            "provision_test_" + UUID.randomUUID().toString().replace("-", "");

    @Autowired
    @Qualifier("userCreated") private Consumer<UserCreatedEvent> provisioning;

    @Autowired
    private CreditAccountService accounts;

    @Autowired
    private CreditAccountRepository accountRepository;

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
    void consumerReturnsOnlyAfterAccountCommitted() {
        var event = event(UUID.randomUUID());
        provisioning.accept(event);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM credit_accounts WHERE user_id = ?",
                        Long.class,
                        event.payload().userId()))
                .isEqualTo(1);
        assertThat(accounts.getBalance(event.payload().userId())).isEqualTo(new CreditBalanceResponse(0, 0));
    }

    @Test
    void duplicateEventAndDifferentEventForSameUserPreserveBalance() {
        var event = event(UUID.randomUUID());
        provisioning.accept(event);
        jdbc.update(
                "UPDATE credit_accounts SET total_balance = 35 WHERE user_id = ?",
                event.payload().userId());
        provisioning.accept(event);
        provisioning.accept(event(event.payload().userId()));
        assertThat(accounts.getBalance(event.payload().userId())).isEqualTo(new CreditBalanceResponse(35, 0));
        assertThat(accountRepository.count()).isEqualTo(1);
    }

    @Test
    void commitFailureReachesConsumerAndRedeliverySucceeds() {
        var event = event(UUID.randomUUID());
        jdbc.execute(
                "CREATE FUNCTION fail_provisioning_commit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Simulated commit failure'; END $$");
        jdbc.execute(
                "CREATE CONSTRAINT TRIGGER fail_provisioning_commit AFTER INSERT ON credit_accounts DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION fail_provisioning_commit()");
        try {
            assertThatThrownBy(() -> provisioning.accept(event)).isInstanceOf(RuntimeException.class);
            assertThat(accountRepository.count()).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER fail_provisioning_commit ON credit_accounts");
            jdbc.execute("DROP FUNCTION fail_provisioning_commit()");
        }
        provisioning.accept(event);
        assertThat(accountRepository.count()).isEqualTo(1);
    }

    @Test
    void invalidContractCannotCreateAccount() {
        var event = event(UUID.randomUUID());
        var unsupported =
                new UserCreatedEvent(UUID.randomUUID(), "OtherEvent", 2, OffsetDateTime.now(), event.payload());
        assertThatThrownBy(() -> provisioning.accept(unsupported)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> provisioning.accept(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(accountRepository.count()).isZero();
    }

    @Test
    void concurrentDuplicateDeliveriesCreateOneAccount() throws Exception {
        var event = event(UUID.randomUUID());
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                provisioning.accept(event);
                return true;
            });
            var second = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                provisioning.accept(event(event.payload().userId()));
                return true;
            });
            assertThat(first.get(20, TimeUnit.SECONDS)).isTrue();
            assertThat(second.get(20, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(accountRepository.count()).isEqualTo(1);
        assertThat(accounts.getBalance(event.payload().userId())).isEqualTo(new CreditBalanceResponse(0, 0));
    }

    @Test
    void migrationPreservesBalancesAndCompletesPreviouslyAcknowledgedInboxWork() {
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
                    .target("3")
                    .load()
                    .migrate();
            var existing = UUID.randomUUID();
            var pending = UUID.randomUUID();
            sql.update(
                    "INSERT INTO credit_accounts (user_id, available_balance, reserved_balance) VALUES (?, 70, 30)",
                    existing);
            for (var user : new UUID[] {existing, pending, pending}) {
                sql.update(
                        "INSERT INTO credit_provisioning_inbox (event_id, user_id, occurred_at) VALUES (?, ?, now())",
                        UUID.randomUUID(),
                        user);
            }
            Flyway.configure()
                    .dataSource(source)
                    .schemas(schema)
                    .defaultSchema(schema)
                    .locations(location)
                    .load()
                    .migrate();
            assertThat(sql.queryForObject(
                            "SELECT total_balance FROM credit_accounts WHERE user_id = ?", Long.class, existing))
                    .isEqualTo(100);
            assertThat(sql.queryForObject(
                            "SELECT total_balance FROM credit_accounts WHERE user_id = ?", Long.class, pending))
                    .isZero();
            assertThat(sql.queryForObject("SELECT count(*) FROM credit_accounts", Long.class))
                    .isEqualTo(2);
            var fresh = UUID.randomUUID();
            sql.update("INSERT INTO credit_accounts (user_id) VALUES (?)", fresh);
            assertThat(sql.queryForObject(
                            "SELECT total_balance FROM credit_accounts WHERE user_id = ?", Long.class, fresh))
                    .isZero();
            assertThat(sql.queryForObject("SELECT to_regclass('credit_provisioning_inbox')", String.class))
                    .isNull();
        } finally {
            sql.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @TestConfiguration
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = CreditAccountRepository.class)
    @Import({UserCreatedConsumer.class, CreditProvisioningService.class, CreditAccountService.class})
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
