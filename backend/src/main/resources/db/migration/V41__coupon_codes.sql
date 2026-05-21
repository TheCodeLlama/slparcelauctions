-- V41: coupon codes feature foundation.
--
-- Adds four new tables:
--   coupons              - template (code, lifetime axes, redemption controls)
--   coupon_discounts     - 1:N child carrying (target, op, value) tuples
--   coupon_allowed_users - 1:N junction for per-coupon allowlists
--   coupon_grants        - per-user instance with state + remaining_count
--
-- Plus two nullable FK columns on auctions so each listing can record
-- which grant powered its listing-fee + commission discounts at creation
-- time (for consumption at activation).
--
-- Spec: docs/superpowers/specs/2026-05-20-coupon-codes-design.md
-- Plan: docs/superpowers/plans/2026-05-20-coupon-codes-plan.md

CREATE TABLE coupons (
    id                     BIGSERIAL PRIMARY KEY,
    public_id              UUID NOT NULL UNIQUE,
    code                   VARCHAR(64) NOT NULL UNIQUE,
    description            TEXT,
    duration_days          INTEGER,
    use_count              INTEGER,
    redeemable_until       TIMESTAMPTZ,
    max_total_redemptions  INTEGER,
    max_per_user           INTEGER NOT NULL DEFAULT 1,
    signup_window_start    DATE,
    signup_window_end      DATE,
    active                 BOOLEAN NOT NULL DEFAULT TRUE,
    notify_on_grant        BOOLEAN NOT NULL DEFAULT TRUE,
    created_by_user_id     BIGINT NOT NULL REFERENCES users(id),
    created_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version                BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT coupon_lifetime_required
        CHECK (duration_days IS NOT NULL OR use_count IS NOT NULL),
    CONSTRAINT coupon_signup_window_paired
        CHECK ((signup_window_start IS NULL) = (signup_window_end IS NULL))
);
CREATE INDEX coupons_code_lower_idx ON coupons(LOWER(code));
CREATE INDEX coupons_signup_window_idx ON coupons(signup_window_start, signup_window_end)
    WHERE signup_window_start IS NOT NULL;

CREATE TABLE coupon_discounts (
    id          BIGSERIAL PRIMARY KEY,
    coupon_id   BIGINT NOT NULL REFERENCES coupons(id) ON DELETE CASCADE,
    target      VARCHAR(32) NOT NULL,
    op          VARCHAR(32) NOT NULL,
    value       NUMERIC(12,4) NOT NULL,
    sort_order  INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT coupon_discount_target_valid
        CHECK (target IN ('LISTING_FEE','COMMISSION_RATE')),
    CONSTRAINT coupon_discount_op_valid
        CHECK (op IN ('OVERRIDE','PERCENT_OFF','FLAT_OFF'))
);
CREATE INDEX coupon_discounts_coupon_id_idx ON coupon_discounts(coupon_id);

CREATE TABLE coupon_allowed_users (
    coupon_id  BIGINT NOT NULL REFERENCES coupons(id) ON DELETE CASCADE,
    user_id    BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    PRIMARY KEY (coupon_id, user_id)
);

CREATE TABLE coupon_grants (
    id                BIGSERIAL PRIMARY KEY,
    public_id         UUID NOT NULL UNIQUE,
    coupon_id         BIGINT NOT NULL REFERENCES coupons(id),
    user_id           BIGINT NOT NULL REFERENCES users(id),
    granted_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at        TIMESTAMPTZ,
    remaining_count   INTEGER,
    state             VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    source            VARCHAR(32) NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version           BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT coupon_grant_state_valid
        CHECK (state IN ('ACTIVE','EXHAUSTED','EXPIRED','REVOKED')),
    CONSTRAINT coupon_grant_source_valid
        CHECK (source IN ('REDEMPTION','ADMIN_GRANT','SIGNUP_WINDOW'))
);
CREATE INDEX coupon_grants_user_state_idx ON coupon_grants(user_id, state);
CREATE INDEX coupon_grants_expires_at_idx ON coupon_grants(expires_at)
    WHERE state = 'ACTIVE' AND expires_at IS NOT NULL;
CREATE INDEX coupon_grants_coupon_user_idx ON coupon_grants(coupon_id, user_id);

ALTER TABLE auctions
    ADD COLUMN listing_fee_coupon_grant_id  BIGINT REFERENCES coupon_grants(id),
    ADD COLUMN commission_coupon_grant_id   BIGINT REFERENCES coupon_grants(id);
CREATE INDEX auctions_listing_fee_grant_idx
    ON auctions(listing_fee_coupon_grant_id)
    WHERE listing_fee_coupon_grant_id IS NOT NULL;
CREATE INDEX auctions_commission_grant_idx
    ON auctions(commission_coupon_grant_id)
    WHERE commission_coupon_grant_id IS NOT NULL;
