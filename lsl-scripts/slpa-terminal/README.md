# SLParcels Terminal (wallet model)

In-world wallet kiosk for SLParcels. Two scripts that go in the **same
prim**, sharing the same `config` notecard:

- `slpa-terminal.lsl` — touch/dialog, lockless `money()` -> personal
  wallet deposit, touch-initiated withdraw, HTTP-in for backend
  PAYOUT/WITHDRAW commands. This is the core wallet kiosk.
- `slpa-terminal-group.lsl` — optional sister script. Adds a "Pay to
  group" touch-menu option that routes `money()` into a realty group's
  wallet instead of the payer's personal wallet. Lives in the same
  prim as the wallet script and coordinates with it via
  `llMessageLinked` (PING/PONG/START/CLAIM/RELEASE protocol). The
  split exists because both flows in one script tripped Stack-Heap
  Collision -- each LSL script gets its own 64KB heap. See the "Pay to
  group sister script" section below.

Drop the wallet script alone for legacy-shape terminals (Deposit +
Withdraw only). Drop both scripts together to enable the third menu
option. Each script silently ignores notecard keys it doesn't recognise,
so the same `config` notecard works whichever scripts are present.

SL Group Verify (founder-of-an-SL-group verification, sub-project E spec
section 7.3) is **not** on this terminal. It lives on the SLParcels
Verification Terminal — same building, the kiosk whose name actually
advertises "verification". See `lsl-scripts/verification-terminal/`.

## Architecture summary

- **Trust:** SL-injected `X-SecondLife-Shard` + `X-SecondLife-Owner-Key` headers
  on outbound, plus a `sharedSecret` field in body for register / deposit /
  withdraw-request / payout-result requests. Inbound HTTP-in: shared-secret
  check on every command.
- **Deposit flow (lockless):** Right-click → Pay → enter L$ amount → `money()`
  fires → POST to `/sl/wallet/deposit` → OK / REFUND / ERROR. On REFUND **and
  ERROR**, the script `llTransferLindenDollars` to bounce — the payer's L$
  is in our hands, so any non-OK response refunds. The earlier "ERROR
  could be an attacker probe, owner-say only" stance was unfounded:
  pre-flight `X-SecondLife-Shard` + `X-SecondLife-Owner-Key` + shared-secret
  validation already throws before any L$-bearing code path runs, so an
  ERROR reaching the deposit response handler implies a legitimate payer
  with an unhandled failure (unknown terminal id, unparseable payer uuid,
  etc). Background retry: 10s / 30s / 90s / 5m / 15m. Multiple users can
  pay simultaneously — `money()` is naturally reentrant.
- **Touch flow:** touch → `llDialog` `[Deposit, Withdraw]`
  (no lock acquired). Deposit selection → `llRegionSayTo` instructions, no
  state. Withdraw selection → acquire per-flow slot → text-box for amount →
  confirm dialog → POST to `/sl/wallet/withdraw-request`. On `OK`, the
  backend queues a `WALLET_WITHDRAWAL` `TerminalCommand` that fires
  asynchronously; on `REFUND_BLOCKED`, no L$ to bounce — `llRegionSayTo` the
  reason.
- **Per-flow withdraw slots:** single `llListen` opened at startup, never
  closed. Up to 4 concurrent withdraw sessions, one per avatar (per-avatar
  dedup). Strided list `[avatarKey, amountOrMinusOne, expiresAt, ...]`.
  Slot dispatched by `id` in the listen handler. 60s session TTL; sweeper
  runs every 10s.
- **HTTP-in flow:** parallel to touch; backend POSTs `TerminalCommandBody`
  with action PAYOUT/WITHDRAW; script validates shared secret, fires
  `llTransferLindenDollars`, reports `transaction_result` to `/payout-result`.
  REFUND action is defensive — refunds are wallet credits in the new model
  and shouldn't arrive; if one does, log CRITICAL and fail.
- **Region restart:** `changed(CHANGED_REGION_START)` triggers `llRequestURL()`
  + re-register. Backend's `terminals.http_in_url` is updated.
