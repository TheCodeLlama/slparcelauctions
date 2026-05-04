-- V12: Base entity UUID migration.
--
-- Drops every application table (in reverse dependency order, CASCADE handles
-- remaining FK constraints) and recreates them in the new shape:
--   - id bigserial PRIMARY KEY
--   - public_id uuid NOT NULL UNIQUE DEFAULT gen_random_uuid()
--   - created_at timestamptz NOT NULL DEFAULT now()
--   - version bigint NOT NULL DEFAULT 0   (mutable tables only)
--   - updated_at timestamptz NOT NULL DEFAULT now() (mutable tables only)
--
-- Excluded from BaseEntity hierarchy (kept close to original shape):
--   - auction_parcel_snapshots  (uses @MapsId, PK = auction_id)  -- NOTE: excluded entity
--   - terminals                 (uses String terminalId natural PK) -- NOTE: excluded entity
--
-- Column-rename notes applied in this migration:
--   - terminal_secrets: version -> secret_version
--   - bot_tasks: last_updated_at -> updated_at (inherited)
--   - bot_workers: first_seen_at -> created_at (inherited)
--   - withdrawals: requested_at -> created_at (inherited)
--
-- Partial unique indexes from runtime initializer classes are reproduced here
-- as durable schema (initializers remain in place; they use IF NOT EXISTS so
-- are harmless at boot).

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ============================================================
-- DROP PHASE (reverse topological order, children first)
-- ============================================================

DROP TABLE IF EXISTS review_flags CASCADE;
DROP TABLE IF EXISTS review_responses CASCADE;
DROP TABLE IF EXISTS reviews CASCADE;

DROP TABLE IF EXISTS listing_reports CASCADE;

DROP TABLE IF EXISTS admin_actions CASCADE;

DROP TABLE IF EXISTS cancellation_logs CASCADE;

DROP TABLE IF EXISTS bid_reservations CASCADE;
DROP TABLE IF EXISTS user_ledger CASCADE;

DROP TABLE IF EXISTS bids CASCADE;
DROP TABLE IF EXISTS proxy_bids CASCADE;

DROP TABLE IF EXISTS saved_auctions CASCADE;
DROP TABLE IF EXISTS auction_tags CASCADE;
DROP TABLE IF EXISTS auction_photos CASCADE;

DROP TABLE IF EXISTS listing_fee_refunds CASCADE;

DROP TABLE IF EXISTS fraud_flags CASCADE;

DROP TABLE IF EXISTS escrow_transactions CASCADE;

DROP TABLE IF EXISTS terminal_commands CASCADE;
DROP TABLE IF EXISTS withdrawals CASCADE;

DROP TABLE IF EXISTS bot_tasks CASCADE;
DROP TABLE IF EXISTS escrows CASCADE;

DROP TABLE IF EXISTS bot_workers CASCADE;

DROP TABLE IF EXISTS bans CASCADE;
DROP TABLE IF EXISTS verification_codes CASCADE;
DROP TABLE IF EXISTS notifications CASCADE;
DROP TABLE IF EXISTS notification CASCADE;
DROP TABLE IF EXISTS sl_im_message CASCADE;
DROP TABLE IF EXISTS reconciliation_runs CASCADE;
DROP TABLE IF EXISTS terminal_secrets CASCADE;
DROP TABLE IF EXISTS refresh_tokens CASCADE;

DROP TABLE IF EXISTS auction_parcel_snapshots CASCADE;
DROP TABLE IF EXISTS auctions CASCADE;
DROP TABLE IF EXISTS regions CASCADE;
DROP TABLE IF EXISTS parcel_tags CASCADE;
DROP TABLE IF EXISTS users CASCADE;
DROP TABLE IF EXISTS terminals CASCADE;

-- ============================================================
-- CREATE PHASE (topological order, parents first)
-- ============================================================

-- ------------------------------------------------------------
-- users (mutable)
-- ------------------------------------------------------------
CREATE TABLE users (
    id                          bigserial PRIMARY KEY,
    public_id                   uuid NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    created_at                  timestamptz NOT NULL DEFAULT now(),
    updated_at                  timestamptz NOT NULL DEFAULT now(),
    version                     bigint NOT NULL DEFAULT 0,

    email                       varchar(255) UNIQUE,
    password_hash               varchar(255) NOT NULL,
    sl_avatar_uuid              uuid UNIQUE,
    sl_avatar_name              varchar(255),
    sl_display_name             varchar(255),
    sl_username                 varchar(255),
    sl_born_date                date,
    sl_payinfo                  integer,
    display_name                varchar(255),
    bio                         text,
    profile_pic_url             text,
    role                        varchar(10) NOT NULL DEFAULT 'USER',
    verified                    boolean NOT NULL DEFAULT false,
    verified_at                 timestamptz,
    avg_seller_rating           numeric(3,2),
    avg_buyer_rating            numeric(3,2),
    total_seller_reviews        integer NOT NULL DEFAULT 0,
    total_buyer_reviews         integer NOT NULL DEFAULT 0,
    completed_sales             integer NOT NULL DEFAULT 0,
    cancelled_with_bids         integer NOT NULL DEFAULT 0,
    escrow_expired_unfulfilled  integer NOT NULL DEFAULT 0,
    listing_suspension_until    timestamptz,
    deleted_at                  timestamptz,
    penalty_balance_owed        bigint NOT NULL DEFAULT 0,
    banned_from_listing         boolean NOT NULL DEFAULT false,
    dismissed_reports_count     bigint NOT NULL DEFAULT 0,
    email_verified              boolean NOT NULL DEFAULT false,
    notify_email                jsonb,
    notify_sl_im                jsonb,
    notify_email_muted          boolean NOT NULL DEFAULT false,
    notify_sl_im_muted          boolean NOT NULL DEFAULT false,
    sl_im_quiet_start           time(0) without time zone,
    sl_im_quiet_end             time(0) without time zone,
    token_version               bigint NOT NULL DEFAULT 0,
    balance_lindens             bigint NOT NULL DEFAULT 0,
    reserved_lindens            bigint NOT NULL DEFAULT 0,
    wallet_dormancy_started_at  timestamptz,
    wallet_dormancy_phase       integer,
    wallet_terms_accepted_at    timestamptz,
    wallet_terms_version        varchar(16),

    CONSTRAINT users_role_check CHECK (role IN ('USER', 'ADMIN')),
    CONSTRAINT users_balance_nonneg CHECK (balance_lindens >= 0),
    CONSTRAINT users_reserved_nonneg CHECK (reserved_lindens >= 0),
    CONSTRAINT users_balance_ge_reserved CHECK (balance_lindens >= reserved_lindens),
    CONSTRAINT users_dormancy_phase_check CHECK (wallet_dormancy_phase IS NULL
                                                  OR wallet_dormancy_phase BETWEEN 1 AND 99)
);

