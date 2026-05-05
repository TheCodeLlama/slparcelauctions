# Admin Listings Table — Design

**Date:** 2026-05-05
**Status:** Approved
**Owner:** Claude (autonomous implementation per user direction)

## 1. Goal

Give admins a single sortable, filterable table of every auction listing on the platform, plus a parallel "Drafts" view focused on the pre-launch pipeline. From either table, an admin can navigate to the public auction page (read-only inspection) and execute the four moderation actions (warn, suspend, cancel, reinstate) via modals — without first having to go through a fraud-flag or report.

The goal is operational: today the only path to those moderation actions is from a report or fraud-flag detail page. That works when the trigger is user-reported, but fails for routine admin sweeps ("show me all live escrows", "find the listing that user mentioned in support"). This page is that surface.

## 2. Architecture

**Routes (new):**
- `/admin/listings` — all-listings table
- `/admin/drafts` — drafts-only table (DRAFT, DRAFT_PAID, VERIFICATION_PENDING, VERIFICATION_FAILED)

**Sidebar:** New entries "Listings" and "Drafts" added to `AdminShell`, between Bans and Users.

**Backend package** (new): `backend/src/main/java/com/slparcelauctions/backend/admin/listings/`

```
AdminListingController.java           # GET list + 4 POST mutations
AdminListingService.java              # query + 4 mutations
AdminListingExceptionHandler.java     # state-rejection -> ProblemDetail
exception/AdminListingStateException.java
dto/AdminListingRowDto.java
dto/AdminListingFilterParams.java
dto/AdminListingActionRequest.java    # { notes: String }
```

The four mutations live alongside the list endpoint; the report-driven equivalents stay where they are. Both code paths reuse the existing `AdminActionType` enum values and `LISTING_*` notification categories. The "decoupling from reports" is a parallel, action-only entry point — not a refactor.

**Frontend package** (new): `frontend/src/components/admin/listings/`, `frontend/src/hooks/admin/useAdminListings.ts`. API and types added to existing `lib/admin/api.ts`, `lib/admin/types.ts`, `lib/admin/queryKeys.ts`.

**Data flow:**

```
/admin/listings or /admin/drafts
  → useAdminListings(filters)
  → GET /api/v1/admin/listings?...

action button (kebab menu)
  → useWarnListing/useSuspend/useCancel/useReinstate
  → POST /api/v1/admin/listings/{publicId}/{action}
  → AdminListingService → AuctionRepository + AdminActionRecorder
                        + NotificationPublisher.publishListing*
```

Sort + filter + paginate happens server-side via Spring Data `Pageable` + `Sort`.

## 3. Backend — list endpoint

### 3.1 `GET /api/v1/admin/listings`

| Param | Type | Default | Notes |
|---|---|---|---|
| `search` | string? | null | Matches `LOWER(title) LIKE %q%` OR `LOWER(seller.username) LIKE %q%` |
| `status` | `AuctionStatus[]` | see below | Repeatable: `?status=ACTIVE&status=SUSPENDED`. Empty = "all statuses". |
| `verification` | `VerificationStatus[]` | `[]` (= all) | Repeatable, multi-select |
| `hasReserve` | bool? | null | null = either, true = `reserve_price IS NOT NULL`, false = `IS NULL` |
| `page` | int | 0 | Standard `Pageable` |
| `size` | int | 25 | Admin-only — no clamp; service trusts the value |
| `sort` | string | `createdAt,desc` | Whitelisted column + direction; rejected otherwise → 400 |

The `status` parameter has no built-in default — the *frontend* default for `/admin/listings` is "all live statuses" (see §4.2), but the backend treats an absent/empty `status` parameter as "all statuses including drafts". This keeps the backend honest; the frontend is responsible for sending the user's intended filter.

**Sortable columns** (server-side whitelist enforced in `AdminListingService`):
`title`, `seller`, `createdAt`, `startPrice`, `currentBid`, `bidCount`, `saveCount`, `endsAt`, `region`.

