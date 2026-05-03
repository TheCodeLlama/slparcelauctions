package com.slparcelauctions.backend.review.dto;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Comparator;

import com.slparcelauctions.backend.auction.AuctionPhoto;
import com.slparcelauctions.backend.escrow.Escrow;
import com.slparcelauctions.backend.review.ReviewService;
import com.slparcelauctions.backend.review.ReviewedRole;
import com.slparcelauctions.backend.user.User;

/**
 * Wire shape for the dashboard's "reviews waiting for you to submit"
 * card. One row per completed escrow where the caller is a party and has
 * not yet submitted a review within the 14-day window (Epic 08 sub-spec 1
 * §4.2). {@link #of(Escrow, User, User, OffsetDateTime)} takes an
 * explicit {@code now} so the service layer's Clock owns time — a
 * frozen-clock test can assert {@code hoursRemaining} deterministically.
 * The pre-resolved {@code counterparty} is provided by the service (the
 * {@link Escrow} only carries {@code winnerUserId} as a numeric id, not
 * a managed User ref) so this record stays pure.
 *
 * <p>{@code viewerRole} describes the reviewer's role in the transaction
 * (what the frontend card badge reads — "Seller"/"Buyer"), NOT the role
 * of the person being reviewed. A seller who owes a review has
 * {@code viewerRole=SELLER} even though they will be writing about their
 * buyer's behaviour.
 */
public record PendingReviewDto(
        Long auctionId,
        String title,
        String primaryPhotoUrl,
        Long counterpartyId,
        String counterpartyDisplayName,
        String counterpartyAvatarUrl,
        OffsetDateTime escrowCompletedAt,
        OffsetDateTime windowClosesAt,
        long hoursRemaining,
        ReviewedRole viewerRole) {

    /**
     * Build a {@link PendingReviewDto} from a COMPLETED {@link Escrow},
     * the viewer, the pre-resolved counterparty, and the service's
     * injected {@code now}.
     */
    public static PendingReviewDto of(Escrow e, User viewer, User counterparty,
                                       OffsetDateTime now) {
        boolean viewerIsSeller = e.getAuction().getSeller().getId().equals(viewer.getId());
        OffsetDateTime windowCloses = e.getCompletedAt().plus(ReviewService.REVIEW_WINDOW);
        long hoursRemaining = Math.max(0L,
                Duration.between(now, windowCloses).toHours());

        ReviewedRole viewerRole = viewerIsSeller ? ReviewedRole.SELLER : ReviewedRole.BUYER;

        String photo = e.getAuction().getPhotos().stream()
                .sorted(Comparator.comparing(AuctionPhoto::getSortOrder))
                .findFirst()
                .map(p -> "/api/v1/auctions/" + e.getAuction().getId()
                        + "/photos/" + p.getId() + "/bytes")
                .orElse(null);

        return new PendingReviewDto(
                e.getAuction().getId(),
                e.getAuction().getTitle(),
                photo,
                counterparty == null ? null : counterparty.getId(),
                counterparty == null ? null : counterparty.getDisplayName(),
                counterparty == null ? null : avatarUrl(counterparty.getId()),
                e.getCompletedAt(),
                windowCloses,
                hoursRemaining,
                viewerRole);
    }

    private static String avatarUrl(Long userId) {
        return userId == null ? null : "/api/v1/users/" + userId + "/avatar/256";
    }
}
