package com.slparcelauctions.backend.realty.wallet.exception;

/**
 * Thrown when a group dissolution is attempted but the group has one or more escrows
 * in a state that still has L$ movement ahead (FUNDED, AWAITING_TRANSFER, DISPUTED).
 * Maps to 409 CONFLICT. Spec §5.5, §9.1.
 *
 * <p>Resolution: wait for in-flight escrows to complete (transfer confirmed or expired)
 * before dissolving.
 */
public class GroupHasInFlightEscrowsException extends RuntimeException {

    public GroupHasInFlightEscrowsException() {
        super("group has in-flight escrows; cannot dissolve");
    }
}
