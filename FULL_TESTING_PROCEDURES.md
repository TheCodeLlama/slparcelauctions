# SLParcels Full Testing Procedures

End-to-end testing playbook for the SLParcels dev environment. Covers automated test suites, the canonical local stack, manual API surfaces, and the per-flow scripts you should run before opening a PR.

This document complements the deeper per-epic testing guides under `docs/testing/` (currently `EPIC_6_TESTING.md`). When testing a specific epic, read its guide alongside this one.

---

## 1. Testing tiers at a glance

| Tier                                           | Tooling                                                 | Where to find tests                                                            | How to run                           |
|------------------------------------------------|---------------------------------------------------------|--------------------------------------------------------------------------------|--------------------------------------|
| Frontend unit + integration                    | Vitest + Testing Library + MSW + jsdom                  | `frontend/src/**/*.test.{ts,tsx}`                                              | `cd frontend && npm test`            |
| Frontend guards                                | Bash scripts                                            | `frontend/scripts/verify-*.sh`                                                 | `cd frontend && npm run verify`      |
| Backend unit + slice + Spring Boot integration | JUnit 5 + Mockito + Spring Boot Test + WireMock         | `backend/src/test/java/**/*Test.java`                                          | `cd backend && ./mvnw test`          |
| Backend concurrency + race                     | `@DirtiesContext` + real Postgres + `CompletableFuture` | `backend/src/test/.../auction/concurrency/`, `.../bot/...IntegrationTest.java` | Same — they run inside `./mvnw test` |
| Bot worker                                     | xUnit through `IBotSession` (never `GridClient`)        | `bot/tests/Slpa.Bot.Tests/`                                                    | `cd bot && dotnet test`              |
| Manual API                                     | Postman collection (canonical)                          | `SLPA` workspace at `https://scatr-devs.postman.co`                            | Postman desktop or CLI runner        |
| Manual UI                                      | Browser at `http://localhost:3000`                      | n/a                                                                            | Docker Compose stack                 |
| LSL in-world                                   | Touch + observe in Second Life                          | `lsl-scripts/<script>/`                                                        | Manual smoke (§9)                    |

Backend has **268** Java test files (`@SpringBootTest`, `@WebMvcTest`, `@DataJpaTest`, plain JUnit). Frontend has **244** `*.test.{ts,tsx}` siblings. Bot has **10** xUnit test files. CI runs everything except the LSL and manual surfaces.

---

## 2. Prerequisites

### 2.1 Required software

| Tool               | Version                                 | Used for                                                               |
|--------------------|-----------------------------------------|------------------------------------------------------------------------|
| Git                | any                                     | Clone, branch                                                          |
| Docker Desktop     | 4.x + Compose v2                        | Canonical local stack (Postgres, Redis, MinIO, backend, frontend, bot) |
| Java JDK           | 26 (or 21+)                             | `./mvnw` builds the backend                                            |
| Node.js            | 20+ / 22+ LTS                           | `npm run dev`, `npm test`, `npm run verify`                            |
| .NET SDK           | 8.0.x (or 9.x cross-targeting `net8.0`) | `dotnet test`, `dotnet run`                                            |
| GitHub CLI (`gh`)  | 2.x                                     | PR status, merging                                                     |
| Postman desktop    | 10.x                                    | Canonical manual-test surface (optional but expected)                  |
| `curl` or `httpie` | any                                     | Quick API pokes                                                        |

### 2.2 Pre-provisioned Second Life accounts

Real SL accounts are already provisioned. No mock mode exists for the bot.

| Account               | Role                                      | Logs in?          |
|-----------------------|-------------------------------------------|-------------------|
| `SLPAEscrow Resident` | Primary escrow receiving-wallet UUID      | Never — UUID only |
| `SLPABot1 Resident`   | Worker bot (default in `bot-1` container) | Yes               |
| `SLPABot2`–`SLPABot5` | Additional workers (scale-out)            | Yes               |

