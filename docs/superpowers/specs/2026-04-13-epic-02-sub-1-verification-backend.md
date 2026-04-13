# Epic 02 — Sub-spec 1: Verification Backend

**Date:** 2026-04-13
**Epic:** 02 — Player Verification
**Sub-spec scope:** Tasks 02-01 (verification code generation) + 02-02 (SL verify endpoint)
**Status:** design
**Author:** brainstorming session

---

## 1. Summary

Ship the backend verification flow end-to-end so an authenticated user can request a 6-digit code from a future dashboard, hand that code to an "in-world" actor (real SL script later, simulated via a dev-profile helper today), and have that actor call a public SL endpoint that validates SL-injected headers plus the code and atomically links an SL avatar identity to the web account.

After this sub-spec merges, the dashboard UI still does not exist. The full flow is testable via Postman (for backend smoke), via the backend integration-test suite, and via the dev-profile `POST /api/v1/dev/sl/simulate-verify` helper (for browser E2E before Epic 11 ships real LSL scripts).

---

## 2. Scope

### In scope

1. **API-prefix migration (throw-in).** Rename all existing `@RequestMapping("/api/...")` in the backend, all frontend fetch paths, the refresh-token cookie `Path` attribute, and the `SecurityConfig` matchers from `/api/...` to `/api/v1/...` as a standalone opening commit. This is the cheapest possible moment to pay that cost — only two domains (`auth`, `users`) exist today.
2. **Verification code primitive** — `verification/` package with entity, repo, service, REST controller for `GET /api/v1/verification/active` and `POST /api/v1/verification/generate`.
3. **SL verification endpoint** — `sl/` package with real `POST /api/v1/sl/verify` endpoint, header validation, orchestrator service that consumes the verification code and writes SL fields onto the `users` row.
4. **Dev-profile simulate helper** — `POST /api/v1/dev/sl/simulate-verify`, gated three ways (bean profile, security config profile, synthesized owner key matching `application-dev.yml`).
5. **Postman collection scaffolding** — create and populate the existing empty SLPA collection as part of this sub-spec, with task-local updates so the collection never drifts from the codebase.
6. **Three-level tests** — unit, `@WebMvcTest` slice, `@SpringBootTest` integration — matching the existing `AuthFlowIntegrationTest` pattern.

### Out of scope (deferred to sub-spec 2)

- Task 02-03 user profile REST API and image upload.
- Task 02-04 dashboard UI (verification flow, verification status display, account settings, tabs).
- Task 02-05 public user profile page.
- Rate limiting on `POST /api/v1/verification/generate` (deferred to Epic 07).
- Cleanup cron for expired verification codes (not needed now; deferred indefinitely).

### Non-goals

- Any new Flyway migration. The `verification_codes` table already exists from V2 (supporting tables); the `users` table already has every SL column from V1 (core tables). Hibernate `ddl-auto: update` handles anything else if a stray field is needed (unlikely in this sub-spec).
- Any change to the WebSocket/STOMP stack under `/ws/**`.
- Any change to the auth token model, refresh token cleanup job, or JWT shape.

---

## 3. Background and references

- `docs/initial-design/DESIGN.md` §4.1, §6.1, §9 — verification flow design.
- `docs/implementation/epic-02/02-player-verification.md` — phase goal.
- `docs/implementation/epic-02/task-01-verification-codes.md` — Task 02-01 spec.
- `docs/implementation/epic-02/task-02-sl-verify-endpoint.md` — Task 02-02 spec.
- `docs/implementation/CONVENTIONS.md` — project-wide rules (no new migrations, Lombok required, vertical slices, feature-based packages).
- `backend/src/main/resources/db/migration/V2__supporting_tables.sql` lines 266-281 — existing `verification_codes` schema (6-digit CHECK constraint, `(user_id, used)` index, `expires_at` index, no uniqueness on `code` alone).
- Existing vertical slices to match style against: `auth/` (AuthController, AuthService, dto, exception subpackages), `user/` (UserController, UserService, dto, exceptions at package root).
- Existing integration-test anchor: `backend/src/test/java/com/slparcelauctions/backend/auth/AuthFlowIntegrationTest.java` — pattern we reuse for the new integration tests in this sub-spec.

---

## 4. API surface

All new and migrated routes sit under `/api/v1/`. Header-gated routes are not JWT-authenticated.

### 4.1 Existing routes — renamed by the migration task

| Method | Before | After |
|---|---|---|
| `POST` | `/api/auth/register` | `/api/v1/auth/register` |
| `POST` | `/api/auth/login` | `/api/v1/auth/login` |
| `POST` | `/api/auth/refresh` | `/api/v1/auth/refresh` |
| `POST` | `/api/auth/logout` | `/api/v1/auth/logout` |
| `POST` | `/api/auth/logout-all` | `/api/v1/auth/logout-all` |
| `POST` | `/api/users` | `/api/v1/users` |
| `GET`  | `/api/users/me` | `/api/v1/users/me` |
| `GET`  | `/api/users/{id}` | `/api/v1/users/{id}` |
| `GET`  | `/api/health` | `/api/v1/health` |

The refresh-token cookie `Path` attribute (`REFRESH_COOKIE_PATH` in `AuthController.java`) moves from `/api/auth` to `/api/v1/auth` in the same commit. The `SecurityConfig` matchers `/api/**` and all subpaths under them are updated in the same commit.

### 4.2 New routes — verification primitive

#### `GET /api/v1/verification/active`

**Auth:** JWT (authenticated user).
**Purpose:** Non-destructive read of the caller's currently active `PLAYER` verification code, if any.

**Request:** no body.

**Response — 200:**
```json
{
  "code": "184307",
  "expiresAt": "2026-04-13T21:15:00Z"
}
```

**Response — 404** (no active code exists, or the most recent one is expired / already used):
`ProblemDetail` with `title: "No active verification code"`, `status: 404`, `detail: "Generate a new code from your dashboard."`

