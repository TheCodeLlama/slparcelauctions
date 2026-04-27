# Epic 10 Sub-spec 2 — Reports + Bans + Admin Enforcement + Admin User Mgmt Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the moderation loop — `ListingReport` entity + report POST endpoint + admin queue/actions, `Ban` entity + Redis cache + 6-path enforcement, admin user search + user-detail page (6 tabs + 280px right rail + actions), `admin_actions` audit table, plus refactor sub-spec 1's reinstate logic into a shared `AdminAuctionService` primitive.

**Architecture:** Three new sub-packages under `com.slparcelauctions.backend.admin` (`reports`, `ban`, `users`), one new shared service (`AdminAuctionService`), one audit-table sub-package (`audit`). JPA `ddl-auto: update` adds three new tables (`listing_reports`, `bans`, `admin_actions`) and two columns (`User.dismissedReportsCount`, `CancellationLog.cancelledByAdminId`). Two new notification categories (`LISTING_WARNED`, `LISTING_REMOVED_BY_ADMIN`).

**Tech Stack:** Spring Boot 4 / Java 26 / Spring Security / Spring Data JPA / Postgres / Redis (spring-data-redis) · Next.js 16 / React 19 / TanStack Query v5 / MSW v2 / Vitest

**Spec:** [`docs/superpowers/specs/2026-04-26-epic-10-sub-2-reports-bans-admin-enforcement.md`](../specs/2026-04-26-epic-10-sub-2-reports-bans-admin-enforcement.md) (commit `4e586d1`)

**Branch:** `task/10-sub-2-reports-bans-admin-enforcement` (already created on the spec commit; subsequent commits go on this branch).

---

## Pre-implementation findings (read once, refer back as needed)

### Reference patterns and exact-shape signatures

#### Cancellation + bidder fan-out (existing)

- **`CancellationService.cancel`** — `backend/src/main/java/com/slparcelauctions/backend/auction/CancellationService.java:71+`. Bidder-fan-out call is at lines 183-185:
  ```java
  List<Long> allBidderIds = bidRepo.findDistinctBidderUserIdsByAuctionId(a.getId());
  notificationPublisher.listingCancelledBySellerFanout(
          a.getId(), allBidderIds, a.getTitle(), reason);
  ```
- **`NotificationPublisher.listingCancelledBySellerFanout`** — `notification/NotificationPublisher.java:49-51`:
  ```java
  void listingCancelledBySellerFanout(long auctionId, List<Long> activeBidderUserIds,
                                       String parcelName, String reason);
  ```
- **`NotificationPublisherImpl.listingCancelledBySellerFanout`** — `notification/NotificationPublisherImpl.java:299-327`. Body strings are CURRENTLY seller-attributed: `title = "Listing cancelled: " + parcelName; body = "The seller cancelled this listing. Reason: " + reason + ".";`. **Sub-spec 2 makes these cause-neutral so admin-cancel can call the same method.**
- **`NotificationDataBuilder.listingCancelledBySeller`** — `notification/NotificationDataBuilder.java:138-142`.
- **`BidRepository.findDistinctBidderUserIdsByAuctionId`** — `auction/BidRepository.java:134-135`. Reused for admin-cancel fan-out.
- **`CancellationLog`** entity — `auction/CancellationLog.java:26-78`. Fields: `id`, `auction`, `seller`, `cancelledFromStatus`, `hadBids`, `reason`, `penaltyKind`, `penaltyAmountL`, `cancelledAt` (`@CreationTimestamp`). Sub-spec 2 adds `cancelledByAdminId` (nullable).
- **`countPriorOffensesWithBids`** — `auction/CancellationLogRepository.java:23-25`:
  ```java
  @Query("SELECT count(c) FROM CancellationLog c WHERE c.seller.id = :sellerId AND c.hadBids = true")
  long countPriorOffensesWithBids(@Param("sellerId") Long sellerId);
  ```
  Sub-spec 2 adds `AND c.cancelledByAdminId IS NULL` to exclude admin cancels.

#### Auction listing creation (capture IP for ban enforcement)

- **`AuctionController.create`** — `auction/AuctionController.java:61-69`. Currently does NOT accept `HttpServletRequest`. Sub-spec 2 adds it.
- **`AuctionService.create`** — `auction/AuctionService.java:54`. Currently `Auction create(Long sellerId, AuctionCreateRequest req)`. Sub-spec 2 adds `String ipAddress`.
- **`BidController` IP-capture pattern** — `auction/BidController.java:52-68`. Mirror exactly: inject `HttpServletRequest httpRequest`, call `httpRequest.getRemoteAddr()`, pass to service.

#### Sub-spec 1 admin code (refactor targets)

- **`AdminFraudFlagService.reinstate`** — `admin/AdminFraudFlagService.java:155-197`. Sub-spec 2 refactors this to delegate to new `AdminAuctionService.reinstate`. Net behavior unchanged.
- **`AdminFraudFlagService` constructor deps** (`@RequiredArgsConstructor`) — `FraudFlagRepository`, `UserRepository`, `AuctionRepository`, `BotMonitorLifecycleService`, `NotificationPublisher`, `Clock`. The auction-related deps move to `AdminAuctionService`; the fraud-flag service keeps `FraudFlagRepository`, `UserRepository`, `AdminAuctionService`, `Clock`.
- **`AdminFraudFlagController.reinstate`** — `admin/AdminFraudFlagController.java:62-68`. Endpoint signature stays the same.
- **`AdminExceptionHandler`** — `admin/exception/AdminExceptionHandler.java:13-46`. New handlers append at the bottom.
- **`AdminApiError`** record — `admin/exception/AdminApiError.java:1-14`. Reuse as-is.
- **`BotMonitorLifecycleService.onAuctionResumed`** — `bot/BotMonitorLifecycleService.java:83-86`. Confirmed delegating to `onAuctionActivatedBot`.
- **`NotificationCategoryCheckConstraintInitializer`** — exists from sub-spec 1. Sub-spec 2 just adds new enum values to its set; the initializer auto-syncs the DB CHECK constraint on each app start.

#### Notifications

- **`NotificationCategory`** enum — `notification/NotificationCategory.java:20-24` (LISTING_STATUS group entries). Sub-spec 2 adds two new values after `LISTING_CANCELLED_BY_SELLER`:
  ```java
  LISTING_WARNED(NotificationGroup.LISTING_STATUS),
  LISTING_REMOVED_BY_ADMIN(NotificationGroup.LISTING_STATUS),
  ```
- **`NotificationPublisherImpl.listingReinstated`** — `notification/NotificationPublisherImpl.java:264+`. Mirror for the two new methods.
- **Group-keyed user prefs**: `User.notifyEmail` and `User.notifySlIm` are JSON keyed by group (`listing_status`, etc.). New categories in LISTING_STATUS group automatically inherit the existing user preference — **no User entity changes for preference routing**.

#### User entity + repository

- **`User`** entity — `user/User.java:30-224`. Sub-spec 2 adds `dismissedReportsCount` field next to `cancelledWithBids` (line 109). Already has `role` (sub-spec 1).
- **`UserRepository`** — `user/UserRepository.java:18-63`. Existing methods: `findByEmail`, `findBySlAvatarUuid`, `findAllBySlAvatarUuidIn`, `existsByEmail`, `findByIdForUpdate`, `findIdBySlAvatarUuid`, `bulkPromoteByEmailIfUser`. Sub-spec 2 adds `bumpTokenVersion(Long userId)` (`@Modifying @Query` UPDATE), the user-search query (`searchAdmin`), and new derived counters as needed.

#### RefreshToken (IP history source for admin user-detail)

- **`RefreshToken`** entity — `auth/RefreshToken.java:31-73`. Has `userId` (Long), `ipAddress` (String, length 45), `createdAt`, `lastUsedAt`. No new entity changes needed.
- **`RefreshTokenRepository`** — `auth/RefreshTokenRepository.java:11+`. Sub-spec 2 adds:
  ```java
  @Query("SELECT new com.slparcelauctions.backend.admin.users.dto.UserIpProjection(" +
      "rt.ipAddress, MIN(rt.createdAt), MAX(rt.lastUsedAt), COUNT(rt.id)) " +
      "FROM RefreshToken rt WHERE rt.userId = :userId AND rt.ipAddress IS NOT NULL " +
      "GROUP BY rt.ipAddress ORDER BY MIN(rt.createdAt) ASC")
  List<UserIpProjection> findIpSummaryByUserId(@Param("userId") Long userId);
  ```

#### Reports / Bans / AdminActions (new — confirmed not present)

No existing entities. Sub-spec 2 introduces all three.

#### Bid + ProxyBid bidder-fanout

- **`BidRepository.findDistinctBidderUserIdsByAuctionId`** — `auction/BidRepository.java:134-135`. Reuse for admin-cancel.
- **`ProxyBidRepository`** — no existing distinct-bidder-by-auction query. **Sub-spec 2 does NOT add proxy-bid coverage to admin-cancel fan-out.** Manual-bid fan-out is sufficient (matches existing seller-cancel behavior).

#### SecurityConfig

- **Admin matcher** — `config/SecurityConfig.java:216` already has `.requestMatchers("/api/v1/admin/**").hasRole("ADMIN")`. **No SecurityConfig change needed for sub-spec 2.** New admin endpoints inherit the gate.

#### SuspensionService

- Constructor deps — `auction/monitoring/SuspensionService.java:47-53`: `AuctionRepository`, `FraudFlagRepository`, `BotMonitorLifecycleService`, `NotificationPublisher`, `Clock`. Sub-spec 2 adds `suspendByAdmin(Auction auction, Long adminUserId, String notes)` mirroring the existing `suspendForXxx` shape.

#### FOOTGUNS index

- Last entry **F.102** (sub-spec 1's last). Sub-spec 2 appends F.103 onwards.

### Footguns to actively respect

1. **Admin-cancel must NOT count as a seller offense.** The `countPriorOffensesWithBids` query update is load-bearing. If the WHERE clause forgets `cancelledByAdminId IS NULL`, every admin-cancel bumps the seller's penalty ladder by one. Test covers it.
2. **Bidder fanout body becomes cause-neutral.** Updating `listingCancelledBySellerFanout` body strings affects existing seller-cancel notifications too. Confirm copy reads OK for both.
3. **`listing_reports` UNIQUE (auction_id, reporter_id)** is the upsert key. Concurrent POSTs from the same user → DataIntegrityViolation; catch + retry as update.
4. **Ban-create tv-bump only fires for AVATAR/BOTH bans where avatar maps to a registered user.** IP-only bans don't have a user account to invalidate.
5. **Redis cache for bans is 5-min TTL.** Cache invalidation on create/lift caps the stale window. Acceptable.
6. **Listing-creation IP capture didn't exist before.** `AuctionController.create` gains `HttpServletRequest`. Existing tests that invoke `auctionService.create(sellerId, req)` directly need an empty IP fixture (`""` or `null`) — the service signature changes too.
7. **`@TestPropertySource` propagation** — every new `@SpringBootTest` integration test must include the full 9-line scheduler-disable property block. Same as sub-spec 1.
8. **Self-demote 409** — `POST /admin/users/{id}/demote` on the calling admin returns 409 `SELF_DEMOTE_FORBIDDEN`. Test guards.
9. **Listing-level report actions only touch OPEN reports** (spec §4.4 fix). Suspend/Cancel/Warn must NOT reclassify DISMISSED reports — those represent deliberate per-report decisions.

### Conventions reminders

- **No new Flyway migrations.** All schema via JPA `ddl-auto: update`.
- **Lombok required** on backend classes. Records preferred for DTOs.
- **Vertical slices** — every task ships entity + repo + service + controller + tests where applicable.
- **Feature-based packages** — admin code lives in `com.slparcelauctions.backend.admin.{reports,ban,users,audit}` plus shared `AdminAuctionService` at `com.slparcelauctions.backend.admin`.
- **`PagedResponse<T>`** for paginated endpoints. Never raw `Page<T>`.
- **Frontend AGENTS.md** — Next.js 16 has breaking changes. Read `frontend/node_modules/next/dist/docs/` before writing new frontend code.
- **No emojis** unless the user explicitly asked.
- **No new comments** unless they explain non-obvious WHY.

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

| # | Title | Tests |
|---|---|---|
| 1 | `AdminAction` audit table + `AdminActionService` helper | unit + integration |
| 2 | `AdminAuctionService.reinstate` shared primitive + sub-spec 1 refactor + standalone endpoint | unit + slice + integration |
| 3 | `CancellationLog.cancelledByAdminId` + `cancelByAdmin` + `LISTING_REMOVED_BY_ADMIN` + cause-neutral fanout copy | unit + integration |
| 4 | `SuspensionService.suspendByAdmin` + `LISTING_WARNED` notification category | unit + integration |
| 5 | `ListingReport` entity + user-facing endpoints + `User.dismissedReportsCount` | unit + slice + integration |
| 6 | `AdminReportService` + `AdminReportController` + stats `openReports` extension | unit + slice + integration |
| 7 | `Ban` entity + `BanCheckService` + Redis cache + `RefreshToken` IPs query | unit + integration |
| 8 | Ban enforcement at 6 paths (register/login/SL-verify/bid/listing-create/listing-cancel) | integration |
| 9 | `AdminBanController` + `AdminBanService` + tv-bump + cache invalidation | unit + slice + integration |
| 10 | `AdminUserController` + `AdminUserService` + role mgmt + `AdminAudit` read endpoint | unit + slice + integration |
| 11 | Frontend foundations (`lib/admin` types/api/queryKeys/MSW) + dashboard 4-up | unit |
| 12 | Frontend reports UI (button + modal + admin queue + slide-over) | unit |
| 13 | Frontend bans UI (page + create modal) | unit |
| 14 | Frontend users UI (search + detail page with 6 tabs + right rail + recent IPs modal) | unit |
| 15 | Cross-cutting docs + manual test + push branch + open PR | manual |

---

## Task 1: `AdminAction` audit table + `AdminActionService` helper

**Goal:** Establish the audit-trail table and a thin helper service that every later admin write action calls. Foundation for tasks 3-10.

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/admin/audit/AdminAction.java` (entity)
- Create: `backend/src/main/java/com/slparcelauctions/backend/admin/audit/AdminActionType.java` (enum)
- Create: `backend/src/main/java/com/slparcelauctions/backend/admin/audit/AdminActionTargetType.java` (enum)
- Create: `backend/src/main/java/com/slparcelauctions/backend/admin/audit/AdminActionRepository.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/admin/audit/AdminActionService.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/admin/audit/AdminActionServiceTest.java` (unit, Mockito)
- Test: `backend/src/test/java/com/slparcelauctions/backend/admin/audit/AdminActionRepositoryTest.java` (`@SpringBootTest`)

- [ ] **Step 1: Create `AdminActionType` enum**

```java
// backend/src/main/java/com/slparcelauctions/backend/admin/audit/AdminActionType.java
package com.slparcelauctions.backend.admin.audit;

public enum AdminActionType {
    DISMISS_REPORT,
    WARN_SELLER_FROM_REPORT,
    SUSPEND_LISTING_FROM_REPORT,
    CANCEL_LISTING_FROM_REPORT,
    CREATE_BAN,
    LIFT_BAN,
    PROMOTE_USER,
    DEMOTE_USER,
    RESET_FRIVOLOUS_COUNTER,
    REINSTATE_LISTING
}
```

- [ ] **Step 2: Create `AdminActionTargetType` enum**

```java
// backend/src/main/java/com/slparcelauctions/backend/admin/audit/AdminActionTargetType.java
package com.slparcelauctions.backend.admin.audit;

public enum AdminActionTargetType {
    USER, LISTING, REPORT, FRAUD_FLAG, BAN
}
```

- [ ] **Step 3: Create `AdminAction` entity**

```java
// backend/src/main/java/com/slparcelauctions/backend/admin/audit/AdminAction.java
package com.slparcelauctions.backend.admin.audit;

import java.time.OffsetDateTime;
import java.util.Map;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.slparcelauctions.backend.user.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "admin_actions", indexes = {
    @Index(name = "idx_admin_actions_target",
           columnList = "target_type, target_id, created_at DESC"),
    @Index(name = "idx_admin_actions_admin",
           columnList = "admin_user_id, created_at DESC")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AdminAction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "admin_user_id", nullable = false)
    private User adminUser;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 40)
    private AdminActionType actionType;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 20)
    private AdminActionTargetType targetType;

    @Column(name = "target_id", nullable = false)
    private Long targetId;

    @Column(columnDefinition = "text")
    private String notes;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> details;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
```

- [ ] **Step 4: Create `AdminActionRepository`**

```java
// backend/src/main/java/com/slparcelauctions/backend/admin/audit/AdminActionRepository.java
package com.slparcelauctions.backend.admin.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminActionRepository extends JpaRepository<AdminAction, Long> {

    Page<AdminAction> findByTargetTypeAndTargetIdOrderByCreatedAtDesc(
        AdminActionTargetType targetType, Long targetId, Pageable pageable);

    Page<AdminAction> findByAdminUserIdOrderByCreatedAtDesc(
        Long adminUserId, Pageable pageable);
}
```

- [ ] **Step 5: Create `AdminActionService` helper**

```java
// backend/src/main/java/com/slparcelauctions/backend/admin/audit/AdminActionService.java
package com.slparcelauctions.backend.admin.audit;

import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import lombok.RequiredArgsConstructor;

/**
 * Helper for writing admin_actions audit rows. All admin write paths in
 * sub-specs 2+ call this. Sub-spec 1's fraud-flag actions self-audit on
 * FraudFlag.resolvedBy + adminNotes and do NOT write here.
 */
@Service
@RequiredArgsConstructor
public class AdminActionService {

    private final AdminActionRepository repository;
    private final UserRepository userRepository;

    @Transactional
    public AdminAction record(
            Long adminUserId,
            AdminActionType actionType,
            AdminActionTargetType targetType,
            Long targetId,
            String notes,
            Map<String, Object> details) {

        User admin = userRepository.findById(adminUserId)
            .orElseThrow(() -> new IllegalStateException("Admin user not found: " + adminUserId));

        return repository.save(AdminAction.builder()
            .adminUser(admin)
            .actionType(actionType)
            .targetType(targetType)
            .targetId(targetId)
            .notes(notes)
            .details(details == null ? Map.of() : details)
            .build());
    }
}
```

- [ ] **Step 6: Write unit test for `AdminActionService`**

```java
// backend/src/test/java/com/slparcelauctions/backend/admin/audit/AdminActionServiceTest.java
package com.slparcelauctions.backend.admin.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

@ExtendWith(MockitoExtension.class)
class AdminActionServiceTest {

    @Mock AdminActionRepository repository;
    @Mock UserRepository userRepository;

    @InjectMocks AdminActionService service;

