_April 10, 2026_

# Task 01-02: Flyway Migrations — Core Tables — Design Spec

## Goal

Create the foundational database tables (`users`, `parcels`, `auctions`) plus the supporting `parcel_tags` and `auction_tags` tables, all in a single Flyway migration. After this task, a fresh database can hold a verified user, a parcel they own, an auction listing that parcel, and the tags attached to that listing.

## Approach

Single migration file `V1__core_tables.sql` containing all five tables and the parcel tag seed data, in dependency order. The "split into V1/V2/V3" alternative offered in the task description is rejected — at this size, atomicity outweighs granularity.

Schema follows DESIGN.md §7 verbatim with three deliberate deviations, all called out in the [Deviations from DESIGN.md](#deviations-from-designmd) section below.

## Migration File Structure

**Path:** `backend/src/main/resources/db/migration/V1__core_tables.sql`

**Order of statements** (each section is a top-level comment block in the SQL file):

1. `users` table
2. `parcels` table + `idx_parcels_grid`
3. `auctions` table + `idx_auctions_status`, `idx_auctions_seller`, `idx_auctions_ends_at`
4. `parcel_tags` table
5. `auction_tags` table + `idx_auction_tags_tag`
6. Seed data: single multi-row `INSERT` into `parcel_tags` with all 25 tags

This order respects FK dependencies: `parcels.owner_id → users(id)`, `auctions.parcel_id → parcels(id)`, `auctions.seller_id/listing_agent_id/winner_id → users(id)`, `auction_tags.auction_id → auctions(id)`, `auction_tags.tag_id → parcel_tags(id)`.

## `users` Table

Exact column set from DESIGN.md §7. The duplicated `email` field in DESIGN.md (declared in both the main identity block and the notification block) is collapsed to a single `VARCHAR(255) UNIQUE NOT NULL` column. This matches the task description's note.

**FKs out:** none
**Constraints:** `UNIQUE(email)`, `UNIQUE(sl_avatar_uuid)`
**Indexes:** none beyond the implicit indexes from PK and UNIQUE constraints
**Notable defaults:**
- `notify_email JSONB DEFAULT '{"bidding":true,"auction_result":true,"escrow":true,"listing_status":true,"reviews":true,"realty_group":true,"marketing":false}'`
- `notify_sl_im JSONB DEFAULT '{"bidding":true,"auction_result":true,"escrow":true,"listing_status":true,"reviews":false,"realty_group":false,"marketing":false}'`
- `verified BOOLEAN DEFAULT FALSE`
- `email_verified BOOLEAN DEFAULT FALSE`
- `notify_email_muted BOOLEAN DEFAULT FALSE`
- `notify_sl_im_muted BOOLEAN DEFAULT FALSE`
- `total_seller_reviews / total_buyer_reviews / completed_sales / cancelled_with_bids INTEGER DEFAULT 0`
- `created_at / updated_at TIMESTAMP DEFAULT NOW()`

## `parcels` Table

Exact column set from DESIGN.md §7.

**FKs out:** `owner_id → users(id)`
**Constraints:** `UNIQUE(parcel_uuid)`
**Indexes:**
- `idx_parcels_grid ON parcels(grid_x, grid_y)` (from DESIGN.md)
**Notable defaults:**
- `owner_type VARCHAR(10) DEFAULT 'AGENT'`
- `verified BOOLEAN DEFAULT FALSE`
- `created_at TIMESTAMP DEFAULT NOW()`

## `auctions` Table

Exact column set from DESIGN.md §7, with two intentional schema choices:

1. **`status VARCHAR(30) NOT NULL DEFAULT 'DRAFT'` with no CHECK constraint.** The task description explicitly chose VARCHAR over an enum so that adding new statuses doesn't require a migration. A CHECK constraint listing valid values would defeat that goal. Status validation lives at the application layer (Java enum + service-layer guards).

2. **`realty_group_id BIGINT` with no foreign key constraint.** The `realty_groups` table is created in Task 01-03. Adding the FK now would require either pulling realty_groups into this migration (scope creep) or splitting V1 into multiple files. Instead, the column is declared as a plain `BIGINT` with an inline SQL comment: `-- FK to realty_groups(id) added in Task 01-03 / V2`.

**FKs out:**
- `parcel_id → parcels(id)`
- `seller_id → users(id)`
- `listing_agent_id → users(id)`
- `winner_id → users(id)`

**Indexes added in this migration:**
- `idx_auctions_status ON auctions(status)` — every browse-listings query filters by status.
- `idx_auctions_seller ON auctions(seller_id)` — "my listings" dashboard query.
- `idx_auctions_ends_at ON auctions(ends_at)` — auction expiry sweeper job (Epic 04).

These three are not in DESIGN.md but are obvious hot paths and are added proactively rather than chasing down a separate index migration in a few weeks.

**Notable defaults:**
- `status VARCHAR(30) NOT NULL DEFAULT 'DRAFT'`
- `current_bid INTEGER DEFAULT 0`
- `bid_count INTEGER DEFAULT 0`
- `listing_fee_paid BOOLEAN DEFAULT FALSE`
- `snipe_protect BOOLEAN DEFAULT FALSE`
- `bot_check_failures INTEGER DEFAULT 0`
- `commission_rate DECIMAL(5,4) DEFAULT 0.0500`
- `agent_fee_rate DECIMAL(5,4) DEFAULT 0`
- `created_at / updated_at TIMESTAMP DEFAULT NOW()`

## `parcel_tags` Table

**Replaces** the `parcel_tag` Postgres ENUM from DESIGN.md. Tags become rows in a real table so that admins can add, rename, and deactivate tags through the SLPA admin UI without writing a migration. See [Deviations from DESIGN.md](#deviations-from-designmd).

```sql
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

**Design rationale:**

- **`code` is the stable machine identifier**, `label` is the display string. Backend logic and analytics key off `code`. UI rename of "Waterfront" → "Oceanfront" only changes `label`.
- **`code` CHECK constraint** enforces UPPER_SNAKE_CASE format at the database level. Prevents the eventual "waterfront" / "WATERFRONT" duplicate-tag mess.
- **`active` flag (soft delete)** rather than hard delete. Once a tag is attached to even one historical auction, hard-deleting it would either cascade-remove `auction_tags` rows (loses listing history) or fail outright. Soft delete lets admins hide a tag from new listings without rewriting history.
- **`category` as plain VARCHAR**, not a normalized `parcel_tag_categories` table. Five categories, rarely-changing — normalization is overkill at this scale and easy to extract later if it becomes admin-editable.
- **`sort_order` column** so the UI can present tags in an intentional order rather than alphabetical or insertion order. Lower values render first.

## `auction_tags` Table

Pure join table — no surrogate id.

```sql
CREATE TABLE auction_tags (
    auction_id BIGINT NOT NULL REFERENCES auctions(id) ON DELETE CASCADE,
    tag_id     BIGINT NOT NULL REFERENCES parcel_tags(id) ON DELETE RESTRICT,
    PRIMARY KEY (auction_id, tag_id)
);
CREATE INDEX idx_auction_tags_tag ON auction_tags(tag_id);
```

**FK delete behavior rationale:**

- `auction_id ... ON DELETE CASCADE` — when an auction is deleted, its tag attachments go with it. Tag attachments have no meaning without the auction.
- `tag_id ... ON DELETE RESTRICT` — pairs with the soft-delete philosophy on `parcel_tags`. Prevents accidental hard delete of an in-use tag; forces admins to use the `active` flag instead.

**Index rationale:**

- The PK already creates an index on `(auction_id, tag_id)`, which serves "what tags does this auction have?" queries.
- `idx_auction_tags_tag ON (tag_id)` serves the reverse query "what auctions have this tag?", which is the basis for the browse-by-tag filter on the listings page.

## Seed Data

A single multi-row `INSERT` at the bottom of `V1__core_tables.sql` populates `parcel_tags` with all 25 tags from DESIGN.md §7. Inline rather than a separate `V2__seed_parcel_tags.sql` file: keeps the "fresh install brings up a working tag system" guarantee atomic in one migration.

```sql
INSERT INTO parcel_tags (code, label, category, description, sort_order) VALUES
  ('WATERFRONT',       'Waterfront',       'Terrain / Environment', 'Ocean, lake, or river border',                          1),
  ('SAILABLE',         'Sailable',         'Terrain / Environment', 'Connected to navigable water (Linden waterways)',       2),
  ('GRASS',            'Grass',            'Terrain / Environment', 'Grass terrain',                                          3),
  ('SNOW',             'Snow',             'Terrain / Environment', 'Snow terrain',                                           4),
  ('SAND',             'Sand',             'Terrain / Environment', 'Sand or beach terrain',                                  5),
  ('MOUNTAIN',         'Mountain',         'Terrain / Environment', 'Elevated or hilly terrain',                              6),
  ('FOREST',           'Forest',           'Terrain / Environment', 'Wooded area',                                            7),
  ('FLAT',             'Flat',             'Terrain / Environment', 'Level terrain, good for building',                       8),
  ('STREETFRONT',      'Streetfront',      'Roads / Access',        'Borders a Linden road',                                  9),
  ('ROADSIDE',         'Roadside',         'Roads / Access',        'Near (but not directly on) a Linden road',              10),
  ('RAILWAY',          'Railway',          'Roads / Access',        'Near Linden railroad / SLRR',                           11),
  ('CORNER_LOT',       'Corner Lot',       'Location Features',     'Parcel on a corner (two road or water sides)',          12),
  ('HILLTOP',          'Hilltop',          'Location Features',     'Elevated with views',                                   13),
  ('ISLAND',           'Island',           'Location Features',     'Surrounded by water',                                   14),
  ('PENINSULA',        'Peninsula',        'Location Features',     'Water on three sides',                                  15),
  ('SHELTERED',        'Sheltered',        'Location Features',     'Enclosed or private feeling, surrounded by terrain',    16),
  ('RESIDENTIAL',      'Residential',      'Neighbors / Context',   'Residential neighborhood',                              17),
  ('COMMERCIAL',       'Commercial',       'Neighbors / Context',   'Commercial or shopping area',                           18),
  ('INFOHUB_ADJACENT', 'Infohub Adjacent', 'Neighbors / Context',   'Near a Linden infohub',                                 19),
  ('PROTECTED_LAND',   'Protected Land',   'Neighbors / Context',   'Adjacent to Linden-owned protected land',               20),
  ('SCENIC',           'Scenic',           'Neighbors / Context',   'Notable views or landscape',                            21),
  ('DOUBLE_PRIM',      'Double Prim',      'Parcel Features',       'On a double-prim region (Magnum, etc.)',                22),
  ('HIGH_PRIM',        'High Prim',        'Parcel Features',       'Above-average prim capacity for size',                  23),
  ('LARGE_PARCEL',     'Large Parcel',     'Parcel Features',       '4096+ sqm',                                             24),
  ('FULL_REGION',      'Full Region',      'Parcel Features',       'Entire region (65536 sqm)',                             25);
```

## Deviations from DESIGN.md

These are the deliberate departures from DESIGN.md §7. All have been explicitly approved during brainstorming.

| # | Deviation | Reason |
|---|---|---|
| 1 | Tags are a **table** (`parcel_tags`), not a Postgres `ENUM` type. `auction_tags.tag` becomes `auction_tags.tag_id BIGINT REFERENCES parcel_tags(id)`. | Enables admin-editable tags via the SLPA UI without database migrations. |
| 2 | The seeded tag set has **25 values**, not the "27 values" mentioned in the task description prose. | DESIGN.md §7 (the stated source of truth) actually defines 25 tags: 8 Terrain + 3 Roads + 5 Location + 5 Neighbors + 4 Parcel = 25. The task description's "27" appears to be a counting error. |
| 3 | Added three indexes not in DESIGN.md: `idx_auctions_status`, `idx_auctions_seller`, `idx_auctions_ends_at`. | All three are obvious hot paths (browse filter, dashboard, expiry sweeper). Adding now is cheaper than discovering them via slow queries later. |
| 4 | `auctions.realty_group_id` is declared without a foreign key constraint. | The `realty_groups` table is created in Task 01-03. The FK is added there via `ALTER TABLE auctions ADD CONSTRAINT`. Tracked by an inline SQL comment on the column. |
| 5 | Added `CHECK (code ~ '^[A-Z][A-Z0-9_]*$')` to `parcel_tags.code`. | Prevents case-insensitive duplicate codes (e.g., `waterfront` vs `WATERFRONT`) at the database level. Cheap insurance against admin UI bugs. |
| 6 | The duplicated `email` column in DESIGN.md's `users` definition is collapsed to one column. | Per the task description note. |

## Verification

This task does not add new test files. Verification relies on existing infrastructure:

1. **`BackendApplicationTests.contextLoads()`** — already runs against the `dev` profile's local PostgreSQL with `ddl-auto: validate`. Flyway runs at startup; if `V1__core_tables.sql` has any SQL syntax error, the test fails. (No JPA entities exist yet, so `validate` has nothing to check beyond migration success.)

2. **Manual smoke check (one-time, during task execution):**
   - `cd backend && ./mvnw spring-boot:run`
   - Confirm the application starts without errors.
   - Connect to the local Postgres and verify:
     - `SELECT * FROM flyway_schema_history;` — one row for V1, `success = true`.
     - `\dt` — five new tables exist: `users`, `parcels`, `auctions`, `parcel_tags`, `auction_tags`.
     - `SELECT count(*) FROM parcel_tags;` — returns 25.

3. **Schema-vs-entity validation lands in Task 01-04**, which adds the JPA entities. At that point `ddl-auto: validate` will catch any column-type mismatch between this migration and the entity classes.

## Files Created/Modified

| Action | Path (relative to repo root) |
|---|---|
| Create | `backend/src/main/resources/db/migration/V1__core_tables.sql` |
| Delete | `backend/src/main/resources/db/migration/.gitkeep` |

The `.gitkeep` is no longer needed once a real migration file exists in the directory.

## Acceptance Criteria

- `V1__core_tables.sql` exists at `backend/src/main/resources/db/migration/V1__core_tables.sql`.
- Running `./mvnw test` succeeds — `BackendApplicationTests.contextLoads()` passes against the dev profile's PostgreSQL with V1 applied.
- After startup against a fresh database:
  - All five tables exist: `users`, `parcels`, `auctions`, `parcel_tags`, `auction_tags`.
  - All foreign key relationships in [the relevant sections above](#auctions-table) are present, except `auctions.realty_group_id`, which is intentionally a plain `BIGINT` until Task 01-03.
  - The `parcel_tags` table contains exactly 25 rows.
  - The `parcel_tags.code` CHECK constraint rejects a row with `code = 'waterfront'` and accepts a row with `code = 'TEST_TAG'`.
  - All indexes from the [migration file structure](#migration-file-structure) section exist (verifiable via `\d auctions`, `\d parcels`, `\d auction_tags`).
  - `flyway_schema_history` has one row for V1 with `success = true`.
- The `auction_tags` join table accepts an insert linking a real auction id to a real tag id, and rejects an insert referencing a nonexistent tag id.
