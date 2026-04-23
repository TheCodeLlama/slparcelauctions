# Epic 05 sub-spec 1 — Escrow Engine Backend

**Date:** 2026-04-21
**Branch target:** `task/05-sub-1-escrow-backend` off `dev`
**Scope:** Backend orchestration for post-auction escrow — state machine, payment callbacks, ownership monitoring, outbound payout/refund commands, terminal registry, retry. Plus two deferred-work pull-ins: listing-fee refund processor and real listing-fee terminal payment callback.

Frontend surfaces (escrow status page, dashboard integration, `AuctionEndedPanel` CTAs, `FeePaymentInstructions` updates) ship in Epic 05 sub-spec 2.

---

## §1 — Scope

**In scope:**

- `Escrow` entity (one row per auction) holding lifecycle state + deadlines + commission + amounts.
- `EscrowTransaction` entity (append-only ledger of L$ movements).
- `Terminal` entity (one row per registered in-world terminal, pooled).
- `TerminalCommand` entity (outbound queue for payouts + refunds with retry metadata).
- State machine: `ESCROW_PENDING → FUNDED → TRANSFER_PENDING → COMPLETED` + `DISPUTED` / `FROZEN` / `EXPIRED` (terminal states).
- Synchronous escrow row creation inside `AuctionEndTask.closeOne` when `endOutcome ∈ {SOLD, BOUGHT_NOW}`.
- REST endpoints: `GET /api/v1/auctions/{id}/escrow`, `POST /api/v1/auctions/{id}/escrow/dispute`.
- SL-headers-gated endpoints: `POST /api/v1/sl/terminal/register`, `POST /api/v1/sl/escrow/payment`, `POST /api/v1/sl/listing-fee/payment`, `POST /api/v1/sl/escrow/payout-result`.
- Schedulers: `EscrowTimeoutJob` (5min), `EscrowOwnershipMonitorJob` (5min), `TerminalCommandDispatcherJob` (30s) with integrated IN_FLIGHT staleness sweep.
- Listing-fee refund processor (drains `ListingFeeRefund WHERE status=PENDING` from Epic 03 via the same command-dispatch pipeline).
- Real production listing-fee terminal payment callback (replaces `POST /api/v1/dev/auctions/{id}/pay` in production traffic; dev endpoint kept for test fixtures).
- Shared secret auth between backend and terminals (static, rotated via config).
- WS envelopes on `/topic/auction/{id}` for escrow state changes.
- Full test coverage per CONVENTIONS.md (unit + slice + integration, all three required).

**Out of scope (explicitly deferred):**

- Admin tooling for DISPUTED/FROZEN resolution (Epic 10).
- Admin tooling for secret rotation (Epic 10).
- Notifications (email / SL IM) on dispute/freeze/expire/funded/completed — logged only; Epic 09 fan-out.
- Daily balance reconciliation (sum of PENDING escrow amounts vs SLPA account balance) — Epic 10 admin tooling.
- HMAC-SHA256 terminal auth — kept as a Phase 2 hardening item, noted in `DEFERRED_WORK.md`.
- Smart regional routing when terminals span >5 regions — noted in `DEFERRED_WORK.md`.
- Per-user STOMP queues (`/user/{id}/queue/escrow`) — Epic 09.
- Re-list-after-expired flow when the winner abandons payment — auction stays `ENDED+SOLD` with `escrow.state=EXPIRED`; no automatic re-list.
- LSL terminal scripts themselves (Phase 11) — this sub-spec defines the HTTP contract the scripts will target.
- Frontend (escrow status page, dashboard integration, AuctionEndedPanel CTAs) — Epic 05 sub-spec 2.

---

## §2 — Sub-spec split rationale

Epic 05 (tasks 01-05 from `docs/implementation/epic-05/`) contains:
1. Escrow state machine (01)
2. Payment receiving endpoint (02)
3. Land transfer ownership monitoring (03)
4. Outbound payout/refund + terminal registration + retry (04)
5. Escrow status UI (05)

Plus three deferred-work pull-ins:
- Listing-fee refund processor (from Epic 03 sub-spec 1 DEFERRED_WORK)
- Real listing-fee terminal callback (from Epic 03 sub-spec 2 DEFERRED_WORK)
- `AuctionEndedPanel` escrow CTAs (from Epic 04 sub-spec 2 DEFERRED_WORK)

Split mirrors Epic 02/03/04 shape:
- **Sub-spec 1 (this doc):** tasks 01-04 + backend listing-fee pull-ins. All backend orchestration, state machine, schedulers, terminal infrastructure.
- **Sub-spec 2:** task 05 + `AuctionEndedPanel` escrow CTAs + `FeePaymentInstructions` updates if the listing-fee terminal address becomes real. All Next.js / React Query work.

Rationale: the backend is a single coherent orchestration layer (one package, shared infrastructure, shared state machine). The frontend is a separate layer with its own review cycle. Splitting keeps each PR reviewable in one sitting and preserves the pattern the team has been operating against for four straight epics.

---

## §3 — Architecture & Data Model

### 3.1 Flow overview

```
Auction ENDED + endOutcome={SOLD|BOUGHT_NOW} + winnerUserId + finalBidAmount
        │ (synchronous, same commit as AuctionEndTask.closeOne)
        ▼
┌──────────────── Escrow (one row per auction) ────────────────┐
│  state: ESCROW_PENDING → FUNDED → TRANSFER_PENDING            │
│         → COMPLETED                                           │
│         → DISPUTED (user-raised, terminal)                    │
│         → FROZEN   (system-detected fraud, terminal)          │
│         → EXPIRED  (timeout, terminal)                        │
│  deadlines: payment_deadline (+48h), transfer_deadline (+72h) │
│  amounts: final_bid, commission_amt, payout_amt               │
│  timestamps: created_at, funded_at, transfer_confirmed_at,    │
│              completed_at, expired_at, disputed_at, frozen_at │
└───────────────────────────────────────────────────────────────┘
        │
        │ (append-only, one row per L$ movement)
        ▼
┌──────────── EscrowTransaction (ledger) ───────────────────────┐
│  type: AUCTION_ESCROW_PAYMENT | AUCTION_ESCROW_PAYOUT |       │
│        AUCTION_ESCROW_REFUND | AUCTION_ESCROW_COMMISSION |    │
│        LISTING_FEE_PAYMENT | LISTING_FEE_REFUND               │
│  amount, payer_id, payee_id, sl_transaction_id, terminal_id   │
│  status: PENDING | COMPLETED | FAILED                         │
│  created_at, completed_at                                     │
└───────────────────────────────────────────────────────────────┘

Terminal
  id, http_in_url, registered_at, last_seen_at, active (boolean)

TerminalCommand (outbound queue)
  id, escrow_id (nullable — exactly one of escrow_id / listing_fee_refund_id),
  listing_fee_refund_id (nullable),
  action (PAYOUT | REFUND),
  purpose (AUCTION_ESCROW | LISTING_FEE_REFUND),
  recipient_uuid, amount,
  status (QUEUED | IN_FLIGHT | COMPLETED | FAILED),
  terminal_id (nullable, stamped on QUEUED → IN_FLIGHT),
  attempt_count, next_attempt_at, dispatched_at, last_error,
  shared_secret_version,
  idempotency_key (unique),
  created_at, completed_at
```

