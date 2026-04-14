# SLPA — Second Life Parcel Auctions

Player-to-player land auction platform for Second Life. Bridges a web-based auction UI to in-world Second Life via verification terminals, escrow objects, and bot services. Phase 1 supports Mainland parcels only.

The full design lives in [`docs/initial-design/DESIGN.md`](docs/initial-design/DESIGN.md). Implementation phases and tasks are under [`docs/implementation/`](docs/implementation/).

## Stack

| Layer    | Tech                                              |
|----------|---------------------------------------------------|
| Frontend | Next.js 16, React 19, TypeScript 5, Tailwind 4    |
| Backend  | Spring Boot 4, Java 26, Maven, JPA + Flyway       |
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

All HTTP routes above are mirrored in the `SLPA` Postman collection (collection id `8070328-cf3e1190-cb71-4af3-8e2e-70c82dfee288` in the `SLPA` workspace at `https://scatr-devs.postman.co`) with `Auth/`, `Users/`, `Verification/`, `SL/`, and `Dev/` folders, saved example responses, and variable-chaining test scripts that thread `accessToken`, `refreshToken`, `userId`, and `verificationCode` between requests via the `SLPA Dev` environment. The collection is the canonical manual-test surface for the backend; if you add a new endpoint, add it to the collection in the same task.

A dev-profile-only helper sits at `POST /api/v1/dev/sl/simulate-verify` so the frontend dashboard can exercise the full SL verification path from a browser before Epic 11 ships real LSL scripts. The body only requires `verificationCode`; `DevSimulateRequest.toSlVerifyRequest()` defaults the avatar metadata (random UUID per call, `Dev Tester` name, payInfo `3`) and `DevSlSimulateController` synthesizes the SL headers internally from the first `slpa.sl.trusted-owner-keys` entry, then delegates to the real `SlVerificationService` so the dev path exercises identical orchestration and exception mapping. The bean is annotated `@Profile("dev")` (the real gate); `SecurityConfig` permits `/api/v1/dev/**` unconditionally so any prod request 404s at the MVC layer because no handler bean exists. `DevSlSimulateBeanProfileTest` pins the gate by booting the context under a non-dev profile and asserting the controller field is `null`.

The frontend dev server runs at `http://localhost:3000`. Component primitives live under `src/components/ui/` (Button, IconButton, Input, Card, StatusBadge, Avatar, ThemeToggle, Dropdown), layout shell under `src/components/layout/`, and the typed API client + auth stub + cn helper under `src/lib/`. Theme tokens (M3 Material Design vocabulary, both light and dark) live in `src/app/globals.css`. The full design rationale is in [`docs/superpowers/specs/2026-04-10-task-01-06-frontend-foundation-design.md`](docs/superpowers/specs/2026-04-10-task-01-06-frontend-foundation-design.md).

The frontend has three auth pages (`/register`, `/login`, `/forgot-password`) wired to the backend JWT auth endpoints from Task 01-07. Forms use react-hook-form + zod with backend ProblemDetail error mapping. Tests run against MSW mocks; the canary `lib/api.401-interceptor.test.tsx` proves the API client's auto-refresh-and-retry behavior. See [`docs/superpowers/specs/2026-04-12-task-01-08-frontend-auth-design.md`](docs/superpowers/specs/2026-04-12-task-01-08-frontend-auth-design.md) for the full design.

The root route `/` is a public marketing landing page composed of four sections: a hero with auth-aware CTAs, a 4-step "how it works" flow, a 6-card asymmetric features bento grid, and an auth-hidden gradient CTA block (Task 01-10). All section components live under `src/components/marketing/`.

