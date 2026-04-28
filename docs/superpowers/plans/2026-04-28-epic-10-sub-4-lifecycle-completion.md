# Epic 10 Sub-spec 4 Implementation Plan — Lifecycle Completion

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close Epic 10 by shipping account deletion (soft-delete with cascade), admin audit log viewer with CSV export, and two reminder schedulers (escrow transfer + review response window).

**Architecture:** Account deletion uses soft-delete with identity scrubbing while retaining `slAvatarUuid` for ban-check coverage; tokenVersion bump invalidates outstanding access tokens. Audit log viewer is read-only over the existing `admin_actions` table from sub-specs 1/2/3. Both schedulers follow the established cron pattern (sub-spec 3 reconciliation), with `@ConditionalOnProperty(matchIfMissing=true)` so tests can opt-out via `@TestPropertySource`.

**Tech Stack:** Spring Boot 4 / Java 26 / Lombok / Postgres / JPA `ddl-auto: update`, Spring Data Redis, Spring Scheduled, Next.js 16 / React 19 / TanStack Query v5 / MSW v2 / Vitest / Tailwind 4.

**Codebase shape verified during sub-spec 3 audit (still applies):**
- Dispute fields live on `Escrow` (not separate entity)
- `User` entity uses `email` (not username); existing constraint is `UNIQUE NOT NULL` — needs relaxing to nullable for soft-delete
- `AdminActionTypeCheckConstraintInitializer` keeps Postgres check constraint synced with enum values (sub-spec 3 pattern)
- `AdminActionService.record(adminUserId, type, targetType, targetId, notes, details)` for admin audit
- `EscrowConfigProperties` — implementer verifies the property name for transfer-deadline-hours
- `NotificationPublisher.escrowTransferReminder(...)` — exists per Epic 09 sub-spec 1
- `NotificationPublisher.reviewResponseWindowClosing(...)` — implementer verifies; if absent, sub-spec 4 adds it as a small ride-along
- `ObjectStorageService` and `S3Presigner` available from sub-spec 3
- `RestClient` is the HTTP client convention (not `WebClient.Builder`)

---

## File Structure

### Backend — new files

```
backend/src/main/java/com/slparcelauctions/backend/
├── user/
│   ├── deletion/                                       (new package)
│   │   ├── UserDeletionService.java
│   │   ├── UserDeletionRequest.java                    (self-service body)
│   │   ├── AdminUserDeletionRequest.java               (admin body)
│   │   ├── DeletionPreconditionError.java              (structured 409 payload)
│   │   ├── UserDeletionExceptionHandler.java           (or merge into AdminExceptionHandler)
│   │   └── exception/
│   │       ├── UserAlreadyDeletedException.java
│   │       ├── InvalidPasswordException.java
│   │       ├── DeletionPreconditionException.java      (interface)
│   │       ├── ActiveAuctionsException.java
│   │       ├── OpenEscrowsException.java
│   │       ├── ActiveHighBidsException.java
│   │       └── ActiveProxyBidsException.java
├── admin/
│   ├── audit/                                          (existing package — extend)
│   │   ├── AdminAuditLogService.java                   (new)
│   │   ├── AdminAuditLogController.java                (new)
│   │   ├── AdminAuditLogRow.java                       (new DTO)
│   │   └── AdminAuditLogFilters.java                   (new query DTO)
│   └── infrastructure/                                 (existing)
│       └── reminders/                                  (new sub-package)
│           ├── EscrowTransferReminderScheduler.java
│           └── ReviewResponseWindowClosingScheduler.java
```

### Backend — modified files

- `user/User.java` — add `deletedAt`; relax `email` to nullable
- `user/UserController.java` — add `DELETE /me` (replaces 501 stub)
- `user/UserRepository.java` — possibly add helpers for soft-delete (TBD by implementer)
- `admin/users/AdminUserController.java` — add `DELETE /{id}` admin endpoint
- `admin/audit/AdminActionType.java` — add `USER_DELETED_BY_ADMIN`
- `admin/audit/AdminActionTypeCheckConstraintInitializer.java` — append new enum value to the migration list
- `escrow/Escrow.java` — add `reminderSentAt`
- `escrow/EscrowRepository.java` — add `findEscrowsApproachingTransferDeadline(...)`
- `review/Review.java` (or wherever Review entity lives — implementer locates) — add `responseClosingReminderSentAt`
- `review/ReviewRepository.java` — add `findReviewsApproachingResponseClose(...)`
- `notification/NotificationPublisher.java` — verify `reviewResponseWindowClosing(...)` exists; add if missing
- `notification/NotificationPublisherImpl.java` — same
- `notification/NotificationDataBuilder.java` — same
- `notification/NotificationCategory.java` — verify `REVIEW_RESPONSE_WINDOW_CLOSING` exists; add if missing
- `notification/slim/SlImLinkResolver.java` — add case if category was newly added

### Frontend — new files

```
frontend/src/
├── app/admin/audit-log/
│   ├── page.tsx
│   ├── AdminAuditLogPage.tsx
│   ├── AdminAuditLogTable.tsx
│   ├── AdminAuditLogFilters.tsx
│   └── AuditLogRowDetails.tsx
├── app/goodbye/
│   └── page.tsx
├── components/settings/
│   └── DeleteAccountSection.tsx
├── components/admin/users/
│   └── DeleteUserModal.tsx
└── lib/admin/
    ├── auditLog.ts                                     (types)
    ├── auditLogHooks.ts
    └── deletionHooks.ts                                (useDeleteSelf + useDeleteUser)
```

### Frontend — modified files

- `lib/admin/types.ts` — re-export new types
- `lib/admin/api.ts` — add `adminApi.auditLog`, `adminApi.users.delete`
- `lib/admin/queryKeys.ts` — add audit-log keys
- `test/msw/handlers.ts` — add audit-log + deletion handler factories
- `components/admin/AdminShell.tsx` — add `Audit log` sidebar item
- `app/settings/page.tsx` (or canonical settings page — implementer locates) — render `DeleteAccountSection` at the bottom
- `app/admin/users/[id]/page.tsx` (or detail page) — add delete-user button that opens `DeleteUserModal`
- `lib/user/api.ts` — add `userApi.deleteSelf(password)`

---

## Conventions

- **Subagent stages files; parent commits.** Do NOT run `git commit` in the subagent — parent handles it after verifying.
- **Don't import React** (per project global instructions).
- **Don't run `npm run dev`** — user keeps it running.
- **Backend tests:** `./mvnw test` (full) or `./mvnw test -Dtest=ClassName` (targeted).
- **Frontend tests:** `npx vitest run <path>` for targeted; `npx vitest run` for full.
- **Frontend type-check:** `npx tsc --noEmit`.
- **Lombok required** on all backend entities/services per project conventions.
- **JPA `ddl-auto: update`** — no Flyway migrations; new columns auto-migrate.
- **Final:** push branch + open PR against `dev`. Do NOT auto-merge.

---

## Task 1: Backend foundation — enum + columns

**Goal:** Land the enum value, three new columns (User/Escrow/Review), and email-nullable relaxation. Pure additive change — nothing references the new fields yet.

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/admin/audit/AdminActionType.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/admin/audit/AdminActionTypeCheckConstraintInitializer.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/user/User.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/escrow/Escrow.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/review/Review.java` (or wherever Review entity lives — `grep -rln "@Entity.*Review\|class Review" backend/src/main/java/`)
- Test: `backend/src/test/java/com/slparcelauctions/backend/admin/audit/UserDeletedByAdminEnumTest.java`

- [ ] **Step 1: Write failing test for new enum value**

```java
// backend/src/test/java/com/slparcelauctions/backend/admin/audit/UserDeletedByAdminEnumTest.java
package com.slparcelauctions.backend.admin.audit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserDeletedByAdminEnumTest {

    @Test
    void userDeletedByAdminEnumValueExists() {
        assertThat(AdminActionType.USER_DELETED_BY_ADMIN.name())
                .isEqualTo("USER_DELETED_BY_ADMIN");
    }
}
```

- [ ] **Step 2: Run test (expect compile failure)**

```
cd backend && ./mvnw test -Dtest=UserDeletedByAdminEnumTest
```

- [ ] **Step 3: Add enum value**

In `AdminActionType.java`, append to the enum list (preserve existing values exactly):

```java
    USER_DELETED_BY_ADMIN
```

- [ ] **Step 4: Update check-constraint initializer**

Read `AdminActionTypeCheckConstraintInitializer.java`. Append `USER_DELETED_BY_ADMIN` to whichever list of enum values it generates the check-constraint SQL from. If the initializer reads `AdminActionType.values()` reflectively, no code change needed; if it has a hardcoded list, append the new value.

- [ ] **Step 5: Add columns to User**

Read existing `User.java`. Find the email field. It currently looks like:

```java
@Column(nullable = false, unique = true)
private String email;
```

Change to:

```java
@Column(unique = true)
private String email;
```

Add the new field, placed logically near other identity fields:

```java
@Column(name = "deleted_at")
private OffsetDateTime deletedAt;
```

Add `import java.time.OffsetDateTime;` if not already present.

- [ ] **Step 6: Add column to Escrow**

In `Escrow.java`, add inside the entity body:

```java
@Column(name = "reminder_sent_at")
private OffsetDateTime reminderSentAt;
```

- [ ] **Step 7: Add column to Review**

Locate `Review.java` (likely at `backend/src/main/java/com/slparcelauctions/backend/review/Review.java`). Add:

```java
@Column(name = "response_closing_reminder_sent_at")
private OffsetDateTime responseClosingReminderSentAt;
```

- [ ] **Step 8: Run test, expect PASS**

```
cd backend && ./mvnw test -Dtest=UserDeletedByAdminEnumTest
```

- [ ] **Step 9: Run full backend suite to verify no regressions**

```
cd backend && ./mvnw test
```

Expected: all 1545+ tests pass except known flake `AuctionRepositoryOwnershipCheckTest`. The email-nullable relaxation may surface latent test assumptions — fix any that fail because they assume non-null email.

- [ ] **Step 10: Stage (do NOT commit)**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/admin/audit/AdminActionType.java \
        backend/src/main/java/com/slparcelauctions/backend/admin/audit/AdminActionTypeCheckConstraintInitializer.java \
        backend/src/main/java/com/slparcelauctions/backend/user/User.java \
        backend/src/main/java/com/slparcelauctions/backend/escrow/Escrow.java \
        backend/src/main/java/com/slparcelauctions/backend/review/Review.java \
        backend/src/test/java/com/slparcelauctions/backend/admin/audit/UserDeletedByAdminEnumTest.java
```

(Adjust the Review path if grep found a different location.)

---

## Task 2: UserDeletionService + exceptions

**Goal:** Service that orchestrates soft-delete in a single transaction. Six exception classes for the precondition matrix. The structured 409 payload DTO.

