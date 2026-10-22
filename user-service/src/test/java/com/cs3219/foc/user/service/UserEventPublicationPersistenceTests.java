package com.cs3219.foc.user.service;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.cs3219.foc.user.UserServiceApplication;
import com.cs3219.foc.user.infrastructure.messaging.producer.SnsUserCreatedEventPublisher;
import com.cs3219.foc.user.mapper.UserMapperImpl;
import com.cs3219.foc.user.model.dto.RegisterUserRequest;
import com.cs3219.foc.user.model.dto.event.UserCreatedEvent;
import com.cs3219.foc.user.security.CurrentUserService;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@ActiveProfiles("test")
@SpringBootTest(
        classes = UserEventPublicationPersistenceTests.TestConfig.class,
        properties = {
            "spring.modulith.events.staleness.published=0s",
            "spring.modulith.events.staleness.processing=0s",
            "spring.modulith.events.staleness.resubmitted=0s"
        })
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class UserEventPublicationPersistenceTests {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

    @MockitoBean
    private EmailVerificationService emailVerificationService;

    @MockitoBean
    private EmailChangeService emailChangeService;

    @MockitoBean
    private AvatarStorageService avatarStorageService;

    @MockitoBean
    private CurrentUserService currentUserService;

    @Autowired
    private UserService userService;

    @Autowired
    private SnsUserCreatedEventPublisher publisher;

    @Autowired
    private FailedEventPublications publications;

    @Autowired
    private JpaTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        publisher = AopTestUtils.getUltimateTargetObject(publisher);
        reset(publisher);
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

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @AutoConfigurationPackage(basePackageClasses = UserServiceApplication.class)
    @EnableAsync
    @Import({UserService.class, UserMapperImpl.class})
    static class TestConfig {
        @Bean
        SnsUserCreatedEventPublisher publisher() {
            return mock(SnsUserCreatedEventPublisher.class);
        }

        @Bean
        PasswordEncoder passwordEncoder() {
            return new BCryptPasswordEncoder(4);
        }
    }
}