Each bot's credentials live in `bot/.env.bot-N` — never commit these files. The dev profile in `application-dev.yml` carries placeholder UUIDs (`00000000-0000-0000-0000-000000000001` for the trusted SL owner key, `00000000-0000-0000-0000-000000000099` for the primary escrow UUID) so you can exercise the verification + bot flows without touching the real grid.

### 2.3 First-time setup

```bash
git clone https://github.com/TheCodeLlama/slparcelauctions.git slpa
cd slpa
git checkout dev
cp .env.example .env                    # adjust ports/credentials if any conflict
cp bot/.env.example bot/.env.bot-1      # required if you bring up bot-1 — see below
cp bot/.env.example bot/.env.bot-2      # required if you bring up bot-2
```

`.env` and `.env.bot-*` are gitignored.

**Bot env files are load-bearing for Compose.** `docker-compose.yml` declares `env_file: ./bot/.env.bot-1` and `./bot/.env.bot-2` for the `bot-1` and `bot-2` services. If those files don't exist, `docker compose up` aborts at config-load **before any service starts** — even if you only wanted frontend + backend. Two ways out:

1. **Bring up bot creds** — fill in real `SLPABot1` / `SLPABot2` credentials in the `.env.bot-N` files (recommended; the bot is required for SL IM dispatch and `WITHDRAW_GROUP` realty-group wallet payouts).
2. **Skip the bot services** — bring up everything else explicitly: `docker compose up --build postgres redis minio backend frontend`. Use this when your test target is verification, bidding, escrow, or any flow that doesn't touch the bot pool — verification is World-API-only and no longer needs a live bot.

---

## 3. Stack bring-up (canonical dev path)

### 3.1 Full stack via Docker Compose

```bash
# With bots (requires bot/.env.bot-1 and bot/.env.bot-2 — see §2.3)
docker compose up --build

# Without bots (any test that doesn't need a bot worker)
docker compose up --build postgres redis minio backend frontend
```

Services and their host ports:

| Service                                     | URL                                                        |
|---------------------------------------------|------------------------------------------------------------|
| Frontend (Next.js dev server, HMR)          | http://localhost:3000                                      |
| Backend (Spring Boot, dev profile)          | http://localhost:8080                                      |
| Backend health                              | http://localhost:8080/api/v1/health                        |
| Bot worker health (when `bot-1` is running) | http://localhost:8081/health (`bot-2` → `:8082`; override via `BOT1_HEALTH_PORT` / `BOT2_HEALTH_PORT` in `.env`) |
| MinIO S3 API                                | http://localhost:9000                                      |
| MinIO console                               | http://localhost:9001 (`slpa-dev-key` / `slpa-dev-secret`) |
| PostgreSQL                                  | `localhost:5432` (user `slpa`)                             |
| Redis                                       | `localhost:6379`                                           |

Reset everything (drops volumes):

```bash
docker compose down -v
```

### 3.2 Hot-reload semantics — the load-bearing gotcha

- **Frontend** — HMR works through the bind mount. `WATCHPACK_POLLING=true` and `CHOKIDAR_USEPOLLING=true` are set so file watches survive the WSL2 boundary on Windows hosts. Edit `.tsx`/`.ts`/CSS and the browser updates.
- **Backend** — `./mvnw spring-boot:run` does **not** auto-reload. After Java edits:

  ```bash
  docker compose restart backend
  ```

  Maven dependencies cache to a named volume (`maven-cache`), so restarts stay fast.

### 3.3 Without Docker (host-side dev)

If you want IDE debugging or faster JVM start, run only Postgres + Redis + MinIO in containers and the apps on the host:

```bash
docker compose up -d postgres redis minio

# In separate shells
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
cd frontend && npm install && npm run dev
```

The `dev` profile is **required** for the dev-helper endpoints (`/api/v1/dev/**`) to exist as beans (§6).

### 3.4 Bot worker — bring-up + health check