    @Test
    void record_buildsRowAndSaves() {
        User admin = User.builder().id(7L).build();
        when(userRepository.findById(7L)).thenReturn(Optional.of(admin));
        when(repository.save(any(AdminAction.class))).thenAnswer(inv -> inv.getArgument(0));

        service.record(7L, AdminActionType.DISMISS_REPORT,
            AdminActionTargetType.REPORT, 42L, "frivolous claim", Map.of("k", "v"));

        ArgumentCaptor<AdminAction> captor = ArgumentCaptor.forClass(AdminAction.class);
        verify(repository).save(captor.capture());
        AdminAction saved = captor.getValue();
        assertThat(saved.getAdminUser()).isSameAs(admin);
        assertThat(saved.getActionType()).isEqualTo(AdminActionType.DISMISS_REPORT);
        assertThat(saved.getTargetType()).isEqualTo(AdminActionTargetType.REPORT);
        assertThat(saved.getTargetId()).isEqualTo(42L);
        assertThat(saved.getNotes()).isEqualTo("frivolous claim");
        assertThat(saved.getDetails()).isEqualTo(Map.of("k", "v"));
    }

    @Test
    void record_nullDetails_storedAsEmptyMap() {
        User admin = User.builder().id(7L).build();
        when(userRepository.findById(7L)).thenReturn(Optional.of(admin));
        when(repository.save(any(AdminAction.class))).thenAnswer(inv -> inv.getArgument(0));

        service.record(7L, AdminActionType.LIFT_BAN, AdminActionTargetType.BAN, 1L, "n", null);

        ArgumentCaptor<AdminAction> captor = ArgumentCaptor.forClass(AdminAction.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getDetails()).isEqualTo(Map.of());
    }
}
```

Run: `cd backend && ./mvnw test -Dtest=AdminActionServiceTest -q`
Expected: PASS.

- [ ] **Step 7: Write integration test for `AdminActionRepository`**

```java
// backend/src/test/java/com/slparcelauctions/backend/admin/audit/AdminActionRepositoryTest.java
package com.slparcelauctions.backend.admin.audit;

import static org.assertj.core.api.Assertions.assertThat;

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
class AdminActionRepositoryTest {

    @Autowired AdminActionRepository repository;
    @Autowired AdminActionService service;
    @Autowired UserRepository userRepo;

    private Long adminId;

    @BeforeEach
    void seed() {
        adminId = userRepo.save(User.builder()
            .email("admin-" + UUID.randomUUID() + "@x.com")
            .passwordHash("x").role(Role.ADMIN).tokenVersion(1L).build()).getId();
    }

    @AfterEach
    void cleanup() {
        repository.deleteAll();
        userRepo.deleteById(adminId);
    }

