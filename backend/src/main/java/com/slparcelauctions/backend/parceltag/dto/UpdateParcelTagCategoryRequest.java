package com.slparcelauctions.backend.parceltag.dto;

import jakarta.validation.constraints.Size;

public record UpdateParcelTagCategoryRequest(
        @Size(min = 1, max = 100) String label,
        @Size(max = 2000) String description) {}
