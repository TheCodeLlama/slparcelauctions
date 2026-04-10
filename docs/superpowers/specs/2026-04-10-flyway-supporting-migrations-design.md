_April 10, 2026_

# Task 01-03: Flyway Migrations — Supporting Tables — Design Spec

## Goal

Create the 15 remaining database tables that support the foundational schema from V1: realty groups, bidding, escrow, bot infrastructure, reviews, moderation, and verification. Add the deferred foreign key from `auctions.realty_group_id` to `realty_groups(id)`.

After this task, the entire SLPA Phase 1 schema exists in the database.

## Approach

Single migration file `V2__supporting_tables.sql` containing all 15 new tables, all indexes, and the `ALTER TABLE auctions` that wires up the deferred FK. Same atomicity rationale as V1 — the "split into V2/V3/V4..." alternative offered in the task description is rejected because none of these tables can be deployed independently and a single mental checkpoint is easier to reason about.

The migration follows V1's conventions across the board:
- `TIMESTAMPTZ` for every timestamp
- `NOT NULL` on every defaulted column (timestamps, counters, booleans)
- `BIGINT` for L$ money columns
- `TEXT` for URL columns
- VARCHAR with inline `-- enum: A | B | C` doc comments for status/role/type/reason fields — never Postgres ENUM types
- Decimal defaults written as `0.0000`-style for parity

