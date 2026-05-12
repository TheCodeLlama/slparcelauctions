-- V28: realty groups sub-project F -- admin moderation toolkit.

-- §4.1 -- group moderation entity (suspend + ban).
CREATE TABLE realty_group_suspensions (
    id                   BIGSERIAL PRIMARY KEY,
    public_id            UUID NOT NULL UNIQUE,
    realty_group_id      BIGINT NOT NULL REFERENCES realty_groups(id),
    issued_by_admin_id   BIGINT NOT NULL REFERENCES users(id),
    reason               VARCHAR(64) NOT NULL,
    notes                TEXT,
    issued_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at           TIMESTAMPTZ,
    lifted_at            TIMESTAMPTZ,
    lifted_by_admin_id   BIGINT REFERENCES users(id),
    lifted_notes         TEXT,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version              BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_rg_susp_lifted_consistency
      CHECK ((lifted_at IS NULL AND lifted_by_admin_id IS NULL)
          OR (lifted_at IS NOT NULL AND lifted_by_admin_id IS NOT NULL))
);

CREATE INDEX ix_rg_susp_active
  ON realty_group_suspensions(realty_group_id)
  WHERE lifted_at IS NULL;

CREATE INDEX ix_rg_susp_expiry_sweep
  ON realty_group_suspensions(expires_at)
  WHERE lifted_at IS NULL AND expires_at IS NOT NULL;

-- §4.2 -- user-submitted reports against groups.
CREATE TABLE realty_group_reports (
    id                   BIGSERIAL PRIMARY KEY,
    public_id            UUID NOT NULL UNIQUE,
    realty_group_id      BIGINT NOT NULL REFERENCES realty_groups(id),
    reporter_user_id     BIGINT NOT NULL REFERENCES users(id),
    reason               VARCHAR(64) NOT NULL,
    details              TEXT NOT NULL,
    status               VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    resolved_by_admin_id BIGINT REFERENCES users(id),
    resolved_at          TIMESTAMPTZ,
    resolution_notes     TEXT,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version              BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_rg_reports_status CHECK (
      status IN ('OPEN', 'RESOLVED', 'DISMISSED')
    )
);

CREATE UNIQUE INDEX uq_rg_reports_one_open_per_reporter
  ON realty_group_reports(realty_group_id, reporter_user_id)
  WHERE status = 'OPEN';

CREATE INDEX ix_rg_reports_open_queue
  ON realty_group_reports(realty_group_id)
  WHERE status = 'OPEN';

-- §4.3 -- listing suspension audit (distinguishes auto / admin-individual / admin-group-bulk).
CREATE TABLE listing_suspensions (
    id                      BIGSERIAL PRIMARY KEY,
    public_id               UUID NOT NULL UNIQUE,
    auction_id              BIGINT NOT NULL REFERENCES auctions(id),
    cause                   VARCHAR(32) NOT NULL,
    suspended_by_admin_id   BIGINT REFERENCES users(id),
    group_suspension_id     BIGINT REFERENCES realty_group_suspensions(id),
    bulk_action_id          UUID,
    reason                  VARCHAR(64),
    notes                   TEXT,
    suspended_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    lifted_at               TIMESTAMPTZ,
    cancelled_at            TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version                 BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_listing_susp_terminal_state
      CHECK (NOT (lifted_at IS NOT NULL AND cancelled_at IS NOT NULL)),
    CONSTRAINT ck_listing_susp_cause CHECK (
      cause IN ('AUTO_OWNERSHIP_CHANGE','AUTO_PARCEL_DELETED',
                'ADMIN_INDIVIDUAL','ADMIN_GROUP_BULK')
    )
);

CREATE INDEX ix_listing_susp_active_bulk
  ON listing_suspensions(auction_id, suspended_at)
  WHERE cause = 'ADMIN_GROUP_BULK'
    AND lifted_at IS NULL
    AND cancelled_at IS NULL;

CREATE INDEX ix_listing_susp_auction
  ON listing_suspensions(auction_id);

