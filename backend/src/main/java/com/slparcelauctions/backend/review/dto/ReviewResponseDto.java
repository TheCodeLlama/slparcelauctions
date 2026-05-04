package com.slparcelauctions.backend.review.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.slparcelauctions.backend.review.ReviewResponse;

/**
 * Wire shape for a persisted {@link ReviewResponse} embedded inside a
 * {@link ReviewDto}. The reviewee's response text is already public at
 * the point the response exists (it can only be written on a visible
 * review in Task 3), so no visibility-gate fields are carried here.
 */
public record ReviewResponseDto(
        UUID publicId,
        String text,
        OffsetDateTime createdAt) {

    public static ReviewResponseDto of(ReviewResponse r) {
        return new ReviewResponseDto(r.getPublicId(), r.getText(), r.getCreatedAt());
    }
}
