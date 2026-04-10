# Task 01-01: Spring Boot Configuration

> **Before starting:** read [CONVENTIONS.md](../CONVENTIONS.md) for project-wide rules (no new migrations, Lombok required, vertical slices, feature-based packages).

## Goal

Configure the Spring Boot application with database connection, Flyway migration support, CORS, and environment-based profiles so the backend can connect to PostgreSQL and run migrations on startup.

## Context

The Spring Boot project is already initialized with all necessary dependencies in pom.xml (data-jpa, flyway, security, websocket, redis, validation, webmvc, webflux, postgresql, lombok). The application.properties file only has `spring.application.name=backend`. This task configures everything.

## What Needs to Happen

- Configure application.yml (or application.properties) with PostgreSQL connection settings, Flyway migration path, Redis connection, and server port
- Set up environment-based profiles: `dev` (local Docker PostgreSQL/Redis) and `prod` (external connection strings via environment variables)
- Configure CORS to allow requests from the frontend origin (localhost:3000 in dev)
- Configure Spring Security to permit public endpoints (health check, auth routes) and require authentication for everything else - but leave auth as a placeholder (no JWT yet, that's a later task)
- Add a simple health check endpoint (GET /api/health) that returns 200 OK
- Create an empty Flyway migration directory so Spring Boot starts without errors

## Acceptance Criteria

- Application starts successfully with `dev` profile when PostgreSQL and Redis are available
- GET /api/health returns 200 OK
- Flyway runs on startup without errors (no migrations yet, just the empty directory)
- CORS allows requests from localhost:3000
- Spring Security does not block the health endpoint
- Configuration values can be overridden via environment variables for production

## Notes

- Don't configure JWT or auth logic yet - just set up the security filter chain with placeholder permit-all for now. Task 01-07 handles JWT.
- Redis connection should be optional for dev (app should start even if Redis isn't running, just with degraded session handling).
- Flyway migration directory: `src/main/resources/db/migration/`
