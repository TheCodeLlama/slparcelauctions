package com.slparcelauctions.backend.escrow.dispute.exception;

public class EvidenceAlreadySubmittedException extends RuntimeException {
    public EvidenceAlreadySubmittedException(long escrowId) {
        super("Seller evidence already submitted for escrow " + escrowId);
    }
}
