-- Wallet-only escrow funding (spec 2026-05-16-wallet-only-escrow-funding):
-- escrows auto-fund from the winner's bid reservation inside
-- createForEndedAuction, so the 48h payment_deadline window never matters.
-- ESCROW_PENDING is now a transactional intermediate; no row persists in
-- that state past commit. Drop the column + its index.

DROP INDEX IF EXISTS ix_escrows_payment_deadline;
ALTER TABLE escrows DROP COLUMN IF EXISTS payment_deadline;
