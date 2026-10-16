package com.cs3219.foc.order.service;

import static org.assertj.core.api.Assertions.*;

import com.cs3219.foc.order.config.OrderCreditProperties;
import com.cs3219.foc.order.exception.IdempotencyConflictException;
import com.cs3219.foc.order.model.dto.CreateErrandRequest;
import com.cs3219.foc.order.model.dto.event.CreditReservationResultEvent;
import com.cs3219.foc.order.repository.ErrandRepository;
import jakarta.persistence.EntityManagerFactory;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.web.server.ResponseStatusException;

@SpringJUnitConfig(ErrandPersistenceTests.TestConfig.class)
@EnabledIfEnvironmentVariable(named = "FOC_TEST_DATABASE_URL", matches = ".+")
class ErrandPersistenceTests {
    private static final String SCHEMA =
            "order_test_" + UUID.randomUUID().toString().replace("-", "");

    @Autowired
    private ErrandService service;

    @Autowired
    private CreditReservationResultService results;

    @Autowired
    private ErrandSubmissionOutboxService outbox;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("TRUNCATE errands CASCADE");
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

    private final CreateErrandRequest request = new CreateErrandRequest("COM3", "UTown", "Collect lunch", 80);

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private CreditReservationResultEvent result(UUID id, String outcome) {
        var row = jdbc.queryForMap("SELECT * FROM errands WHERE id = ?", id);
        return new CreditReservationResultEvent(
                UUID.randomUUID(),
                "CreditReservationResultEvent",
                1,
                OffsetDateTime.now(),
                new CreditReservationResultEvent.Payload(
                        (UUID) row.get("reservation_operation_id"),
                        id,
                        (UUID) row.get("requester_id"),
                        ((Number) row.get("reward")).longValue(),
                        outcome));
    }