### 3.2 Package boundaries

New package: `com.slparcelauctions.backend.escrow`. Vertical slice per CONVENTIONS.md: entities + repositories + services + controllers + DTOs + exceptions + tests.

Cross-package touchpoints (minimal):

- `AuctionEndTask.closeOne` calls `EscrowService.createForEndedAuction(auction)` at the tail of its transaction when `endOutcome ∈ {SOLD, BOUGHT_NOW}`.
- `CancellationService` (from Epic 03) already writes `ListingFeeRefund` rows; no change to that service. The new `ListingFeeRefundProcessorJob` is the consumer.
- `StompAuctionBroadcastPublisher` (from Epic 04) gains new envelope methods but keeps its existing publishing contract.
- `FraudFlag` entity (from Epic 03) is reused for system-detected fraud freezes.

### 3.3 Schema note

No Flyway migrations per CONVENTIONS.md — Hibernate `ddl-auto: update` creates the new tables. `EscrowTransaction.Type` enum ships with the fully-qualified names (`AUCTION_ESCROW_*`, `LISTING_FEE_*`) from the very first task. No rename or data migration is ever needed. Earlier sections of this spec used abbreviated names purely for narrative brevity; the code uses the expanded form throughout.

### 3.4 Latency expectations

The user experience is real-time where it matters; scheduled jobs handle only the inherently async pieces:

| Event | Path | Latency |
|-------|------|---------|
| Auction ends SOLD/BOUGHT_NOW | Inline in `AuctionEndTask` → Escrow row created, WS envelope fires | Instant |
| Winner pays at terminal | Terminal POSTs `/api/v1/sl/escrow/payment` → state=FUNDED → WS | Instant |
| Seller transfers land | `OwnershipMonitorJob` every 5min detects + triggers payout | ≤5 min |
| Payout dispatch | `TerminalCommandDispatcherJob` every 30s drains queue | ≤30 sec |
| Payout callback | Terminal POSTs `/api/v1/sl/escrow/payout-result` → state=COMPLETED → WS | Instant |
| Timeout sweep | `TimeoutJob` every 5min | ≤5 min |

The scheduled jobs are background plumbing between real-time touchpoints. Ownership polling is the only inherently non-instant path — it detects a manual in-world action (seller opens viewer, clicks "Sell Land") that itself takes minutes to hours to perform. 5-minute detection on a 72-hour window is acceptable because nobody is sitting at the screen waiting.

---

## §4 — State Machine & Transitions

### 4.1 Transition table

Canonical source: `EscrowState.java` + `EscrowService.ALLOWED_TRANSITIONS`.

```
ESCROW_PENDING ──────► FUNDED            (payment received, validated)
ESCROW_PENDING ──────► EXPIRED           (48h timeout, no L$ to refund)
ESCROW_PENDING ──────► DISPUTED          (either party raises dispute)

FUNDED ──────────────► TRANSFER_PENDING  (auto, immediate — same transaction as ESCROW_PENDING → FUNDED)
FUNDED ──────────────► DISPUTED          (defensive — see note below)

TRANSFER_PENDING ────► COMPLETED         (payout callback success, after ownership confirmed + payout dispatched)
TRANSFER_PENDING ────► EXPIRED           (72h timeout, queues L$ refund to winner)
TRANSFER_PENDING ────► FROZEN            (ownership changed to unknown party, queues L$ refund to winner)
TRANSFER_PENDING ────► DISPUTED

Terminal states: COMPLETED, EXPIRED, DISPUTED, FROZEN
```

**Defensive transition note (`FUNDED → DISPUTED`):** `FUNDED` is a transient internal state; in current code it always atomically advances to `TRANSFER_PENDING` within the same transaction, so this predecessor is never observable externally. Kept so the transition table remains complete if the two transitions are ever decoupled.

**Externally-observable `ESCROW_PENDING → TRANSFER_PENDING`:** because `FUNDED → TRANSFER_PENDING` is atomic with `ESCROW_PENDING → FUNDED`, external observers (WS subscribers, REST clients) see the state flip directly from `ESCROW_PENDING` to `TRANSFER_PENDING`. A single WS envelope `ESCROW_FUNDED` is published carrying the new `transfer_deadline`.

**No resume paths** from `DISPUTED` or `FROZEN` in this sub-spec. Admin tooling (Epic 10) will add manual resolve/un-suspend flows.

### 4.2 Invariants enforced by `EscrowService.transition(escrow, target)`

- Current state must appear in the allowed predecessors for `target`, or throw `IllegalEscrowTransitionException` (maps to HTTP 409).
- Each transition stamps exactly one timestamp column (`funded_at`, `transfer_confirmed_at`, `completed_at`, `expired_at`, `disputed_at`, `frozen_at`).
- Transitions that leave the happy path (`DISPUTED`, `FROZEN`, `EXPIRED` from `TRANSFER_PENDING`) queue a refund `TerminalCommand` *only if* `funded_at IS NOT NULL`. `ESCROW_PENDING → EXPIRED` queues no command.
- The `TRANSFER_PENDING → COMPLETED` transition is triggered only by the payout callback handler (`POST /api/v1/sl/escrow/payout-result` with `success=true` on a `PAYOUT` command with `purpose=AUCTION_ESCROW`). Ownership confirmation alone stamps `transfer_confirmed_at` and queues the payout — it does not transition state.
- All transitions are `@Transactional`; WS broadcast is published via `TransactionSynchronization.afterCommit`. This matches the Epic 04 bid-placement pattern and ensures listeners see only committed state.
- Every state-mutating operation acquires `PESSIMISTIC_WRITE` on the Escrow row (`findByIdForUpdate`), matching Epic 04.

### 4.3 Commission calculation

```java
long commissionAmt = Math.min(
    Math.max(Math.floorDiv(finalBidAmount * 5L, 100L), 50L),
    finalBidAmount
);
long payoutAmt = finalBidAmount - commissionAmt;
```

Computed once at Escrow row creation (not at FUNDED transition) because `finalBidAmount` is immutable from auction-end forward. Clamping `commissionAmt` to `finalBidAmount` guarantees `payoutAmt >= 0` for the pathological low-bid corner (e.g., L$1 win → commission=1, payout=0).

**Commission floor note:** The L$50 floor means auctions closing under L$1,000 pay a disproportionate commission (full 100% at L$1, 50% at L$100, 5% at L$1,000+). This is intentional per DESIGN.md — micro-auctions should be uneconomical to discourage spam listings.

### 4.4 Dispute endpoint semantics

