# Flyway Supporting Migrations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create a single Flyway migration `V2__supporting_tables.sql` containing all 15 remaining Phase 1 tables (realty groups, bidding, escrow, bot infra, reviews, moderation, verification) and the deferred FK from V1 (`auctions.realty_group_id` → `realty_groups(id)`). Update `DESIGN.md` §7 in the same commit to keep documentation in sync with the schema.

**Architecture:** Single SQL migration file under `backend/src/main/resources/db/migration/` mirroring the V1 pattern. Verification piggybacks on the existing `BackendApplicationTests.contextLoads()` test (which runs Flyway against the dev profile's local PostgreSQL with `ddl-auto: validate`) plus a manual psql smoke check that exercises the new CHECK constraints, the deferred FK, and the partial unique index.

**Tech Stack:** Spring Boot 4.0.5, Flyway, PostgreSQL 16, JUnit 5, Maven.

**Spec:** `docs/superpowers/specs/2026-04-10-flyway-supporting-migrations-design.md`

---

## File Structure

| Action | Path | Purpose |
|---|---|---|
| Create | `backend/src/main/resources/db/migration/V2__supporting_tables.sql` | 15 tables, 19 indexes, 1 ALTER for the deferred V1 FK |
| Modify | `docs/initial-design/DESIGN.md` | §7 — header note + 15 supporting table definitions + removal of `CREATE TYPE report_*` blocks |

No new Java sources, no new test files. The 15 tables are created in raw SQL in dependency order; FK constraints in the SQL itself enforce that ordering.

## Important Context for the Implementer

**Why no failing-test-first cycle.** Same as V1: `BackendApplicationTests.contextLoads()` already exists and already passes. Without a `V2` file, Flyway sees no new migration and the test passes trivially. With a broken `V2`, the test fails with a Flyway exception. The verification cycle is: build the migration, run the test once at the end, fix any errors, commit. The next task (01-04, JPA entities) will exercise every column and constraint via Hibernate `validate`, which is a stronger check than anything written by hand here.

**Why dev DB state matters.** Flyway records every applied migration in `flyway_schema_history` with a checksum. If a previous attempt has already applied a partial or wrong V2, you'll get a checksum mismatch on the next run. **Task 1 below resets the dev DB to a clean state before you start writing SQL.** Do not skip it. Resetting also re-applies V1 from scratch, which is exactly what you want — V2 needs the V1 tables present.

**The dev profile config (already in place from Task 01-01):**
- JDBC URL: `jdbc:postgresql://localhost:5432/slpa`
- Username: `slpa` / Password: `slpa`
- `spring.jpa.hibernate.ddl-auto: validate`
- `spring.flyway.enabled: true`
- Migration location: classpath default `db/migration`

**Containers** (already running, do not start or stop):
- `slpa-postgres` (Postgres 16 alpine) on `localhost:5432`
- `slpa-redis` (Redis 7 alpine) on `localhost:6379`

To run SQL against Postgres: `docker exec -i slpa-postgres psql -U slpa -d slpa`

**JAVA_HOME** is set in the shell environment to `C:\Users/heath/.jdks/openjdk-26`. Just run `./mvnw test` directly — do not prefix with `JAVA_HOME=...`.

---

### Task 1: Reset dev database to a clean state

**Files:** none (database state only)

**Why:** Eliminates any leftover state from a previous attempt at this task or unrelated experiments. Resetting drops every table, including `flyway_schema_history`, so V1 gets re-applied from scratch when the test runs and V2 starts on a guaranteed-clean baseline.

- [ ] **Step 1: Stop any running backend instance**

If `./mvnw spring-boot:run` is running in another terminal, stop it (Ctrl-C). The reset commands below will fail if Spring Boot holds an open connection.

- [ ] **Step 2: Drop and recreate the public schema**

Run from any directory:

```bash
docker exec -i slpa-postgres psql -U slpa -d slpa <<'SQL'
DROP SCHEMA public CASCADE;
CREATE SCHEMA public;
GRANT ALL ON SCHEMA public TO slpa;
GRANT ALL ON SCHEMA public TO public;
SQL
```

Expected: a few `NOTICE` lines about dropped tables (or no notices on a fresh DB), no errors.

- [ ] **Step 3: Verify the schema is empty**

```bash
docker exec -i slpa-postgres psql -U slpa -d slpa -c '\dt'
```

Expected output: `Did not find any relations.`

---

### Task 2: Create V2__supporting_tables.sql, update DESIGN.md, verify, and commit

**Files:**
- Create: `backend/src/main/resources/db/migration/V2__supporting_tables.sql`
- Modify: `docs/initial-design/DESIGN.md`

**Why:** This is the entire migration plus the documentation sync. Building both in one task respects the spec's "single atomic V2" decision and matches V1's pattern. The migration is built up table-by-table in steps, then the test runs once at the end.

The steps below append to the same file in order. After the last SQL block is appended, the test runs once to verify the whole thing.

- [ ] **Step 1: Create the file with a header comment**

Create `backend/src/main/resources/db/migration/V2__supporting_tables.sql` with these contents:

```sql
-- V2__supporting_tables.sql
-- Task 01-03: Realty groups, bidding, escrow, bot infra, reviews,
-- moderation, and verification tables — plus the deferred FK on
-- auctions.realty_group_id from V1.
-- Spec: docs/superpowers/specs/2026-04-10-flyway-supporting-migrations-design.md

```

- [ ] **Step 2: Append the realty_groups block (4 tables)**

Append to `V2__supporting_tables.sql`:

```sql
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
```

- [ ] **Step 3: Append the bidding block (2 tables)**

```sql

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
```

- [ ] **Step 4: Append the escrow block (1 table)**

```sql

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
```

- [ ] **Step 5: Append the bot infrastructure block (2 tables)**

```sql

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
```

Note: DESIGN.md specifies a partial index `idx_bot_tasks_scheduled (scheduled_at) WHERE status='PENDING'`. The composite `idx_bot_tasks_status_scheduled (status, scheduled_at)` above replaces it — it serves both the queue-pickup query and admin status-browsing queries with one index. Do **not** create the partial index from DESIGN.md.

- [ ] **Step 6: Append the reviews block (1 table)**

```sql

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
```

- [ ] **Step 7: Append the moderation block (4 tables)**

```sql

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
    subject     VARCHAR(100) NOT NULL,
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
-- user for any future automated bans). bans_at_least_one_identifier CHECK
-- is belt-and-suspenders against an app bug inserting a ban that targets
-- nothing.
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
    CONSTRAINT bans_at_least_one_identifier
      CHECK (ip_address IS NOT NULL OR sl_avatar_uuid IS NOT NULL)
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
    flag_type   VARCHAR(30) NOT NULL,                  -- enum-ish: SAME_IP_MULTI_ACCOUNT | NEW_ACCOUNT_LAST_SECOND | ...
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
```

- [ ] **Step 8: Append the verification block (1 table)**

```sql

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
```

- [ ] **Step 9: Append the ALTER TABLE for the deferred V1 FK**

```sql

-- ============================================================================
-- Deferred FK from V1: wire up auctions.realty_group_id → realty_groups(id)
-- now that the realty_groups table exists.
-- ============================================================================
ALTER TABLE auctions
  ADD CONSTRAINT auctions_realty_group_id_fkey
  FOREIGN KEY (realty_group_id) REFERENCES realty_groups(id);
```

- [ ] **Step 10: Update DESIGN.md §7 — add the convention header note**

Open `docs/initial-design/DESIGN.md`. Find the section header line `## 7. Database Schema (Key Entities)` (around line 1068). **Immediately after** that header line and before any subsection, add this note as a new paragraph:

```markdown
> **Convention note:** Status, role, type, reason, and similar small-fixed-set columns are declared as `VARCHAR(N)` with inline `-- enum: A | B | C` doc comments. Postgres `CREATE TYPE ... AS ENUM` is intentionally avoided across this schema because `ALTER TYPE ADD VALUE` cannot run inside a transaction and removing values is impossible. Validation lives at the application layer via Java enums mapped through JPA.
```

- [ ] **Step 11: Update DESIGN.md §7 — replace each supporting table block to match V2**

For each of the 15 tables created in Steps 2–8 above, find the corresponding `CREATE TABLE ...` block in DESIGN.md §7 and replace it with the **exact same SQL** that's in `V2__supporting_tables.sql`. The intent is that DESIGN.md §7 mirrors the migration verbatim.

The 15 table blocks to replace are:
1. `realty_groups` (DESIGN.md ~line 1110)
2. `realty_group_members` (~line 1129)
3. `realty_group_invitations` (~line 1142)
4. `realty_group_sl_groups` (~line 1156)
5. `bids` (~line 1295)
6. `proxy_bids` (~line 1308)
7. `escrow_transactions` (~line 1469)
8. `bot_accounts` (~line 1322)
9. `bot_tasks` (~line 1339)
10. `reviews` (~line 1362)
11. `cancellation_log` (~line 1383)
12. `listing_reports` (~line 1416 — note this is the `CREATE TABLE listing_reports (...)` block, NOT the `CREATE TYPE` blocks above it)
13. `bans` (~line 1437)
14. `fraud_flags` (~line 1453)
15. `verification_codes` (~line 1487)

For each table, the replacement should:
- Match the V2 SQL column-for-column (types, defaults, NOT NULL, CHECK constraints)
- Preserve the surrounding markdown (the section header `### tablename`, the enclosing ` ```sql ` fence)
- Include the same trailing `CREATE INDEX` lines that V2 has
- Include any inline comments that V2 has (the section-header banner comment lines like `-- ============================================================================` are NOT needed in DESIGN.md since each block already has a `### tablename` markdown heading; just include the `-- explanatory note` comments that document design decisions)

You do not need to update `users`, `parcels`, `auctions`, `parcel_tags`, or `auction_tags` blocks in DESIGN.md — those are V1's responsibility and are either already updated (users/parcels/auctions) or are spec-documented deviations that DESIGN.md intentionally still shows in their original form (parcel_tag enum + auction_tags-with-enum).

- [ ] **Step 12: Update DESIGN.md §7 — remove the report enum CREATE TYPE blocks**

Find this block in `docs/initial-design/DESIGN.md` (around line 1397, immediately above the `CREATE TABLE listing_reports` block):

```sql
CREATE TYPE report_reason AS ENUM (
    'INACCURATE_DESCRIPTION',
    'WRONG_TAGS',
    'SHILL_BIDDING',
    'FRAUDULENT_SELLER',
    'DUPLICATE_LISTING',
    'NOT_ACTUALLY_FOR_SALE',
    'TOS_VIOLATION',
    'OTHER'
);

CREATE TYPE report_status AS ENUM (
    'OPEN',
    'REVIEWED',
    'DISMISSED',
    'ACTION_TAKEN'
);

CREATE TABLE listing_reports (
```

Delete the two `CREATE TYPE` statements and the blank line between them, leaving the file with `CREATE TABLE listing_reports (` (which Step 11 will have already replaced with the V2 version of that table). The result should be:

```sql
CREATE TABLE listing_reports (
```

- [ ] **Step 13: Run the test suite**

Run from the `backend/` directory:

```bash
cd backend
./mvnw test
```

Expected: `BUILD SUCCESS`, 5 tests run, 0 failures, 0 errors. The relevant test is `BackendApplicationTests.contextLoads` — it spins up Spring against the dev profile's PostgreSQL, which causes Flyway to apply V1 then V2. Any SQL syntax error, FK reference to a missing table, or constraint violation in V2 surfaces as a `FlywayException` in the test failure trace.

If the test fails:
1. Read the Flyway error message carefully — it points to the offending SQL line in V2.
2. Fix the SQL in `V2__supporting_tables.sql`.
3. Re-run Task 1's reset steps (the failed migration left a row in `flyway_schema_history` that blocks re-application).
4. Re-run `./mvnw test`.

- [ ] **Step 14: Manual smoke check via psql**

Run each block below and confirm the expected output. Use `docker exec -i slpa-postgres psql -U slpa -d slpa` to connect, or pipe SQL via heredoc.

Confirm 21 tables exist (6 from V1 + 15 from V2):
```bash
docker exec -i slpa-postgres psql -U slpa -d slpa -c '\dt'
```
Expected: 21 rows including all of: `auctions`, `auction_tags`, `bans`, `bids`, `bot_accounts`, `bot_tasks`, `cancellation_log`, `escrow_transactions`, `flyway_schema_history`, `fraud_flags`, `listing_reports`, `parcel_tags`, `parcels`, `proxy_bids`, `realty_group_invitations`, `realty_group_members`, `realty_group_sl_groups`, `realty_groups`, `reviews`, `users`, `verification_codes`.

Confirm both migrations are applied:
```bash
docker exec -i slpa-postgres psql -U slpa -d slpa -c "SELECT version, description, success FROM flyway_schema_history ORDER BY version;"
```
Expected:
```
 version |    description     | success
---------+--------------------+---------
 1       | core tables        | t
 2       | supporting tables  | t
```

Confirm the deferred FK now exists on auctions:
```bash
docker exec -i slpa-postgres psql -U slpa -d slpa -c "\d auctions" | grep realty_group
```
Expected: a line containing `auctions_realty_group_id_fkey` and `(realty_group_id) REFERENCES realty_groups(id)`.

Test the verification_codes CHECK rejects bad codes:
```bash
docker exec -i slpa-postgres psql -U slpa -d slpa <<'SQL'
-- Need a user row first to satisfy the FK; insert a throwaway one.
INSERT INTO users (email, password_hash) VALUES ('smoketest@slpa.invalid', '!disabled') RETURNING id \gset
-- Should REJECT (5 chars)
INSERT INTO verification_codes (user_id, code, type, expires_at) VALUES (:id, '12345', 'PLAYER', NOW() + INTERVAL '1 day');
-- Should REJECT (7 chars)
INSERT INTO verification_codes (user_id, code, type, expires_at) VALUES (:id, '1234567', 'PLAYER', NOW() + INTERVAL '1 day');
-- Should REJECT (alphanumeric)
INSERT INTO verification_codes (user_id, code, type, expires_at) VALUES (:id, '12345a', 'PLAYER', NOW() + INTERVAL '1 day');
-- Should SUCCEED
INSERT INTO verification_codes (user_id, code, type, expires_at) VALUES (:id, '123456', 'PLAYER', NOW() + INTERVAL '1 day');
SQL
```
Expected: three errors mentioning `verification_codes_format`, then one successful INSERT.

Test the bans CHECK rejects empty-target bans:
```bash
docker exec -i slpa-postgres psql -U slpa -d slpa <<'SQL'
-- Reuse the smoketest user as the admin (banned_by FK)
SELECT id FROM users WHERE email = 'smoketest@slpa.invalid' \gset
-- Should REJECT (no ip_address, no sl_avatar_uuid)
INSERT INTO bans (ban_type, reason, banned_by) VALUES ('IP', 'test', :id);
-- Should SUCCEED
INSERT INTO bans (ban_type, ip_address, reason, banned_by) VALUES ('IP', '1.2.3.4'::inet, 'test', :id);
SQL
```
Expected: one error mentioning `bans_at_least_one_identifier`, then one successful INSERT.

Test the fraud_flags CHECK rejects empty-target flags:
```bash
docker exec -i slpa-postgres psql -U slpa -d slpa <<'SQL'
-- Should REJECT (no user_id, no auction_id)
INSERT INTO fraud_flags (flag_type) VALUES ('SMOKE_TEST');
-- Should SUCCEED (user_id satisfies the CHECK)
SELECT id FROM users WHERE email = 'smoketest@slpa.invalid' \gset
INSERT INTO fraud_flags (flag_type, user_id) VALUES ('SMOKE_TEST', :id);
SQL
```
Expected: one error mentioning `fraud_flags_at_least_one_target`, then one successful INSERT.

Test the bot_accounts partial unique index allows multiple WORKERs but only one PRIMARY:
```bash
docker exec -i slpa-postgres psql -U slpa -d slpa <<'SQL'
INSERT INTO bot_accounts (sl_uuid, sl_username, role) VALUES (gen_random_uuid(), 'worker_a', 'WORKER');
INSERT INTO bot_accounts (sl_uuid, sl_username, role) VALUES (gen_random_uuid(), 'worker_b', 'WORKER');
INSERT INTO bot_accounts (sl_uuid, sl_username, role) VALUES (gen_random_uuid(), 'primary_a', 'PRIMARY');
-- Should REJECT (second PRIMARY)
INSERT INTO bot_accounts (sl_uuid, sl_username, role) VALUES (gen_random_uuid(), 'primary_b', 'PRIMARY');
SQL
```
Expected: three successful INSERTs, then one error mentioning `idx_bot_primary` (or `unique constraint`).

Confirm the indexes added proactively all exist:
```bash
docker exec -i slpa-postgres psql -U slpa -d slpa -c "SELECT indexname FROM pg_indexes WHERE schemaname='public' AND indexname IN ('idx_invitations_user_status','idx_bids_bidder','idx_escrow_auction','idx_escrow_status','idx_verification_codes_user','idx_verification_codes_expires','idx_bot_tasks_status_scheduled') ORDER BY indexname;"
```
Expected: all 7 index names listed.

Cleanup the smoke-test rows so the dev DB stays clean for the next task:
```bash
docker exec -i slpa-postgres psql -U slpa -d slpa <<'SQL'
DELETE FROM fraud_flags WHERE flag_type = 'SMOKE_TEST';
DELETE FROM bans WHERE reason = 'test';
DELETE FROM verification_codes WHERE code = '123456';
DELETE FROM bot_accounts WHERE sl_username IN ('worker_a','worker_b','primary_a');
DELETE FROM users WHERE email = 'smoketest@slpa.invalid';
SQL
```

- [ ] **Step 15: Stage and commit**

Run from the repo root:

```bash
git add backend/src/main/resources/db/migration/V2__supporting_tables.sql docs/initial-design/DESIGN.md
git status
```

Expected `git status`: one new file (`V2__supporting_tables.sql`), one modified file (`DESIGN.md`). Nothing else.

Commit:

```bash
git commit -m "$(cat <<'EOF'
feat(db): add V2 supporting tables migration

Creates 15 supporting tables (realty groups, bidding, escrow, bot infra,
reviews, moderation, verification) and wires up the deferred FK from V1
on auctions.realty_group_id. Inherits all V1 conventions: TIMESTAMPTZ,
BIGINT for L$ money, TEXT for URLs, NOT NULL on defaults, VARCHAR-over-
ENUM with inline doc comments.

New CHECK constraints for cheap insurance:
- verification_codes.code must be 6 numeric digits
- bans must target at least one of (ip_address, sl_avatar_uuid)
- fraud_flags must target at least one of (user_id, auction_id)

DESIGN.md section 7 updated to match: header note documenting the
VARCHAR-over-ENUM convention, all 15 supporting table blocks rewritten
to mirror the migration, the report_reason and report_status CREATE TYPE
blocks removed.

Refs: docs/superpowers/specs/2026-04-10-flyway-supporting-migrations-design.md

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

If a pre-commit hook fails, fix the underlying issue, re-stage, and create a NEW commit (do not amend).

---

## Self-Review

**Spec coverage check** — every section of the spec is implemented in this plan:

| Spec section | Implemented in |
|---|---|
| Migration File Structure (single V2) | Task 2, Step 1 |
| `realty_groups` + 3 related tables | Task 2, Step 2 |
| `bids`, `proxy_bids` | Task 2, Step 3 |
| `escrow_transactions` (with COMMISSION SYSTEM-user note) | Task 2, Step 4 |
| `bot_accounts` (partial unique index) + `bot_tasks` (composite index replacing partial) | Task 2, Step 5 |
| `reviews` (with rating CHECK preserved) | Task 2, Step 6 |
| `cancellation_log`, `listing_reports`, `bans`, `fraud_flags` | Task 2, Step 7 |
| `verification_codes` with format CHECK | Task 2, Step 8 |
| Deferred FK ALTER on auctions | Task 2, Step 9 |
| DESIGN.md header note | Task 2, Step 10 |
| DESIGN.md table block updates | Task 2, Step 11 |
| DESIGN.md CREATE TYPE removals | Task 2, Step 12 |
| Verification via `BackendApplicationTests` | Task 2, Step 13 |
| Manual smoke checks (FK exists, format CHECKs, both at-least-one CHECKs, partial unique index, proactive indexes exist) | Task 2, Step 14 |
| Files Created/Modified | File Structure section above |
| Acceptance Criteria | Covered by Steps 13 + 14 |

**Placeholder scan:** None. Every step contains either complete SQL, an exact command, or an exact action. Step 11 references "the V2 SQL above" rather than re-pasting 15 table blocks — this is intentional (DRY) and is followed by an explicit list of which 15 tables to find in DESIGN.md, so the implementer has unambiguous targets.

**Type / name consistency:** Cross-checked column names, FK targets, and index names between the V2 SQL blocks and the smoke-check SQL. All match. Table list in Step 14's `\dt` expectation matches the union of V1 + V2 tables (21 total). Constraint names used in smoke checks (`verification_codes_format`, `bans_at_least_one_identifier`, `fraud_flags_at_least_one_target`, `idx_bot_primary`, `auctions_realty_group_id_fkey`) all match what's defined in the V2 SQL.
