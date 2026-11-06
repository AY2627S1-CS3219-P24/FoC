package com.cs3219.foc.order.model.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ErrandMessageDto(UUID id, UUID errandId, UUID senderId, String body, OffsetDateTime sentAt) {}
