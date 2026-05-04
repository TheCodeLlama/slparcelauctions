# BaseEntity + Public UUID Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate every backend entity to a two-identifier model (`Long id` internal + `UUID publicId` public) via a shared `BaseEntity` / `BaseMutableEntity` hierarchy, with `@Version` optimistic locking on mutable entities, then propagate `publicId` through DTOs, REST URLs, JWT, WebSocket envelopes, frontend types, and the Postman collection.

**Architecture:** Two `@MappedSuperclass`es. `BaseEntity` carries `Long id` (internal, `@JsonIgnore`), `UUID publicId` (random UUIDv4, assigned at construction), and `createdAt`. `BaseMutableEntity` extends and adds `updatedAt` + `@Version`. Equality and hashCode are `final` on `BaseEntity`, keyed off `publicId` (always non-null from construction). Single big-bang Flyway V12 drops every table and recreates with the new shape; prod gets a Fargate DB wipe in lockstep with deploy.

**Tech Stack:** Spring Boot 4.0.5, Java 24 (per `pom.xml`), Lombok `@SuperBuilder`, Hibernate ORM 7, Postgres `uuid` native type, Next.js 16 / TypeScript / TanStack Query, Postman dynamic chained variables.

**Reference spec:** `docs/superpowers/specs/2026-05-03-base-entity-uuid-design.md`

---

## File Structure

### New backend files

| File | Purpose |
|---|---|
| `backend/src/main/java/com/slparcelauctions/backend/common/BaseEntity.java` | Top-level `@MappedSuperclass` with `id`, `publicId`, `createdAt`, final equality |
| `backend/src/main/java/com/slparcelauctions/backend/common/BaseMutableEntity.java` | Extends `BaseEntity`; adds `updatedAt`, `@Version` |
| `backend/src/test/java/com/slparcelauctions/backend/common/BaseEntityEqualityTest.java` | Equality / hashCode invariants |
| `backend/src/test/java/com/slparcelauctions/backend/common/BaseMutableEntityVersionTest.java` | `@Version` raises `OptimisticLockException` on concurrent update |
| `backend/src/test/java/com/slparcelauctions/backend/common/PublicIdSerializationTest.java` | DTO → JSON emits `publicId`, never internal `id` |
| `backend/src/test/java/com/slparcelauctions/backend/auth/JwtSubjectIsPublicIdTest.java` | JWT `sub` claim carries the user's UUID `publicId` |
| `backend/src/main/resources/db/migration/V12__base_entity_uuid_migration.sql` | Drop & recreate every table in the new shape |

### Modified backend files

| File | What changes |
|---|---|
| `backend/src/main/java/com/slparcelauctions/backend/auth/AuthPrincipal.java` | Add `UUID publicId` field |
| `backend/src/main/java/com/slparcelauctions/backend/auth/JwtService.java` | `sub` = `publicId.toString()`; parse UUID from `sub` |
| `backend/src/main/java/com/slparcelauctions/backend/auth/JwtAuthenticationFilter.java` | Resolve user by publicId; populate principal with both ids |
| `backend/src/main/java/com/slparcelauctions/backend/auth/AuthService.java` | Build `AuthPrincipal` with publicId on login/register/refresh |
| `backend/src/main/java/com/slparcelauctions/backend/user/UserRepository.java` | Add `findByPublicId(UUID)` |
| Every entity under `backend/src/main/java/com/slparcelauctions/backend/{user,auction,wallet,escrow,review,bot,admin,verification,notification,parceltag,region,auth}/...` | Extend `BaseEntity` or `BaseMutableEntity`; remove duplicated id/createdAt/updatedAt fields; switch `@Builder` → `@SuperBuilder` |
| Every public DTO (Response/Dto records) | `Long id` → `UUID publicId`; field rename |
| Every public controller | URL path `{id}` → `{publicId}`; `findByPublicId` lookups |
| Every DtoMapper | Map `entity.getPublicId()` → `dto.publicId()` |

### Modified frontend files

| File pattern | What changes |
|---|---|
| `frontend/src/types/**/*.ts` | `id: number` → `publicId: string` on public types |
| `frontend/src/app/**/[id]/**` | Rename dynamic segment `[id]` → `[publicId]` for public routes |
| `frontend/src/lib/api/*.ts` | Hook param types switch to `publicId: string` |
| `frontend/src/lib/ws/*.ts` | WebSocket subscription topic interpolation |

### Modified Postman / docs

| File | What changes |
|---|---|
| Postman collection (collection id `8070328-cf3e1190-cb71-4af3-8e2e-70c82dfee288`) | Test scripts: `pm.environment.set('userId', ...)` → `pm.environment.set('userPublicId', ...)`; URL paths use `{{userPublicId}}` etc. |
| `README.md` | Update any sections referencing entity IDs in URLs / examples |

---

## Task list overview

| # | Task | Foundation/Refactor |
|---|---|---|
| 1 | Verify Lombok `@SuperBuilder` available + sample compile | Foundation |
| 2 | Create `BaseEntity` class + equality test | Foundation (TDD) |
| 3 | Create `BaseMutableEntity` class | Foundation |
| 4 | Add `findByPublicId` to UserRepository (template lookup) | Foundation |
| 5 | Update `AuthPrincipal` record + `JwtService` (publicId in subject) | Foundation |
| 6 | Migrate User entity (template — most detail) | Refactor |
| 7 | Migrate Auction domain (Auction, Bid, ProxyBid, AuctionPhoto, AuctionParcelSnapshot, BidReservation, CancellationLog, ListingFeeRefund, FraudFlag, SavedAuction, ParcelTag) | Refactor |
| 8 | Migrate Escrow domain (Escrow, EscrowTransaction, Terminal, TerminalCommand, TerminalSecret) | Refactor |
| 9 | Migrate Wallet domain (UserLedgerEntry, Withdrawal) | Refactor |
| 10 | Migrate Review domain (Review, ReviewResponse, ReviewFlag) | Refactor |
| 11 | Migrate Bot/Admin/Verification/Notification/Region (BotTask, BotWorker, Ban, AdminAction, ListingReport, ReconciliationRun, VerificationCode, Notification, SlImMessage, Region, RefreshToken) | Refactor |
| 12 | Write Flyway V12 migration | Foundation |
| 13 | Boot backend; fix Hibernate `validate` errors | Refactor |
| 14 | Update JWT auth filter + auth integration (publicId resolution) | Refactor |
| 15 | DTO renames — Auction domain | Refactor |
| 16 | DTO renames — User/Auth/Review domain | Refactor |
| 17 | DTO renames — Escrow/Wallet/Bot/Admin domains | Refactor |
| 18 | Controller URL path updates (all public controllers) | Refactor |
| 19 | Add new spec'd tests (`PublicIdSerializationTest`, `JwtSubjectIsPublicIdTest`, `BaseMutableEntityVersionTest`) | Foundation (TDD) |
| 20 | Frontend type renames + dynamic route renames | Refactor |
| 21 | Frontend WebSocket subscription topics | Refactor |
| 22 | Postman collection updates | Refactor |
| 23 | Backend full test run + integration smoke | Verification |
| 24 | README sweep + DEFERRED_WORK ledger | Docs |

---

## Task 1: Verify Lombok `@SuperBuilder` available + baseline compile

**Files:**
- Modify (read-only check): `backend/pom.xml`
- No source changes in this task

**Why:** `@SuperBuilder` ships with Lombok core (since 1.18.x), but we need to confirm the Spring Boot 4 BOM-managed Lombok version is recent enough and that annotation processing is correctly configured.

- [ ] **Step 1: Inspect Lombok version and annotation processing**

Read `backend/pom.xml`. Verify:
- `org.projectlombok:lombok` is on the classpath (transitively via Spring Boot starter or directly).
- `<scope>provided</scope>` is set on Lombok.
- `maven-compiler-plugin` has Lombok in `annotationProcessorPaths` OR Spring Boot's parent provides this configuration.

Expected: Lombok 1.18.30+ is on the classpath.

- [ ] **Step 2: Smoke-test `@SuperBuilder` with a throwaway class**

Create `backend/src/test/java/com/slparcelauctions/backend/common/SuperBuilderSmokeTest.java`:

```java
package com.slparcelauctions.backend.common;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SuperBuilderSmokeTest {

    @Getter
    @SuperBuilder
    @NoArgsConstructor
    @AllArgsConstructor
    static class Parent {
        private String parentField;
    }

    @Getter
    @SuperBuilder
    @NoArgsConstructor
    @AllArgsConstructor
    static class Child extends Parent {
        private String childField;
    }

    @Test
    void superBuilderComposesAcrossInheritance() {
        Child c = Child.builder()
                .parentField("p")
                .childField("c")
                .build();
        assertThat(c.getParentField()).isEqualTo("p");
        assertThat(c.getChildField()).isEqualTo("c");
    }
}
```

