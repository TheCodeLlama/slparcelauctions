# Epic 10 Sub-spec 1 — Admin Foundation + Fraud-Flag Triage Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the admin authority + admin section foundation that the rest of Epic 10 hangs off, plus the highest-value content surface (fraud-flag triage) on top of the existing `FraudFlag` entity.

**Architecture:** New `com.slparcelauctions.backend.admin` package owns admin controllers/services/DTOs and consumes existing repositories. JPA entities gain three new columns (`User.role`, `Auction.suspendedAt`, `FraudFlag.adminNotes`) under `ddl-auto: update`. JWT carries a new `role` claim. Frontend gets a new `/admin/*` section with sidebar shell, dashboard, and fraud-flag triage with slide-over.

**Tech Stack:** Spring Boot 4 / Java 26 / Spring Security / jjwt / Spring Data JPA / Postgres · Next.js 16 / React 19 / TanStack Query v5 / MSW v2 / Vitest

**Spec:** [`docs/superpowers/specs/2026-04-26-epic-10-sub-1-admin-foundation-and-fraud-flag-triage.md`](../specs/2026-04-26-epic-10-sub-1-admin-foundation-and-fraud-flag-triage.md)

**Branch:** `task/10-sub-1-admin-foundation-and-fraud-flag-triage` (already created on the spec commit; subsequent commits go on this branch).

---

## Pre-implementation findings (read once, refer back as needed)

### Reference patterns and exact-shape signatures

#### Backend auth & security

- **`AuthPrincipal`** — `backend/src/main/java/com/slparcelauctions/backend/auth/AuthPrincipal.java:17`
  ```java
  public record AuthPrincipal(Long userId, String email, Long tokenVersion) {}
  ```
  Sub-spec 1 extends it to `(Long userId, String email, Long tokenVersion, Role role)`.

- **`JwtService.issueAccessToken`** — `backend/src/main/java/com/slparcelauctions/backend/auth/JwtService.java:40-51`. Adds claims `email`, `tv`, `type`. Sub-spec 1 adds a `role` claim.

- **`JwtService.parseAccessToken`** — same file lines 53-77. Reads `email`, `tv`, `type`. Sub-spec 1 reads `role` with a `USER` default for missing-claim tolerance.

- **`JwtAuthenticationFilter.doFilterInternal`** — `backend/src/main/java/com/slparcelauctions/backend/auth/JwtAuthenticationFilter.java` line ~52 currently builds `new UsernamePasswordAuthenticationToken(principal, null, List.of())` (empty authorities). Sub-spec 1 changes the third arg to `List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().name()))`.

- **`SecurityConfig`** — `backend/src/main/java/com/slparcelauctions/backend/config/SecurityConfig.java:49-241`. Insert `.requestMatchers("/api/v1/admin/**").hasRole("ADMIN")` before the generic `/api/v1/**` matcher. Spring Security expands `hasRole("ADMIN")` to require authority `ROLE_ADMIN`, matching what JwtAuthenticationFilter now emits.

#### User entity & repository

- **`User`** — `backend/src/main/java/com/slparcelauctions/backend/user/User.java`. Lombok `@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor`. Sub-spec 1 adds `role` field with `@Builder.Default`.

- **`UserRepository`** — `backend/src/main/java/com/slparcelauctions/backend/user/UserRepository.java:15-51`. Existing methods include `findByEmail`, `findBySlAvatarUuid`, `findByIdForUpdate` (PESSIMISTIC_WRITE). Sub-spec 1 adds two new methods: `findAllBySlAvatarUuidIn(Set<UUID>)` for batched UUID resolution, and `bulkPromoteByEmailIfUser(List<String>)` `@Modifying @Query` for the bootstrap.

#### Auction & related

- **`Auction`** — `backend/src/main/java/com/slparcelauctions/backend/auction/Auction.java`. Sub-spec 1 adds `suspendedAt` nullable column.

- **`AuctionRepository`** — `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionRepository.java:19-185`. Add `countByStatus(AuctionStatus)`.

- **`AuctionStatus`** — `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionStatus.java:9-25`. 14 values. `SUSPENDED` is the relevant one for reinstate gating.

