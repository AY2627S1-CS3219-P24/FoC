package com.cs3219.foc.supplier.model.dto;

import com.cs3219.foc.supplier.model.entity.SupplierCategory;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;

public record SupplierDto(
        UUID id,
        String name,
        SupplierCategory category,
        String building,
        String floor,
        String locationDescription,
        Double latitude,
        Double longitude,
        LocalTime openingTime,
        LocalTime closingTime,
        String imageUrl,
        boolean active,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {}
