# SLPA — Second Life Parcel Auctions

Player-to-player land auction platform for Second Life. Bridges a web-based auction UI to in-world Second Life via verification terminals, escrow objects, and bot services. Phase 1 supports Mainland parcels only.

The full design lives in [`docs/initial-design/DESIGN.md`](docs/initial-design/DESIGN.md). Implementation phases and tasks are under [`docs/implementation/`](docs/implementation/).

## Stack

| Layer    | Tech                                              |
|----------|---------------------------------------------------|
| Frontend | Next.js 16, React 19, TypeScript 5, Tailwind 4    |
| Backend  | Spring Boot 4, Java 26, Maven, JPA (Hibernate DDL) |
| Storage  | PostgreSQL 17, Redis 7, MinIO (S3-compatible)     |
| In-world | LSL scripts (Phase 6+)                            |

## Quick start (Docker Compose)

The fastest way to bring up the full stack is with Docker Compose. PostgreSQL, Redis, the Spring Boot backend, and the Next.js frontend all run in containers on a shared network.

```bash
cp .env.example .env       # adjust values if you want non-default ports/credentials
docker compose up --build
```

`.env` is gitignored. Never commit it.

Once everything is healthy:

| Service           | URL                              |
|-------------------|----------------------------------|
| Frontend          | http://localhost:3000            |
| Backend API       | http://localhost:8080            |
| Backend health    | http://localhost:8080/api/v1/health |
| PostgreSQL        | `localhost:5432` (user `slpa`)   |
| Redis             | `localhost:6379`                 |

To stop the stack: `docker compose down`. To reset the database too: `docker compose down -v` (drops the named volumes).

### Port conflicts

The compose stack binds host ports `5432`, `6379`, `8080`, and `3000`. If you already run standalone `slpa-postgres` / `slpa-redis` containers (e.g. from earlier in development), stop them first:

```bash
docker stop slpa-postgres slpa-redis
```

Or override the host ports in `.env`:

```bash
POSTGRES_PORT=5433
REDIS_PORT=6380
```

The container-to-container connections always use the standard ports inside the `slpa-net` network — only the host port mapping changes.

### Hot reload

- **Frontend** — Next.js dev server runs inside the container with the source bind-mounted from `./frontend`. Edits to `.tsx` / `.ts` / CSS files trigger HMR. `WATCHPACK_POLLING=true` is set so file watches survive the WSL2 boundary on Windows hosts.
- **Backend** — `./mvnw spring-boot:run` runs inside the container with `./backend/src` bind-mounted. Source edits do not auto-reload; restart the backend container to pick them up:

  ```bash
  docker compose restart backend
  ```

  Maven dependencies are cached in a named volume (`maven-cache`) so restarts stay fast.

## Local development without Docker

If you'd rather run the backend or frontend on the host (faster JVM start, IDE debugging, etc.) you only need PostgreSQL + Redis from compose:

```bash
docker compose up -d postgres redis
```

Then in two separate shells:

```bash
# Backend
cd backend
./mvnw spring-boot:run                # uses application.yml; pass -Dspring-boot.run.profiles=dev for the dev profile

# Frontend
cd frontend
npm install
npm run dev
```

The backend requires `JWT_SECRET` in production (environment variable, ≥ 256 bits base64-encoded) and uses a committed dev default in `application-dev.yml`. The `auth/` slice provides `/api/v1/auth/register`, `/api/v1/auth/login`, `/api/v1/auth/refresh`, `/api/v1/auth/logout`, and `/api/v1/auth/logout-all`. Access tokens are 15-minute HS256 JWTs returned in the response body; refresh tokens are DB-backed, rotated on every refresh, with reuse-detection cascade, held in an HttpOnly `Path=/api/v1/auth` cookie. See [`docs/superpowers/specs/2026-04-11-task-01-07-jwt-auth-backend-design.md`](docs/superpowers/specs/2026-04-11-task-01-07-jwt-auth-backend-design.md) for the full design.

The `verification/` slice provides `GET /api/v1/verification/active` and `POST /api/v1/verification/generate` for the player-verification flow. Codes are 6-digit numerics with a 15-minute TTL; generating a fresh code voids any prior active codes. The handler maps `AlreadyVerifiedException` (409), `CodeNotFoundException` (404), and `CodeCollisionException` (409) to RFC 9457 ProblemDetail responses. See [`docs/superpowers/specs/2026-04-13-epic-02-sub-1-verification-backend.md`](docs/superpowers/specs/2026-04-13-epic-02-sub-1-verification-backend.md).

