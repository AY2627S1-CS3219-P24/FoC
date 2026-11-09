package com.cs3219.foc.order.model.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

// A chat message; text + photo (maybe)
public record ErrandMessageDto(
        UUID id, UUID errandId, UUID senderId, String body, Attachment attachment, OffsetDateTime sentAt) {
    // the link to view the photo
    public record Attachment(String type, String url, OffsetDateTime expiresAt) {}
}
