# Epic 09 Sub-Spec 1 — In-App Notifications & Real-Time Push

**Date:** 2026-04-25
**Epic:** 09 — Notifications
**Sub-spec scope:** Task 01 + Task 05 from `docs/implementation/epic-09/`, plus the shared infrastructure (`/user/queue/*` STOMP destinations) and integration touchpoints across every existing service that fires a notifiable event today. Email + preferences UI + email-change flow live in sub-spec 2; SL IM queue lives in sub-spec 3.
**Branch:** `task/09-sub-1-in-app-notifications` → `dev`
**Predecessor:** Sub-spec preceding nothing — first sub-spec in Epic 09. Builds on Epic 02 (User entity scaffolding for prefs columns), Epic 04 (STOMP infra + WS reconnect), Epic 05 (escrow lifecycle + stall detection), Epic 08 sub-spec 2 (cancellation events + PenaltyTerminalService payment hook).

---

## 1. Goal

Establish the in-app half of the notification system end-to-end: 22 categories, persistence model, publisher API, real-time delivery via Spring user-destinations, bell + dropdown + dedicated feed page, and integration into every existing event source that fires a notifiable event today. Closes six deferred-ledger entries in one shot — the deferred outbid / auction-end / escrow-lifecycle / suspension / bot-fraud notifications all start firing through this new infrastructure, plus the deferred user-targeted WebSocket queues land at the same time, and the deferred `PENALTY_CLEARED` push for `SuspensionBanner` becomes real.

Out of scope: email channel (sub-spec 2), SL IM queue (sub-spec 3), preferences UI (sub-spec 2 — the User entity already has the JSONB columns from Epic 02 sub-spec 2b), `REVIEW_RESPONSE_WINDOW_CLOSING` and `REALTY_GROUP_*` categories (no event source today), system announcements admin tooling (Epic 10), WS reconnect telemetry (existing deferred entry retained).

This sub-spec is being built for full production launch — not as MVP scaffolding. Production traffic, retention, audit, and concurrency requirements are first-class.

---

## 2. Architecture

```
Event sources (existing services)
    │
    │  publisher.<category>(...)   — synchronous, in-tx for single recipient
    │                                 afterCommit batch for fan-out
    ▼
NotificationService
    │  (1) ON CONFLICT upsert via partial unique index
    │  (2) returns (id, wasUpdate) from Postgres xmax
    │  (3) registers afterCommit → WS broadcast
    ▼
afterCommit
    │
    ├─ NotificationWsBroadcaster   ──►  /user/queue/notifications
    │                                   • NOTIFICATION_UPSERTED { full envelope, isUpdate }
    │                                   • READ_STATE_CHANGED { /* invalidation */ }
    │
    └─ AccountStateBroadcaster     ──►  /user/queue/account
                                        • PENALTY_CLEARED { /* invalidation */ }

Frontend
    │
    ├─ useStompSubscription("/user/queue/notifications") + ("/user/queue/account")
    ├─ NotificationBell + Dropdown (Headless UI Popover, card-style 440px)
    ├─ /notifications page (sidebar layout: 220px sidebar + 1fr main column)
    └─ useToast().upsert(id, payload) — per-category variant, timer reset on update
```

Three-layer architecture, each layer atomic and testable. Backend writes Notification rows in transaction with the originating event (single-recipient) or in afterCommit batch (fan-out); WS broadcast always afterCommit. Frontend consumes `/user/queue/*` via Spring user-destinations (no userId in subscription path), with REST reconcile on reconnect.

---

## 3. Backend design

### 3.1 New top-level packages

```
notification/
├── Notification.java                          — entity
├── NotificationCategory.java                  — enum (21 values)
├── NotificationGroup.java                     — enum (8 values)
├── NotificationRepository.java                — JPA CRUD + count + paged list
├── NotificationDao.java                       — native upsert (ON CONFLICT)
├── NotificationService.java                   — orchestration, transactional
├── NotificationPublisher.java                 — interface (per-category methods)
├── NotificationPublisherImpl.java             — implementation
├── NotificationDataBuilder.java               — per-category data construction
├── NotificationCleanupJob.java                — @Scheduled retention
├── NotificationCleanupProperties.java         — @ConfigurationProperties record
├── NotificationController.java                — /api/v1/notifications
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

Plus integration-shim modifications across existing packages — one-line `publisher.<category>(...)` calls inside each event source's existing transactional method.

### 3.2 Entity

`Notification` table, JPA-managed via `ddl-auto: update` (no Flyway migration per CONVENTIONS.md):

```java
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
public class Notification {
    @Id @GeneratedValue Long id;

    @ManyToOne(fetch = LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    User user;

    @Enumerated(STRING)
    @Column(length = 64, nullable = false)
    NotificationCategory category;

    @Column(length = 200, nullable = false)
    String title;

    @Column(length = 500, nullable = false)
    String body;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    Map<String, Object> data;

    @Column(length = 160)
    String coalesceKey;             // nullable

    @Column(nullable = false)
    Boolean read = false;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    OffsetDateTime updatedAt;        // bumped on coalesce update; sort key
}
```

### 3.3 Partial unique index for coalesce

The load-bearing constraint behind coalescing — created via a `NotificationCoalesceIndexInitializer` (mirrors the `EscrowTransactionTypeCheckConstraintInitializer` pattern from Epic 08 sub-spec 2):

```sql
CREATE UNIQUE INDEX IF NOT EXISTS uq_notification_unread_coalesce
ON notification (user_id, coalesce_key)
WHERE read = false;
```

Notes:
- Postgres treats `NULL ≠ NULL` in unique constraints — categories with `coalesceKey = null` never conflict, every insert succeeds.
- Once a row's `read` flips to `true`, it leaves the partial index — next event with the same key creates a fresh row (the "key release" property).
- The index initializer is idempotent (`IF NOT EXISTS`), runs once on app startup before any UPSERT happens.

### 3.4 Enums

`NotificationCategory` — 22 values, one per copy variant (no conditional rendering inside a category):

| Category | Group | Trigger | Recipient | Coalesce key |
|---|---|---|---|---|
| `OUTBID` | `BIDDING` | `BidService` settlement displaces previous high | bidder | `outbid:{userId}:{auctionId}` |
| `PROXY_EXHAUSTED` | `BIDDING` | `BidService` proxy resolution hits user's max | bidder | `proxy_exhausted:{userId}:{auctionId}` |
| `AUCTION_WON` | `AUCTION_RESULT` | Auction settles, user is the winner | winner | null |
| `AUCTION_LOST` | `AUCTION_RESULT` | Auction settles SOLD; user had bid but isn't winner | bidder | null |
| `AUCTION_ENDED_SOLD` | `AUCTION_RESULT` | Auction settles SOLD | seller | null |
| `AUCTION_ENDED_RESERVE_NOT_MET` | `AUCTION_RESULT` | Auction ends with bids below reserve | seller | null |
| `AUCTION_ENDED_NO_BIDS` | `AUCTION_RESULT` | Auction ends with no bids | seller | null |
| `AUCTION_ENDED_BOUGHT_NOW` | `AUCTION_RESULT` | Buy-now exercised | seller | null |
| `ESCROW_FUNDED` | `ESCROW` | Escrow state → `FUNDED` | seller | null |
| `ESCROW_TRANSFER_CONFIRMED` | `ESCROW` | Escrow state → `TRANSFER_CONFIRMED` | both | null |
| `ESCROW_PAYOUT` | `ESCROW` | Escrow state → `COMPLETED`, payout sent | seller | null |
| `ESCROW_EXPIRED` | `ESCROW` | Escrow state → `EXPIRED` | both | null |
| `ESCROW_DISPUTED` | `ESCROW` | Escrow state → `DISPUTED` | both | null |
| `ESCROW_FROZEN` | `ESCROW` | Escrow state → `FROZEN` (freezeForFraud) | both | null |
| `ESCROW_PAYOUT_STALLED` | `ESCROW` | TerminalCommand `requiresManualReview = true` for payout | seller | null |
| `ESCROW_TRANSFER_REMINDER` | `ESCROW` | 24h transfer reminder scheduler | seller | `transfer_reminder:{userId}:{escrowId}` |
| `LISTING_VERIFIED` | `LISTING_STATUS` | Parcel verification succeeds | seller | null |
| `LISTING_SUSPENDED` | `LISTING_STATUS` | `SuspensionService.suspend()` (incl. bot fraud) | seller | null |
| `LISTING_REVIEW_REQUIRED` | `LISTING_STATUS` | `SuspensionService.markReviewRequired()` | seller | null |
| `LISTING_CANCELLED_BY_SELLER` | `LISTING_STATUS` | `CancellationService.cancel()` on auction with active bids | active bidders (fan-out) | null |
| `REVIEW_RECEIVED` | `REVIEWS` | `ReviewService.reveal()` fires `REVIEW_REVEALED` envelope | reviewee | null |
| `SYSTEM_ANNOUNCEMENT` | `SYSTEM` | Admin push (no source today; placeholder for Epic 10) | broadcast | null |

`NotificationGroup` — 8 values (`BIDDING`, `AUCTION_RESULT`, `ESCROW`, `LISTING_STATUS`, `REVIEWS`, `REALTY_GROUP`, `MARKETING`, `SYSTEM`). Each category maps to exactly one group via `NotificationCategory.getGroup()`.

`OUTBID` is *not* split into proxy-vs-direct variants. The `data.isProxyOutbid` boolean drives a body sub-line ("Your proxy max was reached"), not a different category. Same icon, same urgency.

`AUCTION_WON` and `AUCTION_LOST` are separate categories rather than a single `AUCTION_RESULT` with `data.outcome` — keeps email/SL IM templates conditional-free, keeps icons distinct.

The four `AUCTION_ENDED_*` seller-facing categories are split by outcome (rather than carrying `data.outcome`) for the same reason: per-template clarity, no in-template `if/else`.

### 3.5 Repository + DAO split

`NotificationRepository` (Spring Data JPA) — read-side queries, simple CRUD, count, paged list. Per the project-wide footgun (`BidRepository.findMyBidAuctionIds` vs `findMyBidAuctionIdsUnfiltered` split — "splitting the two shapes keeps each JPQL query literal and avoids the HQL-on-Collection-IS-NULL footgun"), filtered and unfiltered list/markAllRead queries are distinct methods. Service dispatches based on whether `group` is null.

```java
public interface NotificationRepository extends JpaRepository<Notification, Long> {
    // List — group filter present
    @Query("SELECT n FROM Notification n WHERE n.user.id = :userId " +
           "AND n.category IN :categoriesInGroup " +
           "AND (:unreadOnly = false OR n.read = false) " +
           "ORDER BY n.updatedAt DESC, n.id DESC")
    Page<Notification> findForUserByGroup(
        @Param("userId") Long userId,
        @Param("categoriesInGroup") Collection<NotificationCategory> categoriesInGroup,
        @Param("unreadOnly") boolean unreadOnly,
        Pageable pageable);

