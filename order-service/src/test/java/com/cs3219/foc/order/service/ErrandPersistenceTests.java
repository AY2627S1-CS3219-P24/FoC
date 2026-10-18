package com.cs3219.foc.order.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cs3219.foc.order.exception.IdempotencyConflictException;
import com.cs3219.foc.order.infrastructure.credit.CreditReservationClient;
import com.cs3219.foc.order.model.dto.CreateErrandRequest;
import com.cs3219.foc.order.model.dto.CreditReservationRequest;
import com.cs3219.foc.order.model.dto.CreditReservationResponse;
import com.cs3219.foc.order.repository.ErrandRepository;
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
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.HttpStatus;
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
    private CreditReservationClient credit;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;
    private final CreateErrandRequest request = new CreateErrandRequest("COM3", "UTown", "Collect lunch", 80);

    @BeforeEach
    void clean() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("TRUNCATE errands CASCADE");
        reset(credit);
        when(credit.reserve(any(), any())).thenReturn(new CreditReservationResponse("RESERVED"));
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

    @Test
    void concurrentRetriesCreateOneErrandWithOneReservationIdentity() throws Exception {
        var owner = UUID.randomUUID();
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var tasks = new ArrayList<Future<UUID>>();
            for (int i = 0; i < 2; i++)
                tasks.add(executor.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return service.create(owner, "key", request, "token").id();
                }));
            assertThat(tasks.get(0).get(20, TimeUnit.SECONDS))
                    .isEqualTo(tasks.get(1).get(20, TimeUnit.SECONDS));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM errands", Long.class))
                .isEqualTo(1);
        assertThat(service.create(owner, "key", request, "token").status()).isEqualTo("OPEN");
        var captor = ArgumentCaptor.forClass(CreditReservationRequest.class);
        verify(credit, atLeastOnce()).reserve(captor.capture(), eq("token"));
        assertThat(captor.getAllValues().stream().distinct().count()).isEqualTo(1);
    }

    @Test
    void changedPayloadConflictsAndAnotherUserCannotReadErrand() {
        var owner = UUID.randomUUID();
        var e = service.create(owner, "key", request, "token");
        assertThatThrownBy(() -> service.create(
                        owner, "key", new CreateErrandRequest("COM3", "UTown", "Collect lunch", 10), "token"))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThatThrownBy(() -> service.get(UUID.randomUUID(), e.id())).isInstanceOf(ResponseStatusException.class);
        verify(credit, times(1)).reserve(any(), any());
    }

    @Test
    void insufficientCreditsAreReturnedImmediatelyAndDecisionIsStable() {
        when(credit.reserve(any(), any())).thenReturn(new CreditReservationResponse("INSUFFICIENT_BALANCE"));
        var owner = UUID.randomUUID();
        var rejected = service.create(owner, "key", request, "token");
        assertThat(rejected.status()).isEqualTo("CREDIT_REJECTED");
        assertThat(rejected.creditOutcome()).isEqualTo("INSUFFICIENT_BALANCE");
        when(credit.reserve(any(), any())).thenReturn(new CreditReservationResponse("RESERVED"));
        assertThat(service.create(owner, "key", request, "token").status()).isEqualTo("CREDIT_REJECTED");
        verify(credit, times(1)).reserve(any(), any());
    }

    @Test
    void lostCreditResponseKeepsIdentityForExplicitRetry() {
        var owner = UUID.randomUUID();
        when(credit.reserve(any(), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE))
                .thenReturn(new CreditReservationResponse("RESERVED"));
        assertThatThrownBy(() -> service.create(owner, "key", request, "token"))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(service.create(owner, "key", request, "token").status()).isEqualTo("OPEN");
        var captor = ArgumentCaptor.forClass(CreditReservationRequest.class);
        verify(credit, times(2)).reserve(captor.capture(), eq("token"));
        assertThat(captor.getAllValues().getFirst())
                .isEqualTo(captor.getAllValues().getLast());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM errands", Long.class))
                .isEqualTo(1);
    }

    @Test
    void orderWriteFailureAfterReservationRetriesSameCreditOperation() {
        var owner = UUID.randomUUID();
        jdbc.execute("ALTER TABLE errands ADD CONSTRAINT fail_funding CHECK(credit_outcome IS NULL)");
        try {
            assertThatThrownBy(() -> service.create(owner, "key", request, "token"))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            jdbc.execute("ALTER TABLE errands DROP CONSTRAINT fail_funding");
        }
        assertThat(service.create(owner, "key", request, "token").status()).isEqualTo("OPEN");
        var captor = ArgumentCaptor.forClass(CreditReservationRequest.class);
        verify(credit, times(2)).reserve(captor.capture(), eq("token"));
        assertThat(captor.getAllValues().getFirst())
                .isEqualTo(captor.getAllValues().getLast());
    }

    @Test
    void duplicateCreationDoesNotReopenLaterState() {
        var owner = UUID.randomUUID();
        var e = service.create(owner, "key", request, "token");
        jdbc.update("UPDATE errands SET status='COMPLETED' WHERE id=?", e.id());
        assertThat(service.create(owner, "key", request, "token").status()).isEqualTo("COMPLETED");
        verify(credit, times(1)).reserve(any(), any());
    }

    @TestConfiguration
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = ErrandRepository.class)
    @Import(ErrandService.class)
    static class TestConfig {
        @Bean
        CreditReservationClient credit() {
            return mock(CreditReservationClient.class);
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
