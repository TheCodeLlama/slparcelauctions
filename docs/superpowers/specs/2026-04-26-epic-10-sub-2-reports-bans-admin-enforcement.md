# Epic 10 Sub-spec 2 — Listing Reports + Bans + Admin Enforcement + Admin User Mgmt

> **Status:** Design
> **Epic:** 10 (Admin & Moderation)
> **Sub-spec:** 2 of 4
> **Branch hint:** `task/10-sub-2-reports-bans-admin-enforcement`
> **Read first:** [`docs/implementation/CONVENTIONS.md`](../../implementation/CONVENTIONS.md), [`docs/implementation/DEFERRED_WORK.md`](../../implementation/DEFERRED_WORK.md), [`docs/implementation/FOOTGUNS.md`](../../implementation/FOOTGUNS.md), and the sub-spec 1 spec for fraud-flag and admin-foundation context.

---

## 1. Goal

Sub-spec 2 ships the moderation loop: users report listings, admins triage from a queue, admins ban offenders, admins manage users. Builds on sub-spec 1's admin role + section shell + fraud-flag triage.

When this lands:
- Verified users can flag a listing with subject + reason + details.
- An admin sees the report queue (sorted by report count per listing), drills into a listing's reports, and takes action: warn the seller, suspend the listing, cancel the listing, or pivot to the seller's user-detail page to ban them.
- Admins can create bans by IP, SL avatar, or both. Bans block register, login, bid, listing creation, and listing cancellation. Cached in Redis with 5-minute TTL.
- Admins can search users, drill into a user's full history (listings, bids, cancellations, reports filed/against, fraud flags, moderation log), promote/demote roles, lift bans, and reset frivolous-reporter counters.
- All admin write actions are persisted in a new `admin_actions` audit table.

## 2. Scope

### In scope

- `ListingReport` entity + report POST endpoint + admin queue/detail/action endpoints
- `Ban` entity + Redis cache + ban-check enforcement at register/login/SL-verify/bid/listing-create/listing-cancel
- `AdminAction` audit table + read endpoint
- Admin user search + user-detail page (6 tabs + action panel + ban-create modal)
- `User.dismissedReportsCount` counter (frivolous tracking — counter only, no automatic privilege revocation)
- `CancellationLog.cancelledByAdminId` column + `CancellationService.cancelByAdmin` path that skips the penalty ladder
- `AdminAuctionService.reinstate` shared primitive — sub-spec 1's fraud-flag reinstate refactored to delegate to it; new standalone `POST /admin/auctions/{id}/reinstate` endpoint
- Two new notification categories: `LISTING_WARNED`, `LISTING_REMOVED_BY_ADMIN`
- Frontend pages: `/admin/reports`, `/admin/bans`, `/admin/users`, `/admin/users/{id}`, plus dashboard's 4-up needs-attention row, Report button + modal on `/auction/[id]`

### Out of scope (later sub-specs within Epic 10)

- Account deletion (`DELETE /me` 501) → sub-spec 4
- Admin "send notification to user" surface → indefinite
- Realtime ban broadcast / forced-logout WebSocket — tv-bump on next refresh is enough
- Frivolous-reporter automatic privilege revocation (counter only this sub-spec)
- 3-report threshold notification (`REPORT_THRESHOLD_REACHED`) — listing-grouped queue + dashboard counter cover the visibility need; deferred with re-add trigger ("if admins miss high-report listings")
- Bot pool health, escrow dispute resolution, terminal secret rotation → sub-spec 3
- Reminder schedulers, audit log viewer page → sub-spec 4

---

## 3. Architecture

```
Frontend
  /auction/[id]                    — Report button + modal (verified, not own listing)
  /admin                           — needs-attention row 4-up: fraud + reports + payments + disputes
  /admin/reports                   — listing-grouped queue + slide-over (4 actions + per-report dismiss)
  /admin/bans                      — Active / History tabs + Create-ban modal
  /admin/users                     — single-search box + paginated table
  /admin/users/{id}                — profile + 5 stats + 6 tabs + 280px right rail
  /admin/users/{id} → ban modal    — pre-filled from user; multi-ban stacking allowed

Backend (com.slparcelauctions.backend.admin)
  reports/                         — AdminReportController, AdminReportService, ListingReport entity
  ban/                             — AdminBanController, AdminBanService, BanCheckService, Ban entity
  users/                           — AdminUserController, AdminUserService (read), AdminRoleService (promote/demote)
  audit/                           — AdminAction entity, AdminActionRepository, AdminAuditController
  AdminAuctionService              — shared primitive: reinstate(auction, adminUserId, notes, fallbackSuspendedFrom)

Cross-cutting
  ListingReport.upsert() on duplicate (auction, reporter)
  Ban + Redis cache (5-min TTL, invalidated on create/lift)
  CancellationService.cancelByAdmin (no penalty ladder)
  SuspensionService.suspendByAdmin (admin reason, no fraud flag)
  Sub-spec 1 AdminFraudFlagService.reinstate refactored to delegate
```

The admin package gains four new sub-packages (`reports`, `ban`, `users`, `audit`) and a shared `AdminAuctionService` used by both sub-specs 1 and 2. No new top-level domains; everything reads from existing User/Auction/Bid/Cancellation/RefreshToken tables.

---

## 4. Backend pieces

### 4.1 `ListingReport` entity

```java
@Entity
@Table(name = "listing_reports",
    uniqueConstraints = @UniqueConstraint(columnNames = {"auction_id", "reporter_id"}))
public class ListingReport {
    @Id @GeneratedValue Long id;
    @ManyToOne(fetch = LAZY, optional = false) @JoinColumn(name = "auction_id") Auction auction;
    @ManyToOne(fetch = LAZY, optional = false) @JoinColumn(name = "reporter_id") User reporter;

    @Column(length = 100, nullable = false) String subject;

    @Enumerated(EnumType.STRING) @Column(length = 30, nullable = false)
    ListingReportReason reason;
    // INACCURATE_DESCRIPTION, WRONG_TAGS, SHILL_BIDDING, FRAUDULENT_SELLER,
    // DUPLICATE_LISTING, NOT_ACTUALLY_FOR_SALE, TOS_VIOLATION, OTHER

    @Column(columnDefinition = "text", nullable = false) String details; // max 2000 char (validated)

    @Enumerated(EnumType.STRING) @Column(length = 20, nullable = false)
    ListingReportStatus status; // OPEN, REVIEWED, DISMISSED, ACTION_TAKEN

    @Column(name = "admin_notes", columnDefinition = "text") String adminNotes;
    @ManyToOne(fetch = LAZY) @JoinColumn(name = "reviewed_by") User reviewedBy;
    @Column(name = "reviewed_at") OffsetDateTime reviewedAt;

    @CreationTimestamp OffsetDateTime createdAt;
    @UpdateTimestamp OffsetDateTime updatedAt;
}
```

