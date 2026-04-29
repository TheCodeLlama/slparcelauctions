# Epic 10 Sub-spec 1 — Admin Foundation + Fraud-Flag Triage

> **Status:** Design
> **Epic:** 10 (Admin & Moderation)
> **Sub-spec:** 1 of 4
> **Branch hint:** `task/10-sub-1-admin-foundation-and-fraud-flag-triage`
> **Read first:** [`docs/implementation/CONVENTIONS.md`](../../implementation/CONVENTIONS.md), [`docs/implementation/DEFERRED_WORK.md`](../../implementation/DEFERRED_WORK.md), [`docs/implementation/FOOTGUNS.md`](../../implementation/FOOTGUNS.md)

---

## 1. Goal

Stand up the admin authority and admin section foundation that the rest of Epic 10 hangs off, and ship the highest-value content surface — fraud-flag triage — on top of the existing `FraudFlag` entity and 12 reason values that have been accumulating un-reviewed data since Epic 03.

A user with `Role.ADMIN` can sign in, see a `/admin` dashboard with platform stats and queue counts, and triage open fraud flags by dismissing or reinstating them — restoring SUSPENDED auctions to ACTIVE with bidder time fairly compensated.

## 2. Scope

### In scope

- `User.role` enum column (USER, ADMIN), JWT role claim, Spring Security gate on `/api/v1/admin/**`.
- Bootstrap config (`slpa.admin.bootstrap-emails`) with idempotent startup promotion.
- Frontend admin section: `/admin/*` route gating, sidebar shell, `/admin` dashboard, `/admin/fraud-flags` triage page.
- `GET /api/v1/admin/stats` endpoint and dashboard cards (3 needs-attention + 6 platform).
- Admin fraud-flag REST API (list, detail, dismiss, reinstate).
- `Auction.suspendedAt` column for unambiguous reinstate-time math.
- `FraudFlag.adminNotes` column for resolution audit trail.
- `LISTING_REINSTATED` notification category.
- `BotMonitorLifecycleService.onAuctionResumed(auction)` (added if not present) for bot-monitor re-engagement on reinstate.

### Out of scope (later sub-specs within Epic 10)