    // List — no group filter
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

    // Mark-all-read — group filter present
    @Modifying
    @Query("UPDATE Notification n SET n.read = true " +
           "WHERE n.user.id = :userId AND n.read = false " +
           "AND n.category IN :categoriesInGroup")
    int markAllReadByGroup(
        @Param("userId") Long userId,
        @Param("categoriesInGroup") Collection<NotificationCategory> categoriesInGroup);

    // Mark-all-read — no group filter
    @Modifying
    @Query("UPDATE Notification n SET n.read = true " +
           "WHERE n.user.id = :userId AND n.read = false")
    int markAllReadUnfiltered(@Param("userId") Long userId);
}
```

`NotificationDao` (manual `JdbcTemplate` wrapper) — write-side ON CONFLICT upsert, returns whether the operation was insert or update:

```java
public record UpsertResult(Notification notification, boolean wasUpdate) {}

@Component
@RequiredArgsConstructor
public class NotificationDao {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public UpsertResult upsert(long userId, NotificationCategory category, String title,
                               String body, Map<String, Object> data, String coalesceKey) {
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
            RETURNING id, (xmax != 0) AS was_update,
                      created_at, updated_at
            """;
        Map<String, Object> row = jdbc.queryForMap(
            sql, userId, category.name(), title, body,
            objectMapper.writeValueAsString(data), coalesceKey
        );
        // Build a Notification from the returning row + the input fields for the broadcaster.
        // ...
    }
}
```

Why DAO split:
- Spring Data JPA doesn't support `ON CONFLICT` natively; `@Modifying @Query` doesn't return values.
- `JdbcTemplate.queryForMap` is the cleanest path to read the `RETURNING id, (xmax != 0) AS was_update` row.
- Postgres `xmax = 0` for fresh inserts, `xmax = current_txid` for updates — single-roundtrip way to know which path executed.

### 3.6 Service layer

`NotificationService`:

```java
@Service
@RequiredArgsConstructor
@Transactional
public class NotificationService {
    private final NotificationDao dao;
    private final NotificationRepository repo;
    private final NotificationWsBroadcaster wsBroadcaster;

    public UpsertResult publish(NotificationEvent event) {
        UpsertResult result = dao.upsert(
            event.userId(), event.category(), event.title(), event.body(),
            event.data(), event.coalesceKey()
        );
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                wsBroadcaster.broadcastUpsert(event.userId(), result.notification(), result.wasUpdate());
            }
        });
        return result;
    }

    public void markRead(long userId, long notificationId) {
        int affected = repo.markRead(notificationId, userId);
        if (affected == 0) {
            // Either id doesn't exist, isn't owned by caller, or was already read.
            // Distinguish "already read" (idempotent, no broadcast, return 204) from
            // "not found" (404). Probe ownership separately:
            if (!repo.existsByIdAndUserId(notificationId, userId)) {
                throw new NotFoundException("notification not found");
            }
            return; // already-read, no broadcast
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                wsBroadcaster.broadcastReadStateChanged(userId);
            }
        });
    }

    public int markAllRead(long userId, NotificationGroup group) {
        int affected = (group != null)
            ? repo.markAllReadByGroup(userId, group.categories())
            : repo.markAllReadUnfiltered(userId);
        if (affected > 0) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {
                    wsBroadcaster.broadcastReadStateChanged(userId);
                }
            });
        }
        return affected;
    }

    @Transactional(readOnly = true)
    public long unreadCount(long userId) { return repo.countByUserIdAndReadFalse(userId); }

    @Transactional(readOnly = true)
    public Map<NotificationCategory, Long> unreadCountByCategory(long userId) { ... }

    @Transactional(readOnly = true)
    public Page<NotificationDto> listFor(long userId, NotificationGroup group,
                                         boolean unreadOnly, Pageable pageable) {
        Page<Notification> page = (group != null)
            ? repo.findForUserByGroup(userId, group.categories(), unreadOnly, pageable)
            : repo.findForUserUnfiltered(userId, unreadOnly, pageable);
        return page.map(NotificationDto::from);
    }
}
```

`@Transactional` propagation default `REQUIRED` — `publish()` MUST be called from inside a writable parent transaction (the originating event's tx). Service tests assert this.

### 3.7 Publisher API — single-recipient methods

`NotificationPublisher` interface — one method per category, type-safe parameters, no JSON-key typo risk:

```java
public interface NotificationPublisher {
    // Bidding
    void outbid(long bidderUserId, long auctionId, String parcelName,
                long currentBidL, boolean isProxyOutbid, OffsetDateTime endsAt);
    void proxyExhausted(long bidderUserId, long auctionId, String parcelName,
                        long proxyMaxL, OffsetDateTime endsAt);

    // Auction result
    void auctionWon(long winnerUserId, long auctionId, String parcelName, long winningBidL);
    void auctionLost(long bidderUserId, long auctionId, String parcelName, long winningBidL);
    void auctionEndedSold(long sellerUserId, long auctionId, String parcelName, long winningBidL);
    void auctionEndedReserveNotMet(long sellerUserId, long auctionId, String parcelName, long highestBidL);
    void auctionEndedNoBids(long sellerUserId, long auctionId, String parcelName);
    void auctionEndedBoughtNow(long sellerUserId, long auctionId, String parcelName, long buyNowL);

    // Escrow
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

    // Listing status
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

Each method:
1. Builds the per-category `data` map via `NotificationDataBuilder.<category>(...)` — the canonical key contract.
2. Templates the `title` + `body` strings (server-side, fixed templates per category).
3. Computes the `coalesceKey` from category + relevant ids.
4. Calls `notificationService.publish(event)`.

`NotificationDataBuilder` is the centralized contract for what each category puts in `data`. Adding a category = one builder method. Adding a field to an existing category's data = one line in the existing builder method (free-form persistence, no schema migration).

### 3.8 Single-recipient lifecycle (in-tx)

```java
// BidService.settleBid (illustrative — actual code is in BidService)
@Transactional
public BidSettlement settleBid(...) {
    BidSettlement settlement = ...;     // existing logic
    bidRepo.save(...);                  // existing write

    // NEW: in-tx publish, sibling to the bid write — atomic.
    if (settlement.displacedPreviousHigh()) {
        publisher.outbid(
            settlement.displacedUserId(),
            settlement.auctionId(),
            settlement.parcelName(),
            settlement.currentBidL(),
            settlement.isProxyOutbid(),
            settlement.endsAt()
        );
    }

    // EXISTING: domain WS broadcast registers its own afterCommit (unchanged)
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override public void afterCommit() { domainBroadcaster.broadcast(settlementEnvelope); }
    });

    return settlement;
}
```

Inside `notificationService.publish`, the row is INSERTed/UPSERTed against the same transaction, then a separate `afterCommit` registers the notification's WS broadcast. On commit:
- Both rows visible atomically (bid + notification).
- Two independent WS broadcasts fire after commit: one to `/topic/auction/{id}` (the existing settlement envelope), one to `/user/queue/notifications` (the notification envelope).
- If the parent transaction rolls back, the notification row rolls back with it — no orphan.

Single-recipient publishers all follow this pattern. Exceptions thrown by `publish()` propagate and roll back the parent — acceptable because:
- The `ON CONFLICT` upsert eliminates the constraint-conflict path that would otherwise produce DataIntegrityViolationException.
- DELETE /me returns 501 today (Epic 10); FK violations on `user_id` are theoretical.
- Real failures (DB outage, FK breakage) *should* roll back the parent — silently losing the bid is worse than the user retrying.

### 3.9 Fan-out lifecycle (afterCommit batch)

Different lifecycle from single-recipient — the cancellation is the business event, the bidder notifications are side effects. A side effect MUST NOT block the primary action.

```java
public void listingCancelledBySellerFanout(long auctionId, List<Long> bidderUserIds,
                                            String parcelName, String reason) {
    NotificationDataBuilder.ListingCancelledData data =
        NotificationDataBuilder.listingCancelledBySeller(auctionId, parcelName, reason);
    String title = "Listing cancelled: " + parcelName;
    String body = ...;

    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
            for (Long bidderId : bidderUserIds) {
                try {
                    UpsertResult result = txTemplate.execute(status ->
                        notificationDao.upsert(bidderId, LISTING_CANCELLED_BY_SELLER,
                                                 title, body, data.toMap(), null /*coalesceKey*/)
                    );
                    notificationWsBroadcaster.broadcastUpsert(
                        bidderId, result.notification(), result.wasUpdate()
                    );
                } catch (Exception ex) {
                    log.warn("Fan-out notification failed for userId={} auctionId={} category={}: {}",
                        bidderId, auctionId, LISTING_CANCELLED_BY_SELLER, ex.toString());
                    // Continue with remaining recipients.
                }
            }
        }
    });
}
```

`txTemplate` is a `TransactionTemplate` configured with `PROPAGATION_REQUIRES_NEW` — each per-recipient UPSERT runs in its own short transaction. Failures on one recipient don't poison the others.

Why `afterCommit` and not `REQUIRES_NEW` *inside* the parent tx:
- If `REQUIRES_NEW` fired during the parent tx, each notification UPSERT would commit independently. If the parent tx then rolled back (for any unrelated reason), notifications saying "auction X was cancelled" would exist as orphans — auction X actually wasn't cancelled.
- `afterCommit` fires only if the parent committed, eliminating the orphan-notifications risk.

Trade-off (acknowledged):
- For fan-out, we lose the "atomic with the event" guarantee. Bidder notifications materialize *after* cancellation commits — eventual consistency, milliseconds-to-seconds.
- Acceptable because the auction's `/topic/auction/{id}` `AUCTION_CANCELLED` envelope (Epic 08 sub-spec 2) reaches active bidders' tabs in real time alongside the fan-out — the notification is a redundant heads-up.
- A bidder who missed both (closed tab + WS down + JVM crash mid-fan-out) sees the cancelled auction state on next page load anyway.
- Same degradation surface as WS broadcast failure in §10.

### 3.10 WS infrastructure

`JwtChannelInterceptor` (existing, extended):
- On `CONNECT`: validate JWT, then `accessor.setUser(authenticationFromJwt)` so `UserDestinationResolver` has a principal.
- On `SUBSCRIBE` to `/user/queue/**`: allow if `accessor.getUser() != null`.

Spring's `UserDestinationResolver` translates the client's `/user/queue/notifications` subscription into a session-specific destination. Backend publishes via `convertAndSendToUser(String.valueOf(userId), "/queue/notifications", envelope)` — only that user's session(s) receive the message.

Critical security property: the client subscription path is `/user/queue/notifications` with **no userId in the path**. Including a literal user id (e.g., `/user/{id}/queue/notifications`) is a security hole — any authenticated user could subscribe to any other user's queue by guessing IDs. The shorthand `/user/{id}/queue/*` in design docs is conventional notation for "the user-specific queue," not a literal subscription path.

`NotificationWsBroadcaster`:
```java
@Component
@RequiredArgsConstructor
public class NotificationWsBroadcaster {
    private final SimpMessagingTemplate template;

    public void broadcastUpsert(long userId, Notification notif, boolean wasUpdate) {
        try {
            template.convertAndSendToUser(
                String.valueOf(userId),
                "/queue/notifications",
                new NotificationUpsertedEnvelope(wasUpdate, NotificationDto.from(notif))
            );
        } catch (Exception ex) {
            log.warn("WS broadcast NOTIFICATION_UPSERTED failed for userId={} notifId={}: {}",
                userId, notif.getId(), ex.toString());
        }
    }

    public void broadcastReadStateChanged(long userId) {
        try {
            template.convertAndSendToUser(
                String.valueOf(userId),
                "/queue/notifications",
                new ReadStateChangedEnvelope()
            );
        } catch (Exception ex) {
            log.warn("WS broadcast READ_STATE_CHANGED failed for userId={}: {}",
                userId, ex.toString());
        }
    }
}
```

`AccountStateBroadcaster`:
```java
@Component
@RequiredArgsConstructor
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
            log.warn("WS broadcast PENALTY_CLEARED failed for userId={}: {}", userId, ex.toString());
        }
    }
}
```

Strict separation: `NotificationWsBroadcaster` owns `/user/queue/notifications` (`NOTIFICATION_UPSERTED` + `READ_STATE_CHANGED`). `AccountStateBroadcaster` owns `/user/queue/account` (`PENALTY_CLEARED` only). Different broadcasters, different destinations, no cross-routing.

WS broadcast failures are logged at WARN and swallowed. Persistence already committed; the user sees the notification on next page load (REST refetch). The reconnect-reconcile pattern (§7.6) closes the gap.

### 3.11 REST controller

`NotificationController` — base path `/api/v1/notifications`:

```java
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class NotificationController {

    @GetMapping
    public PagedResponse<NotificationDto> list(
        @AuthenticationPrincipal AuthUser caller,
        @RequestParam(required = false) NotificationGroup group,
        @RequestParam(defaultValue = "false") boolean unreadOnly,
        @RequestParam(defaultValue = "0") @Min(0) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) { ... }

    @GetMapping("/unread-count")
    public UnreadCountResponse unreadCount(
        @AuthenticationPrincipal AuthUser caller,
        @RequestParam(required = false) String breakdown
    ) {
        long total = notificationService.unreadCount(caller.userId());
        if ("group".equals(breakdown)) {
            Map<String, Long> byGroup = notificationService
                .unreadCountByCategory(caller.userId())
                .entrySet().stream()
                .collect(groupingBy(
                    e -> e.getKey().getGroup().name().toLowerCase(),
                    summingLong(Map.Entry::getValue)
                ));
            return new UnreadCountResponse(total, byGroup);
        }
        return new UnreadCountResponse(total, null);
    }

    @PutMapping("/{id}/read")
    @ResponseStatus(NO_CONTENT)
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

`UnreadCountResponse`:
```java
public record UnreadCountResponse(
    long count,                                  // always present — total unread across all groups
    @JsonInclude(NON_NULL) Map<String, Long> byGroup    // present only when ?breakdown=group
) {}
```

Strict superset: callers that don't pass `breakdown` get `{ count: 42 }`. Callers that pass `breakdown=group` get `{ count: 42, byGroup: { bidding: 12, escrow: 8, ... } }`. The `count` field name and semantics are preserved across both responses (no breaking change).

### 3.12 `/me` extension

`UserResponse` (existing, returned from `GET /api/v1/users/me`) gains one field:

```java
public record UserResponse(
    // ... existing fields ...
    long unreadNotificationCount     // NEW
) {}
```

`UserService.toResponse(...)` populates from `notificationService.unreadCount(userId)`. Single count query (uses `(user_id, read)` index leading edge — fast). Frontend reads on initial mount for first-paint badge accuracy with no extra REST roundtrip.

### 3.13 Cleanup job

`NotificationCleanupJob`:
```java
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "slpa.notifications.cleanup", name = "enabled", havingValue = "true", matchIfMissing = true)
public class NotificationCleanupJob {

    @Scheduled(cron = "${slpa.notifications.cleanup.cron}")
    @SchedulerLock(name = "notification-cleanup", lockAtMostFor = "PT15M")
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

Chunked delete avoids long-running transactions on large backlogs. `Clock` injected for deterministic testing (per the project-wide convention from the pre-Epic-09 cleanup pass).

### 3.14 Configuration

New section in `application.yml`:

```yaml
slpa:
  notifications:
    cleanup:
      enabled: true
      cron: "0 30 3 * * *"        # 3:30 AM UTC daily
      retention-days: 90          # delete read rows older than this
      batch-size: 1000            # chunked DELETE batch size
```

Backed by `NotificationCleanupProperties`:
```java
@ConfigurationProperties(prefix = "slpa.notifications.cleanup")
@Validated
public record NotificationCleanupProperties(
    boolean enabled,
    @NotBlank String cron,
    @Positive int retentionDays,
    @Positive int batchSize
) {}
```

All integration tests get `slpa.notifications.cleanup.enabled=false` in their `@TestPropertySource` (per the per-job pattern from Epic 05 sub-spec 1, FOOTGUNS reference).

### 3.15 Integration touchpoints

Each row is a one-line `publisher.<category>(...)` call inside the originating service's existing transactional method, sibling to the domain-event write (single-recipient) or as an afterCommit fan-out call (the one fan-out row).

| Service | In-tx call site | Publisher call |
|---------|-----------------|----------------|
| `BidService` | bid settlement, after the displaced-bidder check | `publisher.outbid(displacedUserId, ...)` |
| `BidService` | proxy resolution, when user's proxy max is hit | `publisher.proxyExhausted(bidderUserId, ...)` |
| `AuctionSettlementService` | settle path, per outcome | `publisher.auctionWon / auctionLost / auctionEndedSold / auctionEndedReserveNotMet / auctionEndedNoBids / auctionEndedBoughtNow(...)` |
| `EscrowService` | each state transition, in-tx | `publisher.escrowFunded / escrowTransferConfirmed / escrowPayout / escrowExpired / escrowDisputed / escrowFrozen(...)` |
| `EscrowService.freezeForFraud` | in-tx | `publisher.escrowFrozen(...)` |
| `SuspensionService.suspend` (incl. `suspendForBotObservation`) | in-tx | `publisher.listingSuspended(...)` |
| `SuspensionService.markReviewRequired` | in-tx | `publisher.listingReviewRequired(...)` |
| `ParcelVerificationService` | success path, in-tx | `publisher.listingVerified(...)` |
| `ReviewService.reveal` | in-tx (already broadcasts `REVIEW_REVEALED`) | `publisher.reviewReceived(...)` |
| `CancellationService.cancel` | in-tx (already broadcasts `AUCTION_CANCELLED`) | `publisher.listingCancelledBySellerFanout(auctionId, activeBidderIds, ...)` — **fan-out path** |
| `TerminalCommandService` | when payout-related command transitions to `requiresManualReview = true`, in-tx | `publisher.escrowPayoutStalled(sellerUserId, ...)` |
| `PenaltyTerminalService.pay` | in-tx (afterCommit), when `penaltyBalanceOwed` hits 0 | `accountBroadcaster.broadcastPenaltyCleared(userId)` — **separate queue, separate envelope, no Notification row written** |
| 24h transfer reminder scheduler | per-tick, in-tx | `publisher.escrowTransferReminder(sellerUserId, ...)` |

All calls are minimal — the existing services' transactional logic, error handling, and afterCommit broadcasts remain untouched. The publisher injection is a single field per service.

---

## 4. Frontend design

### 4.1 New top-level packages

```
frontend/src/
├── lib/notifications/
│   ├── api.ts                      — REST client (list, unreadCount, markRead, markAllRead)
│   ├── queryKeys.ts                — TanStack Query v5 cache key factory
│   ├── types.ts                    — NotificationDto, NotificationCategory, group, discriminated data union
│   └── categoryMap.ts              — category → { icon, group, iconBgClass, toastVariant, deeplink, action? }
│
├── hooks/notifications/
│   ├── useNotifications.ts         — paginated list query
│   ├── useUnreadCount.ts           — hydrates from /me, kept live by WS
│   ├── useMarkRead.ts              — mutation w/ optimistic update
│   ├── useMarkAllRead.ts           — mutation w/ optimistic update
│   └── useNotificationStream.ts    — WS subscription, dispatches to caches + toasts
│
└── components/notifications/
    ├── NotificationBell.tsx          — header bell button, badge, opens Popover
    ├── NotificationDropdown.tsx      — Headless UI Popover panel, 440px
    ├── NotificationDropdownRow.tsx   — single generic row, reads categoryMap[category]
    ├── FilterChips.tsx               — All / Unread chips (shared with feed page)
    └── feed/
        ├── FeedShell.tsx             — /notifications layout: 220px sidebar + 1fr main
        ├── FeedSidebar.tsx           — group counts, view toggles
        ├── FeedList.tsx              — paginated list, PagedResponse
        └── FeedRow.tsx               — extends DropdownRow visual; same data-driven logic
```

### 4.2 Data-driven row rendering

No per-category renderer files. A single `NotificationDropdownRow` (and `FeedRow` reusing the same logic) reads from `categoryMap`:

```ts
type CategoryMapEntry = {
  group: NotificationGroup;
  icon: ComponentType;
  iconBgClass: string;          // M3 token, e.g., "bg-warning-container text-on-warning-container"
  toastVariant: ToastKind;
  deeplink: (data: NotificationData) => string;
  action?: {                     // optional inline CTA button
    label: string;
    href: (data: NotificationData) => string;
  };
};

const categoryMap: Record<NotificationCategory, CategoryMapEntry> = {
  OUTBID: {
    group: "bidding",
    icon: BoltIcon,
    iconBgClass: "bg-warning-container text-on-warning-container",
    toastVariant: "warning",
    deeplink: (d) => `/auction/${d.auctionId}`,
    action: { label: "Place a new bid", href: (d) => `/auction/${d.auctionId}#bid-panel` },
  },
  // ... 20 more entries
};
```

Adding a category = one builder method (backend) + one `categoryMap` entry (frontend) + one `NotificationCategory` enum value. No file proliferation, no conditional rendering.

### 4.3 Query keys + cache

```ts
export const notificationKeys = {
  all: ["notifications"] as const,
  list: (filters?: { group?: NotificationGroup; unreadOnly?: boolean; page?: number }) =>
    [...notificationKeys.all, "list", filters ?? {}] as const,
  unreadCount: () => [...notificationKeys.all, "unreadCount"] as const,
};
```

`useUnreadCount` initial value seeded from `useCurrentUser()`'s `unreadNotificationCount`, then transitions to its own query identity for live updates from WS.

### 4.4 Bell + dropdown

`NotificationBell` replaces the existing placeholder in `Header.tsx:54-56`. Headless UI Popover. Badge shows `count` capped at "99+", hidden when `count == 0`. The bell is gated on `useAuth().status === "authenticated"`.

`NotificationDropdown`:
- 440px wide Popover panel, anchored bottom-right of bell.
- Header: "Notifications" title + filter chips (`All` / `Unread`) + "Mark all read" link.
- Body: scrollable list, max ~6 rows visible at once (~400px tall), `useNotifications({ size: 10 })`.
- Footer: "View all notifications" → `/notifications`.

### 4.5 Feed page

Route: `app/notifications/page.tsx`, wrapped in `RequireAuth`. Container: `max-w-6xl px-4 py-8`.

Layout: `220px sidebar + 1fr main` grid.

`FeedSidebar` (sticky-top):
- "View" section: All (total count), Unread (unread count).
- "Group" section: each `NotificationGroup` with its unread count.
- Counts come from `GET /notifications/unread-count?breakdown=group` (single request).
- Click on a sidebar item updates the main column's filters.
- Mobile: collapses to a drawer toggled by a "Filters" button (Headless UI `Disclosure`).

`FeedList`:
- `useNotifications({ group, unreadOnly, page, size: 20 })` against `PagedResponse<NotificationDto>`.
- Numbered pagination at bottom (matches existing browse/listings/My-Bids pattern).
- Empty state per filter combination (e.g., "No unread escrow notifications").

`FeedRow` extends `NotificationDropdownRow` with more padding and a category badge in the meta line ("• Bidding").

### 4.6 Toast integration

`useNotificationStream` mounted high in the tree (`providers.tsx`). On WS message:

| Envelope | Behavior |
|---|---|
| `NOTIFICATION_UPSERTED` `isUpdate=false` | Prepend to list cache, increment unread count, fire toast via `useToast().upsert(notif.id, payload)` |
| `NOTIFICATION_UPSERTED` `isUpdate=true` | Replace-by-id in list cache, refire toast via `upsert` (timer resets) |
| `READ_STATE_CHANGED` | Invalidate `unreadCount()` and `list()` |
| `PENALTY_CLEARED` (on `/user/queue/account`) | Invalidate `["currentUser"]` |

`useToast.upsert(id, payload)` extension to existing `ToastProvider`:
- If a toast with `id` is on the queue: replace its payload, **reset the auto-dismiss timer to 3s from now**, leave queue position unchanged.
- Else: standard `push`, `MAX_VISIBLE = 3` cap applies.
- Per-category variant resolved via `categoryMap[notification.category].toastVariant`.

The timer-reset semantics matter for storms: if a user is being outbid every 2 seconds, the toast should stay visible for 3 seconds *from the last update*, not disappear mid-storm because the original toast's timer expired.

`OutbidToastProvider` (existing in `components/auction/`) is **retired** in this sub-spec — its in-page outbid signal is superseded by the canonical `OUTBID` notification firing through `useNotificationStream`. Keeping both would produce a double-toast on every outbid.

### 4.7 WS subscription extension

`useStompSubscription` (existing hook from Epic 04) gains a `/user/queue/*` variant: accepts a destination string starting with `/user/queue/`. Two new subscriptions on the frontend:
- `/user/queue/notifications` — handles `NOTIFICATION_UPSERTED` + `READ_STATE_CHANGED`.
- `/user/queue/account` — handles `PENALTY_CLEARED`.

Both subscriptions only mount when authenticated. The STOMP client's principal (set during CONNECT via `JwtChannelInterceptor`) drives Spring's user-destination resolution — no userId in the subscription path.

On reconnect (existing reconnecting-banner machinery from Epic 04 sub-spec 2 Task 7): the hook's subscriber re-mounts via the live-Map sweep. `useNotificationStream` additionally invalidates `notificationKeys.unreadCount()` and `notificationKeys.list()` to reconcile any notifications missed during the disconnect. Same pattern auction envelopes already use (FOOTGUNS §F.68).

### 4.8 First-paint badge

`useUnreadCount` reads from `useCurrentUser()`'s `unreadNotificationCount` field on initial mount, then transitions to its own query identity for live updates. Result: badge renders accurately on first paint of any logged-in page; no flicker, no extra REST roundtrip on page load.

---

## 5. Data flow

### 5.1 Single-recipient publish lifecycle

```
1. BidService.settleBid(...) — opens transaction
2. bidRepo.save(newBid)
3. publisher.outbid(displacedUserId, auctionId, parcelName, currentBidL, ...)
   └─ NotificationService.publish(event)
      ├─ data = NotificationDataBuilder.outbid(...)
      ├─ NotificationDao.upsert(...) — ON CONFLICT path determines INSERT vs UPDATE
      │   └─ INSERT (no existing unread row with this coalesce_key) → wasUpdate=false
      │      OR UPDATE (existing unread row matches partial unique index) → wasUpdate=true
      └─ TransactionSynchronization.afterCommit(() →
           wsBroadcaster.broadcastUpsert(userId, notif, wasUpdate))
4. existing afterCommit registered for BidSettlementEnvelope (unchanged)
5. tx commits → both rows visible atomically
6. afterCommit hooks fire (independent broadcasts):
   ├─ /topic/auction/{id} ← BidSettlementEnvelope (existing)
   └─ /user/queue/notifications (via convertAndSendToUser)
       ← NotificationUpsertedEnvelope { isUpdate, notification: NotificationDto }
7. Frontend (displaced user's session):
   ├─ useStompSubscription("/topic/auction/{id}") receives BidSettlementEnvelope
   └─ useStompSubscription("/user/queue/notifications") receives NotificationUpsertedEnvelope:
      ├─ if isUpdate=false: prepend to list cache, increment unread count
      ├─ if isUpdate=true:  replace-by-id in list cache, no count change
      └─ useToast().upsert(notif.id, { kind: "warning", title, description, action })
```

### 5.2 Coalesce path (rapid OUTBID storm)

```
First outbid:
  upsert → ON CONFLICT does not match (no unread row) → INSERT
         → RETURNING id=42, was_update=false
         → broadcastUpsert(notif, isUpdate=false)
         → frontend: prepend, increment count, toast.upsert(42)

Second outbid 3s later (user hasn't read):
  upsert → ON CONFLICT matches (unread row id=42 with same coalesce_key)
         → DO UPDATE SET title=..., body=..., data=..., updated_at=now()
         → RETURNING id=42, was_update=true
         → broadcastUpsert(notif, isUpdate=true)
         → frontend: replace by id, count unchanged, toast.upsert(42) (timer resets)

Concurrent storm — two server threads land same coalesce key simultaneously:
  Both threads hit the ON CONFLICT clause. Postgres serializes through the partial
  unique index (uq_notification_unread_coalesce). Whichever loses the race becomes
  an UPDATE; the winner is an INSERT. Both succeed with no exception.
```

### 5.3 Read lifecycle

```
1. User clicks notification row in dropdown (id=42, currently unread)
2. Frontend (acting tab):
   ├─ useMarkRead.mutate(42)
   │   ├─ Optimistic: setQueryData(list) flips read=true on row 42
   │   ├─ Optimistic: setQueryData(unreadCount) decrements
   │   └─ navigate to deeplink (categoryMap[OUTBID].deeplink(data))
   └─ PUT /api/v1/notifications/42/read
3. Backend:
   ├─ NotificationService.markRead(userId=7, id=42)
   ├─ UPDATE notification SET read=true WHERE id=42 AND user_id=7 AND read=false
   ├─ affected = 1 → register afterCommit
   └─ afterCommit: notificationWsBroadcaster.broadcastReadStateChanged(userId=7)
4. afterCommit fires:
   └─ /user/queue/notifications: { type: "READ_STATE_CHANGED" }
5. All of user 7's tabs (acting + others):
   ├─ useNotificationStream sees READ_STATE_CHANGED
   ├─ invalidate notificationKeys.unreadCount()
   └─ invalidate notificationKeys.list()
   ├─ Acting tab: refetches, gets the same state it already optimistically set — no visible change
   └─ Other tabs: refetches, badge drops by 1, the read row's visual state updates if visible
```

### 5.4 Mark-all-read

Same shape as 5.3 but bulk: `UPDATE ... SET read=true WHERE user_id=? AND read=false [AND category IN :groupCategories]` (group filter when called from sidebar's per-group "mark group read"). Single `READ_STATE_CHANGED` broadcast covers all rows. No row-level WS push.

### 5.5 PENALTY_CLEARED — separate queue, separate envelope

```
1. PenaltyTerminalService.pay(userId, amount) — opens transaction
2. ledger row writes, user.penaltyBalanceOwed -= amount
3. if (newBalance == 0) {
     register afterCommit → accountBroadcaster.broadcastPenaltyCleared(userId)
   }
4. tx commits
5. afterCommit fires: convertAndSendToUser(userId, "/queue/account",
     PenaltyClearedEnvelope { type: "PENALTY_CLEARED" })
6. Frontend:
   ├─ useStompSubscription("/user/queue/account") receives PENALTY_CLEARED
   └─ invalidate ["currentUser"] → /me refetches → SuspensionBanner reads cleared state, dismisses
```

No Notification row is written for `PENALTY_CLEARED`. It's an account-state-change push, not a user-facing event-with-history. The SuspensionBanner is the only consumer.

### 5.6 WS reconnect reconciliation

```
1. WS drops (network blip, server restart)
2. Existing reconnecting-banner machinery (Epic 04 sub-spec 2 Task 7) handles UI feedback
3. Existing useStompSubscription live-Map sweep re-attaches subscriptions on recovery:
   - /topic/auction/{id} (existing)
   - /user/queue/notifications (new)
   - /user/queue/account (new)
4. On successful reconnect, useNotificationStream additionally:
   ├─ invalidate notificationKeys.unreadCount()
   └─ invalidate notificationKeys.list()
5. Any notifications that fired while WS was down materialize via REST refetch
```

Same pattern auction envelopes already use. No replay infrastructure.

### 5.7 Cache invalidation matrix

| Event source | Affected query keys |
|---|---|
| `NOTIFICATION_UPSERTED` `isUpdate=false` | `list()` (prepend), `unreadCount()` (increment optimistically + invalidate) |
| `NOTIFICATION_UPSERTED` `isUpdate=true` | `list()` (replace by id) |
| `READ_STATE_CHANGED` | `list()` (invalidate), `unreadCount()` (invalidate) |
| `PENALTY_CLEARED` | `["currentUser"]` (invalidate) |
| `useMarkRead` mutation success | (no extra invalidation — optimistic already applied; WS broadcast finishes the job for other tabs) |
| `useMarkAllRead` mutation success | (same — WS broadcast handles cross-tab) |
| WS reconnect | `list()`, `unreadCount()` |

---

## 6. API surface

### 6.1 REST endpoints

All paths under `/api/v1/notifications`. All require authenticated user; recipient is implicit from JWT subject — `userId` is never part of the path.

| Method | Path | Body / Query | Response | Notes |
|---|---|---|---|---|
| `GET` | `/notifications` | query: `group?`, `unreadOnly?`, `page=0`, `size=20` (max 100) | `PagedResponse<NotificationDto>` | Sort: `updated_at DESC, id DESC`. `group` is lower-snake. |
| `GET` | `/notifications/unread-count` | query: `breakdown=group?` | `UnreadCountResponse` | `byGroup` only present when `breakdown=group` |
| `PUT` | `/notifications/{id}/read` | — | `204 No Content` | 404 if not owned by caller; idempotent (already-read = 204) |
| `PUT` | `/notifications/read-all` | query: `group?` | `{ markedRead: number }` | `group` scopes to that group only |

`NotificationController` enforces ownership via `caller.userId()` from the JWT — every read filters by it; every mutation rejects on mismatch (404, not 403, to avoid revealing existence).

### 6.2 `/me` extension

`UserResponse` gains:
```java
long unreadNotificationCount;
```

Populated via `notificationService.unreadCount(userId)`.

### 6.3 DTO shapes

```java
public record NotificationDto(
    Long id,
    NotificationCategory category,        // enum name string
    NotificationGroup group,              // derived from category, included for frontend convenience
    String title,
    String body,
    Map<String, Object> data,             // free-form per category
    Boolean read,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {}
```

`coalesceKey` is intentionally not exposed (internal mechanism).

### 6.4 WS envelope shapes

**Destination: `/user/queue/notifications`**
```ts
type NotificationsEnvelope =
  | {
      type: "NOTIFICATION_UPSERTED";
      isUpdate: boolean;
      notification: NotificationDto;
    }
  | {
      type: "READ_STATE_CHANGED";
      // no payload — invalidation only
    };
```

**Destination: `/user/queue/account`**
```ts
type AccountEnvelope =
  | {
      type: "PENALTY_CLEARED";
      // no payload — invalidation only
    };
```

**Destination: `/topic/auction/{id}`** — unchanged (Epic 04 infrastructure).

### 6.5 Authentication / authorization

- **REST:** existing JWT-bearer middleware. `@PreAuthorize("isAuthenticated()")` on the controller class. Per-row authorization enforced inside service methods (filter/reject by caller's userId).
- **WS:** `JwtChannelInterceptor` on CONNECT validates JWT and sets `accessor.setUser(authentication)`. SUBSCRIBE to `/user/queue/**` allowed if `accessor.getUser() != null`. Spring's `UserDestinationResolver` routes to the user's session — cross-user leakage impossible.

### 6.6 Error responses (ProblemDetail)

| Status | When |
|---|---|
| `401 Unauthorized` | No JWT, expired JWT |
| `404 Not Found` | `PUT /notifications/{id}/read` where id doesn't exist or isn't owned by caller |
| `400 Bad Request` | invalid `breakdown` value, invalid `group` value, `size > 100`, `page < 0` |

No 409s — the upsert mechanism eliminates the constraint-conflict path that would otherwise produce one.

---

## 7. Error handling

### 7.1 Backend failure modes

| Failure | Behavior |
|---|---|
| **Notification row write fails** (FK violation, DB outage) | `RuntimeException` propagates; parent transaction rolls back; the originating event (bid settlement, escrow transition, etc.) does not commit. Correct: real failures should not silently lose the original event. |
| **WS broadcast fails** (broker down, no active session) | Caught in the broadcaster (`NotificationWsBroadcaster` / `AccountStateBroadcaster`). Logged at WARN with `userId` + `notificationId`. Persistence already committed; user sees the notification on next page load. The "WS reconnect → invalidate" pattern in §5.6 closes the gap. |
| **Concurrent coalesce** (Postgres lock contention on the partial unique index) | Serializes through the index; both threads succeed (one INSERT, one UPDATE) via ON CONFLICT. No exception path. |
| **Notification publish during a read-only request** | `@Transactional(REQUIRED)` propagation requirement on `publish` — fails if invoked outside a writable transaction. Test asserts. |
| **Mark-read on already-read row** | Idempotent: SQL is `UPDATE ... WHERE read = false`, returns 0 rows affected; controller returns 204. No broadcast fired (no state change). |
| **Mark-read on row owned by another user** | 404 Not Found (not 403 — don't reveal existence). |
| **Cleanup job failure** (long-running delete, lock contention) | Logged at ERROR. Idempotent (`WHERE updated_at < ...`). Next scheduled tick retries. Per-batch chunking (`LIMIT batchSize` looped until 0 rows) prevents transaction explosion on large backlogs. |
| **Fan-out partial failure** (`listingCancelledBySellerFanout` — one bidder's UPSERT fails mid-loop) | Per-recipient try-catch in the `afterCommit` hook with `txTemplate.execute(REQUIRES_NEW)`. Failed bidders logged at WARN with `userId`, `auctionId`, exception summary. Cancellation already committed; remaining bidders continue to receive their notifications. Failed-bidder recovery: REST refetch on next page load + the cancelled auction's WS envelope (`AUCTION_CANCELLED` on `/topic/auction/{id}`). |

### 7.2 Frontend failure modes

| Failure | Behavior |
|---|---|
| **WS disconnect mid-session** | Existing reconnecting-banner machinery shows the banner. On reconnect, `useNotificationStream` invalidates `list()` + `unreadCount()`. Missed notifications materialize from REST. |
| **WS broker down at page load** | Bell badge still renders from `/me`'s `unreadNotificationCount`. Dropdown opens against REST. Reconnecting banner indicates degraded state. Functional, not broken. |
| **`useMarkRead` mutation fails** (network error, 500) | Optimistic update reverts (read flag restored, count incremented back). Toast: `useToast().error(...)`. User can retry. |
| **`useMarkAllRead` partial failure** | Backend bulk update is single-statement — atomic. Either all marked read or none. Optimistic reverts cleanly. |
| **REST list query fails** | Dropdown shows error state with "Try again" button (existing pattern from `MyBidsTab`). Bell badge stays at last known count from `/me`. |
| **Toast queue overflow** | `MAX_VISIBLE = 3` already caps concurrent toasts. Storm of upserts on the *same* notification id collapses to one (timer-reset semantics). Storm of *different* ids — fourth+ queue silently and surface as visible ones dismiss. Notification rows persist regardless. |
| **Notification with unknown category** (forward-compat) | `categoryMap[unknownCategory]` returns undefined. Render falls back to generic icon (bell glyph), `info` toast variant, no inline action, deeplink to `/notifications`. Title and body still readable from the backend. Test asserts. |

### 7.3 Observability

- **Logging:** WARN on broadcast failure (with `userId` + `notificationId`). INFO on cleanup job per-tick rowcounts. WARN on fan-out partial failure with bidder context.
- **Auditability:** the `notification` table is the audit log. `created_at` (initial fire) + `updated_at` (last update) preserved across coalesce. 90-day retention post-read exceeds typical user complaint windows.
- **Metrics:** none added in sub-spec 1. Deferred to Epic 09 sub-spec 2 or Epic 10 alongside existing observability work.

---

## 8. Testing approach

### 8.1 Backend critical-path tests

**Repository / DAO** (real Postgres via TestContainers):

| Test | Asserts |
|---|---|
| `NotificationDaoUpsertTest.upsert_inserts_when_no_existing_unread_row` | `wasUpdate=false`, `xmax=0` interpretation correct |
| `…upsert_updates_when_unread_coalesce_key_match` | `wasUpdate=true`, fields replaced, `created_at` preserved, `updated_at` bumped |
| `…upsert_inserts_when_only_match_is_read` | Read row doesn't satisfy partial unique — fresh INSERT (key-release property) |
| `…upsert_with_null_coalesce_never_conflicts` | Two `coalesce_key=null` inserts both succeed (NULL ≠ NULL) |
| `…concurrent_upsert_serializes_through_partial_index` | Two threads on the same `(user_id, coalesce_key)` end up as one row, no exception |

**Service layer** (`NotificationService`):

| Test | Asserts |
|---|---|
| `publish_writes_in_tx_and_registers_afterCommit_broadcast` | UPSERT invoked, `TransactionSynchronization` registered |
| `publish_when_parent_rolls_back_notification_also_rolls_back` | Atomicity proof — parent throws after `publish()`, no notification row visible after rollback |
| `markRead_idempotent_when_already_read` | `read=true` row → 0 rows affected → no broadcast fired (verified via spy) |
| `markRead_other_users_row_throws_NotFoundException` | Cross-user read attempt blocked at service layer |
| `markAllRead_with_group_filter_only_marks_that_group` | Sidebar's per-group mark-read works |

**Publisher fan-out** (`listingCancelledBySellerFanout`):

| Test | Asserts |
|---|---|
| `fanout_partial_failure_does_not_block_cancellation` | Inject stale bidderId → exception → caught + logged → other recipients land → cancellation committed |
| `fanout_runs_in_afterCommit_not_in_parent_tx` | Force parent rollback — verify zero notifications inserted (orphan-prevention proof) |
| `fanout_each_recipient_gets_own_row` | Three bidders → three rows, distinct ids, correct user_ids |

**WS broadcasters**:

| Test | Asserts |
|---|---|
| `NotificationWsBroadcasterTest.broadcastUpsert_sends_to_user_destination` | `convertAndSendToUser(userId, "/queue/notifications", envelope)` invoked correctly |
| `…broadcastReadStateChanged_sends_invalidation_envelope` | `READ_STATE_CHANGED` shape matches contract |
| `AccountStateBroadcasterTest.penaltyCleared_uses_separate_destination` | Routes to `/user/queue/account`, distinct |

**WS routing security**:

| Test | Asserts |
|---|---|
| `JwtChannelInterceptorTest.connect_sets_user_principal_from_jwt` | Principal populated for `UserDestinationResolver` |
| `UserQueueRoutingTest.user_a_does_not_receive_user_b_notifications` | Two STOMP sessions; broadcaster targets user A; user B's session sees nothing — cross-user leakage guard |
| `…unauthenticated_session_cannot_subscribe_to_user_queue` | SUBSCRIBE rejected when `accessor.getUser() == null` |

**Controller integration**: standard MockMvc tests for each endpoint — happy path, ownership enforcement (404 cross-user), pagination defaults, `?breakdown=group` superset response shape, `?group=invalid` → 400.

**Per-event integration touchpoints**: for each row in §3.15, one integration test that exercises the originating service and asserts the corresponding notification row materializes. Vertical-slice tests, prove wiring per category.

**Cleanup job**:

| Test | Asserts |
|---|---|
| `NotificationCleanupJobTest.deletes_only_read_rows_older_than_90_days` | Boundary: 89-day-read stays, 91-day-read deleted, 91-day-unread stays |
| `…chunked_delete_handles_large_backlog` | 5000 deletable rows complete via chunked LIMITs without OOM/long lock |
| `…disabled_when_property_off` | `slpa.notifications.cleanup.enabled=false` skips execution |

### 8.2 Frontend critical-path tests

**Hook layer** (Vitest + MSW):

| Test | Asserts |
|---|---|
| `useUnreadCount.seeds_from_/me_then_listens_to_ws` | Initial value from `/me`, transitions to live updates |
| `useMarkRead.optimistic_decrement_on_success` | Local cache flips before mutation resolves |
| `useMarkRead.reverts_optimistic_on_failure` | Mutation 500 → cache rolls back, error toast |
| `useNotificationStream.handles_NOTIFICATION_UPSERTED_isUpdate=false` | List prepended, count incremented, toast fires |
| `useNotificationStream.handles_NOTIFICATION_UPSERTED_isUpdate=true` | List replaces by id, count unchanged, toast upserts |
| `useNotificationStream.handles_READ_STATE_CHANGED` | List + count invalidated |
| `useNotificationStream.handles_PENALTY_CLEARED_on_account_queue` | `["currentUser"]` invalidated only |
| `useNotificationStream.reconnect_invalidates_list_and_count` | Simulated reconnect triggers REST refetch |

**Component tests** (Vitest + RTL + MSW):

| Test | Asserts |
|---|---|
| `NotificationBell.shows_badge_when_unread_count_positive` | Badge renders with cap `99+` |
| `NotificationBell.hidden_when_unauthenticated` | No bell for logged-out users |
| `NotificationDropdown.click_row_navigates_and_marks_read` | Calls deeplink + mutation |
| `NotificationDropdown.inline_action_button_uses_action_href_not_deeplink` | OUTBID's "Place a new bid" uses `categoryMap[OUTBID].action.href` |
| `NotificationDropdown.filter_chips_drive_query` | Chip click changes `unreadOnly` filter, refetches |
| `NotificationDropdown.unknown_category_falls_back_to_generic_renderer` | Forward-compat — bell glyph, info variant, deeplink to `/notifications` |
| `FeedShell.sidebar_groups_match_byGroup_response` | Counts wired correctly, click filters main column |
| `FeedShell.mobile_collapses_sidebar_to_drawer` | Responsive breakpoint behavior |
| `useToast.upsert_resets_timer_on_existing_id` | Toast for id=42 visible, second `upsert(42)` → timer is now 3s from now |
| `useToast.upsert_does_not_stack_for_same_id` | Toast queue contains one entry for id=42 |

### 8.3 Test infrastructure additions

- **MSW handlers** for `/api/v1/notifications` list/count/markRead/markAllRead. Fixture data covering at least 5 distinct categories so renderer paths exercise.
- **Existing `notifyEmail: {}`, `notifySlIm: {}` fixture in MSW** extended with `unreadNotificationCount: 0` so existing tests don't break on the new field.
- **WS test harness extension** — extend Epic 04's STOMP test client with `/user/queue/*` subscription helper.

### 8.4 Manual test plan

To be appended to the PR description:

1. Two browsers, same user. Trigger an outbid in browser A's auction. Verify both browsers see the toast, bell badge increments to 1, dropdown shows the row.
2. In browser A, click-to-read the row. Verify browser B's bell badge decrements within 1s without focus.
3. With browser A open, restart the backend. Verify reconnecting banner appears, then disappears on recovery; place an outbid mid-disconnect and verify the row appears post-reconnect (REST refetch).
4. Trigger a fan-out: cancel a listing with 3 active bidders. Verify all 3 bidders get a row in their notification feed; seller's cancellation succeeded; auction shows cancelled state on `/topic/auction/{id}` for any open viewers.
5. Trigger PENALTY_CLEARED via `PenaltyTerminalService.pay` for a suspended seller. Verify dashboard's `SuspensionBanner` dismisses without page reload.

---

## 9. Deferred-work updates

### 9.1 Entries removed on merge

Six existing deferred-ledger entries are closed by sub-spec 1:

- **"Notifications for suspension events"** (Epic 03 sub-spec 2) — `SuspensionService.suspend / freezeForFraud / markReviewRequired` all fire notifications.
- **"Outbid / won / reserve-not-met / auction-ended notifications"** (Epic 04 sub-spec 1) — every event source wired.
- **"User-targeted WebSocket queues (`/user/{id}/queue/*`)"** (Epic 04 sub-spec 1) — `JwtChannelInterceptor` extension + Spring user-destination wiring + frontend `/user/queue/*` subscription.
- **"Real-time `PENALTY_CLEARED` push for SuspensionBanner"** (Epic 08 sub-spec 2) — `AccountStateBroadcaster` + `PenaltyTerminalService.pay` integration.
- **"Notifications for escrow lifecycle events"** (Epic 05 sub-spec 1) — every state transition wired.
- **"Notifications for bot-detected fraud"** (Epic 06) — `suspendForBotObservation` is the same code path as `suspend`, both fire `LISTING_SUSPENDED`.

### 9.2 Entries added on merge

- **"Email channel for notifications"** — From: Epic 09 sub-spec 1. Why: scoped to sub-spec 2. When: Epic 09 sub-spec 2. Notes: integration shape mostly resolved (per-category templates, signed-token unsubscribe, debounce/dedupe matching the coalesce pattern).
- **"SL IM channel for notifications"** — From: Epic 09 sub-spec 1. Why: scoped to sub-spec 3. When: Epic 09 sub-spec 3. Notes: backend queue shape resolved; actual delivery awaits Epic 11 LSL terminal.
- **"Notification preferences UI"** — From: Epic 09 sub-spec 1. Why: scoped to sub-spec 2 (lands with email channel). When: Epic 09 sub-spec 2. Notes: User entity already has the JSONB columns (group-level prefs); this sub-spec doesn't read them because the in-app channel is always-on.
- **"REVIEW_RESPONSE_WINDOW_CLOSING category + scheduler"** — From: Epic 09 sub-spec 1. Why: no event source today; needs a scheduler. When: Epic 10 (Admin & Moderation) — alongside the response-window scheduler for review responses.
- **"REALTY_GROUP_* notification categories"** — From: Epic 09 sub-spec 1. Why: Phase 2 feature, no event source today. When: Phase 2.

`WS reconnect telemetry` ledger entry (existing, from Epic 04 sub-spec 2) is unchanged — its target stays "Epic 09 sub-spec 2 OR Epic 10."

---

## 10. FOOTGUNS additions

Three pitfalls worth surfacing for future maintainers:

- **F.NN — Spring user-destination paths: client subscribes to `/user/queue/X`, never `/user/{id}/queue/X`.** The shorthand `/user/{id}/queue/*` shows up in design docs but is never the literal subscription path. The broker resolves the principal from the STOMP session and routes via `UserDestinationResolver`. A subscription that includes a literal user id in the path is a security hole — anyone could guess.

- **F.NN — Notification publish lifecycle differs by recipient cardinality.** Single-recipient publishers run **in-tx** with the originating event (atomicity guaranteed; exceptions roll back the parent — acceptable, single recipient = small surface). Fan-out publishers run **afterCommit batch** with per-recipient try-catch (event commits regardless of side-effect failures; eventual consistency on bidder notifications). Mixing these — fan-out in-tx, single-recipient afterCommit — produces either the "one bad bidder kills the cancellation" or "orphan notifications when parent rolls back" anti-patterns.

- **F.NN — Coalesce uses Postgres ON CONFLICT, not find-then-insert-with-retry.** The naive race-handling pattern (catch DataIntegrityViolationException → retry as UPDATE) marks the parent transaction rollback-only on the exception, killing the originating business event. The native `ON CONFLICT (user_id, coalesce_key) WHERE read = false DO UPDATE` upsert avoids the exception path entirely. Index design: partial unique on `(user_id, coalesce_key) WHERE read = false`; null coalesce_keys never conflict (NULL ≠ NULL semantics) so the same query handles both coalescing and non-coalescing categories.

---

## 11. Sub-spec scope boundaries

**In sub-spec 1 (this spec):**
- `Notification` entity + repo + DAO + service + controller
- `NotificationPublisher` interface + implementation with all per-category methods
- All 22 categories *defined* in the enum
- Comprehensive event-source wiring (every existing service that should fire today)
- `/user/queue/notifications` + `/user/queue/account` STOMP destinations
- `JwtChannelInterceptor` principal-population extension
- `/me` extension with `unreadNotificationCount`
- Bell + dropdown + feed page UI
- Toast integration with `upsert(id, payload)`
- Cleanup job + retention properties
- Six deferred-ledger items closed

**In sub-spec 2 (email channel + preferences UI + email-change flow):**
- Email provider integration (provider TBD during sub-spec 2 brainstorm)
- Per-category email templates (HTML + plain text)
- Signed-token unsubscribe with one-click GET endpoint
- Email debounce / dedupe (reusing the coalesce pattern)
- Preferences UI (the JSONB toggle grid — User entity columns already scaffolded)
- Email-change flow (deferred from Epic 02 sub-spec 2b — explicit dependency)

**In sub-spec 3 (SL IM queue):**
- `SlImQueue` entity + service
- Terminal polling endpoint (`GET /api/v1/internal/sl-im/pending`)
- Quiet-hours enforcement (User entity columns already scaffolded)
- IM rate limiting

**Out of Epic 09 entirely:**
- `REVIEW_RESPONSE_WINDOW_CLOSING` (needs scheduler — Epic 10)
- `REALTY_GROUP_*` events (Phase 2)
- WS reconnect telemetry (Epic 09 sub-spec 2 or Epic 10)
- System announcements admin tooling (Epic 10)

---

## 12. Documentation updates

- **CLAUDE.md** — no changes. Sub-spec stays within established patterns (Lombok, vertical slices, feature packages, no migrations).
- **DESIGN.md §11** (Notifications section) — light edit pass after merge: note that channel-gating is group-level (not per-category), document the coalesce primitive, document the user-destination security pattern.
- **DEFERRED_WORK.md** — six entries removed, five entries added (per §9).
- **FOOTGUNS.md** — three new entries added (per §10).