The bot is a real `.NET 8` / LibreMetaverse worker that logs into Second Life as `SLPABot1 Resident` (or `SLPABot2`–`SLPABot5` for scale-out). After the ownership-only verification refactor (spec 2026-05-16) its remaining roles are:

- **SL IM dispatch** — polls the backend's dispatcher queue and delivers notifications in-world via `llInstantMessage`.
- **`WITHDRAW_GROUP` task handling** — issues `GiveGroupMoney` calls for realty-group wallet payouts.
- **Idle parking** — sits on a configured chair or rectangle when no work is queued so the avatar appears online in the admin Bot-pool panel.

The bot has **no** role in listing verification or active-state ownership monitoring — both run from the backend via the SL World API. **Skip this section entirely** if you're testing the verification flow, bidding, escrow, or any non-IM-dispatch surface.

**Quick bring-up:**

1. Fill in `bot/.env.bot-1` with real `SLPABot1 Resident` credentials + UUID (see `bot/README.md` for the full env-var list and `Bot__`/`Backend__`/`RateLimit__` section conventions).
2. Start the backend (Compose or host-side) — the bot will retry login until the backend is reachable on `http://backend:8080`.
3. Start the bot:
   ```bash
   docker compose up bot-1            # alongside the rest of the stack
   # or, if you already have everything else up:
   docker compose up -d bot-1
   ```
4. Wait ~10s for SL login. Confirm health:
   ```bash
   curl http://localhost:8081/health
   # → { "state": "Online" }   (HTTP 200)
   ```

**Health states**: `Starting`, `Online`, `Reconnecting`, `Error`. Anything other than `Online` returns HTTP 503 so Docker's healthcheck flips Red on sustained disconnect. `Reconnecting` after a successful `Online` is normal — SL routinely drops idle connections.

**Don't have real bot credentials right now?** SL IM dispatch and `WITHDRAW_GROUP` payouts are the only flows that require a live worker; both have admin-side observability via the backend logs. There is no dev-stub callback for either today.

**Smoke flow** (run after a fresh bring-up to confirm the worker is logged in and processing tasks):

1. Trigger a notification that fans out via SL IM (e.g. place a bid on someone else's listing, then check that the seller's bot-deliverable IM is dispatched).
2. Confirm in `docker compose logs -f bot-1` that the worker polls the dispatcher and issues `llInstantMessage` for any queued sends.
3. For `WITHDRAW_GROUP` smoke, trigger a realty-group wallet payout from the admin UI and confirm the worker logs the `GiveGroupMoney` call.

### 3.5 Hybrid: host-running backend with containerised bot

A common dev mode is running the backend in your IDE (debugger attached, fast restart) while the bot runs in Docker (real .NET 8 + LibreMetaverse runtime, networking already wired). Two snags trip up this configuration:

1. `bot-1` has `depends_on: backend` in `docker-compose.yml`, so plain `docker compose up bot-1` also starts the docker backend — which collides with the IDE-run backend on host port 8080.
2. The bot's `Backend__BaseUrl` is hardcoded to `http://backend:8080` (the in-network DNS name). With no docker backend running, that hostname doesn't resolve.

Both fix with a local `compose.override.yml` (gitignored — see `.gitignore`) plus the `--no-deps` flag.

```yaml
# compose.override.yml — local-dev only, do not commit
services:
  bot-1:
    environment:
      Backend__BaseUrl: http://host.docker.internal:8080
    # Linux-only: uncomment if `host.docker.internal` does not resolve.
    # extra_hosts:
    #   - "host.docker.internal:host-gateway"
```

Then bring it up:

```bash
docker compose up -d --no-deps bot-1
```

`docker compose` auto-merges `compose.override.yml` whenever it sits next to `docker-compose.yml`, so no extra `-f` flag is needed. `--no-deps` is **load-bearing** — without it compose still tries to start the docker backend (because of the `depends_on: backend` chain) and you'll get the port collision again.

