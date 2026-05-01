-- Reconciliation extension for the wallet model.
--
-- Per spec: docs/superpowers/specs/2026-04-30-wallet-model-design.md §3.4 + §11.
--
-- The existing ReconciliationService runs daily and compares
-- expected_locked_sum (sum of escrows in FUNDED/TRANSFER_PENDING/DISPUTED/
-- FROZEN states) against the SLPA service avatar's observed L\$ balance.
--
-- The wallet model extends this:
--   - Add wallet_balance_total (SUM(users.balance_lindens))
--   - Add wallet_reserved_total (sub-breakdown for forensics; not summed
--     into expected because reservations are inside balance, not separate)
--   - Add escrow_locked_total (renamed clarity for what expected_locked_sum
--     already represents — kept as duplicate for back-compat with old runs)
--   - Add DENORM_DRIFT status for when users.reserved_lindens disagrees
--     with bid_reservations sum.
--
-- After this migration, expected = expected_locked_sum + wallet_balance_total
-- (computed at run time, not stored).

ALTER TABLE reconciliation_runs
    ADD COLUMN wallet_balance_total BIGINT NULL,
    ADD COLUMN wallet_reserved_total BIGINT NULL,
    ADD COLUMN escrow_locked_total BIGINT NULL;

-- Update status CHECK to include DENORM_DRIFT.
ALTER TABLE reconciliation_runs
    DROP CONSTRAINT IF EXISTS reconciliation_runs_status_check;

ALTER TABLE reconciliation_runs
    ADD CONSTRAINT reconciliation_runs_status_check
        CHECK (status IN ('BALANCED', 'MISMATCH', 'ERROR', 'DENORM_DRIFT'));
