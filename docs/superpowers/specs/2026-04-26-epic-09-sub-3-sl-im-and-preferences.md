# Epic 09 sub-spec 3 — SL IM Channel & Notification Preferences UI

**Date:** 2026-04-26
**Branch (planned):** `task/09-sub-3-sl-im-and-preferences`
**Depends on:** Epic 09 sub-spec 1 (in-app + real-time push), Epic 02 sub-spec 2b (User entity preference columns)
**Successor:** Epic 09 is functionally complete after this sub-spec, modulo deferred items in §10.

---

## 1. Goal & scope

Ship the **SL IM channel** end-to-end as the second user-facing notification channel alongside in-app, plus a **preferences UI** at `/settings/notifications` that lets users control which notification groups deliver via SL IM and master-mute the channel.

The SL IM channel doubles as the email channel: SL natively forwards in-game IMs to the user's registered email when their avatar is offline. Users with active SL email forwarding receive SLPA notifications by email without SLPA building any email infrastructure.

End-to-end means three parts in a single shippable unit:

- **Backend.** A new `SlImMessage` queue table, an `afterCommit` hook in `NotificationService.publish()` plus a sibling call inside the cancellation fan-out's per-recipient `REQUIRES_NEW` lambda, internal polling/confirmation endpoints under `/api/v1/internal/sl-im/*` for the in-world dispatcher, a 48h expiry job with operator-grade INFO logging, a preferences read/write endpoint, and per-recipient gating that reads user preferences fresh on every dispatch.
- **Frontend.** A `/settings` route tree with `/settings/notifications` as its first occupant — banner-style hybrid layout, single column of per-group SL IM toggles with category subtext, a master-mute row that visually disables but does not erase per-group state. Settings entry points from the bell-dropdown footer and the `/notifications` feed-page header.
- **LSL.** A new `lsl-scripts/` directory at the project root with a top-level index README and `lsl-scripts/sl-im-dispatcher/` containing the polling object that delivers via `llInstantMessage`, a config notecard template, and a comprehensive per-script README covering deployment, configuration, operations, and the inherent fire-and-forget limits of `llInstantMessage`.

### Explicitly out of scope

- **Email channel.** Removed from the roadmap; not "coming soon," not deferred. SL IM via offline-IM-to-email forwarding covers the email use case. Re-add only on explicit user request.
- **`SYSTEM_ANNOUNCEMENT` publisher method, admin endpoint, or fan-out-to-all-users mechanism.** The category enum, frontend renderer, and channel gate's bypass-prefs logic all exist or land here, but the trigger is Epic 10 (Admin & Moderation) scope. Sub-spec 3 unit-tests the SYSTEM bypass with a synthetic event; no production code path emits a SYSTEM_ANNOUNCEMENT in this sub-spec.
- **`ESCROW_TRANSFER_REMINDER` scheduler.** The publisher method, category, data builder, and frontend renderer exist from sub-spec 1 with no caller. The scheduler that fires once per escrow ~24h before transfer deadline retargets to Epic 10 alongside the response-window scheduler.
- **HTTP-in push from backend → dispatcher** for low-latency delivery of urgent notifications. Polling-only at 60 s cadence is the design here. Push is a post-launch enhancement once the channel has real traffic and a real latency complaint.
- **Quiet hours.** The User entity's `slImQuietStart` and `slImQuietEnd` columns exist from Epic 02 sub-spec 2b and stay dormant. No scheduling, no held-until logic. A future sub-spec may either expose them in a UI or drop the columns; both options are tracked in the deferred ledger.
- **Multi-avatar accounts.** SLPA models one `slAvatarUuid` per user. Sub-spec 3 doesn't change that.
- **Retry semantics for failed deliveries.** EXPIRED rows are never retried; FAILED rows are not retried (and the dispatcher script doesn't call `/failed` anyway — see §5.5). If retry is wanted later, it's a separate enum value, a separate job, and a separate sub-spec.
- **Notification preferences UI editing the email channel.** The `notifyEmail` JSONB column on User stays in the schema unchanged but is invisible in this sub-spec's UI. When email lands later (if ever), the prefs page grows a section without restructuring.
- **Removing the unused `notifyEmail` column or the dormant quiet-hours columns.** Cheaper to leave them than migrate now; if either stays unused for >12 months, drop them in a dedicated cleanup sub-spec.

### Production-grade posture

This sub-spec ships for full production launch. No "good enough for MVP" choices. The dispatch path is failure-isolated per recipient, the expiry job logs operator-grade pipeline-health signals, the preferences PUT validates a closed shape so frontend bugs surface as 400s instead of silent drops, and the message builder enforces SL's actual byte limit so non-ASCII parcel names don't truncate the deeplink off the bottom of an IM.

---

## 2. Architecture

```
┌────────────────────────────────────────────────────────────────────┐
│  Existing event source (BidService, EscrowService, etc.)           │
│      └─ publisher.<category>(...)                                  │
│           └─ NotificationService.publish(event)  ◄── @MANDATORY    │
│                ├─ DAO upsert (in-app row)                          │
│                ├─ register afterCommit:                            │
│                │     wsBroadcaster.broadcastUpsert(...)            │
│                └─ register afterCommit:        ◄── NEW             │
│                      slImChannelDispatcher.maybeQueue(event)       │
└────────────────────────────────────────────────────────────────────┘
                                │ afterCommit
                                ▼
┌────────────────────────────────────────────────────────────────────┐
│  SlImChannelDispatcher.maybeQueue(event)                           │
│  Inside REQUIRES_NEW per recipient:                                │
│   1. Load User fresh (read your committed prefs)                   │
│   2. Channel gate decision (no-avatar is the universal floor):     │
│        if user.slAvatarUuid==null     ─→ SKIP_NO_AVATAR            │
│        else if category.group == SYSTEM ─→ QUEUE_BYPASS_PREFS      │
│        else if user.notifySlImMuted   ─→ SKIP_MUTED                │
│        else if !notifySlIm[group]     ─→ SKIP_GROUP_DISABLED       │
│        else                           ─→ QUEUE                     │
│   3. Build messageText: "[SLPA] {title}\n\n{body}\n\n{deeplink}"   │
│      (byte-budget ≤ 1024 UTF-8 bytes; ellipsize body if needed)    │
│   4. UPSERT into sl_im_message                                     │
│        ON CONFLICT (user_id, coalesce_key) WHERE status='PENDING'  │
└────────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌────────────────────────────────────────────────────────────────────┐
│  sl_im_message table                                               │
│   id, user_id, avatar_uuid, coalesce_key (nullable),               │
│   message_text, status (PENDING/DELIVERED/EXPIRED/FAILED),         │
│   created_at, updated_at, delivered_at, attempts                   │
│   Partial unique idx: (user_id, coalesce_key) WHERE PENDING        │
│   Index: (status, created_at) for the polling query                │
└────────────────────────────────────────────────────────────────────┘
                                ▲ ▼
┌────────────────────────────────────────────────────────────────────┐
│  /api/v1/internal/sl-im/*  (shared-secret auth via                 │
│                              SlImTerminalAuthFilter)               │
│   GET   /pending?limit=10  → batch (oldest PENDING first,          │
│                                FOR UPDATE SKIP LOCKED)             │
│   POST  /{id}/delivered    → state machine: PENDING→DELIVERED,     │
│                                idempotent on DELIVERED, 409 on     │
│                                FAILED/EXPIRED                      │
│   POST  /{id}/failed       → state machine: PENDING→FAILED,        │
│                                idempotent on FAILED, 409 on        │
│                                DELIVERED/EXPIRED                   │
└────────────────────────────────────────────────────────────────────┘
                                ▲
                                │ poll every 60 s, batch ≤10, 2 s spacing
┌────────────────────────────────────────────────────────────────────┐
│  In-world: lsl-scripts/sl-im-dispatcher/dispatcher.lsl             │
│   timer → llHTTPRequest(POLL_URL)                                  │
│      └─ http_response → for each msg in batch:                     │
│            llInstantMessage(uuid, text); per-IM 2 s timer tick;    │
│            llHTTPRequest(CONFIRM_URL/{id}/delivered)               │
└────────────────────────────────────────────────────────────────────┘

Cleanup job (daily, 03:30 SLT — Spring's zoned cron):
  Stage 1: UPDATE sl_im_message SET status='EXPIRED'
           WHERE status='PENDING' AND created_at < (now - 48h)
  Stage 2: DELETE sl_im_message
           WHERE status IN ('DELIVERED','EXPIRED','FAILED')
             AND updated_at < (now - 30d)
  log INFO: "SL IM expiry sweep: expired={n} deleted={n}
            cutoff={ts} top_users={user_id:count, ...}"
```

Three load-bearing decisions visible in this picture:

### 2.1 afterCommit + REQUIRES_NEW per recipient (single-recipient path)

Same lesson as the cancellation fan-out from sub-spec 1: an IM-queue failure (FK violation, transient DB hiccup, prefs lookup race) must never roll back the originating bid/escrow/cancellation. `NotificationService.publish()` registers the dispatcher's `maybeQueue` call as an afterCommit hook on the parent transaction. Inside the hook, the dispatcher uses the existing `requiresNewTxTemplate` bean (defined in `NotificationConfig` from sub-spec 1, propagation `REQUIRES_NEW`) to wrap the user-fetch + gate-decision + DAO upsert in its own transaction. If the dispatcher tx fails, the in-app row already committed, the parent business event already committed, and the only loss is the IM that wouldn't have queued.

Reliability posture: **in-app guaranteed, IM best-effort.**

### 2.2 Sibling call inside the fan-out's per-recipient REQUIRES_NEW (fan-out path)

`NotificationPublisherImpl.listingCancelledBySellerFanout(...)` from sub-spec 1 calls `notificationDao.upsert()` directly inside a per-recipient REQUIRES_NEW lambda — never touching `NotificationService.publish()`. An `afterCommit` hook attached only to `publish()` would silently miss every `LISTING_CANCELLED_BY_SELLER` IM.

Fix: `slImChannelDispatcher.maybeQueue(...)` is added as a sibling call inside the same per-recipient REQUIRES_NEW lambda, between the DAO upsert and the WS broadcast. Both writes commit atomically per recipient; the per-recipient try-catch already isolates failures from sibling recipients.

```java
// NotificationPublisherImpl.listingCancelledBySellerFanout — modified
for (Long bidderId : bidderUserIds) {
    try {
        UpsertResult result = requiresNewTxTemplate.execute(status -> {
            UpsertResult r = notificationDao.upsert(bidderId,
                NotificationCategory.LISTING_CANCELLED_BY_SELLER,
                title, body, data, /* coalesceKey */ null);
            slImChannelDispatcher.maybeQueueForFanout(    // ← NEW
                bidderId, NotificationCategory.LISTING_CANCELLED_BY_SELLER,
                title, body, data, /* coalesceKey */ null);
            return r;
        });
        wsBroadcaster.broadcastUpsert(bidderId, result, dtoFor(...));
    } catch (Exception ex) {
        log.warn("Fan-out notification failed for userId={}: {}", bidderId, ex.toString());
    }
}
```

Reliability posture: **in-app + IM atomic per recipient** (both write or neither does), with sibling recipients unaffected.

The two postures are different but both correct, and the difference is the kind of trap a future contributor will hit if they refactor either path. It gets a FOOTGUNS entry (§12).

### 2.3 Coalesce key shared with in-app

The `sl_im_message` table reuses the same coalesce key string the in-app `NotificationDao` uses — `outbid:{userId}:{auctionId}`, `proxy_exhausted:{userId}:{auctionId}`, `transfer_reminder:{userId}:{escrowId}`. Different table, different lifecycle (`status='PENDING'` vs `read=false`), but same key namespace and same partial-unique-index pattern. Categories without an in-app coalesce key get no IM coalescing either; that's correct (AUCTION_WON shouldn't collapse). The dispatcher never invents new keys; it passes through whatever key the publisher set.