    @Test
    void writeAndQuery_byTargetUser() {
        service.record(adminId, AdminActionType.PROMOTE_USER,
            AdminActionTargetType.USER, 100L, "promoted", Map.of());
        service.record(adminId, AdminActionType.DEMOTE_USER,
            AdminActionTargetType.USER, 100L, "demoted", Map.of());
        service.record(adminId, AdminActionType.LIFT_BAN,
            AdminActionTargetType.BAN, 999L, "lifted", Map.of());

        var page = repository.findByTargetTypeAndTargetIdOrderByCreatedAtDesc(
            AdminActionTargetType.USER, 100L, PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(2L);
        assertThat(page.getContent().get(0).getActionType())
            .isEqualTo(AdminActionType.DEMOTE_USER); // newest first
    }
}
```

Run: `cd backend && ./mvnw test -Dtest=AdminActionRepositoryTest -q`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/admin/audit/ \
        backend/src/test/java/com/slparcelauctions/backend/admin/audit/

git commit -m "$(cat <<'EOF'
feat(admin): admin_actions audit table + AdminActionService helper

New entity (admin_actions) with AdminActionType (10 values, sub-spec 2
set) and AdminActionTargetType (USER, LISTING, REPORT, FRAUD_FLAG, BAN).
AdminActionService.record(...) is the single entry point every later
admin write action calls. Sub-spec 1's fraud-flag actions continue to
self-audit on FraudFlag.resolvedBy/adminNotes and do NOT write here.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: `AdminAuctionService.reinstate` shared primitive + sub-spec 1 refactor + standalone endpoint

**Goal:** Extract sub-spec 1's reinstate auction-state-change logic into a shared `AdminAuctionService` so both fraud-flag-resolution and report-resolution paths can call it. Add the standalone endpoint used by user-detail Listings tab "Reinstate" button.

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/admin/AdminAuctionService.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/admin/dto/AdminAuctionReinstateResponse.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/admin/AdminAuctionController.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/admin/dto/ReinstateAuctionRequest.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/admin/AdminFraudFlagService.java` (refactor `reinstate` to delegate)
- Test: `backend/src/test/java/com/slparcelauctions/backend/admin/AdminAuctionServiceTest.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/admin/AdminAuctionControllerSliceTest.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/admin/AdminAuctionReinstateIntegrationTest.java`

- [ ] **Step 1: Create `AdminAuctionService`**

```java
// backend/src/main/java/com/slparcelauctions/backend/admin/AdminAuctionService.java
package com.slparcelauctions.backend.admin;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.admin.exception.AuctionNotSuspendedException;
import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.exception.AuctionNotFoundException;
import com.slparcelauctions.backend.bot.BotMonitorLifecycleService;
import com.slparcelauctions.backend.notification.NotificationPublisher;

import lombok.RequiredArgsConstructor;

/**
 * Shared primitive for "flip SUSPENDED auction back to ACTIVE." Called
 * from sub-spec 1's fraud-flag reinstate path AND from sub-spec 2's
 * standalone /admin/auctions/{id}/reinstate endpoint. Auction-state-only
 * — caller is responsible for any flag/report state changes and audit
 * row writing (different callers want different audit semantics).
 */
@Service
@RequiredArgsConstructor
public class AdminAuctionService {

    private final AuctionRepository auctionRepo;
    private final BotMonitorLifecycleService botMonitorLifecycleService;
    private final NotificationPublisher notificationPublisher;
    private final Clock clock;

    @Transactional
    public AdminAuctionReinstateResult reinstate(
            Long auctionId,
            Optional<OffsetDateTime> fallbackSuspendedFrom) {

        Auction auction = auctionRepo.findByIdForUpdate(auctionId)
            .orElseThrow(() -> new AuctionNotFoundException(auctionId));
        if (auction.getStatus() != AuctionStatus.SUSPENDED) {
            throw new AuctionNotSuspendedException(auction.getStatus());
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime suspendedFrom = auction.getSuspendedAt() != null
            ? auction.getSuspendedAt()
            : fallbackSuspendedFrom.orElse(now); // 0-extension if no record

        Duration suspensionDuration = Duration.between(suspendedFrom, now);
        OffsetDateTime newEndsAt = auction.getEndsAt().plus(suspensionDuration);
        if (newEndsAt.isBefore(now)) {
            newEndsAt = now.plusHours(1);
        }

        auction.setStatus(AuctionStatus.ACTIVE);
        auction.setSuspendedAt(null);
        auction.setEndsAt(newEndsAt);
        auctionRepo.save(auction);

        botMonitorLifecycleService.onAuctionResumed(auction);

        notificationPublisher.listingReinstated(
            auction.getSeller().getId(), auction.getId(),
            auction.getTitle(), newEndsAt);

        return new AdminAuctionReinstateResult(auction, suspensionDuration, newEndsAt);
    }

    public record AdminAuctionReinstateResult(
        Auction auction, Duration suspensionDuration, OffsetDateTime newEndsAt
    ) {}
}
```

- [ ] **Step 2: Create response DTO**

```java
// backend/src/main/java/com/slparcelauctions/backend/admin/dto/AdminAuctionReinstateResponse.java
package com.slparcelauctions.backend.admin.dto;

import java.time.OffsetDateTime;

import com.slparcelauctions.backend.auction.AuctionStatus;

public record AdminAuctionReinstateResponse(
    Long auctionId,
    AuctionStatus status,
    OffsetDateTime newEndsAt,
    long suspensionDurationSeconds
) {}
```

- [ ] **Step 3: Create request DTO**

```java
// backend/src/main/java/com/slparcelauctions/backend/admin/dto/ReinstateAuctionRequest.java
package com.slparcelauctions.backend.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReinstateAuctionRequest(
    @NotBlank @Size(max = 1000) String notes
) {}
```

- [ ] **Step 4: Create `AdminAuctionController`**

```java
// backend/src/main/java/com/slparcelauctions/backend/admin/AdminAuctionController.java
package com.slparcelauctions.backend.admin;

import java.util.Optional;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.admin.AdminAuctionService.AdminAuctionReinstateResult;
import com.slparcelauctions.backend.admin.audit.AdminActionService;
import com.slparcelauctions.backend.admin.audit.AdminActionTargetType;
import com.slparcelauctions.backend.admin.audit.AdminActionType;
import com.slparcelauctions.backend.admin.dto.AdminAuctionReinstateResponse;
import com.slparcelauctions.backend.admin.dto.ReinstateAuctionRequest;
import com.slparcelauctions.backend.auth.AuthPrincipal;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/admin/auctions")
@RequiredArgsConstructor
public class AdminAuctionController {

    private final AdminAuctionService adminAuctionService;
    private final AdminActionService adminActionService;

    @PostMapping("/{id}/reinstate")
    public AdminAuctionReinstateResponse reinstate(
            @PathVariable("id") Long id,
            @Valid @RequestBody ReinstateAuctionRequest body,
            @AuthenticationPrincipal AuthPrincipal admin) {

        AdminAuctionReinstateResult result = adminAuctionService.reinstate(id, Optional.empty());
        adminActionService.record(admin.userId(),
            AdminActionType.REINSTATE_LISTING, AdminActionTargetType.LISTING, id,
            body.notes(), null);

        return new AdminAuctionReinstateResponse(
            result.auction().getId(),
            result.auction().getStatus(),
            result.newEndsAt(),
            result.suspensionDuration().toSeconds());
    }
}
```

- [ ] **Step 5: Refactor `AdminFraudFlagService.reinstate` to delegate**

In `AdminFraudFlagService.java`, change the constructor deps:
- REMOVE: `AuctionRepository auctionRepository`, `BotMonitorLifecycleService botMonitorLifecycleService`, `NotificationPublisher notificationPublisher`
- ADD: `AdminAuctionService adminAuctionService`
- KEEP: `FraudFlagRepository fraudFlagRepository`, `UserRepository userRepository`, `Clock clock`

Replace the body of `reinstate(Long flagId, Long adminUserId, String adminNotes)` with:

```java
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

    // Delegate auction-state change + notification to the shared primitive.
    adminAuctionService.reinstate(auction.getId(), Optional.of(flag.getDetectedAt()));

    // Resolve the flag separately. Fraud flags continue to self-audit
    // via FraudFlag.resolvedBy + adminNotes (no admin_actions row).
    OffsetDateTime now = OffsetDateTime.now(clock);
    User admin = userRepository.findById(adminUserId)
        .orElseThrow(() -> new IllegalStateException("Admin user not found: " + adminUserId));
    flag.setResolved(true);
    flag.setResolvedAt(now);
    flag.setResolvedBy(admin);
    flag.setAdminNotes(adminNotes);
    fraudFlagRepository.save(flag);

    return detail(flagId);
}
```

Imports to add: `java.util.Optional`, `com.slparcelauctions.backend.admin.AdminAuctionService`, `com.slparcelauctions.backend.admin.exception.AuctionNotSuspendedException`. Remove imports that are no longer used (`Duration`, `BotMonitorLifecycleService`, `NotificationPublisher`, `AuctionRepository`).

- [ ] **Step 6: Run sub-spec 1's reinstate tests to confirm refactor preserves behavior**

Run: `cd backend && ./mvnw test -Dtest=AdminFraudFlagServiceDismissReinstateTest,AdminFraudFlagReinstateIntegrationTest -q`
Expected: PASS. If any test fails, the refactor leaked behavior — fix and re-run.

- [ ] **Step 7: Write `AdminAuctionServiceTest` (unit, Mockito)**

```java
// backend/src/test/java/com/slparcelauctions/backend/admin/AdminAuctionServiceTest.java
package com.slparcelauctions.backend.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.slparcelauctions.backend.admin.exception.AuctionNotSuspendedException;
import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.bot.BotMonitorLifecycleService;
import com.slparcelauctions.backend.notification.NotificationPublisher;
import com.slparcelauctions.backend.user.User;

@ExtendWith(MockitoExtension.class)
class AdminAuctionServiceTest {

    @Mock AuctionRepository auctionRepo;
    @Mock BotMonitorLifecycleService botMonitorLifecycle;
    @Mock NotificationPublisher notificationPublisher;

    private final Clock clock = Clock.fixed(Instant.parse("2026-04-26T12:00:00Z"), ZoneOffset.UTC);

    private AdminAuctionService service() {
        return new AdminAuctionService(auctionRepo, botMonitorLifecycle, notificationPublisher, clock);
    }

    private Auction makeAuction(AuctionStatus status, OffsetDateTime endsAt, OffsetDateTime suspendedAt) {
        User seller = User.builder().id(1L).build();
        Auction a = Auction.builder()
            .id(100L).seller(seller).title("test").status(status)
            .endsAt(endsAt).suspendedAt(suspendedAt).build();
        return a;
    }

    @Test
    void reinstate_happyPath_extendsEndsAt_withSuspendedAt() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime suspendedAt = now.minusHours(6);
        OffsetDateTime endsAt = now.plusHours(2);
        Auction a = makeAuction(AuctionStatus.SUSPENDED, endsAt, suspendedAt);
        when(auctionRepo.findByIdForUpdate(100L)).thenReturn(Optional.of(a));
        when(auctionRepo.save(any(Auction.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = service().reinstate(100L, Optional.empty());

        assertThat(a.getStatus()).isEqualTo(AuctionStatus.ACTIVE);
        assertThat(a.getSuspendedAt()).isNull();
        assertThat(a.getEndsAt()).isEqualTo(endsAt.plusHours(6));
        verify(botMonitorLifecycle).onAuctionResumed(a);
        verify(notificationPublisher).listingReinstated(1L, 100L, "test", a.getEndsAt());
        assertThat(result.suspensionDuration().toHours()).isEqualTo(6L);
    }

    @Test
    void reinstate_fallbackUsedWhenSuspendedAtNull() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime fallback = now.minusHours(3);
        Auction a = makeAuction(AuctionStatus.SUSPENDED, now.plusHours(2), null);
        when(auctionRepo.findByIdForUpdate(100L)).thenReturn(Optional.of(a));
        when(auctionRepo.save(any(Auction.class))).thenAnswer(inv -> inv.getArgument(0));

        service().reinstate(100L, Optional.of(fallback));

        assertThat(a.getEndsAt().toEpochSecond())
            .isEqualTo(now.plusHours(2).plusHours(3).toEpochSecond());
    }

    @Test
    void reinstate_zeroExtensionWhenBothNull() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime endsAt = now.plusHours(2);
        Auction a = makeAuction(AuctionStatus.SUSPENDED, endsAt, null);
        when(auctionRepo.findByIdForUpdate(100L)).thenReturn(Optional.of(a));
        when(auctionRepo.save(any(Auction.class))).thenAnswer(inv -> inv.getArgument(0));

        service().reinstate(100L, Optional.empty());

        assertThat(a.getEndsAt()).isEqualTo(endsAt); // 0-extension
    }

    @Test
    void reinstate_clampsPastEndsAt_toNowPlus1h() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        // endsAt in the past + suspendedAt also in the past with no math saving it
        Auction a = makeAuction(AuctionStatus.SUSPENDED,
            now.minusDays(1), now.minusDays(2));
        when(auctionRepo.findByIdForUpdate(100L)).thenReturn(Optional.of(a));
        when(auctionRepo.save(any(Auction.class))).thenAnswer(inv -> inv.getArgument(0));

        service().reinstate(100L, Optional.empty());

        // Math: endsAt + (now - suspendedAt) = (-1d) + (2d) = +1d > now → fine
        // Construct an actually-past case:
        Auction b = makeAuction(AuctionStatus.SUSPENDED,
            now.minusDays(2), now.minusDays(1));
        when(auctionRepo.findByIdForUpdate(101L)).thenReturn(Optional.of(b));
        service().reinstate(101L, Optional.empty());
        // (-2d) + (1d) = -1d → clamp to now+1h
        assertThat(b.getEndsAt()).isEqualTo(now.plusHours(1));
    }

    @Test
    void reinstate_throwsWhenNotSuspended() {
        Auction a = makeAuction(AuctionStatus.CANCELLED, OffsetDateTime.now(clock).plusHours(1), null);
        when(auctionRepo.findByIdForUpdate(100L)).thenReturn(Optional.of(a));

        assertThatThrownBy(() -> service().reinstate(100L, Optional.empty()))
            .isInstanceOf(AuctionNotSuspendedException.class);
        verify(botMonitorLifecycle, never()).onAuctionResumed(any());
    }
}
```

Run: `cd backend && ./mvnw test -Dtest=AdminAuctionServiceTest -q`
Expected: PASS.

- [ ] **Step 8: Write controller slice test**

```java
// backend/src/test/java/com/slparcelauctions/backend/admin/AdminAuctionControllerSliceTest.java
package com.slparcelauctions.backend.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.slparcelauctions.backend.admin.AdminAuctionService.AdminAuctionReinstateResult;
import com.slparcelauctions.backend.admin.audit.AdminActionService;
import com.slparcelauctions.backend.admin.audit.AdminActionType;
import com.slparcelauctions.backend.admin.audit.AdminActionTargetType;
import com.slparcelauctions.backend.admin.exception.AuctionNotSuspendedException;
import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionStatus;
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
class AdminAuctionControllerSliceTest {

    @Autowired MockMvc mvc;
    @Autowired JwtService jwtService;

    @MockitoBean AdminAuctionService adminAuctionService;
    @MockitoBean AdminActionService adminActionService;

    private String adminToken() {
        return jwtService.issueAccessToken(new AuthPrincipal(99L, "a@x.com", 1L, Role.ADMIN));
    }

    @Test
    void reinstate_anonymous_returns401() throws Exception {
        mvc.perform(post("/api/v1/admin/auctions/100/reinstate")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"notes\":\"x\"}"))
           .andExpect(status().isUnauthorized());
    }

    @Test
    void reinstate_userRole_returns403() throws Exception {
        String token = jwtService.issueAccessToken(new AuthPrincipal(2L, "u@x.com", 1L, Role.USER));
        mvc.perform(post("/api/v1/admin/auctions/100/reinstate")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"notes\":\"x\"}"))
           .andExpect(status().isForbidden());
    }

    @Test
    void reinstate_emptyNotes_returns400() throws Exception {
        mvc.perform(post("/api/v1/admin/auctions/100/reinstate")
            .header("Authorization", "Bearer " + adminToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"notes\":\"\"}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void reinstate_notSuspended_returns409() throws Exception {
        when(adminAuctionService.reinstate(eq(100L), any()))
            .thenThrow(new AuctionNotSuspendedException(AuctionStatus.CANCELLED));

        mvc.perform(post("/api/v1/admin/auctions/100/reinstate")
            .header("Authorization", "Bearer " + adminToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"notes\":\"x\"}"))
           .andExpect(status().isConflict())
           .andExpect(jsonPath("$.code").value("AUCTION_NOT_SUSPENDED"))
           .andExpect(jsonPath("$.details.currentStatus").value("CANCELLED"));
    }

    @Test
    void reinstate_admin_returns200_writesAudit() throws Exception {
        Auction a = Auction.builder().id(100L).status(AuctionStatus.ACTIVE)
            .endsAt(OffsetDateTime.parse("2026-05-01T00:00:00Z")).build();
        when(adminAuctionService.reinstate(eq(100L), any()))
            .thenReturn(new AdminAuctionReinstateResult(a, Duration.ofHours(6),
                OffsetDateTime.parse("2026-05-01T00:00:00Z")));

        mvc.perform(post("/api/v1/admin/auctions/100/reinstate")
            .header("Authorization", "Bearer " + adminToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"notes\":\"verified\"}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.auctionId").value(100))
           .andExpect(jsonPath("$.status").value("ACTIVE"));

        verify(adminActionService).record(eq(99L),
            eq(AdminActionType.REINSTATE_LISTING),
            eq(AdminActionTargetType.LISTING),
            eq(100L), eq("verified"), any());
    }
}
```

Run: `cd backend && ./mvnw test -Dtest=AdminAuctionControllerSliceTest -q`
Expected: PASS.

- [ ] **Step 9: Write integration test (full flow + audit row)**

```java
// backend/src/test/java/com/slparcelauctions/backend/admin/AdminAuctionReinstateIntegrationTest.java
package com.slparcelauctions.backend.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.slparcelauctions.backend.admin.audit.AdminActionRepository;
import com.slparcelauctions.backend.admin.audit.AdminActionService;
import com.slparcelauctions.backend.admin.audit.AdminActionTargetType;
import com.slparcelauctions.backend.admin.audit.AdminActionType;
import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.VerificationMethod;
import com.slparcelauctions.backend.auction.VerificationTier;
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
class AdminAuctionReinstateIntegrationTest {

    @Autowired AdminAuctionService service;
    @Autowired AdminActionService adminActionService;
    @Autowired AdminActionRepository auditRepo;
    @Autowired AuctionRepository auctionRepo;
    @Autowired ParcelRepository parcelRepo;
    @Autowired UserRepository userRepo;
    @Autowired NotificationRepository notifRepo;

    @MockitoBean NotificationWsBroadcasterPort wsBroadcaster;

    private Long sellerId, adminId, parcelId, auctionId;

    @BeforeEach
    void seed() {
        User seller = userRepo.save(User.builder()
            .email("seller-" + UUID.randomUUID() + "@x.com")
            .passwordHash("x").role(Role.USER).tokenVersion(1L)
            .slAvatarUuid(UUID.randomUUID()).build());
        sellerId = seller.getId();
        User admin = userRepo.save(User.builder()
            .email("admin-" + UUID.randomUUID() + "@x.com")
            .passwordHash("x").role(Role.ADMIN).tokenVersion(1L).build());
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
            .suspendedAt(OffsetDateTime.now().minusHours(3))
            .verificationTier(VerificationTier.MANUAL)
            .verificationMethod(VerificationMethod.MANUAL_UUID)
            .startingBid(1L).endsAt(OffsetDateTime.now().plusHours(2))
            .build());
        auctionId = auction.getId();
    }

    @AfterEach
    void cleanup() {
        auditRepo.deleteAll();
        notifRepo.deleteAll();
        auctionRepo.deleteById(auctionId);
        parcelRepo.deleteById(parcelId);
        userRepo.deleteById(sellerId);
        userRepo.deleteById(adminId);
    }

    @Test
    void reinstate_endToEnd() {
        var result = service.reinstate(auctionId, Optional.empty());
        adminActionService.record(adminId, AdminActionType.REINSTATE_LISTING,
            AdminActionTargetType.LISTING, auctionId, "verified", null);

        Auction reloaded = auctionRepo.findById(auctionId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AuctionStatus.ACTIVE);
        assertThat(reloaded.getSuspendedAt()).isNull();
        assertThat(result.suspensionDuration().toHours()).isGreaterThanOrEqualTo(2L);

        assertThat(notifRepo.findAll())
            .anyMatch(n -> n.getCategory() == NotificationCategory.LISTING_REINSTATED
                        && n.getUserId().equals(sellerId));

        assertThat(auditRepo.findAll())
            .anyMatch(a -> a.getActionType() == AdminActionType.REINSTATE_LISTING
                        && a.getTargetId().equals(auctionId));
    }
}
```

Run: `cd backend && ./mvnw test -Dtest=AdminAuctionReinstateIntegrationTest -q`
Expected: PASS.

- [ ] **Step 10: Run full backend tests to confirm sub-spec 1 still passes**

Run: `cd backend && ./mvnw test -q`
Expected: PASS. Re-run once if `AuctionRepositoryOwnershipCheckTest` flakes.

- [ ] **Step 11: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/admin/AdminAuctionService.java \
        backend/src/main/java/com/slparcelauctions/backend/admin/AdminAuctionController.java \
        backend/src/main/java/com/slparcelauctions/backend/admin/dto/AdminAuctionReinstateResponse.java \
        backend/src/main/java/com/slparcelauctions/backend/admin/dto/ReinstateAuctionRequest.java \
        backend/src/main/java/com/slparcelauctions/backend/admin/AdminFraudFlagService.java \
        backend/src/test/java/com/slparcelauctions/backend/admin/AdminAuctionServiceTest.java \
        backend/src/test/java/com/slparcelauctions/backend/admin/AdminAuctionControllerSliceTest.java \
        backend/src/test/java/com/slparcelauctions/backend/admin/AdminAuctionReinstateIntegrationTest.java

git commit -m "$(cat <<'EOF'
feat(admin): AdminAuctionService.reinstate shared primitive + standalone endpoint

Sub-spec 1's fraud-flag reinstate refactored to delegate to a new shared
AdminAuctionService.reinstate(auctionId, fallbackSuspendedFrom). New
endpoint POST /api/v1/admin/auctions/{id}/reinstate writes an
admin_actions audit row (action=REINSTATE_LISTING). Used by user-detail
Listings tab Reinstate button for SUSPENDED auctions that lack a fraud
flag (sub-spec 2 admin-suspend path).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: `CancellationLog.cancelledByAdminId` + `cancelByAdmin` + `LISTING_REMOVED_BY_ADMIN` + cause-neutral fanout copy

**Goal:** Add admin-cancel path that skips the penalty ladder, sends LISTING_REMOVED_BY_ADMIN to the seller, and reuses the bidder fan-out (with cause-neutral copy update). Foundation for the reports queue's "Cancel listing" admin action.

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/CancellationLog.java` (add `cancelledByAdminId` column)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/CancellationLogRepository.java` (update query)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/CancellationService.java` (add `cancelByAdmin` method)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/notification/NotificationCategory.java` (add `LISTING_REMOVED_BY_ADMIN`)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/notification/NotificationCategoryCheckConstraintInitializer.java` (auto-syncs but verify)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/notification/NotificationPublisher.java` (add `listingRemovedByAdmin` + update fanout body)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/notification/NotificationPublisherImpl.java` (impl + cause-neutral fanout copy)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/notification/NotificationDataBuilder.java` (add `listingRemovedByAdmin`)
- Test: `backend/src/test/java/com/slparcelauctions/backend/auction/CancellationServiceCancelByAdminTest.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/auction/CancellationLogRepositoryTest.java` (extend if exists)

- [ ] **Step 1: Add `cancelledByAdminId` to `CancellationLog` entity**

In `CancellationLog.java`, add the field next to `penaltyAmountL`:

```java
@Column(name = "cancelled_by_admin_id")
private Long cancelledByAdminId;
```

- [ ] **Step 2: Update `countPriorOffensesWithBids` query to exclude admin cancels**

In `CancellationLogRepository.java`, replace the `@Query`:

```java
@Query("SELECT count(c) FROM CancellationLog c "
        + "WHERE c.seller.id = :sellerId "
        + "AND c.hadBids = true "
        + "AND c.cancelledByAdminId IS NULL")
long countPriorOffensesWithBids(@Param("sellerId") Long sellerId);
```

- [ ] **Step 3: Add `LISTING_REMOVED_BY_ADMIN` to NotificationCategory enum**

In `NotificationCategory.java`, add adjacent to `LISTING_CANCELLED_BY_SELLER`:

```java
LISTING_REMOVED_BY_ADMIN(NotificationGroup.LISTING_STATUS),
```

- [ ] **Step 4: Add `listingRemovedByAdmin` to `NotificationPublisher` interface**

```java
// in NotificationPublisher.java, near listingReinstated
void listingRemovedByAdmin(long sellerUserId, long auctionId, String parcelName, String reason);
```

- [ ] **Step 5: Implement `listingRemovedByAdmin` in `NotificationPublisherImpl`**

Locate `listingReinstated` (~line 264), add directly below:

```java
@Override
public void listingRemovedByAdmin(long sellerUserId, long auctionId, String parcelName, String reason) {
    String title = "Listing removed: " + parcelName;
    String body = "Your listing has been removed by SLPA staff. Reason: " + reason + ".";
    notificationService.publish(new NotificationEvent(
        sellerUserId, NotificationCategory.LISTING_REMOVED_BY_ADMIN, title, body,
        NotificationDataBuilder.listingRemovedByAdmin(auctionId, parcelName, reason),
        null
    ));
}
```

- [ ] **Step 6: Update `listingCancelledBySellerFanout` body to be cause-neutral**

In `NotificationPublisherImpl.java`, locate `listingCancelledBySellerFanout` (~lines 299-327). Replace the title and body strings:

```java
// OLD:
// String title = "Listing cancelled: " + parcelName;
// String body = "The seller cancelled this listing. Reason: " + reason + ".";

// NEW (cause-neutral — applies to both seller-cancel and admin-cancel callers):
String title = "Auction cancelled: " + parcelName;
String body = "This auction has been cancelled. Your active proxy bid is no longer in effect. Reason: " + reason + ".";
```

- [ ] **Step 7: Add `listingRemovedByAdmin` to `NotificationDataBuilder`**

In `NotificationDataBuilder.java`, add near `listingCancelledBySeller`:

```java
public static Map<String, Object> listingRemovedByAdmin(long auctionId, String parcelName, String reason) {
    Map<String, Object> m = base(auctionId, parcelName);
    m.put("reason", reason);
    return m;
}
```

- [ ] **Step 8: Add `cancelByAdmin` to `CancellationService`**

Append to `CancellationService.java`:

```java
@Transactional
public Auction cancelByAdmin(Long auctionId, Long adminUserId, String notes) {
    Auction a = auctionRepo.findByIdForUpdate(auctionId)
        .orElseThrow(() -> new AuctionNotFoundException(auctionId));
    if (!CANCELLABLE.contains(a.getStatus())) {
        throw new InvalidAuctionStateException(a.getId(), a.getStatus(), "ADMIN_CANCEL");
    }

    // Skip penalty ladder. Skip seller-row lock — no seller-side state changes.
    boolean hadBids = a.getBidCount() != null && a.getBidCount() > 0;
    AuctionStatus from = a.getStatus();

    logRepo.save(CancellationLog.builder()
        .auction(a).seller(a.getSeller())
        .cancelledFromStatus(from.name())
        .hadBids(hadBids)
        .reason(notes)
        .penaltyKind(CancellationOffenseKind.NONE)
        .penaltyAmountL(null)
        .cancelledByAdminId(adminUserId)
        .build());

    a.setStatus(AuctionStatus.CANCELLED);
    auctionRepo.save(a);

    monitorLifecycle.onAuctionClosed(a);
    broadcastPublisher.publishStatusChanged(a);

    // Seller side: distinct LISTING_REMOVED_BY_ADMIN category.
    notificationPublisher.listingRemovedByAdmin(
        a.getSeller().getId(), a.getId(), a.getTitle(), notes);

    // Bidder side: same fan-out as seller-driven cancel (cause-neutral copy
    // applies to both callers). Bidders need to know their proxy is gone.
    if (hadBids) {
        var bidderIds = bidRepo.findDistinctBidderUserIdsByAuctionId(a.getId());
        notificationPublisher.listingCancelledBySellerFanout(
            a.getId(), bidderIds, a.getTitle(), notes);
    }

    return a;
}
```

- [ ] **Step 9: Write integration test**

```java
// backend/src/test/java/com/slparcelauctions/backend/auction/CancellationServiceCancelByAdminTest.java
package com.slparcelauctions.backend.auction;

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
import org.springframework.test.context.bean.override.mockito.MockitoBean;

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
class CancellationServiceCancelByAdminTest {

    @Autowired CancellationService cancellationService;
    @Autowired CancellationLogRepository logRepo;
    @Autowired AuctionRepository auctionRepo;
    @Autowired ParcelRepository parcelRepo;
    @Autowired UserRepository userRepo;
    @Autowired NotificationRepository notifRepo;

    @MockitoBean NotificationWsBroadcasterPort wsBroadcaster;

    private Long sellerId, adminId, parcelId, auctionId;

    @BeforeEach
    void seed() {
        User seller = userRepo.save(User.builder()
            .email("seller-" + UUID.randomUUID() + "@x.com")
            .passwordHash("x").role(Role.USER).tokenVersion(1L).build());
        sellerId = seller.getId();
        adminId = userRepo.save(User.builder()
            .email("admin-" + UUID.randomUUID() + "@x.com")
            .passwordHash("x").role(Role.ADMIN).tokenVersion(1L).build()).getId();

        Parcel parcel = parcelRepo.save(Parcel.builder()
            .slParcelUuid(UUID.randomUUID()).regionName("R")
            .ownerUuid(UUID.randomUUID()).areaSqm(512)
            .positionX(BigDecimal.ZERO).positionY(BigDecimal.ZERO).positionZ(BigDecimal.ZERO)
            .build());
        parcelId = parcel.getId();

        Auction auction = auctionRepo.save(Auction.builder()
            .seller(seller).parcel(parcel).title("Test")
            .status(AuctionStatus.ACTIVE)
            .verificationTier(VerificationTier.MANUAL)
            .verificationMethod(VerificationMethod.MANUAL_UUID)
            .startingBid(1L).endsAt(OffsetDateTime.now().plusHours(2))
            .bidCount(0)
            .build());
        auctionId = auction.getId();
    }

    @AfterEach
    void cleanup() {
        notifRepo.deleteAll();
        logRepo.deleteAll();
        auctionRepo.deleteById(auctionId);
        parcelRepo.deleteById(parcelId);
        userRepo.deleteById(sellerId);
        userRepo.deleteById(adminId);
    }

    @Test
    void cancelByAdmin_flipsCancelled_writesLogWithCancelledByAdminId() {
        cancellationService.cancelByAdmin(auctionId, adminId, "removed for TOS violation");

        Auction reloaded = auctionRepo.findById(auctionId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AuctionStatus.CANCELLED);

        var logs = logRepo.findAll();
        var log = logs.stream().filter(l -> l.getAuction().getId().equals(auctionId))
            .findFirst().orElseThrow();
        assertThat(log.getCancelledByAdminId()).isEqualTo(adminId);
        assertThat(log.getPenaltyKind()).isEqualTo(CancellationOffenseKind.NONE);
    }

    @Test
    void cancelByAdmin_doesNotIncrementSellerCancelledWithBidsCounter() {
        long before = userRepo.findById(sellerId).orElseThrow().getCancelledWithBids();
        cancellationService.cancelByAdmin(auctionId, adminId, "removed");
        long after = userRepo.findById(sellerId).orElseThrow().getCancelledWithBids();
        assertThat(after).isEqualTo(before);
    }

    @Test
    void cancelByAdmin_priorOffensesQueryExcludesAdminCancel() {
        // Seed an admin-cancel for sellerId.
        cancellationService.cancelByAdmin(auctionId, adminId, "removed");
        long count = logRepo.countPriorOffensesWithBids(sellerId);
        assertThat(count).isZero();
    }

    @Test
    void cancelByAdmin_publishesListingRemovedByAdminToSeller() {
        cancellationService.cancelByAdmin(auctionId, adminId, "removed");

        assertThat(notifRepo.findAll())
            .anyMatch(n -> n.getCategory() == NotificationCategory.LISTING_REMOVED_BY_ADMIN
                        && n.getUserId().equals(sellerId));
    }
}
```

Run: `cd backend && ./mvnw test -Dtest=CancellationServiceCancelByAdminTest -q`
Expected: PASS.

- [ ] **Step 10: Verify existing seller-cancel tests still pass with cause-neutral copy update**

Run: `cd backend && ./mvnw test -Dtest='*Cancellation*' -q`
Expected: PASS. If any existing test asserts the OLD body string ("The seller cancelled this listing"), update its assertion to match the new cause-neutral copy ("This auction has been cancelled").

- [ ] **Step 11: Run full backend test suite**

Run: `cd backend && ./mvnw test -q`
Expected: PASS.

- [ ] **Step 12: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auction/CancellationLog.java \
        backend/src/main/java/com/slparcelauctions/backend/auction/CancellationLogRepository.java \
        backend/src/main/java/com/slparcelauctions/backend/auction/CancellationService.java \
        backend/src/main/java/com/slparcelauctions/backend/notification/ \
        backend/src/test/java/com/slparcelauctions/backend/auction/CancellationServiceCancelByAdminTest.java

git commit -m "$(cat <<'EOF'
feat(admin): cancelByAdmin path skips penalty ladder + LISTING_REMOVED_BY_ADMIN

CancellationLog.cancelledByAdminId column added; countPriorOffensesWithBids
excludes admin-cancel rows so they don't bump the seller's penalty ladder.
Seller gets distinct LISTING_REMOVED_BY_ADMIN notification; bidders get
the existing seller-cancel fan-out (body copy made cause-neutral so it
works for both callers). seller.cancelledWithBids is NOT incremented.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: `SuspensionService.suspendByAdmin` + `LISTING_WARNED` notification category

**Goal:** Add admin-driven suspend path (no fraud flag) + new LISTING_WARNED category for warn-seller report action.

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/monitoring/SuspensionService.java` (add `suspendByAdmin`)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/notification/NotificationCategory.java` (add `LISTING_WARNED`)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/notification/NotificationPublisher.java` (add `listingWarned`)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/notification/NotificationPublisherImpl.java` (impl)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/notification/NotificationDataBuilder.java` (add `listingWarned`)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/notification/slim/SlImLinkResolver.java` (add LISTING_WARNED + LISTING_REMOVED_BY_ADMIN switch entries — required because the switch is exhaustive over the enum; sub-spec 1 Task 7 hit this same trap)
- Test: `backend/src/test/java/com/slparcelauctions/backend/auction/monitoring/SuspensionServiceSuspendByAdminTest.java`

- [ ] **Step 1: Add `LISTING_WARNED` enum value**

In `NotificationCategory.java`, add (between LISTING_REMOVED_BY_ADMIN from Task 3 and the next existing value):

```java
LISTING_WARNED(NotificationGroup.LISTING_STATUS),
```

- [ ] **Step 2: Update SlImLinkResolver to handle the two new categories**

`SlImLinkResolver` has an exhaustive switch over `NotificationCategory`. Adding new enum values requires updating the switch. Locate `SlImLinkResolver.java` (under `notification/slim/`). Add cases for `LISTING_WARNED` and `LISTING_REMOVED_BY_ADMIN` that resolve to the auction detail URL (same pattern as `LISTING_SUSPENDED`):

```java
case LISTING_WARNED, LISTING_REMOVED_BY_ADMIN ->
    baseUrl + "/auction/" + data.get("auctionId");
```

If the existing switch already groups `LISTING_*` cases, add the two new constants to the same arm.

- [ ] **Step 3: Add publisher interface method**

In `NotificationPublisher.java`:

```java
void listingWarned(long sellerUserId, long auctionId, String parcelName, String notes);
```

- [ ] **Step 4: Implement in `NotificationPublisherImpl`**

Add (near `listingSuspended`):

```java
@Override
public void listingWarned(long sellerUserId, long auctionId, String parcelName, String notes) {
    String title = "Warning on your listing: " + parcelName;
    String body = "An admin has reviewed reports on this listing and issued a warning. Notes: " + notes;
    notificationService.publish(new NotificationEvent(
        sellerUserId, NotificationCategory.LISTING_WARNED, title, body,
        NotificationDataBuilder.listingWarned(auctionId, parcelName, notes),
        null
    ));
}
```

- [ ] **Step 5: Add `NotificationDataBuilder.listingWarned`**

```java
public static Map<String, Object> listingWarned(long auctionId, String parcelName, String notes) {
    Map<String, Object> m = base(auctionId, parcelName);
    m.put("notes", notes);
    return m;
}
```

- [ ] **Step 6: Add `suspendByAdmin` to `SuspensionService`**

Append to `SuspensionService.java`:

```java
/**
 * Admin-driven suspension. No FraudFlag created — admin reason is captured
 * in the admin_actions audit row written by the caller. Sets suspendedAt
 * if currently null, mirroring suspendForOwnershipChange.
 */
@Transactional
public void suspendByAdmin(Auction auction, Long adminUserId, String notes) {
    OffsetDateTime now = OffsetDateTime.now(clock);
    auction.setStatus(AuctionStatus.SUSPENDED);
    if (auction.getSuspendedAt() == null) {
        auction.setSuspendedAt(now);
    }
    auctionRepo.save(auction);

    monitorLifecycle.onAuctionClosed(auction);

    notificationPublisher.listingSuspended(
        auction.getSeller().getId(), auction.getId(),
        auction.getTitle(), "Suspended by SLPA staff");
}
```

- [ ] **Step 7: Write integration test**

```java
// backend/src/test/java/com/slparcelauctions/backend/auction/monitoring/SuspensionServiceSuspendByAdminTest.java
package com.slparcelauctions.backend.auction.monitoring;

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
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.VerificationMethod;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.auction.fraud.FraudFlagRepository;
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
class SuspensionServiceSuspendByAdminTest {

    @Autowired SuspensionService suspensionService;
    @Autowired AuctionRepository auctionRepo;
    @Autowired ParcelRepository parcelRepo;
    @Autowired UserRepository userRepo;
    @Autowired FraudFlagRepository fraudFlagRepo;
    @Autowired NotificationRepository notifRepo;

    @MockitoBean NotificationWsBroadcasterPort wsBroadcaster;

    private Long sellerId, adminId, parcelId, auctionId;

    @BeforeEach
    void seed() {
        User seller = userRepo.save(User.builder()
            .email("seller-" + UUID.randomUUID() + "@x.com")
            .passwordHash("x").role(Role.USER).tokenVersion(1L)
            .slAvatarUuid(UUID.randomUUID()).build());
        sellerId = seller.getId();
        adminId = userRepo.save(User.builder()
            .email("admin-" + UUID.randomUUID() + "@x.com")
            .passwordHash("x").role(Role.ADMIN).tokenVersion(1L).build()).getId();

        Parcel parcel = parcelRepo.save(Parcel.builder()
            .slParcelUuid(UUID.randomUUID()).regionName("R")
            .ownerUuid(seller.getSlAvatarUuid()).areaSqm(1024)
            .positionX(BigDecimal.ZERO).positionY(BigDecimal.ZERO).positionZ(BigDecimal.ZERO)
            .build());
        parcelId = parcel.getId();

        Auction auction = auctionRepo.save(Auction.builder()
            .seller(seller).parcel(parcel).title("Test")
            .status(AuctionStatus.ACTIVE)
            .verificationTier(VerificationTier.MANUAL)
            .verificationMethod(VerificationMethod.MANUAL_UUID)
            .startingBid(1L).endsAt(OffsetDateTime.now().plusHours(2))
            .build());
        auctionId = auction.getId();
    }

    @AfterEach
    void cleanup() {
        notifRepo.deleteAll();
        fraudFlagRepo.findByAuctionId(auctionId).forEach(fraudFlagRepo::delete);
        auctionRepo.deleteById(auctionId);
        parcelRepo.deleteById(parcelId);
        userRepo.deleteById(sellerId);
        userRepo.deleteById(adminId);
    }

    @Test
    void suspendByAdmin_flipsSuspended_setsSuspendedAt_noFraudFlag() {
        Auction auction = auctionRepo.findById(auctionId).orElseThrow();
        suspensionService.suspendByAdmin(auction, adminId, "TOS abuse");

        Auction reloaded = auctionRepo.findById(auctionId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AuctionStatus.SUSPENDED);
        assertThat(reloaded.getSuspendedAt()).isNotNull();
        assertThat(fraudFlagRepo.findByAuctionId(auctionId)).isEmpty();
    }

    @Test
    void suspendByAdmin_publishesListingSuspendedToSeller() {
        Auction auction = auctionRepo.findById(auctionId).orElseThrow();
        suspensionService.suspendByAdmin(auction, adminId, "TOS abuse");

        assertThat(notifRepo.findAll())
            .anyMatch(n -> n.getCategory() == NotificationCategory.LISTING_SUSPENDED
                        && n.getUserId().equals(sellerId));
    }
}
```

Run: `cd backend && ./mvnw test -Dtest=SuspensionServiceSuspendByAdminTest -q`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auction/monitoring/SuspensionService.java \
        backend/src/main/java/com/slparcelauctions/backend/notification/ \
        backend/src/test/java/com/slparcelauctions/backend/auction/monitoring/SuspensionServiceSuspendByAdminTest.java

git commit -m "$(cat <<'EOF'
feat(admin): suspendByAdmin path + LISTING_WARNED category

SuspensionService.suspendByAdmin flips ACTIVE→SUSPENDED, sets suspendedAt
if null, calls onAuctionClosed (cancels bot monitor), publishes existing
LISTING_SUSPENDED with admin-flavored reason text. No FraudFlag created
— admin reason captured in admin_actions audit. New LISTING_WARNED
category (used by report's warn-seller action in Task 6).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: `ListingReport` entity + user-facing endpoints + `User.dismissedReportsCount`

**Goal:** Foundation for the reports feature — entity, repository, user-facing POST/GET endpoints, frivolous counter on User.

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/admin/reports/ListingReport.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/admin/reports/ListingReportReason.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/admin/reports/ListingReportStatus.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/admin/reports/ListingReportRepository.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/admin/reports/UserReportController.java` (user-facing endpoints under /api/v1/auctions/...)
- Create: `backend/src/main/java/com/slparcelauctions/backend/admin/reports/UserReportService.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/admin/reports/dto/ReportRequest.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/admin/reports/dto/MyReportResponse.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/admin/reports/exception/CannotReportOwnListingException.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/admin/reports/exception/MustBeVerifiedToReportException.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/admin/reports/exception/AuctionNotReportableException.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/admin/reports/exception/UserReportExceptionHandler.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/user/User.java` (add `dismissedReportsCount`)
- Test: `backend/src/test/java/com/slparcelauctions/backend/admin/reports/UserReportServiceTest.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/admin/reports/UserReportControllerSliceTest.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/admin/reports/ListingReportRepositoryTest.java`

- [ ] **Step 1: Create enums**

```java
// backend/src/main/java/com/slparcelauctions/backend/admin/reports/ListingReportReason.java
package com.slparcelauctions.backend.admin.reports;

public enum ListingReportReason {
    INACCURATE_DESCRIPTION,
    WRONG_TAGS,
    SHILL_BIDDING,
    FRAUDULENT_SELLER,
    DUPLICATE_LISTING,
    NOT_ACTUALLY_FOR_SALE,
    TOS_VIOLATION,
    OTHER
}
```

```java
// backend/src/main/java/com/slparcelauctions/backend/admin/reports/ListingReportStatus.java
package com.slparcelauctions.backend.admin.reports;

public enum ListingReportStatus {
    OPEN, REVIEWED, DISMISSED, ACTION_TAKEN
}
```

- [ ] **Step 2: Create `ListingReport` entity**

```java
// backend/src/main/java/com/slparcelauctions/backend/admin/reports/ListingReport.java
package com.slparcelauctions.backend.admin.reports;

import java.time.OffsetDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.user.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "listing_reports",
    uniqueConstraints = @UniqueConstraint(name = "uk_listing_reports_auction_reporter",
        columnNames = {"auction_id", "reporter_id"}),
    indexes = {
        @Index(name = "idx_listing_reports_status", columnList = "status, auction_id"),
        @Index(name = "idx_listing_reports_auction", columnList = "auction_id, updated_at DESC")
    })
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ListingReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "auction_id", nullable = false)
    private Auction auction;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reporter_id", nullable = false)
    private User reporter;

    @Column(length = 100, nullable = false)
    private String subject;

    @Enumerated(EnumType.STRING)
    @Column(length = 30, nullable = false)
    private ListingReportReason reason;

    @Column(columnDefinition = "text", nullable = false)
    private String details;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    @Builder.Default
    private ListingReportStatus status = ListingReportStatus.OPEN;

    @Column(name = "admin_notes", columnDefinition = "text")
    private String adminNotes;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by")
    private User reviewedBy;

    @Column(name = "reviewed_at")
    private OffsetDateTime reviewedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
