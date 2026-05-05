package com.slparcelauctions.backend.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(
        @NotBlank
        @Size(min = 3, max = 64)
        @Pattern(
                regexp = "^[\\p{L}\\p{M}\\p{N}\\p{P}\\p{S} ]+$",
                message = "username may only contain letters, digits, marks, punctuation, symbols, and spaces")
        String username,

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
        String password) {
}
