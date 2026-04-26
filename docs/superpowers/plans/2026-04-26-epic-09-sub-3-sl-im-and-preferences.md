# Epic 09 sub-spec 3 — SL IM Channel & Notification Preferences UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the SL IM channel end-to-end (queue + dispatcher endpoints + LSL polling object) plus a preferences UI at `/settings/notifications`, with a per-recipient gate that consults user preferences fresh on every dispatch and a 48 h expiry job whose INFO log is the operational canary for a dark dispatcher.

**Architecture:** A new `sl_im_message` queue table is populated by an afterCommit hook in `NotificationService.publish()` (single-recipient path) and by a sibling call inside the existing per-recipient `REQUIRES_NEW` lambda in `NotificationPublisherImpl.listingCancelledBySellerFanout` (fan-out path). A pure-function `SlImChannelGate` decides per (User, Category) whether to queue, with a discriminated `Decision` enum logged at DEBUG. Internal endpoints under `/api/v1/internal/sl-im/*` (shared-secret auth) feed an LSL dispatcher object that polls every 60 s, batches up to 10 IMs with 2 s spacing, and confirms each via `POST /{id}/delivered`. Preferences live under a new `/settings` route tree backed by `GET`/`PUT /api/v1/users/me/notification-preferences` with closed-shape validation.

**Tech Stack:** Spring Boot 4 / Java 26, Hibernate `ddl-auto: update`, Postgres `ON CONFLICT` upsert with `xmax` insert/update detection, partial unique index emitted by an initializer, Spring Scheduling with zoned cron, Spring Security with a custom `OncePerRequestFilter` for terminal auth, Next.js 16 / React 19, TanStack Query v5 (optimistic mutation + rollback), Tailwind CSS 4, Headless UI, Vitest + MSW 2, TestContainers Postgres, LSL.

---

## Pre-implementation findings

Hard-won corrections from sub-spec 1 to apply throughout. Verify each by reading the noted neighbor file before deviating.

1. **Principal type is `AuthPrincipal`, not `AuthUser`.** Controllers use `@AuthenticationPrincipal AuthPrincipal caller`. Verify against `NotificationController.java` (sub-spec 1, Task 2 commit `bda6450`).

2. **`@MockitoBean`, not `@MockBean`.** Spring Boot 3.4+. Verify against `NotificationPublisherImplTest.java` (sub-spec 1, Task 3 commit `2d61d4b`).

3. **Real scheduler-disable property names** that every new `@SpringBootTest` + `@TestPropertySource` test must include (sweep existing tests; these are the canonical set used in `NotificationServiceTest`, `NotificationControllerIntegrationTest`, `NotificationPublisherImplTest`):
   ```
   "slpa.notifications.cleanup.enabled=false",
   "slpa.notifications.sl-im.cleanup.enabled=false",   ← NEW in this sub-spec, Task 5 sweep
   "slpa.escrow.scheduler.enabled=false",
   "slpa.escrow.timeout-job.enabled=false",
   "slpa.escrow.command-dispatcher-job.enabled=false",
   "slpa.escrow.ownership-monitor-job.enabled=false",
   "slpa.review.scheduler.enabled=false",
   "slpa.refresh-token-cleanup.enabled=false",
   "auth.cleanup.enabled=false",
   "slpa.auction-end.enabled=false",
   "slpa.ownership-monitor.enabled=false"
   ```

4. **JWT issuance in tests** uses `JwtService.issueAccessToken(AuthPrincipal)` directly with `new AuthPrincipal(user.getId(), user.getEmail(), user.getTokenVersion())`. No HTTP register-then-extract pattern needed for tests that aren't testing the auth flow itself.

5. **`testUser(...)` helper does not exist as a shared fixture.** Construct users inline with `User.builder().email(uniqueEmail).passwordHash("hash").build()` and `userRepo.save(...)`. Use UUID-suffixed emails to avoid uniqueness collisions across tests. See `NotificationPublisherImplTest` for the exact pattern.

6. **`@RequiredArgsConstructor` does not propagate `@Qualifier` from fields to the generated constructor.** When you need `@Qualifier("requiresNewTxTemplate")` on a constructor parameter, write the constructor explicitly. See `NotificationPublisherImpl` constructor (Task 3 commit `2d61d4b`).

7. **`requiresNewTxTemplate` bean already exists** in `NotificationConfig.java` (sub-spec 1, Task 3). Reuse it via `@Qualifier("requiresNewTxTemplate") TransactionTemplate`. Do not declare a second bean.

8. **JSONB integer/long deserialization gotcha.** Postgres JSONB → Java integers come back as `Integer` (boxed), not `Long`, when the value fits in 32 bits. When asserting on numeric values from the `data` map use `((Number) data.get(key)).longValue()`.

9. **`ddl-auto: update` cannot emit partial unique indexes.** Use the initializer pattern (see `NotificationCoalesceIndexInitializer.java` from sub-spec 1, Task 1). Match its style for `SlImCoalesceIndexInitializer`.

10. **Pre-commit hook reads conversation transcripts** counting native TaskCreate vs TaskUpdate(completed) calls. If you create per-task TodoWrite entries, mark them all completed before running `git commit` — or the commit gets rejected. The actual progress tracking happens via git commits per task; the in-session task list is dispatch-coordination only.

11. **`docs/superpowers/`** is in the global `~/.gitignore` (`**/superpowers/`). Existing files there are tracked from prior sub-specs; new files there require `git add -f`. The plan and spec for this sub-spec are already committed via `git add -f`. Future docs touches in `docs/implementation/` and `docs/initial-design/` are unaffected.

12. **No `import React`** in any frontend file. Use named imports only (`import { useState, useEffect } from "react"`). For type-only imports use `import type { ReactNode } from "react"`.

13. **Do not run `npm run dev`.** The user keeps a dev server running in another terminal. Frontend verification is via `npm test -- --run` and `npm run lint`.

14. **The visual companion server's session directory** for this sub-spec is `.superpowers/brainstorm/24679-1777214512/` — gitignored at the project level. Do not touch.

## Reference patterns from sub-spec 1

Patterns to copy or extend, with specific neighbor files to read first.

| Pattern | Neighbor reference | Used in this sub-spec by |
| --- | --- | --- |
| Native ON CONFLICT upsert with `xmax != 0` insert/update detection | `NotificationDao.java` (sub-spec 1, Task 1) | Task 1 — `SlImMessageDao` |
| `@EventListener(ApplicationReadyEvent)` initializer for partial unique index | `NotificationCoalesceIndexInitializer.java` (Task 1) | Task 1 — `SlImCoalesceIndexInitializer` |
| Split filtered/unfiltered repository queries (avoiding HQL-on-Collection-IS-NULL) | `NotificationRepository.java` (Task 1) | Not needed in this sub-spec — repository has only one query and one count |
| `@ConfigurationProperties` record with JSR-303 validation | `CancellationPenaltyProperties.java` | Task 5 — `SlImCleanupProperties`; Task 4 — `SlImInternalProperties` |
| `@Scheduled` job + `@ConditionalOnProperty` for test-disable | `NotificationCleanupJob.java` (Task 2) | Task 5 — `SlImCleanupJob` (zoned cron via `zone = "America/Los_Angeles"`) |
| `requiresNewTxTemplate` bean (PROPAGATION_REQUIRES_NEW) | `NotificationConfig.java` (Task 3) | Task 3 — reused by `SlImChannelDispatcher` |
| afterCommit registration on `TransactionSynchronizationManager` | `NotificationService.java` (Task 1) | Task 3 — extends `publish()` to register a second hook |
| Per-recipient REQUIRES_NEW lambda with try-catch isolation | `NotificationPublisherImpl.listingCancelledBySellerFanout` (Task 3) | Task 3 — sibling call added inside the lambda |
| `OncePerRequestFilter` registered before JWT chain | `JwtAuthenticationFilter` + `WebSecurityConfig` | Task 4 — `SlImTerminalAuthFilter` |
| `LogCaptor` for asserting on log lines | Existing tests (search for `LogCaptor.forClass`) | Task 5 — `SlImCleanupJobTest` |
| TanStack Query v5 cache key factory | `frontend/src/lib/notifications/queryKeys.ts` (Task 6) | Task 7 — extend with `preferences()` |
| Optimistic mutation with rollback | `frontend/src/hooks/notifications/useMarkRead.ts` (Task 6) | Task 7 — `useUpdateNotificationPreferences` |
| MSW 2 handler conventions + seed/clear test helpers | `frontend/src/test/msw/handlers.ts` (Task 6) | Task 7 — extend with prefs handlers |
| Headless UI Popover for dropdown | `frontend/src/components/notifications/NotificationDropdown.tsx` (Task 7) | Task 8 — bell dropdown footer gear icon (no new Popover, just add to existing) |
| `RequireAuth` wrapper for authenticated pages | `frontend/src/app/notifications/page.tsx` (Task 8) | Task 8 — `/settings/notifications/page.tsx` |
| `formatRelativeTime` from `@/lib/time/relativeTime` | Sub-spec 1, Task 7 | Not needed in this sub-spec — preferences UI shows no times |

## Footguns to avoid

1. **`llInstantMessage` truncates at 1024 BYTES, not characters.** UTF-8 multi-byte characters (CJK, emoji, accented Latin) push the byte count above the char count. SL silently truncates from the end — and the deeplink lives at the end. `SlImMessageBuilder` MUST measure `getBytes(StandardCharsets.UTF_8).length`. Three test cases mandatory: multi-byte parcel name, emoji parcel name, long-body forcing actual truncation. All three assert deeplink intact and total ≤ 1024 bytes.

2. **Single-recipient publish path is afterCommit-then-REQUIRES_NEW; fan-out path is in-the-REQUIRES_NEW.** See spec §2.1 and §2.2. The two have different reliability postures and cannot be unified. A new fan-out method must follow the fan-out pattern (sibling call inside the per-recipient REQUIRES_NEW lambda); a new single-recipient method must follow the publish() pattern (afterCommit hook → maybeQueue → its own REQUIRES_NEW).

3. **Adding a new terminal status to `sl_im_message` requires updating the cleanup predicate.** `SlImCleanupJob` stage 2's `WHERE status IN (...)` enumerates terminal states. New status = predicate update in the same commit, or rows accumulate forever and the GROUP BY status triage query loses its rolling-window meaning.

4. **LSL `llInstantMessage` is fire-and-forget; the dispatcher script never calls `/failed`.** FAILED rows in production come from manual operator intervention only. README's "Limits" section makes this explicit so a support engineer running `SELECT status, count(*) FROM sl_im_message GROUP BY status` doesn't think a missing FAILED bucket means a missing pipeline.

5. **The User entity load in the dispatcher must be inside the REQUIRES_NEW transaction**, not at the originating call site. This guarantees read-your-writes after a preferences PUT — no stale-cache window.

6. **`top_users` for the cleanup INFO log must be captured BEFORE the UPDATE to EXPIRED.** Otherwise the `WHERE status = 'PENDING'` filter no longer matches the rows that just transitioned. Two queries instead of one is the correct shape.

7. **`SlImInternalController.pending()` is NOT @Transactional.** The `FOR UPDATE SKIP LOCKED` on the polling query releases the lock the moment the query returns. This is the correct shape for the current single-dispatcher design. Add a code comment on the line so future contributors don't mistake it for working multi-poller dedup.

8. **Closed-shape validation on PUT preferences.** The `slIm` map keys must be exactly `{ bidding, auction_result, escrow, listing_status, reviews }` — no more, no fewer. `SYSTEM`, `REALTY_GROUP`, and `MARKETING` keys are rejected with 400.

9. **Master mute does NOT clear per-group state.** When `slImMuted=true`, group toggles render disabled with their underlying `notifySlIm[group]` value still showing. Toggling mute off restores them visually with no API roundtrip.

10. **Fan-out path's `maybeQueueForFanout` does NOT wrap in REQUIRES_NEW** (caller already did) and does NOT swallow exceptions (caller's per-recipient try-catch handles isolation). Only `maybeQueue` (single-recipient) does both.

## Branch setup

The branch already exists: `task/09-sub-3-sl-im-and-preferences`, branched from `dev`, with the spec committed at `952956d` and the FOR UPDATE SKIP LOCKED clarification at `e6642f2`. Confirm with `git status` and `git log --oneline -3` before starting Task 1.

```bash
git status                    # expect: clean working tree
git log --oneline -3          # expect tip: e6642f2 docs(epic-09): sub-spec 3 — clarify FOR UPDATE SKIP LOCKED is advisory
git rev-parse --abbrev-ref HEAD  # expect: task/09-sub-3-sl-im-and-preferences
```

If any expectation fails, `git checkout task/09-sub-3-sl-im-and-preferences` and pull from origin if it's been pushed.

---

## Task 1 — Backend foundation: entity + DAO + repository + index initializer

**Goal:** Land the persistence layer for `sl_im_message` — entity, status enum, native ON CONFLICT upsert DAO, repository with the polling query, and the partial-unique-index initializer. No service or dispatcher logic yet (Task 3). No internal endpoints yet (Task 4). Mirror the `NotificationDao` / `NotificationRepository` / `NotificationCoalesceIndexInitializer` patterns from sub-spec 1.

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/notification/slim/SlImMessage.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/notification/slim/SlImMessageStatus.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/notification/slim/SlImMessageDao.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/notification/slim/SlImMessageRepository.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/notification/slim/SlImCoalesceIndexInitializer.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/notification/slim/SlImMessageDaoTest.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/notification/slim/SlImMessageRepositoryTest.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/notification/slim/SlImCoalesceIndexInitializerTest.java`

### Steps

- [ ] **Step 1: Create the `SlImMessageStatus` enum**

```java
package com.slparcelauctions.backend.notification.slim;

public enum SlImMessageStatus {
    PENDING,
    DELIVERED,
    EXPIRED,
    FAILED
}
```

- [ ] **Step 2: Create the `SlImMessage` JPA entity**

```java
package com.slparcelauctions.backend.notification.slim;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(
    name = "sl_im_message",
    indexes = {
        @Index(name = "ix_sl_im_status_created", columnList = "status, created_at"),
        @Index(name = "ix_sl_im_user_status", columnList = "user_id, status")
    }
)
@Getter
@Setter
@NoArgsConstructor
public class SlImMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "avatar_uuid", nullable = false, length = 36)
    private String avatarUuid;

    @Column(name = "coalesce_key", length = 128)
    private String coalesceKey;

    @Column(name = "message_text", nullable = false, length = 1024)
    private String messageText;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SlImMessageStatus status;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "delivered_at")
    private OffsetDateTime deliveredAt;

    @Column(nullable = false)
    private int attempts;
}
```

- [ ] **Step 3: Run the application context once to let `ddl-auto: update` create the table**

Run: `cd backend && ./mvnw spring-boot:run` for ~10 seconds, then Ctrl+C. Verify the table was created:

```bash
psql -h localhost -U slpa -d slpa -c "\d sl_im_message"
```

Expected output: table exists with columns matching the entity. Do not commit yet.

- [ ] **Step 4: Create `SlImMessageDao` with native ON CONFLICT upsert**

```java
package com.slparcelauctions.backend.notification.slim;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Native-SQL DAO for the SL IM message upsert path.
 *
 * <p>Uses {@code ON CONFLICT (user_id, coalesce_key) WHERE status = 'PENDING' DO UPDATE}
 * to collapse repeated deliveries of the same logical event (e.g., repeated OUTBID
 * notifications on the same auction) into a single PENDING row that the dispatcher
 * delivers as one IM with the latest text.
 *
 * <p>The {@code xmax != 0} trick is a Postgres-native way to know whether INSERT
 * (xmax = 0) or UPDATE (xmax holds updating tx ID) executed without a second roundtrip.
 *
 * <p>The partial unique index {@code uq_sl_im_pending_coalesce} is created at
 * startup by {@link SlImCoalesceIndexInitializer} (Hibernate's ddl-auto cannot
 * emit partial indexes). Without that index the ON CONFLICT clause has nothing
 * to match and every call becomes a plain INSERT.
 *
 * <p>NULL coalesce_key never matches itself in Postgres unique constraints
 * (NULL ≠ NULL semantics), so the same query handles coalescing and
 * non-coalescing categories with no service-layer branching.
 */
@Component
@RequiredArgsConstructor
public class SlImMessageDao {

    private final JdbcTemplate jdbc;

    public record UpsertResult(
        long id,
        boolean wasUpdate,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
    ) {}

