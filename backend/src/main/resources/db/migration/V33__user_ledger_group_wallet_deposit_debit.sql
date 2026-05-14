-- V33: realty groups sub-project H section 5.2 -- add GROUP_WALLET_DEPOSIT_DEBIT
-- to user_ledger. Paired with MEMBER_DEPOSIT on realty_group_ledger (V32) via a
-- shared idempotencyKey. user_ledger_entry_type_check was last touched in V27.

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
        'GROUP_WALLET_DEPOSIT_DEBIT'
    )
);
