package com.slparcelauctions.backend.admin.escrowreview.exception;

import java.util.UUID;

public class EscrowReviewNotFoundException extends RuntimeException {
    public EscrowReviewNotFoundException(UUID reviewPublicId) {
        super("Escrow manual review not found: " + reviewPublicId);
    }
}
