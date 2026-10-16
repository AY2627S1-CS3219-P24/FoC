package com.cs3219.foc.credit.model.dto.event;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Order-owned terminal facts, with the requester/reward/assignment from its persisted errand. */
public record ErrandSettlementEvent(
        UUID eventId, String eventType, int schemaVersion, OffsetDateTime occurredAt, Payload payload) {
    public record Payload(
            UUID operationId,
            UUID reservationOperationId,
            UUID errandId,
            UUID requesterId,
            long amount,
            UUID courierId) {}
}
