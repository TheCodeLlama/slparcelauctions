package com.slparcelauctions.backend.admin.escrowreview.exception;

import java.util.UUID;

import com.slparcelauctions.backend.escrow.review.ManualReviewStatus;

public class EscrowReviewAlreadyResolvedException extends RuntimeException {
    public EscrowReviewAlreadyResolvedException(UUID reviewPublicId, ManualReviewStatus status) {
        super("Escrow manual review " + reviewPublicId
                + " is not OPEN (current status " + status + ")");
    }
}