Anything else → `400 Bad Request` with `code=INVALID_SORT_COLUMN`. Status, verification, reserve are filter-only (enums/booleans don't sort meaningfully).

**Response:** `Page<AdminListingRowDto>` (Spring Data envelope — `content`, `number`, `totalPages`, `totalElements`).

### 3.2 `AdminListingRowDto`

```java
public record AdminListingRowDto(
    UUID publicId,                    // /auction/{publicId}
    String title,
    UUID sellerPublicId,              // /admin/users/{publicId}
    String sellerUsername,
    AuctionStatus status,
    VerificationStatus verificationStatus,
    boolean hasReserve,               // bool only — never the value
    Instant createdAt,
    Long startPriceLindens,
    Long currentBidLindens,           // null if no bids
    Integer bidCount,
    Long saveCount,                   // 0 if no saves
    Instant endsAt,                   // null for non-ACTIVE in some flows
    String region                     // SL region from parcel snapshot
) {}
```

### 3.3 Repository query

The "saves" count requires aggregation. To avoid the N+1 trap and to make `ORDER BY save_count` cheap, the admin listing endpoint uses a **native query** with `LEFT JOIN saved_auctions ... GROUP BY a.id`. Actual column names (verified against `Auction.java`):

- `starting_bid` (Long, NOT NULL)
- `reserve_price` (Long, nullable — the "Has reserve" filter / chip is `reserve_price IS NOT NULL`)
- `current_bid` (Long, NOT NULL)
- `bid_count` (Integer, NOT NULL)
- `ends_at` (timestamp, nullable)
- `created_at`, `verification_status`, `status`, `seller_id` (standard)
- Region: from `auction_parcel_snapshots.region_name` (joined via `auction_id`)

Sketch:

```sql
SELECT
    a.id,
    a.public_id,
    a.title,
    u.public_id      AS seller_public_id,
    u.username       AS seller_username,
    a.status,
    a.verification_status,
    (a.reserve_price IS NOT NULL) AS has_reserve,
    a.created_at,
    a.starting_bid,
    a.current_bid,
    a.bid_count,
    COALESCE(s.save_count, 0) AS save_count,
    a.ends_at,
    ps.region_name
FROM auctions a
JOIN users u ON u.id = a.seller_id
LEFT JOIN auction_parcel_snapshots ps ON ps.auction_id = a.id
LEFT JOIN (
    SELECT auction_id, COUNT(*) AS save_count
      FROM saved_auctions
     GROUP BY auction_id
) s ON s.auction_id = a.id
WHERE (:search IS NULL OR LOWER(a.title) LIKE :search OR LOWER(u.username) LIKE :search)
  AND (:hasStatusFilter = false OR a.status = ANY(CAST(:statuses AS varchar[])))
  AND (:hasVerificationFilter = false OR a.verification_status = ANY(CAST(:verifications AS varchar[])))
  AND (:hasReserve IS NULL
       OR (:hasReserve = true  AND a.reserve_price IS NOT NULL)
       OR (:hasReserve = false AND a.reserve_price IS NULL))
```

Same WHERE clause is repeated in a `countQuery` for paging.

Notes:
- `hasStatusFilter` / `hasVerificationFilter` flags work around the Postgres null-typed-as-bytea issue with empty-array binding (proven workaround in `UserRepository`).
- `Pageable` carries the sort. The implementation uses Spring Data's `JpaSort.unsafe(...)` for native queries OR a manual `ORDER BY` clause built from the whitelist — whichever the existing pattern in this codebase prefers. The whitelist maps DTO property → SQL column.
- The `LEFT JOIN (subquery) s` shape avoids GROUP-BY explosion across all SELECT columns.

`AdminListingService.toRow(Object[])` maps the result tuple to `AdminListingRowDto`.

### 3.4 Required Flyway migration

```sql
-- V16__saved_auctions_auction_idx.sql
-- Index for "count saves per auction" used by the admin listings table.
-- The existing ix_saved_auctions_user_saved_at covers the user-side
-- "list my saves" path; this one covers the admin aggregation path.
CREATE INDEX IF NOT EXISTS ix_saved_auctions_auction_id
    ON saved_auctions (auction_id);
```

### 3.5 Authorization

`@PreAuthorize("hasRole('ADMIN')")` on the controller class — same as every other admin controller.

## 4. Backend — action endpoints

### 4.1 Endpoints

| Method | Path | Action | Allowed when status ∈ |
|---|---|---|---|
| POST | `/api/v1/admin/listings/{publicId}/warn` | warn seller, listing untouched | any status (warn is about the seller's behavior, not the listing's lifecycle) |
| POST | `/api/v1/admin/listings/{publicId}/suspend` | flip to `SUSPENDED` | whatever `SuspensionService.suspendByAdmin` already permits |
| POST | `/api/v1/admin/listings/{publicId}/cancel` | flip to `CANCELLED` | whatever `CancellationService.cancelByAdmin` already permits |
| POST | `/api/v1/admin/listings/{publicId}/reinstate` | flip `SUSPENDED` → `ACTIVE` | `SUSPENDED` only (enforced by `AdminAuctionService.reinstate`) |

**Reuse, don't reinvent:**

- `suspend` → calls `SuspensionService.suspendByAdmin(auction, adminUserId, notes)` (existing). That service handles the state flip, bot monitoring teardown, and bidder notification fan-out.
- `cancel` → calls `CancellationService.cancelByAdmin(auctionId, adminUserId, notes)` (existing). Same — handles state flip, bidder notifications, downstream side effects.
- `reinstate` → calls `AdminAuctionService.reinstate(auctionId, Optional.empty())` (existing). That service also publishes the `LISTING_REINSTATED` notification — **do not double-publish from the controller path**.
- `warn` → no shared service exists today (logic is inline in `AdminReportService.warnSeller`). Either: extract a `WarningService.warnSeller(auctionId, adminUserId, notes)` shared helper, OR call `notificationPublisher.listingWarned(...)` directly from `AdminListingService.warn`. The implementation plan picks one based on which is cleaner; the spec mandates only that the warn semantics match the report-driven flow.

The controller's only responsibilities beyond delegation: resolve `publicId → auctionId`, record the `AdminAction` audit row (since the shared services intentionally don't audit — different callers want different audit semantics), and publish the seller-side notification for `warn` (the other three publish their notifications inside the shared service or via downstream side effects).

