package com.cs3219.foc.order.model.dto.event;

import java.time.OffsetDateTime;
import java.util.UUID;

public record CreditSettlementResultEvent(UUID eventId, int schemaVersion, OffsetDateTime occurredAt, Payload payload) {
    public static final String TYPE = "CreditSettlementResultEvent";

    public record Payload(
            UUID operationId,
            UUID errandId,
            UUID requesterId,
            long amount,
            String outcome,
            String settlementType,
            UUID courierId) {}
}
