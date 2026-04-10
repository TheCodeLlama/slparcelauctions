# Phase 1: Project Foundation

_Reference: DESIGN.md Sections 2, 7, 15 - and [CONVENTIONS.md](../CONVENTIONS.md) before starting any task_

---

## Goal

Set up the project scaffolding for a Spring Boot backend and Next.js frontend with a PostgreSQL database, and ship the first working vertical slice (User domain + auth). Everything should build, run, and have a working dev environment out of the box.

---

## What Needs to Happen

### Backend (Java / Spring Boot)

- Spring Boot application with feature-based package structure (`.user`, `.auction`, etc. - not layer-based)
- PostgreSQL database connection. Baseline schema already exists via Flyway V1/V2. **Going forward, JPA entities are the source of truth** with `ddl-auto: update` in dev - no new migrations (see CONVENTIONS.md)
- First vertical slice: User domain - entity, repository, service, DTOs, controller, tests (Lombok throughout)
- JWT-based authentication (register, login, refresh token) built on the User slice
- CORS configuration for frontend
- WebSocket support (STOMP over SockJS) wired up but no handlers yet
- Redis connection for session/cache (can be optional for local dev)
- Health check endpoint
- Environment-based configuration (dev, prod)

### Frontend (Next.js)

- Next.js app with TypeScript
- Basic page structure: home, login, register, dashboard (placeholder pages)
- API client library for backend communication
- JWT token management (store, refresh, attach to requests)
- WebSocket client setup (connection manager, reconnection logic)
- Responsive layout shell (header, nav, content area)
- Tailwind CSS or similar utility-first CSS framework

### Dev Environment

- Docker Compose for local development (PostgreSQL, Redis, backend, frontend)
- README with setup instructions
- `.env.example` files for both frontend and backend

---

## Acceptance Criteria

- `docker compose up` starts the full stack
- Can register a new user account via API
- Can log in and receive a JWT token
- Can access a protected endpoint with the JWT
- User table and its entity exist; other tables from V1/V2 remain empty until their epics add entities that use them
- Frontend loads and shows placeholder pages
- WebSocket connection establishes between frontend and backend
- Backend boots cleanly on a fresh database (Flyway V1/V2 baseline + JPA `ddl-auto: update` thereafter)
