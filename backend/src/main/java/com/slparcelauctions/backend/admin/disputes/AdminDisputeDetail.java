package com.slparcelauctions.backend.admin.disputes;

import com.slparcelauctions.backend.escrow.EscrowDisputeReasonCategory;
import com.slparcelauctions.backend.escrow.EscrowState;

import java.time.OffsetDateTime;
import java.util.List;

public record AdminDisputeDetail(
        long escrowId,
        long auctionId,
        String auctionTitle,
        String sellerEmail,
        long sellerUserId,
        String winnerEmail,
        long winnerUserId,
        long salePriceL,
        EscrowState status,
        EscrowDisputeReasonCategory reasonCategory,
        String winnerDescription,
        String slTransactionKey,
        List<DisputeEvidenceImageDto> winnerEvidence,
        String sellerEvidenceText,
        OffsetDateTime sellerEvidenceSubmittedAt,
        List<DisputeEvidenceImageDto> sellerEvidence,
        OffsetDateTime openedAt,
        List<EscrowLedgerEntry> ledger) {

    public record EscrowLedgerEntry(
            OffsetDateTime at,
            String type,
            Long amount,
            String detail) {
    }
}