The `sl/` slice exposes `POST /api/v1/sl/verify`, the header-gated endpoint that an in-world LSL terminal posts to once the player enters their 6-digit code on the device. The path is `permitAll` in `SecurityConfig` because browser JWTs do not exist in-world; `SlHeaderValidator` is the actual trust boundary, checking `X-SecondLife-Shard` against the configured grid and `X-SecondLife-Owner-Key` against `slpa.sl.trusted-owner-keys`. The orchestrator pre-checks `users.sl_avatar_uuid` for a friendly 409 before consuming the verification code, then links the avatar to the user account and marks `verified=true`. Slice-scoped `SlExceptionHandler` overrides the verification-package responses (400 for not-found, 409 for collision, 409 for the constraint-race avatar-already-linked path via `ConstraintNameExtractor`). The `slpa.sl` config block lives in `application.yml` (`expected-shard: Production`, empty key list), `application-dev.yml` (placeholder UUID `00000000-0000-0000-0000-000000000001` for Postman + integration tests), and `application-prod.yml` (empty list — the deploy pipeline injects real UUIDs and `SlStartupValidator` fails fast on `prod` startup if the list is still empty).

The `sl/` slice also holds two WebFlux `WebClient` clients used by the parcel lookup flow in Epic 03. `SlWorldApiClient` fetches parcel metadata HTML from `world.secondlife.com/place/{uuid}` and parses meta tags with Jsoup (`og:title`, `og:description`, `og:image`, `secondlife:region`, `ownerid`, `ownertype`, `area`, `maturityrating`, `position_x/y/z`); it retries 5xx / network errors with exponential backoff (`slpa.world-api.retry-attempts`, `slpa.world-api.retry-backoff-ms`), fails fast on 404 with `ParcelNotFoundInSlException` (mapped before `bodyToMono` so the retry loop does not eat it), and wraps any other failure as `ExternalApiTimeoutException`. `SlMapApiClient` POSTs to the SL Map CAP endpoint at `cap.secondlife.com/cap/0/{cap-uuid}` with a `URLEncoder`-encoded `var=<regionName>` body and parses the `coords[0] = X; coords[1] = Y;` JavaScript response via regex; it runs the same symmetric retry loop as `SlWorldApiClient` (`slpa.map-api.retry-attempts`, `slpa.map-api.retry-backoff-ms`) and throws `RegionNotFoundException` (→ HTTP 404) when the Map API returns 200 with no coords (i.e. the region name does not exist), distinct from the generic `ExternalApiTimeoutException` (→ HTTP 504) used for actual gateway failures. `MainlandContinents` is a static helper holding the 17 continent bounding boxes from the SL wiki's `ContinentDetector` page — it replaces the unreliable Grid Survey API dependency with a half-open-interval point-in-box check. Both clients are covered by WireMock-backed unit tests; Jsoup 1.17.2 and `org.wiremock:wiremock-standalone:3.10.0` (test scope) are the new deps.