- **`SuspensionService`** — `backend/src/main/java/com/slparcelauctions/backend/auction/monitoring/SuspensionService.java:47-219`. Four public entry points (`suspendForOwnershipChange`, `suspendForDeletedParcel`, `suspendForBotObservation`, `raiseCancelAndSellFlag`). Sub-spec 1 modifies the three "suspend*" methods (not raiseCancelAndSell, which doesn't change auction status) to set `auction.setSuspendedAt(now)` if currently null.

#### Escrow

- **`EscrowRepository`** — `backend/src/main/java/com/slparcelauctions/backend/escrow/EscrowRepository.java:15-115`. Add `countByState(EscrowState)`, `countByStateNotIn(Collection<EscrowState>)`, `sumFinalBidAmountByState(EscrowState)` returning `Long` (or 0 if no rows), `sumCommissionAmtByState(EscrowState)` returning `Long`.

- **`EscrowState`** — `backend/src/main/java/com/slparcelauctions/backend/escrow/EscrowState.java:11-30`. 7 values; terminal set is `{COMPLETED, EXPIRED, DISPUTED, FROZEN}`.

- **`Escrow.finalBidAmount` / `Escrow.commissionAmt`** — `backend/src/main/java/com/slparcelauctions/backend/escrow/Escrow.java:59,62`. Both `Long`. The spec's "L$ gross volume" maps to `sumFinalBidAmountByState(COMPLETED)`; the spec's "L$ commission" maps to `sumCommissionAmtByState(COMPLETED)`.

#### Fraud flags

- **`FraudFlag`** — `backend/src/main/java/com/slparcelauctions/backend/auction/fraud/FraudFlag.java:45-84`. Sub-spec 1 adds `adminNotes` text column.

- **`FraudFlagRepository`** — `backend/src/main/java/com/slparcelauctions/backend/auction/fraud/FraudFlagRepository.java:7-10`. Currently only `findByAuctionId`. Sub-spec 1 adds: `countByResolved(boolean)`, `countByAuctionIdAndResolvedFalseAndIdNot(Long, Long)`, `findAll(Specification, Pageable)` (default Spring Data — extend interface with `JpaSpecificationExecutor<FraudFlag>`).

- **`FraudFlagReason`** — 12 values confirmed in `FraudFlagReason.java:7-74`.

#### Bot monitor

- **`BotMonitorLifecycleService.onAuctionActivatedBot(Auction)`** — `backend/src/main/java/com/slparcelauctions/backend/bot/BotMonitorLifecycleService.java:51-75`. Creates a fresh MONITOR_AUCTION row for a BOT-tier auction; no-op for non-BOT. Sub-spec 1 adds `onAuctionResumed(Auction)` that delegates to `onAuctionActivatedBot` (same body — re-spawn monitoring with current auction context). Tests cover both paths.

- **`BotMonitorLifecycleService.onAuctionClosed(Auction)`** — same file lines 82-89. Cancels live MONITOR_AUCTION rows. Already called by SuspensionService.

- **`BotTaskType`** — `backend/src/main/java/com/slparcelauctions/backend/bot/BotTaskType.java`. `MONITOR_AUCTION` exists.

#### Notifications

- **`NotificationCategory`** — `backend/src/main/java/com/slparcelauctions/backend/notification/NotificationCategory.java`. Sub-spec 1 adds `LISTING_REINSTATED` adjacent to `LISTING_SUSPENDED`. Both belong to `NotificationGroup.LISTING_STATUS` (constructor arg).

- **`NotificationPublisher.listingSuspended`** — `backend/src/main/java/com/slparcelauctions/backend/notification/NotificationPublisher.java:41`. Signature: `listingSuspended(long sellerUserId, long auctionId, String parcelName, String reason)`. Sub-spec 1 mirrors as `listingReinstated(long sellerUserId, long auctionId, String parcelName, OffsetDateTime newEndsAt)`.

- **`NotificationPublisherImpl.listingSuspended`** — `backend/src/main/java/com/slparcelauctions/backend/notification/NotificationPublisherImpl.java:253-261`. Pattern: build title + body, call `notificationService.publish(new NotificationEvent(...))`. Sub-spec 1 mirrors with `LISTING_REINSTATED` category.

- **`NotificationDataBuilder.listingSuspended`** — `backend/src/main/java/com/slparcelauctions/backend/notification/NotificationDataBuilder.java:113-117`. Sub-spec 1 adds `listingReinstated(long auctionId, String parcelName, OffsetDateTime newEndsAt)`.

- **Default category preferences** — User's `notifyEmail` / `notifySlIm` JSONB are keyed by `NotificationGroup` (not by category), so `LISTING_REINSTATED` automatically inherits the LISTING_STATUS group preference. No new default-pref wiring.

#### Frontend foundation

- **`useAuth()`** — `frontend/src/lib/auth/hooks.ts:55-69`. Returns `AuthSession` discriminated union from `frontend/src/lib/auth/session.ts:30-47`. `AuthUser` shape (lines 30-37) has `id`, `email`, `displayName`, `slAvatarUuid`, `verified`. Sub-spec 1 adds `role: "USER" | "ADMIN"`.

- **`RequireAuth`** — `frontend/src/components/auth/RequireAuth.tsx`. Sub-spec 1 mirrors as `RequireAdmin` with the same loading/redirect spinner pattern but redirecting to `/` (not `/login`) when authenticated-but-not-admin.

- **`Header`** — `frontend/src/components/layout/Header.tsx:46-50`. Three nav links: Browse, Dashboard, Create Listing. Sub-spec 1 adds a fourth conditionally: `{user?.role === "ADMIN" && <NavLink href="/admin">Admin</NavLink>}`.

- **`MobileMenu`** — `frontend/src/components/layout/MobileMenu.tsx:38`. Mirror.

- **`UserMenuDropdown`** — `frontend/src/components/auth/UserMenuDropdown.tsx:31`. Mirror with admin-conditional menu item.

- **`useToast`** — `frontend/src/components/ui/Toast/useToast.ts`. Methods: `toast.success(msg)`, `toast.error(msg)`. String or `ToastPayload`.

- **MSW handlers** — `frontend/src/test/msw/handlers.ts`. Domain-grouped factories. Sub-spec 1 adds `adminHandlers` and updates `authHandlers` so the seeded `AuthUser` has a `role` field (default USER).

#### Test conventions

- **`@WebMvcTest` + `@Import`** — slice tests import beans they need. Example: `PenaltyTerminalControllerSliceTest` imports `SecurityConfig`, `JwtConfig`. Sub-spec 1's admin slice tests import `SecurityConfig`, `JwtConfig`, and any new `AdminBootstrapConfig` if the test needs the bootstrap properties bean.

- **`@SpringBootTest` integration test header** — pattern from `SuspensionNotificationIntegrationTest.java:42-54`:
  ```java
  @SpringBootTest
  @ActiveProfiles("dev")
  @TestPropertySource(properties = {
      "auth.cleanup.enabled=false",
      "slpa.auction-end.enabled=false",
      "slpa.ownership-monitor.enabled=false",
      "slpa.escrow.ownership-monitor-job.enabled=false",
      "slpa.escrow.timeout-job.enabled=false",
      "slpa.escrow.command-dispatcher-job.enabled=false",
      "slpa.review.scheduler.enabled=false",
      "slpa.notifications.cleanup.enabled=false",
      "slpa.notifications.sl-im.cleanup.enabled=false"
  })
  ```
  Copy this exactly for new admin integration tests.

- **`PagedResponse.from(page)`** — `backend/src/main/java/com/slparcelauctions/backend/common/PagedResponse.java:39-46`. Use as `return PagedResponse.from(page.map(dtoMapper::toDto))`.

#### Misc

- **`application.yml` slpa block** — `backend/src/main/resources/application.yml`. Add `slpa.admin.bootstrap-emails:` array at same indent as `slpa.bot`, `slpa.escrow`.

- **`@ConfigurationProperties` + `@Configuration` companion pattern** — `backend/src/main/java/com/slparcelauctions/backend/escrow/config/EscrowPropertiesConfig.java`. Companion `@Configuration` class with `@EnableConfigurationProperties(MyProps.class)` registers the props bean. Sub-spec 1 mirrors with `AdminBootstrapConfig` + `AdminBootstrapProperties`.

- **`GlobalExceptionHandler`** — `backend/src/main/java/com/slparcelauctions/backend/common/exception/GlobalExceptionHandler.java`. `@RestControllerAdvice` returning `ProblemDetail`. Sub-spec 1 creates a separate `AdminExceptionHandler` with `@RestControllerAdvice(basePackages = "com.slparcelauctions.backend.admin")` so admin-specific 409 codes don't pollute the global handler.

- **FOOTGUNS index** — last entry is `F.98`. Sub-spec 1 adds F.99 through F.102 (see Task 11).

### Footguns to actively respect

1. **Bootstrap promotes any currently-USER row matching the email list, including deliberately-demoted bootstrap admins.** This is documented behavior — operators must remove an email from the config to permanently demote. Test for it explicitly.

2. **JWT auth filter currently emits empty authorities.** Spring Security's `hasRole("ADMIN")` matcher won't match anything until the filter is updated. Order of operations matters: update the filter in the same task as the SecurityConfig matcher.

3. **`hasRole("ADMIN")` requires authority `ROLE_ADMIN`** (Spring Security adds the `ROLE_` prefix). If the filter emits `ADMIN` instead of `ROLE_ADMIN`, gating silently fails.

4. **Token-version mechanism is the only thing that invalidates an outstanding token.** Demoting via `UPDATE users SET role = 'USER'` alone leaves the existing access token (with `role: "ADMIN"` claim) valid until expiry. Any future demote endpoint must bump `tv`.

5. **`@TestPropertySource` propagation:** every new `@SpringBootTest` integration test must include the full `slpa.*.enabled=false` block above to prevent scheduler-driven test races.

6. **`@WebMvcTest` slice tests don't auto-pick up `@Component`-scanned beans.** New admin services have to be either explicitly imported via `@Import` or referenced via `@MockitoBean`. The existing pattern is `@MockitoBean` for services in slice tests.

7. **`ddl-auto: update` does NOT drop columns or tables** when entities change. Adding `User.role`, `Auction.suspendedAt`, `FraudFlag.adminNotes` is safe; rolling back the merge leaves the columns in the schema. Documented as risk in spec §13.

8. **Spec §10.6 supersedes §4.4** — the bootstrap *will* re-promote a deliberately-demoted bootstrap email on next restart. The fix in the spec was to make §4.4 match §10.6. Implementer must read both before writing the bootstrap initializer test.

### Conventions reminders

- **No new Flyway migrations.** All schema changes via JPA `ddl-auto: update`. Three column additions in this sub-spec.
- **Lombok required** on all backend classes that benefit. Records preferred for DTOs.
- **Vertical slices** — every task ships full entity + repo + service + controller + tests where applicable.
- **Feature-based packages** — new admin code lives in `com.slparcelauctions.backend.admin` (controllers/services/DTOs) and references existing domains directly.
- **`PagedResponse<T>`** for any paginated endpoint, never raw `Page<T>`.
- **Frontend AGENTS.md:** Next.js 16 has breaking changes — read `frontend/node_modules/next/dist/docs/` before writing new frontend code.
- **No emojis** anywhere unless the user explicitly asked.
- **No new comments** unless they explain a non-obvious WHY. Don't narrate WHAT the code does.

### Test Property Source — disable all schedulers (paste into every new @SpringBootTest)

```java
@TestPropertySource(properties = {
    "auth.cleanup.enabled=false",
    "slpa.auction-end.enabled=false",
    "slpa.ownership-monitor.enabled=false",
    "slpa.escrow.ownership-monitor-job.enabled=false",
    "slpa.escrow.timeout-job.enabled=false",
    "slpa.escrow.command-dispatcher-job.enabled=false",
    "slpa.review.scheduler.enabled=false",
    "slpa.notifications.cleanup.enabled=false",
    "slpa.notifications.sl-im.cleanup.enabled=false"
})
```

---

## Task index

| # | Title | Files | Tests |
|---|---|---|---|
| 1 | Role enum + JWT role claim + Spring Security gate | User, AuthPrincipal, JwtService, JwtAuthenticationFilter, SecurityConfig, UserResponse | unit + slice |
| 2 | Admin bootstrap initializer | AdminBootstrapProperties/Config/Initializer, UserRepository, application.yml | unit + integration |
| 3 | Auction.suspendedAt column + SuspensionService update | Auction, SuspensionService | unit + integration |
| 4 | FraudFlag.adminNotes + repository expansion | FraudFlag, FraudFlagRepository | unit |
| 5 | Admin stats endpoint | AdminStatsResponse, AdminStatsService, AdminStatsController, AuctionRepository, EscrowRepository | unit + slice + integration |
| 6 | Fraud-flag list + detail endpoints | DTOs, AdminFraudFlagService, AdminFraudFlagController, UserRepository | unit + slice + integration |
| 7 | Fraud-flag dismiss + reinstate (full flow with notification + bot re-engage) | exceptions, AdminExceptionHandler, BotMonitorLifecycleService, NotificationCategory/Publisher/DataBuilder, AdminFraudFlagService, AdminFraudFlagController | unit + slice + integration |
| 8 | Frontend foundations (role, RequireAdmin, nav, types, api, MSW) | session.ts, Header, MobileMenu, UserMenuDropdown, RequireAdmin, lib/admin/*, MSW handlers | unit |
| 9 | Frontend admin shell + dashboard | AdminShell, layout, page, QueueCard, StatCard, AdminDashboardPage, useAdminStats | unit |
| 10 | Frontend fraud-flag list + slide-over | FraudFlagsListPage, filters, table, slide-over, evidence, banners, hooks, /admin/fraud-flags page | unit |
| 11 | Cross-cutting docs + manual test + push branch | DEFERRED_WORK.md, FOOTGUNS.md, DESIGN.md, CLAUDE.md, manual test | manual |

---

## Task 1: Role enum + JWT role claim + Spring Security gate

**Goal:** Establish admin authority end-to-end at the auth layer, so subsequent tasks can gate `/api/v1/admin/**` endpoints.

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/user/Role.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/user/User.java` (add `role` field)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/user/dto/UserResponse.java` (add `role` field)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/user/UserService.java` (map `role` into UserResponse if not auto-mapped)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auth/AuthPrincipal.java` (add `role` field)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auth/JwtService.java` (issue + parse `role` claim)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auth/JwtAuthenticationFilter.java` (map role to `ROLE_<NAME>` authority)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auth/AuthService.java` (or wherever `AuthPrincipal` is built from `User` — pass `role`)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/config/SecurityConfig.java` (add admin matcher)
- Test: `backend/src/test/java/com/slparcelauctions/backend/auth/JwtServiceTest.java` (extend if exists, else create)
- Test: `backend/src/test/java/com/slparcelauctions/backend/auth/JwtAuthenticationFilterTest.java` (extend, else create)
- Test: `backend/src/test/java/com/slparcelauctions/backend/admin/AdminAuthGateSliceTest.java` (new — verifies `hasRole("ADMIN")` on a probe controller)

- [ ] **Step 1: Add the Role enum**

```java
// backend/src/main/java/com/slparcelauctions/backend/user/Role.java
package com.slparcelauctions.backend.user;

public enum Role {
    USER,
    ADMIN
}
```

- [ ] **Step 2: Add `role` to User entity**

In `User.java`, add the field next to other persisted columns (alongside `verified` or near the top of the entity body):

```java
@Enumerated(EnumType.STRING)
@Column(nullable = false, length = 10)
@Builder.Default
private Role role = Role.USER;
```

Add the import: `import jakarta.persistence.EnumType;` (likely already present), `import jakarta.persistence.Enumerated;` (likely already), and `import com.slparcelauctions.backend.user.Role;` (same package — no import needed).

- [ ] **Step 3: Add `role` to UserResponse DTO**

Read `backend/src/main/java/com/slparcelauctions/backend/user/dto/UserResponse.java` first to see the existing record shape. Add a `Role role` field at the end. If the codebase has a `UserResponse.from(User)` static factory (or similar), ensure it copies `user.getRole()`.

If `UserResponse` is built somewhere via `UserService` mapping, ensure that builder/factory includes the role. Grep for `UserResponse.builder` or `new UserResponse(` to find callers and update them.

- [ ] **Step 4: Run backend compile to verify entity + DTO changes**

Run: `cd backend && ./mvnw compile -q`
Expected: BUILD SUCCESS. Hibernate may emit a startup warning when integration tests later run; the column is auto-added on next test boot.

- [ ] **Step 5: Write failing test for JwtService role claim round-trip**

Look at the existing `JwtServiceTest.java` (likely under `backend/src/test/java/com/slparcelauctions/backend/auth/`). If it exists, add a method:

```java
@Test
void issueAndParse_includesRoleClaim_USER() {
    AuthPrincipal principal = new AuthPrincipal(42L, "user@example.com", 1L, Role.USER);
    String token = jwtService.issueAccessToken(principal);
    AuthPrincipal parsed = jwtService.parseAccessToken(token);
    assertThat(parsed.role()).isEqualTo(Role.USER);
}

@Test
void issueAndParse_includesRoleClaim_ADMIN() {
    AuthPrincipal principal = new AuthPrincipal(42L, "admin@example.com", 1L, Role.ADMIN);
    String token = jwtService.issueAccessToken(principal);
    AuthPrincipal parsed = jwtService.parseAccessToken(token);
    assertThat(parsed.role()).isEqualTo(Role.ADMIN);
}

@Test
void parse_treatsMissingRoleClaim_asUSER() {
    // Build a token manually without the role claim to simulate pre-deploy tokens.
    String token = Jwts.builder()
        .subject("42")
        .claim("email", "legacy@example.com")
        .claim("tv", 1L)
        .claim("type", "access")
        .issuedAt(Date.from(Instant.now()))
        .expiration(Date.from(Instant.now().plusSeconds(3600)))
        .signWith(jwtSigningKey)
        .compact();
    AuthPrincipal parsed = jwtService.parseAccessToken(token);
    assertThat(parsed.role()).isEqualTo(Role.USER);
}
```

If `JwtServiceTest.java` doesn't exist, scaffold it following the @SpringBootTest + @ActiveProfiles("dev") + @TestPropertySource pattern. The implementer should look at any existing auth-test for the exact wiring.

- [ ] **Step 6: Run the new tests to verify failure**

Run: `cd backend && ./mvnw test -Dtest=JwtServiceTest -q`
Expected: FAIL with compilation errors (`AuthPrincipal` constructor missing 4th arg) or assertion failures (claim absent in token).

- [ ] **Step 7: Update AuthPrincipal record**

```java
// backend/src/main/java/com/slparcelauctions/backend/auth/AuthPrincipal.java
package com.slparcelauctions.backend.auth;

import com.slparcelauctions.backend.user.Role;

public record AuthPrincipal(Long userId, String email, Long tokenVersion, Role role) {}
```

- [ ] **Step 8: Update JwtService to issue + parse the role claim**

In `JwtService.issueAccessToken`, add `.claim("role", principal.role().name())` between the existing `.claim("type", "access")` and `.issuedAt(...)` lines.

In `JwtService.parseAccessToken`, after the existing claim reads (email, tv), add:

```java
String roleClaim = (String) claims.get("role");
Role role = roleClaim == null ? Role.USER : Role.valueOf(roleClaim);
return new AuthPrincipal(userId, email, tokenVersion, role);
```

Replace the existing return statement. Add import: `import com.slparcelauctions.backend.user.Role;`.

- [ ] **Step 9: Find and update every AuthPrincipal construction site**

Run: `cd backend && grep -rn "new AuthPrincipal(" src/`
Expected output: a handful of call sites in AuthService, JwtAuthenticationFilter, tests. Update each to pass `user.getRole()` (production code) or the relevant test fixture role.

For `AuthService` (or wherever the principal is built from a `User`): `new AuthPrincipal(user.getId(), user.getEmail(), user.getTokenVersion(), user.getRole())`.

For test helpers that mint a principal: pass `Role.USER` unless the test specifically targets admin behavior.

- [ ] **Step 10: Run JwtServiceTest to verify pass**

Run: `cd backend && ./mvnw test -Dtest=JwtServiceTest -q`
Expected: PASS.

- [ ] **Step 11: Write failing test for JwtAuthenticationFilter authority mapping**

In `JwtAuthenticationFilterTest.java` (extend if exists, else mirror existing slice-test scaffolding), add:

```java
@Test
void filter_mapsAdminPrincipalToRoleAdminAuthority() throws Exception {
    AuthPrincipal admin = new AuthPrincipal(1L, "a@x.com", 1L, Role.ADMIN);
    when(jwtService.parseAccessToken("admin-token")).thenReturn(admin);
    request.addHeader("Authorization", "Bearer admin-token");

    filter.doFilter(request, response, chain);

    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    assertThat(auth).isNotNull();
    assertThat(auth.getAuthorities())
        .extracting(GrantedAuthority::getAuthority)
        .containsExactly("ROLE_ADMIN");
}

@Test
void filter_mapsUserPrincipalToRoleUserAuthority() throws Exception {
    AuthPrincipal user = new AuthPrincipal(2L, "u@x.com", 1L, Role.USER);
    when(jwtService.parseAccessToken("user-token")).thenReturn(user);
    request.addHeader("Authorization", "Bearer user-token");

    filter.doFilter(request, response, chain);

    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    assertThat(auth.getAuthorities())
        .extracting(GrantedAuthority::getAuthority)
        .containsExactly("ROLE_USER");
}
```

Run: `cd backend && ./mvnw test -Dtest=JwtAuthenticationFilterTest -q`
Expected: FAIL (filter currently emits empty authorities).

- [ ] **Step 12: Update JwtAuthenticationFilter to emit role authority**

Find the line in `doFilterInternal` that builds `new UsernamePasswordAuthenticationToken(principal, null, List.of())` and replace the third arg:

```java
new UsernamePasswordAuthenticationToken(
    principal,
    null,
    List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().name())))
```

Add imports: `org.springframework.security.core.authority.SimpleGrantedAuthority` (likely present), `java.util.List` (likely present).

Run: `cd backend && ./mvnw test -Dtest=JwtAuthenticationFilterTest -q`
Expected: PASS.

- [ ] **Step 13: Add admin matcher to SecurityConfig**

In `SecurityConfig.java`, locate the `requestMatchers` chain (lines 49-241). Insert before the catch-all `/api/v1/**` matcher and after the most-specific public matchers:

```java
.requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
```

Spring Security's `hasRole("ADMIN")` matches authority `ROLE_ADMIN` (which the filter now emits for admin users).

- [ ] **Step 14: Write failing slice test for the gate**

Create `backend/src/test/java/com/slparcelauctions/backend/admin/AdminAuthGateSliceTest.java`:

```java
package com.slparcelauctions.backend.admin;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.slparcelauctions.backend.auth.JwtService;
import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.user.Role;

/**
 * Smoke test the admin gate: /api/v1/admin/** must be 401 anon, 403 USER, 200 ADMIN.
 * Uses the existing /api/v1/admin/stats endpoint (created in Task 5) once it lands;
 * for Task 1 alone, the test asserts 401/403 against a non-existent /api/v1/admin/probe
 * (404 confirms the path was reached after auth — actually 401/403 is checked BEFORE
 * the matcher resolves the path, so a non-existent path under /admin/** still gives
 * 401/403, not 404. This is what we want).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = {
    "auth.cleanup.enabled=false",
    "slpa.auction-end.enabled=false",
    "slpa.ownership-monitor.enabled=false",
    "slpa.escrow.ownership-monitor-job.enabled=false",
    "slpa.escrow.timeout-job.enabled=false",
    "slpa.escrow.command-dispatcher-job.enabled=false",
    "slpa.review.scheduler.enabled=false",
    "slpa.notifications.cleanup.enabled=false",
    "slpa.notifications.sl-im.cleanup.enabled=false"
})
class AdminAuthGateSliceTest {

    @Autowired MockMvc mvc;
    @Autowired JwtService jwtService;

    @Test
    void anonymous_returns401() throws Exception {
        mvc.perform(get("/api/v1/admin/probe-task-1"))
           .andExpect(status().isUnauthorized());
    }

    @Test
    void userRole_returns403() throws Exception {
        String token = jwtService.issueAccessToken(
            new AuthPrincipal(1L, "u@x.com", 1L, Role.USER));
        mvc.perform(get("/api/v1/admin/probe-task-1")
            .header("Authorization", "Bearer " + token))
           .andExpect(status().isForbidden());
    }

    @Test
    void adminRole_returns404_pathDoesNotExistButGatePassed() throws Exception {
        String token = jwtService.issueAccessToken(
            new AuthPrincipal(1L, "a@x.com", 1L, Role.ADMIN));
        mvc.perform(get("/api/v1/admin/probe-task-1")
            .header("Authorization", "Bearer " + token))
           .andExpect(status().isNotFound());
    }
}
```

Run: `cd backend && ./mvnw test -Dtest=AdminAuthGateSliceTest -q`
Expected: PASS for `anonymous_returns401` (already 401 from existing `.authenticated()` chain) and the new tests for USER (403) and ADMIN (404).

- [ ] **Step 15: Run the full backend test suite to catch regressions from AuthPrincipal changes**

Run: `cd backend && ./mvnw test -q`
Expected: PASS. If anything fails, it's almost certainly an `AuthPrincipal` construction site that wasn't updated in Step 9 — fix and re-run.

- [ ] **Step 16: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/user/Role.java \
        backend/src/main/java/com/slparcelauctions/backend/user/User.java \
        backend/src/main/java/com/slparcelauctions/backend/user/dto/UserResponse.java \
        backend/src/main/java/com/slparcelauctions/backend/user/UserService.java \
        backend/src/main/java/com/slparcelauctions/backend/auth/AuthPrincipal.java \
        backend/src/main/java/com/slparcelauctions/backend/auth/JwtService.java \
        backend/src/main/java/com/slparcelauctions/backend/auth/JwtAuthenticationFilter.java \
        backend/src/main/java/com/slparcelauctions/backend/auth/AuthService.java \
        backend/src/main/java/com/slparcelauctions/backend/config/SecurityConfig.java \
        backend/src/test/java/com/slparcelauctions/backend/auth/ \
        backend/src/test/java/com/slparcelauctions/backend/admin/AdminAuthGateSliceTest.java

git commit -m "$(cat <<'EOF'
feat(admin): Role enum + JWT role claim + Spring Security gate

User.role enum (USER/ADMIN), JWT carries role claim with USER fallback
for legacy tokens, JwtAuthenticationFilter emits ROLE_<name> authority,
SecurityConfig gates /api/v1/admin/** behind hasRole("ADMIN").

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Admin bootstrap initializer

**Goal:** On app startup, promote any user whose email is in `slpa.admin.bootstrap-emails` AND whose current role is USER. Idempotent. Documented re-promotion behavior.

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/admin/AdminBootstrapProperties.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/admin/AdminBootstrapConfig.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/admin/AdminBootstrapInitializer.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/user/UserRepository.java` (add `bulkPromoteByEmailIfUser`)
- Modify: `backend/src/main/resources/application.yml` (add `slpa.admin.bootstrap-emails`)
- Test: `backend/src/test/java/com/slparcelauctions/backend/admin/AdminBootstrapInitializerTest.java` (new — unit)
- Test: `backend/src/test/java/com/slparcelauctions/backend/admin/AdminBootstrapIntegrationTest.java` (new — `@SpringBootTest`)

- [ ] **Step 1: Add bulkPromoteByEmailIfUser to UserRepository**

```java
// in UserRepository.java
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
// Other existing imports...

@Modifying
@Transactional
@Query("UPDATE User u SET u.role = com.slparcelauctions.backend.user.Role.ADMIN " +
       "WHERE u.email IN :emails AND u.role = com.slparcelauctions.backend.user.Role.USER")
int bulkPromoteByEmailIfUser(@Param("emails") List<String> emails);
```

- [ ] **Step 2: Create AdminBootstrapProperties**

```java
// backend/src/main/java/com/slparcelauctions/backend/admin/AdminBootstrapProperties.java
package com.slparcelauctions.backend.admin;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

@Data
@ConfigurationProperties(prefix = "slpa.admin")
public class AdminBootstrapProperties {
    private List<String> bootstrapEmails = List.of();
}
```

- [ ] **Step 3: Create AdminBootstrapConfig (companion @Configuration)**

```java
// backend/src/main/java/com/slparcelauctions/backend/admin/AdminBootstrapConfig.java
package com.slparcelauctions.backend.admin;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AdminBootstrapProperties.class)
public class AdminBootstrapConfig {
}
```

- [ ] **Step 4: Add bootstrap-emails block to application.yml**

Read `backend/src/main/resources/application.yml`. Add a new top-level block under `slpa:` at the same indentation as `slpa.bot`, `slpa.escrow`:

```yaml
slpa:
  # ... existing slpa.* blocks ...
  admin:
    bootstrap-emails:
      - heath@slparcels.com
      - heath@slparcelauctions.com
      - heath@hadronsoftware.com
      - heath.barcus@gmail.com
```

- [ ] **Step 5: Write failing unit test for AdminBootstrapInitializer**

```java
// backend/src/test/java/com/slparcelauctions/backend/admin/AdminBootstrapInitializerTest.java
package com.slparcelauctions.backend.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.slparcelauctions.backend.user.UserRepository;

@ExtendWith(MockitoExtension.class)
class AdminBootstrapInitializerTest {

    @Mock UserRepository userRepository;

    private AdminBootstrapInitializer subject(List<String> emails) {
        AdminBootstrapProperties props = new AdminBootstrapProperties();
        props.setBootstrapEmails(emails);
        return new AdminBootstrapInitializer(userRepository, props);
    }

    @Test
    void promoteBootstrapAdmins_emptyList_skipsRepoCallEntirely() {
        AdminBootstrapInitializer init = subject(List.of());
        init.promoteBootstrapAdmins();
        verifyNoInteractions(userRepository);
    }

    @Test
    void promoteBootstrapAdmins_callsRepoWithExactList() {
        List<String> emails = List.of("a@x.com", "b@x.com");
        when(userRepository.bulkPromoteByEmailIfUser(emails)).thenReturn(2);
        AdminBootstrapInitializer init = subject(emails);
        init.promoteBootstrapAdmins();
        verify(userRepository).bulkPromoteByEmailIfUser(emails);
    }

    @Test
    void promoteBootstrapAdmins_zeroPromotedIsNotAnError() {
        List<String> emails = List.of("absent@x.com");
        when(userRepository.bulkPromoteByEmailIfUser(emails)).thenReturn(0);
        AdminBootstrapInitializer init = subject(emails);
        init.promoteBootstrapAdmins();
        verify(userRepository).bulkPromoteByEmailIfUser(emails);
    }
}
```

Run: `cd backend && ./mvnw test -Dtest=AdminBootstrapInitializerTest -q`
Expected: FAIL — `AdminBootstrapInitializer` doesn't exist yet.

- [ ] **Step 6: Create AdminBootstrapInitializer**

```java
// backend/src/main/java/com/slparcelauctions/backend/admin/AdminBootstrapInitializer.java
package com.slparcelauctions.backend.admin;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.user.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * On startup, promotes any user whose email matches the configured
 * bootstrap-emails list AND whose current role is USER. The
 * promote-only-currently-USER guard is a forward push at startup, not a
 * configurable opt-out — a deliberately-demoted bootstrap email will be
 * re-promoted on the next restart unless removed from the config list.
 * See spec §10.6 for the full lifecycle.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AdminBootstrapInitializer {

    private final UserRepository userRepository;
    private final AdminBootstrapProperties properties;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void promoteBootstrapAdmins() {
        if (properties.getBootstrapEmails().isEmpty()) {
            log.info("Admin bootstrap: no bootstrap-emails configured, skipping.");
            return;
        }
        int promoted = userRepository.bulkPromoteByEmailIfUser(properties.getBootstrapEmails());
        log.info("Admin bootstrap: promoted {} of {} configured emails to ADMIN.",
                promoted, properties.getBootstrapEmails().size());
    }
}
```

Run: `cd backend && ./mvnw test -Dtest=AdminBootstrapInitializerTest -q`
Expected: PASS.

- [ ] **Step 7: Write failing integration test for full bootstrap flow**

```java
// backend/src/test/java/com/slparcelauctions/backend/admin/AdminBootstrapIntegrationTest.java
package com.slparcelauctions.backend.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import com.slparcelauctions.backend.user.Role;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
    "auth.cleanup.enabled=false",
    "slpa.auction-end.enabled=false",
    "slpa.ownership-monitor.enabled=false",
    "slpa.escrow.ownership-monitor-job.enabled=false",
    "slpa.escrow.timeout-job.enabled=false",
    "slpa.escrow.command-dispatcher-job.enabled=false",
    "slpa.review.scheduler.enabled=false",
    "slpa.notifications.cleanup.enabled=false",
    "slpa.notifications.sl-im.cleanup.enabled=false",
    "slpa.admin.bootstrap-emails[0]=present-user@bootstrap.test",
    "slpa.admin.bootstrap-emails[1]=present-admin@bootstrap.test",
    "slpa.admin.bootstrap-emails[2]=absent@bootstrap.test"
})
class AdminBootstrapIntegrationTest {

    @Autowired UserRepository userRepository;
    @Autowired AdminBootstrapInitializer initializer;

    private Long presentUserId, presentAdminId;

    @BeforeEach
    void seed() {
        presentUserId = userRepository.save(User.builder()
            .email("present-user@bootstrap.test")
            .passwordHash("x")
            .role(Role.USER)
            .tokenVersion(1L)
            .build()).getId();

        presentAdminId = userRepository.save(User.builder()
            .email("present-admin@bootstrap.test")
            .passwordHash("x")
            .role(Role.ADMIN)
            .tokenVersion(1L)
            .build()).getId();
    }

    @AfterEach
    void cleanup() {
        userRepository.deleteById(presentUserId);
        userRepository.deleteById(presentAdminId);
    }

    @Test
    void onStartup_promotesUserRowAndLeavesAdminRowUnchanged() {
        initializer.promoteBootstrapAdmins();

        User user = userRepository.findById(presentUserId).orElseThrow();
        User admin = userRepository.findById(presentAdminId).orElseThrow();

        assertThat(user.getRole()).isEqualTo(Role.ADMIN);
        assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
    }

    @Test
    void onSecondCall_stillIdempotent_noErrorOnAlreadyAdmin() {
        initializer.promoteBootstrapAdmins();
        initializer.promoteBootstrapAdmins();

        User user = userRepository.findById(presentUserId).orElseThrow();
        assertThat(user.getRole()).isEqualTo(Role.ADMIN);
    }

    @Test
    void deliberatelyDemotedBootstrapAdmin_isRePromotedOnNextRun() {
        // Spec §10.6: bootstrap is a forward push, not configurable opt-out.
        initializer.promoteBootstrapAdmins();
        User user = userRepository.findById(presentUserId).orElseThrow();
        user.setRole(Role.USER);
        userRepository.save(user);

        initializer.promoteBootstrapAdmins();

        User reloaded = userRepository.findById(presentUserId).orElseThrow();
        assertThat(reloaded.getRole()).isEqualTo(Role.ADMIN);
    }
}
```

Run: `cd backend && ./mvnw test -Dtest=AdminBootstrapIntegrationTest -q`
Expected: PASS (the @EventListener already fired during context startup; the explicit `initializer.promoteBootstrapAdmins()` call in tests reaches the same path deterministically).

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/admin/ \
        backend/src/main/java/com/slparcelauctions/backend/user/UserRepository.java \
        backend/src/main/resources/application.yml \
        backend/src/test/java/com/slparcelauctions/backend/admin/AdminBootstrapInitializerTest.java \
        backend/src/test/java/com/slparcelauctions/backend/admin/AdminBootstrapIntegrationTest.java

git commit -m "$(cat <<'EOF'
feat(admin): bootstrap initializer + slpa.admin.bootstrap-emails config

@EventListener(ApplicationReadyEvent) promotes any USER whose email is
in the configured list. Promote-only-currently-USER is the WHERE-clause
guard — a deliberately-demoted bootstrap email gets re-promoted on next
restart unless removed from the config (spec §10.6).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Auction.suspendedAt + SuspensionService update

**Goal:** Add the `Auction.suspendedAt` column and have `SuspensionService` set it on the first suspension (idempotent on re-flag). Foundation for unambiguous reinstate-time math.

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/Auction.java` (add `suspendedAt`)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/monitoring/SuspensionService.java` (set `suspendedAt = now if null` in three suspend* methods)
- Test: `backend/src/test/java/com/slparcelauctions/backend/auction/monitoring/SuspensionServiceSuspendedAtTest.java` (new — `@SpringBootTest`)

- [ ] **Step 1: Add suspendedAt field to Auction**

In `Auction.java`, add the field next to other persisted columns (near `lastOwnershipCheckAt` if present, or near other timestamp fields):

```java
@Column(name = "suspended_at")
private OffsetDateTime suspendedAt;
```

Imports likely already present: `java.time.OffsetDateTime`, `jakarta.persistence.Column`.

- [ ] **Step 2: Compile to verify column addition is consistent**

Run: `cd backend && ./mvnw compile -q`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Write failing test for SuspensionService.suspendForOwnershipChange — first call sets suspendedAt**

```java
// backend/src/test/java/com/slparcelauctions/backend/auction/monitoring/SuspensionServiceSuspendedAtTest.java
package com.slparcelauctions.backend.auction.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.VerificationMethod;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.auction.fraud.FraudFlagRepository;
import com.slparcelauctions.backend.notification.NotificationWsBroadcasterPort;
import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.parcel.ParcelRepository;
import com.slparcelauctions.backend.sl.dto.ParcelMetadata;
import com.slparcelauctions.backend.user.Role;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
    "auth.cleanup.enabled=false",
    "slpa.auction-end.enabled=false",
    "slpa.ownership-monitor.enabled=false",
    "slpa.escrow.ownership-monitor-job.enabled=false",
    "slpa.escrow.timeout-job.enabled=false",
    "slpa.escrow.command-dispatcher-job.enabled=false",
    "slpa.review.scheduler.enabled=false",
    "slpa.notifications.cleanup.enabled=false",
    "slpa.notifications.sl-im.cleanup.enabled=false"
})
class SuspensionServiceSuspendedAtTest {

    @Autowired SuspensionService suspensionService;
    @Autowired AuctionRepository auctionRepo;
    @Autowired ParcelRepository parcelRepo;
    @Autowired UserRepository userRepo;
    @Autowired FraudFlagRepository fraudFlagRepo;

    @MockitoBean NotificationWsBroadcasterPort wsBroadcaster;

    private Long sellerId, parcelId, auctionId;

    @BeforeEach
    void seed() {
        User seller = userRepo.save(User.builder()
            .email("seller-" + UUID.randomUUID() + "@x.com")
            .passwordHash("x")
            .slAvatarUuid(UUID.randomUUID())
            .role(Role.USER)
            .tokenVersion(1L)
            .build());
        sellerId = seller.getId();

        Parcel parcel = parcelRepo.save(Parcel.builder()
            .slParcelUuid(UUID.randomUUID())
            .regionName("TestRegion")
            .ownerUuid(seller.getSlAvatarUuid())
            .areaSqm(1024)
            .positionX(BigDecimal.valueOf(128))
            .positionY(BigDecimal.valueOf(128))
            .positionZ(BigDecimal.valueOf(20))
            .build());
        parcelId = parcel.getId();

        Auction auction = auctionRepo.save(Auction.builder()
            .seller(seller)
            .parcel(parcel)
            .title("Test parcel auction")
            .status(AuctionStatus.ACTIVE)
            .verificationTier(VerificationTier.MANUAL)
            .verificationMethod(VerificationMethod.MANUAL_UUID)
            .startingBid(100L)
            .endsAt(OffsetDateTime.now().plusHours(24))
            .build());
        auctionId = auction.getId();
    }

    @AfterEach
    void cleanup() {
        fraudFlagRepo.findByAuctionId(auctionId).forEach(fraudFlagRepo::delete);
        auctionRepo.deleteById(auctionId);
        parcelRepo.deleteById(parcelId);
        userRepo.deleteById(sellerId);
    }

    @Test
    void suspendForOwnershipChange_setsSuspendedAt_onFirstCall() {
        Auction auction = auctionRepo.findById(auctionId).orElseThrow();
        ParcelMetadata evidence = new ParcelMetadata(
            parcelRepo.findById(parcelId).orElseThrow().getSlParcelUuid(),
            "TestRegion", "Test", "individual",
            UUID.randomUUID(), null, 1024, false, null, null, null, null);

        suspensionService.suspendForOwnershipChange(auction, evidence);

        Auction reloaded = auctionRepo.findById(auctionId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AuctionStatus.SUSPENDED);
        assertThat(reloaded.getSuspendedAt()).isNotNull();
    }

    @Test
    void suspendForOwnershipChange_doesNotMoveSuspendedAt_onSecondCall() {
        Auction auction = auctionRepo.findById(auctionId).orElseThrow();
        ParcelMetadata evidence = new ParcelMetadata(
            parcelRepo.findById(parcelId).orElseThrow().getSlParcelUuid(),
            "TestRegion", "Test", "individual",
            UUID.randomUUID(), null, 1024, false, null, null, null, null);

        suspensionService.suspendForOwnershipChange(auction, evidence);
        OffsetDateTime firstSuspendedAt = auctionRepo.findById(auctionId).orElseThrow().getSuspendedAt();

        // Force re-suspend (which is a no-op on status but creates a sibling flag).
        Auction reloaded = auctionRepo.findById(auctionId).orElseThrow();
        suspensionService.suspendForOwnershipChange(reloaded, evidence);

        OffsetDateTime secondSuspendedAt = auctionRepo.findById(auctionId).orElseThrow().getSuspendedAt();
        assertThat(secondSuspendedAt).isEqualTo(firstSuspendedAt);
    }

    @Test
    void suspendForDeletedParcel_setsSuspendedAt_onFirstCall() {
        Auction auction = auctionRepo.findById(auctionId).orElseThrow();
        suspensionService.suspendForDeletedParcel(auction);
        Auction reloaded = auctionRepo.findById(auctionId).orElseThrow();
        assertThat(reloaded.getSuspendedAt()).isNotNull();
    }
}
```

Run: `cd backend && ./mvnw test -Dtest=SuspensionServiceSuspendedAtTest -q`
Expected: FAIL — `getSuspendedAt()` returns null after suspend (column added but service not yet writing).

- [ ] **Step 4: Update SuspensionService to set suspendedAt if null**

In `SuspensionService.java`, in each of the three methods that flip status to SUSPENDED (`suspendForOwnershipChange`, `suspendForDeletedParcel`, `suspendForBotObservation`), add immediately after the existing `auction.setStatus(AuctionStatus.SUSPENDED)` line:

```java
if (auction.getSuspendedAt() == null) {
    auction.setSuspendedAt(now);
}
```

Use the same `now` variable already computed at the top of each method via `OffsetDateTime.now(clock)`. Do NOT modify `raiseCancelAndSellFlag` — that method does not change auction status (the auction is already CANCELLED).

Run: `cd backend && ./mvnw test -Dtest=SuspensionServiceSuspendedAtTest -q`
Expected: PASS.

- [ ] **Step 5: Run full SuspensionService test suite to verify no regressions**

Run: `cd backend && ./mvnw test -Dtest='*Suspension*' -q`
Expected: PASS. If anything fails, revisit the field additions.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auction/Auction.java \
        backend/src/main/java/com/slparcelauctions/backend/auction/monitoring/SuspensionService.java \
        backend/src/test/java/com/slparcelauctions/backend/auction/monitoring/SuspensionServiceSuspendedAtTest.java

git commit -m "$(cat <<'EOF'
feat(admin): Auction.suspendedAt column + first-suspension stamp

SuspensionService.suspendFor{OwnershipChange,DeletedParcel,BotObservation}
sets suspendedAt = now ONLY if currently null. Re-flagging an already-
suspended auction adds a sibling fraud flag without moving suspendedAt.
Foundation for unambiguous reinstate-time math (Task 7).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: FraudFlag.adminNotes + repository expansion

**Goal:** Add the `adminNotes` text column to `FraudFlag` and the new repository methods needed by Tasks 5–7.

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/fraud/FraudFlag.java` (add `adminNotes`)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/fraud/FraudFlagRepository.java` (add methods + `JpaSpecificationExecutor`)
- Test: `backend/src/test/java/com/slparcelauctions/backend/auction/fraud/FraudFlagRepositoryTest.java` (new or extend)

- [ ] **Step 1: Add adminNotes column to FraudFlag**

```java
// in FraudFlag.java
@Column(name = "admin_notes", columnDefinition = "text")
private String adminNotes;
```

- [ ] **Step 2: Extend FraudFlagRepository**

```java
// FraudFlagRepository.java
package com.slparcelauctions.backend.auction.fraud;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface FraudFlagRepository extends
        JpaRepository<FraudFlag, Long>,
        JpaSpecificationExecutor<FraudFlag> {

    List<FraudFlag> findByAuctionId(Long auctionId);

    long countByResolved(boolean resolved);

    long countByAuctionIdAndResolvedFalseAndIdNot(Long auctionId, Long flagId);
}
```

- [ ] **Step 3: Write failing test for the new repository methods**

```java
// backend/src/test/java/com/slparcelauctions/backend/auction/fraud/FraudFlagRepositoryTest.java
package com.slparcelauctions.backend.auction.fraud;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.VerificationMethod;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.parcel.ParcelRepository;
import com.slparcelauctions.backend.user.Role;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
    "auth.cleanup.enabled=false",
    "slpa.auction-end.enabled=false",
    "slpa.ownership-monitor.enabled=false",
    "slpa.escrow.ownership-monitor-job.enabled=false",
    "slpa.escrow.timeout-job.enabled=false",
    "slpa.escrow.command-dispatcher-job.enabled=false",
    "slpa.review.scheduler.enabled=false",
    "slpa.notifications.cleanup.enabled=false",
    "slpa.notifications.sl-im.cleanup.enabled=false"
})
class FraudFlagRepositoryTest {

    @Autowired FraudFlagRepository fraudFlagRepo;
    @Autowired AuctionRepository auctionRepo;
    @Autowired ParcelRepository parcelRepo;
    @Autowired UserRepository userRepo;

    private Long auctionId, parcelId, userId;
    private FraudFlag flagA, flagB;

    @BeforeEach
    void seed() {
        User user = userRepo.save(User.builder()
            .email("seller-" + UUID.randomUUID() + "@x.com")
            .passwordHash("x")
            .slAvatarUuid(UUID.randomUUID())
            .role(Role.USER)
            .tokenVersion(1L)
            .build());
        userId = user.getId();

        Parcel parcel = parcelRepo.save(Parcel.builder()
            .slParcelUuid(UUID.randomUUID())
            .regionName("R")
            .ownerUuid(user.getSlAvatarUuid())
            .areaSqm(512)
            .positionX(BigDecimal.valueOf(0))
            .positionY(BigDecimal.valueOf(0))
            .positionZ(BigDecimal.valueOf(0))
            .build());
        parcelId = parcel.getId();

        Auction auction = auctionRepo.save(Auction.builder()
            .seller(user)
            .parcel(parcel)
            .title("Test")
            .status(AuctionStatus.SUSPENDED)
            .verificationTier(VerificationTier.MANUAL)
            .verificationMethod(VerificationMethod.MANUAL_UUID)
            .startingBid(1L)
            .endsAt(OffsetDateTime.now().plusHours(1))
            .build());
        auctionId = auction.getId();

        flagA = fraudFlagRepo.save(FraudFlag.builder()
            .auction(auction).parcel(parcel)
            .reason(FraudFlagReason.OWNERSHIP_CHANGED_TO_UNKNOWN)
            .detectedAt(OffsetDateTime.now())
            .resolved(false)
            .evidenceJson(Map.of())
            .build());

        flagB = fraudFlagRepo.save(FraudFlag.builder()
            .auction(auction).parcel(parcel)
            .reason(FraudFlagReason.PARCEL_DELETED_OR_MERGED)
            .detectedAt(OffsetDateTime.now())
            .resolved(false)
            .evidenceJson(Map.of())
            .build());
    }

    @AfterEach
    void cleanup() {
        fraudFlagRepo.deleteAll(fraudFlagRepo.findByAuctionId(auctionId));
        auctionRepo.deleteById(auctionId);
        parcelRepo.deleteById(parcelId);
        userRepo.deleteById(userId);
    }

    @Test
    void countByResolved_falseCountsOpenFlags() {
        long open = fraudFlagRepo.countByResolved(false);
        assertThat(open).isGreaterThanOrEqualTo(2L);
    }

    @Test
    void countByAuctionIdAndResolvedFalseAndIdNot_returnsSiblingOpenCount() {
        long siblings = fraudFlagRepo.countByAuctionIdAndResolvedFalseAndIdNot(auctionId, flagA.getId());
        assertThat(siblings).isEqualTo(1L);
    }

    @Test
    void countByAuctionIdAndResolvedFalseAndIdNot_excludesResolved() {
        flagB.setResolved(true);
        flagB.setResolvedAt(OffsetDateTime.now());
        flagB.setAdminNotes("dismissed");
        fraudFlagRepo.save(flagB);

        long siblings = fraudFlagRepo.countByAuctionIdAndResolvedFalseAndIdNot(auctionId, flagA.getId());
        assertThat(siblings).isZero();
    }
}
```

Run: `cd backend && ./mvnw test -Dtest=FraudFlagRepositoryTest -q`
Expected: PASS (the methods are derived-query Spring Data so they should work once the column + interface changes are in).

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auction/fraud/FraudFlag.java \
        backend/src/main/java/com/slparcelauctions/backend/auction/fraud/FraudFlagRepository.java \
        backend/src/test/java/com/slparcelauctions/backend/auction/fraud/FraudFlagRepositoryTest.java

git commit -m "$(cat <<'EOF'
feat(admin): FraudFlag.adminNotes column + repository expansion

Adds admin_notes text column populated on dismiss/reinstate.
FraudFlagRepository extends JpaSpecificationExecutor (used by the list
endpoint in Task 6) and gains countByResolved + sibling-open-count
methods used by the dashboard stats and slide-over warning.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: Admin stats endpoint

**Goal:** Ship `GET /api/v1/admin/stats` returning the 9-number payload (3 queue + 6 platform). Computed live, no caching.

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionRepository.java` (add `countByStatus`)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/escrow/EscrowRepository.java` (add `countByState`, `countByStateNotIn`, `sumFinalBidAmountByState`, `sumCommissionAmtByState`)
- Create: `backend/src/main/java/com/slparcelauctions/backend/admin/dto/AdminStatsResponse.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/admin/AdminStatsService.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/admin/AdminStatsController.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/admin/AdminStatsServiceTest.java` (unit, Mockito)
- Test: `backend/src/test/java/com/slparcelauctions/backend/admin/AdminStatsControllerSliceTest.java` (`@WebMvcTest`)
- Test: `backend/src/test/java/com/slparcelauctions/backend/admin/AdminStatsIntegrationTest.java` (`@SpringBootTest`)

- [ ] **Step 1: Add countByStatus to AuctionRepository**

```java
// AuctionRepository.java — add method
long countByStatus(AuctionStatus status);
```

- [ ] **Step 2: Add aggregation methods to EscrowRepository**

```java
// EscrowRepository.java — add methods
import java.util.Collection;
import com.slparcelauctions.backend.escrow.EscrowState;
import org.springframework.data.jpa.repository.Query;

long countByState(EscrowState state);

long countByStateNotIn(Collection<EscrowState> states);

@Query("SELECT COALESCE(SUM(e.finalBidAmount), 0) FROM Escrow e WHERE e.state = :state")
long sumFinalBidAmountByState(@Param("state") EscrowState state);

@Query("SELECT COALESCE(SUM(e.commissionAmt), 0) FROM Escrow e WHERE e.state = :state")
long sumCommissionAmtByState(@Param("state") EscrowState state);
```

`@Param` import: `org.springframework.data.repository.query.Param`.

- [ ] **Step 3: Create AdminStatsResponse DTO**

```java
// backend/src/main/java/com/slparcelauctions/backend/admin/dto/AdminStatsResponse.java
package com.slparcelauctions.backend.admin.dto;

public record AdminStatsResponse(
    QueueStats queues,
    PlatformStats platform
) {
    public record QueueStats(
        long openFraudFlags,
        long pendingPayments,
        long activeDisputes
    ) {}

    public record PlatformStats(
        long activeListings,
        long totalUsers,
        long activeEscrows,
        long completedSales,
        long lindenGrossVolume,
        long lindenCommissionEarned
    ) {}
}
```

- [ ] **Step 4: Write failing unit test for AdminStatsService**

```java
// backend/src/test/java/com/slparcelauctions/backend/admin/AdminStatsServiceTest.java
package com.slparcelauctions.backend.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.slparcelauctions.backend.admin.dto.AdminStatsResponse;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.fraud.FraudFlagRepository;
import com.slparcelauctions.backend.escrow.EscrowRepository;
import com.slparcelauctions.backend.escrow.EscrowState;
import com.slparcelauctions.backend.user.UserRepository;

@ExtendWith(MockitoExtension.class)
class AdminStatsServiceTest {

    @Mock UserRepository userRepository;
    @Mock AuctionRepository auctionRepository;
    @Mock EscrowRepository escrowRepository;
    @Mock FraudFlagRepository fraudFlagRepository;

    @InjectMocks AdminStatsService service;

    @Test
    void compute_assemblesAllNineNumbers() {
        when(fraudFlagRepository.countByResolved(false)).thenReturn(7L);
        when(escrowRepository.countByState(EscrowState.ESCROW_PENDING)).thenReturn(3L);
        when(escrowRepository.countByState(EscrowState.DISPUTED)).thenReturn(1L);
        when(auctionRepository.countByStatus(AuctionStatus.ACTIVE)).thenReturn(42L);
        when(userRepository.count()).thenReturn(381L);
        when(escrowRepository.countByStateNotIn(any())).thenReturn(12L);
        when(escrowRepository.countByState(EscrowState.COMPLETED)).thenReturn(156L);
        when(escrowRepository.sumFinalBidAmountByState(EscrowState.COMPLETED)).thenReturn(4_827_500L);
        when(escrowRepository.sumCommissionAmtByState(EscrowState.COMPLETED)).thenReturn(241_375L);

        AdminStatsResponse result = service.compute();

        assertThat(result.queues().openFraudFlags()).isEqualTo(7L);
        assertThat(result.queues().pendingPayments()).isEqualTo(3L);
        assertThat(result.queues().activeDisputes()).isEqualTo(1L);
        assertThat(result.platform().activeListings()).isEqualTo(42L);
        assertThat(result.platform().totalUsers()).isEqualTo(381L);
        assertThat(result.platform().activeEscrows()).isEqualTo(12L);
        assertThat(result.platform().completedSales()).isEqualTo(156L);
        assertThat(result.platform().lindenGrossVolume()).isEqualTo(4_827_500L);
        assertThat(result.platform().lindenCommissionEarned()).isEqualTo(241_375L);
    }

    @Test
    void compute_activeEscrows_excludesAllTerminalStates() {
        Set<EscrowState> terminal = Set.of(
            EscrowState.COMPLETED, EscrowState.EXPIRED,
            EscrowState.DISPUTED, EscrowState.FROZEN);
        when(fraudFlagRepository.countByResolved(false)).thenReturn(0L);
        when(escrowRepository.countByState(any())).thenReturn(0L);
        when(auctionRepository.countByStatus(any())).thenReturn(0L);
        when(userRepository.count()).thenReturn(0L);
        when(escrowRepository.countByStateNotIn(terminal)).thenReturn(99L);
        when(escrowRepository.sumFinalBidAmountByState(any())).thenReturn(0L);
        when(escrowRepository.sumCommissionAmtByState(any())).thenReturn(0L);

        AdminStatsResponse result = service.compute();

        assertThat(result.platform().activeEscrows()).isEqualTo(99L);
    }
}
```

Run: `cd backend && ./mvnw test -Dtest=AdminStatsServiceTest -q`
Expected: FAIL — `AdminStatsService` doesn't exist yet.

- [ ] **Step 5: Create AdminStatsService**

```java
// backend/src/main/java/com/slparcelauctions/backend/admin/AdminStatsService.java
package com.slparcelauctions.backend.admin;

import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.admin.dto.AdminStatsResponse;
import com.slparcelauctions.backend.admin.dto.AdminStatsResponse.PlatformStats;
import com.slparcelauctions.backend.admin.dto.AdminStatsResponse.QueueStats;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.fraud.FraudFlagRepository;
import com.slparcelauctions.backend.escrow.EscrowRepository;
import com.slparcelauctions.backend.escrow.EscrowState;
import com.slparcelauctions.backend.user.UserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AdminStatsService {

    private static final Set<EscrowState> TERMINAL_ESCROW_STATES = Set.of(
        EscrowState.COMPLETED,
        EscrowState.EXPIRED,
        EscrowState.DISPUTED,
        EscrowState.FROZEN
    );

    private final UserRepository userRepository;
    private final AuctionRepository auctionRepository;
    private final EscrowRepository escrowRepository;
    private final FraudFlagRepository fraudFlagRepository;

    @Transactional(readOnly = true)
    public AdminStatsResponse compute() {
        QueueStats queues = new QueueStats(
            fraudFlagRepository.countByResolved(false),
            escrowRepository.countByState(EscrowState.ESCROW_PENDING),
            escrowRepository.countByState(EscrowState.DISPUTED)
        );

        PlatformStats platform = new PlatformStats(
            auctionRepository.countByStatus(AuctionStatus.ACTIVE),
            userRepository.count(),
            escrowRepository.countByStateNotIn(TERMINAL_ESCROW_STATES),
            escrowRepository.countByState(EscrowState.COMPLETED),
            escrowRepository.sumFinalBidAmountByState(EscrowState.COMPLETED),
            escrowRepository.sumCommissionAmtByState(EscrowState.COMPLETED)
        );

        return new AdminStatsResponse(queues, platform);
    }
}
```

Run: `cd backend && ./mvnw test -Dtest=AdminStatsServiceTest -q`
Expected: PASS.

- [ ] **Step 6: Create AdminStatsController**

```java
// backend/src/main/java/com/slparcelauctions/backend/admin/AdminStatsController.java
package com.slparcelauctions.backend.admin;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.admin.dto.AdminStatsResponse;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminStatsController {

    private final AdminStatsService statsService;

    @GetMapping("/stats")
    public AdminStatsResponse stats() {
        return statsService.compute();
    }
}
```

- [ ] **Step 7: Write slice test for AdminStatsController auth gating**

```java
// backend/src/test/java/com/slparcelauctions/backend/admin/AdminStatsControllerSliceTest.java
package com.slparcelauctions.backend.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.slparcelauctions.backend.admin.dto.AdminStatsResponse;
import com.slparcelauctions.backend.admin.dto.AdminStatsResponse.PlatformStats;
import com.slparcelauctions.backend.admin.dto.AdminStatsResponse.QueueStats;
import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.auth.JwtService;
import com.slparcelauctions.backend.user.Role;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = {
    "auth.cleanup.enabled=false",
    "slpa.auction-end.enabled=false",
    "slpa.ownership-monitor.enabled=false",
    "slpa.escrow.ownership-monitor-job.enabled=false",
    "slpa.escrow.timeout-job.enabled=false",
    "slpa.escrow.command-dispatcher-job.enabled=false",
    "slpa.review.scheduler.enabled=false",
    "slpa.notifications.cleanup.enabled=false",
    "slpa.notifications.sl-im.cleanup.enabled=false"
})
class AdminStatsControllerSliceTest {

    @Autowired MockMvc mvc;
    @Autowired JwtService jwtService;

    @MockitoBean AdminStatsService statsService;

    @Test
    void stats_anonymous_returns401() throws Exception {
        mvc.perform(get("/api/v1/admin/stats")).andExpect(status().isUnauthorized());
    }

    @Test
    void stats_userRole_returns403() throws Exception {
        String token = jwtService.issueAccessToken(
            new AuthPrincipal(1L, "u@x.com", 1L, Role.USER));
        mvc.perform(get("/api/v1/admin/stats")
            .header("Authorization", "Bearer " + token))
           .andExpect(status().isForbidden());
    }

    @Test
    void stats_adminRole_returns200_withPayload() throws Exception {
        when(statsService.compute()).thenReturn(new AdminStatsResponse(
            new QueueStats(7L, 3L, 1L),
            new PlatformStats(42L, 381L, 12L, 156L, 4_827_500L, 241_375L)));

        String token = jwtService.issueAccessToken(
            new AuthPrincipal(1L, "a@x.com", 1L, Role.ADMIN));
        mvc.perform(get("/api/v1/admin/stats")
            .header("Authorization", "Bearer " + token))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.queues.openFraudFlags").value(7))
           .andExpect(jsonPath("$.queues.pendingPayments").value(3))
           .andExpect(jsonPath("$.queues.activeDisputes").value(1))
           .andExpect(jsonPath("$.platform.activeListings").value(42))
           .andExpect(jsonPath("$.platform.totalUsers").value(381))
           .andExpect(jsonPath("$.platform.activeEscrows").value(12))
           .andExpect(jsonPath("$.platform.completedSales").value(156))
           .andExpect(jsonPath("$.platform.lindenGrossVolume").value(4827500))
           .andExpect(jsonPath("$.platform.lindenCommissionEarned").value(241375));
    }
}
```

Run: `cd backend && ./mvnw test -Dtest=AdminStatsControllerSliceTest -q`
Expected: PASS.

- [ ] **Step 8: Write integration test seeded with known fixture**

```java
// backend/src/test/java/com/slparcelauctions/backend/admin/AdminStatsIntegrationTest.java
package com.slparcelauctions.backend.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import com.slparcelauctions.backend.admin.dto.AdminStatsResponse;
import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.VerificationMethod;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.escrow.Escrow;
import com.slparcelauctions.backend.escrow.EscrowRepository;
import com.slparcelauctions.backend.escrow.EscrowState;
import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.parcel.ParcelRepository;
import com.slparcelauctions.backend.user.Role;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
    "auth.cleanup.enabled=false",
    "slpa.auction-end.enabled=false",
    "slpa.ownership-monitor.enabled=false",
    "slpa.escrow.ownership-monitor-job.enabled=false",
    "slpa.escrow.timeout-job.enabled=false",
    "slpa.escrow.command-dispatcher-job.enabled=false",
    "slpa.review.scheduler.enabled=false",
    "slpa.notifications.cleanup.enabled=false",
    "slpa.notifications.sl-im.cleanup.enabled=false"
})
class AdminStatsIntegrationTest {

    @Autowired AdminStatsService statsService;
    @Autowired UserRepository userRepo;
    @Autowired ParcelRepository parcelRepo;
    @Autowired AuctionRepository auctionRepo;
    @Autowired EscrowRepository escrowRepo;

    private Long userId, parcelId;

    @BeforeEach
    void seed() {
        User user = userRepo.save(User.builder()
            .email("seed-" + UUID.randomUUID() + "@x.com")
            .passwordHash("x").role(Role.USER).tokenVersion(1L)
            .slAvatarUuid(UUID.randomUUID()).build());
        userId = user.getId();

        Parcel parcel = parcelRepo.save(Parcel.builder()
            .slParcelUuid(UUID.randomUUID()).regionName("R")
            .ownerUuid(user.getSlAvatarUuid()).areaSqm(1)
            .positionX(BigDecimal.ZERO).positionY(BigDecimal.ZERO).positionZ(BigDecimal.ZERO)
            .build());
        parcelId = parcel.getId();

        // 2 ACTIVE auctions, 1 SUSPENDED (not counted)
        for (int i = 0; i < 2; i++) {
            auctionRepo.save(Auction.builder()
                .seller(user).parcel(parcel).title("active-" + i)
                .status(AuctionStatus.ACTIVE)
                .verificationTier(VerificationTier.MANUAL)
                .verificationMethod(VerificationMethod.MANUAL_UUID)
                .startingBid(1L).endsAt(OffsetDateTime.now().plusHours(1))
                .build());
        }
        auctionRepo.save(Auction.builder()
            .seller(user).parcel(parcel).title("suspended")
            .status(AuctionStatus.SUSPENDED)
            .verificationTier(VerificationTier.MANUAL)
            .verificationMethod(VerificationMethod.MANUAL_UUID)
            .startingBid(1L).endsAt(OffsetDateTime.now().plusHours(1))
            .build());

        // Escrows: 1 ESCROW_PENDING, 1 FUNDED, 1 COMPLETED with amount=10000 commission=500
        Auction sourceAuction = auctionRepo.findAll().iterator().next();
        escrowRepo.save(Escrow.builder()
            .auction(sourceAuction).state(EscrowState.ESCROW_PENDING)
            .finalBidAmount(1000L).commissionAmt(50L).payoutAmt(950L)
            .createdAt(OffsetDateTime.now()).build());
        escrowRepo.save(Escrow.builder()
            .auction(sourceAuction).state(EscrowState.FUNDED)
            .finalBidAmount(2000L).commissionAmt(100L).payoutAmt(1900L)
            .createdAt(OffsetDateTime.now()).build());
        escrowRepo.save(Escrow.builder()
            .auction(sourceAuction).state(EscrowState.COMPLETED)
            .finalBidAmount(10000L).commissionAmt(500L).payoutAmt(9500L)
            .createdAt(OffsetDateTime.now()).build());
    }

    @AfterEach
    void cleanup() {
        escrowRepo.deleteAll();
        auctionRepo.deleteAll();
        parcelRepo.deleteById(parcelId);
        userRepo.deleteById(userId);
    }

    @Test
    void compute_returnsExactNumbers() {
        AdminStatsResponse result = statsService.compute();

        assertThat(result.platform().activeListings()).isEqualTo(2L);
        assertThat(result.queues().pendingPayments()).isEqualTo(1L);
        assertThat(result.platform().activeEscrows()).isEqualTo(2L); // ESCROW_PENDING + FUNDED
        assertThat(result.platform().completedSales()).isEqualTo(1L);
        assertThat(result.platform().lindenGrossVolume()).isEqualTo(10000L);
        assertThat(result.platform().lindenCommissionEarned()).isEqualTo(500L);
    }
}
```

Run: `cd backend && ./mvnw test -Dtest=AdminStatsIntegrationTest -q`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/admin/AdminStatsService.java \
        backend/src/main/java/com/slparcelauctions/backend/admin/AdminStatsController.java \
        backend/src/main/java/com/slparcelauctions/backend/admin/dto/AdminStatsResponse.java \
        backend/src/main/java/com/slparcelauctions/backend/auction/AuctionRepository.java \
        backend/src/main/java/com/slparcelauctions/backend/escrow/EscrowRepository.java \
        backend/src/test/java/com/slparcelauctions/backend/admin/AdminStatsServiceTest.java \
        backend/src/test/java/com/slparcelauctions/backend/admin/AdminStatsControllerSliceTest.java \
        backend/src/test/java/com/slparcelauctions/backend/admin/AdminStatsIntegrationTest.java

git commit -m "$(cat <<'EOF'
feat(admin): GET /api/v1/admin/stats — 9-number dashboard payload

Live-computed snapshot of 3 queue counts (open fraud flags, pending
payments, active disputes) plus 6 platform numbers (active listings,
total users, active escrows, completed sales, gross L$ volume, L$
commission earned). No caching; single read-only transaction.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: Fraud-flag list + detail endpoints

**Goal:** Ship `GET /api/v1/admin/fraud-flags` (paginated, filterable) and `GET /api/v1/admin/fraud-flags/{id}` (with batched `linkedUsers` resolution + sibling-open-flag count).

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/user/UserRepository.java` (add `findAllBySlAvatarUuidIn`)
- Create: `backend/src/main/java/com/slparcelauctions/backend/admin/dto/AdminFraudFlagSummaryDto.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/admin/dto/AdminFraudFlagDetailDto.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/admin/AdminFraudFlagService.java` (list + detail; dismiss/reinstate added in Task 7)
- Create: `backend/src/main/java/com/slparcelauctions/backend/admin/AdminFraudFlagController.java` (list + detail; dismiss/reinstate added in Task 7)
- Test: `backend/src/test/java/com/slparcelauctions/backend/admin/AdminFraudFlagServiceListTest.java` (`@SpringBootTest`)
- Test: `backend/src/test/java/com/slparcelauctions/backend/admin/AdminFraudFlagControllerListSliceTest.java` (`@WebMvcTest`-style `@SpringBootTest`+`@AutoConfigureMockMvc`)

- [ ] **Step 1: Add findAllBySlAvatarUuidIn to UserRepository**

```java
// UserRepository.java — add
import java.util.Set;
import java.util.UUID;

List<User> findAllBySlAvatarUuidIn(Set<UUID> uuids);
```

- [ ] **Step 2: Create AdminFraudFlagSummaryDto**

```java
// backend/src/main/java/com/slparcelauctions/backend/admin/dto/AdminFraudFlagSummaryDto.java
package com.slparcelauctions.backend.admin.dto;

import java.time.OffsetDateTime;

import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.fraud.FraudFlagReason;

public record AdminFraudFlagSummaryDto(
    Long id,
    FraudFlagReason reason,
    OffsetDateTime detectedAt,
    Long auctionId,
    String auctionTitle,
    AuctionStatus auctionStatus,
    String parcelRegionName,
    Long parcelLocalId,
    boolean resolved,
    OffsetDateTime resolvedAt,
    String resolvedByDisplayName
) {}
```

- [ ] **Step 3: Create AdminFraudFlagDetailDto**

```java
// backend/src/main/java/com/slparcelauctions/backend/admin/dto/AdminFraudFlagDetailDto.java
package com.slparcelauctions.backend.admin.dto;

import java.time.OffsetDateTime;
import java.util.Map;

import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.fraud.FraudFlagReason;

public record AdminFraudFlagDetailDto(
    Long id,
    FraudFlagReason reason,
    OffsetDateTime detectedAt,
    OffsetDateTime resolvedAt,
    String resolvedByDisplayName,
    String adminNotes,
    AuctionContextDto auction,
    Map<String, Object> evidenceJson,
    Map<String, LinkedUserDto> linkedUsers,
    long siblingOpenFlagCount
) {
    public record AuctionContextDto(
        Long id,
        String title,
        AuctionStatus status,
        OffsetDateTime endsAt,
        OffsetDateTime suspendedAt,
        Long sellerUserId,
        String sellerDisplayName
    ) {}

    public record LinkedUserDto(
        Long userId,
        String displayName
    ) {}
}
```

- [ ] **Step 4: Write failing service test for list (basic + filtering)**

```java
// backend/src/test/java/com/slparcelauctions/backend/admin/AdminFraudFlagServiceListTest.java
package com.slparcelauctions.backend.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import com.slparcelauctions.backend.admin.dto.AdminFraudFlagSummaryDto;
import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.VerificationMethod;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.auction.fraud.FraudFlag;
import com.slparcelauctions.backend.auction.fraud.FraudFlagReason;
import com.slparcelauctions.backend.auction.fraud.FraudFlagRepository;
import com.slparcelauctions.backend.common.PagedResponse;
import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.parcel.ParcelRepository;
import com.slparcelauctions.backend.user.Role;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
    "auth.cleanup.enabled=false",
    "slpa.auction-end.enabled=false",
    "slpa.ownership-monitor.enabled=false",
    "slpa.escrow.ownership-monitor-job.enabled=false",
    "slpa.escrow.timeout-job.enabled=false",
    "slpa.escrow.command-dispatcher-job.enabled=false",
    "slpa.review.scheduler.enabled=false",
    "slpa.notifications.cleanup.enabled=false",
    "slpa.notifications.sl-im.cleanup.enabled=false"
})
class AdminFraudFlagServiceListTest {

    @Autowired AdminFraudFlagService service;
    @Autowired FraudFlagRepository fraudFlagRepo;
    @Autowired AuctionRepository auctionRepo;
    @Autowired ParcelRepository parcelRepo;
    @Autowired UserRepository userRepo;

    private Long userId, parcelId, auctionId;

    @BeforeEach
    void seed() {
        User user = userRepo.save(User.builder()
            .email("seed-" + UUID.randomUUID() + "@x.com")
            .passwordHash("x").role(Role.USER).tokenVersion(1L)
            .slAvatarUuid(UUID.randomUUID()).build());
        userId = user.getId();

        Parcel parcel = parcelRepo.save(Parcel.builder()
            .slParcelUuid(UUID.randomUUID()).regionName("R")
            .ownerUuid(user.getSlAvatarUuid()).areaSqm(1)
            .positionX(BigDecimal.ZERO).positionY(BigDecimal.ZERO).positionZ(BigDecimal.ZERO)
            .build());
        parcelId = parcel.getId();

        Auction auction = auctionRepo.save(Auction.builder()
            .seller(user).parcel(parcel).title("T")
            .status(AuctionStatus.SUSPENDED)
            .verificationTier(VerificationTier.MANUAL)
            .verificationMethod(VerificationMethod.MANUAL_UUID)
            .startingBid(1L).endsAt(OffsetDateTime.now().plusHours(1))
            .build());
        auctionId = auction.getId();

        // 3 open OWNERSHIP, 1 open BOT_PRICE_DRIFT, 1 resolved
        for (int i = 0; i < 3; i++) {
            fraudFlagRepo.save(FraudFlag.builder()
                .auction(auction).parcel(parcel)
                .reason(FraudFlagReason.OWNERSHIP_CHANGED_TO_UNKNOWN)
                .detectedAt(OffsetDateTime.now().minusMinutes(i))
                .resolved(false).evidenceJson(Map.of()).build());
        }
        fraudFlagRepo.save(FraudFlag.builder()
            .auction(auction).parcel(parcel)
            .reason(FraudFlagReason.BOT_PRICE_DRIFT)
            .detectedAt(OffsetDateTime.now())
            .resolved(false).evidenceJson(Map.of()).build());
        fraudFlagRepo.save(FraudFlag.builder()
            .auction(auction).parcel(parcel)
            .reason(FraudFlagReason.PARCEL_DELETED_OR_MERGED)
            .detectedAt(OffsetDateTime.now().minusHours(1))
            .resolved(true)
            .resolvedAt(OffsetDateTime.now())
            .evidenceJson(Map.of()).build());
    }

    @AfterEach
    void cleanup() {
        fraudFlagRepo.deleteAll(fraudFlagRepo.findByAuctionId(auctionId));
        auctionRepo.deleteById(auctionId);
        parcelRepo.deleteById(parcelId);
        userRepo.deleteById(userId);
    }

    @Test
    void list_default_returnsOpenFlags_sortedNewestFirst() {
        PagedResponse<AdminFraudFlagSummaryDto> result =
            service.list("open", List.of(), PageRequest.of(0, 25));
        assertThat(result.content()).hasSize(4);
        assertThat(result.content().get(0).resolved()).isFalse();
        // newest first
        assertThat(result.content().get(0).detectedAt())
            .isAfterOrEqualTo(result.content().get(1).detectedAt());
    }

    @Test
    void list_resolvedOnly_returnsResolvedFlag() {
        PagedResponse<AdminFraudFlagSummaryDto> result =
            service.list("resolved", List.of(), PageRequest.of(0, 25));
        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).resolved()).isTrue();
    }

    @Test
    void list_all_returnsAllFlags() {
        PagedResponse<AdminFraudFlagSummaryDto> result =
            service.list("all", List.of(), PageRequest.of(0, 25));
        assertThat(result.content()).hasSize(5);
    }

    @Test
    void list_filteredByReasons_returnsMatchingOnly() {
        PagedResponse<AdminFraudFlagSummaryDto> result = service.list(
            "open",
            List.of(FraudFlagReason.OWNERSHIP_CHANGED_TO_UNKNOWN),
            PageRequest.of(0, 25));
        assertThat(result.content()).hasSize(3);
        assertThat(result.content())
            .allMatch(d -> d.reason() == FraudFlagReason.OWNERSHIP_CHANGED_TO_UNKNOWN);
    }
}
```

Run: `cd backend && ./mvnw test -Dtest=AdminFraudFlagServiceListTest -q`
Expected: FAIL — `AdminFraudFlagService` doesn't exist yet.

- [ ] **Step 5: Create AdminFraudFlagService skeleton with list + detail**

```java
// backend/src/main/java/com/slparcelauctions/backend/admin/AdminFraudFlagService.java
package com.slparcelauctions.backend.admin;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.admin.dto.AdminFraudFlagDetailDto;
import com.slparcelauctions.backend.admin.dto.AdminFraudFlagDetailDto.AuctionContextDto;
import com.slparcelauctions.backend.admin.dto.AdminFraudFlagDetailDto.LinkedUserDto;
import com.slparcelauctions.backend.admin.dto.AdminFraudFlagSummaryDto;
import com.slparcelauctions.backend.admin.exception.FraudFlagNotFoundException;
import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.fraud.FraudFlag;
import com.slparcelauctions.backend.auction.fraud.FraudFlagReason;
import com.slparcelauctions.backend.auction.fraud.FraudFlagRepository;
import com.slparcelauctions.backend.common.PagedResponse;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AdminFraudFlagService {

    private final FraudFlagRepository fraudFlagRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public PagedResponse<AdminFraudFlagSummaryDto> list(
            String status, List<FraudFlagReason> reasons, Pageable pageable) {

        Specification<FraudFlag> spec = (root, q, cb) -> cb.conjunction();
        if ("open".equalsIgnoreCase(status)) {
            spec = spec.and((root, q, cb) -> cb.isFalse(root.get("resolved")));
        } else if ("resolved".equalsIgnoreCase(status)) {
            spec = spec.and((root, q, cb) -> cb.isTrue(root.get("resolved")));
        }
        if (reasons != null && !reasons.isEmpty()) {
            spec = spec.and((root, q, cb) -> root.get("reason").in(reasons));
        }

        Page<FraudFlag> page = fraudFlagRepository.findAll(spec, pageable);
        return PagedResponse.from(page.map(this::toSummary));
    }

    @Transactional(readOnly = true)
    public AdminFraudFlagDetailDto detail(Long flagId) {
        FraudFlag flag = fraudFlagRepository.findById(flagId)
            .orElseThrow(() -> new FraudFlagNotFoundException(flagId));

        Map<String, Object> evidence = flag.getEvidenceJson() == null
            ? Map.of() : flag.getEvidenceJson();

        Map<String, LinkedUserDto> linked = resolveLinkedUsers(evidence);

        long siblings = flag.getAuction() == null ? 0L
            : fraudFlagRepository.countByAuctionIdAndResolvedFalseAndIdNot(
                flag.getAuction().getId(), flag.getId());

        AuctionContextDto auctionCtx = flag.getAuction() == null ? null
            : new AuctionContextDto(
                flag.getAuction().getId(),
                flag.getAuction().getTitle(),
                flag.getAuction().getStatus(),
                flag.getAuction().getEndsAt(),
                flag.getAuction().getSuspendedAt(),
                flag.getAuction().getSeller().getId(),
                flag.getAuction().getSeller().getDisplayName());

        return new AdminFraudFlagDetailDto(
            flag.getId(),
            flag.getReason(),
            flag.getDetectedAt(),
            flag.getResolvedAt(),
            flag.getResolvedBy() == null ? null : flag.getResolvedBy().getDisplayName(),
            flag.getAdminNotes(),
            auctionCtx,
            evidence,
            linked,
            siblings);
    }

    private Map<String, LinkedUserDto> resolveLinkedUsers(Map<String, Object> evidence) {
        Set<UUID> uuids = new HashSet<>();
        for (Object v : evidence.values()) {
            if (v instanceof String s) {
                try {
                    uuids.add(UUID.fromString(s));
                } catch (IllegalArgumentException ignored) {
                    // not a UUID, skip
                }
            }
        }
        if (uuids.isEmpty()) return Map.of();

        Map<String, LinkedUserDto> out = new HashMap<>();
        List<User> users = userRepository.findAllBySlAvatarUuidIn(uuids);
        for (User u : users) {
            out.put(u.getSlAvatarUuid().toString(),
                new LinkedUserDto(u.getId(), u.getDisplayName()));
        }
        return out;
    }

    private AdminFraudFlagSummaryDto toSummary(FraudFlag flag) {
        Auction auction = flag.getAuction();
        return new AdminFraudFlagSummaryDto(
            flag.getId(),
            flag.getReason(),
            flag.getDetectedAt(),
            auction == null ? null : auction.getId(),
            auction == null ? null : auction.getTitle(),
            auction == null ? null : auction.getStatus(),
            flag.getParcel() == null ? null : flag.getParcel().getRegionName(),
            flag.getParcel() == null ? null : flag.getParcel().getId(),
            flag.isResolved(),
            flag.getResolvedAt(),
            flag.getResolvedBy() == null ? null : flag.getResolvedBy().getDisplayName());
    }
}
```

Note: `FraudFlagNotFoundException` is created in Task 7. For Task 6, use a placeholder line `throw new RuntimeException("not found");` and the implementer fixes the import in Task 7. Actually, since this plan task is self-contained, we **must** create `FraudFlagNotFoundException` here. Add a tiny class:

```java
// backend/src/main/java/com/slparcelauctions/backend/admin/exception/FraudFlagNotFoundException.java
package com.slparcelauctions.backend.admin.exception;

public class FraudFlagNotFoundException extends RuntimeException {
    public FraudFlagNotFoundException(Long flagId) {
        super("FraudFlag not found: " + flagId);
    }
}
```

This is minimal — Task 7 adds two more domain exceptions and the `AdminExceptionHandler`. The 404 mapping for this exception class is added in Task 7.

- [ ] **Step 6: Run service list tests to verify pass**

Run: `cd backend && ./mvnw test -Dtest=AdminFraudFlagServiceListTest -q`
Expected: PASS.

- [ ] **Step 7: Create AdminFraudFlagController list + detail endpoints**

```java
// backend/src/main/java/com/slparcelauctions/backend/admin/AdminFraudFlagController.java
package com.slparcelauctions.backend.admin;

import java.util.Arrays;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.admin.dto.AdminFraudFlagDetailDto;
import com.slparcelauctions.backend.admin.dto.AdminFraudFlagSummaryDto;
import com.slparcelauctions.backend.auction.fraud.FraudFlagReason;
import com.slparcelauctions.backend.common.PagedResponse;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/admin/fraud-flags")
@RequiredArgsConstructor
public class AdminFraudFlagController {

    private static final int MAX_PAGE_SIZE = 100;

    private final AdminFraudFlagService service;

    @GetMapping
    public PagedResponse<AdminFraudFlagSummaryDto> list(
            @RequestParam(defaultValue = "open") String status,
            @RequestParam(required = false) String reasons,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {

        int clampedSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        List<FraudFlagReason> parsedReasons = parseReasons(reasons);
        return service.list(status, parsedReasons,
            PageRequest.of(page, clampedSize, Sort.by(Sort.Direction.DESC, "detectedAt")));
    }

    @GetMapping("/{id}")
    public AdminFraudFlagDetailDto detail(@PathVariable("id") Long id) {
        return service.detail(id);
    }

    private List<FraudFlagReason> parseReasons(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .map(FraudFlagReason::valueOf)
            .toList();
    }
}
```

- [ ] **Step 8: Write controller slice test (auth gating + happy paths)**

```java
// backend/src/test/java/com/slparcelauctions/backend/admin/AdminFraudFlagControllerListSliceTest.java
package com.slparcelauctions.backend.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.slparcelauctions.backend.admin.dto.AdminFraudFlagSummaryDto;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.fraud.FraudFlagReason;
import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.auth.JwtService;
import com.slparcelauctions.backend.common.PagedResponse;
import com.slparcelauctions.backend.user.Role;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = {
    "auth.cleanup.enabled=false",
    "slpa.auction-end.enabled=false",
    "slpa.ownership-monitor.enabled=false",
    "slpa.escrow.ownership-monitor-job.enabled=false",
    "slpa.escrow.timeout-job.enabled=false",
    "slpa.escrow.command-dispatcher-job.enabled=false",
    "slpa.review.scheduler.enabled=false",
    "slpa.notifications.cleanup.enabled=false",
    "slpa.notifications.sl-im.cleanup.enabled=false"
})
class AdminFraudFlagControllerListSliceTest {

    @Autowired MockMvc mvc;
    @Autowired JwtService jwtService;

    @MockitoBean AdminFraudFlagService service;

    private String adminToken() {
        return jwtService.issueAccessToken(new AuthPrincipal(1L, "a@x.com", 1L, Role.ADMIN));
    }

    @Test
    void list_anonymous_returns401() throws Exception {
        mvc.perform(get("/api/v1/admin/fraud-flags"))
           .andExpect(status().isUnauthorized());
    }

    @Test
    void list_userRole_returns403() throws Exception {
        String token = jwtService.issueAccessToken(new AuthPrincipal(2L, "u@x.com", 1L, Role.USER));
        mvc.perform(get("/api/v1/admin/fraud-flags").header("Authorization", "Bearer " + token))
           .andExpect(status().isForbidden());
    }

    @Test
    void list_admin_default_returnsContent() throws Exception {
        AdminFraudFlagSummaryDto row = new AdminFraudFlagSummaryDto(
            42L, FraudFlagReason.OWNERSHIP_CHANGED_TO_UNKNOWN,
            OffsetDateTime.now(), 100L, "Test parcel",
            AuctionStatus.SUSPENDED, "TestRegion", 7L,
            false, null, null);
        when(service.list(eq("open"), anyList(), any()))
            .thenReturn(PagedResponse.<AdminFraudFlagSummaryDto>builder()
                .content(List.of(row)).totalElements(1).totalPages(1)
                .number(0).size(25).build());

        mvc.perform(get("/api/v1/admin/fraud-flags").header("Authorization", "Bearer " + adminToken()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.content[0].id").value(42))
           .andExpect(jsonPath("$.content[0].reason").value("OWNERSHIP_CHANGED_TO_UNKNOWN"))
           .andExpect(jsonPath("$.content[0].auctionStatus").value("SUSPENDED"));
    }

    @Test
    void list_clampsPageSize_to100() throws Exception {
        when(service.list(any(), any(), any()))
            .thenReturn(PagedResponse.<AdminFraudFlagSummaryDto>builder()
                .content(List.of()).totalElements(0).totalPages(0)
                .number(0).size(100).build());

        mvc.perform(get("/api/v1/admin/fraud-flags?size=999")
            .header("Authorization", "Bearer " + adminToken()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.size").value(100));
    }
}
```

Note: `PagedResponse.builder()` may or may not exist depending on the implementation. If it doesn't, use `PagedResponse.from(new PageImpl<>(...))` instead. The implementer should check the existing `PagedResponse` source and use whichever construction style is available.

Run: `cd backend && ./mvnw test -Dtest=AdminFraudFlagControllerListSliceTest -q`
Expected: PASS (after adjusting `PagedResponse` construction if needed).

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/admin/AdminFraudFlagService.java \
        backend/src/main/java/com/slparcelauctions/backend/admin/AdminFraudFlagController.java \
        backend/src/main/java/com/slparcelauctions/backend/admin/dto/AdminFraudFlagSummaryDto.java \
        backend/src/main/java/com/slparcelauctions/backend/admin/dto/AdminFraudFlagDetailDto.java \
        backend/src/main/java/com/slparcelauctions/backend/admin/exception/FraudFlagNotFoundException.java \
        backend/src/main/java/com/slparcelauctions/backend/user/UserRepository.java \
        backend/src/test/java/com/slparcelauctions/backend/admin/AdminFraudFlagServiceListTest.java \
        backend/src/test/java/com/slparcelauctions/backend/admin/AdminFraudFlagControllerListSliceTest.java

git commit -m "$(cat <<'EOF'
feat(admin): fraud-flag list + detail endpoints

GET /api/v1/admin/fraud-flags — Specification-based filter on status
(open/resolved/all) + reasons[]. Sort detectedAt DESC, page-size clamp 100.
GET /api/v1/admin/fraud-flags/{id} — full detail with batched
linkedUsers (single findAllBySlAvatarUuidIn) and sibling-open-flag
count for the slide-over banner.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: Fraud-flag dismiss + reinstate (full flow with notification + bot re-engage)

**Goal:** Ship the two write endpoints — `POST /api/v1/admin/fraud-flags/{id}/dismiss` and `.../reinstate`. Includes domain exceptions, exception handler with `code` discriminator, `BotMonitorLifecycleService.onAuctionResumed`, `LISTING_REINSTATED` notification category and publisher method, and full flow integration test.

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/admin/exception/FraudFlagAlreadyResolvedException.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/admin/exception/AuctionNotSuspendedException.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/admin/exception/AdminApiError.java` (record)
- Create: `backend/src/main/java/com/slparcelauctions/backend/admin/exception/AdminExceptionHandler.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/admin/dto/ResolveFraudFlagRequest.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/admin/AdminFraudFlagService.java` (add `dismiss`, `reinstate`)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/admin/AdminFraudFlagController.java` (add `dismiss`, `reinstate`)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/bot/BotMonitorLifecycleService.java` (add `onAuctionResumed`)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/notification/NotificationCategory.java` (add `LISTING_REINSTATED`)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/notification/NotificationPublisher.java` (add `listingReinstated`)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/notification/NotificationPublisherImpl.java` (implement)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/notification/NotificationDataBuilder.java` (add `listingReinstated`)
- Test: `backend/src/test/java/com/slparcelauctions/backend/bot/BotMonitorLifecycleServiceResumedTest.java` (new — `@SpringBootTest`)
- Test: `backend/src/test/java/com/slparcelauctions/backend/admin/AdminFraudFlagServiceDismissReinstateTest.java` (new — `@SpringBootTest`)
- Test: `backend/src/test/java/com/slparcelauctions/backend/admin/AdminFraudFlagControllerWriteSliceTest.java` (new — slice)
- Test: `backend/src/test/java/com/slparcelauctions/backend/admin/AdminFraudFlagReinstateIntegrationTest.java` (new — full flow)

- [ ] **Step 1: Create domain exceptions**

```java
// FraudFlagAlreadyResolvedException.java
package com.slparcelauctions.backend.admin.exception;

import lombok.Getter;

@Getter
public class FraudFlagAlreadyResolvedException extends RuntimeException {
    private final Long flagId;
    public FraudFlagAlreadyResolvedException(Long flagId) {
        super("FraudFlag " + flagId + " already resolved");
        this.flagId = flagId;
    }
}
```

```java
// AuctionNotSuspendedException.java
package com.slparcelauctions.backend.admin.exception;

import com.slparcelauctions.backend.auction.AuctionStatus;
import lombok.Getter;

@Getter
public class AuctionNotSuspendedException extends RuntimeException {
    private final AuctionStatus currentStatus;
    public AuctionNotSuspendedException(AuctionStatus currentStatus) {
        super("Auction is currently " + (currentStatus == null ? "null" : currentStatus.name())
              + ", cannot be reinstated");
        this.currentStatus = currentStatus;
    }
}
```

- [ ] **Step 2: Create AdminApiError DTO**

```java
// backend/src/main/java/com/slparcelauctions/backend/admin/exception/AdminApiError.java
package com.slparcelauctions.backend.admin.exception;

import java.util.Map;

public record AdminApiError(
    String code,
    String message,
    Map<String, Object> details
) {
    public static AdminApiError of(String code, String message) {
        return new AdminApiError(code, message, Map.of());
    }
    public static AdminApiError of(String code, String message, Map<String, Object> details) {
        return new AdminApiError(code, message, details);
    }
}
```

- [ ] **Step 3: Create AdminExceptionHandler**

```java
// backend/src/main/java/com/slparcelauctions/backend/admin/exception/AdminExceptionHandler.java
package com.slparcelauctions.backend.admin.exception;

import java.util.Map;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "com.slparcelauctions.backend.admin")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AdminExceptionHandler {

    @ExceptionHandler(FraudFlagNotFoundException.class)
    public ResponseEntity<AdminApiError> handleNotFound(FraudFlagNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(AdminApiError.of("FLAG_NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(FraudFlagAlreadyResolvedException.class)
    public ResponseEntity<AdminApiError> handleAlreadyResolved(FraudFlagAlreadyResolvedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(AdminApiError.of("ALREADY_RESOLVED", ex.getMessage()));
    }

    @ExceptionHandler(AuctionNotSuspendedException.class)
    public ResponseEntity<AdminApiError> handleNotSuspended(AuctionNotSuspendedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(AdminApiError.of(
                "AUCTION_NOT_SUSPENDED", ex.getMessage(),
                Map.of("currentStatus",
                    ex.getCurrentStatus() == null ? "null" : ex.getCurrentStatus().name())));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<AdminApiError> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
            .map(f -> f.getField() + ": " + f.getDefaultMessage())
            .findFirst().orElse("Invalid request");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(AdminApiError.of("VALIDATION_FAILED", message));
    }
}
```

- [ ] **Step 4: Create ResolveFraudFlagRequest DTO**

```java
// backend/src/main/java/com/slparcelauctions/backend/admin/dto/ResolveFraudFlagRequest.java
package com.slparcelauctions.backend.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResolveFraudFlagRequest(
    @NotBlank
    @Size(max = 1000)
    String adminNotes
) {}
```

- [ ] **Step 5: Add LISTING_REINSTATED notification category**

In `NotificationCategory.java`, add the value adjacent to `LISTING_SUSPENDED` (look at the existing constructor — likely takes a `NotificationGroup` arg):

```java
LISTING_REINSTATED(NotificationGroup.LISTING_STATUS),
```

Place it right after `LISTING_SUSPENDED` for diff readability.

- [ ] **Step 6: Add listingReinstated to NotificationPublisher interface**

```java
// in NotificationPublisher.java
import java.time.OffsetDateTime;

void listingReinstated(long sellerUserId, long auctionId, String parcelName, OffsetDateTime newEndsAt);
```

- [ ] **Step 7: Implement listingReinstated in NotificationPublisherImpl**

Locate the `listingSuspended` impl at lines 252-261. Add the mirror method directly below:

```java
@Override
public void listingReinstated(long sellerUserId, long auctionId, String parcelName, OffsetDateTime newEndsAt) {
    String title = "Listing reinstated: " + parcelName;
    String body = "Your auction has been reinstated. All existing bids and proxy maxes are preserved. "
        + "Ends " + newEndsAt + ".";
    notificationService.publish(new NotificationEvent(
        sellerUserId, NotificationCategory.LISTING_REINSTATED, title, body,
        NotificationDataBuilder.listingReinstated(auctionId, parcelName, newEndsAt),
        null
    ));
}
```

- [ ] **Step 8: Add listingReinstated to NotificationDataBuilder**

```java
// in NotificationDataBuilder.java, near listingSuspended
public static Map<String, Object> listingReinstated(long auctionId, String parcelName, OffsetDateTime newEndsAt) {
    Map<String, Object> m = base(auctionId, parcelName);
    m.put("newEndsAt", newEndsAt == null ? null : newEndsAt.toString());
    return m;
}
```

- [ ] **Step 9: Write failing test for BotMonitorLifecycleService.onAuctionResumed**

```java
// backend/src/test/java/com/slparcelauctions/backend/bot/BotMonitorLifecycleServiceResumedTest.java
package com.slparcelauctions.backend.bot;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.VerificationMethod;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.parcel.ParcelRepository;
import com.slparcelauctions.backend.user.Role;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
    "auth.cleanup.enabled=false",
    "slpa.auction-end.enabled=false",
    "slpa.ownership-monitor.enabled=false",
    "slpa.escrow.ownership-monitor-job.enabled=false",
    "slpa.escrow.timeout-job.enabled=false",
    "slpa.escrow.command-dispatcher-job.enabled=false",
    "slpa.review.scheduler.enabled=false",
    "slpa.notifications.cleanup.enabled=false",
    "slpa.notifications.sl-im.cleanup.enabled=false"
})
class BotMonitorLifecycleServiceResumedTest {

    @Autowired BotMonitorLifecycleService lifecycle;
    @Autowired BotTaskRepository botTaskRepo;
    @Autowired AuctionRepository auctionRepo;
    @Autowired ParcelRepository parcelRepo;
    @Autowired UserRepository userRepo;

    private Long userId, parcelId;

    @BeforeEach
    void seed() {
        User user = userRepo.save(User.builder()
            .email("seed-" + UUID.randomUUID() + "@x.com")
            .passwordHash("x").role(Role.USER).tokenVersion(1L)
            .slAvatarUuid(UUID.randomUUID()).build());
        userId = user.getId();
        Parcel parcel = parcelRepo.save(Parcel.builder()
            .slParcelUuid(UUID.randomUUID()).regionName("R")
            .ownerUuid(user.getSlAvatarUuid()).areaSqm(1)
            .positionX(BigDecimal.ZERO).positionY(BigDecimal.ZERO).positionZ(BigDecimal.ZERO)
            .build());
        parcelId = parcel.getId();
    }

    @AfterEach
    void cleanup() {
        botTaskRepo.deleteAll();
        auctionRepo.deleteAll();
        parcelRepo.deleteById(parcelId);
        userRepo.deleteById(userId);
    }

    @Test
    void onAuctionResumed_botTier_createsFreshMonitorAuctionRow() {
        User seller = userRepo.findById(userId).orElseThrow();
        Parcel parcel = parcelRepo.findById(parcelId).orElseThrow();
        Auction auction = auctionRepo.save(Auction.builder()
            .seller(seller).parcel(parcel).title("BOT auction")
            .status(AuctionStatus.ACTIVE)
            .verificationTier(VerificationTier.BOT)
            .verificationMethod(VerificationMethod.SALE_TO_BOT)
            .startingBid(1L).endsAt(OffsetDateTime.now().plusHours(1))
            .build());

        lifecycle.onAuctionResumed(auction);

        assertThat(botTaskRepo.findAll())
            .anyMatch(t -> t.getTaskType() == BotTaskType.MONITOR_AUCTION
                       && t.getAuction().getId().equals(auction.getId()));
    }

    @Test
    void onAuctionResumed_manualTier_isNoop() {
        User seller = userRepo.findById(userId).orElseThrow();
        Parcel parcel = parcelRepo.findById(parcelId).orElseThrow();
        Auction auction = auctionRepo.save(Auction.builder()
            .seller(seller).parcel(parcel).title("Manual auction")
            .status(AuctionStatus.ACTIVE)
            .verificationTier(VerificationTier.MANUAL)
            .verificationMethod(VerificationMethod.MANUAL_UUID)
            .startingBid(1L).endsAt(OffsetDateTime.now().plusHours(1))
            .build());

        lifecycle.onAuctionResumed(auction);

        assertThat(botTaskRepo.findAll())
            .noneMatch(t -> t.getAuction() != null
                         && t.getAuction().getId().equals(auction.getId()));
    }
}
```

Run: `cd backend && ./mvnw test -Dtest=BotMonitorLifecycleServiceResumedTest -q`
Expected: FAIL — `onAuctionResumed` doesn't exist.

- [ ] **Step 10: Add onAuctionResumed to BotMonitorLifecycleService**

In `BotMonitorLifecycleService.java`, add the public method that delegates to `onAuctionActivatedBot` (same body for now — both spawn a fresh MONITOR_AUCTION row):

```java
/**
 * Re-engage MONITOR_AUCTION on a previously-suspended BOT-tier auction
 * after admin reinstate. Mirrors {@link #onAuctionActivatedBot} since the
 * spawn shape is identical; kept as a separate entry point so the call
 * site reads correctly and the two paths can diverge later (e.g. if
 * reinstate ever wants different recurrence). No-op for non-BOT tiers.
 */
@Transactional
public void onAuctionResumed(Auction auction) {
    onAuctionActivatedBot(auction);
}
```

Run: `cd backend && ./mvnw test -Dtest=BotMonitorLifecycleServiceResumedTest -q`
Expected: PASS.

- [ ] **Step 11: Write failing service test for dismiss happy path**

```java
// backend/src/test/java/com/slparcelauctions/backend/admin/AdminFraudFlagServiceDismissReinstateTest.java
package com.slparcelauctions.backend.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.slparcelauctions.backend.admin.dto.AdminFraudFlagDetailDto;
import com.slparcelauctions.backend.admin.exception.AuctionNotSuspendedException;
import com.slparcelauctions.backend.admin.exception.FraudFlagAlreadyResolvedException;
import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.VerificationMethod;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.auction.fraud.FraudFlag;
import com.slparcelauctions.backend.auction.fraud.FraudFlagReason;
import com.slparcelauctions.backend.auction.fraud.FraudFlagRepository;
import com.slparcelauctions.backend.notification.NotificationWsBroadcasterPort;
import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.parcel.ParcelRepository;
import com.slparcelauctions.backend.user.Role;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
    "auth.cleanup.enabled=false",
    "slpa.auction-end.enabled=false",
    "slpa.ownership-monitor.enabled=false",
    "slpa.escrow.ownership-monitor-job.enabled=false",
    "slpa.escrow.timeout-job.enabled=false",
    "slpa.escrow.command-dispatcher-job.enabled=false",
    "slpa.review.scheduler.enabled=false",
    "slpa.notifications.cleanup.enabled=false",
    "slpa.notifications.sl-im.cleanup.enabled=false"
})
class AdminFraudFlagServiceDismissReinstateTest {

