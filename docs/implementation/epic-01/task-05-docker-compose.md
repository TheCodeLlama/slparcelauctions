# Task 01-05: Docker Compose for Local Development

## Goal

Create a Docker Compose setup so the full stack (PostgreSQL, Redis, backend, frontend) can be started with a single command for local development.

## Context

Backend is a Spring Boot application (Java 26, Maven). Frontend is Next.js 16 with Tailwind 4. Both are already scaffolded in their respective directories.

## What Needs to Happen

- Create a docker-compose.yml at the project root
- PostgreSQL 17 service with a dev database, user, and password pre-configured
- Redis service (latest stable)
- Backend service that builds and runs the Spring Boot app with the `dev` profile
- Frontend service that runs `npm run dev` with hot reload
- Proper networking so frontend can reach backend, backend can reach PostgreSQL and Redis
- Volume mounts for source code (hot reload in dev)
- .env.example file at the project root documenting all environment variables
- Update the project README with setup instructions

## Acceptance Criteria

- `docker compose up` starts all four services without errors
- PostgreSQL is accessible and Flyway migrations run on backend startup
- Redis is accessible from the backend
- Frontend is accessible at localhost:3000
- Backend API is accessible at localhost:8080
- GET /api/health returns 200 OK through the running stack
- Source code changes trigger hot reload for both frontend and backend

## Notes

- For backend hot reload in Docker, consider spring-boot-devtools or just rely on rebuilding. Hot reload is nice-to-have, not critical.
- PostgreSQL data should persist via a named volume so it survives container restarts.
- Make sure the frontend .env points to the backend URL inside Docker networking.
