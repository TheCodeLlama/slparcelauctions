# Epic 09 Sub-Spec 1 — In-App Notifications & Real-Time Push Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Land the in-app channel of the notification system end-to-end: persistence, publisher API, real-time delivery via Spring user-destinations, bell + dropdown + dedicated feed page, and integration into every existing event source. Single PR to `dev`.

**Architecture:** New `notification/` backend package (entity + DAO + repo + service + publisher + controller + cleanup job + WS broadcasters + envelopes). One-line WebSocket config change to register `/queue` on the broker. One-line `/me` field addition. 13 in-tx publisher calls injected into existing services. Frontend adds `lib/notifications/`, `hooks/notifications/`, `components/notifications/` (bell + dropdown + feed), extends `ToastProvider` with `upsert(id, payload)` (timer reset on update), retires `OutbidToastProvider`. Closes six deferred-ledger entries.

**Tech Stack:** Spring Boot 4 / Java 26 / JPA `ddl-auto: update` / Spring Messaging (STOMP user destinations). Next.js 16 / React 19 / TanStack Query v5 / Vitest 4 / MSW 2 / Headless UI Popover.

**Spec:** `docs/superpowers/specs/2026-04-25-epic-09-sub-1-in-app-notifications.md`

**Branch:** `task/09-sub-1-in-app-notifications` → `dev`

---

## Pre-implementation findings (from spec self-review + codebase scout)

These were discovered during planning and the plan reflects them — they amend the spec where it was overspecified or missed something:

- **`JwtChannelInterceptor` requires no changes.** It already calls `accessor.setUser(StompAuthenticationToken)` on CONNECT (`backend/src/main/java/com/slparcelauctions/backend/auth/JwtChannelInterceptor.java:133`), and authenticated sessions can already subscribe to any destination (line 142-146). `/user/queue/**` works automatically.
- **`WebSocketConfig` needs a one-line broker change.** Currently `enableSimpleBroker("/topic")` only — must become `enableSimpleBroker("/topic", "/queue")` to route `convertAndSendToUser(userId, "/queue/X", ...)`.
- **`useStompSubscription` requires no changes.** It accepts arbitrary destinations including `/user/queue/...`. Spring server-side resolves the user routing.
- **24h transfer reminder integration deferred.** No `@Scheduled` job exists today. DESIGN.md §729 says the *bot* sends this for bot-verified listings (Epic 06 territory). The plan implements `publisher.escrowTransferReminder()` API + builder + test, but the integration touchpoint is captured as a deferred-ledger entry rather than wiring a new scheduler in this sub-spec. Sub-spec 2 or 3 can pick this up alongside the email/IM channels that benefit most from reminders.

---

## File structure

### New backend files

```
backend/src/main/java/com/slparcelauctions/backend/notification/
├── Notification.java                          — entity
├── NotificationCategory.java                  — enum (22 values)
├── NotificationGroup.java                     — enum (8 values)
├── NotificationRepository.java                — JPA queries (split filtered/unfiltered)
├── NotificationDao.java                       — native ON CONFLICT upsert
├── NotificationService.java                   — orchestration, transactional
├── NotificationEvent.java                     — record passed into publish()
├── NotificationPublisher.java                 — interface
├── NotificationPublisherImpl.java             — implementation
├── NotificationDataBuilder.java               — per-category data construction
├── NotificationCleanupJob.java                — @Scheduled retention
├── NotificationCleanupProperties.java         — @ConfigurationProperties
├── NotificationCoalesceIndexInitializer.java  — partial unique index setup
├── NotificationController.java                — /api/v1/notifications
├── exception/NotFoundException.java            — if no project-wide one exists
├── dto/
│   ├── NotificationDto.java
│   └── UnreadCountResponse.java
└── ws/
    ├── NotificationWsBroadcaster.java         — /user/queue/notifications
    ├── AccountStateBroadcaster.java           — /user/queue/account
    └── envelope/
        ├── NotificationUpsertedEnvelope.java
        ├── ReadStateChangedEnvelope.java
        └── PenaltyClearedEnvelope.java
```

### Modified backend files

- `config/WebSocketConfig.java` — add `/queue` to broker.
- `user/dto/UserResponse.java` — add `unreadNotificationCount`.
- `user/UserService.java` — populate the new field.
- `bid/BidService.java` — `publisher.outbid` + `publisher.proxyExhausted`.
- `auction/AuctionSettlementService.java` — six per-outcome publisher calls.
- `escrow/EscrowService.java` — `publisher.escrowFunded / TransferConfirmed / Payout / Expired / Disputed / Frozen / PayoutStalled`.
- `auction/SuspensionService.java` — `publisher.listingSuspended` + `publisher.listingReviewRequired`.
- `parcel/ParcelVerificationService.java` — `publisher.listingVerified`.
- `review/ReviewService.java` — `publisher.reviewReceived`.
- `auction/CancellationService.java` — `publisher.listingCancelledBySellerFanout`.
- `escrow/command/TerminalCommandService.java` — `publisher.escrowPayoutStalled` (when `requiresManualReview` flips).
- `escrow/PenaltyTerminalService.java` — `accountBroadcaster.broadcastPenaltyCleared`.
- `application.yml` — `slpa.notifications.cleanup.*`.

### New frontend files

```
frontend/src/lib/notifications/
├── api.ts                 — REST client
├── queryKeys.ts           — TanStack Query cache key factory
├── types.ts               — NotificationDto, NotificationCategory, group, data union
└── categoryMap.ts         — category → renderer config

frontend/src/hooks/notifications/
├── useNotifications.ts
├── useUnreadCount.ts
├── useMarkRead.ts
├── useMarkAllRead.ts
└── useNotificationStream.ts

frontend/src/components/notifications/
├── NotificationBell.tsx
├── NotificationDropdown.tsx
├── NotificationDropdownRow.tsx
├── FilterChips.tsx
└── feed/
    ├── FeedShell.tsx
    ├── FeedSidebar.tsx
    ├── FeedList.tsx
    └── FeedRow.tsx

frontend/src/app/notifications/page.tsx        — /notifications route
```

### Modified frontend files

- `components/layout/Header.tsx:54-56` — replace `<IconButton><Bell/></IconButton>` placeholder with `<NotificationBell/>`.
- `components/ui/Toast/ToastProvider.tsx` — add `upsert(id, payload)` with timer reset.
- `lib/user/api.ts` — add `unreadNotificationCount: number` to user type.
- `app/providers.tsx` — mount `useNotificationStream` high in tree.
- `test/msw/fixtures.ts` — add `unreadNotificationCount: 0` to default user fixture.
- `test/msw/handlers.ts` — add `/api/v1/notifications/*` handlers.

### Deleted frontend files

- `components/auction/OutbidToastProvider.tsx` (and `.test.tsx`) — superseded by central stream.
- Any callsites that import `OutbidToastProvider` (likely `AuctionDetailClient.tsx`).

### Documentation updates

- `docs/implementation/DEFERRED_WORK.md` — remove 6 entries, add 5 new (one being the transfer reminder scheduler).
- `docs/implementation/FOOTGUNS.md` — add 3 entries.
- `docs/initial-design/DESIGN.md` §11 — light wording edits.

---

## Task 1 — Backend foundation: entity + DAO + repo + service + initializer

**Goal:** Persistence and orchestration layer. `Notification` entity with partial unique index, `NotificationDao` for ON CONFLICT upsert, `NotificationRepository` with split filtered/unfiltered queries, `NotificationService` for the publish/markRead/list contract. NO publisher API yet (Task 3), NO controller (Task 2), NO WS (Task 4). Pure data-layer + service.

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/notification/Notification.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/notification/NotificationCategory.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/notification/NotificationGroup.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/notification/NotificationRepository.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/notification/NotificationDao.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/notification/NotificationService.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/notification/NotificationEvent.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/notification/NotificationCoalesceIndexInitializer.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/notification/dto/NotificationDto.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/notification/NotificationDaoUpsertTest.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/notification/NotificationRepositoryTest.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/notification/NotificationServiceTest.java`

### Steps

- [ ] **Step 1: Create the `NotificationGroup` enum**

```java
package com.slparcelauctions.backend.notification;

import java.util.EnumSet;
import java.util.Set;

public enum NotificationGroup {
    BIDDING,
    AUCTION_RESULT,
    ESCROW,
    LISTING_STATUS,
    REVIEWS,
    REALTY_GROUP,
    MARKETING,
    SYSTEM;

    public Set<NotificationCategory> categories() {
        return EnumSet.copyOf(
            java.util.Arrays.stream(NotificationCategory.values())
                .filter(c -> c.getGroup() == this)
                .toList()
        );
    }
}
```

- [ ] **Step 2: Create the `NotificationCategory` enum (22 values)**

```java
package com.slparcelauctions.backend.notification;

public enum NotificationCategory {
    OUTBID(NotificationGroup.BIDDING),
    PROXY_EXHAUSTED(NotificationGroup.BIDDING),

    AUCTION_WON(NotificationGroup.AUCTION_RESULT),
    AUCTION_LOST(NotificationGroup.AUCTION_RESULT),
    AUCTION_ENDED_SOLD(NotificationGroup.AUCTION_RESULT),
    AUCTION_ENDED_RESERVE_NOT_MET(NotificationGroup.AUCTION_RESULT),
    AUCTION_ENDED_NO_BIDS(NotificationGroup.AUCTION_RESULT),
    AUCTION_ENDED_BOUGHT_NOW(NotificationGroup.AUCTION_RESULT),

    ESCROW_FUNDED(NotificationGroup.ESCROW),
    ESCROW_TRANSFER_CONFIRMED(NotificationGroup.ESCROW),
    ESCROW_PAYOUT(NotificationGroup.ESCROW),
    ESCROW_EXPIRED(NotificationGroup.ESCROW),
    ESCROW_DISPUTED(NotificationGroup.ESCROW),
    ESCROW_FROZEN(NotificationGroup.ESCROW),
    ESCROW_PAYOUT_STALLED(NotificationGroup.ESCROW),
    ESCROW_TRANSFER_REMINDER(NotificationGroup.ESCROW),

    LISTING_VERIFIED(NotificationGroup.LISTING_STATUS),
    LISTING_SUSPENDED(NotificationGroup.LISTING_STATUS),
    LISTING_REVIEW_REQUIRED(NotificationGroup.LISTING_STATUS),
    LISTING_CANCELLED_BY_SELLER(NotificationGroup.LISTING_STATUS),

    REVIEW_RECEIVED(NotificationGroup.REVIEWS),

    SYSTEM_ANNOUNCEMENT(NotificationGroup.SYSTEM);

    private final NotificationGroup group;
    NotificationCategory(NotificationGroup group) { this.group = group; }
    public NotificationGroup getGroup() { return group; }
}
```

- [ ] **Step 3: Create the `Notification` entity**

```java
package com.slparcelauctions.backend.notification;

import com.slparcelauctions.backend.user.User;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.Map;
import lombok.*;
import org.hibernate.annotations.*;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
    name = "notification",
    indexes = {
        @Index(name = "ix_notification_user_unread_created",
               columnList = "user_id, read, created_at DESC"),
        @Index(name = "ix_notification_user_updated",
               columnList = "user_id, updated_at DESC")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(length = 64, nullable = false)
    private NotificationCategory category;

    @Column(length = 200, nullable = false)
    private String title;

    @Column(length = 500, nullable = false)
    private String body;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> data;

    @Column(name = "coalesce_key", length = 160)
    private String coalesceKey;

    @Builder.Default
    @Column(nullable = false, columnDefinition = "boolean not null default false")
    private Boolean read = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
```

- [ ] **Step 4: Create the `NotificationCoalesceIndexInitializer`**

Mirrors `EscrowTransactionTypeCheckConstraintInitializer` from Epic 08 sub-spec 2.

```java
package com.slparcelauctions.backend.notification;

import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Creates the partial unique index that backs notification coalescing:
 * {@code (user_id, coalesce_key) WHERE read = false}. Hibernate's
 * {@code ddl-auto: update} cannot emit partial indexes, so this initializer
 * runs the DDL once on app startup. Idempotent via {@code IF NOT EXISTS}.
 *
 * <p>Without this index, the ON CONFLICT clause in {@link NotificationDao}
 * has nothing to match against and every UPSERT becomes a plain INSERT —
 * the coalesce semantics break silently.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationCoalesceIndexInitializer {

    private final DataSource dataSource;

    @EventListener(ApplicationReadyEvent.class)
    public void ensureIndex() {
        new JdbcTemplate(dataSource).execute("""
            CREATE UNIQUE INDEX IF NOT EXISTS uq_notification_unread_coalesce
            ON notification (user_id, coalesce_key)
            WHERE read = false
            """);
        log.info("notification: ensured partial unique index uq_notification_unread_coalesce");
    }
}
```

- [ ] **Step 5: Create the `NotificationDto` record**

```java
package com.slparcelauctions.backend.notification.dto;

import com.slparcelauctions.backend.notification.Notification;
import com.slparcelauctions.backend.notification.NotificationCategory;
import com.slparcelauctions.backend.notification.NotificationGroup;
import java.time.OffsetDateTime;
import java.util.Map;

public record NotificationDto(
    Long id,
    NotificationCategory category,
    NotificationGroup group,
    String title,
    String body,
    Map<String, Object> data,
    Boolean read,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
    public static NotificationDto from(Notification n) {
        return new NotificationDto(
            n.getId(),
            n.getCategory(),
            n.getCategory().getGroup(),
            n.getTitle(),
            n.getBody(),
            n.getData(),
            n.getRead(),
            n.getCreatedAt(),
            n.getUpdatedAt()
        );
    }
}
```

- [ ] **Step 6: Create the `NotificationEvent` record**

```java
package com.slparcelauctions.backend.notification;

import java.util.Map;

public record NotificationEvent(
    long userId,
    NotificationCategory category,
    String title,
    String body,
    Map<String, Object> data,
    String coalesceKey
) {}
```

- [ ] **Step 7: Create the `NotificationRepository` interface (split queries)**

```java
package com.slparcelauctions.backend.notification;

import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    @Query("SELECT n FROM Notification n WHERE n.user.id = :userId " +
           "AND n.category IN :categoriesInGroup " +
           "AND (:unreadOnly = false OR n.read = false) " +
           "ORDER BY n.updatedAt DESC, n.id DESC")
    Page<Notification> findForUserByGroup(
        @Param("userId") Long userId,
        @Param("categoriesInGroup") Collection<NotificationCategory> categoriesInGroup,
        @Param("unreadOnly") boolean unreadOnly,
        Pageable pageable);

    @Query("SELECT n FROM Notification n WHERE n.user.id = :userId " +
           "AND (:unreadOnly = false OR n.read = false) " +
           "ORDER BY n.updatedAt DESC, n.id DESC")
    Page<Notification> findForUserUnfiltered(
        @Param("userId") Long userId,
        @Param("unreadOnly") boolean unreadOnly,
        Pageable pageable);

    long countByUserIdAndReadFalse(Long userId);

    boolean existsByIdAndUserId(Long id, Long userId);

    @Query("SELECT n.category, COUNT(n) FROM Notification n " +
           "WHERE n.user.id = :userId AND n.read = false GROUP BY n.category")
    List<Object[]> countUnreadByCategoryForUser(@Param("userId") Long userId);

    @Modifying
    @Query("UPDATE Notification n SET n.read = true " +
           "WHERE n.id = :id AND n.user.id = :userId AND n.read = false")
    int markRead(@Param("id") Long id, @Param("userId") Long userId);

    @Modifying
    @Query("UPDATE Notification n SET n.read = true " +
           "WHERE n.user.id = :userId AND n.read = false " +
           "AND n.category IN :categoriesInGroup")
    int markAllReadByGroup(
        @Param("userId") Long userId,
        @Param("categoriesInGroup") Collection<NotificationCategory> categoriesInGroup);

    @Modifying
    @Query("UPDATE Notification n SET n.read = true " +
           "WHERE n.user.id = :userId AND n.read = false")
    int markAllReadUnfiltered(@Param("userId") Long userId);
}
```

- [ ] **Step 8: Create the `NotificationDao` (native ON CONFLICT upsert)**

```java
package com.slparcelauctions.backend.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Native-SQL upsert wrapper for the coalesce primitive. JPA's @Modifying
 * doesn't return values; the ON CONFLICT clause requires a partial unique
 * index that JPA can't model. This DAO owns the single write path that all
 * publish() calls funnel through.
 *
 * <p>{@code xmax = 0} for fresh inserts, {@code xmax = current_txid} for
 * updates — Postgres-native way to know which path executed without a
 * second roundtrip. See FOOTGUNS for "ON CONFLICT, not retry-on-violation".
 */
@Component
@RequiredArgsConstructor
public class NotificationDao {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public record UpsertResult(
        long id,
        boolean wasUpdate,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
    ) {}

    public UpsertResult upsert(
        long userId,
        NotificationCategory category,
        String title,
        String body,
        Map<String, Object> data,
        String coalesceKey
    ) {
        String dataJson;
        try {
            dataJson = objectMapper.writeValueAsString(data == null ? Map.of() : data);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("notification data not serializable", e);
        }

        String sql = """
            INSERT INTO notification
              (user_id, category, title, body, data, coalesce_key, read, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?::jsonb, ?, false, now(), now())
            ON CONFLICT (user_id, coalesce_key) WHERE read = false
            DO UPDATE SET
              title = EXCLUDED.title,
              body = EXCLUDED.body,
              data = EXCLUDED.data,
              updated_at = now()
            RETURNING id, (xmax != 0) AS was_update, created_at, updated_at
            """;

        Map<String, Object> row = jdbc.queryForMap(
            sql,
            userId,
            category.name(),
            title,
            body,
            dataJson,
            coalesceKey
        );

        return new UpsertResult(
            ((Number) row.get("id")).longValue(),
            (Boolean) row.get("was_update"),
            ((java.sql.Timestamp) row.get("created_at")).toInstant().atOffset(java.time.ZoneOffset.UTC),
            ((java.sql.Timestamp) row.get("updated_at")).toInstant().atOffset(java.time.ZoneOffset.UTC)
        );
    }
}
```

- [ ] **Step 9: Create the `NotificationService` (orchestration, no broadcaster yet)**

The service depends on a broadcaster interface that lands in Task 4. For this task, define a tiny `NotificationWsBroadcasterPort` interface inline (or just use a `Consumer<UpsertNotice>` field) so Task 1 can be tested in isolation. Concrete broadcaster lands in Task 4.

```java
package com.slparcelauctions.backend.notification;

import com.slparcelauctions.backend.notification.NotificationDao.UpsertResult;
import com.slparcelauctions.backend.notification.dto.NotificationDto;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class NotificationService {

    private final NotificationDao dao;
    private final NotificationRepository repo;
    private final NotificationWsBroadcasterPort wsBroadcaster;
    // ↑ Port interface defined in this package (Task 4 wires the real impl).

    @Transactional(propagation = Propagation.MANDATORY)
    public UpsertResult publish(NotificationEvent event) {
        UpsertResult result = dao.upsert(
            event.userId(),
            event.category(),
            event.title(),
            event.body(),
            event.data(),
            event.coalesceKey()
        );

        long userId = event.userId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                wsBroadcaster.broadcastUpsert(userId, result, dtoFromUpsert(event, result));
            }
        });
        return result;
    }

    public void markRead(long userId, long notificationId) {
        int affected = repo.markRead(notificationId, userId);
        if (affected == 0) {
            if (!repo.existsByIdAndUserId(notificationId, userId)) {
                throw new NoSuchElementException("notification not found");
            }
            return; // already-read; idempotent, no broadcast
        }
        registerReadStateBroadcast(userId);
    }

    public int markAllRead(long userId, NotificationGroup group) {
        int affected = (group != null)
            ? repo.markAllReadByGroup(userId, group.categories())
            : repo.markAllReadUnfiltered(userId);
        if (affected > 0) {
            registerReadStateBroadcast(userId);
        }
        return affected;
    }

    private void registerReadStateBroadcast(long userId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                wsBroadcaster.broadcastReadStateChanged(userId);
            }
        });
    }

    @Transactional(readOnly = true)
    public long unreadCount(long userId) {
        return repo.countByUserIdAndReadFalse(userId);
    }

    @Transactional(readOnly = true)
    public Map<NotificationCategory, Long> unreadCountByCategory(long userId) {
        Map<NotificationCategory, Long> out = new HashMap<>();
        for (Object[] row : repo.countUnreadByCategoryForUser(userId)) {
            out.put((NotificationCategory) row[0], ((Number) row[1]).longValue());
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Page<NotificationDto> listFor(long userId, NotificationGroup group,
                                         boolean unreadOnly, Pageable pageable) {
        Page<Notification> page = (group != null)
            ? repo.findForUserByGroup(userId, group.categories(), unreadOnly, pageable)
            : repo.findForUserUnfiltered(userId, unreadOnly, pageable);
        return page.map(NotificationDto::from);
    }

    private NotificationDto dtoFromUpsert(NotificationEvent event, UpsertResult result) {
        // The upsert returned id + timestamps; the rest of the row mirrors the input event.
        return new NotificationDto(
            result.id(),
            event.category(),
            event.category().getGroup(),
            event.title(),
            event.body(),
            event.data(),
            false,
            result.createdAt(),
            result.updatedAt()
        );
    }
}
```

Inline port interface (to be implemented in Task 4):

```java
// backend/src/main/java/com/slparcelauctions/backend/notification/NotificationWsBroadcasterPort.java
package com.slparcelauctions.backend.notification;

import com.slparcelauctions.backend.notification.NotificationDao.UpsertResult;
import com.slparcelauctions.backend.notification.dto.NotificationDto;

public interface NotificationWsBroadcasterPort {
    void broadcastUpsert(long userId, UpsertResult result, NotificationDto dto);
    void broadcastReadStateChanged(long userId);
}
```

- [ ] **Step 10: Write the `NotificationDaoUpsertTest` (real Postgres via TestContainers)**

```java
package com.slparcelauctions.backend.notification;

// imports omitted for brevity in this plan — follow the project's
// existing TestContainers integration test style.

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
    "slpa.notifications.cleanup.enabled=false",
    "slpa.escrow.scheduler.enabled=false",
    "slpa.escrow.timeout.enabled=false",
    "slpa.escrow.terminal-dispatcher.enabled=false",
    "slpa.refresh-token-cleanup.enabled=false"
})
class NotificationDaoUpsertTest {

