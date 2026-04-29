package com.slparcelauctions.backend.review.dto;

import com.slparcelauctions.backend.review.ReviewFlagReason;
import com.slparcelauctions.backend.review.exception.ElaborationRequiredWhenOther;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/reviews/{id}/flag} (Epic 08
 * sub-spec 1 §4.4). {@code reason} is always required; {@code
 * elaboration} is optional except when {@code reason=OTHER}, enforced by
 * the class-level {@link ElaborationRequiredWhenOther} cross-field
 * validator.
 */
@ElaborationRequiredWhenOther
public record ReviewFlagRequest(
        @NotNull
        ReviewFlagReason reason,
        @Size(max = 500)
        String elaboration) {
}
