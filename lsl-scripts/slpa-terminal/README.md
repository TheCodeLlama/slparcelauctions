# SLPA Terminal (wallet model)

In-world wallet kiosk for SLPA. Two touch-menu options (Deposit-instructions,
Withdraw) plus a lockless `money()` deposit handler. Also accepts
backend-initiated PAYOUT/WITHDRAW commands via HTTP-in.

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
- **Touch flow:** touch → `llDialog` `[Deposit, Withdraw]` (no lock acquired).
  Deposit selection → `llRegionSayTo` instructions, no state. Withdraw
  selection → acquire per-flow slot → text-box for amount → confirm dialog →
  POST to `/sl/wallet/withdraw-request`. On `OK`, the backend queues a
  `WALLET_WITHDRAWAL` `TerminalCommand` that fires asynchronously; on
  `REFUND_BLOCKED`, no L$ to bounce — `llRegionSayTo` the reason.
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

**SLPA-team-deployed only.** This script holds a shared secret and PERMISSION_DEBIT.
Never publish on Marketplace.

1. Rez a generic prim at SLPA HQ or an auction venue. Land must permit
   outbound HTTP and `llRequestURL`.
2. Drop `slpa-terminal.lsl` into the prim.
3. Drop a copy of `config.notecard.example` renamed to **`config`** (no
   extension). Edit all four URLs and `SHARED_SECRET`.
4. Set the prim's owner to the SLPA service avatar (so
   `X-SecondLife-Owner-Key` matches `slpa.sl.trusted-owner-keys`).
5. Reset the script. The script will request `PERMISSION_DEBIT` from the
   owner — accept the dialog. Confirm:
   - `SLPA Terminal: registered (terminal_id=..., url=...)` startup ping.
   - Floating text "SLPA Terminal\nRight-click → Pay to deposit\nTouch for menu" appears.
6. Smoke-test:
   - Right-click the prim → Pay → L$10. Confirm `deposit ok L$10 from <name>`.
   - Touch → Withdraw → enter L$5 → Yes. Confirm withdrawal arrives in your
     SL avatar within ~30s.

The new SLPA Parcel Verifier Giver prim (separate; see
`lsl-scripts/slpa-verifier-giver/`) handles parcel-verifier give-out — it is
**not** built into this terminal anymore.

## Configuration

| Key | Description |
| --- | --- |
| `REGISTER_URL` | Full URL of `/api/v1/sl/terminal/register`. Required. |
| `DEPOSIT_URL` | Full URL of `/api/v1/sl/wallet/deposit`. Required. |
| `WITHDRAW_REQUEST_URL` | Full URL of `/api/v1/sl/wallet/withdraw-request`. Required. |
| `PAYOUT_RESULT_URL` | Full URL of `/api/v1/sl/escrow/payout-result`. Required. |
| `HEARTBEAT_URL` | Full URL of `/api/v1/sl/terminal/heartbeat`. Optional but recommended — see Architecture summary. |
| `SHARED_SECRET` | The shared secret. **Required.** Obtain from `slpa.escrow.terminal-shared-secret`. |
| `TERMINAL_ID` | Optional. Defaults to `(string)llGetKey()`. |
| `REGION_NAME` | Optional. Defaults to `llGetRegionName()`. |
| `DEBUG_MODE` | Optional. `true`/`false`, default `true`. Per-event owner-say. |

### Rotating the shared secret

1. Update `slpa.escrow.terminal-shared-secret` in the deployment's secret store.
2. Restart the SLPA backend so it picks up the new secret.
3. On every deployed SLPA Terminal: edit the `config` notecard with the new
   `SHARED_SECRET` value. `CHANGED_INVENTORY` auto-resets the script and
   re-registers with the new secret.

## Operations

In steady state with `DEBUG_MODE=true`:

- `SLPA Terminal: registered (terminal_id=..., url=...)` — startup confirmation.
- `SLPA Terminal: deposit ok L$<amount> from <payer>` — successful deposit POST.
- `SLPA Terminal: deposit refunded (UNKNOWN_PAYER) L$<amount> to <payer>` —
  bounced an unknown-payer deposit.
- `SLPA Terminal: deposit refunded on ERROR (<reason>) L$<amount> to <payer>` —
  backend returned a non-REFUND error code (e.g. UNKNOWN_TERMINAL); the
  script bounces the L$ regardless so the payer is never out money.
- `SLPA Terminal: deposit retry N/5: status=...` — transient, retrying.
- `SLPA Terminal: withdraw queued L$<amount> for <payer>` — successful withdraw-request.
- `SLPA Terminal: HTTP-in WITHDRAW to <recipient> L$<amount> ikey=...` — backend
  dispatched a wallet-withdrawal fulfillment to this terminal.
- `SLPA Terminal: payout-result acknowledged.` — backend received our async result.
- `SLPA Terminal: heartbeat ok.` — periodic 5-minute heartbeat acknowledged.
- `SLPA Terminal: heartbeat failed status=...` — heartbeat POST failed; will
  retry on the next interval. Not critical unless it persists.
- `CRITICAL: SLPA Terminal: deposit ... not acknowledged after 5 retries` —
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
| Withdraw text-box says `Terminal busy — try another nearby` | All 4 withdraw slots occupied. Walk to another terminal. (Should be vanishingly rare at SLPA's traffic level.) |
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
  deploy multiple SLPA Terminals — backend's dispatcher picks any active
  one for commands.
- Inflight HTTP-in commands cap at 16 concurrent. New commands beyond that
  return 503; backend retry budget handles.
- Bounded payment retry: 10s / 30s / 90s / 5m / 15m, total ~22 minutes of
  trying. After exhaustion the script logs CRITICAL and stops; daily
  reconciliation job catches missed POSTs.

## Security

- The terminal must be owned by an SLPA service avatar listed in
  `slpa.sl.trusted-owner-keys`. Backend rejects header mismatch.
- The shared secret in the notecard is visible to anyone with edit-rights on
  the prim — keep ownership and modify permissions SLPA-team-only.
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
