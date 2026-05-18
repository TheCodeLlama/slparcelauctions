-- V38: allow signed amounts on user_ledger ADJUSTMENT rows.
--
-- V3 created user_ledger with an inline `CHECK (amount > 0)` (Postgres
-- auto-named it user_ledger_amount_check). But AdminWalletService.adjust
-- writes the signed request amount under a single ADJUSTMENT entry type --
-- the same shape the group wallet uses for ADMIN_ADJUSTMENT. An admin debit
-- (negative amount) therefore violated user_ledger_amount_check and 500ed;
-- only credits worked. The invariant is "amount != 0 always, signed only for
-- the admin-adjustment entry type".
--
-- This mirrors V30 (realty_group_ledger_signed_admin_adjustment), which fixed
-- the byte-for-byte identical bug for realty_group_ledger. user_ledger's
-- admin-adjustment entry type is ADJUSTMENT (ADMIN_ADJUSTMENT is group-only).
--
-- balance_after / reserved_after remain the authoritative wallet snapshots;
-- reconciliation sums users.balance_lindens, never user_ledger.amount, so a
-- signed ADJUSTMENT row has no reconciliation impact.

ALTER TABLE user_ledger DROP CONSTRAINT IF EXISTS user_ledger_amount_check;
ALTER TABLE user_ledger ADD CONSTRAINT user_ledger_amount_check CHECK (
    amount <> 0
    AND (entry_type = 'ADJUSTMENT' OR amount > 0)
);