- `POST /api/v1/auctions/{id}/escrow/dispute` callable only from source states `ESCROW_PENDING`, `FUNDED`, `TRANSFER_PENDING`. 409 from terminal states.
- Body: `{ reasonCategory: enum, description: string }`.
- Caller must be seller or winner (403 otherwise).
- Persists `dispute_reason_category` + `dispute_description` to `Escrow`; state → `DISPUTED`; a refund `TerminalCommand` queues only if `funded_at IS NOT NULL`.

### 4.5 Fraud-freeze semantics

System-triggered, not user-callable:

- `EscrowOwnershipCheckTask` detects `ownerId != winnerSlUuid && ownerId != sellerSlUuid` → `freezeForFraud(escrow, reason, evidenceJson)`.
- State → `FROZEN`; creates a `FraudFlag` row (existing Epic 03 entity) tying parcel + escrow + evidence JSON; queues refund `TerminalCommand`.
- World API `404` (parcel deleted during escrow) treated as fraud with reason `PARCEL_DELETED`.
- Persistent World API failures past the consecutive-failure threshold (default 5) treated as fraud with reason `WORLD_API_PERSISTENT_FAILURE` (conservative — a seller covering transfer fraud could also be hosing the World API lookup).

---

## §5 — REST + SL Callback Endpoints

### 5.1 Authenticated (JWT, existing guard)

| Method | Path | Caller | Purpose |
|--------|------|--------|---------|
| `GET` | `/api/v1/auctions/{id}/escrow` | seller or winner | Returns `{ state, finalBidAmount, commissionAmt, payoutAmt, paymentDeadline, transferDeadline, timeline: [] }`. 403 for any other user. `timeline` is the Escrow's state-transition stamps + `EscrowTransaction` ledger rows, sorted by time. |
| `POST` | `/api/v1/auctions/{id}/escrow/dispute` | seller or winner | Body `{ reasonCategory, description }`. Transitions to `DISPUTED`; queues refund if funded. |

### 5.2 SL-headers-gated public endpoints

All must pass `SlHeaderValidator` (`X-SecondLife-Shard=Production`, `X-SecondLife-Owner-Key ∈ slpa.sl.trusted-owner-keys`) AND `sharedSecret` body-field match.

| Method | Path | Purpose |
|--------|------|---------|
| `POST` | `/api/v1/sl/terminal/register` | Body `{ terminalId, httpInUrl, sharedSecret }`. Upserts the `Terminal` row. First call creates; subsequent calls update `httpInUrl` + `last_seen_at`. Idempotent. |
| `POST` | `/api/v1/sl/escrow/payment` | Body `{ auctionId, payerUuid, amount, slTransactionKey, terminalId, sharedSecret }`. Validates against Escrow state + winner + amount. On match: `ESCROW_PENDING → FUNDED → TRANSFER_PENDING`. |
| `POST` | `/api/v1/sl/listing-fee/payment` | Body `{ auctionId, payerUuid, amount, slTransactionKey, terminalId, sharedSecret }`. Validates: auction is `DRAFT` AND `seller.slAvatarUuid == payerUuid` AND `amount == listing_fee_amount`. On match: `DRAFT → DRAFT_PAID`, stamps real `txnRef=slTransactionKey`, writes `LISTING_FEE_PAYMENT` ledger row. If the auction is already `DRAFT_PAID` and `slTransactionKey` is new (different LSL transaction), return `{ status: ERROR, reason: ALREADY_PAID }`. |
| `POST` | `/api/v1/sl/escrow/payout-result` | Body `{ idempotencyKey, success, slTransactionKey, errorMessage, terminalId, sharedSecret }`. Correlates via `idempotencyKey` to a `TerminalCommand`. On `success=true`: stamps the command `COMPLETED`, stamps the Escrow `completed_at` (if action=PAYOUT on AUCTION_ESCROW purpose), writes ledger rows (PAYOUT + COMMISSION for an auction escrow payout). On `success=false`: marks command `FAILED`, scheduler handles retry. |

### 5.3 Dev-profile helpers (`@Profile("dev")`)

- `POST /api/v1/dev/escrow/{id}/simulate-payment` — stamps FUNDED without going through SL headers (test harness).
- `POST /api/v1/dev/escrow/{id}/simulate-transfer` — advances ownership-confirmed + queues payout.
- `POST /api/v1/dev/terminal/register-mock` — registers a loopback URL that responds with a scripted success/failure.
- Existing `POST /api/v1/dev/auctions/{id}/pay` stays unchanged; its Javadoc updates to point at `/api/v1/sl/listing-fee/payment` as the production path.

### 5.4 LSL-parsable response shape

```json
{ "status": "OK" }
{ "status": "REFUND", "reason": "...", "message": "..." }
{ "status": "ERROR", "reason": "...", "message": "..." }
```

`REFUND` reasons: `WRONG_PAYER`, `WRONG_AMOUNT`, `ESCROW_EXPIRED`, `ALREADY_FUNDED`.
`ERROR` reasons: `UNKNOWN_AUCTION`, `UNKNOWN_TERMINAL`, `SECRET_MISMATCH`, `BAD_HEADERS`, `ALREADY_PAID`.

**REFUND vs ERROR discrimination rule:** `REFUND` means the terminal received valid L$ but the backend can't honor the payment (wrong payer/amount/state) — the LSL script MUST call `llTransferLindenDollars()` to return the L$ to the payer using the key from the original `money()` event. `ERROR` means the request itself is invalid (bad auth, unknown entities) — refunding blind on ERROR is unsafe because the request might be an attacker probing the endpoint with no real L$ attached.

### 5.5 Validation pipeline (shared by SL callbacks)

1. `SlHeaderValidator` checks headers → 403 `BAD_HEADERS` on failure.
2. `sharedSecret` body field matches `slpa.escrow.terminal-shared-secret` → 403 `SECRET_MISMATCH`.
3. `terminalId` resolves to a registered `Terminal` row → 404 `UNKNOWN_TERMINAL`.
4. Idempotency check:
   - Payment endpoints: `slTransactionKey` uniqueness against `EscrowTransaction.slTransactionId` — duplicate returns the original response idempotently.
   - Payout-result endpoint: `idempotencyKey` resolves to a known `TerminalCommand` — duplicate returns OK idempotently.
5. Domain validation (auction state, payer identity, amount).

### 5.6 Fraud flag creation rule

Payer UUID mismatch on `/sl/escrow/payment` creates a `FraudFlag` row (existing Epic 03 entity) in addition to returning `REFUND`. Other refund reasons (wrong amount, expired escrow) just return REFUND without a flag — those are legitimate user mistakes.

---

## §6 — Schedulers

Three `@Scheduled` jobs in `com.slparcelauctions.backend.escrow.scheduler`, following the Epic 04 `AuctionEndScheduler` pattern (one trigger method queries a small work list, delegates per-entity work to a separate `@Transactional` task class, swallows exceptions to isolate bad rows).

### 6.1 `EscrowTimeoutJob` — every 5 min