Task 01-09 wires the real-time pipe end-to-end. The backend exposes a STOMP-over-WebSocket endpoint at `/ws` with SockJS fallback, authenticated at the STOMP `CONNECT` frame by `JwtChannelInterceptor` (the HTTP upgrade itself is `permitAll` — browsers cannot send custom headers on a WebSocket handshake). A dev/test-only broadcast endpoint `POST /api/v1/ws-test/broadcast` fans messages out to `/topic/ws-test`. The frontend ships a singleton STOMP client in `lib/ws/client.ts` with a reusable `ensureFreshAccessToken` stampede guard shared with the HTTP 401 interceptor, plus `useConnectionState` / `useStompSubscription` hooks, and a development-only verification harness page at [`/dev/ws-test`](http://localhost:3000/dev/ws-test) (404s in production builds).

The `storage/` slice wraps an S3-compatible object store (MinIO in dev, AWS S3 in prod) behind an `ObjectStorageService` interface with `put` / `get` / `delete` / `deletePrefix` / `exists`. `S3ClientConfig` builds the AWS SDK v2 `S3Client` bean, picking `StaticCredentialsProvider` when `slpa.storage.access-key-id` + `secret-access-key` are set (dev/test) and falling back to `DefaultCredentialsProvider` in prod, and applies `endpointOverride` + `forcePathStyle` only when configured. `StorageStartupValidator` runs on `ApplicationReadyEvent`: in the `prod` profile it fails fast if the bucket is missing, in non-prod it auto-creates the bucket so `docker compose up` on a fresh MinIO volume just works. `S3ObjectStorageService.deletePrefix` paginates via `isTruncated` + continuation token so >1000 keys are handled, and `get()` carries a javadoc warning that it loads the full object into memory — fine for avatar-sized PNGs but must be refactored to streaming before reuse for larger parcel/listing photos. Multipart upload is capped at 2MB for both file and request size. The `user/AvatarImageProcessor` component (Thumbnailator + ImageIO) feeds it: it sniffs the image format from the bytes (not the client `Content-Type`), rejects anything outside `{jpeg, png, webp}`, center-crops to square, and emits PNG bytes at 64/128/256 px — covered by 8 fixture-driven unit tests under `backend/src/test/resources/fixtures/`.

`PUT /api/v1/users/me` edits the authenticated user's `displayName` (1–50 chars) and `bio` (≤ 500 chars); both fields are optional and null means "do not touch this column." `UpdateUserRequest` carries `@JsonIgnoreProperties(ignoreUnknown = false)` and the global `spring.jackson.deserialization.fail-on-unknown-properties: true` is on to reject any extra field as a privilege-escalation guard (canary test: `UserControllerUpdateMeSliceTest.put_me_rejectsUnknownFields_returns400`). `UserExceptionHandler` is a slice-scoped `@RestControllerAdvice` at `@Order(LOWEST_PRECEDENCE - 100)` that intercepts `HttpMessageNotReadableException`, unwraps Jackson's `UnrecognizedPropertyException`, and returns a 400 ProblemDetail with `type: .../user/unknown-field` and `code: USER_UNKNOWN_FIELD`. `DELETE /me` remains a 501 stub pending a future GDPR / soft-delete sub-spec.

Avatars get two new endpoints: `POST /api/v1/users/me/avatar` (multipart, authenticated) runs `AvatarService.upload` which validates the 2MB limit, delegates to `AvatarImageProcessor` for format sniffing + 64/128/256 center-crop resize, puts all three PNGs to S3 under `avatars/{userId}/{size}.png`, and sets `users.profile_pic_url` to the proxy URL — all in a single `@Transactional` boundary (spec §10 + FOOTGUNS §F.29 explain why the narrow boundary was walked back). `GET /api/v1/users/{id}/avatar/{size}` is the public proxy: proxies bytes from S3 with `Cache-Control: public, max-age=86400, immutable`, or falls back to a classpath placeholder PNG for both "user has no avatar" and "orphaned DB URL, S3 key missing" paths. Three handlers land on `UserExceptionHandler` (`AvatarTooLargeException` → 413, `UnsupportedImageFormatException` → 400, `InvalidAvatarSizeException` → 400) and one on `GlobalExceptionHandler` (`MaxUploadSizeExceededException` → 413, same URI + code as the service-layer version because clients must not distinguish which layer caught the oversized upload — see FOOTGUNS §F.28 for why that one cannot live in a slice advice).

## Running tests

```bash
cd backend && ./mvnw test             # ~190 unit / slice / integration tests incl. JWT auth, verification, SL verification, dev simulate, the /api/v1 prefix migration smoke test, the S3 object storage unit tests, the AvatarImageProcessor fixture-driven tests, the PUT /me slice suite with the unknown-field security canary, the avatar upload + public proxy slice suite, and the AvatarUploadFlowIntegrationTest that round-trips register -> upload -> fetch against real dev MinIO (integration tests need postgres on :5432 and MinIO on :9000)
cd frontend && npm run test           # vitest unit tests (~185 cases — primitives, layout, lib, auth flows)
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
│       └── storage/         Object storage slice (S3Client config, ObjectStorageService, startup validator)
├── frontend/                Next.js app
├── docs/
│   ├── initial-design/      Spec, schema, user flows
│   └── implementation/      Phased task breakdown + CONVENTIONS.md
├── docker-compose.yml       Full local dev stack
└── .env.example             Documented env vars for compose
```

## Production deployment

Production deployment is **not covered in Phase 1**. This stack is wired for local development only. Before any non-local deployment:

- Rotate every value in `.env.example` tagged `# DEV ONLY` — `POSTGRES_PASSWORD`, `CORS_ALLOWED_ORIGIN`, `NEXT_PUBLIC_API_URL`, plus any future `*_SECRET` / `*_TOKEN` introduced by later tasks (JWT signing key in Task 01-07, etc.).
- Set `SPRING_PROFILES_ACTIVE=prod` and review `application-prod.yml` to confirm there are no hardcoded credentials and `ddl-auto` is `validate` (not `update`).
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