---

## 3. Backend design

### 3.1 Package layout

```
backend/src/main/java/com/slparcelauctions/backend/notification/slim/
├── SlImMessage.java                       // JPA entity
├── SlImMessageStatus.java                 // enum: PENDING, DELIVERED, EXPIRED, FAILED
├── SlImMessageDao.java                    // native ON CONFLICT upsert
├── SlImMessageRepository.java             // JpaRepository + polling query
├── SlImChannelDispatcher.java             // maybeQueue / maybeQueueForFanout entry points
├── SlImChannelGate.java                   // pure decision function
├── SlImChannelGate.Decision               // discriminated decision enum
├── SlImLinkResolver.java                  // category → URL string
├── SlImMessageBuilder.java                // (title, body, link) → final UTF-8-byte-bounded text
├── SlImCleanupJob.java                    // daily expiry + retention sweep with INFO log
├── SlImCleanupProperties.java             // @ConfigurationProperties
├── SlImCoalesceIndexInitializer.java      // partial unique index DDL at startup
└── ws/                                    // (no new files; sub-spec 1 already wired channels)

backend/src/main/java/com/slparcelauctions/backend/notification/slim/internal/
├── SlImInternalController.java            // GET /pending, POST /{id}/delivered, POST /{id}/failed
├── SlImTerminalAuthFilter.java            // shared-secret filter on /api/v1/internal/sl-im/**
└── SlImInternalProperties.java            // @ConfigurationProperties (shared secret, limit cap)

backend/src/main/java/com/slparcelauctions/backend/notification/preferences/
├── NotificationPreferencesController.java // GET/PUT /api/v1/users/me/notification-preferences
└── NotificationPreferencesDto.java        // record { boolean slImMuted; Map<String,Boolean> slIm }
```

The SL IM dispatcher and gate logic live in a `slim/` sub-package because they're one cohesive subsystem with internal coupling that doesn't need to leak into other notification code. The internal endpoints are further nested in `slim/internal/` to make their non-user-facing nature obvious to readers grepping for endpoint surface area. Preferences live in their own sub-package because the responsibility (read/write user prefs) is distinct from the SL IM channel itself — the same prefs would govern email or any future channel.

### 3.2 `SlImMessage` entity

```java
@Entity
@Table(name = "sl_im_message",
       indexes = {
         @Index(name = "ix_sl_im_status_created", columnList = "status, created_at"),
         @Index(name = "ix_sl_im_user_status", columnList = "user_id, status")
       })
@Getter @Setter @NoArgsConstructor
public class SlImMessage {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "avatar_uuid", nullable = false, length = 36)
    private String avatarUuid;     // denormalized at queue time; user.slAvatarUuid at moment of dispatch

    @Column(name = "coalesce_key", length = 128)
    private String coalesceKey;    // nullable; partial unique index emitted by initializer

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
    private int attempts;          // bumped on each /delivered call (mostly 1)
}
```

The avatar UUID is denormalized into the row at queue time (rather than joined at poll time) for two reasons: the polling query stays a single-table read with no join (predictable plan, easy `FOR UPDATE SKIP LOCKED`), and re-resolving the UUID at poll time would create a race where a user changes their avatar mid-flight and gets messages routed to the wrong UUID. The intended-recipient UUID at the moment of intent is the right value to deliver to.

The `attempts` column exists for triage; in steady state it's always 1 (terminal calls `/delivered` once per send), but if a future enhancement adds retry semantics it's already wired. Bumped via the conditional UPDATE in the controller, not via JPA dirty tracking.

### 3.3 `SlImMessageStatus` enum

```java
public enum SlImMessageStatus {
    PENDING,    // queued, not yet sent by dispatcher
    DELIVERED,  // dispatcher confirmed via POST /delivered
    EXPIRED,    // cleanup job promoted PENDING → EXPIRED after 48h
    FAILED      // operator/manual intervention or future pre-validation revision
}
```

### 3.4 `SlImMessageDao`

Mirrors `NotificationDao` from sub-spec 1's Task 1. Native `ON CONFLICT (user_id, coalesce_key) WHERE status = 'PENDING' DO UPDATE` upsert, with the `xmax = 0` trick in the `RETURNING` clause to distinguish insert from update without a second roundtrip:

```java
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

A `null` coalesce key never matches itself in Postgres unique constraints (NULL ≠ NULL), so the same query handles coalescing and non-coalescing categories with no service-layer branching.

### 3.5 `SlImMessageRepository`

```java
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

`FOR UPDATE SKIP LOCKED` matters because if a future enhancement deploys a fallback dispatcher (e.g., a spare object), concurrent pollers each grab disjoint batches without serializing on a single row. Costs nothing now to future-proof.

### 3.6 `SlImChannelGate`

A pure function: `(User, NotificationCategory) → Decision`. Stateless, instantiated as a `@Component` so it's injectable and Mockito-friendly, but no internal mutable state.

```java
@Component
public class SlImChannelGate {

    public enum Decision {
        QUEUE,                  // normal queue path
        QUEUE_BYPASS_PREFS,     // SYSTEM: skip prefs, still verify avatar
        SKIP_NO_AVATAR,         // user.slAvatarUuid == null
        SKIP_MUTED,             // user.notifySlImMuted == true
        SKIP_GROUP_DISABLED     // user.notifySlIm[group] == false
    }

    public Decision decide(User user, NotificationCategory category) {
        boolean isSystem = category.getGroup() == NotificationGroup.SYSTEM;

        // No-avatar check: hard floor, applies even to SYSTEM.
        if (user.getSlAvatarUuid() == null) {
            return Decision.SKIP_NO_AVATAR;
        }

        if (isSystem) {
            return Decision.QUEUE_BYPASS_PREFS;
        }

        if (user.isNotifySlImMuted()) {
            return Decision.SKIP_MUTED;
        }

        Map<String, Boolean> prefs = user.getNotifySlIm();
        String groupKey = category.getGroup().name().toLowerCase();
        Boolean groupOn = prefs.get(groupKey);
        if (groupOn == null || !groupOn) {
            return Decision.SKIP_GROUP_DISABLED;
        }

        return Decision.QUEUE;
    }
}
```

Returning a discriminated `Decision` (instead of a boolean) means the dispatcher logs the reason at DEBUG, which makes "I didn't get an IM" support tickets resolvable from logs alone.

### 3.7 `SlImChannelDispatcher`

