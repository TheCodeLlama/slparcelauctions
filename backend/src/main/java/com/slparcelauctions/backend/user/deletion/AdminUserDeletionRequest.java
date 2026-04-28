package com.slparcelauctions.backend.user.deletion;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AdminUserDeletionRequest(
        @NotBlank @Size(min = 1, max = 500) String adminNote) {
}
