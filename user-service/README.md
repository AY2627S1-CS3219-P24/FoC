# User Service

## User-created events

Registration publishes `UserCreatedEvent` within the user transaction. Spring Modulith's JDBC publication registry records it in `event_publication` in that same transaction. After commit, `SnsUserCreatedEventPublisher` sends it with `SnsOperations` to the user SNS topic. A failed publish stays in the registry and is retried.

`EventPublicationRecoveryJob` retries failed publications through the common registry API every 30 seconds. Framework staleness checks also recover publications interrupted by a service restart. The publisher keeps the same event ID on retry.

User owns publication. Credit owns account creation and consumes `UserCreatedEvent` from its SQS queue. Local SNS and SQS are provided by Floci on port 4566 (`docker compose up -d`).

There is no existing-user backfill. New registrations produce events; users inserted directly through SQL, including seeded administrators, do not. For a new test administrator that needs credits, register the user normally before assigning the admin role.

## Tests

Run `./gradlew :user-service:check` with Java 25 and Docker. Testcontainers starts
PostgreSQL and Floci as needed. Spring Boot's `@ServiceConnection` supplies database
connection details, so local runs and CI need no database environment variables or
separately managed database. Persistence tests use `@DataJpaTest`; publication tests
use `@SpringBootTest` to include Spring Modulith's event registry and retry behavior.