`host.docker.internal` is the Docker Desktop DNS name that resolves to the host machine. It works on Windows and macOS out of the box. On Linux native Docker 20.10+ it works automatically; older daemons need the `extra_hosts` override commented above.

Confirm the bot is talking to the host backend by tailing `docker compose logs -f bot-1` and watching for `POST /api/v1/bot/tasks/claim` calls in your IDE backend log.

---

## 4. Automated test suites

### 4.1 Backend (`cd backend`)

```bash
./mvnw test                                              # full suite
./mvnw test -Dtest=BidServiceTest                        # one class
./mvnw test -Dtest=BidServiceTest#proxyBid_resurrects    # one method
./mvnw test -Dtest='*IntegrationTest'                    # only integration tests
./mvnw clean package                                     # full build + tests + JAR
```

Test categorisation:

| Pattern                                           | Style                                                  | What it exercises                                                                                          |
|---------------------------------------------------|--------------------------------------------------------|------------------------------------------------------------------------------------------------------------|
| `*Test.java` (most)                               | Plain JUnit + Mockito                                  | Service / mapper / DTO logic in isolation                                                                  |
| `*ControllerTest.java`                            | `@WebMvcTest` or `@SpringBootTest` + MockMvc           | HTTP layer, validation, exception mapping                                                                  |
| `*IntegrationTest.java`                           | `@SpringBootTest` against real Postgres                | Persistence, transaction, scheduler, broadcast wiring                                                      |
| `*RaceTest.java` (`auction/concurrency/`, `bot/`) | `@SpringBootTest` + `CompletableFuture` parallel calls | Pessimistic-lock + DB unique-index race coverage (bid-vs-cancel, bid-vs-suspend, parcel-locked race, etc.) |
| `WireMock`-backed (`sl/`, etc.)                   | `org.wiremock:wiremock-standalone`                     | Outbound SL World API + Map API clients without hitting the real grid                                      |

Race tests use `@DirtiesContext(AFTER_CLASS)` + explicit `@AfterEach` cleanup so stale rows don't bleed between sibling suites and Hikari pools don't exhaust Postgres's 100-connection ceiling. Don't strip those annotations.

### 4.2 Frontend (`cd frontend`)

```bash
npm test                  # Vitest single-shot
npm run test:watch        # Vitest watch mode
npm run test:ui           # Vitest UI on http://localhost:51204
npm run lint              # ESLint v9
npm run verify            # ALL guard scripts (run before pushing)
```

The `verify` script chains four guards that also run in CI:

| Script                    | What it enforces                                                                                                 |
|---------------------------|------------------------------------------------------------------------------------------------------------------|
| `verify:no-dark-variants` | No `dark:` Tailwind variants — design system is theme-token driven                                               |
| `verify:no-hex-colors`    | No raw hex literals in `className`/`style` — must use M3 token utilities (`bg-primary`, `text-on-surface`, etc.) |
| `verify:no-inline-styles` | No `style={{}}` blocks in app/components                                                                         |
| `verify:coverage`         | Every `src/components/ui/*.tsx` primitive has a sibling `*.test.tsx`                                             |

`vitest.setup.ts` configures MSW with `onUnhandledRequest: "error"` — any unmocked `fetch` fails the test loudly. `next/font/google` and `next/navigation` are mocked at module level. `ResizeObserver` and `window.matchMedia` are stubbed for Headless UI + `next-themes`.

### 4.3 Bot (`cd bot`)

```bash
dotnet test                                            # full suite
dotnet test --filter "FullyQualifiedName~ClaimLoop"    # filter by name
dotnet run --project src/Slpa.Bot                      # run the worker locally; needs real .env.bot-N
```

The state machine is exercised through `IBotSession`. Tests never touch `GridClient` directly. The real `LibreMetaverseBotSession` is covered by the manual smoke test (§9.2).

---

## 5. CI gates

CI runs on every push / PR:

- Backend: `./mvnw test` (the full Java suite)
- Frontend: `npm run lint`, `npm test`, `npm run verify` (all four guards)
- Bot: `dotnet test` on PRs that touch `bot/**` (`.github/workflows/bot-ci.yml`)

