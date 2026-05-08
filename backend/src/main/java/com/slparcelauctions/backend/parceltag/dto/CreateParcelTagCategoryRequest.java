package com.slparcelauctions.backend.parceltag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateParcelTagCategoryRequest(
        @NotBlank
        @Size(min = 1, max = 50)
        @Pattern(regexp = "^[A-Z0-9_]+$",
                message = "Code must be uppercase letters, digits, and underscores only")
        String code,

        @NotBlank
        @Size(min = 1, max = 100)
        String label,

        @Size(max = 2000)
        String description) {}
