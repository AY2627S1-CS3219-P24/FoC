package com.cs3219.foc.supplier.model.dto;

import com.cs3219.foc.supplier.model.entity.SupplierCategory;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalTime;

/** Request body format used for create or update a supplier */
public record SupplierRequest(
        @NotBlank @Size(max = 255) String name,
        @NotNull SupplierCategory category,
        @NotBlank @Size(max = 255) String building,
        @Size(max = 32) String floor,
        @Size(max = 500) String locationDescription,
        @DecimalMin("-90.0") @DecimalMax("90.0") Double latitude,
        @DecimalMin("-180.0") @DecimalMax("180.0") Double longitude,
        @NotNull LocalTime openingTime,
        @NotNull LocalTime closingTime,

        @Size(max = 2048) @Pattern(regexp = "^https?://\\S+$", message = "must be an http(s) URL")
        String imageUrl) {}
