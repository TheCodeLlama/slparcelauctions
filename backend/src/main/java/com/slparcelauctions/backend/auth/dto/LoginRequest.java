package com.slparcelauctions.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/auth/login}.
 *
 * <p>Username is matched case-insensitively against {@code users.username} via
 * the functional unique index on {@code LOWER(username)}. Server normalizes
 * leading/trailing whitespace before lookup, so the user can sign in with the
 * casing or padding they typed at registration without the field rejecting them.
 */
public record LoginRequest(
        @NotBlank
        @Size(min = 3, max = 64)
        @Pattern(
                regexp = "^[\\p{L}\\p{M}\\p{N}\\p{P}\\p{S} ]+$",
                message = "username may only contain letters, digits, marks, punctuation, symbols, and spaces")
        String username,

        @NotBlank @Size(max = 255) String password) {
}
