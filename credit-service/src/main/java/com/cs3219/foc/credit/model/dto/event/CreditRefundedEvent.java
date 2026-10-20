package com.cs3219.foc.credit.model.dto.event;

import java.time.OffsetDateTime;
import java.util.UUID;

public record CreditRefundedEvent(UUID eventId, int schemaVersion, OffsetDateTime occurredAt, Payload payload) {
    public static CreditRefundedEvent of(
            UUID operationId, UUID errandId, UUID requesterId, long amount, OffsetDateTime occurredAt) {
        return new CreditRefundedEvent(
                UUID.randomUUID(), 1, occurredAt, new Payload(operationId, errandId, requesterId, amount));
    }

    public record Payload(UUID operationId, UUID errandId, UUID requesterId, long amount) {}
}