    public UpsertResult upsert(
        long userId,
        String avatarUuid,
        String messageText,
        String coalesceKey
    ) {
        String sql = """
            INSERT INTO sl_im_message
              (user_id, avatar_uuid, coalesce_key, message_text,
               status, attempts, created_at, updated_at)
            VALUES (?, ?, ?, ?, 'PENDING', 0, now(), now())
            ON CONFLICT (user_id, coalesce_key) WHERE status = 'PENDING'
            DO UPDATE SET
              message_text = EXCLUDED.message_text,
              avatar_uuid  = EXCLUDED.avatar_uuid,
              updated_at   = now()
            RETURNING id, (xmax != 0) AS was_update, created_at, updated_at
            """;
        Map<String, Object> row = jdbc.queryForMap(
            sql, userId, avatarUuid, coalesceKey, messageText);
        return new UpsertResult(
            ((Number) row.get("id")).longValue(),
            (Boolean) row.get("was_update"),
            ((java.sql.Timestamp) row.get("created_at")).toInstant().atOffset(ZoneOffset.UTC),
            ((java.sql.Timestamp) row.get("updated_at")).toInstant().atOffset(ZoneOffset.UTC)
        );
    }
}
```

- [ ] **Step 5: Create `SlImMessageRepository`**

```java
package com.slparcelauctions.backend.notification.slim;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SlImMessageRepository extends JpaRepository<SlImMessage, Long> {

    @Query(value = """
        SELECT * FROM sl_im_message
        WHERE status = 'PENDING'
        ORDER BY created_at ASC
        LIMIT :limit
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<SlImMessage> pollPending(@Param("limit") int limit);

    @Query("SELECT m.status, count(m) FROM SlImMessage m GROUP BY m.status")
    List<Object[]> countByStatus();
}
```

- [ ] **Step 6: Create `SlImCoalesceIndexInitializer`**

```java
package com.slparcelauctions.backend.notification.slim;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Emits the partial unique index that backs the ON CONFLICT clause in
 * {@link SlImMessageDao}. Hibernate's ddl-auto cannot emit partial unique
 * indexes, so we add it via an {@code ApplicationReadyEvent} listener that
 * runs idempotent DDL.
 *
 * <p>Without this index the ON CONFLICT clause has nothing to match and every
 * upsert call becomes a plain INSERT — coalescing silently breaks.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SlImCoalesceIndexInitializer {

    private final JdbcTemplate jdbc;

    @EventListener(ApplicationReadyEvent.class)
    public void emit() {
        jdbc.execute("""
            CREATE UNIQUE INDEX IF NOT EXISTS uq_sl_im_pending_coalesce
            ON sl_im_message (user_id, coalesce_key)
            WHERE status = 'PENDING'
            """);
        log.info("SL IM partial unique index ensured: uq_sl_im_pending_coalesce");
    }
}
```

- [ ] **Step 7: Write `SlImMessageDaoTest`**

```java
package com.slparcelauctions.backend.notification.slim;

import static org.assertj.core.api.Assertions.assertThat;

import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
    "slpa.notifications.cleanup.enabled=false",
    "slpa.notifications.sl-im.cleanup.enabled=false",
    "slpa.escrow.scheduler.enabled=false",
    "slpa.escrow.timeout-job.enabled=false",
    "slpa.escrow.command-dispatcher-job.enabled=false",
    "slpa.escrow.ownership-monitor-job.enabled=false",
    "slpa.review.scheduler.enabled=false",
    "slpa.refresh-token-cleanup.enabled=false",
    "auth.cleanup.enabled=false",
    "slpa.auction-end.enabled=false",
    "slpa.ownership-monitor.enabled=false"
})
class SlImMessageDaoTest {

    @Autowired SlImMessageDao dao;
    @Autowired SlImMessageRepository repo;
    @Autowired UserRepository userRepo;

    @Test
    void upsert_freshKey_insertsRow_returnsWasUpdateFalse() {
        User u = userRepo.save(testUser());
        String avatar = UUID.randomUUID().toString();

        var result = dao.upsert(u.getId(), avatar, "[SLPA] outbid msg", "outbid:1:42");

        assertThat(result.wasUpdate()).isFalse();
        assertThat(result.id()).isPositive();

        SlImMessage row = repo.findById(result.id()).orElseThrow();
        assertThat(row.getUserId()).isEqualTo(u.getId());
        assertThat(row.getAvatarUuid()).isEqualTo(avatar);
        assertThat(row.getMessageText()).isEqualTo("[SLPA] outbid msg");
        assertThat(row.getCoalesceKey()).isEqualTo("outbid:1:42");
        assertThat(row.getStatus()).isEqualTo(SlImMessageStatus.PENDING);
        assertThat(row.getAttempts()).isZero();
    }

    @Test
    void upsert_secondCallSameKey_updatesPendingRow_returnsWasUpdateTrue() {
        User u = userRepo.save(testUser());
        String avatar = UUID.randomUUID().toString();

        var first = dao.upsert(u.getId(), avatar, "[SLPA] first", "outbid:1:42");
        var second = dao.upsert(u.getId(), avatar, "[SLPA] second", "outbid:1:42");

        assertThat(second.wasUpdate()).isTrue();
        assertThat(second.id()).isEqualTo(first.id());

        SlImMessage row = repo.findById(first.id()).orElseThrow();
        assertThat(row.getMessageText()).isEqualTo("[SLPA] second");
        // updated_at bumped past created_at
        assertThat(row.getUpdatedAt()).isAfterOrEqualTo(row.getCreatedAt());
    }

    @Test
    void upsert_afterDelivered_insertsFreshRow() {
        User u = userRepo.save(testUser());
        String avatar = UUID.randomUUID().toString();

        var first = dao.upsert(u.getId(), avatar, "[SLPA] first", "outbid:1:42");

        // Mark first row DELIVERED via direct repo write (real path is Task 4 controller)
        SlImMessage delivered = repo.findById(first.id()).orElseThrow();
        delivered.setStatus(SlImMessageStatus.DELIVERED);
        repo.save(delivered);

        var second = dao.upsert(u.getId(), avatar, "[SLPA] second", "outbid:1:42");
        assertThat(second.wasUpdate()).isFalse();
        assertThat(second.id()).isNotEqualTo(first.id());
        // Both rows persist; partial index excludes the DELIVERED one from the predicate.
        assertThat(repo.findAll()).hasSize(2);
    }

    @Test
    void upsert_afterExpired_insertsFreshRow() {
        User u = userRepo.save(testUser());
        String avatar = UUID.randomUUID().toString();

        var first = dao.upsert(u.getId(), avatar, "[SLPA] first", "outbid:1:42");

        SlImMessage expired = repo.findById(first.id()).orElseThrow();
        expired.setStatus(SlImMessageStatus.EXPIRED);
        repo.save(expired);

        var second = dao.upsert(u.getId(), avatar, "[SLPA] second", "outbid:1:42");
        assertThat(second.wasUpdate()).isFalse();
        assertThat(second.id()).isNotEqualTo(first.id());
    }

    @Test
    void upsert_afterFailed_insertsFreshRow() {
        User u = userRepo.save(testUser());
        String avatar = UUID.randomUUID().toString();

        var first = dao.upsert(u.getId(), avatar, "[SLPA] first", "outbid:1:42");

        SlImMessage failed = repo.findById(first.id()).orElseThrow();
        failed.setStatus(SlImMessageStatus.FAILED);
        repo.save(failed);

        var second = dao.upsert(u.getId(), avatar, "[SLPA] second", "outbid:1:42");
        assertThat(second.wasUpdate()).isFalse();
        assertThat(second.id()).isNotEqualTo(first.id());
    }

    @Test
    void upsert_nullCoalesceKey_neverCollidesWithItself() {
        User u = userRepo.save(testUser());
        String avatar = UUID.randomUUID().toString();

        var first = dao.upsert(u.getId(), avatar, "[SLPA] one", null);
        var second = dao.upsert(u.getId(), avatar, "[SLPA] two", null);

        assertThat(first.wasUpdate()).isFalse();
        assertThat(second.wasUpdate()).isFalse();
        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(repo.findAll()).hasSize(2);
    }

    @Test
    void upsert_differentUsersSameKey_doNotCollide() {
        User a = userRepo.save(testUser());
        User b = userRepo.save(testUser());
        String avatarA = UUID.randomUUID().toString();
        String avatarB = UUID.randomUUID().toString();

        var rA = dao.upsert(a.getId(), avatarA, "[SLPA] A", "outbid:1:42");
        var rB = dao.upsert(b.getId(), avatarB, "[SLPA] B", "outbid:1:42");

        assertThat(rA.wasUpdate()).isFalse();
        assertThat(rB.wasUpdate()).isFalse();
        assertThat(rA.id()).isNotEqualTo(rB.id());
    }

    private User testUser() {
        return User.builder()
            .email("u-" + UUID.randomUUID() + "@test.local")
            .passwordHash("hash")
            .build();
    }
}
```

- [ ] **Step 8: Run the DAO tests — expect green**

Run: `cd backend && ./mvnw test -Dtest=SlImMessageDaoTest`
Expected: 7 tests pass.

- [ ] **Step 9: Write `SlImMessageRepositoryTest`**

```java
package com.slparcelauctions.backend.notification.slim;

import static org.assertj.core.api.Assertions.assertThat;

import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
    "slpa.notifications.cleanup.enabled=false",
    "slpa.notifications.sl-im.cleanup.enabled=false",
    "slpa.escrow.scheduler.enabled=false",
    "slpa.escrow.timeout-job.enabled=false",
    "slpa.escrow.command-dispatcher-job.enabled=false",
    "slpa.escrow.ownership-monitor-job.enabled=false",
    "slpa.review.scheduler.enabled=false",
    "slpa.refresh-token-cleanup.enabled=false",
    "auth.cleanup.enabled=false",
    "slpa.auction-end.enabled=false",
    "slpa.ownership-monitor.enabled=false"
})
class SlImMessageRepositoryTest {

    @Autowired SlImMessageDao dao;
    @Autowired SlImMessageRepository repo;
    @Autowired UserRepository userRepo;

    @Test
    void pollPending_returnsOldestFirst() throws InterruptedException {
        User u = userRepo.save(testUser());
        String avatar = UUID.randomUUID().toString();

        var first = dao.upsert(u.getId(), avatar, "[SLPA] first", null);
        Thread.sleep(20);
        var second = dao.upsert(u.getId(), avatar, "[SLPA] second", null);
        Thread.sleep(20);
        var third = dao.upsert(u.getId(), avatar, "[SLPA] third", null);

        List<SlImMessage> rows = repo.pollPending(10);
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).getId()).isEqualTo(first.id());
        assertThat(rows.get(1).getId()).isEqualTo(second.id());
        assertThat(rows.get(2).getId()).isEqualTo(third.id());
    }

    @Test
    void pollPending_honorsLimit() {
        User u = userRepo.save(testUser());
        String avatar = UUID.randomUUID().toString();

        for (int i = 0; i < 15; i++) {
            dao.upsert(u.getId(), avatar, "[SLPA] " + i, null);
        }

        assertThat(repo.pollPending(10)).hasSize(10);
        assertThat(repo.pollPending(5)).hasSize(5);
    }

    @Test
    void pollPending_excludesNonPending() {
        User u = userRepo.save(testUser());
        String avatar = UUID.randomUUID().toString();

        var pending = dao.upsert(u.getId(), avatar, "[SLPA] pending", null);
        var delivered = dao.upsert(u.getId(), avatar, "[SLPA] delivered", null);
        var expired = dao.upsert(u.getId(), avatar, "[SLPA] expired", null);
        var failed = dao.upsert(u.getId(), avatar, "[SLPA] failed", null);

        SlImMessage d = repo.findById(delivered.id()).orElseThrow();
        d.setStatus(SlImMessageStatus.DELIVERED);
        repo.save(d);
        SlImMessage e = repo.findById(expired.id()).orElseThrow();
        e.setStatus(SlImMessageStatus.EXPIRED);
        repo.save(e);
        SlImMessage f = repo.findById(failed.id()).orElseThrow();
        f.setStatus(SlImMessageStatus.FAILED);
        repo.save(f);

        List<SlImMessage> rows = repo.pollPending(10);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getId()).isEqualTo(pending.id());
    }

    @Test
    void countByStatus_returnsAllStatusBuckets() {
        User u = userRepo.save(testUser());
        String avatar = UUID.randomUUID().toString();

        var p1 = dao.upsert(u.getId(), avatar, "[SLPA] p1", "k1");
        var p2 = dao.upsert(u.getId(), avatar, "[SLPA] p2", "k2");
        var d = dao.upsert(u.getId(), avatar, "[SLPA] d", "k3");

        SlImMessage row = repo.findById(d.id()).orElseThrow();
        row.setStatus(SlImMessageStatus.DELIVERED);
        repo.save(row);

        Map<SlImMessageStatus, Long> counts = repo.countByStatus().stream()
            .collect(java.util.stream.Collectors.toMap(
                arr -> (SlImMessageStatus) arr[0],
                arr -> ((Number) arr[1]).longValue()));

        assertThat(counts.get(SlImMessageStatus.PENDING)).isEqualTo(2L);
        assertThat(counts.get(SlImMessageStatus.DELIVERED)).isEqualTo(1L);
    }

    private User testUser() {
        return User.builder()
            .email("u-" + UUID.randomUUID() + "@test.local")
            .passwordHash("hash")
            .build();
    }
}
```

- [ ] **Step 10: Run repository tests — expect green**

Run: `cd backend && ./mvnw test -Dtest=SlImMessageRepositoryTest`
Expected: 4 tests pass.

- [ ] **Step 11: Write `SlImCoalesceIndexInitializerTest`**

```java
package com.slparcelauctions.backend.notification.slim;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
    "slpa.notifications.cleanup.enabled=false",
    "slpa.notifications.sl-im.cleanup.enabled=false",
    "slpa.escrow.scheduler.enabled=false",
    "slpa.escrow.timeout-job.enabled=false",
    "slpa.escrow.command-dispatcher-job.enabled=false",
    "slpa.escrow.ownership-monitor-job.enabled=false",
    "slpa.review.scheduler.enabled=false",
    "slpa.refresh-token-cleanup.enabled=false",
    "auth.cleanup.enabled=false",
    "slpa.auction-end.enabled=false",
    "slpa.ownership-monitor.enabled=false"
})
class SlImCoalesceIndexInitializerTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired SlImCoalesceIndexInitializer initializer;

    @Test
    void partialUniqueIndexIsPresentAfterStartup() {
        // ApplicationReadyEvent fires during @SpringBootTest startup.
        Integer count = jdbc.queryForObject("""
            SELECT count(*)
            FROM pg_indexes
            WHERE tablename = 'sl_im_message'
              AND indexname = 'uq_sl_im_pending_coalesce'
            """, Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void emit_isIdempotent() {
        // Calling emit() a second time should not throw.
        initializer.emit();
        Integer count = jdbc.queryForObject("""
            SELECT count(*)
            FROM pg_indexes
            WHERE tablename = 'sl_im_message'
              AND indexname = 'uq_sl_im_pending_coalesce'
            """, Integer.class);
        assertThat(count).isEqualTo(1);
    }
}
```

- [ ] **Step 12: Run initializer tests — expect green**

Run: `cd backend && ./mvnw test -Dtest=SlImCoalesceIndexInitializerTest`
Expected: 2 tests pass.

- [ ] **Step 13: Run the full backend suite to verify no regressions**

Run: `cd backend && ./mvnw test`
Expected: previous baseline + 13 new tests, 0 failures.

- [ ] **Step 14: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/notification/slim/SlImMessage.java \
        backend/src/main/java/com/slparcelauctions/backend/notification/slim/SlImMessageStatus.java \
        backend/src/main/java/com/slparcelauctions/backend/notification/slim/SlImMessageDao.java \
        backend/src/main/java/com/slparcelauctions/backend/notification/slim/SlImMessageRepository.java \
        backend/src/main/java/com/slparcelauctions/backend/notification/slim/SlImCoalesceIndexInitializer.java \
        backend/src/test/java/com/slparcelauctions/backend/notification/slim/SlImMessageDaoTest.java \
        backend/src/test/java/com/slparcelauctions/backend/notification/slim/SlImMessageRepositoryTest.java \
        backend/src/test/java/com/slparcelauctions/backend/notification/slim/SlImCoalesceIndexInitializerTest.java
git commit -m "feat(sl-im): entity + DAO + repository + partial-index initializer"
```

---

## Task 2 — Backend gate + link resolver + message builder

**Goal:** Land the three pure-logic helpers: `SlImChannelGate` (the discriminated decision function), `SlImLinkResolver` (category → URL), `SlImMessageBuilder` (assembled message text with the byte-budget enforcement that prevents deeplinks from being truncated by SL's 1024-byte IM limit). All three are stateless `@Component` beans for injectability + Mockito-friendliness; tests are pure unit (no `@SpringBootTest`).

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/notification/slim/SlImChannelGate.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/notification/slim/SlImLinkResolver.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/notification/slim/SlImMessageBuilder.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/config/SlpaWebProperties.java` (if not already present)
- Modify: `backend/src/main/resources/application.yml` (add `slpa.web.base-url`)
- Test: `backend/src/test/java/com/slparcelauctions/backend/notification/slim/SlImChannelGateTest.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/notification/slim/SlImLinkResolverTest.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/notification/slim/SlImMessageBuilderTest.java`

### Steps

- [ ] **Step 1: Check whether `SlpaWebProperties` exists**

Run: `cd backend && find src/main/java -name "*WebProperties*" -o -name "*BaseUrl*"`

If it exists, note the existing prefix and use it. If it doesn't, create:

```java
package com.slparcelauctions.backend.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "slpa.web")
@Validated
public record SlpaWebProperties(
    @NotBlank String baseUrl
) {}
```

If it had to be created, also register it in whichever class has `@ConfigurationPropertiesScan` (probably `SlpaApplication.java` — verify). Most projects have the scan annotation that auto-registers all `@ConfigurationProperties` records, so a one-line creation is the typical case.

- [ ] **Step 2: Add `slpa.web.base-url` to application.yml**

Open `backend/src/main/resources/application.yml`. Find the `slpa:` block. Add:

```yaml
slpa:
  # ... existing keys ...
  web:
    base-url: ${SLPA_WEB_BASE_URL:http://localhost:3000}
```

The dev default points at the local Next.js port; staging/prod override via env var.

- [ ] **Step 3: Create `SlImChannelGate`**

```java
package com.slparcelauctions.backend.notification.slim;

import com.slparcelauctions.backend.notification.NotificationCategory;
import com.slparcelauctions.backend.notification.NotificationGroup;
import com.slparcelauctions.backend.user.User;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Pure decision function for "should we queue an SL IM for this user/category?"
 *
 * <p>Stateless. Returns a discriminated {@link Decision} so callers can log the
 * reason at DEBUG, which makes "I didn't get an IM" support tickets resolvable
 * from logs alone without DB archaeology.
 *
 * <p>Order of checks (no-avatar is the universal floor — applies even to SYSTEM):
 * <ol>
 *   <li>{@link User#getSlAvatarUuid()} == null  → {@link Decision#SKIP_NO_AVATAR}</li>
 *   <li>category.group == SYSTEM                → {@link Decision#QUEUE_BYPASS_PREFS}</li>
 *   <li>{@link User#isNotifySlImMuted()} true   → {@link Decision#SKIP_MUTED}</li>
 *   <li>notifySlIm[group] false or absent       → {@link Decision#SKIP_GROUP_DISABLED}</li>
 *   <li>otherwise                               → {@link Decision#QUEUE}</li>
 * </ol>
 */
@Component
public class SlImChannelGate {

    public enum Decision {
        QUEUE,
        QUEUE_BYPASS_PREFS,
        SKIP_NO_AVATAR,
        SKIP_MUTED,
        SKIP_GROUP_DISABLED
    }

    public Decision decide(User user, NotificationCategory category) {
        if (user.getSlAvatarUuid() == null) {
            return Decision.SKIP_NO_AVATAR;
        }

        boolean isSystem = category.getGroup() == NotificationGroup.SYSTEM;
        if (isSystem) {
            return Decision.QUEUE_BYPASS_PREFS;
        }

        if (user.isNotifySlImMuted()) {
            return Decision.SKIP_MUTED;
        }

        Map<String, Boolean> prefs = user.getNotifySlIm();
        String groupKey = category.getGroup().name().toLowerCase();
        Boolean groupOn = prefs == null ? null : prefs.get(groupKey);
        if (groupOn == null || !groupOn) {
            return Decision.SKIP_GROUP_DISABLED;
        }

        return Decision.QUEUE;
    }
}
```

- [ ] **Step 4: Write `SlImChannelGateTest` — parameterized matrix**

```java
package com.slparcelauctions.backend.notification.slim;

import static org.assertj.core.api.Assertions.assertThat;

import com.slparcelauctions.backend.notification.NotificationCategory;
import com.slparcelauctions.backend.notification.NotificationGroup;
import com.slparcelauctions.backend.notification.slim.SlImChannelGate.Decision;
import com.slparcelauctions.backend.user.User;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class SlImChannelGateTest {

    private final SlImChannelGate gate = new SlImChannelGate();

    @Test
    void noAvatar_isSkipNoAvatar_evenForSystem() {
        User u = userWithoutAvatar();
        assertThat(gate.decide(u, NotificationCategory.SYSTEM_ANNOUNCEMENT))
            .isEqualTo(Decision.SKIP_NO_AVATAR);
        assertThat(gate.decide(u, NotificationCategory.OUTBID))
            .isEqualTo(Decision.SKIP_NO_AVATAR);
    }

    @Test
    void systemCategory_bypassesMuteAndGroupPrefs_whenAvatarPresent() {
        User u = userWithAvatar();
        u.setNotifySlImMuted(true);
        u.setNotifySlIm(allGroupsOff());

        assertThat(gate.decide(u, NotificationCategory.SYSTEM_ANNOUNCEMENT))
            .isEqualTo(Decision.QUEUE_BYPASS_PREFS);
    }

    @Test
    void mutedUser_isSkipMuted_forNonSystemCategories() {
        User u = userWithAvatar();
        u.setNotifySlImMuted(true);
        u.setNotifySlIm(allGroupsOn());

        assertThat(gate.decide(u, NotificationCategory.OUTBID))
            .isEqualTo(Decision.SKIP_MUTED);
        assertThat(gate.decide(u, NotificationCategory.ESCROW_FUNDED))
            .isEqualTo(Decision.SKIP_MUTED);
    }

    @Test
    void groupOff_isSkipGroupDisabled() {
        User u = userWithAvatar();
        u.setNotifySlImMuted(false);
        Map<String, Boolean> prefs = allGroupsOn();
        prefs.put("bidding", false);
        u.setNotifySlIm(prefs);

        assertThat(gate.decide(u, NotificationCategory.OUTBID))
            .isEqualTo(Decision.SKIP_GROUP_DISABLED);
        // Other groups still queue:
        assertThat(gate.decide(u, NotificationCategory.ESCROW_FUNDED))
            .isEqualTo(Decision.QUEUE);
    }

    @Test
    void groupAbsent_isSkipGroupDisabled() {
        User u = userWithAvatar();
        u.setNotifySlImMuted(false);
        u.setNotifySlIm(new HashMap<>()); // no keys

        assertThat(gate.decide(u, NotificationCategory.OUTBID))
            .isEqualTo(Decision.SKIP_GROUP_DISABLED);
    }

    @Test
    void allGroupsOnUnmutedWithAvatar_isQueue() {
        User u = userWithAvatar();
        u.setNotifySlImMuted(false);
        u.setNotifySlIm(allGroupsOn());

        for (NotificationCategory c : NotificationCategory.values()) {
            if (c.getGroup() == NotificationGroup.SYSTEM) {
                assertThat(gate.decide(u, c)).isEqualTo(Decision.QUEUE_BYPASS_PREFS);
            } else {
                assertThat(gate.decide(u, c)).isEqualTo(Decision.QUEUE);
            }
        }
    }

    @ParameterizedTest
    @MethodSource("matrixCases")
    void matrix(boolean hasAvatar, boolean muted, boolean groupOn,
                NotificationCategory category, Decision expected) {
        User u = hasAvatar ? userWithAvatar() : userWithoutAvatar();
        u.setNotifySlImMuted(muted);
        Map<String, Boolean> prefs = allGroupsOn();
        if (!groupOn) {
            prefs.put(category.getGroup().name().toLowerCase(), false);
        }
        u.setNotifySlIm(prefs);

        assertThat(gate.decide(u, category)).isEqualTo(expected);
    }

    static Stream<Arguments> matrixCases() {
        // (hasAvatar, muted, groupOn, category, expected)
        return Stream.of(
            // No avatar always wins
            Arguments.of(false, false, true,  NotificationCategory.OUTBID,            Decision.SKIP_NO_AVATAR),
            Arguments.of(false, true,  false, NotificationCategory.OUTBID,            Decision.SKIP_NO_AVATAR),
            Arguments.of(false, false, true,  NotificationCategory.SYSTEM_ANNOUNCEMENT, Decision.SKIP_NO_AVATAR),
            // SYSTEM bypasses prefs (when avatar present)
            Arguments.of(true,  true,  false, NotificationCategory.SYSTEM_ANNOUNCEMENT, Decision.QUEUE_BYPASS_PREFS),
            Arguments.of(true,  false, true,  NotificationCategory.SYSTEM_ANNOUNCEMENT, Decision.QUEUE_BYPASS_PREFS),
            // Muted wins over group prefs (non-SYSTEM)
            Arguments.of(true,  true,  true,  NotificationCategory.OUTBID,            Decision.SKIP_MUTED),
            // Group off when not muted
            Arguments.of(true,  false, false, NotificationCategory.OUTBID,            Decision.SKIP_GROUP_DISABLED),
            // Happy path
            Arguments.of(true,  false, true,  NotificationCategory.OUTBID,            Decision.QUEUE),
            Arguments.of(true,  false, true,  NotificationCategory.AUCTION_WON,       Decision.QUEUE),
            Arguments.of(true,  false, true,  NotificationCategory.ESCROW_FUNDED,     Decision.QUEUE)
        );
    }

    private User userWithoutAvatar() {
        User u = User.builder()
            .email("u-" + UUID.randomUUID() + "@test.local")
            .passwordHash("hash")
            .build();
        u.setSlAvatarUuid(null);
        u.setNotifySlIm(allGroupsOn());
        return u;
    }

    private User userWithAvatar() {
        User u = userWithoutAvatar();
        u.setSlAvatarUuid(UUID.randomUUID().toString());
        return u;
    }

    private Map<String, Boolean> allGroupsOn() {
        Map<String, Boolean> m = new HashMap<>();
        for (NotificationGroup g : NotificationGroup.values()) {
            m.put(g.name().toLowerCase(), true);
        }
        return m;
    }

    private Map<String, Boolean> allGroupsOff() {
        Map<String, Boolean> m = new HashMap<>();
        for (NotificationGroup g : NotificationGroup.values()) {
            m.put(g.name().toLowerCase(), false);
        }
        return m;
    }
}
```

- [ ] **Step 5: Run gate tests — expect green**

Run: `cd backend && ./mvnw test -Dtest=SlImChannelGateTest`
Expected: 16 tests pass (6 explicit + 10 parameterized).

- [ ] **Step 6: Create `SlImLinkResolver`**

```java
package com.slparcelauctions.backend.notification.slim;

import com.slparcelauctions.backend.config.SlpaWebProperties;
import com.slparcelauctions.backend.notification.NotificationCategory;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Maps a notification category and its data blob to a deeplink URL for inclusion
 * in the SL IM message. Mirrors the frontend {@code categoryMap.deeplink} logic
 * — the duplication is intentional. Embedding the URL in the in-app row's data
 * blob would couple in-app rows to a specific channel's URL semantics and make
 * URL changes a data migration.
 */
@Component
@RequiredArgsConstructor
public class SlImLinkResolver {

    private final SlpaWebProperties webProps;

    public String resolve(NotificationCategory category, Map<String, Object> data) {
        String base = webProps.baseUrl();
        return switch (category) {
            case OUTBID, PROXY_EXHAUSTED, AUCTION_LOST,
                 AUCTION_ENDED_RESERVE_NOT_MET, AUCTION_ENDED_NO_BIDS,
                 AUCTION_ENDED_BOUGHT_NOW, AUCTION_ENDED_SOLD,
                 LISTING_VERIFIED, LISTING_CANCELLED_BY_SELLER,
                 REVIEW_RECEIVED ->
                base + "/auction/" + data.get("auctionId");
            case AUCTION_WON ->
                base + "/auction/" + data.get("auctionId") + "/escrow";
            case ESCROW_FUNDED, ESCROW_TRANSFER_CONFIRMED, ESCROW_PAYOUT,
                 ESCROW_EXPIRED, ESCROW_DISPUTED, ESCROW_FROZEN,
                 ESCROW_PAYOUT_STALLED, ESCROW_TRANSFER_REMINDER ->
                base + "/auction/" + data.get("auctionId") + "/escrow";
            case LISTING_SUSPENDED, LISTING_REVIEW_REQUIRED ->
                base + "/dashboard/listings";
            case SYSTEM_ANNOUNCEMENT ->
                base + "/notifications";
        };
    }
}
```

- [ ] **Step 7: Write `SlImLinkResolverTest`**

```java
package com.slparcelauctions.backend.notification.slim;

import static org.assertj.core.api.Assertions.assertThat;

import com.slparcelauctions.backend.config.SlpaWebProperties;
import com.slparcelauctions.backend.notification.NotificationCategory;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SlImLinkResolverTest {

    private static final String BASE = "https://slpa.example.com";
    private final SlImLinkResolver resolver = new SlImLinkResolver(new SlpaWebProperties(BASE));

    @Test
    void outbid_resolvesToAuctionPage() {
        assertThat(resolver.resolve(NotificationCategory.OUTBID, Map.of("auctionId", 42)))
            .isEqualTo(BASE + "/auction/42");
    }

    @Test
    void auctionWon_resolvesToEscrowPage() {
        assertThat(resolver.resolve(NotificationCategory.AUCTION_WON, Map.of("auctionId", 42)))
            .isEqualTo(BASE + "/auction/42/escrow");
    }

    @Test
    void escrowFunded_resolvesToEscrowPage() {
        assertThat(resolver.resolve(NotificationCategory.ESCROW_FUNDED,
                Map.of("auctionId", 42, "escrowId", 100)))
            .isEqualTo(BASE + "/auction/42/escrow");
    }

    @Test
    void listingSuspended_resolvesToDashboardListings() {
        assertThat(resolver.resolve(NotificationCategory.LISTING_SUSPENDED,
                Map.of("auctionId", 42, "reason", "test")))
            .isEqualTo(BASE + "/dashboard/listings");
    }

    @Test
    void systemAnnouncement_resolvesToNotificationsFeed() {
        assertThat(resolver.resolve(NotificationCategory.SYSTEM_ANNOUNCEMENT, Map.of()))
            .isEqualTo(BASE + "/notifications");
    }

    @Test
    void everyCategory_producesAValidUrl() {
        // Every category must produce a URL starting with the base; ensures the
        // switch covers all enum values (compile-time guarantee + runtime sanity).
        for (NotificationCategory c : NotificationCategory.values()) {
            String url = resolver.resolve(c, Map.of(
                "auctionId", 42, "escrowId", 100, "reviewId", 5,
                "reason", "x", "parcelName", "P", "currentBidL", 1L,
                "isProxyOutbid", false, "endsAt", "2026-04-26T18:00:00Z",
                "winningBidL", 1L, "highestBidL", 1L, "buyNowL", 1L,
                "transferDeadline", "2026-04-26T18:00:00Z", "payoutL", 1L,
                "reasonCategory", "x", "rating", 5));
            assertThat(url).startsWith(BASE);
        }
    }
}
```

- [ ] **Step 8: Run resolver tests — expect green**

Run: `cd backend && ./mvnw test -Dtest=SlImLinkResolverTest`
Expected: 6 tests pass (5 individual + 1 enum coverage).

- [ ] **Step 9: Create `SlImMessageBuilder` with byte-budget enforcement**

```java
package com.slparcelauctions.backend.notification.slim;

import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

/**
 * Assembles the final SL IM text from (title, body, deeplink) components,
 * enforcing the SL {@code llInstantMessage} 1024-BYTE limit.
 *
 * <p>The deeplink, the {@code [SLPA] } prefix, and the title are non-negotiable
 * — they are reserved first and never trimmed. Only the body is ellipsizable.
 *
 * <p>SL truncates IMs that exceed 1024 bytes silently, from the end. If the
 * deeplink lands at the end (it does), it gets cleanly cut off. UTF-8 multi-byte
 * characters (CJK, emoji, accented Latin) push byte counts above char counts —
 * a 1023-character message can occupy 1500+ bytes. Hence the byte-aware budget.
 *
 * <p>Trim algorithm: progressively shorten the body by Java {@code char} from
 * the end until the assembled string fits. If a trim boundary lands on a high
 * surrogate (would split a UTF-16 surrogate pair, producing an invalid
 * sequence), back off one more char.
 */
@Component
public class SlImMessageBuilder {

    private static final int MAX_BYTES = 1024;
    private static final String PREFIX = "[SLPA] ";
    private static final String SEPARATOR = "\n\n";
    private static final String ELLIPSIS = "…";  // 3 bytes UTF-8

    public String assemble(String title, String body, String deeplink) {
        String candidate = PREFIX + title + SEPARATOR + body + SEPARATOR + deeplink;
        if (utf8Bytes(candidate) <= MAX_BYTES) {
            return candidate;
        }

        int reservedBytes = utf8Bytes(PREFIX + title + SEPARATOR + SEPARATOR + deeplink + ELLIPSIS);
        int availableForBody = MAX_BYTES - reservedBytes;

        if (availableForBody <= 0) {
            return assembleWithoutBody(title, deeplink);
        }

        int k = body.length();
        String trimmedBody = null;
        while (k > 0) {
            int boundary = k;
            if (Character.isHighSurrogate(body.charAt(boundary - 1))) {
                boundary -= 1;
            }
            if (boundary <= 0) {
                break;
            }
            String attempt = body.substring(0, boundary) + ELLIPSIS;
            if (utf8Bytes(attempt) <= availableForBody) {
                trimmedBody = attempt;
                break;
            }
            k = boundary - 1;
        }
        if (trimmedBody == null) {
            return assembleWithoutBody(title, deeplink);
        }
        return PREFIX + title + SEPARATOR + trimmedBody + SEPARATOR + deeplink;
    }

    private String assembleWithoutBody(String title, String deeplink) {
        // Last-resort: no body, just title + deeplink. If even that exceeds the
        // budget, trim the title. Deeplink stays whole.
        String candidate = PREFIX + title + SEPARATOR + deeplink;
        if (utf8Bytes(candidate) <= MAX_BYTES) {
            return candidate;
        }
        int reserved = utf8Bytes(PREFIX + SEPARATOR + deeplink + ELLIPSIS);
        int availableForTitle = MAX_BYTES - reserved;
        int k = title.length();
        while (k > 0) {
            int boundary = k;
            if (Character.isHighSurrogate(title.charAt(boundary - 1))) {
                boundary -= 1;
            }
            if (boundary <= 0) {
                break;
            }
            String attempt = title.substring(0, boundary) + ELLIPSIS;
            if (utf8Bytes(attempt) <= availableForTitle) {
                return PREFIX + attempt + SEPARATOR + deeplink;
            }
            k = boundary - 1;
        }
        // If even an empty-title fallback exceeds the budget, the deeplink itself
        // is too long. Return the deeplink alone — truncated to MAX_BYTES.
        byte[] bytes = deeplink.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) {
            return new String(bytes, 0, MAX_BYTES, StandardCharsets.UTF_8);
        }
        return deeplink;
    }

    private static int utf8Bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }
}
```

- [ ] **Step 10: Write `SlImMessageBuilderTest` — including the three mandatory byte-vs-char cases**

```java
package com.slparcelauctions.backend.notification.slim;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SlImMessageBuilderTest {

    private final SlImMessageBuilder builder = new SlImMessageBuilder();

    @Test
    void assemble_shortInputs_returnsExactTemplate() {
        String result = builder.assemble(
            "You've been outbid on Hampton Hills",
            "Current bid is L$2,000.",
            "https://slpa.example.com/auction/42#bid-panel");

        assertThat(result).isEqualTo(
            "[SLPA] You've been outbid on Hampton Hills\n\n" +
            "Current bid is L$2,000.\n\n" +
            "https://slpa.example.com/auction/42#bid-panel");
        assertThat(byteLen(result)).isLessThanOrEqualTo(1024);
    }

    @Test
    void assemble_multiByteParcelName_keepsDeeplinkIntact() {
        // Three CJK characters, each 3 bytes UTF-8.
        String result = builder.assemble(
            "You've been outbid on 東京タワー Estates",
            "Current bid is L$2,000.",
            "https://slpa.example.com/auction/42#bid-panel");

        assertThat(result).contains("東京タワー Estates");
        assertThat(result).endsWith("https://slpa.example.com/auction/42#bid-panel");
        assertThat(byteLen(result)).isLessThanOrEqualTo(1024);
    }

    @Test
    void assemble_emojiParcelName_keepsDeeplinkIntact() {
        // 🌸 is a 4-byte UTF-8 character (U+1F338) and a UTF-16 surrogate pair.
        String result = builder.assemble(
            "Your auction sold: 🌸 Sakura Plot",
            "Winning bid: L$5,200.",
            "https://slpa.example.com/auction/99");

        assertThat(result).contains("🌸 Sakura Plot");
        assertThat(result).endsWith("https://slpa.example.com/auction/99");
        assertThat(byteLen(result)).isLessThanOrEqualTo(1024);
    }

    @Test
    void assemble_longBody_ellipsizesBodyKeepsDeeplinkIntact() {
        String longBody = "x".repeat(2000);
        String deeplink = "https://slpa.example.com/auction/42";
        String result = builder.assemble("Hampton Hills update", longBody, deeplink);

        assertThat(result).contains("…");
        assertThat(result).endsWith(deeplink);
        assertThat(result).startsWith("[SLPA] Hampton Hills update");
        assertThat(byteLen(result)).isLessThanOrEqualTo(1024);
    }

    @Test
    void assemble_longBodyWithMultiByteContent_ellipsizesAtCharBoundary() {
        // Body of 1500 CJK characters (4500 bytes UTF-8). Must be trimmed; ellipsis
        // must land at a valid char boundary (no orphaned surrogate halves).
        String longBody = "東".repeat(1500);
        String result = builder.assemble("Hampton Hills update", longBody,
            "https://slpa.example.com/auction/42");

        assertThat(result).endsWith("https://slpa.example.com/auction/42");
        assertThat(byteLen(result)).isLessThanOrEqualTo(1024);
        // Decoding round-trips cleanly (no replacement chars from broken sequences):
        byte[] bytes = result.getBytes(StandardCharsets.UTF_8);
        String roundTrip = new String(bytes, StandardCharsets.UTF_8);
        assertThat(roundTrip).isEqualTo(result);
    }

    @Test
    void assemble_bodyEmpty_returnsTitleAndDeeplink() {
        String result = builder.assemble("Title", "", "https://slpa.example.com/x");
        assertThat(result).isEqualTo("[SLPA] Title\n\n\n\nhttps://slpa.example.com/x");
        assertThat(byteLen(result)).isLessThanOrEqualTo(1024);
    }

    @Test
    void assemble_pathologicalCase_titleAndDeeplinkExceedBudget_dropsBodyTrimsTitle() {
        // Title alone is 1500 chars + deeplink 50 chars > 1024.
        String result = builder.assemble("a".repeat(1500), "body content",
            "https://slpa.example.com/auction/42");

        assertThat(result).endsWith("https://slpa.example.com/auction/42");
        assertThat(result).contains("…");
        assertThat(byteLen(result)).isLessThanOrEqualTo(1024);
    }

    private int byteLen(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }
}
```

- [ ] **Step 11: Run builder tests — expect green**

Run: `cd backend && ./mvnw test -Dtest=SlImMessageBuilderTest`
Expected: 7 tests pass.

- [ ] **Step 12: Run the full backend suite to confirm no regressions**

Run: `cd backend && ./mvnw test`
Expected: previous (Task 1) baseline + 29 new tests, 0 failures.

- [ ] **Step 13: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/notification/slim/SlImChannelGate.java \
        backend/src/main/java/com/slparcelauctions/backend/notification/slim/SlImLinkResolver.java \
        backend/src/main/java/com/slparcelauctions/backend/notification/slim/SlImMessageBuilder.java \
        backend/src/main/java/com/slparcelauctions/backend/config/SlpaWebProperties.java \
        backend/src/main/resources/application.yml \
        backend/src/test/java/com/slparcelauctions/backend/notification/slim/SlImChannelGateTest.java \
        backend/src/test/java/com/slparcelauctions/backend/notification/slim/SlImLinkResolverTest.java \
        backend/src/test/java/com/slparcelauctions/backend/notification/slim/SlImMessageBuilderTest.java
git commit -m "feat(sl-im): channel gate + link resolver + byte-budget message builder"
```

---

## Task 3 — Backend dispatcher + integration with NotificationService.publish + fan-out integration

**Goal:** Land the `SlImChannelDispatcher` with two entry points — `maybeQueue` (single-recipient, called from `NotificationService.publish`'s afterCommit hook) and `maybeQueueForFanout` (called inside the per-recipient REQUIRES_NEW lambda in `NotificationPublisherImpl.listingCancelledBySellerFanout`). Wire both call sites. Add 5 vertical-slice integration tests covering OUTBID coalesce + gate, escrow recipient routing, fan-out partial failure, SYSTEM bypass, no-avatar skip.

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/notification/slim/SlImChannelDispatcher.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/notification/NotificationService.java` (add second afterCommit hook)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/notification/NotificationPublisherImpl.java` (sibling call inside fan-out REQUIRES_NEW lambda)
- Test: `backend/src/test/java/com/slparcelauctions/backend/notification/slim/SlImChannelDispatcherTest.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/notification/slim/integration/OutbidImIntegrationTest.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/notification/slim/integration/EscrowImIntegrationTest.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/notification/slim/integration/CancellationFanoutImIntegrationTest.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/notification/slim/integration/SystemBypassImIntegrationTest.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/notification/slim/integration/NoAvatarSkipImIntegrationTest.java`

### Steps

- [ ] **Step 1: Create `SlImChannelDispatcher`**

```java
package com.slparcelauctions.backend.notification.slim;

import com.slparcelauctions.backend.notification.NotificationCategory;
import com.slparcelauctions.backend.notification.NotificationEvent;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Two entry points for queueing an SL IM:
 * <ul>
 *   <li>{@link #maybeQueue(NotificationEvent)} — single-recipient. Caller has
 *       registered this as an afterCommit hook on the parent transaction; we
 *       open our own REQUIRES_NEW so failures don't propagate.</li>
 *   <li>{@link #maybeQueueForFanout(long, NotificationCategory, String, String, Map, String)}
 *       — fan-out. Caller is already inside a REQUIRES_NEW per-recipient lambda;
 *       this writes as a sibling, atomic with the in-app row for that recipient.
 *       Failures here propagate (caller's per-recipient try-catch isolates).</li>
 * </ul>
 *
 * <p>Both paths read User fresh inside their respective transactions so a
 * preferences PUT before the dispatch has read-your-writes semantics.
 */
@Component
@Slf4j
public class SlImChannelDispatcher {

    private final UserRepository userRepo;
    private final SlImChannelGate gate;
    private final SlImMessageBuilder messageBuilder;
    private final SlImLinkResolver linkResolver;
    private final SlImMessageDao dao;
    private final TransactionTemplate requiresNewTx;

    // Explicit constructor — @RequiredArgsConstructor doesn't propagate @Qualifier
    // (see sub-spec 1 Task 3 deviation note).
    public SlImChannelDispatcher(
        UserRepository userRepo,
        SlImChannelGate gate,
        SlImMessageBuilder messageBuilder,
        SlImLinkResolver linkResolver,
        SlImMessageDao dao,
        @Qualifier("requiresNewTxTemplate") TransactionTemplate requiresNewTx
    ) {
        this.userRepo = userRepo;
        this.gate = gate;
        this.messageBuilder = messageBuilder;
        this.linkResolver = linkResolver;
        this.dao = dao;
        this.requiresNewTx = requiresNewTx;
    }

    public void maybeQueue(NotificationEvent event) {
        try {
            requiresNewTx.execute(status -> {
                doQueue(event.userId(), event.category(),
                    event.title(), event.body(), event.data(), event.coalesceKey());
                return null;
            });
        } catch (Exception ex) {
            log.warn("SL IM dispatch failed for userId={} category={}: {}",
                event.userId(), event.category(), ex.toString());
        }
    }

    /**
     * Fan-out variant. Caller (inside a per-recipient REQUIRES_NEW) gets atomic
     * commit semantics: in-app row + IM queue row commit together or neither
     * does. Caller's try-catch isolates this recipient's failure from siblings.
     */
    public void maybeQueueForFanout(
        long userId, NotificationCategory category,
        String title, String body, Map<String, Object> data, String coalesceKey
    ) {
        doQueue(userId, category, title, body, data, coalesceKey);
    }

    private void doQueue(
        long userId, NotificationCategory category,
        String title, String body, Map<String, Object> data, String coalesceKey
    ) {
        User user = userRepo.findById(userId)
            .orElseThrow(() -> new IllegalStateException("user not found: " + userId));

        SlImChannelGate.Decision decision = gate.decide(user, category);
        log.debug("SL IM gate decision userId={} category={}: {}", userId, category, decision);

        switch (decision) {
            case QUEUE, QUEUE_BYPASS_PREFS -> {
                String deeplink = linkResolver.resolve(category, data);
                String messageText = messageBuilder.assemble(title, body, deeplink);
                dao.upsert(userId, user.getSlAvatarUuid(), messageText, coalesceKey);
            }
            case SKIP_NO_AVATAR, SKIP_MUTED, SKIP_GROUP_DISABLED -> {
                // intentional no-op; decision logged at DEBUG above
            }
        }
    }
}
```

- [ ] **Step 2: Modify `NotificationService.publish` to register the second afterCommit hook**

Open `backend/src/main/java/com/slparcelauctions/backend/notification/NotificationService.java`. Find the existing afterCommit registration for `wsBroadcaster.broadcastUpsert`. Add a sibling registration right after it for the SL IM dispatcher.

```java
// Add to imports if not present:
import com.slparcelauctions.backend.notification.slim.SlImChannelDispatcher;

// Add the new field to the class (with the existing @RequiredArgsConstructor):
private final SlImChannelDispatcher slImChannelDispatcher;

// In publish(NotificationEvent event), after the existing
// TransactionSynchronizationManager.registerSynchronization for the WS broadcast,
// register a second one for SL IM:

TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
    @Override
    public void afterCommit() {
        slImChannelDispatcher.maybeQueue(event);
    }
});
```

The exact placement: read the existing `publish(...)` method, locate the existing `registerSynchronization` block, add the new one immediately after it. Constructor injection happens automatically via Lombok's `@RequiredArgsConstructor` — the new field is added in declaration order matching where you place it in the class body.

- [ ] **Step 3: Modify `NotificationPublisherImpl.listingCancelledBySellerFanout` for sibling call**

Open `backend/src/main/java/com/slparcelauctions/backend/notification/NotificationPublisherImpl.java`. Find `listingCancelledBySellerFanout(...)`. The current per-recipient loop wraps `notificationDao.upsert` in `requiresNewTxTemplate.execute(...)`. Add `slImChannelDispatcher.maybeQueueForFanout(...)` as a sibling call inside the same lambda:

```java
// Add to imports:
import com.slparcelauctions.backend.notification.slim.SlImChannelDispatcher;

// The constructor already takes a TransactionTemplate via @Qualifier (see Task 1
// deviation in sub-spec 1). Add SlImChannelDispatcher as a constructor parameter:
private final SlImChannelDispatcher slImChannelDispatcher;

// Update the constructor parameter list to include slImChannelDispatcher.

// In the listingCancelledBySellerFanout method, modify the loop body:
TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
    @Override
    public void afterCommit() {
        for (Long bidderId : bidderUserIds) {
            try {
                UpsertResult result = requiresNewTxTemplate.execute(status -> {
                    UpsertResult r = notificationDao.upsert(
                        bidderId, NotificationCategory.LISTING_CANCELLED_BY_SELLER,
                        title, body, data, /* coalesceKey */ null);
                    slImChannelDispatcher.maybeQueueForFanout(    // ← NEW
                        bidderId, NotificationCategory.LISTING_CANCELLED_BY_SELLER,
                        title, body, data, /* coalesceKey */ null);
                    return r;
                });
                wsBroadcaster.broadcastUpsert(bidderId, result, dtoFor(bidderId, result, data, title, body));
            } catch (Exception ex) {
                log.warn("Fan-out notification failed for userId={} auctionId={} category=LISTING_CANCELLED_BY_SELLER: {}",
                    bidderId, auctionId, ex.toString());
            }
        }
    }
});
```

The lambda inside `requiresNewTxTemplate.execute` now does both DAO upsert AND dispatcher call. If the dispatcher throws, the entire REQUIRES_NEW for that recipient rolls back (in-app row + IM row both lost). Sibling recipients are unaffected because the outer `try-catch` catches before the next loop iteration.

- [ ] **Step 4: Run existing notification tests to confirm the wiring change didn't break anything**

Run: `cd backend && ./mvnw test -Dtest='NotificationServiceTest,NotificationPublisherImplTest'`
Expected: previous baseline still green. The new dispatcher field is autowired but `@MockitoBean SlImChannelDispatcher` has not been added to existing tests — they'll inject the real bean which will try to write to the real `sl_im_message` table. That's fine; the existing tests don't exercise the dispatcher path because they don't use SL avatars or set prefs (default User state has no `slAvatarUuid`, so `gate.decide` returns `SKIP_NO_AVATAR` and no-op).

If existing tests fail because of the wiring, the most likely cause is that `User.builder()...build()` constructs users with default `notifySlIm` somehow throwing — read the failure stack trace and confirm the User entity's defaults are non-null. The dispatcher catches exceptions in `maybeQueue` and only logs WARN; nothing should propagate to fail an existing test.

- [ ] **Step 5: Write `SlImChannelDispatcherTest` — focused tests**

```java
package com.slparcelauctions.backend.notification.slim;

import static org.assertj.core.api.Assertions.assertThat;

import com.slparcelauctions.backend.notification.NotificationCategory;
import com.slparcelauctions.backend.notification.NotificationEvent;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@TestPropertySource(properties = {
    "slpa.notifications.cleanup.enabled=false",
    "slpa.notifications.sl-im.cleanup.enabled=false",
    "slpa.escrow.scheduler.enabled=false",
    "slpa.escrow.timeout-job.enabled=false",
    "slpa.escrow.command-dispatcher-job.enabled=false",
    "slpa.escrow.ownership-monitor-job.enabled=false",
    "slpa.review.scheduler.enabled=false",
    "slpa.refresh-token-cleanup.enabled=false",
    "auth.cleanup.enabled=false",
    "slpa.auction-end.enabled=false",
    "slpa.ownership-monitor.enabled=false"
})
class SlImChannelDispatcherTest {

    @Autowired SlImChannelDispatcher dispatcher;
    @Autowired SlImMessageRepository repo;
    @Autowired UserRepository userRepo;
    @Autowired TransactionTemplate transactionTemplate;

    private User userWithAvatarAndAllGroupsOn() {
        User u = User.builder()
            .email("u-" + UUID.randomUUID() + "@test.local")
            .passwordHash("hash")
            .build();
        u.setSlAvatarUuid(UUID.randomUUID().toString());
        u.setNotifySlImMuted(false);
        Map<String, Boolean> prefs = new HashMap<>();
        prefs.put("bidding", true);
        prefs.put("auction_result", true);
        prefs.put("escrow", true);
        prefs.put("listing_status", true);
        prefs.put("reviews", true);
        prefs.put("system", true);
        u.setNotifySlIm(prefs);
        return userRepo.save(u);
    }

    @BeforeEach
    void clean() {
        repo.deleteAll();
    }

    @Test
    void maybeQueue_happyPath_insertsRowWithBuiltMessage() {
        User u = userWithAvatarAndAllGroupsOn();
        Map<String, Object> data = Map.of("auctionId", 42, "parcelName", "Hampton",
            "currentBidL", 2000L, "isProxyOutbid", false, "endsAt", "2026-04-26T18:00:00Z");
        NotificationEvent event = new NotificationEvent(
            u.getId(), NotificationCategory.OUTBID,
            "You've been outbid on Hampton", "Current bid is L$2,000.",
            data, "outbid:" + u.getId() + ":42");

        // Run inside a transaction so the dispatcher's REQUIRES_NEW commits independently.
        transactionTemplate.execute(status -> {
            dispatcher.maybeQueue(event);
            return null;
        });

        List<SlImMessage> rows = repo.findAll();
        assertThat(rows).hasSize(1);
        SlImMessage row = rows.get(0);
        assertThat(row.getUserId()).isEqualTo(u.getId());
        assertThat(row.getAvatarUuid()).isEqualTo(u.getSlAvatarUuid());
        assertThat(row.getStatus()).isEqualTo(SlImMessageStatus.PENDING);
        assertThat(row.getMessageText()).startsWith("[SLPA] You've been outbid on Hampton");
        assertThat(row.getMessageText()).contains("Current bid is L$2,000.");
        assertThat(row.getMessageText()).endsWith("/auction/42");
        assertThat(row.getCoalesceKey()).isEqualTo("outbid:" + u.getId() + ":42");
    }

    @Test
    void maybeQueue_userMuted_doesNotQueue() {
        User u = userWithAvatarAndAllGroupsOn();
        u.setNotifySlImMuted(true);
        userRepo.save(u);

        NotificationEvent event = new NotificationEvent(
            u.getId(), NotificationCategory.OUTBID, "title", "body",
            Map.of("auctionId", 42), null);

        transactionTemplate.execute(status -> { dispatcher.maybeQueue(event); return null; });

        assertThat(repo.findAll()).isEmpty();
    }

    @Test
    void maybeQueue_groupDisabled_doesNotQueue() {
        User u = userWithAvatarAndAllGroupsOn();
        Map<String, Boolean> prefs = new HashMap<>(u.getNotifySlIm());
        prefs.put("bidding", false);
        u.setNotifySlIm(prefs);
        userRepo.save(u);

        NotificationEvent event = new NotificationEvent(
            u.getId(), NotificationCategory.OUTBID, "title", "body",
            Map.of("auctionId", 42), null);

        transactionTemplate.execute(status -> { dispatcher.maybeQueue(event); return null; });

        assertThat(repo.findAll()).isEmpty();
    }

    @Test
    void maybeQueue_noAvatar_doesNotQueue() {
        User u = userWithAvatarAndAllGroupsOn();
        u.setSlAvatarUuid(null);
        userRepo.save(u);

        NotificationEvent event = new NotificationEvent(
            u.getId(), NotificationCategory.OUTBID, "title", "body",
            Map.of("auctionId", 42), null);

        transactionTemplate.execute(status -> { dispatcher.maybeQueue(event); return null; });

        assertThat(repo.findAll()).isEmpty();
    }

    @Test
    void maybeQueue_systemBypassesPrefs_evenWhenMuted() {
        User u = userWithAvatarAndAllGroupsOn();
        u.setNotifySlImMuted(true);
        Map<String, Boolean> prefs = new HashMap<>(u.getNotifySlIm());
        prefs.put("system", false);
        u.setNotifySlIm(prefs);
        userRepo.save(u);

        NotificationEvent event = new NotificationEvent(
            u.getId(), NotificationCategory.SYSTEM_ANNOUNCEMENT, "Heads up", "body",
            Map.of(), null);

        transactionTemplate.execute(status -> { dispatcher.maybeQueue(event); return null; });

        assertThat(repo.findAll()).hasSize(1);
    }

    @Test
    void maybeQueue_repeatedSameKey_coalescesToSingleRow() {
        User u = userWithAvatarAndAllGroupsOn();
        String key = "outbid:" + u.getId() + ":42";

        for (int i = 1; i <= 5; i++) {
            int currentBidL = 1000 + i * 100;
            NotificationEvent event = new NotificationEvent(
                u.getId(), NotificationCategory.OUTBID,
                "You've been outbid", "Current bid is L$" + currentBidL,
                Map.of("auctionId", 42, "currentBidL", (long) currentBidL,
                    "isProxyOutbid", false, "parcelName", "Hampton",
                    "endsAt", "2026-04-26T18:00:00Z"),
                key);
            transactionTemplate.execute(status -> { dispatcher.maybeQueue(event); return null; });
        }

        List<SlImMessage> rows = repo.findAll();
        assertThat(rows).hasSize(1);
        // Latest body wins:
        assertThat(rows.get(0).getMessageText()).contains("Current bid is L$1500");
    }

    @Test
    void maybeQueueForFanout_propagatesExceptionForCaller() {
        User u = userWithAvatarAndAllGroupsOn();

        // Force a failure inside doQueue by passing data missing the key the
        // resolver needs. linkResolver.resolve(OUTBID, ...) reads data.get("auctionId")
        // and concatenates it; missing key produces "/auction/null" — not a failure.
        // Instead, force failure by passing a non-existent userId so userRepo.findById fails.
        long missingUserId = 999_999_999L;

        // Direct call (no requires_new wrapper) — caller is "already inside a REQUIRES_NEW".
        // For this test we just verify the exception propagates.
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            transactionTemplate.execute(status -> {
                dispatcher.maybeQueueForFanout(missingUserId, NotificationCategory.OUTBID,
                    "title", "body", Map.of("auctionId", 42), null);
                return null;
            }))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("user not found");
    }
}
```

- [ ] **Step 6: Run dispatcher tests — expect green**

Run: `cd backend && ./mvnw test -Dtest=SlImChannelDispatcherTest`
Expected: 7 tests pass.

- [ ] **Step 7: Write `OutbidImIntegrationTest`**

```java
package com.slparcelauctions.backend.notification.slim.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.bid.BidService;
import com.slparcelauctions.backend.notification.slim.SlImMessage;
import com.slparcelauctions.backend.notification.slim.SlImMessageRepository;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
// Add other imports needed by the tests' fixture setup — copy from
// BidPlacementIntegrationTest for the auction setup pattern.

@SpringBootTest
@TestPropertySource(properties = {
    "slpa.notifications.cleanup.enabled=false",
    "slpa.notifications.sl-im.cleanup.enabled=false",
    "slpa.escrow.scheduler.enabled=false",
    "slpa.escrow.timeout-job.enabled=false",
    "slpa.escrow.command-dispatcher-job.enabled=false",
    "slpa.escrow.ownership-monitor-job.enabled=false",
    "slpa.review.scheduler.enabled=false",
    "slpa.refresh-token-cleanup.enabled=false",
    "auth.cleanup.enabled=false",
    "slpa.auction-end.enabled=false",
    "slpa.ownership-monitor.enabled=false"
})
class OutbidImIntegrationTest {

    @Autowired BidService bidService;
    @Autowired SlImMessageRepository slImRepo;
    @Autowired UserRepository userRepo;
    @Autowired AuctionRepository auctionRepo;

    @BeforeEach
    void clean() {
        slImRepo.deleteAll();
    }

    @Test
    void outbid_publishesSlImForBidderWithAvatarAndPrefsOn() {
        User seller = saveUser(/* hasAvatar */ false, /* prefs */ true);
        User alice = saveUser(/* hasAvatar */ true, /* prefs */ true);
        User bob   = saveUser(/* hasAvatar */ true, /* prefs */ true);
        Auction a = saveAuction(seller, 1000L);

        bidService.placeBid(alice.getId(), a.getId(), 1500L);
        bidService.placeBid(bob.getId(),   a.getId(), 2000L);

        // Alice was displaced; she should have one PENDING SL IM row.
        List<SlImMessage> aliceRows = slImRepo.findAll().stream()
            .filter(m -> m.getUserId().equals(alice.getId()))
            .toList();
        assertThat(aliceRows).hasSize(1);
        assertThat(aliceRows.get(0).getMessageText())
            .contains("[SLPA] You've been outbid")
            .contains("L$2,000")
            .endsWith("/auction/" + a.getId());

        // Bob (the new high bidder) gets nothing on this side.
        long bobRows = slImRepo.findAll().stream()
            .filter(m -> m.getUserId().equals(bob.getId())).count();
        assertThat(bobRows).isZero();
    }

    @Test
    void outbidStorm_coalescesIntoSingleRow_latestTextWins() {
        User seller = saveUser(false, true);
        User alice = saveUser(true, true);
        User bob   = saveUser(true, true);
        Auction a = saveAuction(seller, 1000L);

        bidService.placeBid(alice.getId(), a.getId(), 1500L);
        bidService.placeBid(bob.getId(),   a.getId(), 2000L);
        bidService.placeBid(alice.getId(), a.getId(), 2500L);
        bidService.placeBid(bob.getId(),   a.getId(), 3000L);
        bidService.placeBid(alice.getId(), a.getId(), 3500L);
        bidService.placeBid(bob.getId(),   a.getId(), 4000L);

        // Alice was displaced 3 times. Coalesce key is the same each time.
        // Expect ONE PENDING row with the latest text.
        List<SlImMessage> aliceRows = slImRepo.findAll().stream()
            .filter(m -> m.getUserId().equals(alice.getId()))
            .toList();
        assertThat(aliceRows).hasSize(1);
        assertThat(aliceRows.get(0).getMessageText()).contains("L$4,000");
    }

    @Test
    void outbid_groupDisabled_skipsSlImButKeepsInAppRow() {
        User seller = saveUser(false, true);
        User alice = saveUser(true, /* prefs ON */ true);
        // Disable bidding group for alice:
        Map<String, Boolean> prefs = new HashMap<>(alice.getNotifySlIm());
        prefs.put("bidding", false);
        alice.setNotifySlIm(prefs);
        userRepo.save(alice);

        User bob = saveUser(true, true);
        Auction a = saveAuction(seller, 1000L);

        bidService.placeBid(alice.getId(), a.getId(), 1500L);
        bidService.placeBid(bob.getId(),   a.getId(), 2000L);

        // Alice has NO SL IM (group disabled), but she does have an in-app row
        // (covered by sub-spec 1's notification table; not asserted here).
        long aliceImRows = slImRepo.findAll().stream()
            .filter(m -> m.getUserId().equals(alice.getId())).count();
        assertThat(aliceImRows).isZero();
    }

    @Test
    void outbid_userMuted_skipsSlImButKeepsInAppRow() {
        User seller = saveUser(false, true);
        User alice = saveUser(true, true);
        alice.setNotifySlImMuted(true);
        userRepo.save(alice);

        User bob = saveUser(true, true);
        Auction a = saveAuction(seller, 1000L);

        bidService.placeBid(alice.getId(), a.getId(), 1500L);
        bidService.placeBid(bob.getId(),   a.getId(), 2000L);

        long aliceImRows = slImRepo.findAll().stream()
            .filter(m -> m.getUserId().equals(alice.getId())).count();
        assertThat(aliceImRows).isZero();
    }

    // --- Fixture helpers ---

    private User saveUser(boolean hasAvatar, boolean groupPrefsOn) {
        User u = User.builder()
            .email("u-" + UUID.randomUUID() + "@test.local")
            .passwordHash("hash")
            .build();
        if (hasAvatar) {
            u.setSlAvatarUuid(UUID.randomUUID().toString());
        }
        u.setNotifySlImMuted(false);
        Map<String, Boolean> prefs = new HashMap<>();
        prefs.put("bidding", groupPrefsOn);
        prefs.put("auction_result", groupPrefsOn);
        prefs.put("escrow", groupPrefsOn);
        prefs.put("listing_status", groupPrefsOn);
        prefs.put("reviews", groupPrefsOn);
        prefs.put("system", true);
        u.setNotifySlIm(prefs);
        return userRepo.save(u);
    }

    private Auction saveAuction(User seller, long startBidL) {
        // Copy this from BidPlacementIntegrationTest's fixture pattern. Adjust
        // for your actual Auction entity API. The auction must be in OPEN
        // state with a parcel + endsAt in the future.
        // (Implementer: read backend/src/test/java/com/slparcelauctions/backend/auction/BidPlacementIntegrationTest.java
        // and copy the exact fixture pattern that works in this repo.)
        throw new UnsupportedOperationException(
            "Implementer: copy Auction fixture from BidPlacementIntegrationTest.java");
    }
}
```

**Note for the implementer:** the `saveAuction(...)` helper at the bottom is intentionally left as a marker. Read `backend/src/test/java/com/slparcelauctions/backend/auction/BidPlacementIntegrationTest.java` (or whichever existing file successfully drives `bidService.placeBid` end-to-end) and copy its fixture pattern. The Auction setup is intricate (parcel, OPEN state, endsAt, currency precision); reusing the existing pattern is the right move.

- [ ] **Step 8: Run the OUTBID integration test — expect green**

Run: `cd backend && ./mvnw test -Dtest=OutbidImIntegrationTest`
Expected: 4 tests pass.

If `saveAuction` throws because the implementer skipped wiring it, fix it now by copying from the neighbor file.

- [ ] **Step 9: Write `EscrowImIntegrationTest`**

```java
package com.slparcelauctions.backend.notification.slim.integration;

import static org.assertj.core.api.Assertions.assertThat;

// imports...

@SpringBootTest
@TestPropertySource(properties = { /* canonical disable set, see Task 1 Step 7 */ })
class EscrowImIntegrationTest {

    @Autowired EscrowService escrowService;
    @Autowired SlImMessageRepository slImRepo;
    @Autowired UserRepository userRepo;
    @Autowired AuctionRepository auctionRepo;
    // ... other repos

    @BeforeEach
    void clean() { slImRepo.deleteAll(); }

    @Test
    void escrowFunded_queuesSellerOnly_notWinner() {
        User seller = saveUser(true);
        User winner = saveUser(true);
        Auction a = saveSoldAuction(seller, winner, 5000L);
        Escrow e = saveEscrow(a, /* status = AWAITING_PAYMENT */);

        // Drive: winner pays into escrow
        escrowService.markFunded(e.getId());

        // Seller gets the IM, winner does not.
        long sellerRows = slImRepo.findAll().stream()
            .filter(m -> m.getUserId().equals(seller.getId())).count();
        long winnerRows = slImRepo.findAll().stream()
            .filter(m -> m.getUserId().equals(winner.getId())).count();
        assertThat(sellerRows).isEqualTo(1L);
        assertThat(winnerRows).isZero();
    }

    @Test
    void transferConfirmed_queuesBothParties() {
        User seller = saveUser(true);
        User winner = saveUser(true);
        Auction a = saveSoldAuction(seller, winner, 5000L);
        Escrow e = saveEscrow(a, /* status = FUNDED */);

        escrowService.markTransferConfirmed(e.getId());

        long sellerRows = slImRepo.findAll().stream()
            .filter(m -> m.getUserId().equals(seller.getId())).count();
        long winnerRows = slImRepo.findAll().stream()
            .filter(m -> m.getUserId().equals(winner.getId())).count();
        assertThat(sellerRows).isEqualTo(1L);
        assertThat(winnerRows).isEqualTo(1L);
    }

    @Test
    void transferConfirmed_winnerMuted_onlySellerGetsIm() {
        User seller = saveUser(true);
        User winner = saveUser(true);
        winner.setNotifySlImMuted(true);
        userRepo.save(winner);

        Auction a = saveSoldAuction(seller, winner, 5000L);
        Escrow e = saveEscrow(a, /* status = FUNDED */);

        escrowService.markTransferConfirmed(e.getId());

        long sellerRows = slImRepo.findAll().stream()
            .filter(m -> m.getUserId().equals(seller.getId())).count();
        long winnerRows = slImRepo.findAll().stream()
            .filter(m -> m.getUserId().equals(winner.getId())).count();
        assertThat(sellerRows).isEqualTo(1L);
        assertThat(winnerRows).isZero();
    }

    @Test
    void disputed_queuesBothParties() {
        // Same shape — seller and winner both get an IM.
        // Drive: escrowService.dispute(...) per the actual API signature.
        // Assert: 2 IM rows, one per party.
    }

    @Test
    void payout_queuesSellerOnly() {
        // Drive: escrowService.markPayoutCompleted(...) (or equivalent).
        // Assert: seller has 1 IM, winner has 0.
    }

    // --- Fixture helpers (copy from EscrowEndToEndIntegrationTest or
    //     EscrowCreateOnAuctionEndIntegrationTest) ---
    private User saveUser(boolean hasAvatar) { /* ... */ throw new UnsupportedOperationException("copy pattern"); }
    private Auction saveSoldAuction(User seller, User winner, long winningBidL) { /* ... */ throw new UnsupportedOperationException("copy pattern"); }
    private Escrow saveEscrow(Auction a /*, status */) { /* ... */ throw new UnsupportedOperationException("copy pattern"); }
}
```

**Note for the implementer:** the helper bodies are markers. Read `EscrowEndToEndIntegrationTest.java` or `EscrowCreateOnAuctionEndIntegrationTest.java` and copy the working fixture pattern. The existing tests already drive escrows into specific states — reuse their setup verbatim.

- [ ] **Step 10: Run the escrow integration test — expect green**

Run: `cd backend && ./mvnw test -Dtest=EscrowImIntegrationTest`
Expected: 5 tests pass.

- [ ] **Step 11: Write `CancellationFanoutImIntegrationTest`**

```java
package com.slparcelauctions.backend.notification.slim.integration;

// imports...

@SpringBootTest
@TestPropertySource(properties = { /* canonical disable set */ })
class CancellationFanoutImIntegrationTest {

    @Autowired CancellationService cancellationService;
    @Autowired BidService bidService;
    @Autowired SlImMessageRepository slImRepo;
    @Autowired UserRepository userRepo;
    @Autowired AuctionRepository auctionRepo;

    @BeforeEach
    void clean() { slImRepo.deleteAll(); }

    @Test
    void cancel_threeActiveBidders_eachGetsOneSlImRow() {
        User seller = saveUser(false);  // seller doesn't need avatar
        User a = saveUser(true), b = saveUser(true), c = saveUser(true);
        Auction au = saveAuction(seller, 1000L);

        bidService.placeBid(a.getId(),  au.getId(), 1500L);
        bidService.placeBid(b.getId(),  au.getId(), 2000L);
        bidService.placeBid(c.getId(),  au.getId(), 2500L);
        slImRepo.deleteAll();  // clear OUTBID rows from the bid placements

        cancellationService.cancel(au.getId(), seller.getId(), "ownership lost");

        // 3 IM rows, one per active bidder, all LISTING_CANCELLED_BY_SELLER.
        var rows = slImRepo.findAll();
        assertThat(rows).hasSize(3);
        assertThat(rows.stream().map(m -> m.getUserId()).toList())
            .containsExactlyInAnyOrder(a.getId(), b.getId(), c.getId());
        assertThat(rows).allMatch(m -> m.getMessageText().contains("[SLPA] Listing cancelled"));
        assertThat(rows).allMatch(m -> m.getMessageText().contains("ownership lost"));
    }

    @Test
    void cancel_oneBidderListingStatusOff_thatBidderGetsNoSlIm() {
        User seller = saveUser(false);
        User a = saveUser(true);
        User b = saveUser(true);
        Map<String, Boolean> prefs = new HashMap<>(b.getNotifySlIm());
        prefs.put("listing_status", false);
        b.setNotifySlIm(prefs);
        userRepo.save(b);

        Auction au = saveAuction(seller, 1000L);
        bidService.placeBid(a.getId(), au.getId(), 1500L);
        bidService.placeBid(b.getId(), au.getId(), 2000L);
        slImRepo.deleteAll();

        cancellationService.cancel(au.getId(), seller.getId(), "ownership lost");

        var rows = slImRepo.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getUserId()).isEqualTo(a.getId());
    }

    @Test
    void cancel_emptyActiveBidders_zeroSlImRows() {
        User seller = saveUser(false);
        Auction au = saveAuction(seller, 1000L);

        cancellationService.cancel(au.getId(), seller.getId(), "no bidders");

        assertThat(slImRepo.findAll()).isEmpty();
    }

    // --- Fixture helpers — copy from CancellationServiceTest or similar ---
    private User saveUser(boolean hasAvatar) { /* ... */ throw new UnsupportedOperationException("copy pattern"); }
    private Auction saveAuction(User seller, long startBidL) { /* ... */ throw new UnsupportedOperationException("copy pattern"); }
}
```

- [ ] **Step 12: Run the fan-out integration test — expect green**

Run: `cd backend && ./mvnw test -Dtest=CancellationFanoutImIntegrationTest`
Expected: 3 tests pass.

- [ ] **Step 13: Write `SystemBypassImIntegrationTest`**

```java
package com.slparcelauctions.backend.notification.slim.integration;

import com.slparcelauctions.backend.notification.NotificationCategory;
import com.slparcelauctions.backend.notification.NotificationEvent;
import com.slparcelauctions.backend.notification.NotificationService;
// imports...

@SpringBootTest
@TestPropertySource(properties = { /* canonical disable set */ })
class SystemBypassImIntegrationTest {

    @Autowired NotificationService notificationService;
    @Autowired SlImMessageRepository slImRepo;
    @Autowired UserRepository userRepo;
    @Autowired TransactionTemplate tx;

    @BeforeEach
    void clean() { slImRepo.deleteAll(); }

    @Test
    void systemAnnouncement_mutedUserWithAvatar_getsIm() {
        User u = saveUserAllOff(true);  // muted, all groups off, has avatar
        u.setNotifySlImMuted(true);
        userRepo.save(u);

        publishSystem(u.getId(), "All systems normal", "Everything is fine.");

        var rows = slImRepo.findAll().stream()
            .filter(m -> m.getUserId().equals(u.getId())).toList();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getMessageText()).contains("[SLPA] All systems normal");
    }

    @Test
    void systemAnnouncement_mutedUserNoAvatar_skipsIm() {
        User u = saveUserAllOff(false);  // muted, all groups off, NO avatar
        u.setNotifySlImMuted(true);
        userRepo.save(u);

        publishSystem(u.getId(), "All systems normal", "Everything is fine.");

        assertThat(slImRepo.findAll().stream()
            .filter(m -> m.getUserId().equals(u.getId())).count()).isZero();
    }

    @Test
    void systemAnnouncement_groupKeyExplicitlyFalse_stillBypasses() {
        User u = saveUserAllOff(true);
        Map<String, Boolean> prefs = new HashMap<>(u.getNotifySlIm());
        prefs.put("system", false);  // explicitly false
        u.setNotifySlIm(prefs);
        userRepo.save(u);

        publishSystem(u.getId(), "All systems normal", "Everything is fine.");

        // SYSTEM bypasses prefs; the IM is queued.
        assertThat(slImRepo.findAll().stream()
            .filter(m -> m.getUserId().equals(u.getId())).count()).isEqualTo(1L);
    }

    private void publishSystem(long userId, String title, String body) {
        // Synthetic SYSTEM_ANNOUNCEMENT publish via NotificationService directly.
        // No publisher method exists for SYSTEM_ANNOUNCEMENT (Epic 10 trigger);
        // tests construct the event inline.
        NotificationEvent event = new NotificationEvent(
            userId, NotificationCategory.SYSTEM_ANNOUNCEMENT,
            title, body, Map.of(), /* coalesceKey */ null);
        tx.execute(status -> {
            notificationService.publish(event);
            return null;
        });
    }

    private User saveUserAllOff(boolean hasAvatar) {
        User u = User.builder()
            .email("u-" + UUID.randomUUID() + "@test.local")
            .passwordHash("hash").build();
        if (hasAvatar) u.setSlAvatarUuid(UUID.randomUUID().toString());
        u.setNotifySlImMuted(false);
        Map<String, Boolean> prefs = new HashMap<>();
        for (var g : List.of("bidding", "auction_result", "escrow",
                              "listing_status", "reviews", "system")) {
            prefs.put(g, false);
        }
        u.setNotifySlIm(prefs);
        return userRepo.save(u);
    }
}
```

- [ ] **Step 14: Run the system-bypass test — expect green**

Run: `cd backend && ./mvnw test -Dtest=SystemBypassImIntegrationTest`
Expected: 3 tests pass.

- [ ] **Step 15: Write `NoAvatarSkipImIntegrationTest`**

```java
package com.slparcelauctions.backend.notification.slim.integration;

// imports...

@SpringBootTest
@TestPropertySource(properties = { /* canonical disable set */ })
class NoAvatarSkipImIntegrationTest {

    @Autowired NotificationService notificationService;
    @Autowired SlImMessageRepository slImRepo;
    @Autowired UserRepository userRepo;
    @Autowired TransactionTemplate tx;

    @BeforeEach
    void clean() { slImRepo.deleteAll(); }

    @Test
    void noAvatar_publishingMultipleCategories_zeroSlImRows() {
        User u = saveUserAllOnButNoAvatar();

        publish(u.getId(), NotificationCategory.OUTBID, "out", "body",
            Map.of("auctionId", 42), "outbid:" + u.getId() + ":42");
        publish(u.getId(), NotificationCategory.AUCTION_WON, "won", "body",
            Map.of("auctionId", 42), null);
        publish(u.getId(), NotificationCategory.ESCROW_FUNDED, "funded", "body",
            Map.of("auctionId", 42, "escrowId", 100), null);

        // Three categories published; in-app rows exist (sub-spec 1 territory).
        // Zero SL IM rows because no avatar.
        var rows = slImRepo.findAll().stream()
            .filter(m -> m.getUserId().equals(u.getId())).toList();
        assertThat(rows).isEmpty();
    }

    private void publish(long userId, NotificationCategory category, String title,
                         String body, Map<String, Object> data, String coalesceKey) {
        tx.execute(status -> {
            notificationService.publish(new NotificationEvent(
                userId, category, title, body, data, coalesceKey));
            return null;
        });
    }

    private User saveUserAllOnButNoAvatar() {
        User u = User.builder()
            .email("u-" + UUID.randomUUID() + "@test.local")
            .passwordHash("hash").build();
        // slAvatarUuid intentionally null
        u.setNotifySlImMuted(false);
        Map<String, Boolean> prefs = new HashMap<>();
        for (var g : List.of("bidding", "auction_result", "escrow",
                              "listing_status", "reviews", "system")) {
            prefs.put(g, true);
        }
        u.setNotifySlIm(prefs);
        return userRepo.save(u);
    }
}
```

- [ ] **Step 16: Run the no-avatar test — expect green**

Run: `cd backend && ./mvnw test -Dtest=NoAvatarSkipImIntegrationTest`
Expected: 1 test passes.

- [ ] **Step 17: Run the full backend suite — no regressions**

Run: `cd backend && ./mvnw test`
Expected: previous baseline (Task 2) + ~20 new tests, 0 failures.

- [ ] **Step 18: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/notification/slim/SlImChannelDispatcher.java \
        backend/src/main/java/com/slparcelauctions/backend/notification/NotificationService.java \
        backend/src/main/java/com/slparcelauctions/backend/notification/NotificationPublisherImpl.java \
        backend/src/test/java/com/slparcelauctions/backend/notification/slim/SlImChannelDispatcherTest.java \
        backend/src/test/java/com/slparcelauctions/backend/notification/slim/integration/
git commit -m "feat(sl-im): channel dispatcher + integration with publish() and fan-out"
```

---

## Task 4 — Backend internal endpoints + terminal auth filter

**Goal:** Land the three internal endpoints (`GET /pending`, `POST /{id}/delivered`, `POST /{id}/failed`) with the symmetric state machines, the shared-secret auth filter, the configuration properties, and full controller test coverage of the state machine matrix.

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/notification/slim/internal/SlImInternalProperties.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/notification/slim/internal/SlImTerminalAuthFilter.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/notification/slim/internal/SlImInternalController.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/config/WebSecurityConfig.java` (register filter + permitAll for internal path)
- Modify: `backend/src/main/resources/application.yml` (shared secret + max batch limit)
- Test: `backend/src/test/java/com/slparcelauctions/backend/notification/slim/internal/SlImInternalControllerTest.java`

### Steps

- [ ] **Step 1: Create `SlImInternalProperties`**

```java
package com.slparcelauctions.backend.notification.slim.internal;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "slpa.notifications.sl-im.dispatcher")
@Validated
public record SlImInternalProperties(
    @NotBlank String sharedSecret,
    @Positive @Max(50) int maxBatchLimit
) {}
```

- [ ] **Step 2: Add the config block to application.yml**

```yaml
slpa:
  # ... existing ...
  notifications:
    sl-im:
      dispatcher:
        shared-secret: ${SL_IM_DISPATCHER_SECRET:dev-only-secret-do-not-use-in-prod}
        max-batch-limit: 50
```

- [ ] **Step 3: Create `SlImTerminalAuthFilter`**

```java
package com.slparcelauctions.backend.notification.slim.internal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Shared-secret auth filter for {@code /api/v1/internal/sl-im/**}. Constant-time
 * comparison via {@link MessageDigest#isEqual(byte[], byte[])} prevents secret
 * inference via response timing.
 */
@Component
@RequiredArgsConstructor
public class SlImTerminalAuthFilter extends OncePerRequestFilter {

    private final SlImInternalProperties props;
    private static final String PATH_PREFIX = "/api/v1/internal/sl-im/";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        return !req.getRequestURI().startsWith(PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
        throws IOException, ServletException {
        String header = req.getHeader("Authorization");
        String expected = "Bearer " + props.sharedSecret();
        boolean ok = header != null && MessageDigest.isEqual(
            header.getBytes(StandardCharsets.UTF_8),
            expected.getBytes(StandardCharsets.UTF_8));
        if (!ok) {
            res.setStatus(HttpStatus.UNAUTHORIZED.value());
            res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            res.getWriter().write(
                "{\"type\":\"about:blank\",\"title\":\"Unauthorized\",\"status\":401}");
            return;
        }
        chain.doFilter(req, res);
    }
}
```

- [ ] **Step 4: Register the filter in `WebSecurityConfig`**

Open `backend/src/main/java/com/slparcelauctions/backend/config/WebSecurityConfig.java`. Find the `SecurityFilterChain` bean. Modify it to:

1. Add `/api/v1/internal/sl-im/**` to the `permitAll()` matcher (so JWT processing doesn't reject the request — the shared-secret filter has already authenticated it).
2. Add `/api/v1/internal/sl-im/**` to the CSRF-disabled matcher (no browser origin).
3. Register `SlImTerminalAuthFilter` before `JwtAuthenticationFilter` (or whatever the existing JWT filter is named).

The exact code depends on your existing security config. Pattern:

```java
// In SecurityFilterChain bean:
http
    .csrf(csrf -> csrf
        .ignoringRequestMatchers("/api/v1/internal/**", /* ... existing ... */))
    .authorizeHttpRequests(auth -> auth
        .requestMatchers("/api/v1/internal/sl-im/**").permitAll()
        // ... existing matchers ...
    )
    .addFilterBefore(slImTerminalAuthFilter, JwtAuthenticationFilter.class);
```

Read the existing config file before editing to avoid disturbing the existing structure. Constructor-inject `SlImTerminalAuthFilter` into the config class.

- [ ] **Step 5: Create `SlImInternalController`**

```java
package com.slparcelauctions.backend.notification.slim.internal;

import com.slparcelauctions.backend.notification.slim.SlImMessage;
import com.slparcelauctions.backend.notification.slim.SlImMessageRepository;
import com.slparcelauctions.backend.notification.slim.SlImMessageStatus;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/internal/sl-im")
@RequiredArgsConstructor
public class SlImInternalController {

    private final SlImMessageRepository repo;
    private final JdbcTemplate jdbc;
    private final SlImInternalProperties props;

    public record PendingItem(long id, String avatarUuid, String messageText) {}
    public record PendingResponse(List<PendingItem> messages) {}

    @GetMapping("/pending")
    public PendingResponse pending(@RequestParam(defaultValue = "10") int limit) {
        if (limit > props.maxBatchLimit() || limit < 1) {
            throw new IllegalArgumentException(
                "limit must be in [1, " + props.maxBatchLimit() + "]");
        }
        // FOR UPDATE SKIP LOCKED in pollPending() is advisory: this method isn't
        // @Transactional, so the row lock releases when the query returns —
        // before the LSL script delivers. Real multi-dispatcher dedup would need
        // a PENDING → IN_PROGRESS → DELIVERED status transition.
        List<SlImMessage> rows = repo.pollPending(limit);
        List<PendingItem> items = rows.stream()
            .map(m -> new PendingItem(m.getId(), m.getAvatarUuid(), m.getMessageText()))
            .toList();
        return new PendingResponse(items);
    }

    @PostMapping("/{id}/delivered")
    public ResponseEntity<Void> delivered(@PathVariable long id) {
        return transition(id, SlImMessageStatus.DELIVERED);
    }

    @PostMapping("/{id}/failed")
    public ResponseEntity<Void> failed(@PathVariable long id) {
        return transition(id, SlImMessageStatus.FAILED);
    }

    /**
     * State machine for /delivered:
     *   PENDING   → DELIVERED (204, set delivered_at)
     *   DELIVERED → 204 no-op
     *   FAILED    → 409
     *   EXPIRED   → 409
     *   missing   → 404
     *
     * State machine for /failed:
     *   PENDING   → FAILED (204)
     *   FAILED    → 204 no-op
     *   DELIVERED → 409
     *   EXPIRED   → 409
     *   missing   → 404
     *
     * Implemented as a single conditional UPDATE returning affected rows count,
     * with a follow-up read on the no-op path to determine the response.
     */
    private ResponseEntity<Void> transition(long id, SlImMessageStatus target) {
        String sql = (target == SlImMessageStatus.DELIVERED)
            ? """
                UPDATE sl_im_message
                SET status = 'DELIVERED',
                    delivered_at = COALESCE(delivered_at, now()),
                    updated_at = now(),
                    attempts = attempts + 1
                WHERE id = ? AND status = 'PENDING'
                """
            : """
                UPDATE sl_im_message
                SET status = 'FAILED',
                    updated_at = now(),
                    attempts = attempts + 1
                WHERE id = ? AND status = 'PENDING'
                """;
        int updated = jdbc.update(sql, id);
        if (updated == 1) {
            return ResponseEntity.noContent().build();
        }

        Optional<SlImMessage> opt = repo.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        SlImMessageStatus current = opt.get().getStatus();
        if (current == target) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }
}
```

- [ ] **Step 6: Write `SlImInternalControllerTest`**

```java
package com.slparcelauctions.backend.notification.slim.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.slparcelauctions.backend.notification.slim.SlImMessage;
import com.slparcelauctions.backend.notification.slim.SlImMessageDao;
import com.slparcelauctions.backend.notification.slim.SlImMessageRepository;
import com.slparcelauctions.backend.notification.slim.SlImMessageStatus;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "slpa.notifications.cleanup.enabled=false",
    "slpa.notifications.sl-im.cleanup.enabled=false",
    "slpa.escrow.scheduler.enabled=false",
    "slpa.escrow.timeout-job.enabled=false",
    "slpa.escrow.command-dispatcher-job.enabled=false",
    "slpa.escrow.ownership-monitor-job.enabled=false",
    "slpa.review.scheduler.enabled=false",
    "slpa.refresh-token-cleanup.enabled=false",
    "auth.cleanup.enabled=false",
    "slpa.auction-end.enabled=false",
    "slpa.ownership-monitor.enabled=false",
    "slpa.notifications.sl-im.dispatcher.shared-secret=test-secret-12345",
    "slpa.notifications.sl-im.dispatcher.max-batch-limit=50"
})
class SlImInternalControllerTest {

    @Autowired MockMvc mvc;
    @Autowired SlImMessageDao dao;
    @Autowired SlImMessageRepository repo;
    @Autowired UserRepository userRepo;

    private static final String AUTH = "Bearer test-secret-12345";

    private User user;
    private String avatar;

    @BeforeEach
    void seed() {
        repo.deleteAll();
        user = userRepo.save(User.builder()
            .email("u-" + UUID.randomUUID() + "@test.local")
            .passwordHash("hash").build());
        avatar = UUID.randomUUID().toString();
    }

    // --- /pending ---

    @Test
    void pending_returnsBatchOldestFirst() throws Exception {
        var first = dao.upsert(user.getId(), avatar, "[SLPA] first", null);
        Thread.sleep(10);
        var second = dao.upsert(user.getId(), avatar, "[SLPA] second", null);

        mvc.perform(get("/api/v1/internal/sl-im/pending?limit=10").header("Authorization", AUTH))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.messages.length()").value(2))
            .andExpect(jsonPath("$.messages[0].id").value((int) first.id()))
            .andExpect(jsonPath("$.messages[1].id").value((int) second.id()))
            .andExpect(jsonPath("$.messages[0].messageText").value("[SLPA] first"))
            .andExpect(jsonPath("$.messages[0].avatarUuid").value(avatar));
    }

    @Test
    void pending_limitOverMax_returns400() throws Exception {
        mvc.perform(get("/api/v1/internal/sl-im/pending?limit=51").header("Authorization", AUTH))
            .andExpect(status().isBadRequest());
    }

    @Test
    void pending_limitBelowOne_returns400() throws Exception {
        mvc.perform(get("/api/v1/internal/sl-im/pending?limit=0").header("Authorization", AUTH))
            .andExpect(status().isBadRequest());
    }

    @Test
    void pending_missingAuth_returns401() throws Exception {
        mvc.perform(get("/api/v1/internal/sl-im/pending"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void pending_wrongAuth_returns401() throws Exception {
        mvc.perform(get("/api/v1/internal/sl-im/pending").header("Authorization", "Bearer wrong"))
            .andExpect(status().isUnauthorized());
    }

    // --- /delivered state machine ---

    @Test
    void delivered_pendingRow_transitionsTo204AndSetsDeliveredAt() throws Exception {
        var r = dao.upsert(user.getId(), avatar, "[SLPA] x", null);

        mvc.perform(post("/api/v1/internal/sl-im/" + r.id() + "/delivered").header("Authorization", AUTH))
            .andExpect(status().isNoContent());

        SlImMessage row = repo.findById(r.id()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(SlImMessageStatus.DELIVERED);
        assertThat(row.getDeliveredAt()).isNotNull();
        assertThat(row.getAttempts()).isEqualTo(1);
    }

    @Test
    void delivered_alreadyDelivered_idempotent204() throws Exception {
        var r = dao.upsert(user.getId(), avatar, "[SLPA] x", null);
        mvc.perform(post("/api/v1/internal/sl-im/" + r.id() + "/delivered").header("Authorization", AUTH))
            .andExpect(status().isNoContent());
        SlImMessage afterFirst = repo.findById(r.id()).orElseThrow();
        var deliveredAtBefore = afterFirst.getDeliveredAt();

        mvc.perform(post("/api/v1/internal/sl-im/" + r.id() + "/delivered").header("Authorization", AUTH))
            .andExpect(status().isNoContent());

        SlImMessage row = repo.findById(r.id()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(SlImMessageStatus.DELIVERED);
        // delivered_at NOT re-stamped on idempotent call:
        assertThat(row.getDeliveredAt()).isEqualTo(deliveredAtBefore);
        // attempts NOT incremented (the WHERE clause excluded DELIVERED):
        assertThat(row.getAttempts()).isEqualTo(1);
    }

    @Test
    void delivered_onFailedRow_returns409() throws Exception {
        var r = dao.upsert(user.getId(), avatar, "[SLPA] x", null);
        SlImMessage row = repo.findById(r.id()).orElseThrow();
        row.setStatus(SlImMessageStatus.FAILED);
        repo.save(row);

        mvc.perform(post("/api/v1/internal/sl-im/" + r.id() + "/delivered").header("Authorization", AUTH))
            .andExpect(status().isConflict());
    }

    @Test
    void delivered_onExpiredRow_returns409() throws Exception {
        var r = dao.upsert(user.getId(), avatar, "[SLPA] x", null);
        SlImMessage row = repo.findById(r.id()).orElseThrow();
        row.setStatus(SlImMessageStatus.EXPIRED);
        repo.save(row);

        mvc.perform(post("/api/v1/internal/sl-im/" + r.id() + "/delivered").header("Authorization", AUTH))
            .andExpect(status().isConflict());
    }

    @Test
    void delivered_missing_returns404() throws Exception {
        mvc.perform(post("/api/v1/internal/sl-im/999999/delivered").header("Authorization", AUTH))
            .andExpect(status().isNotFound());
    }

    @Test
    void delivered_unauthorized_returns401() throws Exception {
        var r = dao.upsert(user.getId(), avatar, "[SLPA] x", null);
        mvc.perform(post("/api/v1/internal/sl-im/" + r.id() + "/delivered"))
            .andExpect(status().isUnauthorized());
    }

    // --- /failed state machine ---

    @Test
    void failed_pendingRow_transitionsTo204() throws Exception {
        var r = dao.upsert(user.getId(), avatar, "[SLPA] x", null);

        mvc.perform(post("/api/v1/internal/sl-im/" + r.id() + "/failed").header("Authorization", AUTH))
            .andExpect(status().isNoContent());

        SlImMessage row = repo.findById(r.id()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(SlImMessageStatus.FAILED);
        assertThat(row.getDeliveredAt()).isNull();
        assertThat(row.getAttempts()).isEqualTo(1);
    }

    @Test
    void failed_alreadyFailed_idempotent204() throws Exception {
        var r = dao.upsert(user.getId(), avatar, "[SLPA] x", null);
        mvc.perform(post("/api/v1/internal/sl-im/" + r.id() + "/failed").header("Authorization", AUTH))
            .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/internal/sl-im/" + r.id() + "/failed").header("Authorization", AUTH))
            .andExpect(status().isNoContent());
    }

    @Test
    void failed_onDeliveredRow_returns409() throws Exception {
        var r = dao.upsert(user.getId(), avatar, "[SLPA] x", null);
        SlImMessage row = repo.findById(r.id()).orElseThrow();
        row.setStatus(SlImMessageStatus.DELIVERED);
        repo.save(row);

        mvc.perform(post("/api/v1/internal/sl-im/" + r.id() + "/failed").header("Authorization", AUTH))
            .andExpect(status().isConflict());
    }

    @Test
    void failed_onExpiredRow_returns409() throws Exception {
        var r = dao.upsert(user.getId(), avatar, "[SLPA] x", null);
        SlImMessage row = repo.findById(r.id()).orElseThrow();
        row.setStatus(SlImMessageStatus.EXPIRED);
        repo.save(row);

        mvc.perform(post("/api/v1/internal/sl-im/" + r.id() + "/failed").header("Authorization", AUTH))
            .andExpect(status().isConflict());
    }

    @Test
    void failed_missing_returns404() throws Exception {
        mvc.perform(post("/api/v1/internal/sl-im/999999/failed").header("Authorization", AUTH))
            .andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 7: Run controller tests — expect green**

Run: `cd backend && ./mvnw test -Dtest=SlImInternalControllerTest`
Expected: 16 tests pass.

- [ ] **Step 8: Run the full backend suite — no regressions**

Run: `cd backend && ./mvnw test`
Expected: previous (Task 3) baseline + 16 new tests, 0 failures.

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/notification/slim/internal/ \
        backend/src/main/java/com/slparcelauctions/backend/config/WebSecurityConfig.java \
        backend/src/main/resources/application.yml \
        backend/src/test/java/com/slparcelauctions/backend/notification/slim/internal/SlImInternalControllerTest.java
git commit -m "feat(sl-im): internal endpoints + shared-secret auth filter"
```

---

## Task 5 — Backend cleanup job + properties + test sweep

**Goal:** Land `SlImCleanupJob` running daily at 03:30 SLT (Spring zoned cron), with stage 1 PENDING→EXPIRED and stage 2 chunked DELETE for terminal-status rows past 30d retention. The INFO log captures `expired`, `deleted`, both cutoffs, and `top_users` (queried BEFORE the UPDATE). Sweep all existing `@SpringBootTest` files to add `slpa.notifications.sl-im.cleanup.enabled=false`.

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/notification/slim/SlImCleanupProperties.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/notification/slim/SlImCleanupJob.java`
- Modify: `backend/src/main/resources/application.yml` (cleanup config block)
- Modify: ALL existing `@SpringBootTest`+`@TestPropertySource` files (sweep — add the new disable property)
- Test: `backend/src/test/java/com/slparcelauctions/backend/notification/slim/SlImCleanupJobTest.java`

### Steps

- [ ] **Step 1: Create `SlImCleanupProperties`**

```java
package com.slparcelauctions.backend.notification.slim;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "slpa.notifications.sl-im.cleanup")
@Validated
public record SlImCleanupProperties(
    boolean enabled,
    @NotBlank String cron,
    @Positive int expiryAfterHours,
    @Positive int retentionAfterDays,
    @Positive int batchSize,
    @Min(0) @Max(20) int topUsersInLog
) {}
```

- [ ] **Step 2: Add the cleanup config block to application.yml**

```yaml
slpa:
  # ... existing ...
  notifications:
    # ... existing notifications.cleanup ...
    sl-im:
      # ... existing dispatcher block from Task 4 ...
      cleanup:
        enabled: true
        cron: "0 30 3 * * *"        # 03:30 daily, in zone below
        expiry-after-hours: 48
        retention-after-days: 30
        batch-size: 1000
        top-users-in-log: 10
```

- [ ] **Step 3: Create `SlImCleanupJob`**

```java
package com.slparcelauctions.backend.notification.slim;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "slpa.notifications.sl-im.cleanup",
                       name = "enabled", havingValue = "true", matchIfMissing = false)
@Slf4j
public class SlImCleanupJob {

    private final JdbcTemplate jdbc;
    private final SlImCleanupProperties properties;
    private final Clock clock;

    @Scheduled(cron = "${slpa.notifications.sl-im.cleanup.cron}",
               zone = "America/Los_Angeles")
    public void run() {
        OffsetDateTime expiryCutoff = OffsetDateTime.now(clock)
            .minusHours(properties.expiryAfterHours());
        OffsetDateTime retentionCutoff = OffsetDateTime.now(clock)
            .minusDays(properties.retentionAfterDays());

        // Capture top users BEFORE the UPDATE (otherwise the WHERE clause no
        // longer matches the rows that just transitioned).
        List<Map<String, Object>> topUsers = jdbc.queryForList("""
            SELECT user_id, COUNT(*) AS n
            FROM sl_im_message
            WHERE status = 'PENDING' AND created_at < ?
            GROUP BY user_id
            ORDER BY n DESC
            LIMIT ?
            """, expiryCutoff, properties.topUsersInLog());

        int expired = jdbc.update("""
            UPDATE sl_im_message
            SET status = 'EXPIRED', updated_at = now()
            WHERE status = 'PENDING' AND created_at < ?
            """, expiryCutoff);

        int totalDeleted = 0;
        int deletedThisChunk;
        do {
            deletedThisChunk = jdbc.update("""
                DELETE FROM sl_im_message
                WHERE id IN (
                    SELECT id FROM sl_im_message
                    WHERE status IN ('DELIVERED','EXPIRED','FAILED')
                      AND updated_at < ?
                    LIMIT ?
                )
                """, retentionCutoff, properties.batchSize());
            totalDeleted += deletedThisChunk;
        } while (deletedThisChunk == properties.batchSize());

        String topUsersStr = topUsers.stream()
            .map(r -> r.get("user_id") + ":" + r.get("n"))
            .collect(Collectors.joining(", "));
        log.info("SL IM cleanup sweep: expired={} deleted={} expiry_cutoff={} retention_cutoff={} top_users=[{}]",
            expired, totalDeleted, expiryCutoff, retentionCutoff, topUsersStr);
    }
}
```

- [ ] **Step 4: Sweep existing tests to add the new disable property**

Find every `@SpringBootTest` + `@TestPropertySource` test file:

Run: `cd backend && grep -lr "@TestPropertySource" src/test/java`

For EACH file in the output that uses `properties = { ... }`, open it and add `"slpa.notifications.sl-im.cleanup.enabled=false",` to the array. Do not remove or reorder existing properties — only add the new one.

This sweep is the toll for adding a new `@Scheduled` job. Sub-spec 1 went through the same exercise for `slpa.notifications.cleanup.enabled=false`. Same pattern.

After the sweep, run: `cd backend && ./mvnw test -Dtest='*Test' -DfailIfNoTests=false`
Expected: all tests still green.

- [ ] **Step 5: Write `SlImCleanupJobTest`**

```java
package com.slparcelauctions.backend.notification.slim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import nl.altindag.log.LogCaptor;  // verify the project's LogCaptor library + version
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
    "slpa.notifications.cleanup.enabled=false",
    "slpa.notifications.sl-im.cleanup.enabled=true",
    "slpa.notifications.sl-im.cleanup.cron=0 0 0 * * *",  // never fires during test
    "slpa.notifications.sl-im.cleanup.expiry-after-hours=48",
    "slpa.notifications.sl-im.cleanup.retention-after-days=30",
    "slpa.notifications.sl-im.cleanup.batch-size=1000",
    "slpa.notifications.sl-im.cleanup.top-users-in-log=10",
    "slpa.escrow.scheduler.enabled=false",
    "slpa.escrow.timeout-job.enabled=false",
    "slpa.escrow.command-dispatcher-job.enabled=false",
    "slpa.escrow.ownership-monitor-job.enabled=false",
    "slpa.review.scheduler.enabled=false",
    "slpa.refresh-token-cleanup.enabled=false",
    "auth.cleanup.enabled=false",
    "slpa.auction-end.enabled=false",
    "slpa.ownership-monitor.enabled=false"
})
class SlImCleanupJobTest {

    @Autowired SlImCleanupJob job;
    @Autowired SlImMessageDao dao;
    @Autowired SlImMessageRepository repo;
    @Autowired UserRepository userRepo;
    @Autowired JdbcTemplate jdbc;

    @MockitoBean Clock clock;

    @Test
    void run_expiresPendingOlderThan48h_keepsNewer_deletesTerminalOlderThan30d() {
        when(clock.instant()).thenReturn(Instant.parse("2026-04-26T08:30:00Z"));
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);

        User u = userRepo.save(User.builder()
            .email("u-" + UUID.randomUUID() + "@test.local")
            .passwordHash("hash").build());
        String avatar = UUID.randomUUID().toString();

        // 49h-old PENDING — should expire
        var stalePending = dao.upsert(u.getId(), avatar, "[SLPA] stale", "k1");
        forceCreatedAt(stalePending.id(), "2026-04-24T07:00:00Z");

        // 47h-old PENDING — should stay
        var freshPending = dao.upsert(u.getId(), avatar, "[SLPA] fresh", "k2");
        forceCreatedAt(freshPending.id(), "2026-04-24T10:00:00Z");

        // 31d-old DELIVERED — should be deleted
        var oldDelivered = dao.upsert(u.getId(), avatar, "[SLPA] old-d", "k3");
        forceStatusAndUpdatedAt(oldDelivered.id(), "DELIVERED", "2026-03-25T08:30:00Z");

        // 31d-old FAILED — should be deleted
        var oldFailed = dao.upsert(u.getId(), avatar, "[SLPA] old-f", "k4");
        forceStatusAndUpdatedAt(oldFailed.id(), "FAILED", "2026-03-25T08:30:00Z");

        // 31d-old EXPIRED — should be deleted
        var oldExpired = dao.upsert(u.getId(), avatar, "[SLPA] old-e", "k5");
        forceStatusAndUpdatedAt(oldExpired.id(), "EXPIRED", "2026-03-25T08:30:00Z");

        // 29d-old DELIVERED — should stay
        var newishDelivered = dao.upsert(u.getId(), avatar, "[SLPA] newish", "k6");
        forceStatusAndUpdatedAt(newishDelivered.id(), "DELIVERED", "2026-03-29T08:30:00Z");

        job.run();

        // stalePending → EXPIRED
        assertThat(repo.findById(stalePending.id()).orElseThrow().getStatus())
            .isEqualTo(SlImMessageStatus.EXPIRED);
        // freshPending → still PENDING
        assertThat(repo.findById(freshPending.id()).orElseThrow().getStatus())
            .isEqualTo(SlImMessageStatus.PENDING);
        // 31d terminal-status rows deleted
        assertThat(repo.findById(oldDelivered.id())).isEmpty();
        assertThat(repo.findById(oldFailed.id())).isEmpty();
        assertThat(repo.findById(oldExpired.id())).isEmpty();
        // 29d DELIVERED — still present
        assertThat(repo.findById(newishDelivered.id())).isPresent();
    }

    @Test
    void run_logsInfoLineWithExpectedFields() {
        when(clock.instant()).thenReturn(Instant.parse("2026-04-26T08:30:00Z"));
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);

        User u = userRepo.save(User.builder()
            .email("u-" + UUID.randomUUID() + "@test.local")
            .passwordHash("hash").build());
        String avatar = UUID.randomUUID().toString();

        var p = dao.upsert(u.getId(), avatar, "[SLPA] stale", null);
        forceCreatedAt(p.id(), "2026-04-24T07:00:00Z");

        try (LogCaptor captor = LogCaptor.forClass(SlImCleanupJob.class)) {
            job.run();

            assertThat(captor.getInfoLogs()).anyMatch(line ->
                line.contains("SL IM cleanup sweep")
                    && line.contains("expired=1")
                    && line.contains("deleted=")
                    && line.contains("expiry_cutoff=")
                    && line.contains("retention_cutoff=")
                    && line.contains("top_users=[" + u.getId() + ":1"));
        }
    }

    @Test
    void run_chunkedDelete_handlesLargeBacklog() {
        when(clock.instant()).thenReturn(Instant.parse("2026-04-26T08:30:00Z"));
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);

        User u = userRepo.save(User.builder()
            .email("u-" + UUID.randomUUID() + "@test.local")
            .passwordHash("hash").build());
        String avatar = UUID.randomUUID().toString();

        // Seed 25 deletable DELIVERED rows with batch_size = 10 (override via @TestPropertySource
        // would be cleanest; here we exercise the loop with the default batch).
        // Instead, with batch=1000 (default), assert the loop just runs once and clears all 25.
        for (int i = 0; i < 25; i++) {
            var r = dao.upsert(u.getId(), avatar, "[SLPA] msg-" + i, "k" + i);
            forceStatusAndUpdatedAt(r.id(), "DELIVERED", "2026-03-20T00:00:00Z");
        }

        job.run();

        // All 25 rows deleted (batch covers them).
        long remaining = repo.findAll().stream()
            .filter(m -> m.getUserId().equals(u.getId())).count();
        assertThat(remaining).isZero();
    }

    private void forceCreatedAt(long id, String iso) {
        jdbc.update("UPDATE sl_im_message SET created_at = ? WHERE id = ?",
            OffsetDateTime.parse(iso), id);
    }

    private void forceStatusAndUpdatedAt(long id, String status, String iso) {
        jdbc.update("UPDATE sl_im_message SET status = ?::varchar, updated_at = ? WHERE id = ?",
            status, OffsetDateTime.parse(iso), id);
    }
}
```

**Note:** verify `LogCaptor` is the library used by the existing tests. Search: `grep -r "LogCaptor" backend/src/test`. Match the import (`nl.altindag.log.LogCaptor` is the most common Java LogCaptor). If a different log-capture library is in use, swap to that.

- [ ] **Step 6: Run cleanup tests — expect green**

Run: `cd backend && ./mvnw test -Dtest=SlImCleanupJobTest`
Expected: 3 tests pass.

- [ ] **Step 7: Run the full backend suite — confirm sweep didn't break anything**

Run: `cd backend && ./mvnw test`
Expected: previous baseline (Task 4) + 3 new tests, 0 failures across all swept files.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/notification/slim/SlImCleanupJob.java \
        backend/src/main/java/com/slparcelauctions/backend/notification/slim/SlImCleanupProperties.java \
        backend/src/main/resources/application.yml \
        backend/src/test/java/com/slparcelauctions/backend/notification/slim/SlImCleanupJobTest.java \
        backend/src/test/java/com/slparcelauctions/backend/  # all swept test files
git commit -m "feat(sl-im): cleanup job + 30d retention + INFO canary log"
```

---

## Task 6 — Backend preferences controller

**Goal:** Land `GET`/`PUT /api/v1/users/me/notification-preferences` with closed-shape validation. Reject `system`, `realty_group`, and `marketing` keys with 400. Preserve non-visible JSONB keys on PUT (merge, don't replace).

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/notification/preferences/NotificationPreferencesController.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/notification/preferences/PreferencesDto.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/notification/preferences/NotificationPreferencesControllerTest.java`

### Steps

- [ ] **Step 1: Create `PreferencesDto`**

```java
package com.slparcelauctions.backend.notification.preferences;

import java.util.Map;

public record PreferencesDto(
    boolean slImMuted,
    Map<String, Boolean> slIm
) {}
```

- [ ] **Step 2: Create `NotificationPreferencesController`**

```java
package com.slparcelauctions.backend.notification.preferences;

import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.common.exception.BadRequestException;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users/me/notification-preferences")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class NotificationPreferencesController {

    private final UserRepository userRepo;

    /**
     * The closed set of group keys the UI can edit. SYSTEM is delivered
     * regardless; REALTY_GROUP and MARKETING have no shipping categories yet.
     * Server is the source of truth for what's user-mutable.
     */
    private static final Set<String> ALLOWED_GROUP_KEYS = Set.of(
        "bidding", "auction_result", "escrow", "listing_status", "reviews");

    @GetMapping
    public PreferencesDto get(@AuthenticationPrincipal AuthPrincipal caller) {
        User user = userRepo.findById(caller.userId()).orElseThrow();
        Map<String, Boolean> filtered = ALLOWED_GROUP_KEYS.stream()
            .collect(Collectors.toMap(
                k -> k,
                k -> user.getNotifySlIm() == null
                    ? false
                    : user.getNotifySlIm().getOrDefault(k, false)
            ));
        return new PreferencesDto(user.isNotifySlImMuted(), filtered);
    }

    @PutMapping
    @Transactional
    public PreferencesDto put(
        @AuthenticationPrincipal AuthPrincipal caller,
        @RequestBody PreferencesDto body
    ) {
        if (body.slIm() == null || !body.slIm().keySet().equals(ALLOWED_GROUP_KEYS)) {
            throw new BadRequestException(
                "slIm must contain exactly these keys: " + ALLOWED_GROUP_KEYS);
        }
        // Map<String,Boolean> via Jackson rejects non-boolean values at deserialization.

        User user = userRepo.findById(caller.userId()).orElseThrow();
        user.setNotifySlImMuted(body.slImMuted());

        // Merge into existing map: preserve keys we don't expose (system,
        // realty_group, marketing) at their existing/default values.
        Map<String, Boolean> merged = user.getNotifySlIm() == null
            ? new HashMap<>()
            : new HashMap<>(user.getNotifySlIm());
        merged.putAll(body.slIm());
        user.setNotifySlIm(merged);
        userRepo.save(user);

        return get(caller);
    }
}
```

If `BadRequestException` doesn't exist in `common.exception`, check what existing controllers use for 400-on-validation. Likely candidates: `IllegalArgumentException` (handled by `GlobalExceptionHandler` from sub-spec 1, Task 2), or a project-specific `BadRequestException`. Match the existing codebase pattern.

- [ ] **Step 3: Write `NotificationPreferencesControllerTest`**

```java
package com.slparcelauctions.backend.notification.preferences;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.auth.JwtService;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "slpa.notifications.cleanup.enabled=false",
    "slpa.notifications.sl-im.cleanup.enabled=false",
    "slpa.escrow.scheduler.enabled=false",
    "slpa.escrow.timeout-job.enabled=false",
    "slpa.escrow.command-dispatcher-job.enabled=false",
    "slpa.escrow.ownership-monitor-job.enabled=false",
    "slpa.review.scheduler.enabled=false",
    "slpa.refresh-token-cleanup.enabled=false",
    "auth.cleanup.enabled=false",
    "slpa.auction-end.enabled=false",
    "slpa.ownership-monitor.enabled=false"
})
class NotificationPreferencesControllerTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository userRepo;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper objectMapper;

    private User alice;
    private User bob;
    private String aliceJwt;
    private String bobJwt;

    @BeforeEach
    void seed() {
        alice = userRepo.save(User.builder()
            .email("alice-" + UUID.randomUUID() + "@test.local")
            .passwordHash("hash").build());
        bob = userRepo.save(User.builder()
            .email("bob-" + UUID.randomUUID() + "@test.local")
            .passwordHash("hash").build());

        aliceJwt = jwt.issueAccessToken(new AuthPrincipal(
            alice.getId(), alice.getEmail(), alice.getTokenVersion())).token();
        bobJwt = jwt.issueAccessToken(new AuthPrincipal(
            bob.getId(), bob.getEmail(), bob.getTokenVersion())).token();
    }

    @Test
    void get_returnsClosedShapeMap() throws Exception {
        mvc.perform(get("/api/v1/users/me/notification-preferences")
                .header("Authorization", "Bearer " + aliceJwt))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.slImMuted").value(false))
            .andExpect(jsonPath("$.slIm.bidding").exists())
            .andExpect(jsonPath("$.slIm.auction_result").exists())
            .andExpect(jsonPath("$.slIm.escrow").exists())
            .andExpect(jsonPath("$.slIm.listing_status").exists())
            .andExpect(jsonPath("$.slIm.reviews").exists())
            .andExpect(jsonPath("$.slIm.system").doesNotExist())
            .andExpect(jsonPath("$.slIm.realty_group").doesNotExist())
            .andExpect(jsonPath("$.slIm.marketing").doesNotExist());
    }

    @Test
    void put_happyPath_persistsAndReturnsNewState() throws Exception {
        Map<String, Object> body = Map.of(
            "slImMuted", false,
            "slIm", Map.of(
                "bidding", true,
                "auction_result", true,
                "escrow", true,
                "listing_status", false,
                "reviews", true
            )
        );

        mvc.perform(put("/api/v1/users/me/notification-preferences")
                .header("Authorization", "Bearer " + aliceJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.slIm.listing_status").value(false))
            .andExpect(jsonPath("$.slIm.reviews").value(true));

        // Persisted on User entity
        User updated = userRepo.findById(alice.getId()).orElseThrow();
        assertThat(updated.getNotifySlIm().get("listing_status")).isFalse();
        assertThat(updated.getNotifySlIm().get("reviews")).isTrue();
    }

    @Test
    void put_preservesUnexposedKeys() throws Exception {
        // Pre-set system=true on alice (matches default).
        Map<String, Boolean> initial = new HashMap<>(alice.getNotifySlIm());
        initial.put("system", true);
        initial.put("realty_group", false);
        alice.setNotifySlIm(initial);
        userRepo.save(alice);

        Map<String, Object> body = Map.of(
            "slImMuted", false,
            "slIm", Map.of(
                "bidding", false,
                "auction_result", true,
                "escrow", true,
                "listing_status", true,
                "reviews", true
            )
        );

        mvc.perform(put("/api/v1/users/me/notification-preferences")
                .header("Authorization", "Bearer " + aliceJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isOk());

        User updated = userRepo.findById(alice.getId()).orElseThrow();
        // Visible keys updated:
        assertThat(updated.getNotifySlIm().get("bidding")).isFalse();
        // Unexposed keys preserved:
        assertThat(updated.getNotifySlIm().get("system")).isTrue();
        assertThat(updated.getNotifySlIm().get("realty_group")).isFalse();
    }

    @Test
    void put_systemKey_returns400() throws Exception {
        Map<String, Object> body = Map.of(
            "slImMuted", false,
            "slIm", Map.of(
                "bidding", true,
                "auction_result", true,
                "escrow", true,
                "listing_status", true,
                "reviews", true,
                "system", false  // ← rejected
            )
        );

        mvc.perform(put("/api/v1/users/me/notification-preferences")
                .header("Authorization", "Bearer " + aliceJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void put_realtyGroupKey_returns400() throws Exception {
        Map<String, Object> body = Map.of(
            "slImMuted", false,
            "slIm", Map.of(
                "bidding", true,
                "auction_result", true,
                "escrow", true,
                "listing_status", true,
                "reviews", true,
                "realty_group", true
            )
        );

        mvc.perform(put("/api/v1/users/me/notification-preferences")
                .header("Authorization", "Bearer " + aliceJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void put_missingKey_returns400() throws Exception {
        Map<String, Object> body = Map.of(
            "slImMuted", false,
            "slIm", Map.of(
                "bidding", true,
                "auction_result", true,
                "escrow", true,
                "listing_status", true
                // missing "reviews"
            )
        );

        mvc.perform(put("/api/v1/users/me/notification-preferences")
                .header("Authorization", "Bearer " + aliceJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void put_nonBooleanValue_returns400() throws Exception {
        // Send raw JSON with a string where a boolean is expected.
        String rawBody = """
            {
              "slImMuted": false,
              "slIm": {
                "bidding": "true",
                "auction_result": true,
                "escrow": true,
                "listing_status": true,
                "reviews": true
              }
            }""";

        mvc.perform(put("/api/v1/users/me/notification-preferences")
                .header("Authorization", "Bearer " + aliceJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(rawBody))
            .andExpect(status().isBadRequest());
    }

    @Test
    void get_unauthenticated_returns401() throws Exception {
        mvc.perform(get("/api/v1/users/me/notification-preferences"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void put_unauthenticated_returns401() throws Exception {
        mvc.perform(put("/api/v1/users/me/notification-preferences")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void crossUser_aliceCannotAccessBobsPreferences() throws Exception {
        // Bob's PUT updates Bob's prefs, not Alice's. Same endpoint path; the
        // principal is the discriminator.
        Map<String, Object> body = Map.of(
            "slImMuted", true,
            "slIm", Map.of(
                "bidding", false, "auction_result", false,
                "escrow", false, "listing_status", false, "reviews", false
            )
        );

        mvc.perform(put("/api/v1/users/me/notification-preferences")
                .header("Authorization", "Bearer " + bobJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isOk());

        User aliceFresh = userRepo.findById(alice.getId()).orElseThrow();
        User bobFresh = userRepo.findById(bob.getId()).orElseThrow();
        assertThat(aliceFresh.isNotifySlImMuted()).isFalse();  // unchanged
        assertThat(bobFresh.isNotifySlImMuted()).isTrue();      // updated
    }
}
```

- [ ] **Step 4: Run preferences tests — expect green**

Run: `cd backend && ./mvnw test -Dtest=NotificationPreferencesControllerTest`
Expected: 10 tests pass.

- [ ] **Step 5: Run the full backend suite**

Run: `cd backend && ./mvnw test`
Expected: previous (Task 5) baseline + 10 new tests, 0 failures.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/notification/preferences/ \
        backend/src/test/java/com/slparcelauctions/backend/notification/preferences/
git commit -m "feat(sl-im): notification preferences controller + closed-shape PUT validation"
```

---

## Task 7 — Frontend foundations: types, queryKeys, REST client, hooks, MSW

**Goal:** Land non-UI frontend infrastructure: TypeScript types matching the backend DTO, queryKey extension, the REST client for prefs, two hooks (`useNotificationPreferences`, `useUpdateNotificationPreferences` with optimistic + rollback), MSW handlers + fixtures.

**Files:**
- Create: `frontend/src/lib/notifications/preferencesTypes.ts`
- Create: `frontend/src/lib/notifications/preferencesApi.ts`
- Modify: `frontend/src/lib/notifications/queryKeys.ts` (extend with `preferences()`)
- Create: `frontend/src/hooks/notifications/useNotificationPreferences.ts`
- Create: `frontend/src/hooks/notifications/useUpdateNotificationPreferences.ts`
- Modify: `frontend/src/test/msw/handlers.ts` (add prefs handlers + helpers)
- Modify: `frontend/src/test/msw/fixtures.ts` (add default prefs fixture)
- Test: `frontend/src/hooks/notifications/useNotificationPreferences.test.tsx`
- Test: `frontend/src/hooks/notifications/useUpdateNotificationPreferences.test.tsx`

### Steps

- [ ] **Step 1: Create `lib/notifications/preferencesTypes.ts`**

```ts
import type { NotificationGroup } from "./types";

// Closed shape: exactly the user-mutable groups. SYSTEM/REALTY_GROUP/MARKETING
// are excluded by server contract — sending them returns 400.
export type EditableGroup =
  | "bidding" | "auction_result" | "escrow" | "listing_status" | "reviews";

export interface PreferencesDto {
  slImMuted: boolean;
  slIm: Record<EditableGroup, boolean>;
}

export const EDITABLE_GROUPS: EditableGroup[] = [
  "bidding", "auction_result", "escrow", "listing_status", "reviews",
];

export const GROUP_LABELS: Record<EditableGroup, string> = {
  bidding: "Bidding",
  auction_result: "Auction Result",
  escrow: "Escrow",
  listing_status: "Listings",
  reviews: "Reviews",
};

export const GROUP_SUBTEXT: Record<EditableGroup, string> = {
  bidding: "Outbid, proxy exhausted",
  auction_result: "Won, lost, ended (sold/reserve/no-bids/buy-now)",
  escrow: "Funded, transfer confirmed, payout, expired, disputed, frozen, payout stalled",
  listing_status: "Verified, suspended, review required, cancelled by seller",
  reviews: "New review received",
};
```

- [ ] **Step 2: Extend `lib/notifications/queryKeys.ts`**

Open the existing file from sub-spec 1's Task 6. Add:

```ts
export const notificationKeys = {
  // ... existing entries ...
  preferences: () => [...notificationKeys.all, "preferences"] as const,
};
```

- [ ] **Step 3: Create `lib/notifications/preferencesApi.ts`**

```ts
import { api } from "@/lib/api";
import type { PreferencesDto } from "./preferencesTypes";

export async function getNotificationPreferences(): Promise<PreferencesDto> {
  return api.get<PreferencesDto>("/api/v1/users/me/notification-preferences");
}

export async function putNotificationPreferences(
  body: PreferencesDto
): Promise<PreferencesDto> {
  return api.put<PreferencesDto>("/api/v1/users/me/notification-preferences", body);
}
```

- [ ] **Step 4: Create `hooks/notifications/useNotificationPreferences.ts`**

```ts
import { useQuery } from "@tanstack/react-query";
import { getNotificationPreferences } from "@/lib/notifications/preferencesApi";
import { notificationKeys } from "@/lib/notifications/queryKeys";

export function useNotificationPreferences() {
  return useQuery({
    queryKey: notificationKeys.preferences(),
    queryFn: () => getNotificationPreferences(),
    staleTime: 60_000,
  });
}
```

- [ ] **Step 5: Create `hooks/notifications/useUpdateNotificationPreferences.ts`**

```ts
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { putNotificationPreferences } from "@/lib/notifications/preferencesApi";
import { notificationKeys } from "@/lib/notifications/queryKeys";
import type { PreferencesDto } from "@/lib/notifications/preferencesTypes";
import { useToast } from "@/components/ui/Toast";

export function useUpdateNotificationPreferences() {
  const qc = useQueryClient();
  const toast = useToast();
  return useMutation({
    mutationFn: (payload: PreferencesDto) => putNotificationPreferences(payload),
    onMutate: async (payload) => {
      await qc.cancelQueries({ queryKey: notificationKeys.preferences() });
      const prev = qc.getQueryData<PreferencesDto>(notificationKeys.preferences());
      qc.setQueryData(notificationKeys.preferences(), payload);
      return { prev };
    },
    onError: (_err, _vars, ctx) => {
      if (ctx?.prev) {
        qc.setQueryData(notificationKeys.preferences(), ctx.prev);
      }
      toast.push("error", "Couldn't save preferences");
    },
    onSettled: () => {
      qc.invalidateQueries({ queryKey: notificationKeys.preferences() });
      qc.invalidateQueries({ queryKey: ["currentUser"] });
    },
  });
}
```

- [ ] **Step 6: Extend MSW handlers**

Open `frontend/src/test/msw/handlers.ts`. Add:

```ts
import type { PreferencesDto, EditableGroup } from "@/lib/notifications/preferencesTypes";

const DEFAULT_PREFERENCES: PreferencesDto = {
  slImMuted: false,
  slIm: {
    bidding: true,
    auction_result: true,
    escrow: true,
    listing_status: true,
    reviews: false,
  },
};

let currentPreferences: PreferencesDto = { ...DEFAULT_PREFERENCES };
const ALLOWED: Set<string> = new Set(
  ["bidding", "auction_result", "escrow", "listing_status", "reviews"]);

export const preferencesHandlers = [
  http.get("/api/v1/users/me/notification-preferences", () => {
    return HttpResponse.json(currentPreferences);
  }),
  http.put("/api/v1/users/me/notification-preferences", async ({ request }) => {
    const body = (await request.json()) as PreferencesDto;
    if (!body.slIm) {
      return new HttpResponse(null, { status: 400 });
    }
    const keys = new Set(Object.keys(body.slIm));
    if (keys.size !== ALLOWED.size || ![...keys].every((k) => ALLOWED.has(k))) {
      return new HttpResponse(null, { status: 400 });
    }
    for (const v of Object.values(body.slIm)) {
      if (typeof v !== "boolean") {
        return new HttpResponse(null, { status: 400 });
      }
    }
    if (typeof body.slImMuted !== "boolean") {
      return new HttpResponse(null, { status: 400 });
    }
    currentPreferences = body;
    return HttpResponse.json(currentPreferences);
  }),
];

export function seedPreferences(p: PreferencesDto) {
  currentPreferences = { ...p };
}

export function resetPreferences() {
  currentPreferences = { ...DEFAULT_PREFERENCES };
}
```

Then ensure `preferencesHandlers` is included in the array exported to `setupServer(...)` in `frontend/src/test/msw/server.ts` (or wherever the handlers are wired). If the existing module exports a single combined `handlers` array, append `...preferencesHandlers` to it.

- [ ] **Step 7: Extend MSW fixtures**

Open `frontend/src/test/msw/fixtures.ts`. Add the default user fixture with the new prefs:

```ts
// (After the existing currentUser fixture export, ensure unreadNotificationCount
// is already present from sub-spec 1; no further changes needed here. The prefs
// live in their own handler state, not on the user fixture.)
```

If the user fixture is reused for `/me` responses and prefs are accessed elsewhere in tests, verify the integration. The prefs come from their own endpoint, not from `/me`, so no fixture change is strictly required.

- [ ] **Step 8: Write `useNotificationPreferences.test.tsx`**

```tsx
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { describe, expect, test, beforeEach } from "vitest";
import { useNotificationPreferences } from "./useNotificationPreferences";
import { resetPreferences, seedPreferences } from "@/test/msw/handlers";
import type { ReactNode } from "react";

function wrapper({ children }: { children: ReactNode }) {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return <QueryClientProvider client={qc}>{children}</QueryClientProvider>;
}

describe("useNotificationPreferences", () => {
  beforeEach(() => {
    resetPreferences();
  });

  test("fetches preferences from the GET endpoint", async () => {
    seedPreferences({
      slImMuted: false,
      slIm: {
        bidding: true, auction_result: true, escrow: true,
        listing_status: false, reviews: false,
      },
    });

    const { result } = renderHook(() => useNotificationPreferences(), { wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(result.current.data).toEqual({
      slImMuted: false,
      slIm: {
        bidding: true, auction_result: true, escrow: true,
        listing_status: false, reviews: false,
      },
    });
  });

  test("returns reviews=false by default", async () => {
    const { result } = renderHook(() => useNotificationPreferences(), { wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.slIm.reviews).toBe(false);
  });
});
```

- [ ] **Step 9: Write `useUpdateNotificationPreferences.test.tsx`**

```tsx
import { renderHook, waitFor, act } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { describe, expect, test, beforeEach, vi } from "vitest";
import { http, HttpResponse } from "msw";
import { server } from "@/test/msw/server";
import { useUpdateNotificationPreferences } from "./useUpdateNotificationPreferences";
import { useNotificationPreferences } from "./useNotificationPreferences";
import { resetPreferences, seedPreferences } from "@/test/msw/handlers";
import type { ReactNode } from "react";

function makeWrapper() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={qc}>{children}</QueryClientProvider>;
  }
  return { qc, Wrapper };
}

describe("useUpdateNotificationPreferences", () => {
  beforeEach(() => {
    resetPreferences();
  });

  test("optimistically updates the cache before server confirms", async () => {
    seedPreferences({
      slImMuted: false,
      slIm: { bidding: true, auction_result: true, escrow: true,
              listing_status: true, reviews: false },
    });
    const { qc, Wrapper } = makeWrapper();

    const { result: query } = renderHook(() => useNotificationPreferences(), {
      wrapper: Wrapper,
    });
    await waitFor(() => expect(query.current.isSuccess).toBe(true));

    const { result: mut } = renderHook(() => useUpdateNotificationPreferences(), {
      wrapper: Wrapper,
    });

    act(() => {
      mut.current.mutate({
        slImMuted: false,
        slIm: { bidding: false, auction_result: true, escrow: true,
                listing_status: true, reviews: false },
      });
    });

    // Cache should reflect optimistic value immediately, before the request settles.
    await waitFor(() => {
      const cached = qc.getQueryData(["notifications", "preferences"]) as any;
      expect(cached.slIm.bidding).toBe(false);
    });
  });

  test("reverts cache + shows toast on server 4xx", async () => {
    seedPreferences({
      slImMuted: false,
      slIm: { bidding: true, auction_result: true, escrow: true,
              listing_status: true, reviews: false },
    });

    // Override PUT to 400 for this test
    server.use(
      http.put("/api/v1/users/me/notification-preferences", () =>
        new HttpResponse(null, { status: 400 }))
    );

    const { qc, Wrapper } = makeWrapper();
    const { result: query } = renderHook(() => useNotificationPreferences(), {
      wrapper: Wrapper,
    });
    await waitFor(() => expect(query.current.isSuccess).toBe(true));

    const { result: mut } = renderHook(() => useUpdateNotificationPreferences(), {
      wrapper: Wrapper,
    });

    act(() => {
      mut.current.mutate({
        slImMuted: false,
        slIm: { bidding: false, auction_result: true, escrow: true,
                listing_status: true, reviews: false },
      });
    });

    await waitFor(() => expect(mut.current.isError).toBe(true));

    // Cache reverted to prior value
    const cached = qc.getQueryData(["notifications", "preferences"]) as any;
    expect(cached.slIm.bidding).toBe(true);
  });
});
```

If `server.use(...)` isn't the existing pattern, copy whatever override mechanism the project uses for per-test handler overrides.

- [ ] **Step 10: Run frontend tests for the new hooks**

Run: `cd frontend && npm test -- --run hooks/notifications/useNotificationPreferences hooks/notifications/useUpdateNotificationPreferences`
Expected: 4 tests pass.

- [ ] **Step 11: Run full frontend suite**

Run: `cd frontend && npm test -- --run && npm run lint`
Expected: previous baseline + 4 new tests, 0 failures, lint clean.

- [ ] **Step 12: Commit**

```bash
git add frontend/src/lib/notifications/preferencesTypes.ts \
        frontend/src/lib/notifications/preferencesApi.ts \
        frontend/src/lib/notifications/queryKeys.ts \
        frontend/src/hooks/notifications/useNotificationPreferences.ts \
        frontend/src/hooks/notifications/useUpdateNotificationPreferences.ts \
        frontend/src/hooks/notifications/useNotificationPreferences.test.tsx \
        frontend/src/hooks/notifications/useUpdateNotificationPreferences.test.tsx \
        frontend/src/test/msw/handlers.ts \
        frontend/src/test/msw/fixtures.ts
git commit -m "feat(sl-im): preferences types + REST client + hooks + MSW handlers"
```

---

## Task 8 — Frontend preferences UI + settings entry points

**Goal:** Land the `/settings` route tree (layout + index redirect + `/settings/notifications`), the `NotificationPreferencesPage` with banner, master mute, and 5 group toggle rows, plus settings entry points from the bell dropdown footer and the `/notifications` feed-page header.

**Files:**
- Create: `frontend/src/app/settings/layout.tsx`
- Create: `frontend/src/app/settings/page.tsx` (redirect)
- Create: `frontend/src/app/settings/notifications/page.tsx`
- Create: `frontend/src/app/settings/notifications/page.test.tsx`
- Create: `frontend/src/components/notifications/preferences/NotificationPreferencesPage.tsx`
- Create: `frontend/src/components/notifications/preferences/ChannelInfoBanner.tsx`
- Create: `frontend/src/components/notifications/preferences/MasterMuteRow.tsx`
- Create: `frontend/src/components/notifications/preferences/GroupToggleRow.tsx`
- Create: `frontend/src/components/notifications/preferences/*.test.tsx`
- Modify: `frontend/src/components/notifications/NotificationDropdown.tsx` (add gear icon to footer)
- Modify: `frontend/src/components/notifications/feed/FeedShell.tsx` (add gear icon to feed page header)
- Modify: `frontend/src/components/ui/icons.ts` (export `Settings` if not present)

### Steps

- [ ] **Step 1: Create `/settings/layout.tsx`**

```tsx
import type { ReactNode } from "react";

export default function SettingsLayout({ children }: { children: ReactNode }) {
  return (
    <div className="mx-auto max-w-4xl px-4 py-8">
      <h1 className="text-headline-md font-display font-bold mb-6">Settings</h1>
      {children}
    </div>
  );
}
```

- [ ] **Step 2: Create `/settings/page.tsx` (server-component redirect)**

```tsx
import { redirect } from "next/navigation";

export default function SettingsIndexPage() {
  redirect("/settings/notifications");
}
```

- [ ] **Step 3: Verify Settings icon exists in barrel**

Run: `grep -E "Settings|SlidersHorizontal" frontend/src/components/ui/icons.ts`

If neither is exported, add to the barrel:

```ts
// frontend/src/components/ui/icons.ts — append
export { Settings } from "lucide-react";
```

- [ ] **Step 4: Create `ChannelInfoBanner.tsx`**

```tsx
"use client";

export function ChannelInfoBanner() {
  return (
    <div className="bg-primary-container/30 border border-primary/20 rounded-lg p-4 mb-6 text-body-sm text-on-surface">
      <p>
        In-app and system notifications always deliver. Settings below control
        SL IM for the rest. SL natively forwards in-game IMs to your registered
        email when you're offline.
      </p>
    </div>
  );
}
```

- [ ] **Step 5: Create `MasterMuteRow.tsx`**

```tsx
"use client";

export interface MasterMuteRowProps {
  value: boolean;
  onChange: (next: boolean) => void;
}

export function MasterMuteRow({ value, onChange }: MasterMuteRowProps) {
  return (
    <div className="flex items-center justify-between py-4 border-b border-outline-variant mb-4">
      <div>
        <div className="text-title-sm font-semibold text-on-surface">
          Mute all SL IM notifications
        </div>
        <div className="text-body-sm text-on-surface-variant mt-1">
          Master switch — overrides everything below.
        </div>
      </div>
      <button
        type="button"
        role="switch"
        aria-checked={value}
        onClick={() => onChange(!value)}
        className={
          "relative inline-flex h-6 w-11 items-center rounded-full transition-colors " +
          (value ? "bg-primary" : "bg-surface-container-high border border-outline")
        }
        aria-label="Mute all SL IM notifications"
      >
        <span
          className={
            "inline-block h-4 w-4 transform rounded-full bg-on-primary transition-transform " +
            (value ? "translate-x-6" : "translate-x-1")
          }
        />
      </button>
    </div>
  );
}
```

- [ ] **Step 6: Create `GroupToggleRow.tsx`** — with the disabled-state preservation invariant

```tsx
"use client";
import { cn } from "@/lib/cn";

export interface GroupToggleRowProps {
  group: string;
  label: string;
  subtext: string;
  value: boolean;
  mutedDisabled: boolean;
  onChange: (next: boolean) => void;
}

export function GroupToggleRow({
  group, label, subtext, value, mutedDisabled, onChange
}: GroupToggleRowProps) {
  const handleClick = () => {
    if (mutedDisabled) return;  // no-op when muted; preserve underlying state
    onChange(!value);
  };

  return (
    <div className="flex items-center justify-between py-4 border-b border-outline-variant last:border-0">
      <div className="flex-1 mr-4">
        <div className="text-body-md font-medium text-on-surface">{label}</div>
        <div className="text-body-sm text-on-surface-variant mt-0.5">{subtext}</div>
      </div>
      <button
        type="button"
        role="switch"
        aria-checked={value}
        disabled={mutedDisabled}
        onClick={handleClick}
        className={cn(
          "relative inline-flex h-6 w-11 items-center rounded-full transition-colors",
          value ? "bg-primary" : "bg-surface-container-high border border-outline",
          mutedDisabled && "opacity-50 cursor-not-allowed"
        )}
        aria-label={`${label} — ${value ? "on" : "off"}${mutedDisabled ? " (muted)" : ""}`}
        data-testid={`group-toggle-${group}`}
      >
        <span
          className={cn(
            "inline-block h-4 w-4 transform rounded-full bg-on-primary transition-transform",
            value ? "translate-x-6" : "translate-x-1"
          )}
        />
      </button>
    </div>
  );
}
```

- [ ] **Step 7: Create `NotificationPreferencesPage.tsx`**

```tsx
"use client";
import { useState, useEffect } from "react";
import { useNotificationPreferences } from "@/hooks/notifications/useNotificationPreferences";
import { useUpdateNotificationPreferences } from "@/hooks/notifications/useUpdateNotificationPreferences";
import { ChannelInfoBanner } from "./ChannelInfoBanner";
import { MasterMuteRow } from "./MasterMuteRow";
import { GroupToggleRow } from "./GroupToggleRow";
import {
  EDITABLE_GROUPS, GROUP_LABELS, GROUP_SUBTEXT,
  type EditableGroup, type PreferencesDto,
} from "@/lib/notifications/preferencesTypes";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";

export function NotificationPreferencesPage() {
  const { data, isPending } = useNotificationPreferences();
  const update = useUpdateNotificationPreferences();

  // Local state mirrors server state for immediate UI feedback. Master mute
  // does NOT mutate per-group state — disabled is purely presentational.
  const [local, setLocal] = useState<PreferencesDto | null>(null);

  useEffect(() => {
    if (data) setLocal(data);
  }, [data]);

  if (isPending || !local) {
    return <div className="flex justify-center p-8"><LoadingSpinner /></div>;
  }

  const handleMasterMute = (next: boolean) => {
    const updated = { ...local, slImMuted: next };
    setLocal(updated);
    update.mutate(updated);
  };

  const handleGroupToggle = (group: EditableGroup, next: boolean) => {
    const updated = {
      ...local,
      slIm: { ...local.slIm, [group]: next },
    };
    setLocal(updated);
    update.mutate(updated);
  };

  return (
    <div>
      <ChannelInfoBanner />

      <MasterMuteRow value={local.slImMuted} onChange={handleMasterMute} />

      <div className="mb-2 text-label-sm uppercase tracking-wide text-on-surface-variant font-semibold">
        Send via SL IM
      </div>

      <div className="bg-surface border border-outline rounded-xl">
        {EDITABLE_GROUPS.map((g) => (
          <GroupToggleRow
            key={g}
            group={g}
            label={GROUP_LABELS[g]}
            subtext={GROUP_SUBTEXT[g]}
            value={local.slIm[g]}
            mutedDisabled={local.slImMuted}
            onChange={(next) => handleGroupToggle(g, next)}
          />
        ))}
      </div>
    </div>
  );
}
```

- [ ] **Step 8: Create `/settings/notifications/page.tsx`**

```tsx
import { RequireAuth } from "@/components/auth/RequireAuth";
import { NotificationPreferencesPage } from "@/components/notifications/preferences/NotificationPreferencesPage";

export default function NotificationSettingsPage() {
  return (
    <RequireAuth>
      <NotificationPreferencesPage />
    </RequireAuth>
  );
}
```

If the project uses a different auth-gate pattern (e.g., layout-level), match that instead.

- [ ] **Step 9: Add gear icon to bell dropdown footer**

Open `frontend/src/components/notifications/NotificationDropdown.tsx`. Find the existing footer with the "View all notifications" link. Modify:

```tsx
import { Settings } from "@/components/ui/icons";

// In the footer section:
<footer className="border-t border-outline-variant px-4 py-2 flex items-center justify-between bg-surface-container">
  <Link
    href="/notifications"
    onClick={onClose}
    className="text-label-md text-primary hover:underline"
  >
    View all notifications
  </Link>
  <Link
    href="/settings/notifications"
    onClick={onClose}
    className="text-on-surface-variant hover:text-on-surface"
    aria-label="Notification settings"
  >
    <Settings className="size-4" />
  </Link>
</footer>
```

The exact JSX changes depend on the existing footer structure. Read it first.

- [ ] **Step 10: Add gear icon to `/notifications` page header**

Open `frontend/src/components/notifications/feed/FeedShell.tsx`. Find the existing page title/header. Add a gear icon top-right linking to `/settings/notifications`:

```tsx
import { Settings } from "@/components/ui/icons";
import Link from "next/link";

// At the top of the page (replace the existing <h1>...</h1> block):
<div className="flex items-center justify-between mb-2">
  <h1 className="text-headline-md font-display font-bold">Notifications</h1>
  <Link
    href="/settings/notifications"
    className="p-2 text-on-surface-variant hover:text-on-surface rounded-md hover:bg-surface-container"
    aria-label="Notification settings"
  >
    <Settings className="size-5" />
  </Link>
</div>
```

- [ ] **Step 11: Write `MasterMuteRow.test.tsx`**

```tsx
import { render, screen, fireEvent } from "@testing-library/react";
import { describe, expect, test, vi } from "vitest";
import { MasterMuteRow } from "./MasterMuteRow";

describe("MasterMuteRow", () => {
  test("renders aria-checked reflecting value=false", () => {
    render(<MasterMuteRow value={false} onChange={() => {}} />);
    expect(screen.getByRole("switch")).toHaveAttribute("aria-checked", "false");
  });

  test("renders aria-checked reflecting value=true", () => {
    render(<MasterMuteRow value={true} onChange={() => {}} />);
    expect(screen.getByRole("switch")).toHaveAttribute("aria-checked", "true");
  });

  test("calls onChange with the inverted value on click", () => {
    const onChange = vi.fn();
    render(<MasterMuteRow value={false} onChange={onChange} />);
    fireEvent.click(screen.getByRole("switch"));
    expect(onChange).toHaveBeenCalledWith(true);
  });
});
```

- [ ] **Step 12: Write `GroupToggleRow.test.tsx`** — including the disabled-state preservation invariant

```tsx
import { render, screen, fireEvent } from "@testing-library/react";
import { describe, expect, test, vi } from "vitest";
import { GroupToggleRow } from "./GroupToggleRow";

describe("GroupToggleRow", () => {
  test("renders label and subtext", () => {
    render(<GroupToggleRow
      group="bidding" label="Bidding" subtext="Outbid, proxy exhausted"
      value={true} mutedDisabled={false} onChange={() => {}} />);
    expect(screen.getByText("Bidding")).toBeInTheDocument();
    expect(screen.getByText("Outbid, proxy exhausted")).toBeInTheDocument();
  });

  test("aria-checked reflects value when not muted", () => {
    render(<GroupToggleRow
      group="bidding" label="Bidding" subtext="x"
      value={true} mutedDisabled={false} onChange={() => {}} />);
    expect(screen.getByRole("switch")).toHaveAttribute("aria-checked", "true");
  });

  test("click fires onChange when not muted", () => {
    const onChange = vi.fn();
    render(<GroupToggleRow
      group="bidding" label="Bidding" subtext="x"
      value={false} mutedDisabled={false} onChange={onChange} />);
    fireEvent.click(screen.getByRole("switch"));
    expect(onChange).toHaveBeenCalledWith(true);
  });

  test("disabled state preserves checked value (the load-bearing invariant)", () => {
    render(<GroupToggleRow
      group="bidding" label="Bidding" subtext="x"
      value={true} mutedDisabled={true} onChange={() => {}} />);
    const sw = screen.getByRole("switch");
    expect(sw).toHaveAttribute("aria-checked", "true");
    expect(sw).toBeDisabled();
  });

  test("click does NOT fire onChange when mutedDisabled", () => {
    const onChange = vi.fn();
    render(<GroupToggleRow
      group="bidding" label="Bidding" subtext="x"
      value={true} mutedDisabled={true} onChange={onChange} />);
    fireEvent.click(screen.getByRole("switch"));
    expect(onChange).not.toHaveBeenCalled();
  });
});
```

- [ ] **Step 13: Write `NotificationPreferencesPage.test.tsx`**

```tsx
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { describe, expect, test, beforeEach, vi } from "vitest";
import { NotificationPreferencesPage } from "./NotificationPreferencesPage";
import { resetPreferences, seedPreferences } from "@/test/msw/handlers";
import type { ReactNode } from "react";

function renderWithProviders(ui: ReactNode) {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(<QueryClientProvider client={qc}>{ui}</QueryClientProvider>);
}

describe("NotificationPreferencesPage", () => {
  beforeEach(() => resetPreferences());

  test("renders banner and 5 group toggle rows (no SYSTEM/REALTY_GROUP/MARKETING)", async () => {
    renderWithProviders(<NotificationPreferencesPage />);

    await waitFor(() => {
      expect(screen.getByText(/In-app and system notifications always deliver/i))
        .toBeInTheDocument();
    });

    expect(screen.getByText("Bidding")).toBeInTheDocument();
    expect(screen.getByText("Auction Result")).toBeInTheDocument();
    expect(screen.getByText("Escrow")).toBeInTheDocument();
    expect(screen.getByText("Listings")).toBeInTheDocument();
    expect(screen.getByText("Reviews")).toBeInTheDocument();
    // None of these should appear:
    expect(screen.queryByText("System")).not.toBeInTheDocument();
    expect(screen.queryByText("Realty Group")).not.toBeInTheDocument();
    expect(screen.queryByText("Marketing")).not.toBeInTheDocument();
  });

  test("Reviews defaults to OFF for fresh user", async () => {
    renderWithProviders(<NotificationPreferencesPage />);

    await waitFor(() => {
      const reviewsToggle = screen.getByTestId("group-toggle-reviews");
      expect(reviewsToggle).toHaveAttribute("aria-checked", "false");
    });
  });

  test("master mute disables group toggles but preserves underlying values", async () => {
    seedPreferences({
      slImMuted: false,
      slIm: { bidding: true, auction_result: true, escrow: true,
              listing_status: true, reviews: false },
    });
    renderWithProviders(<NotificationPreferencesPage />);

    // Wait for initial render
    await waitFor(() => {
      expect(screen.getByTestId("group-toggle-bidding"))
        .toHaveAttribute("aria-checked", "true");
    });

    // Find the master mute switch by its aria-label and click
    const masterMute = screen.getByLabelText("Mute all SL IM notifications");
    fireEvent.click(masterMute);

    await waitFor(() => {
      expect(masterMute).toHaveAttribute("aria-checked", "true");
    });

    // Group toggles should now be disabled but values unchanged
    const biddingToggle = screen.getByTestId("group-toggle-bidding");
    expect(biddingToggle).toBeDisabled();
    expect(biddingToggle).toHaveAttribute("aria-checked", "true");

    const reviewsToggle = screen.getByTestId("group-toggle-reviews");
    expect(reviewsToggle).toBeDisabled();
    expect(reviewsToggle).toHaveAttribute("aria-checked", "false");

    // Click bidding toggle while muted — should be no-op
    fireEvent.click(biddingToggle);
    expect(biddingToggle).toHaveAttribute("aria-checked", "true");

    // Toggle master mute off
    fireEvent.click(masterMute);
    await waitFor(() => {
      expect(masterMute).toHaveAttribute("aria-checked", "false");
    });

    // Group toggles re-enabled, values preserved
    expect(biddingToggle).not.toBeDisabled();
    expect(biddingToggle).toHaveAttribute("aria-checked", "true");
    expect(reviewsToggle).toHaveAttribute("aria-checked", "false");
  });

  test("clicking a group toggle when not muted updates the toggle state", async () => {
    seedPreferences({
      slImMuted: false,
      slIm: { bidding: true, auction_result: true, escrow: true,
              listing_status: true, reviews: false },
    });
    renderWithProviders(<NotificationPreferencesPage />);

    await waitFor(() => {
      expect(screen.getByTestId("group-toggle-bidding"))
        .toHaveAttribute("aria-checked", "true");
    });

    fireEvent.click(screen.getByTestId("group-toggle-bidding"));

    await waitFor(() => {
      expect(screen.getByTestId("group-toggle-bidding"))
        .toHaveAttribute("aria-checked", "false");
    });
  });
});
```

- [ ] **Step 14: Write `app/settings/notifications/page.test.tsx`**

```tsx
import { render, screen } from "@testing-library/react";
import { describe, expect, test } from "vitest";
import NotificationSettingsPage from "./page";
// Plus whatever providers (QueryClientProvider, etc.) the project's test setup uses for pages.

describe("/settings/notifications page", () => {
  test("renders the preferences page title (via layout) when authenticated", async () => {
    // Render with whatever auth context the project's tests use to mock
    // an authenticated session. Match the pattern from
    // frontend/src/app/notifications/page.test.tsx (sub-spec 1's existing test).
    render(<NotificationSettingsPage />);
    expect(await screen.findByText(/In-app and system notifications/i)).toBeInTheDocument();
  });
});
```

- [ ] **Step 15: Run frontend tests**

Run: `cd frontend && npm test -- --run components/notifications/preferences app/settings`
Expected: tests pass.

- [ ] **Step 16: Run full frontend suite + lint**

Run: `cd frontend && npm test -- --run && npm run lint`
Expected: previous (Task 7) baseline + new component tests, 0 failures, lint clean.

- [ ] **Step 17: Commit**

```bash
git add frontend/src/app/settings/ \
        frontend/src/components/notifications/preferences/ \
        frontend/src/components/notifications/NotificationDropdown.tsx \
        frontend/src/components/notifications/feed/FeedShell.tsx \
        frontend/src/components/ui/icons.ts
git commit -m "feat(sl-im): /settings/notifications UI + bell + feed-page entry points"
```

---

## Task 9 — LSL script + lsl-scripts/ directory tree

**Goal:** Land the `lsl-scripts/` top-level directory with a contributor-rule README, plus `lsl-scripts/sl-im-dispatcher/` with the dispatcher LSL script, the config notecard template, and a comprehensive per-script README. No automated tests; manual deployment + verification covered in the PR's manual test plan.

**Files:**
- Create: `lsl-scripts/README.md`
- Create: `lsl-scripts/sl-im-dispatcher/README.md`
- Create: `lsl-scripts/sl-im-dispatcher/dispatcher.lsl`
- Create: `lsl-scripts/sl-im-dispatcher/config.notecard.example`

### Steps

- [ ] **Step 1: Create `lsl-scripts/README.md` (top-level index)**

```markdown
# LSL Scripts

In-world Linden Scripting Language (LSL) code that consumes SLPA's backend APIs.
Each script is the in-world half of an end-to-end integration; the matching
backend code lives under `backend/src/main/java/com/slparcelauctions/backend/`.

## Contributor rules

**Each new script gets its own subdirectory with its own README.** Updates to
any script's behavior, deployment, or configuration must be reflected in that
script's README in the same commit. The top-level index below updates only on
add / remove / rename.

A script's README must cover:

- **Purpose** — what the script does and what backend system it integrates with.
- **Architecture summary** — high-level flow (events, timers, HTTP calls).
- **Deployment** — step-by-step in-world deployment, prim setup, permissions.
- **Configuration** — notecard format, where to obtain secrets, rotation procedure.
- **Operations** — what owner-say / chat output to expect; how to verify health.
- **Troubleshooting** — common failure modes and their signatures.
- **Limits** — SL platform constraints the script depends on (HTTP throttles,
  IM byte limits, etc.) and any known operational caveats.
- **Security** — secret-handling expectations and access-control notes.

## Scripts

- [`sl-im-dispatcher/`](sl-im-dispatcher/) — Polls SLPA backend for pending
  SL IM notifications and delivers them via `llInstantMessage`. SLPA-team-deployed
  (one instance per environment); not user-deployed.
```

- [ ] **Step 2: Create `lsl-scripts/sl-im-dispatcher/dispatcher.lsl`**

```lsl
// SLPA SL IM Dispatcher
//
// Polls SLPA backend for pending notification IMs and delivers them via
// llInstantMessage. Single state machine with two cadences:
//   - 60-second poll interval when idle
//   - 2-second per-IM tick when delivering a batch
//
// Configuration is loaded from a notecard named "config" in the same prim.
// See README.md for deployment and operations details.

// === Configuration loaded from notecard ===
string POLL_URL = "";
string CONFIRM_URL_BASE = "";
string SHARED_SECRET = "";
integer DEBUG_OWNER_SAY = TRUE;

// === Cadences ===
float POLL_INTERVAL = 60.0;
float IM_INTERVAL = 2.0;

// === Batch state during delivery ===
list batchIds = [];
list batchUuids = [];
list batchTexts = [];
integer batchIndex = 0;
integer deliveringBatch = FALSE;

// === HTTP request tracking ===
key pollRequestId = NULL_KEY;

// === Notecard reading state ===
key notecardLineRequest = NULL_KEY;
integer notecardLineNum = 0;

readNotecardLine(integer n) {
    notecardLineNum = n;
    notecardLineRequest = llGetNotecardLine("config", n);
}

parseConfigLine(string line) {
    if (llStringLength(line) == 0) return;
    if (llSubStringIndex(line, "#") == 0) return;  // comment

    integer eq = llSubStringIndex(line, "=");
    if (eq < 1) return;

    string key = llStringTrim(llGetSubString(line, 0, eq - 1), STRING_TRIM);
    string val = llStringTrim(llGetSubString(line, eq + 1, -1), STRING_TRIM);

    if (key == "POLL_URL") POLL_URL = val;
    else if (key == "CONFIRM_URL_BASE") CONFIRM_URL_BASE = val;
    else if (key == "SHARED_SECRET") SHARED_SECRET = val;
    else if (key == "DEBUG_OWNER_SAY") DEBUG_OWNER_SAY = (val == "true" || val == "TRUE" || val == "1");
}

deliverNextInBatch() {
    if (batchIndex >= llGetListLength(batchIds)) {
        // Batch complete; reset state and return to slow cadence.
        deliveringBatch = FALSE;
        batchIds = [];
        batchUuids = [];
        batchTexts = [];
        llSetTimerEvent(POLL_INTERVAL);
        return;
    }

    string id = llList2String(batchIds, batchIndex);
    string uuid = llList2String(batchUuids, batchIndex);
    string text = llList2String(batchTexts, batchIndex);

    llInstantMessage((key)uuid, text);
    llHTTPRequest(
        CONFIRM_URL_BASE + id + "/delivered",
        [HTTP_METHOD, "POST",
         HTTP_CUSTOM_HEADER, "Authorization", "Bearer " + SHARED_SECRET,
         HTTP_BODY_MAXLENGTH, 4096],
        ""
    );
    batchIndex += 1;
}

integer parseAndStoreBatch(string body) {
    // body is JSON: { "messages": [ {id, avatarUuid, messageText}, ... ] }
    string messagesJson = llJsonGetValue(body, ["messages"]);
    if (messagesJson == JSON_INVALID || messagesJson == "") return 0;

    list keys = llJson2List(messagesJson);
    integer i;
    integer n = llGetListLength(keys);
    for (i = 0; i < n; ++i) {
        string item = llList2String(keys, i);
        string id = llJsonGetValue(item, ["id"]);
        string uuid = llJsonGetValue(item, ["avatarUuid"]);
        string text = llJsonGetValue(item, ["messageText"]);
        if (id != JSON_INVALID && uuid != JSON_INVALID && text != JSON_INVALID) {
            batchIds += [id];
            batchUuids += [uuid];
            batchTexts += [text];
        }
    }
    return llGetListLength(batchIds);
}

default {
    state_entry() {
        POLL_URL = "";
        CONFIRM_URL_BASE = "";
        SHARED_SECRET = "";
        readNotecardLine(0);
    }

    dataserver(key requested, string data) {
        if (requested != notecardLineRequest) return;
        if (data == NAK) {
            llOwnerSay("SL IM dispatcher: notecard 'config' missing or unreadable");
            return;
        }
        if (data == EOF) {
            // Notecard fully read; validate config.
            if (POLL_URL == "" || CONFIRM_URL_BASE == "" || SHARED_SECRET == "") {
                llOwnerSay("SL IM dispatcher: incomplete config — POLL_URL / CONFIRM_URL_BASE / SHARED_SECRET required");
                return;
            }
            if (DEBUG_OWNER_SAY) llOwnerSay("SL IM dispatcher: ready (poll=" + POLL_URL + ")");
            llSetTimerEvent(POLL_INTERVAL);
            return;
        }
        parseConfigLine(data);
        readNotecardLine(notecardLineNum + 1);
    }

    timer() {
        if (deliveringBatch) {
            deliverNextInBatch();
        } else {
            // Slow-cadence poll cycle.
            pollRequestId = llHTTPRequest(
                POLL_URL + "?limit=10",
                [HTTP_METHOD, "GET",
                 HTTP_CUSTOM_HEADER, "Authorization", "Bearer " + SHARED_SECRET,
                 HTTP_BODY_MAXLENGTH, 16384],
                ""
            );
        }
    }

    http_response(key requestId, integer status, list meta, string body) {
        if (requestId == pollRequestId) {
            pollRequestId = NULL_KEY;
            if (status != 200) {
                if (DEBUG_OWNER_SAY) llOwnerSay("SL IM poll failed: " + (string)status);
                return;
            }
            integer count = parseAndStoreBatch(body);
            if (count > 0) {
                if (DEBUG_OWNER_SAY) llOwnerSay("SL IM batch=" + (string)count);
                deliveringBatch = TRUE;
                batchIndex = 0;
                llSetTimerEvent(IM_INTERVAL);
                deliverNextInBatch();  // immediately fire the first IM
            } else {
                if (DEBUG_OWNER_SAY) llOwnerSay("SL IM poll: 0 messages");
            }
            return;
        }
        // Confirmation responses; log only on non-2xx.
        if (status >= 400) {
            llOwnerSay("SL IM confirm failed: status=" + (string)status);
        }
    }

    on_rez(integer start_param) {
        llResetScript();
    }

    changed(integer change) {
        if (change & CHANGED_INVENTORY) {
            // Notecard may have been re-edited; reload.
            llResetScript();
        }
    }
}
```

- [ ] **Step 3: Create `lsl-scripts/sl-im-dispatcher/config.notecard.example`**

```
# SL IM Dispatcher configuration
# Copy this file to a notecard named "config" in the same prim, then edit
# values for your environment.

POLL_URL=https://slpa.example.com/api/v1/internal/sl-im/pending
CONFIRM_URL_BASE=https://slpa.example.com/api/v1/internal/sl-im/
SHARED_SECRET=<obtain from server config: slpa.notifications.sl-im.dispatcher.shared-secret>
DEBUG_OWNER_SAY=true
```

- [ ] **Step 4: Create `lsl-scripts/sl-im-dispatcher/README.md`**

```markdown
# SL IM Dispatcher

In-world component of the SLPA SL IM notification channel. Polls the SLPA
backend for pending IMs and delivers them via `llInstantMessage`.

## Architecture summary

- **Polling cadence:** 60 seconds when idle.
- **Per-IM cadence:** 2 seconds (matches SL's `llInstantMessage` floor).
- **Batch size:** up to 10 messages per poll, sized to fit comfortably within
  SL's 20-second HTTP request window.
- **Confirmation:** for each delivered IM, the script POSTs
  `/api/v1/internal/sl-im/{id}/delivered` to mark the row DELIVERED.
- **Authentication:** shared-secret bearer token, loaded from the `config`
  notecard at script start.

## Deployment

This is a **SLPA-team-deployed object**, one per environment. Not user-deployed.

1. Rez a generic prim somewhere with reliable land permissions (group land or
   owner-controlled). Outbound HTTP must be permitted on that parcel.
2. Drop `dispatcher.lsl` into the prim.
3. Drop a copy of `config.notecard.example` renamed to **`config`** (no extension).
   Edit the values to match the target environment.
4. Reset the script (right-click → Edit → Reset Scripts in Selection).
5. Watch local chat for the startup message: `SL IM dispatcher: ready (poll=...)`.

Object ownership and modify permissions should be tightly scoped to SLPA-team
avatars only. The shared secret is in the notecard, visible to anyone with
edit-rights on the object.

## Configuration

The `config` notecard format is `key=value` pairs, one per line. Lines starting
with `#` are comments. Whitespace is trimmed. Required keys:

| Key | Description |
| --- | --- |
| `POLL_URL` | Full URL of the backend's pending-messages endpoint. |
| `CONFIRM_URL_BASE` | URL prefix for delivery-confirmation posts. The script appends `{id}/delivered` to this base. |
| `SHARED_SECRET` | The bearer-token secret. Obtain from the server's `slpa.notifications.sl-im.dispatcher.shared-secret` configuration property. |
| `DEBUG_OWNER_SAY` | `true` to enable per-poll owner chat (default); `false` to silence. Recommended `true` in prod for observability. |

### Rotating the shared secret

1. Update the deployment's secrets store with the new value.
2. Restart the SLPA backend so it picks up the new secret.
3. Edit the `config` notecard with the new `SHARED_SECRET` value.
4. Reset the script (the `changed(CHANGED_INVENTORY)` handler also auto-resets,
   but a manual reset is faster).

In-flight pending rows are unaffected; the next poll uses the new secret.

## Operations

In steady state, with `DEBUG_OWNER_SAY=true`, you'll see one of these every
60 seconds:

- `SL IM poll: 0 messages` — nothing pending; healthy.
- `SL IM batch=N` — N messages found; per-IM cadence kicks in.
- `SL IM confirm failed: status=N` — a confirmation HTTP call failed; the IM
  was still sent, the row in the backend may end up EXPIRED via the cleanup
  job. Investigate if these are frequent.
- `SL IM poll failed: N` — the poll HTTP call failed. Common causes: 401
  (secret mismatch), 500 (backend issue), 0 (network / permissions).

If you go silent for >5 minutes (no startup ping, no poll output), something is
wrong. Most common: notecard missing, notecard malformed, or the prim landed
on a parcel without outbound HTTP permission.

## Troubleshooting

| Symptom | Likely cause |
| --- | --- |
| Startup says `incomplete config` | Notecard missing one of `POLL_URL`, `CONFIRM_URL_BASE`, `SHARED_SECRET`. |
| Startup says `notecard 'config' missing or unreadable` | Notecard not in the prim, or named something other than exactly `config`. |
| Periodic `SL IM poll failed: 401` | Shared secret in notecard doesn't match the backend's configured value. |
| Periodic `SL IM poll failed: 500` | Backend issue. Check server logs. |
| `SL IM poll failed: 0` (no HTTP response) | Land permissions blocking outbound HTTP, or no-script land. Move the object. |
| Long silence in owner chat | Script may have wedged. Right-click → Edit → Reset Scripts. |
| Backend shows growing PENDING count | Dispatcher is dark. Check that the object is rezzed, not on no-script land, has the right script + notecard. |

## Limits

Three load-bearing notes:

- **Hard byte limit on IM text.** `llInstantMessage` truncates at 1024 BYTES
  (not characters). The backend's `SlImMessageBuilder` enforces this; the
  script trusts the messages it receives. **Operators should never modify
  `messageText` in the script.**
- **No `/failed` caller in this script.** `llInstantMessage` is fire-and-forget
  — no return value, no error, no way to distinguish "delivered" from "UUID
  doesn't exist." The script unconditionally calls `/{id}/delivered` after every
  IM. So FAILED rows in the database only ever come from manual operator
  intervention or a future revision of this script that adds UUID
  pre-validation (e.g., `llRequestAgentData` against `DATA_ONLINE` before
  sending). If `SELECT status, count(*) FROM sl_im_message GROUP BY status`
  shows zero FAILED rows, that's correct behavior, not a missing pipeline.
- **Batch size 10 with 2-second spacing fits the 20-second SL HTTP window.**
  Increasing batch size beyond 10 risks the script's confirmation requests
  racing the next poll cycle's request limits. Don't increase without verifying
  the math.

## Security

The shared secret is a deployment artifact; treat it like a database password.
Notecard contents are visible to anyone with object-edit rights, so the
dispatcher prim's ownership and modify permissions should be SLPA-team-only.
A leaked shared secret means an attacker can pull the entire pending IM queue
and confirm-mark messages as DELIVERED, blocking real delivery — rotate
immediately if compromise is suspected.
```

- [ ] **Step 5: Verify the LSL script syntax (best-effort visual review)**

LSL has no command-line linter. Open `dispatcher.lsl` in any LSL-aware editor
(Firestorm, the in-world editor, an LSL-syntax VS Code extension) and verify
no syntax errors. The script is small; visual review is sufficient.

Specific things to check:
- Every `string`/`integer`/`list`/`key` declaration before use.
- All `llHTTPRequest` calls have matching brackets and a final `""` body argument.
- The `dataserver` event handler reads consecutive notecard lines and stops on
  `EOF`.
- `llResetScript()` on `on_rez` and `changed(CHANGED_INVENTORY)`.

- [ ] **Step 6: Commit**

```bash
git add lsl-scripts/
git commit -m "feat(sl-im): lsl-scripts/sl-im-dispatcher polling object + per-script README"
```

---

## Task 10 — Cross-cutting cleanup: DEFERRED + FOOTGUNS + DESIGN.md + CLAUDE.md + PR

**Goal:** Update the running ledgers (close 2, modify 2, add 3 in DEFERRED_WORK; add 4 entries to FOOTGUNS), append the sub-spec 3 follow-on subsection to DESIGN.md §11, add the `lsl-scripts/` paragraph to root CLAUDE.md, run the full backend + frontend suites once, then push and open the PR. **Do not auto-merge.**

**Files:**
- Modify: `docs/implementation/DEFERRED_WORK.md`
- Modify: `docs/implementation/FOOTGUNS.md`
- Modify: `docs/initial-design/DESIGN.md` (extend Notes (Epic 09 sub-spec 1) section)
- Modify: `CLAUDE.md` (root)

### Steps

- [ ] **Step 1: Update `docs/implementation/DEFERRED_WORK.md`**

Open the file. Find and **remove** these closed entries (full block per entry):
- `### SL IM channel for notifications`
- `### Notification preferences UI`

Find and **modify** these entries:

For `### Email channel for notifications`, replace the existing body with:

```markdown
### Email channel for notifications
- **Status:** Removed from roadmap. Re-add only on explicit user request.
- **Reasoning:** SL natively forwards offline IMs to the user's registered email,
  so the SL IM channel from Epic 09 sub-spec 3 covers the email use case at zero
  additional infrastructure cost.
- **If re-instated:** per-category templates (HTML + plain text), signed-token
  unsubscribe, debounce/dedupe matching the coalesce pattern, email-change flow
  (originally pending from Epic 02 sub-spec 2b).
```

For `### ESCROW_TRANSFER_REMINDER scheduler`, replace the body with:

```markdown
### ESCROW_TRANSFER_REMINDER scheduler
- **From:** Epic 09 sub-spec 1
- **Why:** Sub-spec 1 implements `publisher.escrowTransferReminder(...)` +
  `NotificationDataBuilder.escrowTransferReminder(...)` + corresponding category
  and tests. No `@Scheduled` job calls it today.
- **When:** Epic 10 (Admin & Moderation) — paired with REVIEW_RESPONSE_WINDOW_CLOSING
  scheduler. Both share design DNA (interval-bound + once-per-entity + admin-visible).
- **Implementation sketch:** new `Escrow.reminderSentAt` column for
  once-per-escrow guarantee; new `EscrowReminderScheduler` (cron daily, query
  escrows with FUNDED status approaching transfer deadline, fire
  `publisher.escrowTransferReminder(...)`).
```

**Add** these new entries (append to the file's "Current Deferred Items" section
or wherever existing entries live):

```markdown
### Quiet hours UI for SL IM
- **From:** Epic 09 sub-spec 3
- **Why:** Columns `slImQuietStart` and `slImQuietEnd` exist on User entity
  from Epic 02 sub-spec 2b; no UI consumes them and the dispatcher gate
  ignores them. May tie to a future timezone/account-settings sub-spec; no
  committed home yet. If unused for >12 months, drop the columns in a
  dedicated cleanup sub-spec.
- **When:** No committed phase.

### HTTP-in push from backend to dispatcher for urgency
- **From:** Epic 09 sub-spec 3
- **Why:** Current design polls every 60 seconds — fine for the events
  shipping today (worst case 60 s latency for outbid/won). If outbid latency
  becomes a UX concern, register the dispatcher's HTTP-in URL with the
  backend on startup and have the backend `llHTTPRequest` to it on
  high-priority categories to wake an early poll.
- **When:** Post-launch enhancement; needs the channel to have real traffic
  and a real complaint before the complexity earns its keep.

### Sub-day SL IM dispatcher health monitoring
- **From:** Epic 09 sub-spec 3
- **Why:** The expiry job's INFO log catches a dark dispatcher within 48 h. If
  sub-day signal becomes important, options include: a `last_polled_at`
  timestamp on a singleton `dispatcher_health` row written on each successful
  poll, with an alarm scheduler that pages on `now - last_polled_at > 5 min`.
- **When:** No committed phase. Out of scope until operational data shows the
  48 h canary is insufficient.
```

- [ ] **Step 2: Update `docs/implementation/FOOTGUNS.md`**

Open the file. Find the next sequential `F.NN` number from the file's tail.

Append four new entries:

```markdown
### F.NN — `llInstantMessage` truncates at 1024 BYTES, not characters

The natural Java `String.length()` measures UTF-16 code units; SL's
`llInstantMessage` truncates the IM at 1024 **bytes** in UTF-8 encoding.
Multi-byte UTF-8 characters (CJK, emoji, accented Latin) push the byte count
above the char count. SL silently truncates from the end of the string — no
error, no warning, no return value — and the deeplink in SLPA's IM template
lives at the end of the assembled message. A 1023-character string with
multi-byte content can occupy 1500+ bytes; the deeplink gets cleanly cut off.

`SlImMessageBuilder` measures `text.getBytes(StandardCharsets.UTF_8).length`
and ellipsizes the body, never the prefix or deeplink. Three mandatory test
cases (multi-byte parcel name, emoji parcel name, long-body forcing
truncation) verify the deeplink survives. Adding a new component to the
assembled message (e.g., a timestamp) requires updating the byte-budget
accounting in `SlImMessageBuilder` — the budget assumes exactly
`PREFIX + title + SEPARATOR + body + SEPARATOR + deeplink`.

### F.NN — Single-recipient publish path is afterCommit-then-REQUIRES_NEW; fan-out path is in-the-REQUIRES_NEW

The two notification dispatch sites have different reliability postures and
different transaction structures.

**Single-recipient path** (`NotificationService.publish`): in-app row commits
first as part of the parent transaction, then `afterCommit` runs
`slImChannelDispatcher.maybeQueue` which opens its own REQUIRES_NEW. If the
IM-queue write fails, the in-app row already committed, the parent business
event already committed, and the only loss is the IM. **In-app guaranteed,
IM best-effort.**

**Fan-out path** (`NotificationPublisherImpl.listingCancelledBySellerFanout`):
per-recipient REQUIRES_NEW lambda contains the in-app DAO upsert AND the IM
queue write as siblings. If the IM-queue write fails, that recipient's
in-app row also rolls back; the per-recipient try-catch isolates this from
sibling recipients. **In-app + IM atomic per recipient; sibling recipients
independent.**

Mixing these mental models produces either:
- "One bad bidder kills the cancellation" — if you put fan-out atomic with the parent transaction.
- "In-app row exists but IM never queued because the dispatcher hook failed silently" — if you inline the dispatcher into the single-recipient path's parent transaction.

Future contributors adding a new fan-out method must follow the existing
pattern: per-recipient REQUIRES_NEW lambda containing all per-recipient writes
as siblings, with a per-recipient try-catch wrapping the lambda. Name with a
`Fanout` suffix (sub-spec 1's convention).

### F.NN — Adding a new terminal status to `sl_im_message` requires updating the cleanup predicate

`SlImCleanupJob` stage 2 deletes rows via `WHERE status IN ('DELIVERED',
'EXPIRED', 'FAILED') AND updated_at < retention_cutoff`. The IN-list
enumerates terminal statuses. Adding a new one (e.g., a future
`RETRY_SCHEDULED` for a deferred retry primitive) without updating the IN-list
means those rows accumulate forever — defeating the very `SELECT status,
count(*) FROM sl_im_message GROUP BY status` query the rolling 30-day window
was supposed to keep meaningful. Add the new status to the predicate in the
same commit that introduces the status.

### F.NN — LSL `llInstantMessage` is fire-and-forget; `/failed` has no LSL caller

The `sl-im-dispatcher` script unconditionally calls
`POST /api/v1/internal/sl-im/{id}/delivered` after every `llInstantMessage`
because LSL provides no delivery signal — `llInstantMessage` returns `void`,
raises no event on failure, and produces no observable side effect when the
recipient UUID is invalid or offline-unreachable.

This means FAILED rows in `sl_im_message` only appear via:
1. Manual operator intervention — direct SQL UPDATE in production (rare;
   usually for support clearing a stuck row).
2. A future revision of `dispatcher.lsl` that pre-validates avatar UUIDs
   (e.g., `llRequestAgentData` against `DATA_ONLINE` before sending, or a
   `NULL_KEY` guard).

If support runs `SELECT status, count(*) FROM sl_im_message GROUP BY status`
and sees zero FAILED rows, that's correct behavior. Not a missing pipeline.
```

Replace `F.NN` with the actual sequential numbers (check the file's last entry
and increment).

- [ ] **Step 3: Update `docs/initial-design/DESIGN.md` §11**

Find the existing "Notes (Epic 09 sub-spec 1)" subsection at the bottom of §11.
Append a follow-on subsection:

```markdown
**Notes (Epic 09 sub-spec 3):**
- The SL IM channel reuses the in-app coalesce key namespace; rows in
  `sl_im_message` collapse during pendency (`WHERE status = 'PENDING'`),
  with the same partial-unique-index pattern.
- `SYSTEM` group bypasses preferences and master-mute, but never bypasses the
  no-avatar floor.
- `llInstantMessage` truncates at 1024 BYTES (UTF-8), not characters. The
  backend's `SlImMessageBuilder` enforces this with body ellipsis, never
  trimming the prefix or deeplink.
- The polling/confirmation contract (`/api/v1/internal/sl-im/*`) uses a
  shared secret distinct from the existing escrow-terminal secret. State
  machines for `/delivered` and `/failed` are symmetric: PENDING →
  terminal-status, idempotent on the matching status, 409 on the other
  terminal statuses (FAILED/EXPIRED).
- The system relies on SL's native offline-IM-to-email forwarding to cover
  the email use case. Users with active SL email forwarding receive SLPA
  notifications by email; users with disabled forwarding receive only
  in-world IMs when online. The email channel is intentionally not built.
```

- [ ] **Step 4: Update root `CLAUDE.md`**

Open `/Users/heath/Repos/personal/slparcelauctions/CLAUDE.md`. Find the
"Second Life Integration Notes" section (or the closest equivalent — read the
file first). Append:

```markdown
**In-world LSL code lives in `lsl-scripts/`.** Each script gets its own
subdirectory with its own README covering deployment, configuration,
operations, and limits. Updates to a script's behavior, deployment, or
configuration must update that script's README in the same commit. The
top-level `lsl-scripts/README.md` is an index — updates only on add / remove
/ rename.
```

- [ ] **Step 5: Run the full backend test suite**

Run: `cd backend && ./mvnw test`
Expected: previous (Task 9) baseline + all earlier task additions, 0 failures.

- [ ] **Step 6: Run the full frontend test suite + lint**

Run: `cd frontend && npm test -- --run && npm run lint`
Expected: previous (Task 9) baseline + all earlier task additions, 0 failures, lint clean.

- [ ] **Step 7: Commit the docs cleanup**

```bash
git add docs/implementation/DEFERRED_WORK.md \
        docs/implementation/FOOTGUNS.md \
        docs/initial-design/DESIGN.md \
        CLAUDE.md
git commit -m "docs(sl-im): close 2 deferred entries, modify 2, add 3; FOOTGUNS x4; DESIGN.md notes"
```

- [ ] **Step 8: Push the branch**

```bash
git push -u origin task/09-sub-3-sl-im-and-preferences
```

- [ ] **Step 9: Open the PR**

Verify the base branch by checking how recent PRs target (`gh pr list --state merged --limit 5`). Should be `dev`.

```bash
gh pr create --title "Epic 09 sub-spec 3: SL IM channel + notification preferences UI" --body "$(cat <<'EOF'
## Summary

Ships the SL IM channel + notification preferences UI end-to-end:

- New `sl_im_message` queue with ON CONFLICT coalescing during pendency, partial unique index on `(user_id, coalesce_key) WHERE status = 'PENDING'`.
- `SlImChannelGate` discriminated decision logic (no-avatar floor, SYSTEM bypass, master mute, group prefs).
- Dispatch via afterCommit hook on `NotificationService.publish` (single-recipient) and sibling call inside `listingCancelledBySellerFanout`'s per-recipient REQUIRES_NEW lambda (fan-out).
- Internal endpoints `/api/v1/internal/sl-im/{pending, {id}/delivered, {id}/failed}` with shared-secret auth, symmetric state machines (PENDING → terminal, idempotent on match, 409 on other terminal statuses).
- 48h expiry job at 03:30 SLT with INFO canary log (`expired`, `deleted`, `top_users`).
- `/settings/notifications` UI: banner + master mute + 5 group toggles. Master mute disables group toggles visually but preserves their state. Closed-shape PUT validation (rejects SYSTEM/REALTY_GROUP/MARKETING).
- `lsl-scripts/sl-im-dispatcher/` LSL polling object (60s polls, 2s per-IM throttle, batch ≤10).
- Settings entry points from bell dropdown footer and `/notifications` feed page header.

Closes deferred-ledger entries:
- SL IM channel for notifications
- Notification preferences UI

Email channel: removed from the roadmap. SL's native offline-IM-to-email forwarding covers the email use case via the SL IM channel.

## Test plan

- [ ] Backend: `./mvnw test -pl backend` → green (≥ baseline + ~80 new tests)
- [ ] Frontend: `cd frontend && npm test -- --run` → green
- [ ] Frontend: `npm run lint` → clean
- [ ] Two browser sessions, two users; one with linked SL avatar, one without. Drive an OUTBID for each. Verify: linked user has a PENDING `sl_im_message` row; unlinked user has none. Both users still see in-app + WS push.
- [ ] In `/settings/notifications`, mute Bidding for the linked user. Drive another OUTBID. Verify in-app row created, no new IM row queued.
- [ ] Toggle master mute on. Verify group toggles render disabled with checked-states preserved. Toggle off. States return without API roundtrip.
- [ ] `curl -H "Authorization: Bearer ${SECRET}" GET /api/v1/internal/sl-im/pending?limit=10`. Verify assembled `messageText` matches template; multi-byte parcel name preserves the deeplink.
- [ ] `curl -H "Authorization: Bearer ${SECRET}" -X POST /api/v1/internal/sl-im/{id}/delivered`. Verify status transitions; second curl returns 204; manually mark a row FAILED then curl `/delivered` — expect 409.
- [ ] Deploy `sl-im-dispatcher` in beta sim with staging URL. Drive a notification for a test user with an avatar. Verify the alt account receives the IM with correct text and clickable deeplink. Watch owner-say for poll cadence.
- [ ] Stop the dispatcher object. Wait 49 hours. Verify the cleanup job's INFO log includes `expired > 0` and a `top_users` entry naming the test account.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

After `gh pr create` returns the PR URL, **report it back**.

- [ ] **Step 10: Stop. Do not auto-merge.**

The user reviews and merges manually. Do not run `gh pr merge`.

---

## Self-review

After writing the complete plan, this is a fresh-eyes pass against the spec.

### Spec coverage

- [x] **§1 Goal & scope** — Tasks 1–10 collectively implement the goal. Out-of-scope items are documented in DEFERRED_WORK updates (Task 10 Step 1) and FOOTGUNS (Task 10 Step 2).
- [x] **§2.1 afterCommit + REQUIRES_NEW for single-recipient** — Task 3 Steps 1, 2 (dispatcher + NotificationService modification).
- [x] **§2.2 Sibling call inside fan-out's REQUIRES_NEW lambda** — Task 3 Step 3 (NotificationPublisherImpl modification).
- [x] **§2.3 Coalesce key shared with in-app** — Task 1 (DAO uses passed-through key); Task 3 (dispatcher passes through `event.coalesceKey()`).
- [x] **§3.1 Package layout** — files in Tasks 1–6 match the layout spec.
- [x] **§3.2 SlImMessage entity** — Task 1 Step 2.
- [x] **§3.3 SlImMessageStatus enum** — Task 1 Step 1.
- [x] **§3.4 SlImMessageDao with `xmax != 0` ON CONFLICT upsert** — Task 1 Step 4.
- [x] **§3.5 SlImMessageRepository with FOR UPDATE SKIP LOCKED** — Task 1 Step 5; Task 4 Step 5 (controller comment).
- [x] **§3.6 SlImChannelGate with discriminated Decision** — Task 2 Step 3.
- [x] **§3.7 SlImChannelDispatcher with two entry points** — Task 3 Step 1.
- [x] **§3.8 SlImLinkResolver per-category** — Task 2 Step 6.
- [x] **§3.9 SlImMessageBuilder with byte-budget enforcement** — Task 2 Step 9, with three mandatory test cases at Step 10.
- [x] **§3.10 SlImCleanupJob with stage 1 + stage 2 + INFO log + top_users-before-update** — Task 5 Step 3.
- [x] **§3.11 SlImTerminalAuthFilter** — Task 4 Step 3.
- [x] **§3.12 SlImInternalController state machines** — Task 4 Step 5.
- [x] **§3.13 NotificationPreferencesController closed-shape validation** — Task 6 Step 2.
- [x] **§3.14 SlImCoalesceIndexInitializer** — Task 1 Step 6.
- [x] **§3.15 NotificationService.publish modification** — Task 3 Step 2.
- [x] **§3.16 listingCancelledBySellerFanout modification** — Task 3 Step 3.
- [x] **§4.1 /settings route tree** — Task 8 Steps 1, 2, 8.
- [x] **§4.2 NotificationPreferencesPage layout** — Task 8 Step 7.
- [x] **§4.3 Disabled-state preservation invariant** — Task 8 Step 6 (component) + Step 12 (test) + Step 13 (page-level test).
- [x] **§4.4 Hooks** — Task 7 Steps 4, 5.
- [x] **§4.5 REST client** — Task 7 Step 3.
- [x] **§4.6 Settings entry points** — Task 8 Steps 9, 10.
- [x] **§4.7 MSW handlers** — Task 7 Step 6.
- [x] **§5.1–5.5 LSL script + READMEs** — Task 9 Steps 1–4.
- [x] **§6 Data flow scenarios** — implicit in the test coverage of Task 3 (5 vertical-slice integration tests covering all five scenarios) and Task 5 (cleanup canary).
- [x] **§7 API surface (5 endpoints + state machines)** — Task 4 Step 5 (internal controller); Task 6 Step 2 (preferences controller).
- [x] **§8 Error handling** — implicit in test coverage; explicit notes in dispatcher's try-catch (Task 3 Step 1) and controller's transition logic (Task 4 Step 5).
- [x] **§9 Testing strategy (4 tiers + e2e)** — Tier 1: Tasks 1, 2 (unit). Tier 2: Task 1 (DAO + repo + initializer). Tier 3: Task 3 (vertical-slice). Tier 4: Tasks 4, 6 (controller). Cleanup: Task 5 (with LogCaptor). Frontend: Tasks 7, 8.
- [x] **§10 Sub-spec boundaries** — Task 10 Step 1 (DEFERRED updates).
- [x] **§11 Deferred ledger updates** — Task 10 Step 1.
- [x] **§12 FOOTGUNS additions (4 entries)** — Task 10 Step 2.
- [x] **§13 Documentation updates (DESIGN.md §11, lsl-scripts READMEs, root CLAUDE.md)** — Task 10 Steps 3, 4; Task 9 Steps 1, 4.
- [x] **§13.7 Manual test plan** — Task 10 Step 9 (PR body).

**E2E contract test (`SlImEndToEndIntegrationTest`)** — spec §9.7 calls for one end-to-end test. **Gap:** the plan doesn't have a dedicated step for this. Add it as a Task 4 Step 8 follow-on or as a final integration test in Task 6. *Decision: add a Step in Task 4 after the controller tests are green, since it requires the controller endpoints to exist.*

To address the gap, here's the new step (insert into Task 4 between Step 7 and the existing Step 8/9):

> **Step 7b: Write `SlImEndToEndIntegrationTest`**
>
> An end-to-end contract test driving an OUTBID through `BidService.placeBid()`, then asserting (1) the IM row exists in `sl_im_message`, (2) `GET /pending` returns it via the controller, (3) `POST /{id}/delivered` transitions the row, (4) idempotent second `/delivered` returns 204 without re-stamping `delivered_at`. One test, slow (full Spring context + real Postgres + auction fixture from sub-spec 1's `BidPlacementIntegrationTest`), but the canary for "the contract from event → terminal works."
>
> The test file: `backend/src/test/java/com/slparcelauctions/backend/notification/slim/integration/SlImEndToEndIntegrationTest.java`. Reuse the auction-fixture pattern from Task 3's `OutbidImIntegrationTest` (which itself copies from `BidPlacementIntegrationTest`).

The implementer should add this step's test file when they reach Task 4. The plan as written already has the building blocks; this self-review note flags that the e2e test file should be created and added to the Task 4 commit's `git add` list.

### Placeholder scan

Scanned the plan for the prohibited patterns. The deliberate `throw new UnsupportedOperationException("copy pattern")` markers in Task 3's integration test fixtures (Steps 7, 9, 11) are **not** placeholders in the No-Placeholders sense — they are explicit instructions to the implementer to read the named neighbor file and copy its fixture pattern. The plan provides the test logic; the fixture-construction helpers are codebase-specific and reading the actual neighbor is the right move. Each marker has a "Note for the implementer" paragraph explaining what to copy and from where.

The `// ... existing keys ...` and `// ... existing matchers ...` comments in Tasks 4 and 5 (application.yml additions, WebSecurityConfig modification) are **not** placeholders — they document where to insert into existing files without rewriting the entire file. The implementer reads the existing file first; the plan tells them what to add and where.

`F.NN` in Task 10 Step 2 is a deliberate placeholder for the implementer to fill in based on the file's actual last-numbered entry. Spec §12 acknowledges this.

### Type consistency

- `SlImMessage` entity (Task 1) → `SlImMessageDao.UpsertResult` (Task 1) → `SlImMessageRepository.pollPending(int)` (Task 1) → `SlImInternalController.PendingItem` / `PendingResponse` (Task 4). Field names consistent.
- `SlImChannelGate.Decision` enum (Task 2) → `SlImChannelDispatcher.doQueue` switch (Task 3). All 5 enum values handled.
- `PreferencesDto` Java record (Task 6) → `PreferencesDto` TypeScript interface (Task 7). Field names match (`slImMuted`, `slIm`).
- `EditableGroup` TS type (Task 7) → `ALLOWED_GROUP_KEYS` Java set (Task 6) → MSW `ALLOWED` set (Task 7). All three list the same 5 keys.
- `NotificationEvent` record from sub-spec 1 → consumed by `SlImChannelDispatcher.maybeQueue` (Task 3 Step 1). Signatures match the existing record (`userId`, `category`, `title`, `body`, `data`, `coalesceKey`).
- `requiresNewTxTemplate` qualifier — sub-spec 1's `NotificationConfig` defines it; Task 3's `SlImChannelDispatcher` constructor uses the same qualifier name. Reused, not duplicated.

No drift detected.

### Consistency with brainstorm session

Every decision from the brainstorm landed in the plan:
- ✅ Option B for scope (backend + frontend + LSL in one sub-spec).
- ✅ lsl-scripts/ directory at project root with per-script READMEs.
- ✅ Quiet hours dropped (columns dormant).
- ✅ Coalescing option A (collapse during pendency).
- ✅ Skip queue if no avatar UUID.
- ✅ 48h TTL with INFO logging (`top_users` query before UPDATE).
- ✅ Architecture B (afterCommit hook).
- ✅ Preferences UI structure C (single column with banner).
- ✅ Hide SYSTEM / REALTY_GROUP / MARKETING from toggle grid.
- ✅ Reviews defaults to OFF.
- ✅ Route placement A (`/settings/notifications`).
- ✅ Message format `[SLPA] {title}\n\n{body}\n\n{deeplink}`.
- ✅ FAILED option β (separate enum value for triage).
- ✅ Stage 2 DELETE includes FAILED with 30d retention.
- ✅ Symmetric state machines for `/delivered` and `/failed` (sticky FAILED + EXPIRED → 409).
- ✅ Byte-budget builder with surrogate handling and 3 mandatory test cases.
- ✅ ESCROW_TRANSFER_REMINDER → Epic 10 (option β).
- ✅ FOR UPDATE SKIP LOCKED clarified as advisory + code comment requirement.

---

## Plan complete

Saved to `docs/superpowers/plans/2026-04-26-epic-09-sub-3-sl-im-and-preferences.md`.

10 tasks, ~80 new backend tests, ~25 new frontend tests, 1 LSL script + 2 READMEs + 1 config notecard template. Final commit on Task 10 Step 9 opens the PR; Step 10 explicitly stops short of merge.
