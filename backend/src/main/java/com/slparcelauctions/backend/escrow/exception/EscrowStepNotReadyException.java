package com.slparcelauctions.backend.escrow.exception;

/**
 * Thrown when a manual action is attempted out of sub-phase order — e.g.
 * "Verify Purchase" (Step 3) before the Set-Sell-To gate
 * ({@code sellToConfirmedAt}) has been confirmed (spec §6.1). Mapped to
 * {@code 409 ESCROW_STEP_NOT_READY} by {@link EscrowExceptionHandler}.
 */
public class EscrowStepNotReadyException extends RuntimeException {

    public EscrowStepNotReadyException(String message) {
        super(message);
    }
}