    @Autowired NotificationDao dao;
    @Autowired NotificationRepository repo;
    @Autowired UserRepository userRepo;

    private User user;

    @BeforeEach
    void seed() {
        user = userRepo.save(testUser("alice"));
    }

    @Test
    void insertsWhenNoExistingUnreadRow() {
        var result = dao.upsert(user.getId(), NotificationCategory.OUTBID,
            "title", "body", Map.of("k", "v"), "outbid:1:42");
        assertThat(result.wasUpdate()).isFalse();
        assertThat(repo.count()).isEqualTo(1L);
    }

    @Test
    void updatesWhenUnreadCoalesceKeyMatches() {
        var first = dao.upsert(user.getId(), NotificationCategory.OUTBID,
            "first", "body1", Map.of("bid", 100), "outbid:1:42");

        var second = dao.upsert(user.getId(), NotificationCategory.OUTBID,
            "second", "body2", Map.of("bid", 200), "outbid:1:42");

        assertThat(second.wasUpdate()).isTrue();
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(repo.count()).isEqualTo(1L);
        assertThat(second.createdAt()).isEqualTo(first.createdAt()); // preserved
        assertThat(second.updatedAt()).isAfterOrEqualTo(first.updatedAt());

        Notification stored = repo.findById(first.id()).orElseThrow();
        assertThat(stored.getTitle()).isEqualTo("second");
        assertThat(stored.getData()).containsEntry("bid", 200);
    }

    @Test
    void insertsWhenOnlyMatchIsRead() {
        var first = dao.upsert(user.getId(), NotificationCategory.OUTBID,
            "first", "body", Map.of(), "outbid:1:42");

        // Mark read — releases the coalesce key.
        repo.markRead(first.id(), user.getId());

        var second = dao.upsert(user.getId(), NotificationCategory.OUTBID,
            "second", "body", Map.of(), "outbid:1:42");

        assertThat(second.wasUpdate()).isFalse();
        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(repo.count()).isEqualTo(2L);
    }

    @Test
    void nullCoalesceKeyNeverConflicts() {
        var first = dao.upsert(user.getId(), NotificationCategory.AUCTION_WON,
            "won", "body", Map.of(), null);
        var second = dao.upsert(user.getId(), NotificationCategory.AUCTION_WON,
            "won-again", "body", Map.of(), null);

        assertThat(first.wasUpdate()).isFalse();
        assertThat(second.wasUpdate()).isFalse();
        assertThat(repo.count()).isEqualTo(2L);
    }

    @Test
    void concurrentUpsertOnSameKeyEndsAsOneRow() throws Exception {
        var executor = Executors.newFixedThreadPool(8);
        var latch = new CountDownLatch(8);
        var results = new CopyOnWriteArrayList<UpsertResult>();

        for (int i = 0; i < 8; i++) {
            int n = i;
            executor.submit(() -> {
                try {
                    results.add(dao.upsert(user.getId(), NotificationCategory.OUTBID,
                        "title-" + n, "body", Map.of("n", n), "outbid:1:42"));
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(repo.count()).isEqualTo(1L);
        long inserts = results.stream().filter(r -> !r.wasUpdate()).count();
        long updates = results.stream().filter(UpsertResult::wasUpdate).count();
        assertThat(inserts).isEqualTo(1L);
        assertThat(updates).isEqualTo(7L);
    }
}
```

- [ ] **Step 11: Run the DAO tests — expect all to fail until index initializer runs against the test container**

Run: `./mvnw test -Dtest=NotificationDaoUpsertTest`
Expected: tests fail (no Notification table or ON CONFLICT clause has nothing to match against). The index initializer runs on `ApplicationReadyEvent` — `@SpringBootTest` triggers it, so failures should be on missing index → that's a regression check that the initializer is wired.

After verifying failure on initializer absence → uncomment/fix initializer wiring → re-run → expect all 5 tests pass.

- [ ] **Step 12: Write the `NotificationRepositoryTest`**

Cover the split queries and the partial-index-aware behaviors:

```java
@Test void findForUserByGroup_filtersByCategoriesInGroup() {
    // seed three notifications across two groups for one user, two for another user
    // call findForUserByGroup with BIDDING — expect only OUTBID/PROXY_EXHAUSTED rows for that user
}

@Test void findForUserUnfiltered_returnsAllForUser() {
    // seed five notifications across multiple groups
    // call findForUserUnfiltered with unreadOnly=false — expect all five
    // call again with unreadOnly=true after marking some read — expect only unread
}

@Test void findForUser_sortsByUpdatedAtDescThenIdDesc() {
    // seed three rows, then upsert the oldest one to bump its updatedAt
    // expect the upserted one to be first in the result, even though its created_at is oldest
}

@Test void countUnreadByCategoryForUser_aggregatesCorrectly() {
    // seed: 2 OUTBID, 1 AUCTION_WON, 1 OUTBID read
    // expect: { OUTBID: 2, AUCTION_WON: 1 }
}

@Test void markRead_isIdempotent() {
    var n = save(makeUnread());
    assertThat(repo.markRead(n.getId(), user.getId())).isEqualTo(1);
    assertThat(repo.markRead(n.getId(), user.getId())).isEqualTo(0); // already read
}

@Test void markAllReadByGroup_onlyAffectsThatGroup() {
    // seed unread rows across three groups
    // markAllReadByGroup BIDDING
    // expect only BIDDING rows marked read
}

@Test void existsByIdAndUserId_returnsFalseForOtherUser() {
    var n = save(makeUnread()); // owned by alice
    assertThat(repo.existsByIdAndUserId(n.getId(), bob.getId())).isFalse();
    assertThat(repo.existsByIdAndUserId(n.getId(), alice.getId())).isTrue();
}
```

- [ ] **Step 13: Run repo tests — expect pass**

Run: `./mvnw test -Dtest=NotificationRepositoryTest`
Expected: all tests pass.

- [ ] **Step 14: Write the `NotificationServiceTest`**

Use `@MockBean` for `NotificationWsBroadcasterPort` and a real `NotificationDao` + repo. Cover:

```java
@Test void publish_writesInTxAndRegistersAfterCommitBroadcast() {
    transactionTemplate.execute(status -> {
        service.publish(new NotificationEvent(
            user.getId(), NotificationCategory.OUTBID,
            "title", "body", Map.of(), "outbid:1:42"));
        // Inside tx — broadcaster MUST NOT have been called yet.
        verify(broadcasterPort, never()).broadcastUpsert(anyLong(), any(), any());
        return null;
    });
    // After commit — broadcast fires.
    verify(broadcasterPort).broadcastUpsert(eq(user.getId()), any(), any());
}

@Test void publish_whenParentRollsBackNotificationAlsoRollsBack() {
    try {
        transactionTemplate.execute(status -> {
            service.publish(new NotificationEvent(
                user.getId(), NotificationCategory.OUTBID,
                "title", "body", Map.of(), null));
            status.setRollbackOnly();
            return null;
        });
    } catch (Exception ignored) {}
    assertThat(repo.count()).isEqualTo(0L);
    verify(broadcasterPort, never()).broadcastUpsert(anyLong(), any(), any());
}

@Test void publish_outsideTransaction_throws() {
    // No @Transactional wrapper — should throw IllegalTransactionStateException
    assertThatThrownBy(() -> service.publish(new NotificationEvent(
        user.getId(), NotificationCategory.OUTBID,
        "title", "body", Map.of(), null)))
        .isInstanceOf(IllegalTransactionStateException.class);
}

@Test void markRead_idempotentOnAlreadyRead() {
    var n = seedRead();
    service.markRead(user.getId(), n.getId()); // no-op
    verify(broadcasterPort, never()).broadcastReadStateChanged(anyLong());
}

@Test void markRead_onCrossUserRow_throwsNoSuchElement() {
    var n = seedFor(otherUser);
    assertThatThrownBy(() -> service.markRead(user.getId(), n.getId()))
        .isInstanceOf(NoSuchElementException.class);
}

@Test void markRead_onUnread_firesBroadcastAfterCommit() {
    var n = seedUnread();
    transactionTemplate.execute(status -> {
        service.markRead(user.getId(), n.getId());
        verify(broadcasterPort, never()).broadcastReadStateChanged(anyLong());
        return null;
    });
    verify(broadcasterPort).broadcastReadStateChanged(user.getId());
}

@Test void markAllRead_withGroupFilter_onlyMarksThatGroup() {
    seedUnread(NotificationCategory.OUTBID);          // BIDDING
    seedUnread(NotificationCategory.AUCTION_WON);     // AUCTION_RESULT
    int affected = txExecute(() -> service.markAllRead(user.getId(), NotificationGroup.BIDDING));
    assertThat(affected).isEqualTo(1);
    assertThat(unread(user, NotificationCategory.OUTBID)).isEmpty();
    assertThat(unread(user, NotificationCategory.AUCTION_WON)).hasSize(1);
}
```

- [ ] **Step 15: Run service tests — expect pass**

Run: `./mvnw test -Dtest=NotificationServiceTest`
Expected: all tests pass.

- [ ] **Step 16: Run the full backend test suite — expect no regressions**

Run: `./mvnw test -pl backend`
Expected: 1072 + new tests, all green.

- [ ] **Step 17: Commit**

```bash
git checkout -b task/09-sub-1-in-app-notifications
git add backend/src/main/java/com/slparcelauctions/backend/notification/Notification.java \
        backend/src/main/java/com/slparcelauctions/backend/notification/NotificationCategory.java \
        backend/src/main/java/com/slparcelauctions/backend/notification/NotificationGroup.java \
        backend/src/main/java/com/slparcelauctions/backend/notification/NotificationRepository.java \
        backend/src/main/java/com/slparcelauctions/backend/notification/NotificationDao.java \
        backend/src/main/java/com/slparcelauctions/backend/notification/NotificationService.java \
        backend/src/main/java/com/slparcelauctions/backend/notification/NotificationEvent.java \
        backend/src/main/java/com/slparcelauctions/backend/notification/NotificationCoalesceIndexInitializer.java \
        backend/src/main/java/com/slparcelauctions/backend/notification/NotificationWsBroadcasterPort.java \
        backend/src/main/java/com/slparcelauctions/backend/notification/dto/NotificationDto.java \
        backend/src/test/java/com/slparcelauctions/backend/notification/
git commit -m "feat(notifications): entity + DAO + repo + service foundation"
```

---

## Task 2 — REST controller + /me extension + cleanup job + properties

**Goal:** Land the user-facing REST surface (`/api/v1/notifications/*`), extend `/me` with `unreadNotificationCount` for first-paint badge accuracy, and add the 90-day retention cleanup job. Still no publisher API — that's Task 3.

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/notification/NotificationController.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/notification/dto/UnreadCountResponse.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/notification/NotificationCleanupJob.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/notification/NotificationCleanupProperties.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/user/dto/UserResponse.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/user/UserService.java`
- Modify: `backend/src/main/resources/application.yml`
- Test: `backend/src/test/java/com/slparcelauctions/backend/notification/NotificationControllerIntegrationTest.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/notification/NotificationCleanupJobTest.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/user/UserResponseUnreadCountTest.java`

### Steps

- [ ] **Step 1: Add `unreadNotificationCount` to `UserResponse`**

Open `backend/src/main/java/com/slparcelauctions/backend/user/dto/UserResponse.java` and add the field (record component) at the END of the parameter list to preserve positional construction order in any existing builder usage. Update any factory methods.

```java
public record UserResponse(
    // ... existing fields ...
    long unreadNotificationCount    // NEW — last field
) {}
```

- [ ] **Step 2: Populate the new field in `UserService.toResponse`**

Inject `NotificationService` into `UserService`. In `toResponse(User user)`:

```java
long unreadCount = notificationService.unreadCount(user.getId());
return new UserResponse(
    // ... existing fields ...
    unreadCount
);
```

- [ ] **Step 3: Write `UserResponseUnreadCountTest`**

```java
@SpringBootTest
@TestPropertySource(properties = {
    "slpa.notifications.cleanup.enabled=false",
    // ... other scheduler disables
})
class UserResponseUnreadCountTest {

    @Autowired UserService userService;
    @Autowired NotificationDao notificationDao;
    @Autowired UserRepository userRepo;

    @Test
    void unreadCount_zeroForFreshUser() {
        User u = userRepo.save(testUser("alice"));
        UserResponse r = userService.toResponse(u);
        assertThat(r.unreadNotificationCount()).isEqualTo(0L);
    }

    @Test
    void unreadCount_countsOnlyUnreadOwnedRows() {
        User alice = userRepo.save(testUser("alice"));
        User bob = userRepo.save(testUser("bob"));

        notificationDao.upsert(alice.getId(), NotificationCategory.OUTBID, "t", "b", Map.of(), null);
        notificationDao.upsert(alice.getId(), NotificationCategory.AUCTION_WON, "t", "b", Map.of(), null);
        notificationDao.upsert(bob.getId(), NotificationCategory.OUTBID, "t", "b", Map.of(), null);

        UserResponse r = userService.toResponse(alice);
        assertThat(r.unreadNotificationCount()).isEqualTo(2L);
    }
}
```

- [ ] **Step 4: Run the UserResponse test — expect pass**

Run: `./mvnw test -Dtest=UserResponseUnreadCountTest`
Expected: green.

- [ ] **Step 5: Create the `UnreadCountResponse` DTO**

```java
package com.slparcelauctions.backend.notification.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

public record UnreadCountResponse(
    long count,
    @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, Long> byGroup
) {
    public static UnreadCountResponse of(long count) {
        return new UnreadCountResponse(count, null);
    }
    public static UnreadCountResponse withBreakdown(long count, Map<String, Long> byGroup) {
        return new UnreadCountResponse(count, byGroup);
    }
}
```

- [ ] **Step 6: Create the `NotificationController`**

```java
package com.slparcelauctions.backend.notification;

import com.slparcelauctions.backend.auth.AuthUser;
import com.slparcelauctions.backend.common.PagedResponse;
import com.slparcelauctions.backend.notification.dto.NotificationDto;
import com.slparcelauctions.backend.notification.dto.UnreadCountResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping
    public PagedResponse<NotificationDto> list(
        @AuthenticationPrincipal AuthUser caller,
        @RequestParam(required = false) NotificationGroup group,
        @RequestParam(defaultValue = "false") boolean unreadOnly,
        @RequestParam(defaultValue = "0") @Min(0) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        Page<NotificationDto> result = notificationService.listFor(
            caller.userId(), group, unreadOnly, PageRequest.of(page, size));
        return PagedResponse.from(result);
    }

    @GetMapping("/unread-count")
    public UnreadCountResponse unreadCount(
        @AuthenticationPrincipal AuthUser caller,
        @RequestParam(required = false) String breakdown
    ) {
        long total = notificationService.unreadCount(caller.userId());
        if (!"group".equals(breakdown)) {
            return UnreadCountResponse.of(total);
        }
        Map<NotificationCategory, Long> byCategory =
            notificationService.unreadCountByCategory(caller.userId());
        Map<String, Long> byGroup = new HashMap<>();
        for (NotificationGroup g : NotificationGroup.values()) {
            byGroup.put(g.name().toLowerCase(), 0L);
        }
        byCategory.forEach((cat, count) ->
            byGroup.merge(cat.getGroup().name().toLowerCase(), count, Long::sum));
        return UnreadCountResponse.withBreakdown(total, byGroup);
    }

    @PutMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(@AuthenticationPrincipal AuthUser caller, @PathVariable long id) {
        notificationService.markRead(caller.userId(), id);
    }

    @PutMapping("/read-all")
    public Map<String, Integer> markAllRead(
        @AuthenticationPrincipal AuthUser caller,
        @RequestParam(required = false) NotificationGroup group
    ) {
        int markedRead = notificationService.markAllRead(caller.userId(), group);
        return Map.of("markedRead", markedRead);
    }
}
```

If `NoSuchElementException` is not already mapped to 404 by `GlobalExceptionHandler`, add a handler:

```java
@ExceptionHandler(NoSuchElementException.class)
public ResponseEntity<ProblemDetail> handleNotFound(NoSuchElementException ex) {
    ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(pd);
}
```

(Check whether one already exists for similar 404 cases — `NotVerifiedException` may have set the precedent.)

- [ ] **Step 7: Write `NotificationControllerIntegrationTest`**

```java
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = { "slpa.notifications.cleanup.enabled=false", /*...*/ })
class NotificationControllerIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired NotificationDao dao;
    @Autowired NotificationRepository repo;
    @Autowired UserRepository userRepo;
    @Autowired JwtService jwt;

    private User alice, bob;
    private String aliceJwt, bobJwt;

    @BeforeEach
    void seed() {
        alice = userRepo.save(testUser("alice"));
        bob = userRepo.save(testUser("bob"));
        aliceJwt = jwt.issueAccess(alice).token();
        bobJwt = jwt.issueAccess(bob).token();
    }

    @Test
    void list_returnsPagedResponseSortedByUpdatedAtDesc() throws Exception {
        dao.upsert(alice.getId(), OUTBID, "first", "b", Map.of(), null);
        Thread.sleep(10);
        dao.upsert(alice.getId(), AUCTION_WON, "second", "b", Map.of(), null);

        mvc.perform(get("/api/v1/notifications").header("Authorization", "Bearer " + aliceJwt))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].title").value("second"))
            .andExpect(jsonPath("$.content[1].title").value("first"));
    }

    @Test
    void list_unreadOnlyTrue_filtersOutReadRows() throws Exception {
        var n = dao.upsert(alice.getId(), OUTBID, "t", "b", Map.of(), null);
        repo.markRead(n.id(), alice.getId());
        dao.upsert(alice.getId(), AUCTION_WON, "t2", "b", Map.of(), null);

        mvc.perform(get("/api/v1/notifications?unreadOnly=true")
                .header("Authorization", "Bearer " + aliceJwt))
            .andExpect(jsonPath("$.content").value(hasSize(1)))
            .andExpect(jsonPath("$.content[0].category").value("AUCTION_WON"));
    }

    @Test
    void list_groupFilter_returnsOnlyThatGroupsCategories() throws Exception {
        dao.upsert(alice.getId(), OUTBID, "bidding", "b", Map.of(), null);
        dao.upsert(alice.getId(), AUCTION_WON, "result", "b", Map.of(), null);

        mvc.perform(get("/api/v1/notifications?group=BIDDING")
                .header("Authorization", "Bearer " + aliceJwt))
            .andExpect(jsonPath("$.content").value(hasSize(1)))
            .andExpect(jsonPath("$.content[0].title").value("bidding"));
    }

    @Test
    void list_invalidGroup_returns400() throws Exception {
        mvc.perform(get("/api/v1/notifications?group=NOT_A_GROUP")
                .header("Authorization", "Bearer " + aliceJwt))
            .andExpect(status().isBadRequest());
    }

    @Test
    void list_sizeOver100_returns400() throws Exception {
        mvc.perform(get("/api/v1/notifications?size=101")
                .header("Authorization", "Bearer " + aliceJwt))
            .andExpect(status().isBadRequest());
    }

    @Test
    void unreadCount_withoutBreakdown_returnsCountOnly() throws Exception {
        dao.upsert(alice.getId(), OUTBID, "t", "b", Map.of(), null);

        mvc.perform(get("/api/v1/notifications/unread-count")
                .header("Authorization", "Bearer " + aliceJwt))
            .andExpect(jsonPath("$.count").value(1L))
            .andExpect(jsonPath("$.byGroup").doesNotExist());
    }

    @Test
    void unreadCount_withBreakdownGroup_returnsByGroup() throws Exception {
        dao.upsert(alice.getId(), OUTBID, "t", "b", Map.of(), null);
        dao.upsert(alice.getId(), OUTBID, "t", "b", Map.of(), null); // different row, no coalesce
        dao.upsert(alice.getId(), AUCTION_WON, "t", "b", Map.of(), null);

        mvc.perform(get("/api/v1/notifications/unread-count?breakdown=group")
                .header("Authorization", "Bearer " + aliceJwt))
            .andExpect(jsonPath("$.count").value(3L))
            .andExpect(jsonPath("$.byGroup.bidding").value(2L))
            .andExpect(jsonPath("$.byGroup.auction_result").value(1L));
    }

    @Test
    void markRead_returnsNoContent() throws Exception {
        var n = dao.upsert(alice.getId(), OUTBID, "t", "b", Map.of(), null);

        mvc.perform(put("/api/v1/notifications/" + n.id() + "/read")
                .header("Authorization", "Bearer " + aliceJwt))
            .andExpect(status().isNoContent());
    }

    @Test
    void markRead_alreadyRead_returnsNoContent() throws Exception {
        var n = dao.upsert(alice.getId(), OUTBID, "t", "b", Map.of(), null);
        mvc.perform(put("/api/v1/notifications/" + n.id() + "/read")
                .header("Authorization", "Bearer " + aliceJwt))
            .andExpect(status().isNoContent());
        // Second call — idempotent
        mvc.perform(put("/api/v1/notifications/" + n.id() + "/read")
                .header("Authorization", "Bearer " + aliceJwt))
            .andExpect(status().isNoContent());
    }

    @Test
    void markRead_crossUser_returns404() throws Exception {
        var n = dao.upsert(alice.getId(), OUTBID, "t", "b", Map.of(), null);

        mvc.perform(put("/api/v1/notifications/" + n.id() + "/read")
                .header("Authorization", "Bearer " + bobJwt))
            .andExpect(status().isNotFound());
    }

