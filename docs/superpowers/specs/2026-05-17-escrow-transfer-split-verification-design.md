# Escrow Transfer Split + Sell-To Verification (Design)

**Date:** 2026-05-17
**Status:** Approved — brainstormed + decided 2026-05-17 (user granted full autonomy for the remainder; open interpretation points captured in §13 for later user review)
**Author:** Heath / Claude
**Scope:** Backend (escrow state, bot task revival + result callback, step-3 polling cadence, escalation, admin endpoints, notifications/SLURL), Bot (.NET VerifySellTo handler), Frontend (stepper rename, split state cards, admin escalation queue), Docs (README, FOOTGUNS/DEFERRED if needed), Postman.

## 1. Goal

Split the single escrow "Transfer" step into two explicitly verified steps:

1. **Set Sell To** — the seller sets the parcel "Sell To" the winning buyer at L$0. Verified by the **bot** (the SL World API cannot read a parcel's authorized-buyer field; only `IBotSession.ReadParcelAsync → ParcelSnapshot.AuthBuyerId` can).
2. **Buy Parcel** — the buyer purchases the parcel; ownership flips to the buyer. Verified by the **World API** owner-UUID poll (the existing mechanism).

Every escrow notification (in-app + SL IM) carries a SLURL to the parcel.

This intentionally **re-introduces the bot into the escrow path**, reversing part of the 2026-05-16 ownership-only-verification spec (which retired `MONITOR_ESCROW`). The justification is the deciding technical fact above: "Sell To" is invisible to the World API. The new bot task is single-purpose (`VERIFY_SELL_TO`) and is **not** a revival of the old multi-check `MONITOR_ESCROW`.

## 2. Decisions (locked during brainstorming)

1. **Bot is a hard gate for Step 2.** The escrow cannot leave the Set-Sell-To sub-phase until the bot (or an admin override) confirms it. Step-3 owner polling is inert until then.
2. **Escape hatch = a dedicated, no-fault "request manual review" escalation**, separate from the dispute flow. It does **not** freeze the escrow and does **not** imply seller fault. Available to **both** seller and buyer. It has its own admin-panel queue with admin force-advance actions.
3. **One uniform two-step flow for all listings.** Case-1 (avatar-owned) and case-3 (SL-group-owned) are identical here — both checks are pure UUID equality and do not care about the pre-transfer owner shape (already tracked by existing escrow code, unchanged).
4. **Step 2 passes only if** `AuthBuyerId == winner` **AND** `SalePrice == 0` **AND** the parcel is actually set for sale. Any other combination is "not correctly set yet," surfaced to the seller with a precise reason.
5. **Only a definitive negative consumes a manual attempt.** A manual verify burns one of the 3 attempts only when the check actually ran and the condition was genuinely unmet. Infrastructure failures (bot offline, teleport/region failure, access-denied, World-API timeout) never burn an attempt; if persistent they auto-open an escalation.
6. **State model = keep `TRANSFER_PENDING`, add a `sellToConfirmedAt` timestamp.** No `EscrowState` enum or `ALLOWED_TRANSITIONS` change. Sub-phase is derived from timestamps, mirroring the existing stepper pattern. The hard gate is enforced by "step-3 owner poll only runs when `sellToConfirmedAt IS NOT NULL`."
7. **Single deadline, reset on each phase entry.** `transferDeadline = now + 72h` is (re)stamped at escrow funding (start of Set Sell To) and again when `sellToConfirmedAt` is stamped (start of Buy Parcel), so neither party is squeezed by the other's slowness. `EscrowTimeoutTask` behavior is otherwise unchanged.

## 3. Sub-phase model

Backing state is `TRANSFER_PENDING` for the entire transfer. Sub-phase derives from timestamps:

| fundedAt | sellToConfirmedAt | transferConfirmedAt | Sub-phase | Active mechanism |
|---|---|---|---|---|
| set | null | null | **Set Sell To** | Bot `VERIFY_SELL_TO` task |
| set | set | null | **Buy Parcel** | World-API owner poll (variable cadence) |
| set | set | set | Payout pending | Existing payout callback (unchanged) |

Terminal interrupts (`DISPUTED`/`FROZEN`/`EXPIRED`) are unchanged and can occur from either sub-phase.

## 4. Data changes

One Flyway migration `V<next>__escrow_transfer_split.sql` (number resolved at implementation time; all columns nullable / default-0 → no backfill, consistent with the prod "all listings cancelled" posture). Any pre-existing `TRANSFER_PENDING` row would read as the Set-Sell-To sub-phase with no bot task (tasks are created only on new fundings); prod has no active listings and dev follows the DB-wipe policy, so this is a non-issue.

### 4.1 `escrow` columns

| Column | Type | Purpose |
|---|---|---|
| `sell_to_confirmed_at` | timestamptz null | Hard gate. Stamped by bot `SELL_TO_OK` or admin force-confirm. |
| `sell_to_last_result` | varchar null | Last definitive bot outcome for seller-facing copy (`SELL_TO_NOT_SET`/`WRONG_BUYER`/`PRICE_NOT_ZERO`). |
| `sell_to_last_checked_at` | timestamptz null | When the last step-2 bot read completed. |
| `sell_to_verify_attempts` | int default 0 | Seller manual Set-Sell-To attempts consumed (cap configurable, default 3). |
| `buy_verify_seller_attempts` | int default 0 | Seller manual Buy-Parcel attempts consumed. |
| `buy_verify_buyer_attempts` | int default 0 | Buyer manual Buy-Parcel attempts consumed. |
| `consecutive_sell_to_bot_failures` | int default 0 | Step-2 bot infra-failure streak; mirrors existing `consecutive_world_api_failures` (step-3). Threshold → auto-escalation. |
| `next_owner_check_at` | timestamptz null | Per-escrow step-3 pacing cursor. |
| `manual_verify_pending` | boolean default false | Set when a manual Step-2 press expedites the bot task; gates whether the next definitive-negative bot result consumes an attempt. |

### 4.2 New entity `EscrowManualReview` (table `escrow_manual_reviews`)

`id` (BaseEntity), `publicId`, `escrow_id` FK, `requested_by_user_id` (null for system), `requested_role` (`SELLER`/`BUYER`/`SYSTEM`), `step` (`SET_SELL_TO`/`BUY_PARCEL`), `reason` (`USER_REQUESTED`/`BOT_PERSISTENT_FAILURE`/`WORLD_API_PERSISTENT_FAILURE`), `status` (`OPEN`/`RESOLVED`/`DISMISSED`), `resolved_by_admin_id` null, `resolution` (`FORCE_CONFIRM_SELL_TO`/`FORCE_COMPLETE_TRANSFER`/`REFUND_WINNER`/`DISMISS`) null, `admin_notes` text null, `resolved_at` null, plus `createdAt`/`updatedAt` from BaseMutableEntity. At most one `OPEN` review per escrow (idempotent create).

## 5. Bot — `VERIFY_SELL_TO` task

### 5.1 Backend plumbing
- Revive `BotTaskType.VERIFY_SELL_TO` (Java enum; currently empty scaffolding) and the .NET `BotTaskType` + `BotTaskResponse` model.
- **Task creation:** when `EscrowService` transitions an escrow to `TRANSFER_PENDING` at funding, create one `VERIFY_SELL_TO` `BotTask`: `parcelUuid`, `regionName`, `positionX/Y/Z` (from `AuctionParcelSnapshot`), `expectedWinnerUuid = winner.slAvatarUuid`, `nextRunAt = now`, `recurrenceIntervalSeconds = slpa.escrow.sell-to.bot-recurrence` (default `PT30M`). One open task per escrow.
- **Result callback (rebuilt):** `POST /api/v1/bot/tasks/{taskId}/result` (bearer auth via existing `BotSharedSecretAuthorizer`), body `BotTaskResultRequest { outcome, observedOwnerUuid?, observedAuthBuyerUuid?, observedSalePrice?, observedForSale? }`. Idempotent on terminal task state.

### 5.2 Bot handler (.NET `VerifySellToHandler`, wired into `TaskLoop.DispatchAsync`)
1. `TeleportAsync(regionName, x, y, z)` (existing; 30s, rate-limited 6/min, skips same-sim).
2. `ReadParcelAsync(x, y)` → `ParcelSnapshot`.
3. Classify and report via new `IBackendClient.ReportTaskResultAsync`:
   - `OwnerId == expectedWinnerUuid` → **`OWNER_ALREADY_WINNER`**
   - not for-sale (`ParcelFlags.ForSale` bit clear in `ParcelSnapshot.Flags`) OR `AuthBuyerId == Guid.Empty` → **`SELL_TO_NOT_SET`**
   - `AuthBuyerId != expectedWinnerUuid` → **`WRONG_BUYER`**
   - `SalePrice != 0` → **`PRICE_NOT_ZERO`**
   - else → **`SELL_TO_OK`**
   - `TeleportResult` AccessDenied → **`ACCESS_DENIED`**; RegionNotFound / parcel unresolved → **`PARCEL_NOT_FOUND`**; any other exception/timeout/session-loss → **`BOT_ERROR`** (or no report → backend in-progress-timeout sweep reclaims).

### 5.3 Backend outcome application (under escrow `PESSIMISTIC_WRITE` lock)
- **`SELL_TO_OK`** → `EscrowService.confirmSellTo`: stamp `sellToConfirmedAt`, **reset `transferDeadline = now + 72h`**, set `nextOwnerCheckAt = now`, mark bot task `COMPLETED` (recurrence stops), reset `consecutiveSellToBotFailures = 0`, clear `manual_verify_pending`, publish `ESCROW_SELL_TO_SET` to the buyer (IM + in-app, with SLURL), broadcast escrow envelope.
- **`OWNER_ALREADY_WINNER`** → `confirmSellTo` then existing `confirmTransfer` (queues payout). Covers a full seller→buyer transfer completed before we observed Step 2.
- **`SELL_TO_NOT_SET` / `WRONG_BUYER` / `PRICE_NOT_ZERO`** (definitive negative) → store `sell_to_last_result` + `sell_to_last_checked_at`; if `manual_verify_pending` → consume one `sell_to_verify_attempts`, clear the flag; reschedule task `nextRunAt = now + recurrence` if before deadline; broadcast.
- **`ACCESS_DENIED` / `BOT_ERROR`** → increment `consecutive_sell_to_bot_failures`; do **not** consume an attempt; clear `manual_verify_pending`; at `>= slpa.escrow.sell-to.bot-failure-threshold` (default 5) auto-open an `EscrowManualReview(reason=BOT_PERSISTENT_FAILURE, step=SET_SELL_TO)`; reschedule.
- **`PARCEL_NOT_FOUND`** → same streak handling; at threshold `freezeForFraud(PARCEL_DELETED)` (consistent with `EscrowOwnershipCheckTask`'s parcel-deleted handling).

### 5.4 Manual "Verify Sell To" (seller only)
`POST /api/v1/auctions/{auctionPublicId}/escrow/verify-sell-to`. Guards: caller is seller; sub-phase is Set-Sell-To (`sellToConfirmedAt` null, not terminal). If `sell_to_verify_attempts >= cap` → `409` with escalation hint. Else set `manual_verify_pending = true`, bump the open `VERIFY_SELL_TO` task `nextRunAt = now`, return `202` + current status. The result arrives asynchronously via the bot callback + STOMP broadcast; the page refetches. Attempt consumption per §5.3. The next definitive-negative result while `manual_verify_pending` is true consumes one attempt — given the 30-min auto-cadence the chance of a coincident non-manual run landing first is negligible, and either way the seller did request a check that returned negative, so consuming one attempt is the intended semantics.

## 6. Step 3 — Buy Parcel (World-API owner poll, variable cadence)

- `EscrowOwnershipMonitorJob` sweep predicate becomes: `state = TRANSFER_PENDING AND sell_to_confirmed_at IS NOT NULL AND transfer_confirmed_at IS NULL AND (next_owner_check_at IS NULL OR next_owner_check_at <= now)`. Before `sellToConfirmedAt`, step-3 polling does nothing (**this is the hard gate**).
- After each check, set `next_owner_check_at`: if `now - sellToConfirmedAt < slpa.escrow.buy-parcel.fast-window` (default `PT1H`) → `+ fast-cadence` (`PT5M`); else `+ slow-cadence` (`PT30M`). The job still fires every `PT5M` but now respects per-escrow pacing (also reduces World-API load vs. today's "every TRANSFER_PENDING every 5 min").
- Outcomes are exactly today's `EscrowOwnershipCheckTask`: `owner == winner` → `confirmTransfer` → payout → `COMPLETED`; `owner == seller/group` → `stampChecked`; `owner == other` → `freezeForFraud(UNKNOWN_OWNER)`; not found → `PARCEL_DELETED`; persistent World-API failure → existing `WORLD_API_PERSISTENT_FAILURE` freeze (a frozen escrow already routes to admin; we do not duplicate that into the new queue).

### 6.1 Manual "Verify Purchase" (buyer and seller)
`POST /api/v1/auctions/{auctionPublicId}/escrow/verify-transfer`. Guards: caller is seller or buyer; `sellToConfirmedAt` set (else `409` — cannot verify Step 3 before Step 2); not terminal; that role's attempts `< cap`. Runs the World-API owner check **inline** (sub-second). `owner == winner` → `confirmTransfer` → payout. Definitive negative (owner still seller/group) → consume that role's attempt (`buy_verify_seller_attempts` / `buy_verify_buyer_attempts`). `owner == other` → existing `freezeForFraud`. World-API failure → no consume, retryable error.

## 7. Escalation + admin panel

- **User-initiated:** `POST /api/v1/auctions/{auctionPublicId}/escrow/manual-review` (seller or buyer; optional note). Creates an `OPEN` `EscrowManualReview(reason=USER_REQUESTED, step=current)`. Idempotent (returns the existing open review). Does **not** freeze the escrow or alter the deadline; bot/poll keep running so it can still self-resolve.
- **System-initiated:** auto-created on bot persistent failure (§5.3).
- **Frontend surfacing:** the "Request manual review" control appears when manual attempts are exhausted, when an infra-failure streak is active, or always as a secondary link (low-emphasis). Both parties see review status on the escrow page.
- **Admin endpoints** `/api/v1/admin/escrow-reviews`: `GET` list (filter `status`), `GET /{publicId}` detail (escrow snapshot + observed evidence), `POST /{publicId}/resolve` `{ action, notes }`:
  - `FORCE_CONFIRM_SELL_TO` → `confirmSellTo(...)` (same path as bot `SELL_TO_OK`).
  - `FORCE_COMPLETE_TRANSFER` → `confirmTransfer(...)` → payout (admin verified in-world).
  - `REFUND_WINNER` → existing expire/refund path → `EXPIRED`, winner refunded.
  - `DISMISS` → close, no state change.
  - All set `status`, `resolved_by_admin_id`, `resolution`, `admin_notes`, `resolved_at`, write an admin audit entry, broadcast.
- **Admin frontend:** new `/admin/escrow-reviews` list + `/admin/escrow-reviews/[publicId]` detail with a resolve panel, mirroring the existing `AdminDisputesTable` / `AdminDisputeDetailPage` / `ResolutionPanel` patterns. Add an admin-nav entry.

## 8. Notifications + SLURL

- **All escrow messages carry a SLURL.** Add a backend `SlurlBuilder` (mirrors `frontend/src/lib/sl/slurl.ts`: `mapUrl` = `https://maps.secondlife.com/secondlife/{region}/{x}/{y}/{z}`, `viewerUrl` = `secondlife:///app/teleport/{region}/{x}/{y}/{z}`; region-centre `128/128/0` fallback). `NotificationDataBuilder` adds `parcelMapUrl` + `parcelViewerUrl` to the data blob for every `NotificationGroup.ESCROW` category, sourced from `AuctionParcelSnapshot` (`slurl` column + region/position). SL IM body assembly appends a `Parcel: <parcelViewerUrl>` line (in-world clickable) for escrow categories; the in-app notification renderer shows a "View parcel in Second Life" link from `parcelMapUrl`.
- **New category `ESCROW_SELL_TO_SET`** (`NotificationGroup.ESCROW`): fired to the **buyer** when `sellToConfirmedAt` is stamped (bot or admin). `SlImLinkResolver` → `/auction/{publicId}/escrow`. `NotificationPublisher.escrowSellToSet(buyerUserId, auctionId, escrowId, parcelName)`. Body: seller has set the parcel for sale to you; go buy it now; **only purchase if the price is L$0**; includes SLURL.
- **Seller funding copy:** extend the existing `ESCROW_FUNDED` (already seller-facing) body to include the Set-Sell-To recipe summary + the winner's SL avatar name + SLURL (data blob already carries `winnerSlAvatarName` via `toStatusResponse`; thread it into the funded notification).
- **Completion:** existing `ESCROW_TRANSFER_CONFIRMED` / `ESCROW_PAYOUT` to the seller, unchanged except for the global SLURL injection.

## 9. Frontend

- `types/escrow.ts` `EscrowStatusResponse`: add `sellToConfirmedAt`, `sellToLastResult`, `sellToVerifyAttemptsRemaining`, `buyVerifySellerAttemptsRemaining`, `buyVerifyBuyerAttemptsRemaining`, `manualReview: { status, step } | null`, `parcelMapUrl`, `parcelViewerUrl`.
- `EscrowStepper.tsx`: 4 nodes — **Payment → Set Sell To → Buy Parcel → Complete** — derived from `fundedAt` / `sellToConfirmedAt` / `transferConfirmedAt` / `completedAt`. Interrupt collapse logic extended to include the Set-Sell-To node among preceding-complete nodes.
- `TransferPendingStateCard.tsx`: split by `sellToConfirmedAt`:
  - **Set Sell To:** seller card = numbered recipe (About Land → Sell Land → Sell to: `<winnerSlAvatarName>` with copy button → price **L$0** → confirm), parcel SLURL, **Verify Sell To** button showing "N of 3 manual attempts left" and the "after that the bot auto-checks every 30 min" warning, last-result inline error when present, deadline badge, "Request manual review" link. Buyer card = waiting copy + SLURL.
  - **Buy Parcel:** buyer card = SLURL + "go buy it now, only if price is L$0" + **Verify purchase** button (3 attempts) + request-review link. Seller card = waiting + **Verify purchase** button (3 attempts) + request-review link.
  - **Post-confirm** (`transferConfirmedAt` set): existing payout-pending card, unchanged.
- Hooks: `useVerifySellTo`, `useVerifyTransfer`, `useRequestManualReview` (POST + refetch); reuse the existing escrow query + STOMP subscription so async bot results auto-refresh the page.
- `escrowBannerCopy.ts` + escrow state `types.ts`: add the two sub-phase labels/copy. No emojis; icons from `@/components/ui/icons.ts`.
- Admin: `app/admin/escrow-reviews/page.tsx` (force-dynamic) + table + `[publicId]` detail + resolve panel; `lib/admin/escrowReviews.ts`; admin-nav entry. Mirror disputes patterns and SSR caveats (`apiUrl()` for any image, `force-dynamic`).

## 10. Config (`application.yml`, `slpa.escrow.*`)

```
sell-to:
  bot-recurrence: PT30M
  bot-failure-threshold: 5
buy-parcel:
  fast-cadence: PT5M
  fast-window: PT1H
  slow-cadence: PT30M
manual-verify-attempts: 3
```

`transfer-deadline-hours` stays 72 (existing `TRANSFER_DEADLINE_HOURS`), now re-stamped on each phase entry (§2.7). Bound via the existing `EscrowConfigProperties` record (extend it).

## 11. Edge cases

- **Transfer completed before Step 2 observed** → bot `OWNER_ALREADY_WINNER` short-circuits both steps (§5.3).
- **Seller reverts Sell To after confirmation** → Step-3 owner poll simply never sees the flip; deadline (reset at Step-2 confirm) eventually expires → winner refunded. Acceptable.
- **Buyer pays a non-zero price despite the warning** → out of our control; owner still flips → escrow completes; the buyer's in-world overpayment is a warned, self-inflicted loss. Step-2 price gate (§ decision 4) is the primary defense.
- **Open manual review + deadline elapses** → `EscrowTimeoutTask` still expires/refunds; the review is auto-marked `RESOLVED` with `resolution=REFUND_WINNER` (system) so the admin queue doesn't show stale items. Admins are expected to act within the (reset) 72h window or choose `REFUND_WINNER`.
- **Manual press during a bot outage** → infra outcome doesn't consume an attempt and clears `manual_verify_pending`; user may retry; persistent outage auto-escalates.

## 12. Non-goals

- Changing the in-world transfer mechanism (seller still sets Sell To; buyer still buys; bot never buys land).
- Reviving the old multi-check `MONITOR_AUCTION` / `MONITOR_ESCROW` (auction-active monitoring stays World-API-only per the 2026-05-16 spec).
- Changing dispute or payout mechanics beyond reusing `confirmTransfer` / expire-refund.
- A general ticketing system — the escalation is escrow-scoped only.

## 13. Open interpretation points (for later user review)

1. **Deadline-reset wording.** The user's note — "reset after each step (so seller has time to comply after buyers steps are completed)" — is implemented as: re-stamp `transferDeadline = now + 72h` at funding and again at `sellToConfirmedAt`. There is no seller action after the buyer's step, so no third reset is applied. Flag if a different intent.
2. **"Steps markdown" vs. plan.** Consolidated into the writing-plans implementation plan (`docs/superpowers/plans/2026-05-17-escrow-transfer-split-verification-plan.md`) rather than a separate redundant steps file, to avoid two drifting step lists.
3. **Step-3 persistent World-API failure** keeps the existing fraud-freeze path (frozen → admin) rather than routing into the new escalation queue, to avoid contradicting the established 2026-05-16 fraud-safety design. Revisit if the new queue should subsume it.

## 14. Implementation outline (ordered; full plan in the writing-plans doc)

1. Migration + `Escrow` columns + `EscrowManualReview` entity/repo.
2. `EscrowConfigProperties` + `application.yml` keys; backend `SlurlBuilder`.
3. `EscrowService.confirmSellTo` + deadline-reset wiring at funding and Step-2 confirm.
4. Revive `BotTaskType.VERIFY_SELL_TO`; task creation at funding; result endpoint + `BotTaskResultRequest`; outcome application.
5. Bot `.NET` `VerifySellToHandler` + `BotTaskType`/model + `IBackendClient.ReportTaskResultAsync` + `TaskLoop` wiring.
6. Step-3 sweep predicate + variable cadence (`nextOwnerCheckAt`).
7. Manual endpoints: `verify-sell-to`, `verify-transfer`, `manual-review`.
8. Escalation entity flows + admin endpoints + admin service.
9. Notifications: `ESCROW_SELL_TO_SET`, SLURL injection, `ESCROW_FUNDED` copy.
10. Frontend: DTO/types, stepper, split state cards, hooks, admin queue UI.
11. Tests (backend unit/integration, bot handler, frontend component), Postman mirror, README + docs sweep.
