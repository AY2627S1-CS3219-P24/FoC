package com.cs3219.foc.credit.model.dto.event;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Order-owned terminal facts, validated once at the messaging boundary. */
public record ErrandSettlementEvent(
        @NotNull UUID eventId,

        @NotNull String eventType,

        @Min(1) @Max(1) int schemaVersion,
        @NotNull OffsetDateTime occurredAt,
        @NotNull @Valid Payload payload) {
    @JsonIgnore
    @AssertTrue(message = "A courier is required only for errand completion") public boolean isCourierConsistent() {
        return payload == null || ("ErrandCompletedEvent".equals(eventType) == (payload.courierId() != null));
    }

    public record Payload(
            @NotNull UUID operationId,
            @NotNull UUID reservationOperationId,
            @NotNull UUID errandId,
            @NotNull UUID requesterId,
            @Positive long amount,
            UUID courierId) {}
}