-- ------------------------------------------------------------
-- refresh_tokens (mutable)
-- ------------------------------------------------------------
CREATE TABLE refresh_tokens (
    id           bigserial PRIMARY KEY,
    public_id    uuid NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    version      bigint NOT NULL DEFAULT 0,

    user_id      bigint NOT NULL REFERENCES users(id),
    token_hash   varchar(64) NOT NULL UNIQUE,
    expires_at   timestamptz NOT NULL,
    revoked_at   timestamptz,
    last_used_at timestamptz,
    user_agent   varchar(512),
    ip_address   varchar(45)
);

CREATE INDEX idx_refresh_tokens_user_active ON refresh_tokens (user_id, revoked_at);

-- ------------------------------------------------------------
-- regions (mutable)
-- ------------------------------------------------------------
CREATE TABLE regions (
    id              bigserial PRIMARY KEY,
    public_id       uuid NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    version         bigint NOT NULL DEFAULT 0,

    sl_uuid         uuid NOT NULL UNIQUE,
    name            varchar(100) NOT NULL UNIQUE,
    grid_x          double precision NOT NULL,
    grid_y          double precision NOT NULL,
    maturity_rating varchar(10) NOT NULL
);

CREATE INDEX ix_regions_grid_coords ON regions (grid_x, grid_y);
CREATE INDEX ix_regions_maturity    ON regions (maturity_rating);

-- ------------------------------------------------------------
-- parcel_tags (mutable)
-- ------------------------------------------------------------
CREATE TABLE parcel_tags (
    id          bigserial PRIMARY KEY,
    public_id   uuid NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    version     bigint NOT NULL DEFAULT 0,

    code        varchar(50) NOT NULL UNIQUE,
    label       varchar(100) NOT NULL,
    category    varchar(50) NOT NULL,
    description text,
    sort_order  integer NOT NULL DEFAULT 0,
    active      boolean NOT NULL DEFAULT true
);

-- ------------------------------------------------------------
-- auctions (mutable)
-- ------------------------------------------------------------
CREATE TABLE auctions (
    id                              bigserial PRIMARY KEY,
    public_id                       uuid NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    created_at                      timestamptz NOT NULL DEFAULT now(),
    updated_at                      timestamptz NOT NULL DEFAULT now(),
    version                         bigint NOT NULL DEFAULT 0,

    sl_parcel_uuid                  uuid NOT NULL,
    seller_id                       bigint NOT NULL REFERENCES users(id),
    listing_agent_id                bigint REFERENCES users(id),
    realty_group_id                 bigint,
    status                          varchar(30) NOT NULL,
    verification_tier               varchar(10),
    verification_method             varchar(20),
    assigned_bot_uuid               uuid,
    sale_sentinel_price             bigint,
    last_bot_check_at               timestamptz,
    bot_check_failures              integer NOT NULL DEFAULT 0,
    last_ownership_check_at         timestamptz,
    suspended_at                    timestamptz,
    post_cancel_watch_until         timestamptz,
    consecutive_world_api_failures  integer NOT NULL DEFAULT 0,
    listing_fee_paid                boolean NOT NULL DEFAULT false,
    listing_fee_amt                 bigint,
    listing_fee_txn                 varchar(255),
    listing_fee_paid_at             timestamptz,
    verified_at                     timestamptz,
    verification_notes              text,
    title                           varchar(120) NOT NULL,
    starting_bid                    bigint NOT NULL,
    reserve_price                   bigint,
    buy_now_price                   bigint,
    current_bid                     bigint NOT NULL DEFAULT 0,
    bid_count                       integer NOT NULL DEFAULT 0,
    winner_id                       bigint,
    current_bidder_id               bigint,
    winner_user_id                  bigint,
    final_bid_amount                bigint,
    end_outcome                     varchar(32),
    ended_at                        timestamptz,
    duration_hours                  integer NOT NULL,
    snipe_protect                   boolean NOT NULL DEFAULT false,
    snipe_window_min                integer,
    starts_at                       timestamptz,
    ends_at                         timestamptz,
    original_ends_at                timestamptz,
    seller_desc                     text,
    commission_rate                 numeric(5,4),
    commission_amt                  bigint,
    agent_fee_rate                  numeric(5,4),
    agent_fee_amt                   bigint,

    CONSTRAINT auctions_status_check CHECK (status IN (
        'DRAFT', 'DRAFT_PAID', 'VERIFICATION_PENDING', 'VERIFICATION_FAILED',
        'ACTIVE', 'ENDED', 'ESCROW_PENDING', 'ESCROW_FUNDED', 'TRANSFER_PENDING',
        'COMPLETED', 'CANCELLED', 'EXPIRED', 'DISPUTED', 'SUSPENDED'
    )),
    CONSTRAINT auctions_verification_tier_check CHECK (verification_tier IN (
        'SCRIPT', 'BOT', 'HUMAN'
    )),
    CONSTRAINT auctions_verification_method_check CHECK (verification_method IN (
        'UUID_ENTRY', 'REZZABLE', 'SALE_TO_BOT'
    )),
    CONSTRAINT auctions_end_outcome_check CHECK (end_outcome IN (
        'SOLD', 'RESERVE_NOT_MET', 'NO_BIDS', 'BOUGHT_NOW'
    ))
);