-- §4.3 -- backfill listing_suspensions rows from existing SUSPENDED auctions.
-- Cause is inferred from the most recent fraud_flag for the auction; falls back
-- to ADMIN_INDIVIDUAL if no fraud flag is present.
INSERT INTO listing_suspensions (
    public_id, auction_id, cause, suspended_at, suspended_by_admin_id, reason
)
SELECT
    gen_random_uuid(),
    a.id,
    CASE
        WHEN ff.reason = 'OWNERSHIP_CHANGED_TO_UNKNOWN' THEN 'AUTO_OWNERSHIP_CHANGE'
        WHEN ff.reason = 'PARCEL_DELETED_OR_MERGED' THEN 'AUTO_PARCEL_DELETED'
        ELSE 'ADMIN_INDIVIDUAL'
    END AS cause,
    COALESCE(a.suspended_at, NOW()),
    NULL,
    ff.reason
  FROM auctions a
  LEFT JOIN LATERAL (
    SELECT reason FROM fraud_flags ff2
     WHERE ff2.auction_id = a.id
     ORDER BY detected_at DESC
     LIMIT 1
  ) ff ON TRUE
 WHERE a.status = 'SUSPENDED';

-- §4.4 -- realty_group_sl_groups: drop unused About-text columns,
-- add drift + unregister tracking, tighten verified_via CHECK.
ALTER TABLE realty_group_sl_groups
  DROP COLUMN last_polled_at,
  DROP COLUMN poll_attempts;

UPDATE realty_group_sl_groups
   SET verified_via = 'FOUNDER_TERMINAL'
 WHERE verified_via = 'ABOUT_TEXT';

ALTER TABLE realty_group_sl_groups
  DROP CONSTRAINT ck_rg_sl_groups_verified_via;
ALTER TABLE realty_group_sl_groups
  ADD CONSTRAINT ck_rg_sl_groups_verified_via
    CHECK (verified_via IS NULL OR verified_via = 'FOUNDER_TERMINAL');

ALTER TABLE realty_group_sl_groups
  ADD COLUMN last_revalidated_at              TIMESTAMPTZ,
  ADD COLUMN current_founder_uuid             UUID,
  ADD COLUMN consecutive_fetch_failures       INTEGER NOT NULL DEFAULT 0,
  ADD COLUMN drift_detected_at                TIMESTAMPTZ,
  ADD COLUMN drift_reason                     VARCHAR(64),
  ADD COLUMN drift_acknowledged_at            TIMESTAMPTZ,
  ADD COLUMN drift_acknowledged_by_admin_id   BIGINT REFERENCES users(id),
  ADD COLUMN unregistered_at                  TIMESTAMPTZ,
  ADD COLUMN unregistered_by_admin_id         BIGINT REFERENCES users(id),
  ADD COLUMN unregister_reason                VARCHAR(64);

CREATE INDEX ix_rg_sl_groups_drift_open
  ON realty_group_sl_groups(realty_group_id)
  WHERE drift_detected_at IS NOT NULL
    AND drift_acknowledged_at IS NULL;

CREATE INDEX ix_rg_sl_groups_reverify_due
  ON realty_group_sl_groups(last_revalidated_at)
  WHERE verified = true;

DROP INDEX IF EXISTS ix_rg_sl_groups_pending_poll;

-- §4.5 -- fraud_flags supports REALTY_GROUP entity.
-- NOTE: spec §4.5 assumed fraud_flags already had an entity_type column (USER/LISTING),
-- but the current schema has no such column — fraud_flags is auction-keyed only.
-- Adding the column here (default LISTING for existing rows) so the CHECK widening is
-- meaningful and Task 5's FraudFlagEntityKind enum has somewhere to land.
ALTER TABLE fraud_flags
  ADD COLUMN IF NOT EXISTS entity_type VARCHAR(20) NOT NULL DEFAULT 'LISTING';

ALTER TABLE fraud_flags DROP CONSTRAINT IF EXISTS fraud_flags_entity_type_check;
ALTER TABLE fraud_flags ADD CONSTRAINT fraud_flags_entity_type_check CHECK (
    entity_type IN ('USER', 'LISTING', 'REALTY_GROUP')
);

-- §20.1 step 8 -- admit ADMIN_BULK_EXPIRED in cancellation_logs penalty_kind.
ALTER TABLE cancellation_logs DROP CONSTRAINT IF EXISTS cancellation_logs_penalty_kind_check;
ALTER TABLE cancellation_logs ADD CONSTRAINT cancellation_logs_penalty_kind_check CHECK (
    penalty_kind IS NULL OR penalty_kind IN (
        'NONE','WARNING','PENALTY','PENALTY_AND_30D','PERMANENT_BAN',
        'BROKER_CANCEL','ADMIN_BULK_EXPIRED'
    )
);
