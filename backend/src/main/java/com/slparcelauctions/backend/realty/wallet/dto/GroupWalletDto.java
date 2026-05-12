package com.slparcelauctions.backend.realty.wallet.dto;

import java.time.Instant;
import java.util.List;

/**
 * Response DTO for GET /api/v1/realty/groups/{publicId}/wallet.
 * Spec §5.1 / §5.6 (D); {@code leaderTermsAcceptedAt} added in sub-project G §7.5
 * so the frontend leader-terms-block banner can conditionally render.
 *
 * <p>{@code leaderTermsAcceptedAt} is the timestamp the group's leader accepted
 * the wallet Terms of Service (sourced from {@link
 * com.slparcelauctions.backend.user.User#getWalletTermsAcceptedAt()}). Null
 * means the leader has NOT accepted terms; the banner shows on that condition.
 */
public record GroupWalletDto(
        long balance,
        long reserved,
        long available,
        Instant leaderTermsAcceptedAt,
        List<GroupLedgerEntryDto> recentLedger) {
}