These conventions are applied silently in the table definitions below; deviations from DESIGN.md §7 (which mostly hasn't been updated to match V1's conventions for the supporting tables) are listed in the [Deviations from DESIGN.md](#deviations-from-designmd) section.

## Migration File Structure

**Path:** `backend/src/main/resources/db/migration/V2__supporting_tables.sql`

**Order of statements** (each is a top-level comment block in the SQL file):

1. `realty_groups` table
2. `realty_group_members` table + `idx_rgm_group`
3. `realty_group_invitations` table + `idx_invitations_user_status`
4. `realty_group_sl_groups` table
5. `bids` table + `idx_bids_auction` + `idx_bids_bidder`
6. `proxy_bids` table
7. `escrow_transactions` table + `idx_escrow_auction` + `idx_escrow_status`
8. `bot_accounts` table + `idx_bot_primary` partial unique
9. `bot_tasks` table + `idx_bot_tasks_bot` + `idx_bot_tasks_auction` + `idx_bot_tasks_status_scheduled`
10. `reviews` table + `idx_reviews_reviewee` + `idx_reviews_auction`
11. `cancellation_log` table + `idx_cancellation_seller`
12. `listing_reports` table + `idx_reports_auction` + `idx_reports_status`
13. `bans` table + `idx_bans_ip` partial + `idx_bans_avatar` partial
14. `fraud_flags` table + `idx_fraud_flags_status`
15. `verification_codes` table + `idx_verification_codes_user` + `idx_verification_codes_expires`
16. `ALTER TABLE auctions ADD CONSTRAINT auctions_realty_group_id_fkey ...`

This order respects FK dependencies. Realty groups come first because four tables and one ALTER reference them. Bot accounts come before bot tasks. Everything else only references core V1 tables.

## Realty Groups (4 tables + ALTER)

### `realty_groups`

```sql
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
```

### `realty_group_members`

```sql
CREATE TABLE realty_group_members (
    id        BIGSERIAL PRIMARY KEY,
    group_id  BIGINT NOT NULL REFERENCES realty_groups(id),
    user_id   BIGINT NOT NULL REFERENCES users(id),
    role      VARCHAR(20) NOT NULL DEFAULT 'AGENT',  -- enum: LEADER | AGENT
    joined_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(user_id)
);
CREATE INDEX idx_rgm_group ON realty_group_members(group_id);
```

The `UNIQUE(user_id)` enforces one-group-per-user at the database level. **Phase 1 constraint:** this blocks any future "user belonged to group A, left, joined group B" pattern unless we either soft-delete prior memberships (add a `left_at` column) or relax this constraint. DESIGN.md §6 commits to "one realty group per user" for Phase 1, so the global uniqueness is intentional. Flagged here as a known forward limitation.

### `realty_group_invitations`

```sql
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
```

`idx_invitations_user_status` is added proactively (not in DESIGN.md) to serve the "show me my pending invites" query on a user's dashboard.

### `realty_group_sl_groups`

```sql
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
```

### Deferred FK on `auctions.realty_group_id`

After all the realty group tables exist, wire up the FK that V1 deliberately omitted:

```sql
ALTER TABLE auctions
  ADD CONSTRAINT auctions_realty_group_id_fkey
  FOREIGN KEY (realty_group_id) REFERENCES realty_groups(id);
```

This statement lives at the very end of `V2__supporting_tables.sql` so all 15 new tables are created before the ALTER fires. The constraint name is explicit so the FK can be referenced or dropped later by name.

## Bidding (2 tables)

### `bids`

```sql
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
```

`idx_bids_auction` (composite, descending on amount) serves the "current winning bid" and "leaderboard" queries — a single index seek returns the top bid for an auction. `idx_bids_bidder` is added proactively for the "my bid history" dashboard query.

### `proxy_bids`

```sql
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
```

The `UNIQUE(auction_id, bidder_id)` enforces one-proxy-bid-per-user-per-auction. The implicit unique index also serves `WHERE auction_id = ?` lookups, so no separate `idx_proxy_bids_auction` is needed.

## Escrow (1 table)

### `escrow_transactions`

```sql
CREATE TABLE escrow_transactions (
    id                BIGSERIAL PRIMARY KEY,
    auction_id        BIGINT NOT NULL REFERENCES auctions(id),
    payer_id          BIGINT REFERENCES users(id),                  -- nullable: PAYOUT/REFUND have no payer-as-user
    payee_id          BIGINT REFERENCES users(id),                  -- nullable: PAYMENT has no payee yet
    type              VARCHAR(20) NOT NULL,                         -- enum: PAYMENT | PAYOUT | REFUND | COMMISSION
    amount            BIGINT NOT NULL,                              -- L$
    sl_transaction_id VARCHAR(100),                                 -- from llTransferLindenDollars
    terminal_id       VARCHAR(100),                                 -- which escrow terminal handled this
    status            VARCHAR(20) NOT NULL DEFAULT 'PENDING',       -- enum: PENDING | COMPLETED | FAILED | REFUNDED
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at      TIMESTAMPTZ
);
CREATE INDEX idx_escrow_auction ON escrow_transactions(auction_id);
CREATE INDEX idx_escrow_status  ON escrow_transactions(status);
```

**Why `payer_id`/`payee_id` are nullable:** the txn type determines which side is filled.
- `PAYMENT` (winner pays escrow): `payer_id` = winner, `payee_id` = NULL until the matching PAYOUT
- `PAYOUT` (escrow pays seller): `payer_id` = NULL (the SLPA bot is the source, not a user), `payee_id` = seller
- `REFUND` (escrow returns to buyer): `payer_id` = NULL, `payee_id` = buyer
- `COMMISSION` (platform's cut): `payer_id` = NULL, `payee_id` = SYSTEM user (see convention below)

The `type` column is the discriminator; the application layer picks the correct side.

**Documented convention for COMMISSION rows:** point `payee_id` at a dedicated SYSTEM user row rather than leaving it NULL. This makes aggregate queries unambiguous — `SELECT SUM(amount) FROM escrow_transactions WHERE payee_id = <system_user_id>` returns total commission collected without any reliance on `IS NULL` semantics or the assumption that `type='COMMISSION'` rows are the only rows with NULL payees.

The schema does not enforce this — both columns remain nullable, so the convention can be revisited at the app layer. The SYSTEM user row is **not** seeded in this migration; it will be created by the task that ships the first escrow code path (likely Phase 1 escrow flow). When seeded, it should have a non-routable email like `system@slpa.invalid`, a placeholder `password_hash` value that no real password could ever produce (e.g. literal `!DISABLED`), `verified = true`, and `display_name = 'SLPA System'`.

**Indexes added proactively:** `idx_escrow_auction` (every escrow lookup is per-auction), `idx_escrow_status` (the polling reconciliation monitor filters on status).

## Bot Infrastructure (2 tables)

### `bot_accounts`

```sql
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
CREATE UNIQUE INDEX idx_bot_primary ON bot_accounts(role) WHERE role = 'PRIMARY';
```

`idx_bot_primary` is a partial unique index that enforces "at most one PRIMARY bot account" at the database level. Multiple WORKER rows are allowed.

### `bot_tasks`

```sql
CREATE TABLE bot_tasks (
    id            BIGSERIAL PRIMARY KEY,
    bot_id        BIGINT REFERENCES bot_accounts(id),       -- nullable: PENDING tasks aren't yet assigned
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
```

**On the third index:** DESIGN.md specifies a partial index `idx_bot_tasks_scheduled ON bot_tasks(scheduled_at) WHERE status='PENDING'` for queue pickup. The composite `idx_bot_tasks_status_scheduled (status, scheduled_at)` is replacing it because it serves the same queue-pickup query (`WHERE status='PENDING' ORDER BY scheduled_at`) AND the admin-browse-by-status queries (`WHERE status='IN_PROGRESS' ORDER BY scheduled_at`, etc.). One index, more flexibility, no measurable cost difference for Phase 1 volume.

**Why `bot_id` is nullable:** tasks are created in PENDING state before being assigned to a bot. The task scheduler picks them up and sets `bot_id` when work starts.

## Reviews & Moderation (5 tables)

### `reviews`

```sql
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
```

The `CHECK (rating BETWEEN 1 AND 5)` is preserved from DESIGN.md — domain-correct constraint, not status-flexibility-blocking.

`UNIQUE(auction_id, reviewer_id)` enforces one review per user per auction.

`visible` defaults to `FALSE`. The blind review logic (becomes true when both submit, or after 14-day timeout) lives at the application layer.

### `cancellation_log`

```sql
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
```

### `listing_reports`

```sql
CREATE TABLE listing_reports (
    id          BIGSERIAL PRIMARY KEY,
    auction_id  BIGINT NOT NULL REFERENCES auctions(id),
    reporter_id BIGINT NOT NULL REFERENCES users(id),
    subject     VARCHAR(100) NOT NULL,
    -- reason: enum: INACCURATE_DESCRIPTION | WRONG_TAGS | SHILL_BIDDING |
    --   FRAUDULENT_SELLER | DUPLICATE_LISTING | NOT_ACTUALLY_FOR_SALE |
    --   TOS_VIOLATION | OTHER
    reason      VARCHAR(30) NOT NULL,
    details     TEXT NOT NULL,                                -- max 2000 chars enforced in app
    -- status: enum: OPEN | REVIEWED | DISMISSED | ACTION_TAKEN
    status      VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    admin_notes TEXT,
    reviewed_by BIGINT REFERENCES users(id),                  -- nullable: only set when admin acts
    reviewed_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(auction_id, reporter_id)
);
CREATE INDEX idx_reports_auction ON listing_reports(auction_id);
CREATE INDEX idx_reports_status  ON listing_reports(status);
```

DESIGN.md defines `report_reason` and `report_status` as Postgres `CREATE TYPE ... AS ENUM` types. This migration **does not create those types** — both columns are `VARCHAR` with the legal values listed in inline doc comments. See [Deviations from DESIGN.md](#deviations-from-designmd).

`UNIQUE(auction_id, reporter_id)` enforces one report per user per listing.

### `bans`

```sql
CREATE TABLE bans (
    id             BIGSERIAL PRIMARY KEY,
    ban_type       VARCHAR(10) NOT NULL,        -- enum: IP | AVATAR | BOTH
    ip_address     INET,                        -- NULL if avatar-only ban
    sl_avatar_uuid UUID,                        -- NULL if IP-only ban
    reason         TEXT NOT NULL,
    banned_by      BIGINT NOT NULL REFERENCES users(id),
    expires_at     TIMESTAMPTZ,                 -- NULL = permanent
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT bans_at_least_one_identifier
      CHECK (ip_address IS NOT NULL OR sl_avatar_uuid IS NOT NULL)
);
CREATE INDEX idx_bans_ip     ON bans(ip_address)    WHERE ip_address    IS NOT NULL;
CREATE INDEX idx_bans_avatar ON bans(sl_avatar_uuid) WHERE sl_avatar_uuid IS NOT NULL;
```

Two design decisions worth flagging:

1. **`banned_by` is `NOT NULL`** (DESIGN.md leaves it nullable). Every ban must be attributed to an admin — if SLPA ever adds automated bans, they should be attributed to a dedicated SYSTEM user row, not NULL. Audit clarity wins.

2. **`bans_at_least_one_identifier` CHECK constraint**: requires at least one of `ip_address` or `sl_avatar_uuid` to be set. The `ban_type` column ('IP', 'AVATAR', 'BOTH') tells the app which side(s) to read; the CHECK is belt-and-suspenders so a bug in the app can't insert a ban that targets nothing.

The two partial indexes serve "is this IP banned?" and "is this avatar banned?" lookups — the partial `WHERE ... IS NOT NULL` clause means the indexes only contain rows that are actually targetable, keeping them small.

### `fraud_flags`

```sql
CREATE TABLE fraud_flags (
    id          BIGSERIAL PRIMARY KEY,
    flag_type   VARCHAR(30) NOT NULL,                  -- enum-ish: SAME_IP_MULTI_ACCOUNT | NEW_ACCOUNT_LAST_SECOND | ...
    auction_id  BIGINT REFERENCES auctions(id),        -- nullable: flag may target a user only
    user_id     BIGINT REFERENCES users(id),           -- nullable: flag may target an auction only
    details     JSONB,                                  -- IP, timing, related accounts, etc.
    status      VARCHAR(20) NOT NULL DEFAULT 'OPEN',   -- enum: OPEN | REVIEWED | DISMISSED | ACTION_TAKEN
    reviewed_by BIGINT REFERENCES users(id),           -- nullable: only set when admin acts
    reviewed_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fraud_flags_at_least_one_target
      CHECK (user_id IS NOT NULL OR auction_id IS NOT NULL)
);
CREATE INDEX idx_fraud_flags_status ON fraud_flags(status);
```

Both `auction_id` and `user_id` are nullable because a flag may target either or both, depending on the detection rule. The `fraud_flags_at_least_one_target` CHECK is the same belt-and-suspenders pattern used on `bans` — a flag with no target is a bug, and the database should reject it before the buggy app code can persist it.

## Verification (1 table)

### `verification_codes`

```sql
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
```

The `verification_codes_format` CHECK enforces 6-digit numeric codes at the database level — same cheap-insurance pattern as V1's `parcel_tags.code` regex check. Prevents an app bug from inserting an alphanumeric or wrong-length code.

**Indexes added proactively:**
- `idx_verification_codes_user (user_id, used)` — lookup unused codes for a user during the verification flow
- `idx_verification_codes_expires (expires_at)` — the cleanup sweeper that deletes expired codes

## Deviations from DESIGN.md

These are deliberate departures from DESIGN.md §7. All have been explicitly approved during brainstorming.

| # | Deviation | Reason |
|---|---|---|
| 1 | All `report_reason` / `report_status` Postgres ENUM types are **omitted**. `listing_reports.reason` and `listing_reports.status` are plain `VARCHAR` with inline doc comments. | Same reasoning as `auctions.status` in V1: `ALTER TYPE ADD VALUE` cannot run inside a transaction in Postgres, removing values is impossible, and adding new categories later is then a migration headache. VARCHAR with app-layer Java enum validation gives the same type safety with zero migration cost. |
| 2 | **All status/role/type/reason VARCHAR columns** across the supporting tables get inline `-- enum: A \| B \| C` comments instead of being declared as Postgres ENUMs or constrained by CHECK. | Consistency with the V1 precedent and the deviation above. |
| 3 | Every `TIMESTAMP` column is **`TIMESTAMPTZ`** (DESIGN.md still uses `TIMESTAMP` for the supporting tables — V1's update only touched users/parcels/auctions). | Same tz-footgun rationale as V1. |
| 4 | All defaulted columns (`*_at`, counters, booleans) get **`NOT NULL`**. | Same NULL-prevention rationale as V1. |
| 5 | L$ money columns are **`BIGINT`** (DESIGN.md has `bids.amount`, `proxy_bids.max_amount`, `escrow_transactions.amount` as `INTEGER`). | Same headroom rationale as V1 — high-value parcel sales and aggregate stats can exceed 2.1B L$. |
| 6 | URL columns on `realty_groups` (`logo_url`, `website`, `sl_group_link`) are **`TEXT`** instead of `VARCHAR(512)`. | Same URL-length rationale as V1. |
| 7 | Three FK columns DESIGN.md leaves nullable are **`NOT NULL`**: `bids.auction_id`, `bids.bidder_id`, `escrow_transactions.auction_id`, `bot_tasks.auction_id`, `verification_codes.user_id`, `bans.banned_by`. | Domain correctness — none of these rows are meaningful without the parent reference. `bans.banned_by` in particular is called out separately because DESIGN.md leaves it nullable and the user explicitly asked for `NOT NULL` so audit attribution is unambiguous. |
| 8 | `bot_tasks` index `idx_bot_tasks_scheduled` (DESIGN.md's partial `(scheduled_at) WHERE status='PENDING'`) is **replaced** by `idx_bot_tasks_status_scheduled (status, scheduled_at)`. | The composite serves both the queue-pickup query and admin browsing of other statuses. One index, more flexibility, no measurable cost. |
| 9 | New CHECK constraint `verification_codes_format CHECK (code ~ '^[0-9]{6}$')`. | Same cheap-insurance pattern as `parcel_tags.code` in V1 — DB-level format guarantee. |
| 10 | New CHECK constraint `bans_at_least_one_identifier CHECK (ip_address IS NOT NULL OR sl_avatar_uuid IS NOT NULL)`. | Belt-and-suspenders against an app bug inserting a ban that targets nothing. The `ban_type` column documents intent; the CHECK enforces it. |
| 11 | Added 7 indexes not in DESIGN.md: `idx_invitations_user_status`, `idx_bids_bidder`, `idx_escrow_auction`, `idx_escrow_status`, `idx_verification_codes_user`, `idx_verification_codes_expires`, `idx_bot_tasks_status_scheduled` (which replaces #8). | Same proactive-hot-path-indexes rationale as V1. All serve obvious Phase 1 query patterns (queue pickup, dashboard lists, status polling, cleanup sweepers). |
| 12 | New CHECK constraint `fraud_flags_at_least_one_target CHECK (user_id IS NOT NULL OR auction_id IS NOT NULL)`. | Same belt-and-suspenders pattern as `bans_at_least_one_identifier` — a fraud flag that targets nothing is a bug, and the database should reject it. |

## DESIGN.md §7 Update

Update `docs/initial-design/DESIGN.md` §7 in the same commit as the migration so DESIGN.md remains a live source of truth. Specifically:

1. **Add a header note** at the very top of §7 documenting the VARCHAR-over-ENUM convention so it doesn't have to be repeated per column. Suggested wording:

   > **Convention note:** Status, role, type, reason, and similar small-fixed-set columns are declared as `VARCHAR(N)` with inline `-- enum: A | B | C` doc comments. Postgres `CREATE TYPE ... AS ENUM` is intentionally avoided across this schema because `ALTER TYPE ADD VALUE` cannot run inside a transaction and removing values is impossible. Validation lives at the application layer via Java enums mapped through JPA.

2. **Update all 15 supporting tables** in §7 to match the migration: `TIMESTAMPTZ` everywhere, `NOT NULL` on defaulted columns, `BIGINT` for L$ money columns, `TEXT` for URL columns, inline enum doc comments, no Postgres `CREATE TYPE` statements.

3. **Remove** the `CREATE TYPE report_reason AS ENUM (...)` and `CREATE TYPE report_status AS ENUM (...)` blocks entirely.

4. **Update `auctions.realty_group_id`** in DESIGN.md §7 to drop any "FK deferred" inline comment since the FK now exists in V2. The line should read `realty_group_id BIGINT REFERENCES realty_groups(id), -- NULL if individual listing` (which is already its current form in DESIGN.md — V1's deferred-FK note lived in the migration file, not in DESIGN.md).

## Verification

This task does not add new test files. Verification relies on existing infrastructure plus a manual smoke check.

1. **`BackendApplicationTests.contextLoads()`** — already runs against the dev profile's local PostgreSQL with `ddl-auto: validate`. Flyway runs both V1 and V2 at startup; if `V2__supporting_tables.sql` has any SQL syntax error, FK reference to a missing table, or constraint violation, the test fails with a `FlywayException`.

2. **Manual smoke check (one-time, during task execution):**
   - Connect to the dev Postgres (`docker exec -i slpa-postgres psql -U slpa -d slpa`)
   - `\dt` — confirm 21 tables exist now: 6 from V1 (`users`, `parcels`, `auctions`, `parcel_tags`, `auction_tags`, `flyway_schema_history`) + 15 from V2
   - `SELECT version, description, success FROM flyway_schema_history ORDER BY version;` — confirm V1 and V2 both show `success=t`
   - Test the deferred FK now exists: `\d auctions` should show `auctions_realty_group_id_fkey` foreign key constraint
   - Test the verification_codes CHECK rejects bad codes: `INSERT INTO verification_codes (user_id, code, type, expires_at) VALUES (1, '12345', 'PLAYER', NOW() + INTERVAL '1 day');` should fail with `verification_codes_format` violation. Same for `'1234567'`, `'12345a'`, and `'abcdef'`. `'123456'` should succeed (assuming user_id=1 exists, otherwise it'll fail on FK instead).
   - Test the bans CHECK rejects empty-target bans: `INSERT INTO bans (ban_type, reason, banned_by) VALUES ('IP', 'test', 1);` should fail with `bans_at_least_one_identifier`. Adding `ip_address = '1.2.3.4'::inet` should make it succeed.
   - Test the fraud_flags CHECK rejects empty-target flags: `INSERT INTO fraud_flags (flag_type) VALUES ('TEST');` should fail with `fraud_flags_at_least_one_target`. Adding `user_id = 1` (or `auction_id = 1`) should make it succeed (or fail on the FK if no row exists, which is also fine — the CHECK is satisfied).
   - Test the bot_accounts partial unique index: insert a `PRIMARY` row, then try to insert a second `PRIMARY` row — second insert should fail with unique constraint violation.

3. **Schema-vs-entity validation lands in Task 01-04**, which adds the JPA entities. At that point `ddl-auto: validate` will catch any column-type mismatch between this migration and the entity classes.

## Files Created/Modified

| Action | Path (relative to repo root) |
|---|---|
| Create | `backend/src/main/resources/db/migration/V2__supporting_tables.sql` |
| Modify | `docs/initial-design/DESIGN.md` (§7 — header note + all 15 supporting table definitions + remove `CREATE TYPE` blocks) |

## Acceptance Criteria

- `V2__supporting_tables.sql` exists at the path above.
- `./mvnw test` — BUILD SUCCESS, all 5 tests pass against the dev Postgres with V1 + V2 applied.
- After startup against a fresh database:
  - All 15 new tables exist.
  - `flyway_schema_history` has rows for V1 and V2, both `success=true`.
  - `\d auctions` shows the `auctions_realty_group_id_fkey` foreign key constraint to `realty_groups(id)`.
  - The 5 manual smoke check assertions in the [Verification](#verification) section all behave as documented.
- `DESIGN.md` §7 reflects all migration changes; no `CREATE TYPE` statements remain in §7; the VARCHAR-over-ENUM header note is in place.
- One commit on the feature branch containing both the migration and the DESIGN.md update.