    @Autowired AdminFraudFlagService service;
    @Autowired FraudFlagRepository fraudFlagRepo;
    @Autowired AuctionRepository auctionRepo;
    @Autowired ParcelRepository parcelRepo;
    @Autowired UserRepository userRepo;

    @MockitoBean NotificationWsBroadcasterPort wsBroadcaster;

    private Long userId, parcelId, auctionId, flagId, adminId;

    @BeforeEach
    void seed() {
        User seller = userRepo.save(User.builder()
            .email("seller-" + UUID.randomUUID() + "@x.com")
            .passwordHash("x").role(Role.USER).tokenVersion(1L)
            .slAvatarUuid(UUID.randomUUID()).build());
        userId = seller.getId();

        User admin = userRepo.save(User.builder()
            .email("admin-" + UUID.randomUUID() + "@x.com")
            .passwordHash("x").role(Role.ADMIN).tokenVersion(1L)
            .displayName("Admin Person").build());
        adminId = admin.getId();

        Parcel parcel = parcelRepo.save(Parcel.builder()
            .slParcelUuid(UUID.randomUUID()).regionName("R")
            .ownerUuid(seller.getSlAvatarUuid()).areaSqm(1024)
            .positionX(BigDecimal.ZERO).positionY(BigDecimal.ZERO).positionZ(BigDecimal.ZERO)
            .build());
        parcelId = parcel.getId();

        Auction auction = auctionRepo.save(Auction.builder()
            .seller(seller).parcel(parcel).title("Test")
            .status(AuctionStatus.SUSPENDED)
            .suspendedAt(OffsetDateTime.now().minusHours(6))
            .verificationTier(VerificationTier.MANUAL)
            .verificationMethod(VerificationMethod.MANUAL_UUID)
            .startingBid(1L).endsAt(OffsetDateTime.now().plusHours(2))
            .build());
        auctionId = auction.getId();

        FraudFlag flag = fraudFlagRepo.save(FraudFlag.builder()
            .auction(auction).parcel(parcel)
            .reason(FraudFlagReason.OWNERSHIP_CHANGED_TO_UNKNOWN)
            .detectedAt(OffsetDateTime.now().minusHours(6))
            .resolved(false).evidenceJson(Map.of()).build());
        flagId = flag.getId();
    }

