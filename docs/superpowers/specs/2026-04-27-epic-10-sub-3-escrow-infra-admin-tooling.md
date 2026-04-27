# Epic 10 Sub-spec 3 — Escrow & Infrastructure Admin Tooling

**Spec date:** 2026-04-27
**Branch:** `task/10-sub-3-escrow-infra-admin-tooling`
**Builds on:** sub-spec 1 (admin role + JWT + audit log + admin shell) and sub-spec 2 (ban/report/user-mgmt + AdminAuctionService primitives)
**Target:** PR against `dev`, do NOT auto-merge

---

## 1. Goal

Ship the escrow & infrastructure half of Epic 10's admin surface — every "can't launch without" admin capability for managing real money, in-flight auctions, and the supporting infrastructure (bot pool, in-world terminals, balance reconciliation).

The pain points this closes: no in-app way to resolve a stuck dispute; no in-app secret rotation; no balance reconciliation; no withdrawal mechanism; no bot pool visibility; no synchronous ownership-recheck for fraud-flag triage.

Sub-spec 4 (account deletion + audit log viewer + scheduled reminder fan-outs) follows.

## 2. Scope summary

**Two new admin pages:**
- `/admin/disputes` — combined queue of DISPUTED + FROZEN escrows, with `/admin/disputes/{id}` full-page resolve view
- `/admin/infrastructure` — three-section ops console (Bot pool · Terminals · Reconciliation + Withdrawals)

**Existing admin surface extended:**
- Fraud-flag slide-over gets a "Re-check ownership now" button

**Existing user-facing surfaces extended:**
- Winner's escrow dispute submit form gets optional image uploads + `slTransactionKey` field
- Seller's escrow page gets a `SellerEvidencePanel` while escrow is in DISPUTED state

**Capabilities (10):**
1. Dispute resolution (DISPUTED → FUNDED | TRANSFER_PENDING) with optional cancel-and-refund side effect, audit-trailed
2. FROZEN resolution (FROZEN → TRANSFER_PENDING | EXPIRED), with refund queue
3. Two-sided dispute evidence (image uploads + `slTransactionKey`, JSONB on `EscrowDispute`, S3 reuse)
4. Bot pool heartbeat + health dashboard (Redis-only state, 60s cadence, 180s TTL, register-on-first-heartbeat)
5. Terminal heartbeat (hourly) carrying `accountBalance` from `llGetBalance()`
6. Terminal shared-secret rotation (rolling 2-version, backend-pushed to all registered terminals)
7. Daily balance reconciliation (solvency check, zero-tolerance, fan-out alert)
8. Admin withdrawals (strict solvency-guarded, dispatched as TerminalCommand `WITHDRAW`)
9. Synchronous ownership recheck endpoint
10. New `DISPUTE_FILED_AGAINST_SELLER` notification triggering seller-side evidence submission

## 3. Architecture overview

```
                                 ┌──────────────────────────────────────┐
                                 │              /admin                  │
                                 │ ┌──────────────────────────────────┐ │
                                 │ │  Sidebar: + Disputes             │ │
                                 │ │           + Infrastructure       │ │
                                 │ └──────────────────────────────────┘ │
                                 └──────────────────────────────────────┘
                                       │                          │
                                       ▼                          ▼
                              /admin/disputes              /admin/infrastructure
                                  │                              │
                          AdminDispute*Service       AdminBotPool / Terminal / Recon /
                                  │                  Withdrawal / OwnershipRecheck Services
                                  ▼                              │
                            EscrowDispute                        ▼
                            evidence (JSONB)            BotWorker / TerminalSecret /
                                                        ReconciliationRun / Withdrawal
                                                                 │
                                                                 ▼
                                                       TerminalCommand (REFUND/WITHDRAW)
                                                                 │
                                                                 ▼
                                                       Dispatcher → in-world LSL
```

**Reuse from prior epics:**
- `admin_actions` audit table (sub-spec 1) gets new action types
- `AdminAuctionService.cancelByAdmin` (sub-spec 2) called from dispute-resolve cancel path
- `OwnershipCheckTask` (Epic 03 sub-spec 2) invoked synchronously from new admin endpoint
- `S3UploadService` (avatar upload, Epic 02) reused for dispute evidence
- Existing `TerminalCommand` dispatcher (Epic 05 sub-spec 1) gains `WITHDRAW` action
- Existing escrow timeout REFUND path reused by FROZEN→EXPIRED admin path
- Notification SL IM channel (Epic 09 sub-spec 3) used by all new categories

## 4. Data model

### 4.1 New entities

#### `BotWorker`
Registry-only — the source of truth for "every bot we've ever seen." Live state lives in Redis.

```java
@Entity
@Table(name = "bot_workers", uniqueConstraints = {
    @UniqueConstraint(columnNames = "name"),
    @UniqueConstraint(columnNames = "sl_uuid")
})
class BotWorker {
    @Id @GeneratedValue UUID id;
    @Column(nullable = false) String name;             // e.g. "slpa-bot-1"
    @Column(nullable = false) UUID slUuid;             // SL avatar UUID
    @Column(nullable = false) Instant firstSeenAt;
    @Column(nullable = false) Instant lastSeenAt;      // updated every heartbeat
}
```

