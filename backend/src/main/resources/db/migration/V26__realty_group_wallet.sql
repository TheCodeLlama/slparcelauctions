-- V26__realty_group_wallet.sql
-- Sub-project D: group wallet schema.

-- 1. Wallet columns on realty_groups.
ALTER TABLE realty_groups
    ADD COLUMN balance_lindens BIGINT NOT NULL DEFAULT 0
        CHECK (balance_lindens >= 0),
    ADD COLUMN reserved_lindens BIGINT NOT NULL DEFAULT 0
        CHECK (reserved_lindens >= 0),
    ADD CONSTRAINT realty_groups_wallet_balance_ge_reserved
        CHECK (balance_lindens >= reserved_lindens),
    ADD COLUMN wallet_dormancy_started_at TIMESTAMPTZ NULL,
    ADD COLUMN wallet_dormancy_phase SMALLINT NULL
        CHECK (wallet_dormancy_phase BETWEEN 1 AND 4 OR wallet_dormancy_phase = 99);

-- 2. realty_group_ledger table.
CREATE TABLE realty_group_ledger (
    id                  BIGSERIAL PRIMARY KEY,
    public_id           UUID NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    group_id            BIGINT NOT NULL REFERENCES realty_groups(id),
    entry_type          VARCHAR(32) NOT NULL,
    amount              BIGINT NOT NULL CHECK (amount > 0),
    balance_after       BIGINT NOT NULL,
    reserved_after      BIGINT NOT NULL,
    ref_type            VARCHAR(32) NULL,
    ref_id              BIGINT NULL,
    actor_user_id       BIGINT NULL REFERENCES users(id),
    sl_transaction_id   VARCHAR(36) NULL,
    idempotency_key     VARCHAR(64) NULL,
    description         VARCHAR(500) NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by_admin_id BIGINT NULL REFERENCES users(id)
);

CREATE INDEX realty_group_ledger_group_created_idx
    ON realty_group_ledger (group_id, created_at DESC);
CREATE UNIQUE INDEX realty_group_ledger_sl_tx_idx
    ON realty_group_ledger (sl_transaction_id) WHERE sl_transaction_id IS NOT NULL;
CREATE UNIQUE INDEX realty_group_ledger_idempotency_idx
    ON realty_group_ledger (idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX realty_group_ledger_listing_fee_lookup_idx
    ON realty_group_ledger (ref_type, ref_id) WHERE entry_type = 'LISTING_FEE_DEBIT';

ALTER TABLE realty_group_ledger ADD CONSTRAINT realty_group_ledger_entry_type_check CHECK (
    entry_type IN (
        'LISTING_FEE_DEBIT', 'LISTING_FEE_REFUND',
        'AGENT_FEE_CREDIT',
        'WITHDRAW_QUEUED', 'WITHDRAW_COMPLETED', 'WITHDRAW_REVERSED',
        'DORMANCY_AUTO_RETURN',
        'ADJUSTMENT'
    )
);

-- 3. Terminal command linkage so completion handlers know which ledger to write.
ALTER TABLE terminal_commands
    ADD COLUMN realty_group_id BIGINT NULL REFERENCES realty_groups(id);

CREATE INDEX terminal_commands_realty_group_id_idx
    ON terminal_commands (realty_group_id) WHERE realty_group_id IS NOT NULL;

-- 4. Reconciliation extension.
ALTER TABLE reconciliation_runs
    ADD COLUMN group_wallet_balance_total BIGINT NULL,
    ADD COLUMN group_wallet_reserved_total BIGINT NULL;

-- 5. user_ledger gets AGENT_FEE_CREDIT entry type.
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
        'ADJUSTMENT'
    )
);
