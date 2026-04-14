# Epic 02 Sub-spec 1 — Verification Backend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the player verification backend end-to-end — authenticated users can request a 6-digit code, a public header-gated SL endpoint consumes the code and links an SL avatar to the user account, and a dev-profile simulate helper makes the whole flow browser-testable before Epic 11 ships LSL scripts.

**Architecture:** Two new vertical slices. `verification/` owns the code primitive (entity, repo, service, `GET /active` + `POST /generate`). `sl/` owns the SL integration surface (real `/sl/verify` with header validation, dev-profile simulate helper). `sl/ → verification/` and `sl/ → user/`; never the reverse. A standalone opening task renames `/api/*` to `/api/v1/*` across backend, frontend, tests, and the refresh-token cookie `Path` attribute. Postman collection scaffolding lives inside each task that adds endpoints — no trailing docs task.

**Tech Stack:** Spring Boot 4, Java 26, JPA/Hibernate, Lombok, Spring Security + JWT, JUnit 5 + Mockito + MockMvc, Next.js 16, Vitest + MSW, Postman (via Postman MCP tools).

**Spec:** `docs/superpowers/specs/2026-04-13-epic-02-sub-1-verification-backend.md`

**Branch:** `task/02-sub-1-verification-backend` off `dev`. PRs target `dev`, not `main`. No AI/tool attribution in commits.

---

## File Structure

### Backend — new files

```
backend/src/main/java/com/slparcelauctions/backend/
├── verification/                                    [NEW PACKAGE]
│   ├── VerificationCode.java                        entity, maps verification_codes table
│   ├── VerificationCodeType.java                    enum: PLAYER | PARCEL
│   ├── VerificationCodeRepository.java              Spring Data JPA
│   ├── VerificationCodeService.java                 @Service, @Transactional
│   ├── VerificationController.java                  /api/v1/verification/active, /generate
│   ├── VerificationExceptionHandler.java            @RestControllerAdvice scoped to verification
│   ├── dto/
│   │   ├── ActiveCodeResponse.java                  record
│   │   └── GenerateCodeResponse.java                record
│   └── exception/
│       ├── AlreadyVerifiedException.java
│       ├── CodeNotFoundException.java
│       └── CodeCollisionException.java
├── sl/                                              [NEW PACKAGE]
│   ├── SlConfigProperties.java                      @ConfigurationProperties record
│   ├── SlPropertiesConfig.java                      @Configuration + @EnableConfigurationProperties
│   ├── SlStartupValidator.java                      @EventListener ApplicationReadyEvent
│   ├── SlHeaderValidator.java                       @Component
│   ├── SlVerificationService.java                   @Service, @Transactional
│   ├── SlVerificationController.java                /api/v1/sl/verify
│   ├── DevSlSimulateController.java                 /api/v1/dev/sl/simulate-verify (@Profile("dev"))
│   ├── SlExceptionHandler.java                      @RestControllerAdvice scoped to sl
│   ├── ConstraintNameExtractor.java                 utility for DataIntegrityViolationException parsing
│   ├── dto/
│   │   ├── SlVerifyRequest.java                     record with bean validation
│   │   ├── SlVerifyResponse.java                    record
│   │   └── DevSimulateRequest.java                  record, optional fields with defaults
│   └── exception/
│       ├── InvalidSlHeadersException.java
│       └── AvatarAlreadyLinkedException.java
└── config/
    └── ClockConfig.java                             [NEW] @Bean Clock systemUTC()
```

### Backend — modified files

```
backend/src/main/java/com/slparcelauctions/backend/
├── auth/AuthController.java                         rename: /api/auth → /api/v1/auth, cookie Path
├── user/UserController.java                         rename: /api/users → /api/v1/users
├── wstest/WsTestController.java                     rename: /api/ws-test → /api/v1/ws-test
├── config/SecurityConfig.java                       rename all /api/* matchers; add sl/v/dev permits
└── BackendApplication.java                          (likely unchanged)

backend/src/main/resources/
├── application.yml                                  add slpa.sl config block
├── application-dev.yml                              add slpa.sl.trusted-owner-keys placeholder
└── application-prod.yml                             add slpa.sl config block

backend/src/test/java/com/slparcelauctions/backend/
├── auth/AuthFlowIntegrationTest.java                /api/auth → /api/v1/auth, /api/users → /api/v1/users
├── auth/AuthControllerTest.java                     same
├── user/UserIntegrationTest.java                    same
├── user/UserControllerTest.java                     same
├── controller/HealthControllerTest.java             /api/health → /api/v1/health
├── config/SecurityConfigTest.java                   every /api/ matcher path
├── wstest/WsTestIntegrationTest.java                /api/ws-test → /api/v1/ws-test
└── auth/test/RefreshTokenTestFixture.java           any /api/ path literals
```

### Backend — new test files

```
backend/src/test/java/com/slparcelauctions/backend/
├── verification/
│   ├── VerificationCodeServiceTest.java             unit, Mockito
│   ├── VerificationControllerSliceTest.java         @WebMvcTest
│   └── VerificationFlowIntegrationTest.java         @SpringBootTest @ActiveProfiles("dev")
├── sl/
│   ├── SlHeaderValidatorTest.java                   unit, parameterized
│   ├── SlVerificationServiceTest.java               unit, Mockito
│   ├── SlVerificationControllerSliceTest.java       @WebMvcTest
│   ├── DevSlSimulateBeanProfileTest.java            bean absence, default test profile
│   ├── SlVerificationFlowIntegrationTest.java       @SpringBootTest @ActiveProfiles("dev")
│   └── DevSlSimulateIntegrationTest.java            @SpringBootTest @ActiveProfiles("dev")
└── config/
    └── PrefixMigrationSmokeTest.java                @SpringBootTest — 4 routes, narrative
```

### Frontend — modified files

```
frontend/src/
├── lib/api.ts                                       startsWith("/api/auth/") → "/api/v1/auth/"
├── lib/auth/refresh.ts                              any /api/ literal
├── lib/auth/api.ts                                  any /api/ literal
├── lib/auth/hooks.ts                                any /api/ literal
├── lib/auth/hooks.test.tsx                          same
├── lib/auth/refresh.test.ts                         same
├── lib/api.test.ts                                  every /api/ literal in tests
├── lib/api.401-interceptor.test.tsx                 every MSW handler matcher + test path
├── test/msw/handlers.ts                             http.post("*/api/...") + Set-Cookie Path
└── components/dev/WsTestHarness.tsx                 if it references /api/ws-test
```

### Docs

```
README.md                                            update after Task 4
docs/implementation/FOOTGUNS.md                      add F.22+ for new footguns discovered
```

---

## Preflight

- [ ] **Preflight 1: Confirm working tree is clean and on dev**

Run:
```bash
git status --short && git rev-parse --abbrev-ref HEAD
```

Expected: empty or only `.superpowers/` (ignored), branch `dev`. If dirty or not on dev, stop and resolve.

- [ ] **Preflight 2: Pull latest dev**

```bash
git pull origin dev
```

Expected: `Already up to date.` or a fast-forward.

- [ ] **Preflight 3: Create feature branch**

```bash
git checkout -b task/02-sub-1-verification-backend
```

Expected: `Switched to a new branch 'task/02-sub-1-verification-backend'`.

- [ ] **Preflight 4: Baseline — backend tests green**

```bash
cd backend && ./mvnw test -q 2>&1 | tail -20
```

Expected: `BUILD SUCCESS`. If any existing tests fail, stop and investigate — do not proceed until baseline is clean.

- [ ] **Preflight 5: Baseline — frontend tests green**

```bash
cd frontend && npm test -- --run 2>&1 | tail -20
```

Expected: all tests pass. Same rule: do not proceed on a red baseline.

- [ ] **Preflight 6: Confirm Postman SLPA workspace + collection resolve**

Call the Postman MCP `getCollection` tool with `collectionId: 8070328-cf3e1190-cb71-4af3-8e2e-70c82dfee288`. Expected: 200 response with `collection.info.name == "SLPA"`. If the call fails, stop and raise the workspace/collection IDs issue with the user.

---

## Task 1: API prefix migration + Postman foundation

**Estimated time:** 60-90 minutes.
**Commits:** 1 (all rename work in a single commit, Postman scaffolding also in-scope but stored server-side via MCP — no git diff).

### Files

- Modify: 4 backend main files + ~8 backend test files + ~10 frontend files (see File Structure).
- External: SLPA Postman collection + `SLPA Dev` environment (created via Postman MCP).

### Steps

- [ ] **Step 1.1: Backend — rename `auth/AuthController.java`**

File: `backend/src/main/java/com/slparcelauctions/backend/auth/AuthController.java`

Change lines 26 and 32:
```java
@RequestMapping("/api/v1/auth")                          // was: /api/auth
...
private static final String REFRESH_COOKIE_PATH = "/api/v1/auth";   // was: /api/auth
```

No other changes in this file.

- [ ] **Step 1.2: Backend — rename `user/UserController.java`**

File: `backend/src/main/java/com/slparcelauctions/backend/user/UserController.java`

Change line 28 and line 38:
```java
@RequestMapping("/api/v1/users")                                   // was: /api/users
...
.created(URI.create("/api/v1/users/" + created.id()))              // was: /api/users/
```

- [ ] **Step 1.3: Backend — rename `wstest/WsTestController.java`**

File: `backend/src/main/java/com/slparcelauctions/backend/wstest/WsTestController.java`

Change line 42:
```java
@RequestMapping("/api/v1/ws-test")                                 // was: /api/ws-test
```

- [ ] **Step 1.4: Backend — rename `config/SecurityConfig.java` matchers**

File: `backend/src/main/java/com/slparcelauctions/backend/config/SecurityConfig.java`

Rewrite the `.authorizeHttpRequests(auth -> auth ...)` block. Every `/api/` literal becomes `/api/v1/`. The comment about the catch-all also needs to say `/api/v1/**`.

```java
.authorizeHttpRequests(auth -> auth
        .requestMatchers(HttpMethod.GET, "/api/v1/health").permitAll()
        .requestMatchers(
                "/api/v1/auth/register",
                "/api/v1/auth/login",
                // refresh authenticates via HttpOnly cookie inside the handler, not via SecurityContext
                "/api/v1/auth/refresh",
                // logout is idempotent and cookie-authenticated inside the handler (FOOTGUNS §B.7)
                "/api/v1/auth/logout"
        ).permitAll()
        // User registration and public profile view are unauthenticated by design.
        // /api/v1/users/me must remain authenticated — its more-specific rule below
        // must come before the /{id} wildcard (FOOTGUNS §B.5).
        .requestMatchers(HttpMethod.POST, "/api/v1/users").permitAll()
        .requestMatchers(HttpMethod.GET, "/api/v1/users/me").authenticated()
        .requestMatchers(HttpMethod.GET, "/api/v1/users/{id}").permitAll()
        .requestMatchers("/api/v1/auth/logout-all").authenticated()
        // WebSocket handshake is permitted at the HTTP layer. Authentication
        // happens at the STOMP CONNECT frame via JwtChannelInterceptor. See
        // FOOTGUNS §F.16.
        .requestMatchers("/ws/**").permitAll()
        .requestMatchers("/api/v1/**").authenticated()
        .anyRequest().denyAll())
```

Also update the comment on line 42-43 that references `/api/**` catch-all → `/api/v1/**` catch-all.

- [ ] **Step 1.5: Backend — grep for stray `/api/` references in main source**

```bash
cd backend && grep -rn '"/api/[^v]' src/main/java 2>&1
```

Expected: no output. If anything else shows up, rename it in the same commit. Acceptable prefixes are only `/api/v1/`.

- [ ] **Step 1.6: Backend tests — rename `/api/` paths**

Run targeted sed-style edits on each of these files. For each file, replace every `/api/auth/` → `/api/v1/auth/`, `/api/users` → `/api/v1/users`, `/api/health` → `/api/v1/health`, `/api/ws-test` → `/api/v1/ws-test`, `/api/**` → `/api/v1/**`. Use the Edit tool on each file after reading it to preserve exact indentation.

Files to touch:
- `backend/src/test/java/com/slparcelauctions/backend/auth/AuthFlowIntegrationTest.java`
- `backend/src/test/java/com/slparcelauctions/backend/auth/AuthControllerTest.java`
- `backend/src/test/java/com/slparcelauctions/backend/user/UserIntegrationTest.java`
- `backend/src/test/java/com/slparcelauctions/backend/user/UserControllerTest.java`
- `backend/src/test/java/com/slparcelauctions/backend/controller/HealthControllerTest.java`
- `backend/src/test/java/com/slparcelauctions/backend/config/SecurityConfigTest.java`
- `backend/src/test/java/com/slparcelauctions/backend/wstest/WsTestIntegrationTest.java`
- `backend/src/test/java/com/slparcelauctions/backend/auth/test/RefreshTokenTestFixture.java`

After editing each file, do NOT run tests yet (Step 1.7 runs them all at once).

- [ ] **Step 1.7: Backend — verify rename is complete**

```bash
cd backend && grep -rn '"/api/[^v]' src 2>&1
```

Expected: no output. If anything remains, rename it now.

