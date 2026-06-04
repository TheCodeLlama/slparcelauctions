-- V48: add PROMOTION_DEBIT and PROMOTION_REFUND to user_ledger.entry_type CHECK
-- constraint. Paired with the PROMOTION_DEBIT / PROMOTION_REFUND members added
-- to UserLedgerEntryType in the same feature branch. Required by
-- PromotionService.purchaseFeatured which writes PROMOTION_DEBIT rows when a
-- seller buys PROMO-01 (Featured listing) exposure.
--
-- user_ledger_entry_type_check was last touched in V40.

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
        'DORMANCY_AUTO_RETURN',
        'PROMOTION_DEBIT',
        'PROMOTION_REFUND'
    )
);
