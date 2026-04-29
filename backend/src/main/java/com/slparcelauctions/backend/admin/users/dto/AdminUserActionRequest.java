package com.slparcelauctions.backend.admin.users.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AdminUserActionRequest(
    @NotBlank @Size(max = 1000) String notes
) {}