Run `npm run verify` and `./mvnw test` locally before pushing — they're the most common CI breakers.

---

## 6. Dev-profile API helpers

These endpoints exist **only** under `SPRING_PROFILES_ACTIVE=dev` (the Compose stack defaults to dev). The beans are `@Profile("dev")` so in any other profile they aren't registered and requests 404 at the MVC layer. `SecurityConfig` permits `/api/v1/dev/**` so each helper is responsible for its own auth checks where needed.

| Endpoint                                   | Purpose                                                                                                                                                                                                                                                                 |
|--------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `POST /api/v1/dev/sl/simulate-verify`      | Stand-in for the real LSL terminal `POST /api/v1/sl/verify`. Body only needs `verificationCode` — the controller synthesises avatar metadata and SL headers from the first `slpa.sl.trusted-owner-keys` entry. Browser-driven testing of the full SL verification path. |
| `POST /api/v1/dev/auctions/{id}/pay`       | Stand-in for the in-world listing-fee payment terminal callback. Body `{ amount?, txnRef? }` (defaults to 100 L$ + `dev-mock-<uuid>`). DRAFT → DRAFT_PAID.                                                                                                              |
| `POST /api/v1/dev/auction-end/run-once`    | Forces the auction-end scheduler sweep to run immediately. Returns `{ "processed": [auctionId, ...] }`.                                                                                                                                                                 |
| `POST /api/v1/dev/auctions/{id}/close`     | Closes a single auction synchronously (returns `{ "closedId": id }`, propagates exceptions for assertion-friendly tests).                                                                                                                                               |
| `POST /api/v1/dev/ownership-monitor/run`   | Forces an ownership-monitor sweep. Use the admin endpoint `POST /api/v1/admin/auctions/{id}/recheck-ownership` for prod.                                                                                                                                                |

These helpers are how the frontend and integration tests exercise SL-dependent flows without driving real avatars or terminals.

---

## 7. Manual API surface — Postman collection

The `SLPA` Postman collection is the canonical manual-test surface for the backend.

- Workspace: `https://scatr-devs.postman.co` (workspace `SLPA`)
- Collection id: `8070328-cf3e1190-cb71-4af3-8e2e-70c82dfee288`
- Environment: `SLPA Dev`

Folder layout:

| Folder               | Covers                                                               |
|----------------------|----------------------------------------------------------------------|
| `Auth/`              | register, login, refresh, logout, logout-all                         |
| `Users/`             | `/me`, profile, avatar upload + bytes                                |
| `Verification/`      | code generation + active code retrieval                              |
| `SL/`                | `POST /sl/verify` (player-verification LSL inbound)                  |
| `Parcel & Listings/` | parcel lookup, auction CRUD, photos, tags                            |
| `Bot/`               | bearer-gated `/api/v1/bot/**`                                        |
| `Tags/`              | parcel-tag list                                                      |
| `Photos/`            | listing-photo upload, delete, public bytes                           |
| `Dev/`               | every `/api/v1/dev/**` helper, mirrored 1:1                          |

Variable-chaining test scripts thread `accessToken`, `refreshToken`, `userId`, `verificationCode`, `parcelVerificationCode`, `auctionId`, `botTaskId`, `photoId`, `avatarSize` through subsequent requests via the `SLPA Dev` environment, so a folder runs as a flow.

**Rule from `CLAUDE.md`:** any new endpoint added in a task must be mirrored into the collection in the same task — folder + saved example response + variable-chaining test script if it produces a new identifier.

---

## 8. End-to-end flows in dev

These are the full happy-path sequences worth running before declaring a feature done. Each step's request body lives in the corresponding Postman folder; you can also run them with `curl`.

### 8.1 Account verification

