# Flyway Baseline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Hibernate `ddl-auto: update` with Flyway-managed migrations (V1 baseline + `ddl-auto: validate`) so first prod deploy + every future schema change goes through versioned migrations instead of JPA inference.

**Architecture:** Generate the V1 baseline schema via `pg_dump --schema-only` against a clean dev DB that Hibernate has just created (so V1 captures exactly what Hibernate would have produced, plus the partial indexes / CHECK constraints that the runtime initializers add). Enable Flyway alongside `ddl-auto: update` first to verify coexistence, then switch Hibernate to `validate`. Existing initializer classes stay (they're idempotent + Flyway-compatible). Remove the two `*DevTouchUp` classes whose work the V1 baseline now covers.

**Tech Stack:** Spring Boot 4, Java 26, Flyway (`spring-boot-starter-flyway` + `flyway-database-postgresql`, both already in pom.xml), Postgres 17 (via docker-compose for dev), Hibernate JPA.

---

## Pre-flight context

Before starting, the engineer should know:

- **Existing pom.xml dependencies** — `spring-boot-starter-flyway`, `flyway-database-postgresql`, and `spring-boot-starter-flyway-test` are all present. **Do not add them.** The blocker is `spring.flyway.enabled: false` in `application.yml:34`.
- **Initializer classes that apply schema features Hibernate cannot:** `ParcelLockingIndexInitializer`, `ProxyBidPartialUniqueIndexInitializer`, `SlImCoalesceIndexInitializer`, `NotificationCoalesceIndexInitializer`, `NotificationCategoryCheckConstraintInitializer`, `AuctionStatusCheckConstraintInitializer`, `BotTaskTypeCheckConstraintInitializer`, `BotTaskStatusCheckConstraintInitializer`, `EscrowTransactionTypeCheckConstraintInitializer`, `WithdrawalStatusCheckConstraintInitializer`, `ReconciliationStatusCheckConstraintInitializer`, `AdminActionTypeCheckConstraintInitializer`, `FraudFlagReasonCheckConstraintInitializer`. These keep running — V1 captures their current output, they no-op on subsequent boots.
- **`*DevTouchUp` classes to remove:** `backend/src/main/java/com/slparcelauctions/backend/auction/dev/AuctionTitleDevTouchUp.java`, `backend/src/main/java/com/slparcelauctions/backend/parcel/dev/MaturityRatingDevTouchUp.java`. The V1 baseline already has the columns these classes were patching, so they're dead code after this change.
- **`AdminBootstrapInitializer` and `ParcelTagService` data seeds** — these seed *data* (admin users, parcel tags), not schema. They keep running. `ddl-auto: validate` doesn't care about data.
- **Spring Boot test profile interactions** — many integration tests use `@TestPropertySource` to disable schedulers. None should reference Flyway state today (it was disabled), but if any test fails because Flyway tries to baseline against an empty test DB, set `spring.flyway.baseline-on-migrate: true` in the test config.

## Files touched by this plan

**Create:**
- `backend/src/main/resources/db/migration/V1__initial_schema.sql` — captured baseline schema

**Modify:**
- `backend/src/main/resources/application.yml` — enable Flyway, switch `ddl-auto: update` → `validate` (in the second config-flip task)
- `backend/src/main/resources/application-dev.yml` — switch `ddl-auto: update` → `validate` (the dev profile overrides the base)
- `backend/src/main/resources/application-prod.yml` — already has `baseline-on-migrate: true`; no changes
- `docs/implementation/CONVENTIONS.md` — replace "schema changes via JPA `ddl-auto`" with "schema changes via new Flyway migration"
- `FULL_TESTING_PROCEDURES.md` — note Flyway in §3 hot-reload semantics + §11 gotchas
- `docs/implementation/DEFERRED_WORK.md` — remove "Auction.title NOT NULL backfill on first production deploy" entry (resolved)
- `CLAUDE.md` — update "Backend Stack Details" line about migrations from JPA to Flyway

**Delete:**
- `backend/src/main/java/com/slparcelauctions/backend/auction/dev/AuctionTitleDevTouchUp.java`
- `backend/src/main/java/com/slparcelauctions/backend/parcel/dev/MaturityRatingDevTouchUp.java`
- Any associated test files for the above

---

## Task 1: Branch + baseline state confirmation

**Files:**
- None (workflow setup)

- [ ] **Step 1: Create feature branch off `dev`**

```powershell
git checkout dev
git pull
git checkout -b feat/flyway-baseline
```

- [ ] **Step 2: Confirm test suite is green on baseline `dev`**

Run from repo root:
```powershell
cd backend
.\mvnw test
```

Expected: `BUILD SUCCESS`. If failures already exist on `dev`, halt and report — don't proceed with Flyway changes on top of an already-broken baseline.

- [ ] **Step 3: Confirm dev stack boots cleanly via Compose**

```powershell
cd ..
docker compose down -v
docker compose up -d postgres redis minio
docker compose up -d backend
```

Wait ~60s for backend healthcheck. Then:
```powershell
curl http://localhost:8080/api/v1/health
```

Expected: HTTP 200. If not, halt and report — broken baseline.

---

## Task 2: Generate V1 baseline schema

**Files:**
- Create: `backend/src/main/resources/db/migration/V1__initial_schema.sql`

The strategy: Hibernate has just created the schema fresh in Task 1 step 3 (because we dropped the volume). Plus all initializer classes have run. So the current Postgres schema is exactly what Flyway needs to capture.

- [ ] **Step 1: Verify current schema is the freshly-created one (sanity check)**

```powershell
docker compose exec -T postgres psql -U slpa -d slpa -c "\dt" | head -20
```

Expected: dozens of tables listed (`auctions`, `bids`, `users`, `parcels`, `escrows`, `bot_tasks`, etc.). If empty, halt — Hibernate didn't create the schema; check backend logs.

- [ ] **Step 2: Verify partial indexes from initializers are present**

```powershell
docker compose exec -T postgres psql -U slpa -d slpa -c "\di uq_*"
```

Expected: at least `uq_auctions_parcel_locked_status` and `uq_proxy_bids_active_per_bidder` (the two partial-unique indexes the initializers create). If missing, the initializers haven't run yet — wait another 30s and re-check, or check backend logs for initializer errors.

- [ ] **Step 3: Verify CHECK constraints from initializers are present**

```powershell
docker compose exec -T postgres psql -U slpa -d slpa -c "\d auctions" | grep -i check
```

Expected: at least one CHECK constraint visible on the `status` column. If missing, `AuctionStatusCheckConstraintInitializer` didn't run — same fix as step 2.

- [ ] **Step 4: Dump schema to V1**

Create the directory first:
```powershell
mkdir backend\src\main\resources\db\migration
```

Then dump (PowerShell):
```powershell
docker compose exec -T postgres pg_dump --schema-only --no-owner --no-acl --no-privileges -U slpa slpa > backend\src\main\resources\db\migration\V1__initial_schema.sql
```

- [ ] **Step 5: Inspect V1 — manual sanity check**

Open `backend/src/main/resources/db/migration/V1__initial_schema.sql`. Verify:
- Top of file has `SET` statements (search_path, etc.) — leave them
- `CREATE TABLE` statements for the major entities (`users`, `auctions`, `parcels`, `escrows`, `bot_tasks`, `bids`, `proxy_bids`, etc.)
- `CREATE INDEX` statements including the partial uniques (`uq_auctions_parcel_locked_status WHERE status IN ('VERIFICATION_PENDING', 'ACTIVE')` and `uq_proxy_bids_active_per_bidder WHERE status = 'ACTIVE'`)
- `CHECK` constraints on enum-backed columns
- No `OWNER TO` lines (we passed `--no-owner`)
- No `GRANT/REVOKE` lines (we passed `--no-privileges`)

If any of those expected elements is missing, the initializer hadn't actually run before the dump — restart the backend container, wait, re-dump.

- [ ] **Step 6: Strip Postgres-version-specific noise from V1 (optional but recommended)**

Some `pg_dump` headers include lines like `-- Dumped from database version 17.x` and `-- Dumped by pg_dump version ...`. These are comments only; leaving them is fine. Do NOT strip the `SET` statements or any DDL.

- [ ] **Step 7: Commit V1 baseline (Flyway not yet enabled — this is just the file)**

```powershell
git add backend/src/main/resources/db/migration/V1__initial_schema.sql
git commit -m "feat(backend): capture Flyway V1 baseline schema from current dev DB"
```

---

## Task 3: Enable Flyway, keep `ddl-auto: update` (coexistence test)

**Files:**
- Modify: `backend/src/main/resources/application.yml:33-34`

The goal of this task is to verify Flyway and Hibernate-update coexist without conflict before flipping Hibernate to `validate`. If V1 is correct, Flyway should baseline at version 1, run no migrations, then Hibernate's `update` should be a no-op (schema already matches).

- [ ] **Step 1: Edit `application.yml` to enable Flyway**

Change lines 33-34:

```yaml
  flyway:
    enabled: false
```

to:

```yaml
  flyway:
    enabled: true
    baseline-on-migrate: true   # required: existing dev DBs already have the schema, so Flyway must baseline rather than try to run V1 against a populated DB
    baseline-version: 0          # explicitly baseline at 0 so V1 runs as a real migration on fresh DBs but is treated as already-applied on existing dev DBs
```

Wait — re-read: with `baseline-on-migrate: true` and `baseline-version: 0`, on an *existing populated* DB the first run inserts a baseline row at version 0, and V1 will then attempt to execute against the populated schema (which would fail with "table already exists"). The correct value depends on whether V1 should be considered "already applied" to existing dev DBs.

Use this instead — `baseline-version: 1`, which marks V1 as already-applied on existing dev DBs, and on fresh DBs V1 still runs because there's no schema history table yet:

```yaml
  flyway:
    enabled: true
    baseline-on-migrate: true
    baseline-version: 1
```

Actually re-read the Flyway semantics one more time:
- On a DB with NO `flyway_schema_history` table:
  - If `baseline-on-migrate: true`: Flyway creates the history table, inserts a baseline row at `baseline-version`, then runs all migrations *with version > baseline-version*.
  - With `baseline-version: 1`: V1 is skipped (not > 1), so Flyway treats existing dev DBs as up-to-date.
- On a fresh DB with no schema *and* no history table:
  - Flyway creates the history table, baselines at version 1, then skips V1. **The schema never gets created.** Bug.

Correct shape for the "baseline a populated DB / migrate a fresh DB" mixed case: use `baseline-on-migrate: true` + `baseline-version: 0`, AND ensure the V1 SQL is idempotent (uses `CREATE TABLE IF NOT EXISTS`, etc.). But `pg_dump` output is NOT idempotent.

The cleanest answer is to **manually baseline existing dev DBs** at the right version before deploying, and let fresh DBs run V1 normally. For SLPA:
- Local dev (everyone's machine): drop the docker volume, let Flyway run V1 on the empty DB
- Prod: empty DB at first deploy, V1 runs once, history table created
- Future migrations: V2, V3, etc. apply normally

So the simpler `baseline-on-migrate: true` (default version 1) is wrong for the mixed case. Use:

```yaml
  flyway:
    enabled: true
    baseline-on-migrate: true   # safety net for any existing dev DB someone forgets to wipe
```

And the workflow becomes: every dev must `docker compose down -v` once during this PR review. Fresh DBs are now the standard. Document this in CONVENTIONS.

- [ ] **Step 2: Verify the file edit**

Run:
```powershell
git diff backend/src/main/resources/application.yml
```

Expected diff: `enabled: false` → `enabled: true` plus the new `baseline-on-migrate: true` line. No other changes.

- [ ] **Step 3: Drop volume + cold-boot to test V1 application**

```powershell
docker compose down -v
docker compose up -d postgres redis minio
docker compose up -d backend
```

Wait ~90s (Flyway adds maybe 5-10s to startup on V1 application).

- [ ] **Step 4: Verify Flyway ran V1**

```powershell
docker compose exec -T postgres psql -U slpa -d slpa -c "SELECT * FROM flyway_schema_history;"
```

Expected: at least one row with `version = 1`, `description = initial schema`, `success = true`. If missing the row, check backend logs:
```powershell
docker compose logs backend | grep -i flyway
```

- [ ] **Step 5: Verify Hibernate `update` is a no-op (schema unchanged)**

```powershell
curl http://localhost:8080/api/v1/health
```

Expected: HTTP 200. The backend booted, meaning Hibernate didn't try to create or alter anything (the schema from V1 already matches the entity model). If the backend failed to boot with a Hibernate error, V1 doesn't fully match the entity model — diagnose the diff.

- [ ] **Step 6: Run the full test suite**

```powershell
cd backend
.\mvnw test
```

Expected: `BUILD SUCCESS`. Same count as Task 1 step 2. If failures appear:
- Most likely failure: an `@SpringBootTest` test was implicitly relying on `ddl-auto: create-drop` semantics. Look for tests that drop/recreate tables manually — they may now conflict with Flyway's history table.
- Resolve case-by-case. Most should "just work" because the test profile defaults to H2 in many cases or uses TestContainers (check `pom.xml`).

- [ ] **Step 7: Commit Flyway-enabled config**

```powershell
git add backend/src/main/resources/application.yml
git commit -m "feat(backend): enable Flyway with baseline-on-migrate while keeping ddl-auto: update"
```

---

## Task 4: Switch Hibernate to `validate`

This is the load-bearing change — Hibernate stops modifying the schema and instead validates that the schema matches the entity model on startup. Failure to validate means hard boot failure.

**Files:**
- Modify: `backend/src/main/resources/application.yml:31-32`
- Modify: `backend/src/main/resources/application-dev.yml:11-12`

- [ ] **Step 1: Edit `application.yml` — change `ddl-auto: update` to `ddl-auto: validate`**

Change line 32:

```yaml
  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: update
```

to:

```yaml
  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: validate
```

- [ ] **Step 2: Edit `application-dev.yml` — same change**

Change lines 11-12:

```yaml
  jpa:
    hibernate:
      ddl-auto: update
```

to:

```yaml
  jpa:
    hibernate:
      ddl-auto: validate
```

- [ ] **Step 3: Verify both edits**

```powershell
git diff backend/src/main/resources/application.yml backend/src/main/resources/application-dev.yml
```

Expected: two `update` → `validate` changes, nothing else.

- [ ] **Step 4: Restart backend (don't drop volume — V1 already applied + history exists)**

```powershell
docker compose restart backend
```

Wait ~30s.

- [ ] **Step 5: Verify backend boots cleanly with `validate`**

```powershell
curl http://localhost:8080/api/v1/health
```

Expected: HTTP 200. If Hibernate validate fails, the error in `docker compose logs backend` will say something like:
```
Schema validation: missing column [<table>.<column>]
```
That means V1 doesn't include a column an entity declares. Diagnose:
- Re-dump the schema (V1 generation in Task 2) and compare against the entity in question
- Most common culprit: a `@Column` annotation that was added after the dev DB was last `down -v`d, so the dev DB doesn't have it. Fix: re-do Task 2 from a fully-fresh DB so V1 captures the latest entity state.

- [ ] **Step 6: Run the full test suite**

```powershell
cd backend
.\mvnw test
```

Expected: `BUILD SUCCESS`. If new failures appear here that weren't there in Task 3 step 6, they're caused by the `validate` switch — same diagnostic as step 5 but per-test.

- [ ] **Step 7: Commit the switch**

```powershell
git add backend/src/main/resources/application.yml backend/src/main/resources/application-dev.yml
git commit -m "feat(backend): switch Hibernate from ddl-auto: update to validate"
```

---

## Task 5: Cold-boot regression test

Verify the full happy-path: a brand-new Postgres + Flyway-driven schema creation works end-to-end.

**Files:**
- None (verification only)

- [ ] **Step 1: Drop everything**

```powershell
docker compose down -v
```

- [ ] **Step 2: Boot full stack**

```powershell
docker compose up -d
```

Wait ~90s.

- [ ] **Step 3: Verify health endpoints**

```powershell
curl http://localhost:8080/api/v1/health
curl http://localhost:3000
```

Both should return 200/HTML.

- [ ] **Step 4: Verify Flyway ran V1 on the new DB**

```powershell
docker compose exec -T postgres psql -U slpa -d slpa -c "SELECT version, description, success, installed_on FROM flyway_schema_history ORDER BY installed_rank;"
```

Expected: one row with `version = 1`, `success = true`, `installed_on` = a few seconds ago.

- [ ] **Step 5: Run a smoke API call (auth flow)**

```powershell
curl -i -X POST http://localhost:8080/api/v1/auth/register -H "Content-Type: application/json" -d "{\"email\":\"flyway-smoke@example.com\",\"password\":\"smoketestpass1!\",\"displayName\":\"Smoke\"}"
```

Expected: HTTP 201 with a JSON body containing `accessToken`. If 500, check `docker compose logs backend` for the actual error.

- [ ] **Step 6: Verify integration tests still pass against the cold-booted DB**

```powershell
cd backend
.\mvnw test
```

Expected: `BUILD SUCCESS`.

---

## Task 6: Remove `AuctionTitleDevTouchUp`

The TouchUp class backfilled `auctions.title` with a placeholder where it was NULL — but the V1 baseline already includes `title VARCHAR(120) NOT NULL`, so this class can never run productively (the column is non-null at insert time).

**Files:**
- Delete: `backend/src/main/java/com/slparcelauctions/backend/auction/dev/AuctionTitleDevTouchUp.java`
- Search for: any test files referencing the class

- [ ] **Step 1: Search for tests referencing AuctionTitleDevTouchUp**

```powershell
cd backend
git grep -l "AuctionTitleDevTouchUp" src/
```

Expected: only `src/main/java/com/slparcelauctions/backend/auction/dev/AuctionTitleDevTouchUp.java`. If a test file appears, note its path for Step 3.

- [ ] **Step 2: Delete the class**

```powershell
git rm src/main/java/com/slparcelauctions/backend/auction/dev/AuctionTitleDevTouchUp.java
```

- [ ] **Step 3: Delete associated tests if any**

If Step 1 found a test file like `AuctionTitleDevTouchUpTest.java`, delete it:

```powershell
git rm <path-from-step-1>
```

- [ ] **Step 4: Verify the package directory isn't now empty**

```powershell
ls src/main/java/com/slparcelauctions/backend/auction/dev/
```

Expected: at least `DevAuctionEndController.java` remains. If the directory is now empty, leave it (Maven won't care; future dev controllers go here).

- [ ] **Step 5: Compile + test**

```powershell
.\mvnw test
```

Expected: `BUILD SUCCESS`. Failures here indicate something else referenced this class — `git grep` again to find it.

- [ ] **Step 6: Commit**

```powershell
git add -A
git commit -m "refactor(backend): remove AuctionTitleDevTouchUp (V1 Flyway baseline supersedes it)"
```

---

## Task 7: Remove `MaturityRatingDevTouchUp`

Same pattern as Task 6 but for the parcel package's TouchUp.

**Files:**
- Delete: `backend/src/main/java/com/slparcelauctions/backend/parcel/dev/MaturityRatingDevTouchUp.java`
- Search for: any test files referencing the class

- [ ] **Step 1: Search for tests referencing MaturityRatingDevTouchUp**

```powershell
cd backend
git grep -l "MaturityRatingDevTouchUp" src/
```

Expected: only the source file. If a test file appears, note its path.

- [ ] **Step 2: Delete the class**

```powershell
git rm src/main/java/com/slparcelauctions/backend/parcel/dev/MaturityRatingDevTouchUp.java
```

- [ ] **Step 3: Delete associated tests if any**

```powershell
git rm <path-from-step-1>   # if applicable
```

- [ ] **Step 4: Check if the parcel/dev package directory is now empty**

```powershell
ls src/main/java/com/slparcelauctions/backend/parcel/dev/ 2>&1
```

If the directory is now empty, remove it:
```powershell
rmdir src/main/java/com/slparcelauctions/backend/parcel/dev/
```

- [ ] **Step 5: Compile + test**

```powershell
.\mvnw test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 6: Commit**

```powershell
git add -A
git commit -m "refactor(backend): remove MaturityRatingDevTouchUp (V1 Flyway baseline supersedes it)"
```

---

## Task 8: Update CONVENTIONS.md

The current convention says "schema changes via JPA `ddl-auto`." Update to Flyway.

**Files:**
- Modify: `docs/implementation/CONVENTIONS.md`

- [ ] **Step 1: Find the relevant section**

```powershell
cd ..
git grep -n "ddl-auto\|JPA entities" docs/implementation/CONVENTIONS.md
```

Expected: one or more matches with line numbers. Note the section heading.

- [ ] **Step 2: Replace the convention with Flyway-based language**

Open `docs/implementation/CONVENTIONS.md`. Find the line(s) about `ddl-auto`. Replace with:

```markdown
**Database schema changes go through Flyway migrations**, not JPA `ddl-auto`. Add a new file at `backend/src/main/resources/db/migration/V<N>__<short_description>.sql` where `<N>` is the next sequential integer. Spring Boot runs Flyway at startup before the app accepts requests; failed migrations halt boot.

Hibernate runs in `validate` mode in all profiles — entity changes that drift from the migration set will fail to start the application. Add the migration in the same commit as the entity change.

Runtime initializer classes (e.g. `ParcelLockingIndexInitializer`, `*CheckConstraintInitializer`) handle schema features Hibernate cannot represent (partial unique indexes, JSONB GIN indexes, CHECK constraints for enums). They run idempotently after Hibernate validation; they are *not* a substitute for migrations.
```

- [ ] **Step 3: Verify the diff**

```powershell
git diff docs/implementation/CONVENTIONS.md
```

Expected: old `ddl-auto`-based convention removed; new Flyway-based convention added.

- [ ] **Step 4: Commit**

```powershell
git add docs/implementation/CONVENTIONS.md
git commit -m "docs(conventions): document Flyway-based schema migration workflow"
```

---

## Task 9: Update FULL_TESTING_PROCEDURES.md

The local-dev workflow now requires `docker compose down -v` to apply the V1 baseline cleanly. Document this in the gotchas table.

**Files:**
- Modify: `FULL_TESTING_PROCEDURES.md`

- [ ] **Step 1: Find the §11 Common gotchas table**

```powershell
git grep -n "Common gotchas" FULL_TESTING_PROCEDURES.md
```

Expected: one match. Note the line number.

- [ ] **Step 2: Add a new row to the gotchas table**

Open `FULL_TESTING_PROCEDURES.md`. Find the gotchas table. Add a new row at the bottom:

```markdown
| Backend won't boot after pulling Flyway-baseline branch | Hibernate `validate` reports "missing column" or similar | First time on the Flyway-managed branch, drop the dev DB volume so Flyway runs V1 on a fresh schema: `docker compose down -v && docker compose up -d`. The pre-Flyway dev DB shape doesn't have the `flyway_schema_history` table; baseline-on-migrate handles re-pulls but a clean wipe is the cleanest first run. |
```

- [ ] **Step 3: Verify the diff**

```powershell
git diff FULL_TESTING_PROCEDURES.md
```

- [ ] **Step 4: Commit**

```powershell
git add FULL_TESTING_PROCEDURES.md
git commit -m "docs(testing): document Flyway baseline cold-boot gotcha"
```

---

## Task 10: Resolve `DEFERRED_WORK.md` entries

The Flyway work resolves the `Auction.title NOT NULL backfill on first production deploy` deferred entry.

**Files:**
- Modify: `docs/implementation/DEFERRED_WORK.md`

- [ ] **Step 1: Find the entry**

```powershell
git grep -n "Auction.title NOT NULL backfill" docs/implementation/DEFERRED_WORK.md
```

Expected: one match. Note the start and end lines of the entry (the `### ` heading + a few following lines).

- [ ] **Step 2: Remove the entry**

Open `docs/implementation/DEFERRED_WORK.md`. Delete the entry block (the `### Auction.title NOT NULL backfill on first production deploy (Epic 07)` heading and all bullets under it, up to but not including the next `### ` heading). Per the file's own removal rule (line 12): "When finishing a sub-spec that completes a deferred item, remove the entry."

- [ ] **Step 3: Update `CLEANED_FROM_DEFERRED.md` to record the resolution**

Open `docs/implementation/CLEANED_FROM_DEFERRED.md`. Add a new entry under "Removed entries":

```markdown
### Auction.title NOT NULL backfill on first production deploy
- **Originating epic/task:** Epic 07 sub-spec 1 (Task 2)
- **Implementation:**
  - Resolved by Flyway baseline migration `backend/src/main/resources/db/migration/V1__initial_schema.sql`, which captures the `title VARCHAR(120) NOT NULL` column definition. First prod deploy applies V1 against an empty DB; no manual `ALTER TABLE` required.
  - `AuctionTitleDevTouchUp.java` deleted (its work is now part of V1).
- **Notes:** First-deploy snapshot still required per AWS deployment design §4.5 (CI step), but the schema migration itself is now version-controlled.
```

- [ ] **Step 4: Verify both diffs**

```powershell
git diff docs/implementation/DEFERRED_WORK.md docs/implementation/CLEANED_FROM_DEFERRED.md
```

Expected: deferred-work entry removed; cleaned-from-deferred entry added.

- [ ] **Step 5: Commit**

```powershell
git add docs/implementation/DEFERRED_WORK.md docs/implementation/CLEANED_FROM_DEFERRED.md
git commit -m "docs(deferred): resolve Auction.title NOT NULL backfill via Flyway baseline"
```

---

## Task 11: Update CLAUDE.md backend stack notes

The "Backend Stack Details" section currently reads "Database migrations: Flyway (SQL-based, not Java)" — but the README + practice was JPA `ddl-auto`. Now reality matches the doc. Verify and tighten the line.

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Check the current wording**

```powershell
git grep -n "Database migrations" CLAUDE.md
```

Expected: one match. Read the line.

- [ ] **Step 2: If the line already says "Flyway (SQL-based, not Java)" — leave it**

Just confirm with the engineer that it's accurate now (it is). No edit.

- [ ] **Step 3: If the line says anything other than Flyway — replace with**

```markdown
- **Database migrations**: Flyway (SQL-based, not Java) — files in `backend/src/main/resources/db/migration/` named `V<N>__description.sql`. Hibernate runs in `validate` mode; entity changes require a paired migration.
```

- [ ] **Step 4: Verify**

```powershell
git diff CLAUDE.md
```

- [ ] **Step 5: Commit (only if there was a change)**

```powershell
git add CLAUDE.md
git commit -m "docs(claude): clarify Flyway migration workflow + validate mode"
```

---

## Task 12: Final integration verification

End-to-end verification before opening PR.

**Files:**
- None (verification only)

- [ ] **Step 1: Confirm test suite is green**

```powershell
cd backend
.\mvnw test
```

Expected: `BUILD SUCCESS`. Note the test count for the PR description.

- [ ] **Step 2: Confirm cold-boot still works**

```powershell
cd ..
docker compose down -v
docker compose up -d
```

Wait ~90s. Then:

```powershell
curl http://localhost:8080/api/v1/health
docker compose exec -T postgres psql -U slpa -d slpa -c "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank;"
```

Expected: health 200; one row in history with version=1, success=true.

- [ ] **Step 3: Confirm frontend smoke**

```powershell
curl -I http://localhost:3000
```

Expected: HTTP 200.

- [ ] **Step 4: Run the verify guards (frontend)**

```powershell
cd frontend
npm run verify
```

Expected: `All UI primitives have sibling test files.` and no hex-color/dark-variant/inline-style violations. Frontend isn't touched by this plan, so this should pass — running it confirms no regression.

- [ ] **Step 5: Final commit (if any docs sweep changes appeared)**

```powershell
cd ..
git status
```

If clean, proceed to PR. If anything is uncommitted, commit it with a clear message.

---

## Task 13: Open PR

**Files:**
- None (Git workflow)

- [ ] **Step 1: Push branch**

```powershell
git push -u origin feat/flyway-baseline
```

- [ ] **Step 2: Open PR via gh CLI**

```powershell
gh pr create --base dev --title "feat(backend): Flyway baseline + Hibernate validate mode" --body @-
```

When prompted (or use a here-doc), paste:

```markdown
## Summary

- Capture current dev DB schema as `V1__initial_schema.sql` (Flyway baseline)
- Enable Flyway in `application.yml` with `baseline-on-migrate: true`
- Switch Hibernate `ddl-auto` from `update` to `validate` in all profiles
- Remove `AuctionTitleDevTouchUp` and `MaturityRatingDevTouchUp` (their work is in V1)
- Update CONVENTIONS, FULL_TESTING_PROCEDURES, CLAUDE.md, DEFERRED_WORK to reflect the new workflow

This is the database half of the AWS deployment work (see `docs/superpowers/specs/2026-04-29-aws-deployment-design.md` §4.11). First prod deploy is now safe — no risk of `ddl-auto: update` blowing up on a NOT NULL column add against a populated DB.

## Test plan

- [ ] `cd backend && .\mvnw test` — full suite passes
- [ ] `docker compose down -v && docker compose up -d` — cold boot works; Flyway runs V1
- [ ] `curl http://localhost:8080/api/v1/health` returns 200
- [ ] `flyway_schema_history` shows one row at version 1, success=true
- [ ] `cd frontend && npm run verify` — no regressions
```

- [ ] **Step 3: Confirm PR URL**

`gh pr create` returns the PR URL on success. Capture it.

- [ ] **Step 4: STOP — handoff to user**

Post in the conversation: "**STOP — Flyway PR opened at `<URL>`. Ready for review.**" Wait for review/merge before moving to the next deploy step.

---

## Self-review notes

**Spec coverage:** Plan implements everything in `2026-04-29-aws-deployment-design.md` §4.11 (the Flyway in-scope subsection): Add Flyway dependency (already present, noted in pre-flight), generate baseline migration (Task 2), update `application.yml` to `validate` (Task 4), confirm `baseline-on-migrate: true` in prod (already in `application-prod.yml`, mentioned in pre-flight), remove TouchUp classes (Tasks 6 + 7), update CONVENTIONS (Task 8). All covered.

**Type consistency:** No new types introduced. File paths cross-referenced and consistent across tasks. Migration filename `V1__initial_schema.sql` used identically in all 13 tasks.

**Placeholder scan:** No "TBD" / "TODO" in actionable text. Two placeholders are intentional and explicit:
- Task 6 step 3 / Task 7 step 3: `<path-from-step-1>` — the engineer fills in the path discovered in their grep output. Acceptable because the value depends on the codebase state at execution time.
- Task 11 step 3: provides the exact replacement text *if* the line says something other than Flyway. Conditional, with the literal replacement provided.

**Open considerations for the engineer:**
- Task 3 step 6 / Task 4 step 6: If integration tests fail after enabling Flyway, the most likely cause is `@SpringBootTest` tests that previously relied on `ddl-auto: create-drop` semantics. None are expected (the config used `update`, not `create-drop`), but if surprises appear, fix in a Task-3.5 or Task-4.5 micro-task.
- Task 2 step 5: V1 manual sanity check is the gating step. If V1 is wrong, every downstream task fails. Spend the time here.
