-- Per-user wallet ledger (append-only).
--
-- Per spec: docs/superpowers/specs/2026-04-30-wallet-model-design.md §3.2.
--
-- Every L$ movement to/from a user's wallet appends a row here. The ledger
-- is the source of truth for wallet state; users.balance_lindens and
-- users.reserved_lindens are denorms. Reconciliation verifies them daily.
--
-- amount is always positive; direction is implicit in entry_type.
-- balance_after / reserved_after are snapshots written at insert time.
--
-- sl_transaction_id (LSL llGenerateKey()) deduplicates terminal-side retries.
-- idempotency_key (client-supplied) deduplicates frontend / admin retries.
-- Both are UNIQUE WHERE NOT NULL — duplicate inserts fail loudly.

CREATE TABLE user_ledger (
    id                  BIGSERIAL PRIMARY KEY,
    user_id             BIGINT NOT NULL REFERENCES users(id),
    entry_type          VARCHAR(32) NOT NULL,
    amount              BIGINT NOT NULL CHECK (amount > 0),
    balance_after       BIGINT NOT NULL,
    reserved_after      BIGINT NOT NULL,
    ref_type            VARCHAR(32) NULL,
    ref_id              BIGINT NULL,
    sl_transaction_id   VARCHAR(36) NULL,
    idempotency_key     VARCHAR(64) NULL,
    description         VARCHAR(500) NULL,
    created_at          TIMESTAMPTZ NOT NULL,
    created_by_admin_id BIGINT NULL REFERENCES users(id),

    CONSTRAINT user_ledger_entry_type_check CHECK (entry_type IN (
        'DEPOSIT',
        'WITHDRAW_QUEUED', 'WITHDRAW_COMPLETED', 'WITHDRAW_REVERSED',
        'BID_RESERVED', 'BID_RELEASED',
        'ESCROW_DEBIT', 'ESCROW_REFUND',
        'LISTING_FEE_DEBIT', 'LISTING_FEE_REFUND',
        'PENALTY_DEBIT',
        'ADJUSTMENT'
    ))
);

CREATE INDEX user_ledger_user_created_idx
    ON user_ledger (user_id, created_at DESC);

CREATE UNIQUE INDEX user_ledger_sl_tx_idx
    ON user_ledger (sl_transaction_id) WHERE sl_transaction_id IS NOT NULL;

CREATE UNIQUE INDEX user_ledger_idempotency_idx
    ON user_ledger (idempotency_key) WHERE idempotency_key IS NOT NULL;
