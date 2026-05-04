package com.slparcelauctions.backend.user.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import com.slparcelauctions.backend.user.SellerCompletionRateMapper;
import com.slparcelauctions.backend.user.User;

/**
 * Public profile wire shape. Epic 08 sub-spec 1 §6.2 adds two derived
 * fields:
 *
 * <ul>
 *   <li>{@code completionRate} — server-computed via
 *       {@link SellerCompletionRateMapper} over the three denominator
 *       counters. {@code null} when all three are zero so the UI can
 *       render "—".</li>
 *   <li>{@code isNewSeller} — {@code true} when {@code completedSales}
 *       is below the 3-sale trust threshold. Exposed as a direct
 *       boolean so the profile card can render the "New Seller" badge
 *       without recomputing the threshold on the client.</li>
 * </ul>
 *
 * <p>The private reliability counters ({@code cancelledWithBids},
 * {@code escrowExpiredUnfulfilled}) are intentionally absent from the
 * response — only the computed rate surfaces to the wire.
 */
public record UserProfileResponse(
        UUID publicId,
        String displayName,
        String bio,
        String profilePicUrl,
        UUID slAvatarUuid,
        String slUsername,
        String slDisplayName,
        Boolean verified,
        BigDecimal avgSellerRating,
        BigDecimal avgBuyerRating,
        Integer totalSellerReviews,
        Integer totalBuyerReviews,
        Integer completedSales,
        BigDecimal completionRate,
        Boolean isNewSeller,
        OffsetDateTime createdAt) {

    /**
     * "New Seller" threshold. Kept public on the DTO so tests can
     * reference it without duplicating the constant.
     */
    public static final int NEW_SELLER_THRESHOLD = 3;

    public static UserProfileResponse from(User user) {
        int completed = user.getCompletedSales() == null ? 0 : user.getCompletedSales();
        int cancelled = user.getCancelledWithBids() == null ? 0 : user.getCancelledWithBids();
        int expiredUnfulfilled = user.getEscrowExpiredUnfulfilled() == null
                ? 0 : user.getEscrowExpiredUnfulfilled();
        BigDecimal rate = SellerCompletionRateMapper.compute(
                completed, cancelled, expiredUnfulfilled);
        boolean isNewSeller = completed < NEW_SELLER_THRESHOLD;
        return new UserProfileResponse(
                user.getPublicId(),
                user.getDisplayName(),
                user.getBio(),
                user.getProfilePicUrl(),
                user.getSlAvatarUuid(),
                user.getSlUsername(),
                user.getSlDisplayName(),
                user.getVerified(),
                user.getAvgSellerRating(),
                user.getAvgBuyerRating(),
                user.getTotalSellerReviews(),
                user.getTotalBuyerReviews(),
                user.getCompletedSales(),
                rate,
                isNewSeller,
                user.getCreatedAt());
    }
}
