# SLPA — Second Life Parcel Auctions

Player-to-player land auction platform for Second Life. Bridges a web-based auction UI to in-world Second Life via verification terminals, escrow objects, and bot services. Phase 1 supports Mainland parcels only.

The full design lives in [`docs/initial-design/DESIGN.md`](docs/initial-design/DESIGN.md). Implementation phases and tasks are under [`docs/implementation/`](docs/implementation/).

## Stack

| Layer    | Tech                                              |
|----------|---------------------------------------------------|
| Frontend | Next.js 16, React 19, TypeScript 5, Tailwind 4    |
| Backend  | Spring Boot 4, Java 26, Maven, JPA + Flyway       |
| Storage  | PostgreSQL 17, Redis 7                            |
| In-world | LSL scripts (Phase 6+)                            |

## Quick start (Docker Compose)

The fastest way to bring up the full stack is with Docker Compose. PostgreSQL, Redis, the Spring Boot backend, and the Next.js frontend all run in containers on a shared network.

```bash
cp .env.example .env       # adjust values if you want non-default ports/credentials
docker compose up --build
```

Once everything is healthy:

| Service           | URL                              |
|-------------------|----------------------------------|
| Frontend          | http://localhost:3000            |
| Backend API       | http://localhost:8080            |
| Backend health    | http://localhost:8080/api/health |
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

## Running tests

```bash
cd backend && ./mvnw test             # unit, slice, and integration tests (integration tests need postgres on :5432)
cd frontend && npm run lint           # vitest will land in a later phase
```

## Repository layout

```
.
├── backend/                 Spring Boot app
│   └── src/main/java/com/slparcelauctions/backend/
│       ├── config/          SecurityConfig, PasswordConfig, ...
│       ├── common/          GlobalExceptionHandler, shared utilities
│       └── user/            First vertical slice (entity, repo, service, controller, DTOs)
├── frontend/                Next.js app
├── docs/
│   ├── initial-design/      Spec, schema, user flows
│   └── implementation/      Phased task breakdown + CONVENTIONS.md
├── docker-compose.yml       Full local dev stack
└── .env.example             Documented env vars for compose
```

## Conventions

Read [`docs/implementation/CONVENTIONS.md`](docs/implementation/CONVENTIONS.md) before contributing. Highlights:

- Lombok is required for entities, services, and controllers — no hand-written getters/setters/loggers.
- New schema changes go through JPA entities (`ddl-auto: update` in dev), not new Flyway migrations.
- Each task ships as one vertical slice (entity → repo → service → controller → tests).
- Feature-based packages (`user/`, `parcel/`, `auction/`, …), not layer-based.