Status guards are enforced inside the shared services. The new `AdminListingService` methods translate any `AuctionNotSuspendedException` / `AuctionNotFoundException` / similar from those services into `AdminListingStateException` so the controller emits a uniform problem-detail shape.

### 4.2 Request body (all four)

```json
{ "notes": "Listing description appears to be auto-translated and inaccurate" }
```

Validation: `@NotBlank @Size(min=5, max=1000)` on `notes`.

### 4.3 Error codes (HTTP status via `AdminListingStateException.suggestedStatus()`)

| code | status | when |
|---|---|---|
| `INVALID_STATUS_FOR_ACTION` | 409 | listing's current status doesn't permit the action |
| `ALREADY_SUSPENDED` | 409 | suspend on already-suspended listing |
| `NOT_SUSPENDED` | 409 | reinstate on non-suspended listing |
| `LISTING_NOT_FOUND` | 404 | publicId doesn't resolve |
| `INVALID_SORT_COLUMN` | 400 | sort column not in whitelist (list endpoint, not action endpoints) |

### 4.4 Audit

Each call records an `AdminAction` row via the existing `AdminActionService.record(...)` helper:

- `actionType` = `WARN_SELLER_FROM_REPORT` / `SUSPEND_LISTING_FROM_REPORT` / `CANCEL_LISTING_FROM_REPORT` / `REINSTATE_LISTING` (reused as-is — renaming would touch the entire report flow)
- `targetType` = `AdminActionTargetType.LISTING` (matches the existing report-flow audits)
- `targetId` = auction internal id (Long)
- `notes` = the admin's typed notes
- `metadata` = `Map.of("source", "ADMIN_LISTINGS_TABLE")` so audit-log readers can distinguish report-triggered from table-triggered actions

### 4.5 Notifications to seller

Reuses existing `LISTING_*` categories (already wired through the report flow):

| Action | Category | Deeplink |
|---|---|---|
| warn | `LISTING_WARNED` | `/auction/{publicId}` |
| suspend | `LISTING_SUSPENDED` | `/dashboard/listings` |
| cancel | `LISTING_REMOVED_BY_ADMIN` | `/auction/{publicId}` |
| reinstate | `LISTING_REINSTATED` | `/dashboard/listings` |

Notification `data`: `{ auctionId, auctionTitle, notes, adminUsername }`. The admin's notes ARE the body of the seller-facing message — the modal's textarea placeholder reflects this so admins know they're writing to the seller, not just the audit log.

### 4.6 Notifications to bidders

For ACTIVE listings being suspended or cancelled, every bidder needs a heads-up. The new admin endpoints reuse whatever fan-out helper the existing report-driven `SUSPEND_LISTING_FROM_REPORT` / `CANCEL_LISTING_FROM_REPORT` flow uses (the survey confirmed this exists). No new categories.