Two entry points: `maybeQueue` (single-recipient, called from `NotificationService.publish`'s afterCommit hook) and `maybeQueueForFanout` (called from inside the per-recipient REQUIRES_NEW lambda in `NotificationPublisherImpl.listingCancelledBySellerFanout`). Both share core logic.

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class SlImChannelDispatcher {

    private final UserRepository userRepo;
    private final SlImChannelGate gate;
    private final SlImMessageBuilder messageBuilder;
    private final SlImLinkResolver linkResolver;
    private final SlImMessageDao dao;
    @Qualifier("requiresNewTxTemplate")
    private final TransactionTemplate requiresNewTx;

    /**
     * Single-recipient entry point. Caller is in afterCommit; we open our own
     * REQUIRES_NEW transaction so a failure here doesn't roll back anything else.
     */
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
     * Fan-out entry point. Caller is already inside a REQUIRES_NEW per-recipient
     * lambda; this writes as a sibling, atomic with the in-app row for that recipient.
     * Failures here propagate and roll back that recipient's in-app row too.
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

The fan-out variant intentionally does NOT wrap in REQUIRES_NEW (the caller already did), and intentionally does NOT swallow exceptions (the caller's per-recipient try-catch handles isolation). The single-recipient variant does both, because it runs in afterCommit on the parent's transaction and any failure must not propagate.

### 3.8 `SlImLinkResolver`

```java
@Component
@RequiredArgsConstructor
public class SlImLinkResolver {

    private final SlpaWebProperties webProps;  // exposes slpa.web.base-url

    public String resolve(NotificationCategory category, Map<String, Object> data) {
        String base = webProps.baseUrl();  // e.g. "https://slpa.example.com"
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

This duplicates the deeplink logic that lives in the frontend `categoryMap` (`frontend/src/lib/notifications/categoryMap.ts`). The duplication is intentional — the alternative would be to embed the URL in the in-app `Notification.data` JSON blob at publish time, which couples the in-app row to a specific channel's URL semantics and makes URL changes a data migration. Twenty-line switch in two languages is fine.

The `SlpaWebProperties` `@ConfigurationProperties` record (`slpa.web.base-url`) is read from `application.yml` and must be set per-environment. Defaulting to `https://slpa.example.com` in dev / staging / prod values is appropriate.

### 3.9 `SlImMessageBuilder` (with byte-budget enforcement)

```java
@Component
public class SlImMessageBuilder {

    private static final int MAX_BYTES = 1024;        // SL llInstantMessage hard limit
    private static final String PREFIX = "[SLPA] ";
    private static final String SEPARATOR = "\n\n";
    private static final String ELLIPSIS = "…";       // 3 bytes UTF-8

    public String assemble(String title, String body, String deeplink) {
        // The deeplink and prefix and title are non-negotiable; only body ellipsizes.
        String trimmedBody = body;
        String candidate = PREFIX + title + SEPARATOR + trimmedBody + SEPARATOR + deeplink;

        if (candidate.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES) {
            return candidate;
        }

        // Reserve everything except the body, then trim the body to fit.
        int reservedBytes = (PREFIX + title + SEPARATOR + SEPARATOR + deeplink + ELLIPSIS)
            .getBytes(StandardCharsets.UTF_8).length;
        int availableForBody = MAX_BYTES - reservedBytes;
        if (availableForBody <= 0) {
            // Pathological: title + deeplink alone exceed the budget. Fall back to
            // PREFIX + truncated-title + SEPARATOR + deeplink, dropping the body.
            return assembleWithoutBody(title, deeplink);
        }

        // Trim body by Java char from the end; back off one extra char if the trim
        // boundary lands on a high surrogate to avoid invalid UTF-16.
        int k = trimmedBody.length();
        while (k > 0) {
            String attempt = trimmedBody.substring(0, k) + ELLIPSIS;
            int bytes = attempt.getBytes(StandardCharsets.UTF_8).length;
            if (bytes <= availableForBody) {
                if (k > 0 && Character.isHighSurrogate(trimmedBody.charAt(k - 1))) {
                    k -= 1;
                    continue;
                }
                trimmedBody = attempt;
                break;
            }
            k -= 1;
        }
        if (k == 0) {
            return assembleWithoutBody(title, deeplink);
        }
        return PREFIX + title + SEPARATOR + trimmedBody + SEPARATOR + deeplink;
    }

    private String assembleWithoutBody(String title, String deeplink) {
        // Last-resort fallback. Title also trimmed if it's the cause.
        // ... bounded similar trim loop on title ...
        return PREFIX + title + SEPARATOR + deeplink;
    }
}
```

The byte-budget enforcement is the single most fragile piece of this sub-spec. SL's `llInstantMessage` truncates at 1024 **bytes**, not characters, and silently — there is no error, no warning, no return value. A parcel name like `東京タワー Estates` contains 5 multi-byte CJK characters that occupy 15 bytes in UTF-8 versus 5 in UTF-16. Without the byte-aware budget, a 1023-character message could occupy 1500+ UTF-8 bytes, with the deeplink at the bottom getting truncated off cleanly. Users would receive the IM, click on... nothing, and report a "broken notification."

The implementer must match the contract:
- Measure with `getBytes(StandardCharsets.UTF_8).length`, never `String.length()`.
- Never trim the deeplink, prefix, or separators — they're load-bearing.
- Only trim the body. Use a single ellipsis character at the trim point.
- Back off one char if trimming would split a UTF-16 surrogate pair.
- Three test cases are mandatory: a multi-byte parcel name (CJK, accented Latin), an emoji-bearing parcel name, and a body so long it triggers actual truncation. All assert the deeplink survives intact and the total byte count ≤ 1024.

### 3.10 `SlImCleanupJob` + `SlImCleanupProperties`

```java
@ConfigurationProperties(prefix = "slpa.notifications.sl-im.cleanup")
@Validated
public record SlImCleanupProperties(
    boolean enabled,
    @NotBlank String cron,
    @Positive int expiryAfterHours,         // default 48
    @Positive int retentionAfterDays,       // default 30
    @Positive int batchSize,                // default 1000
    @Min(0) @Max(20) int topUsersInLog      // default 10
) {}
```

```yaml
slpa:
  notifications:
    sl-im:
      cleanup:
        enabled: true
        cron: "0 30 3 * * *"         # 03:30 daily, in zone below
        expiry-after-hours: 48
        retention-after-days: 30
        batch-size: 1000
        top-users-in-log: 10
```

```java
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "slpa.notifications.sl-im.cleanup",
                       name = "enabled", havingValue = "true", matchIfMissing = false)
@Slf4j
public class SlImCleanupJob {

    private final JdbcTemplate jdbc;
    private final SlImCleanupProperties properties;
    private final Clock clock;

    @Scheduled(cron = "${slpa.notifications.sl-im.cleanup.cron}", zone = "America/Los_Angeles")
    public void run() {
        OffsetDateTime expiryCutoff = OffsetDateTime.now(clock)
            .minusHours(properties.expiryAfterHours());
        OffsetDateTime retentionCutoff = OffsetDateTime.now(clock)
            .minusDays(properties.retentionAfterDays());

        // Stage 1: PENDING → EXPIRED, capturing per-user counts BEFORE the update
        // (so the log line names the affected users).
        List<Map<String, Object>> topUsers = jdbc.queryForList("""
            SELECT user_id, COUNT(*) AS n
            FROM sl_im_message
            WHERE status = 'PENDING' AND created_at < ?
            GROUP BY user_id
            ORDER BY n DESC
            LIMIT ?
            """, expiryCutoff, properties.topUsersInLog());

        int expired = jdbc.update("""
            UPDATE sl_im_message SET status = 'EXPIRED', updated_at = now()
            WHERE status = 'PENDING' AND created_at < ?
            """, expiryCutoff);

        // Stage 2: chunked DELETE for terminal-status rows past retention.
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

The INFO log is the operational canary. Stage 1's `expired` count and `top_users` together name "is the dispatcher dark" (high `expired` across many users) versus "did one user's avatar UUID rot" (high `expired` concentrated on one user). Steady-state values are zero `expired` and small `deleted` (matching the daily delivery volume aged 30 days back).

The `top_users` query is captured BEFORE the UPDATE because once rows are EXPIRED the WHERE clause no longer matches them. Two queries instead of one is acceptable — the first is a small aggregation against an indexed column and partial index, the second is the bulk update.

### 3.11 `SlImTerminalAuthFilter`

A `OncePerRequestFilter` registered in the security chain via a custom `WebSecurityConfig` adjustment:

```java
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
        if (header == null || !MessageDigest.isEqual(
                header.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8))) {
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

`MessageDigest.isEqual` provides constant-time comparison so the secret cannot be guessed via response timing.

The filter is registered in `WebSecurityConfig` via `.addFilterBefore(slImTerminalAuthFilter, JwtAuthenticationFilter.class)`. Internal endpoints are also added to the `permitAll()` matcher for the JWT chain (so JWT processing doesn't reject them) and to the CSRF-disabled matchers (no browser origin).

```java
public record SlImInternalProperties(
    @NotBlank String sharedSecret,
    @Positive @Max(50) int maxBatchLimit       // hard cap on ?limit query param
) {}
```

Configuration:
```yaml
slpa:
  notifications:
    sl-im:
      dispatcher:
        shared-secret: ${SL_IM_DISPATCHER_SECRET:dev-only-secret-do-not-use-in-prod}
        max-batch-limit: 50
```

### 3.12 `SlImInternalController`

```java
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
            throw new IllegalArgumentException("limit must be in [1, " + props.maxBatchLimit() + "]");
        }
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
     * State machine for both endpoints:
     *
     *   /delivered:  PENDING   → DELIVERED (204, set delivered_at)
     *                DELIVERED → 204 no-op
     *                FAILED    → 409
     *                EXPIRED   → 409
     *                missing   → 404
     *
     *   /failed:     PENDING   → FAILED (204)
     *                FAILED    → 204 no-op
     *                DELIVERED → 409
     *                EXPIRED   → 409
     *                missing   → 404
     */
    private ResponseEntity<Void> transition(long id, SlImMessageStatus target) {
        // Conditional UPDATE. Returns affected count.
        // Note: delivered_at is only set on the PENDING→DELIVERED transition;
        // idempotent calls don't re-stamp it.
        String sql;
        if (target == SlImMessageStatus.DELIVERED) {
            sql = """
                UPDATE sl_im_message
                SET status = 'DELIVERED',
                    delivered_at = COALESCE(delivered_at, now()),
                    updated_at = now(),
                    attempts = attempts + 1
                WHERE id = ? AND status = 'PENDING'
                """;
        } else {
            sql = """
                UPDATE sl_im_message
                SET status = 'FAILED', updated_at = now(), attempts = attempts + 1
                WHERE id = ? AND status = 'PENDING'
                """;
        }
        int updated = jdbc.update(sql, id);
        if (updated == 1) {
            return ResponseEntity.noContent().build();
        }

        // No row updated. Read current state to decide the response.
        Optional<SlImMessage> opt = repo.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        SlImMessageStatus current = opt.get().getStatus();
        if (current == target) {
            // idempotent no-op
            return ResponseEntity.noContent().build();
        }
        // current is one of the other terminal statuses → 409
        return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }
}
```

The conditional UPDATE returning affected-rows count, combined with a single follow-up read on the no-op path, gives the state machine in §7 with one round trip in the happy path and two round trips on idempotent / conflict paths. The follow-up read is lock-free; we already know the row's not in the target state we wrote.

### 3.13 `NotificationPreferencesController`

```java
@RestController
@RequestMapping("/api/v1/users/me/notification-preferences")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class NotificationPreferencesController {

    private final UserRepository userRepo;

    public record PreferencesDto(
        boolean slImMuted,
        Map<String, Boolean> slIm
    ) {}

    private static final Set<String> ALLOWED_GROUP_KEYS = Set.of(
        "bidding", "auction_result", "escrow", "listing_status", "reviews");

    @GetMapping
    public PreferencesDto get(@AuthenticationPrincipal AuthPrincipal caller) {
        User user = userRepo.findById(caller.userId()).orElseThrow();
        Map<String, Boolean> filtered = ALLOWED_GROUP_KEYS.stream()
            .collect(Collectors.toMap(
                k -> k,
                k -> user.getNotifySlIm().getOrDefault(k, false)
            ));
        return new PreferencesDto(user.isNotifySlImMuted(), filtered);
    }

    @PutMapping
    @Transactional
    public PreferencesDto put(
        @AuthenticationPrincipal AuthPrincipal caller,
        @RequestBody PreferencesDto body
    ) {
        // Closed-shape validation: keys must be exactly ALLOWED_GROUP_KEYS.
        if (body.slIm == null || !body.slIm.keySet().equals(ALLOWED_GROUP_KEYS)) {
            throw new BadRequestException(
                "slIm must contain exactly these keys: " + ALLOWED_GROUP_KEYS);
        }
        // (Map<String,Boolean> via Jackson rejects non-boolean values at deserialization.)

        User user = userRepo.findById(caller.userId()).orElseThrow();
        user.setNotifySlImMuted(body.slImMuted);
        // Merge into existing map: preserve keys we don't expose (system, realty_group,
        // marketing) at their existing/default values. Only update visible keys.
        Map<String, Boolean> merged = new HashMap<>(user.getNotifySlIm());
        merged.putAll(body.slIm);
        user.setNotifySlIm(merged);
        userRepo.save(user);

        return get(caller);   // return reconciled state for optimistic mutation reconcile
    }
}
```

The PUT body's shape is validated as a closed set: exactly the five user-mutable group keys, no more, no fewer. Sending `{"slIm": {"system": false, ...}}` returns 400. This is intentional — the server is the source of truth for what's user-mutable, and frontend bugs that send unexpected keys should be loud.

The merge logic preserves keys we don't expose (`system`, `realty_group`, `marketing`) at their existing values, so a user who has never touched their prefs still has their `User.defaultNotifySlIm()` defaults intact for groups not on the page.

### 3.14 `SlImCoalesceIndexInitializer`

Mirrors `NotificationCoalesceIndexInitializer` from sub-spec 1's Task 1. Hibernate's `ddl-auto: update` cannot emit partial unique indexes, so we add it via an `@EventListener(ApplicationReadyEvent)` that runs idempotent DDL:

```java
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

`CREATE UNIQUE INDEX IF NOT EXISTS` is idempotent in Postgres ≥ 9.5.

### 3.15 Integration with `NotificationService.publish` (the afterCommit hook)

`NotificationService.publish(NotificationEvent event)` from sub-spec 1 already registers an afterCommit hook for the WS broadcast. Adding the SL IM dispatcher hook is a one-line change — adjacent to the existing one:

```java
// NotificationService.publish — extended
@Transactional(propagation = Propagation.MANDATORY)
public UpsertResult publish(NotificationEvent event) {
    UpsertResult result = dao.upsert(/* ... */);

    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
            wsBroadcaster.broadcastUpsert(event.userId(), result, NotificationDto.from(/* ... */));
        }
    });

    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
            slImChannelDispatcher.maybeQueue(event);    // NEW
        }
    });

    return result;
}
```

`slImChannelDispatcher` is constructor-injected. It's a `@Component`; the existing `@RequiredArgsConstructor` Lombok annotation picks it up.

### 3.16 Integration with `listingCancelledBySellerFanout` (sibling call inside REQUIRES_NEW)

Per §2.2, the fan-out path doesn't go through `publish()` and therefore doesn't fire the afterCommit hook from §3.15. The dispatcher's fan-out variant `maybeQueueForFanout` is added as a sibling call inside the per-recipient REQUIRES_NEW lambda, between the in-app DAO upsert and the WS broadcast. See the code in §2.2.

This is the only existing fan-out method in the codebase. If a future fan-out is added (the FOOTGUNS guidance from sub-spec 1 names a `Fanout` suffix convention), the same sibling-call pattern applies.

---

## 4. Frontend design

### 4.1 `/settings` route tree

```
frontend/src/app/settings/
├── layout.tsx          // <h1>Settings</h1> + body slot; ready to grow a sidebar
├── page.tsx            // server-component redirect → /settings/notifications
└── notifications/
    ├── page.tsx        // <RequireAuth><NotificationPreferencesPage /></RequireAuth>
    └── page.test.tsx
