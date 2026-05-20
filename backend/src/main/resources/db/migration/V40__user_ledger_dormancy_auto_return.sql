-- V40: add DORMANCY_AUTO_RETURN to user_ledger.entry_type CHECK constraint.
--
-- Wallet dormancy auto-return appends a UserLedgerEntry of this type
-- alongside the matching TerminalCommand{WITHDRAW,
-- USER_WALLET_DORMANCY_AUTO_RETURN} row at phase 4 of the dormancy state
-- machine. Mirrors realty_group_ledger.DORMANCY_AUTO_RETURN. Spec
-- docs/superpowers/specs/2026-05-19-user-wallet-dormancy-design.md §3.
--
-- user_ledger_entry_type_check was last touched in V39.

ALTER TABLE user_ledger DROP CONSTRAINT user_ledger_entry_type_check;
ALTER TABLE user_ledger ADD CONSTRAINT user_ledger_entry_type_check CHECK (
    entry_type IN (
        'DEPOSIT',
        'WITHDRAW_QUEUED', 'WITHDRAW_COMPLETED', 'WITHDRAW_REVERSED',
        'BID_RESERVED', 'BID_RELEASED',
        'ESCROW_DEBIT', 'ESCROW_REFUND',
        'LISTING_FEE_DEBIT', 'LISTING_FEE_REFUND',
        'PENALTY_DEBIT',
        'AGENT_FEE_CREDIT',
        'AGENT_COMMISSION_CREDIT',
        'ADJUSTMENT',
        'GROUP_WALLET_DEPOSIT_DEBIT',
        'AUCTION_PAYOUT_CREDIT',
        'DORMANCY_AUTO_RETURN'
    )
);
