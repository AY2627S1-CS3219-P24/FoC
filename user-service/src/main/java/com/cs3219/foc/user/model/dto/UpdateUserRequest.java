package com.cs3219.foc.user.model.dto;

import com.cs3219.foc.user.model.entity.UserRole;
import jakarta.validation.constraints.*;
import java.util.List;

public record UpdateUserRequest(
        @NotBlank @Size(max = 255) String name,

        @NotBlank @Pattern(regexp = "\\b[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}\\b") @Size(max = 320) String email,

        @NotEmpty List<@NotNull UserRole> roles) {}