```

`/settings/page.tsx`:

```tsx
import { redirect } from "next/navigation";
export default function SettingsIndexPage() {
  redirect("/settings/notifications");
}
```

When a second settings page lands, this index becomes a real list page; the `/settings/notifications` URL stays stable. `/settings/layout.tsx` carries an `<h1>Settings</h1>` and a root container; sidebar navigation is added in the sub-spec that introduces a second settings page, not now.

### 4.2 `NotificationPreferencesPage` layout

```
frontend/src/components/notifications/preferences/
├── NotificationPreferencesPage.tsx
├── ChannelInfoBanner.tsx
├── MasterMuteRow.tsx
├── GroupToggleRow.tsx
├── *.test.tsx
```

`NotificationPreferencesPage.tsx` composes:

1. `ChannelInfoBanner` — the disclosure callout.
2. `MasterMuteRow` — the channel-master-mute toggle, header-styled.
3. A vertical list of `GroupToggleRow` for each user-mutable group (in order: bidding, auction_result, escrow, listing_status, reviews).

Banner copy:

> *In-app and system notifications always deliver. Settings below control SL IM for the rest. SL natively forwards in-game IMs to your registered email when you're offline.*

Master mute row:

```
┌─────────────────────────────────────────────────────────┐
│  Mute all SL IM notifications                       ⚪──● │
│  Master switch — overrides everything below.            │
└─────────────────────────────────────────────────────────┘
```

Group toggle row (one per visible group):

```
┌─────────────────────────────────────────────────────────┐
│  Bidding                                            ●──⚪ │
│  Outbid, proxy exhausted                                │
└─────────────────────────────────────────────────────────┘
```

Per-group category subtext (rendered under the group label, smaller text, on-surface-variant color):

| Group | Subtext |
| --- | --- |
| Bidding | Outbid, proxy exhausted |
| Auction Result | Won, lost, ended (sold/reserve/no-bids/buy-now) |
| Escrow | Funded, transfer confirmed, payout, expired, disputed, frozen, payout stalled |
| Listings | Verified, suspended, review required, cancelled by seller |
| Reviews | New review received |

### 4.3 `GroupToggleRow` and `MasterMuteRow` components — disabled-state preservation

The most subtle invariant in the entire frontend: when `notifySlImMuted` is `true`, the per-group toggles render with `disabled + opacity-50 + cursor-not-allowed`, but their **checked state reflects the underlying `notifySlIm[group]` value unchanged**. The mute toggle is the only control whose state changes during muted mode; per-group writes are gated client-side (the click handler is a no-op when muted). Toggling mute back to off restores the visible/active state with no API roundtrip — the values were always there.

Implementation contract for the implementer:

- `GroupToggleRow` receives `{ group, label, subtext, value, mutedDisabled, onChange }`.
- When `mutedDisabled` is `true`: set `disabled` on the underlying `<button role="switch">`, apply the disabled visual classes, and short-circuit the click handler (no state change, no `onChange` fired). The `aria-checked` attribute still reflects `value`.
- Underlying React state (in the parent page) holds `value` and is **never mutated** in response to a master-mute change. The mute toggle only flips its own state.
- The PUT request fires only when an actual visible toggle changes (master mute or, when mute is off, a group toggle). No "save" button — it's an immediate-write UI.

`MasterMuteRow` is the same atom but with no `subtext`, header-style typography, and no disabled state of its own.

### 4.4 Hooks

```
frontend/src/hooks/notifications/
├── useNotificationPreferences.ts
├── useUpdateNotificationPreferences.ts
└── *.test.tsx
```

```ts
// useNotificationPreferences.ts
export function useNotificationPreferences() {
  return useQuery({
    queryKey: notificationKeys.preferences(),
    queryFn: () => getNotificationPreferences(),
    staleTime: 60_000,
  });
}
```

```ts
// useUpdateNotificationPreferences.ts
export function useUpdateNotificationPreferences() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (payload: PreferencesDto) => putNotificationPreferences(payload),
    onMutate: async (payload) => {
      await qc.cancelQueries({ queryKey: notificationKeys.preferences() });
      const prev = qc.getQueryData<PreferencesDto>(notificationKeys.preferences());
      qc.setQueryData(notificationKeys.preferences(), payload);
      return { prev };
    },
    onError: (_err, _vars, ctx) => {
      if (ctx?.prev) qc.setQueryData(notificationKeys.preferences(), ctx.prev);
      toast.push("error", "Couldn't save preferences");
    },
    onSettled: () => {
      qc.invalidateQueries({ queryKey: notificationKeys.preferences() });
      // Also invalidate /me — the User entity in the cached current-user
      // response includes the same JSONB columns.
      qc.invalidateQueries({ queryKey: ["currentUser"] });
    },
  });
}
```

`notificationKeys.preferences()` is a new entry in the query-key factory file from sub-spec 1's Task 6.

### 4.5 REST client

```ts
// frontend/src/lib/notifications/preferencesApi.ts
export interface PreferencesDto {
  slImMuted: boolean;
  slIm: {
    bidding: boolean;
    auction_result: boolean;
    escrow: boolean;
    listing_status: boolean;
    reviews: boolean;
  };
}

export async function getNotificationPreferences(): Promise<PreferencesDto> {
  return api.get<PreferencesDto>("/api/v1/users/me/notification-preferences");
}

export async function putNotificationPreferences(body: PreferencesDto): Promise<PreferencesDto> {
  return api.put<PreferencesDto>("/api/v1/users/me/notification-preferences", body);
}
```

### 4.6 Settings entry points

- **Bell dropdown footer.** The existing footer has a single "View all notifications" link. Add a small gear icon on the right side of the same row, linking to `/settings/notifications`. Two visual elements in one footer row, separated by the natural gap.
- **`/notifications` feed page header.** Add a gear icon top-right of the page title, aligned with the existing "Mark all read" action. Same icon, same destination.

Both icons reuse `Settings` (or `SlidersHorizontal`) from `@/components/ui/icons`. If neither is in the barrel, add it (one-line change to `icons.ts`).

### 4.7 MSW handlers (test fixtures)

`frontend/src/test/msw/handlers.ts` from sub-spec 1's Task 6 grows two new handlers:

```ts
http.get("/api/v1/users/me/notification-preferences", () => {
  return HttpResponse.json(currentPreferences);
}),
http.put("/api/v1/users/me/notification-preferences", async ({ request }) => {
  const body = await request.json() as PreferencesDto;
  // Validate closed shape; 400 on violation.
  const validKeys = new Set(["bidding", "auction_result", "escrow", "listing_status", "reviews"]);
  if (!body.slIm || !setEqual(new Set(Object.keys(body.slIm)), validKeys)) {
    return new HttpResponse(null, { status: 400 });
  }
  currentPreferences = body;
  return HttpResponse.json(currentPreferences);
}),
```

Plus a `seedPreferences(...)` and `resetPreferences()` test helper in the same module.

---

## 5. LSL script: `lsl-scripts/sl-im-dispatcher/`

### 5.1 Directory structure

```
lsl-scripts/                                   ← new top-level directory
├── README.md                                  ← index of all scripts; updated on add/remove only
└── sl-im-dispatcher/
    ├── README.md                              ← deployment + ops + troubleshooting for THIS script
    ├── dispatcher.lsl                         ← the script
    └── config.notecard.example                ← notecard template, committed with placeholders
