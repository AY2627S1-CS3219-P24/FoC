package com.cs3219.foc.credit.model.dto.event;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ErrandSubmittedEvent(
        UUID eventId, String eventType, int schemaVersion, OffsetDateTime occurredAt, Payload payload) {
    public record Payload(UUID operationId, UUID errandId, UUID requesterId, long amount) {}
}
