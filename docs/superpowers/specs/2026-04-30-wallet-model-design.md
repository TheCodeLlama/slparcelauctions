# Wallet Model — User L$ Wallet, Hard Bid Reservations, Auto-Funded Escrow

**Date:** 2026-04-30
**Branch target:** `feat/wallet-model` off `dev`
**Scope:** Replace the per-obligation in-world payment flow (escrow / listing-fee / penalty paid directly at the terminal) with a user wallet model. Deposits credit a per-user L$ balance; bids hard-reserve from balance; escrow auto-funds at auction close; refunds collapse to wallet credits; withdrawals route to the user's verified SL avatar via the existing terminal-command pipeline. Includes all required schema, backend services, frontend dashboard work, LSL rewrites, Postman rebuild, and operational scaffolding (reconciliation extension, dormancy, terms-of-use).

This is a destructive, single-shot replacement of four SL-headers payment endpoints and the in-world touch flow. With no live customers, schema migrations are forward-only and additive-only discipline is not required; the rollback path is `git revert` + RDS snapshot restore.

---

## §1 — Scope

**In scope:**

- New entities: `user_ledger` (append-only per-user ledger), `bid_reservations` (active high-bid reservations per user per auction).
- New columns on `users`: `balance_lindens`, `reserved_lindens`, `wallet_dormancy_started_at`, `wallet_dormancy_phase`, `wallet_terms_accepted_at`, `wallet_terms_version`.
- New SL-headers-gated endpoints: `POST /api/v1/sl/wallet/deposit`, `POST /api/v1/sl/wallet/withdraw-request`.
- New user-facing endpoints: `GET /api/v1/me/wallet`, `POST /api/v1/me/wallet/withdraw`, `POST /api/v1/me/wallet/pay-penalty`, `POST /api/v1/me/wallet/accept-terms`, `POST /api/v1/me/auctions/{id}/pay-listing-fee` (replaces the in-world listing-fee terminal flow; transitions DRAFT → DRAFT_PAID with wallet debit).
- Modified endpoints: bid placement (penalty + balance preconditions, hard reservation swap), Buy-It-Now (penalty + balance precondition, full-amount debit). The existing `PUT /api/v1/auctions/{id}/verify` (DRAFT_PAID → VERIFICATION_PENDING / ACTIVE per Method A or B) is unchanged in shape — its precondition that listing fee has been paid still holds; the wallet model only changes how the listing fee was paid.
- Removed endpoints: `POST /api/v1/sl/escrow/payment`, `POST /api/v1/sl/listing-fee/payment`, `POST /api/v1/sl/penalty-lookup`, `POST /api/v1/sl/penalty-payment`. The dev-profile listing-fee stub is renamed to `/api/v1/dev/auctions/{id}/mark-listing-fee-paid` for fixture use.
- Auction lifecycle: `ESCROW_PENDING` state retired; escrow rows are created directly in `FUNDED` state by `AuctionEndTask.closeOne` (and by the BIN handler) by consuming the winner's bid reservation and debiting their wallet.
- Refund collapse: escrow expiry refunds, dispute refunds, and listing-fee refunds become wallet credits (`user_ledger` rows). No `TerminalCommand{action=REFUND}` is queued for refunds. Outbound `TerminalCommand` is now used only for `PAYOUT` (seller payout after transfer) and `WITHDRAW` (user-initiated wallet withdrawals + dormancy auto-returns).
- Dormancy: 30-day inactivity threshold (signal: most recent `refresh_tokens.created_at`), 4 weekly SL IM notifications via Epic 09 dispatcher, then auto-withdraw to the user's verified SL avatar. Excludes users with active reservations or funded escrows.
- Reconciliation extension: `ReconciliationService.runDaily()` adds `SUM(users.balance_lindens) + SUM(active_bid_reservations.amount)` to its expected total, plus a `DENORM_DRIFT` precheck on `users.reserved_lindens`.
- Wallet terms-of-use: new `/legal/terms` website page; first-deposit click-through stamps `users.wallet_terms_accepted_at`.
- LSL: full rewrite of `slpa-terminal.lsl` (lockless deposit via `money()` reentrancy, per-flow withdraw slots dispatched by avatar key on a single shared listen, no per-obligation menu). New `slpa-verifier-giver.lsl` script + prim (touch-to-receive parcel verifier; replaces the unified terminal's "Get Parcel Verifier" menu option).
- Postman collection rebuild: new `Wallet` folder, retire 4 SL-payment folders, update `SLPA Dev` environment variables.
- Documentation: full LSL READMEs, root README sweep, `CONVENTIONS.md` additions (immutable ledger discipline, `available = balance - reserved` invariant, recipient-UUID lock), `FOOTGUNS.md` additions, `DEFERRED_WORK.md` updates.

**Out of scope (explicitly deferred):**

- USD ↔ L$ exchange functionality. Wallet only holds L$ from resident-to-resident transfers.
- Interest, yield, or any return-on-balance product (prohibited by SL banking policy carve-out).
- Multi-currency support.
- Admin manual adjustments to wallet ledger (added later if needed; the schema supports `entry_type=ADJUSTMENT` with `created_by_admin_id` for forward compatibility, but no admin UI is built in this work).
- Email confirmation on withdrawals (no SMTP infrastructure; not required because the recipient UUID is locked to the user's verified SL avatar).
- Per-withdraw caps, daily caps, cool-downs (not required for the same reason — see §10).
- Automatic "abandoned cart" cleanup of un-allocated deposits — N/A in the wallet model since there is no allocation step; deposits credit the wallet directly.

**Constraints inherited from prior decisions:**

- `available = balance_lindens - reserved_lindens` is the invariant. Withdraw, allocate, and bid endpoints all gate on `available`, never on raw `balance_lindens`.
- Ledgers are append-only. Withdraw lifecycle uses `WITHDRAW_QUEUED → WITHDRAW_COMPLETED|WITHDRAW_REVERSED` as separate appended rows, not mutations of the original.
- Recipient UUID for any outbound L$ is always `user.slAvatarUuid` (locked at verification). Never client-supplied.
- No customers exist yet → schema migrations may be destructive; rollback is RDS snapshot restore + `git revert`.

---

## §2 — Architectural Overview

```
            ┌────────────────────────────────────────────────────┐
            │  In-world SLPA Terminal (LSL prim)                 │
            │                                                    │
            │  Steady-state:  llSetPayPrice(default + quick L$)  │
            │  on money():    POST /sl/wallet/deposit (lockless) │
            │  on touch:      Dialog [Deposit-info, Withdraw]    │
            │  Withdraw:      per-flow slot, single shared listen│
            └──────┬──────────────────────────┬──────────────────┘
                   │                          │
        /sl/wallet/deposit         /sl/wallet/withdraw-request
                   ▼                          ▼
        ┌───────────────────────────────────────────────────────┐
        │  Backend (Spring Boot)                                │
        │                                                       │
        │  ┌──────────────┐   ┌──────────────────────────────┐  │
        │  │ WalletService│◄──┤ Existing Escrow / Auction /  │  │
        │  │              │   │ Bid services                 │  │
        │  └──────┬───────┘   └──────────────────────────────┘  │
        │         │                                             │
        │         ▼                                             │
        │  user_ledger          escrow_transactions             │
        │  (per-user, append)   (per-escrow, append, kept)      │
        │                                                       │
        │  bid_reservations  ────►  users.balance_lindens       │
        │  (active locks)           users.reserved_lindens      │
        │                                                       │
        │  Outbound:  TerminalCommand{PAYOUT | WITHDRAW}        │
        │            (REFUND action retired — refunds = credits)│
        └───────────────────────────────────────────────────────┘
                   ▲                          ▲
                   │ /me/wallet/*             │ Bid / BIN / Publish
                   │                          │
        ┌──────────┴──────────────────────────┴─────────────────┐
        │  Frontend (Next.js)                                   │
        │  Wallet panel (balance / reserved / available)        │
        │  Withdraw dialog · Pay-penalty dialog                 │
        │  Bid + Publish flows show preconditions inline        │
        └───────────────────────────────────────────────────────┘
```

The existing `escrow_transactions` ledger remains in place — escrow is still a per-auction state machine with its own ledger. The wallet model adds a per-user ledger above it. Auction-end auto-fund writes both ledger rows in one transaction (`user_ledger.ESCROW_DEBIT` + `escrow_transactions.AUCTION_ESCROW_PAYMENT`); reconciliation ties them together.

The HTTP-in pipeline from backend to terminal (PAYOUT / WITHDRAW) is unchanged in shape — the dispatcher, terminal pool, retry budget, and idempotency-key model are reused as-is. The `REFUND` action is removed from `TerminalCommand` because all refund flows now land as wallet credits, not L$ transfers back to a resident.

---

## §3 — Data Model

### 3.1 `users` (modified)

Additive columns (all `NOT NULL DEFAULT 0` or nullable):

| Column | Type | Notes |
|---|---|---|
| `balance_lindens` | `BIGINT NOT NULL DEFAULT 0` | Available + reserved combined. `CHECK (balance_lindens >= 0)`. |
| `reserved_lindens` | `BIGINT NOT NULL DEFAULT 0` | Sum of active reservations. Denormalized; `CHECK (reserved_lindens >= 0)` and `CHECK (balance_lindens >= reserved_lindens)`. |
| `wallet_dormancy_started_at` | `TIMESTAMPTZ NULL` | Stamped by `WalletDormancyJob` phase 1; cleared on login or refresh-token rotation. |
| `wallet_dormancy_phase` | `SMALLINT NULL CHECK (wallet_dormancy_phase BETWEEN 1 AND 4)` | Current dormancy notification phase; cleared on activity. |
| `wallet_terms_accepted_at` | `TIMESTAMPTZ NULL` | Click-through stamp; required before first deposit (UI-side gate). |
| `wallet_terms_version` | `VARCHAR(16) NULL` | Version of terms accepted; future material change re-prompts. |

`available_lindens` is **not** stored — computed as `balance_lindens - reserved_lindens` at read time.

### 3.2 `user_ledger` (new) — append-only

```sql
CREATE TABLE user_ledger (
    id                  BIGSERIAL PRIMARY KEY,
    user_id             BIGINT NOT NULL REFERENCES users(id),
    entry_type          VARCHAR(32) NOT NULL,
    amount              BIGINT NOT NULL CHECK (amount > 0),
    balance_after       BIGINT NOT NULL,
    reserved_after      BIGINT NOT NULL,
    ref_type            VARCHAR(32) NULL,
    ref_id              BIGINT NULL,
    sl_transaction_id   VARCHAR(36) NULL,
    idempotency_key     VARCHAR(64) NULL,
    description         VARCHAR(500) NULL,
    created_at          TIMESTAMPTZ NOT NULL,
    created_by_admin_id BIGINT NULL REFERENCES users(id)
);

CREATE INDEX user_ledger_user_created_idx ON user_ledger (user_id, created_at DESC);
CREATE UNIQUE INDEX user_ledger_sl_tx_idx ON user_ledger (sl_transaction_id) WHERE sl_transaction_id IS NOT NULL;
CREATE UNIQUE INDEX user_ledger_idempotency_idx ON user_ledger (idempotency_key) WHERE idempotency_key IS NOT NULL;

ALTER TABLE user_ledger ADD CONSTRAINT user_ledger_entry_type_check CHECK (
    entry_type IN (
        'DEPOSIT',
        'WITHDRAW_QUEUED', 'WITHDRAW_COMPLETED', 'WITHDRAW_REVERSED',
        'BID_RESERVED', 'BID_RELEASED',
        'ESCROW_DEBIT', 'ESCROW_REFUND',
        'LISTING_FEE_DEBIT', 'LISTING_FEE_REFUND',
        'PENALTY_DEBIT',
        'ADJUSTMENT'
    )
);
```

`amount` is always positive; direction is implicit in `entry_type`. `balance_after` and `reserved_after` are snapshots written at insert time — these are the source of truth for state, derived deltas are computed against the prior row when needed.

`ref_type` / `ref_id` point to the related domain entity (`AUCTION`, `ESCROW`, `BID`, `TERMINAL_COMMAND`, `LISTING_FEE_REFUND`, `PENALTY`, `ADJUSTMENT`). No FK constraint — append-only ledgers don't cascade.

### 3.3 `bid_reservations` (new)

```sql
CREATE TABLE bid_reservations (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES users(id),
    auction_id      BIGINT NOT NULL REFERENCES auctions(id),
    bid_id          BIGINT NOT NULL REFERENCES bids(id),
    amount          BIGINT NOT NULL CHECK (amount > 0),
    created_at      TIMESTAMPTZ NOT NULL,
    released_at     TIMESTAMPTZ NULL,
    release_reason  VARCHAR(32) NULL CHECK (release_reason IN (
        'OUTBID', 'AUCTION_CANCELLED', 'AUCTION_FRAUD_FREEZE',
        'ESCROW_FUNDED', 'USER_BANNED'
    ))
);

CREATE UNIQUE INDEX bid_reservations_active_idx
    ON bid_reservations (user_id, auction_id) WHERE released_at IS NULL;

CREATE INDEX bid_reservations_user_active_idx
    ON bid_reservations (user_id) WHERE released_at IS NULL;
```

The partial unique index enforces "at most one active reservation per (user, auction)." Outbid → release prior, insert new for same user/auction is fine because the prior row's `released_at` is no longer NULL.

### 3.4 Reconciliation extension

`reconciliation_runs` (existing table) gains:

| Column | Type | Notes |
|---|---|---|
| `wallet_balance_total` | `BIGINT NULL` | `SUM(users.balance_lindens)` at run time. |
| `wallet_reserved_total` | `BIGINT NULL` | `SUM(users.reserved_lindens)` at run time. |
| `escrow_locked_total` | `BIGINT NULL` | The existing `expected_total` value, renamed for clarity. |

`expected_total` becomes the sum of all three. Old runs left with NULL for the new columns are fine (read-only history).

`ReconciliationStatus` enum gains `DENORM_DRIFT` for the case where `users.reserved_lindens` doesn't match the live `bid_reservations` sum.

### 3.5 Invariants

For any user U at any consistent snapshot (post-commit):

1. `users.balance_lindens(U) = SUM(user_ledger.balance_after of the latest row WHERE user_id=U)`. Every ledger row updates the snapshot.
2. `users.reserved_lindens(U) = SUM(bid_reservations.amount WHERE user_id=U AND released_at IS NULL)`.
3. `users.balance_lindens(U) >= users.reserved_lindens(U)` (DB-enforced).
4. `SUM(users.balance_lindens) + SUM(active reservations) + SUM(escrow_lockedstates.amount) ≈ SLPA service avatar L$ balance` (within in-flight `TerminalCommand` tolerance; `WalletReconciliationJob` verifies daily).

Drift on (1), (2), or (4) → reconciliation alarm.

---

## §4 — HTTP Contracts

### 4.1 New SL-headers-gated endpoints

All inherit the existing pipeline: `SlHeaderValidator` (`X-SecondLife-Shard=Production`, `X-SecondLife-Owner-Key ∈ slpa.sl.trusted-owner-keys`) + `sharedSecret` body field + `terminalId` lookup.

#### `POST /api/v1/sl/wallet/deposit`

**Request:**
```json
{
  "payerUuid": "<money() payerKey>",
  "amount": 1500,
  "slTransactionKey": "<llGenerateKey() once, same across retries>",
  "terminalId": "...",
  "sharedSecret": "..."
}
```

**Validation pipeline:**
1. `SlHeaderValidator` → 403 with `ERROR/BAD_HEADERS` body on fail.
2. `sharedSecret` matches `slpa.escrow.terminal-shared-secret` → 403 `ERROR/SECRET_MISMATCH`.
3. `terminalId` resolves to a registered `Terminal` row → 404 `ERROR/UNKNOWN_TERMINAL`.
4. Idempotency: if `user_ledger.sl_transaction_id == slTransactionKey` exists → return that row's original wire response (replay).
5. `users.findBySlAvatarUuid(payerUuid)`. Absent → 200 `REFUND/UNKNOWN_PAYER`. Status `BANNED|FROZEN` → 200 `REFUND/USER_FROZEN`.
6. Lock user `PESSIMISTIC_WRITE`. `balance_lindens += amount`. Insert `user_ledger{type=DEPOSIT, amount, balance_after, reserved_after, sl_transaction_id, ref_type=null, ref_id=null}`.
7. Commit. WS envelope `WALLET_BALANCE_CHANGED` to user's STOMP topic if connected.

**Response shapes (LSL-parsable):**
```json
{ "status": "OK" }
{ "status": "REFUND",  "reason": "UNKNOWN_PAYER" | "USER_FROZEN", "message": "..." }
{ "status": "ERROR",   "reason": "BAD_HEADERS" | "SECRET_MISMATCH" | "UNKNOWN_TERMINAL", "message": "..." }
```

REFUND vs ERROR rule (preserved from existing endpoints): on `REFUND`, the LSL script `llTransferLindenDollars` to bounce; on `ERROR`, owner-say only — never refund blind, since ERROR may be an attacker probing the endpoint with no real L$ attached.

#### `POST /api/v1/sl/wallet/withdraw-request`

**Request:**
```json
{
  "payerUuid": "<toucher avatar key>",
  "amount": 500,
  "slTransactionKey": "<one per confirmed touch session>",
  "terminalId": "...",
  "sharedSecret": "..."
}
```

**Validation:**
1. Header + secret + terminal pipeline (same).
2. Idempotency on `slTransactionKey` against `user_ledger`.
3. Resolve user by `slAvatarUuid`. Absent → `REFUND_BLOCKED/NOT_LINKED`. Status `BANNED|FROZEN` → `REFUND_BLOCKED/USER_FROZEN`.
4. Lock user. Validate `(balance_lindens - reserved_lindens) >= amount`. Insufficient → `REFUND_BLOCKED/INSUFFICIENT_BALANCE`.
5. `balance_lindens -= amount`. Insert `user_ledger{type=WITHDRAW_QUEUED, sl_transaction_id, idempotency_key=null}`. Insert `TerminalCommand{action=WITHDRAW, recipient_uuid=user.slAvatarUuid, amount, idempotency_key=<auto>}`.
6. Commit.

**Response shapes:**
```json
{ "status": "OK", "queueId": 42 }
{ "status": "REFUND_BLOCKED", "reason": "INSUFFICIENT_BALANCE" | "USER_FROZEN" | "NOT_LINKED", "message": "..." }
{ "status": "ERROR", "reason": "BAD_HEADERS" | "SECRET_MISMATCH" | "UNKNOWN_TERMINAL", "message": "..." }
```

`REFUND_BLOCKED` is distinct from `REFUND`: no L$ was paid in this flow, so the LSL **does not** call `llTransferLindenDollars`. It owner-says + `llRegionSayTo(toucher, message)` and releases the session slot.

### 4.2 New user-facing endpoints (JWT-gated)

#### `GET /api/v1/me/wallet`

**Response:**
```json
{
  "balance":   1500,
  "reserved":  500,
  "available": 1000,
  "penaltyOwed": 0,
  "termsAccepted": true,
  "termsVersion": "1.0",
  "recentLedger": [
    { "id": 12, "entryType": "DEPOSIT", "amount": 1000, "balanceAfter": 1500, "reservedAfter": 500, "createdAt": "..." },
    ...
  ]
}
```

`recentLedger` is the most recent 50 entries; `GET /api/v1/me/wallet/ledger?cursor=...` paginates older entries.

#### `POST /api/v1/me/wallet/withdraw`

**Request:**
```json
{ "amount": 500, "idempotencyKey": "<client UUID>" }
```

Recipient is **always** `user.slAvatarUuid`. Never client-supplied; the field is not present on the request schema.

**Validation:**
1. JWT → user identity.
2. `user.status NOT IN (BANNED, FROZEN)` → 403 `USER_FROZEN`.
3. Idempotency on `idempotencyKey` against `user_ledger.idempotency_key`. Replay returns the original 202.
4. Lock user. Validate `available >= amount`. Insufficient → 422 `INSUFFICIENT_AVAILABLE_BALANCE { available, requested }`.
5. Decrement balance, append `WITHDRAW_QUEUED` row with `idempotency_key`, insert `TerminalCommand{action=WITHDRAW}`.
6. WS envelope `WALLET_BALANCE_CHANGED`.

**Response:** `202 { queueId, estimatedFulfillmentSeconds }`.

#### `POST /api/v1/me/wallet/pay-penalty`

**Request:**
```json
{ "amount": 200, "idempotencyKey": "<client UUID>" }
```

**Validation:**
1. JWT → user.
2. Lock user.
3. `amount > 0 AND amount <= user.penalty_balance_owed` → 422 `AMOUNT_EXCEEDS_OWED { owed, requested }`.
4. `(balance - reserved) >= amount` → 422 `INSUFFICIENT_AVAILABLE_BALANCE`. (Cannot surrender reserved L$ to pay a penalty — that would break bid commitments.)
5. Idempotency on `idempotencyKey`.
6. `balance_lindens -= amount`; `penalty_balance_owed -= amount`; append `user_ledger{type=PENALTY_DEBIT, ref_type=PENALTY, ref_id=null}`.
7. If `penalty_balance_owed` reaches 0 → existing penalty-cleared logic fires (Epic 08 hooks).

**Response:** `200 { newBalance, newPenaltyOwed }`.

#### `POST /api/v1/me/wallet/accept-terms`

**Request:** `{ termsVersion: "1.0" }`. JWT-gated. Stamps `wallet_terms_accepted_at = now`, `wallet_terms_version = body.termsVersion`. `200`.

Required before first deposit credits (the deposit endpoint succeeds without this flag, but the website refuses to render the deposit-instructions modal until the user has accepted).

### 4.3 Modified existing endpoints

#### Bid placement (`POST /api/v1/auctions/{id}/bid`)

Existing endpoint shape unchanged. New preconditions inside the existing transaction (auction lock first, then user locks ascending):

1. (existing) Lock auction `PESSIMISTIC_WRITE`.
2. (existing) Validate auction state, bid > current high.
3. (existing) Read prior active `bid_reservation` for this auction, identify prior bidder.
4. Lock new + prior bidder rows in ascending `user_id` order.
5. **NEW:** Validate `newBidder.penalty_balance_owed == 0`. Fail → 422 `PENALTY_OUTSTANDING { owed }`.
6. **NEW:** Validate `(newBidder.balance_lindens - newBidder.reserved_lindens) >= newBidAmount`. Fail → 422 `INSUFFICIENT_AVAILABLE_BALANCE`.
7. **NEW:** Reservation swap (see §5).
8. (existing) Persist bid row.
9. Commit.

#### Buy-It-Now (`POST /api/v1/auctions/{id}/buy-now`)

Same preconditions as bid. Plus:

- BIN amount > current high; the BIN-clicker may have an existing reservation on this auction at a lower amount.
- Validate `available + (existing reservation on this auction, if any) >= bin_price`.
- On success: release any existing reservation on this auction (it would be the BIN-clicker's), debit `balance_lindens -= bin_price`, append `user_ledger{type=ESCROW_DEBIT}`, transition auction to `ENDED+BOUGHT_NOW`, create escrow directly in `FUNDED` state.

#### Listing-fee payment (new endpoint, drives DRAFT → DRAFT_PAID)

Replaces the in-world listing-fee terminal flow. The existing `PUT /api/v1/auctions/{id}/verify` endpoint (which today transitions DRAFT_PAID → VERIFICATION_PENDING / ACTIVE) is unchanged. Its precondition `state == DRAFT_PAID` still holds; the wallet model only changes how DRAFT becomes DRAFT_PAID.

**New endpoint:** `POST /api/v1/me/auctions/{id}/pay-listing-fee`

**Validation:**
1. JWT → user.
2. Find auction; verify `seller_user_id == user.id` AND `state == DRAFT`.
3. Lock user.
4. **NEW:** Validate `user.penalty_balance_owed == 0` → 422 `PENALTY_OUTSTANDING`.
5. **NEW:** Validate `available >= auction.listing_fee_amount` → 422 `INSUFFICIENT_AVAILABLE_BALANCE`.
6. `balance_lindens -= listing_fee_amount`; append `user_ledger{type=LISTING_FEE_DEBIT, ref_type=AUCTION, ref_id=auction.id}`; append `escrow_transactions{type=LISTING_FEE_PAYMENT, status=COMPLETED}`.
7. Transition `DRAFT → DRAFT_PAID`. Commit.
8. WS envelope `LISTING_FEE_PAID`.

The seller's UX is two clicks: pay listing fee (this endpoint), then verify (the existing endpoint). Same as today, just the L$ source for the first step changes from in-world payment to wallet debit.

DRAFT creation itself has zero L$ involvement — it's just a database row. Sellers can save and abandon drafts freely without affecting balance, available, or anything wallet-side.

### 4.4 Removed endpoints

- `POST /api/v1/sl/escrow/payment` — replaced by auto-fund at auction close (see §6).
- `POST /api/v1/sl/listing-fee/payment` — replaced by listing-fee debit folded into publish (§4.3).
- `POST /api/v1/sl/penalty-lookup` — penalty owed exposed on `GET /me/wallet` and `GET /me/profile`.
- `POST /api/v1/sl/penalty-payment` — replaced by `POST /me/wallet/pay-penalty`.

The dev-profile listing-fee stub `POST /api/v1/dev/auctions/{id}/pay` is renamed to `POST /api/v1/dev/auctions/{id}/mark-listing-fee-paid` — the rename clarifies that no L$ changes hands; it just transitions the auction state for fixture setup.

### 4.5 Endpoint summary table

| Endpoint | Status |
|---|---|
| `POST /api/v1/sl/wallet/deposit` | NEW |
| `POST /api/v1/sl/wallet/withdraw-request` | NEW |
| `GET  /api/v1/me/wallet` | NEW |
| `GET  /api/v1/me/wallet/ledger` | NEW |
| `POST /api/v1/me/wallet/withdraw` | NEW |
| `POST /api/v1/me/wallet/pay-penalty` | NEW |
| `POST /api/v1/me/wallet/accept-terms` | NEW |
| `POST /api/v1/me/auctions/{id}/pay-listing-fee` | NEW (drives DRAFT → DRAFT_PAID with wallet debit) |
| `POST /api/v1/auctions/{id}/bid` | MODIFIED (preconditions + reservation swap) |
| `POST /api/v1/auctions/{id}/buy-now` | MODIFIED (preconditions + immediate escrow funding) |
| `PUT  /api/v1/auctions/{id}/verify` | UNCHANGED (still drives DRAFT_PAID → VERIFICATION_PENDING / ACTIVE) |
| `POST /api/v1/auctions/{id}/escrow/dispute` | UNCHANGED |
| `GET  /api/v1/auctions/{id}/escrow` | UNCHANGED |
| `POST /api/v1/sl/terminal/register` | UNCHANGED |
| `POST /api/v1/sl/terminal/heartbeat` | UNCHANGED |
| `POST /api/v1/sl/escrow/payout-result` | EXTENDED (now also handles `WITHDRAW` action results) |
| `POST /api/v1/sl/escrow/payment` | REMOVED |
| `POST /api/v1/sl/listing-fee/payment` | REMOVED |
| `POST /api/v1/sl/penalty-lookup` | REMOVED |
| `POST /api/v1/sl/penalty-payment` | REMOVED |
| `POST /api/v1/dev/auctions/{id}/pay` | RENAMED → `mark-listing-fee-paid` |

---

## §5 — Bid Reservation Atomicity

### 5.1 Core operation

When user A places a new high bid on auction X, in **one transaction**:

1. Read prior active `bid_reservation` row for auction X (call its owner P, amount `p`, if any).
2. Validate A has `available = balance_lindens(A) - reserved_lindens(A) >= newBidAmount` (precondition §4.3).
3. If P exists: mark P's reservation `released_at=now, release_reason=OUTBID`; `users.reserved_lindens(P) -= p`; append `user_ledger{user=P, type=BID_RELEASED, amount=p, balance_after=unchanged, reserved_after=new, ref_type=BID, ref_id=oldBidId}`.
4. Insert new `bid_reservation{user=A, auction=X, amount=newBidAmount, bid_id=newBid.id}`. `users.reserved_lindens(A) += newBidAmount`. Append `user_ledger{user=A, type=BID_RESERVED, amount=newBidAmount, balance_after=unchanged, reserved_after=new, ref_type=BID, ref_id=newBid.id}`.
5. Insert the `bid` row itself (existing behavior).

If precondition (2) fails → 422 `INSUFFICIENT_AVAILABLE_BALANCE`. No rows written; transaction rolled back.

### 5.2 Lock ordering

Rule: **lock auction first, then user rows in ascending `user_id` order.** Always.

```
1. SELECT FROM auctions WHERE id = X FOR UPDATE
2. (read prior reservation; identify P)
3. SELECT FROM users WHERE id IN (A, P) ORDER BY id ASC FOR UPDATE
4. (do the reservation swap)
5. (write bid row)
6. COMMIT
```

The auction-then-users ordering is uniform across all bid requests, so two bids on the same auction serialize at step 1 before they reach the user locks. Two bids on different auctions never contend at step 1; they arrive at step 3 with possibly-overlapping user sets, and ascending-id ordering ensures any overlap is handled in the same lock order on both sides → no deadlock.

### 5.3 Concurrent scenarios

| Scenario | Resolution |
|---|---|
| A and C both try to outbid B simultaneously | Both block at step 1 on X's lock. Whichever wins, the other re-reads on retry (now finds A or C as high) and either bids higher or fails the existing "must exceed current high" check. B's reservation released exactly once. |
| A bids on X while she's prior high on Y, and at the same instant another bidder outbids her on Y | Different auctions, different step-1 locks. Both reach step 3 and contend on A's user row. Ascending-id ordering serializes. Net: A's `reserved_lindens` reflects both, ledger has both rows. |
| A places a bid while simultaneously initiating a withdraw | Bid request locks auction first, then A's user row. Withdraw locks A directly. Both contend at A's row. Whichever commits first, the other re-reads `available`. |
| Auction-end debit fires while a bid is being placed on the same auction | Auction-end task takes the same `PESSIMISTIC_WRITE` on the auction. Bid blocks at step 1 until close commits; on retry, auction state is `ENDED`, bid is rejected. |
| Auction cancelled / fraud-frozen while reservations active | Cancellation handler walks `bid_reservations WHERE auction_id=X AND released_at IS NULL`, releases each (`AUCTION_CANCELLED` or `AUCTION_FRAUD_FREEZE`), decrements each owner's `reserved_lindens`, appends `BID_RELEASED` rows. Same lock-then-mutate discipline. |

### 5.4 Service-layer shape

```java
// com.slparcelauctions.backend.wallet.WalletService
@Transactional(propagation = MANDATORY)  // caller already in a tx + holds locks
public ReservationSwapResult swapReservation(
    Auction auction,                  // already locked by caller
    User newBidder,                   // already locked
    long newBidAmount,
    BidReservation priorReservation,  // nullable; owner already locked if non-null
    Bid newBid                        // freshly persisted; provides bid_id
);
```

Caller (`BidService.placeBid`) does the locking:

```java
@Transactional
public Bid placeBid(BidRequest req) {
    Auction auction = auctionRepo.findByIdForUpdate(req.auctionId());
    // existing validation: state, bid > high, etc.
    BidReservation prior = reservationRepo.findActiveForAuction(auction.id());
    Set<Long> idsToLock = new TreeSet<>();
    idsToLock.add(req.userId());
    if (prior != null) idsToLock.add(prior.userId());
    Map<Long, User> locked = userRepo.findByIdInForUpdateAscending(idsToLock);
    User newBidder = locked.get(req.userId());
    User priorBidder = prior == null ? null : locked.get(prior.userId());
    validatePreconditions(newBidder, req.amount());      // penalty + available
    Bid bid = bidRepo.save(buildBid(req));
    walletService.swapReservation(auction, newBidder, req.amount(), prior, bid);
    return bid;
}
```

### 5.5 Defensive branch

At auction-end auto-fund (§6.1), `bid_reservation.amount == escrow.final_bid_amount` should always hold. If it doesn't, that's a system-integrity bug — log loudly and freeze the escrow as `FROZEN` with `reason=BID_RESERVATION_AMOUNT_MISMATCH`. This is a "should be impossible" branch maintained for forensics, not for runtime recovery.

---

## §6 — Auction Lifecycle Changes

### 6.1 Auto-fund at auction close

`AuctionEndTask.closeOne` for `endOutcome=SOLD` (timer-driven close), in one transaction with the existing close logic:

1. Lock auction (existing).
2. Lock winner's user row.
3. Lookup active `bid_reservation{user=winner, auction=this, released_at=null}`. Sanity: `reservation.amount == final_bid_amount`. Mismatch → `FROZEN` defensive branch (§5.5).
4. Mark reservation `released_at=now, release_reason=ESCROW_FUNDED`. `users.reserved_lindens(winner) -= reservation.amount`.
5. `users.balance_lindens(winner) -= final_bid_amount`.
6. Append `user_ledger{type=BID_RELEASED, amount, ref_type=BID, ref_id=winner.bid_id}` (cleanup).
7. Append `user_ledger{type=ESCROW_DEBIT, amount=final_bid_amount, ref_type=ESCROW, ref_id=escrow.id}` (debit).
8. Insert escrow row directly in state `FUNDED`. Stamp `funded_at=now`. State transitions immediately to `TRANSFER_PENDING` (existing post-funded logic).
9. Append `escrow_transactions{type=AUCTION_ESCROW_PAYMENT, status=COMPLETED}`.
10. WS envelope `ESCROW_FUNDED` to seller + winner; existing 72h transfer deadline timer starts.

### 6.2 Removed: `ESCROW_PENDING` state

`Escrow.state` enum drops the `ESCROW_PENDING` value. The `EscrowTimeoutJob.findExpiredPending()` query path is removed. Escrows now begin life in `FUNDED`, never `ESCROW_PENDING`. The 48-hour payment-deadline column (`payment_deadline`) is dropped from the `escrows` table; the 72-hour `transfer_deadline` is unchanged.

The "winner stiffs seller → escrow EXPIRED → penalty" failure mode is structurally impossible. Hard reservation guaranteed the funds at bid time.

### 6.3 Buy-It-Now

`POST /api/v1/auctions/{id}/buy-now` (existing endpoint shape). Updated transaction:

1. Lock auction (existing). Validate state + that BIN is allowed for this auction.
2. Lock BIN-clicker's user row.
3. Validate `user.penalty_balance_owed == 0` → 422 `PENALTY_OUTSTANDING`.
4. Read any existing `bid_reservation` for (user, auction). Let `existing_reserved = reservation?.amount ?? 0`.
5. Validate `(balance - reserved + existing_reserved) >= bin_price` → 422 `INSUFFICIENT_AVAILABLE_BALANCE`. (Existing reservation counts toward funds available for BIN — the user already had it locked.)
6. If existing reservation: release it (`release_reason=ESCROW_FUNDED`, decrement `reserved_lindens`, append `BID_RELEASED`).
7. `balance_lindens -= bin_price`. Append `user_ledger{type=ESCROW_DEBIT, amount=bin_price}`.
8. Persist BIN bid row + auction state transition `ACTIVE → ENDED+BOUGHT_NOW`.
9. Insert escrow row in state `FUNDED`. Append `escrow_transactions{type=AUCTION_ESCROW_PAYMENT}`.
10. Release all OTHER active reservations on this auction (`release_reason=AUCTION_CANCELLED` or a new `AUCTION_BIN_ENDED` reason; choose one and document — recommend `AUCTION_BIN_ENDED` for clarity), decrement those users' `reserved_lindens`, append `BID_RELEASED` rows.
11. Commit. WS envelopes.

### 6.4 Auction cancellation / freeze

When an auction goes `CANCELLED` (admin or seller-initiated, where allowed) or `FROZEN` (fraud), the cancellation handler walks active reservations:

```sql
SELECT * FROM bid_reservations WHERE auction_id = ? AND released_at IS NULL;
```

For each: lock owner user row, mark reservation released with `release_reason=AUCTION_CANCELLED|AUCTION_FRAUD_FREEZE`, decrement `reserved_lindens`, append `BID_RELEASED` row. If the auction has a funded escrow, the escrow follows its own cancellation → refund path (§7).

### 6.5 User ban

When a user is banned (Epic 10 admin tooling fires `BAN_USER` action):

1. Walk that user's active `bid_reservations`. For each: release with `release_reason=USER_BANNED`, decrement `reserved_lindens`, append `BID_RELEASED`.
2. Walk that user's funded escrows where they're the winner. For each: cancel the escrow per existing dispute logic (transition to `DISPUTED` or `FROZEN` per ban-reason policy).
3. Wallet balance is preserved through the ban itself. Restitution policy follows §12 (bans with restitution → auto-withdraw to last-verified avatar; bans without → balance held per legal-process disposition).

---

## §7 — Refund Flows (Collapsed to Credits)

All three refund flows (escrow expiry, escrow dispute resolution, listing-fee refund) become wallet credits — no `TerminalCommand{action=REFUND}` is queued.

### 7.1 Escrow refund (expiry or dispute resolution)

When escrow goes `EXPIRED` (transfer deadline missed by seller) or dispute resolution rules a refund:

1. Lock escrow + winner user row.
2. `users.balance_lindens(winner) += escrow.final_bid_amount`. Append `user_ledger{type=ESCROW_REFUND, amount, ref_type=ESCROW, ref_id}`.
3. Append `escrow_transactions{type=AUCTION_ESCROW_REFUND, status=COMPLETED}`.
4. Stamp escrow state per existing flow (`COMPLETED` with refund disposition, or whatever the existing terminal state is for refunded escrows).
5. WS envelope `ESCROW_REFUNDED`.

### 7.2 Listing-fee refund

`ListingFeeRefundProcessor` (existing scheduler) drains `listing_fee_refunds WHERE status=PENDING`. Per row:

1. Lock seller user row.
2. `users.balance_lindens(seller) += refund_amount`. Append `user_ledger{type=LISTING_FEE_REFUND, amount, ref_type=LISTING_FEE_REFUND, ref_id}`.
3. Append `escrow_transactions{type=LISTING_FEE_REFUND, status=COMPLETED}`.
4. Mark refund row `status=COMPLETED`.
5. WS envelope `LISTING_FEE_REFUNDED`.

`listing_fee_refunds.terminal_command_id` column becomes nullable / unused. The migration drops it (no live data).

### 7.3 What stays on the terminal-command pipeline

After this work, `TerminalCommand.action` enum has only two values:

- `PAYOUT` — seller payout after transfer confirmed (real L$ leaving SLPA).
- `WITHDRAW` — user-initiated withdrawal fulfillment, plus dormancy auto-returns.

`REFUND` is removed from the enum and from the dispatcher's switch logic. Existing in-flight `REFUND` rows (none expected, but handle defensively): the migration converts them — checks each row's `escrow_id` / `listing_fee_refund_id`, applies the refund as a wallet credit, marks the command `COMPLETED`. Documented in the migration script.

---

## §8 — Penalty Payment + Listing/Bid Preconditions

### 8.1 Manual-only payment

Penalty is **not** auto-deducted. No flow auto-debits the wallet for an outstanding penalty — not auction close, not refunds, not withdrawal. The user surrenders L$ to clear penalty only by explicit `POST /me/wallet/pay-penalty` (§4.2).

### 8.2 Penalty as a precondition (not a debt collected)

| Action | Penalty blocks? |
|---|---|
| Place a new bid | Yes |
| Click Buy-It-Now | Yes |
| Publish a draft listing | Yes |
| Existing high bid → auto-fund at auction close | No (commitment predates penalty) |
| Deposit L$ | No |
| Withdraw L$ | No |
| Pay penalty | No |
| Receive escrow refund / listing-fee refund | No |
| Receive payout as seller | No |

Principle: **new commitments require zero-penalty; existing commitments and money-flow operations don't.**

### 8.3 The `available >= amount` rule for penalty payment

A user with a positive `balance` but fully-reserved L$ across active winning bids cannot pay a penalty until reservations release (or until they deposit more L$). Surrendering reserved L$ to pay a penalty would break bid commitments mid-auction; not permitted. The frontend wallet panel surfaces this with an explicit hint when `penalty_owed > 0 && available < penalty_owed`.

Resolution paths for the user:
1. Deposit more L$ — covers the gap immediately.
2. Wait for active bids to resolve. Outbids release reservations (available goes up). Wins debit balance + release reservations (available unchanged net, balance down).

This is not a deadlock — the user always has agency via deposit. It's a corner the UI educates around.

---

## §9 — LSL Terminal Redesign

### 9.1 SLPA Terminal — touch flow

**Steady-state:**
- Floating text: `SLPA Terminal\nRight-click → Pay to deposit\nTouch for menu`.
- `llSetPayPrice(PAY_DEFAULT, [100, 500, 1000, 5000])` — four sensible quick-pay buttons + custom-amount entry. Pay UI is **always live** — there is no "AWAITING_PAYMENT" state to enter.

**On `money(payerKey, amount)`:**
1. Generate fresh `slTransactionKey` via `llGenerateKey()`.
2. POST to `/sl/wallet/deposit`.
3. On `OK` → owner-say `payment ok DEPOSIT L$<amount> from <payer>`. No state transition. No further user action.
4. On `REFUND` → `llTransferLindenDollars(payer, amount)` to bounce; owner-say the reason.
5. On `ERROR` → owner-say only; do NOT refund.
6. On transient 5xx/timeout → bounded retry: 10s / 30s / 90s / 5m / 15m. After exhaustion → `CRITICAL: payment from {payer} L${amount} key {tx} not acknowledged` + stop retrying.

**No lock acquired for deposits.** Multiple users can pay simultaneously; `money()` is naturally reentrant.

**On touch:**
1. Open `llDialog` on a per-toucher channel filtered by toucher's avatar key. Buttons: `[Deposit, Withdraw]`. No slot acquired yet.
2. `Deposit` selected → `llRegionSayTo(toucher, ...)` with deposit instructions. No state change.
3. `Withdraw` selected → acquire withdraw slot (or reset existing one for same avatar; per-flow slots in §9.2).

### 9.2 Per-flow withdraw slots

Single shared listen on a script-global negative channel, opened at `state_entry()`, never closed. Speaker filter is `NULL_KEY` (wildcard); per-avatar dispatch in the handler.

```lsl
// Globals
integer withdrawChan;        // random negative, set in state_entry()
integer withdrawListenHandle = -1;
list    withdrawSessions = [];   // strided 3-wide:
                                 //   [avatarKey, amountOrMinusOne, expiresAt, ...]
integer MAX_WITHDRAW_SESSIONS = 4;
integer SESSION_TTL_SECONDS = 60;
```

**On menu `Withdraw` selection:**
- Find slot by avatar key. If exists → reset (re-prompt amount). If not exists and capacity available → allocate. If at capacity → `llRegionSayTo(toucher, "Terminal busy — try another nearby.")`.
- `llTextBox(toucher, "Enter L$ amount to withdraw:", withdrawChan)`.
- Slot state: `[avatar, -1, now + 60s]` (awaiting amount).

**On `listen(withdrawChan, _, id, msg)`:**
- Find slot by `id` via `llListFindList(withdrawSessions, [id])`. If `-1`, ignore (stranger injection silently dropped).
- Slot's amount is `-1` (awaiting amount): parse `msg` as integer. Validate `amount > 0`. Update slot to `[avatar, amount, now + 60s]`. Send `llDialog` confirm: `"Withdraw L$<amount> from your SLPA wallet?", ["Yes", "No"]`.
- Slot's amount is `>0` (awaiting confirm):
  - `msg == "Yes"` → POST `/sl/wallet/withdraw-request`. On OK → owner-say + `llRegionSayTo(toucher, "Withdrawal queued — L$ will arrive shortly.")`. On `REFUND_BLOCKED` → `llRegionSayTo` the reason. Release slot.
  - `msg == "No"` or anything else → release slot.

**Timer (every 10 seconds):**
- Sweep `withdrawSessions` for entries where `expiresAt < now`. Release silently.

**Listen-leak class of bug eliminated by construction:** there's exactly one `llListen` for the entire terminal, opened at startup, never removed. Per-flow state is in the `withdrawSessions` list, not in listen handles.

### 9.3 SLPA Terminal HTTP-in (PAYOUT / WITHDRAW)

Unchanged from current implementation. Backend POSTs `TerminalCommandBody{action, recipient, amount, idempotency_key}`. Script:
1. Validates shared secret.
2. Acks immediately (returns 200).
3. Fires `llTransferLindenDollars(recipient, amount)`.
4. On `transaction_result`: posts to `/sl/escrow/payout-result` with success/failure + `slTransactionKey`.

**Note:** the existing terminal script handles `action ∈ {PAYOUT, REFUND, WITHDRAW}`. After this work, `REFUND` is no longer dispatched (refunds are wallet credits). The script's switch-on-action can be simplified to just `PAYOUT | WITHDRAW`, or kept as a 3-way switch with `REFUND` as a "should never arrive" path for defensive logging. Recommend keeping the 3-way switch with a `CRITICAL` log on `REFUND` arrival, since dispatchers + entity migrations could in principle leave a stale row.

### 9.4 SLPA Parcel Verifier Giver — new prim

Single-purpose script + prim, header-trust only, no shared secret, no L$:

- Floating text: `SLPA Parcel Verifier — Free\nTouch to receive`.
- `state_entry()`: load notecard config (currently just has `DEBUG_MODE` and a rate-limit value), check Mainland-only grid guard, register listening for inventory changes (`CHANGED_INVENTORY → llResetScript`).
- `on touch_start(N)`:
  - Per-avatar rate-limit check: keep a strided list `givenSessions=[avatarKey, lastGivenAt, ...]` and refuse if same avatar touched within last 60 seconds (`llRegionSayTo(toucher, "Just gave you one — wait a minute.")`).
  - `llGiveInventory(toucher, "SLPA Parcel Verifier")`.
  - Owner-say `gave verifier to <name>`.
  - Update rate-limit list.
- Notecard auto-reset on `CHANGED_INVENTORY` (handles both notecard updates and verifier-inventory updates).
- Owner-say diagnostic format matches existing scripts.

**Deployment locations:** SLPA HQ, auction venues, Marketplace.

**Operational benefit:** the unified SLPA Terminal no longer carries an SLPA Parcel Verifier in its inventory. The "two-place rule" gotcha (where you have to drag-drop the new verifier into every deployed payment terminal whenever `parcel-verifier.lsl` updates) is gone — the verifier-giver instances are the only place that ships the verifier.

### 9.5 LSL deliverables

| Path | Status |
|---|---|
| `lsl-scripts/slpa-terminal/slpa-terminal.lsl` | REWRITTEN |
| `lsl-scripts/slpa-terminal/config.notecard.example` | UPDATED (URL paths) |
| `lsl-scripts/slpa-terminal/README.md` | REWRITTEN |
| `lsl-scripts/slpa-verifier-giver/slpa-verifier-giver.lsl` | NEW |
| `lsl-scripts/slpa-verifier-giver/config.notecard.example` | NEW |
| `lsl-scripts/slpa-verifier-giver/README.md` | NEW |
| `lsl-scripts/README.md` | UPDATED (index entry for new script) |

The existing `lsl-scripts/parcel-verifier/` (the SLPA Parcel Verifier itself, given out by the giver prim) is unchanged. The existing `lsl-scripts/verification-terminal/` is unchanged. The existing `lsl-scripts/sl-im-dispatcher/` is unchanged.

---

## §10 — Withdrawal Validation

### 10.1 What the withdraw endpoints validate

Both `/sl/wallet/withdraw-request` (touch-initiated) and `/me/wallet/withdraw` (site-initiated) validate only:

1. `available >= amount` — funds correctness.
2. `user.status NOT IN (BANNED, FROZEN)` — existing fraud-flag flow extends to wallet.
3. `amount > 0` — basic input validation.

That's it. No per-withdraw cap, no daily cap, no first-withdraw cool-down, no post-deposit cool-down, no email confirmation.

### 10.2 Why no fraud guards

The recipient UUID is hardcoded to `user.slAvatarUuid` (locked at verification, never client-supplied). For an attacker to extract L$ via withdrawal, they must have compromised the user's SL avatar in addition to the SLPA session — and an SL-avatar compromise is outside SLPA's control (it's a Linden Lab problem). If only the SLPA session is compromised, the L$ lands in the user's own avatar wallet and is recoverable.

The fraud-guard machinery I'd considered (caps, cool-downs, email confirm) only buys value in a narrow middle case where SLPA is compromised but the SL avatar isn't, and even then the user's recovery path is simply "wait for the L$ to arrive in your own avatar." The operational complexity (SMTP infrastructure, daily-window state, cool-down timestamps) doesn't match the value delivered. Skipping all of it.

(Implementation note: LSL's `integer` is signed 32-bit, so single-transaction withdraws above ~L$2.1B fail at the LSL boundary regardless. That's a data-type ceiling, not a policy.)

---

## §11 — Reconciliation Extension

`ReconciliationService.runDaily()` (existing scheduler at `0 0 3 * * *` UTC) gets two extensions.

### 11.1 Denorm precheck

Before running the main expected-vs-observed comparison:

```sql
SELECT
    SUM(reserved_lindens) FROM users
    AS denorm_total,
    (SELECT SUM(amount) FROM bid_reservations WHERE released_at IS NULL)
    AS source_total;
```

If `denorm_total != source_total` → record `ReconciliationRun{status=DENORM_DRIFT, errorMessage="reserved_lindens denorm mismatch", drift=denorm_total - source_total}`, alert via `NotificationPublisher`, **abort the run** (the main expected number is wrong if denorms are wrong; main check would alarm spuriously).

### 11.2 Extended expected total

The SLPA service avatar holds two disjoint pools of L$:

- **Escrows in locked states** (`FUNDED, TRANSFER_PENDING, DISPUTED, FROZEN`) — L$ that has been moved from a winner's wallet to an escrow row at auction close.
- **Wallet balances** (`SUM(users.balance_lindens)`) — L$ that the SLPA service avatar holds on behalf of users. *Reservations are a partition of this pool* (a portion is "locked for active bids"), not a separate pool — bidding doesn't move L$ out of the wallet, it just marks some as reserved.

So:

```
expected_total = sumLockedEscrows() + sumWalletBalances()
```

`wallet_reserved_total` is recorded on the run row as a sub-breakdown for forensics; it is **not** added to `expected_total` (that would double-count, since reservations are already inside `wallet_balance_total`).

### 11.3 Status enum extension

`ReconciliationStatus` adds `DENORM_DRIFT`. The `reconciliation_runs.status` check constraint is updated. `BALANCED` semantics unchanged (`observed >= expected` within tolerance — positive drift OK, negative drift alarms).

### 11.4 Run row extensions

`reconciliation_runs` adds `wallet_balance_total` (BIGINT NULL), `wallet_reserved_total` (BIGINT NULL), `escrow_locked_total` (BIGINT NULL — duplicate of existing `expected_total` pre-renaming). Old runs left with NULL for new columns.

### 11.5 Test surface

- Unit: `WalletReconciliationExtension.computeExpected()` returns escrow + balance sum.
- Integration: synthetic drift scenarios — tweak `users.balance_lindens` directly, confirm `BALANCED=false` with non-zero drift; tweak `users.reserved_lindens` directly, confirm `DENORM_DRIFT` and main-check abort.

---

## §12 — Dormancy

### 12.1 Active-signal source

Dormancy uses `refresh_tokens.created_at` as the "user is active" signal. The most recent refresh-token row for a user (created on fresh login or on every access-token-expiry rotation) is the natural signal: a user who's been gone for 30+ days has had no refresh-token rotation in that window.

```sql
SELECT u.* FROM users u
WHERE u.balance_lindens > 0
  AND u.wallet_dormancy_phase IS NULL
  AND NOT EXISTS (
      SELECT 1 FROM refresh_tokens rt
      WHERE rt.user_id = u.id
        AND rt.created_at > now() - interval '30 days'
  )
  AND NOT EXISTS (
      SELECT 1 FROM bid_reservations br
      WHERE br.user_id = u.id
        AND br.released_at IS NULL
  )
  AND NOT EXISTS (
      SELECT 1 FROM escrows e
      WHERE e.winner_user_id = u.id
        AND e.state IN ('FUNDED', 'TRANSFER_PENDING', 'DISPUTED', 'FROZEN')
  );
```

This uses no new column. The `last_login_at` / `last_active_at` discussion is sidestepped entirely.

### 12.2 `WalletDormancyJob` — weekly

```java
@Scheduled(cron = "${slpa.wallet.dormancy-job.cron:0 0 4 * * MON}", zone = "UTC")
@Transactional
public void sweep() {
    OffsetDateTime now = OffsetDateTime.now(clock);
    // Phase 1: flag newly-dormant users
    List<User> newlyDormant = userRepo.findEligibleForDormancyFlag(now);
    newlyDormant.forEach(u -> dormancyTask.flag(u, now));
    // Phases 2/3/4: escalate weekly
    List<User> awaitingNext = userRepo.findDormancyPhaseDue(now);
    awaitingNext.forEach(u -> dormancyTask.escalateOrAutoReturn(u, now));
}
```

Per-user task (separate `@Transactional` to isolate failures):

- `flag`: stamp `wallet_dormancy_started_at=now, wallet_dormancy_phase=1`. Send IM #1 via Epic 09 SL IM dispatcher.
- `escalateOrAutoReturn`:
  - Re-check liveness (the active-signal query above) — if user is no longer dormant (logged in / has reservation / has escrow), clear `wallet_dormancy_started_at`, `wallet_dormancy_phase`. Defense-in-depth even though the login handler also clears.
  - Phase 1 → 2 → 3 → 4: increment phase, send IM #N (escalating tone, balance reminder).
  - Phase 4 → COMPLETED: queue `TerminalCommand{action=WITHDRAW, recipient=user.slAvatarUuid, amount=balance, idempotency_key=dormancy-{userId}-{phase4StampedAt}}`. Append `user_ledger{type=WITHDRAW_QUEUED, ref_type=DORMANCY, description="auto-return after 30d inactivity + 4 weekly notices"}`. Decrement balance to zero. Send final "balance returned" IM. Stamp `wallet_dormancy_phase=COMPLETED` (out-of-range value handled by app code, not by DB constraint — extend the CHECK to allow it, e.g., 1..4 plus 99 for completed; or use a separate stamp).

Total time from last login to auto-return: 30 days + 4 weekly IMs (each IM ≥7 days apart) ≈ 58 days.

### 12.3 Login + refresh-token rotation handler

On any successful login (`/api/v1/auth/login`) or refresh (`/api/v1/auth/refresh`), if the user's `wallet_dormancy_phase IS NOT NULL`, clear `wallet_dormancy_started_at = NULL, wallet_dormancy_phase = NULL`.

This gives the user immediate dormancy-state reset on return, rather than waiting for next weekly sweep.

### 12.4 IM message templates

Phase 1: *"Your SLPA wallet has been inactive for 30 days with a balance of L$<X>. To prevent automatic return of these funds to your SL avatar, log in to slparcels.com within 4 weeks."*

Phase 2 (week +1): *"Reminder: your SLPA wallet has been inactive for 37 days. L$<X> will be automatically returned to your SL avatar in 3 weeks if you don't log in."*

Phase 3 (week +2): *"Final reminder coming next week: your SLPA wallet has been inactive for 44 days. L$<X> will be returned in 2 weeks."*

Phase 4 (week +3): *"This is your final notice. Your SLPA wallet has been inactive for 51 days. L$<X> will be returned to your SL avatar in 1 week unless you log in to slparcels.com."*

Completion: *"Your inactive SLPA wallet balance of L$<X> has been returned to your SL avatar."*

### 12.5 Edge cases

- **User logs in mid-cycle.** Login handler clears dormancy state immediately; weekly sweep finds them no longer eligible.
- **User deposits L$ during dormancy** (e.g., they touch a terminal but never log into the website). Deposits credit the wallet; dormancy state is unaffected (deposit doesn't generate a refresh-token rotation). They'll hit the next phase IM. Documented behavior.
- **User has active reservation when first phase-flag fires.** They're excluded from the eligibility query (the reservation NOT EXISTS clause). Won't be flagged. If reservations later release without the user logging in, the next weekly sweep flags them with a fresh phase 1.
- **Service avatar can't fulfill the auto-return** (e.g., terminal pool down, recipient avatar banned by Linden). `TerminalCommand` retry budget exhausts → existing `payout-result` failure → `WITHDRAW_REVERSED` ledger row credits balance back. User stays in `wallet_dormancy_phase=COMPLETED` (the cycle ran). Manual admin intervention needed; flagged via existing alert pipeline.

---

## §13 — Wallet Terms of Use

### 13.1 New website page

`/legal/terms` becomes a real surface (it doesn't exist today as a Phase 1 punt). Wallet section content draft (subject to legal review separately):

1. **Non-interest-bearing.** "L$ held in your SLPA wallet do not earn interest, dividends, or any return. SLPA does not offer investment products of any kind."
2. **L$ status.** "L$ are a Linden Lab limited-license token, not currency. SLPA holds L$ on your behalf as a transactional convenience. SLPA does not guarantee L$ value or redeemability — those are governed by Linden Lab's Terms of Service."
3. **No L$↔USD conversion.** "SLPA does not exchange L$ for USD or any other currency. All L$ entering and leaving your wallet do so via SL avatar-to-avatar transfer."
4. **Recoverable on shutdown.** "If SLPA ceases operations, all positive wallet balances will be returned to the Resident's verified SL avatar. Active escrows will resolve per their state at shutdown."
5. **Freezable for fraud.** "SLPA may freeze a wallet balance pending fraud investigation, with a maximum freeze period of 30 days absent legal process."
6. **Dormancy.** "Wallets inactive for 30 days will be flagged for dormancy. SLPA will attempt notification via SL IM for 4 weeks. After that, the balance will be automatically returned to the Resident's verified SL avatar."
7. **Banned-Resident handling.** "If your SL account loses good standing under Linden Lab's ToS, your SLPA wallet balance will be returned to your last-verified SL avatar at the time of SLPA account closure, subject to Linden Lab's enforcement actions."

### 13.2 Click-through gating

First-deposit detection: when the wallet page tries to render the "deposit instructions" modal, it checks `users.wallet_terms_accepted_at`. If null, modal renders the terms with an "I have read and accept" checkbox + button, posting to `/me/wallet/accept-terms`.

The deposit endpoint itself doesn't gate on `wallet_terms_accepted_at` — terms acceptance is a UX gate, not a transactional one. (A user touching the terminal directly without ever visiting the website can deposit; their wallet shows balance + a "Please review wallet terms" banner on next site visit.)

### 13.3 Terms versioning

`wallet_terms_version` (`VARCHAR(16)`) holds the version string the user accepted. Material changes to terms bump the version; the next site visit re-prompts. Versioning is opaque text (`"1.0"`, `"1.1"`, etc.); the version-comparison logic is "if `wallet_terms_version != currentTermsVersion`, re-prompt."

The `currentTermsVersion` is a configuration value (`slpa.wallet.terms-version` in `application.yml`). Bumping this forces re-acceptance.

---

## §14 — Migration Plan & Cutover

### 14.1 Sequencing

```
1. Schema migrations  (Flyway, automatic on backend boot)
2. Backend deploy     (GHA on push to main)
3. Frontend deploy    (Amplify on push to main)
4. Postman collection rebuild
5. In-world terminal swap   ← only manual step, last
```

Old terminals will return 404s against the new backend between steps 2 and 5 (old endpoints removed). With no customers, that's a fine intermediate state — the LSL swap is the team's sign-off step after smoke-testing the rest.

### 14.2 Schema migrations

Five new Flyway files in `backend/src/main/resources/db/migration/`:

| File | Purpose |
|---|---|
| `V<N>__wallet_balance_columns.sql` | Add columns to `users`: `balance_lindens`, `reserved_lindens`, `wallet_dormancy_started_at`, `wallet_dormancy_phase`, `wallet_terms_accepted_at`, `wallet_terms_version`, with CHECK constraints. |
| `V<N+1>__user_ledger.sql` | Create `user_ledger` table + indexes. |
| `V<N+2>__bid_reservations.sql` | Create `bid_reservations` table + partial unique index. |
| `V<N+3>__retire_escrow_pending_state.sql` | Drop `escrows.payment_deadline` column. Update `escrows.state` CHECK to remove `ESCROW_PENDING`. (No live data with that state expected; defensive `DELETE FROM escrows WHERE state='ESCROW_PENDING'` before constraint update with a logged count.) Drop `listing_fee_refunds.terminal_command_id` column. Convert in-flight `TerminalCommand{action=REFUND}` rows to wallet credits with explanatory `description` (defensive, expected count = 0). |
| `V<N+4>__reconciliation_extension.sql` | Add `wallet_balance_total`, `wallet_reserved_total`, `escrow_locked_total` columns to `reconciliation_runs`. Update status CHECK to include `DENORM_DRIFT`. |

The exact `V<N>` prefix is determined by the next-available number at the time of migration creation — the specifics will be set during implementation.

### 14.3 Cutover procedure

```
1. Take RDS snapshot (manual, before merge to main):
     aws rds create-db-snapshot --db-instance-identifier slpa-prod-db \
         --db-snapshot-identifier pre-wallet-<git-sha> \
         --profile slpa-prod
   Wait for snapshot status=available (~5 min).

2. Merge dev → main (via PR review). GHA deploy-backend.yml fires.
   Wait for ECS to settle (~3-5 min). Confirm /actuator/health green.

3. Frontend Amplify build fires from main; wait for it to settle (~5-7 min).

4. Smoke-test backend endpoints with curl:
   - GET /api/v1/me/wallet (with team JWT) → 200 with wallet shape.
   - POST /api/v1/sl/wallet/deposit (with bogus payerUuid) → 200 with REFUND/UNKNOWN_PAYER.
   - Confirm /sl/escrow/payment returns 404 (proves removal landed).

5. Rebuild Postman collection per §15. Run the wallet folder against prod;
   confirm variable-chaining works.

6. In-world: swap each deployed SLPA Terminal prim's script + notecard.
   Drop a copy of slpa-verifier-giver.lsl into a new prim at SLPA HQ.
   Smoke-test deposit (pay L$10) + withdraw (touch → menu → Yes).

7. Sign-off: confirm GET /api/v1/me/wallet shows the test deposit credited.
```

Total estimated time from `git push origin main` to sign-off: ~45-60 min including the in-world step.

### 14.4 Rollback procedure

Triggers: severe data corruption, double-debit bug, anything that warrants pulling the cord rather than pushing a fix forward.

```
1. Restore RDS from pre-wallet-<sha> snapshot:
   aws rds restore-db-instance-from-db-snapshot \
       --db-instance-identifier slpa-prod-db-restored \
       --db-snapshot-identifier pre-wallet-<sha> \
       --profile slpa-prod
   Update Parameter Store SPRING_DATASOURCE_URL to the restored endpoint.
   Restart ECS service. (~10-15 min total.)

2. Revert the merge commit on main:
     git revert -m 1 <merge-commit-sha>
     git push origin main
   GHA redeploys old backend image automatically. (~5 min.)

3. In-world LSL revert:
     - Drag-drop old slpa-terminal.lsl back into each prim.
     - Drag-drop old config notecard back.
     - Restore the old SLPA Parcel Verifier inventory item to the terminal prim.
     - Optionally remove the new slpa-verifier-giver prim.

4. Frontend revert: Amplify auto-redeploys from the reverted main commit.

5. Postman collection: revert via git on the collection JSON.

Total recovery time: ~45 min.
```

The only data loss is whatever dogfood activity happened between snapshot and rollback. Acceptable because there are no real users.

### 14.5 Abandon vs ship decision

After ~1 week of team dogfood, decide:

- **Ship:** wallet stays. No further migration. Post-validation cleanup PR may drop now-unused code paths (e.g., the defensive `REFUND` action in `TerminalCommand` enum if no incidents arrived), simplify retired-endpoint stubs.
- **Abandon:** execute §14.4. Append a "lessons learned" appendix to this spec doc explaining what didn't work. Audit trail for any future retry.

---

## §15 — Postman Collection Rebuild

The `SLPA` Postman collection (id `8070328-cf3e1190-cb71-4af3-8e2e-70c82dfee288` in workspace `https://scatr-devs.postman.co`) is updated:

### 15.1 New folder: `Wallet`

Variable-chaining test scripts thread the wallet state through `SLPA Dev` env vars.

| Request | Test-script effects |
|---|---|
| `GET /me/wallet` | Sets `walletBalance`, `walletReserved`, `walletAvailable`, `walletPenaltyOwed`, `walletTermsAccepted`. |
| `POST /me/wallet/accept-terms` | Sets `walletTermsAccepted = true`. |
| `POST /me/wallet/withdraw` | Sets `withdrawalQueueId`. |
| `POST /me/wallet/pay-penalty` | Refreshes `walletBalance`, `walletPenaltyOwed`. |
| `POST /sl/wallet/deposit` | (under SL-headers folder) Sets `slDepositTransactionKey`. Body uses pre-request script to fill `slTransactionKey` with `pm.variables.replaceIn("{{$guid}}")`. |
| `POST /sl/wallet/withdraw-request` | Sets `slWithdrawTransactionKey`. |

### 15.2 Removed folders/items

- `Escrow Payment` folder (contained `/sl/escrow/payment` requests).
- `Listing Fee Payment` folder.
- `Penalty Lookup`, `Penalty Payment` requests.

### 15.3 Modified items

- `Auctions/Bid` request: documentation updated to note new precondition responses (`PENALTY_OUTSTANDING`, `INSUFFICIENT_AVAILABLE_BALANCE`).
- `Auctions/Buy-It-Now`: same.
- `Auctions/Publish` (or whatever the existing draft → DRAFT_PAID transition request is): same.

### 15.4 SLPA Dev environment variables

**Added:** `walletBalance`, `walletReserved`, `walletAvailable`, `walletPenaltyOwed`, `walletTermsAccepted`, `withdrawalQueueId`, `slDepositTransactionKey`, `slWithdrawTransactionKey`.

**Removed:** `escrowPaymentTxKey`, `listingFeePaymentTxKey`, `penaltyLookupAvatarUuid`, `penaltyPaymentTxKey`, anything else that was only used by the retired endpoints.

---

## §16 — Test Surface

Per `CONVENTIONS.md` discipline: unit + slice + integration, all three required.

### 16.1 Unit tests

- `WalletService.deposit()` — balance increment, ledger entry, idempotency replay.
- `WalletService.swapReservation()` — reservation swap matrix (no prior, prior-same-user, prior-different-user, insufficient available, sanity-amount-mismatch defensive branch).
- `WalletService.withdrawSiteInitiated()` — debit + ledger + TerminalCommand insert; insufficient balance; duplicate idempotency key.
- `WalletService.payPenalty()` — full payment, partial payment, exceeds-owed rejection, insufficient-available rejection.
- `WalletService.creditEscrowRefund()`, `WalletService.creditListingFeeRefund()` — refund-credit flow.
- `BidService.placeBid()` — penalty precondition rejection, available-balance rejection, successful reservation swap (existing tests extended).
- `AuctionEndTask.closeOne()` — auto-fund happy path; reservation-amount-mismatch defensive freeze.
- `BinHandler` — BIN with no prior reservation; BIN with prior reservation at lower amount; BIN with insufficient available even counting prior reservation.
- `WalletDormancyJob.sweep()` — flag matrix, escalation matrix, auto-return flow.
- `ReconciliationService.runDaily()` — extended expected calculation; denorm-drift precheck.
- `LoginHandler` / `RefreshTokenService.rotate()` — clears dormancy state on success.

### 16.2 Slice tests (`@WebMvcTest`, `@DataJpaTest`)

- `/sl/wallet/deposit` controller slice — auth gates, validation responses, idempotency.
- `/sl/wallet/withdraw-request` controller slice — same matrix + REFUND_BLOCKED variants.
- `/me/wallet/*` controller slices — JWT + auth gates + 422 responses.
- `user_ledger` repository slice — UNIQUE constraint enforcement on `sl_transaction_id`, `idempotency_key`.
- `bid_reservations` repository slice — partial unique index enforces "at most one active per (user, auction)."

### 16.3 Integration tests

- End-to-end auction flow: deposit → bid (reserves) → outbid (releases prior) → close (auto-funds) → ownership confirm → payout queued → payout-result → ledger sums match denorms throughout.
- End-to-end BIN flow: deposit → BIN-click → escrow funded immediately → ledger consistent.
- End-to-end listing flow: deposit → publish (debits) → cancel → refund credits balance.
- End-to-end withdrawal flow (site): deposit → withdraw → TerminalCommand fires → payout-result → WITHDRAW_COMPLETED row.
- End-to-end withdrawal flow (touch): deposit → terminal touch + confirm → /sl/wallet/withdraw-request → TerminalCommand → fulfillment.
- Cancellation flow: bid → bid (outbid first) → cancel auction → both reservations released → both users' available restored.
- User ban flow: bid → ban user → reservation released, escrow disposition.
- Concurrent bid simulation (two threads racing the same auction; two threads racing different auctions sharing a user): no deadlock, no negative balance, ledger sums match denorms.
- Reconciliation: synthetic drift on `users.balance_lindens` triggers `BALANCED=false` with non-zero drift; synthetic drift on `users.reserved_lindens` triggers `DENORM_DRIFT` and aborts main check.
- Dormancy: fast-forward 30d → flagged + IM #1; +28d → 4 IMs sent; +7d → auto-withdraw queued + balance zeroed; subsequent login during cycle → state cleared.
- User with active bid reservation excluded from dormancy flag.

---

## §17 — Out of Scope / Deferred

Items intentionally not built in this work:

- **Admin manual ledger adjustments UI.** Schema supports it (`entry_type=ADJUSTMENT, created_by_admin_id`), no admin UI surfaces it. Future Epic 10 extension.
- **Wallet history export (CSV/PDF for tax purposes).** User-facing ledger view is in-app only.
- **Multi-recipient withdrawals or sub-wallet partitioning.** Single recipient = `user.slAvatarUuid`, single wallet per user.
- **Top-up auto-funding (e.g., "deposit X automatically before each bid").** Out of scope. Manual deposit only.
- **Wallet currency other than L$.** Single-currency.
- **Per-region terminal routing optimization for withdrawals.** Reuses existing dispatcher's any-active-terminal selection.
- **Frontend wallet activity exports / detailed receipts.** Listed-only ledger view.

These get added to `docs/implementation/DEFERRED_WORK.md` with the standard "originating epic / how to revisit" annotation.

---

## §18 — Files Touched (estimate)

For implementation-plan reference. Numbers approximate.

**Backend (Java):**
- 5 new Flyway migrations in `backend/src/main/resources/db/migration/`.
- New package `com.slparcelauctions.backend.wallet` — entities, repositories, service, controllers, DTOs, exceptions: ~15 files.
- `EscrowService`, `EscrowRepository`, `EscrowState` enum modifications: ~3 files.
- `BidService`, `BidController` modifications: ~2 files.
- `AuctionEndTask` modification: 1 file.
- BIN handler modification: 1 file.
- Listing-publish controller modification: 1 file.
- `ReconciliationService` extension: 1 file.
- New `WalletDormancyJob` + per-user task: 2 files.
- Login + refresh handlers (clear dormancy on success): 2 files.
- `TerminalCommand.action` enum trim + dispatcher switch: 2 files.
- Removed: 4 SL-headers controllers + their request/response DTOs: ~12 deletions.
- Tests (unit + slice + integration): ~25-30 new files.

**Frontend (TypeScript / React):**
- New wallet panel component + dialog components: ~6 files.
- Bid form, BIN form, Publish form modifications: ~4 files.
- React Query hooks for `/me/wallet/*`: ~3 files.
- New `/legal/terms` page: 1 file.
- Type definitions for new endpoints: ~2 files.
- Tests: ~8-10 files.

**LSL:**
- Rewrite `lsl-scripts/slpa-terminal/slpa-terminal.lsl`: 1 file (~700 lines, simpler than current).
- Rewrite `lsl-scripts/slpa-terminal/README.md`: 1 file.
- New `lsl-scripts/slpa-verifier-giver/`: 3 files.
- Update `lsl-scripts/README.md` index: 1 file.

**Documentation:**
- This spec doc: written.
- Implementation plan: separate doc at `docs/superpowers/plans/2026-04-30-wallet-model.md`.
- `docs/implementation/CONVENTIONS.md`: append wallet conventions.
- `docs/implementation/FOOTGUNS.md`: append surfaced gotchas.
- `docs/implementation/DEFERRED_WORK.md`: append items from §17.
- Root `README.md`: sweep for staleness per the user's "update README each task" rule.

**Postman:** rebuilt collection JSON file (committed under repo if so versioned).

---

## §19 — Open Questions

These are the things I'd rather decide during implementation if they come up — listed here so they're not lost:

1. **Terms-of-use page authoring.** This spec drafts the substance (§13.1) but the actual page copy + presentation needs to be written/styled. Treating as a Phase 1 task to land along with this work; no blocker.
2. **`TerminalCommand{action=REFUND}` enum trim.** Whether to remove `REFUND` from the enum entirely or keep it as a defensive value with a `CRITICAL` log on dispatch. Recommendation: keep it for one release cycle (defensive), drop in a follow-up cleanup PR.
3. **`wallet_dormancy_phase=COMPLETED` representation.** Either extend the CHECK to allow `99`, or use a separate `wallet_dormancy_completed_at` timestamp. Implementation decides; both are fine.
4. **Auto-fund retry behavior on transient DB failure mid-close.** If `AuctionEndTask.closeOne` fails partway (e.g., wallet debit succeeds but escrow insert fails), the transaction rolls back atomically — `@Transactional` ensures it. Just confirming this branch is exercised in integration tests.

---

## §20 — Review Notes

This design was developed in a brainstorming session covering 6 sections of detailed back-and-forth. Key decision points:

- **Holding L$ for users is permitted by SL ToS** (research in §1 of brainstorm) — non-interest-bearing custodial wallets fall outside the 2008 banking-policy ban; §3.2 LindeX-exclusivity restricts purchase/sale, not holding.
- **Path A: iterate on prod, snapshot-based rollback** — chosen because no customers exist yet, making destructive migrations safe and feature flags unnecessary overhead.
- **Hard reservation on bids** — chosen over soft / no-reservation models because it structurally eliminates the "winner stiffs seller" failure mode that today drives penalties and disputes.
- **Per-flow withdraw slots, not terminal-wide lock** — chosen because a terminal-wide lock creates a griefing surface (one bored user clicking each prim DoSes every terminal); per-flow slots with per-avatar dedup eliminates single-griefer DoS entirely.
- **`refresh_tokens.created_at` as dormancy active-signal** — chosen over a new `last_active_at` column to avoid write-amplification on every authenticated request.
- **Penalty as listing/bid precondition, not auto-deduction** — chosen because the wallet is the user's money; collecting via garnishment would be overreach.
