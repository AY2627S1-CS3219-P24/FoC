package com.cs3219.foc.credit.model.dto.event;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.UUID;

public record ErrandCreationStartedEvent(
        @NotNull UUID eventId,

        @Pattern(regexp = "ErrandCreationStartedEvent") @NotNull String eventType,

        @Min(1) @Max(1) int schemaVersion,
        @NotNull OffsetDateTime occurredAt,
        @Valid @NotNull Payload payload) {
    public record Payload(
            @NotNull UUID errandId,
            @NotNull UUID requesterId,
            @Positive long amount,
            @NotNull OffsetDateTime reserveBefore) {}
}
