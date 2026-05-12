package com.slparcelauctions.backend.realty.wallet;

/**
 * Realty-group ledger entry types. The CHECK constraint on
 * realty_group_ledger.entry_type mirrors this enum.
 */
public enum RealtyGroupLedgerEntryType {
    LISTING_FEE_DEBIT,
    LISTING_FEE_REFUND,
    AGENT_FEE_CREDIT,
    WITHDRAW_QUEUED,
    WITHDRAW_COMPLETED,
    WITHDRAW_REVERSED,
    DORMANCY_AUTO_RETURN,
    ADJUSTMENT
}