    @Test
    void markAllRead_returnsCount() throws Exception {
        dao.upsert(alice.getId(), OUTBID, "t", "b", Map.of(), null);
        dao.upsert(alice.getId(), AUCTION_WON, "t", "b", Map.of(), null);

        mvc.perform(put("/api/v1/notifications/read-all")
                .header("Authorization", "Bearer " + aliceJwt))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.markedRead").value(2));
    }

    @Test
    void markAllRead_withGroup_onlyMarksThatGroup() throws Exception {
        dao.upsert(alice.getId(), OUTBID, "t", "b", Map.of(), null);
        dao.upsert(alice.getId(), AUCTION_WON, "t", "b", Map.of(), null);

        mvc.perform(put("/api/v1/notifications/read-all?group=BIDDING")
                .header("Authorization", "Bearer " + aliceJwt))
            .andExpect(jsonPath("$.markedRead").value(1));

        // The AUCTION_WON row is still unread:
        long stillUnread = repo.countByUserIdAndReadFalse(alice.getId());
        assertThat(stillUnread).isEqualTo(1L);
    }

    @Test
    void list_unauthenticated_returns401() throws Exception {
        mvc.perform(get("/api/v1/notifications"))
            .andExpect(status().isUnauthorized());
    }
}
```

- [ ] **Step 8: Run controller tests — expect green**

Run: `./mvnw test -Dtest=NotificationControllerIntegrationTest`
Expected: all pass.

- [ ] **Step 9: Create `NotificationCleanupProperties`**

```java
package com.slparcelauctions.backend.notification;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "slpa.notifications.cleanup")
@Validated
public record NotificationCleanupProperties(
    boolean enabled,
    @NotBlank String cron,
    @Positive int retentionDays,
    @Positive int batchSize
) {}
```

Register in `SlpaApplication` or wherever existing `@ConfigurationPropertiesScan` is set up — confirm no extra registration needed if `@ConfigurationPropertiesScan` auto-discovers.

- [ ] **Step 10: Add the YAML block**

In `backend/src/main/resources/application.yml`, add under `slpa:`:

```yaml
slpa:
  # ... existing keys ...
  notifications:
    cleanup:
      enabled: true
      cron: "0 30 3 * * *"        # 3:30 AM UTC daily
      retention-days: 90
      batch-size: 1000
```

- [ ] **Step 11: Create `NotificationCleanupJob` (Clock-injected per project convention)**

```java
package com.slparcelauctions.backend.notification;

import java.time.Clock;
import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "slpa.notifications.cleanup",
                        name = "enabled", havingValue = "true", matchIfMissing = false)
@Slf4j
public class NotificationCleanupJob {

    private final JdbcTemplate jdbc;
    private final NotificationCleanupProperties properties;
    private final Clock clock;

    @Scheduled(cron = "${slpa.notifications.cleanup.cron}")
    public void run() {
        OffsetDateTime cutoff = OffsetDateTime.now(clock).minusDays(properties.retentionDays());
        int totalDeleted = 0;
        int deletedThisChunk;
        do {
            deletedThisChunk = jdbc.update("""
                DELETE FROM notification
                WHERE id IN (
                    SELECT id FROM notification
                    WHERE read = true AND updated_at < ?
                    LIMIT ?
                )
                """, cutoff, properties.batchSize());
            totalDeleted += deletedThisChunk;
        } while (deletedThisChunk == properties.batchSize());
        log.info("Notification cleanup: deleted {} rows older than {}", totalDeleted, cutoff);
    }
}
```

- [ ] **Step 12: Write `NotificationCleanupJobTest`**

```java
@SpringBootTest
@TestPropertySource(properties = {
    // Keep the job ENABLED for this test, but we invoke run() directly
    // — never let @Scheduled fire in test scope.
    "slpa.notifications.cleanup.enabled=true",
    "slpa.notifications.cleanup.cron=0 0 0 * * *",     // never fires during 1s test
    "slpa.notifications.cleanup.retention-days=90",
    "slpa.notifications.cleanup.batch-size=1000",
    "slpa.escrow.scheduler.enabled=false"
    // ... other scheduler disables
})
class NotificationCleanupJobTest {

    @Autowired NotificationCleanupJob job;
    @Autowired NotificationDao dao;
    @Autowired NotificationRepository repo;
    @Autowired UserRepository userRepo;

    @MockBean Clock clock;

    @Test
    void deletesOnlyReadRowsOlderThan90Days() {
        when(clock.instant()).thenReturn(Instant.parse("2026-04-25T00:00:00Z"));
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);

        User u = userRepo.save(testUser("alice"));

        // Seed: 91-day-read (deletable), 89-day-read (stays), 91-day-unread (stays)
        var oldRead = dao.upsert(u.getId(), OUTBID, "old", "b", Map.of(), null);
        repo.markRead(oldRead.id(), u.getId());
        forceUpdatedAt(oldRead.id(), "2026-01-24T00:00:00Z"); // 91 days ago

        var midRead = dao.upsert(u.getId(), OUTBID, "mid", "b", Map.of(), "k1");
        repo.markRead(midRead.id(), u.getId());
        forceUpdatedAt(midRead.id(), "2026-01-26T00:00:00Z"); // 89 days ago

        var oldUnread = dao.upsert(u.getId(), OUTBID, "unread", "b", Map.of(), "k2");
        forceUpdatedAt(oldUnread.id(), "2026-01-24T00:00:00Z"); // 91 days ago, unread

        job.run();

        assertThat(repo.findById(oldRead.id())).isEmpty();
        assertThat(repo.findById(midRead.id())).isPresent();
        assertThat(repo.findById(oldUnread.id())).isPresent();
    }

    @Test
    void chunkedDeleteHandlesLargeBacklog() {
        // Set batch-size=10 in @TestPropertySource override
        // Seed 25 deletable rows
        // Run once, assert all 25 gone (3 iterations of 10/10/5)
    }

    @Test
    void disabledWhenPropertyOff_jobBeanNotPresent() {
        // Use a separate @SpringBootTest config with enabled=false and assert
        // ApplicationContext does not contain NotificationCleanupJob bean.
    }
}
```

- [ ] **Step 13: Run cleanup tests — expect pass**

Run: `./mvnw test -Dtest=NotificationCleanupJobTest`
Expected: green.

- [ ] **Step 14: Add `slpa.notifications.cleanup.enabled=false` to all existing integration tests**

Sweep all files in `backend/src/test/java/**` that use `@SpringBootTest` + `@TestPropertySource(properties = ...)`. Add the new line. Per project pattern, this is the toll for adding a `@Scheduled` job — see DEFERRED_WORK §"Shared integration-test base class" entry.

Run: `./mvnw test` after the sweep. Expect no regressions.

- [ ] **Step 15: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/notification/NotificationController.java \
        backend/src/main/java/com/slparcelauctions/backend/notification/dto/UnreadCountResponse.java \
        backend/src/main/java/com/slparcelauctions/backend/notification/NotificationCleanupJob.java \
        backend/src/main/java/com/slparcelauctions/backend/notification/NotificationCleanupProperties.java \
        backend/src/main/java/com/slparcelauctions/backend/user/dto/UserResponse.java \
        backend/src/main/java/com/slparcelauctions/backend/user/UserService.java \
        backend/src/main/resources/application.yml \
        backend/src/test/java/com/slparcelauctions/backend/notification/NotificationControllerIntegrationTest.java \
        backend/src/test/java/com/slparcelauctions/backend/notification/NotificationCleanupJobTest.java \
        backend/src/test/java/com/slparcelauctions/backend/user/UserResponseUnreadCountTest.java
git commit -m "feat(notifications): REST controller + /me unread count + cleanup job"
```

---

## Task 3 — Publisher API + NotificationDataBuilder

**Goal:** Land the typed publisher interface + implementation + per-category data builder. NO event source integration yet (Task 5). NO WS broadcaster wiring (Task 4 — `NotificationWsBroadcasterPort` is still mocked here). The implementation calls into `NotificationService.publish(...)` from Task 1.

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/notification/NotificationPublisher.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/notification/NotificationPublisherImpl.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/notification/NotificationDataBuilder.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/notification/NotificationPublisherImplTest.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/notification/NotificationDataBuilderTest.java`

### Steps

- [ ] **Step 1: Create `NotificationPublisher` interface (single-recipient methods + fan-out)**

```java
package com.slparcelauctions.backend.notification;

import java.time.OffsetDateTime;
import java.util.List;

public interface NotificationPublisher {

    // Bidding
    void outbid(long bidderUserId, long auctionId, String parcelName,
                long currentBidL, boolean isProxyOutbid, OffsetDateTime endsAt);
    void proxyExhausted(long bidderUserId, long auctionId, String parcelName,
                        long proxyMaxL, OffsetDateTime endsAt);

    // Auction result — winner/loser
    void auctionWon(long winnerUserId, long auctionId, String parcelName, long winningBidL);
    void auctionLost(long bidderUserId, long auctionId, String parcelName, long winningBidL);

    // Auction result — seller's perspective
    void auctionEndedSold(long sellerUserId, long auctionId, String parcelName, long winningBidL);
    void auctionEndedReserveNotMet(long sellerUserId, long auctionId, String parcelName, long highestBidL);
    void auctionEndedNoBids(long sellerUserId, long auctionId, String parcelName);
    void auctionEndedBoughtNow(long sellerUserId, long auctionId, String parcelName, long buyNowL);

    // Escrow lifecycle
    void escrowFunded(long sellerUserId, long auctionId, long escrowId,
                      String parcelName, OffsetDateTime transferDeadline);
    void escrowTransferConfirmed(long userId, long auctionId, long escrowId, String parcelName);
    void escrowPayout(long sellerUserId, long auctionId, long escrowId,
                      String parcelName, long payoutL);
    void escrowExpired(long userId, long auctionId, long escrowId, String parcelName);
    void escrowDisputed(long userId, long auctionId, long escrowId,
                        String parcelName, String reasonCategory);
    void escrowFrozen(long userId, long auctionId, long escrowId,
                      String parcelName, String reason);
    void escrowPayoutStalled(long sellerUserId, long auctionId, long escrowId, String parcelName);
    void escrowTransferReminder(long sellerUserId, long auctionId, long escrowId,
                                 String parcelName, OffsetDateTime transferDeadline);

    // Listing status — seller-facing
    void listingVerified(long sellerUserId, long auctionId, String parcelName);
    void listingSuspended(long sellerUserId, long auctionId, String parcelName, String reason);
    void listingReviewRequired(long sellerUserId, long auctionId, String parcelName, String reason);

    // Reviews
    void reviewReceived(long revieweeUserId, long reviewId, long auctionId,
                        String parcelName, int rating);

    // Fan-out (afterCommit batch — see §3.9)
    void listingCancelledBySellerFanout(long auctionId, List<Long> activeBidderUserIds,
                                         String parcelName, String reason);
}
```

- [ ] **Step 2: Create `NotificationDataBuilder`**

Centralizes the canonical key contract for the `data` JSON blob. Adding a category = one builder method here. Adding a field = one line.

```java
package com.slparcelauctions.backend.notification;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-category builder methods for the notification {@code data} JSON
 * blob. The contract: each category writes a stable set of keys here,
 * and the frontend reads them via a discriminated union typed by
 * {@code category}. Adding a field to an existing category = one line
 * here; no schema migration. Type-safe at the publish call site (named
 * parameters) without sealing the persistence type.
 */
public final class NotificationDataBuilder {

    private NotificationDataBuilder() {}

    public static Map<String, Object> outbid(long auctionId, String parcelName,
                                              long currentBidL, boolean isProxyOutbid,
                                              OffsetDateTime endsAt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("auctionId", auctionId);
        m.put("parcelName", parcelName);
        m.put("currentBidL", currentBidL);
        m.put("isProxyOutbid", isProxyOutbid);
        m.put("endsAt", endsAt.toString());
        return m;
    }

    public static Map<String, Object> proxyExhausted(long auctionId, String parcelName,
                                                      long proxyMaxL, OffsetDateTime endsAt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("auctionId", auctionId);
        m.put("parcelName", parcelName);
        m.put("proxyMaxL", proxyMaxL);
        m.put("endsAt", endsAt.toString());
        return m;
    }

    public static Map<String, Object> auctionWon(long auctionId, String parcelName, long winningBidL) {
        return base(auctionId, parcelName, "winningBidL", winningBidL);
    }

    public static Map<String, Object> auctionLost(long auctionId, String parcelName, long winningBidL) {
        return base(auctionId, parcelName, "winningBidL", winningBidL);
    }

    public static Map<String, Object> auctionEndedSold(long auctionId, String parcelName, long winningBidL) {
        return base(auctionId, parcelName, "winningBidL", winningBidL);
    }

    public static Map<String, Object> auctionEndedReserveNotMet(long auctionId, String parcelName, long highestBidL) {
        return base(auctionId, parcelName, "highestBidL", highestBidL);
    }

    public static Map<String, Object> auctionEndedNoBids(long auctionId, String parcelName) {
        return base(auctionId, parcelName);
    }

    public static Map<String, Object> auctionEndedBoughtNow(long auctionId, String parcelName, long buyNowL) {
        return base(auctionId, parcelName, "buyNowL", buyNowL);
    }

    public static Map<String, Object> escrowFunded(long auctionId, long escrowId, String parcelName,
                                                    OffsetDateTime transferDeadline) {
        Map<String, Object> m = baseEscrow(auctionId, escrowId, parcelName);
        m.put("transferDeadline", transferDeadline.toString());
        return m;
    }

    public static Map<String, Object> escrowTransferConfirmed(long auctionId, long escrowId, String parcelName) {
        return baseEscrow(auctionId, escrowId, parcelName);
    }

    public static Map<String, Object> escrowPayout(long auctionId, long escrowId, String parcelName, long payoutL) {
        Map<String, Object> m = baseEscrow(auctionId, escrowId, parcelName);
        m.put("payoutL", payoutL);
        return m;
    }

    public static Map<String, Object> escrowExpired(long auctionId, long escrowId, String parcelName) {
        return baseEscrow(auctionId, escrowId, parcelName);
    }

    public static Map<String, Object> escrowDisputed(long auctionId, long escrowId, String parcelName, String reasonCategory) {
        Map<String, Object> m = baseEscrow(auctionId, escrowId, parcelName);
        m.put("reasonCategory", reasonCategory);
        return m;
    }

    public static Map<String, Object> escrowFrozen(long auctionId, long escrowId, String parcelName, String reason) {
        Map<String, Object> m = baseEscrow(auctionId, escrowId, parcelName);
        m.put("reason", reason);
        return m;
    }

    public static Map<String, Object> escrowPayoutStalled(long auctionId, long escrowId, String parcelName) {
        return baseEscrow(auctionId, escrowId, parcelName);
    }

    public static Map<String, Object> escrowTransferReminder(long auctionId, long escrowId, String parcelName,
                                                              OffsetDateTime transferDeadline) {
        Map<String, Object> m = baseEscrow(auctionId, escrowId, parcelName);
        m.put("transferDeadline", transferDeadline.toString());
        return m;
    }

    public static Map<String, Object> listingVerified(long auctionId, String parcelName) {
        return base(auctionId, parcelName);
    }

    public static Map<String, Object> listingSuspended(long auctionId, String parcelName, String reason) {
        Map<String, Object> m = base(auctionId, parcelName);
        m.put("reason", reason);
        return m;
    }

    public static Map<String, Object> listingReviewRequired(long auctionId, String parcelName, String reason) {
        Map<String, Object> m = base(auctionId, parcelName);
        m.put("reason", reason);
        return m;
    }

    public static Map<String, Object> reviewReceived(long reviewId, long auctionId, String parcelName, int rating) {
        Map<String, Object> m = base(auctionId, parcelName);
        m.put("reviewId", reviewId);
        m.put("rating", rating);
        return m;
    }

    public static Map<String, Object> listingCancelledBySeller(long auctionId, String parcelName, String reason) {
        Map<String, Object> m = base(auctionId, parcelName);
        m.put("reason", reason);
        return m;
    }

    private static Map<String, Object> base(long auctionId, String parcelName) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("auctionId", auctionId);
        m.put("parcelName", parcelName);
        return m;
    }

    private static Map<String, Object> base(long auctionId, String parcelName, String extraKey, Object extraValue) {
        Map<String, Object> m = base(auctionId, parcelName);
        m.put(extraKey, extraValue);
        return m;
    }

    private static Map<String, Object> baseEscrow(long auctionId, long escrowId, String parcelName) {
        Map<String, Object> m = base(auctionId, parcelName);
        m.put("escrowId", escrowId);
        return m;
    }
}
```

- [ ] **Step 3: Create `NotificationPublisherImpl`**

Each single-recipient method: build data, format title/body templates, compute coalesce key, call `NotificationService.publish`. The fan-out method is afterCommit batch with per-recipient try-catch + REQUIRES_NEW.

```java
package com.slparcelauctions.backend.notification;