```

- [ ] **Step 3: Create `ListingReportRepository`**

```java
// backend/src/main/java/com/slparcelauctions/backend/admin/reports/ListingReportRepository.java
package com.slparcelauctions.backend.admin.reports;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ListingReportRepository extends JpaRepository<ListingReport, Long> {

    Optional<ListingReport> findByAuctionIdAndReporterId(Long auctionId, Long reporterId);

    List<ListingReport> findByAuctionIdOrderByCreatedAtDesc(Long auctionId);

    long countByStatus(ListingReportStatus status);
}
```

- [ ] **Step 4: Add `dismissedReportsCount` to `User` entity**

In `User.java`, add next to `cancelledWithBids`:

```java
@Column(name = "dismissed_reports_count", nullable = false)
@Builder.Default
private long dismissedReportsCount = 0L;
```

- [ ] **Step 5: Create exception classes**

```java
// CannotReportOwnListingException.java
package com.slparcelauctions.backend.admin.reports.exception;

public class CannotReportOwnListingException extends RuntimeException {
    public CannotReportOwnListingException() { super("Cannot report your own listing"); }
}

// MustBeVerifiedToReportException.java
package com.slparcelauctions.backend.admin.reports.exception;

public class MustBeVerifiedToReportException extends RuntimeException {
    public MustBeVerifiedToReportException() { super("Verify your SL avatar to report listings"); }
}

// AuctionNotReportableException.java
package com.slparcelauctions.backend.admin.reports.exception;

import com.slparcelauctions.backend.auction.AuctionStatus;
import lombok.Getter;

@Getter
public class AuctionNotReportableException extends RuntimeException {
    private final AuctionStatus currentStatus;
    public AuctionNotReportableException(AuctionStatus s) {
        super("Auction is " + s + ", not reportable");
        this.currentStatus = s;
    }
}
```

- [ ] **Step 6: Create `UserReportExceptionHandler`**

```java
// backend/src/main/java/com/slparcelauctions/backend/admin/reports/exception/UserReportExceptionHandler.java
package com.slparcelauctions.backend.admin.reports.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "com.slparcelauctions.backend.admin.reports")
public class UserReportExceptionHandler {

    @ExceptionHandler(CannotReportOwnListingException.class)
    public ProblemDetail handleOwnListing(CannotReportOwnListingException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        pd.setTitle(ex.getMessage());
        pd.setProperty("code", "CANNOT_REPORT_OWN_LISTING");
        return pd;
    }

    @ExceptionHandler(MustBeVerifiedToReportException.class)
    public ProblemDetail handleNotVerified(MustBeVerifiedToReportException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        pd.setTitle(ex.getMessage());
        pd.setProperty("code", "VERIFICATION_REQUIRED");
        return pd;
    }

    @ExceptionHandler(AuctionNotReportableException.class)
    public ProblemDetail handleNotActive(AuctionNotReportableException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        pd.setTitle(ex.getMessage());
        pd.setProperty("code", "AUCTION_NOT_REPORTABLE");
        pd.setProperty("currentStatus", ex.getCurrentStatus().name());
        return pd;
    }
}
```

- [ ] **Step 7: Create DTOs**

```java
// ReportRequest.java
package com.slparcelauctions.backend.admin.reports.dto;

import com.slparcelauctions.backend.admin.reports.ListingReportReason;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ReportRequest(
    @NotBlank @Size(max = 100) String subject,
    @NotNull ListingReportReason reason,
    @NotBlank @Size(max = 2000) String details
) {}

// MyReportResponse.java
package com.slparcelauctions.backend.admin.reports.dto;

import java.time.OffsetDateTime;

import com.slparcelauctions.backend.admin.reports.ListingReportReason;
import com.slparcelauctions.backend.admin.reports.ListingReportStatus;

public record MyReportResponse(
    Long id, String subject, ListingReportReason reason, String details,
    ListingReportStatus status, OffsetDateTime createdAt, OffsetDateTime updatedAt
) {}
```

- [ ] **Step 8: Create `UserReportService`**

```java
// backend/src/main/java/com/slparcelauctions/backend/admin/reports/UserReportService.java
package com.slparcelauctions.backend.admin.reports;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.admin.reports.dto.MyReportResponse;
import com.slparcelauctions.backend.admin.reports.dto.ReportRequest;
import com.slparcelauctions.backend.admin.reports.exception.AuctionNotReportableException;
import com.slparcelauctions.backend.admin.reports.exception.CannotReportOwnListingException;
import com.slparcelauctions.backend.admin.reports.exception.MustBeVerifiedToReportException;
import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.exception.AuctionNotFoundException;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class UserReportService {

    private final ListingReportRepository repo;
    private final AuctionRepository auctionRepo;
    private final UserRepository userRepo;

    @Transactional
    public MyReportResponse upsertReport(Long auctionId, Long reporterId, ReportRequest req) {
        Auction auction = auctionRepo.findById(auctionId)
            .orElseThrow(() -> new AuctionNotFoundException(auctionId));
        User reporter = userRepo.findById(reporterId)
            .orElseThrow(() -> new IllegalStateException("Reporter not found: " + reporterId));

        if (!Boolean.TRUE.equals(reporter.getVerified())) {
            throw new MustBeVerifiedToReportException();
        }
        if (auction.getSeller().getId().equals(reporterId)) {
            throw new CannotReportOwnListingException();
        }
        if (auction.getStatus() != AuctionStatus.ACTIVE) {
            throw new AuctionNotReportableException(auction.getStatus());
        }

        ListingReport report = repo.findByAuctionIdAndReporterId(auctionId, reporterId)
            .orElseGet(() -> ListingReport.builder()
                .auction(auction).reporter(reporter)
                .build());
        report.setSubject(req.subject());
        report.setReason(req.reason());
        report.setDetails(req.details());
        report.setStatus(ListingReportStatus.OPEN); // resubmit resets to OPEN
        report.setReviewedBy(null);
        report.setReviewedAt(null);
        report.setAdminNotes(null);
        ListingReport saved = repo.save(report);
        return toMyReport(saved);
    }

    @Transactional(readOnly = true)
    public Optional<MyReportResponse> findMyReport(Long auctionId, Long reporterId) {
        return repo.findByAuctionIdAndReporterId(auctionId, reporterId)
            .map(this::toMyReport);
    }

    private MyReportResponse toMyReport(ListingReport r) {
        return new MyReportResponse(r.getId(), r.getSubject(), r.getReason(), r.getDetails(),
            r.getStatus(), r.getCreatedAt(), r.getUpdatedAt());
    }
}
```

- [ ] **Step 9: Create `UserReportController`**

```java
// backend/src/main/java/com/slparcelauctions/backend/admin/reports/UserReportController.java
package com.slparcelauctions.backend.admin.reports;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.admin.reports.dto.MyReportResponse;
import com.slparcelauctions.backend.admin.reports.dto.ReportRequest;
import com.slparcelauctions.backend.auth.AuthPrincipal;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/auctions/{auctionId}")
@RequiredArgsConstructor
public class UserReportController {

    private final UserReportService service;

    @PostMapping("/report")
    public MyReportResponse report(
            @PathVariable Long auctionId,
            @Valid @RequestBody ReportRequest body,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return service.upsertReport(auctionId, principal.userId(), body);
    }

