# Auction Status State Machine Rewire

**Date:** 2026-05-17
**Status:** Signed off — implementation in progress. Sign-off answers recorded in §9.

## 1. Problem

The `AuctionStatus` enum has 14 values, but only 7 are ever set by code:
`DRAFT`, `DRAFT_PAID`, `VERIFICATION_PENDING`, `VERIFICATION_FAILED`, `ACTIVE`,
`ENDED`, `CANCELLED`, `SUSPENDED`. The other 6 — `ESCROW_PENDING`,
`ESCROW_FUNDED`, `TRANSFER_PENDING`, `COMPLETED`, `EXPIRED`, `DISPUTED` — are
defined and partially referenced (e.g. `LOCKING_STATUSES`, the partial
unique index DDL) but no path transitions an auction into them. The
auction sits at `ENDED` through the entire escrow lifecycle while the
real state is carried on the `escrows` table.

This produces a concrete operational trap: an auction at `ENDED` holds its
parcel-UUID lock indefinitely via the partial unique index +
`AuctionVerificationService.assertParcelNotLocked`, even when the escrow
has reached a terminal failure state and the seller still owns the parcel.
In prod, a case-3 group auction whose escrow froze (post-`UNKNOWN_OWNER`
incident, fixed in PR #317) blocked the seller from re-listing the same
parcel; cancelling the listing through the admin panel also failed because
`cancelByAdmin` rejects `ENDED`. Two symptoms, one root cause: the auction
status doesn't reflect the actual phase of the listing.

Same trap quietly applies to every `NO_BIDS` / `RESERVE_NOT_MET` close
(parcel locked forever even though no sale happened) and to any
successfully-completed sale where the winner later wants to re-list.

## 2. Goal

Make the auction-status state machine reflect the listing's real phase,
so the parcel-lock decision is `status ∈ {ACTIVE, TRANSFER_PENDING,
DISPUTED}` — a single `existsBy...` query, no escrow join.

## 3. Final enum

```
DRAFT                  pre-active, no fee paid
DRAFT_PAID             fee paid, awaiting verification
VERIFICATION_PENDING   sync verify in flight
VERIFICATION_FAILED    verify failed, retryable

ACTIVE                 live auction accepting bids

TRANSFER_PENDING       escrow funded, seller expected to transfer
DISPUTED               escrow dispute open

COMPLETED              terminal: sale concluded, payout done
EXPIRED                terminal: no-bids / reserve-not-met / transfer timeout / dispute-refund
FROZEN                 terminal: admin/system freeze (UNKNOWN_OWNER / PARCEL_DELETED / WORLD_API_PERSISTENT_FAILURE)
CANCELLED              terminal: cancelled by seller / admin / broker
SUSPENDED              admin-suspended (reversible to ACTIVE)
```

**Dropped:** `ENDED`, `ESCROW_PENDING`, `ESCROW_FUNDED`. Wallet-only escrow
funding makes the FUNDED/PENDING stops atomic and unobservable. `ENDED` is
replaced by the specific terminal/in-flight status that reflects what
actually happened.

**Added:** `FROZEN`. Mirrors `escrow.state = FROZEN`. Distinct from
`EXPIRED` so audit / UI can tell "ended naturally without a sale" apart
from "frozen by admin or system due to a fraud / ownership / API
incident."

## 4. Transitions

```
DRAFT ──> DRAFT_PAID ──> VERIFICATION_PENDING ──> ACTIVE
                                                 └─> VERIFICATION_FAILED ──> VERIFICATION_PENDING (retry)

ACTIVE ──> TRANSFER_PENDING        (close with bids, reserve met / buy-now)
       ──> EXPIRED                 (close with NO_BIDS or RESERVE_NOT_MET)
       ──> CANCELLED               (seller / admin / broker cancel)
       ──> SUSPENDED               (admin suspend)

TRANSFER_PENDING ──> COMPLETED     (transfer confirmed, payout queued)
                 ──> DISPUTED      (dispute filed)
                 ──> EXPIRED       (transfer deadline missed; escrow refund issued)
                 ──> FROZEN        (admin/system freeze; escrow refund issued)
                 ──> CANCELLED     (admin cancel from escrow; winner refunded, escrow → EXPIRED w/ reason ADMIN_CANCEL)

DISPUTED ──> COMPLETED             (resolved in favor of buyer, transfer completed)
         ──> EXPIRED               (resolved in favor of seller / refund-and-close)
         ──> CANCELLED             (admin alsoCancelListing during resolve)

SUSPENDED ──> ACTIVE               (admin reinstate)
```

`COMPLETED`, `EXPIRED`, `FROZEN`, `CANCELLED` are terminal — no outbound
transitions.

## 5. Locking semantics

```
LOCKING_STATUSES = { ACTIVE, TRANSFER_PENDING, DISPUTED }
```

`assertParcelNotLocked` collapses to the original single
`existsBySlParcelUuidAndStatusInAndIdNot` call. No escrow join. Partial
unique index `uq_auctions_parcel_locked_status` matches the same set.

`SUSPENDED` is intentionally NOT locking (mirrors today's behavior — see
existing comment in `AuctionStatus.java`). Suspension releases the parcel
for re-listing.

## 6. PublicAuctionStatus collapse

The public-facing `PublicAuctionStatus` enum (returned in
`PublicAuctionResponse.status`) collapses the internal terminal statuses
to a single `ENDED` for non-sellers, preserving the existing public API
contract. Mapping:

```
DRAFT, DRAFT_PAID, VERIFICATION_PENDING, VERIFICATION_FAILED → hidden (404)
ACTIVE                                                       → ACTIVE
TRANSFER_PENDING, DISPUTED                                   → ENDED
COMPLETED, EXPIRED, FROZEN, CANCELLED                        → ENDED
SUSPENDED                                                    → hidden (404)
```

Public consumers continue to see only `ACTIVE` / `ENDED`. Sellers see the
full internal status via `SellerAuctionResponse.status`.

## 7. Code changes

### 7.1 Enum + constants

- `AuctionStatus`: drop `ENDED`, `ESCROW_PENDING`, `ESCROW_FUNDED`. Add
  `FROZEN`. Update the javadoc that enumerates terminal "why-it-ended"
  states.
- `AuctionStatusConstants.LOCKING_STATUSES`: `{ACTIVE, TRANSFER_PENDING,
  DISPUTED}`.
- `ParcelLockingIndexInitializer`: matching DDL.

### 7.2 Auction-close paths

- `BidPlacementHelpers` (inline buy-now close inside `BidService.acceptBid`):
  replace `setStatus(ENDED)`. For buy-now (`endOutcome = BOUGHT_NOW`), the
  follow-up `EscrowService.createForEndedAuction` flips the status to
  `TRANSFER_PENDING` — so the helper itself can leave the auction at its
  pre-close status briefly, or set `TRANSFER_PENDING` directly. Choose
  one consistent ownership rule (see §9 question 1).
- `AuctionEndTask.closeOne`: for `endOutcome ∈ {SOLD, BOUGHT_NOW}`,
  delegate the post-`ACTIVE` status to `createForEndedAuction`. For
  `endOutcome ∈ {NO_BIDS, RESERVE_NOT_MET}`, set `status = EXPIRED`
  directly.

### 7.3 Escrow service

`EscrowService.createForEndedAuction` is the single point that owns the
`ACTIVE → TRANSFER_PENDING` transition. It already runs in the same
transaction as the close, so the auction-status flip is atomic with the
escrow row insert + auto-fund.

Other escrow-state transitions need matching auction-status flips:

- `confirmTransfer` (escrow → COMPLETED) → auction `TRANSFER_PENDING →
  COMPLETED`.
- `freezeForFraud` (escrow → FROZEN) → auction → `FROZEN`.
- `expireTransfer` (escrow → EXPIRED via transfer-timeout sweep) →
  auction → `EXPIRED`.
- `fileDispute` (escrow → DISPUTED) → auction → `DISPUTED`.
- `AdminDisputeService.resolve` (escrow → COMPLETED / EXPIRED) →
  auction → `COMPLETED` / `EXPIRED`. `alsoCancelListing` path remains
  `cancellationService.cancelByAdmin*` and keeps `CANCELLED`.

### 7.4 Cancellation service

`CancellationService.CANCELLABLE` (governing seller-initiated cancel)
drops `ENDED` and keeps the existing pre-active + `ACTIVE` set —
unchanged scope from today. Sellers still can't cancel mid-escrow.

**Admin-cancel from `TRANSFER_PENDING` (new, per §9 Q2 sign-off).**
`AdminListingService.cancel` accepts `TRANSFER_PENDING` in addition to
the pre-active + `ACTIVE` set. The path:

1. Acquire pessimistic write locks on the auction + escrow.
2. Validate escrow exists and `state = TRANSFER_PENDING`. Reject
   otherwise (e.g. already `COMPLETED` / `FROZEN` / `EXPIRED`).
3. Refund the winner via `EscrowService.queueRefundIfFunded` (credits
   the winner's SLParcels wallet for `escrow.finalBidAmount` and appends
   an `AUCTION_ESCROW_REFUND` ledger row — same path used by
   `freezeForFraud` and `expireTransfer`).
4. Flip the escrow to `EXPIRED` with a new freeze/expire reason
   `ADMIN_CANCEL` so the audit trail distinguishes it from a transfer
   timeout (`TRANSFER_TIMEOUT`) or system-initiated freeze.
5. Flip the auction to `CANCELLED`.
6. Notify seller (`LISTING_REMOVED_BY_ADMIN`) and winner (new
   `LISTING_CANCELLED_DURING_ESCROW` category, or reuse
   `ESCROW_EXPIRED` — see §9 Q5).
7. Write the `CancellationLog` with `cancelledByAdminId` set so the
   penalty-ladder counters do not bill the seller.
8. Publish `AUCTION_CANCELLED` envelope on the STOMP topic.

The implementation lives on `CancellationService` (e.g. a new
`cancelByAdminFromEscrow` method) so the existing penalty-ladder /
fanout helpers stay shared. `cancelByAdmin` (the pre-active + `ACTIVE`
path) is unchanged.

`DISPUTED` cancellation continues to go through
`AdminDisputeService.resolve` with `alsoCancelListing=true`, which
already routes via `CancellationService.cancelByDisputeResolve`. Out of
scope for this spec.

### 7.5 DTOs + admin filters

- `AuctionDtoMapper.toPublicStatus`: update the switch table to handle
  the new statuses. The four terminal failure / completion statuses all
  collapse to public `ENDED` (§6).
- `AdminListingService` filter sets: any place that enumerates "ended"
  statuses for the admin table needs `TRANSFER_PENDING`, `DISPUTED`,
  `COMPLETED`, `EXPIRED`, `FROZEN` swapped in for the old single `ENDED`.

### 7.6 Frontend types

- `frontend/src/types/auction.ts` `AuctionStatus` mirror: drop `ENDED`,
  `ESCROW_PENDING`, `ESCROW_FUNDED`; add `FROZEN`.
- `PublicAuctionStatus` stays unchanged (still `ACTIVE | ENDED`).
- Any seller-side UI that switches on `status === "ENDED"` needs to be
  rewritten in terms of the new terminal statuses or the public collapse.

### 7.7 Migration

Single Flyway migration (`V36__rewire_auction_status.sql`) translates
existing rows. The rule set is deterministic per (auction status, escrow
state) pair:

```
auction.status='ENDED' + escrow.state='COMPLETED'        → COMPLETED
auction.status='ENDED' + escrow.state='TRANSFER_PENDING' → TRANSFER_PENDING
auction.status='ENDED' + escrow.state='FUNDED'           → TRANSFER_PENDING   (transient escrow stop)
auction.status='ENDED' + escrow.state='ESCROW_PENDING'   → TRANSFER_PENDING   (transient escrow stop)
auction.status='ENDED' + escrow.state='DISPUTED'         → DISPUTED
auction.status='ENDED' + escrow.state='FROZEN'           → FROZEN
auction.status='ENDED' + escrow.state='EXPIRED'          → EXPIRED
auction.status='ENDED' + no escrow row                   → EXPIRED            (NO_BIDS / RESERVE_NOT_MET)
auction.status='ESCROW_PENDING'                          → TRANSFER_PENDING   (defensive: never set today)
auction.status='ESCROW_FUNDED'                           → TRANSFER_PENDING   (defensive)
```

`CANCELLED`, `ACTIVE`, `SUSPENDED`, `DRAFT*`, `VERIFICATION_*` rows
unchanged. Migration runs idempotently — re-running on already-translated
data is a no-op since `ENDED`/`ESCROW_PENDING`/`ESCROW_FUNDED` rows won't
exist after the first run.

The dropped enum values stay in the `auctions.status` column type
(Postgres treats it as `VARCHAR` via JPA's `@Enumerated(EnumType.STRING)`).
No `ALTER TYPE` needed.

### 7.8 Tests

Touch points:
- `ParcelLockingRaceIntegrationTest` — drop the PR #319 stopgap tests
  (no longer needed; status itself reflects the lock semantics). Add a
  test that `TRANSFER_PENDING` locks and `FROZEN`/`EXPIRED`/`COMPLETED`
  don't.
- `EscrowCreateOnAuctionEndIntegrationTest`,
  `EscrowCreateOnBuyNowIntegrationTest`: assert
  `auction.status = TRANSFER_PENDING` post-close (was `ENDED`).
- `EscrowOwnershipMonitorIntegrationTest`: assert
  `auction.status = COMPLETED` after confirmTransfer; `= FROZEN` after
  freezeForFraud; `= EXPIRED` after transfer-timeout.
- `EscrowDisputeIntegrationTest`: assert `auction.status = DISPUTED` on
  fileDispute; resolution flips appropriately.
- `AuctionEndIntegrationTest`: assert `auction.status = EXPIRED` for
  NO_BIDS / RESERVE_NOT_MET outcomes.
- Anything matching `status.*ENDED` in tests likely needs updating; full
  sweep before considering done.

## 8. Out of scope

- Admin-cancel from `DISPUTED`. Goes through
  `AdminDisputeService.resolve` with `alsoCancelListing=true` (existing
  path; already correct).
- Unfreeze (re-debit the winner, resume escrow). Deferred per earlier
  YAGNI call.
- Re-classifying historical `escrow.state` rows. Escrow state machine is
  untouched by this spec.

## 9. Sign-off decisions

1. **Ownership of the `ACTIVE → TRANSFER_PENDING` flip in the close
   path: option A.** `EscrowService.createForEndedAuction` owns the
   transition. `BidPlacementHelpers` and `AuctionEndTask` leave the
   auction at `ACTIVE` after deciding the outcome; the escrow service
   then flips it atomically inside the same close transaction. Single
   source of truth: every "auction is now in escrow" transition lives
   in one method. Branches that don't open escrow (`NO_BIDS` /
   `RESERVE_NOT_MET`) set `EXPIRED` directly in
   `AuctionEndTask` / `BidPlacementHelpers` since the escrow service is
   never invoked.

2. **Admin-cancel from `TRANSFER_PENDING`: in scope.** Detailed flow in
   §7.4. New `cancelByAdminFromEscrow` method on `CancellationService`.

3. **`PublicAuctionStatus` stays `ACTIVE | ENDED`.** All internal
   terminals collapse to public `ENDED` for non-sellers. No new public
   surface.

4. **Migration runs on deploy without a maintenance gate.** Single
   `V36__rewire_auction_status.sql` Flyway migration completes in
   milliseconds. Backend container starts against translated rows.

5. **Outstanding sub-question (admin-cancel-from-escrow notification
   category for the winner):** new `LISTING_CANCELLED_DURING_ESCROW`
   category or reuse `ESCROW_EXPIRED`? I'll go with **reuse
   `ESCROW_EXPIRED`** — semantically the winner experience is identical
   ("escrow concluded without a transfer, refund credited"). New
   category only buys finer-grained UI copy, which we can add later if
   needed. Flag if you'd rather have the dedicated category.
