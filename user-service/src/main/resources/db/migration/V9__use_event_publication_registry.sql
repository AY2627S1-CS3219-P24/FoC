-- Preserve previously committed events and their stable IDs during upgrade.
INSERT INTO event_publication(id, listener_id, event_type, serialized_event, publication_date,
                              completion_date, status, completion_attempts)
SELECT event_id, 'user-created-rabbit', 'com.cs3219.foc.user.model.dto.event.UserCreatedEvent',
       json_build_object('eventId', event_id, 'eventType', 'UserCreatedEvent', 'schemaVersion', 1,
                         'occurredAt', occurred_at, 'payload', json_build_object('userId', user_id))::text,
       occurred_at, published_at, CASE WHEN published_at IS NULL THEN 'FAILED' ELSE 'COMPLETED' END,
       attempt_count
FROM user_created_outbox;
DROP TABLE user_created_outbox;
DROP TABLE user_credit_provisioning;