```java
@Scheduled(fixedDelayString = "${slpa.escrow.timeout-job.fixed-delay-ms:300000}")
public void sweep() {
    OffsetDateTime now = OffsetDateTime.now(clock);
    escrowRepo.findExpiredPending(now).forEach(timeoutTask::expirePayment);
    escrowRepo.findExpiredTransferPending(now).forEach(timeoutTask::expireTransfer);
}
```

- `findExpiredPending`: `state=ESCROW_PENDING AND payment_deadline < now`. Transition → `EXPIRED`; no refund command (no L$ to refund); WS envelope `ESCROW_EXPIRED`.
- `findExpiredTransferPending`: `state=TRANSFER_PENDING AND transfer_deadline < now AND (transfer_confirmed_at IS NULL OR NOT EXISTS (SELECT 1 FROM terminal_commands tc WHERE tc.escrow_id = e.id AND tc.action='PAYOUT' AND tc.status IN ('QUEUED','IN_FLIGHT','FAILED')))`. Transition → `EXPIRED`; queue `REFUND` `TerminalCommand` to winner; WS envelope `ESCROW_EXPIRED`.
- The 72h deadline runs from `funded_at` (measures seller-transfer urgency). If the seller transferred but payout is stuck in retry, the payout-in-flight guard prevents false expiry — payout pipeline health is the backend's responsibility, not the seller's deadline.

### 6.2 `EscrowOwnershipMonitorJob` — every 5 min

```java
@Scheduled(fixedDelayString = "${slpa.escrow.ownership-monitor-job.fixed-delay-ms:300000}")
public void sweep() {
    List<Long> escrowIds = escrowRepo.findTransferPendingIds();
    escrowIds.forEach(ownershipCheckTask::checkOne);
}
```

Per-escrow `@Transactional` `EscrowOwnershipCheckTask.checkOne(escrowId)`:

1. Re-lock Escrow row (`PESSIMISTIC_WRITE`); if state ≠ `TRANSFER_PENDING`, skip (drift between query and lock).
2. Call `WorldApiClient.fetchParcel(parcelUuid)` (shared client with Epic 03 ownership monitor; separate cadence).
3. Three ownership outcomes:
   - `ownerId == winnerSlUuid` → `confirmTransfer()`: stamp `transfer_confirmed_at`, queue PAYOUT `TerminalCommand`, WS envelope `ESCROW_TRANSFER_CONFIRMED`. State stays `TRANSFER_PENDING`.
   - `ownerId == sellerSlUuid` → update `last_checked_at`; if `now - funded_at >= 24h`, log `seller_transfer_reminder_due=true` (notification fan-out deferred to Epic 09).
   - Anything else (including World API 404) → `freezeForFraud(escrow, reason, evidence)`.
4. Transient World API failure (5xx, timeout) → increment `consecutive_world_api_failures`; do NOT freeze until configurable threshold (default 5 ≈ 25 min of consecutive outages); threshold exceeded → freeze with reason `WORLD_API_PERSISTENT_FAILURE`.

### 6.3 `TerminalCommandDispatcherJob` — every 30 sec (with integrated IN_FLIGHT staleness sweep)

```java
@Scheduled(fixedDelayString = "${slpa.escrow.command-dispatcher-job.fixed-delay-ms:30000}")
public void dispatch() {
    OffsetDateTime now = OffsetDateTime.now(clock);
    Duration staleCutoff = Duration.ofMillis(inFlightTimeoutMs);
    // Prelude: requeue any IN_FLIGHT commands whose callback never arrived
    commandRepo.findStaleInFlight(now.minus(staleCutoff))
               .forEach(dispatcherTask::markStaleAndRequeue);
    // Main: dispatch QUEUED and retry-ready FAILED commands
    commandRepo.findDispatchable(now).forEach(dispatcherTask::dispatchOne);
}
```

`markStaleAndRequeue(commandId)` — re-lock command, recheck `IN_FLIGHT AND dispatched_at < cutoff`, transition to `FAILED` with `errorMessage="IN_FLIGHT timeout without callback"`, set `next_attempt_at=now` (immediate retry). Idempotency key ensures safe re-dispatch if the terminal actually did execute the transfer.

`dispatchOne(commandId)`:

1. Re-lock command; skip if state isn't `QUEUED` / retry-ready `FAILED`.
2. Pick a terminal: any `Terminal` with `active=true` AND `last_seen_at > now - 15min`. If none → leave `QUEUED`, bump `next_attempt_at` by 1 min.
3. Stamp `terminal_id` + state `IN_FLIGHT` + `attempt_count++` + `dispatched_at=now`.
4. POST command body to `terminal.http_in_url`.
5. HTTP 2xx → stays `IN_FLIGHT` awaiting async callback. HTTP 4xx/5xx/timeout → `FAILED`; schedule retry per backoff table.
6. Starvation bound: oldest-queued-first; per-cycle cap implicit in the 30s cadence + per-terminal one-POST-per-cycle (≤30 dispatches/cycle/pool, below the SL 30/30s per-region cap).

### 6.4 Clock injection

All new services + schedulers inject a `Clock` bean and call `OffsetDateTime.now(clock)` / `Instant.now(clock)`. Integration tests can then override `Clock` via `@TestConfiguration` for deadline-based scenarios. Existing Epic 03/04 code that uses raw `OffsetDateTime.now()` is out of scope for this sub-spec; call sites should be retrofitted when touched. (Without this discipline, deadline tests would have to manipulate DB timestamps directly, which defeats the purpose of exercising the clock path.)

### 6.5 Startup config validator

`EscrowConfigValidator` fails fast on boot in non-dev profiles if `slpa.escrow.terminal-shared-secret` is unset. Default dev value: `dev-escrow-secret-do-not-use-in-prod`. Matches the `SlStartupValidator` pattern from Epic 03.

Per-environment toggles on `application.yml`:
- `slpa.escrow.enabled: true/false`
- `slpa.escrow.terminal-shared-secret`
- `slpa.escrow.timeout-job.fixed-delay-ms: 300000`
- `slpa.escrow.ownership-monitor-job.fixed-delay-ms: 300000`
- `slpa.escrow.command-dispatcher-job.fixed-delay-ms: 30000`
- `slpa.escrow.command-in-flight-timeout-ms: 300000`
- `slpa.escrow.ownership-api-failure-threshold: 5`

---

## §7 — Payout/Refund Command Contract

### 7.1 Outbound request — backend → terminal

```json
POST {terminal.httpInUrl}
Content-Type: application/json

{
  "action": "PAYOUT" | "REFUND",
  "purpose": "AUCTION_ESCROW" | "LISTING_FEE_REFUND",
  "recipientUuid": "a0b1c2d3-...",
  "amount": 5000,
  "escrowId": 42,
  "listingFeeRefundId": null,
  "idempotencyKey": "ESC-42-PAYOUT-1",
  "sharedSecret": "..."
}
```

