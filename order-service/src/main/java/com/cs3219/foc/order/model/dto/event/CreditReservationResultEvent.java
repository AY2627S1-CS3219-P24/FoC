package com.cs3219.foc.order.model.dto.event;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.UUID;

public record CreditReservationResultEvent(
        @NotNull UUID eventId,

        @Pattern(regexp = "CreditReservationResultEvent") @NotNull String eventType,

        @Min(1) @Max(1) int schemaVersion,
        @NotNull OffsetDateTime occurredAt,
        @Valid @NotNull Payload payload) {
    public record Payload(
            @NotNull UUID operationId,
            @NotNull UUID errandId,
            @NotNull UUID requesterId,
            @Positive long amount,
            @NotNull String outcome) {}
}
