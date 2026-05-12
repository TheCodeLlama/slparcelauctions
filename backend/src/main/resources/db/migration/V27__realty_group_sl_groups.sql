-- V27: realty groups sub-project E -- SL-group-owned listings.

CREATE TABLE realty_group_sl_groups (
    id                              BIGSERIAL PRIMARY KEY,
    public_id                       UUID NOT NULL UNIQUE,
    realty_group_id                 BIGINT NOT NULL REFERENCES realty_groups(id),
    sl_group_uuid                   UUID NOT NULL,
    sl_group_name                   VARCHAR(255),
    verified                        BOOLEAN NOT NULL DEFAULT FALSE,
    verified_at                     TIMESTAMPTZ,
    verified_via                    VARCHAR(20),
    verification_code               VARCHAR(32),
    verification_code_expires_at    TIMESTAMPTZ,
    last_polled_at                  TIMESTAMPTZ,
    poll_attempts                   INTEGER NOT NULL DEFAULT 0,
    founder_avatar_uuid             UUID,
    created_at                      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version                         BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_rg_sl_groups_sl_group_uuid UNIQUE (sl_group_uuid),
    CONSTRAINT ck_rg_sl_groups_verified_via
      CHECK (verified_via IS NULL OR verified_via IN ('ABOUT_TEXT', 'FOUNDER_TERMINAL'))
);

CREATE INDEX ix_rg_sl_groups_realty_group
  ON realty_group_sl_groups(realty_group_id);

CREATE INDEX ix_rg_sl_groups_pending_poll
  ON realty_group_sl_groups(last_polled_at)
  WHERE verified = false AND verified_via IS NULL;

ALTER TABLE realty_group_members
  ADD COLUMN agent_commission_rate DECIMAL(5,4) NOT NULL DEFAULT 0;

ALTER TABLE realty_group_members
  ADD CONSTRAINT ck_rg_members_commission_rate_nonneg
    CHECK (agent_commission_rate >= 0);

ALTER TABLE auctions
  ADD COLUMN agent_commission_rate DECIMAL(5,4) NULL;

ALTER TABLE auctions
  ADD COLUMN realty_group_sl_group_id BIGINT NULL
    REFERENCES realty_group_sl_groups(id);

CREATE INDEX ix_auctions_realty_group_sl_group_id
  ON auctions(realty_group_sl_group_id)
  WHERE realty_group_sl_group_id IS NOT NULL;

-- MANAGE_OWN_LISTING was C-era case-2 plumbing. Case 2 was removed by E.
-- Strip the value from any existing permissions array (precautionary; no production rows today).
UPDATE realty_group_members
   SET permissions = array_remove(permissions, 'MANAGE_OWN_LISTING')
 WHERE 'MANAGE_OWN_LISTING' = ANY(permissions);

-- Per-member commission rate is also carried on the invitation row so a leader can
-- set the rate at invite time and have it copied verbatim onto the member row at
-- accept time. Default 0 keeps existing-invite semantics unchanged (no implicit rate).
ALTER TABLE realty_group_invitations
  ADD COLUMN agent_commission_rate DECIMAL(5,4) NOT NULL DEFAULT 0;

ALTER TABLE realty_group_invitations
  ADD CONSTRAINT ck_rg_invitations_commission_rate_nonneg
    CHECK (agent_commission_rate >= 0);
