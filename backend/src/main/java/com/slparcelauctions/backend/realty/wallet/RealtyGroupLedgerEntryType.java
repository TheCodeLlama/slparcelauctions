package com.slparcelauctions.backend.realty.wallet;

/**
 * Realty-group ledger entry types. The CHECK constraint on
 * realty_group_ledger.entry_type mirrors this enum.
 */
public enum RealtyGroupLedgerEntryType {
    LISTING_FEE_DEBIT,
    LISTING_FEE_REFUND,
    AGENT_FEE_CREDIT,
    /**
     * Sub-project E -- case-3 group slice of earnings at escrow completion.
     * Credited to the realty group's wallet after the group-wallet PAYOUT
     * terminal command succeeds. {@code amount = earnings - agent_slice}.
     * See spec §9.6.
     */
    LISTING_PAYOUT,
    WITHDRAW_QUEUED,
    WITHDRAW_COMPLETED,
    WITHDRAW_REVERSED,
    DORMANCY_AUTO_RETURN,
    ADJUSTMENT,
    /**
     * Sub-project G -- admin-initiated wallet adjustment (credit or debit).
     * Direction is encoded in the sign of {@code amount}. A required
     * {@code description} carries the freeform admin reason. Audit row is
     * paired via {@link com.slparcelauctions.backend.admin.audit.AdminActionType#REALTY_GROUP_WALLET_ADMIN_ADJUSTMENT}.
     * See spec §7.2.
     */
    ADMIN_ADJUSTMENT
}