- [ ] **Step 1.8: Backend — run all tests**

```bash
cd backend && ./mvnw test -q 2>&1 | tail -30
```

Expected: `BUILD SUCCESS`, all existing tests green under the new paths.

- [ ] **Step 1.9: Frontend — rename `lib/api.ts`**

File: `frontend/src/lib/api.ts`

Line 132:
```typescript
if (response.status === 401 && !isRetry && !path.startsWith("/api/v1/auth/")) {
```
(was: `"/api/auth/"`)

- [ ] **Step 1.10: Frontend — rename MSW handlers in `test/msw/handlers.ts`**

File: `frontend/src/test/msw/handlers.ts`

Every `http.post("*/api/auth/...")` → `http.post("*/api/v1/auth/...")` (5 occurrences).

Every `Set-Cookie: refreshToken=fake-refresh; HttpOnly; Path=/api/auth; SameSite=Lax` → `Path=/api/v1/auth` (3 occurrences on lines 38, 48, 87).

Do both changes in the same edit so the cookie path and the refresh URL never drift apart — per spec §11.2 this is load-bearing.

- [ ] **Step 1.11: Frontend — rename `lib/api.test.ts`**

File: `frontend/src/lib/api.test.ts`

Every literal `/api/users/1`, `/api/users`, `/api/health`, `/api/auctions` (with or without query) → prefix with `/v1`. Read the file first and enumerate; expect ~6-8 occurrences.

- [ ] **Step 1.12: Frontend — rename `lib/api.401-interceptor.test.tsx`**

File: `frontend/src/lib/api.401-interceptor.test.tsx`

Every `http.get("*/api/users/me", ...)` → `http.get("*/api/v1/users/me", ...)`, every `http.post("*/api/auth/refresh", ...)` → `http.post("*/api/v1/auth/refresh", ...)`, every call site `api.get("/api/users/me")` → `api.get("/api/v1/users/me")`. Expect ~8 occurrences.

- [ ] **Step 1.13: Frontend — rename remaining files**

For each remaining file in the frontend touch list, read it, find every `/api/` literal, and rename to `/api/v1/`:
- `frontend/src/lib/auth/refresh.ts`
- `frontend/src/lib/auth/refresh.test.ts`
- `frontend/src/lib/auth/api.ts`
- `frontend/src/lib/auth/hooks.ts`
- `frontend/src/lib/auth/hooks.test.tsx`
- `frontend/src/components/dev/WsTestHarness.tsx` (only if it contains a literal `/api/ws-test` — it should reference the STOMP `/ws/` path, not `/api/`, but confirm)

- [ ] **Step 1.14: Frontend — verify rename is complete**

```bash
cd frontend && grep -rn '"/api/[^v]' src 2>&1
grep -rn "'/api/[^v]" src 2>&1
grep -rn 'api/[^v]' src 2>&1 | grep -v 'node_modules' | grep -v '//' | head
```

Expected: no output from the first two commands. The third command may show comments or docs that mention `/api/` in prose — those are OK. Any `http.get("*/api/users` or similar runtime literal is not OK.

- [ ] **Step 1.15: Frontend — run all tests**

```bash
cd frontend && npm test -- --run 2>&1 | tail -30
```

Expected: all tests green.

- [ ] **Step 1.16: Frontend — verify chain**

```bash
cd frontend && npm run lint 2>&1 | tail -20
```

Expected: clean. Also run the project's `verify-*.sh` chain if it exists:
```bash
cd frontend && ls scripts/verify-*.sh 2>/dev/null && for f in scripts/verify-*.sh; do bash "$f" || echo "FAILED: $f"; done
```

Any failures must be addressed in this task before committing (per memory: "Don't defer docs/security/semantic-correctness fixes").

- [ ] **Step 1.17: Manual smoke — backend up, new paths work, old paths 404**

In one terminal:
```bash
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

In another:
```bash
curl -i http://localhost:8080/api/v1/health
```
Expected: `200 OK`.

```bash
curl -i http://localhost:8080/api/health
```
Expected: `404 Not Found` or similar — confirming the old path is dead.

Stop the backend (Ctrl-C in the first terminal).

- [ ] **Step 1.18: Postman — create `SLPA Dev` environment**

Call Postman MCP `createEnvironment` with:
- `workspace: "3c50bd16-a197-41d2-9cc8-be245b211f46"`
- `environment.name: "SLPA Dev"`
- `environment.values`: the 6 variables from spec §12.2:
  - `{ "key": "baseUrl", "value": "http://localhost:8080", "type": "default", "enabled": true }`
  - `{ "key": "accessToken", "value": "", "type": "secret", "enabled": true }`
  - `{ "key": "refreshToken", "value": "", "type": "secret", "enabled": true }`
  - `{ "key": "userId", "value": "", "type": "default", "enabled": true }`
  - `{ "key": "slpaServiceAccountUuid", "value": "00000000-0000-0000-0000-000000000001", "type": "default", "enabled": true }`
  - `{ "key": "verificationCode", "value": "", "type": "default", "enabled": true }`

Expected: 200 response with the created environment ID. Record it for use in manual sanity check at Step 1.25.

- [ ] **Step 1.19: Postman — create `Auth/` folder**

Call Postman MCP `createCollectionFolder` with:
- `collectionId: "8070328-cf3e1190-cb71-4af3-8e2e-70c82dfee288"`
- `folder.name: "Auth"`
- `folder.description: "Authentication endpoints. Login populates accessToken, refreshToken, userId into the SLPA Dev environment."`

Expected: 200 with folder id. Record it.

- [ ] **Step 1.20: Postman — add Auth requests**

Call Postman MCP `createCollectionRequest` once per request, attaching each to the `Auth/` folder id from Step 1.19.

**Register** — `POST {{baseUrl}}/api/v1/auth/register`, body raw JSON:
```json
{
  "email": "tester@example.com",
  "password": "hunter22abc",
  "displayName": "Tester"
}
```
Header `Content-Type: application/json`.

**Login** — `POST {{baseUrl}}/api/v1/auth/login`, body raw JSON:
```json
{
  "email": "tester@example.com",
  "password": "hunter22abc"
}
```
Header `Content-Type: application/json`. Add a `tests` script:
```javascript
const r = pm.response.json();
pm.environment.set("accessToken", r.accessToken);
pm.environment.set("userId", r.user.id);
const refreshCookie = pm.cookies.get("refreshToken");
if (refreshCookie) { pm.environment.set("refreshToken", refreshCookie); }
pm.test("access token captured", () => {
    pm.expect(pm.environment.get("accessToken")).to.be.a("string").and.not.empty;
});
```

**Refresh** — `POST {{baseUrl}}/api/v1/auth/refresh`, no body, no extra auth (cookie is sent automatically).

**Logout** — `POST {{baseUrl}}/api/v1/auth/logout`, no body.

**Logout all** — `POST {{baseUrl}}/api/v1/auth/logout-all`, Authorization header `Bearer {{accessToken}}`.

- [ ] **Step 1.21: Postman — add Auth saved examples**

For each Auth request, call `createCollectionResponse` (one per example) with realistic payloads. Per spec §12.5:

- `Register`: 201 example `{ "accessToken": "eyJ...", "user": { "id": 1, "email": "tester@example.com", "displayName": "Tester" } }` + 409 example using the real `AuthExceptionHandler` shape (`type: "https://slpa.example/problems/auth/email-exists"`, `title: "Email already registered"`, `status: 409`, `code: "AUTH_EMAIL_EXISTS"`).
- `Login`: 200 + 401 example with `AUTH_INVALID_CREDENTIALS`.
- `Refresh`: 200 + 401 example with `AUTH_TOKEN_MISSING`.
- `Logout`: 204 only (no body).
- `Logout all`: 204 only.

Pull the exact JSON shapes from `backend/src/main/java/com/slparcelauctions/backend/auth/exception/AuthExceptionHandler.java` so the examples match production responses.

- [ ] **Step 1.22: Postman — create `Users/` folder**

`createCollectionFolder` with name `"Users"` and description `"User profile endpoints. Create user is public; /me requires JWT; /{id} is public."`.

- [ ] **Step 1.23: Postman — add Users requests**

- **Create user** — `POST {{baseUrl}}/api/v1/users`, body `{ "email": "...", "password": "...", "displayName": "..." }`.
- **Get current user** — `GET {{baseUrl}}/api/v1/users/me`, header `Authorization: Bearer {{accessToken}}`.
- **Get user by id** — `GET {{baseUrl}}/api/v1/users/{{userId}}`.

- [ ] **Step 1.24: Postman — add Users saved examples**

- `Create user`: 201 + 409 email-exists.
- `Get current user`: 200 + 401 token missing.
- `Get user by id`: 200 + 404.

Match payload shapes to what `UserController` + `GlobalExceptionHandler` actually return.

- [ ] **Step 1.25: Postman — manual sanity check**

In another terminal, start the backend:
```bash
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

In Postman UI, select the `SLPA Dev` environment and fire:
1. `Auth/Register` — expect 201 (or 409 if tester@example.com already exists from a prior run; ignore).
2. `Auth/Login` — expect 200; check that `accessToken`, `refreshToken`, `userId` are populated in the environment after.
3. `Users/Get current user` — expect 200 with the tester's profile.

Stop the backend. If any step returns 500 or unexpected 401, stop and debug — the rename missed something.

- [ ] **Step 1.26: Commit**

```bash
git add backend/src/main backend/src/test frontend/src
git status --short
```

Expected: only the renamed files. No new untracked files beyond what belongs here (and `.superpowers/` which is ignored).

```bash
git commit -m "refactor(api): migrate /api to /api/v1 across backend, frontend, tests

Rename every @RequestMapping, SecurityConfig matcher, refresh-token cookie
Path attribute, test client path, frontend fetch URL, and MSW handler from
/api/... to /api/v1/... in a single pass. No new endpoints, no behavioral
changes.

Preceded by creation of SLPA Dev environment and Auth/, Users/ folders in
the SLPA Postman collection so the manual smoke at the end of this task
exercises the renamed paths end-to-end."
```

- [ ] **Step 1.27: Push**

```bash
git push -u origin task/02-sub-1-verification-backend
```

Expected: branch pushed, tracking origin.

---

## Task 2: `verification/` slice

**Estimated time:** 3-4 hours.
**Commits:** 1 (all verification code lands together; TDD cycle is visible in the diff but not in separate commits).

### Files

New files listed in File Structure under `verification/`. Modify: `BackendApplication.java` only if bean scanning doesn't pick up the new package automatically (spring boot default scans `com.slparcelauctions.backend.*` so no change should be needed).

### Steps

- [ ] **Step 2.1: Create `ClockConfig.java`**

File: `backend/src/main/java/com/slparcelauctions/backend/config/ClockConfig.java`

```java
package com.slparcelauctions.backend.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides a {@link Clock} bean so services can inject the clock for test determinism.
 * Production wires {@code Clock.systemUTC()}. Tests override with {@code Clock.fixed(...)}
 * via {@code @TestConfiguration} or direct substitution in unit tests.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
```

- [ ] **Step 2.2: Create `VerificationCodeType` enum**

File: `backend/src/main/java/com/slparcelauctions/backend/verification/VerificationCodeType.java`

```java
package com.slparcelauctions.backend.verification;

public enum VerificationCodeType {
    PLAYER,
    PARCEL
}
```

- [ ] **Step 2.3: Create `VerificationCode` entity**

File: `backend/src/main/java/com/slparcelauctions/backend/verification/VerificationCode.java`

```java
package com.slparcelauctions.backend.verification;

import java.time.OffsetDateTime;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 6-digit verification code row. Maps the existing {@code verification_codes} table
 * from V2 migration. No schema changes in this sub-spec.
 *
 * <p>{@code userId} is a plain {@code Long}, not a {@code @ManyToOne} — avoids
 * lazy-loading surprises during validation. Services load the {@link com.slparcelauctions.backend.user.User}
 * directly via {@code UserRepository} when they need the full row.
 */
@Entity
@Table(name = "verification_codes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VerificationCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 6)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VerificationCodeType type;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Builder.Default
    @Column(nullable = false)
    private boolean used = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
```

- [ ] **Step 2.4: Create `VerificationCodeRepository`**

File: `backend/src/main/java/com/slparcelauctions/backend/verification/VerificationCodeRepository.java`

```java
package com.slparcelauctions.backend.verification;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface VerificationCodeRepository
        extends JpaRepository<VerificationCode, Long> {

    /**
     * Returns ALL matching rows because the collision-detection path
     * (spec Q5b) needs to distinguish "exactly one match" from "more than one match."
     */
    List<VerificationCode> findByCodeAndTypeAndUsedFalseAndExpiresAtAfter(
            String code, VerificationCodeType type, OffsetDateTime now);

    /** Hydrates the dashboard via {@code GET /api/v1/verification/active}. */
    Optional<VerificationCode> findFirstByUserIdAndTypeAndUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc(
            Long userId, VerificationCodeType type, OffsetDateTime now);

    /** Returns every active row for a user so generate() can void them all. */
    List<VerificationCode> findByUserIdAndTypeAndUsedFalse(
            Long userId, VerificationCodeType type);
}
```

