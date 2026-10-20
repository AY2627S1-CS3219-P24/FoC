package com.cs3219.foc.credit.model.dto.event;

import java.time.OffsetDateTime;
import java.util.UUID;

public record CreditPaidEvent(UUID eventId, int schemaVersion, OffsetDateTime occurredAt, Payload payload) {
    public static CreditPaidEvent of(
            UUID operationId, UUID errandId, UUID requesterId, long amount, UUID courierId, OffsetDateTime occurredAt) {
        return new CreditPaidEvent(
                UUID.randomUUID(), 1, occurredAt, new Payload(operationId, errandId, requesterId, amount, courierId));
    }

    public record Payload(UUID operationId, UUID errandId, UUID requesterId, long amount, UUID courierId) {}
}
