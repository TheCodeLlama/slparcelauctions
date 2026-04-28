package com.slparcelauctions.backend.escrow.dispute.exception;

public class NotSellerOfEscrowException extends RuntimeException {
    public NotSellerOfEscrowException(long escrowId, long callerId) {
        super("User " + callerId + " is not the seller of escrow " + escrowId);
    }
}