1. `POST /api/v1/auth/register` → response carries `accessToken`
2. `POST /api/v1/verification/generate` → returns 6-digit `verificationCode`
3. `POST /api/v1/dev/sl/simulate-verify` body `{ "verificationCode": "<code>" }` (stands in for the real LSL terminal)
4. `GET /api/v1/users/me` → expect `verified: true` and `slAvatarUuid` populated

To exercise the real LSL path end-to-end, see §9.1.

### 8.2 Create + activate a listing

1. Authenticate + verify (above)
2. `POST /api/v1/parcels/lookup` body `{ "parcelUuid": "<uuid>" }` (uses dev placeholder UUIDs in WireMock-backed flows; Postman env has prebaked values)
3. `POST /api/v1/auctions` body — full auction config (title, parcel id, pricing, snipe, photos, tags)
4. `POST /api/v1/dev/auctions/{id}/pay` → DRAFT → DRAFT_PAID
5. `PUT /api/v1/auctions/{id}/verify` (no body) — backend calls the SL World API synchronously, compares the parcel's owner UUID to the expected UUID (seller's avatar for individual listings; registered SL group for case-3 group land), and transitions DRAFT_PAID → ACTIVE on match or → VERIFICATION_FAILED on mismatch. The dev placeholder owner UUID matches the World API mock so the flow goes straight to ACTIVE.
6. `GET /api/v1/auctions/{id}` (as seller) → expect `status: ACTIVE`, `verifiedAt`, `verificationTier: SCRIPT`

No bot involvement at any step — the legacy Method A / B / C dispatcher was retired by spec `2026-05-16-ownership-only-verification-design`.

### 8.3 Bidding

1. Login as a second verified user
2. `POST /api/v1/auctions/{id}/bids` body `{ "amount": 1000 }`
3. `GET /api/v1/auctions/{id}/bids?page=0&size=20` → paginated history
4. `POST /api/v1/auctions/{id}/proxy-bid` body `{ "maxAmount": 10000 }` → resurrection + tie-flip behaviour exercised
5. WebSocket: subscribe (anonymous OK) to `/topic/auction/{id}`; expect bid envelopes after `afterCommit`

### 8.4 Auction end

1. Either let the 15-second scheduler tick close the auction at `endsAt`, or:
2. `POST /api/v1/dev/auctions/{id}/close` — single auction, synchronous, exception-propagating
3. `POST /api/v1/dev/auction-end/run-once` — force the full scheduler sweep

Outcomes: `SOLD` / `RESERVE_NOT_MET` / `NO_BIDS` / `BOUGHT_NOW`. The `auctionEnded` envelope publishes on `/topic/auction/{id}` only after the closing transaction commits.

### 8.5 Escrow + payment

1. Auction closes with a winner → `Escrow` row created in `ESCROW_PENDING`
2. Manually mark payment via the appropriate dev helper or the in-world `slpa-terminal` callback
3. Backend's `EscrowOwnershipCheckTask` polls the SL World API on its scheduled cadence (default 5 min); once the parcel's recorded owner flips to the winner's UUID, the task calls `confirmTransfer`
4. State transitions: `ESCROW_PENDING → FUNDED → TRANSFER_PENDING → COMPLETED`

Disputes: `POST /api/v1/escrow/{id}/dispute` (with optional image attachments via multipart). Admins resolve via `POST /api/v1/admin/disputes/{id}/resolve` with one of the four `AdminDisputeAction` values.

### 8.6 Bot task queue

**Prerequisite:** bot worker is up and `Online` per §3.4 (only required for SL IM dispatch + `WITHDRAW_GROUP` payouts; verification + ownership monitoring run from the backend without the bot).

1. A producer (e.g. realty-group wallet payout) enqueues a `BotTask` (PENDING)
2. Bot worker calls `POST /api/v1/bot/tasks/claim` (atomic `SKIP LOCKED`) → IN_PROGRESS
3. Worker performs the in-world action and posts the result to the appropriate callback endpoint

