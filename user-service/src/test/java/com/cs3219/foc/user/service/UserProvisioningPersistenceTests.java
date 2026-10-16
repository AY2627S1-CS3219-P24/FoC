package com.cs3219.foc.user.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.cs3219.foc.user.config.UserProvisioningProperties;
import com.cs3219.foc.user.infrastructure.messaging.producer.UserCreatedEventPublisher;
import com.cs3219.foc.user.mapper.UserMapper;
import com.cs3219.foc.user.model.dto.RegisterUserRequest;
import com.cs3219.foc.user.model.dto.UserCreatedDelivery;
import com.cs3219.foc.user.model.dto.event.UserCreatedEvent;
import com.cs3219.foc.user.repository.UserCreatedOutboxRepository;
import com.cs3219.foc.user.repository.UserRepository;
import com.cs3219.foc.user.scheduler.UserCreatedOutboxJob;
import com.cs3219.foc.user.scheduler.UserProvisioningBackfillJob;
import jakarta.persistence.EntityManagerFactory;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
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
import org.mapstruct.factory.Mappers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

@SpringJUnitConfig(UserProvisioningPersistenceTests.TestConfig.class)
@EnabledIfEnvironmentVariable(named = "FOC_TEST_DATABASE_URL", matches = ".+")
class UserProvisioningPersistenceTests {
    private static final String SCHEMA =
            "provisioning_test_" + UUID.randomUUID().toString().replace("-", "");

    @Autowired
    private UserService users;

    @Autowired
    private UserProvisioningService provisioning;

    @Autowired
    private UserCreatedOutboxRepository outbox;

    @Autowired
    private UserProvisioningProperties properties;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JpaTransactionManager transactionManager;

    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("TRUNCATE user_created_outbox, user_credit_provisioning, refresh_tokens, users");
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

    private UUID register() {
        return UUID.fromString(
                users.registerUser(new RegisterUserRequest(UUID.randomUUID() + "@u.nus.edu", "Test", "password123"))
                        .id());
    }

