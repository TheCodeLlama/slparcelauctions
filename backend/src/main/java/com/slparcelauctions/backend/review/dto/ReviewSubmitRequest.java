package com.slparcelauctions.backend.review.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/auctions/{id}/reviews}. The
 * rating is bounded at the DTO level (not the entity column) because
 * entity-level validation happens after persistence decisions and
 * wouldn't let the controller produce a clean 400 ProblemDetail. Text
 * is optional — rating-only reviews are legitimate input.
 */
public record ReviewSubmitRequest(
        @NotNull @Min(1) @Max(5) Integer rating,
        @Size(max = 500) String text) {
}
