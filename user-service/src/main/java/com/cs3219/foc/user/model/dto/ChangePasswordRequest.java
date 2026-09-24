package com.cs3219.foc.user.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequest(
        @NotBlank String currentPassword,
        @NotBlank @Size(min = 8, max = 72) String newPassword) {
    @Override
    public String toString() {
        return "ChangePasswordRequest[REDACTED]";
    }
}
