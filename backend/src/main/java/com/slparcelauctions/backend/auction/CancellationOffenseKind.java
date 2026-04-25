package com.slparcelauctions.backend.auction;

/**
 * Discriminator for the consequence applied at a single cancellation event.
 * Stored on {@link CancellationLog} as a snapshot — historical fact, not a
 * derivation of what would be computed today. The decision uses live query
 * of prior log rows; the record is immutable. See spec §4.3.
 *
 * <ul>
 *   <li>{@code NONE} — pre-live cancellation or active-without-bids; no
 *       penalty applied.</li>
 *   <li>{@code WARNING} — first cancelled-with-bids offense; logged only,
 *       no debt or suspension.</li>
 *   <li>{@code PENALTY} — second cancelled-with-bids offense; debits the
 *       configured second-offense L$ amount (default 1000).</li>
 *   <li>{@code PENALTY_AND_30D} — third cancelled-with-bids offense; debits
 *       the configured third-offense L$ amount (default 2500) and stamps a
 *       30-day listing suspension.</li>
 *   <li>{@code PERMANENT_BAN} — fourth-or-later cancelled-with-bids offense;
 *       sets {@code User.bannedFromListing = true}.</li>
 * </ul>
 */
public enum CancellationOffenseKind {
    NONE,
    WARNING,
    PENALTY,
    PENALTY_AND_30D,
    PERMANENT_BAN
}
