# Epic 08 Sub-Spec 2 — Cancellation Penalties & Anti-Circumvention

**Date:** 2026-04-24
**Epic:** 08 — Ratings & Reputation
**Sub-spec scope:** Task 03 from `docs/implementation/epic-08/`. Layers escalating penalties + suspension enforcement + 48h post-cancel ownership watcher onto the existing cancellation flow.
**Branch:** `task/08-sub-2-cancellation-penalties` → `dev`
**Predecessor:** Sub-spec 1 (reviews + reputation) merged in PR #27.

---

## 1. Goal

Make seller cancellations with active bids carry real consequences: an escalating penalty ladder enforced via a "pay at terminal to unsuspend" model, a 48-hour parcel-ownership watch on cancelled-with-bids auctions, and a frontend that warns sellers up-front about what their next cancellation will cost. Closes Task 03 of Epic 08 in one PR.

Out of scope: admin endpoints (Epic 10), notifications (Epic 09), user-targeted WebSocket queues for live banner push (deferred — using window-focus refetch).

---

## 2. The penalty ladder

Counted by `count(CancellationLog WHERE seller=? AND hadBids=true)` — the live query of prior offenses, BEFORE the current cancellation's log row is inserted. Cancellations with no bids and pre-ACTIVE cancellations don't count.

| Prior offenses | Action this cancel | Result on User row |
|---|---|---|
| 0 | Warning recorded | (no change beyond existing `cancelledWithBids++`) |
| 1 | L$1000 penalty added | `penaltyBalanceOwed += 1000` |
| 2 | L$2500 penalty + 30-day timed suspension | `penaltyBalanceOwed += 2500`; `listingSuspensionUntil = now + 30d` |
| 3+ | Permanent ban | `bannedFromListing = true` |

Penalty amounts come from `application.yml` (`slpa.cancellation.penalty.second-offense-l: 1000`, `third-offense-l: 2500`). Spec text and storage capture the live-config value at the time of the offense — see §4.

### Why this ladder

The user's preferred model — over the spec's "deduct from next payout" — is **suspended-until-paid**. A seller who incurs a penalty cannot create new listings until they walk to a terminal and pay the L$ off. This makes the consequence binary, immediate, and impossible to dodge by going dormant. Settled in Q1 of the brainstorm.

### Concurrent gates on offense #3

The 30-day suspension and the L$2500 debt are independent dimensions, both gating listing creation. Paying the L$2500 clears one gate; the 30-day clock continues to run. Waiting 30 days clears the other gate; the debt remains. Both must clear to list again. (Q1 of brainstorm.)

### Permanent ban irreversibility

Set `bannedFromListing = true` on offense #4. Only an admin can clear it (Epic 10). The 30-day suspension and any owed balance stay on the record but are dominated by the ban — the admin lift action explicitly clears all three.

---

## 3. Architecture

### 3.1 Backend — minimal new code

Sub-spec 2 adds NO new backend packages. All work extends existing ones:

```
auction/
├── CancellationService.java          (extend with ladder + watcher set + WS broadcast)
├── CancellationLog.java              (add penaltyKind, penaltyAmountL columns)
├── CancellationOffenseKind.java      NEW — small enum
├── CancellationStatusController.java NEW — /me/cancellation-status + /me/cancellation-history
├── CancellationStatusService.java    NEW — preview + history queries
├── dto/
│   ├── CancellationStatusResponse.java          NEW
│   ├── CancellationHistoryDto.java              NEW
│   └── NextConsequenceDto.java                  NEW
├── exception/
│   ├── SellerSuspendedException.java           NEW (→ 403)
│   └── SuspensionReason.java                   NEW (PENALTY_OWED / TIMED_SUSPENSION / PERMANENT_BAN)
├── AuctionCancelledEnvelope.java     NEW (joins AuctionTopicEnvelope)
├── AuctionService.java               (gate in create())
├── Auction.java                      (add postCancelWatchUntil column)
└── monitoring/
    ├── OwnershipMonitorScheduler.java (extend query — pass `now`)
    ├── OwnershipCheckTask.java        (branch on auction status, route fraud reason)
    └── ... existing files

auction/fraud/
└── FraudFlagReason.java              (add CANCEL_AND_SELL value)

user/
└── User.java                         (add penaltyBalanceOwed, bannedFromListing)

escrow/
└── EscrowTransactionType.java        (add LISTING_PENALTY_PAYMENT)

sl/
└── PenaltyTerminalController.java    NEW — /sl/penalty-lookup + /sl/penalty-payment

config/
└── SecurityConfig.java               (permit terminal-auth on /sl/penalty-* paths)
```

