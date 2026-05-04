package com.slparcelauctions.backend.review.dto;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import com.slparcelauctions.backend.review.Review;
import com.slparcelauctions.backend.review.ReviewResponse;
import com.slparcelauctions.backend.review.ReviewedRole;

/**
 * Wire shape for a single review row. {@code of(...)} is the single
 * entry point for mapping a persisted {@link Review} to the public DTO
 * — it pulls the reviewer's live display name / avatar (so a rename
 * immediately reflects in every visible review) and gates
 * {@code text}, {@code rating}, {@code submittedAt} behind a visibility
 * check: the reviewer always sees their own pending submission with
 * text; everyone else sees nothing until the row is revealed.
 *
 * <p>{@code pending} is a derived viewer-specific hint: {@code true}
 * means the viewer is this review's reviewer AND the row is not yet
 * visible. The counterparty sees no hint of the viewer's submission
 * (Q9 blind-on-fact decision in spec §4.2).
 */
public record ReviewDto(
        UUID publicId,
        UUID auctionPublicId,
        String auctionTitle,
        String auctionPrimaryPhotoUrl,
        UUID reviewerPublicId,
        String reviewerDisplayName,
        String reviewerAvatarUrl,
        UUID revieweePublicId,
        ReviewedRole reviewedRole,
        Integer rating,
        String text,
        Boolean visible,
        Boolean pending,
        OffsetDateTime submittedAt,
        OffsetDateTime revealedAt,
        ReviewResponseDto response) {

    /**
     * Build a {@link ReviewDto} for {@code viewerId}. When the viewer is
     * the reviewer, {@code pending=true} and {@code text}/{@code rating}
     * are exposed even if the row is not yet visible, so the author sees
     * their own submission in the UI. {@code viewerId} is nullable —
     * anonymous readers get the public-visible shape.
     *
     * <p>{@code primaryPhotoUrl} is resolved by the service (matches the
     * listing-detail mapper's photo-or-parcel-snapshot fallback) so the
     * DTO stays free of Auction→Photo traversal details.
     */
    public static ReviewDto of(Review r, Long viewerId,
                                Optional<ReviewResponse> resp,
                                String primaryPhotoUrl) {
        boolean viewerIsReviewer = viewerId != null
                && r.getReviewer().getId().equals(viewerId);
        boolean visible = Boolean.TRUE.equals(r.getVisible());
        boolean pending = !visible && viewerIsReviewer;
        boolean exposeText = visible || viewerIsReviewer;
        return new ReviewDto(
                r.getPublicId(),
                r.getAuction().getPublicId(),
                r.getAuction().getTitle(),
                primaryPhotoUrl,
                r.getReviewer().getPublicId(),
                r.getReviewer().getDisplayName(),
                avatarUrl(r.getReviewer().getId()),
                r.getReviewee().getPublicId(),
                r.getReviewedRole(),
                exposeText ? r.getRating() : null,
                exposeText ? r.getText() : null,
                r.getVisible(),
                pending,
                exposeText ? r.getSubmittedAt() : null,
                r.getRevealedAt(),
                resp.map(ReviewResponseDto::of).orElse(null));
    }

    private static String avatarUrl(Long userId) {
        return userId == null ? null : "/api/v1/users/" + userId + "/avatar/256";
    }
}