UPSERT semantics: `POST /api/v1/auctions/{id}/report` from a user who already has an OPEN/DISMISSED/REVIEWED report on this auction REPLACES the row's subject/reason/details/status (resets to OPEN). Implementation: `findByAuctionIdAndReporterId(...).orElseGet(new)` then save. Update `updatedAt` via `@UpdateTimestamp`.

ACTION_TAKEN: set on all OPEN reports for a listing when admin clicks Suspend or Cancel via the reports queue. Different from REVIEWED (set by Warn-seller).

### 4.2 `Ban` entity + `BanCheckService` + Redis cache

```java
@Entity
@Table(name = "bans")
@Check(constraints = """
    (ban_type = 'IP'     AND ip_address IS NOT NULL AND sl_avatar_uuid IS NULL) OR
    (ban_type = 'AVATAR' AND ip_address IS NULL     AND sl_avatar_uuid IS NOT NULL) OR
    (ban_type = 'BOTH'   AND ip_address IS NOT NULL AND sl_avatar_uuid IS NOT NULL)
""")
public class Ban {
    @Id @GeneratedValue Long id;

    @Enumerated(EnumType.STRING) @Column(name = "ban_type", length = 10, nullable = false)
    BanType banType; // IP, AVATAR, BOTH

    @Column(name = "ip_address", columnDefinition = "inet") String ipAddress;
    @Column(name = "sl_avatar_uuid") UUID slAvatarUuid;

    @Enumerated(EnumType.STRING) @Column(name = "reason_category", length = 30, nullable = false)
    BanReasonCategory reasonCategory; // SHILL_BIDDING, FRAUDULENT_SELLER, TOS_ABUSE, SPAM, OTHER

    @Column(name = "reason_text", columnDefinition = "text", nullable = false) String reasonText;

    @ManyToOne(fetch = LAZY, optional = false) @JoinColumn(name = "banned_by") User bannedBy;
    @Column(name = "expires_at") OffsetDateTime expiresAt; // null = permanent

    @Column(name = "lifted_at") OffsetDateTime liftedAt;
    @ManyToOne(fetch = LAZY) @JoinColumn(name = "lifted_by") User liftedBy;
    @Column(name = "lifted_reason", columnDefinition = "text") String liftedReason;

    @CreationTimestamp OffsetDateTime createdAt;
}
```

**Active ban predicate**: `liftedAt IS NULL AND (expiresAt IS NULL OR expiresAt > NOW())`. Encoded as `BanRepository.findActiveByIpAddress(String)` and `findActiveBySlAvatarUuid(UUID)` derived queries via `@Query` (the temporal check needs explicit JPQL — see snippet below).

```java
@Query("""
    SELECT b FROM Ban b
    WHERE b.liftedAt IS NULL
      AND (b.expiresAt IS NULL OR b.expiresAt > :now)
      AND (b.banType = 'IP' OR b.banType = 'BOTH')
      AND b.ipAddress = :ip
""")
List<Ban> findActiveByIpAddress(@Param("ip") String ip, @Param("now") OffsetDateTime now);
```

Symmetric query for `findActiveBySlAvatarUuid(UUID, OffsetDateTime)`.

**`BanCheckService`** — single entry point for enforcement:

```java
public class BanCheckService {
    public void assertNotBanned(String ipAddress, UUID slAvatarUuid) {
        Ban hit = lookupCachedActive(ipAddress, slAvatarUuid);
        if (hit != null) {
            throw new UserBannedException(hit.getId(), hit.getExpiresAt());
        }
    }
    // Looks up Redis (5-min TTL) keyed bans:active:ip:<ip> and bans:active:avatar:<uuid>
    // Cache miss → DB query → cache result (or empty marker)
    // Cache invalidated on ban-create / ban-lift
}
```

**`UserBannedException` → 403** with body `{code: USER_BANNED, expiresAt: <ISO8601 | null>}`. Handled by `AdminExceptionHandler` (sub-spec 1) for admin endpoints; user-facing endpoints route through `GlobalExceptionHandler`.

**Cache keys**:
- `bans:active:ip:<ip>` → JSON-encoded ban-id-list (or empty marker `[]`). TTL 5 min.
- `bans:active:avatar:<uuid>` → same shape.
- Both cleared by `BanCacheInvalidator.invalidate(ip, slAvatarUuid)` on create + lift, called inside the ban-write transaction's afterCommit hook.

**Token-version bump** on AVATAR/BOTH ban creation: `userRepository.findBySlAvatarUuid(uuid).ifPresent(u -> userRepository.bumpTokenVersion(u.getId()))`. Existing primitive — no new code beyond the call. The user's outstanding access token becomes invalid on next refresh; their session redirects to login (existing behavior).

### 4.3 Ban enforcement points

| Path | Calls | What's checked |
|------|-------|----------------|
| `POST /api/v1/auth/register` | `assertNotBanned(ipAddress, null)` | IP only |
| `POST /api/v1/auth/login` | `assertNotBanned(ipAddress, null)` | IP only |
| `POST /api/v1/sl/verify` | `assertNotBanned(null, slAvatarUuid)` | SL avatar from header |
| `POST /api/v1/auctions/{id}/bid` | `assertNotBanned(ipAddress, user.slAvatarUuid)` | both |
| `POST /api/v1/auctions` (listing creation) | `assertNotBanned(ipAddress, user.slAvatarUuid)` | both |
| `POST /api/v1/auctions/{id}/cancel` | `assertNotBanned(ipAddress, user.slAvatarUuid)` | both — banned seller can't circumvent via cancel-and-sell |

**Wrinkle for listing creation**: `AuctionController` doesn't currently capture client IP. Sub-spec 2 adds `HttpServletRequest` parameter to the `POST /api/v1/auctions` controller method; `getRemoteAddr()` is used (matches existing `BidController` pattern at line ~65).

**Orthogonal to existing `User.bannedFromListing` flag** (4th-offense penalty-ladder result). That flag still independently blocks listing creation. Sub-spec 2 does NOT touch it. Both checks fire on listing-creation entry; either one rejects.

### 4.4 `AdminReportController` + service

**User-facing endpoints** (`/api/v1/auctions/{id}/...`):

```
POST /api/v1/auctions/{id}/report
  Body: { subject (1-100), reason (enum), details (1-2000) }
  Auth: verified user, not the seller
  Auction state: must be ACTIVE
  Behavior: upsert by (auction_id, reporter_id) — resubmit replaces.
            Resubmit resets status to OPEN even if previously DISMISSED/REVIEWED.
            Returns the persisted report (200 OK).

GET /api/v1/auctions/{id}/my-report
  Auth: any authenticated user
  Returns the current user's report on this auction, or 204 No Content.
  Used by frontend to flip the button to "Reported ✓".
```

**Admin endpoints** (`/api/v1/admin/reports/...`):

