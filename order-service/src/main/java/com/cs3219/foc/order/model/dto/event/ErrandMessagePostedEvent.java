package com.cs3219.foc.order.model.dto.event;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

// A chat message was posted, the realtime service will push it to everyone in {@code recipientIds}
public record ErrandMessagePostedEvent(UUID eventId, int schemaVersion, OffsetDateTime occurredAt, Payload payload) {
    public static final String TYPE = "ErrandMessagePostedEvent";

    public record Payload(
            UUID messageId,
            UUID errandId,
            UUID senderId,
            List<UUID> recipientIds,
            String body,
            OffsetDateTime sentAt) {}
}