    @Test
    void concurrentHttpRetriesCreateOneErrandAndOneSubmissionPerRequester() throws Exception {
        var owner = UUID.randomUUID();
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var tasks = new ArrayList<Future<UUID>>();
            for (int i = 0; i < 2; i++)
                tasks.add(executor.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return service.create(owner, "retry-key", request).id();
                }));
            assertThat(tasks.get(0).get(20, TimeUnit.SECONDS))
                    .isEqualTo(tasks.get(1).get(20, TimeUnit.SECONDS));
        }
        assertThat(count("errands")).isEqualTo(1);
        assertThat(count("errand_submission_outbox")).isEqualTo(1);
        assertThat(service.create(owner, "retry-key", request).status()).isEqualTo("PENDING_CREDIT");
        service.create(UUID.randomUUID(), "retry-key", request);
        assertThat(count("errands")).isEqualTo(2);
    }

    @Test
    void changedIdempotentRequestConflictsAndAnotherUserCannotReadTheErrand() {
        var owner = UUID.randomUUID();
        var e = service.create(owner, "key", request);
        assertThatThrownBy(() ->
                        service.create(owner, "key", new CreateErrandRequest("COM3", "UTown", "Collect lunch", 10)))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThatThrownBy(() -> service.get(UUID.randomUUID(), e.id())).isInstanceOf(ResponseStatusException.class);
        assertThat(service.get(owner, e.id()).reward()).isEqualTo(80);
        assertThat(count("errand_submission_outbox")).isEqualTo(1);
    }

    @Test
    void outboxFailureRollsBackCreationAndKeyCanBeRetried() {
        var owner = UUID.randomUUID();
        jdbc.execute("ALTER TABLE errand_submission_outbox ADD CONSTRAINT fail_enqueue CHECK (FALSE)");
        try {
            assertThatThrownBy(() -> service.create(owner, "key", request)).isInstanceOf(RuntimeException.class);
            assertThat(count("errands")).isZero();
            assertThat(count("errand_submission_outbox")).isZero();
        } finally {
            jdbc.execute("ALTER TABLE errand_submission_outbox DROP CONSTRAINT fail_enqueue");
        }
        assertThat(service.create(owner, "key", request).status()).isEqualTo("PENDING_CREDIT");
    }

    @Test
    void onlyMatchingSuccessOpensAndDuplicatesDoNotReopenLaterStates() {
        var owner = UUID.randomUUID();
        var e = service.create(owner, "key", request);
        var event = result(e.id(), "RESERVED");
        results.accept(event);
        assertThat(service.get(owner, e.id()).status()).isEqualTo("PENDING_CREDIT");
        results.processNext();
        assertThat(service.get(owner, e.id()).status()).isEqualTo("OPEN");
        results.accept(event);
        assertThat(results.processNext()).isFalse();
        for (var state : List.of("ACCEPTED", "CANCELLED", "EXPIRED", "COMPLETED")) {
            jdbc.update("UPDATE errands SET status = ? WHERE id = ?", state, e.id());
            results.accept(result(e.id(), "RESERVED"));
            results.processNext();
            assertThat(service.get(owner, e.id()).status()).isEqualTo(state);
        }
        assertThat(service.create(owner, "key", request).id()).isEqualTo(e.id());
        assertThat(count("errand_submission_outbox")).isEqualTo(1);
    }

    @Test
    void rejectionAndCancellationBeforeResultNeverBecomeOpen() {
        var owner = UUID.randomUUID();
        var rejected = service.create(owner, "reject", request);
        results.accept(result(rejected.id(), "INSUFFICIENT_BALANCE"));
        results.processNext();
        results.accept(result(rejected.id(), "RESERVED"));
        results.processNext();
        assertThat(service.get(owner, rejected.id()).status()).isEqualTo("CREDIT_REJECTED");
        assertThat(service.get(owner, rejected.id()).creditOutcome()).isEqualTo("INSUFFICIENT_BALANCE");
        var cancelled = service.create(owner, "cancel", request);
        jdbc.update("UPDATE errands SET status = 'CANCELLED' WHERE id = ?", cancelled.id());
        results.accept(result(cancelled.id(), "RESERVED"));
        results.processNext();
        assertThat(service.get(owner, cancelled.id()).status()).isEqualTo("CANCELLED");
        assertThat(service.get(owner, cancelled.id()).creditOutcome()).isEqualTo("RESERVED");
    }

    @Test
    void changedResultIdentityOrAmountIsRejectedBeforeInboxAcceptance() {
        var owner = UUID.randomUUID();
        var e = service.create(owner, "key", request);
        var event = result(e.id(), "RESERVED");
        var p = event.payload();
        for (var changed : List.of(
                new CreditReservationResultEvent.Payload(UUID.randomUUID(), e.id(), owner, 80, "RESERVED"),
                new CreditReservationResultEvent.Payload(p.operationId(), e.id(), UUID.randomUUID(), 80, "RESERVED"),
                new CreditReservationResultEvent.Payload(p.operationId(), e.id(), owner, 70, "RESERVED"))) {
            assertThatThrownBy(() -> results.accept(new CreditReservationResultEvent(
                            UUID.randomUUID(), event.eventType(), 1, event.occurredAt(), changed)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(count("credit_reservation_result_inbox")).isZero();
        results.accept(event);
        assertThatThrownBy(() -> results.accept(new CreditReservationResultEvent(
                        event.eventId(),
                        event.eventType(),
                        1,
                        event.occurredAt(),
                        new CreditReservationResultEvent.Payload(
                                p.operationId(), e.id(), owner, 80, "INSUFFICIENT_BALANCE"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resultCompletionFailureRollsBackStatusAndResumes() {
        var owner = UUID.randomUUID();
        var e = service.create(owner, "key", request);
        results.accept(result(e.id(), "RESERVED"));
        jdbc.execute(
                "ALTER TABLE credit_reservation_result_inbox ADD CONSTRAINT fail_completion CHECK (processed_at IS NULL)");
        try {
            assertThatThrownBy(() -> results.processNext()).isInstanceOf(RuntimeException.class);
            assertThat(service.get(owner, e.id()).status()).isEqualTo("PENDING_CREDIT");
            assertThat(service.get(owner, e.id()).creditOutcome()).isNull();
        } finally {
            jdbc.execute("ALTER TABLE credit_reservation_result_inbox DROP CONSTRAINT fail_completion");
        }
        results.processNext();
        assertThat(service.get(owner, e.id()).status()).isEqualTo("OPEN");
    }

    @Test
    void lostConfirmAndExpiredLeaseRepublishSameSubmission() {
        var owner = UUID.randomUUID();
        var e = service.create(owner, "key", request);
        var original = outbox.claimNext().orElseThrow();
        assertThat(original.event().payload().errandId()).isEqualTo(e.id());
        assertThat(outbox.claimNext()).isEmpty();
        outbox.retryLater(original, "BrokerUnavailable");
        jdbc.update("UPDATE errand_submission_outbox SET next_attempt_at = CURRENT_TIMESTAMP");
        var retry = outbox.claimNext().orElseThrow();
        assertThat(retry.event()).isEqualTo(original.event());
        jdbc.update("UPDATE errand_submission_outbox SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second'");
        var recovered = outbox.claimNext().orElseThrow();
        assertThat(recovered.event()).isEqualTo(original.event());
        outbox.markPublished(retry);
        assertThat(jdbc.queryForObject("SELECT published_at IS NULL FROM errand_submission_outbox", Boolean.class))
                .isTrue();
        outbox.markPublished(recovered);
        assertThat(outbox.claimNext()).isEmpty();
        assertThat(service.get(owner, e.id()).status()).isEqualTo("PENDING_CREDIT");
    }

    @TestConfiguration
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = ErrandRepository.class)
    @Import({ErrandService.class, CreditReservationResultService.class, ErrandSubmissionOutboxService.class})
    static class TestConfig {
        @Bean
        OrderCreditProperties properties() {
            return new OrderCreditProperties(
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
