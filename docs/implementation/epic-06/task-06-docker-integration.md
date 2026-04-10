# Task 06-06: Docker & Backend Integration

> **Before starting:** read [CONVENTIONS.md](../CONVENTIONS.md) for project-wide rules (no new migrations, Lombok required, vertical slices, feature-based packages).

## Goal

Containerize the bot service so it runs alongside the Java backend and frontend in Docker Compose, and wire up the HTTP communication between backend and bot service.

## Context

Docker Compose exists from Epic 01 Task 05 with backend, frontend, PostgreSQL, and Redis. The bot service is a separate .NET container that needs to join the stack.

## What Needs to Happen

- **Dockerfile for bot service:**
  - .NET SDK build stage → runtime image
  - Expose API port (e.g., 5100)
  - Health check endpoint

- **Add to Docker Compose:**
  - New `bot` service alongside existing services
  - Environment variables for: SL bot credentials, primary escrow UUID, backend callback URL, API port
  - Depends on backend being up (for callbacks)
  - Restart policy (auto-restart on crash)

- **Java backend integration endpoints:**
  - Create endpoints on the Java backend that the bot service calls back to:
    - POST /api/v1/internal/bot/verify-result - receives verification results
    - POST /api/v1/internal/bot/monitor-result - receives monitoring check results
  - These are internal-only endpoints (not exposed to public, only bot service calls them)
  - Process results: update parcel verification status, update auction status, update escrow status, create fraud flags as needed

- **Java backend task creation:**
  - When a Method C verification is requested (Epic 03 Task 03): POST task to bot service
  - When a Bot-Verified auction goes ACTIVE: POST MONITOR_AUCTION task to bot service
  - When a Bot-Verified escrow starts: POST MONITOR_ESCROW task to bot service
  - When auction ends or escrow completes: DELETE monitoring task from bot service

- **Health monitoring:**
  - Backend periodically checks bot service health endpoint
  - If bot service is down: log warning, fall back to World API-only monitoring
  - Dashboard/admin can view bot pool status

## Acceptance Criteria

- Bot service builds and runs in Docker alongside existing services
- `docker compose up` starts the full stack including bot service
- Backend can create tasks on the bot service via HTTP
- Bot service delivers results to backend callback endpoints
- Backend processes results correctly (updates verification, auction, escrow status)
- Bot service restart doesn't lose in-flight one-shot tasks (acceptable for MVP - backend can retry)
- Health check works and backend can detect bot service status
- Environment variables properly configured for SL credentials

## Notes

- For development without real SL bot accounts: add a mock mode to the bot service that simulates teleport delays and returns configurable ParcelProperties data. This lets the full stack run locally.
- The internal callback endpoints should validate a shared secret (same as terminal communication) to prevent unauthorized calls.
- Bot credentials should NEVER be committed to source. Use environment variables or a secrets manager.
- Start with a simple HTTP client on both sides. If message volume grows, a message queue (RabbitMQ) would be better, but HTTP is fine for MVP.