    @AfterEach
    void cleanup() {
        fraudFlagRepo.deleteAll(fraudFlagRepo.findByAuctionId(auctionId));
        auctionRepo.deleteById(auctionId);
        parcelRepo.deleteById(parcelId);
        userRepo.deleteById(userId);
        userRepo.deleteById(adminId);
    }

    @Test
    void dismiss_marksResolvedWithNotes_doesNotChangeAuctionStatus() {
        AdminFraudFlagDetailDto result = service.dismiss(flagId, adminId, "False positive — verified.");

        assertThat(result.adminNotes()).isEqualTo("False positive — verified.");
        assertThat(result.resolvedAt()).isNotNull();
        Auction reloaded = auctionRepo.findById(auctionId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AuctionStatus.SUSPENDED);
        assertThat(reloaded.getSuspendedAt()).isNotNull();
    }

    @Test
    void dismiss_alreadyResolved_throws() {
        service.dismiss(flagId, adminId, "first");
        assertThatThrownBy(() -> service.dismiss(flagId, adminId, "second"))
            .isInstanceOf(FraudFlagAlreadyResolvedException.class);
    }

    @Test
    void reinstate_flipsToActive_extendsEndsAt_clearsSuspendedAt_marksResolved() {
        Auction before = auctionRepo.findById(auctionId).orElseThrow();
        OffsetDateTime originalEndsAt = before.getEndsAt();
        OffsetDateTime suspendedAt = before.getSuspendedAt();
        long suspensionSeconds = ChronoUnit.SECONDS.between(suspendedAt, OffsetDateTime.now());

        service.reinstate(flagId, adminId, "Verified ok in-world.");

        Auction after = auctionRepo.findById(auctionId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(AuctionStatus.ACTIVE);
        assertThat(after.getSuspendedAt()).isNull();
        long extension = ChronoUnit.SECONDS.between(originalEndsAt, after.getEndsAt());
        // Allow a small epsilon for clock drift between read and reinstate.
        assertThat(extension).isBetween(suspensionSeconds - 5, suspensionSeconds + 5);

        FraudFlag flag = fraudFlagRepo.findById(flagId).orElseThrow();
        assertThat(flag.isResolved()).isTrue();
        assertThat(flag.getAdminNotes()).isEqualTo("Verified ok in-world.");
    }

    @Test
    void reinstate_auctionNotSuspended_throws() {
        Auction auction = auctionRepo.findById(auctionId).orElseThrow();
        auction.setStatus(AuctionStatus.CANCELLED);
        auctionRepo.save(auction);

        assertThatThrownBy(() -> service.reinstate(flagId, adminId, "x"))
            .isInstanceOf(AuctionNotSuspendedException.class);
    }

    @Test
    void reinstate_fallbackToFlagDetectedAt_whenSuspendedAtIsNull() {
        Auction auction = auctionRepo.findById(auctionId).orElseThrow();
        OffsetDateTime originalEndsAt = auction.getEndsAt();
        auction.setSuspendedAt(null);
        auctionRepo.save(auction);

        FraudFlag flag = fraudFlagRepo.findById(flagId).orElseThrow();
        long suspensionSeconds = ChronoUnit.SECONDS.between(flag.getDetectedAt(), OffsetDateTime.now());

        service.reinstate(flagId, adminId, "Backfill case");

        Auction after = auctionRepo.findById(auctionId).orElseThrow();
        long extension = ChronoUnit.SECONDS.between(originalEndsAt, after.getEndsAt());
        assertThat(extension).isBetween(suspensionSeconds - 5, suspensionSeconds + 5);
    }
}
```

Run: `cd backend && ./mvnw test -Dtest=AdminFraudFlagServiceDismissReinstateTest -q`
Expected: FAIL — `dismiss` and `reinstate` don't exist on the service.

- [ ] **Step 12: Add dismiss and reinstate methods to AdminFraudFlagService**

Append to `AdminFraudFlagService.java`. New imports:

```java
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;

import com.slparcelauctions.backend.admin.exception.AuctionNotSuspendedException;
import com.slparcelauctions.backend.admin.exception.FraudFlagAlreadyResolvedException;
import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.fraud.FraudFlag;
import com.slparcelauctions.backend.bot.BotMonitorLifecycleService;
import com.slparcelauctions.backend.notification.NotificationPublisher;
```

Update the constructor (Lombok `@RequiredArgsConstructor` will pick up new final fields):

```java
private final AuctionRepository auctionRepository;
private final BotMonitorLifecycleService botMonitorLifecycleService;
private final NotificationPublisher notificationPublisher;
private final Clock clock;
```

Add the methods:

```java
@Transactional
public AdminFraudFlagDetailDto dismiss(Long flagId, Long adminUserId, String adminNotes) {
    FraudFlag flag = fraudFlagRepository.findById(flagId)
        .orElseThrow(() -> new FraudFlagNotFoundException(flagId));
    if (flag.isResolved()) {
        throw new FraudFlagAlreadyResolvedException(flagId);
    }
    User admin = userRepository.findById(adminUserId)
        .orElseThrow(() -> new IllegalStateException("Admin user not found: " + adminUserId));
    flag.setResolved(true);
    flag.setResolvedAt(OffsetDateTime.now(clock));
    flag.setResolvedBy(admin);
    flag.setAdminNotes(adminNotes);
    fraudFlagRepository.save(flag);
    return detail(flagId);
}

@Transactional
public AdminFraudFlagDetailDto reinstate(Long flagId, Long adminUserId, String adminNotes) {
    FraudFlag flag = fraudFlagRepository.findById(flagId)
        .orElseThrow(() -> new FraudFlagNotFoundException(flagId));
    if (flag.isResolved()) {
        throw new FraudFlagAlreadyResolvedException(flagId);
    }
    Auction auction = flag.getAuction();
    if (auction == null || auction.getStatus() != AuctionStatus.SUSPENDED) {
        throw new AuctionNotSuspendedException(auction == null ? null : auction.getStatus());
    }

    OffsetDateTime now = OffsetDateTime.now(clock);
    OffsetDateTime suspendedFrom = auction.getSuspendedAt() != null
        ? auction.getSuspendedAt()
        : flag.getDetectedAt();
    Duration suspensionDuration = Duration.between(suspendedFrom, now);
    OffsetDateTime newEndsAt = auction.getEndsAt().plus(suspensionDuration);
    if (newEndsAt.isBefore(now)) {
        newEndsAt = now.plusHours(1);
    }

    auction.setStatus(AuctionStatus.ACTIVE);
    auction.setSuspendedAt(null);
    auction.setEndsAt(newEndsAt);
    auctionRepository.save(auction);

    botMonitorLifecycleService.onAuctionResumed(auction);

    User admin = userRepository.findById(adminUserId)
        .orElseThrow(() -> new IllegalStateException("Admin user not found: " + adminUserId));
    flag.setResolved(true);
    flag.setResolvedAt(now);
    flag.setResolvedBy(admin);
    flag.setAdminNotes(adminNotes);
    fraudFlagRepository.save(flag);

    notificationPublisher.listingReinstated(
        auction.getSeller().getId(), auction.getId(),
        auction.getTitle(), newEndsAt);

    return detail(flagId);
}
```

Note: User import may need adding: `import com.slparcelauctions.backend.user.User;`.

Run: `cd backend && ./mvnw test -Dtest=AdminFraudFlagServiceDismissReinstateTest -q`
Expected: PASS.

- [ ] **Step 13: Add dismiss + reinstate endpoints to AdminFraudFlagController**

Add to `AdminFraudFlagController.java`. New imports:

```java
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import com.slparcelauctions.backend.admin.dto.AdminFraudFlagDetailDto;
import com.slparcelauctions.backend.admin.dto.ResolveFraudFlagRequest;
import com.slparcelauctions.backend.auth.AuthPrincipal;
```

New methods:

```java
@PostMapping("/{id}/dismiss")
public AdminFraudFlagDetailDto dismiss(
        @PathVariable("id") Long id,
        @Valid @RequestBody ResolveFraudFlagRequest body,
        @AuthenticationPrincipal AuthPrincipal admin) {
    return service.dismiss(id, admin.userId(), body.adminNotes());
}

@PostMapping("/{id}/reinstate")
public AdminFraudFlagDetailDto reinstate(
        @PathVariable("id") Long id,
        @Valid @RequestBody ResolveFraudFlagRequest body,
        @AuthenticationPrincipal AuthPrincipal admin) {
    return service.reinstate(id, admin.userId(), body.adminNotes());
}
```

- [ ] **Step 14: Write controller slice test for write endpoints**

```java
// backend/src/test/java/com/slparcelauctions/backend/admin/AdminFraudFlagControllerWriteSliceTest.java
package com.slparcelauctions.backend.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.slparcelauctions.backend.admin.dto.AdminFraudFlagDetailDto;
import com.slparcelauctions.backend.admin.exception.AuctionNotSuspendedException;
import com.slparcelauctions.backend.admin.exception.FraudFlagAlreadyResolvedException;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.fraud.FraudFlagReason;
import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.auth.JwtService;
import com.slparcelauctions.backend.user.Role;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = {
    "auth.cleanup.enabled=false",
    "slpa.auction-end.enabled=false",
    "slpa.ownership-monitor.enabled=false",
    "slpa.escrow.ownership-monitor-job.enabled=false",
    "slpa.escrow.timeout-job.enabled=false",
    "slpa.escrow.command-dispatcher-job.enabled=false",
    "slpa.review.scheduler.enabled=false",
    "slpa.notifications.cleanup.enabled=false",
    "slpa.notifications.sl-im.cleanup.enabled=false"
})
class AdminFraudFlagControllerWriteSliceTest {

