package com.slparcelauctions.backend.escrow;

/**
 * Reason an escrow was moved to {@link EscrowState#FROZEN} by the
 * ownership-monitor job (spec §4.5). Persisted on
 * {@link Escrow#getFreezeReason()} as the enum {@code name()} so the dispute
 * timeline and admin dashboard can render the original trigger verbatim.
 *
 * <ul>
 *   <li>{@link #UNKNOWN_OWNER} — World API reported the parcel's owner is an
 *       avatar other than the seller or the auction winner.</li>
 *   <li>{@link #PARCEL_DELETED} — World API returned 404 for the parcel UUID
 *       (parcel deleted, merged, or returned to Linden Lab).</li>
 *   <li>{@link #WORLD_API_PERSISTENT_FAILURE} — World API was unreachable for
 *       {@code slpa.escrow.ownershipApiFailureThreshold} consecutive sweeps;
 *       we freeze rather than indefinitely stall the transfer window.</li>
 * </ul>
 */
public enum FreezeReason {
    UNKNOWN_OWNER,
    PARCEL_DELETED,
    WORLD_API_PERSISTENT_FAILURE
}