### 3.2 Frontend — one new component package

```
components/cancellation/             NEW
└── CancellationConsequenceBadge.tsx
components/dashboard/                EXISTS (Epic 02)
├── SuspensionBanner.tsx             NEW
└── CancellationHistorySection.tsx   NEW
components/listing/
└── CancelListingModal.tsx           MODIFY — consequence-aware copy
hooks/
├── useCancellationStatus.ts         NEW
└── useCancellationHistory.ts        NEW
lib/api/
└── cancellations.ts                 NEW — 2 fetcher functions
types/
├── cancellation.ts                  NEW — DTO mirrors
└── auction.ts                       MODIFY — AuctionCancelledEnvelope joins union
app/auction/[id]/
└── AuctionDetailClient.tsx          MODIFY — handle AUCTION_CANCELLED
app/dashboard/(verified)/overview/
└── page.tsx                         MODIFY — mount banner + history
lib/user.ts                          MODIFY — extend /me types
```

### 3.3 Configuration

```yaml
slpa:
  cancellation:
    penalty:
      second-offense-l: 1000
      third-offense-l: 2500
      third-offense-suspension-days: 30
    post-cancel-watch-hours: 48
```

`@ConfigurationProperties` class `CancellationPenaltyProperties` reads these. Penalty amounts and durations are live-config — historical `CancellationLog` rows snapshot the amount applied so retroactive config changes don't rewrite history (§4).

---

## 4. Data model

### 4.1 New / modified columns on `User`

```java
@Builder.Default
@Column(name = "penalty_balance_owed", nullable = false,
        columnDefinition = "bigint not null default 0")
private Long penaltyBalanceOwed = 0L;

@Builder.Default
@Column(name = "banned_from_listing", nullable = false,
        columnDefinition = "boolean not null default false")
private Boolean bannedFromListing = false;
```

`listingSuspensionUntil OffsetDateTime` already exists on `User` (Epic 02). Sub-spec 2 starts writing it.

### 4.2 New column on `Auction`

```java
@Column(name = "post_cancel_watch_until")
private OffsetDateTime postCancelWatchUntil;  // nullable
```

Set to `now + 48h` on ACTIVE-with-bids cancellation. Cleared in `OwnershipCheckTask` after a `CANCEL_AND_SELL` flag is raised, so subsequent scheduler ticks don't re-flag the same observation. Naturally falls out of the watcher query once `now > postCancelWatchUntil`.

### 4.3 New columns on `CancellationLog`

```java
@Enumerated(EnumType.STRING)
@Column(name = "penalty_kind", length = 30)
private CancellationOffenseKind penaltyKind;  // nullable for pre-sub-spec-2 rows

@Column(name = "penalty_amount_l")
private Long penaltyAmountL;  // nullable
```