No `state` column — Redis is canonical for live state.

#### `ReconciliationRun`
One row per cron tick — feeds the 7-day history strip.

```java
@Entity
@Table(name = "reconciliation_runs")
class ReconciliationRun {
    @Id @GeneratedValue UUID id;
    @Column(nullable = false) Instant ranAt;
    @Column(nullable = false) Long expectedLockedSum;
    @Column Long observedBalance;        // nullable when status = ERROR
    @Column Long drift;                  // observed - lockedSum, signed; nullable when ERROR
    @Enumerated(STRING) @Column(nullable = false) ReconciliationStatus status;
    @Column(length = 500) String errorMessage;  // nullable
}

enum ReconciliationStatus { BALANCED, MISMATCH, ERROR }
```

#### `Withdrawal`
Tracks every admin-initiated withdrawal of SLPA-owned L$.

```java
@Entity
@Table(name = "withdrawals")
class Withdrawal {
    @Id @GeneratedValue UUID id;
    @Column(nullable = false) Long amount;             // L$, > 0
    @Column(nullable = false) UUID recipientUuid;       // SL avatar to receive L$
    @ManyToOne(optional = false) User adminUser;
    @Column(length = 1000) String notes;
    @Column UUID terminalCommandId;                    // FK to TerminalCommand row
    @Enumerated(STRING) @Column(nullable = false) WithdrawalStatus status;
    @Column(nullable = false) Instant requestedAt;
    @Column Instant completedAt;
    @Column(length = 500) String failureReason;
}

enum WithdrawalStatus { PENDING, COMPLETED, FAILED }
```

#### `TerminalSecret`
Rotating credential store. Up to 2 active rows at any time (current + previous). All inactive rows have `retiredAt` set.

```java
@Entity
@Table(name = "terminal_secrets")
class TerminalSecret {
    @Id @GeneratedValue UUID id;
    @Column(nullable = false, unique = true) Integer version;
    @Column(nullable = false, length = 64) String secretValue;  // plaintext at rest; DB is admin-only access
    @Column(nullable = false) Instant createdAt;
    @Column Instant retiredAt;     // null = active (accepted on inbound)
}
```

`secretValue` is plaintext (not encrypted at rest). Justification: the threat model is "attacker has DB access," at which point they also have the application config storing the encryption key. Encryption adds key-management dependency for zero practical security gain.

### 4.2 Existing entity column additions

#### `EscrowDispute` (Epic 05 sub-spec 2 — extended)
```java
// New columns
@Column(length = 64) String slTransactionKey;                // optional except required when reasonCategory == PAYMENT_NOT_CREDITED
@JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb default '[]'")
List<EvidenceImage> winnerEvidenceImages;                    // [{ s3Key, contentType, size, uploadedAt }]
@JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb default '[]'")
List<EvidenceImage> sellerEvidenceImages;
@Column(length = 2000) String sellerEvidenceText;            // nullable, 10–2000 when set
@Column Instant sellerEvidenceSubmittedAt;                   // nullable; non-null = submitted, write-locked
```

The existing `description` column captures the winner's text. Seller's text is in `sellerEvidenceText`.

#### `Terminal` (Epic 05 sub-spec 1 — extended)
```java
@Column Instant lastHeartbeatAt;        // nullable, updated each heartbeat
@Column Long lastReportedBalance;       // L$, nullable when never heartbeat'd
```

`lastHeartbeatAt` doubles as `balanceReportedAt` since balance is reported every heartbeat. Heartbeat staleness threshold: 2 hours.

#### `TerminalCommand` (Epic 05 sub-spec 1 — populated)
The `shared_secret_version` column was reserved but unused. This sub-spec populates it at queue time using the current outbound version.

### 4.3 New enums / values

- `EscrowState.EXPIRED` (new terminal state) — reached by FROZEN → EXPIRED, DISPUTED+cancel→EXPIRED, and existing escrow timeout. Semantics: "no transfer happened, winner refunded, escrow closed."
- `TerminalCommandAction.WITHDRAW` — new action queued by admin withdrawals.
- `NotificationCategory.DISPUTE_FILED_AGAINST_SELLER` — group: ESCROW.
- `NotificationCategory.RECONCILIATION_MISMATCH` — group: new `ADMIN_OPS`.
- `NotificationCategory.WITHDRAWAL_COMPLETED` — group: ADMIN_OPS.
- `NotificationCategory.WITHDRAWAL_FAILED` — group: ADMIN_OPS.
- `NotificationGroup.ADMIN_OPS` — new group for admin-targeted operational alerts. Lets admins mute/configure ops categories independently of user-facing groups.

## 5. Disputes domain

### 5.1 State machine