- Admin user-detail page (`/admin/users/{id}`), user search → sub-spec 2.
- Cross-entity `admin_actions` audit table → sub-spec 2 (sub-spec 1's actions self-audit on `FraudFlag`).
- Listing reports + ban system → sub-spec 2.
- Disputes resolution / unfreeze / terminal-secret rotation / bot-pool health / daily balance reconciliation / dispute evidence attachments / `PAYMENT_NOT_CREDITED` reconciliation → sub-spec 3.
- `REVIEW_RESPONSE_WINDOW_CLOSING` + `ESCROW_TRANSFER_REMINDER` schedulers, account deletion UI, admin audit-log viewer → sub-spec 4.
- Confirmed-fraud disposition that feeds ban suggestions — sub-spec 2 will extend the resolution model when that consumer arrives.
- Period selector / per-tier / per-region breakdowns on stats — lifetime totals only.
- Stats caching — computed live each load this sub-spec.

---

## 3. Architecture

```
Frontend (/admin/*) ── RequireAdmin gate ── reads role from /me ── redirects non-admin
                  │
                  └── /admin (dashboard cards) ── GET /api/v1/admin/stats
                  └── /admin/fraud-flags        ── GET /api/v1/admin/fraud-flags + slide-over
                                                    POST .../dismiss
                                                    POST .../reinstate

Backend (com.slparcelauctions.backend.admin)
  ├── AdminStatsController + AdminStatsService          ── 9 aggregate queries
  ├── AdminFraudFlagController + AdminFraudFlagService  ── list/detail/dismiss/reinstate
  ├── AdminBootstrapInitializer                          ── @EventListener(ApplicationReadyEvent)
  └── dto/                                               ── records

Cross-cutting
  ├── User.role + JwtService.role claim + SecurityConfig hasRole(ADMIN)
  ├── Auction.suspendedAt set by SuspensionService, cleared on reinstate
  ├── FraudFlag.adminNotes populated on dismiss/reinstate
  ├── BotMonitorLifecycleService.onAuctionResumed(auction)  (mirrors onAuctionClosed shape)
  └── NotificationPublisher.listingReinstated(...) ── new LISTING_REINSTATED category
```

The admin domain is a new feature-based package (`backend/src/main/java/com/slparcelauctions/backend/admin/`) that owns its controllers, services, and DTOs but consumes existing repositories (`UserRepository`, `AuctionRepository`, `EscrowRepository`, `FraudFlagRepository`) directly. No new entities — only column additions on existing entities.

---

## 4. Backend pieces

### 4.1 `User.role`

```java
public enum Role {
    USER,
    ADMIN
}

// User.java additions
@Enumerated(EnumType.STRING)
@Column(nullable = false, length = 10)
@Builder.Default
private Role role = Role.USER;
```

`ddl-auto: update` adds the column with default `USER` for existing rows. New users default `USER` via Lombok builder default.

### 4.2 JWT role claim

`AuthPrincipal` gains a `Role role` field. `JwtService.issueAccessToken` adds `.claim("role", principal.role().name())`. `parseAccessToken` reads it with a defensive default to `USER` if the claim is missing (handles rolling deployment where pre-release tokens lack the claim — they're treated as non-admin).

The JWT auth filter (existing `JwtAuthenticationFilter`) maps the role to a Spring Security authority: `new SimpleGrantedAuthority("ROLE_" + role.name())`. This makes `hasRole('ADMIN')` work directly.

`tokenVersion` (`tv`) already exists and is bumped on logout-all / sensitive change. **Demoting an admin via DB or future endpoint must bump `tv`** so existing tokens are rejected on next refresh. Documented in §11.

### 4.3 `SecurityConfig` admin gate

```java
.requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
```

Inserted alongside existing matchers. Order matters — admin matcher is more specific than the generic authenticated matcher and goes first.

### 4.4 `AdminBootstrapInitializer`

```java
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

`UserRepository.bulkPromoteByEmailIfUser(List<String> emails)` — `@Modifying @Query("UPDATE User u SET u.role = 'ADMIN' WHERE u.email IN :emails AND u.role = 'USER'")` returning the affected row count.

The **promote-only-currently-USER guard** prevents accidentally clobbering a non-bootstrap admin (someone promoted manually who happens to share an email with the list — paranoid case) and avoids unnecessary writes on rows already at `ADMIN`. **It does NOT prevent re-promotion of a bootstrap email that was deliberately demoted** — a demoted user has `role = USER`, which matches the WHERE clause, so the next restart promotes them again. Bootstrap is a forward push at startup, not a configurable opt-out for a specific user. To permanently revoke admin access for a bootstrap email, **remove the email from the config list and bump `tv`** (see §10.5–10.6 for the full demote procedure and the operator implications).

`AdminBootstrapProperties` (`@ConfigurationProperties(prefix = "slpa.admin")`):

```yaml
slpa:
  admin:
    bootstrap-emails:
      - heath@slparcels.com
      - heath@slparcelauctions.com
      - heath@hadronsoftware.com
      - heath.barcus@gmail.com
```

In `application.yml` (default profile) so it applies in every environment. Production override (env var `SLPA_ADMIN_BOOTSTRAP_EMAILS`) is available if needed but not required.

### 4.5 `Auction.suspendedAt`

```java
@Column(name = "suspended_at")
private OffsetDateTime suspendedAt;
```

Nullable. Set by `SuspensionService` (in `suspendForOwnershipChange`, `suspendForDeletedParcel`, `suspendForBotObservation`) **only if currently null** — the first suspension wins, subsequent re-flagging on an already-suspended auction does not move the timestamp. Cleared by reinstate.

This is the single source of truth for "how long has this auction been suspended" — not flag.detectedAt, which is per-flag and ambiguous when multiple flags are open.

**Backfill for historical data:** rows that were suspended before this column existed have null. Reinstate falls back to `flag.detectedAt` if `auction.suspendedAt` is null. Documented in §10.

### 4.6 `FraudFlag.adminNotes`

```java
@Column(name = "admin_notes", columnDefinition = "text")
private String adminNotes;
```

Nullable. Populated on dismiss/reinstate from request body. Existing resolved rows have null (no historical notes captured). The dismiss/reinstate endpoints are the only writers.

### 4.7 `AdminStatsService` + `GET /api/v1/admin/stats`

```java
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

Queries:
- `openFraudFlags` — `fraudFlagRepository.countByResolved(false)`
- `pendingPayments` — `escrowRepository.countByState(EscrowState.ESCROW_PENDING)`
- `activeDisputes` — `escrowRepository.countByState(EscrowState.DISPUTED)`
- `activeListings` — `auctionRepository.countByStatus(AuctionStatus.ACTIVE)`
- `totalUsers` — `userRepository.count()`
- `activeEscrows` — `escrowRepository.countByStateNotIn(Set.of(COMPLETED, EXPIRED, DISPUTED, FROZEN))`
- `completedSales` — `escrowRepository.countByState(COMPLETED)`
- `lindenGrossVolume` — `escrowRepository.sumAmountByState(COMPLETED)` (returns 0 if no rows)
- `lindenCommissionEarned` — `escrowRepository.sumCommissionByState(COMPLETED)`

All run in a single read-only transaction — no individual numbers need to be transactionally consistent with each other (they're a snapshot, not a coupled invariant).

**No caching this sub-spec.** If query cost becomes an issue at scale, add a 30-second Redis cache with an invalidate-on-state-change pattern in a follow-up — but that's premature today.

### 4.8 `AdminFraudFlagService` + endpoints

#### List: `GET /api/v1/admin/fraud-flags`

Query params:
- `status` — `open` (default), `resolved`, `all`. Single-select.
- `reasons` — comma-separated subset of `FraudFlagReason` enum values. Empty/absent = all reasons.
- `page` — 0-indexed, default 0.
- `size` — default 25, max 100 (clamp).

Response: `PagedResponse<AdminFraudFlagSummaryDto>` sorted `detectedAt DESC`.

```java
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

Implementation: a Spring Data `@Query` with the `status` and `reasons` filters expressed via a `Specification<FraudFlag>` for clean composition. `auctionStatus` is fetched via the existing `auction` relationship; `auctionTitle` is `auction.title` projection. For flags with `auction == null` (defensive — shouldn't happen given current call sites all set it, but the column is nullable per the entity), the row still appears with `auctionId = null`.

#### Detail: `GET /api/v1/admin/fraud-flags/{id}`

Returns `AdminFraudFlagDetailDto`:

```java
public record AdminFraudFlagDetailDto(
    Long id,
    FraudFlagReason reason,
    OffsetDateTime detectedAt,
    OffsetDateTime resolvedAt,
    String resolvedByDisplayName,
    String adminNotes,
    AuctionContextDto auction,             // null if auction is null
    Map<String, Object> evidenceJson,      // raw, passed through
    Map<String, LinkedUserDto> linkedUsers,// resolved SL UUIDs from evidence
    long siblingOpenFlagCount              // count of OTHER open flags on same auction
) {
    public record AuctionContextDto(
        Long id,
        String title,
        AuctionStatus status,
        OffsetDateTime endsAt,
        OffsetDateTime suspendedAt,        // null if not currently suspended
        Long sellerUserId,
        String sellerDisplayName
    ) {}

    public record LinkedUserDto(
        Long userId,
        String displayName
    ) {}
}
```

`linkedUsers` is built by scanning `evidenceJson` for any value that parses as a UUID, collecting them into a `Set<UUID>`, and resolving in a **single batched query** via `UserRepository.findAllBySlAvatarUuidIn(Set<UUID>)` — not per-UUID lookups. The returned list is folded into the map keyed by `slAvatarUuid.toString()`. UUIDs not present in the result set are omitted from the map (frontend renders plain text with tooltip "(not a registered SLPA user)" for any UUID-shaped value not in the map). One query regardless of evidence size.

`siblingOpenFlagCount` — `fraudFlagRepository.countByAuctionIdAndResolvedFalseAndIdNot(auctionId, currentFlagId)`. Drives the slide-over banner warning.

**404** if no flag with that id.

#### Dismiss: `POST /api/v1/admin/fraud-flags/{id}/dismiss`

Body: `{ "adminNotes": "..." }` (Bean Validation: `@NotBlank @Size(max = 1000)`).

Behavior:
- 404 if flag id doesn't exist.
- 409 with body `{ "code": "ALREADY_RESOLVED", "message": "..." }` if `flag.resolved == true`.
- Otherwise: mark `resolved = true`, set `resolvedBy` from authenticated user, set `resolvedAt = now`, set `adminNotes`. Save. Return updated `AdminFraudFlagDetailDto`.

No state changes outside the flag row. No notifications.

#### Reinstate: `POST /api/v1/admin/fraud-flags/{id}/reinstate`

Body: `{ "adminNotes": "..." }` (same validation).

Behavior in a single `@Transactional` method:
1. Load flag. **404** if missing.
2. **409** with `code: "ALREADY_RESOLVED"` if `flag.resolved`.
3. **409** with `code: "AUCTION_NOT_SUSPENDED"` if `flag.auction == null` OR `flag.auction.status != SUSPENDED`. Body includes the actual current status so the UI can refresh the user.
4. Compute `Duration suspensionDuration = Duration.between(suspendedFrom, now)` where `suspendedFrom = auction.suspendedAt != null ? auction.suspendedAt : flag.detectedAt`. The fallback covers historical rows.
5. `auction.endsAt = auction.endsAt.plus(suspensionDuration)`. If math somehow yields a past endsAt (only possible if both endsAt and suspendedAt happened pre-existing in the past — paranoid check), clamp to `now.plusHours(1)` and log WARN. Save auction.
6. `auction.status = ACTIVE`. `auction.suspendedAt = null`. Save (combined with step 5).
7. Re-engage bot monitoring: if `auction.tier == BOT`, call `botMonitorLifecycleService.onAuctionResumed(auction)`. The method mirrors the existing `onAuctionClosed` shape — adds a fresh `MONITOR_AUCTION` `BotTask` row matching what `BotTaskService.complete()` does on its `SUCCESS` path.
8. Mark flag resolved (resolved=true, resolvedBy, resolvedAt=now, adminNotes).
9. Publish `LISTING_REINSTATED` notification to seller (`notificationPublisher.listingReinstated(...)`).

The afterCommit ordering rules from Epic 09 sub-specs 1 + 3 still apply — notifications fire after the transaction commits, not inside it.

**Sibling open flags** are **not** auto-resolved. The slide-over banner warns the admin; they decide whether to walk through the others. If sibling flags are still about a real ongoing issue, the next monitor tick re-suspends the auction and creates a fresh flag — self-healing.

### 4.9 `BotMonitorLifecycleService.onAuctionResumed(Auction)`

If the method does not already exist on `BotMonitorLifecycleService`, sub-spec 1 adds it.

Behavior: spawn a fresh `MONITOR_AUCTION` `BotTask` for the auction, matching the shape created by `BotTaskService.complete()`'s `SUCCESS` path on the original sale-to-bot listing. Method body is essentially the inverse of `onAuctionClosed` (which cancels in-flight monitor tasks).

The implementer should grep `BotTaskService.complete` and `BotMonitorLifecycleService.onAuctionClosed` to find the existing patterns, then write `onAuctionResumed` against them. Tests cover: bot tier auction → fresh MONITOR_AUCTION row created with correct fields; non-bot tier auction → no-op.

### 4.10 Notification: `LISTING_REINSTATED`

New category. `NotificationCategory` enum gains `LISTING_REINSTATED`. The category goes in the existing **LISTINGS** group (alongside `LISTING_VERIFIED`, `LISTING_SUSPENDED`, `LISTING_CANCELLED_BY_SELLER`, etc.). Default preferences: ON for both in-app and SL IM (high-importance to seller, parallels `LISTING_SUSPENDED` which they already opted into via category default).

`NotificationPublisher.listingReinstated(sellerUserId, auctionId, auctionTitle, newEndsAt)` — new method on the existing publisher interface. Body builder produces:

> Your auction "{title}" has been reinstated. All existing bids and proxy maxes are preserved. Ends {newEndsAt}.

SL IM auto-routes via the existing channel dispatcher (Epic 09 sub-spec 3). Deeplink → `/auction/{id}`.

---

## 5. Frontend pieces

### 5.1 Role propagation

`useCurrentUser` hook (`frontend/src/hooks/auth/useCurrentUser.ts`) — `data.role` is now `"USER" | "ADMIN"`. The MSW seed in tests gains a `role` field. Login response and `/me` response shapes include `role`. Backend DTO update.

### 5.2 `RequireAdmin`

```tsx
// frontend/src/components/auth/RequireAdmin.tsx
"use client";
import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { useCurrentUser } from "@/hooks/auth/useCurrentUser";

export function RequireAdmin({ children }: { children: React.ReactNode }) {
  const router = useRouter();
  const { data, isLoading } = useCurrentUser();

  useEffect(() => {
    if (!isLoading && data?.role !== "ADMIN") {
      router.replace("/");
    }
  }, [data, isLoading, router]);

  if (isLoading || data?.role !== "ADMIN") return null;
  return <>{children}</>;
}
```

Wraps every `/admin/*` page. Renders nothing while loading or while non-admin (redirect race window).

### 5.3 Admin layout + sidebar

```
frontend/src/app/admin/
├── layout.tsx              ── <AdminShell>{children}</AdminShell>
├── page.tsx                ── <RequireAdmin><AdminDashboardPage /></RequireAdmin>
└── fraud-flags/
    └── page.tsx            ── <RequireAdmin><FraudFlagsListPage /></RequireAdmin>
```

`<AdminShell>` is a new component in `frontend/src/components/admin/` — sidebar (200px, dark surface) on the left, main content on the right, role-chip + display name in sidebar footer.

Sidebar items (only links to working pages):

| Item | Route | Badge |
|---|---|---|
| Dashboard | `/admin` | none |
| Fraud Flags | `/admin/fraud-flags` | open-flag count from `useAdminStats()` |

The badge fetches via `useAdminStats()`, which the layout subscribes to. Bell-style red badge when count > 0.

### 5.4 Dashboard cards

Components in `frontend/src/components/admin/dashboard/`:

- `<QueueCard>` — props `{ label, value, tone: "fraud" | "warning", subtext, href? }`. Border tint: red for `fraud`, amber for `warning`. Hover state when `href` present. 3-up always.
- `<StatCard>` — props `{ label, value, accent? }`. Neutral border, optional accent color on the number (used for `L$ commission`). 3-up at desktop, 2-up tablet, 1-up mobile.
- `<AdminDashboardPage>` — composes the above. Single `useAdminStats()` query feeds all cards. Loading skeleton + error retry consistent with the rest of the app.

`useAdminStats()` (`frontend/src/hooks/admin/useAdminStats.ts`) — TanStack Query, key `["admin", "stats"]`, `staleTime: 30000`, GET `/api/v1/admin/stats`.

### 5.5 Fraud-flag triage list

`<FraudFlagsListPage>` — primary surface in `frontend/src/components/admin/fraud-flags/`.

State held in URL query string:
- `status=open|resolved|all` (default `open`)
- `reasons=` comma-separated enum values (default empty = all)
- `page=N` (0-indexed, default 0)
- `flagId=N` opens slide-over for that flag

Hooks:
- `useAdminFraudFlagsList({ status, reasons, page, size })` — list query, key `["admin", "fraud-flags", filters]`.
- `useAdminFraudFlag(flagId)` — detail query, enabled only when `flagId` is set.
- `useDismissFraudFlag()` + `useReinstateFraudFlag()` — mutations.

Components:
- `<FraudFlagFilters>` — status pills (single-select), reason multi-select dropdown.
- `<FraudFlagTable>` — sticky header, 7 columns: detected, reason badge, auction title + region, current status. Click row → updates `flagId` query param.
- `<FraudFlagSlideOver>` — fixed-position, 520px wide, opens when `flagId` is set. Closes on `?flagId=` cleared, ESC key, or backdrop click. Prev/Next arrows in header walk the open queue (when `status=open`) — they update `flagId` to the prev/next id in the list, allowing rapid triage. When `status=resolved` or `all`, prev/next walks that filtered list.
- `<FraudFlagEvidence>` — monospace key/value table. Each value is:
  - String that parses as UUID **and** appears in `linkedUsers` map → `<Link href={\`/users/${linkedUsers[uuid].userId}\`}>` truncated `5fb3c9...e421` with tooltip showing full UUID + display name. Sub-spec 2 swaps `/users/{id}` for `/admin/users/{id}` once the admin user-detail page lands.
  - String that parses as UUID **not** in `linkedUsers` → plain truncated text with tooltip showing full UUID + "(not a registered SLPA user)".
  - Anything else → JSON-stringified, monospace.
- `<ReinstateBanner>` — visible when `auction.status == SUSPENDED`. Shows: "Auction is SUSPENDED. Reinstate will restore ACTIVE status and extend endsAt by the suspension duration ({computedDuration} so far)." Computed from `auction.suspendedAt` (or `flag.detectedAt` fallback).
- `<SiblingFlagWarning>` — visible when `siblingOpenFlagCount > 0`. "This auction has {N} other open flag(s). Resolving this one alone doesn't address them — they need separate review."
- `<NotesField>` — required textarea, 1000-char counter, validation state matching the existing dispute textarea pattern from Epic 05 sub-spec 2.
- Action buttons: `<DismissButton>` (neutral) + `<ReinstateButton>` (accented, disabled when `auction.status != SUSPENDED` or auction is null, with disabled-state tooltip explaining why).

### 5.6 Reason badge styling

`frontend/src/lib/admin/reasonStyle.ts`:

```ts
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
```

Family → tone classes (Tailwind tokens, both modes):

| Family | Background tone | Text tone |
|---|---|---|
| `ownership` | red surface | red on-surface |
| `bot` | amber surface | amber on-surface |
| `escrow` | violet surface | violet on-surface |
| `cancel-and-sell` | teal surface | teal on-surface |

`<ReasonBadge reason={...} />` consumes both maps and renders.

### 5.7 Mutation patterns

```ts
useDismissFraudFlag = () => useMutation({
  mutationFn: ({ flagId, adminNotes }) => fraudFlagsApi.dismiss(flagId, { adminNotes }),
  onSuccess: () => {
    queryClient.invalidateQueries({ queryKey: ["admin", "fraud-flags"] });
    queryClient.invalidateQueries({ queryKey: ["admin", "stats"] });
    toast.success("Flag dismissed.");
    closeSlideOver();
  },
  onError: (err) => {
    if (err.code === "ALREADY_RESOLVED") {
      toast.error("Flag was already resolved. Refresh to see latest state.");
      queryClient.invalidateQueries({ queryKey: ["admin", "fraud-flags"] });
      return;
    }
    toast.error("Couldn't dismiss flag.");
  },
});
```

Reinstate is identical except: success toast says "Auction reinstated."; the 409 paths handle both `ALREADY_RESOLVED` and `AUCTION_NOT_SUSPENDED` codes with appropriate copy.

**Both mutations invalidate `["admin", "stats"]` alongside the list cache.** The dashboard counts (`openFraudFlags`, `activeListings`) shift on every resolution and the cache must follow.

---

## 6. Data flow scenarios

### 6.1 Admin signs in for the first time after deploy

1. `AdminBootstrapInitializer.promoteBootstrapAdmins()` ran on app start. Heath's user row got `role = ADMIN`. Existing access token still has `role = USER` claim — but next refresh issues a new token with `ADMIN`. Sign-out + sign-in shortcut the wait.
2. New token includes `role: "ADMIN"`. JWT auth filter maps to `ROLE_ADMIN` authority.
3. `useCurrentUser` returns `{ ..., role: "ADMIN" }`.
4. Heath navigates to `/admin`. `RequireAdmin` lets the page render. `<AdminDashboardPage>` mounts, fires `useAdminStats()`, renders the 3+6 cards once the response lands.

### 6.2 Heath triages an OWNERSHIP_CHANGED_TO_UNKNOWN flag, dismissing as false positive

1. Heath clicks Fraud Flags in sidebar. List page mounts with `?status=open`. `useAdminFraudFlagsList()` returns 7 rows.
2. Heath clicks the top row. URL gains `?flagId=4218`. `useAdminFraudFlag(4218)` returns full detail.
3. Slide-over renders. Reinstate banner shows ("SUSPENDED, will extend endsAt by 4h 23m"). Evidence table renders with `expected_owner` and `detected_owner` UUIDs as links — `expected_owner` links to seller's profile (in `linkedUsers`), `detected_owner` shows plain text + tooltip "(not a registered SLPA user)" because no match.
4. Heath types notes: "Verified via World API that ownership has reverted to seller — false positive from a known transient flicker." Clicks **Dismiss**.
5. `useDismissFraudFlag()` mutation fires. Backend marks resolved with notes, returns updated flag.
6. Toast: "Flag dismissed." Slide-over closes. List cache invalidated → new list (now 6 open). Stats cache invalidated → sidebar badge drops from 7 to 6.
7. **Auction stays SUSPENDED.** Dismiss doesn't change auction state. To reinstate the auction, Heath would have used the Reinstate action instead.

### 6.3 Heath reinstates a wrongly-suspended auction

1. Same flow as 6.2 through step 3.
2. Heath types notes: "Confirmed in-world — parcel ownership matches seller key. Reinstating with bidder time fairness." Clicks **Reinstate auction**.
3. Backend: load flag → not resolved → auction is SUSPENDED → compute suspensionDuration from `auction.suspendedAt` → extend endsAt → flip to ACTIVE → clear suspendedAt → save → re-engage bot monitor (auction tier is BOT so `onAuctionResumed` adds a new MONITOR_AUCTION row) → mark flag resolved with notes → publish `LISTING_REINSTATED` (afterCommit).
4. Frontend: success toast "Auction reinstated." Slide-over closes. List cache + stats cache invalidated. Sidebar badge drops, `Active listings` count goes up by 1.
5. Seller (in-app + SL IM if their preferences allow): "Your auction "Beachfront 1024m²" has been reinstated. All existing bids and proxy maxes are preserved. Ends Apr 26, 18:25 UTC."

### 6.4 Race: monitor re-flags the same auction during reinstate review

1. Heath has the flag 4218 detail open. Auction is SUSPENDED.
2. Meanwhile, ownership monitor tick fires. World API still returns the wrong owner. Monitor sees auction is already SUSPENDED — does NOT re-suspend. Creates a *second* fraud flag (sibling) on the same auction. `auction.suspendedAt` does NOT move.
3. Heath sees `siblingOpenFlagCount: 1` warning banner appear on next detail refetch (or on slide-over open). Banner says "1 other open flag — needs separate review."
4. Heath chooses to dismiss the current flag without reinstating (because the underlying issue is real). Dismiss succeeds. Sibling flag still open.
5. Heath walks to the sibling via Next arrow. Same context, same options.

### 6.5 Race: auction state changes between list-load and reinstate

1. Heath has flag 4218 open. Auction is SUSPENDED in his cached detail.
2. Meanwhile, an admin (or seller-cancellation) flips the auction to CANCELLED in another tab.
3. Heath types notes and clicks Reinstate.
4. Backend: flag still unresolved, but `auction.status != SUSPENDED` → 409 with `code: "AUCTION_NOT_SUSPENDED", currentStatus: "CANCELLED"`.
5. Frontend toast: "Auction state changed (now CANCELLED). Refreshing..." Detail and list caches invalidated. Slide-over re-renders with the new status; Reinstate button is now disabled with tooltip.

### 6.6 Heath bulk-walks the queue with prev/next

1. Heath opens flag #1 from the list (top-of-queue, oldest detected? no — newest). Slide-over opens.
2. Dismiss with notes. Toast. Slide-over auto-advances to next-most-recent flag (`flagId` updates to the next id in the open list).
3. Repeat for flags 2-7. Stats badge in sidebar walks down 7 → 0 as he goes. Active-listings count walks up as Reinstates happen.
4. After last flag is resolved, `flagId` is unset (or set to a no-longer-existing flag) and the slide-over closes.

---

## 7. API surface (full)

Base path: `/api/v1/admin/`. All endpoints require `ROLE_ADMIN` — non-admin gets **403** before controller body runs.

| Method | Path | Body | Response | Errors |
|---|---|---|---|---|
| GET | `/stats` | — | `AdminStatsResponse` | 403 |
| GET | `/fraud-flags?status&reasons&page&size` | — | `PagedResponse<AdminFraudFlagSummaryDto>` | 403, 400 (invalid params) |
| GET | `/fraud-flags/{id}` | — | `AdminFraudFlagDetailDto` | 403, 404 |
| POST | `/fraud-flags/{id}/dismiss` | `{ adminNotes }` | `AdminFraudFlagDetailDto` | 403, 404, 400, 409 (`ALREADY_RESOLVED`) |
| POST | `/fraud-flags/{id}/reinstate` | `{ adminNotes }` | `AdminFraudFlagDetailDto` | 403, 404, 400, 409 (`ALREADY_RESOLVED` or `AUCTION_NOT_SUSPENDED`) |

### Error response shape

`AdminApiError`:

```json
{
  "code": "AUCTION_NOT_SUSPENDED",
  "message": "Auction is currently CANCELLED, cannot be reinstated.",
  "currentStatus": "CANCELLED"
}
```

`code` is always present. `message` is human-readable but not user-facing copy (frontend chooses copy by code). Extra fields are domain-dependent — `currentStatus` for `AUCTION_NOT_SUSPENDED`, none for `ALREADY_RESOLVED`.

`@RestControllerAdvice` (`AdminExceptionHandler`) translates domain exceptions to this shape:

- `FraudFlagAlreadyResolvedException` → 409 `ALREADY_RESOLVED`
- `AuctionNotSuspendedException` → 409 `AUCTION_NOT_SUSPENDED`
- `FraudFlagNotFoundException` → 404
- `MethodArgumentNotValidException` (Bean Validation) → 400 with field errors

---

## 8. Testing strategy

### Backend

#### Unit tests (`*Test.java` with Mockito)

- `AdminBootstrapInitializerTest` — covers (a) empty list logs skip + makes no DB calls, (b) list with mix of present/absent emails — only present rows promoted, count returned matches affected rows, (c) currently-ADMIN rows untouched (verified via repo argument capture), (d) idempotent on re-run (second call promotes 0 if all already admin or absent).
- `AdminStatsServiceTest` — mocks each repo, verifies query routing and DTO assembly.
- `AdminFraudFlagServiceTest` — covers dismiss happy path, dismiss already-resolved (throws), reinstate happy path with `auction.suspendedAt` populated (correct duration math), reinstate fallback to `flag.detectedAt` when `auction.suspendedAt` is null, reinstate auction-not-suspended (throws), reinstate flag-already-resolved (throws), reinstate triggers bot re-engagement only for BOT-tier auctions, reinstate publishes notification afterCommit (verify call ordering).

#### Slice tests (`@WebMvcTest`)

- `AdminStatsControllerTest` — admin token returns 200 with payload, non-admin token returns 403, anonymous returns 401.
- `AdminFraudFlagControllerTest` — same auth gates per endpoint, plus body validation (empty notes → 400, > 1000 char notes → 400), 409 codes serialize correctly into the error response shape.
- `RequireAdminTest` (frontend equivalent) — see Frontend section.

#### Integration tests (`@SpringBootTest`)

- `AdminFraudFlagReinstateIntegrationTest` — full flow: seed user (seller, admin), seed parcel, seed BOT-tier auction in SUSPENDED with `suspendedAt = T - 6h`, seed open flag. Reinstate. Verify (a) auction status = ACTIVE, (b) endsAt = original endsAt + 6h, (c) suspendedAt = null, (d) flag resolved with notes + resolvedBy = admin, (e) BotTask MONITOR_AUCTION row exists for the auction, (f) `LISTING_REINSTATED` notification was published to seller (verify via `NotificationRepository`).
- `AdminBootstrapIntegrationTest` — `@TestPropertySource` with bootstrap-emails, seed users with mix of matching/non-matching emails and roles, fire `ApplicationReadyEvent` (or rely on Spring's own firing in `@SpringBootTest`), verify roles after.
- `AdminStatsIntegrationTest` — seed a known fixture (5 active auctions, 3 active escrows of various states, 2 completed escrows with known amounts), call endpoint, verify exact numbers.

All `@SpringBootTest` tests use `@ActiveProfiles("dev")` to pick up the dev DataSource (FOOTGUNS §F.20 from a previous epic). `@TestPropertySource` lines disabling all schedulers (`slpa.notifications.sl-im.cleanup.enabled=false`, `slpa.escrow.scheduler.timeout.enabled=false`, etc.) propagated per existing convention. Cross-test DB contamination prevented via `@BeforeEach { repo.deleteAll(); }` blocks where applicable.

### Frontend (Vitest + React Testing Library)

- `RequireAdmin.test.tsx` — renders nothing while loading, redirects + renders nothing for `role=USER`, renders children for `role=ADMIN`.
- `useAdminStats.test.tsx` — hook returns parsed response, MSW handler fixture.
- `QueueCard.test.tsx` + `StatCard.test.tsx` — variant rendering, accent application.
- `FraudFlagFilters.test.tsx` — status pill click updates URL state, reason multi-select, clear-all.
- `FraudFlagSlideOver.test.tsx` — opens on `?flagId=N`, closes on ESC + backdrop, prev/next arrows update flagId to neighbors in current list, Reinstate button disabled state for non-SUSPENDED auctions, sibling-flag warning visible when count > 0, evidence rendering: linked user UUIDs render as `<a>` to `/users/{id}`, unlinked UUIDs render plain.
- `useDismissFraudFlag.test.tsx` + `useReinstateFraudFlag.test.tsx` — optimistic invalidation of both list and stats keys, 409 `ALREADY_RESOLVED` and `AUCTION_NOT_SUSPENDED` toast paths, rollback on generic error.
- `FraudFlagsListPage.integration.test.tsx` — full page mount with seeded MSW fixtures, click row → slide-over opens with detail, dismiss → list updates, stats badge updates.

### Manual test plan (run before merge)

A separate `MANUAL_TESTS.md` lives in the PR description; key cases:

1. Cold deploy → bootstrap promotes the 4 emails. Verify by checking each user's `role` in DB.
2. Demote one bootstrap admin via DB flip + tv bump → restart app → user is NOT re-promoted (idempotent guard works).
3. Sign in as admin → see `/admin` link in nav (requires sub-spec 1 nav update too — see §9). Sign in as USER → no admin link, navigating to `/admin` redirects to `/`.
4. Seed an OWNERSHIP_CHANGED_TO_UNKNOWN flag via dev endpoint (existing `POST /api/v1/dev/ownership-monitor/run` with a parcel whose owner changed). Verify it appears in the queue.
5. Dismiss flag → seller gets no notification, auction stays SUSPENDED, queue count drops.
6. Reinstate flag → seller gets in-app + SL IM notification, auction goes ACTIVE, endsAt extended, bot monitor task spawned.
7. Walk a queue of 5 flags using prev/next arrows.

---

## 9. Frontend nav touchpoint

The existing `<MainNav>` (top-of-page nav) needs an "Admin" link visible only to admins. Implementation: read `useCurrentUser().data?.role`, conditionally render `<Link href="/admin">Admin</Link>` when `role === "ADMIN"`. One file edit, two-line addition. Tests cover both states.

---

## 10. Edge cases & risk mitigation

### 10.1 Historical SUSPENDED auctions with null `suspendedAt`

Rows that became SUSPENDED before this sub-spec landed will have `suspendedAt = null`. Reinstate falls back to `flag.detectedAt` for the duration math. This is fine for ownership-monitor flags (detectedAt = suspension time) but slightly stale for cases where the flag was created later than the suspension. Acceptable trade-off — historical edge case, won't repeat once the column is in use.

Backfill is **not** scripted because:
- Production has no rows yet (project pre-launch).
- Even if it did, `flag.detectedAt` is the closest proxy and matches actual behavior at flag-creation time.

### 10.2 Sibling open flags + reinstate

Reinstating one flag does NOT auto-resolve sibling open flags. The slide-over banner warns the admin. If sibling reasons are still real, the next monitor tick re-suspends and creates a fresh flag. Self-healing.

### 10.3 endsAt arithmetic clamping

If somehow `endsAt + suspensionDuration` lands in the past (paranoid case — would require both endsAt and suspendedAt to be in the past, which shouldn't happen given suspendedAt is only set on currently-active auctions), reinstate clamps endsAt to `now.plusHours(1)` and logs WARN. This is defensive — won't trigger under normal flow.

### 10.4 Bot monitor re-engagement on non-BOT auctions

`BotMonitorLifecycleService.onAuctionResumed` is a no-op for non-BOT-tier auctions. The reinstate path calls it unconditionally — the service itself short-circuits. Test covers both branches.

### 10.5 Demoting an admin via DB flip

To revoke admin access immediately:
1. `UPDATE users SET role = 'USER', token_version = token_version + 1 WHERE id = ?;`
2. The next access-token refresh fails (tv mismatch). User must sign in again.

`role = 'USER'` alone is insufficient — the existing access token still carries `role: "ADMIN"` until expiry. The tv bump invalidates outstanding tokens.

A future admin endpoint (`POST /api/v1/admin/users/{id}/demote`) lands in sub-spec 2 alongside the broader user-management surface; it'll perform both ops in one transaction.

### 10.6 Bootstrap config fights deliberate demotion

If an admin is demoted via the path above and then the app restarts, `AdminBootstrapInitializer` runs again. The promote-only-currently-USER guard means the demoted user **does** get re-promoted. This is by design — bootstrap is a forward push. To permanently demote a bootstrap email, remove it from the config and bump tv.

If this becomes painful in practice, sub-spec 4's audit log + admin-management surface will introduce a "do not auto-promote" flag column, but that's premature for sub-spec 1.

### 10.7 Stats query cost at scale

Eight `count(*)` and two `sum(amount)` queries per page load. At current volume, negligible. At 1M+ row scale, may need a 30-second Redis cache. Premature today — added to deferred work if it becomes real.

### 10.8 Slide-over `?flagId=N` for a flag the admin can't see

If an admin lands on `/admin/fraud-flags?flagId=42` but flag 42 is not in their current filtered list (e.g., they have `status=open` but flag 42 is resolved), the detail query still works (the GET endpoint doesn't apply the list filter). Slide-over renders the flag normally. Prev/Next arrows step through the *current filtered list* — if flag 42 isn't in it, prev/next are disabled with a tooltip. Closing the slide-over unsets `flagId`; back to a clean filtered list.

If flag id doesn't exist (404), the slide-over shows an error state with a Close button.

### 10.9 Notes textarea length validation drift

Backend caps at 1000 chars. Frontend cap is also 1000. If they drift, the user gets a 400 from the server with field details. The frontend already handles validation 400s for other forms — same pattern.

---

## 11. Sub-spec wrap-up checklist

Before merging the PR for sub-spec 1:

- [ ] All backend tests pass (`./mvnw test`).
- [ ] All frontend tests pass (`npm test`).
- [ ] Frontend lint passes (`npm run lint`).
- [ ] Manual test plan run end-to-end (cold-bootstrap → dismiss → reinstate → demote-and-revoke).
- [ ] `docs/implementation/DEFERRED_WORK.md` updated:
  - **Closed:** "Admin dashboard for fraud_flag resolution" (Epic 03 sub-spec 2).
  - **Modified:** any other Epic 10–targeted entries that explicitly depend on admin role / route gating land in sub-spec 1 — re-pointed to "blocked on sub-spec 1, remaining work in sub-spec X."
- [ ] `docs/implementation/FOOTGUNS.md` updated with new entries:
  - **Bootstrap config fights deliberate demotion of a bootstrap email.** Pattern: bootstrap-emails are forward-promoted only — to permanently demote, remove from config + bump tv. (§10.6)
  - **Admin role demote requires `role=USER` + `tv++`.** Existing tokens carry the old role until expiry; only tv invalidates them. (§10.5)
  - **`@TestPropertySource` propagation for new admin endpoints in slice tests.** Same pattern as Epic 09 sub-spec 3 — any new `@WebMvcTest` slice that pulls in the security config has to import the admin filter beans (or override them). Implementer should grep for the pattern when adding tests.
  - **Stats endpoint not cached this sub-spec — every dashboard load runs 10 queries.** Acceptable today; if it bites, add Redis cache, don't refactor controllers.
- [ ] `docs/initial-design/DESIGN.md` §8 gets a "Notes (Epic 10 sub-spec 1)" subsection summarizing what landed and what's deferred.
- [ ] `frontend/AGENTS.md` Next.js 16 reminder honored — implementer reads `frontend/node_modules/next/dist/docs/` before writing any new frontend code.
- [ ] `CLAUDE.md` (root) updated to mention admin role + bootstrap config under "Implementation Status" if the existing section structure warrants.

---

## 12. Documentation updates (in this sub-spec PR)

- `docs/initial-design/DESIGN.md` — append "Notes (Epic 10 sub-spec 1)" subsection to §8.
- `docs/implementation/DEFERRED_WORK.md` — see §11 above.
- `docs/implementation/FOOTGUNS.md` — append F.99–F.102 (next sequential numbers — implementer verifies last-used number).
- `CLAUDE.md` (root) — short paragraph under existing "Backend Stack Details" or new "Admin Foundation" subsection noting role enum, bootstrap config, admin route gating.

---

## 13. Risks & rollback

**Rollback:** revert the merge. JPA `ddl-auto: update` does not auto-drop the new columns (`User.role`, `Auction.suspendedAt`, `FraudFlag.adminNotes`) — they'd remain in the schema as orphan columns. That's safe: Hibernate's annotation-driven approach simply ignores schema columns it doesn't know about. A follow-up hand-rolled SQL would clean them up if rollback is permanent.

**Risk: bootstrap config promotes the wrong user.** Mitigation: emails are exact-match (no LIKE), guard requires currently-USER (won't accidentally clobber a real ADMIN), and the 4 emails are heath@*. Worst case: a future user signs up with one of these emails and gets promoted. Solution: don't allow bootstrap-list emails to register through the public flow — but that's overkill for 4 specifically-curated emails. Acceptable today; revisit if bootstrap list grows.

**Risk: reinstate adjusts endsAt past sane limits.** Mitigation: §10.3 clamping. Test covers it.

**Risk: stats query exhibits N+1.** Mitigation: each query is a single aggregate `count(*)` or `sum(amount)` against a single table — no joins, no N+1 surface. Verified in integration test by enabling Hibernate SQL logging.

---

## 14. Open questions / explicitly resolved

- **Email channel removed from roadmap.** ✅ resolved in Epic 09 sub-spec 3 — SL IM forwards to email natively. Reinstate notification rides the same channel.
- **Stats period selector (lifetime vs 30d).** ✅ explicitly out of scope — lifetime only.
- **Per-tier breakdowns on stats.** ✅ explicitly out of scope.
- **`admin_actions` cross-entity audit table.** ✅ deferred to sub-spec 2.
- **Confirmed-fraud disposition.** ✅ deferred to sub-spec 2 alongside ban-creation consumer.
- **Reason badge color-coding.** ✅ resolved — 4-family color scheme.
- **Slide-over prev/next arrows.** ✅ resolved — yes, walk the current filtered list.
- **Admin user-detail page.** ✅ deferred to sub-spec 2 — sub-spec 1 links UUIDs to existing `/users/{id}`.

---

## 15. Glossary

| Term | Meaning |
|---|---|
| Bootstrap email | An email in `slpa.admin.bootstrap-emails` that gets auto-promoted to ADMIN on app startup if currently a USER. |
| Promote-only-currently-USER guard | The bootstrap initializer's WHERE clause that prevents re-promoting users who were deliberately demoted. |
| Reinstate | Admin action that resolves a fraud flag AND restores the linked auction from SUSPENDED to ACTIVE, extending endsAt by suspension duration. |
| Sibling open flag | Another open fraud flag on the same auction as the one being reviewed. |
| Linked user | A user resolved from an SL avatar UUID found in fraud-flag evidence — backend matches via `UserRepository.findBySlAvatarUuid`. |
| Suspension duration | `now - auction.suspendedAt` (or `flag.detectedAt` fallback). The amount by which `endsAt` is extended on reinstate. |