```

### 5.2 Top-level `lsl-scripts/README.md`

Short index:

- Purpose of the directory (in-world LSL code consumed by SLPA's backend).
- Contributor rule (verbatim, load-bearing): *"Each new script gets its own subdirectory with its own README. Updates to any script's behavior, deployment, or configuration must be reflected in that script's README in the same commit. The top-level index updates only on add/remove/rename."*
- One-line summary per script with a relative link to its README.

Initial entry:
- `sl-im-dispatcher/` — Polls SLPA backend for pending notification IMs and delivers via `llInstantMessage`. SLPA-team-deployed; one instance per environment.

### 5.3 `sl-im-dispatcher/dispatcher.lsl` architecture

Single-state design with two time scales tracked by counters. The state machine reuses the `default` state and the `timer` event for both polling cadence (60 s) and per-IM throttle (2 s), switching cadences via `llSetTimerEvent` calls in the event handlers:

```lsl
// Configuration loaded at state_entry from config.notecard
string POLL_URL;
string CONFIRM_URL_BASE;
string SHARED_SECRET;
integer DEBUG_OWNER_SAY = TRUE;

// Polling cadence (slow) and per-IM throttle (fast)
float POLL_INTERVAL = 60.0;
float IM_INTERVAL = 2.0;

// Batch state during delivery
list batchIds = [];
list batchUuids = [];
list batchTexts = [];
integer batchIndex = 0;
integer deliveringBatch = FALSE;

// HTTP request tracking
key pollRequestId = NULL_KEY;

