-- Wallet balance columns on users for the wallet model.
--
-- Per spec: docs/superpowers/specs/2026-04-30-wallet-model-design.md §3.1.
--
-- balance_lindens         — total wallet balance (available + reserved)
-- reserved_lindens        — sum of active bid reservations (denormalized;
--                           sourced from bid_reservations)
-- wallet_dormancy_*       — dormancy tracking (30d threshold, 4 weekly IMs)
-- wallet_terms_*          — first-deposit click-through gate

ALTER TABLE users
    ADD COLUMN balance_lindens BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN reserved_lindens BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN wallet_dormancy_started_at TIMESTAMPTZ NULL,
    ADD COLUMN wallet_dormancy_phase SMALLINT NULL,
    ADD COLUMN wallet_terms_accepted_at TIMESTAMPTZ NULL,
    ADD COLUMN wallet_terms_version VARCHAR(16) NULL;

ALTER TABLE users
    ADD CONSTRAINT users_balance_nonneg
        CHECK (balance_lindens >= 0),
    ADD CONSTRAINT users_reserved_nonneg
        CHECK (reserved_lindens >= 0),
    ADD CONSTRAINT users_balance_ge_reserved
        CHECK (balance_lindens >= reserved_lindens),
    ADD CONSTRAINT users_dormancy_phase_check
        CHECK (wallet_dormancy_phase IS NULL
               OR wallet_dormancy_phase BETWEEN 1 AND 99);