**Response — 409** (caller account is already SL-verified — they shouldn't be polling this endpoint):
`ProblemDetail` with `title: "Account already verified"`, `status: 409`.

Notes:
- The service query is `findFirstByUserIdAndTypeAndUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc(principal.userId, PLAYER, now)`. If multiple unexpired unused rows exist for the same user (shouldn't happen thanks to `POST /generate` voiding old rows, but possible if two requests race) this returns the newest.

#### `POST /api/v1/verification/generate`

**Auth:** JWT (authenticated user).
**Purpose:** Create a fresh 6-digit code, void any prior active code for this user, return the new code + expiration.

**Request:** no body. Caller inferred from `AuthPrincipal`.

**Response — 200:**
```json
{
  "code": "847219",
  "expiresAt": "2026-04-13T21:30:00Z"
}
```

**Response — 409** (caller already SL-verified):
`ProblemDetail` with `title: "Account already verified"`, `status: 409`, `detail: "This account is already linked to an SL avatar. Contact support if this is wrong."`

Notes:
- Every successful call flips any prior `(userId, type=PLAYER, used=false)` rows to `used=true` inside the same transaction, then inserts the new row. This is the Q5d "always creates fresh" behavior locked in brainstorming.
- Expires at `OffsetDateTime.now() + 15 minutes` (single constant `VerificationCodeService.CODE_TTL`).
- The code is generated as `Random.nextInt(1_000_000)` zero-padded to 6 digits. No retries, no uniqueness constraint on `code` alone — Q5b locked "accept collisions." A `SecureRandom` is preferred (cheaper than you'd think, and the entropy is free insurance even though the threat model is weak).

### 4.3 New routes — SL verification surface

#### `POST /api/v1/sl/verify`

**Auth:** None — `permitAll()`. Request identity is asserted by SL-injected headers, not JWT.

**Headers (validated, required):**
- `X-SecondLife-Shard: Production` — anything else (including `"Beta"`) rejected.
- `X-SecondLife-Owner-Key: <uuid>` — must parse as UUID and must be contained in the configured `slpa.sl.trustedOwnerKeys` set.
- Other SL-injected headers (`X-SecondLife-Object-Name`, `X-SecondLife-Region`, etc.) are accepted and logged but not validated in this sub-spec — they become interesting in Epic 11.

**Request body:**
```json
{
  "verificationCode": "847219",
  "avatarUuid": "a1b2c3d4-...-...",
  "avatarName": "Resident Name",
  "displayName": "Fancy Display",
  "username": "resident.name",
  "bornDate": "2011-03-15",
  "payInfo": 3
}
```

Bean Validation: all fields `@NotNull`/`@NotBlank`; `verificationCode` matches `^[0-9]{6}$`; `avatarUuid` is a valid UUID; `bornDate` parses as ISO local date; `payInfo` is `0..3` inclusive (LSL `DATA_PAYINFO` encoding).

**Response — 200:**
```json
{
  "verified": true,
  "userId": 17,
  "slAvatarName": "Resident Name"
}
```

The LSL script (future) reads this response body, parses `verified`, and displays a confirmation message in-world. The response intentionally omits sensitive user fields — SL callers get the minimum needed to confirm success.

**Error responses — all `ProblemDetail`**:

| HTTP | Exception | `title` | `detail` |
|---|---|---|---|
| 403 | `InvalidSlHeadersException` (wrong shard) | "Invalid SL headers" | "This endpoint only accepts Production grid requests." |
| 403 | `InvalidSlHeadersException` (missing / unparseable / unknown owner key) | "Invalid SL headers" | "Request was not signed by a trusted SLPA script owner." |
| 400 | `CodeNotFoundException` | "Verification failed" | "Code not found, expired, or already used. Please generate a new code from your dashboard." |
| 409 | `CodeCollisionException` | "Verification failed" | "Please generate a new code from your dashboard." |
| 409 | `AvatarAlreadyLinkedException` | "Avatar already linked" | "This SL avatar is already linked to another SLPA account." |
| 409 | `AlreadyVerifiedException` | "Account already verified" | "This account is already linked to an SL avatar." |

`CodeCollisionException` is the Q5b edge case: two users happened to have the same live code. The service voids **both** matching rows and throws. Both users have to regenerate. Logged at **WARN** level with both user IDs and the colliding code, never 500. Remediation message is identical to the other invalid-code cases so the LSL script needs only one "generate a new code" branch.

### 4.4 New routes — dev-profile simulate helper

#### `POST /api/v1/dev/sl/simulate-verify`

**Auth:** None — `permitAll()`, gated to dev profile.
**Registered:** Only when Spring profile `dev` is active. Bean is not instantiated otherwise.

**Purpose:** Let a browser-based dashboard test the full verification round-trip without Postman or LSL. Synthesizes the SL headers and request body fields internally, then calls the same `SlVerificationService` method the real `/sl/verify` endpoint calls. Header validation still runs; the dev placeholder owner key in `application-dev.yml` satisfies it.

**Request body — all optional except `verificationCode`:**
```json
{
  "verificationCode": "847219",
  "avatarUuid": null,
  "avatarName": null,
  "displayName": null,
  "username": null,
  "bornDate": null,
  "payInfo": null
}
```

Defaults applied when fields are omitted / null:
- `avatarUuid` → `UUID.randomUUID()` (so repeated calls don't trip "already linked")
- `avatarName` → `"Dev Tester"`
- `displayName` → `"Dev Tester"`
- `username` → `"dev.tester"`
- `bornDate` → `LocalDate.of(2012, 1, 1)`
- `payInfo` → `3`

**Response:** same shape as `POST /api/v1/sl/verify`.

Errors bubble up identically (the service call is the same), so the frontend dev harness sees the exact same failure modes as the real endpoint would.

---

## 5. Package structure

New packages added in this sub-spec:

```
com.slparcelauctions.backend/
├── verification/                        [NEW]
│   ├── VerificationCode.java            @Entity, maps verification_codes table
│   ├── VerificationCodeRepository.java  Spring Data JPA
│   ├── VerificationCodeType.java        enum PLAYER | PARCEL
│   ├── VerificationCodeService.java     generate / findActive / consume / voidActive
│   ├── VerificationController.java      /api/v1/verification/active, /generate
│   ├── dto/
│   │   ├── ActiveCodeResponse.java      record { String code, OffsetDateTime expiresAt }
│   │   └── GenerateCodeResponse.java    record { String code, OffsetDateTime expiresAt }
│   ├── exception/
│   │   ├── AlreadyVerifiedException.java
│   │   ├── CodeNotFoundException.java   (covers not-found, expired, used — see §7)
│   │   └── CodeCollisionException.java
│   └── VerificationExceptionHandler.java  @RestControllerAdvice scoped to verification.*
├── sl/                                  [NEW]
│   ├── SlConfigProperties.java          @ConfigurationProperties("slpa.sl"), record
│   ├── SlStartupValidator.java          @EventListener(ApplicationReadyEvent.class), prod fail-fast
│   ├── SlHeaderValidator.java           @Component, checks X-SecondLife-Shard + Owner-Key
│   ├── SlVerificationService.java       orchestrator: consume code → link avatar → persist
│   ├── SlVerificationController.java    /api/v1/sl/verify
│   ├── DevSlSimulateController.java     /api/v1/dev/sl/simulate-verify, @Profile("dev")
│   ├── dto/
│   │   ├── SlVerifyRequest.java         record with bean validation
│   │   ├── SlVerifyResponse.java        record
│   │   └── DevSimulateRequest.java      record with optional fields
│   ├── exception/
│   │   ├── InvalidSlHeadersException.java
│   │   └── AvatarAlreadyLinkedException.java
│   └── SlExceptionHandler.java          @RestControllerAdvice scoped to sl.*
├── auth/   (existing — edited for prefix rename only)
└── user/   (existing — edited for prefix rename only)
```

**Dependency direction is strict** and enforced by code review:
- `sl/` may depend on `verification/` (calls `VerificationCodeService`).
- `sl/` may depend on `user/` (reads and updates `User` rows via `UserRepository`).
- `verification/` depends on nothing else in this sub-spec.
- `auth/` and `user/` remain untouched by the new packages — they only see the prefix rename.
- No circular imports. No cross-package `Impl` unwinding.

Each of `verification/` and `sl/` owns its own `@RestControllerAdvice` scoped via `basePackages` / `assignableTypes`, so the two packages never silently hijack each other's exception mapping. If a common exception handler already exists in `common/exception/`, this sub-spec adds package-scoped handlers that complement (not replace) it.

---

## 6. Data model

### 6.1 `VerificationCode` entity

Maps the existing `verification_codes` table one-to-one. No new columns, no schema change, no Flyway migration.

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

**Design notes:**
- `userId` is `Long`, not a `@ManyToOne` to `User`. This avoids lazy-loading surprises during validation and keeps the aggregate boundary clean — a `VerificationCode` doesn't need the full user graph to do its job. `SlVerificationService` loads the `User` directly via `UserRepository` when it needs to write SL fields.
- `used` is a **primitive `boolean`**, not boxed `Boolean`. The DB column is `NOT NULL DEFAULT FALSE`; primitive enforces non-null at the compiler level so no conditional check can trip on a surprise `null`.
- No `version` field / `@Version` optimistic locking. The `POST /generate` transaction serializes through the `(userId, type, used=false)` lookup + update + insert, which on a hot path would need locking but in practice is gated by the human user pressing a button. Add optimistic locking later if needed.

### 6.2 `VerificationCodeType` enum

```java
package com.slparcelauctions.backend.verification;

public enum VerificationCodeType {
    PLAYER,
    PARCEL
}
```

Only `PLAYER` is used in this sub-spec. `PARCEL` is reserved for Epic 03 and lives in the same enum so `verification/` remains the source of truth for code types.

### 6.3 `User` entity — no changes

All SL columns (`slAvatarUuid`, `slAvatarName`, `slDisplayName`, `slUsername`, `slBornDate`, `slPayinfo`, `verified`, `verifiedAt`) already exist on `User.java` from Epic 01. `SlVerificationService` only writes them; no new fields, no column widening, no nullability change.

### 6.4 Repository

```java
package com.slparcelauctions.backend.verification;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface VerificationCodeRepository
        extends JpaRepository<VerificationCode, Long> {

    /**
     * Used by SlVerificationService during /sl/verify. Returns ALL matching rows
     * because the collision-detection path (Q5b) needs to distinguish "exactly
     * one match" from "more than one match."
     */
    List<VerificationCode> findByCodeAndTypeAndUsedFalseAndExpiresAtAfter(
            String code, VerificationCodeType type, OffsetDateTime now);

    /**
     * Used by GET /api/v1/verification/active to hydrate the dashboard.
     * findFirst... + Order By createdAt DESC handles the theoretical race where
     * two generate calls interleave before one has voided the other's row.
     */
    Optional<VerificationCode> findFirstByUserIdAndTypeAndUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc(
            Long userId, VerificationCodeType type, OffsetDateTime now);

    /**
     * Used by POST /api/v1/verification/generate to void any prior active codes
     * for a user before inserting a new one. Returns the count voided (for logging).
     */
    long countByUserIdAndTypeAndUsedFalse(Long userId, VerificationCodeType type);

    // The void-prior update uses the list-based pattern — findBy... + repo.saveAll
    // with used=true set on each — rather than a custom @Modifying @Query, so the
    // entity's audit hooks keep firing. Premature cleverness avoided.
    List<VerificationCode> findByUserIdAndTypeAndUsedFalse(
            Long userId, VerificationCodeType type);
}
```

---

## 7. `VerificationCodeService` design

```java
package com.slparcelauctions.backend.verification;

@Service
@RequiredArgsConstructor
@Slf4j
public class VerificationCodeService {

    public static final Duration CODE_TTL = Duration.ofMinutes(15);

    private final VerificationCodeRepository repository;
    private final UserRepository userRepository;   // to check "already verified" guard
    private final Clock clock;                      // injected for test determinism
    private final SecureRandom random = new SecureRandom();

    /** Generate a fresh code for the given user, voiding any prior active code. */
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
        VerificationCode saved = repository.save(
            VerificationCode.builder()
                .userId(userId)
                .code(code)
                .type(type)
                .expiresAt(expiresAt)
                .used(false)
                .build());
        log.info("Generated verification code for user {} (type={}, id={})",
            userId, type, saved.getId());
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
     * Consume a code — validate it (exists / not expired / not used) and mark it used.
     * Throws specific exceptions for each failure mode so the caller can map to the
     * right HTTP status. Handles the Q5b collision case: if >1 unused unexpired row
     * matches the code, void both and throw CodeCollisionException.
     */
    @Transactional
    public Long consume(String code, VerificationCodeType type) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<VerificationCode> matches = repository
            .findByCodeAndTypeAndUsedFalseAndExpiresAtAfter(code, type, now);

        if (matches.isEmpty()) {
            // Not found, expired, or already used — all three collapse into the
            // same response (caller's remediation is identical: regenerate). See
            // the "single-exception choice" note below the snippet.
            throw new CodeNotFoundException(code);
        }

        if (matches.size() > 1) {
            List<Long> affectedUserIds = matches.stream().map(VerificationCode::getUserId).toList();
            log.warn("Verification code collision: code={} users={} — voiding all matches",
                code, affectedUserIds);
            matches.forEach(c -> c.setUsed(true));
            repository.saveAll(matches);
            throw new CodeCollisionException(code, affectedUserIds);
        }

        VerificationCode match = matches.get(0);
        match.setUsed(true);
        repository.save(match);
        return match.getUserId();
    }

    /** Void any active codes for a user so the next generate starts clean. */
    private void voidActive(Long userId, VerificationCodeType type) {
        List<VerificationCode> active = repository
            .findByUserIdAndTypeAndUsedFalse(userId, type);
        if (active.isEmpty()) return;
        active.forEach(c -> c.setUsed(true));
        repository.saveAll(active);
        log.info("Voided {} prior active code(s) for user {}", active.size(), userId);
    }
}
```

**Clock injection.** Tests substitute `Clock.fixed(...)` to deterministically exercise the "expires at exactly now" boundary. Production wires `Clock.systemUTC()` in a simple `@Configuration` bean.

**Single-exception choice.** The snippet folds "not found", "expired", and "used" into `CodeNotFoundException`. The caller's remediation is identical in all three cases (generate a new code), the LSL-side UX stays simple (one branch, one message), and the service avoids a second query to disambiguate. If future UX feedback says the user needs to distinguish "expired, you were close" from "never existed", the split can be added without changing the call sites — replace the single throw with a follow-up query that returns the most recent `used=true` or `expires_at < now` row for that code and throws the more specific exception. Not worth doing now.

---

## 8. `SlVerificationService` design

Orchestrator that bridges `verification/` and `user/`.

```java
package com.slparcelauctions.backend.sl;

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

        // Pre-check avatar uniqueness before we consume the code. This avoids
        // voiding a valid code because the user happened to link a taken avatar.
        Optional<User> existingLinked = userRepository.findBySlAvatarUuid(body.avatarUuid());
        if (existingLinked.isPresent()) {
            throw new AvatarAlreadyLinkedException(body.avatarUuid());
        }

        // Consume atomically — this validates + marks used + returns the user id.
        Long userId = verificationCodeService.consume(body.verificationCode(), VerificationCodeType.PLAYER);

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

**Pre-check, then consume.** We check `findBySlAvatarUuid` *before* calling `consume(...)` so a valid code isn't burned by an unlinkable avatar (same user's second attempt should still work). The check + consume race is not risk-free — two SL verify calls with the same avatar could both pass the pre-check simultaneously — but the DB-level `UNIQUE` constraint on `users.sl_avatar_uuid` (from V1 migration) catches it on persist and surfaces as `DataIntegrityViolationException`. **The service does not catch this exception** — it bubbles up and is handled in `SlExceptionHandler`, which inspects the constraint name and rethrows as `AvatarAlreadyLinkedException` for the known race or lets unknown constraints fall through to the 500 catch-all. See §15.1 for the handler code. Keeping the catch in the handler (not inline in the service) means the service-layer unit tests never need to construct fake `DataIntegrityViolationException` values.

**`UserRepository.findBySlAvatarUuid` is new** — it'll be added alongside the prefix-rename commit since it's a one-liner method signature that `user/` needs. (If `user/` already exposes a similar method, use it as-is.)

---

## 9. `SlHeaderValidator` and `SlConfigProperties`

### 9.1 Config properties

```java
package com.slparcelauctions.backend.sl;

import java.util.Set;
import java.util.UUID;

import org.springframework.boot.context.properties.ConfigurationProperties;

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

Bound via `@EnableConfigurationProperties(SlConfigProperties.class)` on a `@Configuration` class inside `sl/`.

### 9.2 YAML

`backend/src/main/resources/application.yml` (new section):
```yaml
slpa:
  sl:
    expected-shard: Production
    trusted-owner-keys: []
```

`backend/src/main/resources/application-dev.yml` (new section):
```yaml
slpa:
  sl:
    trusted-owner-keys:
      - "00000000-0000-0000-0000-000000000001"
```

`application-prod.yml` (if it exists; create if not): same `slpa.sl.trusted-owner-keys: []` — the prod deploy pipeline is expected to inject real UUIDs via env var or external config. The startup validator fails fast if the prod set is empty.

### 9.3 Startup validator

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class SlStartupValidator {

    private final SlConfigProperties props;
    private final Environment environment;

    @EventListener(ApplicationReadyEvent.class)
    public void check() {
        boolean isProd = Arrays.stream(environment.getActiveProfiles())
            .anyMatch("prod"::equals);
        if (isProd && props.trustedOwnerKeys().isEmpty()) {
            throw new IllegalStateException(
                "slpa.sl.trusted-owner-keys is empty in prod profile — "
                + "refusing to start. Configure at least one UUID.");
        }
        if (props.trustedOwnerKeys().isEmpty()) {
            log.warn("slpa.sl.trusted-owner-keys is empty — all /api/v1/sl/verify "
                + "calls will be rejected. (non-prod profile, not fatal.)");
        }
    }
}
```

### 9.4 Header validator

```java
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

The validator is stateless and cached — `SlConfigProperties` is immutable after boot. No lookup-per-request latency concern.

---

## 10. Dev-profile simulate helper

```java
package com.slparcelauctions.backend.sl;

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
        SlVerifyRequest synthesized = req.toSlVerifyRequest();  // applies defaults inline
        log.info("Dev simulate: forwarding to SlVerificationService with ownerKey={}", ownerKey);
        return slVerificationService.verify(
            slConfig.expectedShard(),
            ownerKey.toString(),
            synthesized);
    }
}
```

**Three-layer gating:**
1. **Bean-level `@Profile("dev")`** — the bean is not instantiated in any other profile. A `@RestController` that's not a bean doesn't register its routes.
2. **`SecurityConfig` permit matcher** — `/api/v1/dev/**` is only added to `permitAll()` when the dev profile is active. This is done with a `@Profile("dev")` configuration class that contributes a second `SecurityFilterChain` or, more simply, with a runtime profile check inside the existing `SecurityConfig` (see §11).
3. **Random avatar UUID default** — `DevSimulateRequest.toSlVerifyRequest()` fills missing `avatarUuid` with `UUID.randomUUID()` so repeated dev calls don't accidentally hit "already linked" unless the caller deliberately reuses a UUID.

### 10.1 `DevSimulateRequest`

```java
package com.slparcelauctions.backend.sl.dto;

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

---

## 11. API prefix migration — execution plan

This is Task 1 of the sub-spec, run as a standalone commit before any new code lands.

### 11.1 Backend touch list

Grep-anchor sweep with `rg '"/api/'` from `backend/src`:

**Controllers:**
- `auth/AuthController.java` — `@RequestMapping("/api/auth")` → `/api/v1/auth` (one line)
- `auth/AuthController.java` — `REFRESH_COOKIE_PATH = "/api/auth"` → `/api/v1/auth` (one constant)
- `user/UserController.java` — `@RequestMapping("/api/users")` → `/api/v1/users` (one line)
- `controller/*` — grep sweep; unknown inventory as of spec time
- `wstest/*` — `/ws/**` endpoints unchanged; `/api/*` references (if any) renamed

**Security config:**
- `config/SecurityConfig.java` — every `requestMatchers("/api/...")` matcher becomes `/api/v1/...`. Specifically:
  - `GET /api/health` → `GET /api/v1/health`
  - `/api/auth/register`, `/auth/login`, `/auth/refresh`, `/auth/logout` → `/api/v1/auth/*`
  - `POST /api/users` → `POST /api/v1/users`
  - `GET /api/users/me` → `GET /api/v1/users/me` (authenticated)
  - `GET /api/users/{id}` → `GET /api/v1/users/{id}` (permitAll)
  - `/api/auth/logout-all` → `/api/v1/auth/logout-all` (authenticated)
  - `/api/**` catch-all → `/api/v1/**` catch-all (authenticated)
- **New permit matchers for sub-spec 1 endpoints** (added in Task 3 + Task 4, not Task 1):
  - `POST /api/v1/sl/verify` → `permitAll()`
  - `POST /api/v1/dev/sl/**` → `permitAll()`, gated by dev profile

**Backend tests:**
- Grep `"/api/"` under `backend/src/test` → every `mockMvc.perform(get("/api/..."))` or `post("/api/...")` gets the `/v1` inserted.
- `AuthFlowIntegrationTest`, `UserIntegrationTest`, `SecurityConfigTest`, `WsTestIntegrationTest`, `BackendApplicationTests` — all touched.

### 11.2 Frontend touch list

Grep-anchor sweep with `rg '"/api/'` and `rg "'/api/'" ` from `frontend/src`:

- `frontend/src/lib/api.ts` — the 401-refresh guard `path.startsWith("/api/auth/")` → `"/api/v1/auth/"`.
- `frontend/src/lib/api.test.ts` — all the `"/api/users/..."`, `"/api/auctions"`, `"/api/health"` literals in tests.
- `frontend/src/lib/api.401-interceptor.test.tsx` — MSW `http.get("*/api/users/me", ...)` etc. become `*/api/v1/users/me`.
- `frontend/src/test/msw/handlers.ts` — every `http.post("*/api/auth/...")` handler and every `Set-Cookie: ... Path=/api/auth` → `Path=/api/v1/auth`. **This is load-bearing: if the cookie path and the refresh URL drift apart, the browser won't send the refresh cookie and every token rotation fails silently. The spec binds them together; both must move in the same commit.**
- Any other `frontend/src/**/*.ts`, `*.tsx` file with an `/api/` literal.

**Frontend tests** — `npm test` should pass cleanly after the rename. If something still fails, the rename is incomplete; do not modify the new endpoints' shape to accommodate stray old paths.

### 11.3 Migration commit shape

The commit diff should read as a pure rename: zero new functionality, zero schema change, zero test deletions. Commit message:

```
refactor(api): migrate /api to /api/v1 across backend, frontend, tests

Rename every @RequestMapping, SecurityConfig matcher, test client path,
frontend fetch URL, MSW handler, and refresh-token cookie Path attribute
from /api/... to /api/v1/... in a single pass. No new endpoints, no
behavioral changes.

This is the cheapest this migration will ever be — two domains, no live API.
Subsequent sub-spec work lands new routes directly under /api/v1.
```

### 11.4 Verification

After the commit:
- `./mvnw test` — all existing backend tests pass.
- `cd frontend && npm test` — all existing frontend tests pass.
- Manual smoke: `./mvnw spring-boot:run` with dev profile, `curl http://localhost:8080/api/v1/health` → 200, `curl http://localhost:8080/api/health` → 404.
- Manual smoke via browser: register / login / dashboard / ws-test page still work.
- `git grep '"/api/[^v]'` — grep for any stray `"/api/"` literal that doesn't start with `/api/v` → should return only `/api/v1/...` or strings in comments/docs, never a live endpoint.

---

## 12. Postman collection scaffolding

### 12.1 Targets

- Workspace: **SLPA** — `3c50bd16-a197-41d2-9cc8-be245b211f46`
- Collection: **SLPA** — uid `8070328-cf3e1190-cb71-4af3-8e2e-70c82dfee288`
- Environment: **SLPA Dev** — created during Task 1 (sub-spec 1, same commit as the prefix rename)

### 12.2 Environment variables

| Key | Type | Initial value | Purpose |
|---|---|---|---|
| `baseUrl` | default | `http://localhost:8080` | Shared host/port |
| `accessToken` | secret | (empty) | Populated by Auth/Login test script |
| `refreshToken` | secret | (empty) | Populated by Auth/Login test script (from cookie) |
| `userId` | default | (empty) | Populated by Auth/Login test script |
| `slpaServiceAccountUuid` | default | `00000000-0000-0000-0000-000000000001` | Matches `application-dev.yml` dev placeholder |
| `verificationCode` | default | (empty) | Populated by Verification/Generate code test script |

### 12.3 Folder tree at end of sub-spec 1

```
SLPA/
├── Auth/                                 (Task 1)
│   ├── Register                          POST {{baseUrl}}/api/v1/auth/register
│   ├── Login                             POST {{baseUrl}}/api/v1/auth/login
│   ├── Refresh                           POST {{baseUrl}}/api/v1/auth/refresh
│   ├── Logout                            POST {{baseUrl}}/api/v1/auth/logout
│   └── Logout all                        POST {{baseUrl}}/api/v1/auth/logout-all
├── Users/                                (Task 1)
│   ├── Create user                       POST {{baseUrl}}/api/v1/users
│   ├── Get current user                  GET  {{baseUrl}}/api/v1/users/me
│   └── Get user by id                    GET  {{baseUrl}}/api/v1/users/{{userId}}
├── Verification/                         (Task 2)
│   ├── Get active code                   GET  {{baseUrl}}/api/v1/verification/active
│   └── Generate code                     POST {{baseUrl}}/api/v1/verification/generate
├── SL/                                   (Task 3)
│   └── Verify player                     POST {{baseUrl}}/api/v1/sl/verify
│                                         Headers: X-SecondLife-Shard: Production
│                                                  X-SecondLife-Owner-Key: {{slpaServiceAccountUuid}}
│                                         Body: { "verificationCode": "{{verificationCode}}", ... }
└── Dev/                                  (Task 4)
    └── Simulate SL verify                POST {{baseUrl}}/api/v1/dev/sl/simulate-verify
                                          Body: { "verificationCode": "{{verificationCode}}" }
```

### 12.4 Chaining scripts

Postman test scripts capture response values into environment variables so endpoints can be run in order without manual copy-paste.

**`Auth/Login` test script (JavaScript, runs after response):**
```javascript
const response = pm.response.json();
pm.environment.set("accessToken", response.accessToken);
pm.environment.set("userId", response.user.id);
// refreshToken comes back as an HttpOnly cookie; capture from the cookies jar
const refreshCookie = pm.cookies.get("refreshToken");
if (refreshCookie) {
    pm.environment.set("refreshToken", refreshCookie);
}
pm.test("access token captured", () => {
    pm.expect(pm.environment.get("accessToken")).to.be.a("string").and.not.empty;
});
```

**`Verification/Generate code` test script:**
```javascript
const response = pm.response.json();
pm.environment.set("verificationCode", response.code);
pm.test("code captured", () => {
    pm.expect(pm.environment.get("verificationCode")).to.match(/^[0-9]{6}$/);
});
```

### 12.5 Saved examples (minimum per request)

Every request in the collection has at least:
- **One 200/201 happy-path example** with realistic body / headers.
- **One error example** for the most-informative failure (401, 403, 409, or 400-invalid-code depending on the endpoint).

Specifically:
- `Auth/Register` → 201 + 409 (email already registered)
- `Auth/Login` → 200 + 401 (bad credentials)
- `Auth/Refresh` → 200 + 401 (no/expired refresh)
- `Users/Get current user` → 200 + 401
- `Users/Get user by id` → 200 + 404
- `Verification/Get active code` → 200 + 404 (no active code)
- `Verification/Generate code` → 200 + 409 (already verified)
- `SL/Verify player` → 200 + 403 (wrong shard) + 400 (invalid code) + 409 (avatar already linked)
- `Dev/Simulate SL verify` → 200

Saved examples both serve as documentation and unlock a mock server in later phases.

### 12.6 Task ownership of Postman updates

**Rule:** Postman sync happens **inside the task that adds the new endpoint**, not as a trailing "docs" task. This prevents Postman drift. Each task plan step includes a "Update Postman collection" substep at the end.

- **Task 1 (prefix rename)** — create `SLPA Dev` environment, create `Auth/` and `Users/` folders with all existing endpoints, wire `Auth/Login` capture script, add saved examples.
- **Task 2 (verification primitive)** — add `Verification/` folder with both endpoints, wire `Generate code` capture script, add saved examples.
- **Task 3 (SL verify real endpoint)** — add `SL/` folder with `Verify player`, header templates reference `{{slpaServiceAccountUuid}}` and `{{verificationCode}}`, add saved examples.
- **Task 4 (dev simulate helper)** — add `Dev/` folder with `Simulate SL verify`, add the single saved example.

Execution uses the Postman MCP tools: `createEnvironment`, `createCollectionFolder`, `createCollectionRequest`, `createCollectionResponse` (for saved examples), `patchCollection` as needed.

---

## 13. Testing strategy

Three levels per CONVENTIONS §Backend Testing. Every slice ships all three; the task plan's "done" check requires all three passing before the task is merge-ready.

### 13.1 Unit tests (Mockito, no Spring context)

**`VerificationCodeServiceTest`**
- `generate` happy path: fresh user, no prior codes → inserts one row, returns `(code, expiresAt)`.
- `generate` with prior active code → old row flipped `used=true`, new row inserted.
- `generate` for already-verified user → throws `AlreadyVerifiedException`.
- `generate` for non-existent user → throws `UserNotFoundException`.
- `findActive` returns `Optional.empty()` when no live code; returns `Optional.of(...)` when live.
- `findActive` ignores expired and used rows.
- `consume` happy path → row flipped `used=true`, returns userId.
- `consume` with truly non-existent code → throws `CodeNotFoundException`.
- `consume` with expired row (past `expiresAt`) → throws `CodeNotFoundException` (the filter excludes it).
- `consume` with already-used row (`used=true`) → throws `CodeNotFoundException` (the filter excludes it).
- `consume` with 2+ matches (Q5b collision) → voids **both** rows, throws `CodeCollisionException`.
- All tests use `Clock.fixed(...)` for deterministic expiry boundaries.

**`SlHeaderValidatorTest`** (parameterized where sensible)
- Wrong shard (`"Beta"`) → `InvalidSlHeadersException`.
- Missing shard header → `InvalidSlHeadersException`.
- Owner key missing / null → `InvalidSlHeadersException`.
- Owner key unparseable (not a UUID) → `InvalidSlHeadersException`.
- Owner key parses but not in trusted set → `InvalidSlHeadersException`.
- Happy path: `"Production"` + valid UUID in set → no throw.

**`SlVerificationServiceTest`**
- Happy path: calls validator, consumes code, loads user, writes SL fields, sets `verified=true` + `verifiedAt`, saves.
- Avatar already linked (pre-check fires) → throws `AvatarAlreadyLinkedException`, code **not consumed** (verifies `verificationCodeService.consume` is never called).
- User already verified → throws `AlreadyVerifiedException`.
- `verificationCodeService.consume` throws `CodeNotFoundException` → bubbles.
- `verificationCodeService.consume` throws `CodeCollisionException` → bubbles.

### 13.2 `@WebMvcTest` slice tests (mocked services)

**`VerificationControllerSliceTest`**
- `GET /active` with service returning `Optional.of(...)` → 200 + JSON body.
- `GET /active` with `Optional.empty()` → 404 ProblemDetail.
- `GET /active` unauthenticated → 401.
- `POST /generate` with service returning response → 200 + JSON body.
- `POST /generate` with service throwing `AlreadyVerifiedException` → 409.
- `POST /generate` unauthenticated → 401.

**`SlVerificationControllerSliceTest`**
- Missing `X-SecondLife-Shard` header → 403 `InvalidSlHeaders`.
- Wrong shard → 403.
- Missing owner key → 403.
- Unknown owner key → 403.
- Valid headers + valid body → 200.
- Service throws `CodeNotFoundException` → 400.
- Service throws `CodeCollisionException` → 409 with "generate a new code" detail.
- Service throws `AvatarAlreadyLinkedException` → 409.
- Service throws `AlreadyVerifiedException` → 409.
- Confirms `permitAll()` wiring: no JWT sent → still reachable (gets 403 from header check, not 401 from auth gate).

**`DevSlSimulateControllerSliceTest`**
- Happy path: minimal body (just `verificationCode`) → service called with synthesized values.
- Error path: service throws → error bubbles with same mapping as the real endpoint.
- **Bean presence test** — separate class: `DevSlSimulateBeanProfileTest` with the default (non-dev) test profile, `@Autowired(required = false) DevSlSimulateController controller` asserted to be `null`. Per the checkpoint-4 correction, this proves the `@Profile("dev")` gate without fighting prod-profile startup requirements (empty `trustedOwnerKeys`, missing `JWT_SECRET`, etc.).

### 13.3 `@SpringBootTest` integration tests

All integration tests follow the existing `AuthFlowIntegrationTest` pattern: `@SpringBootTest + @AutoConfigureMockMvc + @ActiveProfiles("dev") + @Transactional` against the shared dev Postgres container.

**`VerificationFlowIntegrationTest`**
- Register user → login → `GET /active` → 404 (no code yet).
- `POST /generate` → 200 + code + expiresAt.
- `GET /active` → 200 + same code.
- `POST /generate` again → 200 + different code; old row is `used=true`, new row is `used=false`.
- `GET /active` → 200 + newest code.

**`SlVerificationFlowIntegrationTest`**
- Register → login → generate code → `POST /sl/verify` with valid headers and body → 200 response, `User` row has `slAvatarUuid`, `slAvatarName`, etc., `verified=true`, `verifiedAt` non-null, `verification_codes` row has `used=true`.
- Second `POST /sl/verify` call with same avatar UUID (different user) → 409 `AvatarAlreadyLinkedException`.
- Third `POST /sl/verify` call with already-verified first user's avatar → 409 `AlreadyVerifiedException`.
- `POST /sl/verify` with wrong shard → 403, user row unchanged.
- `POST /sl/verify` with unknown owner key → 403, user row unchanged.
- `POST /sl/verify` with expired code (manipulate DB or `Clock`) → 400, user row unchanged.

**`DevSlSimulateIntegrationTest`** (`@ActiveProfiles("dev")`)
- Register → login → generate code → `POST /dev/sl/simulate-verify` with just `{ "verificationCode": "..." }` → 200, user row has `verified=true` and `slAvatarName="Dev Tester"`.
- Same call repeated (new user, new code) with random avatar default → succeeds independently.

**`PrefixMigrationSmokeTest`** (small, narrative, self-documenting — per checkpoint-4 note)
- Hits `/api/v1/auth/register`, `/api/v1/auth/login`, `/api/v1/users/me`, `/api/v1/users/{id}` — verifies the rename stuck at the HTTP layer.
- Optional assertion: `/api/auth/login` (old path) returns 404. Proves catch-all moved.
- Four routes, single class, ~50 lines. Stop there.

### 13.4 Coverage

`verify-coverage.sh` (or the backend project's coverage gate — to confirm during Task 1 grep) is run as part of CI. New classes maintain the existing thresholds. Exception classes don't need branch coverage and may be excluded per the project's existing patterns.

### 13.5 What not to test

- **Spring startup with `@ActiveProfiles("prod")`** — the prod profile has `trustedOwnerKeys: []` (fail-fast) and `jwt.secret: ${JWT_SECRET}` with no default. Trying to boot a prod context inside JUnit will crash before any assertion fires. Use the bean-presence test pattern (§13.2) instead.
- **The collision retry path** — there is no retry path. Q5b locked "accept collisions"; the only exercised path is void-both-and-throw.
- **The random code generator statistical distribution** — `SecureRandom.nextInt(1_000_000)` is trusted. One smoke test that calls `generate` 100 times and asserts every result matches `^[0-9]{6}$` is the most we'd want.

---

## 14. Security considerations

### 14.1 Public endpoints

Three new routes are `permitAll()`:

1. `POST /api/v1/sl/verify` — identity is asserted by SL-injected headers, not JWT. The attack surface is "someone forges `X-SecondLife-Shard: Production` and `X-SecondLife-Owner-Key: <guessed uuid>`." Mitigation: the owner key is a specific UUID configured server-side, set size is 1-2 entries, guessing is statistically impossible. The headers themselves are injected by SL's HTTP stack and tamper-proof for real script traffic; local forging is possible but irrelevant because a forger could also just write to the DB.
2. `POST /api/v1/dev/sl/simulate-verify` — only registered in dev profile. Bean doesn't exist in prod. Security config permit matcher is profile-gated. Triple safeguard.
3. `GET /api/v1/users/{id}` — existing, unchanged by this sub-spec, public profile read.

### 14.2 Information leakage

- Error messages never include the verification code in the response body. Log lines include the code at WARN level (collision case) but not in the user-facing `ProblemDetail.detail`.
- `POST /sl/verify` response body includes `userId` and `slAvatarName` — intentional, needed by the LSL script for confirmation display. Does not include `email`, `passwordHash`, or any other sensitive user field.
- Failed header validation returns generic "request not trusted" messages — no "close but not quite" hints that would help a forger narrow down valid owner keys.

### 14.3 Transaction boundaries

- `VerificationCodeService.generate` — `@Transactional` — void-old + insert-new is atomic.
- `VerificationCodeService.consume` — `@Transactional` — collision-void + throw is atomic.
- `SlVerificationService.verify` — `@Transactional` — pre-check + consume + load-user + write-fields + save is one transaction. If the user save fails (e.g., unique constraint race on `sl_avatar_uuid`), the transaction rolls back, the consumed code is un-consumed, and the caller can regenerate and retry.

The unique index on `users.sl_avatar_uuid` from V1 is the final safety net. The pre-check is an optimization that makes the common case friendlier.

### 14.4 Rate limiting

Explicitly **not** added in this sub-spec. Q5c locked "defer to Epic 07." `POST /generate` is authenticated, the user has already burned CAPTCHA/bot-gating at register time (Epic 01), and each call just flips one row. Sub-spec 1 accepts the unthrottled state.

---

## 15. Error handling and exception mapping

Full table. Owned by the two package-scoped exception handlers (`VerificationExceptionHandler`, `SlExceptionHandler`) plus any existing `GlobalExceptionHandler` in `common/`.

| Exception | Package | HTTP | `title` | `type` URI | Logged at |
|---|---|---|---|---|---|
| `AlreadyVerifiedException` | verification | 409 | "Account already verified" | `https://slpa.example/problems/verification/already-verified` | INFO |
| `CodeNotFoundException` | verification | 400 | "Verification failed" | `https://slpa.example/problems/verification/code-not-found` | INFO |
| `CodeCollisionException` | verification | **409** | "Verification failed" | `https://slpa.example/problems/verification/code-collision` | **WARN** |
| `UserNotFoundException` | user (existing) | 404 | unchanged | existing URI, unchanged | unchanged |
| `InvalidSlHeadersException` | sl | 403 | "Invalid SL headers" | `https://slpa.example/problems/sl/invalid-headers` | WARN |
| `AvatarAlreadyLinkedException` | sl | 409 | "Avatar already linked" | `https://slpa.example/problems/sl/avatar-already-linked` | WARN |
| `DataIntegrityViolationException` (rethrown as `AvatarAlreadyLinkedException` inside `SlExceptionHandler` — see §15.1) | sl | 409 | "Avatar already linked" | same as above | WARN |

`ProblemDetail.type` follows the existing Epic 01 convention: `https://slpa.example/problems/<slice>/<kind>`. That convention is established in `common/exception/GlobalExceptionHandler.java` and `auth/exception/AuthExceptionHandler.java` (e.g., `https://slpa.example/problems/auth/invalid-credentials`, `https://slpa.example/problems/auth/token-missing`). Do not switch to `urn:` or any other shape.

### 15.1 `DataIntegrityViolationException` handling — handler, not service

`SlVerificationService` does not catch `DataIntegrityViolationException`. It lets the exception bubble from `userRepository.save(user)` so the service layer stays focused on happy-path orchestration. The catch + rethrow lives in `SlExceptionHandler`:

```java
@ExceptionHandler(DataIntegrityViolationException.class)
public ProblemDetail handleDataIntegrityViolation(DataIntegrityViolationException e,
                                                  HttpServletRequest req) {
    // The only constraint in sl/ that this handler cares about is the
    // users.sl_avatar_uuid unique index. Anything else is a genuine programmer
    // error or a truly unexpected constraint — let it fall through to the
    // GlobalExceptionHandler's catch-all handleUnexpected().
    String constraintName = extractConstraintName(e);  // parses root cause message / SQLState
    if (isAvatarUuidUniqueViolation(constraintName)) {
        log.warn("SL verify race: sl_avatar_uuid unique constraint fired. Rethrowing as AvatarAlreadyLinkedException.");
        throw new AvatarAlreadyLinkedException(/* avatarUuid not easily recoverable here */);
    }
    // Unknown constraint — rethrow so the global handler produces a 500.
    throw e;
}
```

Implementation notes:
- `extractConstraintName` parses the root cause (a `org.hibernate.exception.ConstraintViolationException` wrapping a `PSQLException`) — standard pattern, one utility method in `sl/`.
- `isAvatarUuidUniqueViolation` does a substring match on the Postgres constraint name (e.g., `users_sl_avatar_uuid_key` — to confirm during Task 3 by inspecting V1 migration output).
- If the check fails to recognize the constraint, the exception bubbles to `GlobalExceptionHandler.handleUnexpected` which returns a 500 — intentional: an unknown constraint violation is a programming bug, not a user-facing 4xx.
- This keeps `SlVerificationService` tests clean (they don't need to mock `DataIntegrityViolationException`) while still producing the right response for the race case described in §8.

---

## 16. Task breakdown (inside sub-spec 1)

**Task 1 — API prefix migration + Postman foundation** (~1 hour)

1. Grep-sweep backend for `"/api/"` literal — rename to `"/api/v1/"` in `@RequestMapping`, `SecurityConfig` matchers, refresh-cookie `Path`, test client paths.
2. Grep-sweep frontend for `/api/` literal — rename in `lib/api.ts`, MSW handlers (including `Path=/api/auth` in `Set-Cookie`), `api.test.ts`, `api.401-interceptor.test.tsx`.
3. Run `./mvnw test` → green.
4. Run `cd frontend && npm test` → green.
5. Manual smoke: dev backend up, register / login from the Next.js dev server, confirm nothing broke.
6. Postman: `createEnvironment` for `SLPA Dev` with the 6 variables per §12.2.
7. Postman: `createCollectionFolder` for `Auth/`, then `createCollectionRequest` for each of Register / Login / Refresh / Logout / Logout all, with the Login test script per §12.4 and saved 200 + error examples per §12.5.
8. Postman: `createCollectionFolder` for `Users/`, then requests for Create / Get current / Get by id.
9. Manual Postman sanity: fire `Auth/Login`, confirm `accessToken` populates, fire `Users/Get current user` with the captured token → 200.
10. Commit: `refactor(api): migrate /api to /api/v1 across backend, frontend, tests`.

**Task 2 — `verification/` slice + Get active + Generate** (~3-4 hours)

1. `VerificationCode` entity + `VerificationCodeType` enum + `VerificationCodeRepository`.
2. `VerificationCodeService` with `generate`, `findActive`, `consume`, private `voidActive`. Inject `Clock` for test determinism.
3. `VerificationController` with `GET /active` and `POST /generate`.
4. DTOs (`ActiveCodeResponse`, `GenerateCodeResponse`).
5. Exception classes: `AlreadyVerifiedException`, `CodeNotFoundException`, `CodeCollisionException`. Per §7, `CodeNotFoundException` covers the not-found / expired / used triad; no separate classes for those modes.
6. `VerificationExceptionHandler` `@RestControllerAdvice` package-scoped.
7. Unit tests per §13.1.
8. Slice tests per §13.2.
9. Integration test `VerificationFlowIntegrationTest` per §13.3.
10. Postman: `createCollectionFolder` for `Verification/`, add Get active + Generate code, Generate test script per §12.4, saved examples per §12.5.
11. Commit: `feat(verification): add verification code generation and active-code query`.

**Task 3 — `sl/` slice + real `/sl/verify` endpoint** (~2-3 hours)

1. `SlConfigProperties` record + `@EnableConfigurationProperties` wiring.
2. `application.yml` + `application-dev.yml` + `application-prod.yml` (if missing) entries per §9.2.
3. `SlStartupValidator` with `@EventListener(ApplicationReadyEvent.class)`.
4. `SlHeaderValidator`.
5. `SlVerificationService` orchestrator per §8.
6. `SlVerificationController` + `SlVerifyRequest` / `SlVerifyResponse` DTOs.
7. `SecurityConfig` — permit `POST /api/v1/sl/verify`.
8. `InvalidSlHeadersException`, `AvatarAlreadyLinkedException` + `SlExceptionHandler`.
9. `UserRepository.findBySlAvatarUuid` — one-liner added if missing.
10. Unit tests per §13.1.
11. Slice tests per §13.2.
12. Integration test `SlVerificationFlowIntegrationTest` per §13.3.
13. Postman: `createCollectionFolder` for `SL/`, add `Verify player` with header templates + body referencing `{{verificationCode}}`, saved examples per §12.5.
14. Commit: `feat(sl): add SL verification endpoint with header-gated security`.

**Task 4 — `DevSlSimulateController` + profile-gated dev helper** (~1.5 hours)

1. `DevSimulateRequest` DTO with `toSlVerifyRequest()` helper and defaults per §10.1.
2. `DevSlSimulateController` with `@Profile("dev")`.
3. `SecurityConfig` — permit `/api/v1/dev/**` gated by dev profile (profile-aware bean or runtime check).
4. Slice test + bean-presence test per §13.2.
5. Integration test `DevSlSimulateIntegrationTest` per §13.3.
6. Postman: `createCollectionFolder` for `Dev/`, add `Simulate SL verify`, saved example.
7. Commit: `feat(dev): add dev-profile SL verification simulate helper`.

Order is strict: Task 1 before all others. Task 2 before Task 3 (SL verify consumes verification codes). Task 3 before Task 4 (dev helper delegates to `SlVerificationService`).

---

## 17. Done definition

Sub-spec 1 is "done" when all of the following are true on the `task/02-sub-1-verification-backend` branch off `dev`:

- [ ] All 4 tasks committed in order per §16.
- [ ] `./mvnw test` passes (all existing + all new tests green).
- [ ] `cd frontend && npm test` passes (all existing frontend tests green after the prefix rename).
- [ ] `./mvnw verify` or backend coverage gate passes at the existing threshold.
- [ ] `frontend/scripts/verify-*.sh` chain still green after the rename-only frontend changes.
- [ ] Postman `SLPA` collection matches §12.3 folder tree. Running the requests in order (Login → Generate → SL/Verify player, or Login → Generate → Dev/Simulate) works end-to-end against a running dev backend.
- [ ] Manual smoke: dev backend on localhost:8080 + dev frontend on localhost:3000, register a user from the browser, generate a code via Postman, hit `Dev/Simulate SL verify` in Postman, confirm the `users` row in Postgres has `verified=true`, `sl_avatar_uuid` populated, `verified_at` set.
- [ ] `README.md` updated per the memory rule: mention the `/api/v1` prefix, mention the dev SL simulate helper, mention the Postman collection location.
- [ ] `docs/implementation/FOOTGUNS.md` updated with any new footguns discovered during execution. Expected candidates:
  - Refresh cookie `Path` must be renamed in lock-step with the endpoint rename.
  - `@Profile("dev")` alone isn't enough — `SecurityConfig` permit matcher must also be profile-gated.
  - `consume` pre-check then consume has a race that relies on the DB unique index as the safety net.
- [ ] PR into `dev` branch (per memory rule). Not into `main`.
- [ ] No AI/tool attribution in commits or PR body (per memory rule).

---

## 18. Open questions deferred to sub-spec 2

None of these need answering inside sub-spec 1, but they're the pending shape for the next brainstorming session.

1. Image storage backend for profile picture upload (local filesystem / S3-compatible / R2). Task 02-03 says local filesystem is fine; we'll lock this cleanly in sub-spec 2.
2. Image processing library choice — Thumbnailator vs imgscalr. Task 02-03 floats both; pick one in sub-spec 2 brainstorming.
3. Dashboard layout composition — which stitch reference folder, what components decompose out, whether the `VerificationCodeDisplay` + `CountdownTimer` primitives live in `components/ui/` or `components/user/`. This is where the browser companion gets heavy use.
4. Public profile shell (Task 02-05) — empty states, "new seller" badge threshold, star rating component — all sub-spec 2 concerns.
5. Tab primitive (`Tabs` in `components/ui/`) — builds here for dashboard, reused by public profile and admin. Specify in sub-spec 2.

---

## 19. Appendix — brainstorm decisions log

Locked via Q&A during the 2026-04-13 brainstorming session:

- **Q1 epic shape** → B: two sub-specs, verification backend first (this spec), profile+dashboard+public profile second.
- **Q2 API prefix** → B: migrate `/api/*` → `/api/v1/*` as Task 1 throw-in, rename existing controllers now while only two exist.
- **Q3 Postman workspace** → user-created SLPA workspace + empty SLPA collection, folders by path segment, one dev environment with chained variables, saved examples, endpoints added with each task.
- **Q4 SL header validation** → A+C: dev profile has placeholder `trustedOwnerKeys`, real `/sl/verify` is Postman-callable, dev-profile-gated `/api/v1/dev/sl/simulate-verify` helper for browser E2E.
- **Q5a storage** → plaintext in `verification_codes.code`.
- **Q5b collisions** → accept (no DB-level retry), but on `consume` collision: void **both** rows, return user-friendly 409 "Verification failed, please generate a new code", log at WARN with both user IDs + colliding code. **Not** a 500.
- **Q5c rate limit on generate** → defer to Epic 07.
- **Q5d regenerate-while-active** → confirmed as task doc says: `POST /generate` always creates fresh, voids prior active codes. `GET /active` is the non-destructive read path (added during checkpoint-1 pushback).
- **Checkpoint 1 pushback** → add `GET /api/v1/verification/active`, ensure `CodeCollisionException` is 409 with WARN log (not 400, not 500).
- **Checkpoint 2 pushback** → `boolean used` primitive, not `Boolean`.
- **Checkpoint 4 pushback** → dev-profile absence test uses `@Autowired(required=false)` in default test profile, not `@ActiveProfiles("prod")` (which would crash on fail-fast startup). Collision test explicit outcome logged. `PrefixMigrationSmokeTest` stays small.
- **Package shape** → B: two slices `verification/` + `sl/`, strict dependency direction `sl/ → verification/` and `sl/ → user/`, never the other way.
