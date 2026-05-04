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
- **Database migrations**: `application.yml` sets `ddl-auto: update`, but **Spring Boot 4 silently sets `hibernate.hbm2ddl.auto=none` whenever Flyway is on the classpath** — Flyway is the actual schema manager and Hibernate fills no gaps. The "no migrations until users" intent isn't realised today: entity changes still need a paired Flyway migration (`backend/src/main/resources/db/migration/V<N>__*.sql`). To make the original intent real, set `spring.flyway.enabled: false` and rebuild — until then, write a migration when you change an entity.

  **DB wipe procedure (prod).** RDS is in a private subnet, so `psql -h <rds>` from a dev box can't reach it. Use a one-shot Fargate task with the `postgres:16` image inside the same VPC:

  ```bash
  # Task def already lives at .scratch/wipe-task-def.json (subnets + sg of the
  # backend service, IAM exec role pulls the password from SSM).
  aws --profile slpa-prod ecs register-task-definition \
      --cli-input-json file://./.scratch/wipe-task-def.json
  aws --profile slpa-prod ecs run-task \
      --cluster slpa-prod \
      --task-definition slpa-prod-db-wipe \
      --launch-type FARGATE \
      --network-configuration 'awsvpcConfiguration={subnets=[<backend-subnets>],securityGroups=[<backend-sg>],assignPublicIp=DISABLED}'
  # Wait until the task lastStatus=STOPPED, then force a backend redeploy
  # so the new container starts against the empty schema:
  aws --profile slpa-prod ecs update-service \
      --cluster slpa-prod --service slpa-prod-backend --force-new-deployment
  ```

  Existing Flyway migrations on disk apply automatically on startup. After a wipe Hibernate plays no role — Flyway recreates the entire schema from V1 onwards.

- **Auth**: Spring Security + JWT
- **Real-time**: Spring WebSocket with STOMP protocol
- **Cache/Sessions**: Redis (via spring-boot-starter-data-redis + spring-session)
- **HTTP client**: WebFlux's WebClient (for SL World API calls)
- **Validation**: Bean Validation (JSR-380)

## BaseEntity convention

Every entity extends `BaseEntity` (immutable / append-only) or `BaseMutableEntity` (mutable lifecycle). Both live in `backend/src/main/java/com/slparcelauctions/backend/common/`.

- `Long id` — internal PK. `@JsonIgnore`-annotated. Used for FK joins, internal admin endpoints, bot/LSL contracts, Postman variables. **Never crosses a public wire.**
- `UUID publicId` — random UUIDv4, assigned at construction. Used in REST URLs, REST/WebSocket DTOs, frontend types, JWT subject. The only identifier safe in any public surface.

Subclass entities use Lombok `@SuperBuilder`, **not** `@Builder`. Do not redeclare `id`, `publicId`, `createdAt`, `updatedAt`, or `version` — they're inherited. Do not override `equals` or `hashCode` (they're `final`, keyed off `publicId`).

DTO field naming: `publicId: UUID` for public DTOs. Bot / admin-internal DTOs keep `id: Long`. URL paths follow the same split: `/api/v1/auctions/{publicId}` for public, `/api/v1/bot/tasks/{taskId}` (Long) for internal.

JWT subject claim is the user's `publicId` (UUID string). The auth filter resolves `userId` (Long) via `UserRepository.findByPublicId` at request entry; the `AuthPrincipal` carries both.

Two entities are deliberately excluded from the BaseEntity hierarchy because their PK shape is incompatible: `AuctionParcelSnapshot` uses `@MapsId` to share its PK with `auctions(id)`, and `Terminal` uses a String natural key (`terminal_id`) per SL terminal naming. Both keep their existing shape.

Spec: `docs/superpowers/specs/2026-05-03-base-entity-uuid-design.md`. Implementation plan: `docs/superpowers/plans/2026-05-03-base-entity-uuid-implementation.md`.

## Frontend SSR caveats

Server components and Amplify build-time prerendering have surprised us repeatedly — write defensively:

- **`<img src>` does not send the JWT.** Backend endpoints serving image bytes (`/api/v1/photos/{id}`, snapshot URLs, avatars) must be `permitAll` on every status — never gate on the seller's principal. The browser's image fetcher has no Authorization header, so a privacy gate that uses `@AuthenticationPrincipal` 404s the seller to themselves.
- **Server components don't have the user's JWT either.** SSR fetches in `app/**/page.tsx` run on the Amplify runtime with no client cookies. Any backend endpoint a server component hits has to work anonymously (or be moved to a client component).
- **Default to `export const dynamic = "force-dynamic"`** on any page whose data changes per visit (countdowns, current bids, listings). Static prerendering at Amplify build time couples the build to whatever the prod API happens to be serving — a single bad-shape JSON field crashes the entire build and blocks every other page from deploying. The home page already runs `force-dynamic`; mirror that posture for new visit-changing pages.
- **`apiUrl(path)` wraps every backend-emitted URL.** Backend emits relative paths (`/api/v1/photos/3`); the browser resolves them against the page origin (`slparcels.com`), which doesn't proxy `/api/*` to the backend. Always render `<img src={apiUrl(photo.url) ?? undefined}>`. Helper at `frontend/src/lib/api/url.ts`.
- **Defensive coercion at render sites.** When the backend has the freedom to regress (e.g. `tags: ParcelTag[]` instead of `string[]`), components should narrow at the boundary so a wire-shape regression never crashes SSR.

## In-world payment terminals — always refund on deposit error

Once L$ has reached an SLPA Terminal script (the `money()` event has fired), every non-OK backend response **must** bounce the L$ back via `llTransferLindenDollars`. The earlier "ERROR could be an attacker probing, owner-say only" rationale was wrong — SL header + shared-secret pre-flight rejects bad senders before any L$-bearing path runs, so a real ERROR reaching the deposit response handler is a legitimate-but-failed deposit, not an attack. Backend `/sl/wallet/deposit` returns REFUND (not ERROR) for any failure after L$ is in hand; the LSL script also refunds on ERROR responses defensively. Withdraw-side errors are different — no L$ has moved, ERROR is fine there.

## AWS / Prod Ops

- **AWS CLI is on PATH.** Run `aws` directly. (Earlier Claude Code sessions inherited a stale PATH from IntelliJ-spawned shells started before the install — if `aws` ever doesn't resolve in a tool call, restart the parent terminal so the new env propagates.)
- **Profile**: `slpa-prod` (IAM Identity Center user `heath`, account `486208158127`, region `us-east-1`).
- **ECS**: cluster `slpa-prod`. Services: `slpa-prod-backend`, `slpa-prod-bot-1`, `slpa-prod-bot-2`. ECS Exec is enabled on the backend service.
- **CloudWatch logs**: `/aws/ecs/slpa-backend` for backend stdout. Tail with `aws --profile slpa-prod logs tail /aws/ecs/slpa-backend --since 5m --format short`.
- **Amplify**: app id `dil6fhehya5jf`, branch `main`. List recent jobs with `aws --profile slpa-prod amplify list-jobs --app-id dil6fhehya5jf --branch-name main --max-items 5`. Amplify rebuilds on every push to `main` regardless of which paths changed.
- **RDS**: `slpa-prod-postgres.celewqkic6r2.us-east-1.rds.amazonaws.com`, db `slpa`, user `slpa`. **Private subnet — not reachable from a dev box.** Use the one-shot Fargate task pattern for any psql operation.
- **SSM Parameter Store** (encrypted, region us-east-1): `/slpa/prod/rds/{username,password}`, `/slpa/prod/jwt/secret`, `/slpa/prod/redis/auth-token`, `/slpa/prod/escrow/terminal-shared-secret`, `/slpa/prod/sl/{primary-escrow-uuid,trusted-owner-keys}`, `/slpa/prod/bot-N/{username,password,uuid}`, `/slpa/prod/notifications/sl-im/dispatcher-secret`. Read with `aws --profile slpa-prod ssm get-parameter --name /slpa/prod/... --with-decryption --query 'Parameter.Value' --output text`. Secrets Manager is empty — everything lives in SSM.

## Deploy pipelines

- **Backend**: GitHub Actions workflow `.github/workflows/deploy-backend.yml`. Triggers on push to `main` with `paths: backend/**`. Builds + pushes the image to ECR, updates the ECS service. Tail with `gh run list --branch main --workflow 'deploy backend' --limit 3 --json status,conclusion`.
- **Frontend**: Amplify on its own webhook. Triggers on every push to `main` regardless of paths. Independent of the backend pipeline — frontend can finish *before* the backend is up, which causes "build hits old API shape, fails prerender" timing bugs. Forcing pages to `dynamic = "force-dynamic"` (see Frontend SSR caveats above) is the durable fix; if you need to retrigger Amplify without a code change, use `aws --profile slpa-prod amplify start-job --app-id dil6fhehya5jf --branch-name main --job-type RELEASE`.

## Branch / PR workflow

- Feature branches and PRs target `dev`, not `main`.
- Claude opens + merges PRs into `dev`. PRs from `dev` to `main` are reviewed + merged by the user (autonomous prod merge only on explicit per-task authorization).
- Push commits before asking for review — local-only commits don't show on GitHub.

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
