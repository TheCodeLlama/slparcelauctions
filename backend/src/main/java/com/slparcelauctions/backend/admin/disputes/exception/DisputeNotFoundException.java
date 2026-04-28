package com.slparcelauctions.backend.admin.disputes.exception;

public class DisputeNotFoundException extends RuntimeException {
    public DisputeNotFoundException(long escrowId) {
        super("Dispute not found for escrow " + escrowId);
    }
}