CREATE INDEX ix_auctions_status_ends_at      ON auctions (status, ends_at);
CREATE INDEX ix_auctions_status_starts_at    ON auctions (status, starts_at DESC);
CREATE INDEX ix_auctions_status_current_bid  ON auctions (status, current_bid);
CREATE INDEX ix_auctions_seller_status       ON auctions (seller_id, status);
CREATE INDEX ix_auctions_status_reserve      ON auctions (status, reserve_price);

-- Partial unique index: at most one auction per parcel in a locking status.
-- Recreated here as durable schema; ParcelLockingIndexInitializer also ensures
-- it at runtime (idempotent).
CREATE UNIQUE INDEX uq_auctions_parcel_locked_status
    ON auctions (sl_parcel_uuid)
    WHERE status IN ('ACTIVE', 'ENDED', 'ESCROW_PENDING',
                     'ESCROW_FUNDED', 'TRANSFER_PENDING', 'DISPUTED');

-- ------------------------------------------------------------
-- auction_parcel_snapshots (excluded from BaseEntity — @MapsId)
-- NOTE: excluded entity, retains current shape (PK = auction_id)
-- ------------------------------------------------------------
CREATE TABLE auction_parcel_snapshots (
    auction_id              bigint NOT NULL,
    sl_parcel_uuid          uuid NOT NULL,
    owner_uuid              uuid,
    owner_type              varchar(255),
    owner_name              varchar(255),
    parcel_name             varchar(255),
    description             text,
    region_id               bigint REFERENCES regions(id),
    region_name             varchar(255),
    region_maturity_rating  varchar(255),
    area_sqm                integer,
    position_x              double precision,
    position_y              double precision,
    position_z              double precision,
    slurl                   varchar(255),
    layout_map_url          varchar(255),
    layout_map_data         text,
    layout_map_at           timestamptz,
    verified_at             timestamptz,
    last_checked            timestamptz,
    CONSTRAINT auction_parcel_snapshots_pkey PRIMARY KEY (auction_id),
    CONSTRAINT fkchcmuirfnjo3jmv41upsh69ir FOREIGN KEY (auction_id) REFERENCES auctions(id)
);

CREATE INDEX ix_parcels_region  ON auction_parcel_snapshots (region_id);
CREATE INDEX ix_parcels_area_sqm ON auction_parcel_snapshots (area_sqm);

-- ------------------------------------------------------------
-- auction_tags (join table — no BaseEntity)
-- ------------------------------------------------------------
CREATE TABLE auction_tags (
    auction_id bigint NOT NULL REFERENCES auctions(id),
    tag_id     bigint NOT NULL REFERENCES parcel_tags(id),
    PRIMARY KEY (auction_id, tag_id)
);

CREATE INDEX ix_auction_tags_tag_id ON auction_tags (tag_id);

-- ------------------------------------------------------------
-- auction_photos (mutable)
-- ------------------------------------------------------------
CREATE TABLE auction_photos (
    id           bigserial PRIMARY KEY,
    public_id    uuid NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    version      bigint NOT NULL DEFAULT 0,

    auction_id   bigint NOT NULL REFERENCES auctions(id),
    object_key   varchar(500) NOT NULL,
    content_type varchar(50) NOT NULL,
    size_bytes   bigint NOT NULL,
    sort_order   integer NOT NULL,
    source       varchar(255) NOT NULL DEFAULT 'SELLER_UPLOAD',
    uploaded_at  timestamptz NOT NULL DEFAULT now()
);

-- Partial unique index: at most one SL-derived photo per auction.
CREATE UNIQUE INDEX uq_auction_photos_sl_snapshot
    ON auction_photos (auction_id)
    WHERE source = 'SL_PARCEL_SNAPSHOT';

-- ------------------------------------------------------------
-- proxy_bids (mutable)
-- ------------------------------------------------------------
CREATE TABLE proxy_bids (
    id          bigserial PRIMARY KEY,
    public_id   uuid NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    version     bigint NOT NULL DEFAULT 0,

    auction_id  bigint NOT NULL REFERENCES auctions(id),
    user_id     bigint NOT NULL REFERENCES users(id),
    max_amount  bigint NOT NULL,
    status      varchar(16) NOT NULL,

    CONSTRAINT proxy_bids_status_check CHECK (status IN ('ACTIVE', 'EXHAUSTED', 'CANCELLED'))
);

CREATE INDEX ix_proxy_bids_auction_status ON proxy_bids (auction_id, status);
CREATE INDEX ix_proxy_bids_user_auction   ON proxy_bids (user_id, auction_id);

-- Partial unique index: at most one ACTIVE proxy bid per (auction, user).
-- Reproduced here as durable schema; ProxyBidPartialUniqueIndexInitializer
-- also ensures it at runtime (idempotent).
CREATE UNIQUE INDEX proxy_bids_one_active_per_user
    ON proxy_bids (auction_id, user_id)
    WHERE status = 'ACTIVE';

-- ------------------------------------------------------------
-- bids (immutable — extends BaseEntity, no version/updated_at)
-- ------------------------------------------------------------
CREATE TABLE bids (
    id                     bigserial PRIMARY KEY,
    public_id              uuid NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    created_at             timestamptz NOT NULL DEFAULT now(),

    auction_id             bigint NOT NULL REFERENCES auctions(id),
    user_id                bigint NOT NULL REFERENCES users(id),
    amount                 bigint NOT NULL,
    bid_type               varchar(16) NOT NULL,
    proxy_bid_id           bigint,
    snipe_extension_minutes integer,
    new_ends_at            timestamptz,
    ip_address             varchar(45),

    CONSTRAINT bids_bid_type_check CHECK (bid_type IN ('MANUAL', 'PROXY_AUTO', 'BUY_NOW'))
);

