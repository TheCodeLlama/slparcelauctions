package com.slparcelauctions.backend.promotion.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Admin move-slot request body. {@code @Max(13)} on {@code boardIndex} is the
 * physical hard ceiling (13 board prims at HQ, enforced by the
 * featured_board_slots_board_index_range DB check). The runtime
 * "active boards" count from {@code slpa.promotions.featured-slot-count} is a
 * tighter ceiling and is enforced in the service layer via
 * {@link com.slparcelauctions.backend.promotion.exception.InvalidBoardIndexException}.
 */
public record MovePromotionSlotRequest(
        @Min(1) @Max(13) int boardIndex,
        @Min(0) int position
) {}