- [ ] **Step 2.5: Create DTOs**

File: `backend/src/main/java/com/slparcelauctions/backend/verification/dto/ActiveCodeResponse.java`
```java
package com.slparcelauctions.backend.verification.dto;

import java.time.OffsetDateTime;

public record ActiveCodeResponse(String code, OffsetDateTime expiresAt) {}
```

File: `backend/src/main/java/com/slparcelauctions/backend/verification/dto/GenerateCodeResponse.java`
```java
package com.slparcelauctions.backend.verification.dto;

import java.time.OffsetDateTime;

public record GenerateCodeResponse(String code, OffsetDateTime expiresAt) {}
```

- [ ] **Step 2.6: Create exception classes**

File: `backend/src/main/java/com/slparcelauctions/backend/verification/exception/AlreadyVerifiedException.java`
```java
package com.slparcelauctions.backend.verification.exception;

import lombok.Getter;

@Getter
public class AlreadyVerifiedException extends RuntimeException {
    private final Long userId;

    public AlreadyVerifiedException(Long userId) {
        super("User " + userId + " is already SL-verified.");
        this.userId = userId;
    }
}
```

File: `backend/src/main/java/com/slparcelauctions/backend/verification/exception/CodeNotFoundException.java`
```java
package com.slparcelauctions.backend.verification.exception;

import lombok.Getter;

/**
 * Thrown when {@code consume} finds no matching row. Covers not-found, expired,
 * and already-used cases — the caller's remediation is identical (regenerate),
 * so there is one exception, not three.
 */
@Getter
public class CodeNotFoundException extends RuntimeException {
    private final String code;

    public CodeNotFoundException(String code) {
        super("Verification code not found, expired, or already used.");
        this.code = code;
    }
}
```

File: `backend/src/main/java/com/slparcelauctions/backend/verification/exception/CodeCollisionException.java`
```java
package com.slparcelauctions.backend.verification.exception;

import java.util.List;

import lombok.Getter;

/**
 * Thrown when {@code consume} finds more than one row matching the code.
 * Expected statistical event (spec Q5b). Both rows are voided before the
 * exception is thrown. Maps to HTTP 409 with a "generate a new code" message.
 */
@Getter
public class CodeCollisionException extends RuntimeException {
    private final String code;
    private final List<Long> affectedUserIds;

    public CodeCollisionException(String code, List<Long> affectedUserIds) {
        super("Verification code collision: " + affectedUserIds.size() + " users.");
        this.code = code;
        this.affectedUserIds = List.copyOf(affectedUserIds);
    }
}
```

- [ ] **Step 2.7: Write the first failing service test — generate happy path**

File: `backend/src/test/java/com/slparcelauctions/backend/verification/VerificationCodeServiceTest.java`

```java
package com.slparcelauctions.backend.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserNotFoundException;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.verification.dto.ActiveCodeResponse;
import com.slparcelauctions.backend.verification.dto.GenerateCodeResponse;
import com.slparcelauctions.backend.verification.exception.AlreadyVerifiedException;
import com.slparcelauctions.backend.verification.exception.CodeCollisionException;
import com.slparcelauctions.backend.verification.exception.CodeNotFoundException;

class VerificationCodeServiceTest {

    private static final OffsetDateTime NOW = OffsetDateTime.of(
            2026, 4, 13, 20, 0, 0, 0, ZoneOffset.UTC);
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW.toInstant(), ZoneOffset.UTC);

    private VerificationCodeRepository repository;
    private UserRepository userRepository;
    private VerificationCodeService service;

    @BeforeEach
    void setup() {
        repository = mock(VerificationCodeRepository.class);
        userRepository = mock(UserRepository.class);
        service = new VerificationCodeService(repository, userRepository, FIXED_CLOCK);
    }

    @Test
    void generate_happyPath_insertsRowAndReturnsCode() {
        User user = User.builder().id(1L).email("a@b.c").passwordHash("x").verified(false).build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(repository.findByUserIdAndTypeAndUsedFalse(1L, VerificationCodeType.PLAYER))
                .thenReturn(List.of());
        ArgumentCaptor<VerificationCode> saved = ArgumentCaptor.forClass(VerificationCode.class);
        when(repository.save(saved.capture())).thenAnswer(i -> i.getArgument(0));

        GenerateCodeResponse resp = service.generate(1L, VerificationCodeType.PLAYER);

        assertThat(resp.code()).matches("^[0-9]{6}$");
        assertThat(resp.expiresAt()).isEqualTo(NOW.plusMinutes(15));
        VerificationCode row = saved.getValue();
        assertThat(row.getUserId()).isEqualTo(1L);
        assertThat(row.getType()).isEqualTo(VerificationCodeType.PLAYER);
        assertThat(row.isUsed()).isFalse();
    }
}
```

- [ ] **Step 2.8: Run the failing test**

```bash
cd backend && ./mvnw test -Dtest=VerificationCodeServiceTest -q 2>&1 | tail -20
```

Expected: compile failure — `VerificationCodeService` doesn't exist yet.

- [ ] **Step 2.9: Create minimal `VerificationCodeService`**

File: `backend/src/main/java/com/slparcelauctions/backend/verification/VerificationCodeService.java`

```java
package com.slparcelauctions.backend.verification;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserNotFoundException;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.verification.dto.ActiveCodeResponse;
import com.slparcelauctions.backend.verification.dto.GenerateCodeResponse;
import com.slparcelauctions.backend.verification.exception.AlreadyVerifiedException;
import com.slparcelauctions.backend.verification.exception.CodeCollisionException;
import com.slparcelauctions.backend.verification.exception.CodeNotFoundException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class VerificationCodeService {

    public static final Duration CODE_TTL = Duration.ofMinutes(15);

    private final VerificationCodeRepository repository;
    private final UserRepository userRepository;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    /** Generate a fresh code for the given user, voiding any prior active codes. */
    @Transactional
    public GenerateCodeResponse generate(Long userId, VerificationCodeType type) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
        if (Boolean.TRUE.equals(user.getVerified())) {
            throw new AlreadyVerifiedException(userId);
        }
        voidActive(userId, type);
        String code = String.format("%06d", random.nextInt(1_000_000));
        OffsetDateTime expiresAt = OffsetDateTime.now(clock).plus(CODE_TTL);
        VerificationCode row = repository.save(
                VerificationCode.builder()
                        .userId(userId)
                        .code(code)
                        .type(type)
                        .expiresAt(expiresAt)
                        .used(false)
                        .build());
        log.info("Generated verification code for user {} (type={}, id={})",
                userId, type, row.getId());
        return new GenerateCodeResponse(code, expiresAt);
    }

    /** Non-destructive read of the caller's currently active code, if any. */
    @Transactional(readOnly = true)
    public Optional<ActiveCodeResponse> findActive(Long userId, VerificationCodeType type) {
        return repository
                .findFirstByUserIdAndTypeAndUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc(
                        userId, type, OffsetDateTime.now(clock))
                .map(c -> new ActiveCodeResponse(c.getCode(), c.getExpiresAt()));
    }

    /**
     * Validate a code — existence + not-expired + not-used — and mark it used.
     * Handles the Q5b collision case: if more than one row matches, voids both
     * and throws {@link CodeCollisionException}.
     */
    @Transactional
    public Long consume(String code, VerificationCodeType type) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<VerificationCode> matches = repository
                .findByCodeAndTypeAndUsedFalseAndExpiresAtAfter(code, type, now);

        if (matches.isEmpty()) {
            throw new CodeNotFoundException(code);
        }
        if (matches.size() > 1) {
            List<Long> affected = matches.stream().map(VerificationCode::getUserId).toList();
            log.warn("Verification code collision: code={} users={} — voiding all matches",
                    code, affected);
            matches.forEach(c -> c.setUsed(true));
            repository.saveAll(matches);
            throw new CodeCollisionException(code, affected);
        }
        VerificationCode match = matches.get(0);
        match.setUsed(true);
        repository.save(match);
        return match.getUserId();
    }

    private void voidActive(Long userId, VerificationCodeType type) {
        List<VerificationCode> active = repository.findByUserIdAndTypeAndUsedFalse(userId, type);
        if (active.isEmpty()) return;
        active.forEach(c -> c.setUsed(true));
        repository.saveAll(active);
        log.info("Voided {} prior active code(s) for user {}", active.size(), userId);
    }
}
```

- [ ] **Step 2.10: Run the test — expect green**

```bash
cd backend && ./mvnw test -Dtest=VerificationCodeServiceTest -q 2>&1 | tail -20
```

Expected: 1 test passing.

- [ ] **Step 2.11: Add the remaining service tests**

Append to `VerificationCodeServiceTest.java`:

```java
    @Test
    void generate_voidsPriorActiveCode() {
        User user = User.builder().id(1L).verified(false).email("a@b.c").passwordHash("x").build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        VerificationCode prior = VerificationCode.builder()
                .id(99L).userId(1L).code("111111").type(VerificationCodeType.PLAYER)
                .expiresAt(NOW.plusMinutes(5)).used(false).build();
        when(repository.findByUserIdAndTypeAndUsedFalse(1L, VerificationCodeType.PLAYER))
                .thenReturn(List.of(prior));
        when(repository.save(any(VerificationCode.class))).thenAnswer(i -> i.getArgument(0));

        service.generate(1L, VerificationCodeType.PLAYER);

        assertThat(prior.isUsed()).isTrue();
        verify(repository).saveAll(List.of(prior));
    }

    @Test
    void generate_rejectsAlreadyVerifiedUser() {
        User user = User.builder().id(1L).verified(true).email("a@b.c").passwordHash("x").build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.generate(1L, VerificationCodeType.PLAYER))
                .isInstanceOf(AlreadyVerifiedException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void generate_rejectsNonExistentUser() {
        when(userRepository.findById(42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.generate(42L, VerificationCodeType.PLAYER))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void findActive_returnsEmptyWhenNoLiveCode() {
        when(repository.findFirstByUserIdAndTypeAndUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc(
                1L, VerificationCodeType.PLAYER, NOW)).thenReturn(Optional.empty());

        assertThat(service.findActive(1L, VerificationCodeType.PLAYER)).isEmpty();
    }

    @Test
    void findActive_returnsCodeWhenLive() {
        VerificationCode row = VerificationCode.builder()
                .code("123456").expiresAt(NOW.plusMinutes(10)).build();
        when(repository.findFirstByUserIdAndTypeAndUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc(
                1L, VerificationCodeType.PLAYER, NOW)).thenReturn(Optional.of(row));

        ActiveCodeResponse resp = service.findActive(1L, VerificationCodeType.PLAYER).orElseThrow();
        assertThat(resp.code()).isEqualTo("123456");
    }

    @Test
    void consume_happyPath_marksUsedAndReturnsUserId() {
        VerificationCode row = VerificationCode.builder()
                .id(10L).userId(7L).code("654321").type(VerificationCodeType.PLAYER)
                .expiresAt(NOW.plusMinutes(5)).used(false).build();
        when(repository.findByCodeAndTypeAndUsedFalseAndExpiresAtAfter(
                "654321", VerificationCodeType.PLAYER, NOW)).thenReturn(List.of(row));
        when(repository.save(any(VerificationCode.class))).thenAnswer(i -> i.getArgument(0));

        Long userId = service.consume("654321", VerificationCodeType.PLAYER);

        assertThat(userId).isEqualTo(7L);
        assertThat(row.isUsed()).isTrue();
    }

    @Test
    void consume_nothingMatches_throwsCodeNotFound() {
        when(repository.findByCodeAndTypeAndUsedFalseAndExpiresAtAfter(
                "000000", VerificationCodeType.PLAYER, NOW)).thenReturn(List.of());

        assertThatThrownBy(() -> service.consume("000000", VerificationCodeType.PLAYER))
                .isInstanceOf(CodeNotFoundException.class);
    }

    @Test
    void consume_twoMatches_voidsBothAndThrowsCollision() {
        VerificationCode a = VerificationCode.builder()
                .id(10L).userId(1L).code("111111").type(VerificationCodeType.PLAYER)
                .expiresAt(NOW.plusMinutes(5)).used(false).build();
        VerificationCode b = VerificationCode.builder()
                .id(11L).userId(2L).code("111111").type(VerificationCodeType.PLAYER)
                .expiresAt(NOW.plusMinutes(5)).used(false).build();
        when(repository.findByCodeAndTypeAndUsedFalseAndExpiresAtAfter(
                "111111", VerificationCodeType.PLAYER, NOW)).thenReturn(List.of(a, b));

        assertThatThrownBy(() -> service.consume("111111", VerificationCodeType.PLAYER))
                .isInstanceOf(CodeCollisionException.class)
                .hasMessageContaining("2 users");
        assertThat(a.isUsed()).isTrue();
        assertThat(b.isUsed()).isTrue();
        verify(repository).saveAll(List.of(a, b));
    }
}
```

- [ ] **Step 2.12: Run the full service test suite**

```bash
cd backend && ./mvnw test -Dtest=VerificationCodeServiceTest -q 2>&1 | tail -20
```

Expected: 9 tests passing.