import com.slparcelauctions.backend.notification.NotificationDao.UpsertResult;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationPublisherImpl implements NotificationPublisher {

    private final NotificationService notificationService;
    private final NotificationDao notificationDao;
    private final NotificationWsBroadcasterPort wsBroadcaster;
    private final TransactionTemplate requiresNewTxTemplate;
    // ↑ Bean configured with PROPAGATION_REQUIRES_NEW — see config note at end of task.

    @Override
    public void outbid(long bidderUserId, long auctionId, String parcelName,
                       long currentBidL, boolean isProxyOutbid, OffsetDateTime endsAt) {
        String title = "You've been outbid on " + parcelName;
        String body = isProxyOutbid
            ? String.format("Your proxy max was reached. Current bid is L$%,d.", currentBidL)
            : String.format("Current bid is L$%,d.", currentBidL);
        notificationService.publish(new NotificationEvent(
            bidderUserId, NotificationCategory.OUTBID, title, body,
            NotificationDataBuilder.outbid(auctionId, parcelName, currentBidL, isProxyOutbid, endsAt),
            "outbid:" + bidderUserId + ":" + auctionId
        ));
    }

    @Override
    public void proxyExhausted(long bidderUserId, long auctionId, String parcelName,
                                long proxyMaxL, OffsetDateTime endsAt) {
        String title = "Your proxy on " + parcelName + " was exhausted";
        String body = String.format("Your max bid (L$%,d) was reached. Place a new proxy to keep bidding.", proxyMaxL);
        notificationService.publish(new NotificationEvent(
            bidderUserId, NotificationCategory.PROXY_EXHAUSTED, title, body,
            NotificationDataBuilder.proxyExhausted(auctionId, parcelName, proxyMaxL, endsAt),
            "proxy_exhausted:" + bidderUserId + ":" + auctionId
        ));
    }

    @Override
    public void auctionWon(long winnerUserId, long auctionId, String parcelName, long winningBidL) {
        String title = "You won " + parcelName + "!";
        String body = String.format("Pay L$%,d into escrow within 24 hours to claim the parcel.", winningBidL);
        notificationService.publish(new NotificationEvent(
            winnerUserId, NotificationCategory.AUCTION_WON, title, body,
            NotificationDataBuilder.auctionWon(auctionId, parcelName, winningBidL),
            null
        ));
    }

    @Override
    public void auctionLost(long bidderUserId, long auctionId, String parcelName, long winningBidL) {
        String title = "Auction ended: " + parcelName;
        String body = String.format("Winning bid was L$%,d. Better luck next time.", winningBidL);
        notificationService.publish(new NotificationEvent(
            bidderUserId, NotificationCategory.AUCTION_LOST, title, body,
            NotificationDataBuilder.auctionLost(auctionId, parcelName, winningBidL),
            null
        ));
    }

    @Override
    public void auctionEndedSold(long sellerUserId, long auctionId, String parcelName, long winningBidL) {
        String title = "Your auction sold: " + parcelName;
        String body = String.format("Winning bid: L$%,d. Awaiting buyer's escrow payment.", winningBidL);
        notificationService.publish(new NotificationEvent(
            sellerUserId, NotificationCategory.AUCTION_ENDED_SOLD, title, body,
            NotificationDataBuilder.auctionEndedSold(auctionId, parcelName, winningBidL),
            null
        ));
    }

    @Override
    public void auctionEndedReserveNotMet(long sellerUserId, long auctionId, String parcelName, long highestBidL) {
        String title = "Reserve not met: " + parcelName;
        String body = String.format("Highest bid was L$%,d, below your reserve. The auction has ended.", highestBidL);
        notificationService.publish(new NotificationEvent(
            sellerUserId, NotificationCategory.AUCTION_ENDED_RESERVE_NOT_MET, title, body,
            NotificationDataBuilder.auctionEndedReserveNotMet(auctionId, parcelName, highestBidL),
            null
        ));
    }

    @Override
    public void auctionEndedNoBids(long sellerUserId, long auctionId, String parcelName) {
        String title = "No bids received: " + parcelName;
        String body = "Your auction ended without any bids. You can re-list at any time.";
        notificationService.publish(new NotificationEvent(
            sellerUserId, NotificationCategory.AUCTION_ENDED_NO_BIDS, title, body,
            NotificationDataBuilder.auctionEndedNoBids(auctionId, parcelName),
            null
        ));
    }

    @Override
    public void auctionEndedBoughtNow(long sellerUserId, long auctionId, String parcelName, long buyNowL) {
        String title = "Buy-now exercised: " + parcelName;
        String body = String.format("Sold at L$%,d via Buy Now.", buyNowL);
        notificationService.publish(new NotificationEvent(
            sellerUserId, NotificationCategory.AUCTION_ENDED_BOUGHT_NOW, title, body,
            NotificationDataBuilder.auctionEndedBoughtNow(auctionId, parcelName, buyNowL),
            null
        ));
    }

    @Override
    public void escrowFunded(long sellerUserId, long auctionId, long escrowId,
                              String parcelName, OffsetDateTime transferDeadline) {
        String title = "Buyer funded escrow on " + parcelName;
        String body = "Transfer the parcel to escrow within 72 hours to release payout.";
        notificationService.publish(new NotificationEvent(
            sellerUserId, NotificationCategory.ESCROW_FUNDED, title, body,
            NotificationDataBuilder.escrowFunded(auctionId, escrowId, parcelName, transferDeadline),
            null
        ));
    }

    @Override
    public void escrowTransferConfirmed(long userId, long auctionId, long escrowId, String parcelName) {
        String title = "Land transfer confirmed: " + parcelName;
        String body = "Payout is processing.";
        notificationService.publish(new NotificationEvent(
            userId, NotificationCategory.ESCROW_TRANSFER_CONFIRMED, title, body,
            NotificationDataBuilder.escrowTransferConfirmed(auctionId, escrowId, parcelName),
            null
        ));
    }

    @Override
    public void escrowPayout(long sellerUserId, long auctionId, long escrowId,
                              String parcelName, long payoutL) {
        String title = String.format("Payout received: L$%,d", payoutL);
        String body = parcelName + " escrow completed successfully.";
        notificationService.publish(new NotificationEvent(
            sellerUserId, NotificationCategory.ESCROW_PAYOUT, title, body,
            NotificationDataBuilder.escrowPayout(auctionId, escrowId, parcelName, payoutL),
            null
        ));
    }

    @Override
    public void escrowExpired(long userId, long auctionId, long escrowId, String parcelName) {
        String title = "Escrow expired: " + parcelName;
        String body = "The escrow window passed without completion.";
        notificationService.publish(new NotificationEvent(
            userId, NotificationCategory.ESCROW_EXPIRED, title, body,
            NotificationDataBuilder.escrowExpired(auctionId, escrowId, parcelName),
            null
        ));
    }

    @Override
    public void escrowDisputed(long userId, long auctionId, long escrowId,
                                String parcelName, String reasonCategory) {
        String title = "Escrow disputed: " + parcelName;
        String body = "A dispute was opened. Awaiting admin review.";
        notificationService.publish(new NotificationEvent(
            userId, NotificationCategory.ESCROW_DISPUTED, title, body,
            NotificationDataBuilder.escrowDisputed(auctionId, escrowId, parcelName, reasonCategory),
            null
        ));
    }

    @Override
    public void escrowFrozen(long userId, long auctionId, long escrowId,
                              String parcelName, String reason) {
        String title = "Escrow frozen: " + parcelName;
        String body = "Held pending review. Contact support if you need information.";
        notificationService.publish(new NotificationEvent(
            userId, NotificationCategory.ESCROW_FROZEN, title, body,
            NotificationDataBuilder.escrowFrozen(auctionId, escrowId, parcelName, reason),
            null
        ));
    }

    @Override
    public void escrowPayoutStalled(long sellerUserId, long auctionId, long escrowId, String parcelName) {
        String title = "Payout delayed: " + parcelName;
        String body = "Your payout is delayed — we're investigating. No action needed from you.";
        notificationService.publish(new NotificationEvent(
            sellerUserId, NotificationCategory.ESCROW_PAYOUT_STALLED, title, body,
            NotificationDataBuilder.escrowPayoutStalled(auctionId, escrowId, parcelName),
            null
        ));
    }

    @Override
    public void escrowTransferReminder(long sellerUserId, long auctionId, long escrowId,
                                        String parcelName, OffsetDateTime transferDeadline) {
        String title = "Reminder: transfer " + parcelName;
        String body = "Your escrow window expires soon. Transfer the parcel to release payout.";
        notificationService.publish(new NotificationEvent(
            sellerUserId, NotificationCategory.ESCROW_TRANSFER_REMINDER, title, body,
            NotificationDataBuilder.escrowTransferReminder(auctionId, escrowId, parcelName, transferDeadline),
            "transfer_reminder:" + sellerUserId + ":" + escrowId
        ));
    }

    @Override
    public void listingVerified(long sellerUserId, long auctionId, String parcelName) {
        String title = "Listing verified: " + parcelName;
        String body = "Your parcel listing is now live.";
        notificationService.publish(new NotificationEvent(
            sellerUserId, NotificationCategory.LISTING_VERIFIED, title, body,
            NotificationDataBuilder.listingVerified(auctionId, parcelName),
            null
        ));
    }

    @Override
    public void listingSuspended(long sellerUserId, long auctionId, String parcelName, String reason) {
        String title = "Listing suspended: " + parcelName;
        String body = "Reason: " + reason + ". Contact support for details.";
        notificationService.publish(new NotificationEvent(
            sellerUserId, NotificationCategory.LISTING_SUSPENDED, title, body,
            NotificationDataBuilder.listingSuspended(auctionId, parcelName, reason),
            null
        ));
    }

    @Override
    public void listingReviewRequired(long sellerUserId, long auctionId, String parcelName, String reason) {
        String title = "Review required: " + parcelName;
        String body = "Reason: " + reason + ".";
        notificationService.publish(new NotificationEvent(
            sellerUserId, NotificationCategory.LISTING_REVIEW_REQUIRED, title, body,
            NotificationDataBuilder.listingReviewRequired(auctionId, parcelName, reason),
            null
        ));
    }

    @Override
    public void reviewReceived(long revieweeUserId, long reviewId, long auctionId,
                                String parcelName, int rating) {
        String title = "New review (" + rating + "★): " + parcelName;
        String body = "A new review has been posted on your transaction.";
        notificationService.publish(new NotificationEvent(
            revieweeUserId, NotificationCategory.REVIEW_RECEIVED, title, body,
            NotificationDataBuilder.reviewReceived(reviewId, auctionId, parcelName, rating),
            null
        ));
    }

    @Override
    public void listingCancelledBySellerFanout(long auctionId, List<Long> bidderUserIds,
                                                String parcelName, String reason) {
        Map<String, Object> data = NotificationDataBuilder.listingCancelledBySeller(auctionId, parcelName, reason);
        String title = "Listing cancelled: " + parcelName;
        String body = "The seller cancelled this listing. Reason: " + reason + ".";

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                for (Long bidderId : bidderUserIds) {
                    try {
                        UpsertResult result = requiresNewTxTemplate.execute(status ->
                            notificationDao.upsert(bidderId, NotificationCategory.LISTING_CANCELLED_BY_SELLER,
                                                    title, body, data, /*coalesceKey*/ null)
                        );
                        wsBroadcaster.broadcastUpsert(bidderId, result, dtoFor(bidderId, result, data, title, body));
                    } catch (Exception ex) {
                        log.warn("Fan-out notification failed for userId={} auctionId={} category=LISTING_CANCELLED_BY_SELLER: {}",
                                 bidderId, auctionId, ex.toString());
                    }
                }
            }
        });
    }

    private com.slparcelauctions.backend.notification.dto.NotificationDto dtoFor(
        long userId, UpsertResult result, Map<String, Object> data, String title, String body) {
        return new com.slparcelauctions.backend.notification.dto.NotificationDto(
            result.id(),
            NotificationCategory.LISTING_CANCELLED_BY_SELLER,
            NotificationGroup.LISTING_STATUS,
            title, body, data, false,
            result.createdAt(), result.updatedAt()
        );
    }
}
```

**Bean config note:** Define the `requiresNewTxTemplate` bean once in a `@Configuration` class:

```java
@Bean
TransactionTemplate requiresNewTxTemplate(PlatformTransactionManager txm) {
    TransactionTemplate t = new TransactionTemplate(txm);
    t.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return t;
}
```

Place in a new `notification/NotificationConfig.java` or extend an existing `@Configuration` near this package.

- [ ] **Step 4: Write `NotificationDataBuilderTest`**

Quick coverage that each builder produces the expected canonical keys:

```java
class NotificationDataBuilderTest {
    @Test
    void outbid_writesExpectedKeys() {
        var d = NotificationDataBuilder.outbid(42, "Hampton Hills", 5200,
            true, OffsetDateTime.parse("2026-04-25T18:00:00Z"));
        assertThat(d).containsOnlyKeys("auctionId", "parcelName", "currentBidL", "isProxyOutbid", "endsAt");
        assertThat(d).containsEntry("isProxyOutbid", true);
        assertThat(d).containsEntry("currentBidL", 5200L);
    }

    @Test
    void escrowFunded_writesExpectedKeys() {
        var d = NotificationDataBuilder.escrowFunded(42, 100, "Pinewood", OffsetDateTime.now());
        assertThat(d).containsKeys("auctionId", "escrowId", "parcelName", "transferDeadline");
    }

    // ...one similar test per builder method (kept terse — they're symmetric)
    @Test void auctionEndedNoBids_writesMinimalKeys() { /* ... */ }
    @Test void listingCancelledBySeller_writesReasonField() { /* ... */ }
    // etc.
}
```

- [ ] **Step 5: Write `NotificationPublisherImplTest`**

`@SpringBootTest` with all schedulers disabled. For each publisher method, assert that calling it inside a transaction:
1. Creates exactly one notification row with the expected category, recipient, coalesce key, and data shape.
2. Triggers the broadcaster's `broadcastUpsert` after commit.

Pattern (one example; symmetric for the rest):

```java
@Test
void outbid_publishesNotification() {
    User alice = userRepo.save(testUser("alice"));

    transactionTemplate.execute(status -> {
        publisher.outbid(alice.getId(), 42, "Hampton Hills", 5200, false,
            OffsetDateTime.now().plusMinutes(8));
        return null;
    });

    var rows = repo.findAll();
    assertThat(rows).hasSize(1);
    var n = rows.get(0);
    assertThat(n.getUser().getId()).isEqualTo(alice.getId());
    assertThat(n.getCategory()).isEqualTo(NotificationCategory.OUTBID);
    assertThat(n.getCoalesceKey()).isEqualTo("outbid:" + alice.getId() + ":42");
    assertThat(n.getData()).containsKeys("auctionId", "parcelName", "currentBidL", "isProxyOutbid", "endsAt");

    verify(broadcasterPort).broadcastUpsert(eq(alice.getId()), any(), any());
}
```

For the **fan-out** method, three dedicated tests:

```java
@Test
void listingCancelledBySellerFanout_eachRecipientGetsOwnRow() {
    User a = userRepo.save(testUser("a"));
    User b = userRepo.save(testUser("b"));
    User c = userRepo.save(testUser("c"));

    transactionTemplate.execute(status -> {
        publisher.listingCancelledBySellerFanout(42, List.of(a.getId(), b.getId(), c.getId()),
            "Hampton Hills", "ownership lost");
        return null;
    });

    var rows = repo.findAll();
    assertThat(rows).hasSize(3);
    assertThat(rows).allMatch(r -> r.getCategory() == NotificationCategory.LISTING_CANCELLED_BY_SELLER);
    assertThat(rows.stream().map(r -> r.getUser().getId()).toList())
        .containsExactlyInAnyOrder(a.getId(), b.getId(), c.getId());

    verify(broadcasterPort, times(3)).broadcastUpsert(anyLong(), any(), any());
}

@Test
void fanout_runsInAfterCommitNotInParentTx() {
    User a = userRepo.save(testUser("a"));

    try {
        transactionTemplate.execute(status -> {
            publisher.listingCancelledBySellerFanout(42, List.of(a.getId()),
                "Hampton Hills", "test rollback");
            status.setRollbackOnly();
            return null;
        });
    } catch (Exception ignored) {}

    // Parent rolled back — fan-out NEVER fired.
    assertThat(repo.count()).isEqualTo(0L);
    verify(broadcasterPort, never()).broadcastUpsert(anyLong(), any(), any());
}

@Test
void fanout_partialFailureDoesNotBlockRemainingRecipients() {
    User a = userRepo.save(testUser("a"));
    long staleBidderId = 999_999L; // doesn't exist — FK violation
    User c = userRepo.save(testUser("c"));

    transactionTemplate.execute(status -> {
        publisher.listingCancelledBySellerFanout(42, List.of(a.getId(), staleBidderId, c.getId()),
            "Hampton Hills", "test");
        return null;
    });

    // Two valid recipients should land; one stale fails silently.
    var rows = repo.findAll();
    assertThat(rows).hasSize(2);
    assertThat(rows.stream().map(r -> r.getUser().getId()).toList())
        .containsExactlyInAnyOrder(a.getId(), c.getId());
    verify(broadcasterPort, times(2)).broadcastUpsert(anyLong(), any(), any());
}
```

- [ ] **Step 6: Run publisher tests — expect green**

Run: `./mvnw test -Dtest='NotificationPublisherImplTest,NotificationDataBuilderTest'`
Expected: all pass.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/notification/NotificationPublisher.java \
        backend/src/main/java/com/slparcelauctions/backend/notification/NotificationPublisherImpl.java \
        backend/src/main/java/com/slparcelauctions/backend/notification/NotificationDataBuilder.java \
        backend/src/main/java/com/slparcelauctions/backend/notification/NotificationConfig.java \
        backend/src/test/java/com/slparcelauctions/backend/notification/NotificationPublisherImplTest.java \
        backend/src/test/java/com/slparcelauctions/backend/notification/NotificationDataBuilderTest.java
git commit -m "feat(notifications): publisher API + data builder + fan-out lifecycle"
```

---

## Task 4 — WS infrastructure: broadcasters + envelopes + broker config

**Goal:** Land the real `NotificationWsBroadcaster` + `AccountStateBroadcaster` (replacing the mocked port from Task 1), the three envelope records, and the one-line `WebSocketConfig` change to register `/queue` on the broker. Cross-user routing security regression test ships in this task.

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/notification/ws/NotificationWsBroadcaster.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/notification/ws/AccountStateBroadcaster.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/notification/ws/envelope/NotificationUpsertedEnvelope.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/notification/ws/envelope/ReadStateChangedEnvelope.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/notification/ws/envelope/PenaltyClearedEnvelope.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/config/WebSocketConfig.java` (add `/queue`)
- Test: `backend/src/test/java/com/slparcelauctions/backend/notification/ws/NotificationWsBroadcasterTest.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/notification/ws/AccountStateBroadcasterTest.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/notification/ws/UserQueueRoutingTest.java`

### Steps

- [ ] **Step 1: Create the three envelope records**

```java
// NotificationUpsertedEnvelope.java
package com.slparcelauctions.backend.notification.ws.envelope;
import com.slparcelauctions.backend.notification.dto.NotificationDto;

public record NotificationUpsertedEnvelope(
    String type,
    boolean isUpdate,
    NotificationDto notification
) {
    public NotificationUpsertedEnvelope(boolean isUpdate, NotificationDto notification) {
        this("NOTIFICATION_UPSERTED", isUpdate, notification);
    }
}

// ReadStateChangedEnvelope.java
package com.slparcelauctions.backend.notification.ws.envelope;
public record ReadStateChangedEnvelope(String type) {
    public ReadStateChangedEnvelope() { this("READ_STATE_CHANGED"); }
}

// PenaltyClearedEnvelope.java
package com.slparcelauctions.backend.notification.ws.envelope;
public record PenaltyClearedEnvelope(String type) {
    public PenaltyClearedEnvelope() { this("PENALTY_CLEARED"); }
}
```

- [ ] **Step 2: Create `NotificationWsBroadcaster` (implements the port from Task 1)**

```java
package com.slparcelauctions.backend.notification.ws;

import com.slparcelauctions.backend.notification.NotificationDao.UpsertResult;
import com.slparcelauctions.backend.notification.NotificationWsBroadcasterPort;
import com.slparcelauctions.backend.notification.dto.NotificationDto;
import com.slparcelauctions.backend.notification.ws.envelope.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
@Primary
@RequiredArgsConstructor
@Slf4j
public class NotificationWsBroadcaster implements NotificationWsBroadcasterPort {

    private final SimpMessagingTemplate template;

    @Override
    public void broadcastUpsert(long userId, UpsertResult result, NotificationDto dto) {
        try {
            template.convertAndSendToUser(
                String.valueOf(userId),
                "/queue/notifications",
                new NotificationUpsertedEnvelope(result.wasUpdate(), dto)
            );
        } catch (Exception ex) {
            log.warn("WS broadcast NOTIFICATION_UPSERTED failed userId={} notifId={}: {}",
                     userId, dto.id(), ex.toString());
        }
    }

    @Override
    public void broadcastReadStateChanged(long userId) {
        try {
            template.convertAndSendToUser(
                String.valueOf(userId),
                "/queue/notifications",
                new ReadStateChangedEnvelope()
            );
        } catch (Exception ex) {
            log.warn("WS broadcast READ_STATE_CHANGED failed userId={}: {}", userId, ex.toString());
        }
    }
}
```

- [ ] **Step 3: Create `AccountStateBroadcaster`**

```java
package com.slparcelauctions.backend.notification.ws;

import com.slparcelauctions.backend.notification.ws.envelope.PenaltyClearedEnvelope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class AccountStateBroadcaster {

    private final SimpMessagingTemplate template;

    public void broadcastPenaltyCleared(long userId) {
        try {
            template.convertAndSendToUser(
                String.valueOf(userId),
                "/queue/account",
                new PenaltyClearedEnvelope()
            );
        } catch (Exception ex) {
            log.warn("WS broadcast PENALTY_CLEARED failed userId={}: {}", userId, ex.toString());
        }
    }
}
```

- [ ] **Step 4: Add `/queue` to the broker in `WebSocketConfig`**

```java
// backend/src/main/java/com/slparcelauctions/backend/config/WebSocketConfig.java:46
// Existing line:
//   registry.enableSimpleBroker("/topic");
// Replace with:
registry.enableSimpleBroker("/topic", "/queue");
registry.setUserDestinationPrefix("/user");  // Explicit, even though /user is the default.
```

The default user destination prefix `/user` is already in effect; setting it explicitly documents intent and guards against future Spring config defaults shifting.

- [ ] **Step 5: Write `NotificationWsBroadcasterTest`**

Use a `@MockBean` `SimpMessagingTemplate` and assert the correct `convertAndSendToUser` calls.

```java
@SpringBootTest
@TestPropertySource(properties = { /* schedulers off */ })
class NotificationWsBroadcasterTest {

    @MockBean SimpMessagingTemplate template;
    @Autowired NotificationWsBroadcaster broadcaster;

    @Test
    void broadcastUpsert_routesToUserQueueNotifications() {
        var dto = new NotificationDto(42L, NotificationCategory.OUTBID,
            NotificationGroup.BIDDING, "title", "body", Map.of(),
            false, OffsetDateTime.now(), OffsetDateTime.now());
        var result = new NotificationDao.UpsertResult(42L, false, OffsetDateTime.now(), OffsetDateTime.now());

        broadcaster.broadcastUpsert(7L, result, dto);

        ArgumentCaptor<Object> envelopeCap = ArgumentCaptor.forClass(Object.class);
        verify(template).convertAndSendToUser(eq("7"), eq("/queue/notifications"), envelopeCap.capture());
        var env = (NotificationUpsertedEnvelope) envelopeCap.getValue();
        assertThat(env.type()).isEqualTo("NOTIFICATION_UPSERTED");
        assertThat(env.isUpdate()).isFalse();
        assertThat(env.notification().id()).isEqualTo(42L);
    }

    @Test
    void broadcastUpsert_carriesIsUpdateFlag() {
        var dto = makeDto(42L);
        var result = new NotificationDao.UpsertResult(42L, true, OffsetDateTime.now(), OffsetDateTime.now());

        broadcaster.broadcastUpsert(7L, result, dto);

        ArgumentCaptor<Object> cap = ArgumentCaptor.forClass(Object.class);
        verify(template).convertAndSendToUser(any(), any(), cap.capture());
        assertThat(((NotificationUpsertedEnvelope) cap.getValue()).isUpdate()).isTrue();
    }

    @Test
    void broadcastReadStateChanged_sendsInvalidationEnvelope() {
        broadcaster.broadcastReadStateChanged(7L);

        ArgumentCaptor<Object> cap = ArgumentCaptor.forClass(Object.class);
        verify(template).convertAndSendToUser(eq("7"), eq("/queue/notifications"), cap.capture());
        assertThat(cap.getValue()).isInstanceOf(ReadStateChangedEnvelope.class);
        assertThat(((ReadStateChangedEnvelope) cap.getValue()).type()).isEqualTo("READ_STATE_CHANGED");
    }

    @Test
    void broadcastUpsert_swallowsExceptions() {
        doThrow(new RuntimeException("broker down")).when(template)
            .convertAndSendToUser(any(), any(), any());
        var dto = makeDto(42L);
        var result = new NotificationDao.UpsertResult(42L, false, OffsetDateTime.now(), OffsetDateTime.now());

        // Should NOT throw — broadcaster swallows + logs.
        assertThatCode(() -> broadcaster.broadcastUpsert(7L, result, dto)).doesNotThrowAnyException();
    }
}
```

- [ ] **Step 6: Write `AccountStateBroadcasterTest`**

```java
class AccountStateBroadcasterTest {
    @MockBean SimpMessagingTemplate template;
    @Autowired AccountStateBroadcaster broadcaster;

    @Test
    void penaltyCleared_routesToUserQueueAccount() {
        broadcaster.broadcastPenaltyCleared(7L);

        ArgumentCaptor<Object> cap = ArgumentCaptor.forClass(Object.class);
        verify(template).convertAndSendToUser(eq("7"), eq("/queue/account"), cap.capture());
        assertThat(cap.getValue()).isInstanceOf(PenaltyClearedEnvelope.class);
    }