```
GET /api/v1/admin/reports?status=open|reviewed|all&page&size=25
  Returns LISTING-GROUPED rows (one per auction) sorted by reportCount DESC, latestReportAt DESC.
  Implementation: GROUP BY auction with COUNT and MAX(updated_at) projections.
  Each row: auctionId, auctionTitle, auctionStatus, parcelRegionName, sellerUserId,
            sellerDisplayName, openReportCount, latestReportAt, topReason (most common).

GET /api/v1/admin/reports/listing/{auctionId}
  Returns all reports for one listing: ordered detectedAt DESC.
  Each report includes reporter (id, displayName, dismissedReportsCount) for the
  hyperlink-to-user-detail anchor.

GET /api/v1/admin/reports/{id}
  Single report detail.

POST /api/v1/admin/reports/{id}/dismiss
  Body: { notes (1-1000) }
  Marks status=DISMISSED, sets reviewedBy/reviewedAt/adminNotes.
  Increments reporter.dismissedReportsCount.
  Writes admin_actions row (DISMISS_REPORT).

POST /api/v1/admin/reports/listing/{auctionId}/warn-seller
  Body: { notes (1-1000) }
  Marks ONLY the OPEN reports for this listing as REVIEWED (with adminNotes).
  DISMISSED reports stay DISMISSED — those represent deliberate per-report
  decisions (with the reporter's frivolous counter already incremented) and
  must not be reclassified by a listing-level action.
  Sends LISTING_WARNED notification to seller.
  Writes admin_actions row (WARN_SELLER_FROM_REPORT) targetType=LISTING.

POST /api/v1/admin/reports/listing/{auctionId}/suspend
  Body: { notes (1-1000) }
  Calls SuspensionService.suspendByAdmin(auction, adminUserId, notes).
  Marks ONLY the OPEN reports as ACTION_TAKEN. DISMISSED reports stay DISMISSED.
  Writes admin_actions row (SUSPEND_LISTING_FROM_REPORT).

POST /api/v1/admin/reports/listing/{auctionId}/cancel
  Body: { notes (1-1000) }
  Calls CancellationService.cancelByAdmin(auctionId, adminUserId, notes).
  Marks ONLY the OPEN reports as ACTION_TAKEN. DISMISSED reports stay DISMISSED.
  Writes admin_actions row (CANCEL_LISTING_FROM_REPORT).
```

All admin endpoints fail-fast 403 if not `ROLE_ADMIN` (existing matcher from sub-spec 1).

### 4.5 `AdminBanController` + service

```
GET /api/v1/admin/bans?status=active|history&type=IP|AVATAR|BOTH&page&size=25
  status=active default. type filter optional (omit for all types).
  Each row: id, banType, ipAddress (if IP/BOTH), slAvatarUuid (if AVATAR/BOTH),
            avatarLinkedUserId (if avatar maps to a registered user),
            firstSeenIp (for AVATAR rows: the user's earliest RefreshToken.ipAddress),
            reasonCategory, reasonText, bannedByDisplayName, expiresAt, createdAt,
            liftedAt, liftedByDisplayName, liftedReason.

POST /api/v1/admin/bans
  Body: { banType, ipAddress?, slAvatarUuid?, expiresAt?, reasonCategory, reasonText (1-1000) }
  Validation: type-matches-fields invariant enforced server-side (same as DB CHECK).
  On create:
    1. Save Ban row.
    2. If banType in {AVATAR, BOTH} and a user has slAvatarUuid: bump that user's tokenVersion.
    3. Invalidate Redis cache for ip and/or uuid keys.
    4. Write admin_actions row (CREATE_BAN) targetType=BAN, targetId=newBan.id.
  Returns the persisted ban row.

POST /api/v1/admin/bans/{id}/lift
  Body: { liftedReason (1-1000) }
  Sets liftedAt=now, liftedBy=admin, liftedReason.
  Invalidates Redis cache for the ban's ip/avatar.
  Writes admin_actions row (LIFT_BAN).
```

### 4.6 `AdminUserController` + service + role mgmt

```
GET /api/v1/admin/users?search=&page&size=25
  Single search box: backend tries
    - exact match on slAvatarUuid (if input parses as UUID)
    - case-insensitive substring match on email
    - case-insensitive substring match on displayName
    - case-insensitive substring match on slDisplayName
  Empty search → most-recent users (createdAt DESC).
  Each row: id, displayName, email, slAvatarUuid, slDisplayName, role, verified,
            completedSales, cancelledWithBids, hasActiveBan (boolean derived from Ban table).

GET /api/v1/admin/users/{id}
  Returns the user's profile + stats:
    id, displayName, email, slAvatarUuid, slDisplayName, role, verified, createdAt,
    completedSales, cancelledWithBids, escrowExpiredUnfulfilled,
    dismissedReportsCount, penaltyBalanceOwed, listingSuspensionUntil,
    bannedFromListing (the existing penalty flag), activeBanSummary (id + banType + reasonText + expiresAt | null).

GET /api/v1/admin/users/{id}/listings|bids|cancellations|reports|fraud-flags|moderation
  Six tab endpoints, each paginated.
  - listings: User's auctions (where seller_id = userId)
  - bids: Bid history (manual bids only; proxy-derived bids excluded for clarity)
  - cancellations: cancellation_log rows for this user as seller
  - reports: union of (reports filed by user) + (reports against user's listings) with a
             "direction" discriminator on each row (FILED_BY | AGAINST_LISTING)
  - fraud-flags: FraudFlag rows where the underlying auction's seller = userId
  - moderation: AdminAction rows where targetType=USER and targetId=userId

GET /api/v1/admin/users/{id}/ips
  Distinct ipAddress values from RefreshToken table for this user, ordered earliest-first.
  Each row: ipAddress, firstSeenAt, lastSeenAt, sessionCount.
  Used by the "Recent IPs" modal on user-detail and by the bans page's first-seen-IP column.

POST /api/v1/admin/users/{id}/promote
  Body: { notes (1-1000) }
  Validation: target user must currently be USER. 409 ALREADY_ADMIN otherwise.
  Behavior: User.role = ADMIN; bump tokenVersion.
  Writes admin_actions row (PROMOTE_USER).

POST /api/v1/admin/users/{id}/demote
  Body: { notes (1-1000) }
  Validation:
    - Target user must currently be ADMIN. 409 NOT_ADMIN otherwise.
    - Target user MUST NOT be the calling admin. 409 SELF_DEMOTE_FORBIDDEN otherwise.
  Behavior: User.role = USER; bump tokenVersion.
  Writes admin_actions row (DEMOTE_USER).

POST /api/v1/admin/users/{id}/reset-frivolous-counter
  Body: { notes (1-1000) }
  Behavior: User.dismissedReportsCount = 0.
  Writes admin_actions row (RESET_FRIVOLOUS_COUNTER).
```

### 4.7 `AdminAuctionService.reinstate` shared primitive

