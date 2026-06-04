package com.slparcelauctions.backend.promotion.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record MovePromotionSlotRequest(
        @Min(1) @Max(13) int boardIndex,
        @Min(0) int position
) {}
