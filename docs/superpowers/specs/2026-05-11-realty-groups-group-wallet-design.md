# Realty Groups — Sub-project D — Group Wallet (Design)

**Date:** 2026-05-11
**Status:** Spec
**Issue:** [#238](https://github.com/TheCodeLlama/slparcelauctions/issues/238)
**Depends on:** Sub-projects [A+B (core + permissions)](./2026-05-10-realty-groups-core-permissions-design.md), [C (listing integration)](./2026-05-11-realty-groups-listing-integration-design.md), [Wallet model](./2026-04-30-wallet-model-design.md)

---

## 1. Goal

Add a balance-bearing wallet to each realty group with the same shape and invariants as the user wallet. Listing fees on group-listed auctions debit the group wallet at `pay-listing-fee` time. Agent fees snapshotted by C distribute at escrow-completion: `floor(agent_fee_amt × agent_fee_split)` credits the group wallet, the remainder credits the listing agent's user wallet. Leader + delegates with `WITHDRAW_FROM_GROUP_WALLET` can withdraw to the leader's verified SL avatar. Group dormancy mirrors user dormancy, signaled by any member's recent login. The dissolution gate tightens to require a zero balance and no in-flight escrows. New permission enum values: `SPEND_FROM_GROUP_WALLET` (defined, not wired), `WITHDRAW_FROM_GROUP_WALLET` (wired), `VIEW_GROUP_TRANSACTIONS` (wired).

---

## 2. Architecture

New package `backend/.../realty/wallet/` mirrors the user-wallet package shape:

```
realty/wallet/
  RealtyGroupWalletService.java          — balance updates + ledger appends, single tx
  RealtyGroupWalletController.java       — GET balance, GET ledger page, POST withdraw
  RealtyGroupLedger.java                 — entity (BaseEntity)
  RealtyGroupLedgerEntryType.java        — enum
  RealtyGroupLedgerRepository.java
  GroupWalletDormancyTask.java           — group-side weekly sweep
  exception/
    InsufficientGroupBalanceException.java
    LeaderTermsNotAcceptedException.java
    LeaderFrozenException.java
    GroupHasNonzeroBalanceException.java
    GroupHasInFlightEscrowsException.java
```

Integration touch-points outside the new package:

- The escrow-completion handler (`EscrowCommissionCalculator` consumer) gains the agent-fee distribution call.
- `ListingFeeRefundProcessor` gains source-ledger lookup for refund routing.
- `RealtyGroupService.dissolve` extends its gate.
- `MeWalletController.payListingFee` branches on `auction.realty_group_id` and delegates to `RealtyGroupWalletService.debitListingFee` when set.

L$ flow (D-era group-listed auction, happy path):

```
seller pays listing fee → group_wallet.balance -= fee   (realty_group_ledger: LISTING_FEE_DEBIT)
bidder wins → escrow funded from bidder reservations    (unchanged)
parcel transferred → escrow completion runs:
   platform commission → SLPA                            (unchanged)
   agent_fee_amt = floor(finalBid × agent_fee_rate)      (snapshotted by C at SOLD)
       groupSlice = floor(agent_fee_amt × agent_fee_split)
       agentSlice = agent_fee_amt - groupSlice
       group_wallet.balance += groupSlice                (realty_group_ledger: AGENT_FEE_CREDIT)
       listing_agent.balance += agentSlice               (user_ledger:        AGENT_FEE_CREDIT)
   seller_payout = finalBid - commission - agent_fee_amt
   seller.balance += seller_payout                       (user_ledger:        PAYOUT_CREDIT — existing)
```

---

## 3. Data Model

### 3.1 `realty_groups` (modified)

Additive columns mirroring the user-wallet shape on `users`:

| Column | Type | Notes |
|---|---|---|
| `balance_lindens` | `BIGINT NOT NULL DEFAULT 0` | `CHECK (balance_lindens >= 0)`. |
| `reserved_lindens` | `BIGINT NOT NULL DEFAULT 0` | `CHECK (reserved_lindens >= 0)` and `CHECK (balance_lindens >= reserved_lindens)`. Always 0 in D's scope — included for symmetry; activated by a future discretionary-spend feature. |
| `wallet_dormancy_started_at` | `TIMESTAMPTZ NULL` | Set by phase-1 group dormancy job. |
| `wallet_dormancy_phase` | `SMALLINT NULL` | `CHECK (wallet_dormancy_phase BETWEEN 1 AND 4 OR wallet_dormancy_phase = 99)`. 99 = COMPLETED (auto-return fired). |

`available_lindens = balance_lindens - reserved_lindens` is computed at read time, not stored.

Terms acceptance: groups don't get a `wallet_terms_accepted_at` column. Terms are accepted per-user, by the leader, on the group's behalf — checked at withdraw initiation via the caller's user-wallet terms state.

### 3.2 `realty_group_ledger` (new) — append-only

```sql
CREATE TABLE realty_group_ledger (
    id                  BIGSERIAL PRIMARY KEY,
    public_id           UUID NOT NULL UNIQUE,
    group_id            BIGINT NOT NULL REFERENCES realty_groups(id),
    entry_type          VARCHAR(32) NOT NULL,
    amount              BIGINT NOT NULL CHECK (amount > 0),
    balance_after       BIGINT NOT NULL,
    reserved_after      BIGINT NOT NULL,
    ref_type            VARCHAR(32) NULL,
    ref_id              BIGINT NULL,
    actor_user_id       BIGINT NULL REFERENCES users(id),
    sl_transaction_id   VARCHAR(36) NULL,
    idempotency_key     VARCHAR(64) NULL,
    description         VARCHAR(500) NULL,
    created_at          TIMESTAMPTZ NOT NULL,
    created_by_admin_id BIGINT NULL REFERENCES users(id)
);

CREATE INDEX realty_group_ledger_group_created_idx
    ON realty_group_ledger (group_id, created_at DESC);
CREATE UNIQUE INDEX realty_group_ledger_sl_tx_idx
    ON realty_group_ledger (sl_transaction_id) WHERE sl_transaction_id IS NOT NULL;
CREATE UNIQUE INDEX realty_group_ledger_idempotency_idx
    ON realty_group_ledger (idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX realty_group_ledger_listing_fee_lookup_idx
    ON realty_group_ledger (ref_type, ref_id) WHERE entry_type = 'LISTING_FEE_DEBIT';

ALTER TABLE realty_group_ledger ADD CONSTRAINT realty_group_ledger_entry_type_check CHECK (
    entry_type IN (
        'LISTING_FEE_DEBIT', 'LISTING_FEE_REFUND',
        'AGENT_FEE_CREDIT',
        'WITHDRAW_QUEUED', 'WITHDRAW_COMPLETED', 'WITHDRAW_REVERSED',
        'DORMANCY_AUTO_RETURN',
        'ADJUSTMENT'
    )
);
```

`actor_user_id` is new relative to `user_ledger`: it identifies the human who triggered the entry (listing agent for `LISTING_FEE_DEBIT`, withdrawing member for `WITHDRAW_*`). NULL for system-driven entries (`AGENT_FEE_CREDIT`, `DORMANCY_AUTO_RETURN`). Powers the ledger view's "by [name]" column. `user_ledger` doesn't need this because user-ledger entries always credit/debit the same user who acted; on the group side, "who paid" and "whose wallet" diverge.

Append-only per [`feedback_ledgers_immutable`](../../../.claude/projects/C--Users-heath-Repos-Personal-slpa/memory/feedback_ledgers_immutable.md): a resolution is a new row, never a mutation of a prior row.

### 3.3 Why no separate `realty_group_wallets` table

The wallet columns live on `realty_groups` because (a) the user-wallet design put them on `users` and we're mirroring that shape per the issue body, (b) a 1:1 relationship between a group and its wallet doesn't justify a separate table, and (c) the dormancy and balance updates happen in the same write paths that touch the group row — a join would be pointless cost.

### 3.4 Reconciliation extension

`reconciliation_runs` gains:

| Column | Type | Notes |
|---|---|---|
| `group_wallet_balance_total` | `BIGINT NULL` | `SUM(realty_groups.balance_lindens)` at run time. |
| `group_wallet_reserved_total` | `BIGINT NULL` | `SUM(realty_groups.reserved_lindens)` at run time. |

`expected_total` becomes the sum of: `wallet_balance_total + wallet_reserved_total + escrow_locked_total + group_wallet_balance_total + group_wallet_reserved_total`. Old runs left with NULL for the new columns stay valid as read-only history.

`ReconciliationStatus.DENORM_DRIFT` covers group ledger drift too — extended by group queries.

### 3.5 Invariants

For any realty group G at any post-commit snapshot:

1. `realty_groups.balance_lindens(G) = balance_after` of the latest `realty_group_ledger` row WHERE `group_id=G`.
2. `realty_groups.reserved_lindens(G) = 0` in D's scope (no reservation source).
3. `realty_groups.balance_lindens(G) >= realty_groups.reserved_lindens(G)` (DB-enforced; trivially holds while (2) does).
4. `SUM(users.balance_lindens) + SUM(active user reservations) + SUM(escrow_lockedstates.amount) + SUM(realty_groups.balance_lindens) ≈ SLPA service avatar L$` (within in-flight `TerminalCommand` tolerance).

Drift on (1) or (4) → reconciliation alarm with the `DENORM_DRIFT` variant covering group balances.

---

## 4. Permissions Framework

### 4.1 Enum extension

`RealtyGroupPermission` gains three values:

```java
public enum RealtyGroupPermission {
    // ... A/B/C values ...
    SPEND_FROM_GROUP_WALLET,       // D — defined, not wired (see §4.2)
    WITHDRAW_FROM_GROUP_WALLET,    // D — wired to POST /realty/groups/{publicId}/wallet/withdraw
    VIEW_GROUP_TRANSACTIONS,       // D — wired to GET /realty/groups/{publicId}/wallet/* reads
}
```

### 4.2 What's wired in D

| Permission | Endpoint / surface | Notes |
|---|---|---|
| `WITHDRAW_FROM_GROUP_WALLET` | `POST /api/v1/realty/groups/{publicId}/wallet/withdraw` | Caller initiates; recipient is always the group leader's `slAvatarUuid`. |
| `VIEW_GROUP_TRANSACTIONS` | `GET /api/v1/realty/groups/{publicId}/wallet`<br>`GET /api/v1/realty/groups/{publicId}/wallet/ledger` | Returns balance + paginated ledger. |
| `SPEND_FROM_GROUP_WALLET` | (none in D) | Defined; reserved for future discretionary-spend features (advertising, paying member penalties). Excluded from the default-invite set until something wires it. |

Listing-fee debit on `CREATE_LISTING` does not require `SPEND_FROM_GROUP_WALLET` — the spend is intrinsic to the listing.

Leader holds all permissions implicitly (existing `RealtyGroupAuthorizer.canDo` rule).

### 4.3 Default-invite changes

The default permission set on a new invitation extends to include:

- `WITHDRAW_FROM_GROUP_WALLET = false` — high-trust, opt-in per delegate.
- `VIEW_GROUP_TRANSACTIONS = true` — read-only and useful for any active member.

`SPEND_FROM_GROUP_WALLET` is omitted from defaults.

### 4.4 What's leader-only (not delegable)

Nothing new in D. All three new permissions are delegable. The operational constraint is that withdraws land in the leader's avatar regardless of who initiated, so a delegate can authorize but not pocket.

---

## 5. API Surface

### 5.1 `GET /api/v1/realty/groups/{publicId}/wallet`

Auth: JWT. Permission: leader OR `VIEW_GROUP_TRANSACTIONS`. Otherwise 403 `INSUFFICIENT_GROUP_PERMISSION` (reuses existing exception from A+B).

**Response:**

```json
{
  "balance":   12500,
  "reserved":  0,
  "available": 12500,
  "recentLedger": [
    {
      "publicId": "uuid",
      "entryType": "AGENT_FEE_CREDIT",
      "amount": 1200,
      "balanceAfter": 12500,
      "reservedAfter": 0,
      "refType": "AUCTION",
      "refPublicId": "uuid",
      "actor": null,
      "createdAt": "2026-05-12T..."
    }
  ]
}
```

`recentLedger` is the 50 newest entries. `refPublicId` is resolved via `ref_type` lookup so the frontend doesn't see internal Long IDs.

### 5.2 `GET /api/v1/realty/groups/{publicId}/wallet/ledger?cursor={createdAt}&limit=50`

Cursor-paginated older entries. Same auth/permission as §5.1. Limit clamped to 100.

### 5.3 `POST /api/v1/realty/groups/{publicId}/wallet/withdraw`

Auth: JWT. Permission: leader OR `WITHDRAW_FROM_GROUP_WALLET`.

**Request:**

```json
{ "amount": 5000, "idempotencyKey": "<client UUID>" }
```

Recipient is **always** the group leader's `slAvatarUuid` — not present in the request schema. Caller is whoever holds the permission; payee is the leader by construction.

**Validation:**

1. JWT → caller. Permission check.
2. Group not dissolved → 410 `GROUP_DISSOLVED`.
3. Leader's user-wallet terms accepted → 422 `LEADER_TERMS_NOT_ACCEPTED { leaderPublicId }`. The leader is the L$ recipient; if their wallet hasn't accepted ToS, the receiving leg can't accept the L$.
4. Leader's user `status NOT IN (BANNED, FROZEN)` → 422 `LEADER_FROZEN`.
5. Idempotency on `idempotencyKey` against `realty_group_ledger.idempotency_key`. Replay returns the original 202.
6. Lock group `PESSIMISTIC_WRITE`. Validate `available >= amount` → 422 `INSUFFICIENT_GROUP_BALANCE { available, requested }`.
7. `balance_lindens -= amount`. Append `realty_group_ledger{type=WITHDRAW_QUEUED, ref_type=TERMINAL_COMMAND, actor_user_id=caller.id, idempotency_key}`. Insert `TerminalCommand{action=WITHDRAW, recipient_uuid=leader.slAvatarUuid, amount, idempotency_key=auto, realty_group_id=group.id}`.
8. WS envelope `GROUP_WALLET_BALANCE_CHANGED` on `/topic/realty/groups/{publicId}`.

**Response:** `202 { queueId, estimatedFulfillmentSeconds }`.

On terminal completion the existing `WITHDRAW_COMPLETED` / `WITHDRAW_REVERSED` ledger-append paths run against `realty_group_ledger` instead of `user_ledger`. The `TerminalCommand` row's nullable `realty_group_id` column (new — see §14.1) tells the completion handler which ledger to write to.

### 5.4 Listing-fee debit — modified `POST /api/v1/me/auctions/{auctionPublicId}/pay-listing-fee`

Endpoint shape unchanged. New internal routing in `MeWalletController.payListingFee`:

```java
Auction auction = ...;
if (auction.getRealtyGroupId() != null) {
    realtyGroupWalletService.debitListingFee(
        auction.getRealtyGroupId(), auction.getId(),
        auction.getListingFeeAmount(), caller);
} else {
    userWalletService.debitListingFee(...);  // existing path
}
```

`debitListingFee` on the group-wallet side:

1. Lock group `PESSIMISTIC_WRITE`. Validate `available >= fee` → `InsufficientGroupBalanceException` (422).
2. `balance_lindens -= fee`. Append `realty_group_ledger{type=LISTING_FEE_DEBIT, ref_type=AUCTION, ref_id=auction.id, actor_user_id=caller.id}`.
3. Append `escrow_transactions{type=LISTING_FEE_PAYMENT, status=COMPLETED}` (existing).
4. Drive auction `DRAFT → DRAFT_PAID`.
5. WS envelope `LISTING_FEE_PAID` to seller (existing) + `GROUP_WALLET_BALANCE_CHANGED` to group topic.

Caller permission: seller of the auction (unchanged from today). Group authorization is implied by the auction's binding to the group at create time (per Q2 — no re-check of `CREATE_LISTING` at fee-pay time). If the listing agent has lost `CREATE_LISTING` between create and pay-fee, the listing is still bound to the group and the fee still comes from the group wallet — the group authorized the listing at create time and is paying for it now.

### 5.5 Domain exceptions

```java
@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public class InsufficientGroupBalanceException extends RuntimeException {
    private final long available;
    private final long requested;
}

@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public class LeaderTermsNotAcceptedException extends RuntimeException {
    private final UUID leaderPublicId;
}

@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public class LeaderFrozenException extends RuntimeException { }

@ResponseStatus(HttpStatus.CONFLICT)
public class GroupHasNonzeroBalanceException extends RuntimeException { }   // dissolve gate

@ResponseStatus(HttpStatus.CONFLICT)
public class GroupHasInFlightEscrowsException extends RuntimeException { }  // dissolve gate
```

Mapped in `RealtyExceptionHandler` to error codes `INSUFFICIENT_GROUP_BALANCE`, `LEADER_TERMS_NOT_ACCEPTED`, `LEADER_FROZEN`, `GROUP_HAS_NONZERO_BALANCE`, `GROUP_HAS_INFLIGHT_ESCROWS`.

### 5.6 DTO shapes

```java
public record GroupWalletDto(
    long balance,
    long reserved,
    long available,
    List<GroupLedgerEntryDto> recentLedger
) {}

public record GroupLedgerEntryDto(
    UUID publicId,
    String entryType,
    long amount,
    long balanceAfter,
    long reservedAfter,
    @Nullable String refType,
    @Nullable UUID refPublicId,
    @Nullable LedgerActorDto actor,
    Instant createdAt
) {}

public record LedgerActorDto(UUID publicId, String displayName) {}

public record GroupWithdrawRequest(long amount, UUID idempotencyKey) {}
public record GroupWithdrawResponse(long queueId, int estimatedFulfillmentSeconds) {}
```

`refPublicId` resolution is bounded — entry types only have a handful of ref kinds. Pre-compute in the controller's DTO mapper.

---

## 6. Frontend Surface

### 6.1 Routes

| Path | Component | Auth |
|---|---|---|
| `/realty/groups/[publicId]/wallet` | `GroupWalletPage` (client) | JWT; renders 403 if no `VIEW_GROUP_TRANSACTIONS` |

`export const dynamic = "force-dynamic"` per SSR caveats — balance is per-visit.

### 6.2 Components (under `components/realty/wallet/`)

- `GroupWalletBalanceCard` — balance / reserved / available, deposit-instructions accordion (mirrors existing user `WalletCard`).
- `GroupWalletLedgerTable` — paginated table, "load more" pattern. Columns: When, Type, Amount, Balance after, Reference, Actor.
- `GroupWithdrawModal` — amount input, leader-recipient confirmation, idempotency-key generation client-side.
- `LeaderTermsBlockBanner` — shows when leader hasn't accepted user-wallet ToS; blocks the withdraw CTA.

### 6.3 Listing-fee preview — wallet source indicator

The existing `AgentFeePreview` from C extends: when the listing is being created under a group, a line below the fee total reads "Listing fee paid from [Group Name] wallet — current balance L$X." If the group balance is insufficient, the publish button is disabled with a tooltip "Group wallet has L$X; deposit L$Y to publish."

### 6.4 State + API client

- `frontend/src/lib/api/realtyGroupWallet.ts` — `getGroupWallet`, `getGroupLedger`, `withdrawFromGroupWallet`.
- `frontend/src/hooks/realty/useGroupWallet.ts` — TanStack Query hook, 30s stale time, refetch on focus.
- WebSocket: subscribe to `/topic/realty/groups/{publicId}` on the wallet page; on `GROUP_WALLET_BALANCE_CHANGED` invalidate the wallet + ledger queries.

### 6.5 SSR safety

Wallet page is client-only (`use client`). Server components don't have the JWT, balance is per-user, the terms-block banner depends on the leader's user state. Server-rendered fallback is the page skeleton.

---

## 7. Auction Completion — Agent-Fee Distribution

The integration is two sites in the existing escrow flow:

- **Site A — `EscrowService.createForEndedAuction`** (called from `AuctionEndTask.closeOne` at SOLD-close): reduces `escrow.payoutAmt` by `agent_fee_amt` so the PAYOUT terminal command that eventually fires sends only the seller's net share to the seller's SL avatar. The agent-fee L$ stays inside SLPA.
- **Site B — `TerminalCommandService.handleEscrowPayoutSuccess`** (called when the PAYOUT terminal command callback succeeds, i.e. the seller's L$ has actually left SLPA to their avatar): credits the group wallet and the listing-agent user wallet with their respective slices. Tying the credits to the success callback means: if escrow refunds (winner backs out, dispute rules refund), the full `finalBid` returns to the winner via the existing refund path and no wallet credits ever happen — no claw-back logic needed.

This two-site model preserves the wallet-reconciliation invariant (no wallet credit exists before the L$ that funds it has settled) and matches Q1-a's intent.

### 7.1 Site A — `EscrowService.createForEndedAuction`

The existing code:

```java
.payoutAmt(commission.payout(finalBid))
```

becomes:

```java
long agentFeeAmt = auction.getAgentFeeAmt() == null ? 0L : auction.getAgentFeeAmt();
.payoutAmt(commission.payout(finalBid) - agentFeeAmt)
```

`auction.getAgentFeeAmt()` is the value C snapshotted at SOLD-close on `AuctionEndTask`. For individual listings (no group), it's NULL → 0 → no change in payout. For group listings, the payout shrinks by the agent fee, which remains in escrow until completion.

### 7.2 Site B — `TerminalCommandService.handleEscrowPayoutSuccess`

The existing handler creates `EscrowTransaction{type=AUCTION_ESCROW_PAYOUT}` and `EscrowTransaction{type=AUCTION_ESCROW_COMMISSION}` audit rows after the seller's payout success. D appends agent-fee distribution after those rows, still inside the same `@Transactional` callback:

```java
long agentFeeAmt = nullToZero(escrow.getAuction().getAgentFeeAmt());
if (agentFeeAmt > 0) {
    agentFeeDistributor.distribute(escrow.getAuction(), agentFeeAmt);
}
```

A new service `AgentFeeDistributor` (under `backend/.../auction/agentfee/`) provides `distribute(Auction, long)`:

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentFeeDistributor {
    private final RealtyGroupWalletService groupWalletService;
    private final WalletService userWalletService;

    @Transactional(propagation = Propagation.MANDATORY)
    public void distribute(Auction auction, long agentFeeAmt) {
        BigDecimal split = auction.getAgentFeeSplit() == null
            ? BigDecimal.ZERO : auction.getAgentFeeSplit();
        long groupSlice  = BigDecimal.valueOf(agentFeeAmt)
                              .multiply(split)
                              .setScale(0, RoundingMode.FLOOR)
                              .longValueExact();
        long agentSlice  = agentFeeAmt - groupSlice;

        Long groupId = auction.getRealtyGroupId();
        Long agentId = auction.getListingAgent() != null
            ? auction.getListingAgent().getId() : null;

        if (groupId != null && groupSlice > 0) {
            groupWalletService.creditAgentFee(groupId, auction.getId(), groupSlice);
        }
        if (agentId != null && agentSlice > 0) {
            userWalletService.creditAgentFee(agentId, auction.getId(), agentSlice);
        }
    }
}
```

`creditAgentFee` on each side:

1. Lock target row `PESSIMISTIC_WRITE`.
2. `balance_lindens += slice`.
3. Append ledger row `{type=AGENT_FEE_CREDIT, ref_type=AUCTION, ref_id=auction.id, actor_user_id=null, amount=slice, balance_after, reserved_after}`.
4. WS envelope `{GROUP|WALLET}_BALANCE_CHANGED` on the appropriate topic.

### 7.3 Why this transaction

Keeping the agent-fee writes inside the escrow-payout-success `@Transactional` boundary means a single rollback leaves the payout audit row, commission audit row, and wallet credits all consistent. There is no half-credit state. The `Propagation.MANDATORY` contract on `creditAgentFee` enforces that the call site is already in a transaction.

### 7.4 Defensive branches

By design from C, `realty_group_id` and `listing_agent_id` are populated together at create. Defensive handling for entity-invariant drift:

- `realty_group_id` null but `agent_fee_amt > 0`: credit goes entirely to the seller as part of the normal payout — the seller IS the agent in case 1, so the L$ ends up in the right pocket either way. Log a warning at the distribution site.
- `listing_agent_id` null but `realty_group_id` set: credit the group side only. Agent slice is lost to the seller's payout. Log a warning.

These branches are paranoia, not features — entered only if entity invariants drift.

### 7.5 NO_BIDS / RESERVE_NOT_MET

`agent_fee_amt` is NULL (C never wrote it). `nullToZero` handles cleanly — no credits flow. The escrow-completion path doesn't run for these outcomes anyway; the auction terminates without escrow.

### 7.6 Dormancy and frozen receivers

If at distribution time the listing agent is `BANNED` or `FROZEN`, their slice still credits the user wallet (existing user-wallet credits don't block on status — only debits do, per the wallet model §8.2 principle that "money-flow operations don't block"). The agent's banned status prevents them from spending it. Same posture for a dormant agent — credit lands; dormancy phase doesn't reset on inbound credit (per wallet model §12).

If the group is dormant (phase 1–4) at distribution time, credit also lands and dormancy phase is not reset. This is consistent with the user-wallet rule "deposits don't reset dormancy" — only an active-signal event does.

---

## 8. Listing-Fee Refund Routing

`ListingFeeRefundProcessor` drains `listing_fee_refunds WHERE status=PENDING`. Refund routing is determined by looking up the originating ledger row, not by an auction-row denorm column.

### 8.1 Lookup logic

For each pending refund row `r`:

```java
Long auctionId = r.getAuctionId();
long amount    = r.getRefundAmount();

Optional<RealtyGroupLedger> groupDebit = realtyGroupLedgerRepo
    .findFirstByEntryTypeAndRefTypeAndRefId(
        RealtyGroupLedgerEntryType.LISTING_FEE_DEBIT, "AUCTION", auctionId);

if (groupDebit.isPresent()) {
    realtyGroupWalletService.creditListingFeeRefund(
        groupDebit.get().getGroupId(), auctionId, amount, r.getId());
} else {
    // C-era group listing or any individual listing: fall through to user-wallet refund.
    userWalletService.creditListingFeeRefund(
        r.getSellerUserId(), auctionId, amount, r.getId());
}
```

The `(entry_type, ref_type, ref_id)` triplet on `realty_group_ledger` is enough. The partial index `realty_group_ledger_listing_fee_lookup_idx (ref_type, ref_id) WHERE entry_type = 'LISTING_FEE_DEBIT'` (§14.1) makes the lookup O(log n).

### 8.2 Why ledger-first, fall through to user

The fall-through is correct in all directions:

- C-era group listings have a `LISTING_FEE_DEBIT` row only in `user_ledger` (group ledger didn't exist) → refund routes to user wallet. Correct: that's who paid.
- D-era individual listings have the debit only in `user_ledger` → refund routes to user wallet. Correct.
- D-era group listings have the debit in `realty_group_ledger` → refund routes to group wallet. Correct.

The auction's `realty_group_id` is not a sufficient discriminant on its own — C-era group listings have it set but the fee was debited from the user.

### 8.3 Refund credit shape

`realtyGroupWalletService.creditListingFeeRefund`:

1. Lock group `PESSIMISTIC_WRITE`.
2. `balance_lindens += amount`.
3. Append `realty_group_ledger{type=LISTING_FEE_REFUND, ref_type=LISTING_FEE_REFUND, ref_id=r.id, actor_user_id=null}`.
4. Mark `listing_fee_refunds.status=COMPLETED`.
5. WS envelope `GROUP_WALLET_BALANCE_CHANGED`.

Mirror shape on the user side (already exists per wallet model §7.2).

### 8.4 Dissolved-group edge case

Dissolution requires a zero balance (§9). A dissolved group can't accept a refund into a soft-deleted row — the `dissolved_at` check fails the credit. Behavior: log + alert. The refund stays `PENDING`; a manual admin path resolves by crediting the listing agent's user wallet as a substitute. This is an edge case (group dissolved between listing creation and listing-fee refund eligibility) and not worth automating.

---

## 9. Dissolution Gate Tightening

The dissolve path in `RealtyGroupService.dissolve` already gates on "no active listings" (C). D adds two more conditions.

### 9.1 New gates in `dissolve(...)`

```java
public void dissolve(UUID groupPublicId, Long callerUserId) {
    RealtyGroup group = loadActive(groupPublicId);
    authorizer.assertLeader(callerUserId, group.getId());

    // C — no active listings
    if (auctions.existsActiveListingsByGroupId(group.getId())) {
        throw new ActiveListingsBlockDissolveException();
    }
    // D — wallet must be zero
    if (group.getBalanceLindens() != 0 || group.getReservedLindens() != 0) {
        throw new GroupHasNonzeroBalanceException();
    }
    // D — no in-flight escrows
    if (escrowRepo.existsInFlightForGroup(group.getId())) {
        throw new GroupHasInFlightEscrowsException();
    }
    // ... existing dissolution flow
}
```

`escrowRepo.existsInFlightForGroup(groupId)`:

```sql
SELECT EXISTS (
    SELECT 1 FROM escrows e
    JOIN auctions a ON e.auction_id = a.id
    WHERE a.realty_group_id = :groupId
      AND e.state IN ('FUNDED', 'AWAITING_TRANSFER', 'DISPUTED')
)
```

The exact "in-flight" enum set is pulled from the existing escrow state machine at implementation time to avoid drift.

### 9.2 Why both balance AND reserved

`reserved_lindens` is always 0 in D's scope, but the gate checks it anyway. Costs nothing; future-proofs the gate against the day `SPEND_FROM_GROUP_WALLET` gets wired with reservations.

### 9.3 Why pre-ENDED escrows only

Dissolution with COMPLETED escrows is fine — the L$ has settled, the wallet records reflect reality. Same logic C applied to ENDED listings. The gate cares about state that still has L$ movement ahead of it.

### 9.4 Leader's path to satisfy the gate

If the balance is nonzero: withdraw to leader's avatar (§5.3). If escrows are in-flight: wait for transfer-confirmed or expiry. The dissolution UI surfaces both blockers with a "current balance: L$X, click here to withdraw" deep-link and "N in-flight escrows: [list]" — no auto-resolution. Combining auto-withdraw with dissolution would conflate two destructive operations and is hostile to the audit trail.

### 9.5 Admin force-dissolve

Out of scope. Admin moderation (sub-project F) decides force-dissolve semantics including wallet liquidation. D's gate is a soft guard; admin paths are a separate concern.

---

## 10. Group Dormancy

Group dormancy is signaled by **any member's recent login** (most-recent `refresh_tokens.created_at` across current members), with auto-return to the leader's avatar.

### 10.1 Active-signal query

```sql
-- A group is "active" if any current member has rotated a refresh token
-- within the dormancy window (30 days).
SELECT g.id AS group_id
FROM realty_groups g
WHERE g.dissolved_at IS NULL
  AND g.balance_lindens > 0
  AND g.wallet_dormancy_phase IS NULL
  AND NOT EXISTS (
      SELECT 1
      FROM realty_group_members m
      JOIN refresh_tokens rt ON rt.user_id = m.user_id
      WHERE m.group_id = g.id
        AND rt.created_at > now() - interval '30 days'
  );
```

Returns groups newly-eligible for dormancy flagging. Index assumption: `refresh_tokens (user_id, created_at DESC)` exists from the user-wallet work. Group cardinality is small enough (hundreds at scale) that the NOT EXISTS subquery scales fine.

### 10.2 `GroupWalletDormancyTask` — weekly

```java
@Scheduled(cron = "${slpa.realty-wallet.dormancy-job.cron:0 30 4 * * MON}", zone = "UTC")
public void sweep() {
    Instant now = Instant.now();
    List<RealtyGroup> newlyDormant = groupRepo.findEligibleForDormancyFlag(now);
    newlyDormant.forEach(g -> task.flag(g, now));

    List<RealtyGroup> awaitingNext = groupRepo.findDormancyPhaseDue(now);
    awaitingNext.forEach(g -> task.escalateOrAutoReturn(g, now));
}
```

Runs 30 minutes after the user-wallet job — same shape, separate scheduler entry so the two can be tuned/disabled independently.

### 10.3 Phase semantics

Mirror the user-wallet model (4 weekly IMs, then auto-return):

- **Phase 1** (flag): stamp `wallet_dormancy_started_at=now, wallet_dormancy_phase=1`. SL IM #1 to the leader: "Realty group [Name] has been inactive for 30 days. L$X is in the group wallet. If no member logs in within 4 weeks, the balance will be returned to your verified SL avatar."
- **Phase 2 / 3** (escalate): IM #2, #3 to leader. Re-check liveness — if any member has rotated since the last sweep, clear dormancy.
- **Phase 4 → COMPLETED**: queue `TerminalCommand{action=WITHDRAW, recipient=leader.slAvatarUuid, amount=balance, idempotency_key=group-dormancy-{groupId}-{phase4At}, realty_group_id=group.id}`. Append `realty_group_ledger{type=DORMANCY_AUTO_RETURN, ref_type=TERMINAL_COMMAND, actor_user_id=null}`. Decrement balance to zero. Stamp phase 99 (COMPLETED). Final IM to leader.

### 10.4 Active-signal reset

Two reset paths:

1. **Member login or refresh-token rotation.** Existing user-side login handler doesn't know about groups. New hook: after the user-wallet dormancy-clear runs on login, if the user is a member of any group with `wallet_dormancy_phase IS NOT NULL`, clear that group's phase too. Bounded query — most users are in 0 groups.
2. **Group-touching write paths.** Listing fee debit, agent fee credit, refund credit, manual withdraw — all activity. The wallet service clears `wallet_dormancy_phase` as part of any balance-changing write. Defense-in-depth on top of (1).

### 10.5 Edge cases

- **Group with no members** (structurally impossible — leader can't leave, removal requires the leader, dissolution requires zero balance). Defensive only: empty-member-set groups skip the active-signal query and stay flagged. They can't dissolve (balance > 0) and they can't be drained except via dormancy auto-return; auto-return is the resolution.
- **Leader's avatar can't receive auto-return** (banned by Linden, terminal pool offline). Existing `TerminalCommand` retry budget + `WITHDRAW_REVERSED` ledger row credits the group balance back. Group stays in phase 99 (COMPLETED) until manual admin intervention. Mirrors the user-wallet recovery shape.
- **Leader changes mid-dormancy-cycle.** Each phase recomputes the recipient at queue time (reads `group.getLeaderId().slAvatarUuid`), so a leadership transfer mid-cycle correctly routes to the new leader.

### 10.6 Wallet ToS asymmetry

A leader-initiated withdraw (§5.3) refuses if the leader hasn't accepted user-wallet ToS. The dormancy auto-return queues regardless. The asymmetry is deliberate: there's no human to message the error back to during auto-return, and the auto-return is unconditional once dormancy phase-4 hits. The leader-initiated version benefits from front-loading the gate for clean error UX. If the leader later visits the wallet UI to manage the auto-returned L$ on the SLPA side, they'll hit the existing user-wallet terms gate.

---

## 11. Cross-Cutting Integration

### 11.1 Search / browse

No changes. Group attribution on auction cards is C's surface; the wallet doesn't appear in search results.

### 11.2 Notifications

New SL-IM notifications:

- `groupWalletDormancyFlagged(group, phase)` — phases 1–4 to leader (§10.3).
- `groupWalletAutoReturned(group, amount)` — terminal phase-4 IM to leader.
- `groupWalletWithdrawCompleted(group, amount, leader)` — fired by `WITHDRAW_COMPLETED` ledger append; IM to leader + delegates with `VIEW_GROUP_TRANSACTIONS`. The withdraw initiator (caller) gets an in-app notification regardless of permission set.

Reuses the existing Epic 09 SL-IM dispatcher and per-user-channel pattern; group-side delivery resolves recipients per call.

### 11.3 WebSocket envelopes

New envelope `GROUP_WALLET_BALANCE_CHANGED { groupPublicId, balance, reserved, available, latestLedgerEntry }` on `/topic/realty/groups/{publicId}`. Subscribers: anyone with `VIEW_GROUP_TRANSACTIONS`. STOMP subscription gating uses the existing group-topic auth from A+B.

The existing user-side `WALLET_BALANCE_CHANGED` envelope fires on the agent-fee credit and listing-fee refund paths that touch user wallets — no schema change there.

### 11.4 Cancellation penalties

Unchanged shape. Cancellation penalties debit a user wallet (the seller). For group-listed auctions in case 1 (D-era), the seller is the listing agent and they personally absorb the penalty. The group wallet is not touched for penalties. Rationale: penalties are tied to a human's behavior (cancellation), not a group's. "Groups can absorb member penalties" would wire `SPEND_FROM_GROUP_WALLET` in a future spec.

### 11.5 Admin tooling

Admin user-wallet ops already exist (adjustments, ledger view). Admin group-wallet ops are deferred to F. D ships with no admin endpoints — leader-driven operations only. If a production incident demands admin intervention before F ships, a one-shot patch.

---

## 12. Testing Strategy

### 12.1 Backend

**Unit (service-layer):**

- `RealtyGroupWalletServiceTest`
  - `debitListingFee`: happy path, insufficient balance, dormancy-clear side effect, ledger row shape.
  - `creditAgentFee`: zero-slice short-circuit, positive credit + ledger.
  - `creditListingFeeRefund`: happy path, dissolved-group rejection.
  - `withdraw`: leader-only sub-flow, delegate flow, leader-terms-not-accepted, leader-frozen, insufficient balance, idempotency replay, terminal-command shape.
- `EscrowCompletionAgentFeeDistributionTest`
  - Group-only credit (split=1.0), agent-only credit (split=0.0), 50/50 split with floor rounding (`finalBid=1001, rate=0.05, split=0.5` → `agentFeeAmt=50, groupSlice=25, agentSlice=25`), null-realtyGroupId fallback, null-listingAgent defensive branch.
- `ListingFeeRefundProcessorTest`
  - Routes to group when `realty_group_ledger.LISTING_FEE_DEBIT` exists, falls through to user otherwise (covers C-era group listings and individual listings).
- `RealtyGroupServiceDissolveGateTest`
  - Nonzero balance → 409, in-flight escrow → 409, both clear → success.
- `GroupWalletDormancyTaskTest`
  - Phase progression 1→2→3→4→99, member-rotation reset, leader-change-mid-cycle recipient resolution, auto-return ledger row shape.

**Slice (controller):**

- `RealtyGroupWalletControllerTest`
  - 200 on GET wallet + ledger for permission-holder, 403 for outsider.
  - 202 on POST withdraw with idempotency, 422 on `INSUFFICIENT_GROUP_BALANCE` / `LEADER_TERMS_NOT_ACCEPTED` / `LEADER_FROZEN`, 410 on dissolved, 403 on missing permission.

**Integration (full-stack with `@SpringBootTest` + test container):**

- `RealtyGroupWalletIntegrationTest`
  - End-to-end pay-listing-fee debits group wallet, escrow-completion credits group + agent wallets, refund returns to group wallet, withdraw queues a terminal command and post-completion credit/reverse paths run.

**Migration:**

- `V26__realty_group_wallet.sql` is verified by `MigrationCleanupTest` (existing harness) — creates expected columns, constraints, indices.

### 12.2 Frontend

- `GroupWalletPage.test.tsx` — renders balance, ledger, withdraw modal; 403 path renders insufficient-permission notice.
- `GroupWalletBalanceCard.test.tsx` — reserved-vs-available rendering.
- `GroupWalletLedgerTable.test.tsx` — load-more pagination via MSW.
- `GroupWithdrawModal.test.tsx` — idempotency-key generation, amount validation, leader-terms-block rendering, success / 422 / 410 paths.
- `AgentFeePreview.test.tsx` (extends C tests) — "Listing fee paid from [Group] wallet — balance L$X" line renders for group flow; insufficient-balance disables publish; individual flow unaffected.

MSW handlers gain: `GET /realty/groups/{publicId}/wallet`, `GET .../wallet/ledger`, `POST .../wallet/withdraw`. Default mock returns a zero-balance group with empty ledger; test-specific overrides override.

### 12.3 Postman

New folder `SLPA → Realty Groups → Wallet`:

- `GET group wallet` (chains `groupPublicId`)
- `GET group ledger` (chains cursor)
- `POST withdraw` (chains `withdrawQueueId`, idempotency-key generated by pre-request script)
- `POST pay listing fee — group path` (chains `auctionPublicId` from a created group listing)
- Dissolve-with-nonzero-balance → 409 (negative test)

Threaded through the existing `SLPA Dev` environment; new variables: `groupWalletBalance`, `groupWithdrawQueueId`, `groupLedgerCursor`.

### 12.4 Verify guards

No new guards. The existing four (`no-dark-variants`, `no-hex-colors`, `no-inline-styles`, `coverage`) cover the new frontend surfaces.

---

## 13. Out of Scope — Sub-projects E, F

### 13.1 E — Alternative parcel ownership + delegated manage permissions

Unchanged from C's §13.2. Case 2 (member-owned parcel), case 3 (SL-group-owned parcel), `realty_group_sl_groups` table, SL-group verification flows, `REGISTER_SL_GROUP` permission wiring, `MANAGE_OWN_LISTING` / `MANAGE_ALL_LISTINGS` wiring, broker-cancel/pause endpoints.

E will also extend the withdraw flow to allow sending to a verified registered SL group: the withdraw modal grows a recipient picker `[Leader's avatar] / [SL Group X] / ...`, and `TerminalCommand.action` gains `WITHDRAW_GROUP` with a LibreMetaverse `GiveGroupMoney`-style fulfillment path on the bot side.

### 13.2 F — Admin moderation

Unchanged from A+B's §12.4. Group suspension, fraud flagging, force-pause all listings, group bans, reports, audit log, reputation. F gains admin wallet ops on the group surface (adjustments, force-credit, force-debit) symmetric to the existing user-wallet admin tools.

### 13.3 Discretionary group spend (no issue yet)

`SPEND_FROM_GROUP_WALLET` is defined by D but not wired. Future features that justify wiring it (advertising spend, paying member penalties from group funds, sponsored auctions, group-level promotions) get their own specs at the time they're scoped. The permission is in the enum so default-invite matrices and the permission-management UI surface it from D onward, avoiding a backfill migration when the first such feature lands.

---

## 14. Migration / Cutover

### 14.1 Flyway migration

`V26__realty_group_wallet.sql`:

```sql
-- 1. Wallet columns on realty_groups.
ALTER TABLE realty_groups
    ADD COLUMN balance_lindens BIGINT NOT NULL DEFAULT 0
        CHECK (balance_lindens >= 0),
    ADD COLUMN reserved_lindens BIGINT NOT NULL DEFAULT 0
        CHECK (reserved_lindens >= 0),
    ADD CONSTRAINT realty_groups_wallet_balance_ge_reserved
        CHECK (balance_lindens >= reserved_lindens),
    ADD COLUMN wallet_dormancy_started_at TIMESTAMPTZ NULL,
    ADD COLUMN wallet_dormancy_phase SMALLINT NULL
        CHECK (wallet_dormancy_phase BETWEEN 1 AND 4 OR wallet_dormancy_phase = 99);

-- 2. realty_group_ledger table.
CREATE TABLE realty_group_ledger (
    id                  BIGSERIAL PRIMARY KEY,
    public_id           UUID NOT NULL UNIQUE,
    group_id            BIGINT NOT NULL REFERENCES realty_groups(id),
    entry_type          VARCHAR(32) NOT NULL,
    amount              BIGINT NOT NULL CHECK (amount > 0),
    balance_after       BIGINT NOT NULL,
    reserved_after      BIGINT NOT NULL,
    ref_type            VARCHAR(32) NULL,
    ref_id              BIGINT NULL,
    actor_user_id       BIGINT NULL REFERENCES users(id),
    sl_transaction_id   VARCHAR(36) NULL,
    idempotency_key     VARCHAR(64) NULL,
    description         VARCHAR(500) NULL,
    created_at          TIMESTAMPTZ NOT NULL,
    created_by_admin_id BIGINT NULL REFERENCES users(id)
);

CREATE INDEX realty_group_ledger_group_created_idx
    ON realty_group_ledger (group_id, created_at DESC);
CREATE UNIQUE INDEX realty_group_ledger_sl_tx_idx
    ON realty_group_ledger (sl_transaction_id) WHERE sl_transaction_id IS NOT NULL;
CREATE UNIQUE INDEX realty_group_ledger_idempotency_idx
    ON realty_group_ledger (idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX realty_group_ledger_listing_fee_lookup_idx
    ON realty_group_ledger (ref_type, ref_id) WHERE entry_type = 'LISTING_FEE_DEBIT';

ALTER TABLE realty_group_ledger ADD CONSTRAINT realty_group_ledger_entry_type_check CHECK (
    entry_type IN (
        'LISTING_FEE_DEBIT', 'LISTING_FEE_REFUND',
        'AGENT_FEE_CREDIT',
        'WITHDRAW_QUEUED', 'WITHDRAW_COMPLETED', 'WITHDRAW_REVERSED',
        'DORMANCY_AUTO_RETURN',
        'ADJUSTMENT'
    )
);

-- 3. Terminal command linkage so completion handlers know which ledger to write.
ALTER TABLE terminal_commands
    ADD COLUMN realty_group_id BIGINT NULL REFERENCES realty_groups(id);

CREATE INDEX terminal_commands_realty_group_id_idx
    ON terminal_commands (realty_group_id) WHERE realty_group_id IS NOT NULL;

-- 4. Reconciliation extension.
ALTER TABLE reconciliation_runs
    ADD COLUMN group_wallet_balance_total BIGINT NULL,
    ADD COLUMN group_wallet_reserved_total BIGINT NULL;

-- 5. user_ledger gets AGENT_FEE_CREDIT entry type.
ALTER TABLE user_ledger DROP CONSTRAINT user_ledger_entry_type_check;
ALTER TABLE user_ledger ADD CONSTRAINT user_ledger_entry_type_check CHECK (
    entry_type IN (
        'DEPOSIT',
        'WITHDRAW_QUEUED', 'WITHDRAW_COMPLETED', 'WITHDRAW_REVERSED',
        'BID_RESERVED', 'BID_RELEASED',
        'ESCROW_DEBIT', 'ESCROW_REFUND',
        'LISTING_FEE_DEBIT', 'LISTING_FEE_REFUND',
        'PENALTY_DEBIT',
        'AGENT_FEE_CREDIT',
        'ADJUSTMENT'
    )
);
```

No data migration. Existing `realty_groups` rows pick up zero balances (DEFAULT 0). `reconciliation_runs` history keeps NULL on the new columns (read-only).

### 14.2 Backward compatibility

- Existing `realty_groups` rows: all default to `balance=0, reserved=0`, no dormancy state. They behave identically to before until a debit or credit fires.
- C-era group-listed auctions still open at D-launch: listing-fee refund routing (§8) finds the originating `user_ledger.LISTING_FEE_DEBIT` row and refunds to user wallet — correct, as C did.
- Existing user-wallet flows (deposit / withdraw / pay-listing-fee for individual listings) unchanged; the `AGENT_FEE_CREDIT` entry type addition is forward-additive.

### 14.3 Rollback

- Code rollback only. The migration is forward-additive: new columns default 0, new table is empty, the `user_ledger_entry_type_check` widening doesn't reject any existing row.
- If D's code is reverted, dormant `realty_group_ledger` rows are harmless data. The widened `user_ledger` constraint stays widened — no existing `AGENT_FEE_CREDIT` rows would exist pre-D, and a reverted system simply doesn't insert any.
- Hard rollback (DROP TABLE / DROP COLUMN) is safe if absolutely needed, but unnecessary.

### 14.4 Deferred items handed off to later sub-projects

- **SL-group-destination withdraws** → E. D ships leader-avatar withdraws only.
- **Admin group-wallet ops** (force-credit, force-debit, manual ledger adjustments) → F.
- **`SPEND_FROM_GROUP_WALLET` wiring** → indefinite, alongside the first discretionary-spend feature.

---

## 15. Open / Deferred Decisions

| Decision | Status | Owner |
|---|---|---|
| Backfill policy for C-era closed auctions | Resolved: go-live-forward by definition (no production users existed during the C-to-D window). If real users had data in flight at D-launch, we'd reconsider with a targeted one-shot. | D (resolved) |
| Agent-fee distribution timing | Resolved: at escrow-completion, alongside seller payout (Q1-a). Snapshot-time credit would have broken the wallet-reconciliation invariant. | D (resolved) |
| Listing-fee permission split | Resolved: `CREATE_LISTING` alone authorizes listing-fee debit (Q2-a). | D (resolved) |
| Withdraw recipient | Resolved: leader's avatar for D, SL-group destinations deferred to E (Q3-a). | D (resolved) |
| `SPEND_FROM_GROUP_WALLET` wiring | Resolved: defined-but-not-wired in D (Q4-a). Same pattern C used for `MANAGE_OWN_LISTING` / `MANAGE_ALL_LISTINGS`. | Future feature |
| Group dormancy active-signal | Resolved: any member's recent refresh-token rotation (Q5-b). Decouples group dormancy from leader-specific dormancy. | D (resolved) |
| Ledger storage shape | Resolved: separate `realty_group_ledger` (Q6-a). One-table-with-nullable-`user_id` would have broken the wallet-model §3.5 invariant. | D (resolved) |
| Listing-fee refund routing | Resolved: lookup originating ledger row (Q7-a). Handles C-era and D-era cases correctly without a denorm column. | D (resolved) |
| Per-group listing-fee override | Not in scope. Listing fees are platform-level. | — |
| Cancellation penalties paid from group wallet | Not in scope (§11.4). Future discretionary-spend feature. | Future |
| Admin force-dissolve with nonzero balance | Out of scope. F decides force-dissolve semantics. | F |
