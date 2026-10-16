package com.cs3219.foc.order.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreateErrandRequest(
        @NotBlank @Size(max = 255) String pickup,
        @NotBlank @Size(max = 255) String destination,
        @NotBlank @Size(max = 2000) String description,
        @Positive long reward) {}