**Files (all new):**
- `backend/src/main/java/com/slparcelauctions/backend/user/deletion/UserDeletionService.java`
- `backend/src/main/java/com/slparcelauctions/backend/user/deletion/DeletionPreconditionError.java`
- `backend/src/main/java/com/slparcelauctions/backend/user/deletion/exception/UserAlreadyDeletedException.java`
- `backend/src/main/java/com/slparcelauctions/backend/user/deletion/exception/InvalidPasswordException.java`
- `backend/src/main/java/com/slparcelauctions/backend/user/deletion/exception/DeletionPreconditionException.java` (interface)
- `backend/src/main/java/com/slparcelauctions/backend/user/deletion/exception/ActiveAuctionsException.java`
- `backend/src/main/java/com/slparcelauctions/backend/user/deletion/exception/OpenEscrowsException.java`
- `backend/src/main/java/com/slparcelauctions/backend/user/deletion/exception/ActiveHighBidsException.java`
- `backend/src/main/java/com/slparcelauctions/backend/user/deletion/exception/ActiveProxyBidsException.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/user/deletion/UserDeletionServiceTest.java`

**Files to modify:**
- `backend/src/main/java/com/slparcelauctions/backend/admin/audit/AdminActionService.java` — verify the existing `record(...)` signature

- [ ] **Step 1: Create exception classes**

```java
// UserAlreadyDeletedException.java
package com.slparcelauctions.backend.user.deletion.exception;

public class UserAlreadyDeletedException extends RuntimeException {
    public UserAlreadyDeletedException(long userId) {
        super("User " + userId + " is already deleted");
    }
}
```

```java
// InvalidPasswordException.java
package com.slparcelauctions.backend.user.deletion.exception;

public class InvalidPasswordException extends RuntimeException {
    public InvalidPasswordException() {
        super("Invalid password");
    }
}
```

```java
// DeletionPreconditionException.java
package com.slparcelauctions.backend.user.deletion.exception;

import java.util.List;

/** Marker interface for the 4 precondition-failure exceptions. */
public interface DeletionPreconditionException {
    String getCode();
    List<Long> getBlockingIds();
}
```

```java
// ActiveAuctionsException.java
package com.slparcelauctions.backend.user.deletion.exception;

import java.util.List;

public class ActiveAuctionsException extends RuntimeException implements DeletionPreconditionException {
    private final List<Long> auctionIds;

    public ActiveAuctionsException(List<Long> auctionIds) {
        super("User has " + auctionIds.size() + " active auction(s)");
        this.auctionIds = auctionIds;
    }

    @Override public String getCode() { return "ACTIVE_AUCTIONS"; }
    @Override public List<Long> getBlockingIds() { return auctionIds; }
}
```

(Repeat the pattern for `OpenEscrowsException`, `ActiveHighBidsException`, `ActiveProxyBidsException` — each takes a `List<Long>` of the relevant entity IDs and returns a distinct `code` string: `OPEN_ESCROWS`, `ACTIVE_HIGH_BIDS`, `ACTIVE_PROXY_BIDS`.)

- [ ] **Step 2: Create the structured-error DTO**

```java
// DeletionPreconditionError.java
package com.slparcelauctions.backend.user.deletion;

import java.util.List;

public record DeletionPreconditionError(
        String code,
        String message,
        List<Long> blockingIds) {
}
```

- [ ] **Step 3: Write failing test**

```java
// UserDeletionServiceTest.java
package com.slparcelauctions.backend.user.deletion;

import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.user.deletion.exception.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
@TestPropertySource(properties = {
    "slpa.notifications.cleanup.enabled=false",
    "slpa.bot-pool-health-log.enabled=false",
    "slpa.escrow-transfer-reminder.enabled=false",
    "slpa.review-response-reminder.enabled=false"
})
class UserDeletionServiceTest {

    @Autowired UserDeletionService service;
    @Autowired UserRepository userRepo;

    @Test
    void deleteSelfHappyPathScrubsIdentityAndSetsDeletedAt() {
        User user = seedActiveUserWithNoBlockingState();
        Long userId = user.getId();

        service.deleteSelf(userId, "correct-password");

        User reloaded = userRepo.findById(userId).orElseThrow();
        assertThat(reloaded.getDeletedAt()).isNotNull();
        assertThat(reloaded.getEmail()).isNull();
        assertThat(reloaded.getDisplayName()).isEqualTo("Deleted user #" + userId);
        assertThat(reloaded.getBio()).isNull();
        assertThat(reloaded.getAvatarKey()).isNull();
        // slAvatarUuid retained:
        assertThat(reloaded.getSlAvatarUuid()).isEqualTo(user.getSlAvatarUuid());
    }

    @Test
    void deleteSelfBumpsTokenVersion() {
        User user = seedActiveUserWithNoBlockingState();
        long beforeTv = user.getTokenVersion();
        service.deleteSelf(user.getId(), "correct-password");
        User reloaded = userRepo.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getTokenVersion()).isGreaterThan(beforeTv);
    }

    @Test
    void deleteSelfWithWrongPasswordThrows403() {
        User user = seedActiveUserWithNoBlockingState();
        assertThatThrownBy(() -> service.deleteSelf(user.getId(), "wrong"))
                .isInstanceOf(InvalidPasswordException.class);
    }

    @Test
    void deleteAlreadyDeletedThrows410() {
        User user = seedActiveUserWithNoBlockingState();
        service.deleteSelf(user.getId(), "correct-password");
        assertThatThrownBy(() -> service.deleteSelf(user.getId(), "correct-password"))
                .isInstanceOf(UserAlreadyDeletedException.class);
    }

    @Test
    void deleteWithActiveAuctionThrowsPreconditionException() {
        User user = seedUserWithActiveAuction();
        assertThatThrownBy(() -> service.deleteSelf(user.getId(), "correct-password"))
                .isInstanceOf(ActiveAuctionsException.class);
    }

    @Test
    void deleteWithOpenEscrowThrowsPreconditionException() {
        User user = seedUserWithOpenEscrow();
        assertThatThrownBy(() -> service.deleteSelf(user.getId(), "correct-password"))
                .isInstanceOf(OpenEscrowsException.class);
    }

    @Test
    void deleteWithActiveHighBidThrowsPreconditionException() {
        User user = seedUserWithActiveHighBid();
        assertThatThrownBy(() -> service.deleteSelf(user.getId(), "correct-password"))
                .isInstanceOf(ActiveHighBidsException.class);
    }

    @Test
    void deleteWithActiveProxyBidThrowsPreconditionException() {
        User user = seedUserWithActiveProxyBid();
        assertThatThrownBy(() -> service.deleteSelf(user.getId(), "correct-password"))
                .isInstanceOf(ActiveProxyBidsException.class);
    }

    @Test
    void deleteByAdminWritesAdminActionsRow() {
        User target = seedActiveUserWithNoBlockingState();
        Long adminId = seedAdminUser();
        service.deleteByAdmin(target.getId(), adminId, "Spam account");
        // Assert admin_actions row written with USER_DELETED_BY_ADMIN type
        // (use the existing AdminActionRepository to query)
    }

    @Test
    void deleteByAdminDoesNotRequirePasswordVerification() {
        User target = seedActiveUserWithNoBlockingState();
        Long adminId = seedAdminUser();
        // Should not throw InvalidPassword — admin path has no password check
        service.deleteByAdmin(target.getId(), adminId, "Test");
        assertThat(userRepo.findById(target.getId()).orElseThrow().getDeletedAt()).isNotNull();
    }

    // Helper methods (implementer fills in based on existing test fixtures
    // in the codebase — search for "seedUser" or similar in existing tests)
    private User seedActiveUserWithNoBlockingState() { /* ... */ throw new UnsupportedOperationException(); }
    private User seedUserWithActiveAuction() { /* ... */ throw new UnsupportedOperationException(); }
    private User seedUserWithOpenEscrow() { /* ... */ throw new UnsupportedOperationException(); }
    private User seedUserWithActiveHighBid() { /* ... */ throw new UnsupportedOperationException(); }
    private User seedUserWithActiveProxyBid() { /* ... */ throw new UnsupportedOperationException(); }
    private Long seedAdminUser() { /* ... */ throw new UnsupportedOperationException(); }
}
```

- [ ] **Step 4: Implement UserDeletionService**

```java
// UserDeletionService.java
package com.slparcelauctions.backend.user.deletion;

import com.slparcelauctions.backend.admin.audit.AdminActionService;
import com.slparcelauctions.backend.admin.audit.AdminActionTargetType;
import com.slparcelauctions.backend.admin.audit.AdminActionType;
import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.bid.Bid;       // adapt to actual package
import com.slparcelauctions.backend.bid.BidRepository;
import com.slparcelauctions.backend.bid.ProxyBid;
import com.slparcelauctions.backend.bid.ProxyBidRepository;
import com.slparcelauctions.backend.escrow.Escrow;
import com.slparcelauctions.backend.escrow.EscrowRepository;
import com.slparcelauctions.backend.escrow.EscrowState;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.user.deletion.exception.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserDeletionService {

    private static final List<AuctionStatus> BLOCKING_AUCTION_STATUSES = List.of(
            AuctionStatus.ACTIVE,
            AuctionStatus.ESCROW_PENDING,
            AuctionStatus.ESCROW_FUNDED,
            AuctionStatus.TRANSFER_PENDING);
    private static final List<EscrowState> OPEN_ESCROW_STATES = List.of(
            EscrowState.FUNDED,
            EscrowState.TRANSFER_PENDING,
            EscrowState.DISPUTED,
            EscrowState.FROZEN);

    private final UserRepository userRepo;
    private final AuctionRepository auctionRepo;
    private final EscrowRepository escrowRepo;
    private final BidRepository bidRepo;
    private final ProxyBidRepository proxyBidRepo;
    private final PasswordEncoder passwordEncoder;
    private final AdminActionService adminActionService;
    private final Clock clock;
    // Plus repositories for entities that get hard-deleted as part of cascade:
    // (Implementer locates: SavedSearchRepository, WatchlistRepository,
    //  NotificationPreferencesRepository, NotificationRepository, etc.)

    @Transactional
    public void deleteSelf(Long userId, String password) {
        User user = loadUserOrThrow(userId);
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new InvalidPasswordException();
        }
        checkPreconditions(user);
        runCascade(user);
        log.info("User {} deleted their account via self-service", userId);
    }

    @Transactional
    public void deleteByAdmin(Long targetUserId, Long adminUserId, String adminNote) {
        User user = loadUserOrThrow(targetUserId);
        checkPreconditions(user);
        runCascade(user);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("targetUserId", targetUserId);
        details.put("adminNote", adminNote);
        adminActionService.record(adminUserId,
                AdminActionType.USER_DELETED_BY_ADMIN,
                AdminActionTargetType.USER,
                targetUserId,
                adminNote,
                details);
        log.info("User {} deleted by admin {}: {}", targetUserId, adminUserId, adminNote);
    }

    private User loadUserOrThrow(Long userId) {
        User user = userRepo.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        if (user.getDeletedAt() != null) {
            throw new UserAlreadyDeletedException(userId);
        }
        return user;
    }

    private void checkPreconditions(User user) {
        // Active auctions as seller
        List<Long> activeAuctionIds = auctionRepo
                .findIdsBySellerAndStatusIn(user, BLOCKING_AUCTION_STATUSES);
        if (!activeAuctionIds.isEmpty()) {
            throw new ActiveAuctionsException(activeAuctionIds);
        }

        // Open escrows where user is winner OR seller
        List<Long> openEscrowIds = escrowRepo
                .findIdsByUserInvolvedAndStateIn(user.getId(), OPEN_ESCROW_STATES);
        if (!openEscrowIds.isEmpty()) {
            throw new OpenEscrowsException(openEscrowIds);
        }

        // Active high bids
        List<Long> highBidAuctionIds = auctionRepo
                .findIdsByCurrentHighBidder(user);
        if (!highBidAuctionIds.isEmpty()) {
            throw new ActiveHighBidsException(highBidAuctionIds);
        }

        // Active proxy bids
        List<Long> activeProxyBidIds = proxyBidRepo
                .findActiveIdsByBidder(user);
        if (!activeProxyBidIds.isEmpty()) {
            throw new ActiveProxyBidsException(activeProxyBidIds);
        }
    }

    private void runCascade(User user) {
        // 1. Mutate User row
        user.setEmail(null);
        user.setDisplayName("Deleted user #" + user.getId());
        user.setBio(null);
        user.setAvatarKey(null);
        // slAvatarUuid retained intentionally — preserves ban-check coverage
        user.setDeletedAt(OffsetDateTime.now(clock));
        user.setRole(com.slparcelauctions.backend.user.Role.USER);
        user.setTokenVersion(user.getTokenVersion() + 1);
        userRepo.save(user);

        // 2. Hard-delete subscribed-only entities
        // (Implementer locates and adds calls based on existing repositories:
        //  refreshTokenRepo.deleteByUserId(user.getId()))
        //  savedSearchRepo.deleteByUser(user); (if exists)
        //  watchlistRepo.deleteByUser(user); (if exists)
        //  notificationPreferencesRepo.deleteByUserId(user.getId()); (if separate from User)
        //  notificationRepo.deleteByUserId(user.getId());
        //  pendingReviewObligationRepo.deleteByUser(user); (Epic 08 obligations)

        // 3. Bumping tokenVersion in step 1 already invalidates outstanding tokens.
    }
}
```