    @Test
    void penaltyCleared_swallowsExceptions() {
        doThrow(new RuntimeException("broker down")).when(template)
            .convertAndSendToUser(any(), any(), any());
        assertThatCode(() -> broadcaster.broadcastPenaltyCleared(7L)).doesNotThrowAnyException();
    }
}
```

- [ ] **Step 7: Write `UserQueueRoutingTest` — end-to-end cross-user leakage guard**

This is the most important security test. Two STOMP sessions, broadcaster targets one user, verify the other does NOT receive.

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = { /* schedulers off */ })
class UserQueueRoutingTest {

    @Autowired NotificationWsBroadcaster broadcaster;
    @Autowired UserRepository userRepo;
    @Autowired JwtService jwt;
    @LocalServerPort int port;

    @Test
    void userA_doesNotReceiveUserB_notifications() throws Exception {
        User alice = userRepo.save(testUser("alice"));
        User bob = userRepo.save(testUser("bob"));
        String aliceJwt = jwt.issueAccess(alice).token();
        String bobJwt = jwt.issueAccess(bob).token();

        // Two STOMP sessions, each subscribed to /user/queue/notifications.
        StompSession aliceSession = connect(aliceJwt);
        StompSession bobSession = connect(bobJwt);

        var aliceQueue = new LinkedBlockingQueue<Object>();
        var bobQueue = new LinkedBlockingQueue<Object>();

        aliceSession.subscribe("/user/queue/notifications", new StompFrameHandler() {
            public Type getPayloadType(StompHeaders h) { return Object.class; }
            public void handleFrame(StompHeaders h, Object p) { aliceQueue.offer(p); }
        });
        bobSession.subscribe("/user/queue/notifications", new StompFrameHandler() {
            public Type getPayloadType(StompHeaders h) { return Object.class; }
            public void handleFrame(StompHeaders h, Object p) { bobQueue.offer(p); }
        });

        // Wait for subscriptions to land
        Thread.sleep(200);

        // Backend pushes to alice only.
        var dto = new NotificationDto(42L, NotificationCategory.OUTBID,
            NotificationGroup.BIDDING, "title", "body", Map.of(),
            false, OffsetDateTime.now(), OffsetDateTime.now());
        var result = new NotificationDao.UpsertResult(42L, false, OffsetDateTime.now(), OffsetDateTime.now());
        broadcaster.broadcastUpsert(alice.getId(), result, dto);

        // Alice MUST receive within 2s; Bob MUST NOT receive.
        Object aliceMsg = aliceQueue.poll(2, TimeUnit.SECONDS);
        assertThat(aliceMsg).as("alice should receive her own notification").isNotNull();

        Object bobMsg = bobQueue.poll(500, TimeUnit.MILLISECONDS);
        assertThat(bobMsg).as("bob MUST NOT receive alice's notification").isNull();
    }

    @Test
    void unauthenticatedSession_cannotSubscribeToUserQueue() throws Exception {
        StompSession anonSession = connectAnonymous();
        // Subscription should fail — JwtChannelInterceptor only allows
        // PUBLIC_AUCTION_DESTINATION for anonymous sessions.
        // Capture the ERROR frame from the broker:
        AtomicReference<Throwable> err = new AtomicReference<>();
        anonSession.setAutoReceipt(true);
        StompSession.Subscription s = anonSession.subscribe("/user/queue/notifications", new StompFrameHandler() {
            public Type getPayloadType(StompHeaders h) { return Object.class; }
            public void handleFrame(StompHeaders h, Object p) {}
        });
        // The anonymous session should be killed; subsequent assertions confirm
        // session state (varies by stomp client version — check connectivity).
        Thread.sleep(300);
        assertThat(anonSession.isConnected()).isFalse();
    }

    private StompSession connect(String jwt) throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());
        StompHeaders headers = new StompHeaders();
        headers.set("Authorization", "Bearer " + jwt);
        return client.connectAsync(
            "ws://localhost:" + port + "/ws",
            new WebSocketHttpHeaders(),
            headers,
            new StompSessionHandlerAdapter() {}
        ).get(5, TimeUnit.SECONDS);
    }

    private StompSession connectAnonymous() throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());
        return client.connectAsync(
            "ws://localhost:" + port + "/ws",
            new StompSessionHandlerAdapter() {}
        ).get(5, TimeUnit.SECONDS);
    }
}
```

- [ ] **Step 8: Run all WS tests — expect green**

Run: `./mvnw test -Dtest='NotificationWsBroadcasterTest,AccountStateBroadcasterTest,UserQueueRoutingTest'`
Expected: all pass. The cross-user leakage guard MUST pass — this is the security regression test.

- [ ] **Step 9: Verify `NotificationServiceTest` still passes against the real broadcaster (port now wired)**

The `@Primary` annotation on `NotificationWsBroadcaster` means Spring will inject the real broadcaster everywhere `NotificationWsBroadcasterPort` is autowired. Service tests that previously used `@MockBean NotificationWsBroadcasterPort` need to use `@MockBean NotificationWsBroadcaster` now (or keep the port-based mock; either works).

Run: `./mvnw test -Dtest='NotificationServiceTest,NotificationPublisherImplTest'`
Expected: green.

- [ ] **Step 10: Run full backend test suite**

Run: `./mvnw test -pl backend`
Expected: 1072 + new tests, all green.

- [ ] **Step 11: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/notification/ws/ \
        backend/src/main/java/com/slparcelauctions/backend/config/WebSocketConfig.java \
        backend/src/test/java/com/slparcelauctions/backend/notification/ws/
git commit -m "feat(notifications): WS broadcasters + envelopes + /queue broker"
```

---

## Task 5 — Backend integration touchpoints

**Goal:** Wire `publisher.<category>(...)` calls into all existing services per §3.15. One vertical-slice integration test per category. PenaltyTerminalService gets the separate `accountBroadcaster.broadcastPenaltyCleared(userId)` call instead of going through the publisher.

**Files (modify):**
- `backend/src/main/java/com/slparcelauctions/backend/bid/BidService.java`
- `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionSettlementService.java` (or whatever service handles auction-end settlement — look for the existing `AuctionEndedEnvelope` publisher)
- `backend/src/main/java/com/slparcelauctions/backend/escrow/EscrowService.java`
- `backend/src/main/java/com/slparcelauctions/backend/auction/SuspensionService.java`
- `backend/src/main/java/com/slparcelauctions/backend/parcel/ParcelVerificationService.java`
- `backend/src/main/java/com/slparcelauctions/backend/review/ReviewService.java`
- `backend/src/main/java/com/slparcelauctions/backend/auction/CancellationService.java`
- `backend/src/main/java/com/slparcelauctions/backend/escrow/command/TerminalCommandService.java`
- `backend/src/main/java/com/slparcelauctions/backend/escrow/PenaltyTerminalService.java`

**Files (create — one integration test per category):**
- `backend/src/test/java/com/slparcelauctions/backend/notification/integration/BidNotificationIntegrationTest.java`
- `backend/src/test/java/com/slparcelauctions/backend/notification/integration/AuctionEndNotificationIntegrationTest.java`
- `backend/src/test/java/com/slparcelauctions/backend/notification/integration/EscrowNotificationIntegrationTest.java`
- `backend/src/test/java/com/slparcelauctions/backend/notification/integration/SuspensionNotificationIntegrationTest.java`
- `backend/src/test/java/com/slparcelauctions/backend/notification/integration/ParcelVerificationNotificationIntegrationTest.java`
- `backend/src/test/java/com/slparcelauctions/backend/notification/integration/ReviewNotificationIntegrationTest.java`
- `backend/src/test/java/com/slparcelauctions/backend/notification/integration/CancellationFanoutIntegrationTest.java`
- `backend/src/test/java/com/slparcelauctions/backend/notification/integration/TerminalCommandStallNotificationIntegrationTest.java`
- `backend/src/test/java/com/slparcelauctions/backend/notification/integration/PenaltyClearedBroadcastIntegrationTest.java`

### Steps

For each service below, the pattern is the same: inject `NotificationPublisher` (or `AccountStateBroadcaster` for the penalty case), add the in-tx call at the right point, write an integration test that drives the source event and asserts a notification row materializes with the correct shape.

- [ ] **Step 1: Wire `BidService.outbid` and `BidService.proxyExhausted`**

In `BidService` (locate the bid-settlement method that emits `BidSettlementEnvelope`):

```java
// After the settlement decision is made, inside the existing @Transactional method,
// at the point where we know who was displaced:
if (settlement.displacedPreviousHigh()) {
    publisher.outbid(
        settlement.displacedUserId(),
        auction.getId(),
        auction.getParcel().getName(),
        auction.getCurrentBidL(),
        settlement.isProxyOutbid(),
        auction.getEndsAt()
    );
}

// Separately, in the proxy-resolution path, when the resolved bid hits the bidder's max:
if (proxyExhaustionDetected) {
    publisher.proxyExhausted(
        bidder.getId(), auction.getId(), auction.getParcel().getName(),
        proxyMax, auction.getEndsAt()
    );
}
```

`BidService` already injects whatever it needs to publish `BidSettlementEnvelope`. Add `private final NotificationPublisher notificationPublisher;` to its constructor and use it inline.

- [ ] **Step 2: Write `BidNotificationIntegrationTest`**

```java
@SpringBootTest
@TestPropertySource(properties = { /* schedulers off */ })
class BidNotificationIntegrationTest {

    @Autowired BidService bidService;
    @Autowired NotificationRepository notifRepo;
    @Autowired UserRepository userRepo;
    @Autowired AuctionRepository auctionRepo;

    @Test
    void outbid_publishesNotification() {
        User seller = userRepo.save(testUser("seller"));
        User alice = userRepo.save(testUser("alice"));
        User bob = userRepo.save(testUser("bob"));
        Auction a = auctionRepo.save(testAuction(seller, 1000L));

        // Alice bids first → becomes high.
        bidService.placeBid(alice.getId(), a.getId(), 1500L);
        // Bob outbids → alice should be displaced.
        bidService.placeBid(bob.getId(), a.getId(), 2000L);

        var aliceNotifs = notifRepo.findAll().stream()
            .filter(n -> n.getUser().getId().equals(alice.getId()))
            .filter(n -> n.getCategory() == NotificationCategory.OUTBID)
            .toList();
        assertThat(aliceNotifs).hasSize(1);
        assertThat(aliceNotifs.get(0).getCoalesceKey()).isEqualTo("outbid:" + alice.getId() + ":" + a.getId());
        assertThat(aliceNotifs.get(0).getData()).containsEntry("currentBidL", 2000);
    }

    @Test
    void outbid_storm_coalesces() {
        // Place bids 3 times in succession that displace alice each time
        // Assert exactly ONE OUTBID notification row for alice (coalesced)
        // Assert updated_at is bumped past created_at
    }

    @Test
    void proxyExhausted_publishesNotification() {
        // Set alice's proxy max to 2000
        // Bob bids high enough to exhaust alice's proxy
        // Assert PROXY_EXHAUSTED notification with coalesce key proxy_exhausted:alice:auction
    }
}
```

- [ ] **Step 3: Run the bid integration test**

Run: `./mvnw test -Dtest=BidNotificationIntegrationTest`
Expected: green.

- [ ] **Step 4: Wire `AuctionSettlementService` (or the existing settlement service)**

Find the service that processes auction-ended settlement — likely emits `AuctionEndedEnvelope`. Add per-outcome publisher calls based on `auction.endOutcome`:

```java
switch (auction.getEndOutcome()) {
    case SOLD -> {
        publisher.auctionWon(winnerId, a.getId(), parcelName, winningBid);
        publisher.auctionEndedSold(a.getSellerId(), a.getId(), parcelName, winningBid);
        // Each non-winning bidder gets AUCTION_LOST:
        for (Long bidderId : losingBidderIds) {
            publisher.auctionLost(bidderId, a.getId(), parcelName, winningBid);
        }
    }
    case RESERVE_NOT_MET -> publisher.auctionEndedReserveNotMet(a.getSellerId(), a.getId(), parcelName, highestBid);
    case NO_BIDS -> publisher.auctionEndedNoBids(a.getSellerId(), a.getId(), parcelName);
    case BOUGHT_NOW -> {
        publisher.auctionWon(winnerId, a.getId(), parcelName, buyNowL);
        publisher.auctionEndedBoughtNow(a.getSellerId(), a.getId(), parcelName, buyNowL);
    }
}
```

If `losingBidderIds` isn't already enumerated by the service, query it from `BidRepository` once: `bidRepo.findDistinctBidderUserIdsForAuction(a.getId())` minus the winner.

- [ ] **Step 5: Write `AuctionEndNotificationIntegrationTest`**

```java
class AuctionEndNotificationIntegrationTest {
    @Test void sold_winnerGetsWonAndSellerGetsEndedSoldAndOtherBidderGetsLost() { /* ... */ }
    @Test void reserveNotMet_onlySellerGetsRESERVE_NOT_MET() { /* ... */ }
    @Test void noBids_onlySellerGetsNO_BIDS() { /* ... */ }
    @Test void boughtNow_winnerWonAndSellerEndedBoughtNow() { /* ... */ }
}
```

Run: `./mvnw test -Dtest=AuctionEndNotificationIntegrationTest`. Expected: green.

- [ ] **Step 6: Wire `EscrowService` state transitions**

In each state-transition method (FUNDED, TRANSFER_CONFIRMED, COMPLETED, EXPIRED, DISPUTED, FROZEN, payout-stall):

```java
// FUNDED
publisher.escrowFunded(escrow.getSellerId(), escrow.getAuctionId(), escrow.getId(),
                        escrow.getAuction().getParcel().getName(), escrow.getTransferDeadline());

// TRANSFER_CONFIRMED — both parties
publisher.escrowTransferConfirmed(escrow.getWinnerId(), escrow.getAuctionId(), escrow.getId(), parcelName);
publisher.escrowTransferConfirmed(escrow.getSellerId(), escrow.getAuctionId(), escrow.getId(), parcelName);

// PAYOUT — seller only
publisher.escrowPayout(escrow.getSellerId(), escrow.getAuctionId(), escrow.getId(), parcelName, payoutL);

// EXPIRED — both parties
publisher.escrowExpired(escrow.getWinnerId(), ...);
publisher.escrowExpired(escrow.getSellerId(), ...);

// DISPUTED — both parties (caller-of-dispute already knows; the other party needs the notif)
publisher.escrowDisputed(escrow.getWinnerId(), ..., reasonCategory);
publisher.escrowDisputed(escrow.getSellerId(), ..., reasonCategory);

// FROZEN (freezeForFraud) — both
publisher.escrowFrozen(escrow.getWinnerId(), ..., reason);
publisher.escrowFrozen(escrow.getSellerId(), ..., reason);
```

- [ ] **Step 7: Wire `EscrowService.freezeForFraud` / `markReviewRequired` (if present in EscrowService)**

`SuspensionService` owns `markReviewRequired`. Wire there.

- [ ] **Step 8: Write `EscrowNotificationIntegrationTest`**

One sub-test per state transition. Pattern:

```java
@Test void funded_publishesEscrowFundedToSellerOnly() {
    // Drive escrow into FUNDED state
    // Assert exactly one notification with category=ESCROW_FUNDED, recipient=seller
    // Assert NO notification for the winner
}

@Test void transferConfirmed_publishesToBothParties() {
    // Drive escrow into TRANSFER_CONFIRMED
    // Assert two notifications: one for winner, one for seller, both ESCROW_TRANSFER_CONFIRMED
}

@Test void payout_publishesEscrowPayoutToSellerOnly() { /* ... */ }
@Test void expired_publishesToBothParties() { /* ... */ }
@Test void disputed_publishesToBothParties() { /* ... */ }
@Test void frozen_publishesToBothParties() { /* ... */ }
```

Run: `./mvnw test -Dtest=EscrowNotificationIntegrationTest`. Expected: green.

- [ ] **Step 9: Wire `SuspensionService.suspend` (incl. bot path) and `markReviewRequired`**

In each entry point (`suspend`, `suspendForBotObservation`, `markReviewRequired`, `freezeForFraud`):

```java
publisher.listingSuspended(seller.getId(), auction.getId(),
                           auction.getParcel().getName(), reason.toString());
// Or for review-required path:
publisher.listingReviewRequired(seller.getId(), auction.getId(),
                                 auction.getParcel().getName(), reason.toString());
```

- [ ] **Step 10: Write `SuspensionNotificationIntegrationTest`**

```java
@Test void suspend_publishesListingSuspended() { /* ... */ }
@Test void suspendForBotObservation_publishesListingSuspended() { /* same category */ }
@Test void markReviewRequired_publishesListingReviewRequired() { /* ... */ }
```

Run: `./mvnw test -Dtest=SuspensionNotificationIntegrationTest`. Expected: green.

- [ ] **Step 11: Wire `ParcelVerificationService`**

```java
publisher.listingVerified(seller.getId(), auction.getId(), auction.getParcel().getName());
```

Test:

```java
@Test void verificationSuccess_publishesListingVerified() { /* ... */ }
```

- [ ] **Step 12: Wire `ReviewService.reveal`**

```java
publisher.reviewReceived(reviewee.getId(), review.getId(), auction.getId(),
                         auction.getParcel().getName(), review.getRating());
```

Test:

```java
@Test void reveal_publishesReviewReceived() { /* assert recipient is reviewee, not reviewer */ }
```

- [ ] **Step 13: Wire `CancellationService.cancel` for fan-out**

Already enumerates active bidders for refund logic — pass that list:

```java
List<Long> activeBidderIds = ...;  // existing query for refund handling
publisher.listingCancelledBySellerFanout(
    auction.getId(), activeBidderIds,
    auction.getParcel().getName(), reason.toString()
);
```

Test:

```java
@Test void cancel_withActiveBidders_fansOutOneRowPerBidder() { /* ... */ }
@Test void cancel_withNoBidders_publishesNoNotifications() { /* fan-out with empty list = no rows */ }
@Test void cancel_partialFailure_doesNotBlockCancellation() {
    // Inject a stale bidder id → cancellation still commits, valid bidders still get notified
}
```

- [ ] **Step 14: Wire `TerminalCommandService` payout-stall path**

In the path that sets `requiresManualReview = true` for a payout-related command, emit `escrowPayoutStalled`. Look up the escrow + seller from the command:

```java
if (commandType.isPayout() && newRequiresManualReview) {
    Escrow escrow = escrowRepo.findById(command.getEscrowId()).orElseThrow();
    publisher.escrowPayoutStalled(
        escrow.getSellerId(), escrow.getAuctionId(),
        escrow.getId(), escrow.getAuction().getParcel().getName()
    );
}
```

Test:

```java
@Test void payoutCommandStall_publishesEscrowPayoutStalled() { /* ... */ }
@Test void nonPayoutCommandStall_doesNotPublish() { /* only payout commands trigger */ }
```

- [ ] **Step 15: Wire `PenaltyTerminalService.pay` (separate path — `AccountStateBroadcaster`, no Notification row)**

In the `if (newBalance == 0)` branch, register an `afterCommit` hook:

```java
if (newBalance == 0L) {
    final long uid = user.getId();
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override public void afterCommit() {
            accountBroadcaster.broadcastPenaltyCleared(uid);
        }
    });
}
```

This is invalidation-only (no Notification row written) — `/me` refetch on the frontend dismisses the SuspensionBanner.

Test:

```java
class PenaltyClearedBroadcastIntegrationTest {
    @MockBean AccountStateBroadcaster accountBroadcaster;

    @Test
    void payClearingBalance_broadcastsPenaltyCleared() {
        User suspended = userWithPenalty(1000L);
        terminalService.pay(suspended.getId(), 1000L);

        verify(accountBroadcaster).broadcastPenaltyCleared(suspended.getId());
    }

    @Test
    void payPartial_doesNotBroadcast() {
        User suspended = userWithPenalty(1000L);
        terminalService.pay(suspended.getId(), 500L);  // 500 still owed

        verify(accountBroadcaster, never()).broadcastPenaltyCleared(anyLong());
    }
}
```

- [ ] **Step 16: Run all integration tests**

Run: `./mvnw test -Dtest='*NotificationIntegrationTest,*FanoutIntegrationTest,*BroadcastIntegrationTest'`
Expected: all pass.

- [ ] **Step 17: Run full backend test suite — no regressions**

Run: `./mvnw test -pl backend`
Expected: green.

- [ ] **Step 18: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/bid/ \
        backend/src/main/java/com/slparcelauctions/backend/auction/ \
        backend/src/main/java/com/slparcelauctions/backend/escrow/ \
        backend/src/main/java/com/slparcelauctions/backend/parcel/ \
        backend/src/main/java/com/slparcelauctions/backend/review/ \
        backend/src/test/java/com/slparcelauctions/backend/notification/integration/
git commit -m "feat(notifications): wire publisher into 13 event sources"
```

---

## Task 6 — Frontend foundations: types, hooks, api, MSW, Toast upsert

**Goal:** Land all the non-UI frontend infrastructure: types, query keys, REST client, all hooks, ToastProvider's `upsert(id, payload)` extension with timer reset, MSW handlers + fixture extension. NO UI components yet (Tasks 7 + 8).

**Files:**
- Create: `frontend/src/lib/notifications/api.ts`
- Create: `frontend/src/lib/notifications/queryKeys.ts`
- Create: `frontend/src/lib/notifications/types.ts`
- Create: `frontend/src/lib/notifications/categoryMap.ts`
- Create: `frontend/src/hooks/notifications/useNotifications.ts`
- Create: `frontend/src/hooks/notifications/useUnreadCount.ts`
- Create: `frontend/src/hooks/notifications/useMarkRead.ts`
- Create: `frontend/src/hooks/notifications/useMarkAllRead.ts`
- Create: `frontend/src/hooks/notifications/useNotificationStream.ts`
- Modify: `frontend/src/components/ui/Toast/ToastProvider.tsx` (add `upsert(id, payload)`)
- Modify: `frontend/src/lib/user/api.ts` (add `unreadNotificationCount: number`)
- Modify: `frontend/src/test/msw/fixtures.ts` (add `unreadNotificationCount: 0` to default user)
- Modify: `frontend/src/test/msw/handlers.ts` (add notification endpoints)
- Test: alongside each new file (`*.test.ts(x)`)

### Steps

- [ ] **Step 1: Create `lib/notifications/types.ts`**

