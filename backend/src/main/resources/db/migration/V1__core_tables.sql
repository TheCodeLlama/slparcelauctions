-- V1__core_tables.sql
-- Task 01-02: Foundational SLPA tables — users, parcels, auctions, parcel_tags, auction_tags.
-- Spec: docs/superpowers/specs/2026-04-10-flyway-core-migrations-design.md
-- See DESIGN.md §7 for the canonical schema definitions.

-- ============================================================================
-- users
-- ============================================================================
CREATE TABLE users (
    id                       BIGSERIAL PRIMARY KEY,
    email                    VARCHAR(255) UNIQUE NOT NULL,
    password_hash            VARCHAR(255) NOT NULL,
    sl_avatar_uuid           UUID UNIQUE,
    sl_avatar_name           VARCHAR(255),
    sl_display_name          VARCHAR(255),
    sl_username              VARCHAR(255),
    sl_born_date             DATE,
    sl_payinfo               INTEGER,
    display_name             VARCHAR(255),
    bio                      TEXT,
    profile_pic_url          TEXT,
    verified                 BOOLEAN NOT NULL DEFAULT FALSE,
    verified_at              TIMESTAMPTZ,
    avg_seller_rating        DECIMAL(3,2),
    avg_buyer_rating         DECIMAL(3,2),
    total_seller_reviews     INTEGER NOT NULL DEFAULT 0,
    total_buyer_reviews      INTEGER NOT NULL DEFAULT 0,
    completed_sales          INTEGER NOT NULL DEFAULT 0,
    cancelled_with_bids      INTEGER NOT NULL DEFAULT 0,
    listing_suspension_until TIMESTAMPTZ,
    email_verified           BOOLEAN NOT NULL DEFAULT FALSE,
    notify_email             JSONB DEFAULT '{"bidding":true,"auction_result":true,"escrow":true,"listing_status":true,"reviews":true,"realty_group":true,"marketing":false}',
    notify_sl_im             JSONB DEFAULT '{"bidding":true,"auction_result":true,"escrow":true,"listing_status":true,"reviews":false,"realty_group":false,"marketing":false}',
    notify_email_muted       BOOLEAN NOT NULL DEFAULT FALSE,
    notify_sl_im_muted       BOOLEAN NOT NULL DEFAULT FALSE,
    sl_im_quiet_start        TIME,
    sl_im_quiet_end          TIME,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ============================================================================
-- parcels
-- ============================================================================
CREATE TABLE parcels (
    id              BIGSERIAL PRIMARY KEY,
    parcel_uuid     UUID UNIQUE NOT NULL,
    owner_id        BIGINT REFERENCES users(id),
    sl_owner_uuid   UUID,
    owner_type      VARCHAR(10) NOT NULL DEFAULT 'AGENT', -- enum: AGENT | GROUP
    sl_group_uuid   UUID,
    parcel_name     VARCHAR(255),
    region_name     VARCHAR(255),
    grid_x          INTEGER,
    grid_y          INTEGER,
    area_sqm        INTEGER,
    prim_capacity   INTEGER,
    maturity        VARCHAR(20),                          -- enum: GENERAL | MODERATE | ADULT
    estate_type     VARCHAR(50),                          -- from Grid Survey API; e.g. Mainland, Private Estate, Homestead
    description     TEXT,
    snapshot_url    TEXT,
    layout_map_url  TEXT,
    layout_map_data JSONB,
    layout_map_at   TIMESTAMPTZ,
    location        VARCHAR(100),
    slurl           TEXT,
    verified        BOOLEAN NOT NULL DEFAULT FALSE,
    verified_at     TIMESTAMPTZ,
    last_checked    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_parcels_grid ON parcels(grid_x, grid_y);

-- ============================================================================
-- auctions
-- ============================================================================
CREATE TABLE auctions (
    id                  BIGSERIAL PRIMARY KEY,
    parcel_id           BIGINT NOT NULL REFERENCES parcels(id),
    seller_id           BIGINT NOT NULL REFERENCES users(id),
    listing_agent_id    BIGINT REFERENCES users(id),
    realty_group_id     BIGINT,  -- FK to realty_groups(id) added in Task 01-03 / V2 (table doesn't exist yet)
    status              VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
    -- Status values (validated at the application layer, not via CHECK):
    --   DRAFT, DRAFT_PAID, VERIFICATION_PENDING, VERIFICATION_FAILED,
    --   ACTIVE, ENDED, ESCROW_PENDING, ESCROW_FUNDED,
    --   TRANSFER_PENDING, COMPLETED, CANCELLED, EXPIRED, DISPUTED
    verification_tier   VARCHAR(10),  -- enum: SCRIPT | BOT | HUMAN
    verification_method VARCHAR(20),  -- enum: UUID_ENTRY | REZZABLE | SALE_TO_BOT
    assigned_bot_uuid   UUID,
    sale_sentinel_price BIGINT,
    last_bot_check_at   TIMESTAMPTZ,
    bot_check_failures  INTEGER NOT NULL DEFAULT 0,
    listing_fee_paid    BOOLEAN NOT NULL DEFAULT FALSE,
    listing_fee_amt     BIGINT,
    listing_fee_txn     VARCHAR(255),
    listing_fee_paid_at TIMESTAMPTZ,
    verified_at         TIMESTAMPTZ,
    verification_notes  TEXT,
    starting_bid        BIGINT NOT NULL,
    reserve_price       BIGINT,
    buy_now_price       BIGINT,
    current_bid         BIGINT NOT NULL DEFAULT 0,
    bid_count           INTEGER NOT NULL DEFAULT 0,
    winner_id           BIGINT REFERENCES users(id),
    duration_hours      INTEGER NOT NULL,
    snipe_protect       BOOLEAN NOT NULL DEFAULT FALSE,
    snipe_window_min    INTEGER,
    starts_at           TIMESTAMPTZ,
    ends_at             TIMESTAMPTZ,
    original_ends_at    TIMESTAMPTZ,
    seller_desc         TEXT,
    commission_rate     DECIMAL(5,4) DEFAULT 0.0500,
    commission_amt      BIGINT,
    agent_fee_rate      DECIMAL(5,4) DEFAULT 0.0000,
    agent_fee_amt       BIGINT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_auctions_status  ON auctions(status);
CREATE INDEX idx_auctions_seller  ON auctions(seller_id);
CREATE INDEX idx_auctions_ends_at ON auctions(ends_at);

-- ============================================================================
-- parcel_tags
-- Replaces DESIGN.md's parcel_tag ENUM with a real table so admins can
-- add/rename/deactivate tags via the UI without writing migrations.
-- ============================================================================
CREATE TABLE parcel_tags (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(50)  NOT NULL,
    label       VARCHAR(100) NOT NULL,
    category    VARCHAR(50)  NOT NULL,
    description TEXT,
    sort_order  INTEGER      NOT NULL DEFAULT 0,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT parcel_tags_code_unique UNIQUE (code),
    CONSTRAINT parcel_tags_code_format CHECK (code ~ '^[A-Z][A-Z0-9_]*$')
);

-- ============================================================================
-- auction_tags (join table: auction ↔ parcel_tag)
-- ============================================================================
CREATE TABLE auction_tags (
    auction_id BIGINT NOT NULL REFERENCES auctions(id) ON DELETE CASCADE,
    -- tag_id: RESTRICT because tags are soft-deleted via parcel_tags.active;
    -- hard DELETE on a tag with attached auctions should fail loudly.
    tag_id     BIGINT NOT NULL REFERENCES parcel_tags(id) ON DELETE RESTRICT,
    PRIMARY KEY (auction_id, tag_id)
);
CREATE INDEX idx_auction_tags_tag ON auction_tags(tag_id);

-- ============================================================================
-- Seed data: 25 parcel tags from DESIGN.md §7
-- ============================================================================
INSERT INTO parcel_tags (code, label, category, description, sort_order) VALUES
  ('WATERFRONT',       'Waterfront',       'Terrain / Environment', 'Ocean, lake, or river border',                        1),
  ('SAILABLE',         'Sailable',         'Terrain / Environment', 'Connected to navigable water (Linden waterways)',     2),
  ('GRASS',            'Grass',            'Terrain / Environment', 'Grass terrain',                                        3),
  ('SNOW',             'Snow',             'Terrain / Environment', 'Snow terrain',                                         4),
  ('SAND',             'Sand',             'Terrain / Environment', 'Sand or beach terrain',                                5),
  ('MOUNTAIN',         'Mountain',         'Terrain / Environment', 'Elevated or hilly terrain',                            6),
  ('FOREST',           'Forest',           'Terrain / Environment', 'Wooded area',                                          7),
  ('FLAT',             'Flat',             'Terrain / Environment', 'Level terrain, good for building',                     8),
  ('STREETFRONT',      'Streetfront',      'Roads / Access',        'Borders a Linden road',                                9),
  ('ROADSIDE',         'Roadside',         'Roads / Access',        'Near (but not directly on) a Linden road',            10),
  ('RAILWAY',          'Railway',          'Roads / Access',        'Near Linden railroad / SLRR',                         11),
  ('CORNER_LOT',       'Corner Lot',       'Location Features',     'Parcel on a corner (two road or water sides)',        12),
  ('HILLTOP',          'Hilltop',          'Location Features',     'Elevated with views',                                 13),
  ('ISLAND',           'Island',           'Location Features',     'Surrounded by water',                                 14),
  ('PENINSULA',        'Peninsula',        'Location Features',     'Water on three sides',                                15),
  ('SHELTERED',        'Sheltered',        'Location Features',     'Enclosed or private feeling, surrounded by terrain',  16),
  ('RESIDENTIAL',      'Residential',      'Neighbors / Context',   'Residential neighborhood',                            17),
  ('COMMERCIAL',       'Commercial',       'Neighbors / Context',   'Commercial or shopping area',                         18),
  ('INFOHUB_ADJACENT', 'Infohub Adjacent', 'Neighbors / Context',   'Near a Linden infohub',                               19),
  ('PROTECTED_LAND',   'Protected Land',   'Neighbors / Context',   'Adjacent to Linden-owned protected land',             20),
  ('SCENIC',           'Scenic',           'Neighbors / Context',   'Notable views or landscape',                          21),
  ('DOUBLE_PRIM',      'Double Prim',      'Parcel Features',       'On a double-prim region (Magnum, etc.)',              22),
  ('HIGH_PRIM',        'High Prim',        'Parcel Features',       'Above-average prim capacity for size',                23),
  ('LARGE_PARCEL',     'Large Parcel',     'Parcel Features',       '4096+ sqm',                                           24),
  ('FULL_REGION',      'Full Region',      'Parcel Features',       'Entire region (65536 sqm)',                           25);
