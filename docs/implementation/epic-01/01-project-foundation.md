# Phase 1: Project Foundation

_Reference: DESIGN.md Sections 2, 7, 15_

---

## Goal

Set up the project scaffolding for a Spring Boot backend and Next.js frontend with a PostgreSQL database. Everything should build, run, and have a working dev environment out of the box.

---

## What Needs to Happen

### Backend (Java / Spring Boot)

- Spring Boot application with standard project structure
- PostgreSQL database connection with migrations (Flyway or Liquibase)
- All database tables from DESIGN.md Section 7 created as migrations
- JPA/Hibernate entity classes for all tables
- JWT-based authentication (register, login, refresh token)
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
- All database tables from DESIGN.md exist and have correct relationships/constraints
- Frontend loads and shows placeholder pages
- WebSocket connection establishes between frontend and backend
- Database migrations run cleanly on a fresh database