- Exactly one of `escrowId` / `listingFeeRefundId` is non-null.
- `idempotencyKey` format: `ESC-{escrowId}-{action}-{attemptSeq}` or `LFR-{refundId}-{attemptSeq}`. Unique in `terminal_commands.idempotency_key` via DB constraint.
- Synchronous response is minimal: `{ "status": "ACK" }` or `{ "status": "REJECTED", "reason": "..." }`. The actual L$ transfer result comes back async via the payout-result callback.

### 7.2 Inbound callback — terminal → backend

`POST /api/v1/sl/escrow/payout-result`:

```json
{
  "idempotencyKey": "ESC-42-PAYOUT-1",
  "success": true,
  "slTransactionKey": "sl-txn-abc123",
  "errorMessage": null,
  "terminalId": "term-001",
  "sharedSecret": "..."
}
```

Handler:

1. `SlHeaderValidator` + secret check.
2. Load `TerminalCommand` by `idempotencyKey`; 404 if unknown.
3. If command state is already `COMPLETED`, return OK idempotently (LSL retry).
4. On `success=true`:
   - Command `IN_FLIGHT → COMPLETED`; stamp `completed_at` + `slTransactionKey`.
   - Write `EscrowTransaction` ledger row (type=`AUCTION_ESCROW_PAYOUT` or `AUCTION_ESCROW_REFUND` or `LISTING_FEE_REFUND`; status=`COMPLETED`; amount; payer=SLPA escrow account; payee=recipient; `sl_transaction_id`).
   - For `PAYOUT` on `AUCTION_ESCROW`: transition Escrow `TRANSFER_PENDING → COMPLETED`, stamp `completed_at`; also write `AUCTION_ESCROW_COMMISSION` ledger row (payee=SLPA platform internal account, amount=commission_amt); WS envelope `ESCROW_COMPLETED` (no monetary fields).
   - For `REFUND` on `AUCTION_ESCROW`: no state change (Escrow already in EXPIRED/DISPUTED/FROZEN); ledger row is sufficient evidence. WS envelope `ESCROW_REFUND_COMPLETED`.
   - For `REFUND` on `LISTING_FEE_REFUND`: flip `ListingFeeRefund.status=PROCESSED`, stamp `processed_at` + `txnRef=slTransactionKey`. No WS broadcast (listing-fee refunds are seller-only operational state).
5. On `success=false`:
   - Command `IN_FLIGHT → FAILED`; increment `attempt_count`; schedule retry per backoff table; on 4th failure flag `requires_manual_review=true`.
   - Log `errorMessage` to the ledger as a FAILED `EscrowTransaction` row (audit trail captures the attempt even when no L$ moved).

### 7.3 Retry policy

| Attempt | Delay after previous | Cumulative | Notes |
|---------|---------------------|------------|-------|
| 1 (initial) | — | 0 | Immediate on QUEUED discovery |
| 2 | 1 min | ~1 min | First backoff — transient LSL/network |
| 3 | 5 min | ~6 min | Second backoff — region restart likely |
| 4 | 15 min | ~21 min | Third backoff — last automatic attempt |
| — | — | — | 4th failure → `requires_manual_review=true`, WS `ESCROW_PAYOUT_STALLED`, escalate |

Same curve applies to IN_FLIGHT-timeout requeue (stale → FAILED → retry with `next_attempt_at=now`, counts toward the same attempt budget).

### 7.4 Rate-limit discipline

Dispatcher's 30-second cadence + per-terminal POST-once-per-cycle enforces ≤ 30 dispatches per 30s across all terminals — implicit ceiling below the per-region SL cap of 30 L$ transfers / 30s. If the dispatcher needs to process >30 commands in a cycle, it picks oldest-queued-first and leaves the rest for the next cycle.

### 7.5 Terminal registration

- `POST /api/v1/sl/terminal/register` body: `{ terminalId (client-chosen, e.g. SL object UUID), httpInUrl, sharedSecret }`.
- Upsert on `terminal_id`; stamps `last_seen_at=now`.
- Terminals expected to re-register on every region restart (LSL `changed(CHANGED_REGION_START)`); the `last_seen_at > now - 15min` filter in the dispatcher naturally skips stale rows.
- Registration is public-with-SL-headers + shared-secret; no JWT. An attacker registering a fake terminal can't do anything useful — the attacker doesn't know the payout idempotency flow the real LSL would execute, and forged callbacks fail secret validation.

---

## §8 — WebSocket Envelopes

Channel: existing `/topic/auction/{id}` STOMP destination (reuses the `JwtChannelInterceptor` allowlist + re-attach registry + reconnect banner from Epic 04).

New envelope types, discriminated by `type` field on the existing `AuctionBroadcastEnvelope` sealed interface:

| `type` | When | Payload (beyond auctionId + escrowId + state + serverTime) |
|--------|------|------------------------------------------------------------|
| `ESCROW_CREATED` | Escrow row inserted on auction end | `paymentDeadline` |
| `ESCROW_FUNDED` | Payment accepted; atomic advance to TRANSFER_PENDING | `transferDeadline` |
| `ESCROW_TRANSFER_CONFIRMED` | Ownership confirmed; payout queued; state still TRANSFER_PENDING | `transferConfirmedAt` |
| `ESCROW_COMPLETED` | Payout callback success | `completedAt` (no monetary fields) |
| `ESCROW_DISPUTED` | User dispute filed | `reason` (dispute `reasonCategory`) |
| `ESCROW_EXPIRED` | Timeout fired | `reason` (`PAYMENT_TIMEOUT` or `TRANSFER_TIMEOUT`) |
| `ESCROW_FROZEN` | Fraud detected | `reason` (`UNKNOWN_OWNER` / `PARCEL_DELETED` / `WORLD_API_PERSISTENT_FAILURE`) |
| `ESCROW_REFUND_COMPLETED` | Refund callback success | `refundAmount` |
| `ESCROW_PAYOUT_STALLED` | 4+ dispatch failures | `attemptCount`, `lastError` |

**Payload philosophy:** coarse, cache-invalidation-flavored. Frontend receives the event + a couple of critical fields (new deadline, escrow id, reason code) and refetches `GET /api/v1/auctions/{id}/escrow` for the full timeline.

**Publishing hygiene:** all envelopes fire via `TransactionSynchronization.afterCommit` using `StompAuctionBroadcastPublisher`. No pre-commit broadcasts.

**Privacy note:** `ESCROW_COMPLETED` deliberately omits `payoutAmount` and `commissionAmount`. The state flip is public; the amounts are not. The seller and winner see amounts via the auth-gated `GET /api/v1/auctions/{id}/escrow` endpoint that the envelope triggers them to refetch. Keeps commission structure private and leaves room to introduce negotiated rates later without a schema break.

**Why the public topic:** this channel already broadcasts `AUCTION_ENDED` + `winnerUserId` publicly in Epic 04; escrow state flips are a strict subset of information already public. No buyer/seller identity or payment amounts are exposed beyond what `AUCTION_ENDED` already carries. Future per-user queue migration (Epic 09) moves flow-specific events off this channel.