The `parcel/` slice exposes `POST /api/v1/parcels/lookup` — an authenticated + SL-verified endpoint that resolves a parcel UUID to a persisted, shared `parcels` row. `ParcelLookupService` short-circuits on an existing row (one `parcels` row per SL parcel UUID, referenced by any number of auctions), otherwise fetches World API metadata, resolves region → grid coords via the Map API, runs the Mainland continent check, synthesizes a SLURL (`https://maps.secondlife.com/secondlife/{region}/{x}/{y}/{z}`, defaults `128/128/22` if the World API didn't return a position), and persists a `verified=true` row. Verification gating is inline in `ParcelController`: Spring Security enforces the JWT auth (401), then the controller throws `AccessDeniedException` (→ 403 via `GlobalExceptionHandler`) when `user.verified != true`. The four SL-flow exceptions have mappings on `GlobalExceptionHandler`: `ParcelNotFoundInSlException` → 404 / `SL_PARCEL_NOT_FOUND`, `RegionNotFoundException` → 404 / `SL_REGION_NOT_FOUND`, `NotMainlandException` → 422 / `NOT_MAINLAND`, `ExternalApiTimeoutException` → 504 / `SL_API_TIMEOUT`. `ParcelLookupServiceTest` covers the 4-case mocked matrix (new UUID on Mainland, cache hit, World API 404, non-Mainland coords); `ParcelControllerIntegrationTest` covers the 6-case endpoint matrix (happy path, cache hit verified via `Mockito.verify(worldApi, times(1))`, non-Mainland 422, malformed UUID 400, unauthenticated 401, unverified 403) under a full Spring Boot context with `@MockitoBean` on the SL clients so no outbound network calls happen.

The `auction/` slice adds auction CRUD + state machine + cancellation (Epic 03 sub-spec 1, Task 4). `POST /api/v1/auctions`, `GET /api/v1/auctions/{id}`, `GET /api/v1/users/me/auctions`, `PUT /api/v1/auctions/{id}`, `PUT /api/v1/auctions/{id}/cancel`, and `GET /api/v1/auctions/{id}/preview` round out the surface. Write paths are SL-verified (same inline guard as the parcel slice); reads require auth but not verification. The linchpin is the **two-DTO model with type-enforced status collapse**: `SellerAuctionResponse` carries the full internal state (commission, listing fee, winnerId, verification notes, pendingVerification), while `PublicAuctionResponse` is typed on `PublicAuctionStatus { ACTIVE, ENDED }` — the four terminal statuses (COMPLETED / CANCELLED / EXPIRED / DISPUTED) collapse to `ENDED` in an exhaustive `switch`, and the four pre-ACTIVE statuses (DRAFT / DRAFT_PAID / VERIFICATION_PENDING / VERIFICATION_FAILED) throw `IllegalStateException` from the mapper because `AuctionController.get()` is responsible for 404-hiding those from non-sellers before the mapper is ever called. Field-level validation is layered: JSR-380 (`@NotNull`, `@Min`, `@Size(max=10)` on tags, `@Size(max=5000)` on sellerDesc) at the DTO, plus `AuctionService` checks for the enum sets (`durationHours ∈ {24,48,72,168,336}`, `snipeWindowMin ∈ {5,10,15,30,60}` iff `snipeProtect`) and the pricing monotonicity (`reserve >= starting`, `buyNow >= max(starting, reserve ?? 0)`). `CancellationService` implements the state matrix: DRAFT / DRAFT_PAID / VERIFICATION_PENDING / VERIFICATION_FAILED / ACTIVE (pre-`endsAt`) are cancellable, everything else throws `InvalidAuctionStateException` (→ 409 via the slice-scoped `AuctionExceptionHandler`); a `CancellationLog` row is always written, a `ListingFeeRefund` row (PENDING) is written only when `listingFeePaid && from != ACTIVE`, and `user.cancelledWithBids` increments only on ACTIVE+bids cancellations. `AuctionExceptionHandler` sits at `@Order(HIGHEST_PRECEDENCE + 10)` and maps `AuctionNotFoundException` → 404, `InvalidAuctionStateException` → 409, and `IllegalArgumentException` (from the service-layer validation + unknown tag codes + missing parcel) → 400. Test coverage: `AuctionDtoMapperTest` (17 cases — parameterized status-collapse matrices + public-view field hiding), `AuctionServiceTest` (19 cases — pricing / duration / snipe / tag resolution / update-state matrix), `CancellationServiceTest` (14 cases — state × fee × bids matrix + cancel-after-end), `AuctionControllerIntegrationTest` (14 cases — create happy path, unverified 403, bad parcelId 400, seller-vs-public-view split, 404-hides-pre-ACTIVE, cancel refund-row round-trip, cancel-from-COMPLETED 409). `slpa.listing-fee.amount-lindens` (default 100) and `slpa.commission.default-rate` (default 0.05) are new in `application.yml`.

All HTTP routes above are mirrored in the `SLPA` Postman collection (collection id `8070328-cf3e1190-cb71-4af3-8e2e-70c82dfee288` in the `SLPA` workspace at `https://scatr-devs.postman.co`) with `Auth/`, `Users/`, `Verification/`, `SL/`, and `Dev/` folders, saved example responses, and variable-chaining test scripts that thread `accessToken`, `refreshToken`, `userId`, `verificationCode`, and `avatarSize` between requests via the `SLPA Dev` environment. The `Users/` folder holds `Create user`, `Get current user`, `Get user by id`, `Update current user` (PUT /me), `Upload avatar` (multipart — pick a local file from the Body tab before firing), and `Get user avatar` (tweak `avatarSize` to `64` / `128` / `256` to flip sizes). The collection is the canonical manual-test surface for the backend; if you add a new endpoint, add it to the collection in the same task.

A dev-profile-only helper sits at `POST /api/v1/dev/sl/simulate-verify` so the frontend dashboard can exercise the full SL verification path from a browser before Epic 11 ships real LSL scripts. The body only requires `verificationCode`; `DevSimulateRequest.toSlVerifyRequest()` defaults the avatar metadata (random UUID per call, `Dev Tester` name, payInfo `3`) and `DevSlSimulateController` synthesizes the SL headers internally from the first `slpa.sl.trusted-owner-keys` entry, then delegates to the real `SlVerificationService` so the dev path exercises identical orchestration and exception mapping. The bean is annotated `@Profile("dev")` (the real gate); `SecurityConfig` permits `/api/v1/dev/**` unconditionally so any prod request 404s at the MVC layer because no handler bean exists. `DevSlSimulateBeanProfileTest` pins the gate by booting the context under a non-dev profile and asserting the controller field is `null`.

The frontend dev server runs at `http://localhost:3000`. Component primitives live under `src/components/ui/` (Button, IconButton, Input, Card, StatusBadge, Avatar, ThemeToggle, Dropdown, Tabs, CountdownTimer, CodeDisplay, EmptyState, LoadingSpinner, Toast), layout shell under `src/components/layout/`, and the typed API client + auth stub + cn helper under `src/lib/`. Theme tokens (M3 Material Design vocabulary, both light and dark) live in `src/app/globals.css`. The full design rationale is in [`docs/superpowers/specs/2026-04-10-task-01-06-frontend-foundation-design.md`](docs/superpowers/specs/2026-04-10-task-01-06-frontend-foundation-design.md).

The frontend has three auth pages (`/register`, `/login`, `/forgot-password`) wired to the backend JWT auth endpoints from Task 01-07. Forms use react-hook-form + zod with backend ProblemDetail error mapping. Tests run against MSW mocks; the canary `lib/api.401-interceptor.test.tsx` proves the API client's auto-refresh-and-retry behavior. See [`docs/superpowers/specs/2026-04-12-task-01-08-frontend-auth-design.md`](docs/superpowers/specs/2026-04-12-task-01-08-frontend-auth-design.md) for the full design.

The root route `/` is a public marketing landing page composed of four sections: a hero with auth-aware CTAs, a 4-step "how it works" flow, a 6-card asymmetric features bento grid, and an auth-hidden gradient CTA block (Task 01-10). All section components live under `src/components/marketing/`.

The `/dashboard` route tree (Epic 02 sub-spec 2b) uses Next.js 16 route groups to gate access. Unverified users are captured by the layout into `/dashboard/verify`, a full-page verification takeover that polls `/api/v1/users/me` every 5 seconds and auto-transitions to `/dashboard/overview` once the backend reports `verified: true`. Verified users see a tabbed dashboard with `/dashboard/overview`, `/dashboard/bids`, and `/dashboard/listings` tabs. `/users/[id]` is the public profile page (works for authenticated and anonymous visitors). Domain components live under `src/components/user/` (UnverifiedVerifyFlow, VerificationCodeDisplay, VerifiedOverview, ProfileEditForm, ProfilePictureUploader, VerifiedIdentityCard, PublicProfileView, ReputationStars, NewSellerBadge). The data layer in `src/lib/user/` provides TanStack Query hooks (`useCurrentUser`, `useUpdateProfile`, `useUploadAvatar`, `useActiveVerificationCode`, `useGenerateVerificationCode`). UI primitives added for this epic: Tabs, CountdownTimer, CodeDisplay, EmptyState, LoadingSpinner, and a portal-rendered Toast notification system (`src/components/ui/Toast/`).

Task 01-09 wires the real-time pipe end-to-end. The backend exposes a STOMP-over-WebSocket endpoint at `/ws` with SockJS fallback, authenticated at the STOMP `CONNECT` frame by `JwtChannelInterceptor` (the HTTP upgrade itself is `permitAll` — browsers cannot send custom headers on a WebSocket handshake). A dev/test-only broadcast endpoint `POST /api/v1/ws-test/broadcast` fans messages out to `/topic/ws-test`. The frontend ships a singleton STOMP client in `lib/ws/client.ts` with a reusable `ensureFreshAccessToken` stampede guard shared with the HTTP 401 interceptor, plus `useConnectionState` / `useStompSubscription` hooks, and a development-only verification harness page at [`/dev/ws-test`](http://localhost:3000/dev/ws-test) (404s in production builds).

The `storage/` slice wraps an S3-compatible object store (MinIO in dev, AWS S3 in prod) behind an `ObjectStorageService` interface with `put` / `get` / `delete` / `deletePrefix` / `exists`. `S3ClientConfig` builds the AWS SDK v2 `S3Client` bean, picking `StaticCredentialsProvider` when `slpa.storage.access-key-id` + `secret-access-key` are set (dev/test) and falling back to `DefaultCredentialsProvider` in prod, and applies `endpointOverride` + `forcePathStyle` only when configured. `StorageStartupValidator` runs on `ApplicationReadyEvent`: in the `prod` profile it fails fast if the bucket is missing, in non-prod it auto-creates the bucket so `docker compose up` on a fresh MinIO volume just works. `S3ObjectStorageService.deletePrefix` paginates via `isTruncated` + continuation token so >1000 keys are handled, and `get()` carries a javadoc warning that it loads the full object into memory — fine for avatar-sized PNGs but must be refactored to streaming before reuse for larger parcel/listing photos. Multipart upload is capped at 2MB for both file and request size. The `user/AvatarImageProcessor` component (Thumbnailator + ImageIO) feeds it: it sniffs the image format from the bytes (not the client `Content-Type`), rejects anything outside `{jpeg, png, webp}`, center-crops to square, and emits PNG bytes at 64/128/256 px — covered by 8 fixture-driven unit tests under `backend/src/test/resources/fixtures/`.

`PUT /api/v1/users/me` edits the authenticated user's `displayName` (1–50 chars) and `bio` (≤ 500 chars); both fields are optional and null means "do not touch this column." `UpdateUserRequest` carries `@JsonIgnoreProperties(ignoreUnknown = false)` and the global `spring.jackson.deserialization.fail-on-unknown-properties: true` is on to reject any extra field as a privilege-escalation guard (canary test: `UserControllerUpdateMeSliceTest.put_me_rejectsUnknownFields_returns400`). `UserExceptionHandler` is a slice-scoped `@RestControllerAdvice` at `@Order(LOWEST_PRECEDENCE - 100)` that intercepts `HttpMessageNotReadableException`, unwraps Jackson's `UnrecognizedPropertyException`, and returns a 400 ProblemDetail with `type: .../user/unknown-field` and `code: USER_UNKNOWN_FIELD`. `DELETE /me` remains a 501 stub pending a future GDPR / soft-delete sub-spec.

Avatars get two new endpoints: `POST /api/v1/users/me/avatar` (multipart, authenticated) runs `AvatarService.upload` which validates the 2MB limit, delegates to `AvatarImageProcessor` for format sniffing + 64/128/256 center-crop resize, puts all three PNGs to S3 under `avatars/{userId}/{size}.png`, and sets `users.profile_pic_url` to the proxy URL — all in a single `@Transactional` boundary (spec §10 + FOOTGUNS §F.29 explain why the narrow boundary was walked back). `GET /api/v1/users/{id}/avatar/{size}` is the public proxy: proxies bytes from S3 with `Cache-Control: public, max-age=86400, immutable`, or falls back to a classpath placeholder PNG for both "user has no avatar" and "orphaned DB URL, S3 key missing" paths. Three handlers land on `UserExceptionHandler` (`AvatarTooLargeException` → 413, `UnsupportedImageFormatException` → 400, `InvalidAvatarSizeException` → 400) and one on `GlobalExceptionHandler` (`MaxUploadSizeExceededException` → 413, same URI + code as the service-layer version because clients must not distinguish which layer caught the oversized upload — see FOOTGUNS §F.28 for why that one cannot live in a slice advice).

## Running tests

```bash
cd backend && ./mvnw test             # ~276 unit / slice / integration tests incl. JWT auth, verification, SL verification, dev simulate, the /api/v1 prefix migration smoke test, the S3 object storage unit tests, the AvatarImageProcessor fixture-driven tests, the PUT /me slice suite with the unknown-field security canary, the avatar upload + public proxy slice suite, the AvatarUploadFlowIntegrationTest that round-trips register -> upload -> fetch against real dev MinIO, the SL World/Map API WireMock unit tests + MainlandContinents point-in-box test suite, the ParcelLookupService + ParcelController matrix for POST /api/v1/parcels/lookup, and the auction CRUD + state-machine + cancellation matrix (AuctionDtoMapperTest, AuctionServiceTest, CancellationServiceTest, AuctionControllerIntegrationTest) for Task 4 of Epic 03 sub-spec 1 (integration tests need postgres on :5432 and MinIO on :9000)
cd frontend && npm run test           # vitest unit + integration tests (~263 cases — primitives, layout, lib, auth flows, dashboard domain components, public profile, integration smoke)
cd frontend && npm run lint           # eslint
cd frontend && npm run verify         # grep-based rules: no dark: variants, no hex colors, no inline styles, every primitive has a test
```

## Repository layout

```
.
├── backend/                 Spring Boot app
│   └── src/main/java/com/slparcelauctions/backend/
│       ├── config/          SecurityConfig, PasswordConfig, ClockConfig, ...
│       ├── common/          GlobalExceptionHandler, shared utilities
│       ├── auth/            JWT auth slice (register, login, refresh, logout, logout-all)
│       ├── user/            User vertical slice (entity, repo, service, controller, DTOs)
│       ├── verification/    Verification code slice (active, generate)
│       ├── sl/              SL integration slice (header-gated /sl/verify)
│       ├── storage/         Object storage slice (S3Client config, ObjectStorageService, startup validator)
│       ├── parcel/          Parcel entity + repository + ParcelLookupService + ParcelController (shared parcel rows, World+Map orchestration)
│       ├── parceltag/       Parcel tag entity + repository (many-to-many with auctions)
│       ├── auction/         Auction entity + repository + CancellationLog + ListingFeeRefund + AuctionPhoto + parcel-locking index initializer
│       └── bot/             Bot task queue entity + repository (verification bot work units)
├── frontend/                Next.js app
│   └── src/
│       ├── components/ui/   UI primitives (Button, Tabs, Toast, etc.)
│       ├── components/user/ Domain composites (verify flow, profile, reputation)
│       ├── lib/user/        TanStack Query hooks + typed API client for /users
│       └── app/dashboard/   Dashboard route tree with verification gate
├── docs/
│   ├── initial-design/      Spec, schema, user flows
│   └── implementation/      Phased task breakdown + CONVENTIONS.md
├── docker-compose.yml       Full local dev stack
└── .env.example             Documented env vars for compose
```

## Production deployment

Production deployment is **not covered in Phase 1**. This stack is wired for local development only. Before any non-local deployment:

- Rotate every value in `.env.example` tagged `# DEV ONLY` — `POSTGRES_PASSWORD`, `CORS_ALLOWED_ORIGIN`, `NEXT_PUBLIC_API_URL`, plus any future `*_SECRET` / `*_TOKEN` introduced by later tasks (JWT signing key in Task 01-07, etc.).
- Set `SPRING_PROFILES_ACTIVE=prod` and review `application-prod.yml` before shipping. Global `ddl-auto: update` is the dev/source-of-truth mode while the schema stabilizes in Phase 1; the prod profile must override this (and the eventual production migration strategy will replace the disabled Flyway).
- Add a reverse proxy / TLS terminator (nginx, Caddy, cloud load balancer) in front of the backend; the dev stack ships HTTP only.
- Lock down the CORS allow-list to the real frontend origin instead of `localhost:3000`.
- Replace `Dockerfile.dev` with a multi-stage production Dockerfile that builds a layered Spring Boot fat-jar and runs on a JRE base image, not a JDK.
- Decide on a database backup / point-in-time-recovery strategy — the named `postgres-data` volume is fine for local dev, not for production.

Track these as part of the pre-launch checklist; do not ship without each one signed off.

## Conventions

Read [`docs/implementation/CONVENTIONS.md`](docs/implementation/CONVENTIONS.md) before contributing. Highlights:

- Lombok is required for entities, services, and controllers — no hand-written getters/setters/loggers.
- New schema changes go through JPA entities (`ddl-auto: update` in dev), not new Flyway migrations.
- Each task ships as one vertical slice (entity → repo → service → controller → tests).
- Feature-based packages (`user/`, `parcel/`, `auction/`, …), not layer-based.