    @GetMapping("/my-report")
    public ResponseEntity<MyReportResponse> myReport(
            @PathVariable Long auctionId,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return service.findMyReport(auctionId, principal.userId())
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.noContent().build());
    }
}
```

- [ ] **Step 10: Write integration test for the upsert + my-report flow**

```java
// backend/src/test/java/com/slparcelauctions/backend/admin/reports/UserReportServiceTest.java
// (Integration test using @SpringBootTest pattern; covers:
//  - first POST creates report with status=OPEN
//  - resubmit upserts and resets status=OPEN even when previously DISMISSED
//  - own-listing throws CannotReportOwnListingException
//  - unverified reporter throws MustBeVerifiedToReportException
//  - non-ACTIVE auction throws AuctionNotReportableException
//  - findMyReport returns Optional.empty when no row, populated when exists)
//
// Use the same seeding pattern as Task 3's CancellationServiceCancelByAdminTest.
// Assert against return-DTO shape.
```

The implementer writes the full test file mirroring Task 3's seeding pattern. Test cases above are MANDATORY — each case is an `@Test` method.

Run: `cd backend && ./mvnw test -Dtest=UserReportServiceTest -q`
Expected: PASS.

- [ ] **Step 11: Write controller slice test**

```java
// backend/src/test/java/com/slparcelauctions/backend/admin/reports/UserReportControllerSliceTest.java
// @SpringBootTest + @AutoConfigureMockMvc + @MockitoBean UserReportService
// Test cases:
//  - POST anonymous → 401
//  - POST verified user → 200 with MyReportResponse body
//  - POST unverified user → 403 with code:VERIFICATION_REQUIRED
//  - POST own-listing → 409 with code:CANNOT_REPORT_OWN_LISTING
//  - POST non-ACTIVE auction → 409 with code:AUCTION_NOT_REPORTABLE
//  - POST blank subject → 400
//  - POST details > 2000 chars → 400
//  - GET my-report no row → 204
//  - GET my-report with row → 200 with body
```

Mirror sub-spec 1's `AdminFraudFlagControllerWriteSliceTest` patterns. Mock service throws each exception type to verify the error handler response shapes.

Run: `cd backend && ./mvnw test -Dtest=UserReportControllerSliceTest -q`
Expected: PASS.

- [ ] **Step 12: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/admin/reports/ \
        backend/src/main/java/com/slparcelauctions/backend/user/User.java \
        backend/src/test/java/com/slparcelauctions/backend/admin/reports/

git commit -m "$(cat <<'EOF'
feat(admin): ListingReport entity + user-facing report endpoints

POST /api/v1/auctions/{id}/report — upsert by (auction_id, reporter_id),
resubmit resets status=OPEN, gates: must-be-verified, not-own-listing,
auction-must-be-ACTIVE.
GET /api/v1/auctions/{id}/my-report — returns existing report (200) or
204; powers the auction-detail Reported✓ button state.
User.dismissedReportsCount column added (frivolous-counter target).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: `AdminReportService` + `AdminReportController` + stats `openReports` extension

**Goal:** Admin queue (listing-grouped) + per-listing detail + 4 admin actions (dismiss / warn / suspend / cancel) + dashboard stats `openReports` field.

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/admin/reports/AdminReportService.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/admin/reports/AdminReportController.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/admin/reports/dto/AdminReportListingRowDto.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/admin/reports/dto/AdminReportDetailDto.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/admin/reports/dto/AdminReportActionRequest.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/admin/reports/exception/ReportNotFoundException.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/admin/reports/ListingReportRepository.java` (add listing-grouped projection query, sibling-count helpers)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/admin/exception/AdminExceptionHandler.java` (handle `ReportNotFoundException`)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/admin/AdminStatsService.java` (add `openReports` to QueueStats)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/admin/dto/AdminStatsResponse.java` (add `openReports` field)
- Test: `backend/src/test/java/com/slparcelauctions/backend/admin/reports/AdminReportServiceTest.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/admin/reports/AdminReportControllerSliceTest.java`
- Test: extend `backend/src/test/java/com/slparcelauctions/backend/admin/AdminStatsServiceTest.java` (verify openReports plumbing)

- [ ] **Step 1: Add new repository methods**

```java
// in ListingReportRepository.java
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@Query("""
    SELECT new com.slparcelauctions.backend.admin.reports.dto.AdminReportListingRowDto(
        r.auction.id, r.auction.title, r.auction.status,
        r.auction.parcel.regionName, r.auction.seller.id, r.auction.seller.displayName,
        COUNT(r), MAX(r.updatedAt))
    FROM ListingReport r
    WHERE r.status = :status
    GROUP BY r.auction.id, r.auction.title, r.auction.status,
             r.auction.parcel.regionName, r.auction.seller.id, r.auction.seller.displayName
    ORDER BY COUNT(r) DESC, MAX(r.updatedAt) DESC
""")
Page<AdminReportListingRowDto> findListingsGroupedByStatus(
    @Param("status") ListingReportStatus status, Pageable pageable);

// Equivalent for ALL (no filter) — implementer adds a sibling method or uses Specification.

@Query("SELECT count(r) FROM ListingReport r WHERE r.auction.id = :auctionId AND r.status = 'OPEN'")
long countOpenByAuctionId(@Param("auctionId") Long auctionId);
```

- [ ] **Step 2: Create DTOs**

```java
// AdminReportListingRowDto.java
package com.slparcelauctions.backend.admin.reports.dto;

import java.time.OffsetDateTime;

import com.slparcelauctions.backend.auction.AuctionStatus;

public record AdminReportListingRowDto(
    Long auctionId, String auctionTitle, AuctionStatus auctionStatus,
    String parcelRegionName, Long sellerUserId, String sellerDisplayName,
    Long openReportCount, OffsetDateTime latestReportAt
) {}

// AdminReportDetailDto.java
package com.slparcelauctions.backend.admin.reports.dto;

import java.time.OffsetDateTime;

import com.slparcelauctions.backend.admin.reports.ListingReportReason;
import com.slparcelauctions.backend.admin.reports.ListingReportStatus;

public record AdminReportDetailDto(
    Long id, ListingReportReason reason, String subject, String details,
    ListingReportStatus status, String adminNotes,
    OffsetDateTime createdAt, OffsetDateTime updatedAt, OffsetDateTime reviewedAt,
    Long reporterUserId, String reporterDisplayName, long reporterDismissedReportsCount,
    String reviewedByDisplayName
) {}

// AdminReportActionRequest.java
package com.slparcelauctions.backend.admin.reports.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AdminReportActionRequest(@NotBlank @Size(max = 1000) String notes) {}
```

- [ ] **Step 3: Create `ReportNotFoundException` + handler entry**

```java
// ReportNotFoundException.java
package com.slparcelauctions.backend.admin.reports.exception;

public class ReportNotFoundException extends RuntimeException {
    public ReportNotFoundException(Long id) { super("Report not found: " + id); }
}
```

In `AdminExceptionHandler.java`, add:

```java
@ExceptionHandler(ReportNotFoundException.class)
public ResponseEntity<AdminApiError> handleReportNotFound(ReportNotFoundException ex) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(AdminApiError.of("REPORT_NOT_FOUND", ex.getMessage()));
}
```

Plus an import for `ReportNotFoundException`.

- [ ] **Step 4: Create `AdminReportService`**

```java
// backend/src/main/java/com/slparcelauctions/backend/admin/reports/AdminReportService.java
package com.slparcelauctions.backend.admin.reports;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.admin.audit.AdminActionService;
import com.slparcelauctions.backend.admin.audit.AdminActionTargetType;
import com.slparcelauctions.backend.admin.audit.AdminActionType;
import com.slparcelauctions.backend.admin.reports.dto.AdminReportDetailDto;
import com.slparcelauctions.backend.admin.reports.dto.AdminReportListingRowDto;
import com.slparcelauctions.backend.admin.reports.exception.ReportNotFoundException;
import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.CancellationService;
import com.slparcelauctions.backend.auction.exception.AuctionNotFoundException;
import com.slparcelauctions.backend.auction.monitoring.SuspensionService;
import com.slparcelauctions.backend.common.PagedResponse;
import com.slparcelauctions.backend.notification.NotificationPublisher;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AdminReportService {

    private final ListingReportRepository repo;
    private final AuctionRepository auctionRepo;
    private final UserRepository userRepo;
    private final SuspensionService suspensionService;
    private final CancellationService cancellationService;
    private final NotificationPublisher notificationPublisher;
    private final AdminActionService adminActionService;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PagedResponse<AdminReportListingRowDto> listGrouped(
            ListingReportStatus statusFilter, Pageable pageable) {
        Page<AdminReportListingRowDto> page = repo.findListingsGroupedByStatus(statusFilter, pageable);
        return PagedResponse.from(page);
    }

    @Transactional(readOnly = true)
    public List<AdminReportDetailDto> findByListing(Long auctionId) {
        return repo.findByAuctionIdOrderByCreatedAtDesc(auctionId).stream()
            .map(this::toDetail).toList();
    }

    @Transactional(readOnly = true)
    public AdminReportDetailDto findOne(Long reportId) {
        ListingReport r = repo.findById(reportId)
            .orElseThrow(() -> new ReportNotFoundException(reportId));
        return toDetail(r);
    }

    @Transactional
    public AdminReportDetailDto dismiss(Long reportId, Long adminUserId, String notes) {
        ListingReport r = repo.findById(reportId)
            .orElseThrow(() -> new ReportNotFoundException(reportId));
        User admin = userRepo.findById(adminUserId).orElseThrow();
        OffsetDateTime now = OffsetDateTime.now(clock);

        r.setStatus(ListingReportStatus.DISMISSED);
        r.setReviewedBy(admin); r.setReviewedAt(now); r.setAdminNotes(notes);
        repo.save(r);

        // Increment frivolous counter on the reporter.
        User reporter = r.getReporter();
        reporter.setDismissedReportsCount(reporter.getDismissedReportsCount() + 1);
        userRepo.save(reporter);

        adminActionService.record(adminUserId, AdminActionType.DISMISS_REPORT,
            AdminActionTargetType.REPORT, reportId, notes,
            Map.of("auctionId", r.getAuction().getId(),
                   "reporterId", reporter.getId()));

        return toDetail(r);
    }

    @Transactional
    public void warnSeller(Long auctionId, Long adminUserId, String notes) {
        Auction auction = requireAuction(auctionId);
        User admin = userRepo.findById(adminUserId).orElseThrow();
        OffsetDateTime now = OffsetDateTime.now(clock);

        // Only OPEN reports — preserve deliberate per-report DISMISSED decisions.
        repo.findByAuctionIdOrderByCreatedAtDesc(auctionId).stream()
            .filter(r -> r.getStatus() == ListingReportStatus.OPEN)
            .forEach(r -> {
                r.setStatus(ListingReportStatus.REVIEWED);
                r.setReviewedBy(admin); r.setReviewedAt(now); r.setAdminNotes(notes);
                repo.save(r);
            });

        notificationPublisher.listingWarned(
            auction.getSeller().getId(), auction.getId(), auction.getTitle(), notes);

        adminActionService.record(adminUserId, AdminActionType.WARN_SELLER_FROM_REPORT,
            AdminActionTargetType.LISTING, auctionId, notes, null);
    }

    @Transactional
    public void suspend(Long auctionId, Long adminUserId, String notes) {
        Auction auction = requireAuction(auctionId);
        suspensionService.suspendByAdmin(auction, adminUserId, notes);

        OffsetDateTime now = OffsetDateTime.now(clock);
        User admin = userRepo.findById(adminUserId).orElseThrow();
        repo.findByAuctionIdOrderByCreatedAtDesc(auctionId).stream()
            .filter(r -> r.getStatus() == ListingReportStatus.OPEN)
            .forEach(r -> {
                r.setStatus(ListingReportStatus.ACTION_TAKEN);
                r.setReviewedBy(admin); r.setReviewedAt(now); r.setAdminNotes(notes);
                repo.save(r);
            });

        adminActionService.record(adminUserId, AdminActionType.SUSPEND_LISTING_FROM_REPORT,
            AdminActionTargetType.LISTING, auctionId, notes, null);
    }

    @Transactional
    public void cancel(Long auctionId, Long adminUserId, String notes) {
        cancellationService.cancelByAdmin(auctionId, adminUserId, notes);

        OffsetDateTime now = OffsetDateTime.now(clock);
        User admin = userRepo.findById(adminUserId).orElseThrow();
        repo.findByAuctionIdOrderByCreatedAtDesc(auctionId).stream()
            .filter(r -> r.getStatus() == ListingReportStatus.OPEN)
            .forEach(r -> {
                r.setStatus(ListingReportStatus.ACTION_TAKEN);
                r.setReviewedBy(admin); r.setReviewedAt(now); r.setAdminNotes(notes);
                repo.save(r);
            });

        adminActionService.record(adminUserId, AdminActionType.CANCEL_LISTING_FROM_REPORT,
            AdminActionTargetType.LISTING, auctionId, notes, null);
    }

    private Auction requireAuction(Long auctionId) {
        return auctionRepo.findById(auctionId)
            .orElseThrow(() -> new AuctionNotFoundException(auctionId));
    }

    private AdminReportDetailDto toDetail(ListingReport r) {
        User reporter = r.getReporter();
        return new AdminReportDetailDto(
            r.getId(), r.getReason(), r.getSubject(), r.getDetails(),
            r.getStatus(), r.getAdminNotes(),
            r.getCreatedAt(), r.getUpdatedAt(), r.getReviewedAt(),
            reporter.getId(), reporter.getDisplayName(), reporter.getDismissedReportsCount(),
            r.getReviewedBy() == null ? null : r.getReviewedBy().getDisplayName());
    }
}
```

- [ ] **Step 5: Create `AdminReportController`**

```java
// backend/src/main/java/com/slparcelauctions/backend/admin/reports/AdminReportController.java
package com.slparcelauctions.backend.admin.reports;

import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.admin.reports.dto.AdminReportActionRequest;
import com.slparcelauctions.backend.admin.reports.dto.AdminReportDetailDto;
import com.slparcelauctions.backend.admin.reports.dto.AdminReportListingRowDto;
import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.common.PagedResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/admin/reports")
@RequiredArgsConstructor
public class AdminReportController {

    private final AdminReportService service;

    @GetMapping
    public PagedResponse<AdminReportListingRowDto> list(
            @RequestParam(defaultValue = "open") String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        ListingReportStatus filter = "open".equalsIgnoreCase(status)
            ? ListingReportStatus.OPEN
            : "reviewed".equalsIgnoreCase(status) ? ListingReportStatus.REVIEWED
            : null;
        // For "all", fall through with null — the service handles it.
        return service.listGrouped(filter == null ? ListingReportStatus.OPEN : filter,
            PageRequest.of(page, Math.min(size, 100)));
        // NOTE: simplified — real impl handles "all" with a separate method or Specification.
        // Implementer should add a service method `listAllGrouped(Pageable)` for status=all.
    }

    @GetMapping("/listing/{auctionId}")
    public List<AdminReportDetailDto> byListing(@PathVariable Long auctionId) {
        return service.findByListing(auctionId);
    }

    @GetMapping("/{id}")
    public AdminReportDetailDto findOne(@PathVariable Long id) {
        return service.findOne(id);
    }

    @PostMapping("/{id}/dismiss")
    public AdminReportDetailDto dismiss(@PathVariable Long id,
            @Valid @RequestBody AdminReportActionRequest body,
            @AuthenticationPrincipal AuthPrincipal admin) {
        return service.dismiss(id, admin.userId(), body.notes());
    }

    @PostMapping("/listing/{auctionId}/warn-seller")
    public void warnSeller(@PathVariable Long auctionId,
            @Valid @RequestBody AdminReportActionRequest body,
            @AuthenticationPrincipal AuthPrincipal admin) {
        service.warnSeller(auctionId, admin.userId(), body.notes());
    }

    @PostMapping("/listing/{auctionId}/suspend")
    public void suspend(@PathVariable Long auctionId,
            @Valid @RequestBody AdminReportActionRequest body,
            @AuthenticationPrincipal AuthPrincipal admin) {
        service.suspend(auctionId, admin.userId(), body.notes());
    }

    @PostMapping("/listing/{auctionId}/cancel")
    public void cancel(@PathVariable Long auctionId,
            @Valid @RequestBody AdminReportActionRequest body,
            @AuthenticationPrincipal AuthPrincipal admin) {
        service.cancel(auctionId, admin.userId(), body.notes());
    }
}
```

- [ ] **Step 6: Extend `AdminStatsService` and `AdminStatsResponse` with `openReports`**

In `AdminStatsResponse.java`, add `openReports` to `QueueStats`:

```java
public record QueueStats(
    long openFraudFlags,
    long openReports,
    long pendingPayments,
    long activeDisputes
) {}
```

In `AdminStatsService.java`, inject `ListingReportRepository`, add to `compute()`:

```java
QueueStats queues = new QueueStats(
    fraudFlagRepository.countByResolved(false),
    listingReportRepository.countByStatus(ListingReportStatus.OPEN),
    escrowRepository.countByState(EscrowState.ESCROW_PENDING),
    escrowRepository.countByState(EscrowState.DISPUTED)
);
```

Update `AdminStatsServiceTest` (unit) to include the new field in the assertion.

- [ ] **Step 7: Write `AdminReportServiceTest` integration test**

```java
// covers: listGrouped sorting, findByListing, dismiss increments counter,
// warn-seller only touches OPEN (DISMISSED unchanged), suspend marks OPEN as
// ACTION_TAKEN + flips auction SUSPENDED, cancel similarly.
//
// Mirror Task 3's seeding pattern. Mock NotificationWsBroadcasterPort.
```

Run: `cd backend && ./mvnw test -Dtest=AdminReportServiceTest -q`
Expected: PASS.

- [ ] **Step 8: Write `AdminReportControllerSliceTest`**

Mirror sub-spec 1 controller-slice patterns. Cover: 401/403 gates per endpoint; 404 on report-not-found; happy-path 200s with mock service returns.

Run: `cd backend && ./mvnw test -Dtest=AdminReportControllerSliceTest -q`
Expected: PASS.

- [ ] **Step 9: Run full backend tests**

Run: `cd backend && ./mvnw test -q`
Expected: PASS.

- [ ] **Step 10: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/admin/reports/ \
        backend/src/main/java/com/slparcelauctions/backend/admin/exception/AdminExceptionHandler.java \
        backend/src/main/java/com/slparcelauctions/backend/admin/AdminStatsService.java \
        backend/src/main/java/com/slparcelauctions/backend/admin/dto/AdminStatsResponse.java \
        backend/src/test/java/com/slparcelauctions/backend/admin/reports/ \
        backend/src/test/java/com/slparcelauctions/backend/admin/AdminStatsServiceTest.java

git commit -m "$(cat <<'EOF'
feat(admin): admin reports queue + dismiss/warn/suspend/cancel actions

GET /api/v1/admin/reports — listing-grouped (sorted reportCount DESC,
latestReportAt DESC), GET /listing/{id}, GET /{id}.
POST /{id}/dismiss — marks DISMISSED, increments reporter's
dismissedReportsCount.
POST /listing/{id}/warn-seller|suspend|cancel — listing-level actions
that touch ONLY OPEN reports (DISMISSED preserved). Suspend/Cancel
delegate to SuspensionService.suspendByAdmin / CancellationService.
cancelByAdmin. All actions write admin_actions audit rows.
Dashboard stats gain openReports for the 4-up needs-attention row.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: `Ban` entity + `BanCheckService` + Redis cache + `RefreshToken` IPs query

**Goal:** Foundation for ban system — entity, repository (with active-ban queries), `BanCheckService` (cached), `BanCacheInvalidator`, plus the `RefreshToken` distinct-IPs query for admin user-detail "Recent IPs" modal.

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/admin/ban/Ban.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/admin/ban/BanType.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/admin/ban/BanReasonCategory.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/admin/ban/BanRepository.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/admin/ban/BanCheckService.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/admin/ban/BanCacheInvalidator.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/admin/ban/exception/UserBannedException.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/admin/ban/exception/UserBannedExceptionHandler.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/admin/users/dto/UserIpProjection.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auth/RefreshTokenRepository.java` (add `findIpSummaryByUserId`)
- Test: `backend/src/test/java/com/slparcelauctions/backend/admin/ban/BanRepositoryTest.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/admin/ban/BanCheckServiceTest.java`

- [ ] **Step 1: Create enums**

```java
// BanType.java
package com.slparcelauctions.backend.admin.ban;

public enum BanType { IP, AVATAR, BOTH }

// BanReasonCategory.java
package com.slparcelauctions.backend.admin.ban;

public enum BanReasonCategory { SHILL_BIDDING, FRAUDULENT_SELLER, TOS_ABUSE, SPAM, OTHER }
```

- [ ] **Step 2: Create `Ban` entity**

```java
// backend/src/main/java/com/slparcelauctions/backend/admin/ban/Ban.java
package com.slparcelauctions.backend.admin.ban;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import com.slparcelauctions.backend.user.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "bans", indexes = {
    @Index(name = "idx_bans_ip", columnList = "ip_address"),
    @Index(name = "idx_bans_avatar", columnList = "sl_avatar_uuid"),
    @Index(name = "idx_bans_active",
        columnList = "lifted_at, expires_at")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Ban {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "ban_type", length = 10, nullable = false)
    private BanType banType;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "sl_avatar_uuid")
    private UUID slAvatarUuid;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_category", length = 30, nullable = false)
    private BanReasonCategory reasonCategory;

    @Column(name = "reason_text", columnDefinition = "text", nullable = false)
    private String reasonText;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "banned_by", nullable = false)
    private User bannedBy;

    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;

    @Column(name = "lifted_at")
    private OffsetDateTime liftedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lifted_by")
    private User liftedBy;

    @Column(name = "lifted_reason", columnDefinition = "text")
    private String liftedReason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
```

