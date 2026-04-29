# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

SLPA (Second Life Parcel Auctions) is a player-to-player land auction platform for Second Life. It bridges web-based auctions with the Second Life virtual world through verification terminals, escrow objects, and bot services. Phase 1 supports Mainland parcels only.

## Architecture

```
Frontend (Next.js 16 / React 19 / TypeScript 5 / Tailwind CSS 4)
    ↕ REST API + WebSocket (STOMP)
Backend (Spring Boot 4 / Java 26 / PostgreSQL / Redis / MinIO)
    ↕ HTTP (llHTTPRequest / HTTP-in)         ↕ shared-secret HTTP
In-World (Second Life LSL Scripts)      Bot (.NET 8 / LibreMetaverse)
```

**Backend services**: Auth & Identity, Auction Engine, Escrow Manager, Verification Service, Notifications, SL World API Client (ownership polling), Bot Task Queue.

**Frontend pages**: Register/Verify, Browse Listings, Auction Room (live WebSocket), Dashboard (My Bids/Sales).

**Bot worker**: .NET 8 / LibreMetaverse worker that logs in as `SLPABot*` accounts and services backend tasks (Method C verification, BOT-tier ownership/escrow monitoring). See `bot/README.md`.

## Commands

### Full stack (Docker Compose, canonical dev path)
```bash
cp .env.example .env        # first time only
docker compose up --build   # frontend, backend, postgres, redis, minio
docker compose restart backend   # after Java edits — backend does NOT auto-reload
```
Frontend has HMR via bind-mount + `WATCHPACK_POLLING=true`. Backend source is bind-mounted but `spring-boot:run` does not hot-reload — restart the container after Java changes.

### Frontend (`cd frontend`)
```bash
npm run dev       # Dev server at localhost:3000
npm run build     # Production build
npm run start     # Start production server
npm run lint      # ESLint (v9)
npm test          # Vitest, single run
npm run test:watch
npm run verify    # Guard scripts: no-dark-variants, no-hex-colors, no-inline-styles, coverage
```
The `verify` guards run in CI — run them locally before pushing.

### Backend (`cd backend`)
```bash
./mvnw spring-boot:run                                   # default profile
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev    # enables /api/v1/dev/** helpers + dev SL placeholders
./mvnw test                                              # all tests
./mvnw test -Dtest=ClassName                             # single test class
./mvnw test -Dtest=ClassName#methodName                  # single test method
./mvnw clean package                                     # build JAR
```
The `dev` profile gates `/api/v1/dev/**` helpers (e.g. `dev/sl/simulate-verify`, `dev/auctions/{id}/pay`, `dev/bot/tasks/{id}/complete`, `dev/auction-end/run-once`) via `@Profile("dev")` — they don't exist as beans in other profiles.

### Bot (`cd bot`)
```bash
dotnet run --project src/Slpa.Bot   # health on :8081/health
dotnet test                         # all tests (state machine via IBotSession; never touches GridClient)
```
Requires real SL credentials in `.env.bot-N`. No mock mode. See `bot/README.md`.

## Framework Version Warnings

- **Next.js 16.2.3** has breaking changes from earlier versions. Read `frontend/node_modules/next/dist/docs/` before writing frontend code. See `frontend/AGENTS.md`.
- **Spring Boot 4.0.5** with **Java 26** - use latest conventions.
- **Tailwind CSS 4** uses the new `@tailwindcss/postcss` plugin (not the legacy `tailwindcss` PostCSS plugin).

## Key Directories

- `docs/initial-design/DESIGN.md` - Full specification (architecture, user flows, API contracts, DB schema, security)
- `docs/implementation/PHASES.md` - 11 implementation phases with dependency graph
- `docs/implementation/CONVENTIONS.md` - project-wide implementation rules; read before every epic/task
- `docs/implementation/DEFERRED_WORK.md` - running ledger of deferred items; read before every epic/task alongside CONVENTIONS.md
- `docs/implementation/FOOTGUNS.md` - captured gotchas and pitfalls from prior tasks
- `docs/implementation/epic-NN/` - Detailed task breakdowns per phase with acceptance criteria
- `backend/src/main/resources/db/migration/` - Flyway SQL migrations (naming: `V1__description.sql`)
- `backend/src/main/java/com/slparcelauctions/backend/` - Java source root

## Backend Stack Details

- **ORM**: Spring Data JPA / Hibernate with Lombok for boilerplate
- **Database migrations**: Flyway (SQL-based, not Java) — files in `backend/src/main/resources/db/migration/V<N>__description.sql`. Hibernate runs in `ddl-auto: validate` in every profile; entity changes require a paired migration in the same commit.
- **Auth**: Spring Security + JWT
- **Real-time**: Spring WebSocket with STOMP protocol
- **Cache/Sessions**: Redis (via spring-boot-starter-data-redis + spring-session)
- **HTTP client**: WebFlux's WebClient (for SL World API calls)
- **Validation**: Bean Validation (JSR-380)

## Second Life Integration Notes

- In-world HTTP requests include `X-SecondLife-Owner-Key`, `X-SecondLife-Shard`, and other headers that must be validated server-side for security.
- Avatar identities use UUIDs (`key` in SL terminology).
- The SL World API provides parcel metadata and ownership verification.
- LSL scripts communicate with the backend via `llHTTPRequest` (outbound) and HTTP-in URLs (inbound).

**In-world LSL code lives in `lsl-scripts/`.** Each script gets its own
subdirectory with its own README covering deployment, configuration,
operations, and limits. Updates to a script's behavior, deployment, or
configuration must update that script's README in the same commit. The
top-level `lsl-scripts/README.md` is an index — updates only on add / remove
/ rename.

## Infrastructure Dependencies

- **PostgreSQL** - relational data (users, auctions, escrow, reviews)
- **Redis** - sessions, bid rate limiting, auction countdown timers
- **MinIO** - S3-compatible object storage for avatars and listing photos (host ports `9000` API / `9001` console; dev creds `slpa-dev-key` / `slpa-dev-secret`)

## Manual testing surface

The `SLPA` Postman collection (collection id `8070328-cf3e1190-cb71-4af3-8e2e-70c82dfee288` in the `SLPA` workspace at `https://scatr-devs.postman.co`) is the canonical manual-test surface for the backend. New endpoints must be mirrored into the collection in the same task — variable-chaining test scripts thread `accessToken`, `refreshToken`, `userId`, `verificationCode`, `auctionId`, `botTaskId`, etc. via the `SLPA Dev` environment.

## Implementation Status

See `docs/implementation/PHASES.md` for the dependency-ordered phase plan and `docs/implementation/epic-NN/` for per-task acceptance criteria. Current branch state is the source of truth for what has shipped — the README has slice-by-slice descriptions of completed backend work.