All `/api/v1/bot/**` endpoints require the `Authorization: Bearer <slpa.bot.shared-secret>` header — the constant-time compare lives in `BotSharedSecretAuthorizer`. Heartbeats: `POST /api/v1/bot/heartbeat` (also bearer-gated, persisted to Redis with TTL).

### 8.7 Admin surfaces

Admin-gated endpoints require a JWT for a user with `Role.ADMIN`. Bootstrap via `AdminBootstrapInitializer` (configured by `slpa.admin.bootstrap-*` properties).

| Action                                | Endpoint                                                                            |
|---------------------------------------|-------------------------------------------------------------------------------------|
| Recheck ownership of a single auction | `POST /api/v1/admin/auctions/{id}/recheck-ownership`                                |
| Resolve a dispute                     | `POST /api/v1/admin/disputes/{escrowId}/resolve`                                    |
| Rotate terminal shared secret         | `POST /api/v1/admin/terminals/rotate-secret`                                        |
| List bot pool health                  | `GET /api/v1/admin/bot-pool/health`                                                 |
| Run reconciliation manually           | `POST /api/v1/admin/reconciliation/run-now` (or wait for the daily 03:00 UTC sweep) |
| Admin-cancel an auction               | `POST /api/v1/admin/auctions/{id}/cancel`                                           |

---

## 9. LSL in-world manual testing

Bot worker bring-up + smoke moved to §3.4 — the bot is setup, not in-world UX.

### 9.1 Verification terminal smoke (Epic 02 + Epic 11)

1. Rezz the `verification-terminal` prim in any Mainland region
2. Drop the `config` notecard with `VERIFY_URL=http://<your-public-backend>/api/v1/sl/verify`
3. Touch the terminal in-world; the script captures avatar UUID + name + display name + username + born date + payInfo
4. Type your active 6-digit code in local chat (private listen)
5. Script POSTs to the backend with the SL-injected `X-SecondLife-Owner-Key` + `X-SecondLife-Shard` headers
6. Backend `SlHeaderValidator` validates headers; `SlVerificationService` consumes the code and links the avatar
7. Verify `GET /api/v1/users/me` returns `verified: true`

Other LSL scripts under `lsl-scripts/`: `slpa-terminal` (escrow payment + withdrawal — actively deployed), `sl-im-dispatcher` (notification fan-out — actively deployed), and `parcel-verifier` + `slpa-verifier-giver` (retired with the ownership-only verification refactor; sources retained for reference, do not deploy new instances). Each has its own README — read it before deploying.

---

## 10. Observability + inspection

### 10.1 Database

```bash
docker compose exec postgres psql -U slpa -d slpa
```

Useful queries:

```sql
SELECT id, status, verified_at FROM auctions ORDER BY id DESC LIMIT 10;
SELECT id, escrow_state, transfer_deadline, reminder_sent_at FROM escrows ORDER BY id DESC LIMIT 10;
SELECT id, type, status, parcel_uuid, next_run_at FROM bot_tasks WHERE status IN ('PENDING','IN_PROGRESS') ORDER BY next_run_at;
SELECT name, status, expected, observed, run_at FROM reconciliation_runs ORDER BY id DESC LIMIT 10;
```

### 10.2 Redis

```bash
docker compose exec redis redis-cli
> KEYS bans:active:*
> KEYS bot:heartbeat:*
> GET bot:heartbeat:<sl-uuid>
```

### 10.3 MinIO

Console at http://localhost:9001 (creds in `.env`). Buckets used: `slpa-avatars`, `slpa-listing-photos`, `slpa-dispute-evidence`.

### 10.4 Logs

```bash
docker compose logs -f backend
docker compose logs -f bot-1
docker compose logs -f frontend
```

---

## 11. Common gotchas