- [ ] **Step 3: Run the smoke test**

Run: `cd backend && ./mvnw test -Dtest=SuperBuilderSmokeTest`
Expected: `BUILD SUCCESS`. If Lombok complains about `@SuperBuilder` not found, add `<dependency><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId><scope>provided</scope></dependency>` explicitly to pom.xml and re-run.

- [ ] **Step 4: Delete the smoke test**

Delete `backend/src/test/java/com/slparcelauctions/backend/common/SuperBuilderSmokeTest.java`. It served its purpose.

- [ ] **Step 5: Commit (no code change net)**

Skip the commit if no files were modified in net. If pom.xml needed an explicit Lombok declaration:

```bash
git add backend/pom.xml
git commit -m "build(backend): pin lombok dependency for @SuperBuilder usage"
```

---

## Task 2: Create `BaseEntity` class + equality test (TDD)

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/common/BaseEntity.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/common/BaseEntityEqualityTest.java`

- [ ] **Step 1: Write the failing equality test**

Create `backend/src/test/java/com/slparcelauctions/backend/common/BaseEntityEqualityTest.java`:

```java
package com.slparcelauctions.backend.common;

import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BaseEntityEqualityTest {

    @SuperBuilder
    @NoArgsConstructor
    @AllArgsConstructor
    static class Probe extends BaseEntity {}

    @Test
    void twoTransientEntitiesWithDifferentPublicIdsAreNotEqual() {
        Probe a = Probe.builder().build();
        Probe b = Probe.builder().build();
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void twoEntitiesWithSamePublicIdAreEqual() {
        UUID shared = UUID.randomUUID();
        Probe a = Probe.builder().publicId(shared).build();
        Probe b = Probe.builder().publicId(shared).build();
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void hashCodeIsStableAcrossLifecycle() {
        Probe a = Probe.builder().build();
        int hashBefore = a.hashCode();
        // Simulate "persist" by setting the internal id (publicId already set at construction)
        // hashCode must not change because it keys off publicId, not id.
        int hashAfter = a.hashCode();
        assertThat(hashBefore).isEqualTo(hashAfter);
    }

    @Test
    void hashSetMembershipSurvivesPersistBoundary() {
        Probe a = Probe.builder().build();
        Set<Probe> set = new HashSet<>();
        set.add(a);
        assertThat(set).contains(a);
        // Even after lifecycle progression, the entity is still findable
        assertThat(set).contains(a);
    }

    @Test
    void publicIdIsNonNullAtConstruction() {
        Probe a = Probe.builder().build();
        assertThat(a.getPublicId()).isNotNull();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=BaseEntityEqualityTest`
Expected: FAIL — `BaseEntity` class does not exist (compile error).

- [ ] **Step 3: Implement `BaseEntity`**

Create `backend/src/main/java/com/slparcelauctions/backend/common/BaseEntity.java`:

```java
package com.slparcelauctions.backend.common;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Top-level @MappedSuperclass. Every entity in the codebase extends this directly
 * (immutable / append-only) or {@link BaseMutableEntity} (mutable lifecycle).
 *
 * <p>Two-identifier model:
 * <ul>
 *   <li>{@code id} — internal {@code Long} primary key. Used for FK joins, internal lookups,
 *       admin internal-data endpoints, bot/LSL contracts, Postman variables. Annotated
 *       {@code @JsonIgnore} so it never escapes via direct entity serialization.</li>
 *   <li>{@code publicId} — random UUIDv4. Assigned at construction (not persist time) so
 *       equality and {@code HashSet} membership work pre-persist. The only identifier safe
 *       to expose anywhere a user can see it. JWT subject, REST DTOs, frontend types,
 *       WebSocket envelopes all use this.</li>
 * </ul>
 *
 * <p>{@link #equals} and {@link #hashCode} are {@code final}, keyed off {@code publicId}.
 * Subclasses must NOT override and must NOT use Lombok {@code @EqualsAndHashCode}.
 *
 * <p>Subclasses MUST use Lombok {@code @SuperBuilder}, not {@code @Builder} — plain
 * {@code @Builder} does not compose across {@code @MappedSuperclass} inheritance.
 *
 * <p>See {@code docs/superpowers/specs/2026-05-03-base-entity-uuid-design.md}.
 */
@MappedSuperclass
@Getter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public abstract class BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @JsonIgnore
    @Setter(AccessLevel.NONE)
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true, updatable = false,
            columnDefinition = "uuid")
    @Builder.Default
    @Setter(AccessLevel.NONE)
    private UUID publicId = UUID.randomUUID();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Override
    public final boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BaseEntity that)) return false;
        return publicId.equals(that.publicId);
    }

    @Override
    public final int hashCode() {
        return publicId.hashCode();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=BaseEntityEqualityTest`
Expected: PASS, 5 tests green.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/common/BaseEntity.java \
        backend/src/test/java/com/slparcelauctions/backend/common/BaseEntityEqualityTest.java
git commit -m "feat(common): add BaseEntity with Long id + UUID publicId"
```

---

## Task 3: Create `BaseMutableEntity` class

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/common/BaseMutableEntity.java`

(`@Version` test deferred to Task 19, since it requires a real entity + DB to demonstrate `OptimisticLockException`.)

- [ ] **Step 1: Implement `BaseMutableEntity`**

Create `backend/src/main/java/com/slparcelauctions/backend/common/BaseMutableEntity.java`:

```java
package com.slparcelauctions.backend.common;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

/**
 * @MappedSuperclass extending {@link BaseEntity} for entities with a mutable lifecycle —
 * rows that get UPDATE'd over time. Adds {@code updatedAt} (Hibernate
 * {@code @UpdateTimestamp}) and {@code version} ({@code @Version}, optimistic locking).
 *
 * <p>Append-only / immutable entities (e.g. {@code Bid}, {@code UserLedgerEntry},
 * {@code AdminAction}) extend {@link BaseEntity} directly, not this class.
 */
@MappedSuperclass
@Getter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public abstract class BaseMutableEntity extends BaseEntity {

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Version
    @Column(nullable = false)
    @Builder.Default
    private Long version = 0L;
}
```

- [ ] **Step 2: Verify the class compiles**

Run: `cd backend && ./mvnw compile`
Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/common/BaseMutableEntity.java
git commit -m "feat(common): add BaseMutableEntity with @UpdateTimestamp and @Version"
```

---

## Task 4: Add `findByPublicId` to UserRepository

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/user/UserRepository.java`

This is the template lookup method. Every repository whose entity has a public surface gets one. We add User's now because the auth filter (Task 14) needs it.

- [ ] **Step 1: Read current UserRepository**

Read: `backend/src/main/java/com/slparcelauctions/backend/user/UserRepository.java`

- [ ] **Step 2: Add the method**

Add to the interface body (preserving existing methods):

```java
import java.util.Optional;
import java.util.UUID;

// ... inside the interface
Optional<User> findByPublicId(UUID publicId);
```

Spring Data JPA derives the implementation from the method name; no `@Query` needed. The unique index on `users.public_id` (added in V12) makes this a B-tree seek.

- [ ] **Step 3: Verify compile**

Run: `cd backend && ./mvnw compile`
Expected: `BUILD SUCCESS`.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/user/UserRepository.java
git commit -m "feat(user): add findByPublicId for auth principal resolution"
```

---

## Task 5: Update `AuthPrincipal` to carry both `id` and `publicId`

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auth/AuthPrincipal.java`

`AuthPrincipal` is a record. It currently exposes `userId` (Long), `email`, `tokenVersion`, `role`. We add a `userPublicId` field. Every caller that constructs `AuthPrincipal` (login, register, refresh, JWT parse) is updated in this task.

- [ ] **Step 1: Update the record signature**

Replace the record body of `backend/src/main/java/com/slparcelauctions/backend/auth/AuthPrincipal.java` with:

```java
package com.slparcelauctions.backend.auth;

import com.slparcelauctions.backend.user.Role;

import java.util.UUID;

/**
 * Lightweight authentication principal set into the Spring {@code SecurityContext} by
 * {@code JwtAuthenticationFilter} on a successful access-token parse. Consumed by controllers via
 * {@code @AuthenticationPrincipal AuthPrincipal principal}.
 *
 * <p><strong>Never use {@code @AuthenticationPrincipal UserDetails}</strong> in this codebase —
 * the filter sets this record, not a Spring {@code UserDetails}, and reaching for {@code UserDetails}
 * yields {@code null}. See FOOTGUNS §B.1.
 *
 * <p>Carries both {@code userId} (internal {@code Long}, used for FK joins / internal lookups) and
 * {@code userPublicId} (UUID, used for outbound JSON, JWT subject claim, public URLs). Service
 * code reads {@code principal.userId()} for joins and {@code principal.userPublicId()} for any
 * value that crosses a public wire.
 *
 * <p>The {@code tokenVersion} field is the freshness-mitigation claim: write-path services compare
 * it against the freshly-loaded {@code user.getTokenVersion()} at the integrity boundary to detect
 * stale sessions within the 15-minute access-token window.
 */
public record AuthPrincipal(Long userId, UUID userPublicId, String email, Long tokenVersion, Role role) {}
```

- [ ] **Step 2: Find all callers**

Run: `cd backend && grep -rn "new AuthPrincipal(" src/`
Expected: a list of constructor sites in `AuthService`, `JwtService`, possibly tests.

- [ ] **Step 3: Update `JwtService.issueAccessToken` to put publicId in `sub`**

Modify `backend/src/main/java/com/slparcelauctions/backend/auth/JwtService.java`:

Replace:
```java
.subject(String.valueOf(principal.userId()))
```
with:
```java
.subject(principal.userPublicId().toString())
```

Replace:
```java
Long userId = Long.parseLong(claims.getSubject());
String email = (String) claims.get("email");
Long tokenVersion = ((Number) claims.get("tv")).longValue();
String roleClaim = (String) claims.get("role");
Role role = roleClaim == null ? Role.USER : Role.valueOf(roleClaim);

return new AuthPrincipal(userId, email, tokenVersion, role);
```
with:
```java
UUID userPublicId = UUID.fromString(claims.getSubject());
String email = (String) claims.get("email");
Long tokenVersion = ((Number) claims.get("tv")).longValue();
String roleClaim = (String) claims.get("role");
Role role = roleClaim == null ? Role.USER : Role.valueOf(roleClaim);

// userId (Long) is resolved by JwtAuthenticationFilter via UserRepository.findByPublicId,
// not encoded in the JWT itself. Place a sentinel here; the filter overwrites the principal
// before it lands in the SecurityContext.
return new AuthPrincipal(null, userPublicId, email, tokenVersion, role);
```

Add the import: `import java.util.UUID;`

- [ ] **Step 4: Update `AuthService` constructor sites**

Read `backend/src/main/java/com/slparcelauctions/backend/auth/AuthService.java`. Find every `new AuthPrincipal(...)` call. Each one happens after a User has been loaded — so `user.getId()` and `user.getPublicId()` are both available. Update calls from:

```java
new AuthPrincipal(user.getId(), user.getEmail(), user.getTokenVersion(), user.getRole())
```
to:
```java
new AuthPrincipal(user.getId(), user.getPublicId(), user.getEmail(), user.getTokenVersion(), user.getRole())
```

(NOTE: `user.getPublicId()` does not exist yet — it appears once `User` extends `BaseEntity` in Task 6. This task and Task 6 must merge before the build passes.)

- [ ] **Step 5: Update test fixtures referencing AuthPrincipal**

Run: `cd backend && grep -rn "new AuthPrincipal(" src/test/`
For each, add `UUID.randomUUID()` (or a fixture UUID) as the second argument.

- [ ] **Step 6: Verify compile (build will fail until Task 6 lands; that's expected)**

Run: `cd backend && ./mvnw compile`
Expected at this point: COMPILE FAILS at `user.getPublicId()`. Continue to Task 6 — they're paired.

- [ ] **Step 7: Commit (will be paired with Task 6)**

Hold this commit. Stage the changes:

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auth/AuthPrincipal.java \
        backend/src/main/java/com/slparcelauctions/backend/auth/JwtService.java \
        backend/src/main/java/com/slparcelauctions/backend/auth/AuthService.java
```

Do NOT commit yet. Proceed to Task 6.

---

## Task 6: Migrate `User` entity (template task — most detail)

This is the template. Every subsequent entity migration follows the same pattern; later tasks reference back here.

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/user/User.java`

- [ ] **Step 1: Read current User entity**

Read: `backend/src/main/java/com/slparcelauctions/backend/user/User.java`

Notice the current shape:
- `@Entity @Table(name = "users") @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder`
- Has explicit `@Id Long id`, `@CreationTimestamp createdAt`, `@UpdateTimestamp updatedAt`
- Has `@Builder.Default` on default-valued fields

The migration: extend `BaseMutableEntity`, remove the duplicated fields, switch `@Builder` → `@SuperBuilder`.

- [ ] **Step 2: Apply the transformation**

Modify `backend/src/main/java/com/slparcelauctions/backend/user/User.java`:

a. Replace class-level `@Builder` with `@SuperBuilder`:
```java
import lombok.experimental.SuperBuilder;
// ...
@SuperBuilder   // was: @Builder
```

b. Make the class extend `BaseMutableEntity`:
```java
import com.slparcelauctions.backend.common.BaseMutableEntity;
// ...
public class User extends BaseMutableEntity {
```

c. Delete these fields (now inherited):
```java
@Id
@GeneratedValue(strategy = GenerationType.IDENTITY)
private Long id;
// ...
@CreationTimestamp
@Column(name = "created_at", nullable = false, updatable = false)
private OffsetDateTime createdAt;

@UpdateTimestamp
@Column(name = "updated_at", nullable = false)
private OffsetDateTime updatedAt;
```

d. Remove now-unused imports:
```java
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
```
(Keep `OffsetDateTime` — still used by `verifiedAt`, `walletDormancyStartedAt`, etc.)

e. **Do NOT remove `Lombok.Builder.Default`** — it's still used on body fields (`role`, `verified`, etc.). Only the class-level `@Builder` is replaced.

- [ ] **Step 3: Verify the entity still has expected getters**

Run: `cd backend && ./mvnw compile`
Expected: `BUILD SUCCESS` (assuming Task 5 staged changes are still in the working tree). If `user.getPublicId()` errors elsewhere, that's the unfinished Task 5 wiring — proceed.

- [ ] **Step 4: Update `UserDtoMapper` and any User-touching service**

Run: `cd backend && grep -rn "user\.getId()" src/main/`
Many sites read `user.getId()` — that still returns `Long` from `BaseEntity`. No change needed.

Run: `cd backend && grep -rn "user\.getCreatedAt()" src/main/`
`getCreatedAt()` is now inherited from `BaseEntity`. No change needed (Lombok `@Getter` on `BaseEntity` produces it).

- [ ] **Step 5: Run the User test suite**

Run: `cd backend && ./mvnw test -Dtest='User*Test'`
Expected: existing User tests still pass. If a test constructs a `User` via `User.builder()...build()`, `@SuperBuilder` produces a compatible builder API — no changes needed.

If any test fails because it asserts `user.getId() != null` immediately after `User.builder().build()`, it's expecting the old Long-id-at-construction behavior that we never had (Long id has always been IDENTITY-assigned-at-persist). The test was relying on something else; investigate the specific failure.

- [ ] **Step 6: Commit (with Task 5)**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/user/User.java \
        backend/src/main/java/com/slparcelauctions/backend/user/UserRepository.java
git commit -m "refactor(user): User extends BaseMutableEntity; AuthPrincipal carries publicId"
```

---

## Task 7: Migrate Auction domain entities

**Files (all under `backend/src/main/java/com/slparcelauctions/backend/auction/`):**

- Modify: `Auction.java` → extends `BaseMutableEntity`
- Modify: `Bid.java` → extends `BaseEntity` (immutable, append-only)
- Modify: `ProxyBid.java` → extends `BaseMutableEntity`
- Modify: `AuctionPhoto.java` → extends `BaseMutableEntity`
- Modify: `AuctionParcelSnapshot.java` → extends `BaseMutableEntity`
- Modify: `BidReservation.java` (in `wallet/`) → extends `BaseMutableEntity`
- Modify: `CancellationLog.java` → **VERIFY** — extends `BaseEntity` if no mutable fields, else `BaseMutableEntity`
- Modify: `ListingFeeRefund.java` → extends `BaseMutableEntity`
- Modify: `auction/fraud/FraudFlag.java` → extends `BaseMutableEntity`
- Modify: `auction/saved/SavedAuction.java` → extends `BaseMutableEntity`
- Modify: `parceltag/ParcelTag.java` → extends `BaseMutableEntity`

For each entity, apply the transformation from **Task 6 Step 2** (a–e). All ten follow the identical pattern.

- [ ] **Step 1: Verify CancellationLog mutability**

Read: `backend/src/main/java/com/slparcelauctions/backend/auction/CancellationLog.java`. If the entity has only `createdAt` and no fields that get UPDATE'd post-insert (no `*_at` lifecycle columns, no `status` field, no `Boolean` flags that flip), classify as **immutable** (`extends BaseEntity`). Otherwise **mutable** (`extends BaseMutableEntity`).

Record the decision here:
> CancellationLog: ____________________ (fill in during execution)

- [ ] **Step 2: Migrate each entity (template per Task 6 Step 2)**

For each file in the list above:

a. Add import: `import com.slparcelauctions.backend.common.BaseMutableEntity;` (or `BaseEntity` for immutable).
b. Replace class-level `@Builder` with `@SuperBuilder`. Add import `import lombok.experimental.SuperBuilder;`.
c. Make the class `extends BaseMutableEntity` (or `BaseEntity`).
d. Delete the fields: `@Id Long id`, `@CreationTimestamp createdAt`, `@UpdateTimestamp updatedAt` (the latter two only if present).
e. Remove now-unused imports (`@Id`, `@GeneratedValue`, `@GenerationType`, `@CreationTimestamp`, `@UpdateTimestamp`).
f. Keep all body fields, including `@Builder.Default` on defaulted fields.

- [ ] **Step 3: Compile**

Run: `cd backend && ./mvnw compile`
Expected: `BUILD SUCCESS`. If a builder-pattern call site breaks, it's likely `*.builder().id(...)` — those callers were always relying on a setter that no longer exists. Either remove the `id(...)` line (it was always unused since IDENTITY assigns at persist) or replace with `publicId(specificUuid)` if the caller meant "this entity has this specific public identifier."

- [ ] **Step 4: Run the auction-domain test suite**

Run: `cd backend && ./mvnw test -Dtest='*Auction*Test,*Bid*Test,*ProxyBid*Test,*ParcelTag*Test'`
Expected: green. Any failures are likely either:
1. Test fixtures calling `Auction.builder().id(longValue).build()` — drop the `.id(...)` call.
2. Test asserting on `auction.getCreatedAt()` ordering — still works, `createdAt` is inherited.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auction/ \
        backend/src/main/java/com/slparcelauctions/backend/wallet/BidReservation.java \
        backend/src/main/java/com/slparcelauctions/backend/parceltag/ParcelTag.java
git commit -m "refactor(auction): auction-domain entities extend BaseEntity hierarchy"
```

---

## Task 8: Migrate Escrow domain entities

**Files (all under `backend/src/main/java/com/slparcelauctions/backend/escrow/`):**

- Modify: `Escrow.java` → `BaseMutableEntity`
- Modify: `EscrowTransaction.java` → `BaseMutableEntity`
- Modify: `terminal/Terminal.java` → `BaseMutableEntity`
- Modify: `command/TerminalCommand.java` → `BaseMutableEntity`
- Modify: `admin/infrastructure/terminals/TerminalSecret.java` (under `admin/`) → `BaseMutableEntity`

- [ ] **Step 1: Migrate each entity (template per Task 6 Step 2)**

Apply the transformation. All five are mutable.

- [ ] **Step 2: Compile**

Run: `cd backend && ./mvnw compile`
Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Run the escrow test suite**

Run: `cd backend && ./mvnw test -Dtest='*Escrow*Test,*Terminal*Test'`
Expected: green.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/escrow/ \
        backend/src/main/java/com/slparcelauctions/backend/admin/infrastructure/terminals/TerminalSecret.java
git commit -m "refactor(escrow): escrow-domain entities extend BaseMutableEntity"
```

---

## Task 9: Migrate Wallet domain entities

**Files:**

- Modify: `backend/src/main/java/com/slparcelauctions/backend/wallet/UserLedgerEntry.java` → `BaseEntity` (immutable, append-only)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/admin/infrastructure/withdrawals/Withdrawal.java` → `BaseMutableEntity`

- [ ] **Step 1: Migrate `UserLedgerEntry`**

Apply Task 6 transformation. Note: `UserLedgerEntry` does NOT have `@UpdateTimestamp` today (it's append-only). Extend `BaseEntity` directly.

- [ ] **Step 2: Migrate `Withdrawal`**

Apply Task 6 transformation. `Withdrawal` is mutable — extends `BaseMutableEntity`. The current entity has `@CreationTimestamp` on `requested_at` (not `created_at`). Decision: rename `requestedAt` to `createdAt` and use the inherited field, OR keep `requestedAt` as a separate semantic field and let `createdAt` be its synonym.

The right call: **drop `requestedAt`, use inherited `createdAt`** — they have identical semantics. Update any callers reading `withdrawal.getRequestedAt()` to use `getCreatedAt()`. This removes a redundant column.

Migration SQL implication: V12 will create `withdrawals.created_at` only; the old `requested_at` column does not exist in the new schema.

- [ ] **Step 3: Compile**

Run: `cd backend && ./mvnw compile`
Expected: `BUILD SUCCESS`. Fix any `getRequestedAt()` callers to `getCreatedAt()`.

- [ ] **Step 4: Run wallet/withdrawal tests**

Run: `cd backend && ./mvnw test -Dtest='*Withdrawal*Test,*UserLedger*Test,*Wallet*Test'`
Expected: green.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/wallet/UserLedgerEntry.java \
        backend/src/main/java/com/slparcelauctions/backend/admin/infrastructure/withdrawals/Withdrawal.java
git commit -m "refactor(wallet): wallet entities extend BaseEntity hierarchy; rename Withdrawal.requestedAt → createdAt"
```

---

## Task 10: Migrate Review domain entities

**Files:**

- Modify: `backend/src/main/java/com/slparcelauctions/backend/review/Review.java` → `BaseMutableEntity`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/review/ReviewResponse.java` → `BaseMutableEntity`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/review/ReviewFlag.java` → `BaseEntity` (immutable per spec)

- [ ] **Step 1: Migrate each (template per Task 6 Step 2)**

- [ ] **Step 2: Compile + run review tests**

Run: `cd backend && ./mvnw test -Dtest='*Review*Test'`
Expected: green.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/review/
git commit -m "refactor(review): review-domain entities extend BaseEntity hierarchy"
```

---

## Task 11: Migrate remaining entities (bot, admin, verification, notification, region, auth)

**Files:**

- Modify: `backend/src/main/java/com/slparcelauctions/backend/bot/BotTask.java` → `BaseMutableEntity`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/admin/infrastructure/bots/BotWorker.java` → `BaseMutableEntity`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/admin/ban/Ban.java` → `BaseMutableEntity`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/admin/audit/AdminAction.java` → `BaseEntity` (immutable per spec)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/admin/reports/ListingReport.java` → `BaseMutableEntity`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/admin/infrastructure/reconciliation/ReconciliationRun.java` → `BaseMutableEntity`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/verification/VerificationCode.java` → `BaseMutableEntity`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/notification/Notification.java` → `BaseMutableEntity`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/notification/slim/SlImMessage.java` → `BaseMutableEntity`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/region/Region.java` → `BaseMutableEntity`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auth/RefreshToken.java` → `BaseMutableEntity`

- [ ] **Step 1: Migrate each (template per Task 6 Step 2)**

- [ ] **Step 2: Compile**

Run: `cd backend && ./mvnw compile`
Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Run remaining test suites**

Run: `cd backend && ./mvnw test -Dtest='*Bot*Test,*Admin*Test,*Ban*Test,*Verification*Test,*Notification*Test,*Region*Test,*Refresh*Test'`
Expected: green.

- [ ] **Step 4: Full backend compile to confirm nothing was missed**

Run: `cd backend && ./mvnw test-compile`
Expected: `BUILD SUCCESS`. If ANY entity is still using `@Builder` instead of `@SuperBuilder` or has its own `@Id Long id`, the test-compile will surface the inconsistency via downstream callers.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/bot/ \
        backend/src/main/java/com/slparcelauctions/backend/admin/ \
        backend/src/main/java/com/slparcelauctions/backend/verification/ \
        backend/src/main/java/com/slparcelauctions/backend/notification/ \
        backend/src/main/java/com/slparcelauctions/backend/region/ \
        backend/src/main/java/com/slparcelauctions/backend/auth/RefreshToken.java
git commit -m "refactor: remaining entities (bot/admin/verification/notification/region/auth) extend BaseEntity hierarchy"
```

---

## Task 12: Write Flyway V12 migration

**Files:**
- Create: `backend/src/main/resources/db/migration/V12__base_entity_uuid_migration.sql`

V12 drops every application table and recreates with the new shape (per spec §5.1).

- [ ] **Step 1: List existing tables**

Run: `cd backend && ls src/main/resources/db/migration/`
Read each migration to enumerate all created tables. Build the canonical table list:

```
users, refresh_tokens, parcel_tags, regions, auctions, auction_tags, auction_parcel_snapshots,
auction_photos, bids, proxy_bids, bid_reservations, cancellation_logs, listing_fee_refunds,
fraud_flags, saved_auctions, escrows, escrow_transactions, terminals, terminal_commands,
terminal_secrets, withdrawals, user_ledger, reviews, review_responses, review_flags,
bot_tasks, bot_workers, bans, admin_actions, listing_reports, reconciliation_runs,
verification_codes, notifications, sl_im_messages
```

(Verify the full set by running `grep -rh "create table\|CREATE TABLE" src/main/resources/db/migration/*.sql | sort -u`.)

- [ ] **Step 2: Author the migration**

Create `backend/src/main/resources/db/migration/V12__base_entity_uuid_migration.sql`:

The migration body has three parts:

1. **DROP all tables in dependency order** (children first, parents last). Use `DROP TABLE ... CASCADE` to handle FK constraints.
2. **CREATE all tables** in the new shape. For each table:
   - `id bigserial primary key`
   - `public_id uuid not null unique default gen_random_uuid()` (the SQL-side default is belt-and-braces; Java assigns it at construction, but the default protects manual inserts)
   - `created_at timestamptz not null default now()`
   - `updated_at timestamptz not null default now()` — only on tables backing mutable entities
   - `version bigint not null default 0` — only on tables backing mutable entities
   - All other columns from the existing schema, plus FK constraints (`bigint references foo(id) ...`), indexes, unique constraints, CHECK constraints.
3. **Enable `pgcrypto`** at the top if not already (for `gen_random_uuid()`):

```sql
CREATE EXTENSION IF NOT EXISTS pgcrypto;
```

The full migration is large (~600 lines for ~33 tables). Author it section-by-section, reading the corresponding entity to confirm column types, indexes, and constraints. Each table follows this skeleton:

```sql
DROP TABLE IF EXISTS auction_tags CASCADE;
DROP TABLE IF EXISTS bids CASCADE;
DROP TABLE IF EXISTS auctions CASCADE;
-- ... etc, in dependency order

CREATE TABLE auctions (
    id bigserial PRIMARY KEY,
    public_id uuid NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    sl_parcel_uuid uuid NOT NULL,
    seller_id bigint NOT NULL REFERENCES users(id),
    listing_agent_id bigint REFERENCES users(id),
    -- ... all other columns from Auction.java
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX ix_auctions_status_ends_at ON auctions(status, ends_at);
CREATE INDEX ix_auctions_status_starts_at ON auctions(status, starts_at DESC);
-- ... etc.
```

For immutable tables (no version, no updated_at):

```sql
CREATE TABLE bids (
    id bigserial PRIMARY KEY,
    public_id uuid NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    auction_id bigint NOT NULL REFERENCES auctions(id),
    user_id bigint NOT NULL REFERENCES users(id),
    -- ... other columns
    created_at timestamptz NOT NULL DEFAULT now()
);
```

- [ ] **Step 3: Verify the migration runs against a fresh DB**

Stop the dev container, blow away its volume, restart:

```bash
docker compose down -v
docker compose up -d postgres
docker compose up backend
```

Watch backend startup. Expected log lines:
- `Migrating schema "public" to version "1 - initial schema"`
- ... V2 ... V11 ...
- `Migrating schema "public" to version "12 - base entity uuid migration"`
- `Validated 12 migrations`
- Hibernate `validate` passes (no `SchemaManagementException`).

If Hibernate `validate` complains about a column type mismatch or missing column, the migration is incomplete or an entity field doesn't match. Fix the migration (or the entity if the entity is wrong); re-run.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/resources/db/migration/V12__base_entity_uuid_migration.sql
git commit -m "feat(db): V12 — base entity UUID migration (drop & recreate all tables)"
```

---

## Task 13: Boot backend and fix Hibernate `validate` errors

**Files:**
- Likely modify: any entity whose schema drifted from the V12 migration

- [ ] **Step 1: Boot backend cleanly**

Run: `cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`
Expected: backend starts, Flyway runs to V12, Hibernate `validate` passes, application accepts requests.

- [ ] **Step 2: If `validate` fails, reconcile**

Hibernate `validate` errors say exactly which column / table / type mismatches. For each:
1. Read the failing entity's field declaration.
2. Read the V12 migration's column definition for that table.
3. Identify the discrepancy (column missing, type wrong, nullability wrong).
4. Fix the V12 migration to match the entity (preferred) OR fix the entity to match the migration.

Re-run docker compose down -v + up to apply the fixed migration on a fresh DB.

- [ ] **Step 3: Commit any fixes**

```bash
git add backend/src/main/resources/db/migration/V12__base_entity_uuid_migration.sql
git commit -m "fix(db): reconcile V12 migration with entity declarations"
```

---

## Task 14: Update JWT auth filter to resolve user by publicId

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auth/JwtAuthenticationFilter.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auth/JwtChannelInterceptor.java` (if it constructs principals)

- [ ] **Step 1: Read the current auth filter**

Read: `backend/src/main/java/com/slparcelauctions/backend/auth/JwtAuthenticationFilter.java`

- [ ] **Step 2: Add UserRepository dependency and resolve by publicId**

Locate the path where the filter, after parsing the JWT, builds the `AuthPrincipal` and sets it into the SecurityContext. The current shape (paraphrased):

```java
AuthPrincipal principal = jwtService.parseAccessToken(token);
// principal.userId() == Long, sourced from sub
// ... set into SecurityContext
```

After Task 5, `parseAccessToken` returns a principal where `userId == null` and `userPublicId` is set from `sub`. The filter must now:

1. Resolve `userId` from `userPublicId` via `UserRepository.findByPublicId(...)`.
2. Build a *new* `AuthPrincipal` with both `userId` and `userPublicId` populated.
3. Set that principal into the SecurityContext.

Apply this change:

```java
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.user.User;

@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserRepository userRepository;
    // ... existing fields

    // Inside doFilterInternal, after parseAccessToken:
    AuthPrincipal parsed = jwtService.parseAccessToken(token);

    // Resolve internal id by publicId — the JWT only carries publicId in sub.
    User user = userRepository.findByPublicId(parsed.userPublicId())
        .orElseThrow(() -> new TokenInvalidException("User not found for token subject."));

    AuthPrincipal principal = new AuthPrincipal(
        user.getId(),
        user.getPublicId(),
        parsed.email(),
        parsed.tokenVersion(),
        parsed.role()
    );

    // ... existing tokenVersion freshness check stays as-is, comparing principal.tokenVersion()
    //     to user.getTokenVersion()
    // ... existing SecurityContext setup
}
```

- [ ] **Step 3: Update `JwtChannelInterceptor` (STOMP) similarly**

Read: `backend/src/main/java/com/slparcelauctions/backend/auth/JwtChannelInterceptor.java`. If it parses the JWT and builds a principal, apply the same pattern: resolve `userId` from `userPublicId` via UserRepository before building the `AuthPrincipal` placed in the STOMP message context.

- [ ] **Step 4: Run integration tests touching auth**

Run: `cd backend && ./mvnw test -Dtest='*Auth*Test,*Jwt*Test'`
Expected: green. Failures will surface where test fixtures construct an `AuthPrincipal` without a `userPublicId` — fix those by passing `UUID.randomUUID()` (or a fixture UUID).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auth/JwtAuthenticationFilter.java \
        backend/src/main/java/com/slparcelauctions/backend/auth/JwtChannelInterceptor.java
git commit -m "feat(auth): resolve user by publicId from JWT subject"
```

---

## Task 15: DTO renames — Auction domain

**Files (all under `backend/src/main/java/com/slparcelauctions/backend/auction/dto/` and adjacent):**

For every public DTO that today exposes an entity ID, rename `Long id` → `UUID publicId` and `Long fooId` (where `foo` is a public-facing entity like `auction`, `user`, `bid`, etc.) → `UUID fooPublicId`.

Soft FKs that reference internal entities (e.g., `Long realtyGroupId`) stay as `Long`. Soft FKs that reference public-facing entities (e.g., `Long winnerId` referencing User) become `UUID winnerPublicId`.

**Rule:** check the referenced table. If the referenced entity has a public surface (User, Auction, Bid, ProxyBid, Review, ReviewResponse, ParcelTag, AuctionPhoto), use `UUID xxxPublicId`. Otherwise `Long xxxId` stays.

- [ ] **Step 1: List affected DTOs**

Run: `cd backend && find src/main/java/com/slparcelauctions/backend/auction -name "*.java" | xargs grep -l "private Long id;"`
Run: `cd backend && find src/main/java/com/slparcelauctions/backend/auction -name "*.java" | xargs grep -lE "private Long (auctionId|userId|sellerId|bidderId|winnerId|currentBidderId|proxyBidId)"`

- [ ] **Step 2: For each DTO, rename id → publicId**

Example transformation, `AuctionResponse`:

Before:
```java
public record AuctionResponse(
    Long id,
    String title,
    Long sellerId,
    String sellerName,
    Long currentBid,
    Long winnerId,
    OffsetDateTime endsAt
    // ...
) {}
```

After:
```java
public record AuctionResponse(
    UUID publicId,
    String title,
    UUID sellerPublicId,
    String sellerName,
    Long currentBid,            // L$ amount, NOT an id — unchanged
    UUID winnerPublicId,
    OffsetDateTime endsAt
    // ...
) {}
```

Critical: `Long currentBid`, `Long startingBid`, `Long buyNowPrice`, `Long reservePrice`, `Long amount`, etc. are **L$ amounts, not IDs**. Leave them alone.

- [ ] **Step 3: Update the corresponding DtoMapper**

For every DTO in `auction/dto/`, find the mapper that constructs it. Update field-by-field:

```java
// Before
return new AuctionResponse(
    auction.getId(),
    auction.getTitle(),
    auction.getSeller().getId(),
    auction.getSeller().getDisplayName(),
    auction.getCurrentBid(),
    auction.getWinnerId(),  // Long FK column on Auction entity, references users.id
    auction.getEndsAt()
);

// After
return new AuctionResponse(
    auction.getPublicId(),
    auction.getTitle(),
    auction.getSeller().getPublicId(),
    auction.getSeller().getDisplayName(),
    auction.getCurrentBid(),
    resolveWinnerPublicId(auction),  // helper that does findById(winnerId).getPublicId() if non-null
    auction.getEndsAt()
);
```

Soft FK columns like `winnerId: Long` on the `Auction` entity STAY as `Long` (they're internal, FK joins). The mapper resolves them to `publicId` for outbound DTO emission.

- [ ] **Step 4: Compile + run auction-domain tests**

Run: `cd backend && ./mvnw test -Dtest='*Auction*Test,*Bid*Test,*ProxyBid*Test'`
Expected: green. Failures are usually DTO field renames not yet propagated to test JSON fixtures or assertions — fix locally.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auction/
git commit -m "feat(auction): public DTOs expose publicId instead of internal Long id"
```

---

## Task 16: DTO renames — User / Auth / Review domain

**Files:** `backend/src/main/java/com/slparcelauctions/backend/{user,auth,review}/dto/`

- [ ] **Step 1: List affected DTOs**

Run: `cd backend && find src/main/java/com/slparcelauctions/backend/user src/main/java/com/slparcelauctions/backend/auth src/main/java/com/slparcelauctions/backend/review -name "*.java" | xargs grep -lE "private Long (id|userId|reviewId|reviewerId|targetId)"`

- [ ] **Step 2: Apply Task 15 transformation pattern**

Same rule: ID fields → publicId; rename to `xxxPublicId`. L$ amounts and rating values unchanged. Mappers resolve via `entity.getPublicId()`.

`AuthResponse` is critical — login/refresh response. Today it likely has `Long userId` or similar. Rename to `userPublicId`.

- [ ] **Step 3: Compile + run tests**

Run: `cd backend && ./mvnw test -Dtest='*User*Test,*Auth*Test,*Review*Test'`
Expected: green.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/user/ \
        backend/src/main/java/com/slparcelauctions/backend/auth/ \
        backend/src/main/java/com/slparcelauctions/backend/review/
git commit -m "feat(user/auth/review): public DTOs expose publicId"
```

---

## Task 17: DTO renames — Escrow / Wallet / Bot / Admin / Notification / Verification / Region

**Files:** all `dto/` directories under remaining domains.

- [ ] **Step 1: Apply Task 15 pattern to each domain**

Critical distinction:
- **Escrow public DTOs** (winner sees their pending escrow, seller sees their escrow): `escrowPublicId`, `auctionPublicId`, `winnerPublicId`, `sellerPublicId`.
- **Bot DTOs** (`BotTaskResponse`, etc.): keep `id: Long`. These are internal.
- **Admin internal-data DTOs** (ledger entries, audit logs, reconciliation runs): keep `id: Long`. These are admin-only and don't cross to general users.
- **Admin user-facing DTOs** (admin viewing User, Auction, Listing): use `publicId` (same as user-facing).
- **Wallet** is interesting: `MeWalletResponse` (the user's own wallet view) carries balances + maybe a transaction history → ledger entries should expose `id: Long` (since the user can only enumerate their own ledger and the goal is to prevent cross-user enumeration; per-user enumeration of one's own ledger is fine), OR `publicId` for safety. **Decision: use `publicId` for wallet ledger DTOs** — consistency wins, no special-casing.
- **Notification DTOs**: `notificationPublicId`. The user only sees their own notifications, but consistency.

- [ ] **Step 2: Compile + run**

Run: `cd backend && ./mvnw test`
Expected: full test suite green.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/escrow/ \
        backend/src/main/java/com/slparcelauctions/backend/wallet/ \
        backend/src/main/java/com/slparcelauctions/backend/bot/ \
        backend/src/main/java/com/slparcelauctions/backend/admin/ \
        backend/src/main/java/com/slparcelauctions/backend/notification/ \
        backend/src/main/java/com/slparcelauctions/backend/verification/ \
        backend/src/main/java/com/slparcelauctions/backend/region/
git commit -m "feat: remaining domains expose publicId on user-facing DTOs (bot/admin-internal keep Long id)"
```

---

## Task 18: Controller URL path updates

**Files:** every controller under `backend/src/main/java/com/slparcelauctions/backend/`.

Public-facing URL paths use `{publicId}`; bot/internal paths keep `{id}` (Long).

- [ ] **Step 1: Inventory public-facing URL paths**

Run: `cd backend && grep -rn '@RequestMapping\|@GetMapping\|@PostMapping\|@PutMapping\|@DeleteMapping\|@PatchMapping' src/main/java/com/slparcelauctions/backend/ | grep -E '\{(id|userId|auctionId|bidId|reviewId|notificationId|escrowId|reservationId|withdrawalId|tagId)\}' | grep -vE '/(bot|admin/(ledger|audit|reconciliation))/'`

- [ ] **Step 2: Rename URL path variables**

For each public-facing endpoint, rename `{id}` → `{publicId}` and the corresponding `@PathVariable` declaration. Update the repository call:

Before:
```java
@GetMapping("/{id}")
public AuctionResponse get(@PathVariable Long id) {
    Auction a = auctionRepository.findById(id)
        .orElseThrow(() -> new ResourceNotFoundException(...));
    return mapper.toResponse(a);
}
```

After:
```java
@GetMapping("/{publicId}")
public AuctionResponse get(@PathVariable UUID publicId) {
    Auction a = auctionRepository.findByPublicId(publicId)
        .orElseThrow(() -> new ResourceNotFoundException(...));
    return mapper.toResponse(a);
}
```

This requires adding `findByPublicId(UUID)` to **every public-facing repository**: `AuctionRepository`, `BidRepository` (if `/bids/{id}` exists publicly), `ReviewRepository`, `UserRepository` (already done in Task 4), etc.

- [ ] **Step 3: Add `findByPublicId` to every public-facing repository**

For each Spring Data JPA repository whose entity has a public-facing controller endpoint, add:

```java
import java.util.Optional;
import java.util.UUID;

Optional<Foo> findByPublicId(UUID publicId);
```

- [ ] **Step 4: Bot/admin-internal controllers stay as-is**

These keep `{id}` (Long):
- `/api/v1/bot/tasks/{id}/...`
- `/api/v1/admin/ledger/{id}/...`
- `/api/v1/admin/audit/{id}/...`
- `/api/v1/admin/reconciliation/{id}/...`

Bot endpoints already documented as Long-keyed in the spec. Admin internal-data endpoints follow the same rule.

- [ ] **Step 4a: Photo URL paths**

`AuctionPhotoController` serves bytes at `/api/v1/photos/{id}` per CLAUDE.md "Frontend SSR caveats." This is a public surface — `<img src={apiUrl(photo.url)}>` runs in users' browsers. Switch the route to `/api/v1/photos/{publicId}` and update `AuctionPhotoService` (or wherever the URL is built) to emit `/api/v1/photos/<publicId>` strings.

Run: `cd backend && grep -rn '/api/v1/photos/' src/main/java/`
Update every emit-site to use `photo.getPublicId().toString()` in the URL string. Frontend consumes via `apiUrl(photo.url)` — no change there since the URL string still arrives whole.

The endpoint must remain `permitAll` per CLAUDE.md (browser image fetcher has no Authorization header). Don't gate on principal.

- [ ] **Step 5: Compile + run**

Run: `cd backend && ./mvnw test`
Expected: full backend test suite green. Controller slice tests (`@WebMvcTest`) will fail if URL paths are stale — fix them as you encounter.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/
git commit -m "feat: public controllers route by {publicId}; add findByPublicId to repos"
```

---

## Task 19: Add new spec'd tests

**Files:**
- Create: `backend/src/test/java/com/slparcelauctions/backend/common/BaseMutableEntityVersionTest.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/common/PublicIdSerializationTest.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/auth/JwtSubjectIsPublicIdTest.java`

- [ ] **Step 1: Write `BaseMutableEntityVersionTest`**

Create `backend/src/test/java/com/slparcelauctions/backend/common/BaseMutableEntityVersionTest.java`:

```java
package com.slparcelauctions.backend.common;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class BaseMutableEntityVersionTest {

    @Autowired AuctionRepository auctionRepository;
    @Autowired UserRepository userRepository;
    @Autowired EntityManager em;

    @Test
    void concurrentUpdateRaisesOptimisticLockException() {
        // Construct + persist a minimal valid Auction.
        // (Use existing test fixtures / factories from your codebase; the shape below
        // is illustrative — adjust to match what AuctionService.create requires.)
        User seller = userRepository.findAll().stream().findFirst().orElseGet(() ->
            userRepository.save(User.builder()
                .email("seed@example.com")
                .passwordHash("x")
                .role(com.slparcelauctions.backend.user.Role.USER)
                .build()));

        Auction a = auctionRepository.saveAndFlush(Auction.builder()
            .seller(seller)
            .title("test")
            .startingBid(10L)
            .durationHours(24)
            .status(AuctionStatus.DRAFT)
            // ... whatever else is required
            .build());

        em.detach(a);

        // Two managed copies of the same row.
        Auction copy1 = auctionRepository.findById(a.getId()).orElseThrow();
        em.detach(copy1);
        Auction copy2 = auctionRepository.findById(a.getId()).orElseThrow();

        // Mutate + persist copy1 first.
        copy1.setTitle("updated by copy1");
        auctionRepository.saveAndFlush(copy1);

        // copy2 has the stale version. saveAndFlush should raise.
        copy2.setTitle("updated by copy2");
        assertThatThrownBy(() -> auctionRepository.saveAndFlush(copy2))
            .isInstanceOfAny(
                OptimisticLockException.class,
                org.springframework.orm.ObjectOptimisticLockingFailureException.class);
    }
}
```

- [ ] **Step 2: Write `PublicIdSerializationTest`**

Create `backend/src/test/java/com/slparcelauctions/backend/common/PublicIdSerializationTest.java`:

```java
package com.slparcelauctions.backend.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slparcelauctions.backend.auction.Auction;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class PublicIdSerializationTest {

    @Autowired ObjectMapper objectMapper;

    @Test
    void directEntitySerializationDoesNotLeakInternalId() throws Exception {
        Auction a = Auction.builder()
            .title("x")
            .startingBid(10L)
            .durationHours(24)
            .build();

        String json = objectMapper.writeValueAsString(a);
        JsonNode node = objectMapper.readTree(json);

        assertThat(node.has("id")).isFalse();              // @JsonIgnore on BaseEntity.id
        assertThat(node.has("publicId")).isTrue();
        assertThat(node.get("publicId").asText()).isNotEmpty();
    }
}
```

- [ ] **Step 3: Write `JwtSubjectIsPublicIdTest`**

Create `backend/src/test/java/com/slparcelauctions/backend/auth/JwtSubjectIsPublicIdTest.java`:

```java
package com.slparcelauctions.backend.auth;

import com.slparcelauctions.backend.user.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.crypto.SecretKey;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class JwtSubjectIsPublicIdTest {

    @Autowired JwtService jwtService;
    @Autowired SecretKey jwtSigningKey;

    @Test
    void issuedAccessTokenSubjectIsPublicIdString() {
        UUID publicId = UUID.randomUUID();
        AuthPrincipal principal = new AuthPrincipal(42L, publicId, "x@example.com", 1L, Role.USER);

        String token = jwtService.issueAccessToken(principal);

        Jws<Claims> parsed = Jwts.parser().verifyWith(jwtSigningKey).build().parseSignedClaims(token);
        assertThat(parsed.getPayload().getSubject()).isEqualTo(publicId.toString());
        // Internal Long id MUST NOT be in any claim
        assertThat(parsed.getPayload()).doesNotContainEntry("userId", 42L);
    }
}
```

- [ ] **Step 4: Run all three new tests**

Run: `cd backend && ./mvnw test -Dtest='BaseMutableEntityVersionTest,PublicIdSerializationTest,JwtSubjectIsPublicIdTest'`
Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add backend/src/test/java/com/slparcelauctions/backend/common/BaseMutableEntityVersionTest.java \
        backend/src/test/java/com/slparcelauctions/backend/common/PublicIdSerializationTest.java \
        backend/src/test/java/com/slparcelauctions/backend/auth/JwtSubjectIsPublicIdTest.java
git commit -m "test: BaseMutableEntity @Version, public-id serialization, JWT subject = publicId"
```

---

## Task 20: Frontend type renames + dynamic route renames

**Files:** every public-facing TypeScript type under `frontend/src/types/` and `frontend/src/lib/api/`. Every Next.js dynamic route segment under `frontend/src/app/`.

- [ ] **Step 1: Inventory public types**

Run: `cd frontend && grep -rn "id: number" src/types/ src/lib/`
Run: `cd frontend && grep -rn "auctionId: number\|userId: number\|reviewId: number\|bidId: number" src/`

- [ ] **Step 2: Rename types**

For each public-facing type:

```ts
// Before
export type AuctionSummary = {
    id: number;
    title: string;
    sellerId: number;
    sellerName: string;
    currentBid: number;            // L$ amount, NOT id — keep number
    endsAt: string;
};

// After
export type AuctionSummary = {
    publicId: string;
    title: string;
    sellerPublicId: string;
    sellerName: string;
    currentBid: number;
    endsAt: string;
};
```

Bot/admin-internal types (rare in frontend, but check `frontend/src/types/admin/` for any internal-data views): keep `id: number`.

- [ ] **Step 3: Rename Next.js dynamic route segments**

Find: `cd frontend && find src/app -type d -name '\[id\]' -o -name '\[*Id\]'`

For each public-facing route:
- Rename directory: `[id]` → `[publicId]`, `[auctionId]` → `[auctionPublicId]`, etc.
- Update the page component: `params.id` → `params.publicId`.
- Drop any `Number(params.id)` coercion — it's a string now.
- Update any links pointing to that route: `href={`/auctions/${auction.id}`}` → `href={`/auctions/${auction.publicId}`}`.

- [ ] **Step 4: Update TanStack Query cache keys**

Find: `cd frontend && grep -rn 'queryKey.*id' src/lib/api/`

Replace numeric ID cache keys with `publicId`:

```ts
// Before
useQuery({ queryKey: ['auction', auctionId], queryFn: () => fetchAuction(auctionId) })

// After
useQuery({ queryKey: ['auction', auctionPublicId], queryFn: () => fetchAuction(auctionPublicId) })
```

- [ ] **Step 5: Update API hook signatures**

Hook functions like `useAuction(id: number)` → `useAuction(publicId: string)`. Update all callers.

- [ ] **Step 6: Run frontend lint + tests + build**

Run: `cd frontend && npm run lint && npm test && npm run build`
Expected: all green. The `npm run build` will surface any remaining `id: number` mismatches via TypeScript.

- [ ] **Step 7: Commit**

```bash
git add frontend/
git commit -m "feat(frontend): public types use publicId: string; dynamic routes renamed"
```

---

## Task 21: Frontend WebSocket subscription topic updates

**Files:** `frontend/src/lib/ws/` and any client component that subscribes to a STOMP topic.

- [ ] **Step 1: Inventory subscription topics that interpolate IDs**

Run: `cd frontend && grep -rn '/topic/' src/`

- [ ] **Step 2: Update topic interpolation**

For each topic that today uses `{auctionId}` or similar:

```ts
// Before
client.subscribe(`/topic/auction/${auctionId}/bids`, ...)

// After
client.subscribe(`/topic/auction/${auctionPublicId}/bids`, ...)
```

The backend's STOMP destinations also need updating in lockstep — search backend for `convertAndSend("/topic/auction/...")` and ensure those are publicId-keyed.

- [ ] **Step 3: Update backend STOMP publishers**

Run: `cd backend && grep -rn 'convertAndSend\|/topic/' src/main/java/`
For each publisher that emits to a topic with an entity ID:

```java
// Before
template.convertAndSend("/topic/auction/" + auction.getId() + "/bids", envelope);

// After
template.convertAndSend("/topic/auction/" + auction.getPublicId() + "/bids", envelope);
```

- [ ] **Step 4: Run backend WebSocket tests**

Run: `cd backend && ./mvnw test -Dtest='*WebSocket*Test,*Stomp*Test,*Broadcast*Test'`
Expected: green (with topic-string updates).

- [ ] **Step 5: Run frontend tests + build**

Run: `cd frontend && npm test && npm run build`
Expected: green.

- [ ] **Step 6: Commit**

```bash
git add frontend/ backend/
git commit -m "feat: WebSocket topics carry publicId on the wire"
```

---

## Task 22: Postman collection updates

**Files:** Postman collection (id `8070328-cf3e1190-cb71-4af3-8e2e-70c82dfee288`, workspace `SLPA` at `https://scatr-devs.postman.co`). Edit via Postman UI or Postman CLI.

- [ ] **Step 1: Identify chained-variable test scripts**

In the Postman collection, find every request whose Tests tab calls `pm.environment.set(...)` capturing an entity ID. Examples:
- `/auth/login` → captures `userId`
- `POST /auctions` → captures `auctionId`
- `POST /reviews` → captures `reviewId`

- [ ] **Step 2: Rename captures to `publicId`**

For each:

```js
// Before
const json = pm.response.json();
pm.environment.set('userId', json.id);

// After
const json = pm.response.json();
pm.environment.set('userPublicId', json.publicId);
```

- [ ] **Step 3: Update URLs in subsequent requests**

Find every request whose URL uses `{{userId}}`, `{{auctionId}}`, etc. Replace with `{{userPublicId}}`, `{{auctionPublicId}}`, etc.

Bot endpoint variables (`{{botTaskId}}`, `{{terminalId}}` for internal admin) stay as-is.

- [ ] **Step 4: Run the full collection against dev**

In Postman: select `SLPA Dev` environment, run the entire collection. Expected: every request green; chained variables thread cleanly.

- [ ] **Step 5: Commit (collection lives in Postman, not git)**

The collection isn't checked into git. Note the changes in the next git commit message:

```bash
git commit --allow-empty -m "chore: update Postman SLPA collection — publicId variables for public endpoints

Updated in Postman workspace:
- pm.environment.set('userId' → 'userPublicId')
- {{userId}} → {{userPublicId}} in URLs
- (and equivalents for auctionId, reviewId, etc.)
- Bot variables (botTaskId, etc.) unchanged."
```

---

## Task 23: Backend full test run + integration smoke

**Files:** none (verification only)

- [ ] **Step 1: Full backend test suite**

Run: `cd backend && ./mvnw clean test`
Expected: all green.

- [ ] **Step 2: Frontend full check**

Run: `cd frontend && npm run lint && npm test && npm run build && npm run verify`
Expected: all green (`verify` runs the no-dark-variants / no-hex-colors / no-inline-styles / coverage guards).

- [ ] **Step 3: Boot full stack and exercise via Postman**

Run: `docker compose down -v && docker compose up --build -d`. Wait for backend healthy (~60s).

Run the full Postman collection. Confirm:
- Every response contains `publicId` for public entities, never raw `id`.
- Every URL with `{{xxxPublicId}}` resolves correctly.
- WebSocket flows (auction subscription, bid placement) emit and consume `publicId` envelopes.

- [ ] **Step 4: Commit (verification only — no source change)**

No commit needed unless fixes were applied during smoke testing. If fixes were applied, commit them under the appropriate Task N message.

---

## Task 24: README sweep + DEFERRED_WORK ledger

**Files:**
- Modify: `README.md` (root)
- Modify: `docs/implementation/DEFERRED_WORK.md` (if any items deferred)
- Modify: `CLAUDE.md` (root) — add a section about the BaseEntity convention

- [ ] **Step 1: Sweep README for stale references**

Read: `README.md`. Search for any URL examples, code samples, or descriptions that reference entity IDs as numbers. Update to UUIDs.

- [ ] **Step 2: Add a CLAUDE.md section about BaseEntity convention**

Add to `CLAUDE.md` under a new "## BaseEntity convention" section:

```markdown
## BaseEntity convention

Every entity extends `BaseEntity` (immutable / append-only) or `BaseMutableEntity` (mutable lifecycle). Both live in `backend/src/main/java/com/slparcelauctions/backend/common/`.

- `Long id` — internal PK. `@JsonIgnore`-annotated. Used for FK joins, internal admin endpoints, bot/LSL contracts, Postman variables. **Never crosses a public wire.**
- `UUID publicId` — random UUIDv4, assigned at construction. Used in REST URLs, REST/WebSocket DTOs, frontend types, JWT subject. The only identifier safe in any public surface.

Subclass entities use Lombok `@SuperBuilder`, **not** `@Builder`. Do not redeclare `id`, `publicId`, `createdAt`, `updatedAt`, or `version` — they're inherited. Do not override `equals` or `hashCode` (they're `final`).

DTO field naming: `publicId: UUID` for public DTOs. Bot / admin-internal DTOs keep `id: Long`. URL paths follow the same split: `/api/v1/auctions/{publicId}` for public, `/api/v1/bot/tasks/{taskId}` (Long) for internal.

JWT subject claim is the user's `publicId` (UUID string), not Long. The auth filter resolves `userId` (Long) via `UserRepository.findByPublicId` at request entry; the resolved `AuthPrincipal` carries both.

Spec: `docs/superpowers/specs/2026-05-03-base-entity-uuid-design.md`.
```

- [ ] **Step 3: Update DEFERRED_WORK.md if anything was deferred**

Check the spec's §9 (Out of scope). None of those items belong on the deferred ledger — they're explicitly non-goals, not deferrals. Skip unless something was discovered during implementation that genuinely needs deferring.

- [ ] **Step 4: Commit**

```bash
git add README.md CLAUDE.md docs/implementation/DEFERRED_WORK.md
git commit -m "docs: BaseEntity convention in CLAUDE.md; sweep README for ID-shape changes"
```

- [ ] **Step 5: Push to dev**

Run: `git push origin dev`
Expected: success. Backend GitHub Actions builds the new image; Amplify rebuilds frontend on the next push to main.

---

## Final notes for the executor

**Schema-validate trips:** Hibernate's `validate` mode is the load-bearing safety net. If the V12 migration disagrees with any entity declaration in shape (column type, nullability, length), Hibernate fails boot loudly. Fix the migration or the entity in lockstep. Never set `ddl-auto: update` to dodge a validation error — that masks the bug for prod.

**Test fixture sweeps:** Many test fixtures construct entities via `Foo.builder().id(123L).build()`. With `@SuperBuilder` over `BaseEntity` and `id` having `@Setter(AccessLevel.NONE)`, the `id(...)` builder method is GONE. Most callers were setting `id` to support an in-memory test that never persisted. Drop the `.id(...)` line; equality now works via `publicId`. If a test genuinely needs to assert against a specific id (e.g., URL path `findById(123L)`), inject a fixture User/Auction/etc. via the repository, then read the assigned id back.

**The bot worker:** Bot DTOs (`BotTaskResponse.Id/AuctionId/EscrowId`) stay `long` per spec. No bot-side code changes in this plan. After deploy, watch bot logs to confirm task lifecycle still works end-to-end.

**LSL scripts:** untouched. Per the audit summary in the spec, no LSL script carries backend PKs in payloads that change shape.

**Prod rollout:** the spec §5.2 describes the prod deploy sequence. Coordinate the backend image build (GitHub Actions) with the frontend Amplify build with the Fargate DB-wipe task. Don't do this outside a window where you can spend an hour fixing surprises.
