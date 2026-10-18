-- Spring Modulith JDBC event publication registry.
CREATE TABLE event_publication (
    id UUID PRIMARY KEY,
    listener_id TEXT NOT NULL,
    event_type TEXT NOT NULL,
    serialized_event TEXT NOT NULL,
    publication_date TIMESTAMPTZ NOT NULL,
    completion_date TIMESTAMPTZ,
    status TEXT,
    completion_attempts INTEGER,
    last_resubmission_date TIMESTAMPTZ
);
CREATE INDEX event_publication_serialized_event_hash_idx ON event_publication USING hash(serialized_event);
CREATE INDEX event_publication_by_completion_date_idx ON event_publication(completion_date);

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
