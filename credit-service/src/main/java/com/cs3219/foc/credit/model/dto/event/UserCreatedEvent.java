package com.cs3219.foc.credit.model.dto.event;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Wire contract owned by User service; no cross-service persistence dependency. */
public record UserCreatedEvent(UUID eventId, int schemaVersion, OffsetDateTime occurredAt, Payload payload) {
    public static final String TYPE = "UserCreatedEvent";

    public record Payload(UUID userId) {}
}