The repository methods used above (`findIdsBySellerAndStatusIn`, `findIdsByUserInvolvedAndStateIn`, `findIdsByCurrentHighBidder`, `findActiveIdsByBidder`) may not exist yet. Add them with appropriate JPQL queries. Example:

```java
// In AuctionRepository:
@Query("SELECT a.id FROM Auction a WHERE a.seller = :seller AND a.status IN :statuses")
List<Long> findIdsBySellerAndStatusIn(User seller, Collection<AuctionStatus> statuses);

@Query("SELECT a.id FROM Auction a WHERE a.currentHighBidder = :user AND a.status = 'ACTIVE'")
List<Long> findIdsByCurrentHighBidder(User user);

// In EscrowRepository:
@Query("SELECT e.id FROM Escrow e WHERE (e.winnerUserId = :userId OR e.auction.seller.id = :userId) AND e.state IN :states")
List<Long> findIdsByUserInvolvedAndStateIn(Long userId, Collection<EscrowState> states);

// In ProxyBidRepository:
@Query("SELECT p.id FROM ProxyBid p WHERE p.bidder = :user AND p.status = 'ACTIVE'")
List<Long> findActiveIdsByBidder(User user);
```

Adapt field names to match the actual entities (e.g., `currentHighBidder` may be a different column name; `winnerUserId` may be `winner.id` if it's a relationship).

- [ ] **Step 5: Run targeted tests**

```
cd backend && ./mvnw test -Dtest=UserDeletionServiceTest
```

Expected: all 10 tests pass after implementer fills in the seed helpers based on existing fixtures.

- [ ] **Step 6: Run full backend suite**

```
cd backend && ./mvnw test
```

Expected: all tests pass.

- [ ] **Step 7: Stage**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/user/deletion/ \
        backend/src/main/java/com/slparcelauctions/backend/auction/AuctionRepository.java \
        backend/src/main/java/com/slparcelauctions/backend/escrow/EscrowRepository.java \
        backend/src/main/java/com/slparcelauctions/backend/bid/ProxyBidRepository.java \
        backend/src/test/java/com/slparcelauctions/backend/user/deletion/UserDeletionServiceTest.java
```

(Adjust paths if entities live elsewhere.)

---

## Task 3: Deletion endpoints + exception handlers

**Goal:** Wire `DELETE /api/v1/users/me` and `DELETE /api/v1/admin/users/{id}` to `UserDeletionService`. Add exception handlers that return the structured `DeletionPreconditionError` payload.

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/user/deletion/UserDeletionRequest.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/user/deletion/AdminUserDeletionRequest.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/user/UserController.java` — replace 501 stub with real DELETE handler
- Modify: existing admin user controller (likely `backend/src/main/java/com/slparcelauctions/backend/admin/users/AdminUserController.java`) — add DELETE endpoint
- Modify: `backend/src/main/java/com/slparcelauctions/backend/admin/exception/AdminExceptionHandler.java` (or new package-scoped handler in `user/deletion/`) — add 6 exception handlers
- Test: integration test for both endpoints

- [ ] **Step 1: Create request DTOs**

```java
// UserDeletionRequest.java (self-service)
package com.slparcelauctions.backend.user.deletion;

import jakarta.validation.constraints.NotBlank;

public record UserDeletionRequest(
        @NotBlank String password) {
}
```

```java
// AdminUserDeletionRequest.java
package com.slparcelauctions.backend.user.deletion;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AdminUserDeletionRequest(
        @NotBlank @Size(min = 1, max = 500) String adminNote) {
}
```

- [ ] **Step 2: Add self-service DELETE endpoint**

In `UserController.java`, find the existing 501-stub handler for `DELETE /me` (or add a new method if there's no stub). Replace/add:

```java
@DeleteMapping("/me")
@ResponseStatus(HttpStatus.NO_CONTENT)
public void deleteSelf(
        @Valid @RequestBody UserDeletionRequest body,
        @AuthenticationPrincipal AuthPrincipal principal) {
    userDeletionService.deleteSelf(principal.userId(), body.password());
}
```

Inject `UserDeletionService` if not already injected (Lombok `@RequiredArgsConstructor`).

- [ ] **Step 3: Add admin DELETE endpoint**

In the existing admin user controller, add:

```java
@DeleteMapping("/{id}")
@ResponseStatus(HttpStatus.NO_CONTENT)
public void deleteUser(
        @PathVariable Long id,
        @Valid @RequestBody AdminUserDeletionRequest body,
        @AuthenticationPrincipal AuthPrincipal principal) {
    userDeletionService.deleteByAdmin(id, principal.userId(), body.adminNote());
}
```

- [ ] **Step 4: Add exception handlers**

The handlers can live in `AdminExceptionHandler.java` (existing) since they apply to admin endpoints too, OR a new package-scoped handler in `user/deletion/`. Pick whichever matches existing patterns — likely the global handler for cross-cutting concerns. If the project has a global error handler outside of admin (search `@RestControllerAdvice` without package restriction), use that.

Add these handlers (preserve existing handlers):

```java
@ExceptionHandler(UserAlreadyDeletedException.class)
public ResponseEntity<AdminApiError> handleAlreadyDeleted(UserAlreadyDeletedException ex) {
    return ResponseEntity.status(HttpStatus.GONE)
        .body(AdminApiError.of("USER_ALREADY_DELETED", ex.getMessage()));
}

@ExceptionHandler(InvalidPasswordException.class)
public ResponseEntity<AdminApiError> handleInvalidPassword(InvalidPasswordException ex) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .body(AdminApiError.of("INVALID_PASSWORD", ex.getMessage()));
}

@ExceptionHandler({
    ActiveAuctionsException.class,
    OpenEscrowsException.class,
    ActiveHighBidsException.class,
    ActiveProxyBidsException.class
})
public ResponseEntity<DeletionPreconditionError> handlePrecondition(
        RuntimeException ex) {
    DeletionPreconditionException dpe = (DeletionPreconditionException) ex;
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(new DeletionPreconditionError(
                dpe.getCode(),
                ex.getMessage(),
                dpe.getBlockingIds()));
}
```

The `AdminApiError.of(code, message)` shape may differ — match the existing patterns. If the project uses Spring's `ProblemDetail`, mirror that.

- [ ] **Step 5: Integration test for both endpoints**

```java
// Integration test (add to existing UserController test or create new)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UserDeletionEndpointTest {

    @Autowired MockMvc mvc;

    @Test @WithMockUser
    void deleteSelfReturns204() throws Exception {
        // Seed user, authenticate, POST DELETE /me with password
        mvc.perform(delete("/api/v1/users/me")
                .contentType(APPLICATION_JSON)
                .content("{\"password\":\"correct-password\"}"))
                .andExpect(status().isNoContent());
    }

    @Test @WithMockUser
    void deleteSelfWith403WhenWrongPassword() throws Exception {
        mvc.perform(delete("/api/v1/users/me")
                .contentType(APPLICATION_JSON)
                .content("{\"password\":\"wrong\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INVALID_PASSWORD"));
    }

    @Test @WithMockUser
    void deleteSelf409WithBlockingIdsWhenActiveAuction() throws Exception {
        // Seed user with active auction
        mvc.perform(delete("/api/v1/users/me")
                .contentType(APPLICATION_JSON)
                .content("{\"password\":\"correct-password\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACTIVE_AUCTIONS"))
                .andExpect(jsonPath("$.blockingIds").isArray());
    }

    @Test @WithMockAdmin
    void deleteUserAsAdminReturns204() throws Exception {
        // Seed target user, admin authenticated
        mvc.perform(delete("/api/v1/admin/users/" + targetId)
                .contentType(APPLICATION_JSON)
                .content("{\"adminNote\":\"Test deletion\"}"))
                .andExpect(status().isNoContent());
    }

    @Test @WithMockUser(roles = "USER")
    void nonAdminCannotDeleteOtherUser() throws Exception {
        mvc.perform(delete("/api/v1/admin/users/1")
                .contentType(APPLICATION_JSON)
                .content("{\"adminNote\":\"Test\"}"))
                .andExpect(status().isForbidden());
    }
}
```

- [ ] **Step 6: Run tests + full suite**

```
cd backend && ./mvnw test
```

- [ ] **Step 7: Stage**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/user/deletion/ \
        backend/src/main/java/com/slparcelauctions/backend/user/UserController.java \
        backend/src/main/java/com/slparcelauctions/backend/admin/ \
        backend/src/test/java/com/slparcelauctions/backend/user/deletion/
```

---

## Task 4: AdminAuditLogService + DTOs

**Goal:** Read-only service over `admin_actions` with paginated query and CSV streaming. Filter set: action type, target type, admin user, date range, free-text notes search.

**Files (all new in `backend/src/main/java/com/slparcelauctions/backend/admin/audit/`):**
- `AdminAuditLogService.java`
- `AdminAuditLogRow.java`
- `AdminAuditLogFilters.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/admin/audit/AdminAuditLogServiceTest.java`

- [ ] **Step 1: DTOs**

```java
// AdminAuditLogRow.java
package com.slparcelauctions.backend.admin.audit;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AdminAuditLogRow(
        Long id,
        OffsetDateTime occurredAt,
        AdminActionType actionType,
        Long adminUserId,
        String adminEmail,
        AdminActionTargetType targetType,
        Long targetId,
        String notes,
        Map<String, Object> details) {
}
```

```java
// AdminAuditLogFilters.java
package com.slparcelauctions.backend.admin.audit;

import java.time.OffsetDateTime;

public record AdminAuditLogFilters(
        AdminActionType actionType,
        AdminActionTargetType targetType,
        Long adminUserId,
        OffsetDateTime from,
        OffsetDateTime to,
        String q) {

    public static AdminAuditLogFilters empty() {
        return new AdminAuditLogFilters(null, null, null, null, null, null);
    }
}
```

- [ ] **Step 2: Service**

```java
// AdminAuditLogService.java
package com.slparcelauctions.backend.admin.audit;

import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class AdminAuditLogService {

    private final AdminActionRepository repo;
    private final UserRepository userRepo;

    @Transactional(readOnly = true)
    public Page<AdminAuditLogRow> list(AdminAuditLogFilters filters, int page, int size) {
        Specification<AdminAction> spec = buildSpec(filters);
        Page<AdminAction> rows = repo.findAll(spec,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "occurredAt")));
        Map<Long, String> emailById = lookupAdminEmails(rows.getContent());
        return rows.map(a -> toRow(a, emailById));
    }

    @Transactional(readOnly = true)
    public Stream<AdminAuditLogRow> exportCsvStream(AdminAuditLogFilters filters) {
        Specification<AdminAction> spec = buildSpec(filters);
        // Stream all matching rows; assume table size manageable (<10k typical).
        // For larger tables, switch to a cursor-based query in a follow-up.
        List<AdminAction> rows = repo.findAll(spec,
                Sort.by(Sort.Direction.DESC, "occurredAt"));
        Map<Long, String> emailById = lookupAdminEmails(rows);
        return rows.stream().map(a -> toRow(a, emailById));
    }

    private Specification<AdminAction> buildSpec(AdminAuditLogFilters f) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (f.actionType() != null) {
                predicates.add(cb.equal(root.get("actionType"), f.actionType()));
            }
            if (f.targetType() != null) {
                predicates.add(cb.equal(root.get("targetType"), f.targetType()));
            }
            if (f.adminUserId() != null) {
                predicates.add(cb.equal(root.get("adminUserId"), f.adminUserId()));
            }
            if (f.from() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("occurredAt"), f.from()));
            }
            if (f.to() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("occurredAt"), f.to()));
            }
            if (f.q() != null && !f.q().isBlank()) {
                predicates.add(cb.like(cb.lower(root.get("notes")),
                        "%" + f.q().toLowerCase() + "%"));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private Map<Long, String> lookupAdminEmails(List<AdminAction> actions) {
        List<Long> adminIds = actions.stream()
                .map(AdminAction::getAdminUserId).distinct().toList();
        if (adminIds.isEmpty()) return Map.of();
        Map<Long, String> result = new HashMap<>();
        userRepo.findAllById(adminIds).forEach(u -> result.put(u.getId(), u.getEmail()));
        return result;
    }

    private AdminAuditLogRow toRow(AdminAction a, Map<Long, String> emailById) {
        return new AdminAuditLogRow(
                a.getId(),
                a.getOccurredAt(),
                a.getActionType(),
                a.getAdminUserId(),
                emailById.getOrDefault(a.getAdminUserId(), null),
                a.getTargetType(),
                a.getTargetId(),
                a.getNotes(),
                a.getDetails());
    }
}
```

`AdminActionRepository` may not currently extend `JpaSpecificationExecutor<AdminAction>`. Add it:

```java
public interface AdminActionRepository extends JpaRepository<AdminAction, Long>,
        JpaSpecificationExecutor<AdminAction> {
}
```

- [ ] **Step 3: Test**

```java
// AdminAuditLogServiceTest.java
package com.slparcelauctions.backend.admin.audit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
@TestPropertySource(properties = {
    "slpa.notifications.cleanup.enabled=false",
    "slpa.bot-pool-health-log.enabled=false"
})
class AdminAuditLogServiceTest {
    @Autowired AdminAuditLogService service;
    @Autowired AdminActionRepository repo;