```ts
// Mirror of backend NotificationGroup
export type NotificationGroup =
  | "bidding" | "auction_result" | "escrow"
  | "listing_status" | "reviews" | "realty_group" | "marketing" | "system";

// Mirror of backend NotificationCategory (22 values, stringified enum names)
export type NotificationCategory =
  | "OUTBID" | "PROXY_EXHAUSTED"
  | "AUCTION_WON" | "AUCTION_LOST"
  | "AUCTION_ENDED_SOLD" | "AUCTION_ENDED_RESERVE_NOT_MET"
  | "AUCTION_ENDED_NO_BIDS" | "AUCTION_ENDED_BOUGHT_NOW"
  | "ESCROW_FUNDED" | "ESCROW_TRANSFER_CONFIRMED"
  | "ESCROW_PAYOUT" | "ESCROW_EXPIRED" | "ESCROW_DISPUTED"
  | "ESCROW_FROZEN" | "ESCROW_PAYOUT_STALLED" | "ESCROW_TRANSFER_REMINDER"
  | "LISTING_VERIFIED" | "LISTING_SUSPENDED"
  | "LISTING_REVIEW_REQUIRED" | "LISTING_CANCELLED_BY_SELLER"
  | "REVIEW_RECEIVED" | "SYSTEM_ANNOUNCEMENT";

// Discriminated-union of data shapes per category. Frontend renderers
// narrow on `notification.category` and read the typed branch.
export type NotificationDataMap = {
  OUTBID: { auctionId: number; parcelName: string; currentBidL: number; isProxyOutbid: boolean; endsAt: string };
  PROXY_EXHAUSTED: { auctionId: number; parcelName: string; proxyMaxL: number; endsAt: string };
  AUCTION_WON: { auctionId: number; parcelName: string; winningBidL: number };
  AUCTION_LOST: { auctionId: number; parcelName: string; winningBidL: number };
  AUCTION_ENDED_SOLD: { auctionId: number; parcelName: string; winningBidL: number };
  AUCTION_ENDED_RESERVE_NOT_MET: { auctionId: number; parcelName: string; highestBidL: number };
  AUCTION_ENDED_NO_BIDS: { auctionId: number; parcelName: string };
  AUCTION_ENDED_BOUGHT_NOW: { auctionId: number; parcelName: string; buyNowL: number };
  ESCROW_FUNDED: { auctionId: number; escrowId: number; parcelName: string; transferDeadline: string };
  ESCROW_TRANSFER_CONFIRMED: { auctionId: number; escrowId: number; parcelName: string };
  ESCROW_PAYOUT: { auctionId: number; escrowId: number; parcelName: string; payoutL: number };
  ESCROW_EXPIRED: { auctionId: number; escrowId: number; parcelName: string };
  ESCROW_DISPUTED: { auctionId: number; escrowId: number; parcelName: string; reasonCategory: string };
  ESCROW_FROZEN: { auctionId: number; escrowId: number; parcelName: string; reason: string };
  ESCROW_PAYOUT_STALLED: { auctionId: number; escrowId: number; parcelName: string };
  ESCROW_TRANSFER_REMINDER: { auctionId: number; escrowId: number; parcelName: string; transferDeadline: string };
  LISTING_VERIFIED: { auctionId: number; parcelName: string };
  LISTING_SUSPENDED: { auctionId: number; parcelName: string; reason: string };
  LISTING_REVIEW_REQUIRED: { auctionId: number; parcelName: string; reason: string };
  LISTING_CANCELLED_BY_SELLER: { auctionId: number; parcelName: string; reason: string };
  REVIEW_RECEIVED: { auctionId: number; parcelName: string; reviewId: number; rating: number };
  SYSTEM_ANNOUNCEMENT: Record<string, unknown>;
};

export type NotificationData = NotificationDataMap[NotificationCategory];

export interface NotificationDto {
  id: number;
  category: NotificationCategory;
  group: NotificationGroup;
  title: string;
  body: string;
  data: Record<string, unknown>;  // typed when narrowed via category
  read: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface UnreadCountResponse {
  count: number;
  byGroup?: Partial<Record<NotificationGroup, number>>;
}

// WS envelope types
export type NotificationsEnvelope =
  | { type: "NOTIFICATION_UPSERTED"; isUpdate: boolean; notification: NotificationDto }
  | { type: "READ_STATE_CHANGED" };

export type AccountEnvelope =
  | { type: "PENALTY_CLEARED" };
```

- [ ] **Step 2: Create `lib/notifications/queryKeys.ts`**

```ts
import type { NotificationGroup } from "./types";

type ListFilters = {
  group?: NotificationGroup;
  unreadOnly?: boolean;
  page?: number;
  size?: number;
};

export const notificationKeys = {
  all: ["notifications"] as const,
  list: (filters?: ListFilters) =>
    [...notificationKeys.all, "list", filters ?? {}] as const,
  unreadCount: () => [...notificationKeys.all, "unreadCount"] as const,
  unreadCountBreakdown: () =>
    [...notificationKeys.all, "unreadCount", "breakdown"] as const,
};
```

- [ ] **Step 3: Create `lib/notifications/api.ts`**

```ts
import { api } from "@/lib/api";
import type { PagedResponse } from "@/lib/types/paged";
import type { NotificationDto, NotificationGroup, UnreadCountResponse } from "./types";

export interface ListNotificationsParams {
  group?: NotificationGroup;
  unreadOnly?: boolean;
  page?: number;
  size?: number;
}

export async function listNotifications(
  params: ListNotificationsParams = {}
): Promise<PagedResponse<NotificationDto>> {
  const search = new URLSearchParams();
  if (params.group) search.set("group", params.group.toUpperCase());
  if (params.unreadOnly) search.set("unreadOnly", "true");
  if (params.page !== undefined) search.set("page", String(params.page));
  if (params.size !== undefined) search.set("size", String(params.size));
  const query = search.toString();
  return api.get<PagedResponse<NotificationDto>>(
    `/api/v1/notifications${query ? "?" + query : ""}`
  );
}

export async function getUnreadCount(breakdown?: "group"): Promise<UnreadCountResponse> {
  const query = breakdown ? "?breakdown=group" : "";
  return api.get<UnreadCountResponse>(`/api/v1/notifications/unread-count${query}`);
}

export async function markRead(id: number): Promise<void> {
  await api.put(`/api/v1/notifications/${id}/read`);
}

export async function markAllRead(group?: NotificationGroup): Promise<{ markedRead: number }> {
  const query = group ? "?group=" + group.toUpperCase() : "";
  return api.put<{ markedRead: number }>(`/api/v1/notifications/read-all${query}`);
}
```

- [ ] **Step 4: Create `lib/notifications/categoryMap.ts`**

```tsx
import type { ComponentType } from "react";
import {
  Bell, Bolt, Wallet, Shield, Star, Trophy, Clock, AlertTriangle,
  CheckCircle2, AlertOctagon, BadgeCheck, XCircle, Pause,
} from "@/components/ui/icons";
import type { ToastKind } from "@/components/ui/Toast";
import type { NotificationCategory, NotificationGroup, NotificationData } from "./types";

export interface CategoryMapEntry {
  group: NotificationGroup;
  icon: ComponentType<{ className?: string }>;
  iconBgClass: string;
  toastVariant: ToastKind;
  deeplink: (data: NotificationData) => string;
  action?: {
    label: string;
    href: (data: NotificationData) => string;
  };
}

export const categoryMap: Record<NotificationCategory, CategoryMapEntry> = {
  OUTBID: {
    group: "bidding",
    icon: Bolt,
    iconBgClass: "bg-warning-container text-on-warning-container",
    toastVariant: "warning",
    deeplink: (d: any) => `/auction/${d.auctionId}`,
    action: { label: "Place a new bid", href: (d: any) => `/auction/${d.auctionId}#bid-panel` },
  },
  PROXY_EXHAUSTED: {
    group: "bidding",
    icon: Bolt,
    iconBgClass: "bg-warning-container text-on-warning-container",
    toastVariant: "warning",
    deeplink: (d: any) => `/auction/${d.auctionId}`,
    action: { label: "Increase proxy", href: (d: any) => `/auction/${d.auctionId}#bid-panel` },
  },
  AUCTION_WON: {
    group: "auction_result",
    icon: Trophy,
    iconBgClass: "bg-primary-container text-on-primary-container",
    toastVariant: "success",
    deeplink: (d: any) => `/auction/${d.auctionId}/escrow`,
    action: { label: "Pay escrow", href: (d: any) => `/auction/${d.auctionId}/escrow` },
  },
  AUCTION_LOST: {
    group: "auction_result", icon: Trophy, iconBgClass: "bg-surface-container-high text-on-surface",
    toastVariant: "info", deeplink: (d: any) => `/auction/${d.auctionId}`,
  },
  AUCTION_ENDED_SOLD: {
    group: "auction_result", icon: Trophy, iconBgClass: "bg-primary-container text-on-primary-container",
    toastVariant: "info", deeplink: (d: any) => `/auction/${d.auctionId}`,
  },
  AUCTION_ENDED_RESERVE_NOT_MET: {
    group: "auction_result", icon: AlertTriangle, iconBgClass: "bg-warning-container text-on-warning-container",
    toastVariant: "info", deeplink: (d: any) => `/auction/${d.auctionId}`,
  },
  AUCTION_ENDED_NO_BIDS: {
    group: "auction_result", icon: Pause, iconBgClass: "bg-surface-container-high text-on-surface",
    toastVariant: "info", deeplink: (d: any) => `/auction/${d.auctionId}`,
  },
  AUCTION_ENDED_BOUGHT_NOW: {
    group: "auction_result", icon: Trophy, iconBgClass: "bg-primary-container text-on-primary-container",
    toastVariant: "info", deeplink: (d: any) => `/auction/${d.auctionId}`,
  },
  ESCROW_FUNDED: {
    group: "escrow", icon: Wallet, iconBgClass: "bg-primary-container text-on-primary-container",
    toastVariant: "info", deeplink: (d: any) => `/auction/${d.auctionId}/escrow`,
    action: { label: "View escrow", href: (d: any) => `/auction/${d.auctionId}/escrow` },
  },
  ESCROW_TRANSFER_CONFIRMED: {
    group: "escrow", icon: BadgeCheck, iconBgClass: "bg-primary-container text-on-primary-container",
    toastVariant: "success", deeplink: (d: any) => `/auction/${d.auctionId}/escrow`,
  },
  ESCROW_PAYOUT: {
    group: "escrow", icon: CheckCircle2, iconBgClass: "bg-primary-container text-on-primary-container",
    toastVariant: "success", deeplink: (d: any) => `/auction/${d.auctionId}/escrow`,
  },
  ESCROW_EXPIRED: {
    group: "escrow", icon: Clock, iconBgClass: "bg-warning-container text-on-warning-container",
    toastVariant: "warning", deeplink: (d: any) => `/auction/${d.auctionId}/escrow`,
  },
  ESCROW_DISPUTED: {
    group: "escrow", icon: AlertOctagon, iconBgClass: "bg-error-container text-on-error-container",
    toastVariant: "error", deeplink: (d: any) => `/auction/${d.auctionId}/escrow`,
  },
  ESCROW_FROZEN: {
    group: "escrow", icon: AlertOctagon, iconBgClass: "bg-error-container text-on-error-container",
    toastVariant: "error", deeplink: (d: any) => `/auction/${d.auctionId}/escrow`,
  },
  ESCROW_PAYOUT_STALLED: {
    group: "escrow", icon: AlertTriangle, iconBgClass: "bg-warning-container text-on-warning-container",
    toastVariant: "warning", deeplink: (d: any) => `/auction/${d.auctionId}/escrow`,
  },
  ESCROW_TRANSFER_REMINDER: {
    group: "escrow", icon: Clock, iconBgClass: "bg-primary-container text-on-primary-container",
    toastVariant: "info", deeplink: (d: any) => `/auction/${d.auctionId}/escrow`,
    action: { label: "View escrow", href: (d: any) => `/auction/${d.auctionId}/escrow` },
  },
  LISTING_VERIFIED: {
    group: "listing_status", icon: BadgeCheck, iconBgClass: "bg-primary-container text-on-primary-container",
    toastVariant: "success", deeplink: (d: any) => `/auction/${d.auctionId}`,
  },
  LISTING_SUSPENDED: {
    group: "listing_status", icon: XCircle, iconBgClass: "bg-error-container text-on-error-container",
    toastVariant: "error", deeplink: (d: any) => `/dashboard/listings`,
  },
  LISTING_REVIEW_REQUIRED: {
    group: "listing_status", icon: AlertOctagon, iconBgClass: "bg-error-container text-on-error-container",
    toastVariant: "error", deeplink: (d: any) => `/dashboard/listings`,
  },
  LISTING_CANCELLED_BY_SELLER: {
    group: "listing_status", icon: XCircle, iconBgClass: "bg-surface-container-high text-on-surface",
    toastVariant: "info", deeplink: (d: any) => `/auction/${d.auctionId}`,
  },
  REVIEW_RECEIVED: {
    group: "reviews", icon: Star, iconBgClass: "bg-tertiary-container text-on-tertiary-container",
    toastVariant: "info", deeplink: (d: any) => `/auction/${d.auctionId}`,
  },
  SYSTEM_ANNOUNCEMENT: {
    group: "system", icon: Bell, iconBgClass: "bg-surface-container-high text-on-surface",
    toastVariant: "info", deeplink: () => `/notifications`,
  },
};

// Forward-compat: returns a fallback config when category is unknown.
export function categoryConfigOrFallback(category: string): CategoryMapEntry {
  const entry = (categoryMap as Record<string, CategoryMapEntry | undefined>)[category];
  if (entry) return entry;
  return {
    group: "system",
    icon: Bell,
    iconBgClass: "bg-surface-container-high text-on-surface",
    toastVariant: "info",
    deeplink: () => `/notifications`,
  };
}
```

(If a particular icon name isn't in `@/components/ui/icons`, use `Bell` as a stand-in and add the icon. Confirm against the existing icon barrel.)

- [ ] **Step 5: Add `unreadNotificationCount` to `lib/user/api.ts`**

Add the field to the existing user response type:

```ts
export interface CurrentUser {
  // ... existing fields ...
  unreadNotificationCount: number;
}
```

- [ ] **Step 6: Update MSW fixtures and handlers**

In `frontend/src/test/msw/fixtures.ts`, add to the default user fixture:

```ts
unreadNotificationCount: 0,
```

In `frontend/src/test/msw/handlers.ts`, add notification endpoint handlers:

```ts
import { http, HttpResponse } from "msw";

const notifications = new Map<number, NotificationDto>();
let nextId = 1;

export const notificationHandlers = [
  http.get("/api/v1/notifications", ({ request }) => {
    // simple paged response shape
    const all = Array.from(notifications.values())
      .sort((a, b) => b.updatedAt.localeCompare(a.updatedAt));
    return HttpResponse.json({
      content: all, page: 0, size: 20, totalElements: all.length, totalPages: 1,
    });
  }),
  http.get("/api/v1/notifications/unread-count", ({ request }) => {
    const url = new URL(request.url);
    const breakdown = url.searchParams.get("breakdown");
    const count = Array.from(notifications.values()).filter(n => !n.read).length;
    if (breakdown === "group") {
      const byGroup: Record<string, number> = {};
      for (const n of notifications.values()) {
        if (!n.read) byGroup[n.group] = (byGroup[n.group] ?? 0) + 1;
      }
      return HttpResponse.json({ count, byGroup });
    }
    return HttpResponse.json({ count });
  }),
  http.put("/api/v1/notifications/:id/read", ({ params }) => {
    const n = notifications.get(Number(params.id));
    if (!n) return new HttpResponse(null, { status: 404 });
    n.read = true;
    return new HttpResponse(null, { status: 204 });
  }),
  http.put("/api/v1/notifications/read-all", () => {
    for (const n of notifications.values()) n.read = true;
    return HttpResponse.json({ markedRead: notifications.size });
  }),
];

// Test helpers exported for hook/component tests:
export function seedNotification(partial: Partial<NotificationDto>): NotificationDto {
  const id = nextId++;
  const n: NotificationDto = {
    id, category: "OUTBID", group: "bidding",
    title: "title", body: "body", data: {}, read: false,
    createdAt: new Date().toISOString(), updatedAt: new Date().toISOString(),
    ...partial,
  };
  notifications.set(id, n);
  return n;
}
export function clearNotifications() { notifications.clear(); nextId = 1; }
```

- [ ] **Step 7: Add `useToast.upsert(id, payload)` to ToastProvider**

In `frontend/src/components/ui/Toast/ToastProvider.tsx`, add:

```tsx
type ToastUpsertItem = ToastItem & { dismissTimer?: ReturnType<typeof setTimeout> };

// Inside the provider component:
const upsert = useCallback(
  (id: string, kind: ToastKind, payload: string | ToastPayload) => {
    setToasts((prev) => {
      const existingIdx = prev.findIndex((t) => t.id === id);
      const next: ToastItem = {
        id,
        kind,
        message: typeof payload === "string" ? payload : payload.title,
        description: typeof payload === "string" ? undefined : payload.description,
        action: typeof payload === "string" ? undefined : payload.action,
      };
      if (existingIdx >= 0) {
        // Replace in place — preserve queue position; reset timer below.
        const updated = [...prev];
        updated[existingIdx] = next;
        // Cancel and restart auto-dismiss.
        if (timersRef.current.has(id)) {
          clearTimeout(timersRef.current.get(id)!);
        }
        timersRef.current.set(id, setTimeout(() => dismiss(id), AUTO_DISMISS_MS));
        return updated;
      }
      // Fresh push — apply MAX_VISIBLE cap if needed (existing behavior).
      const trimmed = prev.length >= MAX_VISIBLE ? prev.slice(prev.length - MAX_VISIBLE + 1) : prev;
      timersRef.current.set(id, setTimeout(() => dismiss(id), AUTO_DISMISS_MS));
      return [...trimmed, next];
    });
  },
  [dismiss]
);
```

Add a `useRef<Map<string, ReturnType<typeof setTimeout>>>(new Map())` named `timersRef` near the existing state declarations. Wire `upsert` into the context value alongside the existing `push` and `dismiss`.

In the existing `push` flow, switch the timer storage to also use `timersRef` so `upsert`'s timer reset works uniformly.

- [ ] **Step 8: Test ToastProvider.upsert**

```tsx
test("upsert with new id pushes a fresh toast", () => {
  // render provider, call upsert("a", "info", { title: "first" })
  // assert toast visible with text "first"
});

test("upsert with existing id replaces payload in place", () => {
  // upsert("a", "info", { title: "first" })
  // upsert("a", "info", { title: "second" })
  // assert exactly one toast visible with text "second" (not stacked)
});

test("upsert resets auto-dismiss timer on existing id", async () => {
  // upsert("a", "info", { title: "first" })
  // wait 2.5s
  // upsert("a", "info", { title: "second" }) — timer should reset
  // wait 2.5s — toast STILL visible (would have been dismissed at 3s without reset)
  // wait another 0.6s (3.1s total since second upsert) — now dismissed
});

test("upsert respects MAX_VISIBLE cap on fresh ids", () => {
  // upsert "a", "b", "c" → 3 visible
  // upsert "d" → "a" dropped, "b" "c" "d" visible
});
```

- [ ] **Step 9: Implement `useNotifications` hook**

```ts
// hooks/notifications/useNotifications.ts
import { useQuery } from "@tanstack/react-query";
import { listNotifications, type ListNotificationsParams } from "@/lib/notifications/api";
import { notificationKeys } from "@/lib/notifications/queryKeys";

export function useNotifications(params: ListNotificationsParams = {}) {
  return useQuery({
    queryKey: notificationKeys.list({
      group: params.group, unreadOnly: params.unreadOnly,
      page: params.page, size: params.size,
    }),
    queryFn: () => listNotifications(params),
    staleTime: 30_000,
  });
}
```

- [ ] **Step 10: Implement `useUnreadCount` hook (seeds from /me, kept live by WS)**

```ts
import { useQuery } from "@tanstack/react-query";
import { getUnreadCount } from "@/lib/notifications/api";
import { notificationKeys } from "@/lib/notifications/queryKeys";
import { useCurrentUser } from "@/lib/user";

export function useUnreadCount() {
  const { data: user } = useCurrentUser();
  return useQuery({
    queryKey: notificationKeys.unreadCount(),
    queryFn: () => getUnreadCount(),
    initialData: user ? { count: user.unreadNotificationCount } : undefined,
    staleTime: 60_000,
    enabled: !!user,
  });
}

export function useUnreadCountBreakdown() {
  return useQuery({
    queryKey: notificationKeys.unreadCountBreakdown(),
    queryFn: () => getUnreadCount("group"),
    staleTime: 30_000,
  });
}
```

- [ ] **Step 11: Implement `useMarkRead` and `useMarkAllRead`**

```ts
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { markRead, markAllRead } from "@/lib/notifications/api";
import { notificationKeys } from "@/lib/notifications/queryKeys";
import type { NotificationGroup } from "@/lib/notifications/types";

export function useMarkRead() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => markRead(id),
    onMutate: async (id) => {
      // Optimistic: flip read flag in all list caches; decrement count.
      await qc.cancelQueries({ queryKey: notificationKeys.all });
      const prevCount = qc.getQueryData<{ count: number }>(notificationKeys.unreadCount());
      qc.setQueriesData<unknown>({ queryKey: [...notificationKeys.all, "list"] }, (data: any) =>
        data ? { ...data, content: data.content.map((n: any) => n.id === id ? { ...n, read: true } : n) } : data);
      if (prevCount) qc.setQueryData(notificationKeys.unreadCount(), { count: Math.max(0, prevCount.count - 1) });
      return { prevCount };
    },
    onError: (_err, _id, ctx) => {
      if (ctx?.prevCount) qc.setQueryData(notificationKeys.unreadCount(), ctx.prevCount);
      qc.invalidateQueries({ queryKey: notificationKeys.all });
    },
  });
}

export function useMarkAllRead() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (group?: NotificationGroup) => markAllRead(group),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: notificationKeys.all });
    },
  });
}
```

- [ ] **Step 12: Implement `useNotificationStream` (WS subscription dispatcher)**

```tsx
"use client";
import { useEffect } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { useStompSubscription } from "@/lib/ws/hooks";
import { useAuth } from "@/lib/auth";
import { useToast } from "@/components/ui/Toast";
import { notificationKeys } from "@/lib/notifications/queryKeys";
import { categoryConfigOrFallback } from "@/lib/notifications/categoryMap";
import type {
  NotificationsEnvelope, AccountEnvelope, NotificationDto,
} from "@/lib/notifications/types";