Note: `INET` Postgres type for `ip_address` would be ideal, but Hibernate/Postgres mapping requires custom converters. Sub-spec 2 uses `VARCHAR(45)` (covers IPv4 + IPv6). The DB-level CHECK constraint enforcing the type-matches-fields invariant is enforced at the **service level** (sub-spec 2 doesn't ship a DB CHECK constraint to keep the JPA mapping simple).

- [ ] **Step 3: Create `BanRepository`**

```java
// backend/src/main/java/com/slparcelauctions/backend/admin/ban/BanRepository.java
package com.slparcelauctions.backend.admin.ban;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BanRepository extends JpaRepository<Ban, Long> {

    @Query("""
        SELECT b FROM Ban b
        WHERE b.liftedAt IS NULL
          AND (b.expiresAt IS NULL OR b.expiresAt > :now)
          AND (b.banType = com.slparcelauctions.backend.admin.ban.BanType.IP
               OR b.banType = com.slparcelauctions.backend.admin.ban.BanType.BOTH)
          AND b.ipAddress = :ip
    """)
    List<Ban> findActiveByIp(@Param("ip") String ip, @Param("now") OffsetDateTime now);

    @Query("""
        SELECT b FROM Ban b
        WHERE b.liftedAt IS NULL
          AND (b.expiresAt IS NULL OR b.expiresAt > :now)
          AND (b.banType = com.slparcelauctions.backend.admin.ban.BanType.AVATAR
               OR b.banType = com.slparcelauctions.backend.admin.ban.BanType.BOTH)
          AND b.slAvatarUuid = :uuid
    """)
    List<Ban> findActiveByAvatar(@Param("uuid") UUID uuid, @Param("now") OffsetDateTime now);

    @Query("""
        SELECT b FROM Ban b
        WHERE b.liftedAt IS NULL
          AND (b.expiresAt IS NULL OR b.expiresAt > :now)
    """)
    Page<Ban> findActive(@Param("now") OffsetDateTime now, Pageable pageable);

    @Query("""
        SELECT b FROM Ban b
        WHERE b.liftedAt IS NOT NULL
           OR (b.expiresAt IS NOT NULL AND b.expiresAt <= :now)
    """)
    Page<Ban> findHistory(@Param("now") OffsetDateTime now, Pageable pageable);

    boolean existsBySlAvatarUuidAndLiftedAtIsNullAndExpiresAtIsNullOrSlAvatarUuidAndLiftedAtIsNullAndExpiresAtAfter(
        UUID uuid, UUID uuid2, OffsetDateTime now);
    // Note: above derived-query is awkward; implementer may use a custom @Query
    // returning boolean for "does the user have an active AVATAR-or-BOTH ban".
}
```

- [ ] **Step 4: Create `UserBannedException` + handler**

```java
// UserBannedException.java
package com.slparcelauctions.backend.admin.ban.exception;

import java.time.OffsetDateTime;
import lombok.Getter;

@Getter
public class UserBannedException extends RuntimeException {
    private final OffsetDateTime expiresAt; // null = permanent
    public UserBannedException(OffsetDateTime expiresAt) {
        super("Account is suspended");
        this.expiresAt = expiresAt;
    }
}
```

```java
// UserBannedExceptionHandler.java — global, NOT admin-scoped (this exception
// is thrown from user-facing endpoints like login/bid).
package com.slparcelauctions.backend.admin.ban.exception;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class UserBannedExceptionHandler {

    @ExceptionHandler(UserBannedException.class)
    public ProblemDetail handleBanned(UserBannedException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        pd.setTitle("Account suspended");
        pd.setProperty("code", "USER_BANNED");
        pd.setProperty("expiresAt", ex.getExpiresAt() == null ? null : ex.getExpiresAt().toString());
        return pd;
    }
}
```

- [ ] **Step 5: Create `BanCheckService` (Redis-cached)**

```java
// backend/src/main/java/com/slparcelauctions/backend/admin/ban/BanCheckService.java
package com.slparcelauctions.backend.admin.ban;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.admin.ban.exception.UserBannedException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class BanCheckService {

    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final String IP_KEY = "bans:active:ip:";
    private static final String AVATAR_KEY = "bans:active:avatar:";
    private static final String NEGATIVE = "0";

    private final BanRepository banRepository;
    private final StringRedisTemplate redis;
    private final Clock clock;

    @Transactional(readOnly = true)
    public void assertNotBanned(String ipAddress, UUID slAvatarUuid) {
        if (ipAddress != null && !ipAddress.isBlank() && checkIp(ipAddress)) {
            // already threw
        }
        if (slAvatarUuid != null) {
            checkAvatar(slAvatarUuid);
        }
    }

    private boolean checkIp(String ip) {
        String cached = redis.opsForValue().get(IP_KEY + ip);
        if (NEGATIVE.equals(cached)) return false;
        if (cached != null) {
            // cached as a serialized expiresAt epoch-second or "perm"
            throw new UserBannedException("perm".equals(cached) ? null
                : OffsetDateTime.parse(cached));
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<Ban> hits = banRepository.findActiveByIp(ip, now);
        if (hits.isEmpty()) {
            redis.opsForValue().set(IP_KEY + ip, NEGATIVE, CACHE_TTL);
            return false;
        }
        Ban ban = hits.get(0);
        String value = ban.getExpiresAt() == null ? "perm" : ban.getExpiresAt().toString();
        redis.opsForValue().set(IP_KEY + ip, value, CACHE_TTL);
        throw new UserBannedException(ban.getExpiresAt());
    }

    private void checkAvatar(UUID uuid) {
        String key = AVATAR_KEY + uuid;
        String cached = redis.opsForValue().get(key);
        if (NEGATIVE.equals(cached)) return;
        if (cached != null) {
            throw new UserBannedException("perm".equals(cached) ? null
                : OffsetDateTime.parse(cached));
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<Ban> hits = banRepository.findActiveByAvatar(uuid, now);
        if (hits.isEmpty()) {
            redis.opsForValue().set(key, NEGATIVE, CACHE_TTL);
            return;
        }
        Ban ban = hits.get(0);
        String value = ban.getExpiresAt() == null ? "perm" : ban.getExpiresAt().toString();
        redis.opsForValue().set(key, value, CACHE_TTL);
        throw new UserBannedException(ban.getExpiresAt());
    }
}
```

- [ ] **Step 6: Create `BanCacheInvalidator`**

```java
// backend/src/main/java/com/slparcelauctions/backend/admin/ban/BanCacheInvalidator.java
package com.slparcelauctions.backend.admin.ban;

import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class BanCacheInvalidator {

    private static final String IP_KEY = "bans:active:ip:";
    private static final String AVATAR_KEY = "bans:active:avatar:";

    private final StringRedisTemplate redis;

    public void invalidate(String ipAddress, UUID slAvatarUuid) {
        if (ipAddress != null && !ipAddress.isBlank()) {
            redis.delete(IP_KEY + ipAddress);
        }
        if (slAvatarUuid != null) {
            redis.delete(AVATAR_KEY + slAvatarUuid);
        }
    }
}
```

- [ ] **Step 7: Create `UserIpProjection` + add `findIpSummaryByUserId` to `RefreshTokenRepository`**

```java
// UserIpProjection.java
package com.slparcelauctions.backend.admin.users.dto;

import java.time.OffsetDateTime;

public record UserIpProjection(
    String ipAddress, OffsetDateTime firstSeenAt, OffsetDateTime lastSeenAt, long sessionCount
) {}
```

In `RefreshTokenRepository.java`:

```java
import com.slparcelauctions.backend.admin.users.dto.UserIpProjection;

@Query("""
    SELECT new com.slparcelauctions.backend.admin.users.dto.UserIpProjection(
        rt.ipAddress, MIN(rt.createdAt), MAX(rt.lastUsedAt), COUNT(rt.id))
    FROM RefreshToken rt
    WHERE rt.userId = :userId AND rt.ipAddress IS NOT NULL
    GROUP BY rt.ipAddress
    ORDER BY MIN(rt.createdAt) ASC
""")
List<UserIpProjection> findIpSummaryByUserId(@Param("userId") Long userId);
```

- [ ] **Step 8: Write `BanRepositoryTest` integration test**

Cover:
- active ban (no expiry) found by IP
- active ban (no expiry) found by avatar
- expired ban not returned
- lifted ban not returned
- BOTH ban returned by both IP and avatar queries
- IP-only ban not returned by avatar query

Use the same seeding pattern as Task 3.

- [ ] **Step 9: Write `BanCheckServiceTest`**

Mock `BanRepository` and `StringRedisTemplate`. Cover:
- cache hit (negative) — no DB query
- cache hit (positive, perm) — throws with null expiresAt
- cache miss → DB hit positive → cached → throws
- cache miss → DB hit empty → cached as negative
- assertNotBanned(null, null) is no-op (no throws, no DB queries)

- [ ] **Step 10: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/admin/ban/ \
        backend/src/main/java/com/slparcelauctions/backend/admin/users/dto/UserIpProjection.java \
        backend/src/main/java/com/slparcelauctions/backend/auth/RefreshTokenRepository.java \
        backend/src/test/java/com/slparcelauctions/backend/admin/ban/

git commit -m "$(cat <<'EOF'
feat(admin): Ban entity + BanCheckService + Redis cache + IP history query

bans table with type/identifiers/reason/expires/lifted columns. Active
ban predicate: liftedAt IS NULL AND (expiresAt IS NULL OR > now). Type
matched on ip+avatar respectively, BOTH bans hit both queries.
BanCheckService caches positive AND negative results with 5-min TTL;
BanCacheInvalidator clears keys on create/lift. RefreshTokenRepository
gains findIpSummaryByUserId for the user-detail Recent IPs modal.
UserBannedException → 403 with code:USER_BANNED.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 8: Ban enforcement at 6 paths

**Goal:** Wire `BanCheckService.assertNotBanned` into register, login, SL-verify, bid, listing-create, listing-cancel. Listing-create needs `HttpServletRequest` added to controller + service.

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auth/AuthService.java` (call in register + login)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/sl/SlVerificationService.java` (call before SL avatar link)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/BidService.java` (call before bid placement)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionController.java` (add HttpServletRequest)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionService.java` (add ipAddress arg + call BanCheckService)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/CancellationService.java` (call before cancel — only seller-driven `cancel(...)`, NOT `cancelByAdmin`)
- Test: `backend/src/test/java/com/slparcelauctions/backend/admin/ban/BanEnforcementIntegrationTest.java`

- [ ] **Step 1: Wire BanCheckService into AuthService**

In `AuthService.java`, inject `BanCheckService banCheckService`. In `register(...)`:

```java
public AuthResult register(RegisterRequest request, HttpServletRequest httpReq) {
    String ip = httpReq.getRemoteAddr();
    banCheckService.assertNotBanned(ip, null);
    // ... existing logic
}
```

Same pattern in `login(...)`.

- [ ] **Step 2: Wire BanCheckService into SlVerificationService**

In `SlVerificationService.java` (or wherever the SL-verify avatar-link logic lives — find via `grep -rn "@PostMapping.*verify"` in `sl/` package), call `banCheckService.assertNotBanned(null, slAvatarUuid)` BEFORE the avatar is linked to the user.

- [ ] **Step 3: Wire BanCheckService into BidService**

In `BidService.placeBid(...)`, call before any bid logic:

```java
public BidResponse placeBid(Long auctionId, Long bidderId, long amount, String ipAddress) {
    User bidder = userRepo.findById(bidderId).orElseThrow();
    banCheckService.assertNotBanned(ipAddress, bidder.getSlAvatarUuid());
    // ... existing logic
}
```

- [ ] **Step 4: Add HttpServletRequest to AuctionController.create + AuctionService.create**

```java
// AuctionController.java
@PostMapping("/auctions")
@ResponseStatus(HttpStatus.CREATED)
public SellerAuctionResponse create(
        @AuthenticationPrincipal AuthPrincipal principal,
        @Valid @RequestBody AuctionCreateRequest req,
        HttpServletRequest httpRequest) {
    requireVerified(principal.userId());
    String ip = httpRequest.getRemoteAddr();
    Auction created = auctionService.create(principal.userId(), req, ip);
    return mapper.toSellerResponse(created, null);
}
```

```java
// AuctionService.java
public Auction create(Long sellerId, AuctionCreateRequest req, String ipAddress) {
    User seller = userRepo.findById(sellerId).orElseThrow();
    banCheckService.assertNotBanned(ipAddress, seller.getSlAvatarUuid());
    // ... existing logic
}
```

Inject `BanCheckService` into `AuctionService`. Update any existing tests calling `auctionService.create(sellerId, req)` to pass `null` or `""` as the IP arg.

- [ ] **Step 5: Wire BanCheckService into seller-driven CancellationService.cancel**

In `CancellationService.cancel(Long auctionId, String reason)`, accept an additional `String ipAddress` arg (or look up seller's last-known IP — simpler: change the signature).

The cleanest path: change `cancel` signature to accept `ipAddress`. Update the controller calling it to pass IP via `HttpServletRequest`. The cancel-by-admin path does NOT need this check (admin's own IP isn't subject to bans).

```java
@Transactional
public Auction cancel(Long auctionId, String reason, String ipAddress) {
    Auction a = auctionRepo.findByIdForUpdate(auctionId)
        .orElseThrow(() -> new AuctionNotFoundException(auctionId));
    User seller = userRepo.findByIdForUpdate(a.getSeller().getId()).orElseThrow();
    banCheckService.assertNotBanned(ipAddress, seller.getSlAvatarUuid());
    // ... existing logic
}
```

The AuctionController's cancel endpoint adds `HttpServletRequest httpRequest` and passes IP.

- [ ] **Step 6: Write `BanEnforcementIntegrationTest`**

```java
// covers: AVATAR ban blocks bid for that user
//        : IP ban blocks register/login from that IP
//        : BOTH ban blocks both
//        : lifted ban allows action again (after cache flush)
//        : assertNotBanned with both null is no-op
//
// Use a real Redis (Spring Data Redis with TestContainers, or a mock)
// — if the project uses an embedded/test Redis, follow that pattern.
// Otherwise mock StringRedisTemplate at the integration boundary.
```

- [ ] **Step 7: Run full backend tests**

Run: `cd backend && ./mvnw test -q`

Expected: PASS. **Many existing tests calling `auctionService.create(...)` or `cancellationService.cancel(...)` will need their signatures updated.** This is a sweeping change — implementer should `grep -rn "auctionService.create\|cancellationService.cancel("` and update each call site.

- [ ] **Step 8: Commit**

```bash
git add -u
git add backend/src/test/java/com/slparcelauctions/backend/admin/ban/

git commit -m "$(cat <<'EOF'
feat(admin): ban enforcement at 6 paths (register/login/SL-verify/bid/list/cancel)

BanCheckService.assertNotBanned wired into:
- AuthService.register (IP)
- AuthService.login (IP)
- SlVerificationService (avatar UUID)
- BidService.placeBid (IP + linked avatar)
- AuctionService.create (IP + linked avatar; AuctionController gains
  HttpServletRequest)
- CancellationService.cancel (IP + linked avatar; cancelByAdmin NOT
  ban-checked since admin's own IP isn't subject to user bans)

Banned seller can no longer cancel-and-sell circumvent. Existing call
sites of auctionService.create / cancellationService.cancel updated to
match new signatures.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 9: `AdminBanController` + `AdminBanService`

**Goal:** Admin endpoints for listing/creating/lifting bans + tv-bump on AVATAR-or-BOTH bans + cache invalidation + admin_actions writes.

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/admin/ban/AdminBanController.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/admin/ban/AdminBanService.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/admin/ban/dto/AdminBanRowDto.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/admin/ban/dto/CreateBanRequest.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/admin/ban/dto/LiftBanRequest.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/admin/ban/exception/BanNotFoundException.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/admin/ban/exception/BanAlreadyLiftedException.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/admin/ban/exception/BanTypeFieldMismatchException.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/admin/exception/AdminExceptionHandler.java` (add 3 new handlers)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/user/UserRepository.java` (add `bumpTokenVersion`)
- Test: `backend/src/test/java/com/slparcelauctions/backend/admin/ban/AdminBanServiceTest.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/admin/ban/AdminBanControllerSliceTest.java`

- [ ] **Step 1: Add `bumpTokenVersion` to UserRepository**

```java
// in UserRepository.java
import org.springframework.data.jpa.repository.Modifying;

@Modifying
@Query("UPDATE User u SET u.tokenVersion = u.tokenVersion + 1 WHERE u.id = :userId")
int bumpTokenVersion(@Param("userId") Long userId);
```

- [ ] **Step 2: Create exception classes**

```java
// BanNotFoundException.java
package com.slparcelauctions.backend.admin.ban.exception;
public class BanNotFoundException extends RuntimeException {
    public BanNotFoundException(Long id) { super("Ban not found: " + id); }
}

// BanAlreadyLiftedException.java
package com.slparcelauctions.backend.admin.ban.exception;
public class BanAlreadyLiftedException extends RuntimeException {
    public BanAlreadyLiftedException(Long id) { super("Ban " + id + " already lifted"); }
}

// BanTypeFieldMismatchException.java
package com.slparcelauctions.backend.admin.ban.exception;
public class BanTypeFieldMismatchException extends RuntimeException {
    public BanTypeFieldMismatchException(String message) { super(message); }
}
```

- [ ] **Step 3: Add handlers in `AdminExceptionHandler`**

```java
@ExceptionHandler(BanNotFoundException.class)
public ResponseEntity<AdminApiError> handleBanNotFound(BanNotFoundException ex) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(AdminApiError.of("BAN_NOT_FOUND", ex.getMessage()));
}

@ExceptionHandler(BanAlreadyLiftedException.class)
public ResponseEntity<AdminApiError> handleBanAlreadyLifted(BanAlreadyLiftedException ex) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(AdminApiError.of("BAN_ALREADY_LIFTED", ex.getMessage()));
}

@ExceptionHandler(BanTypeFieldMismatchException.class)
public ResponseEntity<AdminApiError> handleBanTypeMismatch(BanTypeFieldMismatchException ex) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(AdminApiError.of("BAN_TYPE_FIELD_MISMATCH", ex.getMessage()));
}
```

- [ ] **Step 4: Create DTOs**

```java
// AdminBanRowDto.java — full row for list endpoint
package com.slparcelauctions.backend.admin.ban.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.slparcelauctions.backend.admin.ban.BanReasonCategory;
import com.slparcelauctions.backend.admin.ban.BanType;

