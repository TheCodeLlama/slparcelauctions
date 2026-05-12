-- V29: realty groups sub-project G -- final cleanup pass.
-- Drops C-era column family, widens ledger entry_type CHECK for ADMIN_ADJUSTMENT,
-- adds report-threshold notified flag, scrubs deleted SPEND_FROM_GROUP_WALLET enum.

-- sect 4 -- C-era column drops.
ALTER TABLE auctions DROP COLUMN IF EXISTS agent_fee_rate;
ALTER TABLE auctions DROP COLUMN IF EXISTS agent_fee_split;
ALTER TABLE auctions DROP COLUMN IF EXISTS agent_fee_amt;
ALTER TABLE realty_groups DROP COLUMN IF EXISTS agent_fee_rate;
ALTER TABLE realty_groups DROP COLUMN IF EXISTS agent_fee_split;

-- sect 7.1 / 7.2 -- widen entry_type CHECK to admit ADMIN_ADJUSTMENT.
ALTER TABLE realty_group_ledger DROP CONSTRAINT IF EXISTS realty_group_ledger_entry_type_check;
ALTER TABLE realty_group_ledger ADD CONSTRAINT realty_group_ledger_entry_type_check CHECK (
    entry_type IN (
        'LISTING_FEE_DEBIT', 'LISTING_FEE_REFUND', 'AGENT_FEE_CREDIT', 'LISTING_PAYOUT',
        'WITHDRAW_QUEUED', 'WITHDRAW_COMPLETED', 'WITHDRAW_REVERSED',
        'DORMANCY_AUTO_RETURN', 'ADJUSTMENT', 'ADMIN_ADJUSTMENT'
    )
);

-- sect 11 -- drop dead SPEND_FROM_GROUP_WALLET permission from any persisted member rows.
UPDATE realty_group_members
   SET permissions = array_remove(permissions, 'SPEND_FROM_GROUP_WALLET')
 WHERE 'SPEND_FROM_GROUP_WALLET' = ANY(permissions);

-- sect 12.5 -- per-group flag for one-shot-per-cycle report-threshold notification.
ALTER TABLE realty_groups
  ADD COLUMN IF NOT EXISTS reports_threshold_notified BOOLEAN NOT NULL DEFAULT FALSE;