### 4.7 Response

`204 No Content` on success. Frontend invalidates `adminQueryKeys.listingsList()` to refresh the row.

## 5. Frontend — `/admin/listings` page

### 5.1 URL state

Source of truth for filters/sort/page: URL search params. Reads via Next's `useSearchParams`, writes via `router.replace` (no full nav).

```
?search=...&status=ACTIVE&status=SUSPENDED&verification=VERIFIED&hasReserve=true&page=0&size=25&sort=endsAt,asc
```

### 5.2 Default filter for `/admin/listings`

When no `status` param is in the URL, the frontend sends a default filter that **excludes** the pre-launch pipeline:

```
status=ACTIVE&status=ENDED&status=ESCROW_PENDING&status=ESCROW_FUNDED&status=TRANSFER_PENDING
&status=COMPLETED&status=CANCELLED&status=EXPIRED&status=DISPUTED&status=SUSPENDED
```

This is the **default** — the user can override by selecting any combination in the Status multi-select, including draft statuses.

### 5.3 Filter bar layout

```
[Search title or seller…        ]  [Status ▾]  [Verification ▾]  [Reserve ▾]  [Reset]
                                       3 selected     1 selected     Either
```

- **Search** — debounced 300ms, fires on Enter or pause. Clears on Esc.
- **Status** — multi-select dropdown of `AuctionStatus` values, shows count or "All".
- **Verification** — multi-select of `VerificationStatus`.
- **Reserve** — three-state cycle: `Either` / `Has reserve` / `No reserve`.
- **Reset** — clears everything to defaults; visible only when any filter is non-default.

Below the bar: a results-summary line `Showing 26–50 of 1,438 listings`. Page-size text input lives **bottom right** next to pagination: `Per page: [25]`. Numeric input; non-numeric/≤0 silently retains the last valid value.

### 5.4 Presets (chip row)

```
[ Ending soon ]  [ Live escrow ]  [ Suspended ]  [ Recently ended ]
```

| Preset | Filter | Sort |
|---|---|---|
| Ending soon | status=ACTIVE | endsAt asc |
| Live escrow | status=ESCROW_PENDING, ESCROW_FUNDED, TRANSFER_PENDING, DISPUTED | createdAt asc |
| Suspended | status=SUSPENDED | createdAt desc |
| Recently ended | status=ENDED | endsAt desc |

Clicking a chip overwrites filters + sort. Clicking the active preset clears back to defaults.

### 5.5 Table columns (13)

| Title | Seller | Status | Verif. | Reserve | Created | Start | Current bid | Bids | Saves | Ends | Region | … |
|---|---|---|---|---|---|---|---|---|---|---|---|---|

- **Title** — link to `/auction/{publicId}` (cmd/ctrl-click opens new tab via default browser behavior). Truncates with ellipsis; full title in `title=`.
- **Seller** — link to `/admin/users/{sellerPublicId}`.
- **Status** / **Verif.** / **Reserve** — chips. Filter-only.
- **Created**, **Start**, **Current bid**, **Bids**, **Saves**, **Ends**, **Region** — sortable. Header click cycles asc → desc → off. Active sort shows ↑/↓ glyph.
- **Ends** — relative string ("2h 14m") computed from `endsAt`. Sorts on `endsAt` server-side. Non-ACTIVE rows show `—`.
- **…** — kebab `MoreVertical` opens an action menu (Warn / Suspend / Cancel / Reinstate). Items disabled when the listing's status doesn't permit, with a tooltip explaining why.

**Row interaction:** Click anywhere outside a link/button highlights the row (local-state `selected` row id, applies `bg-bg-hover`). No detail slide-over.

### 5.6 Loading / empty / error

- **Loading**: skeleton rows (13 cols × 5 rows of `bg-bg-muted` shimmer).
- **Empty**: `No listings match these filters. [Reset filters]`.
- **Error**: `Could not load listings. Refresh to retry.`

## 6. Frontend — `/admin/drafts` page

A second page that reuses the same `<AdminListingsTable>` component:

- **Status filter**: hidden. The page IS the status filter — locked to `DRAFT, DRAFT_PAID, VERIFICATION_PENDING, VERIFICATION_FAILED`.
- **Verification filter**: visible (useful for "show only failed-verification drafts").
- **Default sort**: `createdAt desc` (newest drafts first).
- **Action menu**: same four items, same status guards. The status guards in §4.1 already correctly permit suspend/cancel on draft statuses and forbid reinstate.
- **Title link**: still `/auction/{publicId}` — the public auction page handles the "this is a draft" view itself.