CREATE INDEX ix_bids_auction_created    ON bids (auction_id, created_at);
CREATE INDEX ix_bids_user_auction_amount ON bids (user_id, auction_id, amount DESC);
CREATE INDEX ix_bids_user_created       ON bids (user_id, created_at DESC);

-- ------------------------------------------------------------
-- bid_reservations (mutable)
-- ------------------------------------------------------------
CREATE TABLE bid_reservations (
    id              bigserial PRIMARY KEY,
    public_id       uuid NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    version         bigint NOT NULL DEFAULT 0,

    user_id         bigint NOT NULL REFERENCES users(id),
    auction_id      bigint NOT NULL REFERENCES auctions(id),
    bid_id          bigint NOT NULL REFERENCES bids(id),
    amount          bigint NOT NULL CHECK (amount > 0),
    released_at     timestamptz,
    release_reason  varchar(32),

    CONSTRAINT bid_reservations_release_reason_check CHECK (
        release_reason IS NULL OR release_reason IN (
            'OUTBID', 'AUCTION_CANCELLED', 'AUCTION_FRAUD_FREEZE',
            'AUCTION_BIN_ENDED', 'ESCROW_FUNDED', 'USER_BANNED'
        )
    ),
    CONSTRAINT bid_reservations_release_consistency_check CHECK (
        (released_at IS NULL AND release_reason IS NULL) OR
        (released_at IS NOT NULL AND release_reason IS NOT NULL)
    )
);

-- Partial unique index: at most one active reservation per (user, auction).
CREATE UNIQUE INDEX bid_reservations_active_idx
    ON bid_reservations (user_id, auction_id) WHERE released_at IS NULL;
CREATE INDEX bid_reservations_user_active_idx
    ON bid_reservations (user_id) WHERE released_at IS NULL;
CREATE INDEX bid_reservations_auction_active_idx
    ON bid_reservations (auction_id) WHERE released_at IS NULL;