    @Autowired MockMvc mvc;
    @Autowired JwtService jwtService;

    @MockitoBean AdminFraudFlagService service;

    private String adminToken() {
        return jwtService.issueAccessToken(new AuthPrincipal(99L, "a@x.com", 1L, Role.ADMIN));
    }

    @Test
    void dismiss_emptyNotes_returns400_VALIDATION_FAILED() throws Exception {
        mvc.perform(post("/api/v1/admin/fraud-flags/1/dismiss")
            .header("Authorization", "Bearer " + adminToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"adminNotes\":\"\"}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void dismiss_alreadyResolved_returns409_ALREADY_RESOLVED() throws Exception {
        when(service.dismiss(eq(1L), anyLong(), anyString()))
            .thenThrow(new FraudFlagAlreadyResolvedException(1L));

        mvc.perform(post("/api/v1/admin/fraud-flags/1/dismiss")
            .header("Authorization", "Bearer " + adminToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"adminNotes\":\"x\"}"))
           .andExpect(status().isConflict())
           .andExpect(jsonPath("$.code").value("ALREADY_RESOLVED"));
    }

    @Test
    void reinstate_auctionNotSuspended_returns409_AUCTION_NOT_SUSPENDED_withCurrentStatus() throws Exception {
        when(service.reinstate(eq(1L), anyLong(), anyString()))
            .thenThrow(new AuctionNotSuspendedException(AuctionStatus.CANCELLED));

        mvc.perform(post("/api/v1/admin/fraud-flags/1/reinstate")
            .header("Authorization", "Bearer " + adminToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"adminNotes\":\"x\"}"))
           .andExpect(status().isConflict())
           .andExpect(jsonPath("$.code").value("AUCTION_NOT_SUSPENDED"))
           .andExpect(jsonPath("$.details.currentStatus").value("CANCELLED"));
    }

    @Test
    void reinstate_admin_returns200() throws Exception {
        when(service.reinstate(eq(1L), anyLong(), anyString())).thenReturn(
            new AdminFraudFlagDetailDto(
                1L, FraudFlagReason.OWNERSHIP_CHANGED_TO_UNKNOWN,
                OffsetDateTime.now(), OffsetDateTime.now(), "Admin Person",
                "Verified ok", null, null, null, 0L));

        mvc.perform(post("/api/v1/admin/fraud-flags/1/reinstate")
            .header("Authorization", "Bearer " + adminToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"adminNotes\":\"Verified ok\"}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.id").value(1));
    }
}
```

Run: `cd backend && ./mvnw test -Dtest=AdminFraudFlagControllerWriteSliceTest -q`
Expected: PASS.

- [ ] **Step 15: Write full-flow integration test for reinstate**

```java
// backend/src/test/java/com/slparcelauctions/backend/admin/AdminFraudFlagReinstateIntegrationTest.java
package com.slparcelauctions.backend.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.VerificationMethod;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.auction.fraud.FraudFlag;
import com.slparcelauctions.backend.auction.fraud.FraudFlagReason;
import com.slparcelauctions.backend.auction.fraud.FraudFlagRepository;
import com.slparcelauctions.backend.bot.BotTaskRepository;
import com.slparcelauctions.backend.bot.BotTaskType;
import com.slparcelauctions.backend.notification.NotificationCategory;
import com.slparcelauctions.backend.notification.NotificationRepository;
import com.slparcelauctions.backend.notification.NotificationWsBroadcasterPort;
import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.parcel.ParcelRepository;
import com.slparcelauctions.backend.user.Role;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
    "auth.cleanup.enabled=false",
    "slpa.auction-end.enabled=false",
    "slpa.ownership-monitor.enabled=false",
    "slpa.escrow.ownership-monitor-job.enabled=false",
    "slpa.escrow.timeout-job.enabled=false",
    "slpa.escrow.command-dispatcher-job.enabled=false",
    "slpa.review.scheduler.enabled=false",
    "slpa.notifications.cleanup.enabled=false",
    "slpa.notifications.sl-im.cleanup.enabled=false"
})
class AdminFraudFlagReinstateIntegrationTest {

    @Autowired AdminFraudFlagService service;
    @Autowired AuctionRepository auctionRepo;
    @Autowired ParcelRepository parcelRepo;
    @Autowired UserRepository userRepo;
    @Autowired FraudFlagRepository fraudFlagRepo;
    @Autowired BotTaskRepository botTaskRepo;
    @Autowired NotificationRepository notificationRepo;

    @MockitoBean NotificationWsBroadcasterPort wsBroadcaster;

    private Long sellerId, adminId, parcelId, auctionId, flagId;

    @BeforeEach
    void seed() {
        User seller = userRepo.save(User.builder()
            .email("seller-" + UUID.randomUUID() + "@x.com")
            .passwordHash("x").role(Role.USER).tokenVersion(1L)
            .slAvatarUuid(UUID.randomUUID()).build());
        sellerId = seller.getId();

        User admin = userRepo.save(User.builder()
            .email("admin-" + UUID.randomUUID() + "@x.com")
            .passwordHash("x").role(Role.ADMIN).tokenVersion(1L)
            .displayName("Admin Person").build());
        adminId = admin.getId();

        Parcel parcel = parcelRepo.save(Parcel.builder()
            .slParcelUuid(UUID.randomUUID()).regionName("R")
            .ownerUuid(seller.getSlAvatarUuid()).areaSqm(1024)
            .positionX(BigDecimal.ZERO).positionY(BigDecimal.ZERO).positionZ(BigDecimal.ZERO)
            .build());
        parcelId = parcel.getId();

        Auction auction = auctionRepo.save(Auction.builder()
            .seller(seller).parcel(parcel).title("Bot auction")
            .status(AuctionStatus.SUSPENDED)
            .suspendedAt(OffsetDateTime.now().minusHours(6))
            .verificationTier(VerificationTier.BOT)
            .verificationMethod(VerificationMethod.SALE_TO_BOT)
            .startingBid(1L).endsAt(OffsetDateTime.now().plusHours(2))
            .build());
        auctionId = auction.getId();

        FraudFlag flag = fraudFlagRepo.save(FraudFlag.builder()
            .auction(auction).parcel(parcel)
            .reason(FraudFlagReason.OWNERSHIP_CHANGED_TO_UNKNOWN)
            .detectedAt(OffsetDateTime.now().minusHours(6))
            .resolved(false).evidenceJson(Map.of()).build());
        flagId = flag.getId();
    }

    @AfterEach
    void cleanup() {
        botTaskRepo.deleteAll();
        notificationRepo.deleteAll();
        fraudFlagRepo.deleteAll(fraudFlagRepo.findByAuctionId(auctionId));
        auctionRepo.deleteById(auctionId);
        parcelRepo.deleteById(parcelId);
        userRepo.deleteById(sellerId);
        userRepo.deleteById(adminId);
    }