public record AdminBanRowDto(
    Long id, BanType banType, String ipAddress, UUID slAvatarUuid,
    Long avatarLinkedUserId, String avatarLinkedDisplayName,
    String firstSeenIp,
    BanReasonCategory reasonCategory, String reasonText,
    Long bannedByUserId, String bannedByDisplayName,
    OffsetDateTime expiresAt, OffsetDateTime createdAt,
    OffsetDateTime liftedAt, Long liftedByUserId, String liftedByDisplayName,
    String liftedReason
) {}

// CreateBanRequest.java
package com.slparcelauctions.backend.admin.ban.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.slparcelauctions.backend.admin.ban.BanReasonCategory;
import com.slparcelauctions.backend.admin.ban.BanType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateBanRequest(
    @NotNull BanType banType,
    String ipAddress,
    UUID slAvatarUuid,
    OffsetDateTime expiresAt,
    @NotNull BanReasonCategory reasonCategory,
    @NotBlank @Size(max = 1000) String reasonText
) {}

// LiftBanRequest.java
package com.slparcelauctions.backend.admin.ban.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LiftBanRequest(@NotBlank @Size(max = 1000) String liftedReason) {}
```

- [ ] **Step 5: Create `AdminBanService`**

The service:
- `list(active|history, BanType filter, Pageable)` — returns `PagedResponse<AdminBanRowDto>`. Joins to User for avatar-linked-display-name. For AVATAR rows, also joins to RefreshToken for first-seen IP.
- `create(CreateBanRequest req, Long adminUserId)` — validates type-matches-fields invariant (throws `BanTypeFieldMismatchException` if not). Saves Ban row. If banType in {AVATAR, BOTH} AND avatar maps to a registered user → `userRepo.bumpTokenVersion(user.getId())`. Invalidates Redis cache. Writes admin_actions.
- `lift(Long banId, Long adminUserId, String liftedReason)` — 404 if not found, 409 if already lifted. Sets liftedAt/liftedBy/liftedReason. Invalidates Redis cache. Writes admin_actions.

Implementer writes the full body — pattern is clear from Task 6's `AdminReportService`.

- [ ] **Step 6: Create `AdminBanController`**

```java
@RestController
@RequestMapping("/api/v1/admin/bans")
@RequiredArgsConstructor
public class AdminBanController {

    private final AdminBanService service;

    @GetMapping
    public PagedResponse<AdminBanRowDto> list(
            @RequestParam(defaultValue = "active") String status,
            @RequestParam(required = false) BanType type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return service.list(status, type, PageRequest.of(page, Math.min(size, 100)));
    }

    @PostMapping
    public AdminBanRowDto create(
            @Valid @RequestBody CreateBanRequest body,
            @AuthenticationPrincipal AuthPrincipal admin) {
        return service.create(body, admin.userId());
    }

    @PostMapping("/{id}/lift")
    public AdminBanRowDto lift(
            @PathVariable Long id,
            @Valid @RequestBody LiftBanRequest body,
            @AuthenticationPrincipal AuthPrincipal admin) {
        return service.lift(id, admin.userId(), body.liftedReason());
    }
}
```

- [ ] **Step 7: Tests**

`AdminBanServiceTest` — Mockito unit tests covering type-matches-fields validation, tv-bump only when avatar maps to a user, cache invalidation called on create+lift, lift-already-lifted throws.

`AdminBanControllerSliceTest` — `@SpringBootTest + @AutoConfigureMockMvc`, auth gates per endpoint, validation errors.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/admin/ban/AdminBanController.java \
        backend/src/main/java/com/slparcelauctions/backend/admin/ban/AdminBanService.java \
        backend/src/main/java/com/slparcelauctions/backend/admin/ban/dto/ \
        backend/src/main/java/com/slparcelauctions/backend/admin/ban/exception/ \
        backend/src/main/java/com/slparcelauctions/backend/admin/exception/AdminExceptionHandler.java \
        backend/src/main/java/com/slparcelauctions/backend/user/UserRepository.java \
        backend/src/test/java/com/slparcelauctions/backend/admin/ban/

git commit -m "$(cat <<'EOF'
feat(admin): admin ban CRUD + tv-bump + cache invalidation

GET /api/v1/admin/bans?status=active|history&type=IP|AVATAR|BOTH —
paginated list with first-seen-IP for AVATAR rows.
POST /api/v1/admin/bans — type-matches-fields validation, AVATAR/BOTH
bans bump matching user's tokenVersion (next refresh fails),
BanCacheInvalidator flushes Redis keys, admin_actions row written.
POST /api/v1/admin/bans/{id}/lift — sets lifted columns, flushes cache.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 10: `AdminUserController` + `AdminUserService` + role mgmt + `AdminAudit` read endpoint

**Goal:** Admin user search + detail page (6 tabs + actions) + audit log read endpoint.

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/admin/users/AdminUserController.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/admin/users/AdminUserService.java` (read-only: search, detail, tab data)
- Create: `backend/src/main/java/com/slparcelauctions/backend/admin/users/AdminRoleService.java` (write: promote, demote, reset-counter)
- Create: `backend/src/main/java/com/slparcelauctions/backend/admin/users/dto/*` (8+ DTOs — see step list)
- Create: `backend/src/main/java/com/slparcelauctions/backend/admin/users/exception/*` (`SelfDemoteException`, `UserAlreadyAdminException`, `UserNotAdminException`)
- Create: `backend/src/main/java/com/slparcelauctions/backend/admin/audit/AdminAuditController.java` (`GET /api/v1/admin/audit`)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/admin/exception/AdminExceptionHandler.java` (add 3 new handlers)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/user/UserRepository.java` (admin user-search query)
- Tests: per-service unit + per-controller slice

The endpoints map exactly to spec §4.6 — implementer reads the spec section + writes the controller methods. Each tab endpoint is a small `findBy*Paginated(userId, Pageable)` query against existing repos:

