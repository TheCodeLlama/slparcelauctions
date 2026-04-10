package com.slparcelauctions.backend.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(
        @NotBlank @Email @Size(max = 255) String email,

        // At least 10 characters, must contain at least one letter and at least
        // one digit or non-alphanumeric character. Length cap matches the
        // password_hash column width upstream of BCrypt encoding. This is the
        // floor for self-registration; we tighten further (entropy / breach
        // checks) once we have real users to migrate.
        @NotBlank
        @Size(min = 10, max = 255)
        @Pattern(
                regexp = "^(?=.*[A-Za-z])(?=.*[\\d\\W]).{10,}$",
                message = "password must contain at least one letter and at least one digit or symbol")
        String password,

        @Size(max = 255) String displayName) {
}