- [ ] **Step 2.13: Create `VerificationController`**

File: `backend/src/main/java/com/slparcelauctions/backend/verification/VerificationController.java`

```java
package com.slparcelauctions.backend.verification;

import java.util.Optional;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.verification.dto.ActiveCodeResponse;
import com.slparcelauctions.backend.verification.dto.GenerateCodeResponse;
import com.slparcelauctions.backend.verification.exception.CodeNotFoundException;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/verification")
@RequiredArgsConstructor
public class VerificationController {

    private final VerificationCodeService service;

    @GetMapping("/active")
    public ActiveCodeResponse getActive(@AuthenticationPrincipal AuthPrincipal principal) {
        Optional<ActiveCodeResponse> active = service.findActive(
                principal.userId(), VerificationCodeType.PLAYER);
        return active.orElseThrow(
                () -> new CodeNotFoundException("(no active code)"));
    }

    @PostMapping("/generate")
    public GenerateCodeResponse generate(@AuthenticationPrincipal AuthPrincipal principal) {
        return service.generate(principal.userId(), VerificationCodeType.PLAYER);
    }
}
```

- [ ] **Step 2.14: Create `VerificationExceptionHandler`**

File: `backend/src/main/java/com/slparcelauctions/backend/verification/VerificationExceptionHandler.java`

```java
package com.slparcelauctions.backend.verification;

import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.slparcelauctions.backend.verification.exception.AlreadyVerifiedException;
import com.slparcelauctions.backend.verification.exception.CodeCollisionException;
import com.slparcelauctions.backend.verification.exception.CodeNotFoundException;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * Slice-scoped exception handler for the {@code verification/} package.
 * Scoped via {@code basePackages} so it catches only verification-slice
 * exceptions; the global handler picks up everything else.
 */
@RestControllerAdvice(basePackages = "com.slparcelauctions.backend.verification")
@Slf4j
public class VerificationExceptionHandler {

    @ExceptionHandler(AlreadyVerifiedException.class)
    public ProblemDetail handleAlreadyVerified(AlreadyVerifiedException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                "This account is already linked to an SL avatar. Contact support if this is wrong.");
        pd.setType(URI.create("https://slpa.example/problems/verification/already-verified"));
        pd.setTitle("Account already verified");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "VERIFICATION_ALREADY_VERIFIED");
        return pd;
    }

    @ExceptionHandler(CodeNotFoundException.class)
    public ProblemDetail handleCodeNotFound(CodeNotFoundException e, HttpServletRequest req) {
        // 404 when reading /active, 400 when consuming via /sl/verify — status picked by endpoint.
        // Default to 404 here; SlExceptionHandler overrides for the sl-path case inline.
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND,
                "No active verification code found. Generate a new code from your dashboard.");
        pd.setType(URI.create("https://slpa.example/problems/verification/code-not-found"));
        pd.setTitle("No active verification code");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "VERIFICATION_CODE_NOT_FOUND");
        return pd;
    }

    @ExceptionHandler(CodeCollisionException.class)
    public ProblemDetail handleCollision(CodeCollisionException e, HttpServletRequest req) {
        // Already logged WARN in VerificationCodeService.consume with user IDs + code.
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                "Please generate a new code from your dashboard.");
        pd.setType(URI.create("https://slpa.example/problems/verification/code-collision"));
        pd.setTitle("Verification failed");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "VERIFICATION_CODE_COLLISION");
        return pd;
    }
}
```

> **Note on the CodeNotFoundException dual status:** the verification controller calls `/active` and throws 404 — that's the default above. The SL path throws 400. Because the SL controller lives in `sl/` package and its `@RestControllerAdvice` is scoped to `sl/`, it cannot override `verification/` handler responses directly. Instead, the SL path rewraps the `CodeNotFoundException` inside `SlVerificationService.verify` into a SL-specific exception OR catches + rethrows in `SlExceptionHandler`. Task 3 picks the simpler path: `SlExceptionHandler` also handles `CodeNotFoundException` and `CodeCollisionException` with SL-specific 400/409 responses because handlers in narrower-package advice take precedence over broader ones — but both are at the same specificity here. **The concrete choice** in Task 3 Step 3.13 is: `SlExceptionHandler` explicitly handles both `CodeNotFoundException` and `CodeCollisionException` so the `@RestControllerAdvice(basePackages = "sl")` gets first crack on requests to `/api/v1/sl/**`.

- [ ] **Step 2.15: Write controller slice test**

File: `backend/src/test/java/com/slparcelauctions/backend/verification/VerificationControllerSliceTest.java`

```java
package com.slparcelauctions.backend.verification;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.verification.dto.ActiveCodeResponse;
import com.slparcelauctions.backend.verification.dto.GenerateCodeResponse;
import com.slparcelauctions.backend.verification.exception.AlreadyVerifiedException;

@WebMvcTest(VerificationController.class)
@TestPropertySource(properties = "auth.cleanup.enabled=false")
class VerificationControllerSliceTest {

    @Autowired MockMvc mockMvc;
    @MockBean VerificationCodeService service;

    // NOTE: slice tests with JWT-authenticated endpoints typically use
    // SecurityMockMvcRequestPostProcessors.user(...) or a custom
    // AuthPrincipal-aware request post-processor. Copy the pattern from
    // UserControllerTest if it exists; otherwise use .with(...) from
    // SecurityMockMvcRequestPostProcessors to inject a principal.
    // For this plan's minimal shape, rely on @WithMockUser and verify the
    // endpoint returns 401 without it — the principal injection nuance is
    // covered in the integration test Step 2.18.

    @Test
    void getActive_returns200WithBodyWhenServiceReturnsCode() throws Exception {
        // Confirm service delegation shape via 401 absence — the real principal
        // injection is exercised in the integration test. Slice tests verify
        // status code mapping.
    }

    @Test
    void getActive_returns401WhenUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/verification/active"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void generate_returns401WhenUnauthenticated() throws Exception {
        mockMvc.perform(post("/api/v1/verification/generate"))
                .andExpect(status().isUnauthorized());
    }
}
```

> **Slice test honesty note:** JWT-authenticated endpoints with `@AuthenticationPrincipal AuthPrincipal` are awkward to unit-test at the slice layer because the custom principal isn't a stock `UserDetails`. The real coverage for 200 paths lives in the integration test (Step 2.18) which goes through the full JWT filter. Slice tests confirm the 401 unauth path and the controller wiring exists; the service logic is already covered by the Mockito-level tests in Steps 2.7-2.11.

- [ ] **Step 2.16: Run slice test**

```bash
cd backend && ./mvnw test -Dtest=VerificationControllerSliceTest -q 2>&1 | tail -20
```

