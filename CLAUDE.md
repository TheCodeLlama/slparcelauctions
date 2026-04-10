# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

SLPA (Second Life Parcel Auctions) is a player-to-player land auction platform for Second Life. It bridges web-based auctions with the Second Life virtual world through verification terminals, escrow objects, and bot services. Phase 1 supports Mainland parcels only.

## Architecture

```
Frontend (Next.js 16 / React 19 / TypeScript 5 / Tailwind CSS 4)
    ↕ REST API + WebSocket (STOMP)
Backend (Spring Boot 4 / Java 26 / PostgreSQL / Redis)
    ↕ HTTP (llHTTPRequest / HTTP-in)
In-World (Second Life LSL Scripts)
```

**Backend services**: Auth & Identity, Auction Engine, Escrow Manager, Verification Service, Notifications, SL World API Client (ownership polling).

**Frontend pages**: Register/Verify, Browse Listings, Auction Room (live WebSocket), Dashboard (My Bids/Sales).

## Commands

### Frontend (`cd frontend`)
```bash
npm run dev       # Dev server at localhost:3000
npm run build     # Production build
npm run start     # Start production server
npm run lint      # ESLint (v9)
```

### Backend (`cd backend`)
```bash
./mvnw spring-boot:run      # Run Spring Boot app
./mvnw test                 # Run all tests
./mvnw test -Dtest=ClassName              # Run single test class
./mvnw test -Dtest=ClassName#methodName   # Run single test method
./mvnw clean package        # Build JAR
```

## Framework Version Warnings

- **Next.js 16.2.3** has breaking changes from earlier versions. Read `frontend/node_modules/next/dist/docs/` before writing frontend code. See `frontend/AGENTS.md`.
- **Spring Boot 4.0.5** with **Java 26** - use latest conventions.
- **Tailwind CSS 4** uses the new `@tailwindcss/postcss` plugin (not the legacy `tailwindcss` PostCSS plugin).

## Key Directories

- `docs/initial-design/DESIGN.md` - Full specification (architecture, user flows, API contracts, DB schema, security)
- `docs/implementation/PHASES.md` - 11 implementation phases with dependency graph
- `docs/implementation/epic-NN/` - Detailed task breakdowns per phase with acceptance criteria
- `backend/src/main/resources/db/migration/` - Flyway SQL migrations (naming: `V1__description.sql`)
- `backend/src/main/java/com/slparcelauctions/backend/` - Java source root

## Backend Stack Details

- **ORM**: Spring Data JPA / Hibernate with Lombok for boilerplate
- **Database migrations**: Flyway (SQL-based, not Java)
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

## Infrastructure Dependencies

- **PostgreSQL** - relational data (users, auctions, escrow, reviews)
- **Redis** - sessions, bid rate limiting, auction countdown timers

## Implementation Status

The project is in early scaffolding (Phase 1). The frontend has a default Next.js page and the backend has only the Spring Boot entry point. All 11 phases are documented with task breakdowns but no application logic is implemented yet.