export function useNotificationStream() {
  const { status } = useAuth();
  const qc = useQueryClient();
  const toast = useToast();

  const enabled = status === "authenticated";

  useStompSubscription<NotificationsEnvelope>(
    enabled ? "/user/queue/notifications" : "",
    (env) => handleNotificationsEnvelope(env, qc, toast),
  );

  useStompSubscription<AccountEnvelope>(
    enabled ? "/user/queue/account" : "",
    (env) => handleAccountEnvelope(env, qc),
  );

  // On reconnect (existing reconnecting-banner mechanism), invalidate to reconcile.
  // The reconnect signal is exposed via useConnectionState — when it transitions
  // back to "connected" from any other state, invalidate.
  // (Implementation detail: track previous state via ref; on transition, invalidate.)
  // Existing pattern from auction envelopes — match it.
}

function handleNotificationsEnvelope(env: NotificationsEnvelope, qc: any, toast: ReturnType<typeof useToast>) {
  if (env.type === "NOTIFICATION_UPSERTED") {
    const n = env.notification;
    const config = categoryConfigOrFallback(n.category);

    // Update list caches: prepend (insert) or replace by id (update).
    qc.setQueriesData<any>({ queryKey: [...notificationKeys.all, "list"] }, (data: any) => {
      if (!data) return data;
      const existingIdx = data.content.findIndex((x: NotificationDto) => x.id === n.id);
      if (existingIdx >= 0) {
        const updated = [...data.content];
        updated[existingIdx] = n;
        return { ...data, content: updated };
      }
      return { ...data, content: [n, ...data.content], totalElements: data.totalElements + 1 };
    });

    // Increment unread count only on insert; updates leave count alone.
    if (!env.isUpdate) {
      const prev = qc.getQueryData<{ count: number }>(notificationKeys.unreadCount());
      if (prev) qc.setQueryData(notificationKeys.unreadCount(), { count: prev.count + 1 });
      qc.invalidateQueries({ queryKey: notificationKeys.unreadCount() }); // safety net
      qc.invalidateQueries({ queryKey: notificationKeys.unreadCountBreakdown() });
    }

    // Toast — upsert by notification id so a coalesced storm collapses.
    toast.upsert(`notif-${n.id}`, config.toastVariant, {
      title: n.title,
      description: n.body,
      action: config.action ? {
        label: config.action.label,
        onClick: () => { window.location.href = config.action!.href(n.data as any); },
      } : undefined,
    });
    return;
  }
  if (env.type === "READ_STATE_CHANGED") {
    qc.invalidateQueries({ queryKey: notificationKeys.list() });
    qc.invalidateQueries({ queryKey: notificationKeys.unreadCount() });
    qc.invalidateQueries({ queryKey: notificationKeys.unreadCountBreakdown() });
    return;
  }
}

function handleAccountEnvelope(env: AccountEnvelope, qc: any) {
  if (env.type === "PENALTY_CLEARED") {
    qc.invalidateQueries({ queryKey: ["currentUser"] });
  }
}
```

- [ ] **Step 13: Test all hooks (Vitest + MSW)**

```ts
// useUnreadCount.test.ts
test("seeds from /me unreadNotificationCount", async () => { /* ... */ });
test("transitions to live updates from WS", async () => { /* ... */ });

// useMarkRead.test.ts
test("optimistic decrement on mutation start", async () => { /* ... */ });
test("reverts optimistic on failure", async () => { /* ... */ });

// useNotificationStream.test.tsx
test("NOTIFICATION_UPSERTED isUpdate=false prepends and increments count", () => { /* ... */ });
test("NOTIFICATION_UPSERTED isUpdate=true replaces in place, no count change", () => { /* ... */ });
test("READ_STATE_CHANGED invalidates list and count", () => { /* ... */ });
test("PENALTY_CLEARED invalidates currentUser only", () => { /* ... */ });
test("toast.upsert called with correct variant per category", () => { /* ... */ });
test("unknown category falls back to bell glyph + info variant", () => { /* ... */ });
test("reconnect invalidates list and count", () => { /* ... */ });
```

- [ ] **Step 14: Run frontend tests**

```bash
cd frontend && npm test -- --run lib/notifications hooks/notifications components/ui/Toast
```

Expected: green.

- [ ] **Step 15: Commit**

```bash
git add frontend/src/lib/notifications/ \
        frontend/src/hooks/notifications/ \
        frontend/src/components/ui/Toast/ \
        frontend/src/lib/user/api.ts \
        frontend/src/test/msw/
git commit -m "feat(notifications): frontend foundations (types, hooks, MSW, toast.upsert)"
```

---

## Task 7 — Bell + Dropdown + retire OutbidToastProvider

**Goal:** Land `NotificationBell`, `NotificationDropdown` (440px Headless UI Popover), `NotificationDropdownRow` (data-driven), `FilterChips`. Replace `Header.tsx:54-56` placeholder. Mount `useNotificationStream` in `app/providers.tsx`. Retire `OutbidToastProvider` and remove its callsites.

**Files:**
- Create: `frontend/src/components/notifications/NotificationBell.tsx`
- Create: `frontend/src/components/notifications/NotificationDropdown.tsx`
- Create: `frontend/src/components/notifications/NotificationDropdownRow.tsx`
- Create: `frontend/src/components/notifications/FilterChips.tsx`
- Modify: `frontend/src/components/layout/Header.tsx` (replace placeholder)
- Modify: `frontend/src/app/providers.tsx` (mount stream)
- Delete: `frontend/src/components/auction/OutbidToastProvider.tsx` + `.test.tsx`
- Modify: any consumer of `OutbidToastProvider` (`AuctionDetailClient.tsx` and tests)
- Tests: `frontend/src/components/notifications/NotificationBell.test.tsx`, `NotificationDropdown.test.tsx`, `NotificationDropdownRow.test.tsx`

### Steps

- [ ] **Step 1: Create `FilterChips` (shared with feed page)**

```tsx
// components/notifications/FilterChips.tsx
"use client";
import { cn } from "@/lib/cn";
import type { NotificationGroup } from "@/lib/notifications/types";

export type FilterMode = "all" | "unread" | NotificationGroup;

export interface FilterChipsProps {
  value: FilterMode;
  onChange: (next: FilterMode) => void;
  unreadCount?: number;
  groupCounts?: Partial<Record<NotificationGroup, number>>;
  showGroups?: boolean;
}

const GROUP_LABELS: Record<NotificationGroup, string> = {
  bidding: "Bidding",
  auction_result: "Auctions",
  escrow: "Escrow",
  listing_status: "Listings",
  reviews: "Reviews",
  realty_group: "Realty",
  marketing: "Marketing",
  system: "System",
};

export function FilterChips({ value, onChange, unreadCount, groupCounts, showGroups }: FilterChipsProps) {
  return (
    <div className="flex flex-wrap gap-2 items-center">
      <Chip active={value === "all"} onClick={() => onChange("all")}>All</Chip>
      <Chip active={value === "unread"} onClick={() => onChange("unread")}>
        Unread{unreadCount != null && ` (${unreadCount})`}
      </Chip>
      {showGroups && Object.entries(GROUP_LABELS)
        .filter(([g]) => g !== "marketing" && g !== "system" && g !== "realty_group")
        .map(([g, label]) => (
          <Chip key={g} active={value === g} onClick={() => onChange(g as NotificationGroup)}>
            {label}
            {groupCounts?.[g as NotificationGroup] != null && ` (${groupCounts[g as NotificationGroup]})`}
          </Chip>
        ))}
    </div>
  );
}

function Chip({ active, onClick, children }: { active: boolean; onClick: () => void; children: React.ReactNode }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={cn(
        "px-3 py-1 rounded-full text-label-md border transition-colors",
        active
          ? "bg-primary text-on-primary border-primary"
          : "bg-surface text-on-surface border-outline hover:bg-surface-container"
      )}
    >
      {children}
    </button>
  );
}
```

- [ ] **Step 2: Create `NotificationDropdownRow`**

```tsx
"use client";
import { useRouter } from "next/navigation";
import { categoryConfigOrFallback } from "@/lib/notifications/categoryMap";
import { useMarkRead } from "@/hooks/notifications/useMarkRead";
import { cn } from "@/lib/cn";
import type { NotificationDto } from "@/lib/notifications/types";
import { formatRelativeTime } from "@/lib/format/time"; // existing util or add inline if absent

export interface NotificationDropdownRowProps {
  notification: NotificationDto;
  onClose?: () => void;
  variant?: "dropdown" | "feed";
}

export function NotificationDropdownRow({ notification: n, onClose, variant = "dropdown" }: NotificationDropdownRowProps) {
  const router = useRouter();
  const config = categoryConfigOrFallback(n.category);
  const Icon = config.icon;
  const markRead = useMarkRead();

  const handleClickRow = () => {
    if (!n.read) markRead.mutate(n.id);
    const href = config.deeplink(n.data as never);
    router.push(href);
    onClose?.();
  };

  const handleClickAction = (e: React.MouseEvent) => {
    e.stopPropagation();
    if (!config.action) return;
    if (!n.read) markRead.mutate(n.id);
    router.push(config.action.href(n.data as never));
    onClose?.();
  };

  return (
    <button
      type="button"
      onClick={handleClickRow}
      className={cn(
        "w-full flex gap-3 text-left transition-colors",
        variant === "dropdown" ? "px-4 py-3" : "px-4 py-4",
        !n.read ? "bg-primary-container/40 hover:bg-primary-container/60" : "hover:bg-surface-container"
      )}
      aria-label={`${n.title}${n.read ? "" : ", unread"}`}
    >
      <div className={cn(
        "shrink-0 size-8 rounded-md flex items-center justify-center",
        config.iconBgClass
      )}>
        <Icon className="size-4" />
      </div>
      <div className="flex-1 min-w-0">
        <div className="text-body-sm font-semibold text-on-surface truncate">{n.title}</div>
        <div className={cn(
          "text-body-xs text-on-surface-variant",
          variant === "dropdown" ? "line-clamp-2" : "line-clamp-3"
        )}>{n.body}</div>
        <div className="text-label-sm text-on-surface-variant mt-1">
          {formatRelativeTime(n.updatedAt)}
        </div>
        {config.action && (
          <button
            type="button"
            onClick={handleClickAction}
            className="mt-2 px-3 py-1 rounded-md text-label-md border border-outline text-primary hover:bg-primary-container/40"
          >
            {config.action.label} →
          </button>
        )}
      </div>
      {!n.read && <div className="shrink-0 mt-2 size-2 rounded-full bg-primary" />}
    </button>
  );
}
```

If `formatRelativeTime` doesn't exist in `@/lib/format/time`, add one (import dayjs/relativeTime if already used elsewhere, or write a small inline helper).

- [ ] **Step 3: Create `NotificationDropdown`**

```tsx
"use client";
import { Popover, PopoverButton, PopoverPanel } from "@headlessui/react";
import { useState } from "react";
import { useNotifications } from "@/hooks/notifications/useNotifications";
import { useUnreadCount } from "@/hooks/notifications/useUnreadCount";
import { useMarkAllRead } from "@/hooks/notifications/useMarkAllRead";
import { NotificationDropdownRow } from "./NotificationDropdownRow";
import { FilterChips, type FilterMode } from "./FilterChips";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import Link from "next/link";

export interface NotificationDropdownProps {
  onClose: () => void;
}

export function NotificationDropdown({ onClose }: NotificationDropdownProps) {
  const [filter, setFilter] = useState<FilterMode>("all");
  const unreadCount = useUnreadCount();
  const list = useNotifications({
    unreadOnly: filter === "unread",
    size: 10,
  });
  const markAllRead = useMarkAllRead();

  return (
    <PopoverPanel
      anchor={{ to: "bottom end", gap: 8 }}
      className="w-[440px] max-h-[520px] flex flex-col bg-surface border border-outline rounded-xl shadow-elevation-3 overflow-hidden"
    >
      <header className="flex items-center justify-between px-4 py-3 border-b border-outline-variant">
        <div>
          <h2 className="text-title-sm font-semibold text-on-surface">Notifications</h2>
          <div className="mt-2">
            <FilterChips
              value={filter}
              onChange={setFilter}
              unreadCount={unreadCount.data?.count}
            />
          </div>
        </div>
        <button
          type="button"
          onClick={() => markAllRead.mutate(undefined)}
          className="text-label-md text-primary hover:underline shrink-0"
        >
          Mark all read
        </button>
      </header>

      <div className="flex-1 overflow-y-auto">
        {list.isPending ? (
          <div className="p-8 flex justify-center"><LoadingSpinner /></div>
        ) : list.data?.content.length === 0 ? (
          <div className="p-8 text-center text-body-sm text-on-surface-variant">
            {filter === "unread" ? "No unread notifications" : "No notifications yet"}
          </div>
        ) : (
          list.data?.content.map((n) => (
            <NotificationDropdownRow key={n.id} notification={n} onClose={onClose} />
          ))
        )}
      </div>

      <footer className="border-t border-outline-variant px-4 py-2 text-center bg-surface-container">
        <Link
          href="/notifications"
          onClick={onClose}
          className="text-label-md text-primary hover:underline"
        >
          View all notifications
        </Link>
      </footer>
    </PopoverPanel>
  );
}
```

- [ ] **Step 4: Create `NotificationBell`**

```tsx
"use client";
import { Popover, PopoverButton } from "@headlessui/react";
import { Bell } from "@/components/ui/icons";
import { useUnreadCount } from "@/hooks/notifications/useUnreadCount";
import { useAuth } from "@/lib/auth";
import { NotificationDropdown } from "./NotificationDropdown";
import { cn } from "@/lib/cn";

export function NotificationBell() {
  const { status } = useAuth();
  const unread = useUnreadCount();
  if (status !== "authenticated") return null;

  const count = unread.data?.count ?? 0;
  const display = count > 99 ? "99+" : String(count);

  return (
    <Popover className="relative">
      {({ close }) => (
        <>
          <PopoverButton
            className={cn(
              "relative size-10 rounded-md flex items-center justify-center",
              "text-on-surface hover:bg-surface-container transition-colors"
            )}
            aria-label={count > 0 ? `Notifications (${count} unread)` : "Notifications"}
          >
            <Bell className="size-5" />
            {count > 0 && (
              <span
                className="absolute -top-1 -right-1 min-w-[18px] h-[18px] px-1 rounded-full bg-error text-on-error text-[10px] font-bold flex items-center justify-center border-2 border-surface"
                aria-hidden
              >
                {display}
              </span>
            )}
          </PopoverButton>
          <NotificationDropdown onClose={close} />
        </>
      )}
    </Popover>
  );
}
```

- [ ] **Step 5: Replace the Header placeholder**

In `frontend/src/components/layout/Header.tsx`, replace lines 54-56:

```tsx
// BEFORE:
<IconButton aria-label="Notifications" variant="tertiary">
  <Bell />
</IconButton>

// AFTER:
<NotificationBell />
```

Add `import { NotificationBell } from "@/components/notifications/NotificationBell";` to the imports. Remove the `Bell` import from `@/components/ui/icons` (no longer used directly here — `NotificationBell` owns the bell glyph).

- [ ] **Step 6: Mount `useNotificationStream` in providers**

In `frontend/src/app/providers.tsx`, add a thin component that mounts the stream hook (because hooks must be inside components):

```tsx
"use client";
import { useNotificationStream } from "@/hooks/notifications/useNotificationStream";

function NotificationStreamMount() {
  useNotificationStream();
  return null;
}

// In the providers tree, after the QueryClient + Auth + Toast providers:
<NotificationStreamMount />
```

The hook is auth-aware and self-disables when not authenticated, so mounting it unconditionally is safe.

- [ ] **Step 7: Retire `OutbidToastProvider`**

```bash
git rm frontend/src/components/auction/OutbidToastProvider.tsx
git rm frontend/src/components/auction/OutbidToastProvider.test.tsx 2>/dev/null || true
```

Then sweep callsites:
```bash
grep -rn "OutbidToastProvider" frontend/src
```

Each callsite (likely in `AuctionDetailClient.tsx`) — remove the import and the `OutbidToastProvider.maybeFire(...)` invocation. The central `useNotificationStream` now handles outbid toasts via the canonical `OUTBID` notification firing through `/user/queue/notifications`.

Update or remove tests that asserted `OutbidToastProvider` behavior — those scenarios are now covered by `useNotificationStream.test.tsx` from Task 6.

- [ ] **Step 8: Write component tests**

```tsx
// NotificationBell.test.tsx
test("hidden when unauthenticated", () => { /* render with status=unauthenticated, expect null */ });
test("shows badge when unread count positive", () => { /* render with count=3, expect badge text "3" */ });
test("badge caps at 99+", () => { /* count=150, expect "99+" */ });
test("badge hidden when count is 0", () => { /* count=0, expect no badge */ });
test("aria-label includes count when unread", () => { /* count=3, expect aria-label match */ });

// NotificationDropdown.test.tsx
test("renders 10 latest notifications", () => { /* seed 12, expect 10 rendered */ });
test("filter chip 'Unread' filters list", async () => { /* click chip, expect refetch with unreadOnly=true */ });
test("'Mark all read' calls mutation", async () => { /* click, assert mutationFn called with undefined group */ });
test("'View all' link goes to /notifications", () => { /* expect Link with href="/notifications" */ });
test("empty state when no notifications", () => { /* seed 0, expect "No notifications yet" */ });
test("empty state when filtered to unread and all are read", () => { /* expect "No unread notifications" */ });

// NotificationDropdownRow.test.tsx
test("clicking row navigates to category deeplink and marks read", async () => { /* OUTBID → /auction/{id} */ });
test("inline action button uses categoryMap action.href, not row deeplink", async () => {
  // OUTBID's action goes to /auction/{id}#bid-panel; row deeplink goes to /auction/{id}.
  // Click inline button → URL is the action one.
});
test("unknown category falls back to bell glyph + info variant + /notifications deeplink", () => { /* ... */ });
test("unread row has visual unread treatment + dot", () => { /* ... */ });
test("read row has no unread treatment", () => { /* ... */ });
```

- [ ] **Step 9: Run frontend tests**

```bash
cd frontend && npm test -- --run components/notifications
```

Expected: green.

- [ ] **Step 10: Manually verify dev server** (user runs server; we don't)

Skip the dev-server gesture per global instruction. The user has confirmed the dev server is running separately. Tests + type check are the verification surface for this task.

```bash
cd frontend && npm run lint
```

Expected: clean.

- [ ] **Step 11: Commit**

```bash
git add frontend/src/components/notifications/ \
        frontend/src/components/layout/Header.tsx \
        frontend/src/app/providers.tsx
git rm frontend/src/components/auction/OutbidToastProvider.tsx \
       frontend/src/components/auction/OutbidToastProvider.test.tsx
# Plus any AuctionDetailClient.tsx changes that removed the import
git add frontend/src/app/auction/[id]/AuctionDetailClient.tsx
git commit -m "feat(notifications): bell + dropdown + retire OutbidToastProvider"
```

---

## Task 8 — Feed page: /notifications route + sidebar layout

**Goal:** Land the dedicated `/notifications` page with sidebar (group counts) + main column (paginated feed). Mobile sidebar collapses to a drawer.

**Files:**
- Create: `frontend/src/app/notifications/page.tsx`
- Create: `frontend/src/components/notifications/feed/FeedShell.tsx`
- Create: `frontend/src/components/notifications/feed/FeedSidebar.tsx`
- Create: `frontend/src/components/notifications/feed/FeedList.tsx`
- Create: `frontend/src/components/notifications/feed/FeedRow.tsx`
- Tests: `frontend/src/components/notifications/feed/FeedShell.test.tsx`, `FeedSidebar.test.tsx`, alongside others

### Steps

- [ ] **Step 1: Create `app/notifications/page.tsx`**

```tsx
// app/notifications/page.tsx
import { RequireAuth } from "@/components/auth/RequireAuth";
import { FeedShell } from "@/components/notifications/feed/FeedShell";

export default function NotificationsPage() {
  return (
    <RequireAuth>
      <FeedShell />
    </RequireAuth>
  );
}
```

- [ ] **Step 2: Create `FeedRow` (extends DropdownRow visual with feed variant)**

```tsx
"use client";
import { NotificationDropdownRow } from "../NotificationDropdownRow";
import type { NotificationDto } from "@/lib/notifications/types";

export function FeedRow({ notification }: { notification: NotificationDto }) {
  return <NotificationDropdownRow notification={notification} variant="feed" />;
}
```

- [ ] **Step 3: Create `FeedSidebar`**

```tsx
"use client";
import { useUnreadCountBreakdown } from "@/hooks/notifications/useUnreadCount";
import { cn } from "@/lib/cn";
import type { NotificationGroup } from "@/lib/notifications/types";
import type { FilterMode } from "../FilterChips";

const SIDEBAR_GROUPS: { key: NotificationGroup; label: string }[] = [
  { key: "bidding", label: "Bidding" },
  { key: "auction_result", label: "Auctions" },
  { key: "escrow", label: "Escrow" },
  { key: "listing_status", label: "Listings" },
  { key: "reviews", label: "Reviews" },
];

export interface FeedSidebarProps {
  value: FilterMode;
  onChange: (next: FilterMode) => void;
}

export function FeedSidebar({ value, onChange }: FeedSidebarProps) {
  const breakdown = useUnreadCountBreakdown();
  const total = breakdown.data?.count ?? 0;
  const byGroup = breakdown.data?.byGroup ?? {};

  return (
    <aside className="w-[220px] shrink-0 bg-surface border border-outline rounded-xl py-3 h-fit sticky top-24">
      <SidebarSection label="View">
        <SidebarItem active={value === "all"} onClick={() => onChange("all")} count={total}>
          All
        </SidebarItem>
        <SidebarItem active={value === "unread"} onClick={() => onChange("unread")} count={total}>
          Unread
        </SidebarItem>
      </SidebarSection>
      <SidebarSection label="Group">
        {SIDEBAR_GROUPS.map(({ key, label }) => (
          <SidebarItem
            key={key}
            active={value === key}
            onClick={() => onChange(key)}
            count={byGroup[key] ?? 0}
          >
            {label}
          </SidebarItem>
        ))}
      </SidebarSection>
    </aside>
  );
}