The shared component takes a `lockedStatuses?: AuctionStatus[]` prop. When set, the component:
1. Always sends those statuses regardless of URL `status` params (URL `status` params are ignored here)
2. Hides the Status filter dropdown
3. Hides the "Suspended" preset (irrelevant) and shows a different preset row (e.g. "Failed verification" / "Awaiting verification")

## 7. Action modals

Reuses the modal pattern from `WalletTab`'s `AdjustBalanceModal` shape.

### 7.1 Common layout

```
┌──────────────────────────────────────────────┐
│  Suspend listing                          ✕  │
│  ──────────────────────────────────────────  │
│  Listing                                     │
│  "5,000 m² Heterocera Atoll oceanfront"      │
│  Seller: Heath Onyx                          │
│                                              │
│  Notes (sent to seller and audit log) *      │
│  ┌────────────────────────────────────────┐  │
│  │                                        │  │
│  │                                        │  │
│  └────────────────────────────────────────┘  │
│  Min 5 chars, max 1000.                      │
│                                              │
│           [ Cancel ]    [ Suspend listing ]  │
└──────────────────────────────────────────────┘
```

### 7.2 Per-action specifics

| Action | Title | Primary button | Variant | Body content |
|---|---|---|---|---|
| Warn | "Warn seller" | "Send warning" | destructive | "The seller will be notified. The listing remains active." |
| Suspend | "Suspend listing" | "Suspend listing" | destructive | "The listing will be hidden from browse. Bidders will be notified. You can reinstate it later." |
| Cancel | "Cancel listing" | "Cancel listing" | destructive | "**This is permanent.** The listing will be terminated and cannot be reinstated. All bidders will be notified. Use Suspend if you may want to reinstate later." |
| Reinstate | "Reinstate listing" | "Reinstate listing" | primary | "The listing will return to ACTIVE status. The seller will be notified." |

### 7.3 Submit flow

1. Mutation hook → `POST /api/v1/admin/listings/{publicId}/{action}` with `{ notes }`.
2. Success: close modal, invalidate `adminQueryKeys.listingsList()`, toast "Listing suspended" / etc.
3. Failure (409 / 404): keep modal open, show error banner mapped from `code`:
   - `INVALID_STATUS_FOR_ACTION` → "This listing's status changed — refresh and try again."
   - `ALREADY_SUSPENDED` → "This listing is already suspended."
   - `NOT_SUSPENDED` → "This listing isn't suspended."
   - `LISTING_NOT_FOUND` → "Listing not found. It may have been deleted." (close modal, refresh table)

## 8. Out of scope (deferred)

1. **Bulk actions** (multi-select + bulk suspend/cancel/warn). Adds checkbox column, bulk-action bar, fan-out semantics. Worth shipping if a real moderation case shows up where one-at-a-time is the bottleneck.
2. **Inline edits** (admin overrides reserve / endsAt / title). Brittle — listings are seller-authored. Today's "suspend → seller fixes → admin reinstates" flow keeps responsibility clear.
3. **CSV export.** Add when a concrete reporting need shows up.
4. **Listing detail slide-over.** The public auction page covers this; title link goes there.
5. **Drafts page hard-delete action.** `cancel` on a `DRAFT` already ends it. Hard-delete is its own retention-policy feature.
6. **Saved-by-whom popover** on the Saves column. Privacy implications warrant a separate design pass.
7. **"Listings ending in N hours" sidebar badge.** Disproportionate vs `?preset=ending-soon`.
8. **Per-admin saved filter presets.** URL bookmarks suffice; per-user preference plumbing is its own feature.

## 9. Postman

Add the new endpoints to the `SLPA` Postman collection in the same task:

- `GET admin/listings` (with example query params for each preset)
- `POST admin/listings/{{auctionPublicId}}/warn`
- `POST admin/listings/{{auctionPublicId}}/suspend`
- `POST admin/listings/{{auctionPublicId}}/cancel`
- `POST admin/listings/{{auctionPublicId}}/reinstate`

Variable chaining: list response stamps `auctionPublicId` for downstream action requests.

## 10. README

Update root `README.md` to mention the new admin pages once shipped.