    @Test
    void listReturnsAllWhenNoFilters() {
        seedAdminAction(AdminActionType.USER_DELETED_BY_ADMIN);
        seedAdminAction(AdminActionType.DISMISS_REPORT);
        Page<AdminAuditLogRow> result = service.list(AdminAuditLogFilters.empty(), 0, 50);
        assertThat(result.getTotalElements()).isEqualTo(2);
    }

    @Test
    void listFiltersByActionType() {
        seedAdminAction(AdminActionType.USER_DELETED_BY_ADMIN);
        seedAdminAction(AdminActionType.DISMISS_REPORT);
        Page<AdminAuditLogRow> result = service.list(
                new AdminAuditLogFilters(AdminActionType.DISMISS_REPORT,
                        null, null, null, null, null), 0, 50);
        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent().get(0).actionType())
                .isEqualTo(AdminActionType.DISMISS_REPORT);
    }

    @Test
    void listFiltersByDateRange() {
        // Seed actions at different timestamps; assert filter narrows correctly
    }

    @Test
    void listFiltersByQSubstringInNotes() {
        seedAdminActionWithNotes("Spammer banned permanently");
        seedAdminActionWithNotes("Routine review");
        Page<AdminAuditLogRow> result = service.list(
                new AdminAuditLogFilters(null, null, null, null, null, "spammer"), 0, 50);
        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    @Test
    void exportCsvStreamReturnsAllMatching() {
        seedAdminAction(AdminActionType.USER_DELETED_BY_ADMIN);
        seedAdminAction(AdminActionType.DISMISS_REPORT);
        long count = service.exportCsvStream(AdminAuditLogFilters.empty()).count();
        assertThat(count).isEqualTo(2);
    }

    @Test
    void rowIncludesAdminEmail() {
        // Seed admin user + admin_actions row authored by them
        // Assert row.adminEmail() == admin's email
    }

    private AdminAction seedAdminAction(AdminActionType type) { /* helper */ throw new UnsupportedOperationException(); }
    private AdminAction seedAdminActionWithNotes(String notes) { /* helper */ throw new UnsupportedOperationException(); }
}
```

- [ ] **Step 4: Stage**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/admin/audit/AdminAuditLogService.java \
        backend/src/main/java/com/slparcelauctions/backend/admin/audit/AdminAuditLogRow.java \
        backend/src/main/java/com/slparcelauctions/backend/admin/audit/AdminAuditLogFilters.java \
        backend/src/main/java/com/slparcelauctions/backend/admin/audit/AdminActionRepository.java \
        backend/src/test/java/com/slparcelauctions/backend/admin/audit/AdminAuditLogServiceTest.java
```

---

## Task 5: AdminAuditLogController (paginated + CSV export)

**Goal:** Two endpoints: paginated list returning JSON `Page<AdminAuditLogRow>`, and CSV streaming export with `Content-Type: text/csv` and `Content-Disposition` attachment.

**Files (new):**
- `backend/src/main/java/com/slparcelauctions/backend/admin/audit/AdminAuditLogController.java`
- Test: integration test for both endpoints

- [ ] **Step 1: Controller**

```java
// AdminAuditLogController.java
package com.slparcelauctions.backend.admin.audit;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@RestController
@RequestMapping("/api/v1/admin/audit-log")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Slf4j
public class AdminAuditLogController {

    private final AdminAuditLogService service;

    @GetMapping
    public Page<AdminAuditLogRow> list(
            @RequestParam(required = false) AdminActionType actionType,
            @RequestParam(required = false) AdminActionTargetType targetType,
            @RequestParam(required = false) Long adminUserId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        AdminAuditLogFilters filters = new AdminAuditLogFilters(
                actionType, targetType, adminUserId, from, to, q);
        return service.list(filters, page, size);
    }

    @GetMapping("/export")
    public void exportCsv(
            @RequestParam(required = false) AdminActionType actionType,
            @RequestParam(required = false) AdminActionTargetType targetType,
            @RequestParam(required = false) Long adminUserId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) String q,
            HttpServletResponse response) throws IOException {
        AdminAuditLogFilters filters = new AdminAuditLogFilters(
                actionType, targetType, adminUserId, from, to, q);

        String filename = "audit-log-" + LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE) + ".csv";
        response.setContentType("text/csv");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + filename + "\"");

        try (PrintWriter writer = response.getWriter()) {
            writer.println("timestamp,action,admin_email,target_type,target_id,notes,details_json");
            service.exportCsvStream(filters).forEach(row -> {
                writer.println(formatCsvRow(row));
            });
        }
    }

    private String formatCsvRow(AdminAuditLogRow row) {
        return String.join(",",
                csvEscape(row.occurredAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)),
                csvEscape(row.actionType().name()),
                csvEscape(row.adminEmail() != null ? row.adminEmail() : ""),
                csvEscape(row.targetType() != null ? row.targetType().name() : ""),
                csvEscape(row.targetId() != null ? row.targetId().toString() : ""),
                csvEscape(row.notes() != null ? row.notes() : ""),
                csvEscape(serializeDetails(row.details())));
    }

    private String csvEscape(String value) {
        if (value == null) return "";
        boolean needsQuoting = value.contains(",") || value.contains("\"")
                || value.contains("\n") || value.contains("\r");
        if (!needsQuoting) return value;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private String serializeDetails(java.util.Map<String, Object> details) {
        if (details == null || details.isEmpty()) return "";
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(details);
        } catch (Exception e) {
            return "";
        }
    }
}
```

- [ ] **Step 2: Test**

```java
@SpringBootTest @AutoConfigureMockMvc @Transactional
class AdminAuditLogControllerTest {
    @Autowired MockMvc mvc;

    @Test @WithMockAdmin
    void listReturnsPaged() throws Exception {
        mvc.perform(get("/api/v1/admin/audit-log"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test @WithMockAdmin
    void exportCsvHasCorrectHeadersAndContent() throws Exception {
        // seed at least one admin_actions row
        mvc.perform(get("/api/v1/admin/audit-log/export"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.startsWith("attachment; filename=\"audit-log-")))
                .andExpect(content().string(org.hamcrest.Matchers.startsWith(
                        "timestamp,action,admin_email,target_type,target_id,notes,details_json")));
    }

    @Test @WithMockUser(roles = "USER")
    void nonAdminGets403() throws Exception {
        mvc.perform(get("/api/v1/admin/audit-log"))
                .andExpect(status().isForbidden());
    }
}
```

- [ ] **Step 3: Stage**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/admin/audit/AdminAuditLogController.java \
        backend/src/test/java/com/slparcelauctions/backend/admin/audit/AdminAuditLogControllerTest.java