function SidebarSection({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="mb-2">
      <div className="px-4 py-1.5 text-label-sm uppercase tracking-wide text-on-surface-variant font-semibold">
        {label}
      </div>
      <div className="flex flex-col">{children}</div>
    </div>
  );
}

function SidebarItem({ active, onClick, count, children }: {
  active: boolean; onClick: () => void; count?: number; children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={cn(
        "flex items-center justify-between px-4 py-2 text-body-sm border-l-[3px]",
        active
          ? "bg-primary-container/40 text-on-surface font-semibold border-primary"
          : "text-on-surface-variant border-transparent hover:bg-surface-container"
      )}
    >
      <span>{children}</span>
      {count != null && count > 0 && (
        <span className={cn(
          "text-label-sm",
          active ? "text-primary font-semibold" : "text-on-surface-variant"
        )}>{count}</span>
      )}
    </button>
  );
}
```

- [ ] **Step 4: Create `FeedList` (paginated feed)**

```tsx
"use client";
import { useNotifications } from "@/hooks/notifications/useNotifications";
import { useMarkAllRead } from "@/hooks/notifications/useMarkAllRead";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import { Pagination } from "@/components/ui/Pagination";
import { FeedRow } from "./FeedRow";
import type { NotificationGroup } from "@/lib/notifications/types";
import { useState } from "react";

export interface FeedListProps {
  group?: NotificationGroup;
  unreadOnly: boolean;
}

export function FeedList({ group, unreadOnly }: FeedListProps) {
  const [page, setPage] = useState(0);
  const list = useNotifications({ group, unreadOnly, page, size: 20 });
  const markAllRead = useMarkAllRead();

  return (
    <div className="flex-1">
      <div className="flex justify-end mb-3">
        <button
          type="button"
          onClick={() => markAllRead.mutate(group)}
          className="text-label-md text-primary hover:underline"
        >
          {group ? `Mark ${group.replace("_", " ")} read` : "Mark all read"}
        </button>
      </div>

      <div className="bg-surface border border-outline rounded-xl overflow-hidden">
        {list.isPending ? (
          <div className="p-12 flex justify-center"><LoadingSpinner /></div>
        ) : list.data?.content.length === 0 ? (
          <div className="p-12 text-center text-body-sm text-on-surface-variant">
            {unreadOnly ? "No unread notifications in this view." : "No notifications yet."}
          </div>
        ) : (
          list.data?.content.map((n) => <FeedRow key={n.id} notification={n} />)
        )}
      </div>

      {list.data && list.data.totalPages > 1 && (
        <div className="mt-4 flex justify-center">
          <Pagination
            currentPage={page}
            totalPages={list.data.totalPages}
            onPageChange={setPage}
          />
        </div>
      )}
    </div>
  );
}
```

If `Pagination` doesn't exist in `@/components/ui/Pagination`, use the project's existing pagination primitive (browse / My Bids / My Listings each have a pagination affordance — match that pattern).

- [ ] **Step 5: Create `FeedShell` (sidebar + main, mobile drawer)**

```tsx
"use client";
import { useState } from "react";
import { Disclosure, DisclosurePanel, DisclosureButton } from "@headlessui/react";
import { FilterIcon } from "@/components/ui/icons";
import { FeedSidebar } from "./FeedSidebar";
import { FeedList } from "./FeedList";
import type { FilterMode } from "../FilterChips";

export function FeedShell() {
  const [filter, setFilter] = useState<FilterMode>("all");

  // Map FilterMode to FeedList's (group, unreadOnly) inputs:
  const group = (filter === "all" || filter === "unread") ? undefined : filter;
  const unreadOnly = filter === "unread";

  return (
    <div className="mx-auto max-w-6xl px-4 py-8">
      <h1 className="text-headline-md font-display font-bold mb-2">Notifications</h1>
      <p className="text-body-sm text-on-surface-variant mb-6">
        Activity from your bids, listings, and account.
      </p>

      {/* Mobile filter drawer */}
      <Disclosure as="div" className="md:hidden mb-4">
        <DisclosureButton className="flex items-center gap-2 px-4 py-2 border border-outline rounded-md text-body-sm">
          <FilterIcon className="size-4" />
          Filters
        </DisclosureButton>
        <DisclosurePanel className="mt-2">
          <FeedSidebar value={filter} onChange={setFilter} />
        </DisclosurePanel>
      </Disclosure>

      <div className="hidden md:grid md:grid-cols-[220px_1fr] gap-6">
        <FeedSidebar value={filter} onChange={setFilter} />
        <FeedList group={group} unreadOnly={unreadOnly} />
      </div>

      {/* Mobile main column (sidebar is in the disclosure above) */}
      <div className="md:hidden">
        <FeedList group={group} unreadOnly={unreadOnly} />
      </div>
    </div>
  );
}
```

- [ ] **Step 6: Write component tests**

```tsx
// FeedShell.test.tsx
test("renders sidebar and main column on desktop", () => { /* viewport >= md, both visible */ });
test("collapses sidebar to drawer on mobile", () => { /* viewport < md, sidebar hidden, drawer trigger visible */ });
test("clicking sidebar group filter updates main column", async () => {
  // seed across multiple groups
  // click "Escrow" sidebar item
  // assert FeedList re-queries with group=escrow
});
test("'View' Unread item filters main column to unread", async () => {
  // ...
});

// FeedSidebar.test.tsx
test("group counts match byGroup response from useUnreadCountBreakdown", () => { /* MSW seeds breakdown */ });
test("zero counts hide the count badge", () => { /* group with 0 unread shows no number */ });
test("active item has primary border and highlight", () => { /* ... */ });
```

- [ ] **Step 7: Run frontend tests**

```bash
cd frontend && npm test -- --run components/notifications/feed app/notifications
```

Expected: green.

- [ ] **Step 8: Lint**

```bash
cd frontend && npm run lint
```

Expected: clean.

- [ ] **Step 9: Commit**

```bash
git add frontend/src/components/notifications/feed/ \
        frontend/src/app/notifications/
git commit -m "feat(notifications): /notifications feed page + sidebar layout"
```

---

## Task 9 — Cross-cutting cleanup: docs, deferred ledger, FOOTGUNS, PR

**Goal:** Update the running ledgers (close 6 entries, add 5), surface the 3 new FOOTGUNS, light edits to DESIGN.md §11, and open the PR with the manual test plan.

**Files:**
- Modify: `docs/implementation/DEFERRED_WORK.md`
- Modify: `docs/implementation/FOOTGUNS.md`
- Modify: `docs/initial-design/DESIGN.md` (light pass on §11)

### Steps

- [ ] **Step 1: Remove 6 closed entries from `DEFERRED_WORK.md`**

Open `docs/implementation/DEFERRED_WORK.md` and delete these full entries:

- `### Notifications for suspension events` (Epic 03 sub-spec 2)
- `### Outbid / won / reserve-not-met / auction-ended notifications` (Epic 04 sub-spec 1)
- `### User-targeted WebSocket queues (`/user/{id}/queue/*`)` (Epic 04 sub-spec 1)
- `### Real-time `PENALTY_CLEARED` push for SuspensionBanner` (Epic 08 sub-spec 2)
- `### Notifications for escrow lifecycle events` (Epic 05 sub-spec 1)
- `### Notifications for bot-detected fraud` (Epic 06)

- [ ] **Step 2: Add 5 new entries to `DEFERRED_WORK.md`**

Append these to the "Current Deferred Items" section:

```markdown
### Email channel for notifications
- **From:** Epic 09 sub-spec 1
- **Why:** Scoped to sub-spec 2. Sub-spec 1 ships in-app only.
- **When:** Epic 09 sub-spec 2.
- **Notes:** Per-category templates (HTML + plain text), signed-token unsubscribe, debounce/dedupe matching the coalesce pattern. Email-change flow (deferred from Epic 02 sub-spec 2b) lands at the same time — both depend on the same email plumbing.

### SL IM channel for notifications
- **From:** Epic 09 sub-spec 1
- **Why:** Scoped to sub-spec 3. Backend queue + terminal polling endpoint; actual delivery awaits Epic 11 LSL terminal.
- **When:** Epic 09 sub-spec 3.

### Notification preferences UI
- **From:** Epic 09 sub-spec 1
- **Why:** Scoped to sub-spec 2 — lands with the email channel. The User entity already has the `notify_email` / `notify_sl_im` JSONB columns from Epic 02 sub-spec 2b; sub-spec 1 doesn't read them because the in-app channel is always-on. The toggle grid + global mute switches + quiet hours pickers belong with sub-spec 2.
- **When:** Epic 09 sub-spec 2.

### REVIEW_RESPONSE_WINDOW_CLOSING notification + scheduler
- **From:** Epic 09 sub-spec 1
- **Why:** No event source today; needs a scheduler that fires N hours before the response window closes. Sub-spec 1 wires every existing event source but doesn't add new schedulers.
- **When:** Epic 10 (Admin & Moderation) — alongside the response-window scheduler for review responses, since both share the timing primitive.

### ESCROW_TRANSFER_REMINDER scheduler
- **From:** Epic 09 sub-spec 1
- **Why:** Sub-spec 1 implements `publisher.escrowTransferReminder(...)` + `NotificationDataBuilder.escrowTransferReminder(...)` + corresponding category and tests. No `@Scheduled` job calls it today — DESIGN.md §729 says the bot service sends this for bot-verified listings (Epic 06 territory). A general non-bot scheduler with an Escrow `reminderSentAt` column for once-per-escrow guarantee is its own scope.
- **When:** Epic 09 sub-spec 2 or sub-spec 3 — alongside email/IM channels that benefit most from reminders. Touchpoint: Epic 06 bot service can call this method directly for its 24h reminder; the general scheduler is the deferred piece.
```

- [ ] **Step 3: Add 3 new entries to `FOOTGUNS.md`**

Find the next available numbered slot (likely §F.85 or so — confirm by checking the last entry). Append:

```markdown
### F.NN — Spring user-destination paths: client subscribes to `/user/queue/X`, never `/user/{id}/queue/X`

The shorthand `/user/{id}/queue/*` shows up in design docs and ledger entries
but is **never** the literal subscription path. Spring's `UserDestinationResolver`
resolves the principal from the STOMP session (set by `JwtChannelInterceptor` on
CONNECT via `accessor.setUser(StompAuthenticationToken)`) and translates the
client's `/user/queue/X` subscription into a session-specific destination. The
backend publishes via `convertAndSendToUser(String.valueOf(userId), "/queue/X", ...)`.

A subscription path that includes a literal user id (e.g.,
`/user/123/queue/notifications`) is a security hole — any authenticated user
could subscribe to any other user's queue by guessing IDs. The `WebSocketConfig`
broker registration must include `/queue` as a destination prefix
(`enableSimpleBroker("/topic", "/queue")`), or `convertAndSendToUser` silently
drops the message because there's no broker for the destination.

### F.NN — Notification publish lifecycle differs by recipient cardinality

Single-recipient publishers (`outbid`, `escrowFunded`, etc.) run **in-tx** with
the originating event — atomic with the event, exceptions roll back the parent.
Acceptable: single recipient = small surface, real failures *should* roll back.

Fan-out publishers (only `listingCancelledBySellerFanout` today) run as
**afterCommit batch** with per-recipient try-catch + `TransactionTemplate`
configured `PROPAGATION_REQUIRES_NEW`. The cancellation is the business event;
notification delivery is the side effect — a side effect must never block the
primary action. afterCommit (rather than REQUIRES_NEW *inside* the parent tx)
also prevents orphan notifications when the parent rolls back unrelatedly.

Mixing these lifecycles produces either:
- "one bad bidder kills the cancellation" (fan-out in-tx)
- "orphan notifications when parent rolls back" (per-recipient REQUIRES_NEW
  inside parent tx)

If you add a new fan-out method, name it with a `Fanout` suffix and accept
`List<Long>` recipients — match `listingCancelledBySellerFanout`'s shape.

### F.NN — Coalesce uses Postgres ON CONFLICT, not find-then-insert-with-retry

The naive race-handling pattern (catch `DataIntegrityViolationException` →
retry as UPDATE) marks the parent transaction rollback-only on the exception,
killing the originating business event (e.g., a bid settlement). The native
`ON CONFLICT (user_id, coalesce_key) WHERE read = false DO UPDATE` upsert
avoids the exception path entirely.

Index design: partial unique on `(user_id, coalesce_key) WHERE read = false`,
created via `NotificationCoalesceIndexInitializer` because Hibernate's
`ddl-auto: update` cannot emit partial indexes. Null `coalesce_key` values
never conflict (NULL ≠ NULL semantics in Postgres unique constraints), so the
same UPSERT query handles both coalescing and non-coalescing categories — no
service-layer branching.

The `xmax = 0` vs `xmax = current_txid` trick in the `RETURNING` clause tells
the DAO whether the operation was insert or update without a second roundtrip.
This drives the `isUpdate` flag on the `NOTIFICATION_UPSERTED` WS envelope,
which the frontend uses to decide whether to prepend (insert) or replace-by-id
(update) in the dropdown cache.
```

(Replace `F.NN` with actual sequential numbers — check the last entry in the file first.)

- [ ] **Step 4: Light edits to `DESIGN.md` §11**

Open `docs/initial-design/DESIGN.md` and find §11 (Notifications). Light edits:

- Note that channel-gating is **group-level** (the `notify_email` / `notify_sl_im` JSONB on User stores per-group ON/OFF), while notification *rows* carry fine-grained `category` enum values for icon/copy/deeplink rendering. Each category maps to exactly one group.
- Note the **coalesce primitive**: rows can carry an optional `coalesce_key`; events with the same key on an unread row UPSERT into that row rather than inserting a fresh one. OUTBID is the first consumer.
- Note the **user-destination security pattern**: clients subscribe to `/user/queue/X`, server publishes via `convertAndSendToUser`, never include a literal user id in the subscription path (FOOTGUNS reference).

Keep the edits surgical — don't rewrite §11. Add a "Notes (Epic 09 sub-spec 1)" subsection at the bottom of §11 with these three bullets.

- [ ] **Step 5: Run the full test suite end-to-end**

```bash
./mvnw test -pl backend
cd frontend && npm test -- --run && npm run lint
cd ..
```

Expected:
- Backend: all green, including ~100+ new tests across notification/, integration/, ws/.
- Frontend: all green, including new component + hook + provider tests.
- Lint: clean.

- [ ] **Step 6: Commit docs**

```bash
git add docs/implementation/DEFERRED_WORK.md \
        docs/implementation/FOOTGUNS.md \
        docs/initial-design/DESIGN.md
git commit -m "docs(notifications): close 6 deferred entries, add 5; FOOTGUNS + DESIGN.md notes"
```

- [ ] **Step 7: Push branch and open PR**

```bash
git push -u origin task/09-sub-1-in-app-notifications
gh pr create --title "Epic 09 sub-spec 1: in-app notifications + real-time push" --body "$(cat <<'EOF'
## Summary

Ships the in-app channel of the notification system end-to-end:
- 22 categories, persistence model with ON CONFLICT coalescing
- Publisher API with type-safe per-category methods
- Real-time delivery via Spring user-destinations (`/user/queue/notifications` + `/user/queue/account`)
- Bell + dropdown + dedicated `/notifications` feed page (sidebar layout)
- Toast integration with `upsert(id, payload)` (timer reset on update)
- Cleanup job + 90-day retention
- Wired into 13 existing event sources

Closes six deferred-ledger entries:
- Suspension event notifications (Epic 03 sub-spec 2)
- Outbid / auction-result notifications (Epic 04 sub-spec 1)
- User-targeted WebSocket queues (Epic 04 sub-spec 1)
- Real-time PENALTY_CLEARED push for SuspensionBanner (Epic 08 sub-spec 2)
- Escrow lifecycle notifications (Epic 05 sub-spec 1)
- Bot-detected fraud notifications (Epic 06)

Email + preferences UI + email-change flow are sub-spec 2; SL IM queue is sub-spec 3.

## Test plan

- [ ] Backend test suite: `./mvnw test -pl backend` → green
- [ ] Frontend test suite: `cd frontend && npm test -- --run` → green
- [ ] Frontend lint: `npm run lint` → clean
- [ ] Two browsers, same user. Trigger an outbid in browser A's auction. Verify both see toast + bell badge increments + dropdown row appears.
- [ ] Browser A click-to-read. Browser B's badge decrements within 1s without focus.
- [ ] Restart backend mid-session. Verify reconnecting banner appears, then disappears on recovery; outbid mid-disconnect appears post-reconnect.
- [ ] Cancel a listing with 3 active bidders. Verify all 3 bidders get a row; seller's cancellation succeeded; auction shows cancelled state via `/topic/auction/{id}` for any open viewers.
- [ ] Trigger `PENALTY_CLEARED` via `PenaltyTerminalService.pay` for a suspended seller. Verify `SuspensionBanner` dismisses without page reload.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

Confirm the PR URL is returned.

- [ ] **Step 8: Wait for CI / manual review, then merge**

After review approval and CI green:

```bash
gh pr merge --merge
git checkout dev && git pull origin dev
```

The branch is now done. Local cleanup branch can be deleted with `git branch -d task/09-sub-1-in-app-notifications`.

---

## Self-review (plan)

After writing, run through these checklists.

### Spec coverage

- [x] §2 Architecture — Tasks 1-4 + 6-8 implement the three layers.
- [x] §3.2 Entity — Task 1 step 3.
- [x] §3.3 Partial unique index — Task 1 step 4.
- [x] §3.4 Enums (22 categories, 8 groups) — Task 1 steps 1-2.
- [x] §3.5 Repository split — Task 1 step 7.
- [x] §3.5 DAO ON CONFLICT — Task 1 step 8.
- [x] §3.6 Service — Task 1 step 9.
- [x] §3.7 Publisher API (single-recipient) — Task 3.
- [x] §3.8 Single-recipient lifecycle (in-tx) — Tasks 1, 3, 5.
- [x] §3.9 Fan-out lifecycle (afterCommit) — Task 3.
- [x] §3.10 WS infrastructure — Task 4.
- [x] §3.11 REST controller — Task 2.
- [x] §3.12 /me extension — Task 2.
- [x] §3.13 Cleanup job — Task 2.
- [x] §3.14 Configuration — Task 2.
- [x] §3.15 Integration touchpoints — Task 5.
- [x] §4 Frontend design — Tasks 6-8.
- [x] §5 Data flow — covered indirectly by integration tests in Task 5 + WS tests in Task 4 + hook tests in Task 6.
- [x] §6 API surface — Tasks 2 + 4.
- [x] §7 Error handling — Tasks 3 (fan-out partial failure), 4 (broadcast swallowing), 6 (optimistic revert), 7 (forward-compat fallback).
- [x] §8 Testing — every task includes its own tests; full coverage matrix realized across the 9 tasks.
- [x] §9 Deferred-work updates — Task 9.
- [x] §10 FOOTGUNS additions — Task 9.
- [x] §11 Sub-spec scope — Task 9 commit message + PR description.
- [x] §12 Documentation updates — Task 9.

### Placeholder scan

- [x] No "TBD", "TODO", "implement later" markers (other than `F.NN` placeholders for FOOTGUNS entry numbers, which are intentional and called out in Task 9 step 3).
- [x] No "fill in details", "handle edge cases" without specifics — every error path and edge case has a concrete behavior.
- [x] All code blocks contain real, runnable code (or carefully-narrated test outlines like `@Test void name() { /* ... */ }` where the assertion shape is clear).
- [x] No "similar to Task N" references — each task carries its own context.

### Type consistency

- [x] `NotificationCategory` enum values match between backend (Java) and frontend (TS) — 22 values, identical names.
- [x] `NotificationGroup` values consistent (lowercase snake_case in JSON wire format, UPPER_SNAKE in Java enum).
- [x] `NotificationPublisher` method names match between Task 3 (interface) and Task 5 (callsites).
- [x] `useToast.upsert(id, kind, payload)` — Task 6 step 7's signature is the same as the type used in Task 6 step 12's `useNotificationStream`.
- [x] WS envelope shapes consistent: `NOTIFICATION_UPSERTED` (with `isUpdate: boolean`, `notification: NotificationDto`), `READ_STATE_CHANGED`, `PENALTY_CLEARED` — same field names backend (Task 4) and frontend (Task 6).
- [x] `categoryConfigOrFallback` is the function name throughout (Task 6 step 4 defines it; Task 7 step 2 + Task 8's FeedRow consume it).
- [x] `NotificationDto` shape: `id, category, group, title, body, data, read, createdAt, updatedAt` — same in Task 1's Java record (step 5) and Task 6's TypeScript interface (step 1).

### Open issues / risks

- **Spring `convertAndSendToUser` test in `UserQueueRoutingTest`** (Task 4 step 7) requires a real WebSocket setup with `@LocalServerPort`. This is a heavier test than other backend tests but is essential as the cross-user leakage guard. If the existing test infrastructure doesn't have a STOMP client harness, set one up — there may be a similar pattern already from Epic 04 STOMP work.
- **`escrowTransferReminder` is implemented but uncalled** in this sub-spec (Task 3 implements the publisher method + builder; no caller wires it). Tests assert the method's wire-up against the publisher, not an integration touchpoint. The deferred-ledger entry in Task 9 step 2 explains. Future bot service or scheduler can call directly.
- **`OutbidToastProvider` retirement** in Task 7 step 7 may surface integration-test breakage at consumer sites (`AuctionDetailClient.tsx`). Sweep the grep results carefully — there may be tests asserting the toast fires from the provider; those scenarios are now `useNotificationStream`'s responsibility (covered by Task 6 step 13).

