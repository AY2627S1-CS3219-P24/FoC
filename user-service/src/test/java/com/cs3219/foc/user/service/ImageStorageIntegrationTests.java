package com.cs3219.foc.user.service;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

import com.cs3219.foc.user.exception.AvatarException;
import com.cs3219.foc.user.exception.StorageException;
import com.cs3219.foc.user.infrastructure.mail.AccountMailListener;
import com.cs3219.foc.user.infrastructure.storage.*;
import com.cs3219.foc.user.mapper.*;
import com.cs3219.foc.user.model.dto.event.AvatarDeletionRequestedEvent;
import com.cs3219.foc.user.model.dto.event.EmailTokenCreatedEvent;
import com.cs3219.foc.user.model.entity.*;
import com.cs3219.foc.user.repository.*;
import com.cs3219.foc.user.security.AuthenticatedUserResolver;
import com.cs3219.foc.user.security.AuthenticationContextService;
import com.cs3219.foc.user.security.CurrentUserService;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import javax.imageio.ImageIO;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.*;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataAccessException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

@Testcontainers
@ActiveProfiles("dev")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
        classes = ImageStorageIntegrationTests.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.cloud.aws.s3.path-style-access-enabled=true"})
class ImageStorageIntegrationTests {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

    static final String BUCKET = "foc-user-integration";