- `listings` — `auctionRepository.findBySellerIdOrderByCreatedAtDesc(userId, pageable)`
- `bids` — `bidRepository.findByBidderIdOrderByCreatedAtDesc(userId, pageable)` (add if not present)
- `cancellations` — `cancellationLogRepository.findBySellerIdOrderByCancelledAtDesc(userId, pageable)` (add)
- `reports` — combined query: reports filed by user OR reports against user's listings, with a `direction` discriminator (`FILED_BY` | `AGAINST_LISTING`)
- `fraud-flags` — `fraudFlagRepository.findByAuctionSellerIdOrderByDetectedAtDesc(userId, pageable)` (add — sub-spec 1's repo extension may need a new method)
- `moderation` — `adminActionRepository.findByTargetTypeAndTargetIdOrderByCreatedAtDesc(USER, userId, pageable)` (already exists from Task 1)

Each tab endpoint returns a flat `PagedResponse<TabRowDto>` shape — the frontend renders rows. DTOs are small projections.

**Promote/demote/reset-counter:**
- `POST /admin/users/{id}/promote` — 409 ALREADY_ADMIN if current role is ADMIN. Else flips role, bumps tv, writes admin_actions.
- `POST /admin/users/{id}/demote` — 409 SELF_DEMOTE_FORBIDDEN if `id == admin.userId()`. 409 NOT_ADMIN if current role is USER. Else flips role, bumps tv, writes admin_actions.
- `POST /admin/users/{id}/reset-frivolous-counter` — sets `dismissedReportsCount = 0`, writes admin_actions.

**Audit read:**
- `GET /api/v1/admin/audit?targetType=&targetId=&adminUserId=&page=&size=25` — paginated. Queries `AdminActionRepository` with the appropriate `findBy*` derived method based on which params are set.

- [ ] **Step 1: Create exceptions** (3 classes — pattern from Task 9)
- [ ] **Step 2: Add handlers in `AdminExceptionHandler`** (3 new handlers — pattern from Task 9)
- [ ] **Step 3: Add user-search query to UserRepository**

```java
@Query("""
    SELECT u FROM User u
    WHERE (:uuid IS NOT NULL AND u.slAvatarUuid = :uuid)
       OR (:uuid IS NULL AND :search IS NOT NULL AND
           (LOWER(u.email) LIKE LOWER(CONCAT('%', :search, '%'))
            OR LOWER(COALESCE(u.displayName, '')) LIKE LOWER(CONCAT('%', :search, '%'))
            OR LOWER(COALESCE(u.slDisplayName, '')) LIKE LOWER(CONCAT('%', :search, '%'))))
       OR (:search IS NULL AND :uuid IS NULL)
    ORDER BY u.createdAt DESC
""")
Page<User> searchAdmin(@Param("search") String search, @Param("uuid") UUID uuidOrNull, Pageable pageable);
```

The controller parses the search input — if it parses as a UUID, pass `uuid=parsed, search=null`; else `uuid=null, search=raw`.

- [ ] **Steps 4-12: Build DTOs, services, controller, audit controller, tests**

The implementer writes each per-spec section (§4.6). Patterns established in Tasks 1, 6, 9 cover everything needed.

- [ ] **Step 13: Run full backend tests**

```bash
cd backend && ./mvnw test -q
```

- [ ] **Step 14: Commit**

```bash
git commit -m "$(cat <<'EOF'
feat(admin): admin user search + user-detail tabs + role mgmt + audit read

GET /api/v1/admin/users — single-search across email/displayName/
slDisplayName/slAvatarUuid (UUID-mode if input parses).
GET /admin/users/{id} — profile + stats + activeBan summary.
GET /admin/users/{id}/{listings|bids|cancellations|reports|fraud-flags|
moderation} — paginated tab data.
GET /admin/users/{id}/ips — distinct IPs from RefreshToken history.
POST /admin/users/{id}/promote — 409 ALREADY_ADMIN, else flip + tv bump.
POST /admin/users/{id}/demote — 409 SELF_DEMOTE_FORBIDDEN, 409 NOT_ADMIN,
else flip + tv bump.
POST /admin/users/{id}/reset-frivolous-counter — zeroes counter.
GET /admin/audit — paginated audit log (filter by targetType+targetId
or by adminUserId).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 11: Frontend foundations (lib/admin types/api/queryKeys/MSW) + dashboard 4-up

**Goal:** Extend sub-spec 1's `lib/admin/*` with reports/bans/users types + api + queryKeys + MSW handlers. Update dashboard to 4-up needs-attention row including Open reports.

**Files:**
- Modify: `frontend/src/lib/admin/types.ts` (add report, ban, user, audit types)
- Modify: `frontend/src/lib/admin/api.ts` (add reports, bans, users, audit endpoints)
- Modify: `frontend/src/lib/admin/queryKeys.ts` (add new keys)
- Modify: `frontend/src/test/msw/handlers.ts` (add report/ban/user MSW handlers)
- Modify: `frontend/src/components/admin/AdminShell.tsx` (add Reports / Bans / Users sidebar items + badges)
- Modify: `frontend/src/components/admin/dashboard/AdminDashboardPage.tsx` (4-up needs-attention row)

- [ ] **Step 1: Extend `lib/admin/types.ts`**

Add types matching the backend DTOs from Tasks 5-10. Mirror the existing fraud-flag-related types' shape (record-style, exhaustive enum unions).

Required additions: `ListingReportReason`, `ListingReportStatus`, `BanType`, `BanReasonCategory`, `AdminReportListingRow`, `AdminReportDetail`, `AdminBanRow`, `AdminUserSummary`, `AdminUserDetail`, `UserIpProjection`, `AdminAction`, `AdminActionType`, `AdminActionTargetType`, `MyReportResponse`. Plus update `AdminStatsResponse.queues` to include `openReports: number`.

- [ ] **Step 2: Extend `lib/admin/api.ts`**

Add api groups: `adminApi.reports`, `adminApi.bans`, `adminApi.users`, `adminApi.audit`, `adminApi.auctions` (for the standalone reinstate endpoint), `userReportsApi` (for the auction-detail report button + my-report).

Each method follows the existing `apiClient.get<T>(url)` / `apiClient.post(url, body)` pattern.

- [ ] **Step 3: Extend `lib/admin/queryKeys.ts`**

Mirror existing `adminQueryKeys` shape:

```ts
reports: () => [...adminQueryKeys.all, "reports"] as const,
reportsList: (filters) => [...adminQueryKeys.reports(), "list", filters] as const,
reportListing: (auctionId) => [...adminQueryKeys.reports(), "listing", auctionId] as const,
report: (id) => [...adminQueryKeys.reports(), "detail", id] as const,
bans: () => [...adminQueryKeys.all, "bans"] as const,
bansList: (filters) => [...adminQueryKeys.bans(), "list", filters] as const,
users: () => [...adminQueryKeys.all, "users"] as const,
usersList: (filters) => [...adminQueryKeys.users(), "list", filters] as const,
user: (id) => [...adminQueryKeys.users(), "detail", id] as const,
userTab: (id, tab, filters) => [...adminQueryKeys.user(id), tab, filters] as const,
userIps: (id) => [...adminQueryKeys.user(id), "ips"] as const,
audit: (filters) => [...adminQueryKeys.all, "audit", filters] as const,
myReport: (auctionId) => ["auction", auctionId, "my-report"] as const,
```

- [ ] **Step 4: Add MSW handlers**

In `frontend/src/test/msw/handlers.ts`, extend `adminHandlers` with new factory methods covering the new endpoints. Pattern from sub-spec 1 fraud-flag handlers — happy-path success returns + 409/403/404 variants per endpoint.

- [ ] **Step 5: Update `AdminShell` sidebar items**

Add Reports / Bans / Users items below Fraud Flags. Each gets a badge from the appropriate stats field (Reports → `data.queues.openReports`).

```tsx
const items: SidebarItem[] = [
  { label: "Dashboard", href: "/admin" },
  { label: "Fraud Flags", href: "/admin/fraud-flags",
    badge: stats?.queues.openFraudFlags },
  { label: "Reports", href: "/admin/reports",
    badge: stats?.queues.openReports },
  { label: "Bans", href: "/admin/bans" },
  { label: "Users", href: "/admin/users" },
];
```

- [ ] **Step 6: Update dashboard 4-up**

In `AdminDashboardPage.tsx`, change the needs-attention grid from 3-column to 4-column and add the Open reports card:

```tsx
<div className="grid grid-cols-4 gap-3 mb-7">
  <QueueCard label="Open fraud flags" value={data.queues.openFraudFlags} tone="fraud"
    subtext="Click to triage" href="/admin/fraud-flags" />
  <QueueCard label="Open reports" value={data.queues.openReports} tone="fraud"
    subtext="Click to triage" href="/admin/reports" />
  <QueueCard label="Pending payments" value={data.queues.pendingPayments} tone="warning"
    subtext="Awaiting winner L$" />
  <QueueCard label="Active disputes" value={data.queues.activeDisputes} tone="warning"
    subtext="Escrow disputed" />
</div>
```

- [ ] **Step 7: Run typecheck + tests**

Run: `cd frontend && npx tsc --noEmit && npm test -- --run`
Expected: PASS (sub-spec 1's `AdminDashboardPage.test.tsx` may need a fixture update for the new `openReports` field).

- [ ] **Step 8: Commit**

```bash
git add frontend/src/lib/admin/ \
        frontend/src/test/msw/handlers.ts \
        frontend/src/components/admin/AdminShell.tsx \
        frontend/src/components/admin/dashboard/

git commit -m "$(cat <<'EOF'
feat(admin): frontend foundations + dashboard 4-up

lib/admin/{types,api,queryKeys} extended with reports/bans/users/audit
shapes. MSW adminHandlers gain new factory methods.
AdminShell sidebar adds Reports / Bans / Users items (Reports gets the
open-reports badge). AdminDashboardPage needs-attention row goes 4-up
including Open reports card.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 12: Frontend reports UI (button + modal + admin queue + slide-over)

**Goal:** User-facing report button + modal on auction detail. Admin reports queue page at `/admin/reports` with listing-grouped table + slide-over (560px) with 4 actions + per-report dismiss + shared notes textarea.

**Files:**
- Create: `frontend/src/components/auction/ReportListingButton.tsx`
- Create: `frontend/src/components/auction/ReportListingModal.tsx`
- Create: `frontend/src/hooks/auction/useMyReport.ts`
- Create: `frontend/src/hooks/auction/useSubmitReport.ts`
- Modify: `frontend/src/app/auction/[id]/AuctionDetailClient.tsx` (insert `<ReportListingButton>` in title row)
- Create: `frontend/src/app/admin/reports/page.tsx`
- Create: `frontend/src/components/admin/reports/AdminReportsListPage.tsx`
- Create: `frontend/src/components/admin/reports/AdminReportsTable.tsx`
- Create: `frontend/src/components/admin/reports/AdminReportsFilters.tsx`
- Create: `frontend/src/components/admin/reports/AdminReportSlideOver.tsx`
- Create: `frontend/src/components/admin/reports/AdminReportListingHeader.tsx`
- Create: `frontend/src/components/admin/reports/AdminReportCard.tsx` (single-report card with Dismiss button)
- Create: `frontend/src/hooks/admin/useAdminReportsList.ts`
- Create: `frontend/src/hooks/admin/useAdminReportsByListing.ts`
- Create: `frontend/src/hooks/admin/useDismissReport.ts`
- Create: `frontend/src/hooks/admin/useWarnSeller.ts`
- Create: `frontend/src/hooks/admin/useSuspendListingFromReport.ts`
- Create: `frontend/src/hooks/admin/useCancelListingFromReport.ts`
- Tests: companion `*.test.tsx` for button, modal, list page, slide-over

The patterns match sub-spec 1's fraud-flag UI exactly (URL state with `?status=` and `?auctionId=` for slide-over open, mutations invalidate both list-cache and stats-cache, slide-over with prev/next arrows). Implementer mirrors `AdminFraudFlagsListPage` and friends.

**Auction detail integration**: `<ReportListingButton>` in the title row state machine matches spec §5.1:
- Anonymous → hidden
- Logged-in unverified → disabled with tooltip "Verify your SL avatar to report listings"
- Logged-in verified, current user is seller → hidden
- `useMyReport(auctionId)` returns 204 → button enabled "Report"
- `useMyReport(auctionId)` returns row with `status === "DISMISSED"` → button enabled "Report"
- Otherwise → "Reported ✓" disabled

Tests cover: button state matrix, modal field validation, mutation success → button flips, mutation error → toast.

- [ ] **Step 1-12**: Implementer scaffolds each file in order. Estimated 600-700 lines of frontend code.

- [ ] **Step 13: Run typecheck + tests + lint**

```bash
cd frontend && npx tsc --noEmit && npm test -- --run && npm run lint
```

Expected: PASS. Pre-existing `CodeDisplay.test.tsx` TS error — known, not introduced by this task.

- [ ] **Step 14: Commit**

```bash
git commit -m "$(cat <<'EOF'
feat(admin): /admin/reports queue + auction-detail Report button

Auction detail (/auction/[id]) gains Report button in title row with
state machine (hidden anon/own-listing, disabled unverified, "Reported ✓"
after submit, re-enables after admin dismisses). Modal: subject (100) +
reason dropdown + details (2000) + disclosure block. Submit upserts
report row.
/admin/reports listing-grouped queue (sorted reportCount DESC) with
slide-over (560px) showing all reports for that listing, 4 actions
(Warn / Suspend / Cancel / Ban seller →), per-report Dismiss, shared
notes textarea, prev/next arrows. Mutations invalidate reports + stats.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 13: Frontend bans UI (page + create modal)

**Goal:** Admin bans page at `/admin/bans` with Active/History tabs + create-ban modal (with type selector, identifier inputs, expiry, reason category, side-effect note, user-search autocomplete on no-pre-fill case).

**Files:**
- Create: `frontend/src/app/admin/bans/page.tsx`
- Create: `frontend/src/components/admin/bans/AdminBansPage.tsx`
- Create: `frontend/src/components/admin/bans/AdminBansTable.tsx`
- Create: `frontend/src/components/admin/bans/AdminBansFilters.tsx`
- Create: `frontend/src/components/admin/bans/CreateBanModal.tsx`
- Create: `frontend/src/components/admin/bans/LiftBanModal.tsx`
- Create: `frontend/src/components/admin/bans/UserSearchAutocomplete.tsx`
- Create: `frontend/src/hooks/admin/useAdminBansList.ts`
- Create: `frontend/src/hooks/admin/useCreateBan.ts`
- Create: `frontend/src/hooks/admin/useLiftBan.ts`
- Create: `frontend/src/hooks/admin/useUserSearch.ts` (for autocomplete)
- Tests: companion test files

Patterns match Task 12. The user-search autocomplete is a thin debounced typeahead querying `GET /api/v1/admin/users?search=...` and rendering up to 5 result rows in a popover.

Create modal supports two entry modes:
- **Pre-filled** (from `/admin/users/{id}` "+ Add another ban…" button): receives initial `slAvatarUuid` + most-recent IP via props; user can edit
- **Blank** (from `/admin/bans` "+ Create ban" button): renders the user-search autocomplete; user picks a result → modal pre-fills

Both modes hit the same `POST /api/v1/admin/bans` endpoint.

- [ ] **Step 1-9**: Implementer scaffolds each file. Estimated 400 lines of frontend code.
- [ ] **Step 10: Run typecheck + tests + lint**
- [ ] **Step 11: Commit**

```bash
git commit -m "$(cat <<'EOF'
feat(admin): /admin/bans page + create/lift modals

Active / History tabs, type filter pills (IP/AVATAR/BOTH), table with
identifier UUID linked to user-detail and first-seen-IP for AVATAR rows.
Create-ban modal: type selector, identifier inputs (conditional on type),
expiry (Permanent or date picker), reason category dropdown + reason text,
side-effect note (tv-bump + cache flush). User-search autocomplete on
the no-pre-fill case. Lift modal with required notes textarea.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 14: Frontend users UI (search + detail page)

**Goal:** Admin user search at `/admin/users` + user-detail page at `/admin/users/{id}` with profile + 5 stat cards + 6 tabs + 280px right rail with active-ban callout + action buttons + Recent IPs modal.

**Files:**
- Create: `frontend/src/app/admin/users/page.tsx`
- Create: `frontend/src/app/admin/users/[id]/page.tsx`
- Create: `frontend/src/components/admin/users/AdminUsersSearchPage.tsx`
- Create: `frontend/src/components/admin/users/AdminUsersTable.tsx`
- Create: `frontend/src/components/admin/users/AdminUserDetailPage.tsx`
- Create: `frontend/src/components/admin/users/UserProfileHeader.tsx`
- Create: `frontend/src/components/admin/users/UserStatsCards.tsx`
- Create: `frontend/src/components/admin/users/UserTabsNav.tsx`
- Create: `frontend/src/components/admin/users/tabs/ListingsTab.tsx` (with Reinstate button per row when SUSPENDED)
- Create: `frontend/src/components/admin/users/tabs/BidsTab.tsx`
- Create: `frontend/src/components/admin/users/tabs/CancellationsTab.tsx`
- Create: `frontend/src/components/admin/users/tabs/ReportsTab.tsx` (filed-by + against split)
- Create: `frontend/src/components/admin/users/tabs/FraudFlagsTab.tsx`
- Create: `frontend/src/components/admin/users/tabs/ModerationTab.tsx`
- Create: `frontend/src/components/admin/users/UserActionsRail.tsx` (right-rail with promote/demote/reset/ban shortcut/active-ban callout)
- Create: `frontend/src/components/admin/users/RecentIpsModal.tsx`
- Create: `frontend/src/components/admin/users/ConfirmActionModal.tsx` (notes-required confirmation for promote/demote/reset)
- Create: `frontend/src/components/admin/users/ReinstateListingModal.tsx` (notes-required for the per-row Reinstate button)
- Create: `frontend/src/hooks/admin/useAdminUsersList.ts`
- Create: `frontend/src/hooks/admin/useAdminUser.ts` (detail)
- Create: `frontend/src/hooks/admin/useAdminUserListings.ts` (and 5 sibling tab hooks)
- Create: `frontend/src/hooks/admin/useAdminUserIps.ts`
- Create: `frontend/src/hooks/admin/usePromoteUser.ts`
- Create: `frontend/src/hooks/admin/useDemoteUser.ts`
- Create: `frontend/src/hooks/admin/useResetFrivolousCounter.ts`
- Create: `frontend/src/hooks/admin/useReinstateAuction.ts` (POST /admin/auctions/{id}/reinstate)
- Tests: companion test files

Patterns established. Implementer scaffolds in dependency order: hooks → leaf components → page composition.

Self-demote 409 handling: the demote mutation's `onError` checks `code === "SELF_DEMOTE_FORBIDDEN"` and surfaces the toast "You cannot demote yourself."

Reinstate-from-listings-tab: per row when `status === "SUSPENDED"`, render a small "Reinstate" button → opens `<ReinstateListingModal>` with notes textarea → calls `useReinstateAuction()`. Success → invalidate `useAdminUserListings(id, page)` and `["admin", "stats"]`. Error 409 → toast.

- [ ] **Step 1-22**: Implementer scaffolds each file. Estimated 1000+ lines of frontend code.
- [ ] **Step 23: Run typecheck + tests + lint**
- [ ] **Step 24: Commit**

```bash
git commit -m "$(cat <<'EOF'
feat(admin): /admin/users search + user-detail page

/admin/users — single search box, paginated table with display name +
activity meta, role/verified/banned chips.
/admin/users/{id} — profile header + 5 stat cards + 6-tab history
(Listings/Bids/Cancellations/Reports/Fraud flags/Moderation) + 280px
right rail (active-ban callout, Promote/Demote/Reset/+Add ban actions,
Recent IPs modal). Listings tab gets per-row Reinstate button for
SUSPENDED auctions calling /admin/auctions/{id}/reinstate. Demote 409
SELF_DEMOTE_FORBIDDEN surfaces toast. All admin write actions require
notes (confirmation modal).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 15: Cross-cutting docs + manual test + push branch + open PR

**Goal:** Close out the sub-spec — DEFERRED_WORK ledger sweep, FOOTGUNS additions (F.103+), DESIGN.md §8 notes, full test suite run, branch push, PR open (NOT auto-merged).

**Files:**
- Modify: `docs/implementation/DEFERRED_WORK.md`
- Modify: `docs/implementation/FOOTGUNS.md`
- Modify: `docs/initial-design/DESIGN.md`

- [ ] **Step 1: Run full backend test suite**

```bash
cd backend && ./mvnw test -q
```

Expected: PASS. Re-run if `AuctionRepositoryOwnershipCheckTest` flakes.

- [ ] **Step 2: Run full frontend tests + typecheck + lint**

```bash
cd frontend && npx tsc --noEmit && npm test -- --run && npm run lint
```

Expected: PASS, lint clean (pre-existing warnings only).

- [ ] **Step 3: Update DEFERRED_WORK.md**

Read first. Then:
- **Close** entries that sub-spec 2 ships:
  - "Account deletion UI" cascade-rules-shared-with-bans entry — sub-spec 2 establishes the admin role + admin actions audit; account deletion can be revisited in sub-spec 4 with a cleaner home.
  - Any entries explicitly saying "blocked on admin user mgmt" or "blocked on bans" — sub-spec 2 unblocks.
- **Modify** entries with narrowed scope:
  - "Non-dev admin endpoint for ownership-monitor trigger" (Epic 03) — admin foundation now exists, only the endpoint + button remain.
- **Add** entries deferred from sub-spec 2:
  - `REPORT_THRESHOLD_REACHED` admin-targeted notification (re-add trigger: "if admins miss high-report listings").
  - Admin "Send notification to user" surface — out of scope, indefinite.
  - Frivolous-reporter automatic privilege revocation — counter ships, automatic revoke deferred to "operational data shows the threshold is needed."
  - Realtime ban broadcast / forced-logout WebSocket — tv-bump on next refresh sufficient; revisit if forced-logout latency becomes a complaint.
  - ProxyBid bidder fan-out from admin-cancel — manual-bid fan-out ships, proxy-bid fan-out can come if proxy-bidder feedback shows missing notifications.

- [ ] **Step 4: Update FOOTGUNS.md (F.103+)**

Append:

```markdown
### F.103 — Admin-cancel must NOT bump the seller's penalty ladder

`CancellationLog.cancelledByAdminId IS NULL` is the load-bearing predicate
in `countPriorOffensesWithBids`. Without it, every admin-removed listing
counts as a seller offense — a seller who's been wrongly reported but
exonerated would still climb the ladder. Test
`CancellationServiceCancelByAdminTest.priorOffensesQueryExcludesAdminCancel`
is the canary.

### F.104 — Cause-neutral fanout body string

`NotificationPublisher.listingCancelledBySellerFanout` body strings
("This auction has been cancelled. Your active proxy bid is no longer
in effect.") are deliberately cause-neutral so admin-cancel can call the
same method. If anyone reverts the body to seller-attributed copy ("The
seller cancelled..."), bidders on admin-cancelled auctions get a
misleading message. Existing seller-cancel tests assert against the
new copy — they're the canary.

### F.105 — Listing-level report actions touch ONLY OPEN reports

`AdminReportService.warnSeller / suspend / cancel` filter to OPEN status
when batching the report-state-change. DISMISSED reports stay DISMISSED
because each represents a deliberate per-report decision (with the
reporter's frivolous counter already incremented). Reclassifying them
on a listing-level action would undo that decision.

### F.106 — Ban cache TTL = 5 min; create/lift flushes immediately

`BanCheckService` caches both positive AND negative results with 5-min
TTL. `BanCacheInvalidator.invalidate(ip, uuid)` is called on ban-create
and ban-lift to clear the keys immediately. The 5-min cap limits the
worst-case stale window to 5 min — acceptable because admin actions are
infrequent and a banned user being one bid late to be blocked is a
non-event.

### F.107 — Listing-creation IP capture didn't exist before

`AuctionController.create` and `AuctionService.create` gained
`HttpServletRequest` / `String ipAddress` parameters in sub-spec 2. Any
test calling `auctionService.create(sellerId, req)` directly fails with
a method-not-found compile error — pass `null` or `""` as the new third
arg. The integration tests in this sub-spec already do this; older tests
that haven't been touched in a while may need a sweep.

### F.108 — Self-demote returns 409, NOT 403

A current admin trying to demote themselves gets `409 SELF_DEMOTE_FORBIDDEN`
from `AdminRoleService.demote`. The 409 is intentional — they have
permission to call the endpoint (they're an admin), but the operation
is forbidden by business rule. The frontend toast surfaces "You cannot
demote yourself."
```

- [ ] **Step 5: Append DESIGN.md §8 sub-spec 2 notes**

After sub-spec 1's notes subsection:

```markdown
### Notes (Epic 10 sub-spec 2 — Reports + Bans + Admin enforcement + User mgmt, 2026-04-26)

- Listing reports: users flag with subject + reason + details. Upsert by
  (auction, reporter) — resubmit replaces and resets status to OPEN
  (issue-still-happening escape valve). Reports are ALWAYS informational
  — admin acts manually.
- Admin queue is listing-grouped, sorted reportCount DESC. Listing-level
  actions (warn/suspend/cancel) only touch OPEN reports — DISMISSED
  reports preserve the admin's prior per-report decision (and the
  reporter's frivolous counter increment).
- Bans block at 6 paths: register, login, SL-verify, bid, listing
  creation, listing cancellation. The cancel-path check prevents banned
  sellers from circumventing via cancel-and-sell. Cached in Redis with
  5-min TTL.
- Multi-ban stacking: one user can have AVATAR + IP bans concurrently as
  separate rows. Active = liftedAt IS NULL AND (expiresAt IS NULL OR > now).
- Admin-cancel skips the seller penalty ladder. `CancellationLog.
  cancelledByAdminId` excludes the row from `countPriorOffensesWithBids`.
  Distinct seller notification (LISTING_REMOVED_BY_ADMIN) plus
  cause-neutral bidder fan-out (LISTING_CANCELLED_BY_SELLER reused).
- Admin reinstate is a shared primitive (`AdminAuctionService.reinstate`)
  used by both fraud-flag-resolution (sub-spec 1) and the standalone
  `/admin/auctions/{id}/reinstate` endpoint (called from the user-detail
  Listings tab Reinstate button when status=SUSPENDED).
- `admin_actions` audit table writes from sub-spec 2 forward. Sub-spec 1
  fraud-flag actions continue to self-audit on FraudFlag.resolvedBy +
  adminNotes — no backfill.
- Frivolous reporter tracking: counter only this sub-spec
  (`User.dismissedReportsCount`). Automatic privilege revocation deferred.
- Self-demote returns 409 SELF_DEMOTE_FORBIDDEN. Promote doesn't need
  the guard (you're already admin to reach the page).
```

- [ ] **Step 6: Commit docs**

```bash
git add docs/implementation/DEFERRED_WORK.md \
        docs/implementation/FOOTGUNS.md \
        docs/initial-design/DESIGN.md

git commit -m "$(cat <<'EOF'
docs(epic-10-sub-2): close X deferred entries, modify Y; FOOTGUNS x6; DESIGN.md notes

Closes admin-mgmt-blocking entries (account-deletion cascade, fraud-flag
admin endpoint dependencies). Modifies ownership-monitor admin-trigger
entry (foundation now exists). Adds REPORT_THRESHOLD_REACHED, "Send
notification to user", frivolous-reporter automatic revocation,
forced-logout WebSocket, and ProxyBid fan-out as deferred items.
FOOTGUNS F.103-F.108 cover penalty-ladder exclusion, cause-neutral
fanout copy, listing-action OPEN-only filter, ban cache TTL,
listing-creation IP capture method-signature change, and self-demote
409 semantics. DESIGN.md §8 gains sub-spec 2 notes.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 7: Push branch**

```bash
git push -u origin task/10-sub-2-reports-bans-admin-enforcement
```

- [ ] **Step 8: Open PR (DO NOT auto-merge, base = `dev`)**

```bash
gh pr create --base dev --title "Epic 10 sub-spec 2 — Reports + Bans + Admin enforcement + User mgmt" --body "$(cat <<'EOF'
## Summary

- `ListingReport` entity + user-facing report endpoints (upsert + my-report)
- Admin reports queue at /admin/reports (listing-grouped + slide-over with 4 actions)
- `Ban` entity + Redis cache + 6-path enforcement (register/login/SL-verify/bid/listing-create/listing-cancel)
- Admin bans page at /admin/bans + create/lift modals + first-seen-IP for AVATAR rows
- Admin user search at /admin/users + user-detail at /admin/users/{id} with 6 tabs + 280px right rail
- `admin_actions` audit table + audit read endpoint (per-user via Moderation tab)
- `AdminAuctionService.reinstate` shared primitive + standalone /admin/auctions/{id}/reinstate
- Sub-spec 1's fraud-flag reinstate refactored to delegate (net behavior unchanged)
- `CancellationService.cancelByAdmin` skips penalty ladder; cause-neutral bidder fanout body
- New notification categories: LISTING_WARNED, LISTING_REMOVED_BY_ADMIN
- Dashboard 4-up needs-attention row (Open reports added)
- Auction detail Report button + modal

## Test plan

- [ ] Backend: `cd backend && ./mvnw test` — all green
- [ ] Frontend: `cd frontend && npx tsc --noEmit && npm test && npm run lint` — all green
- [ ] Manual: user reports a listing → modal flow → button flips to Reported ✓
- [ ] Manual: admin dismisses → button re-enables (re-submit replaces row + resets OPEN)
- [ ] Manual: admin warns seller (different report) → seller gets LISTING_WARNED
- [ ] Manual: admin suspends from reports → seller gets LISTING_SUSPENDED, all open reports → ACTION_TAKEN, DISMISSED reports stay DISMISSED
- [ ] Manual: admin reinstates from user-detail Listings tab → auction → ACTIVE, seller gets LISTING_REINSTATED
- [ ] Manual: admin cancels from reports → seller gets LISTING_REMOVED_BY_ADMIN, bidders get cause-neutral cancel notification, no penalty applied (cancellation_log row has cancelledByAdminId, seller.cancelledWithBids unchanged)
- [ ] Manual: admin creates AVATAR ban on logged-in user → user invalidates on next refresh (tv bump)
- [ ] Manual: banned user attempts bid → 403 USER_BANNED
- [ ] Manual: admin lifts ban → user can act again (after Redis cache flush)
- [ ] Manual: admin self-demote → 409 SELF_DEMOTE_FORBIDDEN

## Out of scope (next sub-specs)

- Bot pool health, escrow dispute resolution, terminal secret rotation → sub-spec 3
- Reminder schedulers, account deletion UI, audit-log viewer page → sub-spec 4
- REPORT_THRESHOLD_REACHED admin-targeted notification — deferred
- Frivolous-reporter automatic revocation — counter only this sub-spec

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

Stop after PR creation. Report PR URL.

---

## Self-review checklist (run mentally before each task starts execution)

- ✅ **Spec coverage:** every section of the spec maps to a task. Reports (§4.1, §4.4, §5.1-§5.3) → Tasks 5, 6, 12. Bans (§4.2, §4.3, §4.5, §5.4) → Tasks 7, 8, 9, 13. AdminAction audit (§4.11) → Task 1, 10. AdminAuctionService (§4.7) → Task 2. Cancel/Suspend by admin (§4.8, §4.9) → Tasks 3, 4. Notification categories (§4.10) → Tasks 3, 4. User mgmt (§4.6, §5.5) → Tasks 10, 14. Dashboard 4-up (§5.6) → Task 11. Wrap-up (§9, §10) → Task 15.
- ✅ **No placeholders:** every step has either real code, a real command, or an explicit "implementer follows pattern from Task N" reference. The pattern-reference shortcuts in tasks 9, 10, 13, 14 are tighter than tasks 1-7 because the patterns are established by then.
- ✅ **Type consistency:** `AdminAction*` types stable across tasks 1, 6, 9, 10. `ListingReport*` stable across 5, 6, 12. `Ban*` stable across 7, 8, 9, 13. `AdminAuctionReinstateResult` defined in task 2, used in task 14. URL paths consistent (`/api/v1/admin/reports/...`, `/api/v1/admin/bans/...`, `/api/v1/admin/users/...`, `/api/v1/admin/auctions/{id}/reinstate`, `/api/v1/admin/audit`).
- ✅ **TDD throughout:** every backend code change has a test. Frontend tests cover hooks + components.
- ✅ **Bite-sized:** no step does more than one thing. Each commit is self-contained and reviewable.