- **Heartbeat (every 5 min):** the script periodically POSTs to
  `/sl/terminal/heartbeat` so the backend dispatcher's `lastSeenAt` window
  stays fresh. Without it, an idle but healthy terminal can drop out of
  dispatch rotation between rezzes / inventory changes. The backend ALSO
  refreshes `lastSeenAt` on every authenticated terminal call (deposit /
  withdraw-request / payout-result), so heartbeats only matter when the
  terminal is genuinely idle. Backward-compatible: if `HEARTBEAT_URL` is
  empty in the notecard, heartbeats are skipped silently.

## Deployment

**SLParcels-team-deployed only.** This script holds a shared secret and PERMISSION_DEBIT.
Never publish on Marketplace.

1. Rez a generic prim at SLParcels HQ or an auction venue. Land must permit
   outbound HTTP and `llRequestURL`.
2. Drop `slpa-terminal.lsl` into the prim.
3. Drop a copy of `config.notecard.example` renamed to **`config`** (no
   extension). Edit all four URLs and `SHARED_SECRET`.
4. Set the prim's owner to the SLParcels service avatar (so
   `X-SecondLife-Owner-Key` matches `slpa.sl.trusted-owner-keys`).
5. Reset the script. The script will request `PERMISSION_DEBIT` from the
   owner — accept the dialog. Confirm:
   - `SLParcels Terminal: registered (terminal_id=..., url=...)` startup ping.
   - Floating text "SLParcels Terminal\nRight-click → Pay to deposit\nTouch for menu" appears.
6. Smoke-test:
   - Right-click the prim → Pay → L$10. Confirm `deposit ok L$10 from <name>`.
   - Touch → Withdraw → enter L$5 → Yes. Confirm withdrawal arrives in your
     SL avatar within ~30s.
7. **(Optional) Pay to group:** drop `slpa-terminal-group.lsl` into the
   same prim. `CHANGED_INVENTORY` auto-resets it; accept a SECOND
   `PERMISSION_DEBIT` dialog (each script needs its own grant). The
   wallet's startup PING will hear the sister's PONG and the touch
   menu will now include "Pay to group". See the section below for
   the protocol + smoke tests.

The new SLParcels Parcel Verifier Giver prim (separate; see
`lsl-scripts/slpa-verifier-giver/`) handles parcel-verifier give-out — it is
**not** built into this terminal anymore.

## Configuration

| Key | Description |
| --- | --- |
| `REGISTER_URL` | Full URL of `/api/v1/sl/terminal/register`. Required (wallet). |
| `DEPOSIT_URL` | Full URL of `/api/v1/sl/wallet/deposit`. Required (wallet). |
| `WITHDRAW_REQUEST_URL` | Full URL of `/api/v1/sl/wallet/withdraw-request`. Required (wallet). |
| `PAYOUT_RESULT_URL` | Full URL of `/api/v1/sl/escrow/payout-result`. Required (wallet). |
| `HEARTBEAT_URL` | Full URL of `/api/v1/sl/terminal/heartbeat`. Optional but recommended (wallet) — see Architecture summary. |
| `GROUP_DEPOSIT_URL` | Full URL of `/api/v1/sl/wallet/group-deposit`. Required only when `slpa-terminal-group.lsl` is also in the prim. |
| `SHARED_SECRET` | The shared secret. **Required.** Obtain from `slpa.escrow.terminal-shared-secret`. Read by both scripts. |
| `TERMINAL_ID` | Optional. Defaults to `(string)llGetKey()`. Read by both scripts; they should agree. |
| `REGION_NAME` | Optional. Defaults to `llGetRegionName()`. |
| `DEBUG_MODE` | Optional. `true`/`false`, default `true`. Per-event owner-say. |

### Rotating the shared secret

1. Update `slpa.escrow.terminal-shared-secret` in the deployment's secret store.
2. Restart the SLParcels backend so it picks up the new secret.
3. On every deployed SLParcels Terminal: edit the `config` notecard with the new
   `SHARED_SECRET` value. `CHANGED_INVENTORY` auto-resets the script and
   re-registers with the new secret.

## Operations

In steady state with `DEBUG_MODE=true`:

- `SLParcels Terminal: registered (terminal_id=..., url=...)` — startup confirmation.
- `SLParcels Terminal: deposit ok L$<amount> from <payer>` — successful deposit POST.
- `SLParcels Terminal: deposit refunded (UNKNOWN_PAYER) L$<amount> to <payer>` —
  bounced an unknown-payer deposit.
- `SLParcels Terminal: deposit refunded on ERROR (<reason>) L$<amount> to <payer>` —
  backend returned a non-REFUND error code (e.g. UNKNOWN_TERMINAL); the
  script bounces the L$ regardless so the payer is never out money.
- `SLParcels Terminal: deposit retry N/5: status=...` — transient, retrying.
- `SLParcels Terminal: withdraw queued L$<amount> for <payer>` — successful withdraw-request.
- `SLParcels Terminal: HTTP-in WITHDRAW to <recipient> L$<amount> ikey=...` — backend
  dispatched a wallet-withdrawal fulfillment to this terminal.
- `SLParcels Terminal: ignoring 0-L$ PAYOUT for ikey=...` — sub-project G §8.2
  graceful-skip behaviour. The backend's post-G `runZeroPayoutSuccessInline`
  path never emits amount=0 PAYOUT commands, but a stale command from before
  the deploy can still arrive in-world. The script acks the command as
  success with memo `skipped-zero-amount` so the backend clears it on the
  next callback round-trip rather than letting it stall. No L$ moves; no
  refund needed.
- `SLParcels Terminal: payout-result acknowledged.` — backend received our async result.
- `SLParcels Terminal: heartbeat ok.` — periodic 5-minute heartbeat acknowledged.
- `SLParcels Terminal: heartbeat failed status=...` — heartbeat POST failed; will
  retry on the next interval. Not critical unless it persists.
- `CRITICAL: SLParcels Terminal: deposit ... not acknowledged after 5 retries` —
  deposit recovery failed; manual reconciliation required.
- `CRITICAL: unexpected REFUND HTTP-in command` — the backend dispatched a
  REFUND action; refunds should be wallet credits in the new model. Investigate.
- `CRITICAL: PERMISSION_DEBIT denied — script halted. Owner must re-grant.` —
  permissions issue.

## Troubleshooting