| Gotcha                                    | Symptom                                  | Fix                                                                                                     |
|-------------------------------------------|------------------------------------------|---------------------------------------------------------------------------------------------------------|
| Backend not picking up Java edits         | Old behaviour after edit                 | `docker compose restart backend` — `spring-boot:run` does not hot-reload                                |
| Frontend tests making real network calls  | Test passes/fails non-deterministically  | MSW `onUnhandledRequest: "error"` is load-bearing — add a handler instead of disabling it               |
| Race tests flaky                          | Hikari pool exhaustion or stale rows     | Don't strip `@DirtiesContext(AFTER_CLASS)` or `@AfterEach` cleanup                                      |
| `dev/**` 404s in non-dev                  | Helper endpoint missing                  | Confirm `SPRING_PROFILES_ACTIVE=dev` — beans are `@Profile("dev")`                                      |
| Bot bearer rejected                       | 401 from `/api/v1/bot/**`                | `BotStartupValidator` requires a non-placeholder, ≥16-char `slpa.bot.shared-secret` outside dev profile |
| Missing endpoint in Postman after PR      | Manual-test gap                          | The same task that adds the endpoint must mirror it into the `SLPA` collection                          |
| Backend container OOM at boot             | `docker compose up` hangs after ~60s     | Maven cold-start — first boot can take 90s while it pulls dependencies into the `maven-cache` volume    |
| `dark:` Tailwind variant in a frontend PR | `npm run verify` fails locally and in CI | Move colour to a token utility (`bg-primary`, `text-on-surface`, etc.); never reintroduce `dark:`       |
| `bot-1` image build fails with `NETSDK1064: Package <X>, version 8.0.0 was not found` | Build aborts at `dotnet publish --no-restore`, exit 1 | A local `dotnet build` / IDE session left `bot/src/Slpa.Bot/{bin,obj}` on disk; `COPY . .` baked Windows-side NuGet paths into the container. `bot/.dockerignore` excludes those — confirm it's present, then `docker compose build --no-cache bot-1`. |
| `docker compose up bot-1` fails with port 8080 already bound | `Error response from daemon: ports are not available: ... bind: ... 8080` | You have a host-running backend (IDE) and `depends_on: backend` is dragging the docker backend in. Use `docker compose up -d --no-deps bot-1` and a `compose.override.yml` pointing the bot at `host.docker.internal:8080` — see §3.5. |
| Backend won't boot after pulling Flyway-baseline branch | Hibernate `validate` reports "missing column" or "Schema-validation failure" | First time on the Flyway-managed branch, drop the dev DB volume so Flyway runs `V1__initial_schema.sql` on a fresh schema: `docker compose down -v && docker compose up -d`. The pre-Flyway dev DB doesn't have the `flyway_schema_history` table; `baseline-on-migrate: true` handles re-pulls but a clean wipe is the cleanest first run. |

---

## 12. Pre-PR checklist

Run from the repo root in order. Each step short-circuits on failure.

```bash
# Backend
(cd backend && ./mvnw test)

# Frontend
(cd frontend && npm run lint && npm test && npm run verify)

# Bot (only if your PR touches bot/**)
(cd bot && dotnet test)

# Spec / docs sweep
git status        # confirm any new endpoint has Postman collection diff
```

If you're adding a new `@Scheduled` job, also add `slpa.<job>.enabled=false` to existing `@SpringBootTest` `@TestPropertySource` blocks that race with it (until the shared integration-test base class deferral lands — see `docs/implementation/DEFERRED_WORK.md`).

If you're adding a new endpoint, mirror it into the Postman `SLPA` collection in the same commit.

---

## 13. Further reading

- `docs/implementation/CONVENTIONS.md` — project-wide implementation rules
- `docs/implementation/FOOTGUNS.md` — captured gotchas and pitfalls from prior tasks (every entry is a real bug we hit)
- `docs/implementation/DEFERRED_WORK.md` — running ledger of deferred items
- `docs/testing/EPIC_6_TESTING.md` — Epic 06 (bot service) end-to-end testing guide; deeper than this doc on bot-specific surfaces
- `bot/README.md` — bot worker runtime, env vars, manual smoke
- `lsl-scripts/<script>/README.md` — per-script deployment + operations
- `CLAUDE.md` — entry point with command quick-reference
