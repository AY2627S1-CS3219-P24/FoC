package com.cs3219.foc.user.service;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.cs3219.foc.user.infrastructure.messaging.producer.UserCreatedEventPublisher;
import com.cs3219.foc.user.mapper.UserMapper;
import com.cs3219.foc.user.model.dto.RegisterUserRequest;
import com.cs3219.foc.user.model.dto.event.UserCreatedEvent;
import com.cs3219.foc.user.repository.UserRepository;
import jakarta.persistence.EntityManagerFactory;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.annotation.DirtiesContext.HierarchyMode;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
        classes = UserEventPublicationPersistenceTests.TestConfig.class,
        properties = {
            "spring.flyway.enabled=false", "spring.modulith.events.jdbc.schema-initialization.enabled=false",
            "spring.modulith.events.staleness.published=0s", "spring.modulith.events.staleness.processing=0s",
            "spring.modulith.events.staleness.resubmitted=0s", "spring.cloud.aws.s3.enabled=false"
        })
@TestExecutionListeners(
        listeners = UserEventPublicationPersistenceTests.SchemaCleanup.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
@EnabledIfEnvironmentVariable(named = "FOC_TEST_DATABASE_URL", matches = ".+")
class UserEventPublicationPersistenceTests {
    private static final String SCHEMA =
            "publication_" + UUID.randomUUID().toString().replace("-", "");

    @Autowired
    private UserService userService;

    @Autowired
    private UserCreatedEventPublisher publisher;

    @Autowired
    private FailedEventPublications publications;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JpaTransactionManager transactionManager;

    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        publisher = AopTestUtils.getUltimateTargetObject(publisher);
        reset(publisher);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("TRUNCATE event_publication, refresh_tokens, users CASCADE");
    }

    private UUID register() {
        return UUID.fromString(userService
                .registerUser(new RegisterUserRequest(UUID.randomUUID() + "@u.nus.edu", "Test", "password123"))
                .id());
    }

    private void awaitState(String state) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM event_publication WHERE status = ?", Long.class, state))
                .isEqualTo(1));
    }

    private void awaitPublicationCompleted() {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(
                        jdbc.queryForObject("SELECT count(*) FROM event_publication", Long.class))
                .isZero());
    }

    @Test
    void registrationRecordsPublicationAndDeliversAfterCommit() {
        var user = register();
        awaitPublicationCompleted();
        var captor = ArgumentCaptor.forClass(UserCreatedEvent.class);
        verify(publisher).publish(captor.capture());
        assertThat(captor.getValue().payload().userId()).isEqualTo(user);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE id = ?", Long.class, user))
                .isEqualTo(1);
    }

    @Test
    void registrationRollbackLeavesNoUserOrPublication() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            register();
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Long.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_publication", Long.class))
                .isZero();
        verifyNoInteractions(publisher);
    }

    @Test
    void publicationWriteFailureRollsBackRegistration() {
        jdbc.execute("ALTER TABLE event_publication ADD CONSTRAINT fail_publication CHECK (false) NOT VALID");
        try {
            assertThatThrownBy(this::register).isInstanceOf(RuntimeException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Long.class))
                    .isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM event_publication", Long.class))
                    .isZero();
            verifyNoInteractions(publisher);
        } finally {
            jdbc.execute("ALTER TABLE event_publication DROP CONSTRAINT fail_publication");
        }
    }

    @Test
    void failedPublicationRetriesWithSameEventIdentity() {
        doThrow(new IllegalStateException("Broker unavailable")).when(publisher).publish(any());
        register();
        awaitState("FAILED");
        doNothing().when(publisher).publish(any());
        publications.resubmit(ResubmissionOptions.defaults());
        awaitPublicationCompleted();
        var captor = ArgumentCaptor.forClass(UserCreatedEvent.class);
        verify(publisher, times(2)).publish(captor.capture());
        var first = captor.getAllValues().getFirst();
        var retried = captor.getAllValues().getLast();
        assertThat(retried.eventId()).isEqualTo(first.eventId());
        assertThat(retried.payload()).isEqualTo(first.payload());
        assertThat(retried.occurredAt().toInstant())
                .isEqualTo(first.occurredAt().toInstant());
    }

    static class SchemaCleanup extends AbstractTestExecutionListener {
        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE;
        }

        @Override
        public void afterTestClass(TestContext context) throws Exception {
            context.markApplicationContextDirty(HierarchyMode.CURRENT_LEVEL);
            try (var connection = DriverManager.getConnection(
                            System.getenv("FOC_TEST_DATABASE_URL"),
                            System.getenv("FOC_TEST_DATABASE_USERNAME"),
                            System.getenv("FOC_TEST_DATABASE_PASSWORD"));
                    var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
            }
        }
    }

    @Configuration
    @EnableAutoConfiguration
    @EnableAsync
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = UserRepository.class)
    static class TestConfig {
        @Bean
        UserCreatedEventPublisher publisher() {
            return mock(UserCreatedEventPublisher.class);
        }

        @Bean
        UserService userService(UserRepository repository, ApplicationEventPublisher events) {
            return new UserService(
                    repository,
                    new BCryptPasswordEncoder(4),
                    Mappers.getMapper(UserMapper.class),
                    events,
                    mock(EmailVerificationService.class),
                    mock(EmailChangeService.class),
                    mock(AvatarStorageService.class));
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