| Symptom | Likely cause |
| --- | --- |
| Startup says `incomplete config` | One of the required notecard keys is empty. |
| Startup says `notecard 'config' missing or unreadable` | Notecard not in inventory or named something other than exactly `config`. |
| `PERMISSION_DEBIT denied` | Owner declined the permission dialog. Reset the script and accept. |
| `URL_REQUEST_DENIED` | Land doesn't allow scripts to request URLs. Move the prim to a region with permissive land settings. |
| Periodic `register retry N/5` | Backend unreachable or rejecting registration. Check `slpa.sl.trusted-owner-keys` includes this terminal's owner. |
| `deposit retry N/5` repeatedly | Backend transient or network issue. Self-recovers in most cases. |
| `CRITICAL: deposit not acknowledged` | Backend POST never succeeded after 5 retries. Manual reconciliation required. |
| Withdraw text-box says `Terminal busy — try another nearby` | All 4 withdraw slots occupied. Walk to another terminal. (Should be vanishingly rare at SLParcels's traffic level.) |
| Backend command dispatcher logs 403 | Shared secret mismatch. Update notecard, reset. |
| `CRITICAL: unexpected REFUND HTTP-in command` | Stale code path or migration issue — refunds should be wallet credits. Investigate. |

## Limits

- LSL listen cap is 65; the script opens **exactly one** listen at startup
  for the entire terminal lifetime. Per-flow withdraw state is stored in the
  `withdrawSessions` strided list, not per-flow listens. Listen leak class
  of bug eliminated by construction.
- HTTP-in URLs change on region restart; re-registration is automatic via
  `changed(CHANGED_REGION_START)`.
- `llTransferLindenDollars` rate limit: 30 payments per 30 seconds per owner per
  region. Phase 1 traffic is well under this; alert if approached.
- 4 concurrent withdraw sessions max per terminal. For high-volume venues,
  deploy multiple SLParcels Terminals — backend's dispatcher picks any active
  one for commands.
- Inflight HTTP-in commands cap at 16 concurrent. New commands beyond that
  return 503; backend retry budget handles.
- Bounded payment retry: 10s / 30s / 90s / 5m / 15m, total ~22 minutes of
  trying. After exhaustion the script logs CRITICAL and stops; daily
  reconciliation job catches missed POSTs.

## Security

- The terminal must be owned by an SLParcels service avatar listed in
  `slpa.sl.trusted-owner-keys`. Backend rejects header mismatch.
- The shared secret in the notecard is visible to anyone with edit-rights on
  the prim — keep ownership and modify permissions SLParcels-team-only.
- A leaked shared secret means an attacker can call `/payout-result` with
  forged "success" outcomes (debiting the wallet ledger without actually
  paying anyone) — rotate immediately if compromise is suspected.
- HMAC-SHA256 per-request auth is on the deferred list (`DEFERRED_WORK.md`)
  for Phase 2 hardening once the LSL terminal is dogfooded.
- Withdraw recipient UUID is **always** `user.slAvatarUuid` server-side,
  never client-supplied. The wallet model's central anti-fraud invariant.

## SL grid Content-Type filter (footgun)

`llHTTPRequest` filters response Content-Type against `HTTP_ACCEPT`.
The grid passes `application/json` through, but it does NOT recognise
the `+json` structured-syntax suffix — so a backend response with
`Content-Type: application/problem+json` (Spring's RFC 9457 default for
ProblemDetail) gets silently replaced by the SL HTTP layer with a
synthetic `415` and body `Unsupported or unknown Content-Type.`. The
script never sees the real status or body.

Backend mitigation: `SlProblemDetailContentTypeAdvice` rewrites all 4xx/5xx
responses on `/api/v1/sl/**` paths to `Content-Type: application/json`.

If you add a new SL-facing endpoint that returns 4xx/5xx, the advice
covers it automatically by path prefix — no per-endpoint work needed.

## Pay to group sister script (`slpa-terminal-group.lsl`)

Optional sister script that goes in the **same prim** as the wallet
script. Adds a "Pay to group" touch-menu option that routes a `money()`
event into a realty group's wallet via `/sl/wallet/group-deposit`
instead of crediting the payer's personal wallet via `/sl/wallet/deposit`.
The split exists because the combined feature set tripped Stack-Heap
Collision on real terminals — each LSL script gets its own 64KB heap,
so two scripts in one prim doubles the budget.

### Coordination protocol

`llMessageLinked` between the two scripts, fixed integer codes:

| Code | Direction | Meaning |
| ---- | --------- | ------- |
| `10 PING` | wallet → group | "Are you here?" sent at wallet's `state_entry`. |
| `11 PONG` | group → wallet | Response to PING. Wallet uses this to decide whether to show the "Pay to group" button. |
| `12 START` | wallet → group | "Avatar X just tapped Pay to group." |
| `13 CLAIM` | group → wallet | "Avatar X has typed a group name; skip your personal-deposit POST when their next `money()` event fires." |
| `14 RELEASE` | group → wallet | "Drop the claim on avatar X." Sent after the group script handles the `money()` or when the slot expires. |

`money()` fires in BOTH scripts when an avatar pays. The wallet's
`money()` handler checks its claimed-avatars list on every event; if
the payer is in it, wallet skips its `/sl/wallet/deposit` POST and
lets this sister script POST `/sl/wallet/group-deposit` instead.
Single ledger credit per payment.

### Flow

1. Touch the prim → wallet dialog `[Deposit, Pay to group, Withdraw]`
   (third button only present when the sister script answered PING).
2. User picks "Pay to group" → wallet `llMessageLinked`s START to the
   sister → sister opens its own `llTextBox` on its own channel:
   "Type the realty group's name."
3. User types a name → sister stores a 60-second deposit slot keyed
   by `(avatarKey, groupName)` and sends CLAIM to wallet.
4. User has 60 seconds to right-click → Pay → enter L$. `money()`
   fires in both scripts. Wallet sees CLAIMed → skips. Sister sees
   matching slot → POSTs `/sl/wallet/group-deposit` with `groupName`.
5. Sister sends RELEASE after handling. Slot is one-shot.

### Refund discipline

Per [CLAUDE.md](../../CLAUDE.md) "always refund on deposit error".
L$ is in the sister script's hands by the time `money()` fires. Every
post-auth failure — `REFUND/UNKNOWN_GROUP` (typo on the typed name),
permission revoked, group dissolved, suspended, frozen depositor,
unparseable response, retry exhaustion, expired slot — bounces the
L$ back via `llTransferLindenDollars`.

Retry chain mirrors the wallet's personal-deposit chain: 10s / 30s /
90s / 5m / 15m. Idempotent on `slTransactionKey`. After the five-try
chain exhausts, the sister script refunds the payer and logs CRITICAL.

### Deployment

Prerequisite: wallet script is already in the prim and running.

1. Drop `slpa-terminal-group.lsl` into the prim's contents. The script
   auto-resets via `CHANGED_INVENTORY`.
2. Accept the **second** `PERMISSION_DEBIT` dialog (each script needs
   its own grant for `llTransferLindenDollars`).
3. Confirm on owner-say:
   - `SLParcels Group Pay: config loaded.`
   - The wallet's PING triggers a PONG and the touch menu now includes
     "Pay to group".
4. Smoke-test:
   - Touch → Pay to group → type a real group's display name → wait
     for "You have 60 seconds..." chat → right-click → Pay → L$10.
     Confirm `ok L$10 to '<group name>'` and the `MEMBER_DEPOSIT` row
     on the group's wallet ledger.
   - Touch → Pay to group → type a non-existent name → Pay → L$10.
     Confirm `refunded (UNKNOWN_GROUP)` and the L$ comes back.
   - Touch → Pay to group → type a name → wait > 65 seconds → Pay →
     L$10. Confirm the slot-expired refund path fires.

### Operations (sister script)

In steady state with `DEBUG_MODE=true`:

- `SLParcels Group Pay: config loaded.` — startup confirmation.
- `SLParcels Group Pay: ok L$<N> to '<group name>'` — successful deposit.
- `SLParcels Group Pay: refunded (<REASON>) L$<N> to <payer>` — backend
  returned REFUND; L$ bounced.
- `SLParcels Group Pay: refunded on ERROR (<REASON>) L$<N> to <payer>`
  — backend returned ERROR; L$ bounced defensively.
- `SLParcels Group Pay: retry N/5: status=<code>` — transient backend
  error; retrying on the same `slTransactionKey`.
- `SLParcels Group Pay: slot expired before pay, refunded L$<N> to
  <payer>` — payer paid more than 60s after typing the group name.
- `CRITICAL: SLParcels Group Pay: deposit ... not acknowledged after 5
  retries; refunded payer` — POST failed five times; L$ refunded, log
  for ops reconciliation.
- `CRITICAL: SLParcels Group Pay: PERMISSION_DEBIT denied.` — owner
  declined the debit dialog; refunds-on-error won't work.
- `CRITICAL: SLParcels Group Pay: incomplete config notecard.` —
  `GROUP_DEPOSIT_URL`, `SHARED_SECRET`, or `TERMINAL_ID` missing.

### Troubleshooting (sister script)

| Symptom | Likely cause |
| --- | --- |
| Touch menu doesn't show "Pay to group" | Sister script not present, or its `state_entry` halted before sending PONG. Check owner-say for `incomplete config notecard` or `PERMISSION_DEBIT denied`. |
| "Pay to group" button does nothing | Wallet sent START but the sister's `link_message` handler didn't fire — script halted or DEBIT was denied. Reset and re-accept. |
| Pay L$ went to the payer's personal wallet, not the group | The 60-second slot expired before they paid, OR the text-box was cancelled. The wallet's claim was released, so personal deposit took over. |
| `REFUND/UNKNOWN_GROUP` on a name that should exist | Backend's case-insensitive match against active groups returned empty. The group is dissolved, suspended, or the typed name contains a typo (whitespace, Unicode confusables). |
| Both scripts log "ok" for the same pay | Coordination bug — CLAIM didn't propagate. Reset both scripts; check that the wallet's startup PING fires before any avatar can interact. |
