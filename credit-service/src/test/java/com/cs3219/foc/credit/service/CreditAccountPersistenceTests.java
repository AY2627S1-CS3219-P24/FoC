package com.cs3219.foc.credit.service;

import static org.assertj.core.api.Assertions.*;

import com.cs3219.foc.credit.config.CreditProperties;
import com.cs3219.foc.credit.exception.CreditAccountPendingException;
import com.cs3219.foc.credit.model.dto.CreditBalanceResponse;
import com.cs3219.foc.credit.repository.CreditAccountRepository;
import com.cs3219.foc.credit.security.AuthenticatedUserResolver;
import com.cs3219.foc.credit.security.AuthenticationContextService;
import com.cs3219.foc.credit.security.CurrentUserService;
import jakarta.persistence.EntityManagerFactory;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

/** Uses real PostgreSQL migrations and service transactions in an isolated schema. */
@SpringJUnitConfig(CreditAccountPersistenceTests.TestConfig.class)
@TestPropertySource(properties = "credits.initial-allocation=20")
@EnabledIfEnvironmentVariable(named = "FOC_TEST_DATABASE_URL", matches = ".+")
class CreditAccountPersistenceTests {
    private static final String SCHEMA =
            "credit_test_" + UUID.randomUUID().toString().replace("-", "");

    @Autowired
    private CreditAccountService service;

    @Autowired
    private CreditAccountRepository repository;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JpaTransactionManager transactionManager;

    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanAccounts() {
        jdbc = new JdbcTemplate(dataSource);
        repository.deleteAll();
    }

    @AfterAll
    static void dropTestSchema() throws Exception {
        try (var connection = DriverManager.getConnection(
                        System.getenv("FOC_TEST_DATABASE_URL"),
                        System.getenv("FOC_TEST_DATABASE_USERNAME"),
                        System.getenv("FOC_TEST_DATABASE_PASSWORD"));
                var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }

    @Test
    void initializesWithConfiguredAllocationAndPreservesExistingBalancesAndTimestamps() {
        var owner = UUID.randomUUID();
        assertThat(service.initializeAccount(owner)).isTrue();
        assertThat(service.getBalance(owner)).isEqualTo(new CreditBalanceResponse(20, 0));
        var initial = repository.findById(owner).orElseThrow();
        assertThat(initial.getCreatedAt()).isNotNull();
        assertThat(initial.getUpdatedAt()).isNotNull();
        jdbc.update("UPDATE credit_accounts SET total_balance = 100 WHERE user_id = ?", owner);
        assertThat(service.initializeAccount(owner)).isFalse();
        assertThat(service.getBalance(owner)).isEqualTo(new CreditBalanceResponse(100, 0));
        var after = repository.findById(owner).orElseThrow();
        assertThat(after.getCreatedAt()).isEqualTo(initial.getCreatedAt());
        assertThat(after.getUpdatedAt()).isEqualTo(initial.getUpdatedAt());
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void concurrentInitializationGrantsConfiguredAllocationOnce() throws Exception {
        var owner = UUID.randomUUID();
        int workers = 8;
        var barrier = new CyclicBarrier(workers);
        try (var executor = Executors.newFixedThreadPool(workers)) {
            var results = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < workers; i++) {
                results.add(executor.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return service.initializeAccount(owner);
                }));
            }
            int created = 0;
            for (var result : results) {
                if (result.get(20, TimeUnit.SECONDS)) created++;
            }
            assertThat(created).isEqualTo(1);
        }
        assertThat(repository.count()).isEqualTo(1);
        assertThat(service.getBalance(owner)).isEqualTo(new CreditBalanceResponse(20, 0));
    }

    @Test
    void pendingReadDoesNotCreateAccount() {
        assertThatThrownBy(() -> service.getBalance(UUID.randomUUID()))
                .isInstanceOf(CreditAccountPendingException.class);
        assertThat(repository.count()).isZero();
    }

    @Test
    void readsSeparateBalancesForSeparateOwners() {
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        service.initializeAccount(first);
        service.initializeAccount(second);
        jdbc.update("UPDATE credit_accounts SET total_balance = 35 WHERE user_id = ?", first);
        assertThat(service.getBalance(first)).isEqualTo(new CreditBalanceResponse(35, 0));
        assertThat(service.getBalance(second)).isEqualTo(new CreditBalanceResponse(20, 0));
    }

    @Test
    void databaseRejectsNegativeBalancesOnInsertAndUpdate() {
        var owner = UUID.randomUUID();
        assertThatThrownBy(
                        () -> jdbc.update("INSERT INTO credit_accounts (user_id, total_balance) VALUES (?, -1)", owner))
                .isInstanceOf(DataIntegrityViolationException.class);
        service.initializeAccount(owner);
        assertThatThrownBy(() -> jdbc.update("UPDATE credit_accounts SET total_balance = -1 WHERE user_id = ?", owner))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(service.getBalance(owner)).isEqualTo(new CreditBalanceResponse(20, 0));
    }

    @Test
    void databaseRequiresAnExplicitInitialBalance() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO credit_accounts (user_id) VALUES (?)", UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(repository.count()).isZero();
    }

    @Test
    void negativeInitialAllocationFailsConfigurationBinding() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfig.class)
                .withPropertyValues("credits.initial-allocation=-1")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasRootCauseInstanceOf(BindValidationException.class));
    }

    @Test
    void missingInitialAllocationFailsConfigurationBinding() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfig.class)
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasRootCauseInstanceOf(BindValidationException.class));
    }

    @Test
    void zeroInitialAllocationIsValid() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfig.class)
                .withPropertyValues("credits.initial-allocation=0")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(CreditProperties.class).initialAllocation())
                            .isZero();
                });
    }

    @Test
    void initializationRollsBackWithItsOwningTransaction() {
        var owner = UUID.randomUUID();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThat(service.initializeAccount(owner)).isTrue();
            status.setRollbackOnly();
        });
        assertThat(repository.existsById(owner)).isFalse();
        assertThat(service.initializeAccount(owner)).isTrue();
    }

    @TestConfiguration
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = CreditAccountRepository.class)
    @Import({
        CreditAccountService.class,
        PropertiesConfig.class,
        CurrentUserService.class,
        AuthenticationContextService.class,
        AuthenticatedUserResolver.class
    })
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
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
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

    @TestConfiguration
    @EnableConfigurationProperties(CreditProperties.class)
    static class PropertiesConfig {}
}