    @Test
    void reinstate_botTier_fullFlow_flipsActive_extendsEndsAt_clearsSuspendedAt_spawnsMonitor_publishesNotification() {
        service.reinstate(flagId, adminId, "Verified ok in-world.");

        Auction after = auctionRepo.findById(auctionId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(AuctionStatus.ACTIVE);
        assertThat(after.getSuspendedAt()).isNull();

        assertThat(botTaskRepo.findAll())
            .anyMatch(t -> t.getTaskType() == BotTaskType.MONITOR_AUCTION
                       && t.getAuction().getId().equals(auctionId));

        assertThat(notificationRepo.findAll())
            .anyMatch(n -> n.getCategory() == NotificationCategory.LISTING_REINSTATED
                        && n.getUserId().equals(sellerId));
    }
}
```

Run: `cd backend && ./mvnw test -Dtest=AdminFraudFlagReinstateIntegrationTest -q`
Expected: PASS.

- [ ] **Step 16: Run full backend test suite to catch regressions**

Run: `cd backend && ./mvnw test -q`
Expected: PASS.

- [ ] **Step 17: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/admin/exception/ \
        backend/src/main/java/com/slparcelauctions/backend/admin/dto/ResolveFraudFlagRequest.java \
        backend/src/main/java/com/slparcelauctions/backend/admin/AdminFraudFlagService.java \
        backend/src/main/java/com/slparcelauctions/backend/admin/AdminFraudFlagController.java \
        backend/src/main/java/com/slparcelauctions/backend/bot/BotMonitorLifecycleService.java \
        backend/src/main/java/com/slparcelauctions/backend/notification/NotificationCategory.java \
        backend/src/main/java/com/slparcelauctions/backend/notification/NotificationPublisher.java \
        backend/src/main/java/com/slparcelauctions/backend/notification/NotificationPublisherImpl.java \
        backend/src/main/java/com/slparcelauctions/backend/notification/NotificationDataBuilder.java \
        backend/src/test/java/com/slparcelauctions/backend/bot/BotMonitorLifecycleServiceResumedTest.java \
        backend/src/test/java/com/slparcelauctions/backend/admin/AdminFraudFlagServiceDismissReinstateTest.java \
        backend/src/test/java/com/slparcelauctions/backend/admin/AdminFraudFlagControllerWriteSliceTest.java \
        backend/src/test/java/com/slparcelauctions/backend/admin/AdminFraudFlagReinstateIntegrationTest.java

git commit -m "$(cat <<'EOF'
feat(admin): fraud-flag dismiss + reinstate (full flow)

Dismiss marks resolved with notes, no state change.
Reinstate flips SUSPENDED→ACTIVE, extends endsAt by suspension duration
(suspendedAt with flag.detectedAt fallback), clears suspendedAt,
re-engages bot monitor for BOT-tier auctions, publishes
LISTING_REINSTATED notification to seller. Domain exceptions return
409 with code discriminator (ALREADY_RESOLVED, AUCTION_NOT_SUSPENDED)
via AdminExceptionHandler.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 8: Frontend foundations (role, RequireAdmin, nav, types, api, MSW)

**Goal:** Add `role` to the frontend `AuthUser` type, ship `RequireAdmin`, add the admin nav links to Header/MobileMenu/UserMenuDropdown, scaffold `lib/admin/*` (api, queryKeys, types, reasonStyle), and wire MSW so admin handlers can override defaults in tests.

**Files:**
- Modify: `frontend/src/lib/auth/session.ts` (add `role: "USER" | "ADMIN"`)
- Modify: `frontend/src/components/layout/Header.tsx` (admin nav link)
- Modify: `frontend/src/components/layout/MobileMenu.tsx` (admin nav link)
- Modify: `frontend/src/components/auth/UserMenuDropdown.tsx` (admin menu item)
- Create: `frontend/src/components/auth/RequireAdmin.tsx`
- Create: `frontend/src/components/auth/RequireAdmin.test.tsx`
- Create: `frontend/src/lib/admin/types.ts`
- Create: `frontend/src/lib/admin/api.ts`
- Create: `frontend/src/lib/admin/queryKeys.ts`
- Create: `frontend/src/lib/admin/reasonStyle.ts`
- Modify: `frontend/src/test/msw/handlers.ts` (admin handlers + role default on AuthUser fixtures)

- [ ] **Step 1: Add role to AuthUser**

In `frontend/src/lib/auth/session.ts`, update the `AuthUser` type:

```ts
export type AuthUser = {
  id: number;
  email: string;
  displayName: string | null;
  slAvatarUuid: string | null;
  verified: boolean;
  role: "USER" | "ADMIN";
};
```

Run: `cd frontend && npx tsc --noEmit`
Expected: a handful of TS errors at construction sites (test fixtures, MSW handlers). The next steps fix them.

- [ ] **Step 2: Update MSW auth handler default user with role: "USER"**

In `frontend/src/test/msw/handlers.ts`, find the default `AuthUser` factory inside `authHandlers` (likely a function called `loginSuccess()` or similar that constructs a `user` object). Add `role: "USER"` to the default. Add a new helper for admin tests:

```ts
function adminUser(): AuthUser {
  return {
    id: 1,
    email: "admin@bootstrap.test",
    displayName: "Admin Person",
    slAvatarUuid: null,
    verified: true,
    role: "ADMIN",
  };
}
```

If `authHandlers` already has a `withUser(user)` style override pattern, mirror it for the admin user.

- [ ] **Step 3: Create RequireAdmin component**

```tsx
// frontend/src/components/auth/RequireAdmin.tsx
"use client";

import { useEffect, type ReactNode } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth";

type RequireAdminProps = {
  children: ReactNode;
};

/**
 * Client-side guard for admin pages. Three states from useAuth():
 *   - loading → centered spinner placeholder
 *   - unauthenticated OR role !== "ADMIN" → redirect to "/", render null
 *   - authenticated as ADMIN → render children
 */
export function RequireAdmin({ children }: RequireAdminProps) {
  const session = useAuth();
  const router = useRouter();

  useEffect(() => {
    if (session.status === "unauthenticated") {
      router.replace("/");
    } else if (session.status === "authenticated" && session.user.role !== "ADMIN") {
      router.replace("/");
    }
  }, [session, router]);

  if (session.status === "loading") {
    return (
      <div className="flex min-h-[60vh] items-center justify-center">
        <div className="h-8 w-8 animate-spin rounded-full border-4 border-primary border-t-transparent" />
      </div>
    );
  }

  if (session.status !== "authenticated" || session.user.role !== "ADMIN") {
    return null;
  }

  return <>{children}</>;
}
```

- [ ] **Step 4: Write RequireAdmin test**

```tsx
// frontend/src/components/auth/RequireAdmin.test.tsx
import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { RequireAdmin } from "./RequireAdmin";
import { server } from "@/test/msw/server";
import { authHandlers } from "@/test/msw/handlers";

const replace = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace }),
}));

function wrap(ui: React.ReactNode) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={qc}>{ui}</QueryClientProvider>);
}

beforeEach(() => {
  replace.mockClear();
});

describe("RequireAdmin", () => {
  it("renders spinner while loading", () => {
    wrap(<RequireAdmin><div>secret</div></RequireAdmin>);
    expect(screen.queryByText("secret")).toBeNull();
  });

  it("redirects to / and renders nothing when unauthenticated", async () => {
    server.use(authHandlers.refreshUnauthenticated());
    wrap(<RequireAdmin><div>secret</div></RequireAdmin>);
    await waitFor(() => expect(replace).toHaveBeenCalledWith("/"));
    expect(screen.queryByText("secret")).toBeNull();
  });

  it("redirects to / when authenticated but not admin", async () => {
    server.use(authHandlers.refreshSuccess({
      id: 1, email: "u@x.com", displayName: "U", slAvatarUuid: null,
      verified: true, role: "USER",
    }));
    wrap(<RequireAdmin><div>secret</div></RequireAdmin>);
    await waitFor(() => expect(replace).toHaveBeenCalledWith("/"));
    expect(screen.queryByText("secret")).toBeNull();
  });

  it("renders children when role is ADMIN", async () => {
    server.use(authHandlers.refreshSuccess({
      id: 1, email: "a@x.com", displayName: "A", slAvatarUuid: null,
      verified: true, role: "ADMIN",
    }));
    wrap(<RequireAdmin><div>secret</div></RequireAdmin>);
    await waitFor(() => expect(screen.queryByText("secret")).not.toBeNull());
  });
});
```

The implementer adapts to whatever the actual MSW `authHandlers.refreshSuccess()` and `refreshUnauthenticated()` signatures look like. If the existing factory takes a user and returns a handler, pass the role-bearing user; if it doesn't take any args, add an optional user arg in the same task.

- [ ] **Step 5: Add admin link to Header**

In `frontend/src/components/layout/Header.tsx` line 46-50, add the admin nav link conditional on role:

```tsx
<nav className="hidden md:flex items-center gap-8">
  <NavLink variant="header" href="/browse">Browse</NavLink>
  <NavLink variant="header" href="/dashboard">Dashboard</NavLink>
  <NavLink variant="header" href="/auction/new">Create Listing</NavLink>
  {status === "authenticated" && user.role === "ADMIN" && (
    <NavLink variant="header" href="/admin">Admin</NavLink>
  )}
</nav>
```

- [ ] **Step 6: Add admin link to MobileMenu**

Open `frontend/src/components/layout/MobileMenu.tsx`. Find the existing Dashboard link (line 38) and add a sibling admin link below it conditional on role. The exact JSX shape will match the existing nav-link pattern in the file.

- [ ] **Step 7: Add admin menu item to UserMenuDropdown**

Open `frontend/src/components/auth/UserMenuDropdown.tsx`. Find the existing items array (line 31 has the `Dashboard` entry). Add an item conditional on `user.role === "ADMIN"`:

```tsx
...(user.role === "ADMIN" ? [{ label: "Admin", href: "/admin" }] : []),
```

- [ ] **Step 8: Create lib/admin/types.ts**

```ts
// frontend/src/lib/admin/types.ts
export type FraudFlagReason =
  | "OWNERSHIP_CHANGED_TO_UNKNOWN"
  | "PARCEL_DELETED_OR_MERGED"
  | "WORLD_API_FAILURE_THRESHOLD"
  | "ESCROW_WRONG_PAYER"
  | "ESCROW_UNKNOWN_OWNER"
  | "ESCROW_PARCEL_DELETED"
  | "ESCROW_WORLD_API_FAILURE"
  | "BOT_AUTH_BUYER_REVOKED"
  | "BOT_PRICE_DRIFT"
  | "BOT_OWNERSHIP_CHANGED"
  | "BOT_ACCESS_REVOKED"
  | "CANCEL_AND_SELL";

export type AuctionStatus =
  | "DRAFT" | "DRAFT_PAID" | "VERIFICATION_PENDING" | "VERIFICATION_FAILED"
  | "ACTIVE" | "ENDED" | "ESCROW_PENDING" | "ESCROW_FUNDED"
  | "TRANSFER_PENDING" | "COMPLETED" | "CANCELLED" | "EXPIRED"
  | "DISPUTED" | "SUSPENDED";

export type FraudFlagListStatus = "open" | "resolved" | "all";

export type AdminStatsResponse = {
  queues: {
    openFraudFlags: number;
    pendingPayments: number;
    activeDisputes: number;
  };
  platform: {
    activeListings: number;
    totalUsers: number;
    activeEscrows: number;
    completedSales: number;
    lindenGrossVolume: number;
    lindenCommissionEarned: number;
  };
};

export type AdminFraudFlagSummary = {
  id: number;
  reason: FraudFlagReason;
  detectedAt: string;
  auctionId: number | null;
  auctionTitle: string | null;
  auctionStatus: AuctionStatus | null;
  parcelRegionName: string | null;
  parcelLocalId: number | null;
  resolved: boolean;
  resolvedAt: string | null;
  resolvedByDisplayName: string | null;
};

export type LinkedUser = {
  userId: number;
  displayName: string | null;
};

export type AdminFraudFlagDetail = {
  id: number;
  reason: FraudFlagReason;
  detectedAt: string;
  resolvedAt: string | null;
  resolvedByDisplayName: string | null;
  adminNotes: string | null;
  auction: {
    id: number;
    title: string;
    status: AuctionStatus;
    endsAt: string;
    suspendedAt: string | null;
    sellerUserId: number;
    sellerDisplayName: string | null;
  } | null;
  evidenceJson: Record<string, unknown>;
  linkedUsers: Record<string, LinkedUser>;
  siblingOpenFlagCount: number;
};

export type AdminApiError = {
  code: string;
  message: string;
  details: Record<string, unknown>;
};
```

- [ ] **Step 9: Create lib/admin/queryKeys.ts**

```ts
// frontend/src/lib/admin/queryKeys.ts
import type { FraudFlagListStatus, FraudFlagReason } from "./types";

export const adminQueryKeys = {
  all: ["admin"] as const,
  stats: () => [...adminQueryKeys.all, "stats"] as const,
  fraudFlags: () => [...adminQueryKeys.all, "fraud-flags"] as const,
  fraudFlagsList: (filters: {
    status: FraudFlagListStatus;
    reasons: FraudFlagReason[];
    page: number;
    size: number;
  }) => [...adminQueryKeys.fraudFlags(), "list", filters] as const,
  fraudFlagDetail: (flagId: number) =>
    [...adminQueryKeys.fraudFlags(), "detail", flagId] as const,
};
```

- [ ] **Step 10: Create lib/admin/api.ts**

```ts
// frontend/src/lib/admin/api.ts
import { apiClient } from "@/lib/api";
import type {
  AdminFraudFlagDetail, AdminFraudFlagSummary, AdminStatsResponse,
  FraudFlagListStatus, FraudFlagReason,
} from "./types";
import type { Page } from "@/types/page";

export const adminApi = {
  stats(): Promise<AdminStatsResponse> {
    return apiClient.get("/api/v1/admin/stats");
  },

  fraudFlagsList(params: {
    status: FraudFlagListStatus;
    reasons: FraudFlagReason[];
    page: number;
    size: number;
  }): Promise<Page<AdminFraudFlagSummary>> {
    const search = new URLSearchParams();
    search.set("status", params.status);
    if (params.reasons.length > 0) search.set("reasons", params.reasons.join(","));
    search.set("page", String(params.page));
    search.set("size", String(params.size));
    return apiClient.get(`/api/v1/admin/fraud-flags?${search.toString()}`);
  },

  fraudFlagDetail(flagId: number): Promise<AdminFraudFlagDetail> {
    return apiClient.get(`/api/v1/admin/fraud-flags/${flagId}`);
  },

  dismissFraudFlag(flagId: number, adminNotes: string): Promise<AdminFraudFlagDetail> {
    return apiClient.post(
      `/api/v1/admin/fraud-flags/${flagId}/dismiss`,
      { adminNotes });
  },

  reinstateFraudFlag(flagId: number, adminNotes: string): Promise<AdminFraudFlagDetail> {
    return apiClient.post(
      `/api/v1/admin/fraud-flags/${flagId}/reinstate`,
      { adminNotes });
  },
};
```

The implementer should check the existing `apiClient` shape (likely in `frontend/src/lib/api.ts`) — the helper may be `apiClient.get<T>(url)` returning `Promise<T>` or have a slightly different surface. Adapt accordingly.

- [ ] **Step 11: Create lib/admin/reasonStyle.ts**

```ts
// frontend/src/lib/admin/reasonStyle.ts
import type { FraudFlagReason } from "./types";

export type ReasonFamily = "ownership" | "bot" | "escrow" | "cancel-and-sell";

export const REASON_FAMILY: Record<FraudFlagReason, ReasonFamily> = {
  OWNERSHIP_CHANGED_TO_UNKNOWN: "ownership",
  PARCEL_DELETED_OR_MERGED: "ownership",
  WORLD_API_FAILURE_THRESHOLD: "ownership",
  ESCROW_WRONG_PAYER: "escrow",
  ESCROW_UNKNOWN_OWNER: "escrow",
  ESCROW_PARCEL_DELETED: "escrow",
  ESCROW_WORLD_API_FAILURE: "escrow",
  BOT_AUTH_BUYER_REVOKED: "bot",
  BOT_PRICE_DRIFT: "bot",
  BOT_OWNERSHIP_CHANGED: "bot",
  BOT_ACCESS_REVOKED: "bot",
  CANCEL_AND_SELL: "cancel-and-sell",
};

export const REASON_LABEL: Record<FraudFlagReason, string> = {
  OWNERSHIP_CHANGED_TO_UNKNOWN: "Owner changed",
  PARCEL_DELETED_OR_MERGED: "Parcel deleted",
  WORLD_API_FAILURE_THRESHOLD: "World API failures",
  ESCROW_WRONG_PAYER: "Wrong payer",
  ESCROW_UNKNOWN_OWNER: "Escrow owner unknown",
  ESCROW_PARCEL_DELETED: "Escrow parcel deleted",
  ESCROW_WORLD_API_FAILURE: "Escrow API failures",
  BOT_AUTH_BUYER_REVOKED: "Bot auth revoked",
  BOT_PRICE_DRIFT: "Bot price drift",
  BOT_OWNERSHIP_CHANGED: "Bot owner changed",
  BOT_ACCESS_REVOKED: "Bot access revoked",
  CANCEL_AND_SELL: "Cancel-and-sell",
};

export const FAMILY_TONE_CLASSES: Record<ReasonFamily, string> = {
  ownership: "bg-error/15 text-error",
  bot: "bg-warning/15 text-warning",
  escrow: "bg-secondary/15 text-secondary",
  "cancel-and-sell": "bg-tertiary/15 text-tertiary",
};
```

The actual Tailwind tone classes should match the existing design tokens (Digital Curator). The implementer reads `docs/stitch_generated-design/DESIGN.md` to confirm exact tone tokens (`error`/`warning`/`secondary`/`tertiary` are placeholders — adapt to whatever color tokens the project actually has). If only one tone color exists today, fall back to a single neutral pill style consistent with existing badges in the codebase.

- [ ] **Step 12: Add admin MSW handlers**

In `frontend/src/test/msw/handlers.ts`, add a new `adminHandlers` factory group:

```ts
import { http, HttpResponse } from "msw";
import type {
  AdminFraudFlagDetail, AdminFraudFlagSummary, AdminStatsResponse,
} from "@/lib/admin/types";
import type { Page } from "@/types/page";

const defaultStats: AdminStatsResponse = {
  queues: { openFraudFlags: 0, pendingPayments: 0, activeDisputes: 0 },
  platform: {
    activeListings: 0, totalUsers: 0, activeEscrows: 0, completedSales: 0,
    lindenGrossVolume: 0, lindenCommissionEarned: 0,
  },
};

export const adminHandlers = {
  statsSuccess(stats: Partial<AdminStatsResponse> = {}) {
    return http.get("*/api/v1/admin/stats", () =>
      HttpResponse.json({ ...defaultStats, ...stats }));
  },
  fraudFlagsListSuccess(rows: AdminFraudFlagSummary[]) {
    return http.get("*/api/v1/admin/fraud-flags", () => {
      const page: Page<AdminFraudFlagSummary> = {
        content: rows,
        totalElements: rows.length,
        totalPages: 1,
        number: 0,
        size: 25,
      };
      return HttpResponse.json(page);
    });
  },
  fraudFlagDetailSuccess(detail: AdminFraudFlagDetail) {
    return http.get(`*/api/v1/admin/fraud-flags/${detail.id}`, () =>
      HttpResponse.json(detail));
  },
  dismissSuccess(detail: AdminFraudFlagDetail) {
    return http.post(`*/api/v1/admin/fraud-flags/${detail.id}/dismiss`, () =>
      HttpResponse.json(detail));
  },
  reinstateSuccess(detail: AdminFraudFlagDetail) {
    return http.post(`*/api/v1/admin/fraud-flags/${detail.id}/reinstate`, () =>
      HttpResponse.json(detail));
  },
  dismiss409AlreadyResolved(flagId: number) {
    return http.post(`*/api/v1/admin/fraud-flags/${flagId}/dismiss`, () =>
      HttpResponse.json(
        { code: "ALREADY_RESOLVED", message: "Already resolved", details: {} },
        { status: 409 }));
  },
  reinstate409NotSuspended(flagId: number, currentStatus: string) {
    return http.post(`*/api/v1/admin/fraud-flags/${flagId}/reinstate`, () =>
      HttpResponse.json(
        { code: "AUCTION_NOT_SUSPENDED", message: "Not suspended",
          details: { currentStatus } },
        { status: 409 }));
  },
};
```

The wildcard `*/api/v1/...` URL prefix is the project convention (matches MSW patterns from previous epics).

- [ ] **Step 13: Run frontend tests + typecheck**

Run: `cd frontend && npx tsc --noEmit && npm test -- RequireAdmin -q`
Expected: PASS.

- [ ] **Step 14: Commit**

```bash
git add frontend/src/lib/auth/session.ts \
        frontend/src/lib/admin/ \
        frontend/src/components/auth/RequireAdmin.tsx \
        frontend/src/components/auth/RequireAdmin.test.tsx \
        frontend/src/components/layout/Header.tsx \
        frontend/src/components/layout/MobileMenu.tsx \
        frontend/src/components/auth/UserMenuDropdown.tsx \
        frontend/src/test/msw/handlers.ts

git commit -m "$(cat <<'EOF'
feat(admin): frontend foundations — role, RequireAdmin, nav, types, MSW