```java
public class AdminAuctionService {

    @Transactional
    public AdminAuctionReinstateResult reinstate(
            Long auctionId, Long adminUserId, String notes,
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
            newEndsAt = now.plusHours(1);  // clamp
        }

        auction.setStatus(AuctionStatus.ACTIVE);
        auction.setSuspendedAt(null);
        auction.setEndsAt(newEndsAt);
        auctionRepo.save(auction);

        botMonitorLifecycleService.onAuctionResumed(auction);

        notificationPublisher.listingReinstated(
            auction.getSeller().getId(), auction.getId(),
            auction.getTitle(), newEndsAt);

        // Audit row written by caller (caller decides target_type — LISTING for the
        // standalone endpoint, FRAUD_FLAG-context-bound for the sub-spec 1 path).
        return new AdminAuctionReinstateResult(auction, suspensionDuration, newEndsAt);
    }
}
```

**Sub-spec 1 refactor**: `AdminFraudFlagService.reinstate(flagId, adminUserId, notes)` decomposes:
1. Load flag → 404 if missing → 409 if already resolved → check auction non-null
2. Call `adminAuctionService.reinstate(flag.auction.id, adminUserId, notes, Optional.of(flag.detectedAt))`
3. Mark flag resolved with notes
4. Write admin_actions row (no — sub-spec 1 didn't write to admin_actions for fraud flags; flag.resolvedBy is the audit. Stays that way to avoid a breaking change.)

Net behavior: identical to sub-spec 1 today. The refactor extracts the auction-state-change into a reusable service.

**New endpoint**: `POST /api/v1/admin/auctions/{id}/reinstate` body `{notes}`. Calls `adminAuctionService.reinstate(id, adminUserId, notes, Optional.empty())` then writes `admin_actions` row (REINSTATE_LISTING, target_type=LISTING). Used by the user-detail Listings tab "Reinstate" button (visible per row when status=SUSPENDED).

### 4.8 `SuspensionService.suspendByAdmin` (new path)

Adds an admin-driven suspension entry point alongside the existing ownership-monitor / bot-monitor / cancel-and-sell paths.

```java
@Transactional
public void suspendByAdmin(Auction auction, Long adminUserId, String notes) {
    OffsetDateTime now = OffsetDateTime.now(clock);
    auction.setStatus(AuctionStatus.SUSPENDED);
    if (auction.getSuspendedAt() == null) {
        auction.setSuspendedAt(now);
    }
    auctionRepo.save(auction);

    // No FraudFlag created — admin reason, not a fraud-detection event.
    monitorLifecycle.onAuctionClosed(auction);

    notificationPublisher.listingSuspended(
        auction.getSeller().getId(), auction.getId(),
        auction.getTitle(), "Suspended by SLPA staff");
    // Existing LISTING_SUSPENDED category; reason text differs from fraud-driven reasons.
}
```

The reports-queue `POST .../suspend` endpoint calls this.

### 4.9 `CancellationService.cancelByAdmin` (new path)

```java
@Transactional
public Auction cancelByAdmin(Long auctionId, Long adminUserId, String notes) {
    Auction a = auctionRepo.findByIdForUpdate(auctionId)
        .orElseThrow(() -> new AuctionNotFoundException(auctionId));
    if (!CANCELLABLE.contains(a.getStatus())) {
        throw new InvalidAuctionStateException(a.getId(), a.getStatus(), "ADMIN_CANCEL");
    }
    User admin = userRepo.findById(adminUserId).orElseThrow();

    // Skip penalty ladder. Skip seller-row lock (no seller-side state changes).
    logRepo.save(CancellationLog.builder()
        .auction(a).seller(a.getSeller())
        .cancelledFromStatus(a.getStatus().name())
        .hadBids(a.getBidCount() != null && a.getBidCount() > 0)
        .reason(notes)
        .penaltyKind(CancellationOffenseKind.NONE)
        .penaltyAmountL(null)
        .cancelledByAdminId(adminUserId)  // NEW field
        .build());

    a.setStatus(AuctionStatus.CANCELLED);
    auctionRepo.save(a);

    monitorLifecycle.onAuctionClosed(a);
    broadcastPublisher.publishStatusChanged(a);

    // Seller side: distinct LISTING_REMOVED_BY_ADMIN category.
    notificationPublisher.listingRemovedByAdmin(
        a.getSeller().getId(), a.getId(), a.getTitle(), notes);

    // Bidder side: same fan-out as seller-driven cancel — bidders need to know
    // their proxy max won't fire. Reuses LISTING_CANCELLED_BY_SELLER category
    // for bidders since the bidder's outcome is identical regardless of cause
    // (auction is gone, proxy is wasted). Body copy is cause-neutral so it
    // works for both seller-cancel and admin-cancel callers.
    notificationPublisher.listingCancelledBySellerFanout(
        a.getId(), a.getTitle(), bidderUserIds(a), notes);

    return a;
}
```

The existing `listingCancelledBySellerFanout` method body is updated so the title/body strings are cause-neutral ("This auction has been cancelled. Your active proxy bid is no longer in effect.") instead of seller-attributed. That copy applies cleanly to both callers (seller-cancel and admin-cancel).

`bidderUserIds(a)` returns distinct user ids from `Bid` and `ProxyBid` rows on the auction (excluding the seller, who already gets the seller-side notification). Reuses the same query the existing seller-cancel fan-out uses — sub-spec 2 doesn't add new query infrastructure.

**`CancellationLog.cancelledByAdminId`** — new nullable column. The existing `countPriorOffensesWithBids` query updated:

```java
@Query("""
    SELECT COUNT(c) FROM CancellationLog c
    WHERE c.seller.id = :sellerId
      AND c.hadBids = true
      AND c.penaltyKind <> 'NONE'
      AND c.cancelledByAdminId IS NULL
""")
long countPriorOffensesWithBids(@Param("sellerId") Long sellerId);
```

Admin-cancel rows don't count as seller offenses. Existing `seller.cancelledWithBids` counter — also NOT incremented on admin cancel. The penalty ladder index stays consistent for the seller's actual choices.

### 4.10 New notification categories

```java
LISTING_WARNED(NotificationGroup.LISTING_STATUS),
LISTING_REMOVED_BY_ADMIN(NotificationGroup.LISTING_STATUS),
```

Both default ON (LISTING_STATUS group default). Standard notification flow — in-app row + SL IM dispatch via existing channel.

`NotificationPublisher` gains:
```java
void listingWarned(long sellerUserId, long auctionId, String parcelName, String notes);
void listingRemovedByAdmin(long sellerUserId, long auctionId, String parcelName, String reason);
```

### 4.11 `AdminAction` audit table

```java
@Entity @Table(name = "admin_actions",
    indexes = {
        @Index(columnList = "target_type, target_id, created_at DESC"),
        @Index(columnList = "admin_user_id, created_at DESC")
    })
public class AdminAction {
    @Id @GeneratedValue Long id;
    @ManyToOne(fetch = LAZY, optional = false) @JoinColumn(name = "admin_user_id") User adminUser;
    @Enumerated(EnumType.STRING) @Column(length = 40, nullable = false) AdminActionType actionType;
    @Enumerated(EnumType.STRING) @Column(length = 20, nullable = false) AdminActionTargetType targetType;
    @Column(name = "target_id", nullable = false) Long targetId;
    @Column(columnDefinition = "text") String notes;
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") Map<String, Object> details;
    @CreationTimestamp OffsetDateTime createdAt;
}

enum AdminActionType {
    DISMISS_REPORT, WARN_SELLER_FROM_REPORT, SUSPEND_LISTING_FROM_REPORT, CANCEL_LISTING_FROM_REPORT,
    CREATE_BAN, LIFT_BAN,
    PROMOTE_USER, DEMOTE_USER, RESET_FRIVOLOUS_COUNTER,
    REINSTATE_LISTING
}
enum AdminActionTargetType { USER, LISTING, REPORT, FRAUD_FLAG, BAN }
```

Sub-spec 1's fraud-flag actions are NOT backfilled; their audit is the FraudFlag row itself. Sub-spec 2 forward only.

`GET /api/v1/admin/audit?targetType=&targetId=&adminUserId=&page&size=25` — paginated, sort `createdAt DESC`. The user-detail Moderation tab queries `targetType=USER&targetId={userId}`.

### 4.12 `User.dismissedReportsCount`

```java
@Column(name = "dismissed_reports_count", nullable = false)
@Builder.Default
private long dismissedReportsCount = 0L;
```

Increments inside the dismiss-report transaction:
```java
reporter.setDismissedReportsCount(reporter.getDismissedReportsCount() + 1);
userRepo.save(reporter);
```

No automatic privilege revocation. Surfaces on the admin user-detail stats card. Reset endpoint zeroes it.

---

## 5. Frontend pieces

### 5.1 Auction-detail Report button + modal

**Button placement**: title row of `/auction/[id]`, right side, neutral border, flag icon (⚐) + "Report" label. State machine:
- Anonymous user → hidden
- Logged-in but not verified → disabled with tooltip "Verify your SL avatar to report listings"
- Logged-in verified, current user is the seller → hidden
- Logged-in verified, not seller, no existing report → enabled
- Logged-in verified, not seller, existing report (any status) → "Reported ✓" disabled. Click does nothing. Modal NOT opened. (Reporter must wait until admin DISMISSES — at which point existing-report query returns 204 — to file again.)

Hook: `useMyReport(auctionId)` queries `GET /api/v1/auctions/{id}/my-report` (cached, only fires when user is verified). Determines button state.

Wait — the button-state spec above conflicts with the resubmit-after-dismiss flow. Let me reconcile:

Actually, the GET /api/v1/auctions/{id}/my-report endpoint should return the existing report regardless of status (unless dismissed AND admin reset something). The frontend reads the `status` field:
- No existing row → 204 → button shows "Report"
- Existing row, status in {OPEN, REVIEWED, ACTION_TAKEN} → button shows "Reported ✓" disabled
- Existing row, status = DISMISSED → button shows "Report" enabled (allows resubmit)

This way the button automatically re-enables after admin dismisses, and the resubmit upserts the row back to OPEN. Modal opens BLANK on resubmit (per Q decision; modal does not pre-fill).

### 5.2 Report modal

3 fields (subject 100, reason dropdown, details 2000) + disclosure block + Cancel/Submit buttons. On submit: `POST .../report`, success toast "Report submitted. Our team will review it.", invalidate `useMyReport(auctionId)` so button updates. Failure → toast "Couldn't submit report" + leaves modal open.

### 5.3 Admin reports queue page

Listing-grouped table at `/admin/reports`. Filter pills (Open/Reviewed/All). 5 columns: report-count (right-aligned, prominent, color-tinted on count >= 3), listing (title + region + auction id + seller + time-left), latestReportAt, top-reason badge, auctionStatus.

URL state: `?status=open|reviewed|all&page=N&auctionId=N` (auctionId opens the slide-over).

Click row → URL gains `auctionId=N` → slide-over (560px) opens with:
- Header: open-count badge + auction context + "View listing" link + close
- 4 action buttons (Warn / Suspend / Cancel / Ban seller — last navigates to `/admin/users/{sellerId}`)
- All reports for this listing as cards: reporter name (linked to `/admin/users/{reporterId}`), reason badge, subject, details, per-report Dismiss button
- Shared notes textarea at bottom (used by whichever main action button fires)

Mutations invalidate both `["admin", "reports"]` and `["admin", "stats"]` query keys.

### 5.4 Admin bans page

`/admin/bans` with Active / History tabs. Type filter pills (All/IP/Avatar/Both). Table columns: type badge (red IP / purple AVATAR / amber BOTH), identifiers (UUID linked to user-detail when applicable, plus first-seen-IP for AVATAR rows), reason (truncated), bannedBy, expires, Lift action.

Create-ban modal:
- Type selector (3-up: IP only / Avatar only / Both)
- Identifier inputs (conditional on type)
  - User-search autocomplete: types user name → backend returns matches → select user → fills slAvatarUuid AND/OR most-recent IP
  - Direct UUID/IP input (manual paste)
- Expiry: Permanent / Pick date…
- Reason category dropdown (SHILL_BIDDING / FRAUDULENT_SELLER / TOS_ABUSE / SPAM / OTHER) + reason-text textarea (1-1000, required)
- Side-effect note explaining tv-bump + cache flush
- Cancel / Create ban actions

Pre-filled variant (from user-detail "+ Add another ban" button): SL UUID and most-recent IP populated from the user record.

### 5.5 Admin user search + detail

`/admin/users` — single search input + paginated table. 7 columns: display name (with `{N} sold · {N} cancelled` muted under), email, slAvatarUuid (truncated), role chip, verified chip, banned chip (if active ban matches user's slAvatarUuid), member since.

`/admin/users/{id}` — profile header (Banned chip + email + truncated UUID + verified + last-seen + role chip), 5 stat cards (sold, cancelled-w-bids, escrow-expired, dismissed-reports, penalty-owed L$), 6 tabs (Listings / Bids / Cancellations / Reports / Fraud flags / Moderation).

**280px right rail**:
- Active-ban callout (if any active ban) — type, expiry, reason, bannedBy, "Lift ban with notes…" button
- Action buttons stacked: Promote (USER only), Demote (ADMIN only, disabled with reason inline if N/A; self-demote returns 409 SELF_DEMOTE_FORBIDDEN), Reset frivolous counter (only when count > 0), "+ Add another ban…"
- Quick links: Public profile, Copy SL UUID, Recent IPs (modal)

**"Recent IPs" modal**: list of distinct IPs from `RefreshToken` for this user, sorted earliest-first, with first/last-seen timestamps and session count. Each row has a "Ban this IP" button that opens the ban-create modal pre-filled with type=IP and the chosen IP.

**Listings tab — Reinstate button**: per row when `status === "SUSPENDED"`, "Reinstate" action button opens a notes modal. On submit, calls `POST /api/v1/admin/auctions/{id}/reinstate` with `{notes}`. Success → invalidate `["admin", "users", id, "listings"]` and `["admin", "stats"]`. Failure → 409 toast.

### 5.6 Dashboard 4-up

Sub-spec 1's dashboard's needs-attention row gains a 4th card (Open reports → `/admin/reports?status=open`) in single 4-up row. Card sizes shrink (24px value glyph instead of 32px). Future cards add to the same row.

`AdminStatsResponse.queues` gains `openReports: number`. The frontend `QueueCard` renders identical for the new card.

### 5.7 Mutations follow the established pattern

All admin write mutations:
- Use `useMutation` with `onSuccess` invalidating BOTH the relevant list cache AND `["admin", "stats"]` (keeps the dashboard counts honest).
- 409 paths handled by reading the `code` field from the error body, with specific toast copy per code (`ALREADY_RESOLVED`, `AUCTION_NOT_SUSPENDED`, `SELF_DEMOTE_FORBIDDEN`, `USER_BANNED`, etc.).
- All write actions require non-empty notes; 400 `VALIDATION_FAILED` if empty.

---

## 6. Data flow scenarios

### 6.1 User reports a listing (first time)

1. Verified user lands on `/auction/893`. `useMyReport(893)` returns 204 → button shows "Report" enabled.
2. User clicks → modal opens blank. User types subject + selects "Shill bidding" + types details. Submits.
3. Backend `POST /api/v1/auctions/893/report` validates (verified, not seller, auction ACTIVE), upserts a ListingReport with status=OPEN.
4. Response 200. Frontend invalidates `useMyReport(893)` → button flips to "Reported ✓" disabled.
5. Toast: "Report submitted. Our team will review it."
6. No notification to admin this sub-spec (deferred). Admin sees the report on next visit to `/admin/reports`.

### 6.2 User resubmits after admin dismisses

1. Admin dismisses report. `report.status = DISMISSED`. Reporter's `dismissedReportsCount += 1`.
2. User reloads `/auction/893`. `useMyReport(893)` now returns the dismissed report → frontend reads `status === "DISMISSED"` → button re-enables to "Report".
3. User clicks → modal opens BLANK (per design decision — pre-fill explicitly rejected).
4. Submit upserts the SAME row (matching unique key on auction+reporter), resets status to OPEN.

### 6.3 Admin triages a listing with 5 reports

1. Admin clicks Reports in sidebar. List shows 5 listings, top one has report count 5.
2. Admin clicks the top row. Slide-over opens.
3. Admin reads the 5 reports. Decides 3 are credible (shill-bidding pattern). Decides on Suspend.
4. Admin types notes "Verified shill pattern from same IP across 3 accounts." Clicks Suspend.
5. Backend: `SuspensionService.suspendByAdmin(auction, adminUserId, notes)` flips status, sets suspendedAt if null, calls `monitorLifecycle.onAuctionClosed`, publishes `LISTING_SUSPENDED` to seller. Then marks all OPEN reports for this listing as ACTION_TAKEN with adminNotes. Then writes admin_actions row.
6. Slide-over closes. Reports list cache invalidated. Stats counter ticks down.
7. Seller (in-app + SL IM): "Listing suspended: Beachfront 1024m². Reason: Suspended by SLPA staff. Contact support for details."

### 6.4 Admin reinstates a wrongly-suspended listing (from user-detail)

1. Admin navigates to `/admin/users/4218`. Listings tab shows 17 listings; top one is SUSPENDED.
2. Admin clicks "Reinstate" on the row. Notes modal opens.
3. Admin types "Reviewed reports — clear false positives. Restoring." Clicks Reinstate.
4. Backend: `AdminAuctionService.reinstate(auctionId, adminUserId, notes, Optional.empty())`. suspendedFrom = auction.suspendedAt (set by sub-spec 2's suspendByAdmin). Computes duration, extends endsAt, flips status, clears suspendedAt, re-engages bot monitor (if BOT-tier), publishes `LISTING_REINSTATED`.
5. Backend writes admin_actions row (REINSTATE_LISTING, target_type=LISTING).
6. Frontend invalidates listings tab + stats. Toast: "Auction reinstated."
7. Seller notification: "Your auction has been reinstated. All existing bids and proxy maxes are preserved. Ends Apr 28, 14:35 UTC."
8. Reports remain in their current state (ACTION_TAKEN). Admin can dismiss them separately if they want to clear the historical fact (but typically they won't — actioned reports are part of the audit trail).

### 6.5 Admin creates a ban on a user with active session

1. From `/admin/users/4218`, admin clicks "+ Add another ban…" or follows the report queue's "Ban seller" link. Modal opens pre-filled with the user's slAvatarUuid.
2. Admin selects type=AVATAR, expiry=Permanent, reason=SHILL_BIDDING + reason text. Submits.
3. Backend: validate type-matches-fields → save Ban row → bump tokenVersion on the user (since slAvatarUuid maps) → invalidate Redis cache key `bans:active:avatar:<uuid>` → write admin_actions.
4. The user is currently logged in. Their access token is still valid (carries old tv) until expiry. On their next access-token refresh (within ~15 min), the new tv invalidates → 401 → frontend redirects to login. They can't login back in (banned IP/avatar — login endpoint hits BanCheckService).

### 6.6 Banned user attempts to bid

1. Banned user opens `/auction/893` while their cached page still works (browser SW or cached SSR).
2. They try to place a bid. `POST /api/v1/auctions/893/bid` reaches `BidController` which captures IP via `httpRequest.getRemoteAddr()`. Service calls `BanCheckService.assertNotBanned(ip, user.slAvatarUuid)`.
3. Cache miss → DB query → finds active AVATAR ban → cached for 5 min → throws `UserBannedException`.
4. Response 403 `{code: USER_BANNED, expiresAt: null}`.
5. Frontend toast: "Your account is suspended. Contact support if you believe this is in error."

### 6.7 Race: admin deletes their own admin role

1. Heath navigates to `/admin/users/{heath_id}` (his own detail page). Demote button visible.
2. Heath clicks Demote → notes modal → submits.
3. Backend `POST /admin/users/{heath_id}/demote`: target user = current user → throws `SelfDemoteException` → 409 `{code: SELF_DEMOTE_FORBIDDEN}`.
4. Frontend toast: "You cannot demote yourself." Modal stays open. Heath can cancel.

---

## 7. Error handling

`AdminExceptionHandler` (sub-spec 1) extended with new exception types:

| Exception | HTTP | Body code |
|-----------|------|-----------|
| `ListingReportNotFoundException` | 404 | `REPORT_NOT_FOUND` |
| `BanNotFoundException` | 404 | `BAN_NOT_FOUND` |
| `BanAlreadyLiftedException` | 409 | `BAN_ALREADY_LIFTED` |
| `BanTypeFieldMismatchException` | 400 | `BAN_TYPE_FIELD_MISMATCH` |
| `SelfDemoteException` | 409 | `SELF_DEMOTE_FORBIDDEN` |
| `UserAlreadyAdminException` | 409 | `ALREADY_ADMIN` |
| `UserNotAdminException` | 409 | `NOT_ADMIN` |
| `AlreadyReportedByUserException` | (no — upsert means resubmit succeeds) | — |
| `CannotReportOwnListingException` | 409 | `CANNOT_REPORT_OWN_LISTING` |
| `MustBeVerifiedToReportException` | 403 | `VERIFICATION_REQUIRED` |
| `AuctionNotActiveForReportException` | 409 | `AUCTION_NOT_REPORTABLE` |

`UserBannedException` → 403 `{code: USER_BANNED, expiresAt: <iso8601 \| null>}` — handled by `GlobalExceptionHandler` (user-facing path) so the existing user-facing endpoints get the same error shape. Sub-spec 2 adds the handler if not present.

---

## 8. Testing strategy

### Backend

#### Unit tests (Mockito)

- `AdminReportServiceTest` — dismiss happy path, dismiss already-dismissed (idempotent re-write — no error since notes update is allowed), warn/suspend/cancel batching of OPEN reports for a listing, frivolous-counter increment on dismiss.
- `AdminBanServiceTest` — create-ban write flow (with Redis cache invalidation mock), tv-bump only when slAvatarUuid maps to a user, lift-ban write flow with cache invalidation.
- `BanCheckServiceTest` — cache-hit path (no DB query), cache-miss path (DB query + write back), expired ban not enforced, lifted ban not enforced, type-mismatched ban (IP-only ban does NOT trigger on a UUID-only check) not enforced.
- `AdminUserServiceTest` — search routing (UUID-mode vs substring-mode), promote validates ALREADY_ADMIN, demote validates NOT_ADMIN + self-demote, reset-counter zeroes the field.
- `AdminAuctionServiceTest` — reinstate happy path with explicit `suspendedAt`, reinstate with fallbackSuspendedFrom (sub-spec 1 path simulation), reinstate with neither (0-extension), reinstate auction-not-suspended throws, endsAt clamping when math yields past.
- `CancellationServiceCancelByAdminTest` — admin cancel does NOT increment seller.cancelledWithBids, does NOT charge penalty, log row has cancelledByAdminId set, sends LISTING_REMOVED_BY_ADMIN.

#### Slice tests (`@SpringBootTest + @AutoConfigureMockMvc`)

- `AdminReportControllerSliceTest` — all admin endpoints: 401 anon, 403 USER, 200 ADMIN; body validation; 409 paths.
- `AdminBanControllerSliceTest` — same auth gates; create-ban with type-mismatch returns 400; lift returns 409 if already lifted.
- `AdminUserControllerSliceTest` — search response shape; promote/demote 409 paths; self-demote 409.
- `AdminAuctionControllerSliceTest` — reinstate auth gates + 409 AUCTION_NOT_SUSPENDED.
- `AuctionReportControllerSliceTest` — user-facing report endpoint: anonymous 401, unverified 403, own listing 409, non-ACTIVE auction 409, valid report 200, resubmit 200 (upsert).

#### Integration tests (`@SpringBootTest + @ActiveProfiles("dev")` + 9-line scheduler-disable property block)

- `ReportsFullFlowIntegrationTest` — user submits report → admin dismisses → user resubmits (upsert verified) → admin warns (REVIEWED + LISTING_WARNED) → admin suspends (ACTION_TAKEN on all OPEN + LISTING_SUSPENDED + admin_actions row).
- `BansEnforcementIntegrationTest` — create AVATAR ban → BanCheckService rejects bid by user with that avatar → lift ban → BanCheckService allows bid (after Redis cache flush). Repeat for IP and BOTH.
- `BansSessionInvalidationIntegrationTest` — create AVATAR ban → tokenVersion bumps on user → user's old access token returns 401 on next request.
- `AdminCancelBypassesPenaltyLadderIntegrationTest` — seller has 2 prior offenses → admin cancels → log row written with cancelledByAdminId → query countPriorOffensesWithBids still returns 2 (admin cancel excluded) → seller.cancelledWithBids unchanged.
- `AdminAuctionReinstateIntegrationTest` — admin suspends listing via reports queue (no fraud flag created) → admin reinstates via /admin/auctions/{id}/reinstate → auction status flips ACTIVE, suspendedAt cleared, endsAt extended, MONITOR_AUCTION row spawned (BOT tier), LISTING_REINSTATED notification, admin_actions row written.
- `FrivolousCounterIntegrationTest` — user reports 3 times → admin dismisses each → user.dismissedReportsCount = 3 → admin resets counter → field = 0, admin_actions row written.

### Frontend (Vitest + React Testing Library)

- `ReportButton.test.tsx` — state matrix: anonymous (hidden), unverified (disabled with tooltip), seller (hidden), reportable (enabled), already-reported (disabled "Reported ✓"), dismissed-by-admin (re-enabled).
- `ReportModal.test.tsx` — field validation, submit flow, error handling.
- `AdminReportsListPage.test.tsx` — listing-grouped rendering, slide-over open on row click, action button flow, per-report dismiss flow.
- `AdminBansListPage.test.tsx` — active/history tab toggle, type filter, lift action.
- `BanCreateModal.test.tsx` — type-selector conditionals (IP fields render for IP/BOTH, avatar field renders for AVATAR/BOTH), user-search autocomplete, validation.
- `AdminUsersSearchPage.test.tsx` — single-search input, results table with all columns including activity meta.
- `AdminUserDetailPage.test.tsx` — profile + stats + tab navigation; action buttons fire mutations and invalidate caches.
- `RecentIpsModal.test.tsx` — fetches and displays IP list; "Ban this IP" button opens ban-create modal pre-filled.

### Manual test plan (run before merge)

1. Sign in as USER → `/auction/[id]` → report a listing → verify modal flow, button flips to "Reported ✓".
2. Sign in as admin → `/admin/reports` → see the report → drill into slide-over → dismiss with notes → verify report status DISMISSED, frivolous counter on user-detail = 1.
3. Resubmit the report as the original user → button enabled again → submit → verify button flips back to "Reported ✓" and admin sees a fresh OPEN report.
4. Admin warns seller (different report) → verify seller gets `LISTING_WARNED` notification.
5. Admin suspends listing (different report) → verify seller gets `LISTING_SUSPENDED` notification, all open reports for that listing → ACTION_TAKEN. Verify auction stays SUSPENDED until admin reinstates.
6. Admin reinstates from user-detail Listings tab → verify auction → ACTIVE, endsAt extended, seller gets `LISTING_REINSTATED`.
7. Admin admin-cancels another listing → verify seller gets `LISTING_REMOVED_BY_ADMIN`, no penalty applied (cancellation_log row has `cancelledByAdminId`, `seller.cancelledWithBids` unchanged).
8. Admin creates AVATAR ban on a user → user is logged in → verify on next refresh, user is logged out (tv bump).
9. Banned user attempts bid → 403 with USER_BANNED code.
10. Admin lifts ban with notes → user logs in normally, can bid again.
11. Admin self-demote test → 409.
12. Admin creates ban via "Recent IPs" modal → verify IP-type ban is created with the chosen IP.

---

## 9. Documentation updates (in this sub-spec PR)

- `docs/initial-design/DESIGN.md` — append "Notes (Epic 10 sub-spec 2)" subsection to §8.
- `docs/implementation/DEFERRED_WORK.md`:
  - Close: ban system (Epic 03/05/06 deferred entries that mentioned "needs admin role"), listing reports (Epic 03 PARCEL-code-rate-tracking subset that mentions reports infrastructure).
  - Modify: account deletion entry (sub-spec 4 target retained).
  - Add: `REPORT_THRESHOLD_REACHED` admin notification, with re-add trigger ("admins missing high-report listings").
- `docs/implementation/FOOTGUNS.md` — append F.103–F.10X (numbers verified at write time):
  - Ban-cache TTL is 5min; admin-create flushes immediately, but a 5-min stale-window exists where a banned avatar could still bid if cached pre-ban. Acceptable tradeoff.
  - `BanCheckService.assertNotBanned(null, null)` is a no-op — both args optional. Caller responsible for passing what they have.
  - Listing-grouped reports queue uses GROUP BY auction_id; querying by status="open" is the operational query and must hit the index `(status, auction_id)`. Add explicit composite index if EXPLAIN reveals seq scan at scale.
  - Resubmit upsert resets status to OPEN — admin who dismissed a report sees it pop back into queue if reporter re-files. This is intentional (escape valve for "issue still happening") but admins should know.
  - `cancelByAdmin` writes `CancellationLog` rows with `cancelledByAdminId`; the existing `countPriorOffensesWithBids` query MUST exclude these or admin cancels accidentally count as seller offenses. Test covers it.
- `CLAUDE.md` (root) — short paragraph noting reports/bans/admin-user-mgmt under existing admin section.

---

## 10. Sub-spec wrap-up checklist

- [ ] All backend tests pass (`./mvnw test`)
- [ ] All frontend tests pass (`npm test`) + lint clean (`npm run lint`)
- [ ] Manual test plan run (12 scenarios)
- [ ] DEFERRED_WORK.md updated (close/modify/add)
- [ ] FOOTGUNS.md updated with new entries (numbers continuous after sub-spec 1's F.102)
- [ ] DESIGN.md §8 gains sub-spec 2 notes subsection
- [ ] CLAUDE.md root reference updated if section structure warrants

---

## 11. Risks & rollback

**Rollback**: revert the merge. JPA `ddl-auto: update` does not auto-drop the new columns/tables (`listing_reports`, `bans`, `admin_actions`, `User.dismissedReportsCount`, `CancellationLog.cancelledByAdminId`). All remain as orphan schema objects — Hibernate ignores. A follow-up SQL would clean up if rollback is permanent.

**Risk: Redis cache desync with bans table.** Mitigation: cache invalidation runs in afterCommit hook of ban-write transaction; cache TTL caps stale-window at 5 min even in the worst case. Documented.

**Risk: ban-check IP from `getRemoteAddr()` is the proxy IP, not real client IP** if the app is behind a load balancer that doesn't set `X-Forwarded-For`. Same risk applies to existing IP-capture code (RefreshToken, Bid) — sub-spec 2 doesn't make this worse. Pre-launch ops checklist item.

**Risk: ListingReport unique constraint upsert race.** Two concurrent POSTs from the same user would collide. JPA's `INSERT ... ON CONFLICT DO UPDATE` is the right idiom; or a try-catch on `DataIntegrityViolationException` retrying as update. Use the latter (Spring Data idiomatic), narrow window — concurrent posts from the same user are rare.

**Risk: Admin-bumped tokenVersion races with in-flight refresh.** The user has 1 second of grace window where their refresh could succeed with the old tv. Acceptable — the next request after that is rejected.

**Risk: `firstSeenIp` column on bans page reads from RefreshToken history**, which is a join — could be slow at scale. Mitigate by capping the join to most-recent N tokens and noting the limit on the column tooltip. Sub-day operational concern at current scale.

---

## 12. Open questions / explicitly resolved

- **3-report threshold notification**: ✅ deferred (DEFERRED_WORK).
- **Frivolous reporter automatic revocation**: ✅ counter only this sub-spec, no automatic revoke.
- **Admin-cancel skips penalty ladder**: ✅ confirmed via separate `cancelByAdmin` path + `cancelledByAdminId` exclusion.
- **Reinstate path for admin-suspended listings**: ✅ extracted `AdminAuctionService.reinstate` shared primitive; standalone endpoint + user-detail Listings tab button.
- **Multi-ban stacking**: ✅ allowed; same user can have AVATAR + IP bans.
- **First-seen IP on bans list**: ✅ shown for AVATAR rows.
- **Reason categories on ban**: ✅ preset enum + free text.
- **Modal autocomplete for ban-create from /admin/bans**: ✅ user-search autocomplete.
- **IP history exposed on user-detail**: ✅ "Recent IPs" modal.
- **Self-demote guard**: ✅ 409 SELF_DEMOTE_FORBIDDEN.
- **Resubmit pre-fill**: ✅ blank, not pre-filled.
- **Frivolous reports disclosure on report modal**: ✅ kept.
- **Dashboard 4-up layout**: ✅ 4-up single row.

---

## 13. Glossary

| Term | Meaning |
|---|---|
| Listing report | A user-filed flag on an auction. Subject + reason + details. Upsert by (auction, reporter). |
| Frivolous reporter | A user whose `dismissedReportsCount` is non-zero. Counter only — no automatic privilege revocation this sub-spec. |
| Ban | A row in the `bans` table. Active = not lifted AND not expired. By IP, SL avatar, or both. |
| Admin-cancel | Admin-driven listing cancellation via `CancellationService.cancelByAdmin`. Skips penalty ladder. |
| Admin-suspend | Admin-driven listing suspension via `SuspensionService.suspendByAdmin`. No fraud flag created. |
| Reinstate | Restoring SUSPENDED auction to ACTIVE via shared `AdminAuctionService.reinstate`. Independent of why it was suspended. |
| `admin_actions` | Audit table — every admin write action sub-spec 2 forward. Indexed by (target_type, target_id) for "what happened to this user/listing/etc." queries. |
| Sibling | Another open report on the same auction (or another open fraud flag — same concept from sub-spec 1). |