**JwtChannelInterceptor:** already allowlists `/topic/auction/{id}` subscriptions; no changes. SEND-requires-auth guard is untouched (clients never SEND escrow events; only the backend publishes).

---

## §9 — Error Handling

### 9.1 HTTP error taxonomy — authenticated endpoints

| Scenario | Exception → Status | Error Code |
|----------|--------------------|------------|
| Not seller or winner | `EscrowAccessDeniedException` → 403 | `ESCROW_FORBIDDEN` |
| Auction has no escrow yet | `EscrowNotFoundException` → 404 | `ESCROW_NOT_FOUND` |
| Dispute from terminal state | `IllegalEscrowTransitionException` → 409 | `ESCROW_INVALID_TRANSITION` |
| Unverified caller | `NotVerifiedException` (existing) → 403 | `NOT_VERIFIED` |

Exceptions handled by a new `EscrowExceptionHandler` (`@RestControllerAdvice`) alongside the existing `AuctionExceptionHandler`.

### 9.2 Idempotency-failure semantics

- `slTransactionKey` collision on `/sl/escrow/payment` or `/sl/listing-fee/payment`: response is a replay of the original decision. If the first call returned OK, the retry returns OK; if the first returned REFUND, the retry returns REFUND with the same reason. No state change on the retry.
- `idempotencyKey` collision on `/sl/escrow/payout-result`: always returns OK. Reason: even a misbehaving terminal re-sending a successful callback shouldn't corrupt state, and a legitimate double-send just confirms the same outcome.

### 9.3 Concurrency

- Every state-mutating operation on Escrow acquires `PESSIMISTIC_WRITE` via `escrowRepo.findByIdForUpdate(id)`.
- `EscrowOwnershipCheckTask`: query IDs without lock, re-lock each in per-escrow transaction (avoids holding DB lock during World API call; matches Epic 03 ownership monitor).
- `TerminalCommandDispatcherTask`: both `markStaleAndRequeue` and `dispatchOne` re-lock the command row before checking state (handles the race where a callback arrives mid-sweep and the command is already COMPLETED).

### 9.4 Logging posture

- INFO: every state transition (`"Escrow {id} transitioned {from} -> {to}: reason={}, actor={}"`).
- INFO: every SL callback (payer UUID + amount + slTransactionKey).
- WARN: FraudFlag creation, IN_FLIGHT timeout, `ESCROW_PAYOUT_STALLED`, `WORLD_API_PERSISTENT_FAILURE`, shared-secret mismatch (without leaking the attempted secret).
- ERROR: 4th payout attempt failure (full command history).

---

## §10 — Listing-Fee Flow Integration

Two deferred-work items from Epic 03 land in this sub-spec. Both reuse the terminal pool + shared secret + dispatcher infrastructure built for auction escrow.

### 10.1 Listing-fee payment callback

`POST /api/v1/sl/listing-fee/payment` per §5.2. Implementation notes:

- **Package:** `com.slparcelauctions.backend.escrow.listingfee.ListingFeePaymentController`. Sits inside the escrow package because it shares terminal pool / header validator / shared secret / idempotency store with the escrow payment endpoint. Business logic delegates to the existing `AuctionVerificationService` (or its nearest equivalent that owns `DRAFT → DRAFT_PAID`) to avoid re-implementing transition rules.
- **Handler flow:**
  1. Validate SL headers + shared secret.
  2. Idempotency check on `slTransactionKey`.
  3. Load auction; confirm `status=DRAFT` AND `seller.slAvatarUuid == payerUuid` AND `amount == listing_fee_amount`. Mismatches return `REFUND` with appropriate reason. `status=DRAFT_PAID` with new `slTransactionKey` returns `{ status: ERROR, reason: ALREADY_PAID }`.
  4. On match: `DRAFT → DRAFT_PAID`, stamp `txn_ref=slTransactionKey`, write `LISTING_FEE_PAYMENT` ledger row.
  5. Response `{ status: "OK" }`.
- **Dev endpoint coexists:** `POST /api/v1/dev/auctions/{id}/pay` unchanged, `@Profile("dev")`. Javadoc updates to point at the production path. Test fixtures and local dev unaffected.

### 10.2 Listing-fee refund processor

Existing `ListingFeeRefund` entity already has `status` (PENDING/PROCESSED), `amount`, `txn_ref`, `processed_at`. `CancellationService` (Epic 03) creates PENDING rows when a seller cancels a paid-but-unverified listing. Missing piece: drain the queue.

- **New sibling job `ListingFeeRefundProcessorJob` (every 60 sec).** Scans `ListingFeeRefund WHERE status=PENDING AND terminal_command_id IS NULL` and queues one `TerminalCommand` per refund:
  ```
  action: REFUND
  purpose: LISTING_FEE_REFUND
  recipientUuid: seller.slAvatarUuid
  amount: listing_fee_refund.amount
  listingFeeRefundId: <id>
  escrowId: null
  idempotencyKey: "LFR-{refundId}-1"
  ```
- **`ListingFeeRefund` entity additions:** `terminal_command_id` FK (nullable), `last_queued_at` timestamp. Idempotency — never queues a second command for the same refund unless the first was cancelled.
- **Callback handling:** existing `/sl/escrow/payout-result` callback discriminates on `purpose`. For `LISTING_FEE_REFUND` + `success=true`: flip `status=PROCESSED`, stamp `processed_at` + `txn_ref`, write `LISTING_FEE_REFUND` ledger row. No WS broadcast.
- **Backlog drain:** any `ListingFeeRefund.status=PENDING` rows already in the DB at merge time are automatically queued within 60s of the new job starting. No manual backfill needed.

### 10.3 DEFERRED_WORK entries closed

On merge, remove these entries from `docs/implementation/DEFERRED_WORK.md`:

- "Listing fee refund processor"
- "Real in-world listing-fee terminal" (the backend callback; the frontend copy update ships in sub-spec 2)
- "Escrow handoff from ENDED + SOLD"

Keep but update scope:

- "Primary escrow UUID + SLPA trusted-owner-keys production config" — add note: "A companion startup guard for `primary-escrow-uuid` ships with Epic 05 sub-spec 1 (`EscrowConfigValidator`)."

---

## §11 — DEFERRED_WORK entries opened

Add these to `docs/implementation/DEFERRED_WORK.md` on merge:

### HMAC-SHA256 terminal auth
- **From:** Epic 05 sub-spec 1
- **Why:** Sub-spec 1 ships static shared secret + rotation via config + redeploy. HMAC-SHA256 adds per-request replay protection but requires SHA256 implementation in LSL (~50-100 line library). Premature to ship until a working LSL terminal exists to dogfood against.
- **When:** Phase 2 hardening — after Epic 11 LSL terminals are stable and SHA256-in-LSL is validated.
- **Notes:** Body + timestamp HMAC, per-request nonce, backend nonce-replay window (~60s). `TerminalCommand.shared_secret_version` column already reserved for rotation bookkeeping.

