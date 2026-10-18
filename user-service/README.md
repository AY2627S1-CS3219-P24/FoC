# User Service

## User-created events

Registration publishes `UserCreatedEvent` within the user transaction. Spring Modulith's JDBC publication registry records it in `event_publication` in that same transaction. After commit, `UserCreatedEventPublisher` sends it through Spring Cloud Stream and waits for RabbitMQ confirmation. Failed or unroutable publications remain retryable in the registry.

`EventPublicationRecoveryJob` retries failed publications through the common registry API every 30 seconds. Framework staleness checks also recover publications interrupted by a service restart. The publisher keeps the same event ID on retry. There are no custom outbox leases, backfill workers, or credit-provisioning tracking records.

User owns publication; Credit owns account creation and its queue/retry/DLQ settings. User can start independently. If Credit has never declared its queue, the publisher treats returned messages as failures and retries after Credit starts.

There is no existing-user backfill. New registrations produce events; users inserted directly through SQL, including seeded administrators, do not. For a new test administrator that needs credits, register the user normally before assigning the admin role.

## Upgrade

Stop old User Service instances before upgrading. Flyway moves existing custom outbox events into the publication registry, retaining their event IDs and delivery status, then removes `user_created_outbox` and `user_credit_provisioning`. Existing migrations are unchanged.

Follow the [RabbitMQ upgrade steps in Credit Service](../credit-service/README.md#upgrade-from-the-previous-pr-version) to replace the old timed retry queue with a parked DLQ. No database reset is needed.

## Tests

Run `./gradlew :user-service:check`. Database integration tests require `FOC_TEST_DATABASE_URL`, `FOC_TEST_DATABASE_USERNAME`, and `FOC_TEST_DATABASE_PASSWORD`. RabbitMQ tests also require `FOC_TEST_RABBIT_HOST`, `FOC_TEST_RABBIT_PORT`, `FOC_TEST_RABBIT_USERNAME`, and `FOC_TEST_RABBIT_PASSWORD`.
