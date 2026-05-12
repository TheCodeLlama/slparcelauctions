# Realty Groups: F — Admin Moderation (Design)

**Date:** 2026-05-12
**Status:** Spec
**Sub-project:** F of the Realty Groups feature family. Loads on top of A+B (core + permissions), C (group-listing integration), D (group wallet), E (SL-group-owned listings).
**Issue:** [#240](https://github.com/TheCodeLlama/slparcelauctions/issues/240)

## 1. Goal

Ship the realty-group admin moderation toolkit:

- Timed group suspension + permanent group ban (single entity, "ban" = no expiry).
- Force-suspend-all-listings of a group with a 48 h auto-cancel timer.
- Group fraud-flagging (extends the existing polymorphic `AdminFraudFlagService`).
- User-submitted reports against groups (mirrors `listing_reports`).
- Group-scoped audit-log filter (extends existing admin audit view).
- Group reputation/rating display (aggregates existing `Review` data).

Plus the SL-group admin items deferred from E:

- Drift-detecting re-verify (periodic + on-demand).
- Admin force-unregister of an SL group (cascade-suspends in-flight case-3 listings).
- Admin drift acknowledgment (refresh founder snapshot without forcing leader through founder-terminal again).
- Bulk per-member commission edit + per-member commission analytics dashboard.

Plus two cleanups discovered during F brainstorm:

- Fix the `parseGroupHtml` selector bugs (group name + about text — the founder-UUID selector is correct).
- Remove the unused About-text verification path entirely (E shipped both methods; the About-text path was broken because the parser couldn't find `aboutText`, and we keep founder-terminal as the sole path).

## 2. Architecture

Five mostly-independent vertical slices that share state through `realty_groups` and the admin audit log:

1. **Group moderation entity** — `realty_group_suspensions` table, `RealtyGroupSuspensionService`, `RealtyGroupGuard` enforcement gate injected on three blocked operations.
2. **Bulk listing force-suspend with 48 h auto-cancel** — reuses the existing Epic 10 `AuctionStatus.SUSPENDED` freeze + clock-pause mechanism per the **No PAUSED state for active auctions** memory's admin-moderation carve-out. New `BulkListingSuspendService` + `BulkSuspendedListingExpiryTask`.
3. **Group fraud flagging** — extends existing `AdminFraudFlagService` polymorphism: `FraudFlagEntityKind.REALTY_GROUP`. No new table.
4. **Reports against groups** — new `realty_group_reports` table mirroring `listing_reports` shape; new `RealtyGroupReportService`; new admin queue.
5. **SL group admin / E deferred items** — parser fixes, About-text removal, `SlGroupReverifyTask`, admin recheck + drift ack + force-unregister, bulk commission edit, commission analytics.

Plus an audit-log filter extension (no new table — just an `entityType=REALTY_GROUP` query parameter on the existing `/admin/audit` endpoint) and a group reputation/rating display (read-only aggregate of existing `Review` data; cached in Redis with 1 h TTL).

## 3. Dependencies

F depends on:

- **A+B** — `realty_groups`, `realty_group_members`, admin shell.
- **C** — `CREATE_LISTING` permission gate (gated by `RealtyGroupGuard`).
- **D** — group wallet withdraw + ledger (gated by `RealtyGroupGuard`).
- **E** — SL group registration, case-3 listing flow, agent commission distributor.
- **Phase 8 (already shipped)** — `Review` entity for rating aggregation.

Nothing outside this list is touched.

## 4. Data model

### 4.1 `realty_group_suspensions` (new)

One row models both suspension (timed) and ban (permanent). Active predicate: `lifted_at IS NULL AND (expires_at IS NULL OR expires_at > now())`.

```sql
CREATE TABLE realty_group_suspensions (
    id                   BIGSERIAL PRIMARY KEY,
    public_id            UUID NOT NULL UNIQUE,
    realty_group_id      BIGINT NOT NULL REFERENCES realty_groups(id),
    issued_by_admin_id   BIGINT NOT NULL REFERENCES users(id),
    reason               VARCHAR(64) NOT NULL,          -- SuspensionReason enum
    notes                TEXT,                          -- admin freeform
    issued_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at           TIMESTAMPTZ,                   -- NULL = permanent (ban)
    lifted_at            TIMESTAMPTZ,
    lifted_by_admin_id   BIGINT REFERENCES users(id),
    lifted_notes         TEXT,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version              BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_rg_susp_lifted_consistency
      CHECK ((lifted_at IS NULL AND lifted_by_admin_id IS NULL)
          OR (lifted_at IS NOT NULL AND lifted_by_admin_id IS NOT NULL))
);

CREATE INDEX ix_rg_susp_active
  ON realty_group_suspensions(realty_group_id)
  WHERE lifted_at IS NULL;

CREATE INDEX ix_rg_susp_expiry_sweep
  ON realty_group_suspensions(expires_at)
  WHERE lifted_at IS NULL AND expires_at IS NOT NULL;
```

`SuspensionReason` enum (Java-side, no DB CHECK for ops flexibility):
`FRAUD`, `REPORTS_RESOLVED_AGAINST`, `TOS_VIOLATION`, `ABUSE`, `OTHER`.

### 4.2 `realty_group_reports` (new)

Mirrors `listing_reports` shape exactly.

```sql
CREATE TABLE realty_group_reports (
    id                   BIGSERIAL PRIMARY KEY,
    public_id            UUID NOT NULL UNIQUE,
    realty_group_id      BIGINT NOT NULL REFERENCES realty_groups(id),
    reporter_user_id     BIGINT NOT NULL REFERENCES users(id),
    reason               VARCHAR(64) NOT NULL,    -- RealtyGroupReportReason
    details              TEXT NOT NULL,
    status               VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    resolved_by_admin_id BIGINT REFERENCES users(id),
    resolved_at          TIMESTAMPTZ,
    resolution_notes     TEXT,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version              BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_rg_reports_status CHECK (
      status IN ('OPEN', 'RESOLVED', 'DISMISSED')
    )
);

-- Only one OPEN report per (group, reporter); RESOLVED/DISMISSED rows
-- don't prevent the same reporter from submitting again later.
CREATE UNIQUE INDEX uq_rg_reports_one_open_per_reporter
  ON realty_group_reports(realty_group_id, reporter_user_id)
  WHERE status = 'OPEN';

CREATE INDEX ix_rg_reports_open_queue
  ON realty_group_reports(realty_group_id)
  WHERE status = 'OPEN';
```

`RealtyGroupReportReason` enum: `FRAUDULENT_LISTINGS`, `MISLEADING_ATTRIBUTION`, `HARASSMENT`, `IMPERSONATION`, `SPAM`, `OTHER`.

### 4.3 `listing_suspensions` (new)

Adds a per-listing audit row so the new bulk-group-suspend pipeline can be distinguished from automatic ownership-monitor suspensions and from existing per-listing admin-suspends. The Epic 10 SUSPENDED status semantics stay unchanged; this table is purely additional audit + a hook for the 48 h timer to find ADMIN_GROUP_BULK rows.

```sql
CREATE TABLE listing_suspensions (
    id                      BIGSERIAL PRIMARY KEY,
    public_id               UUID NOT NULL UNIQUE,
    auction_id              BIGINT NOT NULL REFERENCES auctions(id),
    cause                   VARCHAR(32) NOT NULL,
    suspended_by_admin_id   BIGINT REFERENCES users(id),
    group_suspension_id     BIGINT REFERENCES realty_group_suspensions(id),
    bulk_action_id          UUID,
    reason                  VARCHAR(64),
    notes                   TEXT,
    suspended_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    lifted_at               TIMESTAMPTZ,
    cancelled_at            TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version                 BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_listing_susp_terminal_state
      CHECK (NOT (lifted_at IS NOT NULL AND cancelled_at IS NOT NULL)),
    CONSTRAINT ck_listing_susp_cause CHECK (
      cause IN ('AUTO_OWNERSHIP_CHANGE','AUTO_PARCEL_DELETED',
                'ADMIN_INDIVIDUAL','ADMIN_GROUP_BULK')
    )
);

CREATE INDEX ix_listing_susp_active_bulk
  ON listing_suspensions(auction_id, suspended_at)
  WHERE cause = 'ADMIN_GROUP_BULK'
    AND lifted_at IS NULL
    AND cancelled_at IS NULL;

CREATE INDEX ix_listing_susp_auction
  ON listing_suspensions(auction_id);
```

`cause` values:
- `AUTO_OWNERSHIP_CHANGE` — `SuspensionService.suspendForOwnershipChange` (existing E10).
- `AUTO_PARCEL_DELETED` — `SuspensionService.suspendForDeletedParcel` (existing E10).
- `ADMIN_INDIVIDUAL` — `AdminListingService.suspend` (existing E10; no auto-cancel timer).
- `ADMIN_GROUP_BULK` — new in F; subject to the 48 h auto-cancel timer.

Backfill: each currently-SUSPENDED auction gets a `listing_suspensions` row at migration time inferred from `auction.suspended_at` and the most recent `fraud_flag.reason` for that auction (AUTO_* cause). If no fraud flag exists, infer `ADMIN_INDIVIDUAL`. Currently-ACTIVE auctions get nothing.

### 4.4 `realty_group_sl_groups` cleanup (existing E table)

Removes the unused About-text columns (E shipped both verification methods; we keep founder-terminal only). Adds drift-tracking columns for re-verify.

```sql
ALTER TABLE realty_group_sl_groups
  DROP COLUMN last_polled_at,
  DROP COLUMN poll_attempts;

ALTER TABLE realty_group_sl_groups
  DROP CONSTRAINT ck_rg_sl_groups_verified_via;
ALTER TABLE realty_group_sl_groups
  ADD CONSTRAINT ck_rg_sl_groups_verified_via
    CHECK (verified_via IS NULL OR verified_via = 'FOUNDER_TERMINAL');

UPDATE realty_group_sl_groups
   SET verified_via = 'FOUNDER_TERMINAL'
 WHERE verified_via = 'ABOUT_TEXT';

ALTER TABLE realty_group_sl_groups
  ADD COLUMN last_revalidated_at              TIMESTAMPTZ,
  ADD COLUMN current_founder_uuid             UUID,
  ADD COLUMN consecutive_fetch_failures       INTEGER NOT NULL DEFAULT 0,
  ADD COLUMN drift_detected_at                TIMESTAMPTZ,
  ADD COLUMN drift_reason                     VARCHAR(64),
  ADD COLUMN drift_acknowledged_at            TIMESTAMPTZ,
  ADD COLUMN drift_acknowledged_by_admin_id   BIGINT REFERENCES users(id),
  ADD COLUMN unregistered_at                  TIMESTAMPTZ,
  ADD COLUMN unregistered_by_admin_id         BIGINT REFERENCES users(id),
  ADD COLUMN unregister_reason                VARCHAR(64);

CREATE INDEX ix_rg_sl_groups_drift_open
  ON realty_group_sl_groups(realty_group_id)
  WHERE drift_detected_at IS NOT NULL
    AND drift_acknowledged_at IS NULL;

CREATE INDEX ix_rg_sl_groups_reverify_due
  ON realty_group_sl_groups(last_revalidated_at)
  WHERE verified = true;

DROP INDEX IF EXISTS ix_rg_sl_groups_pending_poll;
```

`drift_reason` values (Java-side):
- `GROUP_NOT_FOUND` — `world.secondlife.com/group/{uuid}` returned 404.
- `FOUNDER_CHANGED` — `current_founder_uuid != founder_avatar_uuid` (snapshot from registration).
- `FETCH_FAILED_REPEATEDLY` — `consecutive_fetch_failures >= slpa.realty.sl-group.reverify-fetch-failure-threshold` (default 3).

### 4.5 `fraud_flags.entity_type` CHECK widening

```sql
ALTER TABLE fraud_flags DROP CONSTRAINT fraud_flags_entity_type_check;
ALTER TABLE fraud_flags ADD CONSTRAINT fraud_flags_entity_type_check CHECK (
    entity_type IN ('USER', 'LISTING', 'REALTY_GROUP')
);
```

### 4.6 `admin_actions.action_type` CHECK widening

Via `AdminActionTypeCheckConstraintInitializer` (existing runtime pattern — not a Flyway migration line). New values added:

- `REALTY_GROUP_SUSPEND`
- `REALTY_GROUP_UNSUSPEND`
- `REALTY_GROUP_BAN`
- `REALTY_GROUP_UNBAN`
- `REALTY_GROUP_FRAUD_FLAG`
- `REALTY_GROUP_REPORT_RESOLVE`
- `REALTY_GROUP_REPORT_DISMISS`
- `REALTY_GROUP_BULK_SUSPEND`
- `REALTY_GROUP_BULK_REINSTATE`
- `REALTY_GROUP_BULK_SUSPEND_EXPIRY_RUN` (system actor; one row per `BulkSuspendedListingExpiryTask` execution)
- `REALTY_GROUP_SL_GROUP_FORCE_UNREGISTER`
- `REALTY_GROUP_SL_GROUP_DRIFT_ACK`
- `REALTY_GROUP_SL_GROUP_RECHECK`

## 5. Permissions

### 5.1 No new `RealtyGroupPermission` values

F is admin-side. Admin actions go through the `ROLE_ADMIN` global authority — they don't pass through the per-group permission flag. The one leader-side surface (bulk commission edit) reuses the existing `MANAGE_MEMBERS` permission.

### 5.2 Suspension enforcement (`RealtyGroupGuard`)

A new `@Component` exposes `requireGroupCanOperate(realtyGroupId)` and throws `RealtyGroupSuspendedException` (HTTP 409) when an active suspension row exists. Called from:

- `RealtyGroupListingService.createListingForGroup` (C/E)
- `RealtyGroupWalletService.withdrawToLeader` / `withdrawToSlGroup` (D/E)
- `RealtyGroupMembershipService.invite` / `acceptInvitation` / `removeMember` / `updatePermissions` / `editCommissionRate` (A+B/E)
- `RealtyGroupSlGroupService.register` (E)

The exception body carries `{ status: "SUSPENDED" | "BANNED", expiresAt: ISO_DATE | null, reason: string }`. Public reads stay unblocked — the public group page deliberately stays visible (admins can publicize that a group is under moderation via the status pill).

### 5.3 Cache invalidation

`RealtyGroupSuspensionService.issue` / `lift` evict the existing realty-group cache (`@CacheEvict`), and additionally bump a Redis key `realty_group_suspended:{groupId}` with the suspension's expiry. `RealtyGroupGuard` short-circuits on this key (5 min TTL — mirrors the User ban-cache posture documented in FOOTGUNS §F.106).

## 6. API surface

### 6.1 Public

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/v1/realty-groups/{publicId}/reports` | Submit a report. Body: `{reason, details}`. Auth required; rate-limited. |

### 6.2 Admin — group moderation

| Method | Path | Purpose |
|---|---|---|
| GET    | `/api/v1/admin/realty-groups/{publicId}/suspensions` | List suspension history |
| POST   | `/api/v1/admin/realty-groups/{publicId}/suspensions` | Issue suspension. Body: `{reason, notes, expiresAt nullable, bulkSuspendListings: bool}`. `expiresAt=null` → permanent ban. |
| DELETE | `/api/v1/admin/realty-groups/{publicId}/suspensions/{suspensionPublicId}` | Lift suspension. Body: `{notes, bulkReinstateListings: bool}` |

### 6.3 Admin — bulk listing actions

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/v1/admin/realty-groups/{publicId}/listings/suspend-all` | Body: `{reason, notes, groupSuspensionPublicId nullable}` |
| POST | `/api/v1/admin/realty-groups/{publicId}/listings/reinstate-all` | Body: `{notes}` |

### 6.4 Admin — fraud flags

Reuses existing `POST /api/v1/admin/fraud-flags` with `entityType=REALTY_GROUP`. No new endpoint.

### 6.5 Admin — group reports

| Method | Path | Purpose |
|---|---|---|
| GET  | `/api/v1/admin/realty-groups/reports` | Paginated queue, filter by status |
| GET  | `/api/v1/admin/realty-groups/reports/{publicId}` | Detail |
| POST | `/api/v1/admin/realty-groups/reports/{publicId}/resolve` | Body: `{notes, escalateTo: SUSPEND_GROUP \| BAN_GROUP \| null}` (escalation is a front-end-coordinated separate POST; backend just resolves) |
| POST | `/api/v1/admin/realty-groups/reports/{publicId}/dismiss` | Increments `User.dismissedReportsCount` |

### 6.6 Admin — SL group admin

| Method | Path | Purpose |
|---|---|---|
| POST   | `/api/v1/admin/realty-groups/{publicId}/sl-groups/{slGroupPublicId}/recheck` | Sync re-fetch + drift eval |
| POST   | `/api/v1/admin/realty-groups/{publicId}/sl-groups/{slGroupPublicId}/ack-drift` | Clears drift, refreshes founder snapshot |
| DELETE | `/api/v1/admin/realty-groups/{publicId}/sl-groups/{slGroupPublicId}?force=true` | Force-unregister; cascades to bulk-suspend |

### 6.7 Leader — bulk commission edit

| Method | Path | Purpose |
|---|---|---|
| PATCH | `/api/v1/realty-groups/{publicId}/members/commission-rates` | Body: `{ memberRates: [{memberPublicId, rate}] }`. Atomic. Permission: `MANAGE_MEMBERS`. |

### 6.8 Leader — commission analytics

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/v1/realty-groups/{publicId}/analytics/commissions` | Per-member lifetime + last-30 d AGENT_COMMISSION_CREDIT totals. Permission: leader OR `MANAGE_MEMBERS`. |

### 6.9 Audit filter

Existing `GET /api/v1/admin/audit` accepts new query parameters `entityType=REALTY_GROUP` (matches any of the 12 new action types) and `entityId={groupPublicId}`. No new endpoint.

## 7. Frontend surface

| Path | Notes |
|---|---|
| `/realty/groups/[publicId]` (existing public) | Adds `GroupStatusPill`, `GroupRatingBadge`, "Report group" button → `ReportGroupModal` |
| `/realty/groups/[publicId]/analytics/commissions` (new) | Leader / `MANAGE_MEMBERS` only; per-member L$ totals |
| `/admin/realty-groups/[publicId]` (existing) | Adds tabs: Suspensions, Reports, Bulk-Listings, SL-Groups, Audit |
| `/admin/realty-groups/reports` (new) | Paginated report queue |

Component-level additions:

- `GroupStatusPill` — `Active` / `Suspended until <date>` / `Banned`; tooltip carries reason.
- `GroupRatingBadge` — `4.2 stars (12 reviews)`. Links to leader's user-reviews page as a stopgap (a dedicated group-reviews page is deferred).
- `AdminGroupSuspensionsTab` — list + "Issue suspension/ban" button.
- `AdminGroupSuspensionModal` — issue form with timed/permanent toggle + optional "also bulk-suspend listings" checkbox.
- `AdminGroupBulkListingsTab` — "Suspend all active listings" + "Reinstate all" buttons + list view of `ADMIN_GROUP_BULK`-cause suspensions with their countdown to the 48 h timer.
- `AdminGroupReportsTab` — per-group report list, scoped to one group.
- `AdminGroupReportsQueuePage` — global queue at `/admin/realty-groups/reports`.
- `AdminGroupSlGroupsTab` — list of SL group registrations with drift status + recheck/ack/force-unregister actions.
- `AdminGroupAuditTab` — pre-filtered audit log.
- `ReportGroupModal` — public-facing report submission.
- `BulkMemberCommissionEditDrawer` — leader-side bulk commission edit on the Members tab.
- `GroupCommissionAnalyticsPage` — table + bar chart.
- `AdminSlGroupDriftRow` — surface drift-detected registrations with action buttons.

Pages: all admin pages `export const dynamic = "force-dynamic"`. Public group page is the existing one with additive overlays.

MSW handler additions: `realtyGroupSuspensionHandlers`, `realtyGroupReportHandlers`, `bulkSuspendListingsHandlers`, `slGroupAdminHandlers`, `bulkCommissionHandlers`, `commissionAnalyticsHandlers`.

## 8. Backend services

| Service | Slice | Responsibilities |
|---|---|---|
| `RealtyGroupSuspensionService` | 1 | `issue`, `lift`, `findActive`, `listHistory`. Cache evict + Redis hash bump on writes. |
| `RealtyGroupGuard` | 1 | `requireGroupCanOperate(groupId)` — short-circuits on Redis hash; falls back to DB. |
| `GroupSuspensionExpiryTask` | 1 | `@Scheduled(fixedRate = 1h)` — synthesizes `lifted_at = expires_at` + `lifted_by_admin_id = SYSTEM_USER` for timed suspensions past their `expires_at`. |
| `BulkListingSuspendService` | 2 | `suspendAll(groupId, adminId, reason, linkedGroupSuspensionId?)`, `reinstateAll(groupId, adminId)`. Writes `listing_suspensions` rows + flips `Auction.status`. |
| `BulkSuspendedListingExpiryTask` | 2 | `@Scheduled(fixedRate = 1h)` — finds `ADMIN_GROUP_BULK` rows older than configured-hours, force-cancels via `CancellationService.adminCancelExpiredBulkSuspend`. |
| `RealtyGroupReportService` | 4 | `submit`, `resolve`, `dismiss`. Mirrors `ListingReportService`. |
| `RealtyGroupReportRateLimiter` | 4 | Redis-backed per-reporter rate limiter; 5 reports/day across all report types. |
| `SlGroupReverifyService` | 5 | `recheck(slGroupId)` — sync re-fetch + drift evaluation. Shared by scheduled task and admin endpoint. |
| `SlGroupReverifyTask` | 5 | `@Scheduled(fixedRate = 1h)` — picks groups due for revalidation per the configured cadence. |
| `SlGroupForceUnregisterService` | 5 | Force-unregister + cascade to `BulkListingSuspendService`. |
| `RealtyGroupBulkCommissionService` | 5 | Atomic batch rate update; one bad row rolls back the batch. |
| `GroupCommissionAnalyticsService` | 5 | Read-side aggregation of `AGENT_COMMISSION_CREDIT` ledger rows. |
| `GroupRatingService` | (reputation) | Read-side aggregation of `Review` data; 1 h Redis cache. |

**Cross-service transactions**:

- `RealtyGroupSuspensionService.issue(..., bulkSuspendListings=true)` runs the suspension insert + bulk listing suspend in one `@Transactional`. The `bulk_action_id` (UUID) is set on both `realty_group_suspensions.public_id` and every linked `listing_suspensions.bulk_action_id` so reinstate-all can find the cascade group later.
- `SlGroupForceUnregisterService.forceUnregister(...)` runs the unregister + cascade bulk-suspend in one transaction.
- `RealtyGroupBulkCommissionService.update(...)` runs the rate updates in one transaction; validation failure for any single member rate aborts the whole batch.

## 9. Group moderation entity — flow

### 9.1 Issue suspension

```http
POST /api/v1/admin/realty-groups/{publicId}/suspensions
Authorization: Bearer <admin-jwt>

{
  "reason": "FRAUD",
  "notes": "Multiple reports of misrepresented parcel sizes",
  "expiresAt": "2026-06-12T00:00:00Z",      // null = permanent ban
  "bulkSuspendListings": true
}
```

Service:

1. Resolve group; assert ROLE_ADMIN.
2. Reject with 409 `SUSPENSION_ALREADY_ACTIVE` if an active row already exists.
3. Insert `realty_group_suspensions` row.
4. If `bulkSuspendListings=true`: call `BulkListingSuspendService.suspendAll(...)` with `linkedGroupSuspensionId = suspension.id`.
5. Write `admin_actions` row (`REALTY_GROUP_SUSPEND` or `REALTY_GROUP_BAN`).
6. Cache evict + Redis bump.
7. Publish notification to all members: "Your group has been suspended/banned by SLParcels admin. Reason: <reason>".

Response: `201` with the created suspension DTO (including `publicId` for the bulk-action link).

### 9.2 Lift suspension

```http
DELETE /api/v1/admin/realty-groups/{publicId}/suspensions/{suspensionPublicId}

{
  "notes": "Reports investigated; insufficient evidence",
  "bulkReinstateListings": true
}
```

Service:

1. Update `lifted_at = now`, `lifted_by_admin_id`, `lifted_notes`.
2. If `bulkReinstateListings=true`: find all `listing_suspensions` where `group_suspension_id = suspension.id AND lifted_at IS NULL AND cancelled_at IS NULL`, call `AdminAuctionService.reinstate(auctionId, Optional.of(suspendedAt))` for each. The existing reinstate logic extends `endsAt` by the suspension duration.
3. Write `admin_actions` row (`REALTY_GROUP_UNSUSPEND` or `REALTY_GROUP_UNBAN`).
4. Cache evict + Redis bump.
5. Notify members.

**Listings that were force-cancelled by the 48 h timer are NOT resurrected.** They're gone. Bidders were refunded. Admin would need to ask the seller to relist.

### 9.3 Automatic expiry

`GroupSuspensionExpiryTask` runs hourly:

```sql
SELECT id, expires_at FROM realty_group_suspensions
 WHERE lifted_at IS NULL
   AND expires_at IS NOT NULL
   AND expires_at < now();
```

For each: `UPDATE realty_group_suspensions SET lifted_at = expires_at, lifted_by_admin_id = (SYSTEM_USER_ID), lifted_notes = 'Auto-lifted on expiry'`. No bulk reinstate — listings linked to an expired suspension either:

- Already auto-cancelled (48 h timer fired before suspension's `expires_at`), or
- Still suspended (suspension's `expires_at` came before the 48 h listing timer).

If still suspended, admins explicitly reinstate them. The expiry job only handles the group-level row.

### 9.4 Permanent ban semantics

`expires_at = NULL`. Expiry job ignores it. A ban can be lifted via the `DELETE` endpoint, but the intent is terminal — lifting is rare and exceptional. Status pill renders "Banned" with no countdown.

## 10. Bulk listing suspend + 48 h auto-cancel

### 10.1 `BulkListingSuspendService.suspendAll(...)`

1. Find all `AuctionStatus.ACTIVE` listings where `realtyGroupId = groupId` OR (`realtyGroupSlGroupId IS NOT NULL` AND the SL-group's `realtyGroupId = groupId`). Covers case-1 (legacy) and case-3 (E) auctions.
2. Generate `bulkActionUuid = UUID.randomUUID()`.
3. For each:
   - Write `listing_suspensions` row: `cause=ADMIN_GROUP_BULK`, `bulk_action_id`, `group_suspension_id`, `suspended_by_admin_id`, `reason`.
   - `auction.setStatus(SUSPENDED)`; `auction.setSuspendedAt(now)`.
   - `BotMonitorLifecycleService.onAuctionClosed(auction)` — stops the ownership monitor from touching it during the freeze.
4. Publish `LISTING_SUSPENDED` to each seller via the existing `NotificationPublisher.listingSuspended(sellerUserId, auctionId, parcelName, reason)` (the `reason` argument carries `ADMIN_GROUP_BULK_SUSPEND`).
5. Write `admin_actions` row (`REALTY_GROUP_BULK_SUSPEND`) with `evidenceJson = { count, bulkActionId }`.
6. Return `{ bulkActionId, suspendedCount }`.

**Skip rules:**

- Auction already `SUSPENDED` (e.g., by ownership monitor) — skip; don't write a second `listing_suspensions` row.
- Auction not `ACTIVE` (ended between query and update) — skip; log.

### 10.2 `BulkSuspendedListingExpiryTask`

`@Scheduled(fixedRate = 1h)`:

```sql
SELECT ls.* FROM listing_suspensions ls
 WHERE ls.cause = 'ADMIN_GROUP_BULK'
   AND ls.lifted_at IS NULL
   AND ls.cancelled_at IS NULL
   AND ls.suspended_at < now() - :auto_cancel_hours * INTERVAL '1 hour';
```

For each: call `CancellationService.adminCancelExpiredBulkSuspend(auctionId, listingSuspensionId)`:

1. Resolve auction (`findByIdForUpdate`).
2. Refund all reserved bids (existing cancel pathway).
3. Write `cancellation_log` row with `penaltyKind = ADMIN_BULK_EXPIRED` (new value — excluded from the seller-penalty ladder; broker-cancel-like exclusion via the existing `CancellationOffenseKind` mechanism).
4. `auction.setStatus(CANCELLED)`.
5. `listing_suspensions.setCancelledAt(now)`.
6. Bidder fan-out via existing `NotificationPublisher.listingCancelledBySellerFanout(auctionId, activeBidderUserIds, ...)` — cause-neutral copy per FOOTGUNS §F.104; bidders don't see admin attribution. Seller-side notification reuses the same fan-out (seller appears in `activeBidderUserIds` when seller-self-bid edge cases are excluded; otherwise the seller is notified via a separate `LISTING_SUSPENDED`-reason-amended call — F adds a `NotificationPublisher.listingAutoCancelledFromBulkSuspend(sellerUserId, auctionId, parcelName)` helper that fans out the seller copy with the specific `BULK_SUSPEND_TIMER_EXPIRED` reason).

One batched `admin_actions` row per task execution (not per listing): `REALTY_GROUP_BULK_SUSPEND_EXPIRY_RUN` with `evidenceJson = { cancelledCount }`, actor = SYSTEM_USER.

### 10.3 `BulkListingSuspendService.reinstateAll(...)`

1. Find all `listing_suspensions` rows for the group via:

```sql
SELECT ls.* FROM listing_suspensions ls
  JOIN auctions a ON a.id = ls.auction_id
  LEFT JOIN realty_group_sl_groups rsg ON rsg.id = a.realty_group_sl_group_id
 WHERE ls.cause = 'ADMIN_GROUP_BULK'
   AND ls.lifted_at IS NULL
   AND ls.cancelled_at IS NULL
   AND (a.realty_group_id = :groupId OR rsg.realty_group_id = :groupId);
```

   This covers both case-1 (legacy direct `realty_group_id`) and case-3 (via the SL group registration).
2. For each: call `AdminAuctionService.reinstate(auctionId, Optional.of(suspendedAt))`. Existing logic flips to ACTIVE and extends `endsAt` by suspension duration.
3. Mark `listing_suspensions.lifted_at = now`.
4. Write batched `admin_actions` (`REALTY_GROUP_BULK_REINSTATE`).

### 10.4 Configuration

```yaml
slpa:
  realty:
    group-bulk-suspend:
      auto-cancel-hours: 48          # default; tunable per env
      enabled: true                  # disables both the bulk-suspend write side and the expiry task in tests
```

## 11. Group fraud flagging

The smallest slice. The existing `AdminFraudFlagService` is already polymorphic over `entity_type`. F adds:

1. `FraudFlagEntityKind.REALTY_GROUP` to the enum.
2. Widen `fraud_flags.entity_type` CHECK (see §4.5).
3. Reuse the existing flag-issue UI on `AdminRealtyGroupDetailPage.tsx` — drop in `<FraudFlagSection entityType="REALTY_GROUP" entityId={group.publicId} />`.
4. Three new `FraudFlagReason` enum values scoped to group context: `REALTY_GROUP_FRAUDULENT_LISTINGS`, `REALTY_GROUP_IMPERSONATION`, `REALTY_GROUP_REPEATED_REPORTS`.

No public surface — fraud flags are admin-internal. The flagged group has no UI for its own flags.

## 12. Reports against groups

### 12.1 User submission

Logged-in user on the public group page clicks "Report group" → modal opens:

- Reason dropdown (`RealtyGroupReportReason` enum, friendly labels).
- `details` textarea (10–2000 chars).
- Submit.

Backend (`POST /api/v1/realty-groups/{publicId}/reports`):

1. Resolve reporter from JWT.
2. Reject 409 `CANNOT_REPORT_OWN_GROUP` if reporter is a member of the target group.
3. Reject 409 `ALREADY_REPORTED` if reporter has an OPEN row for this group (enforced by `uq_rg_reports_one_open_per_reporter` partial index — service catches the unique violation and translates).
4. Rate limit: 5 reports/day per reporter across all report types (`RealtyGroupReportRateLimiter` shares state with the existing listing-report limiter). 429 `REPORT_RATE_LIMITED`.
5. Insert `realty_group_reports` row with `status = OPEN`.
6. Return `201` with the report's `publicId`.

### 12.2 Admin queue (`/admin/realty-groups/reports`)

Paginated, default sort `created_at DESC` for OPEN reports. Filters: status (all / open / resolved / dismissed), group name search.

Row shows: group name, reporter display name, reason, age, first 80 chars of `details`.

### 12.3 Resolve / dismiss

```http
POST /api/v1/admin/realty-groups/reports/{publicId}/resolve

{ "notes": "...", "escalateTo": null }
```

- Resolve: `status = RESOLVED`, `resolved_by_admin_id`, `resolved_at`, `resolution_notes`. `escalateTo` value is **informational only** — the frontend uses it to know whether to immediately open the suspension/ban modal as a follow-up. Backend just resolves.
- Dismiss: `status = DISMISSED` + `User.dismissedReportsCount += 1` for the reporter. Same counter Epic 10 uses for listing-report dismissals.

Both write `admin_actions` (REALTY_GROUP_REPORT_RESOLVE / REALTY_GROUP_REPORT_DISMISS).

### 12.4 Reporter visibility

Reuse the existing `/me/reports` page (Epic 10) — extends the existing `ListingReportRowDto` query to UNION with `realty_group_reports` rows, common envelope: `{ entityType: "LISTING" | "REALTY_GROUP", entityPublicId, reason, status, createdAt }`. Reporter sees both report types in one timeline.

## 13. SL group re-verify + drift handling

### 13.1 `SlGroupReverifyTask` (`@Scheduled(fixedRate = 1h)`)

```sql
SELECT * FROM realty_group_sl_groups
 WHERE verified = true
   AND (last_revalidated_at IS NULL
        OR now() - last_revalidated_at > :cadence_days * INTERVAL '1 day');
```

For each: call `SlGroupReverifyService.recheck(slGroupId)`.

### 13.2 `SlGroupReverifyService.recheck(slGroupId)`

1. Re-fetch `world.secondlife.com/group/{slGroupUuid}` via `SlWorldApiClient.fetchGroupPage`.
2. On `404`: set `drift_detected_at`, `drift_reason = GROUP_NOT_FOUND`. Don't unregister.
3. On fetch failure (5xx / network error): increment `consecutive_fetch_failures`. If `consecutive_fetch_failures >= slpa.realty.sl-group.reverify-fetch-failure-threshold` (default 3): set `drift_detected_at`, `drift_reason = FETCH_FAILED_REPEATEDLY`. Do not update `last_revalidated_at` on failure.
4. On success:
   - Reset `consecutive_fetch_failures = 0`.
   - `last_revalidated_at = now()`.
   - `current_founder_uuid = parsed.founderUuid`.
   - If `parsed.founderUuid != founder_avatar_uuid AND drift_detected_at IS NULL`: set `drift_detected_at`, `drift_reason = FOUNDER_CHANGED`.
5. On drift detection: publish notification to the realty group's leader: "Your registered SL group <name> has changed in-world. SLParcels admin will review."

### 13.3 Admin "Recheck now"

`POST /api/v1/admin/realty-groups/{publicId}/sl-groups/{slGroupPublicId}/recheck` calls `SlGroupReverifyService.recheck(...)` synchronously, returns the evaluation result. Same drift semantics as the scheduled task. Writes `admin_actions` (`REALTY_GROUP_SL_GROUP_RECHECK`).

### 13.4 Admin drift acknowledgment

`POST .../ack-drift`:

1. Resolve registration; reject 409 `NO_DRIFT_DETECTED` if `drift_detected_at IS NULL`.
2. Clear `drift_detected_at` + `drift_reason`. Set `drift_acknowledged_at = now`, `drift_acknowledged_by_admin_id = adminId`.
3. If `current_founder_uuid != founder_avatar_uuid AND current_founder_uuid IS NOT NULL`: update `founder_avatar_uuid = current_founder_uuid` (refresh snapshot).
4. Write `admin_actions` (`REALTY_GROUP_SL_GROUP_DRIFT_ACK`).

### 13.5 Admin force-unregister

`DELETE .../sl-groups/{slGroupPublicId}?force=true`:

1. Resolve registration; assert ROLE_ADMIN.
2. Find all `AuctionStatus.ACTIVE` case-3 auctions on this SL group: `auction.realtyGroupSlGroupId = registration.id AND auction.status = ACTIVE`.
3. If any exist: call `BulkListingSuspendService.suspendAll(...)` with `reason = SL_GROUP_FORCE_UNREGISTER`. 48 h auto-cancel timer applies — admin has 48 h to decide per-listing cancel-or-resolve.
4. Mark the registration row as unregistered (set `unregistered_at` — column carried from E or added here if not).
5. Write `admin_actions` (`REALTY_GROUP_SL_GROUP_FORCE_UNREGISTER`).

### 13.6 Configuration

```yaml
slpa:
  realty:
    sl-group:
      reverify-cadence-days: 30                  # default
      reverify-fetch-failure-threshold: 3        # default
      reverify:
        enabled: true                            # for test disabling
```

## 14. Parser fixes + About-text path removal

### 14.1 Parser fixes

Today's `SlWorldApiClient.parseGroupHtml` selectors miss real HTML:

- `parseGroupName` — current: `meta[name="groupname"]` then `.groupname, .group-name, h1.groupname, h1.group-name`. Real markup: `<h1>` inside `.details` with no class. **Fix:** `Document#selectFirst(".details h1")`; fallback to `<title>` text content (stripped of any prefix/suffix).
- `parseGroupAboutText` — current: `meta[name="charter"]` then `.groupcharter, .group-charter, .charter, .about`. Real markup: `<p class="desc">` inside `.details`. **Fix:** `Document#selectFirst(".details p.desc")`; null when empty.
- `parseGroupFounderUuid` — current: `meta[name="founderid"]`. **Unchanged** — matches real markup.

### 14.2 Fixture-based test

Move `.scratch/sl-group-page.html` (the live capture from `world.secondlife.com/group/79f06955-38f4-3124-25b3-f5506c85828f`, group "SLParcels" — verified during brainstorm) to `backend/src/test/resources/sl/group-page-slparcels.html`. Add a second fixture `backend/src/test/resources/sl/group-page-with-charter.html` — hand-crafted variant with a non-empty `<p class="desc">` so the about-text path has positive-case coverage.

New `SlWorldApiClientGroupParserTest` asserts:

- Fixture 1 (`group-page-slparcels.html`): name = `"SLParcels"`, founderUuid = `aa87bc38-c175-427d-b665-02e6838963cc`, aboutText = `null` or empty string.
- Fixture 2 (`group-page-with-charter.html`): name = expected, founderUuid = expected, aboutText = expected charter content.

### 14.3 About-text verification path removal

1. Delete `SlGroupAboutTextPollTask.java` + its tests.
2. Drop `SlGroupVerifyMethod.ABOUT_TEXT` enum value.
3. Drop the unused columns + tighten CHECK (see §4.4).
4. Update `UPDATE realty_group_sl_groups SET verified_via = 'FOUNDER_TERMINAL' WHERE verified_via = 'ABOUT_TEXT'` (migration-time data normalization — any historically about-text-verified row gets re-labelled as founder-terminal; verification stays valid but the audit becomes slightly fictionalized).
5. `RegisterSlGroupRequest`: drop the `method` field. Backend implicitly uses `FOUNDER_TERMINAL`.
6. `RegisterSlGroupModal.tsx`: drop the method radio group. Flow becomes single-step: "register → backend returns code → take it to a SLPA terminal with the SL group's founder".
7. `SlGroupVerificationInstructionsCard`: keep only the founder-terminal copy.
8. FOOTGUNS entry documenting the audit-trail fictionalization.

## 15. Bulk commission edit + commission analytics

### 15.1 Bulk commission edit

`PATCH /api/v1/realty-groups/{publicId}/members/commission-rates`:

```json
{
  "memberRates": [
    { "memberPublicId": "uuid-1", "rate": "0.0750" },
    { "memberPublicId": "uuid-2", "rate": "0.1000" }
  ]
}
```

Service:

1. Resolve group; assert caller has `MANAGE_MEMBERS` permission.
2. `RealtyGroupGuard.requireGroupCanOperate(groupId)` — suspended groups can't edit.
3. For each row:
   - Resolve member by publicId; reject `MEMBER_NOT_IN_GROUP` if absent.
   - Validate `rate >= 0` (no upper cap per E feedback memory).
   - Update `realty_group_members.agent_commission_rate`.
4. All in one `@Transactional`. Any single validation failure rolls back the whole batch.
5. Write `admin_actions` is not appropriate here — this is leader action, not admin. Consider a `realty_group_actions` table later (deferred); for F, the action is captured implicitly via the entity row's `updatedAt`.

UI: `BulkMemberCommissionEditDrawer` on the existing Members tab. One row per member with `CommissionRateInput` (existing component from E) + a "Save all" button.

### 15.2 Commission analytics

`GET /api/v1/realty-groups/{publicId}/analytics/commissions`:

```sql
SELECT
    u.public_id,
    u.display_name,
    COALESCE(SUM(ul.amount), 0) AS lifetime,
    COALESCE(SUM(ul.amount) FILTER (
        WHERE ul.created_at > now() - INTERVAL '30 days'
    ), 0) AS last_30_days
  FROM realty_group_members m
  JOIN users u ON u.id = m.user_id
  LEFT JOIN user_ledger ul
    ON ul.user_id = m.user_id
   AND ul.entry_type = 'AGENT_COMMISSION_CREDIT'
   AND ul.idempotency_key LIKE 'AGCOMM-%'
   AND EXISTS (
     SELECT 1 FROM auctions a
      WHERE 'AGCOMM-' || a.id = ul.idempotency_key
        AND (a.realty_group_id = m.realty_group_id
             OR EXISTS (
               SELECT 1 FROM realty_group_sl_groups rsg
                WHERE rsg.id = a.realty_group_sl_group_id
                  AND rsg.realty_group_id = m.realty_group_id
             ))
   )
 WHERE m.realty_group_id = :groupId
 GROUP BY u.public_id, u.display_name
 ORDER BY lifetime DESC;
```

Permission: leader OR `MANAGE_MEMBERS`.

UI: table sorted by lifetime desc + horizontal bar chart (simple Tailwind-divs; no chart library). Empty state: "No commissions paid out yet."

## 16. Group reputation/rating display

### 16.1 Aggregation

`GroupRatingService.computeRating(groupId)`:

```sql
SELECT AVG(r.star_rating) AS avg_rating, COUNT(*) AS review_count
  FROM reviews r
  JOIN auctions a ON a.id = r.auction_id
 WHERE a.realty_group_id = :groupId
    OR EXISTS (
      SELECT 1 FROM realty_group_sl_groups rsg
       WHERE rsg.id = a.realty_group_sl_group_id
         AND rsg.realty_group_id = :groupId
    );
```

Both case-1 (legacy) and case-3 (E) coverage.

### 16.2 Cache

Redis key `realty_groups_rating:{groupId}`. TTL 1 h. Invalidated by an event listener on new Review creation when `review.auction.realtyGroupId != null OR review.auction.realtyGroupSlGroupId != null`.

### 16.3 Display

`GroupRatingBadge` component: "★★★★☆ 4.2 (12 reviews)". Renders on:
- Public group page header.
- Admin realty-groups list (column).
- Admin group detail (badge in header).

Stopgap: badge links to `/users/{leaderPublicId}/reviews` — a dedicated group-reviews page is deferred (see §20).

## 17. Audit log filter

Existing `GET /api/v1/admin/audit` adds:
- `entityType=REALTY_GROUP` — filter to the 12 new `AdminActionType` values listed in §4.6.
- `entityId={groupPublicId}` — narrow to one group's actions.

Frontend: `/admin/audit` adds an entityType dropdown; `/admin/realty-groups/[publicId]` gets an "Audit" tab pre-filtered to that group.

## 18. Edge cases & error handling

| Case | Handling |
|---|---|
| Suspend a group with no active listings | Allowed; bulk cascade is no-op (returns count=0). |
| Suspend an already-suspended group | 409 `SUSPENSION_ALREADY_ACTIVE`. |
| Lift non-existent or already-lifted suspension | 404 / 409. |
| Force-unregister SL group with 0 case-3 listings | Standard non-force unregister works; `force=true` accepted (no-op cascade). |
| Bulk expiry task race with admin reinstate | `findByIdForUpdate` pessimistic lock; first writer wins. |
| User reports a banned group | Allowed (useful for ban evidence). |
| Reporter exceeds rate limit | 429 with retry-after. |
| Admin tries to suspend a dissolved group | 409 `GROUP_DISSOLVED`. |
| `SlGroupReverifyTask` fetch transient failure | Increment `consecutive_fetch_failures`; only flag drift after threshold. Reset on success. Don't update `last_revalidated_at` on failure. |
| Drift detected but admin force-unregistered first | Drift state stays in row; admin can ack or ignore (no longer verified). |
| Bulk commission edit references a non-member | 400 `MEMBER_NOT_IN_GROUP`; entire batch rolls back. |
| Two admins simultaneously bulk-suspend the same group | Pessimistic lock on the group row in suspension service; second admin gets 409. |
| User reports their own group | 409 `CANNOT_REPORT_OWN_GROUP`. |
| Auction was ACTIVE at query time but ended by update time | Skip; log. |
| Reinstate-all of a group whose listings were all auto-cancelled | No-op (no `ADMIN_GROUP_BULK` rows left in the lifted_at=null/cancelled_at=null state). |
| Force-cancel from 48 h timer publishes to bidders | Use `LISTING_CANCELLED_BY_SELLER` envelope (cause-neutral per FOOTGUNS §F.104) — bidders don't see admin attribution. Sellers get explicit `BULK_SUSPEND_TIMER_EXPIRED` reason. |

## 19. Testing strategy

### 19.1 Backend slice tests

One per service:

- `RealtyGroupSuspensionServiceTest`
- `RealtyGroupGuardTest`
- `GroupSuspensionExpiryTaskTest`
- `BulkListingSuspendServiceTest`
- `BulkSuspendedListingExpiryTaskTest`
- `RealtyGroupReportServiceTest`
- `RealtyGroupReportRateLimiterTest`
- `SlGroupReverifyTaskTest`
- `SlGroupReverifyServiceTest`
- `SlGroupForceUnregisterServiceTest`
- `RealtyGroupBulkCommissionServiceTest`
- `GroupCommissionAnalyticsServiceTest`
- `GroupRatingServiceTest`
- `SlWorldApiClientGroupParserTest` (the parser bug fix)

### 19.2 Backend controller slice tests

- `AdminRealtyGroupSuspensionControllerSliceTest`
- `AdminRealtyGroupBulkListingsControllerSliceTest`
- `RealtyGroupReportControllerSliceTest`
- `AdminRealtyGroupReportControllerSliceTest`
- `AdminSlGroupControllerSliceTest`
- `RealtyGroupCommissionControllerSliceTest`
- `RealtyGroupAnalyticsControllerSliceTest`

### 19.3 Integration tests

- `RealtyGroupGuardIntegrationTest` — exercises the gate via real C/D/E entry points with an active suspension row.
- `BulkSuspendExpiryIntegrationTest` — `@SpringBootTest` with manual clock advance to confirm the 48 h timer fires.

### 19.4 Frontend tests

Each new component gets a `.test.tsx` mirroring existing patterns:
- `GroupStatusPill.test.tsx`
- `GroupRatingBadge.test.tsx`
- `AdminGroupSuspensionsTab.test.tsx`
- `AdminGroupSuspensionModal.test.tsx`
- `AdminGroupBulkListingsTab.test.tsx`
- `AdminGroupReportsTab.test.tsx`
- `AdminGroupReportsQueuePage.test.tsx`
- `ReportGroupModal.test.tsx`
- `AdminSlGroupDriftRow.test.tsx`
- `BulkMemberCommissionEditDrawer.test.tsx`
- `GroupCommissionAnalyticsPage.test.tsx`

Pages: `/admin/realty-groups/reports/page.test.tsx`, `/realty/groups/[publicId]/analytics/commissions/page.test.tsx`.

### 19.5 Coverage + verify

Maintain existing per-package coverage thresholds. Frontend verify guards (no-dark-variants, no-hex-colors, no-inline-styles, coverage) must all be green for PR merge.

## 20. Migration / cutover

### 20.1 V28 migration

Single file: `V28__realty_group_admin_moderation.sql`. Contains:

1. `CREATE TABLE realty_group_suspensions` (§4.1).
2. `CREATE TABLE realty_group_reports` (§4.2).
3. `CREATE TABLE listing_suspensions` (§4.3).
4. Backfill `listing_suspensions` rows from existing `AuctionStatus.SUSPENDED` auctions, inferring cause from `fraud_flags.reason` (or `ADMIN_INDIVIDUAL` if no flag).
5. `ALTER TABLE realty_group_sl_groups` — drop unused columns, add drift + unregister columns, tighten CHECK (§4.4).
6. `UPDATE realty_group_sl_groups SET verified_via = 'FOUNDER_TERMINAL' WHERE verified_via = 'ABOUT_TEXT'`.
7. Widen `fraud_flags.entity_type` CHECK (§4.5).
8. Widen `cancellation_logs.penalty_kind` CHECK to admit `ADMIN_BULK_EXPIRED` (the new `CancellationOffenseKind` value used by `CancellationService.adminCancelExpiredBulkSuspend`):
   ```sql
   ALTER TABLE cancellation_logs DROP CONSTRAINT IF EXISTS cancellation_logs_penalty_kind_check;
   ALTER TABLE cancellation_logs ADD CONSTRAINT cancellation_logs_penalty_kind_check CHECK (
       penalty_kind IS NULL OR penalty_kind IN (
           'NONE','WARNING','PENALTY','PENALTY_AND_30D','PERMANENT_BAN',
           'BROKER_CANCEL','ADMIN_BULK_EXPIRED'
       )
   );
   ```

`admin_actions.action_type` CHECK widening goes through the runtime `AdminActionTypeCheckConstraintInitializer` pattern, not the V28 file.

### 20.2 Configuration additions (`application.yml`)

```yaml
slpa:
  realty:
    group-bulk-suspend:
      auto-cancel-hours: 48
      enabled: true
    sl-group:
      reverify-cadence-days: 30
      reverify-fetch-failure-threshold: 3
      reverify:
        enabled: true
    group-suspension-expiry:
      enabled: true
```

Test profile (`application-test.yml`) sets all three `enabled: false` to suppress scheduled tasks during tests, per the existing convention (carried forward from FOOTGUNS).

### 20.3 Rollback

Drop the three new tables; revert the `realty_group_sl_groups` and `fraud_flags` CHECK alterations. No data loss in `realty_groups` itself. Admin pages on the frontend fall back to existing admin UI (no broken routes — new pages are purely additive).

## 21. Acceptance criteria

1. Admin can issue / lift a suspension on a realty group (timed or permanent). Active suspension visible as a status pill on public + admin pages.
2. Suspended group cannot: create listings, accept invitations, change members, change member permissions, edit commission rates, withdraw from wallet. Public reads continue.
3. Admin can bulk-suspend all of a group's listings via standalone action or as a cascade from group suspension. Suspensions reuse `AuctionStatus.SUSPENDED` and the existing clock-pause mechanism.
4. Listings `ADMIN_GROUP_BULK`-suspended for longer than the configured auto-cancel hours (default 48) auto-cancel; bidders refunded; sellers notified with the specific reason.
5. Admin can lift a group suspension and optionally bulk-reinstate its listings (extend `endsAt` by suspension duration).
6. Admin can fraud-flag a group with reason. Flag appears in `/admin/fraud-flags` filter.
7. User can submit a report against a group via the public group page. One OPEN report per (reporter, group); rate-limited; visible in the admin queue.
8. Admin can resolve / dismiss group reports; dismiss increments `User.dismissedReportsCount`.
9. `SlGroupReverifyTask` re-polls verified SL groups at the configured cadence (default 30 days); drift detected for founder change / 404 / 3-consecutive-fetch-failures; admin sees a "Drift detected" pill.
10. Admin can manually "Recheck now" an SL group registration; same drift evaluation runs synchronously.
11. Admin can acknowledge drift (clears flag, takes new founder snapshot).
12. Admin can force-unregister an SL group bypassing the active-listings gate; in-flight case-3 listings auto-suspend with the 48 h timer.
13. Leader can bulk-edit member commission rates atomically; one bad rate rolls back the batch.
14. Leader can view per-member commission analytics (lifetime + last-30-day totals).
15. Public group page displays a star rating aggregate from existing Review data; covers case-1 and case-3 auctions.
16. Admin audit page can filter to REALTY_GROUP entity actions and to a specific group.
17. Parser bug fixed: group name + about text correctly extracted on the SLParcels test fixture.
18. About-text verification path fully removed; founder-terminal is the sole verification path; UI no longer offers a method choice.
19. All existing tests pass; new test files cover each new service and component.
20. Frontend verify guards all green; backend test pass rate ≥ current baseline.

## 22. Out of scope (deferred)

Captured in `docs/implementation/DEFERRED_WORK.md` at PR-close time.

- **Realtime ban broadcast / forced-logout WebSocket** for groups — same posture as the User-side ban broadcast (FOOTGUNS §F.106). Members continue under the group until their next request after Redis cache eviction (5 min default TTL).
- **Report-threshold notification fan-out** — when a group crosses N open reports, no proactive admin notification. Same shape as the deferred listing-side counterpart.
- **C-era code/column cleanup** — `AgentFeeDistributor`, `auctions.agent_fee_*`, `realty_groups.agent_fee_*`. Owned by Realty Groups: G ([#246](https://github.com/TheCodeLlama/slparcelauctions/issues/246)).
- **Per-listing admin-suspend timer** — existing indefinite per-listing admin-suspend stays untouched (deliberate, per user direction). Could be unified later with the bulk-suspend timer.
- **Dedicated group-reviews page** — `GroupRatingBadge` links to the leader's user-side reviews page as a stopgap. A dedicated `/realty/groups/{publicId}/reviews` page is deferred.
- **Reputation-driven bid-eligibility gating** — Phase 8 review-score thresholds for bidding. Not in F.
- **Reverse-search by SL group UUID for ban evasion** — if a banned group's leader registers a new SLPA group with the same SL group UUID, F doesn't currently block. Tracked as a deferred fraud-signal item.
- **`AdminActionType` UI label localization** — twelve new action types only render their enum name in the audit log today. No localization for now.

## 23. References

- A+B spec: `docs/superpowers/specs/2026-05-10-realty-groups-core-permissions-design.md` §12.4
- C spec: `docs/superpowers/specs/2026-05-11-realty-groups-listing-integration-design.md`
- D spec: `docs/superpowers/specs/2026-05-11-realty-groups-group-wallet-design.md`
- E spec: `docs/superpowers/specs/2026-05-12-realty-groups-sl-group-listing-design.md` §14
- Epic 10 admin moderation precedent (listing reports, bans, fraud flags)
- `feedback_no_pause_active_auctions.md` (the carve-out for admin moderation freeze)
- `FOOTGUNS.md` §F.104 (cause-neutral bidder fan-out), §F.106 (ban cache TTL reasoning)