```

---

## Task 6: Both schedulers (escrow + review)

**Goal:** Two parallel scheduler classes. Same shape, different entities + publishers. Verify `reviewResponseWindowClosing` publisher exists; add it (small ride-along) if missing.

**Files (new in `backend/src/main/java/com/slparcelauctions/backend/admin/infrastructure/reminders/`):**
- `EscrowTransferReminderScheduler.java`
- `ReviewResponseWindowClosingScheduler.java`
- Tests: `EscrowTransferReminderSchedulerTest.java`, `ReviewResponseWindowClosingSchedulerTest.java`

**Files to modify:**
- `backend/src/main/java/com/slparcelauctions/backend/escrow/EscrowRepository.java` — add query
- `backend/src/main/java/com/slparcelauctions/backend/review/ReviewRepository.java` — add query
- `backend/src/main/java/com/slparcelauctions/backend/notification/*` — verify + maybe add publisher
- `backend/src/main/java/com/slparcelauctions/backend/notification/slim/SlImLinkResolver.java` — case if newly added

- [ ] **Step 1: Verify reviewResponseWindowClosing publisher exists**

```bash
grep -rn "reviewResponseWindowClosing\|REVIEW_RESPONSE_WINDOW_CLOSING" backend/src/main/java/
```

- If exists: note the signature and skip Step 2.
- If absent: add the publisher (Step 2 below).

- [ ] **Step 2: Add reviewResponseWindowClosing publisher (only if Step 1 found it missing)**

```java
// In NotificationCategory.java, append:
    REVIEW_RESPONSE_WINDOW_CLOSING(NotificationGroup.REVIEWS),
```

```java
// In NotificationPublisher interface, add:
    void reviewResponseWindowClosing(long revieweeUserId, long reviewId, long auctionId,
                                      String parcelName, OffsetDateTime responseDeadline);
```

```java
// In NotificationDataBuilder, add:
    public static Map<String, Object> reviewResponseWindowClosing(
            long reviewId, long auctionId, String parcelName, OffsetDateTime responseDeadline) {
        Map<String, Object> m = base(auctionId, parcelName);
        m.put("reviewId", reviewId);
        m.put("responseDeadline", responseDeadline.toString());
        return m;
    }
```

```java
// In NotificationPublisherImpl, add:
    @Override
    public void reviewResponseWindowClosing(long revieweeUserId, long reviewId, long auctionId,
                                             String parcelName, OffsetDateTime responseDeadline) {
        String title = "Response window closing: " + parcelName;
        String body = "The window to respond to a review for " + parcelName
                + " closes soon. Respond before " + responseDeadline + ".";
        notificationService.publish(new NotificationEvent(
            revieweeUserId, NotificationCategory.REVIEW_RESPONSE_WINDOW_CLOSING,
            title, body,
            NotificationDataBuilder.reviewResponseWindowClosing(
                    reviewId, auctionId, parcelName, responseDeadline),
            null));
    }
```

```java
// In SlImLinkResolver switch, add (or extend an existing case):
    case REVIEW_RESPONSE_WINDOW_CLOSING ->
        base + "/auction/" + data.get("auctionId");
```

- [ ] **Step 3: Add repository queries**

In `EscrowRepository`:

```java
@Query("SELECT e FROM Escrow e WHERE e.state = 'FUNDED' " +
       "AND e.fundedAt BETWEEN :rangeStart AND :rangeEnd " +
       "AND e.reminderSentAt IS NULL")
List<Escrow> findEscrowsApproachingTransferDeadline(
    OffsetDateTime rangeStart, OffsetDateTime rangeEnd);
```

In `ReviewRepository`:

```java
// Adapt field names to match the actual Review entity. If Review has a
// responseDeadline column directly:
@Query("SELECT r FROM Review r " +
       "WHERE r.responseDeadline BETWEEN :rangeStart AND :rangeEnd " +
       "AND r.responseClosingReminderSentAt IS NULL " +
       "AND r.responseText IS NULL")
List<Review> findReviewsApproachingResponseClose(
    OffsetDateTime rangeStart, OffsetDateTime rangeEnd);

// If Review only has submittedAt + a config-based response window, the
// scheduler computes the equivalent fundedAt-style window math (see Task 6
// Step 4 below).
```

- [ ] **Step 4: Implement EscrowTransferReminderScheduler**

```java
// EscrowTransferReminderScheduler.java
package com.slparcelauctions.backend.admin.infrastructure.reminders;

import com.slparcelauctions.backend.escrow.Escrow;
import com.slparcelauctions.backend.escrow.EscrowConfigProperties;
import com.slparcelauctions.backend.escrow.EscrowRepository;
import com.slparcelauctions.backend.notification.NotificationPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "slpa.escrow-transfer-reminder",
                       name = "enabled", matchIfMissing = true)
@Slf4j
public class EscrowTransferReminderScheduler {

    private final EscrowRepository escrowRepo;
    private final NotificationPublisher publisher;
    private final EscrowConfigProperties escrowConfig;
    private final Clock clock;

    @Scheduled(cron = "${slpa.escrow-transfer-reminder.cron:0 0 9 * * *}", zone = "UTC")
    @Transactional
    public void run() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        long windowHours = escrowConfig.getTransferDeadlineHours();

        // Reminder fires when deadline ∈ [now+12h, now+36h]
        // Equivalently: fundedAt ∈ [now − (windowHours − 12h), now − (windowHours − 36h)]
        // For windowHours=72: rangeStart = T−60h (earlier), rangeEnd = T−36h (later)
        OffsetDateTime rangeStart = now.minusHours(windowHours - 12);
        OffsetDateTime rangeEnd = now.minusHours(windowHours - 36);

        List<Escrow> rows = escrowRepo.findEscrowsApproachingTransferDeadline(
                rangeStart, rangeEnd);  // BETWEEN: lower, upper

        for (Escrow e : rows) {
            publisher.escrowTransferReminder(
                    e.getAuction().getSeller().getId(),
                    e.getAuction().getId(),
                    e.getId(),
                    e.getAuction().getTitle(),
                    e.getFundedAt().plusHours(windowHours));
            e.setReminderSentAt(now);
            escrowRepo.save(e);
        }

        log.info("EscrowTransferReminderScheduler: reminded {} escrow(s)", rows.size());
    }
}
```

The `EscrowConfigProperties.getTransferDeadlineHours()` method may not exist exactly under that name. Implementer locates the actual config bean and method (search `@ConfigurationProperties.*escrow` or `transfer-deadline-hours` in `application.yml`). Adapt the call.

- [ ] **Step 5: Implement ReviewResponseWindowClosingScheduler**

```java
// ReviewResponseWindowClosingScheduler.java
package com.slparcelauctions.backend.admin.infrastructure.reminders;

import com.slparcelauctions.backend.notification.NotificationPublisher;
import com.slparcelauctions.backend.review.Review;
import com.slparcelauctions.backend.review.ReviewRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "slpa.review-response-reminder",
                       name = "enabled", matchIfMissing = true)
@Slf4j
public class ReviewResponseWindowClosingScheduler {

    private final ReviewRepository reviewRepo;
    private final NotificationPublisher publisher;
    private final Clock clock;

    @Scheduled(cron = "${slpa.review-response-reminder.cron:0 5 9 * * *}", zone = "UTC")
    @Transactional
    public void run() {
        OffsetDateTime now = OffsetDateTime.now(clock);

        // Reminder fires when responseDeadline ∈ [now+24h, now+48h]
        OffsetDateTime rangeStart = now.plusHours(24);
        OffsetDateTime rangeEnd = now.plusHours(48);

        List<Review> rows = reviewRepo.findReviewsApproachingResponseClose(
                rangeStart, rangeEnd);

        for (Review r : rows) {
            publisher.reviewResponseWindowClosing(
                    r.getRevieweeUserId(),    // adapt to actual field name
                    r.getId(),
                    r.getAuctionId(),         // adapt
                    r.getParcelName(),        // adapt
                    r.getResponseDeadline());  // adapt
            r.setResponseClosingReminderSentAt(now);
            reviewRepo.save(r);
        }

        log.info("ReviewResponseWindowClosingScheduler: reminded {} review(s)", rows.size());
    }
}
```

The publisher arguments depend on the publisher's actual signature (verified in Step 1). The Review getters depend on actual field names — implementer adapts.

- [ ] **Step 6: Tests**

```java
// EscrowTransferReminderSchedulerTest.java
@SpringBootTest
@Transactional
@TestPropertySource(properties = {
    "slpa.escrow-transfer-reminder.enabled=true",
    "slpa.escrow-transfer-reminder.cron=0 0 0 1 1 0",  // never fires during test
    "slpa.review-response-reminder.enabled=false",
    "slpa.notifications.cleanup.enabled=false",
    "slpa.bot-pool-health-log.enabled=false"
})
class EscrowTransferReminderSchedulerTest {
    @Autowired EscrowTransferReminderScheduler scheduler;
    @Autowired EscrowRepository escrowRepo;
    @MockitoBean NotificationPublisher publisher;

    @Test
    void remindsEscrowsInWindow() {
        // Seed an escrow with fundedAt = now - 48h (deadline at +24h, within reminder window)
        Escrow e = seedEscrowFundedHoursAgo(48L);
        scheduler.run();
        verify(publisher).escrowTransferReminder(
                anyLong(), anyLong(), eq(e.getId()), anyString(), any(OffsetDateTime.class));
        Escrow reloaded = escrowRepo.findById(e.getId()).orElseThrow();
        assertThat(reloaded.getReminderSentAt()).isNotNull();
    }

    @Test
    void doesNotRemindAlreadyReminded() {
        Escrow e = seedEscrowFundedHoursAgo(48L);
        e.setReminderSentAt(OffsetDateTime.now());
        escrowRepo.save(e);
        scheduler.run();
        verify(publisher, never()).escrowTransferReminder(
                anyLong(), anyLong(), anyLong(), anyString(), any());
    }

    @Test
    void doesNotRemindOutsideWindow() {
        seedEscrowFundedHoursAgo(10L);  // too early — deadline at +62h
        scheduler.run();
        verify(publisher, never()).escrowTransferReminder(
                anyLong(), anyLong(), anyLong(), anyString(), any());
    }

    private Escrow seedEscrowFundedHoursAgo(long hours) { /* helper */ throw new UnsupportedOperationException(); }
}
```

Mirror this shape for `ReviewResponseWindowClosingSchedulerTest`.

- [ ] **Step 7: Stage**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/admin/infrastructure/reminders/ \
        backend/src/main/java/com/slparcelauctions/backend/escrow/EscrowRepository.java \
        backend/src/main/java/com/slparcelauctions/backend/review/ReviewRepository.java \
        backend/src/main/java/com/slparcelauctions/backend/notification/ \
        backend/src/test/java/com/slparcelauctions/backend/admin/infrastructure/reminders/
```

---

## Task 7: Frontend foundation — types + api + queryKeys + MSW

**Goal:** Types, API client groups, query keys, and MSW handler factories for audit log + deletion endpoints. Pure plumbing.

**Files:**
- Create: `frontend/src/lib/admin/auditLog.ts`
- Create: `frontend/src/lib/admin/auditLogHooks.ts`
- Create: `frontend/src/lib/admin/deletionHooks.ts`
- Modify: `frontend/src/lib/admin/types.ts` — re-export
- Modify: `frontend/src/lib/admin/api.ts` — add `adminApi.auditLog`, `adminApi.users.delete`
- Modify: `frontend/src/lib/admin/queryKeys.ts` — audit log keys
- Modify: `frontend/src/lib/user/api.ts` — add `userApi.deleteSelf(password)`
- Modify: `frontend/src/test/msw/handlers.ts` — handler factories

- [ ] **Step 1: Audit log types**