AuthUser.role added to session shape. RequireAdmin redirects non-admin
users to /. Admin link rendered conditionally in Header, MobileMenu, and
UserMenuDropdown. lib/admin/* scaffolds api client, query keys, types,
and reason styling. MSW adminHandlers cover stats + list + detail +
dismiss + reinstate + 409 paths.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 9: Frontend admin shell + dashboard

**Goal:** `/admin/layout.tsx` (shell), `/admin/page.tsx` (dashboard with stats cards), `<AdminShell>` (sidebar + main + role chip), `<QueueCard>` + `<StatCard>` primitives, `<AdminDashboardPage>` composing them, `useAdminStats()` hook.

**Files:**
- Create: `frontend/src/app/admin/layout.tsx`
- Create: `frontend/src/app/admin/page.tsx`
- Create: `frontend/src/components/admin/AdminShell.tsx`
- Create: `frontend/src/components/admin/AdminShell.test.tsx`
- Create: `frontend/src/components/admin/dashboard/QueueCard.tsx`
- Create: `frontend/src/components/admin/dashboard/QueueCard.test.tsx`
- Create: `frontend/src/components/admin/dashboard/StatCard.tsx`
- Create: `frontend/src/components/admin/dashboard/StatCard.test.tsx`
- Create: `frontend/src/components/admin/dashboard/AdminDashboardPage.tsx`
- Create: `frontend/src/components/admin/dashboard/AdminDashboardPage.test.tsx`
- Create: `frontend/src/hooks/admin/useAdminStats.ts`

- [ ] **Step 1: Create useAdminStats hook**

```ts
// frontend/src/hooks/admin/useAdminStats.ts
"use client";

import { useQuery } from "@tanstack/react-query";
import { adminApi } from "@/lib/admin/api";
import { adminQueryKeys } from "@/lib/admin/queryKeys";

export function useAdminStats() {
  return useQuery({
    queryKey: adminQueryKeys.stats(),
    queryFn: adminApi.stats,
    staleTime: 30_000,
  });
}
```

- [ ] **Step 2: Create AdminShell**

```tsx
// frontend/src/components/admin/AdminShell.tsx
"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { type ReactNode } from "react";
import { useAuth } from "@/lib/auth";
import { useAdminStats } from "@/hooks/admin/useAdminStats";
import { cn } from "@/lib/cn";

type SidebarItem = {
  label: string;
  href: string;
  badge?: number;
};

export function AdminShell({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  const { user } = useAuth();
  const { data: stats } = useAdminStats();

  const items: SidebarItem[] = [
    { label: "Dashboard", href: "/admin" },
    { label: "Fraud Flags", href: "/admin/fraud-flags",
      badge: stats?.queues.openFraudFlags },
  ];

  return (
    <div className="grid grid-cols-[200px_1fr] min-h-[calc(100vh-4rem)]">
      <aside className="bg-surface-container border-r border-outline-variant px-4 py-5 flex flex-col gap-1">
        <div className="text-[11px] uppercase tracking-wider opacity-50 mb-3">Admin</div>
        {items.map(item => {
          const active = pathname === item.href ||
            (item.href !== "/admin" && pathname?.startsWith(item.href));
          return (
            <Link
              key={item.href}
              href={item.href}
              className={cn(
                "flex items-center justify-between rounded-md px-3 py-2 text-sm",
                active ? "bg-secondary-container text-on-secondary-container font-medium"
                       : "opacity-85 hover:opacity-100"
              )}
            >
              <span>{item.label}</span>
              {item.badge !== undefined && item.badge > 0 && (
                <span className="bg-error text-on-error rounded-full px-1.5 py-0.5 text-[10px] ml-1">
                  {item.badge}
                </span>
              )}
            </Link>
          );
        })}
        <div className="mt-auto px-3 py-2 text-[11px] opacity-50">
          {user?.displayName ?? user?.email}
          <br />
          <span className="text-[10px] text-primary">ADMIN</span>
        </div>
      </aside>
      <main className="px-7 py-6">{children}</main>
    </div>
  );
}
```

- [ ] **Step 3: Create QueueCard**

```tsx
// frontend/src/components/admin/dashboard/QueueCard.tsx
import Link from "next/link";

type Tone = "fraud" | "warning";

const TONE_CLASSES: Record<Tone, { bg: string; border: string; value: string }> = {
  fraud: { bg: "bg-error-container/30", border: "border-error/40", value: "text-error" },
  warning: { bg: "bg-warning-container/30", border: "border-warning/40", value: "text-warning" },
};

type QueueCardProps = {
  label: string;
  value: number;
  tone: Tone;
  subtext: string;
  href?: string;
};

export function QueueCard({ label, value, tone, subtext, href }: QueueCardProps) {
  const t = TONE_CLASSES[tone];
  const inner = (
    <div className={`${t.bg} border ${t.border} rounded-lg p-4`}>
      <div className="flex items-start justify-between">
        <div>
          <div className="text-[11px] uppercase tracking-wide opacity-70">{label}</div>
          <div className={`text-3xl font-semibold leading-tight mt-1 ${t.value}`}>{value}</div>
        </div>
        {href && <div className="opacity-40 text-xs">→</div>}
      </div>
      <div className="text-[11px] opacity-50 mt-2">{subtext}</div>
    </div>
  );
  return href ? <Link href={href} className="block hover:opacity-90">{inner}</Link> : inner;
}
```

The actual Tailwind tone class names depend on the existing Digital Curator design tokens — implementer adapts to project conventions. If `bg-error-container/30` doesn't exist in this project's tailwind config, use whatever the closest red surface tone is.

- [ ] **Step 4: Create StatCard**

```tsx
// frontend/src/components/admin/dashboard/StatCard.tsx
type StatCardProps = {
  label: string;
  value: string | number;
  accent?: boolean;
};

export function StatCard({ label, value, accent }: StatCardProps) {
  return (
    <div className="bg-surface-container border border-outline-variant rounded-lg p-4">
      <div className="text-[11px] opacity-60">{label}</div>
      <div className={`text-2xl font-semibold mt-1.5 ${accent ? "text-primary" : ""}`}>
        {value}
      </div>
    </div>
  );
}
```

- [ ] **Step 5: Create AdminDashboardPage**

```tsx
// frontend/src/components/admin/dashboard/AdminDashboardPage.tsx
"use client";

import { useAdminStats } from "@/hooks/admin/useAdminStats";
import { QueueCard } from "./QueueCard";
import { StatCard } from "./StatCard";

function formatLindens(n: number): string {
  return `L$ ${n.toLocaleString("en-US")}`;
}

export function AdminDashboardPage() {
  const { data, isLoading, isError } = useAdminStats();

  if (isLoading) {
    return <div className="text-sm opacity-60">Loading dashboard…</div>;
  }
  if (isError || !data) {
    return <div className="text-sm text-error">Could not load admin stats. Refresh to retry.</div>;
  }

  return (
    <>
      <h1 className="text-2xl font-semibold">Dashboard</h1>
      <div className="text-xs opacity-60 mt-0.5 mb-6">Platform overview · lifetime</div>

      <div className="text-[11px] uppercase tracking-wide opacity-60 mb-2">Needs attention</div>
      <div className="grid grid-cols-3 gap-3 mb-7">
        <QueueCard
          label="Open fraud flags"
          value={data.queues.openFraudFlags}
          tone="fraud"
          subtext="Click to triage"
          href="/admin/fraud-flags"
        />
        <QueueCard
          label="Pending payments"
          value={data.queues.pendingPayments}
          tone="warning"
          subtext="Awaiting winner L$"
        />
        <QueueCard
          label="Active disputes"
          value={data.queues.activeDisputes}
          tone="warning"
          subtext="Escrow disputed"
        />
      </div>

      <div className="text-[11px] uppercase tracking-wide opacity-60 mb-2">Platform · lifetime</div>
      <div className="grid grid-cols-3 gap-3">
        <StatCard label="Active listings" value={data.platform.activeListings} />
        <StatCard label="Total users" value={data.platform.totalUsers} />
        <StatCard label="Active escrows" value={data.platform.activeEscrows} />
        <StatCard label="Completed sales" value={data.platform.completedSales} />
        <StatCard label="L$ gross volume" value={formatLindens(data.platform.lindenGrossVolume)} />
        <StatCard
          label="L$ commission"
          value={formatLindens(data.platform.lindenCommissionEarned)}
          accent
        />
      </div>
    </>
  );
}
```

- [ ] **Step 6: Create app/admin/layout.tsx**

```tsx
// frontend/src/app/admin/layout.tsx
import { type ReactNode } from "react";
import { RequireAdmin } from "@/components/auth/RequireAdmin";
import { AdminShell } from "@/components/admin/AdminShell";

export default function AdminLayout({ children }: { children: ReactNode }) {
  return (
    <RequireAdmin>
      <AdminShell>{children}</AdminShell>
    </RequireAdmin>
  );
}
```

- [ ] **Step 7: Create app/admin/page.tsx**

```tsx
// frontend/src/app/admin/page.tsx
import { AdminDashboardPage } from "@/components/admin/dashboard/AdminDashboardPage";

export default function AdminDashboardRoute() {
  return <AdminDashboardPage />;
}
```

- [ ] **Step 8: Write QueueCard test**

```tsx
// frontend/src/components/admin/dashboard/QueueCard.test.tsx
import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { QueueCard } from "./QueueCard";

describe("QueueCard", () => {
  it("renders label, value, subtext", () => {
    render(<QueueCard label="Test" value={7} tone="fraud" subtext="Click me" />);
    expect(screen.getByText("Test")).toBeInTheDocument();
    expect(screen.getByText("7")).toBeInTheDocument();
    expect(screen.getByText("Click me")).toBeInTheDocument();
  });

  it("wraps in a Link when href is provided", () => {
    render(<QueueCard label="X" value={1} tone="fraud" subtext="y" href="/admin/fraud-flags" />);
    expect(screen.getByRole("link")).toHaveAttribute("href", "/admin/fraud-flags");
  });

  it("does not render arrow indicator when no href", () => {
    render(<QueueCard label="X" value={1} tone="warning" subtext="y" />);
    expect(screen.queryByRole("link")).toBeNull();
  });
});
```

- [ ] **Step 9: Write StatCard test**

```tsx
// frontend/src/components/admin/dashboard/StatCard.test.tsx
import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { StatCard } from "./StatCard";

describe("StatCard", () => {
  it("renders label and string value", () => {
    render(<StatCard label="Test label" value="L$ 1,000" />);
    expect(screen.getByText("Test label")).toBeInTheDocument();
    expect(screen.getByText("L$ 1,000")).toBeInTheDocument();
  });

  it("renders numeric value", () => {
    render(<StatCard label="Count" value={42} />);
    expect(screen.getByText("42")).toBeInTheDocument();
  });
});
```

- [ ] **Step 10: Write AdminDashboardPage test**

```tsx
// frontend/src/components/admin/dashboard/AdminDashboardPage.test.tsx
import { describe, it, expect } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { AdminDashboardPage } from "./AdminDashboardPage";
import { server } from "@/test/msw/server";
import { adminHandlers } from "@/test/msw/handlers";

function wrap() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <AdminDashboardPage />
    </QueryClientProvider>
  );
}

describe("AdminDashboardPage", () => {
  it("renders all 9 numbers from the API response", async () => {
    server.use(adminHandlers.statsSuccess({
      queues: { openFraudFlags: 7, pendingPayments: 3, activeDisputes: 1 },
      platform: {
        activeListings: 42, totalUsers: 381, activeEscrows: 12, completedSales: 156,
        lindenGrossVolume: 4_827_500, lindenCommissionEarned: 241_375,
      },
    }));

    wrap();

    await waitFor(() => expect(screen.queryByText("7")).not.toBeNull());
    expect(screen.getByText("3")).toBeInTheDocument();
    expect(screen.getByText("1")).toBeInTheDocument();
    expect(screen.getByText("42")).toBeInTheDocument();
    expect(screen.getByText("381")).toBeInTheDocument();
    expect(screen.getByText("12")).toBeInTheDocument();
    expect(screen.getByText("156")).toBeInTheDocument();
    expect(screen.getByText("L$ 4,827,500")).toBeInTheDocument();
    expect(screen.getByText("L$ 241,375")).toBeInTheDocument();
  });
});
```

- [ ] **Step 11: Run tests**

Run: `cd frontend && npm test -- admin -q`
Expected: PASS.

- [ ] **Step 12: Commit**

```bash
git add frontend/src/app/admin/ \
        frontend/src/components/admin/AdminShell.tsx \
        frontend/src/components/admin/AdminShell.test.tsx \
        frontend/src/components/admin/dashboard/ \
        frontend/src/hooks/admin/useAdminStats.ts

git commit -m "$(cat <<'EOF'
feat(admin): /admin dashboard with sidebar shell + 9-card overview

AdminShell sidebar (Dashboard + Fraud Flags with badge), role chip in
footer. QueueCard (red fraud / amber warning) + StatCard (neutral, L$
commission accent) primitives. AdminDashboardPage composes 3 queue
cards + 6 platform stats from /api/v1/admin/stats. Layout wraps in
RequireAdmin gate.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 10: Frontend fraud-flag list + slide-over

**Goal:** `/admin/fraud-flags/page.tsx`, `<FraudFlagsListPage>` with filters, table, slide-over panel (evidence + banners + notes + actions), prev/next queue walking, dual-cache invalidation on mutations.

**Files:**
- Create: `frontend/src/app/admin/fraud-flags/page.tsx`
- Create: `frontend/src/components/admin/fraud-flags/FraudFlagsListPage.tsx`
- Create: `frontend/src/components/admin/fraud-flags/FraudFlagFilters.tsx`
- Create: `frontend/src/components/admin/fraud-flags/FraudFlagTable.tsx`
- Create: `frontend/src/components/admin/fraud-flags/FraudFlagSlideOver.tsx`
- Create: `frontend/src/components/admin/fraud-flags/FraudFlagEvidence.tsx`
- Create: `frontend/src/components/admin/fraud-flags/ReinstateBanner.tsx`
- Create: `frontend/src/components/admin/fraud-flags/SiblingFlagWarning.tsx`
- Create: `frontend/src/components/admin/fraud-flags/ReasonBadge.tsx`
- Create: `frontend/src/components/admin/fraud-flags/NotesField.tsx`
- Create: `frontend/src/hooks/admin/useAdminFraudFlagsList.ts`
- Create: `frontend/src/hooks/admin/useAdminFraudFlag.ts`
- Create: `frontend/src/hooks/admin/useDismissFraudFlag.ts`
- Create: `frontend/src/hooks/admin/useReinstateFraudFlag.ts`
- Tests: companion `*.test.tsx` for each component + hook

This task is large; split into three commits for reviewability:

### 10a — Hooks + small components

- [ ] **Step 1: Write list-query hook**

```ts
// frontend/src/hooks/admin/useAdminFraudFlagsList.ts
"use client";

import { useQuery } from "@tanstack/react-query";
import { adminApi } from "@/lib/admin/api";
import { adminQueryKeys } from "@/lib/admin/queryKeys";
import type { FraudFlagListStatus, FraudFlagReason } from "@/lib/admin/types";

export function useAdminFraudFlagsList(filters: {
  status: FraudFlagListStatus;
  reasons: FraudFlagReason[];
  page: number;
  size: number;
}) {
  return useQuery({
    queryKey: adminQueryKeys.fraudFlagsList(filters),
    queryFn: () => adminApi.fraudFlagsList(filters),
    staleTime: 10_000,
  });
}
```

- [ ] **Step 2: Write detail-query hook**

```ts
// frontend/src/hooks/admin/useAdminFraudFlag.ts
"use client";

import { useQuery } from "@tanstack/react-query";
import { adminApi } from "@/lib/admin/api";
import { adminQueryKeys } from "@/lib/admin/queryKeys";

export function useAdminFraudFlag(flagId: number | null) {
  return useQuery({
    queryKey: flagId ? adminQueryKeys.fraudFlagDetail(flagId) : ["admin-flag-noop"],
    queryFn: () => adminApi.fraudFlagDetail(flagId!),
    enabled: flagId !== null,
    staleTime: 10_000,
  });
}
```

- [ ] **Step 3: Write dismiss + reinstate mutations with dual-cache invalidation**

```ts
// frontend/src/hooks/admin/useDismissFraudFlag.ts
"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { adminApi } from "@/lib/admin/api";
import { adminQueryKeys } from "@/lib/admin/queryKeys";
import { useToast } from "@/components/ui/Toast/useToast";
import type { AdminApiError } from "@/lib/admin/types";

export function useDismissFraudFlag() {
  const qc = useQueryClient();
  const toast = useToast();
  return useMutation({
    mutationFn: ({ flagId, adminNotes }: { flagId: number; adminNotes: string }) =>
      adminApi.dismissFraudFlag(flagId, adminNotes),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: adminQueryKeys.fraudFlags() });
      qc.invalidateQueries({ queryKey: adminQueryKeys.stats() });
      toast.success("Flag dismissed.");
    },
    onError: (err: AdminApiError | Error) => {
      const code = "code" in err ? err.code : null;
      if (code === "ALREADY_RESOLVED") {
        toast.error("Flag was already resolved. Refreshing.");
        qc.invalidateQueries({ queryKey: adminQueryKeys.fraudFlags() });
        return;
      }
      toast.error("Couldn't dismiss flag.");
    },
  });
}
```

```ts
// frontend/src/hooks/admin/useReinstateFraudFlag.ts
"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { adminApi } from "@/lib/admin/api";
import { adminQueryKeys } from "@/lib/admin/queryKeys";
import { useToast } from "@/components/ui/Toast/useToast";
import type { AdminApiError } from "@/lib/admin/types";

export function useReinstateFraudFlag() {
  const qc = useQueryClient();
  const toast = useToast();
  return useMutation({
    mutationFn: ({ flagId, adminNotes }: { flagId: number; adminNotes: string }) =>
      adminApi.reinstateFraudFlag(flagId, adminNotes),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: adminQueryKeys.fraudFlags() });
      qc.invalidateQueries({ queryKey: adminQueryKeys.stats() });
      toast.success("Auction reinstated.");
    },
    onError: (err: AdminApiError | Error) => {
      const code = "code" in err ? err.code : null;
      if (code === "ALREADY_RESOLVED") {
        toast.error("Flag was already resolved. Refreshing.");
        qc.invalidateQueries({ queryKey: adminQueryKeys.fraudFlags() });
        return;
      }
      if (code === "AUCTION_NOT_SUSPENDED") {
        toast.error("Auction state changed — refreshing.");
        qc.invalidateQueries({ queryKey: adminQueryKeys.fraudFlags() });
        return;
      }
      toast.error("Couldn't reinstate auction.");
    },
  });
}
```

The implementer should check the actual `apiClient` error shape — if errors are thrown as a plain `Error` with message-only, parse the JSON body via `error.cause` or however the existing api client surfaces error bodies. Adapt the `code` extraction accordingly.

- [ ] **Step 4: Create ReasonBadge**

```tsx
// frontend/src/components/admin/fraud-flags/ReasonBadge.tsx
import { FAMILY_TONE_CLASSES, REASON_FAMILY, REASON_LABEL } from "@/lib/admin/reasonStyle";
import type { FraudFlagReason } from "@/lib/admin/types";

export function ReasonBadge({ reason }: { reason: FraudFlagReason }) {
  const tone = FAMILY_TONE_CLASSES[REASON_FAMILY[reason]];
  return (
    <span className={`${tone} px-2 py-0.5 rounded-full text-[10px] font-medium`}>
      {REASON_LABEL[reason]}
    </span>
  );
}
```

- [ ] **Step 5: Create NotesField**

```tsx
// frontend/src/components/admin/fraud-flags/NotesField.tsx
"use client";

const MAX = 1000;

type NotesFieldProps = {
  value: string;
  onChange: (s: string) => void;
  disabled?: boolean;
};

export function NotesField({ value, onChange, disabled }: NotesFieldProps) {
  return (
    <div>
      <label className="block text-[10px] uppercase tracking-wider opacity-60 mb-1.5">
        Admin notes <span className="text-error normal-case tracking-normal">(required)</span>
      </label>
      <textarea
        value={value}
        onChange={(e) => onChange(e.target.value.slice(0, MAX))}
        disabled={disabled}
        placeholder="Why are you taking this action? What did you check?"
        className="w-full h-22 bg-surface-container border border-outline-variant rounded-md p-2.5 text-sm font-sans resize-y disabled:opacity-50"
      />
      <div className="text-[10px] opacity-40 mt-1">{value.length} / {MAX}</div>
    </div>
  );
}
```

- [ ] **Step 6: Run a quick typecheck**

Run: `cd frontend && npx tsc --noEmit`
Expected: clean.

- [ ] **Step 7: Commit 10a**

```bash
git add frontend/src/hooks/admin/ \
        frontend/src/components/admin/fraud-flags/ReasonBadge.tsx \
        frontend/src/components/admin/fraud-flags/NotesField.tsx

git commit -m "$(cat <<'EOF'
feat(admin): fraud-flag hooks + ReasonBadge/NotesField primitives

useAdminFraudFlagsList + useAdminFraudFlag queries.
useDismissFraudFlag + useReinstateFraudFlag mutations invalidate both
the fraud-flags list AND admin stats query (queue counts shift on each
resolution). 409 codes (ALREADY_RESOLVED, AUCTION_NOT_SUSPENDED)
surface as toast + cache refresh.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

### 10b — List, filters, table, banners, evidence

- [ ] **Step 8: Create FraudFlagFilters**

```tsx
// frontend/src/components/admin/fraud-flags/FraudFlagFilters.tsx
"use client";

import type { FraudFlagListStatus, FraudFlagReason } from "@/lib/admin/types";

const STATUS_OPTIONS: { value: FraudFlagListStatus; label: string }[] = [
  { value: "open", label: "Open" },
  { value: "resolved", label: "Resolved" },
  { value: "all", label: "All" },
];

type Props = {
  status: FraudFlagListStatus;
  onStatusChange: (s: FraudFlagListStatus) => void;
  reasons: FraudFlagReason[];
  onReasonsChange: (r: FraudFlagReason[]) => void;
};

export function FraudFlagFilters({ status, onStatusChange }: Props) {
  return (
    <div className="flex gap-2 mb-3.5 flex-wrap items-center">
      <div className="flex border border-outline-variant rounded-md overflow-hidden">
        {STATUS_OPTIONS.map((opt, idx) => (
          <button
            key={opt.value}
            onClick={() => onStatusChange(opt.value)}
            className={`px-3 py-1.5 text-xs font-medium ${
              status === opt.value
                ? "bg-secondary-container text-on-secondary-container"
                : "opacity-60 hover:opacity-100"
            } ${idx > 0 ? "border-l border-outline-variant" : ""}`}
          >
            {opt.label}
          </button>
        ))}
      </div>
      {/* Reason multi-select dropdown — placeholder for now, full implementation
          uses Headless UI Listbox or similar. Empty (= all reasons) is the default. */}
    </div>
  );
}
```

The reason multi-select dropdown UI shape is a minor detail — implementer may use Headless UI's Listbox in multi-select mode, a custom popover, or skip this detail in the first commit and add it as a follow-up step. The plan task accepts either: ship a working filter without the reason dropdown, then add reason filtering in a small follow-up. The state shape is already wired in props.

- [ ] **Step 9: Create FraudFlagTable**

```tsx
// frontend/src/components/admin/fraud-flags/FraudFlagTable.tsx
"use client";

import type { AdminFraudFlagSummary, AuctionStatus } from "@/lib/admin/types";
import { ReasonBadge } from "./ReasonBadge";

type Props = {
  rows: AdminFraudFlagSummary[];
  selectedId: number | null;
  onSelect: (id: number) => void;
};

function statusTone(s: AuctionStatus | null): string {
  if (s === "SUSPENDED") return "text-error font-medium";
  if (s === "FROZEN" || s === "DISPUTED") return "text-secondary font-medium";
  return "opacity-70";
}

export function FraudFlagTable({ rows, selectedId, onSelect }: Props) {
  if (rows.length === 0) {
    return <div className="text-sm opacity-60 py-8 text-center">No fraud flags match the current filter.</div>;
  }

  return (
    <>
      <div
        className="grid gap-3 px-2.5 py-2 text-[10px] uppercase tracking-wider opacity-50 border-b border-outline-variant"
        style={{ gridTemplateColumns: "90px 150px 1fr 100px" }}
      >
        <div>Detected</div>
        <div>Reason</div>
        <div>Auction</div>
        <div className="text-right">Status</div>
      </div>
      <div className="text-xs">
        {rows.map(row => (
          <button
            key={row.id}
            onClick={() => onSelect(row.id)}
            className={`grid gap-3 px-2.5 py-3 border-b border-outline-variant/50 w-full text-left ${
              selectedId === row.id ? "bg-secondary-container/40" : ""
            }`}
            style={{ gridTemplateColumns: "90px 150px 1fr 100px" }}
          >
            <div className="opacity-85">{new Date(row.detectedAt).toLocaleString("en-US", {
              month: "short", day: "numeric", hour: "2-digit", minute: "2-digit"
            })}</div>
            <div><ReasonBadge reason={row.reason} /></div>
            <div className="opacity-95 truncate">
              {row.auctionTitle ?? "—"}{row.parcelRegionName ? ` · ${row.parcelRegionName}` : ""}
            </div>
            <div className={`text-right ${statusTone(row.auctionStatus)}`}>
              {row.auctionStatus ?? (row.resolved ? "RESOLVED" : "—")}
            </div>
          </button>
        ))}
      </div>
    </>
  );
}
```

- [ ] **Step 10: Create ReinstateBanner**

```tsx
// frontend/src/components/admin/fraud-flags/ReinstateBanner.tsx
import type { AdminFraudFlagDetail } from "@/lib/admin/types";

type Props = {
  detail: AdminFraudFlagDetail;
};

function formatDuration(ms: number): string {
  const h = Math.floor(ms / 3_600_000);
  const m = Math.floor((ms % 3_600_000) / 60_000);
  if (h === 0) return `${m}m`;
  return `${h}h ${m}m`;
}

export function ReinstateBanner({ detail }: Props) {
  if (!detail.auction || detail.auction.status !== "SUSPENDED") return null;
  const suspendedFrom = detail.auction.suspendedAt ?? detail.detectedAt;
  const elapsedMs = Date.now() - new Date(suspendedFrom).getTime();
  return (
    <div className="bg-error-container/30 border border-error/40 rounded-md px-3 py-2.5 mb-4 text-xs">
      <span className="text-error font-medium">Auction is SUSPENDED.</span>
      <span className="opacity-70">
        {" "}Reinstate will restore ACTIVE status and extend endsAt by the suspension duration ({formatDuration(elapsedMs)} so far).
      </span>
    </div>
  );
}
```

- [ ] **Step 11: Create SiblingFlagWarning**

```tsx
// frontend/src/components/admin/fraud-flags/SiblingFlagWarning.tsx
type Props = {
  count: number;
};

export function SiblingFlagWarning({ count }: Props) {
  if (count <= 0) return null;
  return (
    <div className="bg-warning-container/30 border border-warning/40 rounded-md px-3 py-2.5 mb-4 text-xs">
      This auction has {count} other open flag{count === 1 ? "" : "s"}. Resolving this one alone doesn&apos;t address {count === 1 ? "it" : "them"} — {count === 1 ? "it" : "they"} need separate review.
    </div>
  );
}
```

- [ ] **Step 12: Create FraudFlagEvidence**

```tsx
// frontend/src/components/admin/fraud-flags/FraudFlagEvidence.tsx
import Link from "next/link";
import type { AdminFraudFlagDetail } from "@/lib/admin/types";

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function truncate(uuid: string): string {
  return `${uuid.slice(0, 6)}…${uuid.slice(-4)}`;
}

type Props = { detail: AdminFraudFlagDetail };

export function FraudFlagEvidence({ detail }: Props) {
  const entries = Object.entries(detail.evidenceJson);
  return (
    <>
      <div className="text-[10px] uppercase tracking-wider opacity-55 mb-1.5">Evidence</div>
      <div className="bg-surface-container border border-outline-variant rounded-md p-2.5 font-mono text-xs leading-7 mb-4">
        {entries.length === 0 && <div className="opacity-50">No evidence recorded.</div>}
        {entries.map(([key, value]) => (
          <EvidenceRow key={key} k={key} v={value} linkedUsers={detail.linkedUsers} />
        ))}
      </div>
    </>
  );
}

function EvidenceRow({ k, v, linkedUsers }: {
  k: string; v: unknown; linkedUsers: AdminFraudFlagDetail["linkedUsers"];
}) {
  const valueDisplay = renderValue(v, linkedUsers);
  return (
    <div>
      <span className="opacity-55">{k}</span>{"  "}{valueDisplay}
    </div>
  );
}

function renderValue(v: unknown, linkedUsers: AdminFraudFlagDetail["linkedUsers"]) {
  if (typeof v === "string" && UUID_RE.test(v)) {
    const linked = linkedUsers[v];
    if (linked) {
      return (
        <Link href={`/users/${linked.userId}`}
          title={`${v} · ${linked.displayName ?? "(no display name)"}`}
          className="underline">
          {truncate(v)}
        </Link>
      );
    }
    return (
      <span title={`${v} · (not a registered SLPA user)`} className="opacity-90">
        {truncate(v)}
      </span>
    );
  }
  return <span>{JSON.stringify(v)}</span>;
}
```

- [ ] **Step 13: Commit 10b**

```bash
git add frontend/src/components/admin/fraud-flags/FraudFlagFilters.tsx \
        frontend/src/components/admin/fraud-flags/FraudFlagTable.tsx \
        frontend/src/components/admin/fraud-flags/ReinstateBanner.tsx \
        frontend/src/components/admin/fraud-flags/SiblingFlagWarning.tsx \
        frontend/src/components/admin/fraud-flags/FraudFlagEvidence.tsx

git commit -m "$(cat <<'EOF'
feat(admin): fraud-flag list table + slide-over support components

