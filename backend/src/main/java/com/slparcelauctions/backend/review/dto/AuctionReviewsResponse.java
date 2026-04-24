package com.slparcelauctions.backend.review.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Wire shape for {@code GET /api/v1/auctions/{id}/reviews}. Public —
 * anonymous callers see only {@code reviews}; {@code myPendingReview}
 * and {@code canReview} + {@code windowClosesAt} are null / false for
 * non-party or anonymous viewers (Epic 08 sub-spec 1 §4.1, spec §5).
 *
 * <p>{@code windowClosesAt} is the absolute server timestamp at which
 * the 14-day window closes (escrow.completedAt + 14d) — the UI derives
 * its countdown from the delta against {@code Date.now()} on the
 * client rather than receiving a pre-computed hoursRemaining the
 * moment the response was built.
 */
public record AuctionReviewsResponse(
        List<ReviewDto> reviews,
        ReviewDto myPendingReview,
        Boolean canReview,
        OffsetDateTime windowClosesAt) {
}
