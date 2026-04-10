package com.slparcelauctions.backend.user.dto;

import jakarta.validation.constraints.Size;

public record UpdateUserRequest(
        @Size(max = 255) String displayName,
        @Size(max = 5000) String bio) {
}
