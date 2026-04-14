package com.slparcelauctions.backend.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/auth/register}.
 *
 * <p>Password constraints mirror {@link com.slparcelauctions.backend.user.dto.CreateUserRequest}.
 */
public record RegisterRequest(
        @NotBlank @Email @Size(max = 255) String email,

        @NotBlank
        @Size(min = 10, max = 255)
        @Pattern(
                regexp = "^(?=.*[A-Za-z])(?=.*[\\d\\W]).{10,}$",
                message = "password must contain at least one letter and at least one digit or symbol")
        String password,

        @Size(max = 255) String displayName) {
}