    private UUID seed(String role) {
        var id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, name, roles, password_hash, created_at, updated_at)
                VALUES (?, ?, 'Seeded user', ARRAY[?]::text[], 'unused-test-hash', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, id, id + "@u.nus.edu", role);
        return id;
    }

    @Test
    void registrationCommitsUserReceiptAndEventTogetherWithoutBroker() {
        var owner = register();
        assertThat(outbox.count()).isEqualTo(1);
        var event = outbox.findAll().getFirst();
        assertThat(event.getUserId()).isEqualTo(owner);
        assertThat(event.getPublishedAt()).isNull();
        assertThat(event.getAttemptCount()).isZero();
        assertThat(event.getOccurredAt()).isNotNull();
        assertThat(jdbc.queryForObject(
                        "SELECT event_id FROM user_credit_provisioning WHERE user_id = ?", UUID.class, owner))
                .isEqualTo(event.getEventId());
    }

    @Test
    void rollbackLeavesNeitherUserNorEventNorReceipt() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            register();
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Long.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_credit_provisioning", Long.class))
                .isZero();
        assertThat(outbox.count()).isZero();
    }

    @Test
    void outboxWriteFailureAlsoRollsBackRegistration() {
        jdbc.execute("ALTER TABLE user_created_outbox ADD CONSTRAINT simulate_outbox_failure CHECK (false) NOT VALID");
        try {
            assertThatThrownBy(this::register).isInstanceOf(RuntimeException.class);
        } finally {
            jdbc.execute("ALTER TABLE user_created_outbox DROP CONSTRAINT simulate_outbox_failure");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Long.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_credit_provisioning", Long.class))
                .isZero();
        assertThat(outbox.count()).isZero();
    }

    @Test
    void backfillResumesInBatchesAndIncludesAdminAndLaterUsers() {
        var first = seed("USER");
        var admin = seed("ADMIN");
        var third = seed("USER");
        assertThat(provisioning.backfill()).isEqualTo(2);
        assertThat(outbox.count()).isEqualTo(2);
        var registeredDuringScan = register();
        // A new job has no cursor to restore; progress is recorded in PostgreSQL.
        new UserProvisioningBackfillJob(provisioning).reconcileUsers();
        assertThat(outbox.findAll())
                .extracting(row -> row.getUserId())
                .containsExactlyInAnyOrder(first, admin, third, registeredDuringScan);
        var eventIds = outbox.findAll().stream().map(row -> row.getEventId()).toList();
        assertThat(provisioning.backfill()).isZero();
        assertThat(outbox.findAll()).extracting(row -> row.getEventId()).containsExactlyInAnyOrderElementsOf(eventIds);
        var lateAdmin = seed("ADMIN");
        assertThat(provisioning.backfill()).isEqualTo(1);
        assertThat(outbox.findAll()).extracting(row -> row.getUserId()).contains(lateAdmin);
    }

    @Test
    void receiptSurvivesPublishedOutboxCleanup() {
        var owner = register();
        var claimed = provisioning.claimNext().orElseThrow();
        provisioning.markPublished(claimed);
        jdbc.update("DELETE FROM user_created_outbox WHERE published_at IS NOT NULL");
        assertThat(provisioning.backfill()).isZero();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> provisioning.recordUser(owner));
        assertThat(outbox.count()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_credit_provisioning", Long.class))
                .isEqualTo(1);
    }

    @Test
    void concurrentBackfillsCannotCreateDuplicateEvents() throws Exception {
        seed("ADMIN");
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return provisioning.backfill();
            });
            var second = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return provisioning.backfill();
            });
            assertThat(first.get(20, TimeUnit.SECONDS) + second.get(20, TimeUnit.SECONDS))
                    .isEqualTo(1);
        }
        assertThat(outbox.count()).isEqualTo(1);
    }

    @Test
    void publicationFailureSchedulesRetryAndNextWorkerUsesSameEvent() {
        register();
        var publisher = mock(UserCreatedEventPublisher.class);
        doThrow(new IllegalStateException("Broker unavailable")).when(publisher).publish(any());
        new UserCreatedOutboxJob(provisioning, publisher, properties).publishPending();
        var failed = outbox.findAll().getFirst();
        assertThat(failed.getPublishedAt()).isNull();
        assertThat(failed.getLeaseToken()).isNull();
        assertThat(failed.getAttemptCount()).isEqualTo(1);
        assertThat(failed.getLastError()).isEqualTo("IllegalStateException");
        assertThat(provisioning.claimNext()).isEmpty();
        var eventId = failed.getEventId();
        jdbc.update("UPDATE user_created_outbox SET next_attempt_at = CURRENT_TIMESTAMP - INTERVAL '1 second'");
        reset(publisher);
        new UserCreatedOutboxJob(provisioning, publisher, properties).publishPending();
        var published = outbox.findById(eventId).orElseThrow();
        assertThat(published.getPublishedAt()).isNotNull();
        assertThat(published.getAttemptCount()).isEqualTo(2);
        assertThat(published.getLastError()).isNull();
        verify(publisher).publish(UserCreatedEvent.of(eventId, failed.getUserId(), failed.getOccurredAt()));
    }

    @Test
    void lostConfirmCanDuplicateDeliveryButRetainsTheSameEventIdentity() {
        register();
        var received = new ArrayList<UserCreatedEvent>();
        var publisher = mock(UserCreatedEventPublisher.class);
        doAnswer(call -> {
                    received.add(call.getArgument(0));
                    if (received.size() == 1) throw new IllegalStateException("Confirm lost after broker acceptance");
                    return null;
                })
                .when(publisher)
                .publish(any());
        new UserCreatedOutboxJob(provisioning, publisher, properties).publishPending();
        jdbc.update("UPDATE user_created_outbox SET next_attempt_at = CURRENT_TIMESTAMP - INTERVAL '1 second'");
        new UserCreatedOutboxJob(provisioning, publisher, properties).publishPending();
        assertThat(received).hasSize(2);
        assertThat(received.get(0)).isEqualTo(received.get(1));
        assertThat(outbox.findAll().getFirst().getPublishedAt()).isNotNull();
    }

    @Test
    void expiredLeaseIsReclaimedAndOldWorkerCannotOverwriteNewOwner() {
        register();
        var abandoned = provisioning.claimNext().orElseThrow();
        assertThat(provisioning.claimNext()).isEmpty();
        jdbc.update("UPDATE user_created_outbox SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second'");
        var reclaimed = provisioning.claimNext().orElseThrow();
        assertThat(reclaimed.event()).isEqualTo(abandoned.event());
        assertThat(reclaimed.leaseToken()).isNotEqualTo(abandoned.leaseToken());
        provisioning.markPublished(abandoned);
        provisioning.retryLater(abandoned, "stale worker");
        var row = outbox.findById(reclaimed.event().eventId()).orElseThrow();
        assertThat(row.getPublishedAt()).isNull();
        assertThat(row.getLeaseToken()).isEqualTo(reclaimed.leaseToken());
        provisioning.markPublished(reclaimed);
        assertThat(provisioning.claimNext()).isEmpty();
    }

    @Test
    void concurrentWorkersClaimDifferentEvents() throws Exception {
        register();
        register();
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return provisioning.claimNext().orElseThrow();
            });
            var second = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return provisioning.claimNext().orElseThrow();
            });
            assertThat(first.get(20, TimeUnit.SECONDS).event().eventId())
                    .isNotEqualTo(second.get(20, TimeUnit.SECONDS).event().eventId());
        }
        assertThat(provisioning.claimNext()).isEmpty();
    }

    @Test
    void retryBackoffIsCapped() {
        register();
        var first = provisioning.claimNext().orElseThrow();
        var manyAttempts = new UserCreatedDelivery(first.event(), first.leaseToken(), 100);
        var before = OffsetDateTime.now();
        provisioning.retryLater(manyAttempts, "Unavailable");
        var due = outbox.findById(first.event().eventId()).orElseThrow().getNextAttemptAt();
        assertThat(Duration.between(before, due).toSeconds()).isBetween(299L, 301L);
    }

    @Configuration
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = UserRepository.class)
    @Import(UserProvisioningService.class)
    static class TestConfig {
        @Bean
        UserProvisioningProperties properties() {
            return new UserProvisioningProperties(
                    10, 2, Duration.ofSeconds(1), Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofMinutes(5));
        }

        @Bean
        UserService userService(UserRepository repository, UserProvisioningService provisioning) {
            return new UserService(
                    repository, new BCryptPasswordEncoder(4), Mappers.getMapper(UserMapper.class), provisioning);
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
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();
            return source;
        }

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(source);
            factory.setPackagesToScan("com.cs3219.foc.user.model.entity");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of(
                    "hibernate.hbm2ddl.auto",
                    "validate",
                    "hibernate.physical_naming_strategy",
                    "org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl"));
            return factory;
        }

        @Bean
        JpaTransactionManager transactionManager(EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }
    }
}