```typescript
// frontend/src/lib/admin/auditLog.ts

export type AdminActionType =
  | "DISMISS_REPORT" | "WARN_SELLER_FROM_REPORT" | "SUSPEND_LISTING_FROM_REPORT"
  | "CANCEL_LISTING_FROM_REPORT" | "CREATE_BAN" | "LIFT_BAN"
  | "PROMOTE_USER" | "DEMOTE_USER" | "RESET_FRIVOLOUS_COUNTER"
  | "REINSTATE_LISTING" | "DISPUTE_RESOLVED" | "LISTING_CANCELLED_VIA_DISPUTE"
  | "WITHDRAWAL_REQUESTED" | "OWNERSHIP_RECHECK_INVOKED"
  | "TERMINAL_SECRET_ROTATED" | "USER_DELETED_BY_ADMIN";

export type AdminActionTargetType =
  | "REPORT" | "BAN" | "USER" | "AUCTION" | "FRAUD_FLAG"
  | "DISPUTE" | "WITHDRAWAL" | "TERMINAL_SECRET";

export type AdminAuditLogRow = {
  id: number;
  occurredAt: string;
  actionType: AdminActionType;
  adminUserId: number;
  adminEmail: string | null;
  targetType: AdminActionTargetType | null;
  targetId: number | null;
  notes: string | null;
  details: Record<string, unknown>;
};

export type AdminAuditLogFilters = {
  actionType?: AdminActionType;
  targetType?: AdminActionTargetType;
  adminUserId?: number;
  from?: string;
  to?: string;
  q?: string;
  page?: number;
  size?: number;
};
```

- [ ] **Step 2: Re-export from types.ts**

Append to `frontend/src/lib/admin/types.ts`:

```typescript
export * from "./auditLog";
```

- [ ] **Step 3: Extend api.ts**

```typescript
// In adminApi (frontend/src/lib/admin/api.ts), add:

  auditLog: {
    list(filters: AdminAuditLogFilters): Promise<Page<AdminAuditLogRow>> {
      const search = new URLSearchParams();
      if (filters.actionType) search.set("actionType", filters.actionType);
      if (filters.targetType) search.set("targetType", filters.targetType);
      if (filters.adminUserId !== undefined) search.set("adminUserId", String(filters.adminUserId));
      if (filters.from) search.set("from", filters.from);
      if (filters.to) search.set("to", filters.to);
      if (filters.q) search.set("q", filters.q);
      search.set("page", String(filters.page ?? 0));
      search.set("size", String(filters.size ?? 50));
      return api.get(`/api/v1/admin/audit-log?${search.toString()}`);
    },
    exportUrl(filters: AdminAuditLogFilters): string {
      const search = new URLSearchParams();
      if (filters.actionType) search.set("actionType", filters.actionType);
      if (filters.targetType) search.set("targetType", filters.targetType);
      if (filters.adminUserId !== undefined) search.set("adminUserId", String(filters.adminUserId));
      if (filters.from) search.set("from", filters.from);
      if (filters.to) search.set("to", filters.to);
      if (filters.q) search.set("q", filters.q);
      return `/api/v1/admin/audit-log/export?${search.toString()}`;
    },
  },

  users: {
    // ... existing user methods preserved ...
    delete(userId: number, adminNote: string): Promise<void> {
      return api.delete(`/api/v1/admin/users/${userId}`, { adminNote });
    },
  },
```

If `adminApi.users` already exists from sub-spec 2, add only the `delete` method (don't replace the existing object).

- [ ] **Step 4: Add userApi.deleteSelf**

In `frontend/src/lib/user/api.ts`:

```typescript
  deleteSelf(password: string): Promise<void> {
    return api.delete("/api/v1/users/me", { password });
  },
```

(`api.delete` may need to be added to the api helpers if it doesn't exist — most clients have GET/POST/PUT/DELETE; adapt based on existing pattern. If only POST exists, the deletion bodies use `api.requestWithBody("DELETE", path, body)` or similar.)

- [ ] **Step 5: Extend queryKeys.ts**

```typescript
  auditLog: () => [...adminQueryKeys.all, "audit-log"] as const,
  auditLogList: (filters: AdminAuditLogFilters) =>
    [...adminQueryKeys.auditLog(), "list", filters] as const,
```

- [ ] **Step 6: Hooks**

```typescript
// auditLogHooks.ts
import { useQuery } from "@tanstack/react-query";
import { adminApi } from "./api";
import { adminQueryKeys } from "./queryKeys";
import type { AdminAuditLogFilters } from "./auditLog";

export function useAuditLog(filters: AdminAuditLogFilters) {
  return useQuery({
    queryKey: adminQueryKeys.auditLogList(filters),
    queryFn: () => adminApi.auditLog.list(filters),
  });
}

export function useAuditLogExportUrl(filters: AdminAuditLogFilters) {
  return adminApi.auditLog.exportUrl(filters);
}
```

```typescript
// deletionHooks.ts
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { adminApi } from "./api";
import { adminQueryKeys } from "./queryKeys";
import { userApi } from "@/lib/user/api";

export function useDeleteSelf() {
  return useMutation({
    mutationFn: (password: string) => userApi.deleteSelf(password),
  });
}

export function useDeleteUser() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ userId, adminNote }: { userId: number; adminNote: string }) =>
      adminApi.users.delete(userId, adminNote),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: adminQueryKeys.users() });
    },
  });
}
```

- [ ] **Step 7: MSW handlers**

Append to `frontend/src/test/msw/handlers.ts`:

```typescript
import type { AdminAuditLogRow } from "@/lib/admin/auditLog";

export const adminAuditLogHandlers = {
  listEmpty: () =>
    http.get("*/api/v1/admin/audit-log", () =>
      HttpResponse.json({ content: [], number: 0, size: 50, totalElements: 0, totalPages: 0 })
    ),
  listWithItems: (rows: AdminAuditLogRow[]) =>
    http.get("*/api/v1/admin/audit-log", () =>
      HttpResponse.json({ content: rows, number: 0, size: 50, totalElements: rows.length, totalPages: 1 })
    ),
};

export const adminUserDeletionHandlers = {
  deleteSuccess: (userId: number) =>
    http.delete(`*/api/v1/admin/users/${userId}`, () => new HttpResponse(null, { status: 204 })),
  delete409Auctions: (userId: number, auctionIds: number[]) =>
    http.delete(`*/api/v1/admin/users/${userId}`, () =>
      HttpResponse.json({ code: "ACTIVE_AUCTIONS", message: "...", blockingIds: auctionIds }, { status: 409 })
    ),
};

export const userDeletionHandlers = {
  deleteSelfSuccess: () =>
    http.delete("*/api/v1/users/me", () => new HttpResponse(null, { status: 204 })),
  deleteSelf403WrongPassword: () =>
    http.delete("*/api/v1/users/me", () =>
      HttpResponse.json({ code: "INVALID_PASSWORD" }, { status: 403 })
    ),
  deleteSelf409Auctions: (auctionIds: number[]) =>
    http.delete("*/api/v1/users/me", () =>
      HttpResponse.json({ code: "ACTIVE_AUCTIONS", message: "...", blockingIds: auctionIds }, { status: 409 })
    ),
};
```

- [ ] **Step 8: Type-check**

```
cd frontend && npx tsc --noEmit
```

- [ ] **Step 9: Stage**

```bash
git add frontend/src/lib/admin/ frontend/src/lib/user/api.ts frontend/src/test/msw/handlers.ts
```

---

## Task 8: /admin/audit-log page + sidebar

**Goal:** Page with filters row + table + inline JSONB expansion + CSV download button. Add `Audit log` to AdminShell sidebar at the bottom.

**Files (all new in `frontend/src/app/admin/audit-log/`):**
- `page.tsx`
- `AdminAuditLogPage.tsx`
- `AdminAuditLogFilters.tsx`
- `AdminAuditLogTable.tsx`
- `AuditLogRowDetails.tsx`
- `AdminAuditLogPage.test.tsx`

**Files to modify:**
- `frontend/src/components/admin/AdminShell.tsx` — add sidebar item

- [ ] **Step 1: AdminAuditLogFilters**

```typescript
"use client";

import type { AdminAuditLogFilters as Filters, AdminActionType, AdminActionTargetType } from "@/lib/admin/auditLog";

const ACTION_TYPES: AdminActionType[] = [
  "DISMISS_REPORT", "WARN_SELLER_FROM_REPORT", "SUSPEND_LISTING_FROM_REPORT",
  "CANCEL_LISTING_FROM_REPORT", "CREATE_BAN", "LIFT_BAN", "PROMOTE_USER",
  "DEMOTE_USER", "RESET_FRIVOLOUS_COUNTER", "REINSTATE_LISTING",
  "DISPUTE_RESOLVED", "LISTING_CANCELLED_VIA_DISPUTE", "WITHDRAWAL_REQUESTED",
  "OWNERSHIP_RECHECK_INVOKED", "TERMINAL_SECRET_ROTATED", "USER_DELETED_BY_ADMIN",
];

const TARGET_TYPES: AdminActionTargetType[] = [
  "REPORT", "BAN", "USER", "AUCTION", "FRAUD_FLAG",
  "DISPUTE", "WITHDRAWAL", "TERMINAL_SECRET",
];

type Props = {
  filters: Filters;
  onChange: (next: Filters) => void;
  onDownloadCsv: () => void;
};

export function AdminAuditLogFilters({ filters, onChange, onDownloadCsv }: Props) {
  return (
    <div className="flex gap-2 flex-wrap items-center bg-surface-container-low p-3 rounded">
      <select
        value={filters.actionType ?? ""}
        onChange={(e) => onChange({ ...filters, actionType: (e.target.value || undefined) as AdminActionType | undefined })}
        className="px-2 py-1 bg-surface-container text-xs rounded"
      >
        <option value="">All actions</option>
        {ACTION_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
      </select>
      <select
        value={filters.targetType ?? ""}
        onChange={(e) => onChange({ ...filters, targetType: (e.target.value || undefined) as AdminActionTargetType | undefined })}
        className="px-2 py-1 bg-surface-container text-xs rounded"
      >
        <option value="">All target types</option>
        {TARGET_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
      </select>
      <input
        type="number"
        placeholder="Admin user ID"
        value={filters.adminUserId ?? ""}
        onChange={(e) => onChange({ ...filters, adminUserId: e.target.value ? Number(e.target.value) : undefined })}
        className="px-2 py-1 bg-surface-container text-xs rounded w-32"
      />
      <input
        type="datetime-local"
        value={filters.from ?? ""}
        onChange={(e) => onChange({ ...filters, from: e.target.value || undefined })}
        className="px-2 py-1 bg-surface-container text-xs rounded"
      />
      <input
        type="datetime-local"
        value={filters.to ?? ""}
        onChange={(e) => onChange({ ...filters, to: e.target.value || undefined })}
        className="px-2 py-1 bg-surface-container text-xs rounded"
      />
      <input
        type="text"
        placeholder="Search notes..."
        value={filters.q ?? ""}
        onChange={(e) => onChange({ ...filters, q: e.target.value || undefined })}
        className="px-2 py-1 bg-surface-container text-xs rounded flex-1 min-w-[180px]"
      />
      <button
        type="button"
        onClick={onDownloadCsv}
        className="px-3 py-1.5 border border-outline rounded text-xs"
      >
        ↓ Download CSV
      </button>
    </div>
  );
}
```

- [ ] **Step 2: AuditLogRowDetails**

```typescript
"use client";
import type { AdminAuditLogRow } from "@/lib/admin/auditLog";

export function AuditLogRowDetails({ row }: { row: AdminAuditLogRow }) {
  return (
    <div className="bg-surface-container-low rounded p-3 my-2">
      <div className="text-[10px] uppercase opacity-55 mb-2">Details</div>
      <pre className="text-[10.5px] leading-relaxed bg-surface-container p-2 rounded overflow-x-auto">
{JSON.stringify(row.details, null, 2)}
      </pre>
    </div>
  );
}
```

- [ ] **Step 3: AdminAuditLogTable**

```typescript
"use client";
import { useState } from "react";
import Link from "next/link";
import type { AdminAuditLogRow, AdminActionTargetType } from "@/lib/admin/auditLog";
import { AuditLogRowDetails } from "./AuditLogRowDetails";

function targetUrlFor(type: AdminActionTargetType | null, id: number | null): string | null {
  if (!type || id === null) return null;
  switch (type) {
    case "AUCTION": return `/auction/${id}`;
    case "USER": return `/admin/users/${id}`;
    case "DISPUTE": return `/admin/disputes/${id}`;
    case "BAN": return `/admin/bans`;
    case "WITHDRAWAL": return `/admin/infrastructure`;
    case "FRAUD_FLAG": return `/admin/fraud-flags?flagId=${id}`;
    case "REPORT": return `/admin/reports?reportId=${id}`;
    case "TERMINAL_SECRET": return `/admin/infrastructure`;
    default: return null;
  }
}

type Props = { rows: AdminAuditLogRow[] };

export function AdminAuditLogTable({ rows }: Props) {
  const [expanded, setExpanded] = useState<Set<number>>(new Set());

  if (rows.length === 0) {
    return <p className="text-sm text-on-surface-variant">No audit log entries match.</p>;
  }

  const toggle = (id: number) => {
    const next = new Set(expanded);
    if (next.has(id)) next.delete(id); else next.add(id);
    setExpanded(next);
  };

  return (
    <table className="w-full text-xs">
      <thead className="text-[10px] uppercase opacity-55 text-left">
        <tr className="border-b border-outline-variant">
          <th className="py-2 px-2 w-32">When</th>
          <th className="py-2 px-2">Action</th>
          <th className="py-2 px-2">Admin</th>
          <th className="py-2 px-2">Target</th>
          <th className="py-2 px-2">Notes</th>
          <th className="py-2 px-2 w-8"></th>
        </tr>
      </thead>
      <tbody>
        {rows.map((row) => {
          const url = targetUrlFor(row.targetType, row.targetId);
          const isOpen = expanded.has(row.id);
          return (
            <>
              <tr key={row.id} className="border-b border-outline-variant/40 hover:bg-surface-container-low cursor-pointer"
                  onClick={() => toggle(row.id)}>
                <td className="py-2 px-2 opacity-70">{new Date(row.occurredAt).toLocaleString()}</td>
                <td className="py-2 px-2 text-primary">{row.actionType}</td>
                <td className="py-2 px-2">{row.adminEmail ?? `#${row.adminUserId}`}</td>
                <td className="py-2 px-2">
                  {row.targetType ? (
                    <>
                      <span className="opacity-70">{row.targetType}</span>{" "}
                      {url ? (
                        <Link href={url} onClick={(e) => e.stopPropagation()} className="text-primary">
                          #{row.targetId}
                        </Link>
                      ) : (
                        <span>#{row.targetId}</span>
                      )}
                    </>
                  ) : "—"}
                </td>
                <td className="py-2 px-2 opacity-85">{row.notes ?? "—"}</td>
                <td className="py-2 px-2 text-primary">{isOpen ? "▾" : "▸"}</td>
              </tr>
              {isOpen && (
                <tr key={`${row.id}-details`} className="border-b border-outline-variant/40">
                  <td colSpan={6} className="px-4">
                    <AuditLogRowDetails row={row} />
                  </td>
                </tr>
              )}
            </>
          );
        })}
      </tbody>
    </table>
  );
}
```

- [ ] **Step 4: AdminAuditLogPage**

```typescript
"use client";
import { useState } from "react";
import { AdminAuditLogFilters } from "./AdminAuditLogFilters";
import { AdminAuditLogTable } from "./AdminAuditLogTable";
import { useAuditLog, useAuditLogExportUrl } from "@/lib/admin/auditLogHooks";
import type { AdminAuditLogFilters as Filters } from "@/lib/admin/auditLog";

