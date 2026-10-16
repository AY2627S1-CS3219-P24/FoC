package com.cs3219.foc.order.model.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ErrandResponse(
        UUID id,
        UUID requesterId,
        String pickup,
        String destination,
        String description,
        long reward,
        String status,
        String creditOutcome,
        OffsetDateTime createdAt) {}