```
                        ┌─ admin RECOGNIZE_PAYMENT ─→ TRANSFER_PENDING
                        │
        FUNDED ─→ DISPUTED ─→ admin RESET_TO_FUNDED ─→ FUNDED (winner re-tries)
                        │
                        └─ admin RESET_TO_FUNDED + checkbox ─→ EXPIRED  (refund queued + auction cancelled)

        TRANSFER_PENDING ─→ FROZEN ─→ admin RESUME_TRANSFER ─→ TRANSFER_PENDING
                                  └─ admin MARK_EXPIRED ─→ EXPIRED  (refund queued)

        FUNDED (timeout) ─→ EXPIRED  (existing path, refund queued — unchanged by this sub-spec)
```

**EXPIRED is the unified terminal state** for "no transfer happened, winner refunded." Reached from three paths; all three queue REFUND TerminalCommand. The state transitions immediately (synchronously); the refund executes async on the next dispatcher cycle.

### 5.2 Backend services

#### `AdminDisputeService` (new)
- `resolve(disputeId, action, alsoCancelListing, adminNote, adminUser)` — single-transaction orchestration:
  - Validates state preconditions (DISPUTED for {RECOGNIZE_PAYMENT, RESET_TO_FUNDED}; FROZEN for {RESUME_TRANSFER, MARK_EXPIRED})
  - `alsoCancelListing` only valid when `action == RESET_TO_FUNDED`; rejects otherwise (400)
  - State transition per the table above
  - For paths reaching EXPIRED: queues REFUND TerminalCommand with `{ recipientUuid: winnerSlUuid, amount: escrowAmount }`
  - For RESET_TO_FUNDED + alsoCancelListing: calls `AdminAuctionService.cancelByAdmin(auctionId, adminUser, "DISPUTE_RESOLUTION")` after the dispute resolution
  - Writes 1 or 2 audit rows (DISPUTE_RESOLVED always; LISTING_CANCELLED_VIA_DISPUTE when checkbox fires)
  - Fans out resolution notification to involved parties (existing dispute fan-out — extended)

#### `EscrowDisputeService` (existing — extended)
- Extend `openDispute(...)` to accept image uploads + `slTransactionKey`
- Defensive guard: assert `winnerEvidenceImages.isEmpty() && slTransactionKey == null` before writing — protects against any future code path bypassing the state transition
- New method `submitSellerEvidence(disputeId, sellerUserId, imageUploads, sellerEvidenceText)`:
  - 403 if `sellerUserId != escrow.auction.sellerUserId`
  - 409 EVIDENCE_ALREADY_SUBMITTED if `sellerEvidenceSubmittedAt IS NOT NULL`
  - 409 ESCROW_NOT_DISPUTED if `escrow.state != DISPUTED`
  - Writes `sellerEvidenceImages`, `sellerEvidenceText`, `sellerEvidenceSubmittedAt = now()`
  - Fires no notification (admin sees it on next dispute view; no need to ping admin per evidence submission)
- Fires new `DISPUTE_FILED_AGAINST_SELLER` notification immediately after winner-side fan-out in `openDispute`

#### `DisputeEvidenceUploadService` (new)
- Validates: max 5 images per side, 5MB per image, content-type ∈ {image/png, image/jpeg, image/webp}. Rejects PDF, SVG, video.
- S3 key pattern: `dispute-evidence/{disputeId}/{role}/{uuid}.{ext}` where role ∈ {winner, seller}
- Reuses `S3UploadService` from avatar upload (Epic 02 sub-spec 2b)
- Returns `EvidenceImage` records on success

### 5.3 Endpoints

#### Existing endpoints — extended
- `POST /api/v1/escrow/{escrowId}/dispute` — multipart payload now accepts up to 5 image files + `reasonCategory` + `description` + optional `slTransactionKey`. Validates `slTransactionKey` is non-blank when `reasonCategory == PAYMENT_NOT_CREDITED`. Defensive guards above.

#### New endpoints
- `POST /api/v1/escrow/{escrowId}/dispute/seller-evidence` — multipart payload: up to 5 image files + `sellerEvidenceText` (10–2000). Auth: `@AuthenticationPrincipal`, must be the seller. Returns 200 with the updated dispute on success.
- `GET /api/v1/admin/disputes` — paginated. Query params: `status` (DISPUTED|FROZEN|all, default all), `reasonCategory` (filter), `ageGreaterThan` (ISO duration), `page`, `size`. Returns rows: `{ disputeId, escrowId, auctionTitle, sellerUsername, winnerUsername, salePriceL, status, reasonCategory, openedAt, ageMinutes, winnerEvidenceCount, sellerEvidenceCount }`.
- `GET /api/v1/admin/disputes/{disputeId}` — full detail. Returns auction context, escrow ledger (last N `EscrowTransaction` rows), winner+seller evidence with **presigned S3 image URLs (5min TTL)**.
- `POST /api/v1/admin/disputes/{disputeId}/resolve` — body: `{ action: "RECOGNIZE_PAYMENT|RESET_TO_FUNDED|RESUME_TRANSFER|MARK_EXPIRED", alsoCancelListing?: bool, adminNote: string }`. `adminNote` required, max 500.