    @Container
    static final GenericContainer<?> FLOCI = new GenericContainer<>(DockerImageName.parse("floci/floci:2.3.0-compat"))
            .withExposedPorts(4566)
            .withEnv("FLOCI_SERVICES_S3_ENFORCE_AUTH", "true");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        var endpoint = URI.create("http://" + FLOCI.getHost() + ":" + FLOCI.getMappedPort(4566));
        try (var s3 = S3Client.builder()
                .endpointOverride(endpoint)
                .region(Region.AP_SOUTHEAST_2)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .forcePathStyle(true)
                .build()) {
            s3.createBucket(CreateBucketRequest.builder()
                    .bucket(BUCKET)
                    .createBucketConfiguration(CreateBucketConfiguration.builder()
                            .locationConstraint(BucketLocationConstraint.AP_SOUTHEAST_2)
                            .build())
                    .build());
            s3.putPublicAccessBlock(PutPublicAccessBlockRequest.builder()
                    .bucket(BUCKET)
                    .publicAccessBlockConfiguration(PublicAccessBlockConfiguration.builder()
                            .blockPublicAcls(true)
                            .blockPublicPolicy(true)
                            .ignorePublicAcls(true)
                            .restrictPublicBuckets(true)
                            .build())
                    .build());
        }
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "&currentSchema=users");
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("storage.s3.bucket", () -> BUCKET);
        registry.add("spring.cloud.aws.s3.region", () -> "ap-southeast-2");
        registry.add("spring.cloud.aws.s3.endpoint", endpoint::toString);
    }

    @Configuration
    @ConfigurationPropertiesScan("com.cs3219.foc.user.config")
    @EnableAsync
    @EnableAutoConfiguration
    @AutoConfigurationPackage(basePackages = "com.cs3219.foc.user")
    @EntityScan(basePackageClasses = User.class)
    @EnableJpaRepositories(basePackageClasses = UserRepository.class)
    @Import({
        S3ObjectStorage.class,
        AvatarDeletionListener.class,
        AccountMailListener.class,
        AvatarStorageService.class,
        UserMapperImpl.class,
        AvatarService.class,
        AvatarImageProcessor.class,
        CurrentUserService.class,
        AuthenticationContextService.class,
        AuthenticatedUserResolver.class
    })
    static class Config {
        @Bean
        JavaMailSender mailSender() {
            return mock(JavaMailSender.class);
        }

        @Bean
        PasswordEncoder passwordEncoder() {
            return new BCryptPasswordEncoder();
        }

        @Bean
        EmailVerificationService emailVerificationService() {
            return mock(EmailVerificationService.class);
        }
    }

    @Autowired
    UserRepository userRepository;

    @Autowired
    AvatarService avatarService;

    @Autowired
    AvatarStorageService storage;

    @Autowired
    S3Client s3;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @MockitoSpyBean
    ObjectStorage objectStorage;

    @MockitoSpyBean
    DataSource dataSource;

    @Autowired
    IncompleteEventPublications publications;

    @Autowired
    ApplicationEventPublisher eventPublisher;

    @Autowired
    JavaMailSender mailSender;

    final HttpClient http = HttpClient.newHttpClient();

    User owner() {
        return userRepository.saveAndFlush(User.builder()
                .email(UUID.randomUUID() + "@u.nus.edu")
                .name("Image Test")
                .roles(List.of(UserRole.USER))
                .passwordHash("hash")
                .build());
    }

    MockMultipartFile photo() throws Exception {
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB), "png", bytes);
        return new MockMultipartFile("file", "photo.png", "image/png", bytes.toByteArray());
    }

    String key(UUID id) {
        return userRepository.findById(id).orElseThrow().getAvatarKey();
    }

    byte[] get(String url) throws Exception {
        var response = http.send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }

    List<String> storedKeys(UUID ownerId) {
        return s3
                .listObjectsV2(ListObjectsV2Request.builder()
                        .bucket(BUCKET)
                        .prefix("avatars/" + ownerId + "/")
                        .build())
                .contents()
                .stream()
                .map(S3Object::key)
                .toList();
    }

    @Test
    void uploadIsProcessedAndPrivateAndDeletesReplacedAndRemovedImages() throws Exception {
        var owner = owner();
        var first = avatarService.uploadAvatar(owner.getId(), photo());
        var oldKey = key(owner.getId());
        assertThat(oldKey).startsWith("avatars/" + owner.getId() + "/");
        var url = first.avatarUrl();
        assertThat(ImageIO.read(new ByteArrayInputStream(get(url)))).isNotNull();
        var anonymous = url.substring(0, url.indexOf('?'));
        assertThat(http.send(
                                HttpRequest.newBuilder(URI.create(anonymous))
                                        .GET()
                                        .build(),
                                HttpResponse.BodyHandlers.discarding())
                        .statusCode())
                .isEqualTo(403);
        avatarService.uploadAvatar(owner.getId(), photo());
        var newKey = key(owner.getId());
        assertThat(newKey).isNotEqualTo(oldKey);
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(storedKeys(owner.getId())).containsExactly(newKey));
        avatarService.removeAvatar(owner.getId());
        assertThat(key(owner.getId())).isNull();
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(storedKeys(owner.getId())).isEmpty());
    }

    @Test
    void lostPutResponseDeletesUploadedObjectOutsideTransaction() throws Exception {
        var owner = owner();
        var failure = new StorageException(new RuntimeException("PUT response lost"));
        doAnswer(invocation -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                            .isFalse();
                    invocation.callRealMethod();
                    throw failure;
                })
                .when(objectStorage)
                .putObject(startsWith("avatars/" + owner.getId()), any(), anyString());
        doAnswer(invocation -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                            .isFalse();
                    return invocation.callRealMethod();
                })
                .when(objectStorage)
                .deleteObject(startsWith("avatars/" + owner.getId()));
        try {
            assertThatThrownBy(() -> avatarService.uploadAvatar(owner.getId(), photo()))
                    .isSameAs(failure);
            assertThat(storedKeys(owner.getId())).isEmpty();
            assertThat(key(owner.getId())).isNull();
        } finally {
            reset(objectStorage);
        }
    }

    @Test
    void connectionFailureAfterPutDeletesObjectWithoutDatabaseAccess() throws Exception {
        var owner = owner();
        var connectionFailure = new SQLException("PostgreSQL unavailable");
        doAnswer(invocation -> {
                    invocation.callRealMethod();
                    doThrow(connectionFailure).when(dataSource).getConnection();
                    return null;
                })
                .when(objectStorage)
                .putObject(startsWith("avatars/" + owner.getId()), any(), anyString());
        doAnswer(invocation -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                            .isFalse();
                    return invocation.callRealMethod();
                })
                .when(objectStorage)
                .deleteObject(startsWith("avatars/" + owner.getId()));
        try {
            assertThatThrownBy(() -> avatarService.uploadAvatar(owner.getId(), photo()))
                    .isInstanceOf(CannotCreateTransactionException.class)
                    .hasRootCause(connectionFailure);
            assertThat(storedKeys(owner.getId())).isEmpty();
            verify(objectStorage).deleteObject(startsWith("avatars/" + owner.getId()));
        } finally {
            doCallRealMethod().when(dataSource).getConnection();
            reset(objectStorage);
        }
        assertThat(key(owner.getId())).isNull();
    }

    @Test
    void enclosingRollbackPreservesOldImageAndDeletesNewUpload() throws Exception {
        var owner = owner();
        avatarService.uploadAvatar(owner.getId(), photo());
        var previousKey = key(owner.getId());
        var file = photo();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            avatarService.uploadAvatar(owner.getId(), file);
            assertThat(storedKeys(owner.getId())).hasSize(2);
            status.setRollbackOnly();
        });
        assertThat(storedKeys(owner.getId())).containsExactly(previousKey);
        assertThat(key(owner.getId())).isEqualTo(previousKey);
    }

    @Test
    void invalidImagePreservesActiveImage() throws Exception {
        var owner = owner();
        avatarService.uploadAvatar(owner.getId(), photo());
        var active = key(owner.getId());
        assertThatThrownBy(() -> avatarService.uploadAvatar(
                        owner.getId(), new MockMultipartFile("file", "bad.png", "image/png", new byte[] {1, 2})))
                .isInstanceOf(AvatarException.class);
        assertThat(key(owner.getId())).isEqualTo(active);
        assertThat(get(storage.createPresignedUrl(active))).isNotEmpty();
    }

    @Test
    void failedDatabaseUpdatePreservesActiveImageAndDeletesNewUpload() throws Exception {
        var owner = owner();
        avatarService.uploadAvatar(owner.getId(), photo());
        var active = key(owner.getId());
        jdbc.execute("ALTER TABLE users ADD CONSTRAINT test_image_failure CHECK (id != '" + owner.getId()
                + "' OR avatar_key IS NULL OR avatar_key = '" + active + "')");
        try {
            assertThatThrownBy(() -> avatarService.uploadAvatar(owner.getId(), photo()))
                    .isInstanceOf(DataAccessException.class);
        } finally {
            jdbc.execute("ALTER TABLE users DROP CONSTRAINT test_image_failure");
        }
        assertThat(key(owner.getId())).isEqualTo(active);
        assertThat(get(storage.createPresignedUrl(active))).isNotEmpty();
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(storedKeys(owner.getId())).containsExactly(active));
    }

    @Test
    void deletesReplacedImageOnlyAfterCommit() throws Exception {
        var owner = owner();
        avatarService.uploadAvatar(owner.getId(), photo());
        var previousKey = key(owner.getId());
        var file = photo();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            avatarService.uploadAvatar(owner.getId(), file);
            assertThat(storedKeys(owner.getId())).hasSize(2).contains(previousKey);
        });
        var newKey = key(owner.getId());
        assertThat(newKey).isNotEqualTo(previousKey);
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(storedKeys(owner.getId())).containsExactly(newKey));
    }

    @Test
    void persistsFailedDeletionAndRemovesPublicationAfterRetry() throws Exception {
        var owner = owner();
        avatarService.uploadAvatar(owner.getId(), photo());
        var oldKey = key(owner.getId());
        doThrow(new StorageException(new RuntimeException("S3 unavailable")))
                .when(objectStorage)
                .deleteObject(oldKey);
        avatarService.uploadAvatar(owner.getId(), photo());
        var activeKey = key(owner.getId());
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM event_publication WHERE status = 'FAILED' AND serialized_event LIKE ?",
                        Integer.class,
                        "%" + oldKey + "%"))
                .isEqualTo(1));
        assertThat(storedKeys(owner.getId())).containsExactlyInAnyOrder(oldKey, activeKey);
        doCallRealMethod().when(objectStorage).deleteObject(oldKey);
        publications.resubmitIncompletePublications(
                publication -> publication.getEvent() instanceof AvatarDeletionRequestedEvent event
                        && event.key().equals(oldKey));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(storedKeys(owner.getId())).containsExactly(activeKey);
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM event_publication WHERE serialized_event LIKE ?",
                            Integer.class,
                            "%" + oldKey + "%"))
                    .isZero();
        });
        assertThat(key(owner.getId())).isEqualTo(activeKey);
    }

    @Test
    void verificationMailIsDeliveredWithoutPersistingItsToken() {
        var token = UUID.randomUUID().toString();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            eventPublisher.publishEvent(new EmailTokenCreatedEvent(
                    UUID.randomUUID(), "mail-test@u.nus.edu", EmailTokenPurpose.REGISTRATION, token));
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM event_publication WHERE event_type = ?",
                            Integer.class,
                            EmailTokenCreatedEvent.class.getName()))
                    .isZero();
        });
        verify(mailSender, timeout(10000))
                .send(argThat((SimpleMailMessage message) -> message.getText().contains(token)));
    }
}
