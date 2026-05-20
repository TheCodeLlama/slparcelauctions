-- V39: add AUCTION_PAYOUT_CREDIT to user_ledger.entry_type CHECK constraint.
--
-- Individual-sale payouts now credit the seller's SLParcels wallet inline
-- with escrow -> COMPLETED instead of dispatching a TerminalCommand{action=PAYOUT}
-- to the seller's avatar. Per the wallet-first policy the sale conclusion
-- keeps L$ inside SLParcels; the seller withdraws separately if they choose.
-- Idempotency key on the ledger row is "AUCPAYOUT-{escrowId}".
--
-- user_ledger_entry_type_check was last touched in V33.

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
        'AUCTION_PAYOUT_CREDIT'
    )
);
