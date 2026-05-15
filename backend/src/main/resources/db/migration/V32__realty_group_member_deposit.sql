-- V32: realty groups sub-project H section 5.1 -- add MEMBER_DEPOSIT entry type.
-- Extends the realty_group_ledger entry_type CHECK constraint (last touched in
-- V29) to admit member-initiated deposits into the group wallet, covering both
-- the app flow (personal SLParcels wallet -> group wallet) and the in-world
-- flow (avatar pays L$ at a terminal, routed to a chosen group).

ALTER TABLE realty_group_ledger DROP CONSTRAINT realty_group_ledger_entry_type_check;
ALTER TABLE realty_group_ledger ADD CONSTRAINT realty_group_ledger_entry_type_check CHECK (
    entry_type IN (
        'LISTING_FEE_DEBIT', 'LISTING_FEE_REFUND', 'AGENT_FEE_CREDIT', 'LISTING_PAYOUT',
        'WITHDRAW_QUEUED', 'WITHDRAW_COMPLETED', 'WITHDRAW_REVERSED',
        'DORMANCY_AUTO_RETURN', 'ADJUSTMENT', 'ADMIN_ADJUSTMENT',
        'MEMBER_DEPOSIT'
    )
);
