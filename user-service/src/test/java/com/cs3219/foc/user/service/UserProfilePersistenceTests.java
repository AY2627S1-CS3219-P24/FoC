package com.cs3219.foc.user.service;

import static com.cs3219.foc.user.support.AvatarTestImages.image;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.cs3219.foc.user.config.AuthProperties;
import com.cs3219.foc.user.config.AvatarProperties;
import com.cs3219.foc.user.exception.AvatarException;
import com.cs3219.foc.user.exception.EntityAlreadyExistsException;
import com.cs3219.foc.user.exception.InvalidPasswordException;
import com.cs3219.foc.user.exception.InvalidRefreshTokenException;
import com.cs3219.foc.user.exception.UserNotFoundException;
import com.cs3219.foc.user.infrastructure.storage.AvatarStorage;
import com.cs3219.foc.user.infrastructure.storage.LocalAvatarStorage;
import com.cs3219.foc.user.mapper.UserMapper;
import com.cs3219.foc.user.model.dto.ChangePasswordRequest;
import com.cs3219.foc.user.model.dto.LoginRequest;
import com.cs3219.foc.user.model.dto.UpdateUserProfileRequest;
import com.cs3219.foc.user.model.entity.AvatarCleanupTask;
import com.cs3219.foc.user.model.entity.User;
import com.cs3219.foc.user.model.entity.UserRole;
import com.cs3219.foc.user.repository.AvatarCleanupRepository;
import com.cs3219.foc.user.repository.RefreshTokenRepository;
import com.cs3219.foc.user.repository.UserRepository;
import com.cs3219.foc.user.security.CustomUserDetailsService;
import jakarta.persistence.EntityManagerFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.mapstruct.factory.Mappers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

/** Runs the real migrations and service transactions in a disposable PostgreSQL schema. */
@SpringJUnitConfig(UserProfilePersistenceTests.TestConfig.class)
@EnabledIfEnvironmentVariable(named = "FOC_TEST_DATABASE_URL", matches = ".+")
class UserProfilePersistenceTests {
    private static final String SCHEMA =
            "profile_test_" + UUID.randomUUID().toString().replace("-", "");

    @TempDir
    static Path avatarDirectory;

    @Autowired
    private AvatarStorage avatarStorage;

    @Autowired
    private AvatarService avatars;

    @Autowired
    private AvatarCleanupService avatarCleanup;

    @Autowired
    private AvatarCleanupRepository avatarTasks;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private UserRepository repository;

    @Autowired
    private UserService service;

    @Autowired
    private PasswordService passwordService;