Both written at cancellation time inside the same transaction. The KIND captures the discriminator (used by the history view's badge map). The AMOUNT captures the L$ snapshotted from config — if the config changes later, historical rows stay correct.

**Why store on the log row instead of deriving:** an admin retroactively forgiving a past cancellation (setting that row's `had_bids = false`) correctly changes the live offense COUNT — so future decisions self-heal — but the penalty actually applied at that moment is historical fact and cannot be recomputed safely. Decision uses live query; record uses snapshot. (Section 2 correction.)

### 4.4 New enum `CancellationOffenseKind`

```java
public enum CancellationOffenseKind {
    NONE,             // no bids OR pre-ACTIVE cancel
    WARNING,          // 1st offense with bids
    PENALTY,          // 2nd offense with bids: L$1000
    PENALTY_AND_30D,  // 3rd offense with bids: L$2500 + 30-day suspension
    PERMANENT_BAN     // 4th+ offense with bids
}
```

### 4.5 New enum value `FraudFlagReason.CANCEL_AND_SELL`

Appended to existing enum at `auction/fraud/FraudFlagReason.java`. Existing `FraudFlagReasonCheckConstraintInitializer` refreshes the `fraud_flags_reason_check` constraint at startup automatically — no migration needed.

### 4.6 New enum value `EscrowTransactionType.LISTING_PENALTY_PAYMENT`

Tag for ledger rows from `/sl/penalty-payment`. Mirrors the existing `LISTING_FEE_REFUND`-class shape: `payer = seller`, `payee = null` (platform side), `amount = paid amount`, `slTransactionId`, `terminalId`, `completedAt`.

### 4.7 No new entities

No `PenaltyPayment` entity, no `Suspension` entity, no `OffenseRecord` entity. All state is captured by:
- `CancellationLog` (existing) for history
- `User.penaltyBalanceOwed` for live debt
- `User.listingSuspensionUntil` for time-based suspension
- `User.bannedFromListing` for permanent ban
- `EscrowTransaction` (existing) for payment audit

---

## 5. The cancellation flow (single transaction)

```
CancellationService.cancel(auctionId, reason)
  ├── findByIdForUpdate(auctionId) [lock auction]
  ├── validate cancellable status + not-after-end (existing)
  ├── seller = auction.seller
  ├── userRepo.findByIdForUpdate(seller.id) [lock user — same tx]
  │
  ├── from = auction.status; hadBids = auction.bidCount > 0
  │
  ├── // Pre-INSERT count → ladder index
  ├── if (from == ACTIVE && hadBids) {
  │     priorOffenses = count(CancellationLog WHERE seller=? AND hadBids=true)
  │     consequence = ladder[min(priorOffenses, 3)]
  │       0 → CancellationOffenseKind.WARNING,   amountL=null
  │       1 → PENALTY,                            amountL=1000
  │       2 → PENALTY_AND_30D,                    amountL=2500
  │       3 → PERMANENT_BAN,                      amountL=null
  │   } else {
  │     consequence = NONE, amountL=null
  │   }
  │
  ├── INSERT CancellationLog (auction, seller, from, hadBids, reason,
  │                           penaltyKind=consequence.kind,
  │                           penaltyAmountL=consequence.amountL)
  │
  ├── if (from == ACTIVE && hadBids) {
  │     seller.cancelledWithBids++   // existing denormalised counter
  │     switch (consequence.kind) {
  │       case PENALTY:
  │         seller.penaltyBalanceOwed += consequence.amountL
  │       case PENALTY_AND_30D:
  │         seller.penaltyBalanceOwed += consequence.amountL
  │         seller.listingSuspensionUntil = now + 30d
  │       case PERMANENT_BAN:
  │         seller.bannedFromListing = true
  │     }
  │     auction.postCancelWatchUntil = now + 48h
  │     userRepo.save(seller)
  │   }
  │
  ├── if (listingFeePaid && from != ACTIVE) {
  │     INSERT ListingFeeRefund (existing)
  │   }
  │
  ├── auction.status = CANCELLED
  ├── auctionRepo.save(auction)
  ├── monitorLifecycle.onAuctionClosed(auction) (existing)
  │
  └── registerSync.afterCommit:
        broadcastPublisher.publishCancelled(AuctionCancelledEnvelope)
```

### Invariants

- **All-or-nothing transaction**: counter, debt, suspension, ban, watcher timestamp, log row, refund record — atomic. Crash mid-flow rolls everything back.
- **Pessimistic lock on User row** prevents two concurrent cancellations (same seller, different auctions) from both reading priorOffenses=N and both applying as the (N+1)th.
- **Offense count is fresh COUNT before INSERT**, indexed by `priorOffenses` directly. No off-by-one arithmetic. (Section 3 correction.)
- **Permanent ban irreversible** within sub-spec 2. Pre-existing 30-day suspension and owed penalty stay on the user row alongside the ban — admin lift action (Epic 10) clears all three explicitly.

---

## 6. The 48-hour ownership watcher

### 6.1 Query extension

`AuctionRepository.findDueForOwnershipCheck` extended:

```java
@Query("""
    SELECT a.id FROM Auction a
    WHERE (a.status = AuctionStatus.ACTIVE
            AND (a.lastOwnershipCheckAt IS NULL OR a.lastOwnershipCheckAt < :cutoff))
       OR (a.postCancelWatchUntil IS NOT NULL
            AND a.postCancelWatchUntil > :now
            AND (a.lastOwnershipCheckAt IS NULL OR a.lastOwnershipCheckAt < :cutoff))
    """)
List<Long> findDueForOwnershipCheck(
        @Param("cutoff") OffsetDateTime cutoff,
        @Param("now") OffsetDateTime now);
```

The `lastOwnershipCheckAt` column gates polling cadence for both ACTIVE and post-cancel-watched auctions.

### 6.2 Task branching

```java
@Async
@Transactional
public void checkOne(Long auctionId) {
    Auction a = auctionRepo.findById(auctionId).orElseThrow(...);
    OwnerLookupResult result = worldApi.lookupOwner(a.getParcel());
    a.setLastOwnershipCheckAt(OffsetDateTime.now(clock));

    if (result.isMismatch()) {
        FraudFlagReason reason = (a.getStatus() == AuctionStatus.CANCELLED)
                ? FraudFlagReason.CANCEL_AND_SELL
                : FraudFlagReason.OWNERSHIP_CHANGED_TO_UNKNOWN;
        raiseFlag(a, reason, buildEvidence(a, result));

        if (a.getStatus() == AuctionStatus.CANCELLED) {
            a.setPostCancelWatchUntil(null);  // prevent re-flag on next tick
        }
        // ACTIVE path's existing flow suspends the auction via SuspensionService,
        // which removes it from the query naturally. No equivalent change there.
    }
    auctionRepo.save(a);
}
```

The shared `raiseFlag` helper extracts the fraud-flag write logic; the CANCELLED branch skips suspension (auction is already cancelled).

### 6.3 Evidence payload for `CANCEL_AND_SELL`

```json
{
  "cancelledAt": "2026-04-22T14:30:00Z",
  "expectedSellerKey": "abc-123-...",
  "observedOwnerKey": "xyz-789-...",
  "hoursSinceCancellation": 17.4,
  "parcelRegion": "Aurora",
  "parcelLocalId": 42,
  "auctionTitle": "Lakefront Lot at Aurora"
}
```

`hoursSinceCancellation` is computed at flag-creation time so admin reviewers can score temporal proximity ("4 hours after = strong signal" vs "30 hours = weaker").

### 6.4 Edge cases

- **Seller re-buys their own parcel within 48h** (alt-account round-trip): no flag — owner still resolves to seller's avatar UUID.
- **World API failure during watch window**: existing threshold logic (`WORLD_API_FAILURE_THRESHOLD`) applies. Doesn't escalate to `CANCEL_AND_SELL` based on absence of data.
- **Cancellation timing relative to ticks**: `OwnershipMonitorScheduler` runs at `PT30S` cadence; first observation lands ~30s after cancellation. Acceptable.
- **Post-flag ownership reverts**: if owner flips to non-seller (flag raised, watcher cleared), then back to seller before 48h expires — flag stays. Off-platform deal-then-undo doesn't erase the signal. Admin reviews.
- **Multiple cancellations within 48h**: each cancellation gets its own `postCancelWatchUntil` window scoped to that auction. Each auction can raise at most one `CANCEL_AND_SELL` flag.

---

## 7. API surface

All endpoints under `/api/v1`. `J` = JWT, `T` = SL terminal-only (`X-SecondLife-Owner-Key` validated).

### 7.1 New endpoints

| Method | Path | Auth | Purpose |
|---|---|---|---|
| `GET` | `/users/me/cancellation-status` | J | Cancel modal preview + current suspension state |
| `GET` | `/users/me/cancellation-history` | J | Paginated seller-only cancellation log |
| `POST` | `/sl/penalty-lookup` | T | Terminal queries debt by SL avatar UUID |
| `POST` | `/sl/penalty-payment` | T | Terminal posts payment confirmation after pulling L$ |

### 7.2 Modified endpoints

- `GET /users/me` — extend response with `penaltyBalanceOwed`, `listingSuspensionUntil`, `bannedFromListing` so the dashboard banner has session-level state without an extra fetch.
- `POST /auctions` — gate now throws `SellerSuspendedException` (→ **403**, not 422) when suspended/banned. Existing happy path unchanged.
- `POST /auctions/{id}/cancel` — service-layer extends with penalty ladder + `AUCTION_CANCELLED` WS broadcast on `afterCommit`.

### 7.3 `GET /users/me/cancellation-status`

```json
{
  "priorOffensesWithBids": 1,
  "currentSuspension": {
    "penaltyBalanceOwed": 1000,
    "listingSuspensionUntil": "2026-05-24T10:15:00Z",
    "bannedFromListing": false
  },
  "nextConsequenceIfBidsPresent": {
    "kind": "PENALTY",
    "amountL": 1000,
    "suspends30Days": false,
    "permanentBan": false
  }
}
```

`nextConsequenceIfBidsPresent.kind` is one of `WARNING`, `PENALTY`, `PENALTY_AND_30D`, `PERMANENT_BAN` (never `NONE`, since the query asks "what would happen IF this cancel had bids").

### 7.4 `GET /users/me/cancellation-history?page=0&size=10`

`PagedResponse<CancellationHistoryDto>` per CONVENTIONS.md §PagedResponse, sorted `cancelledAt DESC`. Each row:

```json
{
  "auctionId": 1234,
  "auctionTitle": "Aurora Parcel",
  "primaryPhotoUrl": "https://...",
  "cancelledFromStatus": "ACTIVE",
  "hadBids": true,
  "reason": "Personal reasons.",
  "cancelledAt": "2026-04-20T10:15:00Z",
  "penaltyApplied": {
    "kind": "PENALTY",
    "amountL": 1000
  }
}
```

`penaltyApplied` is null when `penaltyKind == NONE` on the log row. Read directly from the snapshotted columns — no live recomputation.

### 7.5 `POST /sl/penalty-lookup`

Terminal request:
```json
{ "slAvatarUuid": "abc-123-...", "terminalId": "terminal-7" }
```

Response (200) when debt exists:
```json
{
  "userId": 42,
  "displayName": "Alice",
  "penaltyBalanceOwed": 1000
}
```

Response (404) when avatar unknown OR balance is 0. Validates `X-SecondLife-Owner-Key` per existing terminal auth pattern.

### 7.6 `POST /sl/penalty-payment`

Terminal-side request after pulling L$:
```json
{
  "slAvatarUuid": "abc-123-...",
  "slTransactionId": "tx-uuid",
  "amount": 600,
  "terminalId": "terminal-7"
}
```

Server-side single transaction (pessimistic lock on User row):

1. Validate `X-SecondLife-Owner-Key` (existing terminal auth)
2. Resolve user from `slAvatarUuid`; 404 if unknown
3. Idempotency: if `slTransactionId` already recorded as `LISTING_PENALTY_PAYMENT`, return `200 { remainingBalance: <current> }` (benign replay)
4. `findByIdForUpdate(user)` — pessimistic lock
5. Validate `0 < amount <= user.penaltyBalanceOwed` — return 422 if overpayment attempted
6. `user.penaltyBalanceOwed -= amount`
7. `INSERT EscrowTransaction { type=LISTING_PENALTY_PAYMENT, payer=user, payee=null, amount, slTransactionId, terminalId, completedAt=now }`
8. Return `{ remainingBalance: <new value> }`

Partial payments allowed and additive — a seller without L$1000 in their account can pay L$600 now and L$400 later. Each partial is its own ledger row. Suspension stays active until `penaltyBalanceOwed == 0`.

### 7.7 `POST /auctions` (suspension gate)

```java
public Auction create(Long sellerId, AuctionCreateRequest req) {
    User seller = userRepo.findById(sellerId).orElseThrow(...);

    SuspensionReason reason = checkCanCreateListing(seller, clock);
    if (reason != null) throw new SellerSuspendedException(reason);

    // existing validation + entity build
}

private SuspensionReason checkCanCreateListing(User u, Clock c) {
    if (u.getBannedFromListing()) return SuspensionReason.PERMANENT_BAN;
    if (u.getListingSuspensionUntil() != null
            && OffsetDateTime.now(c).isBefore(u.getListingSuspensionUntil()))
        return SuspensionReason.TIMED_SUSPENSION;
    if (u.getPenaltyBalanceOwed() > 0)
        return SuspensionReason.PENALTY_OWED;
    return null;
}
```

`SellerSuspendedException` → 403 ProblemDetail with `code` field carrying the enum:

```json
{
  "type": "https://slpa.example/problems/seller-suspended",
  "title": "Listing creation suspended",
  "status": 403,
  "code": "PENALTY_OWED",
  "detail": "You owe L$1000 in cancellation penalties. Pay at any SLPA terminal."
}
```

Frontend branches on `code`, not status.

---

## 8. Frontend surfaces

### 8.1 Cancel modal — consequence-aware copy

`CancelListingModal.tsx` extends with one new fetch on open:

```tsx
const { data: status } = useQuery({
  queryKey: ["me", "cancellation-status"],
  queryFn: () => apiGet("/api/v1/users/me/cancellation-status"),
  staleTime: 30_000,
});
```

Copy table — checked **top-down, first match wins**:

| Condition | Copy |
|---|---|
| `user.bannedFromListing === true` | "You are permanently banned from creating new listings. This cancellation will be recorded." |
| no bids, any prior count | "No penalty will apply. Listing fee is non-refundable for already-paid auctions." |
| has bids, 0 prior offenses | "This is a cancellation with active bids. Your first such cancellation is recorded as a warning — no L$ penalty." |
| has bids, 1 prior | "**This will be your 2nd cancellation with active bids. You will be suspended from new listings until you pay a L$1000 penalty at any SLPA terminal.**" |
| has bids, 2 prior | "**This will be your 3rd cancellation with active bids. You will be suspended from new listings for 30 days AND must pay a L$2500 penalty before listing again. One more cancellation will result in a permanent ban.**" |
| has bids, 3+ prior (unbanned) | "**This will be your 4th cancellation with active bids. This will result in a permanent ban from new listings.**" |

Reason field: free text, required, ≤500 chars. Confirm button: `Button variant="destructive"`.

### 8.2 SuspensionBanner

Component renders when `!user.canCreateListing`. State sourced from extended `/me` response. Variants:

| Condition | Banner |
|---|---|
| `bannedFromListing` | "Your listing privileges have been permanently suspended. Contact support to request a review." |
| timed AND debt | "Listing suspended until {date}. You also owe **L${amount}**. Visit any SLPA terminal to pay." |
| timed only | "Listing suspended until {date}." |
| debt only | "You owe **L${amount}** in cancellation penalties. Visit any SLPA terminal to pay and resume listing." |

No "Pay now" button on web — pure walk-in model. State refreshes via React Query window-focus refetch on `/me` (no WS push for `PENALTY_CLEARED` in sub-spec 2; deferred to user-targeted-queue infrastructure work).

### 8.3 CancellationHistorySection

Lives on the seller's dashboard. `useCancellationHistory()` returns paginated `PagedResponse<CancellationHistoryDto>`. Empty state: "No cancellations yet."

Each row: compact auction card (existing `<ListingCard variant="compact">`-shape) + `<CancellationConsequenceBadge>` on the right + click-to-expand reason text.

`<CancellationConsequenceBadge>` map:
- `null` / `NONE` → grey "No penalty"
- `WARNING` → yellow "Warning"
- `PENALTY` → orange "L${amount} penalty"
- `PENALTY_AND_30D` → red "L${amount} + 30-day suspension"
- `PERMANENT_BAN` → red "Permanent ban"

### 8.4 Listing wizard suspension gate

**Frontend pre-check:** Dashboard "Create new listing" CTA renders as a disabled button with tooltip when `!user.canCreateListing`. The disabled state carries the same suspension-reason copy.

**Backend gate authoritative:** Any request that bypasses the pre-check (deep link, API client) gets a 403 with structured `code`. The wizard's submit handler catches the 403, reads `code`, and routes to a focused modal carrying the right banner copy + a link back to the dashboard.

### 8.5 WebSocket integration

**`AUCTION_CANCELLED` envelope** added to `AuctionTopicEnvelope` union:

```typescript
export type AuctionCancelledEnvelope = {
  type: "AUCTION_CANCELLED";
  auctionId: number;
  cancelledAt: string;
  hadBids: boolean;
};

export type AuctionTopicEnvelope =
  | AuctionEnvelope
  | EscrowEnvelope
  | ReviewRevealedEnvelope
  | AuctionCancelledEnvelope;
```

`AuctionDetailClient` handles it by invalidating the auction query — page transitions to "auction cancelled" state in real time. Bidders mid-bid see their bid form replaced with a cancellation banner. `EscrowPageClient` ignores it (escrow doesn't exist for cancelled auctions).

Closes the deferred ledger entry "Cancellation WS broadcast on active-auction cancel."

### 8.6 No `PENALTY_CLEARED` push in sub-spec 2

Banner state freshness depends on:
- React Query `staleTime` on `/me` (default 30s)
- Window-focus refetch when seller tabs back from SL after paying
- Manual refresh

A few seconds of staleness between L$ payment and banner dismissal is acceptable for the walk-in flow. Real-time push deferred until user-targeted WS queues land (existing deferred ledger item "User-targeted WebSocket queues").

---

## 9. Security & authorization

- `/users/me/cancellation-status` and `/users/me/cancellation-history`: JWT required, scoped to caller (no cross-user reads).
- `/sl/penalty-lookup` and `/sl/penalty-payment`: terminal auth via `X-SecondLife-Owner-Key` header. Existing pattern from escrow terminals (Epic 05). No JWT on these — terminals don't carry user JWTs.
- `POST /auctions`: existing JWT requirement; gate is service-layer, not filter (visible in tests).
- Idempotency on `/sl/penalty-payment`: `slTransactionId` is unique-indexed on `EscrowTransaction.slTransactionId` (existing unique constraint). Replay returns 200-with-current-balance instead of 409 for benign terminal retries.

Penalty amounts and suspension dates are private data — only the user's own `/me/cancellation-status` exposes them. No cross-user visibility into another seller's debt or ban status; only the public profile signals (review count, completion rate from sub-spec 1) are externally visible.

---

## 10. Testing strategy

### 10.1 Backend

| Layer | Coverage |
|---|---|
| Unit (service) | `CancellationService.cancel` ladder branches at every offense level (0/1/2/3+); concurrent cancel race serialised at User row lock; `CancellationStatusService.preview` returns correct kind+amount per prior count; `OwnershipCheckTask` routes `CANCEL_AND_SELL` for cancelled-status auctions and clears `postCancelWatchUntil` after flag |
| Slice (`@WebMvcTest`) | All 4 new endpoints: happy path, auth failures (401/403), 422 on overpayment, 404 on unknown avatar / zero balance, idempotent replay returns 200 with current balance |
| Integration (`@SpringBootTest` + Testcontainers) | End-to-end: seller hits offense ladder 1→2→3→4 across 4 cancellations; ban applied; gate trips on `POST /auctions`; pay penalty at terminal partial then full; banner clears; ownership-watcher fires on a cancelled auction with parcel ownership change → `CANCEL_AND_SELL` flag created with evidence payload populated |

Key test — **suspended-while-cancelling-existing-listing**: user is banned (offense #4 already happened), they cancel a 5th ACTIVE-with-bids auction → ladder records `PERMANENT_BAN` on log row, `bannedFromListing` stays true (no-op write), no new debt, watcher window still set on auction row.

Key test — **partial payment idempotency**: pay L$600 with `txn-1`, balance L$400; replay `txn-1` → returns 200 with `remainingBalance: 400`, no double-debit; pay L$400 with `txn-2` → balance 0; replay `txn-2` → returns 200 with `remainingBalance: 0`; replay `txn-1` → still 200 with `remainingBalance: 0`.

Key test — **ladder uses pre-INSERT count**: insert 3 prior cancelled-with-bids log rows manually, call `cancel()` on a 4th ACTIVE-with-bids auction, assert `penaltyKind == PERMANENT_BAN` on the new log row. (Verifies no off-by-one in count timing.)

### 10.2 Frontend

| File | Coverage |
|---|---|
| `CancelListingModal.test.tsx` | All 6 copy variants render correctly; ban-precedence branch wins over ladder-row branches |
| `SuspensionBanner.test.tsx` | All 4 banner variants; renders nothing when `canCreateListing` |
| `CancellationHistorySection.test.tsx` | Pagination, empty state, badge mapping per kind; reason text expand/collapse |
| `useCancellationStatus.test.tsx` | Query key stability, cache invalidation on mutations |
| `AuctionDetailClient.test.tsx` | `AUCTION_CANCELLED` envelope invalidates auction query; bid form replaced |
| `app/listings/new/page.test.tsx` (or wizard) | 403 SellerSuspendedException with each `code` value routes to correct modal copy |

MSW 2 with `onUnhandledRequest: "error"` for all hook + component tests.

---

## 11. Task breakdown

Four tasks, sequenced via `superpowers:subagent-driven-development`.

### Task 1 — Backend ladder + cancel-flow extensions
- `User.penaltyBalanceOwed` + `User.bannedFromListing` columns
- `Auction.postCancelWatchUntil` column
- `CancellationLog.penaltyKind` + `CancellationLog.penaltyAmountL` columns + `CancellationOffenseKind` enum
- New `FraudFlagReason.CANCEL_AND_SELL` value
- New `EscrowTransactionType.LISTING_PENALTY_PAYMENT` value
- `CancellationService.cancel` extension: pre-INSERT COUNT, ladder, postCancelWatchUntil set, `afterCommit` `AuctionCancelledEnvelope` broadcast
- `AuctionCancelledEnvelope` record + broadcaster method on existing publisher
- `CancellationPenaltyProperties` `@ConfigurationProperties` class + `application.yml` keys
- Tests covering every ladder rung + concurrent-cancel race + WS broadcast firing

### Task 2 — Backend gate + endpoints + watcher
- `SellerSuspendedException` (→ 403) + `SuspensionReason` enum + ProblemDetail mapping
- `AuctionService.create` gate (ban → timed → penalty order)
- `GET /users/me/cancellation-status` controller + service
- `GET /users/me/cancellation-history` controller + service (paginated)
- Extend `/users/me` response shape
- `AuctionRepository.findDueForOwnershipCheck` query extension (passes `now`)
- `OwnershipCheckTask` status branch + `CANCEL_AND_SELL` evidence builder
- Tests: gate by reason, status preview at every level, history pagination, watcher fires correctly + clears postCancelWatchUntil

### Task 3 — Backend terminal payment endpoints
- `PenaltyTerminalController` with `/sl/penalty-lookup` + `/sl/penalty-payment`
- Idempotency on `slTransactionId` (existing unique-constraint reuse)
- Pessimistic User-row lock during payment apply
- `LISTING_PENALTY_PAYMENT` ledger row insertion
- `SecurityConfig` updates for new `/sl/penalty-*` paths (terminal auth)
- Tests: lookup happy + 404 paths, partial payment, full clear, idempotent replay, overpayment 422, simultaneous payments at two terminals serialise

### Task 4 — Frontend
- `types/cancellation.ts` + extend `types/auction.ts` with `AuctionCancelledEnvelope`
- `lib/api/cancellations.ts` (2 fetchers)
- `hooks/useCancellationStatus.ts` + `hooks/useCancellationHistory.ts`
- `components/cancellation/CancellationConsequenceBadge.tsx`
- `components/dashboard/SuspensionBanner.tsx` (4 variants)
- `components/dashboard/CancellationHistorySection.tsx`
- Modify `components/listing/CancelListingModal.tsx` — consequence-aware copy with ban-precedence branch
- Modify `app/dashboard/(verified)/overview/page.tsx` — mount banner + history section
- Modify `app/auction/[id]/AuctionDetailClient.tsx` — handle `AUCTION_CANCELLED`
- Modify `lib/user.ts` (or wherever) — extend `/me` shape
- Modify listing wizard — catch 403 `SellerSuspendedException` with structured `code`, route to right copy
- Tests for all variants + 403 routing + WS handling

---

## 12. DEFERRED_WORK ledger changes

**Resolve (delete):**
- "Cancellation WS broadcast on active-auction cancel" — Section 8.5

**Out of scope (carried forward):**
- Admin endpoint for cancellation history — Epic 10
- Admin lift action for permanent ban / penalty balance / timed suspension — Epic 10
- Admin fraud-flag triage UI — Epic 10 (existing deferred entry, unchanged)
- Email/IM notification of suspension events — Epic 09 (existing deferred entry, unchanged)
- User-targeted WS queue infrastructure (`/user/{id}/queue/*`) — kept deferred (existing entry, unchanged)
- Real-time `PENALTY_CLEARED` push — deferred behind the queue infrastructure
- Cancellation reason categorisation / enum — kept free-text per Q3 of brainstorm
- Automatic write-off / decay of penalty balance over time — admin-only, no design need today
- LSL terminal-side script implementation — Phase 11
- Notifications when listing is auto-cancelled by admin (separate flow) — Epic 10

---

## 13. Out of scope (explicit)

- Spec's original "deduct from next payout" model — replaced by the user's pay-at-terminal model in Q1 of brainstorm
- Spec's L$500 amount — replaced with L$1000 / L$2500 per user direction
- Storing the "next consequence" on the user record — derived live from log row count
- Visual cancellation-progression timeline on the dashboard — current row-per-cancellation history is sufficient
- Auto-decay of cancelled_with_bids counter or penalty balance — no time-based forgiveness
- Per-region or per-listing-class penalty schedules — flat ladder for all auctions

---

## 14. Open questions

None. All design questions resolved in brainstorm Q1–Q5.

---

## 15. Cross-references

- Sub-spec 1 (reviews & reputation, merged): `docs/superpowers/specs/2026-04-24-epic-08-sub-1-reviews-reputation.md`
- Original epic doc: `docs/implementation/epic-08/08-ratings-and-reputation.md` + `task-03-cancellation-penalties.md`
- DESIGN.md section 8 (Anti-Circumvention)
- Epic 03 ownership-monitoring infrastructure: `backend/src/main/java/com/slparcelauctions/backend/auction/monitoring/`
- Epic 05 escrow terminal patterns: `backend/src/main/java/com/slparcelauctions/backend/escrow/command/TerminalCommandService.java`