-- ------------------------------------------------------------
-- user_ledger (immutable — extends BaseEntity, no version/updated_at)
-- ------------------------------------------------------------
CREATE TABLE user_ledger (
    id                  bigserial PRIMARY KEY,
    public_id           uuid NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    created_at          timestamptz NOT NULL DEFAULT now(),

    user_id             bigint NOT NULL REFERENCES users(id),
    entry_type          varchar(32) NOT NULL,
    amount              bigint NOT NULL CHECK (amount > 0),
    balance_after       bigint NOT NULL,
    reserved_after      bigint NOT NULL,
    ref_type            varchar(32),
    ref_id              bigint,
    sl_transaction_id   varchar(36),
    idempotency_key     varchar(64),
    description         varchar(500),
    created_by_admin_id bigint REFERENCES users(id),

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

-- ------------------------------------------------------------
-- escrows (mutable)
-- ------------------------------------------------------------
CREATE TABLE escrows (
    id                              bigserial PRIMARY KEY,
    public_id                       uuid NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    created_at                      timestamptz NOT NULL DEFAULT now(),
    updated_at                      timestamptz NOT NULL DEFAULT now(),
    version                         bigint NOT NULL DEFAULT 0,

    auction_id                      bigint NOT NULL UNIQUE REFERENCES auctions(id),
    state                           varchar(30) NOT NULL,
    final_bid_amount                bigint NOT NULL,
    commission_amt                  bigint NOT NULL,
    payout_amt                      bigint NOT NULL,
    payment_deadline                timestamptz NOT NULL,
    transfer_deadline               timestamptz,
    funded_at                       timestamptz,
    transfer_confirmed_at           timestamptz,
    completed_at                    timestamptz,
    disputed_at                     timestamptz,
    frozen_at                       timestamptz,
    expired_at                      timestamptz,
    last_checked_at                 timestamptz,
    reminder_sent_at                timestamptz,
    consecutive_world_api_failures  integer NOT NULL DEFAULT 0,
    dispute_reason_category         varchar(40),
    dispute_description             text,
    sl_transaction_key              varchar(64),
    winner_evidence_images          jsonb,
    seller_evidence_images          jsonb,
    seller_evidence_text            varchar(2000),
    seller_evidence_submitted_at    timestamptz,
    freeze_reason                   varchar(40),
    review_required                 boolean NOT NULL DEFAULT false,

    CONSTRAINT escrows_state_check CHECK (state IN (
        'ESCROW_PENDING', 'FUNDED', 'TRANSFER_PENDING', 'COMPLETED',
        'DISPUTED', 'EXPIRED', 'FROZEN'
    ))
);

CREATE INDEX ix_escrows_state             ON escrows (state);
CREATE INDEX ix_escrows_payment_deadline  ON escrows (payment_deadline);
CREATE INDEX ix_escrows_transfer_deadline ON escrows (transfer_deadline);

-- ------------------------------------------------------------
-- escrow_transactions (mutable)
-- ------------------------------------------------------------
CREATE TABLE escrow_transactions (
    id                 bigserial PRIMARY KEY,
    public_id          uuid NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    version            bigint NOT NULL DEFAULT 0,

    escrow_id          bigint REFERENCES escrows(id),
    auction_id         bigint REFERENCES auctions(id),
    type               varchar(40) NOT NULL,
    status             varchar(20) NOT NULL,
    amount             bigint NOT NULL,
    payer_id           bigint REFERENCES users(id),
    payee_id           bigint REFERENCES users(id),
    sl_transaction_id  varchar(100),
    terminal_id        varchar(100),
    error_message      varchar(500),
    completed_at       timestamptz,

    CONSTRAINT escrow_transactions_type_check CHECK (type IN (
        'AUCTION_ESCROW_PAYMENT', 'AUCTION_ESCROW_PAYOUT', 'AUCTION_ESCROW_REFUND',
        'AUCTION_ESCROW_COMMISSION', 'LISTING_FEE_PAYMENT', 'LISTING_FEE_REFUND',
        'LISTING_PENALTY_PAYMENT'
    )),
    CONSTRAINT escrow_transactions_status_check CHECK (status IN (
        'PENDING', 'COMPLETED', 'FAILED'
    ))
);

CREATE INDEX ix_escrow_tx_escrow  ON escrow_transactions (escrow_id);
CREATE INDEX ix_escrow_tx_auction ON escrow_transactions (auction_id);
CREATE INDEX ix_escrow_tx_sl_txn  ON escrow_transactions (sl_transaction_id);

-- ------------------------------------------------------------
-- terminal_commands (mutable)
-- ------------------------------------------------------------
CREATE TABLE terminal_commands (
    id                     bigserial PRIMARY KEY,
    public_id              uuid NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    created_at             timestamptz NOT NULL DEFAULT now(),
    updated_at             timestamptz NOT NULL DEFAULT now(),
    version                bigint NOT NULL DEFAULT 0,

    escrow_id              bigint,
    listing_fee_refund_id  bigint,
    action                 varchar(20) NOT NULL,
    purpose                varchar(30) NOT NULL,
    recipient_uuid         varchar(36) NOT NULL,
    amount                 bigint NOT NULL,
    status                 varchar(20) NOT NULL,
    terminal_id            varchar(100),
    attempt_count          integer NOT NULL DEFAULT 0,
    next_attempt_at        timestamptz NOT NULL,
    dispatched_at          timestamptz,
    last_error             varchar(500),
    shared_secret_version  varchar(20),
    idempotency_key        varchar(80) NOT NULL,
    requires_manual_review boolean NOT NULL DEFAULT false,
    completed_at           timestamptz,

    CONSTRAINT uk_tcmd_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT terminal_commands_action_check CHECK (action IN ('PAYOUT', 'REFUND', 'WITHDRAW')),
    CONSTRAINT terminal_commands_purpose_check CHECK (purpose IN (
        'AUCTION_ESCROW', 'LISTING_FEE_REFUND', 'ADMIN_WITHDRAWAL'
    )),
    CONSTRAINT terminal_commands_status_check CHECK (status IN (
        'QUEUED', 'IN_FLIGHT', 'COMPLETED', 'FAILED'
    ))
);

CREATE INDEX ix_tcmd_status_next_attempt ON terminal_commands (status, next_attempt_at);
CREATE INDEX ix_tcmd_escrow              ON terminal_commands (escrow_id);
CREATE INDEX ix_tcmd_listing_fee_refund  ON terminal_commands (listing_fee_refund_id);

-- ------------------------------------------------------------
-- listing_fee_refunds (mutable)
-- ------------------------------------------------------------
CREATE TABLE listing_fee_refunds (
    id                  bigserial PRIMARY KEY,
    public_id           uuid NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    version             bigint NOT NULL DEFAULT 0,

    auction_id          bigint NOT NULL REFERENCES auctions(id),
    amount              bigint NOT NULL,
    status              varchar(20) NOT NULL,
    txn_ref             varchar(255),
    processed_at        timestamptz,
    terminal_command_id bigint,
    last_queued_at      timestamptz,

    CONSTRAINT listing_fee_refunds_status_check CHECK (status IN (
        'PENDING', 'PROCESSED', 'FAILED'
    ))
);

-- ------------------------------------------------------------
-- fraud_flags (mutable)
-- ------------------------------------------------------------
CREATE TABLE fraud_flags (
    id                  bigserial PRIMARY KEY,
    public_id           uuid NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    version             bigint NOT NULL DEFAULT 0,

    auction_id          bigint REFERENCES auctions(id),
    sl_parcel_uuid      uuid,
    reason              varchar(40) NOT NULL,
    detected_at         timestamptz NOT NULL,
    evidence_json       jsonb,
    resolved            boolean NOT NULL DEFAULT false,
    resolved_at         timestamptz,
    resolved_by_user_id bigint REFERENCES users(id),
    admin_notes         text,

    CONSTRAINT fraud_flags_reason_check CHECK (reason IN (
        'OWNERSHIP_CHANGED_TO_UNKNOWN', 'PARCEL_DELETED_OR_MERGED',
        'WORLD_API_FAILURE_THRESHOLD', 'ESCROW_WRONG_PAYER', 'ESCROW_UNKNOWN_OWNER',
        'ESCROW_PARCEL_DELETED', 'ESCROW_WORLD_API_FAILURE', 'BOT_AUTH_BUYER_REVOKED',
        'BOT_PRICE_DRIFT', 'BOT_OWNERSHIP_CHANGED', 'BOT_ACCESS_REVOKED', 'CANCEL_AND_SELL'
    ))
);

-- ------------------------------------------------------------
-- saved_auctions (mutable)
-- ------------------------------------------------------------
CREATE TABLE saved_auctions (
    id          bigserial PRIMARY KEY,
    public_id   uuid NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    version     bigint NOT NULL DEFAULT 0,

    user_id     bigint NOT NULL REFERENCES users(id),
    auction_id  bigint NOT NULL REFERENCES auctions(id) ON DELETE CASCADE,
    saved_at    timestamptz NOT NULL,

    CONSTRAINT uk_saved_auctions_user_auction UNIQUE (user_id, auction_id)
);

CREATE INDEX ix_saved_auctions_user_saved_at ON saved_auctions (user_id, saved_at DESC);

-- ------------------------------------------------------------
-- bot_tasks (mutable)
-- BotTask.lastUpdatedAt -> inherited updatedAt (column: updated_at)
-- ------------------------------------------------------------
CREATE TABLE bot_tasks (
    id                              bigserial PRIMARY KEY,
    public_id                       uuid NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    created_at                      timestamptz NOT NULL DEFAULT now(),
    updated_at                      timestamptz NOT NULL DEFAULT now(),
    version                         bigint NOT NULL DEFAULT 0,

    task_type                       varchar(20) NOT NULL,
    status                          varchar(20) NOT NULL,
    auction_id                      bigint NOT NULL REFERENCES auctions(id),
    escrow_id                       bigint REFERENCES escrows(id),
    parcel_uuid                     uuid NOT NULL,
    region_name                     varchar(100),
    position_x                      double precision,
    position_y                      double precision,
    position_z                      double precision,
    sentinel_price                  bigint NOT NULL,
    expected_owner_uuid             uuid,
    expected_auth_buyer_uuid        uuid,
    expected_sale_price_lindens     bigint,
    expected_winner_uuid            uuid,
    expected_seller_uuid            uuid,
    expected_max_sale_price_lindens bigint,
    next_run_at                     timestamptz,
    recurrence_interval_seconds     integer,
    assigned_bot_uuid               uuid,
    result_data                     jsonb,
    last_check_at                   timestamptz,
    failure_reason                  varchar(500),
    completed_at                    timestamptz,

    CONSTRAINT bot_tasks_task_type_check CHECK (task_type IN (
        'VERIFY', 'MONITOR_AUCTION', 'MONITOR_ESCROW'
    )),
    CONSTRAINT bot_tasks_status_check CHECK (status IN (
        'PENDING', 'IN_PROGRESS', 'COMPLETED', 'FAILED', 'CANCELLED'
    ))
);

-- ------------------------------------------------------------
-- bot_workers (mutable)
-- BotWorker.firstSeenAt -> inherited createdAt (column: created_at)
-- ------------------------------------------------------------
CREATE TABLE bot_workers (
    id          bigserial PRIMARY KEY,
    public_id   uuid NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    version     bigint NOT NULL DEFAULT 0,

    name        varchar(80) NOT NULL UNIQUE,
    sl_uuid     varchar(36) NOT NULL UNIQUE,
    last_seen_at timestamptz NOT NULL
);

-- ------------------------------------------------------------
-- withdrawals (mutable)
-- Withdrawal.requestedAt -> inherited createdAt (column: created_at)
-- ------------------------------------------------------------
CREATE TABLE withdrawals (
    id                  bigserial PRIMARY KEY,
    public_id           uuid NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    version             bigint NOT NULL DEFAULT 0,

    amount              bigint NOT NULL,
    recipient_uuid      varchar(36) NOT NULL,
    admin_user_id       bigint NOT NULL REFERENCES users(id),
    notes               varchar(1000),
    terminal_command_id bigint,
    status              varchar(16) NOT NULL,
    completed_at        timestamptz,
    failure_reason      varchar(500),

    CONSTRAINT withdrawals_status_check CHECK (status IN (
        'PENDING', 'COMPLETED', 'FAILED'
    ))
);

-- ------------------------------------------------------------
-- bans (mutable)
-- ------------------------------------------------------------
CREATE TABLE bans (
    id               bigserial PRIMARY KEY,
    public_id        uuid NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    version          bigint NOT NULL DEFAULT 0,

    admin_user_id    bigint NOT NULL REFERENCES users(id),
    ban_type         varchar(10) NOT NULL,
    ip_address       varchar(45),
    sl_avatar_uuid   uuid,
    reason_category  varchar(20) NOT NULL,
    notes            text,
    expires_at       timestamptz,
    lifted_at        timestamptz,
    lifted_by_user_id bigint REFERENCES users(id),
    lifted_reason    text,

    CONSTRAINT bans_ban_type_check CHECK (ban_type IN ('IP', 'AVATAR', 'BOTH')),
    CONSTRAINT bans_reason_category_check CHECK (reason_category IN (
        'SHILL_BIDDING', 'FRAUDULENT_SELLER', 'TOS_ABUSE', 'SPAM', 'OTHER'
    ))
);

CREATE INDEX idx_bans_ip_address    ON bans (ip_address);
CREATE INDEX idx_bans_sl_avatar_uuid ON bans (sl_avatar_uuid);
CREATE INDEX idx_bans_lifted_expires ON bans (lifted_at, expires_at);

-- ------------------------------------------------------------
-- listing_reports (mutable)
-- ------------------------------------------------------------
CREATE TABLE listing_reports (
    id          bigserial PRIMARY KEY,
    public_id   uuid NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    version     bigint NOT NULL DEFAULT 0,

    auction_id  bigint NOT NULL REFERENCES auctions(id),
    reporter_id bigint NOT NULL REFERENCES users(id),
    subject     varchar(100) NOT NULL,
    reason      varchar(30) NOT NULL,
    details     text NOT NULL,
    status      varchar(20) NOT NULL DEFAULT 'OPEN',
    admin_notes text,
    reviewed_by bigint REFERENCES users(id),
    reviewed_at timestamptz,

    CONSTRAINT uk_listing_reports_auction_reporter UNIQUE (auction_id, reporter_id),
    CONSTRAINT listing_reports_reason_check CHECK (reason IN (
        'INACCURATE_DESCRIPTION', 'WRONG_TAGS', 'SHILL_BIDDING', 'FRAUDULENT_SELLER',
        'DUPLICATE_LISTING', 'NOT_ACTUALLY_FOR_SALE', 'TOS_VIOLATION', 'OTHER'
    )),
    CONSTRAINT listing_reports_status_check CHECK (status IN (
        'OPEN', 'REVIEWED', 'DISMISSED', 'ACTION_TAKEN'
    ))
);

CREATE INDEX idx_listing_reports_status  ON listing_reports (status, auction_id);
CREATE INDEX idx_listing_reports_auction ON listing_reports (auction_id, updated_at DESC);

-- ------------------------------------------------------------
-- reconciliation_runs (mutable)
-- ------------------------------------------------------------
CREATE TABLE reconciliation_runs (
    id                   bigserial PRIMARY KEY,
    public_id            uuid NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    version              bigint NOT NULL DEFAULT 0,

    ran_at               timestamptz NOT NULL,
    expected_locked_sum  bigint NOT NULL,
    observed_balance     bigint,
    drift                bigint,
    status               varchar(16) NOT NULL,
    error_message        varchar(500),
    wallet_balance_total bigint,
    wallet_reserved_total bigint,
    escrow_locked_total  bigint,

    CONSTRAINT reconciliation_runs_status_check CHECK (status IN (
        'BALANCED', 'MISMATCH', 'ERROR', 'DENORM_DRIFT'
    ))
);

-- ------------------------------------------------------------
-- verification_codes (mutable)
-- ------------------------------------------------------------
CREATE TABLE verification_codes (
    id          bigserial PRIMARY KEY,
    public_id   uuid NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    version     bigint NOT NULL DEFAULT 0,

    user_id     bigint NOT NULL REFERENCES users(id),
    auction_id  bigint,
    code        varchar(6) NOT NULL,
    type        varchar(20) NOT NULL,
    expires_at  timestamptz NOT NULL,
    used        boolean NOT NULL DEFAULT false,

    CONSTRAINT verification_codes_type_check CHECK (type IN ('PLAYER', 'PARCEL'))
);

-- ------------------------------------------------------------
-- notification (mutable)
-- ------------------------------------------------------------
CREATE TABLE notification (
    id            bigserial PRIMARY KEY,
    public_id     uuid NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    version       bigint NOT NULL DEFAULT 0,

    user_id       bigint NOT NULL REFERENCES users(id),
    category      varchar(64) NOT NULL,
    title         varchar(200) NOT NULL,
    body          varchar(500) NOT NULL,
    data          jsonb,
    coalesce_key  varchar(160),
    read          boolean NOT NULL DEFAULT false,

    CONSTRAINT notification_category_check CHECK (category IN (
        'OUTBID', 'PROXY_EXHAUSTED', 'AUCTION_WON', 'AUCTION_LOST',
        'AUCTION_ENDED_SOLD', 'AUCTION_ENDED_RESERVE_NOT_MET', 'AUCTION_ENDED_NO_BIDS',
        'AUCTION_ENDED_BOUGHT_NOW', 'ESCROW_FUNDED', 'ESCROW_TRANSFER_CONFIRMED',
        'ESCROW_PAYOUT', 'ESCROW_EXPIRED', 'ESCROW_DISPUTED', 'ESCROW_FROZEN',
        'ESCROW_PAYOUT_STALLED', 'ESCROW_TRANSFER_REMINDER', 'LISTING_VERIFIED',
        'LISTING_SUSPENDED', 'LISTING_REINSTATED', 'LISTING_REVIEW_REQUIRED',
        'LISTING_CANCELLED_BY_SELLER', 'LISTING_REMOVED_BY_ADMIN', 'LISTING_WARNED',
        'REVIEW_RECEIVED', 'REVIEW_RESPONSE_WINDOW_CLOSING', 'SYSTEM_ANNOUNCEMENT',
        'DISPUTE_FILED_AGAINST_SELLER', 'DISPUTE_RESOLVED', 'RECONCILIATION_MISMATCH',
        'WITHDRAWAL_COMPLETED', 'WITHDRAWAL_FAILED'
    ))
);

CREATE INDEX ix_notification_user_unread_created
    ON notification (user_id, read, created_at DESC);
CREATE INDEX ix_notification_user_updated
    ON notification (user_id, updated_at DESC);

-- Partial unique index: at most one unread notification per coalesce key per user.
-- Reproduced here as durable schema; NotificationCoalesceIndexInitializer also
-- ensures it at runtime (idempotent).
CREATE UNIQUE INDEX uq_notification_unread_coalesce
    ON notification (user_id, coalesce_key)
    WHERE read = false;

-- ------------------------------------------------------------
-- sl_im_message (mutable)
-- ------------------------------------------------------------
CREATE TABLE sl_im_message (
    id            bigserial PRIMARY KEY,
    public_id     uuid NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    version       bigint NOT NULL DEFAULT 0,

    user_id       bigint NOT NULL REFERENCES users(id),
    avatar_uuid   varchar(36) NOT NULL,
    coalesce_key  varchar(128),
    message_text  varchar(1024) NOT NULL,
    status        varchar(16) NOT NULL,
    delivered_at  timestamptz,
    attempts      integer NOT NULL DEFAULT 0,

    CONSTRAINT sl_im_message_status_check CHECK (status IN (
        'PENDING', 'DELIVERED', 'EXPIRED', 'FAILED'
    ))
);

CREATE INDEX ix_sl_im_status_created ON sl_im_message (status, created_at);
CREATE INDEX ix_sl_im_user_status    ON sl_im_message (user_id, status);

-- Partial unique index: at most one PENDING message per coalesce key per user.
-- Reproduced here as durable schema; SlImCoalesceIndexInitializer also ensures
-- it at runtime (idempotent).
CREATE UNIQUE INDEX uq_sl_im_pending_coalesce
    ON sl_im_message (user_id, coalesce_key)
    WHERE status = 'PENDING';

-- ------------------------------------------------------------
-- terminal_secrets (mutable)
-- Column rename: version -> secret_version (field rename in TerminalSecret entity)
-- ------------------------------------------------------------
CREATE TABLE terminal_secrets (
    id             bigserial PRIMARY KEY,
    public_id      uuid NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    version        bigint NOT NULL DEFAULT 0,

    secret_version integer NOT NULL UNIQUE,
    secret_value   varchar(64) NOT NULL,
    retired_at     timestamptz
);

-- ------------------------------------------------------------
-- terminals (excluded from BaseEntity — natural String PK)
-- NOTE: excluded entity, retains current shape (PK = terminal_id)
-- ------------------------------------------------------------
CREATE TABLE terminals (
    terminal_id          varchar(100) NOT NULL PRIMARY KEY,
    http_in_url          varchar(500) NOT NULL,
    active               boolean NOT NULL DEFAULT true,
    region_name          varchar(100),
    last_seen_at         timestamptz NOT NULL,
    registered_at        timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    last_heartbeat_at    timestamptz,
    last_reported_balance bigint
);

CREATE INDEX ix_terminals_active_last_seen ON terminals (active, last_seen_at);

-- ------------------------------------------------------------
-- reviews (mutable)
-- ------------------------------------------------------------
CREATE TABLE reviews (
    id                               bigserial PRIMARY KEY,
    public_id                        uuid NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    created_at                       timestamptz NOT NULL DEFAULT now(),
    updated_at                       timestamptz NOT NULL DEFAULT now(),
    version                          bigint NOT NULL DEFAULT 0,

    auction_id                       bigint NOT NULL REFERENCES auctions(id),
    reviewer_id                      bigint NOT NULL REFERENCES users(id),
    reviewee_id                      bigint NOT NULL REFERENCES users(id),
    reviewed_role                    varchar(10) NOT NULL,
    rating                           integer NOT NULL,
    text                             varchar(500),
    visible                          boolean NOT NULL DEFAULT false,
    submitted_at                     timestamptz NOT NULL DEFAULT now(),
    revealed_at                      timestamptz,
    flag_count                       integer NOT NULL DEFAULT 0,
    response_closing_reminder_sent_at timestamptz,

    CONSTRAINT uq_reviews_auction_reviewer UNIQUE (auction_id, reviewer_id),
    CONSTRAINT reviews_reviewed_role_check CHECK (reviewed_role IN ('SELLER', 'BUYER'))
);

CREATE INDEX idx_reviews_reviewee_visible ON reviews (reviewee_id, reviewed_role, visible);
CREATE INDEX idx_reviews_auction          ON reviews (auction_id);

-- ------------------------------------------------------------
-- review_responses (mutable)
-- ------------------------------------------------------------
CREATE TABLE review_responses (
    id          bigserial PRIMARY KEY,
    public_id   uuid NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    version     bigint NOT NULL DEFAULT 0,

    review_id   bigint NOT NULL UNIQUE REFERENCES reviews(id),
    text        varchar(500) NOT NULL
);

-- ------------------------------------------------------------
-- review_flags (immutable — extends BaseEntity, no version/updated_at)
-- ------------------------------------------------------------
CREATE TABLE review_flags (
    id          bigserial PRIMARY KEY,
    public_id   uuid NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    created_at  timestamptz NOT NULL DEFAULT now(),

    review_id   bigint NOT NULL REFERENCES reviews(id),
    flagger_id  bigint NOT NULL REFERENCES users(id),
    reason      varchar(20) NOT NULL,
    elaboration varchar(500),

    CONSTRAINT uq_review_flags_review_flagger UNIQUE (review_id, flagger_id),
    CONSTRAINT review_flags_reason_check CHECK (reason IN (
        'SPAM', 'ABUSIVE', 'OFF_TOPIC', 'FALSE_INFO', 'OTHER'
    ))
);

-- ------------------------------------------------------------
-- admin_actions (immutable — extends BaseEntity, no version/updated_at)
-- ------------------------------------------------------------
CREATE TABLE admin_actions (
    id            bigserial PRIMARY KEY,
    public_id     uuid NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    created_at    timestamptz NOT NULL DEFAULT now(),

    admin_user_id bigint NOT NULL REFERENCES users(id),
    action_type   varchar(40) NOT NULL,
    target_type   varchar(20) NOT NULL,
    target_id     bigint NOT NULL,
    notes         text,
    details       jsonb,

    CONSTRAINT admin_actions_action_type_check CHECK (action_type IN (
        'DISMISS_REPORT', 'WARN_SELLER_FROM_REPORT', 'SUSPEND_LISTING_FROM_REPORT',
        'CANCEL_LISTING_FROM_REPORT', 'CREATE_BAN', 'LIFT_BAN', 'PROMOTE_USER',
        'DEMOTE_USER', 'RESET_FRIVOLOUS_COUNTER', 'REINSTATE_LISTING',
        'DISPUTE_RESOLVED', 'LISTING_CANCELLED_VIA_DISPUTE', 'WITHDRAWAL_REQUESTED',
        'OWNERSHIP_RECHECK_INVOKED', 'TERMINAL_SECRET_ROTATED', 'USER_DELETED_BY_ADMIN'
    )),
    CONSTRAINT admin_actions_target_type_check CHECK (target_type IN (
        'USER', 'LISTING', 'REPORT', 'FRAUD_FLAG', 'BAN', 'DISPUTE',
        'WITHDRAWAL', 'TERMINAL_SECRET', 'AUCTION'
    ))
);

CREATE INDEX idx_admin_actions_target    ON admin_actions (target_type, target_id, created_at DESC);
CREATE INDEX idx_admin_actions_admin_user ON admin_actions (admin_user_id, created_at DESC);

-- ------------------------------------------------------------
-- cancellation_logs (immutable — extends BaseEntity, no version/updated_at)
-- ------------------------------------------------------------
CREATE TABLE cancellation_logs (
    id                    bigserial PRIMARY KEY,
    public_id             uuid NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    created_at            timestamptz NOT NULL DEFAULT now(),

    auction_id            bigint NOT NULL REFERENCES auctions(id),
    seller_id             bigint NOT NULL REFERENCES users(id),
    cancelled_from_status varchar(30) NOT NULL,
    had_bids              boolean NOT NULL DEFAULT false,
    reason                varchar(500),
    penalty_kind          varchar(30),
    penalty_amount_l      bigint,
    cancelled_by_admin_id bigint,
    cancelled_at          timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT cancellation_logs_penalty_kind_check CHECK (penalty_kind IN (
        'NONE', 'WARNING', 'PENALTY', 'PENALTY_AND_30D', 'PERMANENT_BAN'
    ))
);
