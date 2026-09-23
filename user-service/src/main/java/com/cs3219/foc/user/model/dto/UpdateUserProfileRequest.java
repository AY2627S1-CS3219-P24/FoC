package com.cs3219.foc.user.model.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Locale;

public record UpdateUserProfileRequest(
        @NotBlank @Size(max = 255) String name,
        @NotBlank @Email @Size(max = 320) String email,

        @Pattern(regexp = "\\+[1-9][0-9]{1,14}", message = "must include a country code and 2 to 15 digits") String phoneNumber,

        @Size(max = 255) String faculty) {
    public UpdateUserProfileRequest {
        name = name == null ? null : name.strip();
        email = email == null ? null : email.strip().toLowerCase(Locale.ROOT);
        phoneNumber = optionalText(phoneNumber);
        if (phoneNumber != null) {
            phoneNumber = phoneNumber.replace(" ", "").replace("-", "");
        }
        faculty = optionalText(faculty);
    }

    private static String optionalText(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