### Smart regional routing for TerminalCommand dispatch
- **From:** Epic 05 sub-spec 1
- **Why:** Phase 1 dispatcher picks any active terminal for any command (pooled, non-sticky). If terminal deployment spreads across >5 regions and regional rate limits start to bite, smart routing (prefer terminals in the recipient's current region, fall back to pool) becomes useful.
- **When:** Indefinite — trigger is operational, not feature-driven.
- **Notes:** `Terminal.region_name` column reserved. Router pluggable behind a `TerminalSelector` interface.

### Notifications for escrow lifecycle events
- **From:** Epic 05 sub-spec 1
- **Why:** State transitions (FUNDED, TRANSFER_CONFIRMED, COMPLETED, EXPIRED, DISPUTED, FROZEN) and the 24h seller-transfer reminder log at INFO but fire no email / SL IM. Consistent with Epic 04's deferral.
- **When:** Epic 09 (Notifications) — hook a subscriber on the escrow broadcast envelope stream.

### Admin tooling for DISPUTED / FROZEN resolution + secret rotation
- **From:** Epic 05 sub-spec 1
- **Why:** No resume path from terminal states (DISPUTED, FROZEN) in sub-spec 1. Admin also has no in-app way to rotate `slpa.escrow.terminal-shared-secret` — rotation requires config edit + redeploy.
- **When:** Epic 10 (Admin & Moderation).
- **Notes:** Admin endpoints `POST /api/v1/admin/escrow/{id}/resolve-dispute`, `POST /api/v1/admin/escrow/{id}/unfreeze`, `POST /api/v1/admin/terminal/rotate-secret`. State machine gains `DISPUTED → FUNDED | TRANSFER_PENDING` and `FROZEN → TRANSFER_PENDING | EXPIRED` at admin's discretion.

### Daily escrow balance reconciliation
- **From:** Epic 05 sub-spec 1
- **Why:** DESIGN.md §5.2 suggests "sum of pending escrow amounts should match the expected SL account balance." Sub-spec 1 writes every L$ movement to the `EscrowTransaction` ledger, so the data is there — just no job reconciles it against SL grid queries.
- **When:** Epic 10 (Admin & Moderation).
- **Notes:** Daily job that sums `EscrowTransaction` rows by type, queries SLPAEscrow account balance via World API, alerts on mismatch.

### Retrofit existing Epic 03/04 code to `Clock` injection
- **From:** Epic 05 sub-spec 1
- **Why:** Sub-spec 1 code injects `Clock` and calls `OffsetDateTime.now(clock)` throughout. Existing Epic 03/04 services that use raw `OffsetDateTime.now()` are unaffected but can't be cleanly tested with a frozen clock. Out of scope for this sub-spec; retrofit when touched.
- **When:** Opportunistic — pull in during the next maintenance pass that touches the affected services.

### AuctionEndedPanel / My Bids / My Listings escrow CTAs
- **From:** Epic 05 sub-spec 1 (frontend follow-up)
- **Why:** Backend ships the escrow state + endpoints in this sub-spec; frontend surfaces (role-aware CTA buttons on the ended auction panel, escrow status link on dashboard rows) ship in sub-spec 2.
- **When:** Epic 05 sub-spec 2.

---

## §12 — LSL Contract Notes for Epic 11

This sub-spec defines the HTTP contract the Epic 11 LSL scripts must implement. Non-negotiable obligations:

### Terminal registration (on rez + on `changed(CHANGED_REGION_START)`)
- Call `llRequestURL()` to obtain a new HTTP-in URL.
- POST that URL to `/api/v1/sl/terminal/register` with `{ terminalId, httpInUrl, sharedSecret }`.
- On failure (4xx), log via `llOwnerSay` but do NOT retry indefinitely — a 4xx likely means the terminal is misconfigured or its shared secret is wrong.
- On success, transition to the READY state and configure `llSetPayPrice(PAY_HIDE, [...])`.

### On `money()` event (user pays the terminal)
- Read payer key + amount from the `money()` event (authoritative — DO NOT derive from any other source).
- POST to the appropriate payment endpoint:
  - If the terminal context is an auction escrow payment: `/api/v1/sl/escrow/payment` with `{ auctionId, payerUuid, amount, slTransactionKey, terminalId, sharedSecret }`.
  - If listing-fee: `/api/v1/sl/listing-fee/payment`.
- On response `{ status: "OK" }`: confirm to the payer via `llInstantMessage` / `llRegionSayTo`.
- **On response `{ status: "REFUND", reason: ... }`: IMMEDIATELY call `llTransferLindenDollars(payer_key_from_money_event, amount)` to return the L$. This is the ONE place where the terminal makes an autonomous financial decision based on the backend response — the money event's payer key is the authoritative refund target and must not be substituted.**
- On response `{ status: "ERROR", reason: ... }`: do NOT refund (request is invalid, may be an attacker with no real L$ attached). Log and alert via `llOwnerSay`.

### On HTTP-in POST from backend (payout/refund command)
- Validate `sharedSecret` body field against the configured terminal secret. Mismatch → respond `{ "status": "REJECTED", "reason": "SECRET_MISMATCH" }` and do NOT execute the transfer.
- Validate `idempotencyKey` against the terminal's local seen-keys set (bounded LRU — last 100 keys). Seen key → respond `{ "status": "ACK" }` without re-executing.
- Respond synchronously with `{ "status": "ACK" }` (before executing the transfer).
- Call `llTransferLindenDollars(recipientUuid, amount)`.
- On `transaction_result` event: POST to `/api/v1/sl/escrow/payout-result` with `{ idempotencyKey, success, slTransactionKey, errorMessage, terminalId, sharedSecret }`.
- Add `idempotencyKey` to the terminal's seen-keys LRU.

### Shared secret handling
- Store in script memory, not in notecards (notecards are readable by anyone with edit perms on the terminal prim).
- Rotation: admin redeploys with a new secret; overlap window during which both old and new are accepted is coordinated via config, not handled by the LSL script (script just reads the current compiled-in secret).

---

## §13 — Testing Strategy

Three-layer distribution per CONVENTIONS.md.

### 13.1 Unit tests (Mockito)

- `EscrowServiceTest` — transition table (valid + invalid, 10+ each), commission edge cases, refund-queuing rule.
- `EscrowOwnershipCheckTaskTest` — three outcomes + World API 404 + 5xx + consecutive-failure threshold.
- `TerminalCommandDispatcherTaskTest` — terminal selection, staleness filter, retry backoff, IN_FLIGHT sweep.
- `EscrowTimeoutTaskTest` — 48h pending, 72h transfer-pending with payout-in-flight guard.
- `EscrowCommissionCalculatorTest` — table-driven.

### 13.2 Slice tests

- `@WebMvcTest` for every controller (GET/dispute, register, payment, listing-fee payment, payout-result).
- `@DataJpaTest` for custom queries: `findExpiredPending`, `findExpiredTransferPending` (with payout-in-flight exclusion), `findTransferPendingIds`, `findDispatchable`, `findStaleInFlight`, `idempotency_key` unique constraint.

### 13.3 Integration tests (`@SpringBootTest` + Testcontainers)

- `EscrowEndToEndIntegrationTest` — full happy path from auction end through payout callback.
- `EscrowFraudPathIntegrationTest` — TRANSFER_PENDING + unknown owner → FROZEN + FraudFlag + refund.
- `EscrowTimeoutIntegrationTest` — `Clock.fixed()` advances past deadlines; verifies payout-in-flight guard.
- `EscrowDisputeIntegrationTest` — dispute from each valid source; refund-if-funded rule.
- `ListingFeeFlowIntegrationTest` — SL endpoint drives DRAFT → DRAFT_PAID; cancel creates refund; processor queues; callback stamps PROCESSED.
- `TerminalCommandRetryIntegrationTest` — 4 failures → PAYOUT_STALLED + WS envelope + `requires_manual_review=true`.
- `EscrowConcurrencyIntegrationTest` — simultaneous payments on same escrow; simultaneous ownership checks; pessimistic locking.

### 13.4 Test infrastructure

- `ClockOverrideConfig` (`@TestConfiguration`) replaces `Clock.systemDefaultZone()` with `Clock.fixed()`.
- `MockTerminalHttpHandler` Spring bean replaces the outbound terminal `RestClient`/`WebClient` — records received commands, scripted responses (`respondWithAck`, `respondWithFailure`, `respondWithTimeout`).
- `TestWsRecorder` (reuse from Epic 04 if present) captures broadcast publisher emissions.

### 13.5 Coverage expectations

- Every state transition exercised by ≥1 integration test.
- Every REST endpoint hit by ≥1 slice + ≥1 integration.
- Every scheduled job exercised by ≥1 integration test using the forced-dispatch pattern.
- Concurrency paths (pessimistic lock contention) covered by ≥1 integration test per entity.

Estimated new tests: ~60-80. No deletions. Backend total post-merge: ~680-700 tests.

---

## §14 — Task Breakdown (preview; full breakdown in plan doc)

Plan will cover ~7-8 tasks. Dependency-ordered:

1. **Foundation:** `Escrow` + `EscrowTransaction` + `EscrowState` + `EscrowService.transition` + state-machine table + commission calc + exception types. Unit tests only. No controllers yet.
2. **Auction end integration:** `EscrowService.createForEndedAuction` + `AuctionEndTask.closeOne` integration + `ESCROW_CREATED` WS envelope. Integration test: auction end creates escrow.
3. **Authenticated endpoints:** `GET /api/v1/auctions/{id}/escrow` + `POST /api/v1/auctions/{id}/escrow/dispute` + `EscrowExceptionHandler`. Slice + integration tests.
4. **Terminal registry + shared secret:** `Terminal` entity + `POST /api/v1/sl/terminal/register` + `EscrowConfigValidator` + `SlHeaderValidator` reuse. Slice tests.
5. **Payment receiving:** `POST /api/v1/sl/escrow/payment` + fraud flag creation + idempotency + `ESCROW_FUNDED` WS envelope. Slice + integration tests.
6. **Ownership monitor:** `EscrowOwnershipMonitorJob` + `EscrowOwnershipCheckTask` + fraud-freeze + consecutive-failure threshold + `ESCROW_TRANSFER_CONFIRMED` / `ESCROW_FROZEN` envelopes. Integration tests.
7. **Terminal command dispatcher + payout callback + retry:** `TerminalCommand` + `TerminalCommandDispatcherJob` + IN_FLIGHT staleness sweep + `POST /api/v1/sl/escrow/payout-result` + retry backoff + `ESCROW_COMPLETED` / `ESCROW_REFUND_COMPLETED` / `ESCROW_PAYOUT_STALLED` envelopes. Full integration tests.
8. **Timeout job:** `EscrowTimeoutJob` + payout-in-flight guard + `ESCROW_EXPIRED` envelope. Integration tests.
9. **Listing-fee integration:** `POST /api/v1/sl/listing-fee/payment` + `ListingFeeRefundProcessorJob` + `ListingFeeRefund.terminal_command_id` addition. Slice + integration tests.

Final task sweeps DEFERRED_WORK.md closures + openings (§10.3, §11) + FOOTGUNS.md entries + README refresh per standing user preference.

---

## §15 — Open Questions / Risks

**Risks:**

- **Clock injection discipline:** sub-spec 1 code consistently uses `Clock` via DI. If a future task hand-writes `OffsetDateTime.now()` inside an escrow service, deadline tests pass but don't actually exercise the clock path. Reviewers should flag raw `.now()` in any new code under `escrow/`.
- **Listing-fee endpoint and auction-escrow endpoint share terminal pool and shared secret.** If a compromised listing-fee terminal tries to submit an escrow payment with a forged `slTransactionKey`, the attack vector is limited by the auction's state (only `ESCROW_PENDING` accepts payment), the winner UUID check, and the amount match. Still — a defense-in-depth pass before Phase 2 launch should audit whether listing-fee terminals should hold a different shared secret than escrow terminals. Noted for Phase 2 hardening.
- **Commission floor behavior at low amounts** (full 100% at L$1 win) is intentional per DESIGN.md. Disclosed to users on the listing page pre-bid as Phase 2 polish if feedback demands.

**Non-risks that might look risky:**

- **`EscrowCompletedEnvelope` omits monetary fields:** intentional privacy posture (§8). Frontend refetches via auth-gated GET. Not a regression against DESIGN.md which never mandated broadcast of commission amounts.
- **Terminal pool model:** L$ held at SLPA avatar account level, not per-terminal. Any registered terminal can execute any command. Resilient to region restarts; no stuck escrows because one specific terminal is offline.

---

## §16 — Cross-cutting concerns

- **No Flyway migrations:** per CONVENTIONS.md, all new tables created via Hibernate `ddl-auto: update`.
- **Lombok required:** per CONVENTIONS.md on all new Java classes.
- **Vertical slice:** per CONVENTIONS.md — every task ships working end-to-end (entity + repo + service + controller + tests).
- **`PagedResponse<T>`:** if any endpoint added in this sub-spec returns paginated data, it uses `PagedResponse<T>` (none expected — all escrow endpoints are per-auction single-resource).
- **No AI/tool attribution in commits or PRs** (per standing user preference).
- **Work off `dev` branch**, branch name `task/05-sub-1-escrow-backend`, PR targets `dev`.
- **README sweep** at end of task sequence per standing user preference.
- **`FOOTGUNS.md`** gets entries for any non-obvious gotchas discovered during implementation (consecutive-failure threshold rationale, payout-in-flight guard, enum-expansion from day one, Clock injection discipline, etc.).
