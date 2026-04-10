-- V2__supporting_tables.sql
-- Task 01-03: Realty groups, bidding, escrow, bot infra, reviews,
-- moderation, and verification tables — plus the deferred FK on
-- auctions.realty_group_id from V1.
-- Spec: docs/superpowers/specs/2026-04-10-flyway-supporting-migrations-design.md

-- ============================================================================
-- realty_groups
-- ============================================================================
CREATE TABLE realty_groups (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(255) UNIQUE NOT NULL,
    slug            VARCHAR(255) UNIQUE NOT NULL,
    leader_id       BIGINT NOT NULL REFERENCES users(id),
    logo_url        TEXT,
    description     TEXT,
    website         TEXT,
    sl_group_link   TEXT,
    agent_fee_rate  DECIMAL(5,4) NOT NULL DEFAULT 0.0000,
    agent_fee_split DECIMAL(5,4) NOT NULL DEFAULT 0.5000,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ============================================================================
-- realty_group_members
-- ============================================================================
CREATE TABLE realty_group_members (
    id        BIGSERIAL PRIMARY KEY,
    group_id  BIGINT NOT NULL REFERENCES realty_groups(id),
    user_id   BIGINT NOT NULL REFERENCES users(id),
    role      VARCHAR(20) NOT NULL DEFAULT 'AGENT',  -- enum: LEADER | AGENT
    joined_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(user_id)
);
CREATE INDEX idx_rgm_group ON realty_group_members(group_id);

-- ============================================================================
-- realty_group_invitations
-- ============================================================================
CREATE TABLE realty_group_invitations (
    id              BIGSERIAL PRIMARY KEY,
    group_id        BIGINT NOT NULL REFERENCES realty_groups(id),
    invited_user_id BIGINT NOT NULL REFERENCES users(id),
    invited_by_id   BIGINT NOT NULL REFERENCES users(id),
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',  -- enum: PENDING | ACCEPTED | DECLINED | REVOKED
    expires_at      TIMESTAMPTZ NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    responded_at    TIMESTAMPTZ
);
CREATE INDEX idx_invitations_user_status ON realty_group_invitations(invited_user_id, status);

-- ============================================================================
-- realty_group_sl_groups
-- ============================================================================
CREATE TABLE realty_group_sl_groups (
    id              BIGSERIAL PRIMARY KEY,
    realty_group_id BIGINT NOT NULL REFERENCES realty_groups(id),
    sl_group_uuid   UUID NOT NULL,
    sl_group_name   VARCHAR(255),
    verified        BOOLEAN NOT NULL DEFAULT FALSE,
    verified_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(sl_group_uuid)
);

-- ============================================================================
-- bids
-- ============================================================================
CREATE TABLE bids (
    id         BIGSERIAL PRIMARY KEY,
    auction_id BIGINT NOT NULL REFERENCES auctions(id),
    bidder_id  BIGINT NOT NULL REFERENCES users(id),
    amount     BIGINT NOT NULL,                               -- L$
    placed_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    ip_address INET
);
CREATE INDEX idx_bids_auction ON bids(auction_id, amount DESC);
CREATE INDEX idx_bids_bidder  ON bids(bidder_id);

-- ============================================================================
-- proxy_bids
-- ============================================================================
CREATE TABLE proxy_bids (
    id         BIGSERIAL PRIMARY KEY,
    auction_id BIGINT NOT NULL REFERENCES auctions(id),
    bidder_id  BIGINT NOT NULL REFERENCES users(id),
    max_amount BIGINT NOT NULL,                              -- L$
    active     BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(auction_id, bidder_id)
);

-- ============================================================================
-- escrow_transactions
-- payer_id/payee_id are nullable: txn type tells which side is filled.
-- COMMISSION rows should point payee_id at the SYSTEM user (created by the
-- task that ships the escrow code path; not seeded in this migration).
-- ============================================================================
CREATE TABLE escrow_transactions (
    id                BIGSERIAL PRIMARY KEY,
    auction_id        BIGINT NOT NULL REFERENCES auctions(id),
    payer_id          BIGINT REFERENCES users(id),
    payee_id          BIGINT REFERENCES users(id),
    type              VARCHAR(20) NOT NULL,                         -- enum: PAYMENT | PAYOUT | REFUND | COMMISSION
    amount            BIGINT NOT NULL,                              -- L$
    sl_transaction_id VARCHAR(100),
    terminal_id       VARCHAR(100),
    status            VARCHAR(20) NOT NULL DEFAULT 'PENDING',       -- enum: PENDING | COMPLETED | FAILED | REFUNDED
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at      TIMESTAMPTZ
);
CREATE INDEX idx_escrow_auction ON escrow_transactions(auction_id);
CREATE INDEX idx_escrow_status  ON escrow_transactions(status);

-- ============================================================================
-- bot_accounts
-- ============================================================================
CREATE TABLE bot_accounts (
    id             BIGSERIAL PRIMARY KEY,
    sl_uuid        UUID UNIQUE NOT NULL,
    sl_username    VARCHAR(255) NOT NULL,
    role           VARCHAR(10) NOT NULL DEFAULT 'WORKER',  -- enum: PRIMARY | WORKER
    status         VARCHAR(20) NOT NULL DEFAULT 'ONLINE',  -- enum: ONLINE | OFFLINE | MAINTENANCE
    current_region VARCHAR(255),
    last_heartbeat TIMESTAMPTZ,
    active_tasks   INTEGER NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
-- Only one PRIMARY bot account allowed
CREATE UNIQUE INDEX idx_bot_primary ON bot_accounts(role) WHERE role = 'PRIMARY';

-- ============================================================================
-- bot_tasks
-- bot_id is nullable: PENDING tasks aren't yet assigned to a bot.
-- ============================================================================
CREATE TABLE bot_tasks (
    id            BIGSERIAL PRIMARY KEY,
    bot_id        BIGINT REFERENCES bot_accounts(id),
    auction_id    BIGINT NOT NULL REFERENCES auctions(id),
    task_type     VARCHAR(30) NOT NULL,                     -- enum: VERIFY | MONITOR_AUCTION | MONITOR_ESCROW
    region_name   VARCHAR(255) NOT NULL,
    parcel_uuid   UUID NOT NULL,
    status        VARCHAR(20) NOT NULL DEFAULT 'PENDING',   -- enum: PENDING | IN_PROGRESS | COMPLETED | FAILED | CANCELLED
    result_data   JSONB,
    scheduled_at  TIMESTAMPTZ,
    started_at    TIMESTAMPTZ,
    completed_at  TIMESTAMPTZ,
    error_message TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_bot_tasks_bot              ON bot_tasks(bot_id, status);
CREATE INDEX idx_bot_tasks_auction          ON bot_tasks(auction_id);
CREATE INDEX idx_bot_tasks_status_scheduled ON bot_tasks(status, scheduled_at);

-- ============================================================================
-- reviews
-- ============================================================================
CREATE TABLE reviews (
    id            BIGSERIAL PRIMARY KEY,
    auction_id    BIGINT NOT NULL REFERENCES auctions(id),
    reviewer_id   BIGINT NOT NULL REFERENCES users(id),
    reviewee_id   BIGINT NOT NULL REFERENCES users(id),
    reviewer_role VARCHAR(10) NOT NULL,   -- enum: BUYER | SELLER
    rating        SMALLINT NOT NULL CHECK (rating BETWEEN 1 AND 5),
    review_text   VARCHAR(500),
    response_text VARCHAR(500),
    response_at   TIMESTAMPTZ,
    visible       BOOLEAN NOT NULL DEFAULT FALSE,
    flagged       BOOLEAN NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(auction_id, reviewer_id)
);
CREATE INDEX idx_reviews_reviewee ON reviews(reviewee_id, visible);
CREATE INDEX idx_reviews_auction  ON reviews(auction_id);

-- ============================================================================
-- cancellation_log
-- ============================================================================
CREATE TABLE cancellation_log (
    id              BIGSERIAL PRIMARY KEY,
    auction_id      BIGINT NOT NULL REFERENCES auctions(id),
    seller_id       BIGINT NOT NULL REFERENCES users(id),
    had_bids        BOOLEAN NOT NULL,
    bid_count       INTEGER NOT NULL DEFAULT 0,
    reason          TEXT,
    penalty_applied VARCHAR(30),  -- enum: WARNING | FEE | SUSPENSION | BAN
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_cancellation_seller ON cancellation_log(seller_id);

-- ============================================================================
-- listing_reports
-- VARCHAR over Postgres ENUM (DESIGN.md's report_reason / report_status types
-- are intentionally not created); see spec deviation #1.
-- ============================================================================
CREATE TABLE listing_reports (
    id          BIGSERIAL PRIMARY KEY,
    auction_id  BIGINT NOT NULL REFERENCES auctions(id),
    reporter_id BIGINT NOT NULL REFERENCES users(id),
    subject     VARCHAR(100) NOT NULL,                          -- user-supplied short title (max 100 chars)
    -- reason: enum: INACCURATE_DESCRIPTION | WRONG_TAGS | SHILL_BIDDING |
    --   FRAUDULENT_SELLER | DUPLICATE_LISTING | NOT_ACTUALLY_FOR_SALE |
    --   TOS_VIOLATION | OTHER
    reason      VARCHAR(30) NOT NULL,
    details     TEXT NOT NULL,
    -- status: enum: OPEN | REVIEWED | DISMISSED | ACTION_TAKEN
    status      VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    admin_notes TEXT,
    reviewed_by BIGINT REFERENCES users(id),
    reviewed_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(auction_id, reporter_id)
);
CREATE INDEX idx_reports_auction ON listing_reports(auction_id);
CREATE INDEX idx_reports_status  ON listing_reports(status);

-- ============================================================================
-- bans
-- banned_by NOT NULL: every ban must be attributed to an admin (or SYSTEM
-- user for any future automated bans). bans_type_matches_identifiers CHECK
-- enforces that ban_type matches which identifier columns are set, so the
-- two cannot drift out of sync via app-side bugs.
-- ============================================================================
CREATE TABLE bans (
    id             BIGSERIAL PRIMARY KEY,
    ban_type       VARCHAR(10) NOT NULL,        -- enum: IP | AVATAR | BOTH
    ip_address     INET,
    sl_avatar_uuid UUID,
    reason         TEXT NOT NULL,
    banned_by      BIGINT NOT NULL REFERENCES users(id),
    expires_at     TIMESTAMPTZ,                 -- NULL = permanent
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT bans_type_matches_identifiers CHECK (
      (ban_type = 'IP'     AND ip_address IS NOT NULL AND sl_avatar_uuid IS NULL) OR
      (ban_type = 'AVATAR' AND ip_address IS NULL     AND sl_avatar_uuid IS NOT NULL) OR
      (ban_type = 'BOTH'   AND ip_address IS NOT NULL AND sl_avatar_uuid IS NOT NULL)
    )
);
CREATE INDEX idx_bans_ip     ON bans(ip_address)     WHERE ip_address     IS NOT NULL;
CREATE INDEX idx_bans_avatar ON bans(sl_avatar_uuid) WHERE sl_avatar_uuid IS NOT NULL;

-- ============================================================================
-- fraud_flags
-- fraud_flags_at_least_one_target CHECK: matches the bans pattern — a flag
-- that targets nothing is a bug, and the database should reject it.
-- ============================================================================
CREATE TABLE fraud_flags (
    id          BIGSERIAL PRIMARY KEY,
    flag_type   VARCHAR(30) NOT NULL,                  -- examples: SAME_IP_MULTI_ACCOUNT, NEW_ACCOUNT_LAST_SECOND; full set defined by app fraud detection rules
    auction_id  BIGINT REFERENCES auctions(id),
    user_id     BIGINT REFERENCES users(id),
    details     JSONB,
    status      VARCHAR(20) NOT NULL DEFAULT 'OPEN',   -- enum: OPEN | REVIEWED | DISMISSED | ACTION_TAKEN
    reviewed_by BIGINT REFERENCES users(id),
    reviewed_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fraud_flags_at_least_one_target
      CHECK (user_id IS NOT NULL OR auction_id IS NOT NULL)
);
CREATE INDEX idx_fraud_flags_status ON fraud_flags(status);

-- ============================================================================
-- verification_codes
-- 6-digit numeric codes only; verification_codes_format CHECK enforces this
-- at the database level (same cheap-insurance pattern as parcel_tags.code).
-- ============================================================================
CREATE TABLE verification_codes (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT NOT NULL REFERENCES users(id),
    code       VARCHAR(6) NOT NULL,
    type       VARCHAR(20) NOT NULL,                            -- enum: PLAYER | PARCEL
    expires_at TIMESTAMPTZ NOT NULL,
    used       BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT verification_codes_format CHECK (code ~ '^[0-9]{6}$')
);
CREATE INDEX idx_verification_codes_user    ON verification_codes(user_id, used);
CREATE INDEX idx_verification_codes_expires ON verification_codes(expires_at);

-- ============================================================================
-- Deferred FK from V1: wire up auctions.realty_group_id → realty_groups(id)
-- now that the realty_groups table exists.
-- ============================================================================
ALTER TABLE auctions
  ADD CONSTRAINT auctions_realty_group_id_fkey
  FOREIGN KEY (realty_group_id) REFERENCES realty_groups(id);
