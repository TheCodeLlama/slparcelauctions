# Epic 06 — SL Bot Service Testing Guide

End-to-end testing guide for the bot service shipped in PR #23 / commit `a13e298`. Covers automated test suites, local stack bring-up, and manual verification of every Epic 06 surface — backend bot API, bot worker login + teleport + callbacks, lifecycle-hooked monitor rows, and SL integration.

> This doc assumes you are running on the `dev` branch (Epic 06 already merged) with local Postgres, Redis, MinIO, backend, frontend, and one bot container.

---

## 1. Overview — What Epic 06 Adds

| Surface                      | What to test                                                                                                                      |
|------------------------------|-----------------------------------------------------------------------------------------------------------------------------------|
| Backend `/api/v1/bot/**`     | Bearer-auth gate, `POST /claim` atomic `SKIP LOCKED`, `PUT /{id}/verify`, `POST /{id}/monitor`, `GET /pending` (debug)            |
| `bot_tasks` table            | New columns (`next_run_at`, `expected_*`, `escrow_id`, etc.), `MONITOR_AUCTION` / `MONITOR_ESCROW` task types, `CANCELLED` status |
| Schedulers                   | `BotTaskTimeoutJob.sweepPending` (48h PENDING timeout) + new `sweepInProgress` (20m IN_PROGRESS re-arm)                           |
| `BotMonitorDispatcher`       | Eight `MonitorOutcome` values → suspension / freeze / confirmTransfer / markReviewRequired                                        |
| `BotMonitorLifecycleService` | Auto-create + auto-cancel monitor rows on auction and escrow lifecycle transitions                                                |
| C# bot worker                | Login, reconnect backoff, teleport + `ParcelProperties` read, handlers, claim loop                                                |
| Bot container                | Dockerfile, `docker-compose.yml` `bot-1` block, healthcheck, restart-on-crash                                                     |
| CI                           | `.github/workflows/bot-ci.yml` on PRs touching `bot/**`                                                                           |

---

## 2. Prerequisites

### 2.1 Required software

| Tool               | Version                                 | Where used                                                   |
|--------------------|-----------------------------------------|--------------------------------------------------------------|
| Git                | any                                     | Clone + worktree                                             |
| Docker Desktop     | 4.x + Compose v2                        | Local stack (Postgres, Redis, MinIO, backend, frontend, bot) |
| Java JDK           | 26 (or 21+)                             | `mvnw` builds the backend                                    |
| Node.js            | 20+ / 22+ LTS                           | Frontend (`npm run dev`)                                     |
| .NET SDK           | 8.0.x (or 9.x — cross-targets `net8.0`) | Bot worker `dotnet test`, `dotnet run`                       |
| GitHub CLI (`gh`)  | 2.x                                     | PR status, merging                                           |
| `curl` or `httpie` | any                                     | Manual API pokes                                             |
| Postman (optional) | Desktop 10.x                            | `SLPA` collection covers every endpoint (see §11)            |

### 2.2 Second Life accounts (pre-provisioned)

Per the user's setup, these scripted-agent accounts already exist:

| Account                 | Role                    | Logs in?              | UUID source                                       |
|-------------------------|-------------------------|-----------------------|---------------------------------------------------|
| `SLPAEscrow Resident`   | Primary escrow sentinel | **Never** — UUID only | Check in-world via `llKey2Name` or viewer profile |
| `SLPABot1 Resident`     | Worker bot              | Yes — dev + prod      | SL viewer → profile → UUID                        |
| `SLPABot2` … `SLPABot5` | Additional workers      | Yes — scale out       | Same                                              |

> Each bot account's password and UUID go into `bot/.env.bot-N` — never commit these files.

### 2.3 Repo layout

```
slpa/
├── backend/         Java / Spring Boot 4
├── frontend/        Next.js 16 / React 19
├── bot/             .NET 8 / LibreMetaverse (Epic 06)
├── docs/            You are here
└── docker-compose.yml
```

---

## 3. Initial Setup (first time only)

### 3.1 Clone + branch

```bash
git clone https://github.com/TheCodeLlama/slparcelauctions.git slpa
cd slpa
git checkout dev
git pull
```

### 3.2 Bot credentials file

Copy the template and fill in real SL credentials:

```bash
cp bot/.env.example bot/.env.bot-1
```

Edit `bot/.env.bot-1`:

```
SLPA_BOT_USERNAME=SLPABot1 Resident
SLPA_BOT_PASSWORD=<real password>
SLPA_BOT_UUID=<SLPABot1's avatar UUID>
SLPA_BOT_START_LOCATION=last
SLPA_BOT_SHARED_SECRET=<same value as backend slpa.bot.shared-secret>
```

Verify gitignore coverage (the file should NOT appear in `git status`):

```bash
git check-ignore bot/.env.bot-1
# expected output: bot/.env.bot-1
```

### 3.3 Shared secret alignment

