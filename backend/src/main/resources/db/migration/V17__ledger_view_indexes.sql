-- Indexes for the admin global ledger view (`/admin/ledger`).
-- The page issues a UNION ALL across user_ledger / escrow_transactions /
-- terminal_commands / withdrawals / bid_reservations and sorts by created_at
-- DESC. user_ledger already has the (user_id, created_at DESC) composite from
-- V3 — this migration adds the missing single-column indexes so the other
-- three sources can satisfy the outer ORDER BY without a sort step.
CREATE INDEX IF NOT EXISTS ix_escrow_transactions_created_at
    ON escrow_transactions (created_at DESC);
CREATE INDEX IF NOT EXISTS ix_terminal_commands_created_at
    ON terminal_commands (created_at DESC);
CREATE INDEX IF NOT EXISTS ix_withdrawals_created_at
    ON withdrawals (created_at DESC);
