package com.slparcelauctions.backend.auction.dto;

import java.time.OffsetDateTime;

/**
 * {@code GET /api/v1/users/me/cancellation-status} response shape (Epic 08
 * sub-spec 2 §7.3). Three concerns:
 * <ul>
 *   <li>{@link #priorOffensesWithBids} — count of historical
 *       cancelled-with-bids logs for this seller, drives the ladder index in
 *       the cancel modal preview.</li>
 *   <li>{@link #currentSuspension} — current account state mirroring the
 *       three new {@code User} columns (penalty balance, suspension expiry,
 *       permanent ban). Echoed here so the dashboard banner has the data on
 *       a single fetch.</li>
 *   <li>{@link #nextConsequenceIfBidsPresent} — preview of what the next
 *       cancel WOULD trigger if the auction had bids. Never
 *       {@link com.slparcelauctions.backend.auction.CancellationOffenseKind#NONE}
 *       since the question is hypothetical — see {@link NextConsequenceDto}.</li>
 * </ul>
 */
public record CancellationStatusResponse(
        long priorOffensesWithBids,
        CurrentSuspension currentSuspension,
        NextConsequenceDto nextConsequenceIfBidsPresent) {

    public record CurrentSuspension(
            Long penaltyBalanceOwed,
            OffsetDateTime listingSuspensionUntil,
            Boolean bannedFromListing) {}
}
