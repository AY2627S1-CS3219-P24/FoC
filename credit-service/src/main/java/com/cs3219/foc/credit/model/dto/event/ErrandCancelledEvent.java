package com.cs3219.foc.credit.model.dto.event;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Order-owned fact. Redelivery retains eventId; amount is the errand's immutable reward. */
public record ErrandCancelledEvent(UUID eventId, int schemaVersion, OffsetDateTime occurredAt, Payload payload) {
    public static final String TYPE = "ErrandCancelledEvent";

    public record Payload(UUID errandId, UUID requesterId, long amount) {}
}
