package com.slparcelauctions.backend.auction.exception;

/**
 * Enumerates the reasons a seller can be barred from creating a new listing
 * (Epic 08 sub-spec 2 §7.7). Order is significant: the gate evaluates the most
 * restrictive condition first ({@code PERMANENT_BAN} → {@code TIMED_SUSPENSION}
 * → {@code PENALTY_OWED}) and surfaces the first match. The enum value rides
 * along on the {@code SellerSuspendedException} and is serialised as the
 * {@code code} property on the 403 ProblemDetail so the frontend can branch on
 * it without parsing the human-readable detail.
 */
public enum SuspensionReason {
    PENALTY_OWED,
    TIMED_SUSPENSION,
    PERMANENT_BAN
}
