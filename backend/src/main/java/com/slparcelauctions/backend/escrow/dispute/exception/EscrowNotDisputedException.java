package com.slparcelauctions.backend.escrow.dispute.exception;

public class EscrowNotDisputedException extends RuntimeException {
    public EscrowNotDisputedException(long escrowId, String state) {
        super("Escrow " + escrowId + " is not in DISPUTED state (state=" + state + ")");
    }
}
