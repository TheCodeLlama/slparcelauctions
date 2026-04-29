package com.slparcelauctions.backend.review.dto;

import java.time.OffsetDateTime;

import com.slparcelauctions.backend.review.ReviewResponse;

/**
 * Wire shape for a persisted {@link ReviewResponse} embedded inside a
 * {@link ReviewDto}. The reviewee's response text is already public at
 * the point the response exists (it can only be written on a visible
 * review in Task 3), so no visibility-gate fields are carried here.
 */
public record ReviewResponseDto(
        Long id,
        String text,
        OffsetDateTime createdAt) {

    public static ReviewResponseDto of(ReviewResponse r) {
        return new ReviewResponseDto(r.getId(), r.getText(), r.getCreatedAt());
    }
}