default {
    state_entry() {
        // Load config from notecard (synchronously via dataserver in real script)
        // ... read POLL_URL, CONFIRM_URL_BASE, SHARED_SECRET, DEBUG_OWNER_SAY ...
        if (DEBUG_OWNER_SAY) llOwnerSay("SL IM dispatcher starting (poll=" + POLL_URL + ")");
        llSetTimerEvent(POLL_INTERVAL);
    }

    timer() {
        if (deliveringBatch) {
            // Per-IM tick: send the next message in the batch.
            if (batchIndex >= llGetListLength(batchIds)) {
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
            // stay on IM_INTERVAL timer
        } else {
            // Poll cycle.
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
            // Parse JSON: { "messages": [{id, avatarUuid, messageText}, ...] }
            // (LSL JSON parsing details elided here; see actual script)
            integer count = parseAndStoreBatch(body);
            if (count > 0) {
                if (DEBUG_OWNER_SAY) llOwnerSay("SL IM batch=" + (string)count);
                deliveringBatch = TRUE;
                batchIndex = 0;
                // Switch to fast cadence and immediately fire first IM.
                llSetTimerEvent(IM_INTERVAL);
                // Manually fire the first one without waiting for the tick.
                string id = llList2String(batchIds, 0);
                string uuid = llList2String(batchUuids, 0);
                string text = llList2String(batchTexts, 0);
                llInstantMessage((key)uuid, text);
                llHTTPRequest(CONFIRM_URL_BASE + id + "/delivered", [...], "");
                batchIndex = 1;
            } else if (DEBUG_OWNER_SAY) {
                llOwnerSay("SL IM poll: 0 messages");
            }
        }
        // Confirmation responses ignored unless non-2xx (then log)
        else if (status >= 400) {
            llOwnerSay("SL IM confirm failed: " + (string)status);
        }
    }
}
```

The actual script will be longer (notecard-reading via `llGetNotecardLine` is an async dataserver pattern, JSON parsing uses `llJsonGetValue`, and there's housekeeping for state reset on `on_rez` / script reset). The above is the architectural skeleton; the implementation lands in `lsl-scripts/sl-im-dispatcher/dispatcher.lsl` with full bodies.

### 5.4 `config.notecard.example`

```
POLL_URL=https://slpa.example.com/api/v1/internal/sl-im/pending
CONFIRM_URL_BASE=https://slpa.example.com/api/v1/internal/sl-im/
SHARED_SECRET=<obtain from server config: slpa.notifications.sl-im.dispatcher.shared-secret>
DEBUG_OWNER_SAY=true
```

Committed verbatim. Operators copy to `config` (rename, no extension), edit values, drop into the same prim as the script. Notecard is read once at `state_entry`; rotating the secret means editing the notecard and resetting the script.

### 5.5 Per-script `sl-im-dispatcher/README.md`

Sections (load-bearing content for each):

- **Purpose.** In-world component of the SL IM channel; polls SLPA backend for pending IMs and delivers via `llInstantMessage`. SLPA-team-deployed (one per environment); not user-deployed.

- **Architecture summary.** 60 s polling cadence, batches of up to 10, per-IM 2 s throttle (matching SL's `llInstantMessage` floor), confirmation callback per delivered IM. Single state machine.

- **Deployment.** Steps: rez a generic prim somewhere with reliable land permissions (group land or owner-controlled), drop `dispatcher.lsl` into the prim, drop a copy of `config.notecard.example` renamed to `config` and edited with real values, reset script. Ownership and modify permissions should be SLPA-team-only — the shared secret is in the notecard.

- **Configuration.** Notecard format (key=value, one per line, no quotes). Where to obtain the shared secret (server's `slpa.notifications.sl-im.dispatcher.shared-secret` configuration property; check the deployment's secrets store). How to rotate (edit notecard, reset script — running connections aren't affected because each poll is a fresh request).

- **Operations.** What owner-say messages to expect (poll cadence pings, batch sizes, error codes). How to verify health from chat: `llOwnerSay`-tagged messages every 60 s in steady state. Reset procedure if the script wedges (right-click → Edit → Reset script).

- **Troubleshooting.** Common failure modes:
  - `SL IM poll failed: 401` → secret mismatch; check notecard, check server config.
  - `SL IM poll failed: 500` → backend issue; check server logs.
  - `SL IM poll failed: 0` (no HTTP response) → land permissions blocking outbound HTTP, or the prim is on no-script land; relocate.
  - No messages ever, no errors → backend has nothing pending; verify by manually triggering a notification for a user with a linked avatar.
  - Long silences in owner-say → script may have wedged (e.g., the notecard read failed); reset the script.

- **Limits.** Three load-bearing notes:
  - **Hard byte limit on IM text.** `llInstantMessage` truncates at 1024 BYTES (not characters). The backend's `SlImMessageBuilder` enforces this; the script trusts the messages it receives. Operators should never modify `messageText` in the script.
  - **No `/failed` caller in this script.** `llInstantMessage` is fire-and-forget — no return value, no error, no way to distinguish "delivered" from "UUID doesn't exist." The script unconditionally calls `/{id}/delivered` after every IM. So FAILED rows in `sl_im_message` only ever come from manual operator intervention or a future script revision that adds UUID pre-validation (e.g., `llRequestAgentData` against `DATA_ONLINE` before sending). Worth knowing: if a support engineer runs `SELECT status, count(*) FROM sl_im_message GROUP BY status` and sees zero FAILED rows, that's correct behavior, not a missing pipeline.
  - **Batch size 10 with 2 s spacing fits the 20 s SL HTTP window.** Increasing batch size beyond 10 risks the script's confirmation requests racing the next poll cycle's request limits. Don't increase without verifying the math.

- **Security.** The shared secret is a deployment artifact; treat it like a database password. Notecard contents are visible to anyone with object-edit rights, so the dispatcher prim's owner/group should be tightly scoped.

---

## 6. Data flow scenarios

Five load-bearing scenarios; most edge cases live in these.

### 6.1 Single-recipient with coalesce, then redelivery

User offline, gets outbid 5 times in 60 s.

```
Event 1 (t=0):  publish() → in-app row #100 (PENDING)
                            afterCommit → dispatcher.maybeQueue
                            sl_im_message #500 INSERT (PENDING, key=outbid:42:7)
Event 2 (t=12): publish() → in-app row #100 UPDATE (coalesced via WHERE read=false)
                            afterCommit → dispatcher.maybeQueue
                            sl_im_message #500 UPDATE (coalesced via WHERE status=PENDING)
                            updated_at bumped, message_text replaced with latest
... (3 more events, all coalesce into the same row)

t=60:  terminal polls /pending?limit=10 → returns [{id=500, text=<latest>}]
       llInstantMessage → POST /500/delivered → status=DELIVERED
       Single IM sent for 5 events.

Event 6 (t=120): publish() → in-app upsert
                              afterCommit → dispatcher.maybeQueue
                              partial unique index doesn't match (#500 is now DELIVERED)
                              sl_im_message #501 INSERT (PENDING, key=outbid:42:7)
                              Fresh row; terminal picks up on next poll.
```

The ON CONFLICT predicate `WHERE status = 'PENDING'` is what makes "fresh row after delivery" automatic. No service-layer branching.

### 6.2 Fan-out cancellation

Seller cancels listing with 3 active bidders.

```
CancellationService.cancel() commits → afterCommit registered earlier fires:

For bidder A (REQUIRES_NEW tx):
  notification_dao.upsert (in-app row)
  sl_im_dispatcher.maybeQueueForFanout (sibling — atomic with in-app)
   ↓ commit ↓
  ws_broadcaster.broadcastUpsert (post-commit, in-memory)

For bidder B (REQUIRES_NEW tx):  ← independent; A's failure can't affect this
  ... same three steps ...

For bidder C (REQUIRES_NEW tx):
  ... same three steps ...
```

If the FK lookup for a stale bidder ID throws, the per-recipient try-catch catches and continues. Real bidders all get their rows; the cancellation transaction itself is long-committed.

### 6.3 SYSTEM bypass + no-avatar interaction

Admin posts a SYSTEM_ANNOUNCEMENT to all users (Epic 10 trigger; in this sub-spec, exercised only in tests).

```
For each user:
  publish() → in-app row created
              afterCommit → dispatcher.maybeQueue
              gate.decide():
                user.slAvatarUuid == null   → SKIP_NO_AVATAR    (in-app only)
                user.slAvatarUuid present   → QUEUE_BYPASS_PREFS (IM + in-app)
              (mute and group prefs not consulted on QUEUE_BYPASS_PREFS path)
```

Even SYSTEM cannot deliver without an avatar UUID. The decision enum (`SKIP_NO_AVATAR` vs `QUEUE_BYPASS_PREFS`) is logged at DEBUG so post-incident triage can answer "did this user get the system announcement?" without DB archaeology.

### 6.4 Preference gate flow with master mute interaction

User has muted everything, but receives a SYSTEM_ANNOUNCEMENT alongside an OUTBID.

```
OUTBID  → group=BIDDING → muted check first → SKIP_MUTED → no IM, in-app only
SYSTEM  → bypass mute → no-avatar check → QUEUE → IM sent
```

Reverse case — user toggles `notifySlIm[bidding]` off via `/settings/notifications`, then 5 minutes later gets outbid:

```
PUT /me/notification-preferences → User.notifySlIm[bidding] = false (committed)

Event: OUTBID for that user
publish() → in-app row created
            afterCommit → dispatcher.maybeQueue
            User loaded fresh inside REQUIRES_NEW (sees committed update)
            notifySlIm[bidding] == false → SKIP_GROUP_DISABLED
```

Critical detail: **the User entity load happens inside the dispatcher's REQUIRES_NEW transaction**, not at the originating call site. So the dispatcher always reads the latest committed prefs — no stale-cache window. The cost is one User SELECT per dispatched event; a cache here would be wrong (we want read-your-writes after a preferences PUT) and the join is cheap.

### 6.5 Expiry canary

Terminal goes dark Friday at 18:00 SLT. By Sunday 03:30 SLT it's been silent for 57 hours.

```
03:30 SLT — SlImCleanupJob runs:
  Stage 1: capture top users by pending count (BEFORE the UPDATE)
           UPDATE sl_im_message SET status='EXPIRED'
           WHERE status='PENDING' AND created_at < (now - 48h)
           Returns: 237 rows
  Stage 2: chunked DELETE
           WHERE status IN ('DELIVERED','EXPIRED','FAILED')
             AND updated_at < (now - 30d)
           Returns: small number (matches typical daily delivery aged 30 days back)

  log.info("SL IM cleanup sweep: expired=237 deleted=X " +
           "expiry_cutoff=2026-04-26T08:30Z retention_cutoff=2026-03-27T08:30Z " +
           "top_users=[42:89, 71:34, 103:21, ...]")
```

The top-N user breakdown lets operators distinguish "one user is mass-failing" (account closed, avatar UUID stale) from "everyone's failing" (terminal dark) at a glance. The first time `expired > 0` on a Sunday morning, somebody investigates.

### 6.6 What explicitly does not happen — the absences are spec, too

- **Preference changes don't retroactively cancel queued IMs.** A user toggling reviews off doesn't expire their already-pending review IM. They opted out for *future* events; the in-flight one delivers under the prior intent. Cheap and honest.
- **EXPIRED rows are never retried.** If you want a retry primitive, that's a separate enum value and a different job; not in scope.
- **WS broadcast failures are independent of IM queue.** Broker down → in-app row durable, IM row durable, only the live WS push lost. Frontend reconciles on reconnect (sub-spec 1's existing pattern).
- **`/failed` has no caller in the LSL script** (per §5.5). FAILED rows in production come from operator/manual intervention only.
- **Quiet hours don't exist.** The columns are dormant; no scheduling, no held-until logic.
- **EXPIRED rows are observable.** Stage 2 deletes them after 30 days, not stage 1, so the `GROUP BY status` triage query reflects a rolling window of pipeline health, not just steady-state.

---

## 7. API surface

### 7.1 User-facing — preferences

Auth: bearer JWT; `@PreAuthorize("isAuthenticated()")`.

```
GET /api/v1/users/me/notification-preferences
200 {
  "slImMuted": false,
  "slIm": {
    "bidding": true, "auction_result": true, "escrow": true,
    "listing_status": true, "reviews": false
  }
}
```

```
PUT /api/v1/users/me/notification-preferences
Content-Type: application/json
{
  "slImMuted": false,
  "slIm": {
    "bidding": true, "auction_result": true, "escrow": true,
    "listing_status": true, "reviews": true
  }
}

200 — same shape as GET, returns the new state for optimistic-mutation reconcile
400 — body's slIm map keys are not exactly { bidding, auction_result, escrow, listing_status, reviews }
400 — body contains non-boolean values (Jackson rejects on deserialization)
401 — unauthenticated
```

The closed-shape validation on the `slIm` map is intentional. Sending a key outside the visible-groups set (e.g., `system`, `realty_group`, `marketing`) returns 400 — the server is the source of truth for what's user-mutable, and frontend bugs that send unexpected keys should surface as errors. Server-side, the merge into `User.notifySlIm` JSONB preserves the non-visible keys at their existing values.

### 7.2 Internal — dispatcher polling

Auth: shared secret via `Authorization: Bearer ${slpa.notifications.sl-im.dispatcher.shared-secret}`. NOT a JWT. Enforced by `SlImTerminalAuthFilter` registered before the JWT chain on `/api/v1/internal/sl-im/**`.

```
GET /api/v1/internal/sl-im/pending?limit=10
200 {
  "messages": [
    {
      "id": 12345,
      "avatarUuid": "00000000-0000-0000-0000-000000000000",
      "messageText": "[SLPA] You've been outbid on Hampton Hills\n\nCurrent bid is L$2,000.\n\nhttps://slpa.example.com/auction/42#bid-panel"
    }
  ]
}

400 — limit > slpa.notifications.sl-im.dispatcher.max-batch-limit (default 50)
400 — limit < 1
401 — Authorization header missing
401 — Authorization header doesn't match shared secret
```

```
POST /api/v1/internal/sl-im/{id}/delivered
204 — PENDING → DELIVERED (sets delivered_at = now()), or DELIVERED → 204 no-op (idempotent)
404 — message id doesn't exist
409 — current status is FAILED or EXPIRED (system intervention is sticky)
401 — auth mismatch
```

```
POST /api/v1/internal/sl-im/{id}/failed
204 — PENDING → FAILED, or FAILED → 204 no-op (idempotent)
404 — message id doesn't exist
409 — current status is DELIVERED or EXPIRED (sticky)
401 — auth mismatch
```

State machine for `/delivered`:
```
PENDING   → DELIVERED (204, set delivered_at)
DELIVERED → 204 no-op
FAILED    → 409
EXPIRED   → 409
missing   → 404
```

State machine for `/failed`:
```
PENDING   → FAILED (204)
FAILED    → 204 no-op
DELIVERED → 409
EXPIRED   → 409
missing   → 404
```

Both implemented as a single conditional UPDATE returning affected-rows count, plus a follow-up read on the no-op path to determine the response (idempotent vs conflict). One round trip on the happy path; two on idempotent/conflict paths.

### 7.3 Auth and security boundaries

- **Internal endpoints have NO session creation.** Stateless requests; the filter doesn't touch `SecurityContext`. CSRF is disabled on the internal path prefix (the path matcher excludes `/api/v1/internal/sl-im/**`).
- **Internal endpoints serve `application/problem+json` on errors.** No HTML error pages. Predictable for the LSL parser.
- **The shared secret is environment-scoped.** Different value per dev/staging/prod. Rotation is out-of-band: update the deployment's secrets store, edit the dispatcher's notecard, reset the script. There's no in-app key-rotation flow.
- **Constant-time secret comparison** (`MessageDigest.isEqual`) so the secret cannot be inferred via response timing.
- **The shared secret for SL IM dispatch is distinct from the existing escrow-terminal shared secret.** Different blast radius if leaked; different rotation cadence; different operators.

---

## 8. Error handling

The dispatch path's failure modes and their handlers:

| Failure | Where | Handler |
| --- | --- | --- |
| User not found in dispatcher | `doQueue` | Throws `IllegalStateException`; in single-recipient path the outer `try` logs WARN and swallows; in fan-out path propagates and rolls back that recipient's REQUIRES_NEW (sibling recipients unaffected). |
| Channel gate decides SKIP_* | `doQueue` | No-op; logs DEBUG with the decision enum. No row, no error. |
| `SlImMessageBuilder` budget exhausted (title alone exceeds 1024 bytes) | `doQueue` | Falls back to title-trimmed + deeplink, logs WARN. Row still queued. |
| DAO upsert throws (FK violation, unique-index race, transient) | `doQueue` | Single-recipient: outer try logs WARN, swallows. Fan-out: propagates, rolls back recipient's REQUIRES_NEW. |
| afterCommit hook throws in single-recipient path | `NotificationService.publish` | Spring logs the exception via `TransactionSynchronizationManager` default logger; doesn't affect parent. We rely on the outer try in `maybeQueue` to log first, but Spring's safety net catches anything we miss. |
| WS broadcast fails (broker down) | `NotificationService.publish` afterCommit | Existing sub-spec 1 behavior — caught and logged; in-app row durable, IM row durable. |
| Cleanup job fails mid-stage 1 | `SlImCleanupJob.run` | `@Scheduled` exception is logged; next day's run picks up where this left off (UPDATE is idempotent and re-runs find the same set of rows). |
| Cleanup job stage 2 fails partway through chunked DELETE | `SlImCleanupJob.run` | Logs the partial deletion, partial completion. Next day's run completes the rest. |
| Internal endpoint receives request without/with-wrong Authorization header | `SlImTerminalAuthFilter` | 401 with `application/problem+json` body. |
| Internal endpoint receives `?limit > maxBatchLimit` | `SlImInternalController` | 400 via `IllegalArgumentException` → `GlobalExceptionHandler` from sub-spec 1 Task 2. |
| `/delivered` or `/failed` on non-existent id | controller | 404. |
| `/delivered` on a row currently FAILED or EXPIRED | controller | 409 — operator/system intervention is sticky. |
| Idempotent `/delivered` on already-DELIVERED row | controller | 204 no-op; `attempts` not incremented (because the conditional UPDATE's WHERE clause excludes DELIVERED). |
| Preferences PUT with extra/missing keys | `NotificationPreferencesController.put` | `BadRequestException` → 400. |
| Preferences PUT with non-boolean value | Jackson | `HttpMessageNotReadableException` → 400. |

The expiry canary itself is the operational error-handling story for "dispatcher dark" — see §6.5.

---

## 9. Testing strategy

Four tiers, scaled to where the bugs live, plus an end-to-end test as a contract canary.

### 9.1 Tier 1 — Pure unit (fast, exhaustive)

- **`SlImChannelGateTest`** — parameterized matrix over `(NotificationCategory × { hasAvatar, noAvatar } × { muted, unmuted } × { groupOn, groupOff })`. Asserts the exact `Decision` enum returned. This is the test that catches gate-logic regressions; everything else builds on it. ~200 cases via `@ParameterizedTest` with a method source.
- **`SlImMessageBuilderTest`** — covers the `[SLPA] {title}\n\n{body}\n\n{deeplink}` template, the 1024-byte ceiling truncation behavior (deeplink preserved, body ellipsized), and at least one test per category to confirm the assembled string fits. **Three mandatory cases for the byte-vs-char bug:**
  1. Multi-byte parcel name (e.g., `東京タワー Estates`) — assert deeplink intact, total bytes ≤ 1024.
  2. Emoji-bearing parcel name (e.g., `🌸 Sakura Plot`) — same assertions.
  3. Body so long it requires actual truncation — assert ellipsis appears, deeplink intact, total bytes ≤ 1024.
- **`SlImLinkResolverTest`** — one test per category × the data-blob keys it consumes. 22 tests; cheap and exhaustive. Confirms each category produces a well-formed URL with no missing path segments.

### 9.2 Tier 2 — DAO + repository (TestContainers Postgres)

- **`SlImMessageDaoTest`** — ON CONFLICT upsert behavior:
  - Insert when no PENDING row exists for `(user_id, coalesce_key)`.
  - Update when one exists (asserts `wasUpdate=true`, `message_text` replaced, `updated_at` bumped).
  - Fresh insert when the prior row is DELIVERED (asserts the partial index doesn't match).
  - Fresh insert when the prior row is EXPIRED.
  - Fresh insert when the prior row is FAILED.
  - NULL coalesce key never collides with itself (two inserts with `coalesce_key = NULL` produce two distinct rows).
- **`SlImCoalesceIndexInitializerTest`** — `pg_indexes` reflects the partial unique index after startup; idempotent re-emit on second run (no exception).
- **`SlImMessageRepositoryTest`** — polling query returns oldest-PENDING-first; `limit` honored; `FOR UPDATE SKIP LOCKED` skips rows held by a concurrent tx (one assertion with a manual second JDBC connection holding a row).

### 9.3 Tier 3 — Vertical-slice integration (`@SpringBootTest` per gate-path cluster)

Five files, NOT 22 (one per category). Coverage strategy is "one test per gate decision + dispatch path":

- **`OutbidImIntegrationTest`** — drives `BidService.placeBid()`'s outbid path; asserts:
  - IM row created with correct text and avatar UUID.
  - Coalesce on storm produces single row (5 rapid outbids = 1 PENDING row, latest text).
  - Gate-disabled (`notifySlIm[bidding] = false`) suppresses IM but keeps in-app row.
  - Master mute suppresses IM but keeps in-app row.
- **`EscrowImIntegrationTest`** — one test per recipient pattern:
  - `ESCROW_FUNDED` (seller only — assert no IM for buyer).
  - `ESCROW_TRANSFER_CONFIRMED` (both parties — assert 2 IM rows, one per party).
  - `ESCROW_DISPUTED` (both parties).
  - `ESCROW_PAYOUT` (seller only).
  - The "both parties" cases also exercise the gate independently per recipient (e.g., seller muted, winner not).
- **`CancellationFanoutImIntegrationTest`** — drives `listingCancelledBySellerFanout`; asserts:
  - One IM row per active bidder (3 bidders → 3 rows).
  - One bidder with `notifySlIm[listing_status] = false` gets in-app but no IM.
  - Stale-bidder-id rolls back THAT recipient's REQUIRES_NEW only — others land cleanly.
  - Auction cancellation commits regardless of any IM-queue failure.
- **`SystemBypassImIntegrationTest`** — synthetic SYSTEM_ANNOUNCEMENT event constructed inline (no publisher method exists yet); asserts:
  - Muted user with avatar gets IM (mute bypassed).
  - Muted user without avatar gets no IM (no-avatar floor wins).
  - Group toggle off has no effect (prefs bypassed entirely).
- **`NoAvatarSkipImIntegrationTest`** — user with `slAvatarUuid = null` gets in-app rows for several categories, zero IM rows queued.

### 9.4 Tier 4 — Internal endpoint contract (`@SpringBootTest` + MockMvc)

- **`SlImInternalControllerTest`** — the state machine in §7 explicitly verified:
  - `/delivered`: PENDING→DELIVERED 204, DELIVERED idempotent 204, FAILED→/delivered returns 409, EXPIRED→/delivered 409, missing 404.
  - `/failed`: same matrix mirrored.
  - `?limit` cap (>50 → 400, <1 → 400).
  - Auth: missing header → 401, wrong header → 401, valid header → 200 / 204 as appropriate.
  - The `messages` payload shape (id is number, avatarUuid is string UUID, messageText is string).
  - `delivered_at` is set on PENDING→DELIVERED, NOT re-stamped on idempotent DELIVERED→204.
- **`NotificationPreferencesControllerTest`** —
  - GET returns the current closed-shape map.
  - PUT happy path persists and returns the new state.
  - PUT with `system` key in `slIm` → 400.
  - PUT with `realty_group` or `marketing` key → 400.
  - PUT missing a required key → 400.
  - PUT with non-boolean value (e.g., string `"true"`) → 400.
  - Unauthenticated → 401.
  - Cross-user: User A's session can't read or write User B's prefs (verified by JWT mismatch test).

### 9.5 Cleanup job tests

- **`SlImCleanupJobTest`** — three rows seeded at varied ages and statuses:
  - 49 h-old PENDING → expected EXPIRED.
  - 47 h-old PENDING → expected unchanged.
  - 31 d-old DELIVERED → expected DELETED.
  - 31 d-old FAILED → expected DELETED.
  - 31 d-old EXPIRED → expected DELETED.
  - 29 d-old DELIVERED → expected unchanged.

  Captures log output via `LogCaptor` (existing pattern in the codebase) and asserts the INFO line:
  - Contains `expired=N` with the correct count.
  - Contains `deleted=N` with the correct count.
  - Contains `expiry_cutoff=` and `retention_cutoff=` ISO timestamps.
  - Contains `top_users=[user_id:count, ...]` with at least one entry when there were any expired rows.

  The log assertion is what guarantees the canary actually fires — without it, regressions could silently turn the operator-grade signal into a useless "swept N rows" line.

### 9.6 Frontend tests

- **`useNotificationPreferences.test.tsx`** — seeds from `useCurrentUser()`, refetches via the dedicated endpoint after mutation success, returns the closed-shape DTO.
- **`useUpdateNotificationPreferences.test.tsx`** — optimistic flip on click; on 4xx, revert + toast `"Couldn't save preferences"`; on 5xx, same revert + toast.
- **`NotificationPreferencesPage.test.tsx`** —
  - Renders banner with the disclosure copy.
  - Renders 5 group toggles (no SYSTEM, no REALTY_GROUP, no MARKETING).
  - Reviews defaults to OFF for a fresh user.
  - Master mute ON greys group toggles, preserves their underlying checked state, toggle mute off → states return without API call.
  - Clicking a group toggle when not muted fires the PUT with the new shape.
  - Clicking a group toggle when muted is a no-op (no PUT).
- **`MasterMuteRow.test.tsx`** — atom-level: renders, fires onChange, accepts `value` prop, no internal state.
- **`GroupToggleRow.test.tsx`** — atom-level: the disabled-state-preservation invariant. Given `value=true, mutedDisabled=true`: aria-checked is `true`, `disabled` is set, click is a no-op, `onChange` not fired.
- **Bell dropdown footer.test.tsx update** — settings entry-point assertion: gear icon rendered, link resolves to `/settings/notifications`.
- **`/notifications` page header.test.tsx update** — settings gear icon rendered, link resolves to `/settings/notifications`.

### 9.7 End-to-end (one test, expensive but valuable)

- **`SlImEndToEndIntegrationTest`** — drives an OUTBID through `BidService.placeBid()`, asserts:
  1. The IM row exists in `sl_im_message` with the expected coalesce key, avatar UUID, and assembled `messageText`.
  2. `GET /api/v1/internal/sl-im/pending?limit=10` with the configured shared secret returns the row in its `messages` array.
  3. `POST /api/v1/internal/sl-im/{id}/delivered` returns 204 and transitions the row to DELIVERED with `delivered_at` set.
  4. A second `POST /{id}/delivered` returns 204 (idempotent), `delivered_at` unchanged.

  One test, but it's the canary for "the contract from event → terminal works." Slow (full Spring context, real Postgres), so kept singular.

### 9.8 LSL

No unit-test framework. The PR's manual test plan covers it (§13.4).

### 9.9 What's intentionally not tested in this sub-spec

- **WS routing.** Covered exhaustively by sub-spec 1's `UserQueueRoutingTest`. The new dispatcher hook doesn't change WS routing.
- **In-app row creation correctness.** Covered by sub-spec 1's per-category integration tests. The new dispatcher hook doesn't change in-app row creation.
- **LSL script behavior** beyond what the manual test plan exercises in beta sim.

---

## 10. Sub-spec boundaries

After this sub-spec ships, **Epic 09 is functionally complete** for the launch the project is targeting, except for items below.

### 10.1 What remains pending in Epic 09 (deferred forward)

- **Email channel.** Removed from the roadmap. Re-add only on explicit user request. Reasoning: SL natively forwards offline IMs to the user's registered email, so the SL IM channel covers the email use-case at zero additional infrastructure cost.
- **`SYSTEM_ANNOUNCEMENT` publisher and admin trigger.** Gate logic supports it; no production code path emits one in this sub-spec. Trigger lives in Epic 10 (Admin & Moderation).
- **`REVIEW_RESPONSE_WINDOW_CLOSING` notification + scheduler.** Stays in Epic 10 with the response-window scheduler (shared scheduling DNA).
- **`ESCROW_TRANSFER_REMINDER` scheduler.** Publisher method, category, data builder, and frontend renderer all exist from sub-spec 1; no `@Scheduled` job calls it. Retargets to Epic 10 alongside the response-window scheduler.

### 10.2 What lives in this sub-spec but is exercised only in tests

- **Channel gate's `QUEUE_BYPASS_PREFS` decision** is unit-tested with synthetic SYSTEM_ANNOUNCEMENT events. There's no production trigger.

### 10.3 What this sub-spec doesn't change

- The User entity's `notifyEmail` JSONB column stays in the schema unchanged.
- The User entity's `slImQuietStart` and `slImQuietEnd` columns stay in the schema unchanged.
- The `NotificationCategory` enum's 22 values are unchanged.
- The in-app channel's lifecycle (publish → afterCommit WS broadcast) is unchanged.
- The cancellation fan-out's per-recipient REQUIRES_NEW shape is unchanged in structure; a new sibling call is added inside the lambda.

---

## 11. Deferred ledger updates

### 11.1 Close (this sub-spec ships these)

- `SL IM channel for notifications`
- `Notification preferences UI`

### 11.2 Modify

- `Email channel for notifications`:
  > **Status:** removed from roadmap. Re-add only on explicit user request. Reasoning: SL natively forwards offline IMs to the user's registered email, so the SL IM channel from Epic 09 sub-spec 3 covers the email use case at zero additional infrastructure cost. Re-instating would mean: per-category templates (HTML + plain text), signed-token unsubscribe, debounce/dedupe matching the coalesce pattern, email-change flow (originally pending from Epic 02 sub-spec 2b).
- `ESCROW_TRANSFER_REMINDER scheduler`:
  > **Retargeted from Epic 09 sub-spec 2/3 to Epic 10.** Pairs naturally with the REVIEW_RESPONSE_WINDOW_CLOSING scheduler — shared design DNA (interval-bound + once-per-entity + admin-visible). New entity: `Escrow.reminderSentAt` column for once-per-escrow guarantee. New job: `EscrowReminderScheduler` (cron daily, query escrows with FUNDED status approaching transfer deadline, fire `publisher.escrowTransferReminder(...)`).

### 11.3 Add

- **Quiet hours UI for SL IM.**
  > Columns `slImQuietStart` and `slImQuietEnd` exist on User entity from Epic 02 sub-spec 2b; no UI consumes them. Will likely tie to a future timezone/account-settings sub-spec; no committed home yet. If unused for >12 months, drop the columns in a dedicated cleanup sub-spec.
- **HTTP-in push from backend to dispatcher for urgency.**
  > Current design polls every 60 s — fine for the events shipping today (worst case 60 s latency for outbid/won). If outbid latency becomes a UX concern, register the dispatcher's HTTP-in URL with the backend on startup and have the backend `llHTTPRequest` to it on high-priority categories to wake an early poll. Post-launch enhancement; needs the channel to have real traffic and a real complaint before the complexity earns its keep.
- **Dispatcher monitoring beyond the expiry canary.**
  > The expiry job's INFO log catches a dark dispatcher within 48 h. If sub-day signal becomes important, options include: a `last_polled_at` timestamp on a singleton `dispatcher_health` row written on each successful poll, with an alarm scheduler that pages on `now - last_polled_at > 5 min`. Out of scope here.

---

## 12. FOOTGUNS additions

Four new entries.

### 12.1 `llInstantMessage` truncates at 1024 BYTES, not characters

The natural Java `String.length()` measures UTF-16 code units; SL's `llInstantMessage` truncates the IM at 1024 **bytes** in UTF-8 encoding. Multi-byte UTF-8 characters (CJK, emoji, accented Latin) push the byte count above the char count. SL silently truncates from the end of the string — no error, no warning, no return value — and the deeplink in SLPA's IM template lives at the end of the assembled message. A 1023-character string with multi-byte content can occupy 1500+ bytes; the deeplink gets cleanly cut off.

`SlImMessageBuilder` measures `text.getBytes(StandardCharsets.UTF_8).length` and ellipsizes the body, never the prefix or deeplink. Three mandatory test cases (multi-byte parcel name, emoji parcel name, long-body forcing truncation) verify the deeplink survives. Adding a new component to the assembled message (say, a timestamp) requires updating the byte-budget accounting in `SlImMessageBuilder` — the budget is currently computed assuming exactly `PREFIX + title + SEPARATOR + body + SEPARATOR + deeplink`.

### 12.2 Single-recipient publish path is afterCommit-then-REQUIRES_NEW; fan-out path is in-the-REQUIRES_NEW

The two notification dispatch sites have different reliability postures and different transaction structures.

**Single-recipient path** (`NotificationService.publish`): in-app row commits first as part of the parent transaction, then `afterCommit` runs `slImChannelDispatcher.maybeQueue` which opens its own REQUIRES_NEW. If the IM-queue write fails, the in-app row already committed, the parent business event already committed, and the only loss is the IM. **In-app guaranteed, IM best-effort.**

**Fan-out path** (`NotificationPublisherImpl.listingCancelledBySellerFanout`): per-recipient REQUIRES_NEW lambda contains the in-app DAO upsert AND the IM queue write as siblings. If the IM-queue write fails, that recipient's in-app row also rolls back; the per-recipient try-catch isolates this from sibling recipients. **In-app + IM atomic per recipient; sibling recipients independent.**

Mixing these mental models produces either:
- "One bad bidder kills the cancellation" — if you put fan-out atomic with the parent transaction.
- "In-app row exists but IM never queued because the dispatcher hook failed silently" — if you inline the dispatcher into the single-recipient path's parent transaction.

Future contributors adding a new fan-out method must follow the existing pattern: per-recipient REQUIRES_NEW lambda containing all per-recipient writes as siblings, with a per-recipient try-catch wrapping the lambda. Name with a `Fanout` suffix (sub-spec 1's convention).

### 12.3 Adding a new terminal status to `sl_im_message` requires updating the cleanup predicate

`SlImCleanupJob` stage 2 deletes rows via `WHERE status IN ('DELIVERED', 'EXPIRED', 'FAILED') AND updated_at < retention_cutoff`. The IN-list enumerates terminal statuses. Adding a new one (say, a future `RETRY_SCHEDULED` for the deferred retry primitive) without updating the IN-list means those rows accumulate forever — defeating the very `SELECT status, count(*) FROM sl_im_message GROUP BY status` query the rolling 30-day window was supposed to keep meaningful. Add the new status to the predicate in the same commit that introduces the status.

### 12.4 LSL `llInstantMessage` is fire-and-forget; `/failed` has no LSL caller

The `sl-im-dispatcher` script unconditionally calls `POST /api/v1/internal/sl-im/{id}/delivered` after every `llInstantMessage` because LSL provides no delivery signal — `llInstantMessage` returns `void`, raises no event on failure, and produces no observable side effect when the recipient UUID is invalid or offline-unreachable.

This means FAILED rows in `sl_im_message` only appear via:
1. **Manual operator intervention** — direct SQL UPDATE in production (rare; usually for support clearing a stuck row).
2. **A future revision of `dispatcher.lsl`** that pre-validates avatar UUIDs (e.g., `llRequestAgentData` against `DATA_ONLINE` before sending, or a NULL_KEY guard).

If support runs `SELECT status, count(*) FROM sl_im_message GROUP BY status` and sees zero FAILED rows, that's correct behavior. Not a missing pipeline.

---

## 13. Documentation updates

### 13.1 `docs/initial-design/DESIGN.md` §11

Extend the existing "Notes (Epic 09 sub-spec 1)" subsection with a follow-on for sub-spec 3:

> **Notes (Epic 09 sub-spec 3):**
> - The SL IM channel reuses the in-app coalesce key namespace; rows in `sl_im_message` collapse during pendency (`WHERE status = 'PENDING'`), with the same partial-unique-index pattern.
> - `SYSTEM` group bypasses preferences and master-mute, but never bypasses the no-avatar floor.
> - `llInstantMessage` truncates at 1024 BYTES (UTF-8), not characters. The backend's `SlImMessageBuilder` enforces this with body ellipsis, never trimming the prefix or deeplink.
> - The polling/confirmation contract (`/api/v1/internal/sl-im/*`) uses a shared secret distinct from the existing escrow-terminal secret. State machines for `/delivered` and `/failed` are symmetric: PENDING → terminal-status, idempotent on the matching status, 409 on the other terminal statuses.
> - The system relies on SL's native offline-IM-to-email forwarding to cover the email use case. Users with active SL email forwarding receive SLPA notifications by email; users with disabled forwarding receive only in-world IMs when online.

### 13.2 `docs/implementation/DEFERRED_WORK.md`

Per §11 above.

### 13.3 `docs/implementation/FOOTGUNS.md`

Per §12 above. Append four new numbered entries (next sequential numbers from the file's tail).

### 13.4 `lsl-scripts/README.md` (new top-level)

Per §5.2.

### 13.5 `lsl-scripts/sl-im-dispatcher/README.md` (new per-script)

Per §5.5.

### 13.6 Root `CLAUDE.md` — addition under "Second Life Integration Notes"

> **In-world LSL code lives in `lsl-scripts/`.** Each script gets its own subdirectory with its own README covering deployment, configuration, operations, and limits. Updates to a script's behavior, deployment, or configuration must update that script's README in the same commit. The top-level `lsl-scripts/README.md` is an index — updates only on add/remove/rename.

### 13.7 Manual test plan (for the eventual PR)

- Backend (`./mvnw test -pl backend`) and frontend (`cd frontend && npm test -- --run && npm run lint`) suites green.
- Two browser sessions, two users; one with linked SL avatar, one without. Drive an OUTBID for each. Verify: linked user has a PENDING row in `sl_im_message`; unlinked user has none. Both users see in-app + WS push as before.
- Toggle preferences in the linked user's `/settings/notifications` (mute Bidding). Drive another OUTBID. Verify: in-app row created, no new IM row queued, gate decision logged at DEBUG.
- Toggle master mute on. Verify: per-group toggles render disabled but checked-states preserved. Toggle off. Verify: states return without API roundtrip.
- `curl -H "Authorization: Bearer ${SECRET}" GET /api/v1/internal/sl-im/pending?limit=10`. Verify the assembled `messageText` matches the expected template; verify a multi-byte parcel name (set up a test auction with `東京タワー` in the name) comes through with deeplink intact.
- `curl -H "Authorization: Bearer ${SECRET}" -X POST /api/v1/internal/sl-im/{id}/delivered`. Verify status transitions; second curl returns 204; manually mark a row FAILED via DB then curl `/delivered` — expect 409.
- Deploy `sl-im-dispatcher` in beta sim pointed at staging. Drop `dispatcher.lsl` and `config` notecard into a prim, reset script, watch owner-say. Drive a notification for a test user with an avatar. Verify the alt account receives the IM with correct text and a clickable deeplink. Watch owner-say messages for poll cadence.
- Stop the dispatcher object (delete or no-script land it). Wait 49 hours. Verify the cleanup job's INFO log includes a top_users entry naming the test account, and `expired > 0`.

---
