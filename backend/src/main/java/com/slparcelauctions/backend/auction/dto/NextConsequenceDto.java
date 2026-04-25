package com.slparcelauctions.backend.auction.dto;

import com.slparcelauctions.backend.auction.CancellationOffenseKind;

/**
 * Cancel-modal preview row — what would happen IF the seller cancels an
 * {@code ACTIVE}-with-bids auction right now (Epic 08 sub-spec 2 §7.3). Because
 * the query is hypothetical ("if bids were present"), {@link #kind} is never
 * {@link CancellationOffenseKind#NONE} — even a clean record (0 prior
 * offenses) yields {@link CancellationOffenseKind#WARNING}. The boolean
 * convenience flags ({@link #suspends30Days}, {@link #permanentBan}) are
 * derived from {@link #kind} so the frontend can render badges off a single
 * field without re-deriving.
 *
 * <p>{@link #amountL} is null for {@code WARNING} and {@code PERMANENT_BAN}
 * (no L$ change) and populated for {@code PENALTY} / {@code PENALTY_AND_30D}.
 */
public record NextConsequenceDto(
        CancellationOffenseKind kind,
        Long amountL,
        Boolean suspends30Days,
        Boolean permanentBan) {

    public static NextConsequenceDto from(CancellationOffenseKind kind, Long amountL) {
        return new NextConsequenceDto(
                kind,
                amountL,
                kind == CancellationOffenseKind.PENALTY_AND_30D,
                kind == CancellationOffenseKind.PERMANENT_BAN);
    }
}
