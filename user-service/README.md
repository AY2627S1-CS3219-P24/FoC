# User Service

## User-created events

Registration publishes `UserCreatedEvent` within the user transaction. Spring Modulith's JDBC publication registry records it in `event_publication` in that same transaction. After commit, `SnsUserCreatedEventPublisher` sends it with `SnsOperations` to the user SNS topic. A failed publish stays in the registry and is retried.

`EventPublicationRecoveryJob` retries failed publications through the common registry API every 30 seconds. Framework staleness checks also recover publications interrupted by a service restart. The publisher keeps the same event ID on retry.

User owns publication. Credit owns account creation and consumes `UserCreatedEvent` from its SQS queue. Local SNS and SQS are provided by Floci on port 4566 (`docker compose up -d`).

There is no existing-user backfill. New registrations produce events; users inserted directly through SQL, including seeded administrators, do not. For a new test administrator that needs credits, register the user normally before assigning the admin role.

## Tests

Run `./gradlew :user-service:check` from the repository root with Docker running. Testcontainers starts PostgreSQL and Floci, and Spring Boot's service connection supplies the database, so runs need no database environment variables. Cross-service tests: [e2e-tests](../e2e-tests/README.md).
