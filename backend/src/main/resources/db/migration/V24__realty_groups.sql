-- V24: Realty Groups (sub-projects A + B — core entities + per-(group, agent) permission flags).
--
-- Three new tables under the BaseMutableEntity convention plus the deferred FK on auctions.realty_group_id
-- (the column already exists on auctions; this migration realizes the FK constraint).
--
-- Spec: docs/superpowers/specs/2026-05-10-realty-groups-core-permissions-design.md
--
-- Intentional schema notes (see spec §10):
--   - No `role` column on realty_group_members: role is computed from leader_id at read time
--     (avoids drift between realty_groups.leader_id and a per-member role column).
--   - permissions stored as TEXT[] (PostgreSQL native array of enum names) for cheap "add a new
--     permission value" semantics in future sub-projects (C/D/E/F) without column-add migrations.
--   - Partial unique indexes on (name_lower) and (slug) so dissolved groups don't block name reuse.
--   - dissolved_at is a soft-delete column; the row stays for audit but the partial indexes
--     immediately let a fresh group claim the same name+slug.

CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS citext;

-- ============================================================
-- realty_groups
-- ============================================================
CREATE TABLE realty_groups (
    id                  BIGSERIAL PRIMARY KEY,
    public_id           UUID NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    name                VARCHAR(64) NOT NULL,
    name_lower          CITEXT GENERATED ALWAYS AS (lower(name)) STORED,
    slug                VARCHAR(80) NOT NULL,
    leader_id           BIGINT NOT NULL REFERENCES users(id),
    logo_object_key     VARCHAR(500),
    logo_content_type   VARCHAR(100),
    logo_size_bytes     BIGINT,
    cover_object_key    VARCHAR(500),
    cover_content_type  VARCHAR(100),
    cover_size_bytes    BIGINT,
    description         TEXT,
    website             TEXT,
    agent_fee_rate      NUMERIC(5,4) NOT NULL DEFAULT 0.0000,
    agent_fee_split     NUMERIC(5,4) NOT NULL DEFAULT 0.5000,
    member_seat_limit   INTEGER NOT NULL DEFAULT 50,
    last_renamed_at     TIMESTAMPTZ,
    dissolved_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version             BIGINT NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX ix_realty_groups_name_lower_active
    ON realty_groups (name_lower) WHERE dissolved_at IS NULL;
CREATE UNIQUE INDEX ix_realty_groups_slug_active
    ON realty_groups (slug) WHERE dissolved_at IS NULL;
CREATE INDEX ix_realty_groups_leader ON realty_groups (leader_id);

-- ============================================================
-- realty_group_members
-- ============================================================
CREATE TABLE realty_group_members (
    id          BIGSERIAL PRIMARY KEY,
    public_id   UUID NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    group_id    BIGINT NOT NULL REFERENCES realty_groups(id) ON DELETE CASCADE,
    user_id     BIGINT NOT NULL REFERENCES users(id),
    permissions TEXT[] NOT NULL DEFAULT '{}',
    joined_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version     BIGINT NOT NULL DEFAULT 0,
    UNIQUE (group_id, user_id)
);
CREATE INDEX ix_realty_group_members_user ON realty_group_members (user_id);

-- ============================================================
-- realty_group_invitations
-- ============================================================
CREATE TABLE realty_group_invitations (
    id                 BIGSERIAL PRIMARY KEY,
    public_id          UUID NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    group_id           BIGINT NOT NULL REFERENCES realty_groups(id) ON DELETE CASCADE,
    invited_user_id    BIGINT NOT NULL REFERENCES users(id),
    invited_by_id      BIGINT NOT NULL REFERENCES users(id),
    status             VARCHAR(10) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING','ACCEPTED','DECLINED','REVOKED','EXPIRED')),
    permissions        TEXT[] NOT NULL DEFAULT '{}',
    expires_at         TIMESTAMPTZ NOT NULL,
    responded_at       TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version            BIGINT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ix_invitations_one_live_per_pair
    ON realty_group_invitations (group_id, invited_user_id) WHERE status = 'PENDING';
CREATE INDEX ix_invitations_invitee_status
    ON realty_group_invitations (invited_user_id, status);

-- ============================================================
-- Realize the deferred FK from auctions.realty_group_id.
-- The column already exists on auctions; it's null everywhere prior to this migration.
-- ON DELETE SET NULL: dissolving a group SET NULLs auctions.realty_group_id rather than
-- cascading the auction. Soft-delete (dissolved_at) is the normal path; this FK behavior
-- handles the rare hard-delete case (admin purge).
-- ============================================================
ALTER TABLE auctions
    ADD CONSTRAINT fk_auctions_realty_group
    FOREIGN KEY (realty_group_id) REFERENCES realty_groups(id) ON DELETE SET NULL;