### 5.4 Frontend

#### `/admin/disputes` (queue page)
- Match sub-spec 2 pattern: `AdminDisputesListPage` + `AdminDisputesTable` + `AdminDisputesFilters`
- Filter chips: status (DISPUTED on by default + FROZEN on by default), reason category, age range
- Default sort: oldest first (FIFO triage)
- Click row → push to `/admin/disputes/{id}`
- Hooks: `useDisputesQueue(filters)`

#### `/admin/disputes/[id]` (resolve view, full page)
- Component: `AdminDisputeDetailPage`
- Layout: breadcrumb / hero strip / two-column body
  - Left column: `EscrowLedgerPanel`, `EvidenceSideBySidePanel` (winner | seller)
  - Right rail: `ResolutionPanel` with `ResolveActionPicker` (radio), `AlsoCancelListingCheckbox` (visible only when action == RESET_TO_FUNDED), `AdminNoteField`, `ApplyResolutionButton`
- Image lightbox component (`EvidenceImageLightbox`) for click-to-zoom of evidence thumbnails
- Cancel checkbox label: **"Also cancel this listing and refund winner (no seller penalty)"** (refund consequence explicit)
- On apply success: toast + push back to `/admin/disputes`
- Hooks: `useDispute(id)`, `useResolveDispute(id)`

#### Existing `/escrow/[id]` page — extended
- `DisputeSubmitModal` (winner side, existing) — extended with image upload field (drag-drop + click) + `slTransactionKey` text field that's only required when `reasonCategory == PAYMENT_NOT_CREDITED`
- New `SellerEvidencePanel` rendered when `escrow.state == DISPUTED && currentUser == seller && sellerEvidenceSubmittedAt == null`. Same upload + text field UX as winner side. Submit-once button.
- Hooks: `useSubmitSellerEvidence(escrowId)`

### 5.5 Notifications fired from this domain
- `DISPUTE_FILED_AGAINST_SELLER` — fires from `EscrowDisputeService.openDispute` after winner-side fan-out. Recipient: seller. Body: `"A winner disputed your sale of {parcel} (L$ {amount}). Submit your evidence at {url}."`. Default ON for SL IM + email.

### 5.6 Audit log entries
- `DISPUTE_RESOLVED` — actor: admin, target: dispute. Detail: `{ disputeId, action, alsoCancelListing, refundQueued, adminNote }`.
- `LISTING_CANCELLED_VIA_DISPUTE` — actor: admin, target: auction. Detail: `{ auctionId, originatingDisputeId, refundQueued }`. Distinct from sub-spec 2's `LISTING_CANCELLED` so the dispute-driven path is queryable.

## 6. Infrastructure domain

### 6.1 Bot pool

#### Backend
- `BotHeartbeatService.handle(BotHeartbeatRequest)` — bot bearer auth (existing), upserts `BotWorker` registry row (set `lastSeenAt`, insert if first time), writes Redis key `bot:heartbeat:{slUuid}` with TTL 180s, value JSON: `{ workerName, sessionState, currentRegion, currentTaskKey, currentTaskType, lastClaimAt, reportedAt }`.
- `AdminBotPoolService.getHealth()` — returns merged registry + Redis state. Each row: registered bot from `bot_workers` joined to its Redis heartbeat (or `null` heartbeat = "missing"). Sorted by `lastSeenAt DESC`.
- New cron: `BotPoolHealthLogger` runs every 5 min, INFO log: `"Bot pool: {alive}/{registered} healthy, {dead} dead"`. Dark-pipeline sentinel matching the user's logging-discipline preferences.

#### Endpoints
- `POST /api/v1/bot/heartbeat` — bot bearer auth. Body: `{ workerName, slUuid, sessionState, currentRegion?, currentTaskKey?, currentTaskType?, lastClaimAt? }`. Returns 200.
- `GET /api/v1/admin/bot-pool/health` — admin auth. Returns `[{ workerId, name, slUuid, registeredAt, lastSeenAt, sessionState?, currentRegion?, currentTaskKey?, currentTaskType?, isAlive }]`.

#### Frontend
- `BotPoolSection` rendered as the top section of `/admin/infrastructure` page. Status badge top-right ("3/3 healthy"). Table columns: worker · SL UUID · state · region · current task · last beat.
- Hooks: `useBotPoolHealth(refetchInterval=30s)`

#### LSL/bot-side (deferred to Epic 06 follow-on)
- C# bot adds a `HeartbeatLoop` background task (60s tick, posts current state to backend). Sub-spec 3's UI gracefully renders "no bots heartbeating yet" until this lands.

### 6.2 Terminals