The bot's `Backend__SharedSecret` MUST match the backend's `slpa.bot.shared-secret`. In dev the backend uses the placeholder `dev-bot-shared-secret` (see `backend/src/main/resources/application-dev.yml`). So for local dev only, set:

```
SLPA_BOT_SHARED_SECRET=dev-bot-shared-secret
```

For prod, rotate via env var on both sides; `BotStartupValidator` rejects the placeholder outside the `dev` profile.

### 3.4 Primary escrow UUID

In `dev` the backend uses the placeholder UUID `00000000-0000-0000-0000-000000000099`. For real SL integration testing, override with the actual SLPAEscrow UUID:

```bash
export SLPA_PRIMARY_ESCROW_UUID=<SLPAEscrow's real UUID>
```

The bot no longer needs this (removed as dead config in the final PR polish); only the backend reads it.

---

## 4. Automated Test Suites

Run these first — if any of them fail, stop and diagnose before doing manual testing.

### 4.1 Backend — full JUnit suite

```bash
cd backend
./mvnw test
```

**Expected:** `Tests run: 782, Failures: 0, Errors: 0` and `BUILD SUCCESS`.

Focus on the Epic 06 test classes if you want a faster subset:

```bash
./mvnw test -Dtest='Bot*,EscrowService*,SuspensionService*'
```

Key test classes to look at:
- `BotTaskClaimRaceIntegrationTest` — SKIP LOCKED concurrent claim.
- `BotTaskControllerAuthIntegrationTest` — bearer-auth gating.
- `BotMonitorDispatcherTest` — 14 cases covering every `(taskType, outcome)` cell.
- `BotMonitorLifecycleServiceTest` — hook creates + cancels monitor rows.
- `BotTaskTimeoutJobInProgressTest` — VERIFY fail vs MONITOR re-arm on IN_PROGRESS timeout.

### 4.2 Bot worker — C# xUnit suite

```bash
cd bot
dotnet test
```

**Expected:** `Passed: 31, Failed: 0` and `Test Run Successful`.

Test classes:
- `LibreMetaverseBotSessionTests` (4) — state machine via `FakeBotSession`.
- `TeleportRateLimiterTests` (3) — token bucket math.
- `ParcelReaderTests` (2) — snapshot + access-denied via fake.
- `HttpBackendClientTests` (6) — bearer, retry, 401→`AuthConfigException`, 204→null.
- `VerifyHandlerTests` (4) — success, teleport-AccessDenied, parcel-read-timeout, missing-coords.
- `MonitorHandlerTests` (9) — all 8 outcome classifications + missing-coords.
- `TaskLoopTests` (3) — offline/empty/handler-crash behavior.

### 4.3 Frontend tests (not affected by Epic 06 but sanity-check)

```bash
cd frontend
npm install   # first time
npm test
```

**Expected:** all passing (Epic 05 sub-spec 2 baseline).

---

## 5. Starting the Local Stack

You can run the stack three ways. Option A is the fastest path for manual testing with real SL integration.

### 5.1 Option A — Full Docker Compose (recommended)

Brings up Postgres, Redis, MinIO, backend, frontend, and `bot-1` in one command.

```bash
# From the repo root
docker compose up -d postgres redis minio
# Wait ~5s for health checks to settle, then:
docker compose up -d backend frontend
# Wait ~60s for backend to pass its healthcheck (Flyway + JPA init + dependency download):
docker compose ps
# Once backend shows (healthy), start the bot:
docker compose up -d bot-1
```

Watch logs live:

```bash
docker compose logs -f backend bot-1
```

Check health endpoints:

```bash
curl -fsS http://localhost:8080/api/v1/health
# {"status":"UP"}

curl -fsS http://localhost:8081/health
# {"state":"Online"}  — after login completes (~20-60s for cold SL login)
```

