package com.cs3219.foc.supplier.integration;

import java.nio.file.Path;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

@ActiveProfiles("test")
@Import(AbstractIntegrationTest.Containers.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class AbstractIntegrationTest {
    @TestConfiguration(proxyBeanMethods = false)
    public static class Containers {
        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer("postgres:18-alpine");
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Aws {
        @Bean
        GenericContainer<?> floci() {
            return new GenericContainer<>(DockerImageName.parse("floci/floci:2.3.0-compat"))
                    .withExposedPorts(4566)
                    .withEnv("AWS_REGION", "ap-southeast-1")
                    .withEnv("FOC_NAME_PREFIX", "foc-test")
                    .withEnv("FOC_USER_BUCKET", "foc-user-integration")
                    .withEnv("FOC_SUPPLIER_BUCKET", "foc-supplier-integration")
                    .withEnv("FLOCI_SERVICES_S3_ENFORCE_AUTH", "true")
                    .withCopyFileToContainer(
                            MountableFile.forHostPath(
                                    Path.of(System.getProperty("foc.root"), "infra/docker/floci/init-resources.sh")),
                            "/etc/localstack/init/ready.d/10-foc-resources.sh")
                    .waitingFor(Wait.forSuccessfulCommand("test -f /tmp/foc-resources-ready"));
        }

        @Bean
        DynamicPropertyRegistrar awsProperties(GenericContainer<?> floci) {
            return registry -> {
                var endpoint = "http://" + floci.getHost() + ":" + floci.getMappedPort(4566);
                registry.add("spring.cloud.aws.s3.endpoint", () -> endpoint);
                registry.add("spring.cloud.aws.sns.endpoint", () -> endpoint);
                registry.add("spring.cloud.aws.sqs.endpoint", () -> endpoint);
            };
        }
    }
}