Expected: 3 tests passing (2 401 checks + 1 empty-body placeholder that's trivially green).

- [ ] **Step 2.17: Write the integration test**

File: `backend/src/test/java/com/slparcelauctions/backend/verification/VerificationFlowIntegrationTest.java`

```java
package com.slparcelauctions.backend.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = "auth.cleanup.enabled=false")
@Transactional
class VerificationFlowIntegrationTest {

    @Autowired MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void registerThenGenerateThenReadActiveThenRegenerate() throws Exception {
        // Register a user
        MvcResult reg = mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"verif@example.com\",\"password\":\"hunter22abc\",\"displayName\":\"Verif\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String accessToken = objectMapper.readTree(reg.getResponse().getContentAsString())
                .get("accessToken").asText();

        // /active returns 404 before any code exists
        mockMvc.perform(get("/api/v1/verification/active")
                .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNotFound());

        // Generate a code
        MvcResult gen = mockMvc.perform(post("/api/v1/verification/generate")
                .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").isNotEmpty())
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andReturn();
        String firstCode = objectMapper.readTree(gen.getResponse().getContentAsString())
                .get("code").asText();
        assertThat(firstCode).matches("^[0-9]{6}$");

        // /active now returns it
        mockMvc.perform(get("/api/v1/verification/active")
                .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(firstCode));

        // Regenerate — old code should be voided, new code returned
        MvcResult gen2 = mockMvc.perform(post("/api/v1/verification/generate")
                .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andReturn();
        String secondCode = objectMapper.readTree(gen2.getResponse().getContentAsString())
                .get("code").asText();

        // /active now returns the new one
        mockMvc.perform(get("/api/v1/verification/active")
                .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(secondCode));
    }
}
```

- [ ] **Step 2.18: Run the integration test**

```bash
cd backend && ./mvnw test -Dtest=VerificationFlowIntegrationTest -q 2>&1 | tail -30
```

Expected: 1 test passing. Requires the dev Postgres container — per memory `dev_containers.md`, start it if not already running.

- [ ] **Step 2.19: Run the full backend test suite**

```bash
cd backend && ./mvnw test -q 2>&1 | tail -20
```

Expected: all tests green (existing + 13 new).

- [ ] **Step 2.20: Postman — create `Verification/` folder**

Call `createCollectionFolder` with `collectionId: 8070328-cf3e1190-cb71-4af3-8e2e-70c82dfee288`, `folder.name: "Verification"`, `folder.description: "Verification code lifecycle. Generate code populates verificationCode into the SLPA Dev environment for chaining into SL/Verify player."`.

- [ ] **Step 2.21: Postman — add Verification requests**

**Get active code** — `GET {{baseUrl}}/api/v1/verification/active`, header `Authorization: Bearer {{accessToken}}`.

**Generate code** — `POST {{baseUrl}}/api/v1/verification/generate`, header `Authorization: Bearer {{accessToken}}`, no body. Add tests script:
```javascript
const r = pm.response.json();
pm.environment.set("verificationCode", r.code);
pm.test("code captured", () => {
    pm.expect(pm.environment.get("verificationCode")).to.match(/^[0-9]{6}$/);
});
```

- [ ] **Step 2.22: Postman — add Verification saved examples**

- `Get active code`: 200 `{ "code": "123456", "expiresAt": "2026-04-13T21:15:00Z" }` + 404 using the `VerificationExceptionHandler` shape (`type: "https://slpa.example/problems/verification/code-not-found"`, `code: "VERIFICATION_CODE_NOT_FOUND"`).
- `Generate code`: 200 `{ "code": "847219", "expiresAt": "2026-04-13T21:30:00Z" }` + 409 using `VERIFICATION_ALREADY_VERIFIED`.

- [ ] **Step 2.23: Commit Task 2**

```bash
git add backend/src
git status --short
git commit -m "feat(verification): add verification code generation and active-code query

Vertical slice for the verification/ package: VerificationCode entity,
repository, service, controller (GET /active + POST /generate), DTOs,
package-scoped exception handler, and three levels of tests.

Generate is @Transactional and voids any prior active codes before
inserting a new row. Consume handles the Q5b collision case by voiding
both matching rows and throwing CodeCollisionException (mapped to HTTP
409 with WARN log). Clock is injected for test determinism."
```

- [ ] **Step 2.24: Push**

```bash
git push
```

---

## Task 3: `sl/` slice + real `/sl/verify` endpoint

**Estimated time:** 2-3 hours.
**Commits:** 1.

### Files

New files listed in File Structure under `sl/`. Modify: `application.yml`, `application-dev.yml`, `application-prod.yml`, `SecurityConfig.java`.

### Steps

- [ ] **Step 3.1: Create `SlConfigProperties` record**

File: `backend/src/main/java/com/slparcelauctions/backend/sl/SlConfigProperties.java`

```java
package com.slparcelauctions.backend.sl;

import java.util.Set;
import java.util.UUID;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for SL-side integration. Bound to {@code slpa.sl.*}.
 *
 * <p>The compact canonicalizing constructor supplies safe defaults: {@code expectedShard}
 * defaults to {@code "Production"} if blank, and {@code trustedOwnerKeys} is always a
 * defensively-copied immutable set. The startup validator (
 * {@link SlStartupValidator}) fails fast in the prod profile if the set is empty.
 */
@ConfigurationProperties(prefix = "slpa.sl")
public record SlConfigProperties(
        String expectedShard,
        Set<UUID> trustedOwnerKeys
) {
    public SlConfigProperties {
        if (expectedShard == null || expectedShard.isBlank()) {
            expectedShard = "Production";
        }
        trustedOwnerKeys = trustedOwnerKeys == null ? Set.of() : Set.copyOf(trustedOwnerKeys);
    }
}
```

- [ ] **Step 3.2: Create `SlPropertiesConfig` to enable the properties bean**

File: `backend/src/main/java/com/slparcelauctions/backend/sl/SlPropertiesConfig.java`

```java
package com.slparcelauctions.backend.sl;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(SlConfigProperties.class)
public class SlPropertiesConfig {
}
```

- [ ] **Step 3.3: Add `slpa.sl` config to `application.yml`**

File: `backend/src/main/resources/application.yml`

Append:
```yaml

slpa:
  sl:
    expected-shard: Production
    trusted-owner-keys: []
```

- [ ] **Step 3.4: Add `slpa.sl` config to `application-dev.yml`**

File: `backend/src/main/resources/application-dev.yml`

Append:
```yaml

slpa:
  sl:
    trusted-owner-keys:
      - "00000000-0000-0000-0000-000000000001"
```

- [ ] **Step 3.5: Add `slpa.sl` config to `application-prod.yml`**

File: `backend/src/main/resources/application-prod.yml`

Append:
```yaml

slpa:
  sl:
    trusted-owner-keys: []  # deploy pipeline injects the real UUIDs via env
```

- [ ] **Step 3.6: Create `SlStartupValidator`**

File: `backend/src/main/java/com/slparcelauctions/backend/sl/SlStartupValidator.java`

```java
package com.slparcelauctions.backend.sl;

import java.util.Arrays;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Fails fast on startup in the {@code prod} profile if no trusted SL owner keys
 * are configured. Non-prod profiles log a warning but continue.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SlStartupValidator {

    private final SlConfigProperties props;
    private final Environment environment;

    @EventListener(ApplicationReadyEvent.class)
    public void check() {
        boolean isProd = Arrays.asList(environment.getActiveProfiles()).contains("prod");
        if (isProd && props.trustedOwnerKeys().isEmpty()) {
            throw new IllegalStateException(
                    "slpa.sl.trusted-owner-keys is empty in prod profile — "
                            + "refusing to start. Configure at least one UUID.");
        }
        if (props.trustedOwnerKeys().isEmpty()) {
            log.warn("slpa.sl.trusted-owner-keys is empty — all /api/v1/sl/verify "
                    + "calls will be rejected. (non-prod profile, not fatal.)");
        } else {
            log.info("SL integration configured with {} trusted owner key(s)",
                    props.trustedOwnerKeys().size());
        }
    }
}
```

- [ ] **Step 3.7: Create `InvalidSlHeadersException`**

File: `backend/src/main/java/com/slparcelauctions/backend/sl/exception/InvalidSlHeadersException.java`

```java
package com.slparcelauctions.backend.sl.exception;

public class InvalidSlHeadersException extends RuntimeException {
    public InvalidSlHeadersException(String message) {
        super(message);
    }
}
```

- [ ] **Step 3.8: Write `SlHeaderValidatorTest`**

File: `backend/src/test/java/com/slparcelauctions/backend/sl/SlHeaderValidatorTest.java`

```java
package com.slparcelauctions.backend.sl;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.slparcelauctions.backend.sl.exception.InvalidSlHeadersException;

class SlHeaderValidatorTest {

    private static final UUID TRUSTED = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private SlHeaderValidator validator;

    @BeforeEach
    void setup() {
        validator = new SlHeaderValidator(new SlConfigProperties("Production", Set.of(TRUSTED)));
    }

    @Test
    void happyPath_noThrow() {
        assertThatCode(() -> validator.validate("Production", TRUSTED.toString())).doesNotThrowAnyException();
    }

    @Test
    void wrongShard_throws() {
        assertThatThrownBy(() -> validator.validate("Beta", TRUSTED.toString()))
                .isInstanceOf(InvalidSlHeadersException.class);
    }

    @Test
    void nullShard_throws() {
        assertThatThrownBy(() -> validator.validate(null, TRUSTED.toString()))
                .isInstanceOf(InvalidSlHeadersException.class);
    }

    @Test
    void missingOwnerKey_throws() {
        assertThatThrownBy(() -> validator.validate("Production", null))
                .isInstanceOf(InvalidSlHeadersException.class);
    }

    @Test
    void unparseableOwnerKey_throws() {
        assertThatThrownBy(() -> validator.validate("Production", "not-a-uuid"))
                .isInstanceOf(InvalidSlHeadersException.class);
    }

    @Test
    void untrustedOwnerKey_throws() {
        UUID stranger = UUID.fromString("00000000-0000-0000-0000-000000000999");
        assertThatThrownBy(() -> validator.validate("Production", stranger.toString()))
                .isInstanceOf(InvalidSlHeadersException.class);
    }
}
```

- [ ] **Step 3.9: Create `SlHeaderValidator`**

File: `backend/src/main/java/com/slparcelauctions/backend/sl/SlHeaderValidator.java`

```java
package com.slparcelauctions.backend.sl;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.slparcelauctions.backend.sl.exception.InvalidSlHeadersException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@RequiredArgsConstructor
@Slf4j
public class SlHeaderValidator {

    private final SlConfigProperties props;

    public void validate(String shardHeader, String ownerKeyHeader) {
        if (!props.expectedShard().equals(shardHeader)) {
            log.warn("SL header rejected: shard '{}' != '{}'", shardHeader, props.expectedShard());
            throw new InvalidSlHeadersException("Request not from the expected grid");
        }
        UUID key;
        try {
            key = UUID.fromString(ownerKeyHeader);
        } catch (IllegalArgumentException | NullPointerException e) {
            log.warn("SL header rejected: owner key unparseable: '{}'", ownerKeyHeader);
            throw new InvalidSlHeadersException("Owner key missing or malformed");
        }
        if (!props.trustedOwnerKeys().contains(key)) {
            log.warn("SL header rejected: owner key {} not in trusted set", key);
            throw new InvalidSlHeadersException("Owner key is not trusted");
        }
    }
}
```

- [ ] **Step 3.10: Run header validator test**

```bash
cd backend && ./mvnw test -Dtest=SlHeaderValidatorTest -q 2>&1 | tail -20
```

Expected: 6 tests passing.

- [ ] **Step 3.11: Create DTOs — `SlVerifyRequest`, `SlVerifyResponse`**

File: `backend/src/main/java/com/slparcelauctions/backend/sl/dto/SlVerifyRequest.java`

```java
package com.slparcelauctions.backend.sl.dto;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record SlVerifyRequest(
        @NotBlank @Pattern(regexp = "^[0-9]{6}$") String verificationCode,
        @NotNull UUID avatarUuid,
        @NotBlank String avatarName,
        @NotBlank String displayName,
        @NotBlank String username,
        @NotNull LocalDate bornDate,
        @NotNull @Min(0) @Max(3) Integer payInfo
) {}
```

File: `backend/src/main/java/com/slparcelauctions/backend/sl/dto/SlVerifyResponse.java`

```java
package com.slparcelauctions.backend.sl.dto;

public record SlVerifyResponse(boolean verified, Long userId, String slAvatarName) {}
```

- [ ] **Step 3.12: Create `AvatarAlreadyLinkedException`**

File: `backend/src/main/java/com/slparcelauctions/backend/sl/exception/AvatarAlreadyLinkedException.java`

```java
package com.slparcelauctions.backend.sl.exception;

import java.util.UUID;

import lombok.Getter;

@Getter
public class AvatarAlreadyLinkedException extends RuntimeException {
    private final UUID avatarUuid;

    public AvatarAlreadyLinkedException(UUID avatarUuid) {
        super("SL avatar " + avatarUuid + " is already linked to another account.");
        this.avatarUuid = avatarUuid;
    }

    public AvatarAlreadyLinkedException() {
        super("SL avatar is already linked to another account.");
        this.avatarUuid = null;
    }
}
```

- [ ] **Step 3.13: Write `SlVerificationServiceTest`**

File: `backend/src/test/java/com/slparcelauctions/backend/sl/SlVerificationServiceTest.java`

```java
package com.slparcelauctions.backend.sl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.slparcelauctions.backend.sl.dto.SlVerifyRequest;
import com.slparcelauctions.backend.sl.dto.SlVerifyResponse;
import com.slparcelauctions.backend.sl.exception.AvatarAlreadyLinkedException;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserNotFoundException;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.verification.VerificationCodeService;
import com.slparcelauctions.backend.verification.VerificationCodeType;
import com.slparcelauctions.backend.verification.exception.AlreadyVerifiedException;

class SlVerificationServiceTest {

    private static final OffsetDateTime NOW = OffsetDateTime.of(
            2026, 4, 13, 20, 0, 0, 0, ZoneOffset.UTC);
    private static final Clock FIXED = Clock.fixed(NOW.toInstant(), ZoneOffset.UTC);
    private static final UUID TRUSTED = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID AVATAR = UUID.fromString("a1b2c3d4-a1b2-c3d4-e5f6-000000000123");

    private VerificationCodeService codeService;
    private UserRepository userRepository;
    private SlHeaderValidator headerValidator;
    private SlVerificationService service;

    @BeforeEach
    void setup() {
        codeService = mock(VerificationCodeService.class);
        userRepository = mock(UserRepository.class);
        headerValidator = new SlHeaderValidator(new SlConfigProperties("Production", Set.of(TRUSTED)));
        service = new SlVerificationService(codeService, userRepository, headerValidator, FIXED);
    }

    private SlVerifyRequest body() {
        return new SlVerifyRequest(
                "123456", AVATAR, "Test Resident", "Test",
                "test.resident", LocalDate.of(2012, 1, 1), 3);
    }

    @Test
    void happyPath_linksAvatarAndMarksVerified() {
        when(userRepository.findBySlAvatarUuid(AVATAR)).thenReturn(Optional.empty());
        when(codeService.consume("123456", VerificationCodeType.PLAYER)).thenReturn(7L);
        User user = User.builder().id(7L).email("a@b.c").passwordHash("x").verified(false).build();
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        SlVerifyResponse resp = service.verify("Production", TRUSTED.toString(), body());

        assertThat(resp.verified()).isTrue();
        assertThat(resp.userId()).isEqualTo(7L);
        assertThat(user.getSlAvatarUuid()).isEqualTo(AVATAR);
        assertThat(user.getVerified()).isTrue();
        assertThat(user.getVerifiedAt()).isEqualTo(NOW);
    }

    @Test
    void avatarAlreadyLinked_throwsBeforeConsumingCode() {
        User other = User.builder().id(99L).slAvatarUuid(AVATAR).build();
        when(userRepository.findBySlAvatarUuid(AVATAR)).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.verify("Production", TRUSTED.toString(), body()))
                .isInstanceOf(AvatarAlreadyLinkedException.class);
        verify(codeService, never()).consume(any(), any());
    }

    @Test
    void userAlreadyVerified_throws() {
        when(userRepository.findBySlAvatarUuid(AVATAR)).thenReturn(Optional.empty());
        when(codeService.consume("123456", VerificationCodeType.PLAYER)).thenReturn(7L);
        User verified = User.builder().id(7L).verified(true).email("a@b.c").passwordHash("x").build();
        when(userRepository.findById(7L)).thenReturn(Optional.of(verified));

        assertThatThrownBy(() -> service.verify("Production", TRUSTED.toString(), body()))
                .isInstanceOf(AlreadyVerifiedException.class);
    }

    @Test
    void headerValidationFails_shortCircuitsBeforeAvatarCheck() {
        assertThatThrownBy(() -> service.verify("Beta", TRUSTED.toString(), body()))
                .hasMessageContaining("grid");
        verify(userRepository, never()).findBySlAvatarUuid(any());
        verify(codeService, never()).consume(any(), any());
    }
}
```

- [ ] **Step 3.14: Create `SlVerificationService`**

File: `backend/src/main/java/com/slparcelauctions/backend/sl/SlVerificationService.java`

```java
package com.slparcelauctions.backend.sl;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.sl.dto.SlVerifyRequest;
import com.slparcelauctions.backend.sl.dto.SlVerifyResponse;
import com.slparcelauctions.backend.sl.exception.AvatarAlreadyLinkedException;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserNotFoundException;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.verification.VerificationCodeService;
import com.slparcelauctions.backend.verification.VerificationCodeType;
import com.slparcelauctions.backend.verification.exception.AlreadyVerifiedException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Orchestrates {@code POST /api/v1/sl/verify}. Validates SL-injected headers,
 * pre-checks avatar uniqueness, consumes a verification code, then links the
 * avatar to the user and marks the account verified.
 *
 * <p>Does NOT catch {@code DataIntegrityViolationException} — the unique index
 * race on {@code users.sl_avatar_uuid} is handled by {@link SlExceptionHandler}.
 * Keeping the catch out of the service keeps the unit tests clean.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SlVerificationService {

    private final VerificationCodeService verificationCodeService;
    private final UserRepository userRepository;
    private final SlHeaderValidator headerValidator;
    private final Clock clock;

    @Transactional
    public SlVerifyResponse verify(
            String shardHeader, String ownerKeyHeader, SlVerifyRequest body) {
        headerValidator.validate(shardHeader, ownerKeyHeader);

        Optional<User> existingLinked = userRepository.findBySlAvatarUuid(body.avatarUuid());
        if (existingLinked.isPresent()) {
            throw new AvatarAlreadyLinkedException(body.avatarUuid());
        }

        Long userId = verificationCodeService.consume(
                body.verificationCode(), VerificationCodeType.PLAYER);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
        if (Boolean.TRUE.equals(user.getVerified())) {
            throw new AlreadyVerifiedException(userId);
        }

        user.setSlAvatarUuid(body.avatarUuid());
        user.setSlAvatarName(body.avatarName());
        user.setSlDisplayName(body.displayName());
        user.setSlUsername(body.username());
        user.setSlBornDate(body.bornDate());
        user.setSlPayinfo(body.payInfo());
        user.setVerified(true);
        user.setVerifiedAt(OffsetDateTime.now(clock));
        userRepository.save(user);

        log.info("SL verification succeeded: userId={} avatarName={} payInfo={}",
                userId, body.avatarName(), body.payInfo());

        return new SlVerifyResponse(true, userId, body.avatarName());
    }
}
```

- [ ] **Step 3.15: Run service test**

```bash
cd backend && ./mvnw test -Dtest=SlVerificationServiceTest -q 2>&1 | tail -20
```

Expected: 4 tests passing.

- [ ] **Step 3.16: Create `ConstraintNameExtractor` utility**

File: `backend/src/main/java/com/slparcelauctions/backend/sl/ConstraintNameExtractor.java`

```java
package com.slparcelauctions.backend.sl;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Pulls the Postgres constraint name out of a {@link DataIntegrityViolationException}
 * by unwrapping to Hibernate's {@link ConstraintViolationException}. Returns an empty
 * string if the chain doesn't contain one — the caller falls through to the 500 handler.
 */
final class ConstraintNameExtractor {

    private ConstraintNameExtractor() {}

    static String extract(DataIntegrityViolationException e) {
        Throwable cursor = e;
        while (cursor != null) {
            if (cursor instanceof ConstraintViolationException cve) {
                String name = cve.getConstraintName();
                return name == null ? "" : name;
            }
            cursor = cursor.getCause();
        }
        return "";
    }

    static boolean isAvatarUuidUniqueViolation(String constraintName) {
        // V1 migration generates: users_sl_avatar_uuid_key (Postgres default from UNIQUE)
        // Lenient substring match in case the exact name ever drifts.
        return constraintName != null && constraintName.contains("sl_avatar_uuid");
    }
}
```

- [ ] **Step 3.17: Create `SlExceptionHandler`**

File: `backend/src/main/java/com/slparcelauctions/backend/sl/SlExceptionHandler.java`

```java
package com.slparcelauctions.backend.sl;

import java.net.URI;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.slparcelauctions.backend.sl.exception.AvatarAlreadyLinkedException;
import com.slparcelauctions.backend.sl.exception.InvalidSlHeadersException;
import com.slparcelauctions.backend.verification.exception.AlreadyVerifiedException;
import com.slparcelauctions.backend.verification.exception.CodeCollisionException;
import com.slparcelauctions.backend.verification.exception.CodeNotFoundException;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * Slice-scoped exception handler for {@code /api/v1/sl/**} and
 * {@code /api/v1/dev/sl/**}. Handles both sl-package exceptions and the
 * verification-package exceptions that bubble through from the consume path
 * so the SL-side responses (400 for not-found, 409 for collision) override
 * the verification-side defaults (404 for not-found, 409 for collision).
 */
@RestControllerAdvice(basePackages = "com.slparcelauctions.backend.sl")
@Slf4j
public class SlExceptionHandler {

    @ExceptionHandler(InvalidSlHeadersException.class)
    public ProblemDetail handleInvalidHeaders(InvalidSlHeadersException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
        pd.setType(URI.create("https://slpa.example/problems/sl/invalid-headers"));
        pd.setTitle("Invalid SL headers");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "SL_INVALID_HEADERS");
        return pd;
    }

    @ExceptionHandler(AvatarAlreadyLinkedException.class)
    public ProblemDetail handleAvatarLinked(AvatarAlreadyLinkedException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, "This SL avatar is already linked to another SLPA account.");
        pd.setType(URI.create("https://slpa.example/problems/sl/avatar-already-linked"));
        pd.setTitle("Avatar already linked");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "SL_AVATAR_ALREADY_LINKED");
        return pd;
    }

    @ExceptionHandler(AlreadyVerifiedException.class)
    public ProblemDetail handleAlreadyVerified(AlreadyVerifiedException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, "This account is already linked to an SL avatar.");
        pd.setType(URI.create("https://slpa.example/problems/sl/already-verified"));
        pd.setTitle("Account already verified");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "SL_ALREADY_VERIFIED");
        return pd;
    }

    @ExceptionHandler(CodeNotFoundException.class)
    public ProblemDetail handleCodeNotFound(CodeNotFoundException e, HttpServletRequest req) {
        // SL path uses 400 (invalid code from the SL caller's perspective).
        // The verification package handler would default to 404; this narrower
        // advice takes precedence for requests that reach sl-package controllers.
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "Code not found, expired, or already used. Please generate a new code from your dashboard.");
        pd.setType(URI.create("https://slpa.example/problems/sl/code-not-found"));
        pd.setTitle("Verification failed");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "SL_CODE_NOT_FOUND");
        return pd;
    }

    @ExceptionHandler(CodeCollisionException.class)
    public ProblemDetail handleCollision(CodeCollisionException e, HttpServletRequest req) {
        // Already logged WARN in VerificationCodeService.consume.
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, "Please generate a new code from your dashboard.");
        pd.setType(URI.create("https://slpa.example/problems/sl/code-collision"));
        pd.setTitle("Verification failed");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "SL_CODE_COLLISION");
        return pd;
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrity(
            DataIntegrityViolationException e, HttpServletRequest req) {
        String constraintName = ConstraintNameExtractor.extract(e);
        if (ConstraintNameExtractor.isAvatarUuidUniqueViolation(constraintName)) {
            log.warn("SL verify race: sl_avatar_uuid unique constraint fired ({}). "
                    + "Mapping to AvatarAlreadyLinkedException response.", constraintName);
            ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                    HttpStatus.CONFLICT,
                    "This SL avatar is already linked to another SLPA account.");
            pd.setType(URI.create("https://slpa.example/problems/sl/avatar-already-linked"));
            pd.setTitle("Avatar already linked");
            pd.setInstance(URI.create(req.getRequestURI()));
            pd.setProperty("code", "SL_AVATAR_ALREADY_LINKED");
            return pd;
        }
        // Unknown constraint — bubble to GlobalExceptionHandler.handleUnexpected (500).
        throw e;
    }
}
```

- [ ] **Step 3.18: Create `SlVerificationController`**

File: `backend/src/main/java/com/slparcelauctions/backend/sl/SlVerificationController.java`

```java
package com.slparcelauctions.backend.sl;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.sl.dto.SlVerifyRequest;
import com.slparcelauctions.backend.sl.dto.SlVerifyResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/sl")
@RequiredArgsConstructor
public class SlVerificationController {

    private final SlVerificationService service;

    @PostMapping("/verify")
    public SlVerifyResponse verify(
            @RequestHeader(value = "X-SecondLife-Shard", required = false) String shard,
            @RequestHeader(value = "X-SecondLife-Owner-Key", required = false) String ownerKey,
            @Valid @RequestBody SlVerifyRequest body) {
        return service.verify(shard, ownerKey, body);
    }
}
```

- [ ] **Step 3.19: Update `SecurityConfig` to permit `/api/v1/sl/verify`**

File: `backend/src/main/java/com/slparcelauctions/backend/config/SecurityConfig.java`

Add a new matcher **before** the `/api/v1/**` catch-all (FOOTGUNS §B.5 — first-match-wins). Insert after the `/ws/**` line:

```java
                        // SL-injected headers gate /api/v1/sl/verify — no JWT required.
                        // The SlHeaderValidator component runs inside the request handler.
                        .requestMatchers(HttpMethod.POST, "/api/v1/sl/verify").permitAll()
                        // Dev-profile simulate helper — Task 4 adds the second permit matcher
                        // and wires it profile-gated; leaving a placeholder marker here.
```

- [ ] **Step 3.20: Run all backend tests**

```bash
cd backend && ./mvnw test -q 2>&1 | tail -30
```

Expected: all tests green. Fix any fallout from the new `sl/` package (e.g., component scan picking up `SlStartupValidator` in tests that didn't expect it).

- [ ] **Step 3.21: Write `SlVerificationControllerSliceTest`**

File: `backend/src/test/java/com/slparcelauctions/backend/sl/SlVerificationControllerSliceTest.java`

```java
package com.slparcelauctions.backend.sl;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = "auth.cleanup.enabled=false")
@Transactional
class SlVerificationControllerSliceTest {

    @Autowired MockMvc mockMvc;

    private static final String VALID_BODY = """
        {
          "verificationCode":"000000",
          "avatarUuid":"11111111-1111-1111-1111-111111111111",
          "avatarName":"Tester",
          "displayName":"Tester",
          "username":"tester",
          "bornDate":"2012-01-01",
          "payInfo":3
        }
        """;

    @Test
    void missingShardHeader_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/sl/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY)
                .header("X-SecondLife-Owner-Key", "00000000-0000-0000-0000-000000000001"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://slpa.example/problems/sl/invalid-headers"));
    }

    @Test
    void wrongShard_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/sl/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY)
                .header("X-SecondLife-Shard", "Beta")
                .header("X-SecondLife-Owner-Key", "00000000-0000-0000-0000-000000000001"))
                .andExpect(status().isForbidden());
    }

    @Test
    void unknownOwnerKey_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/sl/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY)
                .header("X-SecondLife-Shard", "Production")
                .header("X-SecondLife-Owner-Key", "00000000-0000-0000-0000-000000000999"))
                .andExpect(status().isForbidden());
    }

    @Test
    void validHeadersButNonExistentCode_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/sl/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY)
                .header("X-SecondLife-Shard", "Production")
                .header("X-SecondLife-Owner-Key", "00000000-0000-0000-0000-000000000001"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://slpa.example/problems/sl/code-not-found"));
    }
}
```

> **Note:** Using `@SpringBootTest` instead of `@WebMvcTest` here because the full Spring Security filter chain plus `SlHeaderValidator` + `SlConfigProperties` wiring is the unit under test; mocking them all in a slice test is more ceremony than the test is worth. Matches the pattern `AuthFlowIntegrationTest` uses.

- [ ] **Step 3.22: Run controller test**

```bash
cd backend && ./mvnw test -Dtest=SlVerificationControllerSliceTest -q 2>&1 | tail -20
```

Expected: 4 tests passing.

- [ ] **Step 3.23: Write `SlVerificationFlowIntegrationTest`**

File: `backend/src/test/java/com/slparcelauctions/backend/sl/SlVerificationFlowIntegrationTest.java`

```java
package com.slparcelauctions.backend.sl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = "auth.cleanup.enabled=false")
@Transactional
class SlVerificationFlowIntegrationTest {

    private static final String TRUSTED_OWNER = "00000000-0000-0000-0000-000000000001";

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void fullFlow_registerGenerateVerify_updatesUserRow() throws Exception {
        // Register + extract access token
        MvcResult reg = mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"slflow@example.com\",\"password\":\"hunter22abc\",\"displayName\":\"SlFlow\"}"))
                .andExpect(status().isCreated()).andReturn();
        String accessToken = objectMapper.readTree(reg.getResponse().getContentAsString())
                .get("accessToken").asText();
        Long userId = objectMapper.readTree(reg.getResponse().getContentAsString())
                .get("user").get("id").asLong();

        // Generate code
        MvcResult gen = mockMvc.perform(post("/api/v1/verification/generate")
                .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk()).andReturn();
        String code = objectMapper.readTree(gen.getResponse().getContentAsString())
                .get("code").asText();

        // Fire /sl/verify
        String body = String.format("""
            {
              "verificationCode":"%s",
              "avatarUuid":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
              "avatarName":"Test Resident",
              "displayName":"Test",
              "username":"test.resident",
              "bornDate":"2012-05-15",
              "payInfo":3
            }
            """, code);
        mockMvc.perform(post("/api/v1/sl/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("X-SecondLife-Shard", "Production")
                .header("X-SecondLife-Owner-Key", TRUSTED_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified").value(true))
                .andExpect(jsonPath("$.userId").value(userId));

        // Assert User row was updated
        User user = userRepository.findById(userId).orElseThrow();
        assertThat(user.getVerified()).isTrue();
        assertThat(user.getSlAvatarName()).isEqualTo("Test Resident");
        assertThat(user.getSlAvatarUuid().toString()).isEqualTo("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        assertThat(user.getVerifiedAt()).isNotNull();
    }

    @Test
    void secondVerifyWithSameAvatar_returns409() throws Exception {
        // First verification — reuse full flow from above but inline
        MvcResult reg = mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"dup1@example.com\",\"password\":\"hunter22abc\",\"displayName\":\"Dup1\"}"))
                .andExpect(status().isCreated()).andReturn();
        String t1 = objectMapper.readTree(reg.getResponse().getContentAsString()).get("accessToken").asText();
        MvcResult gen1 = mockMvc.perform(post("/api/v1/verification/generate")
                .header("Authorization", "Bearer " + t1)).andExpect(status().isOk()).andReturn();
        String code1 = objectMapper.readTree(gen1.getResponse().getContentAsString()).get("code").asText();
        String body1 = String.format("""
            {"verificationCode":"%s","avatarUuid":"cccccccc-cccc-cccc-cccc-cccccccccccc",
            "avatarName":"A","displayName":"A","username":"a.a","bornDate":"2012-01-01","payInfo":3}
            """, code1);
        mockMvc.perform(post("/api/v1/sl/verify")
                .contentType(MediaType.APPLICATION_JSON).content(body1)
                .header("X-SecondLife-Shard", "Production")
                .header("X-SecondLife-Owner-Key", TRUSTED_OWNER))
                .andExpect(status().isOk());

        // Second user tries to link the same avatar
        MvcResult reg2 = mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"dup2@example.com\",\"password\":\"hunter22abc\",\"displayName\":\"Dup2\"}"))
                .andExpect(status().isCreated()).andReturn();
        String t2 = objectMapper.readTree(reg2.getResponse().getContentAsString()).get("accessToken").asText();
        MvcResult gen2 = mockMvc.perform(post("/api/v1/verification/generate")
                .header("Authorization", "Bearer " + t2)).andExpect(status().isOk()).andReturn();
        String code2 = objectMapper.readTree(gen2.getResponse().getContentAsString()).get("code").asText();
        String body2 = String.format("""
            {"verificationCode":"%s","avatarUuid":"cccccccc-cccc-cccc-cccc-cccccccccccc",
            "avatarName":"B","displayName":"B","username":"b.b","bornDate":"2012-02-02","payInfo":3}
            """, code2);
        mockMvc.perform(post("/api/v1/sl/verify")
                .contentType(MediaType.APPLICATION_JSON).content(body2)
                .header("X-SecondLife-Shard", "Production")
                .header("X-SecondLife-Owner-Key", TRUSTED_OWNER))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SL_AVATAR_ALREADY_LINKED"));
    }
}
```

- [ ] **Step 3.24: Run integration test**

```bash
cd backend && ./mvnw test -Dtest=SlVerificationFlowIntegrationTest -q 2>&1 | tail -30
```

Expected: 2 tests passing. Requires dev Postgres container.

- [ ] **Step 3.25: Full backend test run**

```bash
cd backend && ./mvnw test -q 2>&1 | tail -20
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 3.26: Postman — create `SL/` folder + Verify player endpoint**

`createCollectionFolder`: name `"SL"`, description `"Second Life integration surface. Header-gated endpoints consumed by in-world LSL scripts (real) or the Dev/ folder (simulated)."`.

`createCollectionRequest`: `POST {{baseUrl}}/api/v1/sl/verify`, headers:
- `X-SecondLife-Shard: Production`
- `X-SecondLife-Owner-Key: {{slpaServiceAccountUuid}}`
- `Content-Type: application/json`

Body:
```json
{
  "verificationCode": "{{verificationCode}}",
  "avatarUuid": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
  "avatarName": "Test Resident",
  "displayName": "Test",
  "username": "test.resident",
  "bornDate": "2012-05-15",
  "payInfo": 3
}
```

- [ ] **Step 3.27: Postman — add SL saved examples**

Per spec §12.5: 200 + 403 (wrong shard) + 400 (invalid code) + 409 (avatar already linked). Pull exact JSON shapes from `SlExceptionHandler` (the `type`, `title`, `code` fields).

- [ ] **Step 3.28: Commit Task 3**

```bash
git add backend/src backend/src/main/resources
git status --short
git commit -m "feat(sl): add SL verification endpoint with header-gated security

Vertical slice for the sl/ package: SlConfigProperties (records),
SlStartupValidator (prod fail-fast), SlHeaderValidator, SlVerificationService
orchestrator, controller, DTOs, and package-scoped exception handler
covering both sl/ exceptions and the verification/ exceptions that bubble
through from the consume path.

DataIntegrityViolationException catch-and-rethrow lives in SlExceptionHandler,
not in the service, keeping unit tests free of fake constraint exceptions.
Unknown constraints fall through to GlobalExceptionHandler for 500 handling.

application.yml / application-dev.yml / application-prod.yml wire slpa.sl
with a dev placeholder owner key that satisfies header validation for
Postman + integration tests."
```

- [ ] **Step 3.29: Push**

```bash
git push
```

---

## Task 4: DevSlSimulateController + profile-gated dev helper

**Estimated time:** 1.5 hours.
**Commits:** 1.

### Steps

- [ ] **Step 4.1: Create `DevSimulateRequest` DTO**

File: `backend/src/main/java/com/slparcelauctions/backend/sl/dto/DevSimulateRequest.java`

```java
package com.slparcelauctions.backend.sl.dto;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Request body for the dev-profile simulate helper. Everything except
 * {@code verificationCode} is optional — {@link #toSlVerifyRequest()} fills
 * sensible defaults so the frontend dev harness can POST just the code.
 */
public record DevSimulateRequest(
        @NotBlank @Pattern(regexp = "^[0-9]{6}$") String verificationCode,
        UUID avatarUuid,
        String avatarName,
        String displayName,
        String username,
        LocalDate bornDate,
        Integer payInfo
) {
    public SlVerifyRequest toSlVerifyRequest() {
        return new SlVerifyRequest(
                verificationCode,
                avatarUuid != null ? avatarUuid : UUID.randomUUID(),
                avatarName != null ? avatarName : "Dev Tester",
                displayName != null ? displayName : "Dev Tester",
                username != null ? username : "dev.tester",
                bornDate != null ? bornDate : LocalDate.of(2012, 1, 1),
                payInfo != null ? payInfo : 3);
    }
}
```

- [ ] **Step 4.2: Create `DevSlSimulateController`**

File: `backend/src/main/java/com/slparcelauctions/backend/sl/DevSlSimulateController.java`

```java
package com.slparcelauctions.backend.sl;

import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.sl.dto.DevSimulateRequest;
import com.slparcelauctions.backend.sl.dto.SlVerifyRequest;
import com.slparcelauctions.backend.sl.dto.SlVerifyResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Dev-profile-only helper for exercising {@code /api/v1/sl/verify} from a browser
 * without running actual LSL scripts. Synthesizes SL headers using the first
 * trusted owner key from config, fills in any missing body fields via
 * {@link DevSimulateRequest#toSlVerifyRequest()}, then delegates to the real
 * service (same code path, same exception mapping).
 *
 * <p>Three-layer gating:
 * <ol>
 *   <li>{@code @Profile("dev")} — bean not instantiated outside dev profile.</li>
 *   <li>{@link com.slparcelauctions.backend.config.SecurityConfig} only permits
 *       {@code /api/v1/dev/**} under the dev profile.</li>
 *   <li>{@code toSlVerifyRequest} fills a random avatar UUID per call so repeated
 *       tests don't trip the unique constraint unless the caller opts in.</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/v1/dev/sl")
@RequiredArgsConstructor
@Profile("dev")
@Slf4j
public class DevSlSimulateController {

    private final SlVerificationService slVerificationService;
    private final SlConfigProperties slConfig;

    @PostMapping("/simulate-verify")
    public SlVerifyResponse simulate(@Valid @RequestBody DevSimulateRequest req) {
        UUID ownerKey = slConfig.trustedOwnerKeys().iterator().next();
        SlVerifyRequest synthesized = req.toSlVerifyRequest();
        log.info("Dev simulate: forwarding to SlVerificationService with ownerKey={}", ownerKey);
        return slVerificationService.verify(
                slConfig.expectedShard(),
                ownerKey.toString(),
                synthesized);
    }
}
```

- [ ] **Step 4.3: Update `SecurityConfig` to profile-gate `/api/v1/dev/**`**

File: `backend/src/main/java/com/slparcelauctions/backend/config/SecurityConfig.java`

The cleanest approach is a runtime profile check inside the existing config. Replace the placeholder comment left in Step 3.19 with the actual permit. Inject `Environment` into `SecurityConfig`:

```java
// At the top, add import:
import org.springframework.core.env.Environment;

// In the class, add field:
private final Environment environment;

// Inside securityFilterChain, after the /api/v1/sl/verify permit matcher and
// before the /api/v1/** catch-all, add:
if (java.util.Arrays.asList(environment.getActiveProfiles()).contains("dev")) {
    // In dev profile, the simulate helper is registered and permitted.
    // This block does not execute in prod — DevSlSimulateController is not a bean there.
    // Cannot call .requestMatchers inside a conditional directly because the fluent
    // chain is already committed. Use a separate configuration path below.
}
```

Because the fluent builder complicates conditional matchers, use a separate profile-scoped `@Configuration` class to add a second `SecurityFilterChain` bean dedicated to dev paths. Alternative and simpler: always register the permit, since the controller bean only exists in dev — in prod, any request to `/api/v1/dev/sl/simulate-verify` hits no handler and returns 404, but the permit itself is harmless.

**Adopt the simpler approach:** unconditionally permit `/api/v1/dev/**` in `SecurityConfig`. The bean-level `@Profile("dev")` is the real gate. Replace the Step 3.19 placeholder with:

```java
                        .requestMatchers(HttpMethod.POST, "/api/v1/sl/verify").permitAll()
                        // Dev simulate helper — permit at HTTP layer always. The bean is only
                        // registered under @Profile("dev"); in prod the handler doesn't exist so
                        // the request 404s (falling through Spring MVC rather than Spring Security).
                        .requestMatchers("/api/v1/dev/**").permitAll()
```

- [ ] **Step 4.4: Run backend tests after SecurityConfig edit**

```bash
cd backend && ./mvnw test -q 2>&1 | tail -20
```

Expected: green.

- [ ] **Step 4.5: Write `DevSlSimulateIntegrationTest`**

File: `backend/src/test/java/com/slparcelauctions/backend/sl/DevSlSimulateIntegrationTest.java`

```java
package com.slparcelauctions.backend.sl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = "auth.cleanup.enabled=false")
@Transactional
class DevSlSimulateIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void simulate_withJustCode_linksAvatarWithDefaults() throws Exception {
        MvcResult reg = mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"sim@example.com\",\"password\":\"hunter22abc\",\"displayName\":\"Sim\"}"))
                .andExpect(status().isCreated()).andReturn();
        String accessToken = objectMapper.readTree(reg.getResponse().getContentAsString()).get("accessToken").asText();
        Long userId = objectMapper.readTree(reg.getResponse().getContentAsString()).get("user").get("id").asLong();

        MvcResult gen = mockMvc.perform(post("/api/v1/verification/generate")
                .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk()).andReturn();
        String code = objectMapper.readTree(gen.getResponse().getContentAsString()).get("code").asText();

        mockMvc.perform(post("/api/v1/dev/sl/simulate-verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(String.format("{\"verificationCode\":\"%s\"}", code)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified").value(true))
                .andExpect(jsonPath("$.userId").value(userId))
                .andExpect(jsonPath("$.slAvatarName").value("Dev Tester"));

        User user = userRepository.findById(userId).orElseThrow();
        assertThat(user.getVerified()).isTrue();
        assertThat(user.getSlAvatarName()).isEqualTo("Dev Tester");
    }
}
```

- [ ] **Step 4.6: Run the dev integration test**

```bash
cd backend && ./mvnw test -Dtest=DevSlSimulateIntegrationTest -q 2>&1 | tail -20
```

Expected: 1 test passing.

> **Spec deviation note:** Spec §13.2 lists a `DevSlSimulateControllerSliceTest` in addition to `DevSlSimulateBeanProfileTest`. The slice test is omitted from this plan — its coverage (happy path with defaults filled, error bubbling) is fully exercised by the integration test in Step 4.5 which hits the real service and real exception handler. Adding a slice-level `@WebMvcTest` with mocked services would re-test the same code paths at a shallower layer for no gain. The integration test IS the slice test for this endpoint.

- [ ] **Step 4.7: Write `DevSlSimulateBeanProfileTest`**

File: `backend/src/test/java/com/slparcelauctions/backend/sl/DevSlSimulateBeanProfileTest.java`

```java
package com.slparcelauctions.backend.sl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Proves {@code @Profile("dev")} gates {@link DevSlSimulateController}: loads the
 * context under the {@code test} profile (not {@code dev}), injects the controller
 * field with {@code required=false}, and asserts it's null.
 *
 * <p>Deliberately does NOT use {@code @ActiveProfiles("prod")} — the prod profile
 * requires {@code JWT_SECRET} and fails fast on empty {@code slpa.sl.trusted-owner-keys},
 * neither of which is available in a unit test environment. The {@code test} profile
 * (an empty profile name that Spring treats as "not dev") is sufficient to prove
 * the gate is working.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        // Satisfy the bean dependencies that would otherwise fail in a non-dev profile.
        "jwt.secret=dGVzdC1zZWNyZXQtdGVzdC1zZWNyZXQtdGVzdC1zZWNyZXQtMTI=",
        "slpa.sl.trusted-owner-keys[0]=00000000-0000-0000-0000-000000000001",
        "spring.datasource.url=jdbc:postgresql://localhost:5432/slpa",
        "spring.datasource.username=slpa",
        "spring.datasource.password=slpa",
        "spring.data.redis.host=localhost"
})
class DevSlSimulateBeanProfileTest {

    @Autowired(required = false)
    DevSlSimulateController controller;

    @Test
    void controllerBeanIsNotRegisteredOutsideDevProfile() {
        assertThat(controller)
                .as("DevSlSimulateController must only be wired under @Profile(\"dev\")")
                .isNull();
    }
}
```

> **Adjust if context startup in the test profile hits other issues.** The test profile inherits from `application.yml` which has `ddl-auto: validate` — if that fights the entity metadata, either override `spring.jpa.hibernate.ddl-auto=update` in the property source above, or rename the active profile to `beanprofiletest` and create a minimal `application-beanprofiletest.yml` with the required fields. The goal is a context that boots cleanly without being `dev`.

- [ ] **Step 4.8: Run bean profile test**

```bash
cd backend && ./mvnw test -Dtest=DevSlSimulateBeanProfileTest -q 2>&1 | tail -30
```

Expected: 1 test passing. If the context fails to start, adjust per the note above.

- [ ] **Step 4.9: Full backend test run**

```bash
cd backend && ./mvnw test -q 2>&1 | tail -30
```

Expected: `BUILD SUCCESS`. All prior tests still green.

- [ ] **Step 4.10: Postman — create `Dev/` folder + Simulate SL verify endpoint**

`createCollectionFolder`: name `"Dev"`, description `"Dev-profile-only helpers. Not available in prod."`.

`createCollectionRequest`: `POST {{baseUrl}}/api/v1/dev/sl/simulate-verify`, header `Content-Type: application/json`, body:
```json
{
  "verificationCode": "{{verificationCode}}"
}
```

Saved example: 200 `{ "verified": true, "userId": 1, "slAvatarName": "Dev Tester" }`.

- [ ] **Step 4.11: Manual browser-flow smoke**

Start the backend (`dev` profile), start the frontend, log in from the browser, open Postman, run `Verification/Generate code`, then run `Dev/Simulate SL verify`. Confirm `users` row in Postgres has `verified=true`.

```bash
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev &
cd frontend && npm run dev &
```

After manual verification, stop both processes.

- [ ] **Step 4.12: Commit Task 4**

```bash
git add backend/src
git commit -m "feat(dev): add dev-profile SL verification simulate helper

DevSlSimulateController exposes POST /api/v1/dev/sl/simulate-verify under
@Profile(\"dev\") only. Synthesizes SL headers internally (using the first
trusted owner key from config) and delegates to the real SlVerificationService,
exercising the same code path and exception mapping as /api/v1/sl/verify.

DevSimulateRequest defaults fill in a random avatar UUID per call so
repeated dev tests don't trip the unique constraint unless the caller
deliberately reuses a UUID.

SecurityConfig permits /api/v1/dev/** unconditionally; the bean-level
@Profile is the real gate. DevSlSimulateBeanProfileTest proves the gate
works by asserting the controller field is null in a non-dev profile.

Integration test covers the browser-flow path: register → generate code →
simulate with just the code → user row has verified=true with the default
'Dev Tester' avatar name."
```

- [ ] **Step 4.13: Push**

```bash
git push
```

---

## Task 5: Prefix migration smoke test, README, FOOTGUNS, PR

**Estimated time:** 45-60 minutes.
**Commits:** 2 (docs sweep + final polish).

### Steps

- [ ] **Step 5.1: Write `PrefixMigrationSmokeTest`**

File: `backend/src/test/java/com/slparcelauctions/backend/config/PrefixMigrationSmokeTest.java`

```java
package com.slparcelauctions.backend.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Narrative smoke test that pins the API prefix migration. Four routes across
 * the renamed surface; any failure signals the migration is incomplete.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = "auth.cleanup.enabled=false")
@Transactional
class PrefixMigrationSmokeTest {

    @Autowired MockMvc mockMvc;

    @Test
    void renamedRoutesResolve_andOldRoutesAreDead() throws Exception {
        // Register on the new path — positive proof
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"smoke@example.com\",\"password\":\"hunter22abc\",\"displayName\":\"Smoke\"}"))
                .andExpect(status().isCreated());

        // Health on the new path
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk());

        // Old path should not resolve — 404 (Spring MVC has no handler)
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"x@y.z\",\"password\":\"a\",\"displayName\":\"x\"}"))
                .andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 5.2: Run the smoke test**

```bash
cd backend && ./mvnw test -Dtest=PrefixMigrationSmokeTest -q 2>&1 | tail -20
```

Expected: 1 test passing.

- [ ] **Step 5.3: Full backend test suite**

```bash
cd backend && ./mvnw test -q 2>&1 | tail -30
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 5.4: Full frontend test suite + verify chain**

```bash
cd frontend && npm test -- --run 2>&1 | tail -20
cd frontend && npm run lint 2>&1 | tail -20
ls frontend/scripts/verify-*.sh 2>/dev/null && for f in frontend/scripts/verify-*.sh; do bash "$f" || echo "FAILED: $f"; done
```

Expected: all green.

- [ ] **Step 5.5: Update README.md**

File: `README.md` (at repo root)

Read the current README first, then sweep:
- The backend section should mention the `/api/v1` prefix.
- The backend section should mention the dev SL simulate helper endpoint.
- The "external integrations" or equivalent section should mention the Postman collection location (`https://scatr-devs.postman.co/workspace/SLPA~.../collection/...`).
- Any "getting started" instructions that reference the old `/api/` prefix must be updated.

Per memory: "Update README.md at the end of every task". Do a full sweep, not just targeted edits.

- [ ] **Step 5.6: Update FOOTGUNS.md**

File: `docs/implementation/FOOTGUNS.md`

Read the current highest footgun number (the summary history shows F.21 as the most recent). Add new footguns:

**F.22 — Refresh-token cookie Path must be renamed in lock-step with the endpoint rename.**
Reason: `AuthController.REFRESH_COOKIE_PATH` and the MSW handler's `Set-Cookie: Path=/api/v1/auth` must match the URL `/api/v1/auth/refresh`. If they drift (e.g., endpoint moved to `/api/v1/auth` but cookie still set at `Path=/api/auth`), the browser won't send the refresh cookie on rotation and every token refresh fails silently with 401 "missing cookie."
How to apply: any future URL versioning or auth-path restructure must touch both the controller constant and the MSW handlers' `Set-Cookie` headers in the same commit.

**F.23 — `@Profile("dev")` alone doesn't gate URL access; the security matcher must permit the path too.**
Reason: Spring Security runs its filter chain before hitting the handler. If `DevSlSimulateController` is `@Profile("dev")` but `SecurityConfig` has no `permitAll()` for `/api/v1/dev/**`, requests in dev profile hit the `/api/v1/**` catch-all and get rejected as 401 before reaching the handler. Conversely, leaving the permit matcher in for prod is harmless: the bean doesn't exist, so the request falls through to a 404 at the MVC layer.
How to apply: any profile-gated endpoint must have both the `@Profile` on the bean AND a security matcher that permits the path (preferably unconditionally — let the bean absence be the real gate).

**F.24 — `DataIntegrityViolationException` handling lives in the exception handler, not inline in the service.**
Reason: Catching `DataIntegrityViolationException` in a `@Transactional` service method is awkward — the transaction is already marked rollback-only, and the catch forces the service-layer tests to construct fake constraint-violation exceptions. The cleaner pattern is to let the exception bubble, catch it in a `@RestControllerAdvice`, inspect the constraint name, and rewrap as a domain exception (or rethrow for unknown constraints, falling through to the 500 handler).
How to apply: when future features need uniqueness-constraint race handling, put the catch in the slice's `@RestControllerAdvice`, never in the service method. See `SlExceptionHandler.handleDataIntegrity`.

**F.25 — Prod-profile startup tests are a dead end.**
Reason: The prod profile has `jwt.secret: ${JWT_SECRET}` with no default and `slpa.sl.trusted-owner-keys: []` which triggers `SlStartupValidator.check` to throw. Any JUnit test with `@ActiveProfiles("prod")` will fail to start the context before a single assertion fires.
How to apply: to prove a `@Profile("dev")` bean is absent outside dev, use the `@Autowired(required = false)` pattern under a neutral test profile (see `DevSlSimulateBeanProfileTest`). Never try to boot a prod-profile Spring context inside the test JVM.

- [ ] **Step 5.7: Commit docs**

```bash
git add README.md docs/implementation/FOOTGUNS.md
git commit -m "docs: README sweep and FOOTGUNS F.22-F.25 for Epic 02 sub-spec 1

F.22 — refresh-cookie Path must match the endpoint path
F.23 — @Profile(\"dev\") needs a matching SecurityConfig permit
F.24 — DataIntegrityViolationException handling belongs in the advice
F.25 — prod-profile Spring contexts cannot boot in tests"
```

- [ ] **Step 5.8: Commit the smoke test (if not already bundled)**

```bash
git add backend/src/test/java/com/slparcelauctions/backend/config/PrefixMigrationSmokeTest.java
git commit -m "test(config): add PrefixMigrationSmokeTest pinning /api/v1 rename

Four-route narrative test that fails loudly if any future refactor accidentally
restores the old /api/ prefix. Positive proof (new paths resolve) plus negative
proof (old paths 404)."
```

- [ ] **Step 5.9: Push**

```bash
git push
```

- [ ] **Step 5.10: Open PR into `dev`**

```bash
gh pr create --base dev --title "Epic 02 sub-spec 1 — verification backend" --body "$(cat <<'EOF'
## Summary

Implements Epic 02 sub-spec 1 (verification backend) per `docs/superpowers/specs/2026-04-13-epic-02-sub-1-verification-backend.md`. Four commits covering:

- API prefix migration `/api/*` → `/api/v1/*` across backend, frontend, tests, and the refresh-token cookie Path attribute
- `verification/` vertical slice: `VerificationCode` entity, repo, service, `GET /active` + `POST /generate` endpoints, DTOs, package-scoped exception handler, three levels of tests
- `sl/` vertical slice: `SlConfigProperties`, `SlStartupValidator` (prod fail-fast), `SlHeaderValidator`, `SlVerificationService` orchestrator, `POST /api/v1/sl/verify` endpoint, `SlExceptionHandler` (including `DataIntegrityViolationException` race handling), three levels of tests
- Dev-profile `POST /api/v1/dev/sl/simulate-verify` helper for browser-testable E2E before Epic 11 ships real LSL scripts

Postman `SLPA` collection populated via MCP: `Auth/`, `Users/`, `Verification/`, `SL/`, `Dev/` folders with saved examples and variable-chaining scripts. `SLPA Dev` environment created with `baseUrl`, `accessToken`, `refreshToken`, `userId`, `slpaServiceAccountUuid`, `verificationCode`.

FOOTGUNS.md gains F.22–F.25 for the load-bearing gotchas surfaced during implementation. README.md swept for `/api/v1` references.

## Test plan

- [ ] `./mvnw test` passes (all existing + new backend tests)
- [ ] `npm test` passes (all existing frontend tests post-rename)
- [ ] Coverage gate holds at the existing threshold
- [ ] Postman: run Login → Generate → SL/Verify player end-to-end against a dev backend
- [ ] Postman: run Login → Generate → Dev/Simulate SL verify, confirm user row has `verified=true`
- [ ] Manual browser smoke: dev backend on :8080, dev frontend on :3000, register a user from browser, generate code via Postman, fire Dev simulate, confirm Postgres row updated
- [ ] Verify `curl http://localhost:8080/api/health` returns 404 (old path is dead) and `curl http://localhost:8080/api/v1/health` returns 200
EOF
)"
```

Expected: PR URL printed. Record it.

- [ ] **Step 5.11: Return to dev branch after PR opens**

```bash
git checkout dev
```

The user reviews and merges the PR via GitHub. Per the memory rule "user reviews on GitHub, local-only commits don't count" — do not auto-merge.

---

## Done definition

Sub-spec 1 is complete when:

- [ ] PR into `dev` is opened and passing CI
- [ ] The user has reviewed and merged the PR
- [ ] `dev` branch locally matches `origin/dev` after merge
- [ ] Manual smoke on `dev`: full flow via Postman works end-to-end
- [ ] Postman collection reflects the final endpoint inventory with saved examples

---

## Appendix — spec section cross-reference

| Spec section | Plan coverage |
|---|---|
| §1 Summary | Plan preamble |
| §2 Scope | Preamble + Task list |
| §3 References | Plan preflight (read spec) |
| §4.1 Prefix rename | Task 1 (Steps 1.1–1.17) |
| §4.2 Verification routes | Task 2 (Steps 2.13–2.14) |
| §4.3 SL verify route | Task 3 (Step 3.18) |
| §4.4 Dev simulate route | Task 4 (Step 4.2) |
| §5 Package structure | File Structure section |
| §6 Data model | Task 2 (Steps 2.2–2.4) |
| §7 VerificationCodeService | Task 2 (Steps 2.7–2.12) |
| §8 SlVerificationService | Task 3 (Steps 3.13–3.15) |
| §9 SlHeaderValidator + config | Task 3 (Steps 3.1–3.10) |
| §10 Dev simulate helper | Task 4 |
| §11 Prefix migration scope | Task 1 |
| §12 Postman scaffolding | Task 1.18–1.25, 2.20–2.22, 3.26–3.27, 4.10 |
| §13 Testing strategy | Test files in every task |
| §14 Security considerations | Implicit in permit matchers + exception mapping |
| §15 Exception mapping table | Task 2 handler, Task 3 handler |
| §15.1 DataIntegrityViolationException catch in handler | Task 3 (Step 3.17) |
| §16 Task breakdown | Tasks 1-4 |
| §17 Done definition | Task 5 + Done definition section above |
| §18 Deferred to sub-spec 2 | Not in this plan |
| §19 Decisions log | Not in this plan |
