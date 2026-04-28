package com.slparcelauctions.backend.admin.disputes;

import com.slparcelauctions.backend.escrow.EscrowState;

import java.time.OffsetDateTime;

public record AdminDisputeResolveResponse(
        long escrowId,
        EscrowState newState,
        boolean refundQueued,
        boolean listingCancelled,
        OffsetDateTime resolvedAt) {
}
