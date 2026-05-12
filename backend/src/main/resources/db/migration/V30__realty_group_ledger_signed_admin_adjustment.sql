-- V30: realty groups sub-project G section 7.2 -- allow signed amounts on
-- ADMIN_ADJUSTMENT ledger rows. V26 created realty_group_ledger with a blanket
-- `amount > 0` check. ADMIN_ADJUSTMENT rows must carry the direction in the
-- sign (positive = credit, negative = debit), so the constraint is loosened to
-- "amount != 0 always, signed only for ADMIN_ADJUSTMENT". V29 widened the
-- entry_type CHECK to admit ADMIN_ADJUSTMENT but missed the amount sign.

ALTER TABLE realty_group_ledger DROP CONSTRAINT IF EXISTS realty_group_ledger_amount_check;
ALTER TABLE realty_group_ledger ADD CONSTRAINT realty_group_ledger_amount_check CHECK (
    amount <> 0
    AND (entry_type = 'ADMIN_ADJUSTMENT' OR amount > 0)
);