> **If `bot-1` shows `unhealthy`:** the container has a 2-minute start-period for SL login (widened from 30s in PR #23 polish). If still unhealthy after 2 minutes, check `docker compose logs bot-1` for login errors — wrong password, wrong UUID, grid maintenance, etc.

### 5.2 Option B — Infra in Docker, apps native

Fastest iteration loop for developers who want the backend + frontend + bot as `dotnet run` / `mvnw spring-boot:run` / `npm run dev` processes with hot reload.

```bash
# Infra
docker compose up -d postgres redis minio

# Backend (separate terminal)
cd backend
./mvnw spring-boot:run

# Frontend (separate terminal)
cd frontend
npm install
npm run dev

# Bot (separate terminal) — reads bot/.env.bot-1 manually
cd bot
set -a; source .env.bot-1; set +a
dotnet run --project src/Slpa.Bot
```

Backend picks `dev` profile automatically via `application-dev.yml`. Frontend binds `http://localhost:3000`, backend `http://localhost:8080`, bot health `http://localhost:8081`.

### 5.3 Option C — Fully native (no Docker)

Same as Option B but with local Postgres/Redis/MinIO installations. Only worth it on constrained hardware; follow standard install docs for each and point the backend at them via `application-dev.yml` or env vars (`SPRING_DATASOURCE_URL`, etc.).

### 5.4 Shutdown

```bash
docker compose down          # stops containers, keeps volumes
docker compose down -v       # also wipes postgres-data, redis-data, etc. — nuclear reset
```

For Option B, just Ctrl-C each process.

---

## 6. Manual Smoke Tests — Service Health

Run these in order. Each step must succeed before moving on.

### 6.1 Backend `/api/v1/health`

```bash
curl -fsS http://localhost:8080/api/v1/health
```

Expected: `{"status":"UP"}`. If 4xx/5xx, check backend logs for Flyway / Hibernate errors.

### 6.2 Frontend loads

Open `http://localhost:3000` in a browser. The landing page should render (no JS errors in DevTools console). If there's a CORS error, check `cors.allowed-origin` in `application-dev.yml` matches the frontend URL.

### 6.3 Bot `/health`

```bash
curl -i http://localhost:8081/health
```

Expected (eventually — after login completes):

```
HTTP/1.1 200 OK
Content-Type: application/json

{"state":"Online"}
```

Intermediate states are valid during startup:

- `{"state":"Starting"}` → HTTP 503 (container still pre-login)
- `{"state":"Reconnecting"}` → HTTP 503 (SL drop; auto-reconnecting)
- `{"state":"Error"}` → HTTP 503 (login failed; check bot logs)
- `{"state":"Stopped"}` → HTTP 503 (clean shutdown in progress)

### 6.4 Bearer-auth gate on bot API

Confirm the backend rejects unauthenticated bot calls:

```bash
# Missing bearer → 401
curl -i -X POST http://localhost:8080/api/v1/bot/tasks/claim \
  -H 'Content-Type: application/json' \
  -d '{"botUuid":"00000000-0000-0000-0000-000000000001"}'

# Expected: HTTP/1.1 401
```

Now with the dev bearer — should return 204 (empty queue) if no tasks are pending:

```bash
curl -i -X POST http://localhost:8080/api/v1/bot/tasks/claim \
  -H 'Authorization: Bearer dev-bot-shared-secret' \
  -H 'Content-Type: application/json' \
  -d '{"botUuid":"00000000-0000-0000-0000-000000000001"}'

# Expected: HTTP/1.1 204 No Content   (empty queue)
#     or    HTTP/1.1 200 OK + JSON task (if a VERIFY task is pending)
```

Wrong bearer → 401:

```bash
curl -i -X POST http://localhost:8080/api/v1/bot/tasks/claim \
  -H 'Authorization: Bearer wrong-secret' \
  -H 'Content-Type: application/json' \
  -d '{"botUuid":"00000000-0000-0000-0000-000000000001"}'

# Expected: HTTP/1.1 401
```

---

## 7. Manual Scenario — Method C Verification (No SL Bot Needed)

This exercises the backend bot API without actually teleporting the bot in SL. Uses the dev-only `POST /api/v1/dev/bot/tasks/{id}/complete` shortcut.

### 7.1 Register + verify a test user

Register a seller account:

```bash
curl -fsS -X POST http://localhost:8080/api/v1/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"email":"seller1@test.local","password":"Passw0rd!","displayName":"Test Seller"}' | jq .
```

Keep the `accessToken` from the response. Export it:

```bash
export TOKEN=<accessToken>
```

Simulate SL verification (dev helper — bypasses the real in-world terminal):

```bash
curl -fsS -X POST http://localhost:8080/api/v1/dev/sl/simulate-verify \
  -H 'Content-Type: application/json' \
  -d '{
    "userUuid":"550e8400-e29b-41d4-a716-446655440000",
    "displayName":"Test Seller SL",
    "legacyName":"Testseller Resident",
    "accountCreatedDate":"2020-01-01",
    "paymentInfoOnFile":true,
    "region":"Ahern"
  }' | jq .
```

Now the user is SL-verified and can create listings.

### 7.2 Create a Method C draft auction

```bash
curl -fsS -X POST http://localhost:8080/api/v1/auctions \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "parcel": {
      "slParcelUuid":"11111111-2222-3333-4444-555555555555",
      "regionName":"Ahern",
      "positionX":128,"positionY":128,"positionZ":20,
      "areaSqm":1024,
      "ownerUuid":"550e8400-e29b-41d4-a716-446655440000"
    },
    "verificationMethod":"SALE_TO_BOT",
    "startBid":100,
    "reservePrice":500,
    "durationHours":24
  }' | jq '.id'
```

Capture the auction id:

```bash
export AUCTION_ID=<id>
```

### 7.3 Pay the listing fee (dev shortcut)

```bash
curl -fsS -X POST "http://localhost:8080/api/v1/dev/auctions/$AUCTION_ID/pay" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"amount":100}' | jq '.status'
# "DRAFT_PAID"
```

### 7.4 Submit for verification

```bash
curl -fsS -X PUT "http://localhost:8080/api/v1/auctions/$AUCTION_ID/verify" \
  -H "Authorization: Bearer $TOKEN" | jq '.status'
# "VERIFICATION_PENDING"
```

A `BotTask` row now exists in `PENDING` status. Check:

```bash
docker compose exec postgres psql -U slpa -d slpa -c \
  "SELECT id, task_type, status, auction_id, parcel_uuid FROM bot_tasks ORDER BY id DESC LIMIT 5"
```

Expected: one `VERIFY / PENDING` row for the auction.

### 7.5 Simulate bot SUCCESS (dev shortcut)

```bash
# Get the bot task id
export TASK_ID=$(docker compose exec -T postgres psql -U slpa -d slpa -At -c \
  "SELECT id FROM bot_tasks WHERE auction_id = $AUCTION_ID AND status = 'PENDING'")

curl -fsS -X POST "http://localhost:8080/api/v1/dev/bot/tasks/$TASK_ID/complete" \
  -H 'Content-Type: application/json' \
  -d '{
    "result":"SUCCESS",
    "authBuyerId":"00000000-0000-0000-0000-000000000099",
    "salePrice":999999999,
    "parcelOwner":"550e8400-e29b-41d4-a716-446655440000",
    "parcelName":"Test Parcel",
    "areaSqm":1024,
    "regionName":"Ahern",
    "positionX":128,"positionY":128,"positionZ":20
  }' | jq '.status'
# "COMPLETED"
```

### 7.6 Verify the lifecycle chain

```bash
# Auction should now be ACTIVE with tier=BOT
docker compose exec postgres psql -U slpa -d slpa -c \
  "SELECT id, status, verification_tier FROM auctions WHERE id = $AUCTION_ID"

# MONITOR_AUCTION row should have been auto-created (Task 6 hook)
docker compose exec postgres psql -U slpa -d slpa -c \
  "SELECT id, task_type, status, next_run_at, recurrence_interval_seconds
     FROM bot_tasks WHERE auction_id = $AUCTION_ID ORDER BY id"
```

Expected:
- Auction: `ACTIVE`, `BOT`.
- Two bot_tasks rows: the `VERIFY / COMPLETED` from step 7.5, plus a new `MONITOR_AUCTION / PENDING` with `next_run_at ~= now + 30min` and `recurrence_interval_seconds = 1800`.

✅ **Method C end-to-end via dev shortcuts: PASS.**

---

## 8. Manual Scenario — Real Bot Against Real SL

This is the full integration test. Requires the bot container running + real SL parcel set up correctly.

### 8.1 Prerequisites

1. Bot is `Online` (`curl http://localhost:8081/health`).
2. You own a parcel in SL that you can set for sale.
3. The SLPAEscrow UUID is configured on the backend (env var `SLPA_PRIMARY_ESCROW_UUID`).

### 8.2 Set up the parcel in-world

In Second Life:

1. Stand on the parcel.
2. World → About Land → General tab.
3. Click **Sell Land**.
4. In the sell-to dialog:
   - **Sell to:** Specific avatar → enter `SLPAEscrow Resident`.
   - **Price:** `L$ 999,999,999` (the sentinel — safe because nobody will accidentally pay it).
   - **Include objects:** No.
5. Click **Set**. Confirm the dialog.

Verify: About Land → General should now show "For sale to SLPAEscrow Resident" and the price `L$ 999999999`.

### 8.3 Create + submit the auction

Follow §7.2 - §7.4 above, using the REAL parcel UUID (from About Land → General → "parcel ID" in the viewer) and REAL coordinates.

### 8.4 Watch the bot claim + verify

Tail the bot logs:

```bash
docker compose logs -f bot-1
```

Within ~20 seconds the bot should:
1. `claim`ed a task (log: `Bot task N claimed by <botUuid>`).
2. Teleport to the parcel's region (log: `TeleportProgress: Start → Progress → Finished`).
3. Read `ParcelProperties` (log: `MONITOR N (VERIFY) reported SUCCESS` or log output from `VerifyHandler`).
4. POST to `/verify` → backend flips auction ACTIVE.

Check DB:

```sql
SELECT id, status, verification_tier FROM auctions WHERE id = <id>;
-- status = ACTIVE, verification_tier = BOT

SELECT id, task_type, status FROM bot_tasks WHERE auction_id = <id> ORDER BY id;
-- VERIFY / COMPLETED
-- MONITOR_AUCTION / PENDING
```

### 8.5 Test teleport rate limit

Queue 7+ VERIFY tasks rapidly and watch the bot logs — only 6 teleports should happen per minute. The 7th task should wait.

---

## 9. Manual Scenario — MONITOR_AUCTION Cycle

Once the auction is ACTIVE with tier=BOT, the monitor cycle runs every 30 min by default. For testing, shrink the interval.

### 9.1 Shrink monitor interval for test

Edit `backend/src/main/resources/application-dev.yml`:

```yaml
slpa:
  bot:
    monitor-auction-interval: PT1M   # 1 minute instead of PT30M
```

Restart the backend. (Or stop the bot, restart backend with `SLPA_BOT_MONITOR_AUCTION_INTERVAL=PT1M`, then restart bot.)

### 9.2 Observe the cycle

Tail backend + bot logs. Every 1 minute you should see:
- `bot monitor N auction=<id> outcome=ALL_GOOD action=ALL_GOOD` in backend logs.
- `MONITOR N (MONITOR_AUCTION) reported ALL_GOOD` in bot logs.
- The `bot_tasks` row's `next_run_at` bumps +60s each cycle, `last_check_at` updates.

### 9.3 Trigger fraud scenarios

**Test A: AuthBuyerID change (seller revokes sale-to-bot)**

In SL:
1. About Land → click **Cancel Sale**.
2. Wait for the next monitor tick (~1 min with shrunk interval).

Expected:
- Bot observes `AuthBuyerID = UUID.Zero` ≠ expected primary escrow UUID.
- Bot posts `outcome=AUTH_BUYER_CHANGED`.
- Backend dispatcher calls `SuspensionService.suspendForBotObservation(auction, BOT_AUTH_BUYER_REVOKED, evidence)`.
- Auction flips to `SUSPENDED`.
- A `fraud_flags` row is written.
- The MONITOR_AUCTION row flips to `CANCELLED` via lifecycle hook.

Verify:

```sql
SELECT status FROM auctions WHERE id = <id>;
-- SUSPENDED

SELECT reason, evidence_json FROM fraud_flags WHERE auction_id = <id> ORDER BY id DESC LIMIT 1;
-- BOT_AUTH_BUYER_REVOKED, evidence includes observed_auth_buyer

SELECT status FROM bot_tasks WHERE auction_id = <id> AND task_type = 'MONITOR_AUCTION';
-- CANCELLED
```

**Test B: Price drift (seller changes sale price)**

In SL: Sell Land → change price to `L$100` → Set.

Expected: outcome `PRICE_MISMATCH` → `SuspensionService.suspendForBotObservation(..., BOT_PRICE_DRIFT, ...)` → auction SUSPENDED.

**Test C: Ownership change (seller deeds parcel away)**

In SL: Deed to group, or transfer to another account.

Expected: outcome `OWNER_CHANGED` → `SuspensionService.suspendForBotObservation(..., BOT_OWNERSHIP_CHANGED, ...)` → auction SUSPENDED.

**Test D: Access denied (seller bans bot)**

In SL: About Land → Access tab → add SLPABot1 to the banned list.

Expected: outcome `ACCESS_DENIED` on first cycle → `accessDeniedStreak` in `result_data` bumps to 1. Second cycle → 2. Third cycle → threshold hit → `SuspensionService.suspendForBotObservation(..., BOT_ACCESS_REVOKED, ...)` → SUSPENDED.

Verify the streak in JSONB:

```sql
SELECT result_data FROM bot_tasks WHERE auction_id = <id> AND task_type = 'MONITOR_AUCTION';
-- {"accessDeniedStreak": 2, ...}
```

### 9.4 Reset interval

Before committing any changes, revert `monitor-auction-interval` to `PT30M`.

---

## 10. Manual Scenario — MONITOR_ESCROW Cycle

After an auction sells (reaches `ENDED` with outcome `SOLD`), an escrow is created. If `verificationTier == BOT`, a MONITOR_ESCROW row is also created.

### 10.1 Force an auction to end with a sale

```bash
# Assume auction is ACTIVE; drop a bid on it:
curl -fsS -X POST "http://localhost:8080/api/v1/auctions/$AUCTION_ID/bids" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"amount":600}'

# Force-end the auction (dev endpoint)
curl -fsS -X POST "http://localhost:8080/api/v1/dev/auctions/$AUCTION_ID/end-now" \
  -H "Authorization: Bearer $TOKEN"
```

The auction flips to `ENDED` with `endOutcome=SOLD`. An `escrows` row is created in `ESCROW_PENDING`. A `bot_tasks / MONITOR_ESCROW / PENDING` row is auto-created (lifecycle hook).

```sql
SELECT id, state FROM escrows WHERE auction_id = <id>;
SELECT id, task_type, status, escrow_id, next_run_at
  FROM bot_tasks WHERE auction_id = <id> ORDER BY id;
```

### 10.2 Escrow outcomes — backend interpretation

With the interval shrunk (`slpa.bot.monitor-escrow-interval: PT1M`), observe each outcome:

| Scenario in SL                       | Bot outcome                  | Backend action                                                                                                       |
|--------------------------------------|------------------------------|----------------------------------------------------------------------------------------------------------------------|
| Parcel still owned by seller         | `STILL_WAITING`              | Re-arm only                                                                                                          |
| Seller sets for sale to winner @ L$1 | `TRANSFER_READY`             | Stamp `transferReady=true`, publish TRANSFER_READY_OBSERVED broadcast (first time only)                              |
| Ownership transferred to winner      | `TRANSFER_COMPLETE`          | `EscrowService.confirmTransfer()` — escrow advances to `TRANSFER_PENDING`; MONITOR_ESCROW cancels via lifecycle hook |
| Ownership transferred to third party | `OWNER_CHANGED` (not winner) | `EscrowService.freezeForFraud(BOT_OWNERSHIP_CHANGED)` — escrow FROZEN                                                |
| Bot banned from parcel               | `ACCESS_DENIED` × 3          | `EscrowService.markReviewRequired()` — flag for admin review                                                         |

Verify freeze path:

```sql
SELECT state, frozen_at, freeze_reason FROM escrows WHERE id = <escrow_id>;
-- FROZEN, <timestamp>, BOT_OWNERSHIP_CHANGED

SELECT status FROM bot_tasks WHERE escrow_id = <escrow_id>;
-- CANCELLED

SELECT reason FROM fraud_flags WHERE auction_id = <auction_id> ORDER BY id DESC LIMIT 1;
-- BOT_OWNERSHIP_CHANGED
```

---

## 11. Postman Collection

The repo's Postman collection (shared via workspace) has an `SLPA` collection with folders for every endpoint. Epic 06 additions:

- **Bot** folder:
  - `Claim task` → `POST /api/v1/bot/tasks/claim`
  - `Complete verify` → `PUT /api/v1/bot/tasks/{id}/verify`
  - `Post monitor` → `POST /api/v1/bot/tasks/{id}/monitor`
  - `List pending (debug)` → `GET /api/v1/bot/tasks/pending`
- **Dev** folder:
  - `Simulate bot complete` → `POST /api/v1/dev/bot/tasks/{id}/complete`

All bot endpoints require the `Authorization: Bearer {{slpaBotSharedSecret}}` variable. Set it to `dev-bot-shared-secret` for local dev.

Variables:

| Variable               | Value (dev)                                            |
|------------------------|--------------------------------------------------------|
| `baseUrl`              | `http://localhost:8080`                                |
| `accessToken`          | (set after login; auto-injected by pre-request script) |
| `slpaBotSharedSecret`  | `dev-bot-shared-secret`                                |
| `slOwnerKey`           | `00000000-0000-0000-0000-000000000001`                 |
| `slEscrowSharedSecret` | `dev-escrow-secret-do-not-use-in-prod`                 |

---

## 12. Concurrency Tests

### 12.1 SKIP LOCKED race (automated)

Already covered by `BotTaskClaimRaceIntegrationTest`. To inspect in isolation:

```bash
cd backend
./mvnw test -Dtest=BotTaskClaimRaceIntegrationTest -q
```

Expected: PASS in ~5s. Two threads barrier-synced, both `POST /claim`, both get distinct rows, neither blocks.

### 12.2 Multiple bots racing (manual)

Spin up `bot-2` by copying the `bot-1` block in `docker-compose.yml` with different credentials:

```yaml
  bot-2:
    build: { context: ./bot, dockerfile: Dockerfile }
    env_file: ./bot/.env.bot-2
    environment:
      Bot__Username: ${SLPA_BOT_USERNAME}
      # ... same as bot-1
      Backend__BaseUrl: http://backend:8080
      Backend__SharedSecret: ${SLPA_BOT_SHARED_SECRET}
    depends_on: { backend: { condition: service_healthy } }
    restart: unless-stopped
    networks: [slpa-net]
```

Create `bot/.env.bot-2` with `SLPABot2` credentials. Start:

```bash
docker compose up -d bot-2
```

Queue 10 VERIFY tasks rapidly. Watch both bots' logs — they should each claim distinct task IDs, never duplicate. Confirm no two rows ever have the same `assigned_bot_uuid` at the same time:

```sql
SELECT assigned_bot_uuid, COUNT(*) AS in_flight
  FROM bot_tasks WHERE status = 'IN_PROGRESS' GROUP BY assigned_bot_uuid;
```

Each row should have count ≤ 1.

---

## 13. Timeout & Recovery Tests

### 13.1 IN_PROGRESS timeout sweep

Simulate a crashed worker:

1. Queue a VERIFY task (see §7).
2. Claim it via the bearer endpoint (intercepts the bot worker's claim):

   ```bash
   curl -fsS -X POST http://localhost:8080/api/v1/bot/tasks/claim \
     -H 'Authorization: Bearer dev-bot-shared-secret' \
     -H 'Content-Type: application/json' \
     -d '{"botUuid":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"}'
   ```

   Row flips to `IN_PROGRESS` assigned to that fake bot UUID.

3. Do NOT post a callback — simulate a crash.

4. Shrink the sweep interval for testing: set `slpa.bot-task.in-progress-timeout=PT1M` and `slpa.bot-task.timeout-check-interval=PT30S` via env vars, restart backend.

5. Wait ~1m30s. Check:

   ```sql
   SELECT id, status, failure_reason FROM bot_tasks WHERE task_type='VERIFY' ORDER BY id DESC LIMIT 1;
   -- FAILED, "TIMEOUT (IN_PROGRESS)"

   SELECT status FROM auctions WHERE id = <auction_id>;
   -- VERIFICATION_FAILED
   ```

For MONITOR tasks, the behavior is different — they re-arm:

```sql
SELECT status, assigned_bot_uuid, next_run_at FROM bot_tasks
  WHERE task_type = 'MONITOR_AUCTION' AND id = <id>;
-- PENDING, NULL, <future time>
```

### 13.2 Auth config error recovery

Set an invalid bearer on the bot:

```bash
docker compose stop bot-1
# Edit bot/.env.bot-1: set SLPA_BOT_SHARED_SECRET=wrong
docker compose up -d bot-1
docker compose logs -f bot-1
```

Expected: bot logs `Auth config error; exiting` (LogCritical) + container exits. Because `restart: unless-stopped`, it restarts, exits again. This is the correct failure mode — operator must rotate the secret.

Restore the correct secret to recover.

---

## 14. Frontend Verification

Epic 06 has no new UI (all surfaces are backend + bot), but existing UI should still reflect bot-tier listings correctly.

### 14.1 My Listings

Log in as the seller from §7. Navigate to `/dashboard` → My Listings.

Expected:
- The BOT-tier auction from §7 appears with `Verification: Bot-Verified` badge (if implemented) or at minimum `Status: Active`.
- If suspended via §9.3, the row shows `Status: SUSPENDED` and a red callout with the fraud reason.

### 14.2 Escrow status page

After §10 (auction sold), navigate to `/auction/<id>/escrow` as the buyer.

Expected:
- Escrow stepper renders (from Epic 05 sub-spec 2).
- State updates propagate via WS as `TRANSFER_READY` (if implemented) or at minimum via the next refresh.

### 14.3 Auction detail page

During §9 fraud tests, navigate to `/auction/<id>` in another tab.

Expected: when the auction is suspended mid-bid, the WS envelope arrives and the UI shows the SUSPENDED banner without a page reload.

---

## 15. Troubleshooting

| Symptom                                          | Likely cause                                                   | Fix                                                                                                                                                               |
|--------------------------------------------------|----------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Bot stuck in `Starting`                          | SL login failed (wrong password/UUID)                          | Check `docker compose logs bot-1` for `Login failed:` message; fix `bot/.env.bot-1`                                                                               |
| Bot health returns 503 forever                   | Grid maintenance / network                                     | Check `status.secondlife.com`; wait or restart container                                                                                                          |
| Backend rejects bot with 401                     | Secret mismatch                                                | Ensure `Backend__SharedSecret` in `bot/.env.bot-1` == `slpa.bot.shared-secret` in backend config                                                                  |
| MONITOR row not created after Method C           | Auction `verificationTier != BOT`                              | Check `SELECT verification_tier FROM auctions WHERE id=X` — `BotMonitorLifecycleService.onAuctionActivatedBot` no-ops for non-BOT tiers                           |
| MONITOR row not cancelled after auction end      | Lifecycle hook not wired, or the wrong service terminal method | Grep `onAuctionClosed` / `onEscrowTerminal` in service code; confirm they're called on every exit path                                                            |
| `docker compose up` hangs on backend             | First boot downloads Maven deps (~45s)                         | Wait. Subsequent boots with the `maven-cache` volume warm take ~10s                                                                                               |
| Bot throws `SessionLostException` mid-teleport   | SL drop                                                        | No action — backend's IN_PROGRESS sweep reclaims the task on next cycle                                                                                           |
| `pg_get_constraintdef` returns old enum values   | Hibernate `ddl-auto: update` doesn't widen CHECK constraints   | Verify `BotTaskTypeCheckConstraintInitializer` / `BotTaskStatusCheckConstraintInitializer` `@EventListener(ApplicationReadyEvent.class)` ran — check backend logs |
| `@Modifying` query didn't update `lastUpdatedAt` | FOOTGUNS §F.87 — `@UpdateTimestamp` bypassed                   | Check the query's SET clause includes `lastUpdatedAt = :now`                                                                                                      |
| Bot docker build fails with MCR egress error     | Local network blocks `mcr.microsoft.com`                       | Use CI build, or add `HTTPS_PROXY`/`HTTP_PROXY` to Docker daemon config                                                                                           |

---

## 16. Log Greps Cheat-Sheet

Useful one-liners when debugging.

```bash
# Backend monitor dispatcher decisions
docker compose logs backend | grep 'bot monitor'

# Bot claim + handler activity
docker compose logs bot-1 | grep -E 'claimed by|reported|handler crashed'

# Auction lifecycle transitions
docker compose logs backend | grep -E 'SUSPENDED|VERIFICATION_FAILED|VERIFICATION_PENDING|ACTIVE|ENDED|CANCELLED'

# Escrow lifecycle transitions
docker compose logs backend | grep -E 'Escrow.*FUNDED|TRANSFER_CONFIRMED|COMPLETED|EXPIRED|DISPUTED|FROZEN'

# Timeout sweep activity
docker compose logs backend | grep -E 'BotTaskTimeoutJob|IN_PROGRESS sweep'
```

---

## 17. Database Inspection Queries

Save these as Postman pre-request snippets or a `psql` scratchpad.

```sql
-- Queue snapshot
SELECT id, task_type, status, auction_id, escrow_id, next_run_at, assigned_bot_uuid
  FROM bot_tasks ORDER BY id DESC LIMIT 20;

-- Live PENDING + IN_PROGRESS tasks
SELECT id, task_type, status, assigned_bot_uuid, created_at, last_updated_at
  FROM bot_tasks WHERE status IN ('PENDING','IN_PROGRESS') ORDER BY created_at;

-- Failed tasks + reasons
SELECT id, task_type, failure_reason, completed_at
  FROM bot_tasks WHERE status = 'FAILED' ORDER BY completed_at DESC LIMIT 10;

-- Fraud flags from bot observations
SELECT reason, evidence_json, detected_at FROM fraud_flags
  WHERE reason LIKE 'BOT\_%' ESCAPE '\' ORDER BY detected_at DESC LIMIT 20;

-- Escrow + monitor row join
SELECT e.id AS escrow_id, e.state, e.review_required, bt.status AS monitor_status
  FROM escrows e
  LEFT JOIN bot_tasks bt ON bt.escrow_id = e.id AND bt.task_type = 'MONITOR_ESCROW'
  ORDER BY e.id DESC LIMIT 10;

-- Access-denied streak counters (JSONB)
SELECT id, auction_id, result_data->>'accessDeniedStreak' AS streak
  FROM bot_tasks WHERE task_type = 'MONITOR_AUCTION'
    AND result_data ? 'accessDeniedStreak' ORDER BY id DESC;

-- Monitor rows approaching their next check
SELECT id, task_type, auction_id, next_run_at,
       next_run_at - NOW() AS due_in
  FROM bot_tasks
 WHERE status = 'PENDING' AND next_run_at IS NOT NULL
 ORDER BY next_run_at LIMIT 10;
```

---

## 18. References

- **Spec**: `docs/superpowers/specs/2026-04-22-epic-06-sl-bot-service.md`
- **Plan**: `docs/superpowers/plans/2026-04-22-epic-06-sl-bot-service.md`
- **Bot README**: `bot/README.md`
- **Project conventions**: `docs/implementation/CONVENTIONS.md`
- **Deferred work ledger**: `docs/implementation/DEFERRED_WORK.md` (Epic 06 entries near the bottom)
- **FOOTGUNS**: `docs/implementation/FOOTGUNS.md` §F.86–F.89 (Epic 06 additions)
- **DESIGN.md**: `docs/initial-design/DESIGN.md` §5.4 (SLPA Verification Bot Pool architecture)
- **PR**: https://github.com/TheCodeLlama/slparcelauctions/pull/23

---

## 19. Done-Bar Checklist (Sign-Off)

Before calling the stack "verified for production readiness," every row below should be ✅.

- [ ] `./mvnw test` → 782/782 passing.
- [ ] `dotnet test` (in `bot/`) → 31/31 passing.
- [ ] `npm test` (in `frontend/`) → all passing.
- [ ] `docker compose up` brings every service to `(healthy)` status.
- [ ] `GET /api/v1/health` → 200 `{"status":"UP"}`.
- [ ] `GET http://localhost:8081/health` → 200 `{"state":"Online"}` within 2 min of boot.
- [ ] `POST /api/v1/bot/tasks/claim` without bearer → 401; with correct bearer + empty queue → 204.
- [ ] Method C dev-shortcut flow (§7) completes — auction flips ACTIVE, MONITOR_AUCTION row created.
- [ ] Method C real-bot flow (§8) completes — bot teleports, reads parcel, callbacks POST SUCCESS, auction flips ACTIVE.
- [ ] MONITOR_AUCTION fraud scenarios (§9.3) trigger the correct `SuspensionService.suspendForBotObservation` variant for each outcome.
- [ ] MONITOR_ESCROW transfer-complete path calls `EscrowService.confirmTransfer`; third-party-ownership path calls `freezeForFraud`.
- [ ] IN_PROGRESS timeout sweep fails VERIFY tasks, re-arms MONITOR tasks.
- [ ] Two bots racing each claim distinct tasks (no double-assignment in `assigned_bot_uuid`).
- [ ] `docker compose down -v` + reboot cleanly re-migrates schema with new columns via `ddl-auto: update`.
