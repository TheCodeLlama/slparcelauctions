package com.slparcelauctions.backend.review;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * WebSocket envelope broadcast on {@code /topic/auction/{auctionId}} the
 * moment a {@link Review} flips from {@code visible=false} to
 * {@code visible=true} — either via the simultaneous-reveal branch in
 * {@code ReviewService.submit} or the hourly {@code BlindReviewRevealTask}
 * scheduler sweep. The spec (§7) defines this envelope as part of the
 * auction topic; the frontend's {@code AuctionTopicEnvelope} TypeScript
 * union gains a {@code REVIEW_REVEALED} variant in Task 5. Emitted on
 * {@code afterCommit} so subscribers never observe a reveal that gets
 * rolled back.
 *
 * <p>Payload intentionally omits the rating/text fields — clients that
 * receive a {@code REVIEW_REVEALED} invalidate their
 * {@code useAuctionReviews(auctionId)} query and refetch through the
 * authenticated GET path, which is the single place visibility rules
 * and viewer-specific gating live. Passing rating/text here would duplicate
 * the gating logic and leak content to any subscriber on the public topic.
 */
public record ReviewRevealedEnvelope(
        String type,
        UUID auctionPublicId,
        UUID reviewPublicId,
        UUID reviewerPublicId,
        UUID revieweePublicId,
        ReviewedRole reviewedRole,
        OffsetDateTime revealedAt) {

    public static ReviewRevealedEnvelope of(Review r) {
        return new ReviewRevealedEnvelope(
                "REVIEW_REVEALED",
                r.getAuction().getPublicId(),
                r.getPublicId(),
                r.getReviewer().getPublicId(),
                r.getReviewee().getPublicId(),
                r.getReviewedRole(),
                r.getRevealedAt());
    }
}
