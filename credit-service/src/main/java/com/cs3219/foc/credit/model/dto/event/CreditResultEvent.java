package com.cs3219.foc.credit.model.dto.event;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Shared envelope for the durable publisher; each record retains its own wire contract. */
public sealed interface CreditResultEvent permits CreditReservationResultEvent, CreditSettlementResultEvent {
    UUID eventId();

    String eventType();

    int schemaVersion();

    OffsetDateTime occurredAt();

    Payload payload();

    interface Payload {
        UUID operationId();

        UUID errandId();

        UUID requesterId();

        long amount();

        String outcome();
    }
}