#### Backend
- `TerminalHeartbeatService.handle(TerminalHeartbeatRequest)` — validates current-or-previous secret. Upserts `Terminal.lastHeartbeatAt` + `Terminal.lastReportedBalance`.
- `TerminalSecretService` — manages the rolling 2-version window:
  - `current()` — returns the active row used for outbound signing (highest `version` with `retiredAt IS NULL`)
  - `accept(rawSecret)` — returns `true` if `rawSecret` matches any row with `retiredAt IS NULL`
  - `rotate()` — generates 64-char random hex value, inserts new row with `version = max+1`, retires `vN-1` (sets `retiredAt = now`), returns the new row
- `AdminTerminalRotationService.rotate(adminUser)`:
  - Reads OLD current secret first (needed to sign push)
  - Calls `TerminalSecretService.rotate()`
  - Synchronously POSTs `{ action: "SECRET_ROTATED", newSecret, newVersion }` to every registered terminal's `httpInUrl`, signed with the OLD current secret. Each push has a 5s timeout.
  - Aggregates per-terminal results: `[{ terminalId, terminalName, success: bool, errorMessage? }]`
  - Writes `TERMINAL_SECRET_ROTATED` audit log entry with the result list (does NOT log the secret value)
  - Returns `{ newVersion, secretValue, terminalPushResults }`. `secretValue` is returned ONLY in this response and never by any GET endpoint or log.
- `AdminTerminalsService.list()` — read endpoint for the dashboard.

#### Endpoints
- `POST /api/v1/sl/terminal/heartbeat` — terminal-secret-authenticated. Body: `{ terminalKey, accountBalance }`. Returns 200.
- `GET /api/v1/admin/terminals` — admin auth. Returns `[{ id, regionName, terminalName, slUrl, lastCommandAt, lastCommandAction, lastHeartbeatAt, lastReportedBalance, currentSecretVersion }]`.
- `POST /api/v1/admin/terminals/rotate-secret` — admin auth. Body: `{}`. Returns `{ newVersion, secretValue, terminalPushResults: [{terminalId, terminalName, success, errorMessage?}] }`.

The existing `POST /api/v1/sl/terminal/register` is extended: response includes the **current** secret + version so a re-rezzed terminal catches up automatically. Authenticated with the registering bearer (existing).

#### Frontend
- `TerminalsSection` rendered as second section of `/admin/infrastructure`. Table columns: region · terminal name · SLURL · last command · secret version. "Rotate shared secret" button below the table.
- `RotateSecretModal` — confirmation copy ("This rotates the active credential for all registered terminals. The new secret will be displayed once."), confirm button. On success, modal switches to display state showing the new secret value with copy-to-clipboard button + warning ("Save this now — it will not be shown again.") + per-terminal push results list. Modal is dismissable only by an explicit "I've saved it, close" button (avoid accidental dismiss).
- Hooks: `useTerminalsAdmin()`, `useRotateSecret()`

#### LSL-side (deferred to Epic 11)
- Heartbeat: `llSetTimerEvent(3600)` — every hour, POST `{ terminalKey, accountBalance: llGetBalance() }` signed with current shared secret.
- `SECRET_ROTATED` action handler: validate signature with current secret, overwrite in-memory secret, reply 200.
- `WITHDRAW` action handler: validate signature, execute `llTransferLindenDollars(recipient, amount)`, post callback.

### 6.3 Reconciliation

#### Backend
- `ReconciliationService.runDaily()` — invoked by `@Scheduled(cron = "0 0 3 * * *", zone = "UTC")`:
  - Read freshest `Terminal.lastReportedBalance` where `lastHeartbeatAt >= now - 2h`. If none, persist `ReconciliationRun(status=ERROR, errorMessage="Balance data stale — terminal may be offline")`. Log ERROR.
  - Compute `lockedSum = sum(escrow.amount where state ∈ {FUNDED, TRANSFER_PENDING, DISPUTED, FROZEN})`
  - First check: if `observedBalance >= lockedSum`, persist `BALANCED`, log INFO, return
  - On apparent mismatch: sleep 30s, re-read freshest balance + re-sum locked. If still mismatched, persist `MISMATCH(drift = observed - lockedSum)`, log ERROR, fan out `RECONCILIATION_MISMATCH` notification to all admin users. If now balanced, persist `BALANCED` (timing race resolved).
  - Cron disabled in test contexts via `slpa.reconciliation.enabled=false` property on `@TestPropertySource`.
- `AdminReconciliationService.recentRuns(days=7)` — returns the history strip data.

#### Endpoints
- `GET /api/v1/admin/reconciliation/runs?since=2026-04-20` — admin auth. Returns `[{ id, ranAt, status, expected, observed, drift, errorMessage? }]` ordered by `ranAt DESC`.

#### Frontend
- `ReconciliationSection` — third section of `/admin/infrastructure` page. Latest-run card (expected · observed · drift · status badge) + 7-day history chips (each green if BALANCED, yellow with `+L$N` overlay if MISMATCH, gray if ERROR).
- Hooks: `useReconciliationRuns(days=7)`

