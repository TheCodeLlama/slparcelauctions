# Flyway Core Migrations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create a single Flyway migration `V1__core_tables.sql` containing the foundational SLPA schema (`users`, `parcels`, `auctions`, `parcel_tags`, `auction_tags`) plus seed data for the 25 parcel tags from DESIGN.md §7.

**Architecture:** One atomic SQL migration file under `backend/src/main/resources/db/migration/`. Verification piggybacks on the existing `BackendApplicationTests.contextLoads()` test (which runs Flyway against the dev profile's local PostgreSQL with `ddl-auto: validate`) plus a manual psql smoke check.

**Tech Stack:** Spring Boot 4.0.5, Flyway, PostgreSQL, JUnit 5, Maven.

**Spec:** `docs/superpowers/specs/2026-04-10-flyway-core-migrations-design.md`

---

## File Structure

| Action | Path | Purpose |
|---|---|---|
| Create | `backend/src/main/resources/db/migration/V1__core_tables.sql` | Single migration: 5 tables, 7 indexes, 25 tag rows |
| Delete | `backend/src/main/resources/db/migration/.gitkeep` | No longer needed once a real migration file exists |

No new Java sources, no new test files. The `users`, `parcels`, `auctions`, `parcel_tags`, and `auction_tags` tables are created by raw SQL in dependency order; FK constraints in the SQL itself enforce that ordering.

## Important Context for the Implementer

**Why no TDD failing-test-first cycle for this task.** The test we rely on (`BackendApplicationTests.contextLoads()`) already exists and already passes. Without a `V1` file, Flyway has nothing to migrate and the test passes trivially. With a broken `V1` file, the test fails with a Flyway exception. So the natural verification cycle is: build the migration, run the test once at the end, fix any errors, commit. There is no point writing a new test that asserts schema state — the next task (01-04, JPA entities) will exercise every column and constraint via Hibernate's `validate` mode, which is a stronger check than anything we'd write here by hand.

**Why dev DB state matters.** Flyway records every applied migration in `flyway_schema_history` with a checksum. If a previous attempt has already applied a partial or wrong `V1`, you'll get a checksum mismatch on the next run. **Task 1 below resets the dev DB to a clean state before you start writing SQL.** Do not skip it.

**The dev profile config (already in place from Task 01-01):**
- JDBC URL: `jdbc:postgresql://localhost:5432/slpa`
- Username: `slpa` / Password: `slpa`
- `spring.jpa.hibernate.ddl-auto: validate`
- `spring.flyway.enabled: true`
- Migration location: classpath default `db/migration`

---

### Task 1: Reset dev database to a clean state

**Files:** none (database state only)

**Why:** Eliminates the possibility of a Flyway checksum mismatch from a previous attempt at this task.

- [ ] **Step 1: Stop any running backend instance**

If `./mvnw spring-boot:run` is running in another terminal, stop it (Ctrl-C). The reset commands below will fail if Spring Boot holds an open connection.

- [ ] **Step 2: Open a psql session against the dev database**

Run:
```bash
psql -h localhost -U slpa -d slpa
```

Password when prompted: `slpa`

Expected: psql prompt `slpa=>`.

If `psql` is not on PATH, use whatever client you prefer (DBeaver, IntelliJ Database tool, `docker exec` into the postgres container, etc.). The SQL commands below are the same regardless of client.

- [ ] **Step 3: Drop and recreate the public schema**

Run inside psql:
```sql
DROP SCHEMA public CASCADE;
CREATE SCHEMA public;
GRANT ALL ON SCHEMA public TO slpa;
GRANT ALL ON SCHEMA public TO public;
```

Expected: four `NOTICE` / command-complete lines, no errors. The `flyway_schema_history` table is dropped along with everything else.

- [ ] **Step 4: Verify the schema is empty**

Run inside psql:
```sql
\dt
```

Expected output: `Did not find any relations.`

- [ ] **Step 5: Exit psql**

Run:
```sql
\q
```

---

### Task 2: Create V1__core_tables.sql, verify, and commit

**Files:**
- Create: `backend/src/main/resources/db/migration/V1__core_tables.sql`
- Delete: `backend/src/main/resources/db/migration/.gitkeep`

**Why:** This is the entire migration. Building it as one file in one task respects the spec's "single atomic V1" decision and avoids Flyway checksum churn between sub-steps.

The steps below append to the same file in order. After the last SQL block is appended, run the test once to verify the whole thing.

- [ ] **Step 1: Create the file with a header comment**

Create `backend/src/main/resources/db/migration/V1__core_tables.sql` with these contents:

```sql
-- V1__core_tables.sql
-- Task 01-02: Foundational SLPA tables — users, parcels, auctions, parcel_tags, auction_tags.
-- Spec: docs/superpowers/specs/2026-04-10-flyway-core-migrations-design.md
-- See DESIGN.md §7 for the canonical schema definitions.

```

- [ ] **Step 2: Append the `users` table**

Append to `V1__core_tables.sql`:

```sql
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
    profile_pic_url          VARCHAR(512),
    verified                 BOOLEAN DEFAULT FALSE,
    verified_at              TIMESTAMP,
    avg_seller_rating        DECIMAL(3,2),
    avg_buyer_rating         DECIMAL(3,2),
    total_seller_reviews     INTEGER DEFAULT 0,
    total_buyer_reviews      INTEGER DEFAULT 0,
    completed_sales          INTEGER DEFAULT 0,
    cancelled_with_bids      INTEGER DEFAULT 0,
    listing_suspension_until TIMESTAMP,
    email_verified           BOOLEAN DEFAULT FALSE,
    notify_email             JSONB DEFAULT '{"bidding":true,"auction_result":true,"escrow":true,"listing_status":true,"reviews":true,"realty_group":true,"marketing":false}',
    notify_sl_im             JSONB DEFAULT '{"bidding":true,"auction_result":true,"escrow":true,"listing_status":true,"reviews":false,"realty_group":false,"marketing":false}',
    notify_email_muted       BOOLEAN DEFAULT FALSE,
    notify_sl_im_muted       BOOLEAN DEFAULT FALSE,
    sl_im_quiet_start        TIME,
    sl_im_quiet_end          TIME,
    created_at               TIMESTAMP DEFAULT NOW(),
    updated_at               TIMESTAMP DEFAULT NOW()
);
```

Notes:
- The DESIGN.md duplicate `email` column is collapsed into the single `UNIQUE NOT NULL` declaration on line 5 of the table.
- Both JSONB defaults are on a single line (Postgres tolerates whitespace inside the JSON literal but a single line is easier to diff).

- [ ] **Step 3: Append the `parcels` table and its index**

Append to `V1__core_tables.sql`:

```sql

-- ============================================================================
-- parcels
-- ============================================================================
CREATE TABLE parcels (
    id              BIGSERIAL PRIMARY KEY,
    parcel_uuid     UUID UNIQUE NOT NULL,
    owner_id        BIGINT REFERENCES users(id),
    sl_owner_uuid   UUID,
    owner_type      VARCHAR(10) DEFAULT 'AGENT',
    sl_group_uuid   UUID,
    parcel_name     VARCHAR(255),
    region_name     VARCHAR(255),
    grid_x          INTEGER,
    grid_y          INTEGER,
    area_sqm        INTEGER,
    prim_capacity   INTEGER,
    maturity        VARCHAR(20),
    estate_type     VARCHAR(50),
    description     TEXT,
    snapshot_url    VARCHAR(512),
    layout_map_url  VARCHAR(512),
    layout_map_data JSONB,
    layout_map_at   TIMESTAMP,
    location        VARCHAR(100),
    slurl           VARCHAR(512),
    verified        BOOLEAN DEFAULT FALSE,
    verified_at     TIMESTAMP,
    last_checked    TIMESTAMP,
    created_at      TIMESTAMP DEFAULT NOW()
);
CREATE INDEX idx_parcels_grid ON parcels(grid_x, grid_y);
```

- [ ] **Step 4: Append the `auctions` table and its three indexes**

Append to `V1__core_tables.sql`:

```sql

-- ============================================================================
-- auctions
-- ============================================================================
CREATE TABLE auctions (
    id                  BIGSERIAL PRIMARY KEY,
    parcel_id           BIGINT REFERENCES parcels(id),
    seller_id           BIGINT REFERENCES users(id),
    listing_agent_id    BIGINT REFERENCES users(id),
    realty_group_id     BIGINT,  -- FK to realty_groups(id) added in Task 01-03 / V2 (table doesn't exist yet)
    status              VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
    -- Status values (validated at the application layer, not via CHECK):
    --   DRAFT, DRAFT_PAID, VERIFICATION_PENDING, VERIFICATION_FAILED,
    --   ACTIVE, ENDED, ESCROW_PENDING, ESCROW_FUNDED,
    --   TRANSFER_PENDING, COMPLETED, CANCELLED, EXPIRED, DISPUTED
    verification_tier   VARCHAR(10),
    verification_method VARCHAR(20),
    assigned_bot_uuid   UUID,
    sale_sentinel_price INTEGER,
    last_bot_check_at   TIMESTAMP,
    bot_check_failures  INTEGER DEFAULT 0,
    listing_fee_paid    BOOLEAN DEFAULT FALSE,
    listing_fee_amt     INTEGER,
    listing_fee_txn     VARCHAR(255),
    listing_fee_paid_at TIMESTAMP,
    verified_at         TIMESTAMP,
    verification_notes  TEXT,
    starting_bid        INTEGER NOT NULL,
    reserve_price       INTEGER,
    buy_now_price       INTEGER,
    current_bid         INTEGER DEFAULT 0,
    bid_count           INTEGER DEFAULT 0,
    winner_id           BIGINT REFERENCES users(id),
    duration_hours      INTEGER NOT NULL,
    snipe_protect       BOOLEAN DEFAULT FALSE,
    snipe_window_min    INTEGER,
    starts_at           TIMESTAMP,
    ends_at             TIMESTAMP,
    original_ends_at    TIMESTAMP,
    seller_desc         TEXT,
    commission_rate     DECIMAL(5,4) DEFAULT 0.0500,
    commission_amt     INTEGER,
    agent_fee_rate      DECIMAL(5,4) DEFAULT 0,
    agent_fee_amt       INTEGER,
    created_at          TIMESTAMP DEFAULT NOW(),
    updated_at          TIMESTAMP DEFAULT NOW()
);
CREATE INDEX idx_auctions_status  ON auctions(status);
CREATE INDEX idx_auctions_seller  ON auctions(seller_id);
CREATE INDEX idx_auctions_ends_at ON auctions(ends_at);
```

Notes:
- `realty_group_id` is a plain `BIGINT` with no FK — the comment on that line documents why. Task 01-03 (V2) will add the FK via `ALTER TABLE`.
- No CHECK constraint on `status` — see spec section "auctions Table" for the rationale.
- The three new indexes (`idx_auctions_status`, `idx_auctions_seller`, `idx_auctions_ends_at`) are deviations from DESIGN.md, justified in the spec's "Deviations" table.

- [ ] **Step 5: Append the `parcel_tags` table**

Append to `V1__core_tables.sql`:

```sql

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
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT parcel_tags_code_unique UNIQUE (code),
    CONSTRAINT parcel_tags_code_format CHECK (code ~ '^[A-Z][A-Z0-9_]*$')
);
```

Notes:
- The `code` CHECK constraint forbids lowercase or punctuation, preventing case-insensitive duplicate codes.
- `active` is a soft-delete flag — pairs with the `ON DELETE RESTRICT` on `auction_tags.tag_id` below.

- [ ] **Step 6: Append the `auction_tags` join table and its index**

Append to `V1__core_tables.sql`:

```sql

-- ============================================================================
-- auction_tags (join table: auction ↔ parcel_tag)
-- ============================================================================
CREATE TABLE auction_tags (
    auction_id BIGINT NOT NULL REFERENCES auctions(id) ON DELETE CASCADE,
    tag_id     BIGINT NOT NULL REFERENCES parcel_tags(id) ON DELETE RESTRICT,
    PRIMARY KEY (auction_id, tag_id)
);
CREATE INDEX idx_auction_tags_tag ON auction_tags(tag_id);
```

Notes:
- `auction_id ON DELETE CASCADE`: deleting an auction removes its tag attachments.
- `tag_id ON DELETE RESTRICT`: pairs with `parcel_tags.active` soft-delete philosophy — admins must deactivate tags, not delete them.
- The PK index already covers `(auction_id, tag_id)` lookups; the secondary index serves "what auctions have this tag?" queries (browse-by-tag filter).

- [ ] **Step 7: Append the parcel_tags seed INSERT**

Append to `V1__core_tables.sql`:

```sql

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
```

- [ ] **Step 8: Delete `.gitkeep`**

Delete `backend/src/main/resources/db/migration/.gitkeep`. The directory is no longer empty so the placeholder is unneeded.

```bash
rm backend/src/main/resources/db/migration/.gitkeep
```

- [ ] **Step 9: Run the test suite**

Run from the repo root:
```bash
cd backend && ./mvnw test
```

Expected: `BUILD SUCCESS`. The test you care about is `BackendApplicationTests.contextLoads`, which spins up the full Spring context against the dev profile's PostgreSQL. Flyway runs `V1__core_tables.sql` during context startup; any SQL syntax error or constraint violation will surface as a `FlywayException` in the test failure trace.

If the test fails:
1. Read the Flyway error message carefully — it points to the offending SQL line.
2. Fix the SQL in `V1__core_tables.sql`.
3. Re-run Task 1's reset steps (the failed migration left a row in `flyway_schema_history` that will block re-application).
4. Re-run `./mvnw test`.

- [ ] **Step 10: Manual smoke check via psql**

Open a fresh psql session:
```bash
psql -h localhost -U slpa -d slpa
```

Run each query and confirm the expected output:

```sql
-- Should list exactly: auctions, auction_tags, flyway_schema_history, parcel_tags, parcels, users
\dt
```

```sql
-- Should return 1 row, success = t
SELECT version, description, success FROM flyway_schema_history;
```

Expected:
```
 version |   description    | success
---------+------------------+---------
 1       | core tables      | t
```

```sql
-- Should return 25
SELECT count(*) FROM parcel_tags;
```

```sql
-- Should return 5: 'Terrain / Environment', 'Roads / Access', 'Location Features', 'Neighbors / Context', 'Parcel Features'
SELECT DISTINCT category FROM parcel_tags ORDER BY category;
```

```sql
-- Should reject the insert with: ERROR:  new row for relation "parcel_tags" violates check constraint "parcel_tags_code_format"
INSERT INTO parcel_tags (code, label, category) VALUES ('lowercase', 'Lowercase', 'Test');
```

```sql
-- Should reject the insert with: ERROR:  insert or update on table "auction_tags" violates foreign key constraint
INSERT INTO auction_tags (auction_id, tag_id) VALUES (999, 999);
```

```sql
-- Confirms the three deferred / new indexes exist on auctions
SELECT indexname FROM pg_indexes WHERE tablename = 'auctions' ORDER BY indexname;
```

Expected to include: `idx_auctions_ends_at`, `idx_auctions_seller`, `idx_auctions_status`, plus the PK index `auctions_pkey`.

Exit psql:
```sql
\q
```

- [ ] **Step 11: Stage and commit**

Run from repo root:
```bash
git add backend/src/main/resources/db/migration/V1__core_tables.sql
git rm backend/src/main/resources/db/migration/.gitkeep
git status
```

Expected `git status`: one new file (`V1__core_tables.sql`), one deleted file (`.gitkeep`). Nothing else.

Then commit:
```bash
git commit -m "$(cat <<'EOF'
feat(db): add V1 core tables migration

Creates users, parcels, auctions, parcel_tags, and auction_tags
in a single Flyway migration. Tags are a real table (not a Postgres
enum) so admins can manage them via the UI later. Three extra
hot-path indexes added on auctions (status, seller_id, ends_at).
realty_group_id FK deferred to Task 01-03.

Refs: docs/superpowers/specs/2026-04-10-flyway-core-migrations-design.md

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

Expected: commit succeeds. If a pre-commit hook fails, fix the underlying issue, re-stage, and create a NEW commit (do not amend).

---

## Self-Review

**Spec coverage check** — every section of the spec is implemented in this plan:

| Spec section | Implemented in |
|---|---|
| Migration File Structure (single V1) | Task 2, Step 1 |
| `users` table | Task 2, Step 2 |
| `parcels` table + `idx_parcels_grid` | Task 2, Step 3 |
| `auctions` table + 3 new indexes + deferred FK | Task 2, Step 4 |
| `parcel_tags` table + CHECK + soft delete | Task 2, Step 5 |
| `auction_tags` join table + CASCADE/RESTRICT | Task 2, Step 6 |
| Seed data (25 tags, single multi-row INSERT) | Task 2, Step 7 |
| Delete `.gitkeep` | Task 2, Step 8 |
| Verification via `BackendApplicationTests` | Task 2, Step 9 |
| Manual smoke check | Task 2, Step 10 |
| Files Created/Modified table | File Structure section above |
| Acceptance Criteria | Covered by Steps 9 + 10 |

**Placeholder scan:** None. Every step contains either complete SQL, an exact command, or an exact action.

**Type / name consistency:** Cross-checked column names between tables (`users.id` vs FKs in `parcels.owner_id`, `auctions.seller_id`, `auctions.listing_agent_id`, `auctions.winner_id`; `parcels.id` vs `auctions.parcel_id`; `parcel_tags.id` vs `auction_tags.tag_id`; `auctions.id` vs `auction_tags.auction_id`). All match. Index names consistent. Constraint names consistent.
