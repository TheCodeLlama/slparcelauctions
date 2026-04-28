package com.slparcelauctions.backend.user.deletion;

import jakarta.validation.constraints.NotBlank;

public record UserDeletionRequest(
        @NotBlank String password) {
}