    @Autowired
    private AuthService authService;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private RefreshTokenRepository refreshTokens;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void cleanUsers() throws Exception {
        reset(avatarStorage);
        repository.deleteAll();
        avatarTasks.deleteAll();
        try (var files = Files.list(avatarDirectory)) {
            for (var file : files.toList()) {
                Files.delete(file);
            }
        }
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

    private User createUser(String email) {
        return repository.saveAndFlush(User.builder()
                .name("Alex Tan")
                .email(email)
                .roles(List.of(UserRole.USER))
                .passwordHash("unchanged-hash")
                .build());
    }

    @Test
    void uploadsReplacesAndRemovesAvatarWithoutChangingProfileOrPassword() throws Exception {
        var user = createPasswordUser("avatar@example.com");
        assertThat(service.getUserProfile(user.getId()).avatarUrl()).isNull();
        var first = avatars.upload(user.getId(), image("jpeg", 32, 32));
        assertThat(first.avatarUrl()).startsWith("/users/me/avatar?v=");
        var oldKey = repository.findById(user.getId()).orElseThrow().getAvatarKey();
        assertThat(avatars.read(user.getId())).isNotEmpty();
        var second = avatars.upload(user.getId(), image("png", 64, 64));
        assertThat(second.avatarUrl()).isNotEqualTo(first.avatarUrl());
        assertThat(Files.exists(avatarDirectory.resolve(oldKey))).isFalse();
        service.updateUserProfile(
                user.getId(), new UpdateUserProfileRequest("New Name", "avatar@example.com", null, null));
        passwordService.changePassword(user.getId(), new ChangePasswordRequest("CurrentPassword1", "NewPassword2"));
        assertThat(service.getUserProfile(user.getId()).avatarUrl()).isEqualTo(second.avatarUrl());
        avatars.remove(user.getId());
        avatars.remove(user.getId());
        assertThat(service.getUserProfile(user.getId()).avatarUrl()).isNull();
        assertThat(service.getUserProfile(user.getId()).name()).isEqualTo("New Name");
        assertThat(passwordEncoder.matches(
                        "NewPassword2",
                        repository.findById(user.getId()).orElseThrow().getPasswordHash()))
                .isTrue();
        assertThatThrownBy(() -> avatars.read(user.getId()))
                .isInstanceOfSatisfying(
                        AvatarException.class, ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThat(avatarFileCount()).isZero();
    }

    @Test
    void anotherAccountCannotReadOrRemoveThisUsersAvatar() throws Exception {
        var first = createUser("first-avatar@example.com");
        var second = createUser("second-avatar@example.com");
        avatars.upload(first.getId(), image("png", 10, 10));
        assertThatThrownBy(() -> avatars.read(second.getId())).isInstanceOf(AvatarException.class);
        avatars.remove(second.getId());
        assertThat(avatars.read(first.getId())).isNotEmpty();
    }

    @Test
    void failedStorageWritePreservesPreviousAvatarAndCleansPartialFile() throws Exception {
        var user = createUser("avatar@example.com");
        var original = avatars.upload(user.getId(), image("png", 10, 10));
        doAnswer(invocation -> {
                    invocation.callRealMethod();
                    throw new AvatarException(HttpStatus.SERVICE_UNAVAILABLE, "Simulated write failure");
                })
                .when(avatarStorage)
                .write(anyString(), any());
        assertThatThrownBy(() -> avatars.upload(user.getId(), image("png", 20, 20)))
                .isInstanceOf(AvatarException.class);
        assertThat(service.getUserProfile(user.getId()).avatarUrl()).isEqualTo(original.avatarUrl());
        assertThat(avatars.read(user.getId())).isNotEmpty();
        assertThat(avatarFileCount()).isEqualTo(1);
        assertThat(avatarTasks.count()).isZero();
    }

    @Test
    void databaseFailurePreservesPreviousAvatarAndDeletesNewFile() throws Exception {
        var user = createUser("avatar@example.com");
        var original = avatars.upload(user.getId(), image("png", 10, 10));
        var originalKey = repository.findById(user.getId()).orElseThrow().getAvatarKey();
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            // Only the existing generated key is allowed, deliberately failing the next update.
            statement.execute(
                    "ALTER TABLE users ADD CONSTRAINT test_avatar_failure CHECK (avatar_key IS NULL OR avatar_key = '"
                            + originalKey + "')");
            try {
                assertThatThrownBy(() -> avatars.upload(user.getId(), image("png", 20, 20)))
                        .isInstanceOfSatisfying(AvatarException.class, ex -> assertThat(ex.getStatus())
                                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
            } finally {
                statement.execute("ALTER TABLE users DROP CONSTRAINT test_avatar_failure");
            }
        }
        assertThat(service.getUserProfile(user.getId()).avatarUrl()).isEqualTo(original.avatarUrl());
        assertThat(avatars.read(user.getId())).isNotEmpty();
        assertThat(avatarFileCount()).isEqualTo(1);
        assertThat(avatarTasks.count()).isZero();
    }

    @Test
    void failedDeletionIsRetriedWithoutFailingSuccessfulReplacement() throws Exception {
        var user = createUser("avatar@example.com");
        avatars.upload(user.getId(), image("png", 10, 10));
        var previous = repository.findById(user.getId()).orElseThrow().getAvatarKey();
        doThrow(new AvatarException(HttpStatus.SERVICE_UNAVAILABLE, "Simulated delete failure"))
                .when(avatarStorage)
                .delete(previous);
        var replacement = avatars.upload(user.getId(), image("png", 20, 20));
        assertThat(service.getUserProfile(user.getId()).avatarUrl()).isEqualTo(replacement.avatarUrl());
        assertThat(avatarTasks.count()).isEqualTo(1);
        assertThat(avatarFileCount()).isEqualTo(2);
        reset(avatarStorage);
        avatarCleanup.cleanPending();
        assertThat(avatarTasks.count()).isZero();
        assertThat(avatarFileCount()).isEqualTo(1);
        assertThat(avatars.read(user.getId())).isNotEmpty();
    }

    @Test
    void cleanupRecoversAbandonedUploadsAndNeverDeletesReferencedImage() throws Exception {
        var abandoned = UUID.randomUUID() + ".png";
        avatarCleanup.reserve(abandoned);
        avatarStorage.write(abandoned, image("png", 10, 10).getBytes());
        avatarCleanup.cleanPending();
        assertThat(avatarFileCount()).isEqualTo(1); // Ten-minute reservation grace period.
        avatarTasks.saveAndFlush(
                new AvatarCleanupTask(abandoned, OffsetDateTime.now().minusMinutes(1)));
        avatarCleanup.cleanPending();
        assertThat(avatarFileCount()).isZero();
        var user = createUser("avatar@example.com");
        avatars.upload(user.getId(), image("png", 10, 10));
        var active = repository.findById(user.getId()).orElseThrow().getAvatarKey();
        avatarTasks.saveAndFlush(
                new AvatarCleanupTask(active, OffsetDateTime.now().minusMinutes(1)));
        avatarCleanup.cleanPending();
        assertThat(avatars.read(user.getId())).isNotEmpty();
        assertThat(avatarTasks.count()).isZero();
    }

    @Test
    void concurrentReplacementsKeepOnlyTheWinningImage() throws Exception {
        var user = createUser("avatar@example.com");
        avatars.upload(user.getId(), image("png", 10, 10));
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<String> upload = () -> {
                barrier.await(10, TimeUnit.SECONDS);
                return avatars.upload(user.getId(), image("png", 20, 20)).avatarUrl();
            };
            var first = executor.submit(upload);
            var second = executor.submit(upload);
            var urls = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            assertThat(urls).contains(service.getUserProfile(user.getId()).avatarUrl());
        }
        assertThat(avatars.read(user.getId())).isNotEmpty();
        assertThat(avatarFileCount()).isEqualTo(1);
        assertThat(avatarTasks.count()).isZero();
    }

    @Test
    void uploadForMissingAccountLeavesNoImage() {
        assertThatThrownBy(() -> avatars.upload(UUID.randomUUID(), image("png", 10, 10)))
                .isInstanceOf(UserNotFoundException.class);
        assertThat(avatarTasks.count()).isZero();
    }

    private long avatarFileCount() throws Exception {
        try (var files = Files.list(avatarDirectory)) {
            return files.count();
        }
    }

    private User createPasswordUser(String email) {
        var user = createUser(email);
        user.setPasswordHash(passwordEncoder.encode("CurrentPassword1"));
        return repository.saveAndFlush(user);
    }

    @Test
    void passwordChangeRequiresNewLoginAndRevokesAllOfOnlyThisUsersSessions() {
        var user = createPasswordUser("password@example.com");
        createPasswordUser("other@example.com");
        var first = authService.login(new LoginRequest("password@example.com", "CurrentPassword1"));
        var second = authService.login(new LoginRequest("password@example.com", "CurrentPassword1"));
        var other = authService.login(new LoginRequest("other@example.com", "CurrentPassword1"));
        passwordService.changePassword(user.getId(), new ChangePasswordRequest("CurrentPassword1", "NewPassword2"));
        assertThatThrownBy(() -> authService.login(new LoginRequest("password@example.com", "CurrentPassword1")))
                .isInstanceOf(BadCredentialsException.class);
        assertThat(authService
                        .login(new LoginRequest("password@example.com", "NewPassword2"))
                        .accessToken())
                .isNotBlank();
        assertThatThrownBy(() -> authService.refreshAccessToken(first.refreshToken()))
                .isInstanceOf(InvalidRefreshTokenException.class);
        assertThatThrownBy(() -> authService.refreshAccessToken(second.refreshToken()))
                .isInstanceOf(InvalidRefreshTokenException.class);
        assertThat(authService.refreshAccessToken(other.refreshToken()).refreshToken())
                .isNotBlank();
    }

    @Test
    void wrongPasswordPreservesPasswordAndSession() {
        var user = createPasswordUser("password@example.com");
        var login = authService.login(new LoginRequest("password@example.com", "CurrentPassword1"));
        assertThatThrownBy(() -> passwordService.changePassword(
                        user.getId(), new ChangePasswordRequest("IncorrectPassword", "NewPassword2")))
                .isInstanceOf(InvalidPasswordException.class);
        assertThat(passwordEncoder.matches(
                        "CurrentPassword1",
                        repository.findById(user.getId()).orElseThrow().getPasswordHash()))
                .isTrue();
        assertThat(authService.refreshAccessToken(login.refreshToken()).accessToken())
                .isNotBlank();
    }

    @Test
    void transactionRollbackRestoresBothPasswordAndRefreshTokens() {
        var user = createPasswordUser("password@example.com");
        var login = authService.login(new LoginRequest("password@example.com", "CurrentPassword1"));
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            passwordService.changePassword(user.getId(), new ChangePasswordRequest("CurrentPassword1", "NewPassword2"));
            status.setRollbackOnly();
        });
        assertThat(passwordEncoder.matches(
                        "CurrentPassword1",
                        repository.findById(user.getId()).orElseThrow().getPasswordHash()))
                .isTrue();
        assertThat(authService.refreshAccessToken(login.refreshToken()).accessToken())
                .isNotBlank();
    }

    @Test
    void concurrentPasswordChangesCannotBothUseTheOldPassword() throws Exception {
        var user = createPasswordUser("password@example.com");
        var barrier = new CyclicBarrier(2);
        Callable<Boolean> change = () -> {
            barrier.await(10, TimeUnit.SECONDS);
            try {
                passwordService.changePassword(
                        user.getId(), new ChangePasswordRequest("CurrentPassword1", "NewPassword2"));
                return true;
            } catch (InvalidPasswordException exception) {
                return false;
            }
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(change);
            var second = executor.submit(change);
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
    }

    @Test
    void simultaneousRefreshCannotLeaveASessionAfterPasswordChange() throws Exception {
        var user = createPasswordUser("password@example.com");
        var login = authService.login(new LoginRequest("password@example.com", "CurrentPassword1"));
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var refresh = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                try {
                    return authService.refreshAccessToken(login.refreshToken());
                } catch (InvalidRefreshTokenException exception) {
                    return null;
                }
            });
            var change = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                passwordService.changePassword(
                        user.getId(), new ChangePasswordRequest("CurrentPassword1", "NewPassword2"));
                return true;
            });
            var rotated = refresh.get(20, TimeUnit.SECONDS);
            assertThat(change.get(20, TimeUnit.SECONDS)).isTrue();
            assertThat(refreshTokens.findAll()).isEmpty();
            if (rotated != null) {
                assertThatThrownBy(() -> authService.refreshAccessToken(rotated.refreshToken()))
                        .isInstanceOf(InvalidRefreshTokenException.class);
            }
        }
    }

    @Test
    void simultaneousProfileSaveCannotRestoreThePreviousPassword() throws Exception {
        var user = createPasswordUser("password@example.com");
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var profile = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return service.updateUserProfile(
                        user.getId(),
                        new UpdateUserProfileRequest("Changed Name", "password@example.com", null, "Computing"));
            });
            var password = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                passwordService.changePassword(
                        user.getId(), new ChangePasswordRequest("CurrentPassword1", "NewPassword2"));
                return true;
            });
            profile.get(20, TimeUnit.SECONDS);
            assertThat(password.get(20, TimeUnit.SECONDS)).isTrue();
        }
        var reloaded = repository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getName()).isEqualTo("Changed Name");
        assertThat(passwordEncoder.matches("NewPassword2", reloaded.getPasswordHash()))
                .isTrue();
    }

    @Test
    void persistsProfileAndAllowsUsersToShareARealName() {
        var first = createUser("first@example.com");
        createUser("second@example.com");
        service.updateUserProfile(
                first.getId(), new UpdateUserProfileRequest("Alex Tan", " NEW@EXAMPLE.COM ", null, null));
        var reloaded = repository.findById(first.getId()).orElseThrow();
        assertThat(reloaded.getEmail()).isEqualTo("new@example.com");
        assertThat(reloaded.getName()).isEqualTo("Alex Tan");
        assertThat(reloaded.getPasswordHash()).isEqualTo("unchanged-hash");
        assertThat(reloaded.getRoles()).containsExactly(UserRole.USER);
        service.updateUserProfile(
                first.getId(), new UpdateUserProfileRequest("Alex Tan", "new@example.com", null, null));
    }

    @Test
    void rejectedUpdateLeavesBothFieldsUnchanged() {
        var first = createUser("first@example.com");
        createUser("taken@example.com");
        assertThatThrownBy(() -> service.updateUserProfile(
                        first.getId(), new UpdateUserProfileRequest("Changed Name", "taken@example.com", null, null)))
                .isInstanceOf(EntityAlreadyExistsException.class);
        var reloaded = repository.findById(first.getId()).orElseThrow();
        assertThat(reloaded.getName()).isEqualTo("Alex Tan");
        assertThat(reloaded.getEmail()).isEqualTo("first@example.com");
    }

    @Test
    void persistsReadsAndClearsOptionalDetails() {
        var user = createUser("details@example.com");
        assertThat(service.getUserProfile(user.getId()).phoneNumber()).isNull();
        assertThat(service.getUserProfile(user.getId()).faculty()).isNull();
        service.updateUserProfile(
                user.getId(),
                new UpdateUserProfileRequest(
                        "Alex Tan", "details@example.com", " +65 9123-5436 ", " School of Computing "));
        var profile = service.getUserProfile(user.getId());
        assertThat(profile.phoneNumber()).isEqualTo("+6591235436");
        assertThat(profile.faculty()).isEqualTo("School of Computing");
        service.updateUserProfile(
                user.getId(), new UpdateUserProfileRequest("Alex Tan", "details@example.com", " ", " "));
        var reloaded = repository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getPhoneNumber()).isNull();
        assertThat(reloaded.getFaculty()).isNull();
    }

    @Test
    void migrationPreservesAnExistingAccount() throws Exception {
        var schema = "profile_test_" + UUID.randomUUID().toString().replace("-", "");
        var source = new DriverManagerDataSource(
                System.getenv("FOC_TEST_DATABASE_URL"),
                System.getenv("FOC_TEST_DATABASE_USERNAME"),
                System.getenv("FOC_TEST_DATABASE_PASSWORD"));
        try (var connection = source.getConnection();
                var statement = connection.createStatement()) {
            try {
                var config = Flyway.configure()
                        .dataSource(source)
                        .schemas(schema)
                        .defaultSchema(schema)
                        .locations("classpath:db/migration");
                config.target("2").load().migrate();
                connection.setSchema(schema);
                statement.execute("""
                        INSERT INTO users (id, email, name, roles, password_hash, created_at, updated_at)
                        VALUES ('00000000-0000-0000-0000-000000000001', 'old@example.com', 'Existing User',
                                ARRAY['USER'], 'existing-hash', now(), now())
                        """);
                config.target("latest").load().migrate();
                try (var rows = statement.executeQuery("SELECT * FROM users")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString("email")).isEqualTo("old@example.com");
                    assertThat(rows.getString("name")).isEqualTo("Existing User");
                    assertThat(rows.getString("password_hash")).isEqualTo("existing-hash");
                    assertThat(rows.getString("phone_number")).isNull();
                    assertThat(rows.getString("faculty")).isNull();
                    assertThat(rows.getString("avatar_key")).isNull();
                    assertThat(rows.next()).isFalse();
                }
            } finally {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    @Test
    void duplicateEmailDoesNotChangeOptionalDetails() {
        var user = createUser("details@example.com");
        createUser("taken@example.com");
        service.updateUserProfile(
                user.getId(),
                new UpdateUserProfileRequest("Alex Tan", "details@example.com", "+6591235436", "School of Computing"));
        assertThatThrownBy(() -> service.updateUserProfile(
                        user.getId(),
                        new UpdateUserProfileRequest("Changed", "taken@example.com", "+6591239999", "Changed faculty")))
                .isInstanceOf(EntityAlreadyExistsException.class);
        var profile = service.getUserProfile(user.getId());
        assertThat(profile.phoneNumber()).isEqualTo("+6591235436");
        assertThat(profile.faculty()).isEqualTo("School of Computing");
    }

    @Test
    void concurrentClaimsForSameEmailHaveOnlyOneWinner() throws Exception {
        var first = createUser("first@example.com");
        var second = createUser("second@example.com");
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var one = executor.submit(claimEmail(first.getId(), barrier));
            var two = executor.submit(claimEmail(second.getId(), barrier));
            assertThat(List.of(one.get(20, TimeUnit.SECONDS), two.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(repository.findAll().stream().filter(user -> "shared@example.com".equals(user.getEmail())))
                .hasSize(1);
        var loser = repository.findAll().stream()
                .filter(user -> !"shared@example.com".equals(user.getEmail()))
                .findFirst()
                .orElseThrow();
        assertThat(loser.getName()).isEqualTo("Alex Tan");
    }

    private Callable<Boolean> claimEmail(UUID id, CyclicBarrier barrier) {
        return () -> {
            barrier.await(10, TimeUnit.SECONDS);
            try {
                service.updateUserProfile(
                        id, new UpdateUserProfileRequest("Changed", "shared@example.com", null, null));
                return true;
            } catch (EntityAlreadyExistsException exception) {
                return false;
            }
        };
    }

    @Configuration
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = UserRepository.class)
    static class TestConfig {
        @Bean
        AvatarStorage avatarStorage() {
            return spy(new LocalAvatarStorage(new AvatarProperties(avatarDirectory)));
        }

        @Bean
        AvatarCleanupService avatarCleanupService(
                AvatarCleanupRepository tasks,
                UserRepository users,
                AvatarStorage storage,
                PlatformTransactionManager manager) {
            return new AvatarCleanupService(tasks, users, storage, Clock.systemUTC(), manager);
        }

        @Bean
        AvatarService avatarService(
                UserRepository users,
                AvatarStorage storage,
                AvatarCleanupRepository tasks,
                AvatarCleanupService cleanup,
                PlatformTransactionManager manager) {
            return new AvatarService(
                    users,
                    Mappers.getMapper(UserMapper.class),
                    storage,
                    new AvatarImageProcessor(),
                    tasks,
                    cleanup,
                    Clock.systemUTC(),
                    manager);
        }

        @Bean
        PasswordEncoder passwordEncoder() {
            return new BCryptPasswordEncoder(4);
        }

        @Bean
        PasswordService passwordService(UserRepository users, RefreshTokenRepository tokens, PasswordEncoder encoder) {
            return new PasswordService(users, tokens, encoder);
        }

        @Bean
        TokenService tokenService(RefreshTokenRepository tokens, UserRepository users) {
            var properties = new AuthProperties(
                    Duration.ofMinutes(5),
                    Duration.ofDays(30),
                    new ClassPathResource("unused-private.pem"),
                    new ClassPathResource("unused-public.pem"),
                    "test",
                    false);
            // Exercise credentials and database sessions without testing JWT cryptography here.
            JwtEncoder encoder = parameters -> Jwt.withTokenValue("test-access-token")
                    .header("alg", "ES256")
                    .subject("test-user")
                    .build();
            return new TokenService(tokens, users, Clock.systemUTC(), properties, encoder);
        }

        @Bean
        AuthService authService(UserRepository users, TokenService tokens, PasswordEncoder encoder) {
            var provider = new DaoAuthenticationProvider(new CustomUserDetailsService(users));
            provider.setPasswordEncoder(encoder);
            AuthenticationManager manager = new ProviderManager(provider);
            return new AuthService(manager, tokens, users);
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
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setPackagesToScan("com.cs3219.foc.user.model.entity");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            // Match Spring Boot's default conversion of createdAt to created_at.
            factory.setJpaPropertyMap(Map.of(
                    "hibernate.hbm2ddl.auto", "validate",
                    "hibernate.physical_naming_strategy",
                            "org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl"));
            return factory;
        }

        @Bean
        JpaTransactionManager transactionManager(EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }

        @Bean
        UserService userService(UserRepository repository) {
            return new UserService(repository, new BCryptPasswordEncoder(), Mappers.getMapper(UserMapper.class));
        }
    }
}
