package com.cs3219.foc.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cs3219.foc.user.exception.EntityAlreadyExistsException;
import com.cs3219.foc.user.mapper.UserMapper;
import com.cs3219.foc.user.model.dto.UpdateUserProfileRequest;
import com.cs3219.foc.user.model.entity.User;
import com.cs3219.foc.user.model.entity.UserRole;
import com.cs3219.foc.user.repository.UserRepository;
import jakarta.persistence.EntityManagerFactory;
import java.sql.DriverManager;
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
import org.mapstruct.factory.Mappers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** Runs the real migrations and service transactions in a disposable PostgreSQL schema. */
@SpringJUnitConfig(UserProfilePersistenceTests.TestConfig.class)
@EnabledIfEnvironmentVariable(named = "FOC_TEST_DATABASE_URL", matches = ".+")
class UserProfilePersistenceTests {
    private static final String SCHEMA =
            "profile_test_" + UUID.randomUUID().toString().replace("-", "");

    @Autowired
    private UserRepository repository;

    @Autowired
    private UserService service;

    @BeforeEach
    void cleanUsers() {
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

    private User createUser(String email) {
        return repository.saveAndFlush(User.builder()
                .name("Alex Tan")
                .email(email)
                .roles(List.of(UserRole.USER))
                .passwordHash("unchanged-hash")
                .build());
    }

    @Test
    void persistsProfileAndAllowsUsersToShareARealName() {
        var first = createUser("first@example.com");
        createUser("second@example.com");
        service.updateUserProfile(first.getId(), new UpdateUserProfileRequest("Alex Tan", " NEW@EXAMPLE.COM "));
        var reloaded = repository.findById(first.getId()).orElseThrow();
        assertThat(reloaded.getEmail()).isEqualTo("new@example.com");
        assertThat(reloaded.getName()).isEqualTo("Alex Tan");
        assertThat(reloaded.getPasswordHash()).isEqualTo("unchanged-hash");
        assertThat(reloaded.getRoles()).containsExactly(UserRole.USER);
        service.updateUserProfile(first.getId(), new UpdateUserProfileRequest("Alex Tan", "new@example.com"));
    }

    @Test
    void rejectedUpdateLeavesBothFieldsUnchanged() {
        var first = createUser("first@example.com");
        createUser("taken@example.com");
        assertThatThrownBy(() -> service.updateUserProfile(
                        first.getId(), new UpdateUserProfileRequest("Changed Name", "taken@example.com")))
                .isInstanceOf(EntityAlreadyExistsException.class);
        var reloaded = repository.findById(first.getId()).orElseThrow();
        assertThat(reloaded.getName()).isEqualTo("Alex Tan");
        assertThat(reloaded.getEmail()).isEqualTo("first@example.com");
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
                service.updateUserProfile(id, new UpdateUserProfileRequest("Changed", "shared@example.com"));
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
