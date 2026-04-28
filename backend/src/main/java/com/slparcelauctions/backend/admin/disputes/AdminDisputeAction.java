package com.slparcelauctions.backend.admin.disputes;

public enum AdminDisputeAction {
    RECOGNIZE_PAYMENT,    // DISPUTED → TRANSFER_PENDING
    RESET_TO_FUNDED,      // DISPUTED → FUNDED (or → EXPIRED if alsoCancelListing)
    RESUME_TRANSFER,      // FROZEN → TRANSFER_PENDING
    MARK_EXPIRED          // FROZEN → EXPIRED
}
