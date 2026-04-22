package com.slparcelauctions.backend.escrow;

/**
 * Machine reason for an escrow freeze. Mapped to FraudFlagReason inside
 * EscrowService.freezeForFraud. See spec §4.5 + Epic 06 spec §6.2.
 */
public enum FreezeReason {
    UNKNOWN_OWNER,
    PARCEL_DELETED,
    WORLD_API_PERSISTENT_FAILURE,
    /**
     * Raised by the Epic 06 bot escrow monitor when observed OwnerID is
     * neither seller nor winner during an active escrow. Treated identically
     * to UNKNOWN_OWNER for state transition purposes; the FraudFlag reason
     * differs to keep the admin-dashboard signal source explicit.
     */
    BOT_OWNERSHIP_CHANGED
}