#### Notifications
- `RECONCILIATION_MISMATCH` (group: ADMIN_OPS, default ON for SL IM, default OFF for email — admins likely don't want a 3am email for every drift event) — recipients: all `User.role == ADMIN`. Body: `"Daily reconciliation detected L$ {drift} drift on {date}. Open dashboard."`

### 6.4 Withdrawals

#### Backend
- `AdminWithdrawalService.requestWithdrawal(amount, recipientUuid, notes, adminUser)`:
  - Validates `amount > 0`, `recipientUuid != null`, `notes.length <= 1000`
  - Computes solvency: `available = observedBalance - lockedSum - sumPendingWithdrawals - sumOutboundCommands`
    - `sumPendingWithdrawals = sum(Withdrawal.amount where status == PENDING)`
    - `sumOutboundCommands = sum(TerminalCommand.amount where action ∈ {PAYOUT, REFUND} and status ∈ {QUEUED, IN_FLIGHT})`
    - PAYOUT/REFUND and WITHDRAW status sets are non-overlapping → no double-count
  - Returns 409 INSUFFICIENT_BALANCE if `amount > available`
  - **Single transaction**: persists `Withdrawal(status=PENDING)`, queues `TerminalCommand(action=WITHDRAW, recipientUuid, amount)`, sets `Withdrawal.terminalCommandId = newCommandId`. The strict transaction boundary closes the TOCTOU window between solvency-check and persistence.
  - Writes `WITHDRAWAL_REQUESTED` audit row
  - Returns the `Withdrawal` row
- `WithdrawalCallbackHandler` (extended `TerminalCommandCallbackHandler`) — on `WITHDRAW` callback:
  - On success: `Withdrawal.status = COMPLETED`, `completedAt = now()`, fires `WITHDRAWAL_COMPLETED` notification to `adminUser`
  - On failure: `Withdrawal.status = FAILED`, `failureReason = callback.error`, fires `WITHDRAWAL_FAILED` notification to `adminUser`
- `AdminWithdrawalService.list(page, size)` — read history.

#### Endpoints
- `POST /api/v1/admin/withdrawals` — admin auth. Body: `{ amount, recipientUuid, notes }`. Returns 201 with the `Withdrawal` row, or 409 INSUFFICIENT_BALANCE.
- `GET /api/v1/admin/withdrawals?page&size` — admin auth. Returns paginated history.

#### Frontend
- `AvailableToWithdrawCard` — sits above `ReconciliationSection` on `/admin/infrastructure`. Shows `availableToWithdraw` value live (computed from latest reconciliation snapshot + pending count). "Withdraw to Account" button opens modal.
- `WithdrawalModal` — fields: amount (number, with live solvency message under field "Available: L$ X"), recipient SL UUID (text), notes (textarea, 1000 chars). Submit → toast.
- `WithdrawalsHistorySection` — fourth visual block on the page, after reconciliation history strip. Paginated table: requested at · admin · amount · recipient · status · completed at · notes.
- Hooks: `useAvailableToWithdraw()`, `useWithdrawals(page)`, `useRequestWithdrawal()`

#### Notifications
- `WITHDRAWAL_COMPLETED` (group: ADMIN_OPS, default ON for SL IM, default OFF for email) — recipient: requesting admin. Body: `"Withdrawal of L$ {amount} to {recipientUuid} completed."`
- `WITHDRAWAL_FAILED` (group: ADMIN_OPS, default ON for SL IM + email) — recipient: requesting admin. Body: `"Withdrawal of L$ {amount} to {recipientUuid} failed: {reason}. Open dashboard."`

#### Audit log entries
- `WITHDRAWAL_REQUESTED` — actor: admin, target: withdrawal. Detail: `{ withdrawalId, amount, recipientUuid, notes }`.

### 6.5 Ownership recheck

#### Backend
- `AdminOwnershipRecheckService.recheck(auctionId, adminUser)`:
  - Loads auction, validates exists
  - Synchronously invokes existing `OwnershipCheckTask` for this single auction
  - The task internally suspends the auction if mismatch detected (existing behavior — unchanged)
  - Writes `OWNERSHIP_RECHECK_INVOKED` audit row
  - Returns `{ ownerMatch, observedOwner, expectedOwner, checkedAt, auctionStatus }`. `auctionStatus` reflects the post-check state so the frontend can show "this triggered a suspension" UX.

#### Endpoints
- `POST /api/v1/admin/auctions/{id}/recheck-ownership` — admin auth. Returns the result above.

#### Frontend
- Existing fraud-flag slide-over — adds a "Re-check ownership now" button (just below the auction summary). On click → invokes endpoint, on response shows toast (`"Owner match — no change"` OR `"Owner mismatch detected. Auction suspended."`). Slide-over refreshes its fraud-flag detail data after the call to pick up any new flag rows that the existing OwnershipCheckTask created.
- Hooks: `useOwnershipRecheck(auctionId)`

#### Audit log entries
- `OWNERSHIP_RECHECK_INVOKED` — actor: admin, target: auction. Detail: `{ auctionId, ownerMatch, observedOwner, expectedOwner, autoSuspended }`.

## 7. Notifications summary

### 7.1 Categories shipping in this sub-spec (4)

| Category | Group | Default channels | Recipient |
|---|---|---|---|
| `DISPUTE_FILED_AGAINST_SELLER` | ESCROW | SL IM ✓, email ✓ | seller |
| `RECONCILIATION_MISMATCH` | ADMIN_OPS | SL IM ✓, email ✗ | all admins |
| `WITHDRAWAL_COMPLETED` | ADMIN_OPS | SL IM ✓, email ✗ | requesting admin |
| `WITHDRAWAL_FAILED` | ADMIN_OPS | SL IM ✓, email ✓ | requesting admin |

### 7.2 Categories deferred (1)
- `BOT_POOL_DEGRADED` — sub-spec 3 ships the bot pool dashboard for glanceable status; the threshold logic ("degraded" = 1 dead? 50% dead?) deserves operational data first. **Do not add this category enum value yet** — JPA `ddl-auto: update` would migrate a phantom category with no publisher, which is harder to reason about than just adding the value when its publisher ships. New deferred-ledger entry on close-out.

### 7.3 New notification group
- `ADMIN_OPS` — admin-targeted operational alerts. Lets admins mute/configure ops categories independently of user-facing groups (so a `RECONCILIATION_MISMATCH` doesn't get drowned out if an admin silenced their `ESCROW` notifications).

## 8. Logging discipline

Per memory `feedback_background_job_ops_logging.md` — background jobs must surface their state in INFO logs even when no admin is looking at the dashboard.

| Job / service | Cadence | Log level | Format |
|---|---|---|---|
| `ReconciliationService.runDaily()` | once daily 03:00 UTC | INFO at start + end | `"Reconciliation starting"` / `"Reconciliation completed: status={}, expected={}, observed={}, drift={}"`; ERROR on stale balance |
| `BotHeartbeatService.handle(...)` | per call (60s × N bots) | DEBUG only | `"Bot heartbeat from {workerName}"` — too noisy for INFO |
| `TerminalHeartbeatService.handle(...)` | per call (hourly × N terminals) | INFO | `"Terminal heartbeat: {name}, balance=L${balance}"` |
| `AdminTerminalRotationService.rotate()` | per admin click | INFO | `"Secret rotated to v{newVersion}: pushed to {n}/{m} terminals"` + per-terminal failure detail |
| `AdminWithdrawalService.requestWithdrawal()` | per admin click | INFO | `"Withdrawal queued: id={}, amount=L${}, recipient={}, admin={}"` |
| Withdrawal callback handler | per callback | INFO | `"Withdrawal {id} {COMPLETED|FAILED}"` + reason on failure |
| `BotPoolHealthLogger` (cron) | every 5 min | INFO | `"Bot pool: {alive}/{registered} healthy, {dead} dead"` — dark-pipeline sentinel |

## 9. Testing strategy

### 9.1 Backend (matches Epic 10 sub-specs 1 & 2 patterns)

**Unit tests per service:**
- `AdminDisputeServiceTest` — every action × valid/invalid state precondition; alsoCancel only with RESET_TO_FUNDED; refund queueing on EXPIRED-bound paths; audit row count
- `EscrowDisputeServiceTest` — winner-evidence guard rejects double-write; seller-evidence path 403/409 cases; `slTransactionKey` required for PAYMENT_NOT_CREDITED
- `BotHeartbeatServiceTest` — register-on-first-heartbeat upserts BotWorker; Redis key written with TTL
- `AdminBotPoolServiceTest` — registry + Redis merge; missing-heartbeat rows; sort order
- `TerminalHeartbeatServiceTest` — secret current-or-previous validation; balance update
- `TerminalSecretServiceTest` — rotation correctness (insert + retire); rolling-2-window acceptance; outbound signing uses current
- `AdminTerminalRotationServiceTest` — mocked WebClient pushes; signed with OLD secret; per-terminal result aggregation; audit row written without secret
- `ReconciliationServiceTest` — BALANCED, MISMATCH, ERROR/stale-balance, retry-once timing race resolved, retry-once timing race persisted as MISMATCH
- `AdminWithdrawalServiceTest` — solvency formula correctness with each subtractor in isolation and combined; INSUFFICIENT_BALANCE 409; transactional persistence atomic with TerminalCommand queue
- `AdminOwnershipRecheckServiceTest` — calls existing OwnershipCheckTask; returns auctionStatus; audit row written

**Integration tests** (`@SpringBootTest`, scheduler property disabled): one per new controller, focused on auth gate + happy/sad paths.

**High-leverage interaction tests:**
- Rotation transaction atomicity (insert + retire + push)
- Withdrawal TOCTOU (concurrent withdrawal-request rejection)
- Reconciliation cron uses `@TestPropertySource(slpa.reconciliation.enabled=false)` for isolation

### 9.2 Frontend

**Vitest hooks:** `useDisputesQueue`, `useDispute`, `useResolveDispute`, `useBotPoolHealth`, `useTerminalsAdmin`, `useReconciliationRuns`, `useAvailableToWithdraw`, `useWithdrawals`, `useRequestWithdrawal`, `useRotateSecret`, `useOwnershipRecheck`, `useSubmitSellerEvidence`.

**Component tests with MSW handlers:**
- Resolve view: action picker state machine, cancel-checkbox visibility tied to action selection, refund-implication copy on checkbox label
- Rotate-secret modal: one-time secret display, copy-to-clipboard, push results list
- Withdraw modal: live solvency display tracks state changes
- Seller evidence panel: only renders for seller in DISPUTED state; submit-once button locks after success

**Standard regressions:** admin gate works on every new page, breadcrumbs render, loading/error states render.

### 9.3 Pre-existing flakes / known issues

- `AuctionRepositoryOwnershipCheckTest` flakes occasionally — known issue, re-run on demand.
- `CodeDisplay.test.tsx` has a Clipboard cast TS issue — known unrelated, never touched.

## 10. Out of scope

### 10.1 Deferred to sub-spec 4
- Account deletion UI + cascade matrix
- Admin audit log viewer (`admin_actions` read surface)
- `ESCROW_TRANSFER_REMINDER` scheduler
- `REVIEW_RESPONSE_WINDOW_CLOSING` scheduler

### 10.2 Deferred to ledger (indefinite)
- `BOT_POOL_DEGRADED` notification — needs threshold operational data
- WS reconnect telemetry data plane (Epic 04 deferred entry)
- Per-worker bot auth tokens (`bot_workers.token_hash`) — Phase 2 hardening
- HMAC-SHA256 per-request auth (terminals + bots) — Phase 2 hardening
- Smart regional routing for TerminalCommand dispatch — operational trigger
- Parcel layout map generation — needs design pass
- Frivolous-reporter automatic privilege revocation — needs operational data
- Realtime ban broadcast / forced-logout WS — needs UX trigger
- Admin "Send notification to user" surface — no demonstrated need
- LSL terminal locator on PAY ESCROW state — Epic 11

### 10.3 Deferred to LSL/bot tracks
- Epic 11 LSL: terminal heartbeat handler, `SECRET_ROTATED` action handler, `WITHDRAW` action handler. Sub-spec 3's UI gracefully renders "no terminals heartbeating yet" until Epic 11.
- Epic 06 bot: C# `HeartbeatLoop` background task. Sub-spec 3's UI gracefully renders "no bots heartbeating yet" until that small Epic 06 follow-on.

## 11. Execution model

- **Lean Subagent-Driven Development** matching sub-specs 1 & 2.
- **Sonnet implementers** per task. Skip formal spec/quality reviewer subagents. Spot-verify between tasks via diff inspection.
- After all tasks complete: push branch `task/10-sub-3-escrow-infra-admin-tooling` + open PR against `dev`. **Do not auto-merge.**
- Bootstrap admin emails (sub-spec 1) carry over: heath@slparcels.com, heath@slparcelauctions.com, heath@hadronsoftware.com, heath.barcus@gmail.com.

## 12. Acceptance criteria (per Phase 10 brief)

- ☐ Admin can resolve DISPUTED escrows via `RECOGNIZE_PAYMENT` or `RESET_TO_FUNDED` paths
- ☐ Admin can resolve FROZEN escrows via `RESUME_TRANSFER` or `MARK_EXPIRED` paths
- ☐ DISPUTED `RESET_TO_FUNDED` + cancel checkbox correctly fires `cancelByAdmin` and queues REFUND
- ☐ FROZEN `MARK_EXPIRED` queues REFUND and transitions to EXPIRED
- ☐ Two-sided dispute evidence renders for admin, with submit-once invariants on both sides
- ☐ Bot pool dashboard renders live state from Redis, with registry-only rows for missing bots
- ☐ Terminal heartbeat updates `Terminal.lastReportedBalance` and is queryable as the reconciliation balance source
- ☐ Terminal secret rotation generates a new value, pushes to all registered terminals signed with the OLD secret, accepts both old + new on inbound until the next rotation
- ☐ Daily reconciliation persists `ReconciliationRun` rows; mismatches fan out `RECONCILIATION_MISMATCH` to admins
- ☐ Admin withdrawals enforce strict 4-component solvency formula; pending withdrawals reduce available; failed withdrawals notify the requesting admin
- ☐ Synchronous ownership recheck endpoint returns `auctionStatus` reflecting any auto-suspension
- ☐ All admin actions write to `admin_actions` with appropriate detail JSONB
- ☐ All capabilities except those explicitly deferred to LSL/bot tracks are gated only by admin role

---

**End of spec.** Implementation plan to follow via the writing-plans skill.
