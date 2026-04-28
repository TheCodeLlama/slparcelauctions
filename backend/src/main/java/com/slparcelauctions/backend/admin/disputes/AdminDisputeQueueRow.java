package com.slparcelauctions.backend.admin.disputes;

import com.slparcelauctions.backend.escrow.EscrowDisputeReasonCategory;
import com.slparcelauctions.backend.escrow.EscrowState;

import java.time.OffsetDateTime;

public record AdminDisputeQueueRow(
        long escrowId,
        long auctionId,
        String auctionTitle,
        String sellerEmail,
        String winnerEmail,
        long salePriceL,
        EscrowState status,
        EscrowDisputeReasonCategory reasonCategory,
        OffsetDateTime openedAt,
        long ageMinutes,
        int winnerEvidenceCount,
        int sellerEvidenceCount) {
}