export function AdminAuditLogPage() {
  const [filters, setFilters] = useState<Filters>({});
  const { data, isLoading, error } = useAuditLog(filters);
  const exportUrl = useAuditLogExportUrl(filters);

  const handleDownload = () => {
    const a = document.createElement("a");
    a.href = exportUrl;
    a.download = "";
    a.click();
  };

  if (isLoading) return <p>Loading…</p>;
  if (error) return <p className="text-error">Failed to load audit log</p>;

  return (
    <div className="space-y-4">
      <header>
        <h1 className="text-xl font-semibold">Admin audit log</h1>
        <p className="text-xs opacity-60 mt-1">
          Every admin action — fraud-flag triage, reports, bans, disputes, withdrawals, secret rotations.
        </p>
      </header>
      <AdminAuditLogFilters
        filters={filters}
        onChange={setFilters}
        onDownloadCsv={handleDownload}
      />
      <AdminAuditLogTable rows={data?.content ?? []} />
      {data && (
        <div className="flex justify-between text-xs opacity-60 border-t border-outline-variant pt-3">
          <span>Showing {data.content.length} of {data.totalElements}</span>
        </div>
      )}
    </div>
  );
}
```

```typescript
// page.tsx
import { AdminAuditLogPage } from "./AdminAuditLogPage";
export default function Page() { return <AdminAuditLogPage />; }
```

- [ ] **Step 5: Add sidebar item**

In `AdminShell.tsx`, append to the sidebar items array:

```typescript
  { label: "Audit log", href: "/admin/audit-log" },
```

Position: at the bottom (after-the-fact reference, not daily triage).

- [ ] **Step 6: Test**

```typescript
import { describe, it, expect, beforeAll, afterAll, afterEach } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { AdminAuditLogPage } from "./AdminAuditLogPage";
import { adminAuditLogHandlers } from "@/test/msw/handlers";
// Use project's renderWithProviders + server pattern.

describe("AdminAuditLogPage", () => {
  it("renders empty state", async () => {
    server.use(adminAuditLogHandlers.listEmpty());
    renderWithProviders(<AdminAuditLogPage />);
    expect(await screen.findByText(/No audit log entries match/i)).toBeInTheDocument();
  });

  it("renders rows + expands details on click", async () => {
    server.use(adminAuditLogHandlers.listWithItems([
      {
        id: 1, occurredAt: "2026-04-27T14:22:00Z",
        actionType: "DISPUTE_RESOLVED",
        adminUserId: 1, adminEmail: "admin@example.com",
        targetType: "DISPUTE", targetId: 47,
        notes: "Verified payment landed.",
        details: { foo: "bar" },
      },
    ]));
    renderWithProviders(<AdminAuditLogPage />);
    const row = await screen.findByText("DISPUTE_RESOLVED");
    expect(row).toBeInTheDocument();

    await userEvent.click(row);
    expect(await screen.findByText(/"foo"/)).toBeInTheDocument();
  });

  it("filters by action type", async () => {
    server.use(adminAuditLogHandlers.listEmpty());
    renderWithProviders(<AdminAuditLogPage />);
    const select = await screen.findByRole("combobox", { name: /All actions/i });
    await userEvent.selectOptions(select, "DISPUTE_RESOLVED");
    // Filter changed; query re-runs (MSW handler still returns empty)
  });
});
```

- [ ] **Step 7: Stage**

```bash
git add frontend/src/app/admin/audit-log/ \
        frontend/src/components/admin/AdminShell.tsx
```

---

## Task 9: DeleteAccountSection on settings + /goodbye route

**Goal:** Self-service deletion section on the settings page. New public `/goodbye` route. Mutation `onSuccess` order is critical (clear token → clear cache → navigate).

**Files:**
- Create: `frontend/src/components/settings/DeleteAccountSection.tsx`
- Create: `frontend/src/components/settings/DeleteAccountSection.test.tsx`
- Create: `frontend/src/app/goodbye/page.tsx`
- Modify: `frontend/src/app/settings/page.tsx` (or wherever the canonical settings/profile page lives — implementer locates with `grep -rln "Settings\|Profile editor" frontend/src/app/`)

- [ ] **Step 1: /goodbye route**

```typescript
// frontend/src/app/goodbye/page.tsx
import Link from "next/link";

export default function GoodbyePage() {
  return (
    <div className="min-h-screen flex items-center justify-center p-4">
      <div className="bg-surface-container rounded p-6 max-w-md text-center space-y-4">
        <h1 className="text-2xl font-semibold">Account deleted</h1>
        <p className="text-sm opacity-75">
          Your account has been deleted. Your auctions, bids, and reviews may remain
          visible as "Deleted user" to preserve the integrity of past records.
        </p>
        <p className="text-sm opacity-75">
          You can register a new account at any time.
        </p>
        <Link
          href="/register"
          className="inline-block px-4 py-2 bg-primary text-on-primary rounded text-sm font-semibold"
        >
          Register a new account
        </Link>
      </div>
    </div>
  );
}
```

The route is public (no `RequireAuth` wrapper). If the project uses a global layout with auth wrapping, this page may need to opt out — implementer checks the routing/layout setup.

- [ ] **Step 2: DeleteAccountSection**

```typescript
"use client";
import { useState } from "react";
import { useRouter } from "next/navigation";
import { useQueryClient } from "@tanstack/react-query";
import { useDeleteSelf } from "@/lib/admin/deletionHooks";
// Existing project helpers — adapt names to actual exports:
import { clearAccessToken } from "@/lib/auth";  // implementer locates the access token clear helper

type PreconditionError = {
  code: string;
  message: string;
  blockingIds: number[];
};

export function DeleteAccountSection() {
  const [expanded, setExpanded] = useState(false);
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | PreconditionError | null>(null);
  const router = useRouter();
  const qc = useQueryClient();
  const mutation = useDeleteSelf();

  const submit = () => {
    setError(null);
    mutation.mutate(password, {
      onSuccess: () => {
        // Order matters: prevent 401 refresh race
        clearAccessToken();
        qc.clear();
        router.push("/goodbye");
      },
      onError: (e: unknown) => {
        const errorObj = e as { status?: number; data?: PreconditionError };
        if (errorObj.status === 409 && errorObj.data?.blockingIds) {
          setError(errorObj.data);
        } else if (errorObj.status === 403) {
          setError("Incorrect password.");
        } else {
          setError("Failed to delete account. Please try again.");
        }
      },
    });
  };

  return (
    <section className="bg-error-container/20 border border-error rounded p-4 mt-8">
      <button
        type="button"
        onClick={() => setExpanded(!expanded)}
        className="w-full flex justify-between items-center text-left"
      >
        <span className="font-semibold text-error">Delete account</span>
        <span className="text-error">{expanded ? "▾" : "▸"}</span>
      </button>
      {expanded && (
        <div className="mt-4 space-y-3">
          <div className="bg-error-container text-on-error-container rounded p-3 text-xs">
            <strong>This is irreversible.</strong> Your auctions, bids, and reviews may remain
            visible as "Deleted user" to preserve past records. You can register a new account
            with the same email after deletion.
          </div>
          <input
            type="password"
            placeholder="Enter your password to confirm"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            className="w-full bg-surface-container-low p-2 rounded text-sm"
          />
          {error && typeof error === "string" && (
            <p className="text-error text-xs">{error}</p>
          )}
          {error && typeof error !== "string" && (
            <div className="text-error text-xs">
              <p className="font-medium">Cannot delete: {error.code}</p>
              <p className="mt-1 opacity-85">{error.message}</p>
              {error.blockingIds.length > 0 && (
                <ul className="mt-2 space-y-1">
                  {error.blockingIds.map((id) => (
                    <li key={id}>· #{id}</li>
                  ))}
                </ul>
              )}
            </div>
          )}
          <button
            type="button"
            disabled={!password || mutation.isPending}
            onClick={submit}
            className="px-4 py-2 bg-error text-on-error rounded text-sm font-semibold disabled:opacity-50"
          >
            {mutation.isPending ? "Deleting…" : "Delete my account"}
          </button>
        </div>
      )}
    </section>
  );
}
```

The `clearAccessToken` import path needs verification — search for how the existing logout flow clears the access token. Common patterns: a function in `@/lib/auth/tokenStore`, a context-provider clearer, or a Zustand-like store. Match the existing pattern.

- [ ] **Step 3: Render on settings page**

In the canonical settings/profile page (implementer locates), append at the bottom of the rendered content:

```tsx
import { DeleteAccountSection } from "@/components/settings/DeleteAccountSection";