FraudFlagFilters (status pills), FraudFlagTable (90/150/auto/100 grid
with status-tone right column), ReinstateBanner (SUSPENDED-only,
duration text), SiblingFlagWarning (count > 0), FraudFlagEvidence
(monospace key/value, UUIDs link to /users/{id} for registered users
and tooltip-only for non-registered).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

### 10c — Slide-over assembly + page composition + tests + route

- [ ] **Step 14: Create FraudFlagSlideOver**

```tsx
// frontend/src/components/admin/fraud-flags/FraudFlagSlideOver.tsx
"use client";

import { useEffect, useState } from "react";
import { useAdminFraudFlag } from "@/hooks/admin/useAdminFraudFlag";
import { useDismissFraudFlag } from "@/hooks/admin/useDismissFraudFlag";
import { useReinstateFraudFlag } from "@/hooks/admin/useReinstateFraudFlag";
import { ReasonBadge } from "./ReasonBadge";
import { ReinstateBanner } from "./ReinstateBanner";
import { SiblingFlagWarning } from "./SiblingFlagWarning";
import { FraudFlagEvidence } from "./FraudFlagEvidence";
import { NotesField } from "./NotesField";

type Props = {
  flagId: number;
  onClose: () => void;
  onPrev: () => void;
  onNext: () => void;
  hasPrev: boolean;
  hasNext: boolean;
};

export function FraudFlagSlideOver({ flagId, onClose, onPrev, onNext, hasPrev, hasNext }: Props) {
  const { data, isLoading, isError } = useAdminFraudFlag(flagId);
  const dismiss = useDismissFraudFlag();
  const reinstate = useReinstateFraudFlag();
  const [notes, setNotes] = useState("");

  // Reset notes when flag changes (prev/next walk).
  useEffect(() => {
    setNotes("");
  }, [flagId]);

  // Close on ESC.
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  }, [onClose]);

  const detail = data;
  const reinstateEnabled = detail?.auction?.status === "SUSPENDED";
  const notesValid = notes.trim().length > 0 && notes.length <= 1000;
  const busy = dismiss.isPending || reinstate.isPending;

  return (
    <aside
      className="fixed top-16 right-0 bottom-0 w-[520px] bg-surface border-l border-outline-variant shadow-2xl overflow-y-auto p-5 z-40"
      role="dialog"
      aria-modal="true"
      aria-labelledby={detail ? `flag-${detail.id}-title` : undefined}
    >
      <div className="flex justify-between items-start mb-4">
        <div className="flex gap-2 items-center">
          <button onClick={onPrev} disabled={!hasPrev} className="opacity-50 hover:opacity-100 disabled:opacity-20" aria-label="Previous flag">←</button>
          <button onClick={onNext} disabled={!hasNext} className="opacity-50 hover:opacity-100 disabled:opacity-20" aria-label="Next flag">→</button>
        </div>
        <button onClick={onClose} className="text-lg opacity-50 hover:opacity-100" aria-label="Close">×</button>
      </div>

      {isLoading && <div className="text-sm opacity-60">Loading…</div>}
      {isError && (
        <div>
          <div className="text-sm text-error">Couldn&apos;t load flag.</div>
          <button onClick={onClose} className="mt-3 text-sm underline">Close</button>
        </div>
      )}

      {detail && (
        <>
          <div className="mb-3.5">
            <div className="flex items-center gap-2 mb-1.5">
              <ReasonBadge reason={detail.reason} />
              <span className="text-[10px] opacity-50">Flag #{detail.id}</span>
            </div>
            <div id={`flag-${detail.id}-title`} className="text-sm font-semibold">
              {detail.auction?.title ?? "(no auction context)"}
            </div>
            <div className="text-[11px] opacity-60 mt-0.5">
              {detail.auction && `Auction #${detail.auction.id} · `}
              {detail.auction?.sellerDisplayName && `seller ${detail.auction.sellerDisplayName} · `}
              detected {new Date(detail.detectedAt).toLocaleString()}
            </div>
          </div>

          <ReinstateBanner detail={detail} />
          <SiblingFlagWarning count={detail.siblingOpenFlagCount} />
          <FraudFlagEvidence detail={detail} />

          {detail.resolved ? (
            <div className="bg-surface-container border border-outline-variant rounded-md p-3 text-xs">
              <div className="opacity-60 mb-1">Resolved by {detail.resolvedByDisplayName ?? "—"}{detail.resolvedAt ? ` · ${new Date(detail.resolvedAt).toLocaleString()}` : ""}</div>
              <div className="whitespace-pre-wrap">{detail.adminNotes}</div>
            </div>
          ) : (
            <>
              <NotesField value={notes} onChange={setNotes} disabled={busy} />
              <div className="flex gap-2.5 mt-4">
                <button
                  onClick={() => dismiss.mutate({ flagId: detail.id, adminNotes: notes }, { onSuccess: onClose })}
                  disabled={!notesValid || busy}
                  className="flex-1 px-3.5 py-2.5 bg-surface-container border border-outline-variant rounded-md text-sm disabled:opacity-50"
                >Dismiss</button>
                <button
                  onClick={() => reinstate.mutate({ flagId: detail.id, adminNotes: notes }, { onSuccess: onClose })}
                  disabled={!notesValid || !reinstateEnabled || busy}
                  className="flex-1 px-3.5 py-2.5 bg-primary text-on-primary rounded-md text-sm font-medium disabled:opacity-50"
                  title={reinstateEnabled ? "" : "Reinstate is only available when the auction is SUSPENDED"}
                >Reinstate auction</button>
              </div>
              {!reinstateEnabled && (
                <div className="text-[10px] opacity-40 mt-2">
                  Reinstate is disabled when the auction isn&apos;t SUSPENDED. For escrow / cancel-and-sell flags, only Dismiss applies.
                </div>
              )}
            </>
          )}
        </>
      )}
    </aside>
  );
}
```

- [ ] **Step 15: Create FraudFlagsListPage**

```tsx
// frontend/src/components/admin/fraud-flags/FraudFlagsListPage.tsx
"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { useMemo } from "react";
import { useAdminFraudFlagsList } from "@/hooks/admin/useAdminFraudFlagsList";
import { FraudFlagFilters } from "./FraudFlagFilters";
import { FraudFlagTable } from "./FraudFlagTable";
import { FraudFlagSlideOver } from "./FraudFlagSlideOver";
import type { FraudFlagListStatus, FraudFlagReason } from "@/lib/admin/types";

const PAGE_SIZE = 25;

export function FraudFlagsListPage() {
  const router = useRouter();
  const search = useSearchParams();

  const status = (search.get("status") as FraudFlagListStatus | null) ?? "open";
  const reasonsParam = search.get("reasons") ?? "";
  const reasons = reasonsParam ? reasonsParam.split(",") as FraudFlagReason[] : [];
  const page = Number(search.get("page") ?? "0");
  const flagIdParam = search.get("flagId");
  const flagId = flagIdParam ? Number(flagIdParam) : null;

  const filters = { status, reasons, page, size: PAGE_SIZE };
  const { data: list, isLoading } = useAdminFraudFlagsList(filters);

  const updateUrl = (next: Partial<{ status: FraudFlagListStatus; reasons: FraudFlagReason[]; page: number; flagId: number | null }>) => {
    const params = new URLSearchParams(search.toString());
    if (next.status !== undefined) params.set("status", next.status);
    if (next.reasons !== undefined) {
      if (next.reasons.length === 0) params.delete("reasons");
      else params.set("reasons", next.reasons.join(","));
    }
    if (next.page !== undefined) params.set("page", String(next.page));
    if (next.flagId !== undefined) {
      if (next.flagId === null) params.delete("flagId");
      else params.set("flagId", String(next.flagId));
    }
    router.replace(`/admin/fraud-flags?${params.toString()}`);
  };

  const ids = useMemo(() => list?.content.map(r => r.id) ?? [], [list]);
  const idx = flagId !== null ? ids.indexOf(flagId) : -1;
  const hasPrev = idx > 0;
  const hasNext = idx >= 0 && idx < ids.length - 1;

  return (
    <>
      <div className="flex justify-between items-baseline mb-4">
        <div>
          <h1 className="text-2xl font-semibold">Fraud Flags</h1>
          <div className="text-xs opacity-60 mt-0.5">
            {list ? `${list.totalElements} ${status === "all" ? "total" : status}` : "Loading…"}
          </div>
        </div>
      </div>

      <FraudFlagFilters
        status={status}
        onStatusChange={s => updateUrl({ status: s, page: 0 })}
        reasons={reasons}
        onReasonsChange={r => updateUrl({ reasons: r, page: 0 })}
      />

      {isLoading && <div className="text-sm opacity-60 py-8">Loading…</div>}

      {list && (
        <>
          <FraudFlagTable
            rows={list.content}
            selectedId={flagId}
            onSelect={id => updateUrl({ flagId: id })}
          />
          <div className="flex justify-between items-center mt-3.5 text-[11px] opacity-50">
            <div>
              Showing {list.content.length} of {list.totalElements}
            </div>
            <div className="flex gap-3">
              <button
                onClick={() => updateUrl({ page: Math.max(page - 1, 0) })}
                disabled={page === 0}
                className="disabled:opacity-30"
              >‹ Prev</button>
              <span>{page + 1} / {list.totalPages}</span>
              <button
                onClick={() => updateUrl({ page: page + 1 })}
                disabled={page >= list.totalPages - 1}
                className="disabled:opacity-30"
              >Next ›</button>
            </div>
          </div>
        </>
      )}

      {flagId !== null && (
        <FraudFlagSlideOver
          flagId={flagId}
          onClose={() => updateUrl({ flagId: null })}
          onPrev={() => hasPrev && updateUrl({ flagId: ids[idx - 1] })}
          onNext={() => hasNext && updateUrl({ flagId: ids[idx + 1] })}
          hasPrev={hasPrev}
          hasNext={hasNext}
        />
      )}
    </>
  );
}
```

- [ ] **Step 16: Create the route page**

```tsx
// frontend/src/app/admin/fraud-flags/page.tsx
import { FraudFlagsListPage } from "@/components/admin/fraud-flags/FraudFlagsListPage";

export default function AdminFraudFlagsRoute() {
  return <FraudFlagsListPage />;
}
```

- [ ] **Step 17: Write FraudFlagsListPage integration test**

```tsx
// frontend/src/components/admin/fraud-flags/FraudFlagsListPage.test.tsx
import { describe, it, expect, vi } from "vitest";
import { render, screen, waitFor, fireEvent } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { FraudFlagsListPage } from "./FraudFlagsListPage";
import { server } from "@/test/msw/server";
import { adminHandlers } from "@/test/msw/handlers";
import type { AdminFraudFlagDetail, AdminFraudFlagSummary } from "@/lib/admin/types";

const replace = vi.fn();
const searchParams = new URLSearchParams({ status: "open" });
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace }),
  useSearchParams: () => searchParams,
}));

const sampleRow: AdminFraudFlagSummary = {
  id: 1, reason: "OWNERSHIP_CHANGED_TO_UNKNOWN",
  detectedAt: new Date().toISOString(), auctionId: 100,
  auctionTitle: "Sample listing", auctionStatus: "SUSPENDED",
  parcelRegionName: "TestRegion", parcelLocalId: 7,
  resolved: false, resolvedAt: null, resolvedByDisplayName: null,
};

const sampleDetail: AdminFraudFlagDetail = {
  id: 1, reason: "OWNERSHIP_CHANGED_TO_UNKNOWN",
  detectedAt: new Date().toISOString(), resolvedAt: null,
  resolvedByDisplayName: null, adminNotes: null,
  auction: {
    id: 100, title: "Sample listing", status: "SUSPENDED",
    endsAt: new Date(Date.now() + 3_600_000).toISOString(),
    suspendedAt: new Date(Date.now() - 3_600_000).toISOString(),
    sellerUserId: 50, sellerDisplayName: "Seller Name",
  },
  evidenceJson: { expected_owner: "5fb3c9aa-1111-2222-3333-aaaaaaaae421" },
  linkedUsers: {},
  siblingOpenFlagCount: 0,
};

function wrap() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <FraudFlagsListPage />
    </QueryClientProvider>
  );
}

describe("FraudFlagsListPage", () => {
  it("renders the row and opens slide-over when clicked", async () => {
    server.use(adminHandlers.fraudFlagsListSuccess([sampleRow]));
    server.use(adminHandlers.fraudFlagDetailSuccess(sampleDetail));

    wrap();

    await waitFor(() => expect(screen.queryByText("Sample listing")).not.toBeNull());
    // Click the row.
    fireEvent.click(screen.getByText(/Sample listing/));
    // URL update.
    expect(replace).toHaveBeenCalledWith(expect.stringContaining("flagId=1"));
  });

  it("renders empty state when no rows", async () => {
    server.use(adminHandlers.fraudFlagsListSuccess([]));
    wrap();
    await waitFor(() => expect(screen.queryByText(/No fraud flags match/)).not.toBeNull());
  });
});
```

- [ ] **Step 18: Run all frontend tests**

Run: `cd frontend && npx tsc --noEmit && npm test -q`
Expected: PASS.

- [ ] **Step 19: Manual smoke test**

The user runs the dev server (do not start `npm run dev` yourself per project convention). They visit `/admin/fraud-flags`, confirm a row + slide-over render, and signal back. Document any visual regressions in the PR.

- [ ] **Step 20: Commit 10c**

```bash
git add frontend/src/app/admin/fraud-flags/ \
        frontend/src/components/admin/fraud-flags/FraudFlagSlideOver.tsx \
        frontend/src/components/admin/fraud-flags/FraudFlagsListPage.tsx \
        frontend/src/components/admin/fraud-flags/FraudFlagsListPage.test.tsx

git commit -m "$(cat <<'EOF'
feat(admin): /admin/fraud-flags list + slide-over with prev/next

URL state: status=open|resolved|all, reasons=..., page=N, flagId=N.
Slide-over renders detail with reinstate banner (SUSPENDED-only,
duration), sibling-flag warning, evidence (UUIDs link to /users/{id}),
required notes (1000 char), Dismiss + Reinstate (disabled when not
SUSPENDED). Prev/Next arrows walk the current filtered list.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 11: Cross-cutting docs + manual test + push branch

**Goal:** Close out the sub-spec — DEFERRED_WORK ledger sweep, FOOTGUNS additions (F.99–F.102), DESIGN.md §8 notes, full test suite run, branch push, PR open (NOT auto-merged).

**Files:**
- Modify: `docs/implementation/DEFERRED_WORK.md`
- Modify: `docs/implementation/FOOTGUNS.md`
- Modify: `docs/initial-design/DESIGN.md`

- [ ] **Step 1: Run the full backend test suite**

Run: `cd backend && ./mvnw test -q`
Expected: PASS. If anything is flaky, look at the output and either fix or document. The pre-existing flaky `AuctionRepositoryOwnershipCheckTest` may or may not fire — same handling pattern as Epic 09 sub-spec 3 (note in PR if it does).

- [ ] **Step 2: Run the full frontend test suite + lint**

Run: `cd frontend && npx tsc --noEmit && npm test -- --run && npm run lint`
Expected: PASS, lint clean (or only pre-existing warnings — diff against main if unsure).

- [ ] **Step 3: Update DEFERRED_WORK.md**

Read `docs/implementation/DEFERRED_WORK.md` first. Find these entries and update:

**Close (remove entirely):**

- "Admin dashboard for fraud_flag resolution" (Epic 03 sub-spec 2). Sub-spec 1 ships the admin dashboard for fraud-flag resolution.

**Modify:**

- "Non-dev admin endpoint for ownership-monitor trigger" (Epic 03) — leave entry, but note its scope is now narrower since admin role + auth gate exists. Remaining work: the endpoint itself + a one-off trigger button in the slide-over (sub-spec 3).

- "Account deletion UI" (Epic 02) — leave entry; the cascade matrix work is still outstanding. Sub-spec 1 only adds Role enum, not deletion plumbing.

**Add (none new from this sub-spec — it closes work rather than deferring it).**

- [ ] **Step 4: Update FOOTGUNS.md with F.99–F.102**

Append four new entries at the end, mirroring the existing entry shape (`### F.NN — short title`, paragraph or bullets, scope tags). Confirm last existing number is F.98 before numbering.

```markdown
### F.99 — Admin bootstrap config WILL re-promote a deliberately-demoted bootstrap email

The `slpa.admin.bootstrap-emails` list is a forward-promote-on-startup
mechanism, not a configurable opt-out. The `WHERE u.role = 'USER'` guard
catches deliberately-demoted bootstrap emails on next restart and
re-promotes them. To permanently demote a bootstrap email, **remove it
from the config list** AND bump `tokenVersion` (else outstanding tokens
keep working until expiry). Documented as intentional in spec
2026-04-26 §10.6.

### F.100 — Demoting an admin requires both `role = USER` AND `tokenVersion + 1`

`UPDATE users SET role = 'USER' WHERE id = ?` alone is insufficient.
Existing access tokens carry `role: "ADMIN"` in their JWT claim and stay
valid until expiry — a demoted admin keeps full access for up to one
access-token lifetime. The `tv` bump invalidates all outstanding
tokens. Either do both ops in one transaction (preferred) or bump tv as
the LAST step so the role flip is observable across the cluster before
tokens get invalidated.

### F.101 — JWT-claim authority mapping is the only source of `ROLE_*` authorities

`hasRole("ADMIN")` in SecurityConfig depends on JwtAuthenticationFilter
emitting `ROLE_ADMIN` authority. The filter reads `principal.role()` and
prefixes with `ROLE_`. If the filter's third constructor arg is changed
back to empty `List.of()` for any reason, ALL admin matchers silently
fail closed (every request 403s). Tests at `AdminAuthGateSliceTest`
verify the round-trip — they are the canary.

### F.102 — Stats endpoint is uncached and runs 10 queries per page load

`GET /api/v1/admin/stats` runs three `count(*)` against fraud_flags +
escrows, four `count(*)` against auctions/users/escrows + the
`countByStateNotIn` set check, and two `sum(*)` against escrows. Single
read-only transaction, no Redis cache. Acceptable today (admin traffic
is low). If pre-launch volume makes the dashboard noticeably slow, the
fix is a 30-second Redis cache, NOT N+1 fixes — there are no joins to
optimize.
```

- [ ] **Step 5: Append DESIGN.md §8 sub-spec notes**

In `docs/initial-design/DESIGN.md`, find Section 8 ("Edge Cases & Risk Mitigation") — add a subsection at the end of §8:

```markdown
### Notes (Epic 10 sub-spec 1 — Admin foundation + fraud-flag triage, 2026-04-26)

- Admin authority is a `User.role` enum (`USER` | `ADMIN`) propagated via
  JWT `role` claim. Demoting an admin requires bumping `tokenVersion`
  alongside the role flip (FOOTGUNS §F.100).

- The bootstrap config (`slpa.admin.bootstrap-emails`) seeds initial
  admins on app startup. It is a forward-push promotion mechanism —
  removing an email from the config does NOT demote a user, and a
  deliberately-demoted bootstrap email gets re-promoted on next restart
  (FOOTGUNS §F.99). The four bootstrap emails (heath@slparcels.com,
  heath@slparcelauctions.com, heath@hadronsoftware.com,
  heath.barcus@gmail.com) live in `application.yml`.

- Fraud-flag resolution flow: an admin can `dismiss` (mark resolved with
  notes, no state change) or `reinstate` (mark resolved + flip auction
  SUSPENDED→ACTIVE + extend `endsAt` by suspension duration + clear
  `suspendedAt` + re-engage bot monitor for BOT-tier auctions + publish
  `LISTING_REINSTATED` notification). Sibling open flags on the same
  auction are NOT auto-resolved — admin walks them via the slide-over
  prev/next arrows.

- Time math on reinstate uses `Auction.suspendedAt` (set on first
  suspension by `SuspensionService`, idempotent across re-flagging),
  with fallback to `flag.detectedAt` for historical rows that
  pre-existed the column.

- Admin dashboard surfaces 9 numbers: 3 queue counts (open fraud flags,
  pending escrow payments, active disputes) + 6 platform stats (active
  listings, total users, active escrows, completed sales, gross L$,
  commission L$). Lifetime totals only; no caching this sub-spec
  (FOOTGUNS §F.102).

- Reason badges color-coded by source family: ownership monitor (red),
  bot monitor (amber), escrow monitor (purple), post-cancel watcher
  (teal).
```

- [ ] **Step 6: Commit docs**

```bash
git add docs/implementation/DEFERRED_WORK.md \
        docs/implementation/FOOTGUNS.md \
        docs/initial-design/DESIGN.md

git commit -m "$(cat <<'EOF'
docs(epic-10-sub-1): close 1 deferred entry, modify 2; FOOTGUNS x4; DESIGN.md notes

Closes "Admin dashboard for fraud_flag resolution" (Epic 03 sub-spec 2).
Modifies "Non-dev admin endpoint for ownership-monitor trigger" (Epic 03)
and "Account deletion UI" (Epic 02) to reflect what sub-spec 1 enables.
FOOTGUNS F.99–F.102 cover bootstrap re-promotion, role+tv demote
sequencing, JWT authority canary, and uncached stats endpoint.
DESIGN.md §8 gains an Epic 10 sub-spec 1 notes subsection.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 7: Push branch**

```bash
git push -u origin task/10-sub-1-admin-foundation-and-fraud-flag-triage
```

- [ ] **Step 8: Open PR (DO NOT auto-merge)**

```bash
gh pr create --title "Epic 10 sub-spec 1 — Admin foundation + fraud-flag triage" --body "$(cat <<'EOF'
## Summary

- Admin role + JWT claim + Spring Security gate on `/api/v1/admin/**`
- Bootstrap config `slpa.admin.bootstrap-emails` (4 emails) with idempotent startup promotion
- `Auction.suspendedAt` + `SuspensionService` first-suspension stamp; `FraudFlag.adminNotes`
- `GET /api/v1/admin/stats` (3 queue + 6 platform numbers) and dashboard at `/admin`
- Fraud-flag triage at `/admin/fraud-flags` — list, slide-over with prev/next, Dismiss + Reinstate (full-flow with bot re-engage + `LISTING_REINSTATED` notification + endsAt extension)
- `RequireAdmin`, admin nav links in Header / MobileMenu / UserMenuDropdown

## Test plan

- [ ] Backend: `cd backend && ./mvnw test` — all green
- [ ] Frontend: `cd frontend && npx tsc --noEmit && npm test && npm run lint` — all green
- [ ] Manual: bootstrap promotes the 4 emails on first deploy (verify via DB)
- [ ] Manual: demote one bootstrap admin via DB flip + tv bump → restart app → user is NOT re-promoted
- [ ] Manual: sign in as admin → /admin loads with stats; sign in as USER → admin link hidden, /admin redirects to /
- [ ] Manual: seed a fraud flag (existing dev endpoint), open slide-over, dismiss with notes → auction stays SUSPENDED, queue count drops
- [ ] Manual: seed a SUSPENDED auction with an open flag, reinstate with notes → auction goes ACTIVE, endsAt extended, seller notified, bot monitor task spawned

## Out of scope (next sub-specs)

- `/admin/users/{id}` admin user-detail page → sub-spec 2
- `admin_actions` cross-entity audit table → sub-spec 2
- Listing reports + bans + admin enforcement → sub-spec 2
- Escrow dispute / unfreeze / terminal-secret rotation / bot-pool health → sub-spec 3
- Account deletion UI + reminder schedulers + audit log viewer → sub-spec 4

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

Stop after PR creation. Do not auto-merge. Do not switch branches. Report PR URL in the implementer summary.

---

## Self-review checklist (run mentally before each task starts execution)

- ✅ **Spec coverage:** every section of the spec maps to a task. §3 architecture → all backend + frontend tasks. §4 backend pieces → Tasks 1-7. §5 frontend pieces → Tasks 8-10. §6 data flow scenarios → covered by integration tests in Tasks 5, 7. §7 API surface → Tasks 5, 6, 7. §8 testing strategy → tests embedded in each task. §9 nav touchpoint → Task 8. §10 edge cases → covered by tests + FOOTGUNS in Task 11. §11 wrap-up checklist → Task 11. §12 doc updates → Task 11. §13 risks → addressed in test design.

- ✅ **No placeholders:** every step has either real code, a real command + expected output, or an explicit "implementer adapts to project convention" note where the project's existing pattern is the source of truth.

- ✅ **Type consistency:** `AuthPrincipal` is used as 4-arg in Tasks 1, 2, 5-7. `AdminFraudFlagSummaryDto` and `AdminFraudFlagDetailDto` are stable across Tasks 6, 7. Hook names (`useAdminStats`, `useAdminFraudFlagsList`, `useAdminFraudFlag`, `useDismissFraudFlag`, `useReinstateFraudFlag`) match between definitions (Tasks 9, 10) and consumers. URL parameter names (`status`, `reasons`, `page`, `flagId`) match between backend controller and frontend page state.

- ✅ **TDD throughout:** every backend code change has a failing-test step before implementation. Frontend tests cover hooks + components.

- ✅ **Bite-sized:** no step does more than one thing. Each commit is a self-contained, reviewable diff.