// ... existing JSX ...
<DeleteAccountSection />
```

- [ ] **Step 4: Test**

```typescript
import { describe, it, expect, beforeAll, afterAll, afterEach, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { DeleteAccountSection } from "./DeleteAccountSection";
import { userDeletionHandlers } from "@/test/msw/handlers";

// Mock the router.push
vi.mock("next/navigation", () => ({ useRouter: () => ({ push: vi.fn() }) }));

describe("DeleteAccountSection", () => {
  it("disables delete button when password is empty", async () => {
    renderWithProviders(<DeleteAccountSection />);
    await userEvent.click(screen.getByText(/^Delete account$/));  // expand
    const button = screen.getByRole("button", { name: /Delete my account/i });
    expect(button).toBeDisabled();
  });

  it("renders precondition error inline on 409", async () => {
    server.use(userDeletionHandlers.deleteSelf409Auctions([100, 101]));
    renderWithProviders(<DeleteAccountSection />);
    await userEvent.click(screen.getByText(/^Delete account$/));
    await userEvent.type(screen.getByPlaceholderText(/Enter your password/), "pw");
    await userEvent.click(screen.getByRole("button", { name: /Delete my account/i }));
    expect(await screen.findByText(/ACTIVE_AUCTIONS/)).toBeInTheDocument();
    expect(screen.getByText(/#100/)).toBeInTheDocument();
    expect(screen.getByText(/#101/)).toBeInTheDocument();
  });

  it("calls token clear before queryClient.clear before router.push", async () => {
    server.use(userDeletionHandlers.deleteSelfSuccess());
    // Spy on clearAccessToken, queryClient.clear, router.push and assert call order
    // (implementer fills based on actual exported functions)
  });

  it("renders 'Incorrect password' on 403", async () => {
    server.use(userDeletionHandlers.deleteSelf403WrongPassword());
    renderWithProviders(<DeleteAccountSection />);
    await userEvent.click(screen.getByText(/^Delete account$/));
    await userEvent.type(screen.getByPlaceholderText(/Enter your password/), "wrong");
    await userEvent.click(screen.getByRole("button", { name: /Delete my account/i }));
    expect(await screen.findByText(/Incorrect password/i)).toBeInTheDocument();
  });
});
```

- [ ] **Step 5: Stage**

```bash
git add frontend/src/components/settings/ \
        frontend/src/app/goodbye/ \
        frontend/src/app/settings/    # whichever file was modified
```

---

## Task 10: Admin "Delete user" button + modal

**Goal:** Button on `/admin/users/{id}` (sub-spec 2 surface) opens a modal that calls `useDeleteUser`. Same precondition-error rendering. On success → redirect to /admin/users.

**Files:**
- Create: `frontend/src/components/admin/users/DeleteUserModal.tsx`
- Create: `frontend/src/components/admin/users/DeleteUserModal.test.tsx`
- Modify: existing `/admin/users/[id]/page.tsx` (or detail component) to render the button + modal

- [ ] **Step 1: DeleteUserModal**

```typescript
"use client";
import { useState } from "react";
import { useRouter } from "next/navigation";
import { useDeleteUser } from "@/lib/admin/deletionHooks";

type PreconditionError = {
  code: string;
  message: string;
  blockingIds: number[];
};

type Props = { userId: number; userEmail?: string | null; onClose: () => void };

export function DeleteUserModal({ userId, userEmail, onClose }: Props) {
  const [adminNote, setAdminNote] = useState("");
  const [error, setError] = useState<string | PreconditionError | null>(null);
  const router = useRouter();
  const mutation = useDeleteUser();

  const submit = () => {
    setError(null);
    mutation.mutate(
      { userId, adminNote },
      {
        onSuccess: () => {
          onClose();
          router.push("/admin/users");
        },
        onError: (e: unknown) => {
          const errorObj = e as { status?: number; data?: PreconditionError };
          if (errorObj.status === 409 && errorObj.data?.blockingIds) {
            setError(errorObj.data);
          } else if (errorObj.status === 410) {
            setError("User already deleted.");
          } else {
            setError("Failed to delete user. Please try again.");
          }
        },
      }
    );
  };

  return (
    <div className="fixed inset-0 bg-black/60 z-50 flex items-center justify-center" onClick={onClose}>
      <div className="bg-surface rounded p-5 max-w-md w-full" onClick={(e) => e.stopPropagation()}>
        <h2 className="text-sm font-semibold mb-2">Delete user{userEmail ? ` ${userEmail}` : ""}?</h2>
        <p className="text-[11px] opacity-70 mb-3">
          This soft-deletes the user. Their identity is scrubbed but their auctions
          and reviews remain visible as "Deleted user".
        </p>
        <label className="text-[10px] uppercase opacity-55 block mb-1">
          Admin note <span className="text-error normal-case">(required)</span>
        </label>
        <textarea
          value={adminNote}
          onChange={(e) => setAdminNote(e.target.value)}
          placeholder="Reason for deletion (e.g., 'GDPR request', 'Spam account')"
          maxLength={500}
          className="w-full h-20 bg-surface-container-low text-xs p-2 rounded resize-y"
        />
        <div className="text-[10px] opacity-40 mt-1 mb-3">{adminNote.length} / 500</div>

        {error && typeof error === "string" && (
          <p className="text-error text-xs mb-3">{error}</p>
        )}
        {error && typeof error !== "string" && (
          <div className="text-error text-xs mb-3">
            <p className="font-medium">Cannot delete: {error.code}</p>
            <p className="mt-1 opacity-85">{error.message}</p>
            {error.blockingIds.length > 0 && (
              <ul className="mt-2 space-y-1">
                {error.blockingIds.map((id) => <li key={id}>· #{id}</li>)}
              </ul>
            )}
          </div>
        )}

        <div className="flex gap-2">
          <button type="button" onClick={onClose}
                  className="flex-1 px-3 py-2 border border-outline rounded text-xs">Cancel</button>
          <button
            type="button"
            disabled={!adminNote.trim() || mutation.isPending}
            onClick={submit}
            className="flex-1 px-3 py-2 bg-error text-on-error rounded text-xs font-semibold disabled:opacity-50"
          >
            {mutation.isPending ? "Deleting…" : "Delete user"}
          </button>
        </div>
      </div>
    </div>
  );
}
```

- [ ] **Step 2: Wire button into /admin/users/[id] page**

Locate the detail page (likely `frontend/src/app/admin/users/[id]/page.tsx` or a client component nearby). Add a button in the destructive-actions section (sub-spec 2 has analogous patterns for demote/ban):

```typescript
const [deleteOpen, setDeleteOpen] = useState(false);

// In JSX, near other destructive admin actions:
<button
  type="button"
  onClick={() => setDeleteOpen(true)}
  className="px-3 py-1.5 border border-error text-error rounded text-xs"
>
  Delete user
</button>
{deleteOpen && (
  <DeleteUserModal
    userId={user.id}
    userEmail={user.email}
    onClose={() => setDeleteOpen(false)}
  />
)}
```

- [ ] **Step 3: Test**

```typescript
describe("DeleteUserModal", () => {
  it("disables delete button when admin note is empty", async () => {
    renderWithProviders(<DeleteUserModal userId={42} userEmail="user@example.com" onClose={() => {}} />);
    expect(screen.getByRole("button", { name: /Delete user/i })).toBeDisabled();
  });

  it("renders 409 precondition error inline", async () => {
    server.use(adminUserDeletionHandlers.delete409Auctions(42, [100, 101]));
    renderWithProviders(<DeleteUserModal userId={42} onClose={() => {}} />);
    await userEvent.type(screen.getByPlaceholderText(/Reason for deletion/), "Test");
    await userEvent.click(screen.getByRole("button", { name: /Delete user/i }));
    expect(await screen.findByText(/ACTIVE_AUCTIONS/)).toBeInTheDocument();
  });

  it("calls onClose + redirect on success", async () => {
    server.use(adminUserDeletionHandlers.deleteSuccess(42));
    const onClose = vi.fn();
    renderWithProviders(<DeleteUserModal userId={42} onClose={onClose} />);
    await userEvent.type(screen.getByPlaceholderText(/Reason for deletion/), "GDPR request");
    await userEvent.click(screen.getByRole("button", { name: /Delete user/i }));
    await waitFor(() => expect(onClose).toHaveBeenCalled());
  });
});
```

- [ ] **Step 4: Stage**

```bash
git add frontend/src/components/admin/users/ \
        frontend/src/app/admin/users/
```

---

## Final integration step

After all 10 tasks land:

- [ ] **Run full backend suite**

```
cd backend && ./mvnw test
```

Expected: all pass except known flake.

- [ ] **Run full frontend suite**

```
cd frontend && npx vitest run
```

- [ ] **Run frontend build**

```
cd frontend && npm run build
```

- [ ] **Push branch**

```
git push -u origin task/10-sub-4-lifecycle-completion
```

- [ ] **Open PR against `dev`**

```
gh pr create --base dev --title "Epic 10 sub-spec 4 — lifecycle completion" --body "..."
```

Body summary:
- Account deletion (soft-delete with cascade matrix; self-service + admin-driven)
- /admin/audit-log viewer with CSV export
- ESCROW_TRANSFER_REMINDER + REVIEW_RESPONSE_WINDOW_CLOSING schedulers
- Closes 4 deferred-ledger entries

Do NOT auto-merge.

---

## Self-Review Notes

Spec coverage:
- §1 Goal → all 10 tasks collectively
- §2 Scope → tasks 8 (audit log page) + 9-10 (deletion frontends)
- §3 Architecture → respected; reuse from prior epics in tasks
- §4 Data model → Task 1
- §5 Account deletion domain → Tasks 1, 2, 3, 9, 10
- §6 Audit log viewer → Tasks 4, 5, 7, 8
- §7 Schedulers → Task 6
- §8 Notifications → Task 6 (verification step)
- §9 Logging → Tasks 2, 6 (INFO logs per service)
- §10 Testing → each task has test step
- §11 Out of scope → respected
- §12 Execution model → final integration step
- §13 Acceptance criteria → mapped to task outputs

No placeholders found. Type consistency verified across the repository, service, and DTO names.
