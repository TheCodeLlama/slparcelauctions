# SLParcels Terminal (wallet model)

In-world wallet kiosk for SLParcels. Three touch-menu options (Deposit-instructions,
Withdraw, SL Group Verify) plus a lockless `money()` deposit handler. Also accepts
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
- **Touch flow:** touch → `llDialog` `[Deposit, Withdraw, SL Group Verify]`
  (no lock acquired). Deposit selection → `llRegionSayTo` instructions, no
  state. Withdraw selection → acquire per-flow slot → text-box for amount →
  confirm dialog → POST to `/sl/wallet/withdraw-request`. On `OK`, the
  backend queues a `WALLET_WITHDRAWAL` `TerminalCommand` that fires
  asynchronously; on `REFUND_BLOCKED`, no L$ to bounce — `llRegionSayTo` the
  reason. SL Group Verify selection → acquire per-flow verify slot →
  text-box for the `SLPA-XXXXXXXXXXXX` code → POST to
  `/sl/sl-group/verify` with `{ verificationCode, founderAvatarUuid }`.
  Result goes to the toucher via `llDialog` `[OK]` (private; not open-chat
  — terminal-output-genericisation policy from commit 5a5276a) and to the
  terminal owner via `llOwnerSay` in DEBUG_MODE: 200 → "SL group
  verified."; 410 → "code expired"; 422 (`SL_GROUP_FOUNDER_MISMATCH`) →
  "not the founder"; 404 → "code not recognised"; other → generic "problem
  with SL Group Verify" message. Backend ProblemDetail title/detail are
  NOT surfaced to the toucher. No L$ involved on this path — no refund
  logic.
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
   - Touch → SL Group Verify. If `SL_GROUP_VERIFY_URL` is set, you get a
     `llTextBox` prompt for the `SLPA-XXXXXXXXXXXX` code; if not, you get an
     "unavailable on this terminal" dialog. (Full happy-path verification
     requires a live SL group registration from the web UI — see Operations.)

The new SLParcels Parcel Verifier Giver prim (separate; see
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
| `SL_GROUP_VERIFY_URL` | Full URL of `/api/v1/sl/sl-group/verify`. Optional for backward compatibility — pre-E deployments degrade gracefully (user picking "SL Group Verify" gets an "unavailable on this terminal" dialog). New deployments should set it so realty-group founders can complete verification from this terminal. |
| `SHARED_SECRET` | The shared secret. **Required.** Obtain from `slpa.escrow.terminal-shared-secret`. |
| `TERMINAL_ID` | Optional. Defaults to `(string)llGetKey()`. |
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
- `SLParcels Terminal: SL Group Verify OK for <avatar>` — founder-terminal
  verification succeeded (sub-project E spec §7.3). The realty group leader
  can now list parcels owned by that SL group.
- `SLParcels Terminal: SL Group Verify failed for <avatar> status=... code=...` —
  founder-terminal verification rejected. Mapped statuses: 410
  (`SL_GROUP_VERIFICATION_EXPIRED`), 422 (`SL_GROUP_FOUNDER_MISMATCH`), 404
  (code not recognised). Other statuses fall through to a generic
  "verification failed" message to the toucher.
- `CRITICAL: SLParcels Terminal: deposit ... not acknowledged after 5 retries` —
  deposit recovery failed; manual reconciliation required.
- `CRITICAL: unexpected REFUND HTTP-in command` — the backend dispatched a
  REFUND action; refunds should be wallet credits in the new model. Investigate.
- `CRITICAL: PERMISSION_DEBIT denied — script halted. Owner must re-grant.` —
  permissions issue.

## SL Group Verify (sub-project E spec §7.3, §13.3)

The "SL Group Verify" menu item supports the founder-of-an-SL-group
verification path for realty groups listing parcels under case-3 (SL group
owns the parcel). The leader of a realty group starts the registration in
the SLParcels web UI; the SL group's founder finishes it by typing the
short `SLPA-XXXXXXXXXXXX` code into any deployed SLParcels Terminal.

- **Menu:** Main menu → **SL Group Verify**.
- **Input:** the 12-character `SLPA-XXXXXXXXXXXX` verification code shown
  on the realty group's SL Groups page after the leader initiates
  registration. Trimmed of whitespace before send.
- **Effect:** POSTs `{ verificationCode, founderAvatarUuid }` to
  `/api/v1/sl/sl-group/verify`. Backend cross-checks the toucher's avatar
  UUID against the SL group's founder via the World API and, on match,
  flips the registration row to `verified=true,
  verified_via=FOUNDER_TERMINAL`.
- **Outcomes** (owner-said in DEBUG_MODE; toucher gets `llDialog` `[OK]`
  on every status — private to the toucher, per the
  terminal-output-genericisation policy in commit 5a5276a):
  - 200 OK → "SL group verified. The realty group can now list parcels
    owned by this SL group."
  - 410 (`SL_GROUP_VERIFICATION_EXPIRED`) → "Verification code expired.
    The realty group leader needs to start a new registration."
  - 422 (`SL_GROUP_FOUNDER_MISMATCH`) → "You are not the founder of the SL
    group registered with this code."
  - 404 → "Verification code not recognised. Check the code and try again."
  - 401 / other 4xx → "Verification failed; check the code and try again."
- **Concurrency:** up to 4 per-terminal verify slots (one per avatar)
  share the same 60s TTL + 10s sweeper as the withdraw slots. Only ONE
  verify POST may be in flight per terminal at a time — a second avatar's
  submit while the first is in flight gets a "busy, try again" dialog
  (concurrent independent verifies should use separate terminals).
- **No new env vars or permissions required.** The existing
  `X-SecondLife-Owner-Key` SL header (validated server-side by
  `SlHeaderValidator` on `/api/v1/sl/**`) is the trust gate. No
  `sharedSecret` is sent on this path because the controller doesn't read
  one; the wire shape exactly mirrors `SlGroupVerifyRequest`.
- **Backward compatibility.** `SL_GROUP_VERIFY_URL` is optional in the
  notecard. Pre-E deployments simply show an "unavailable on this
  terminal" dialog when the menu item is chosen; the rest of the terminal
  (deposit / withdraw / heartbeat / HTTP-in) continues to work
  unchanged.

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
| "SL Group Verify is unavailable on this terminal." | `SL_GROUP_VERIFY_URL` is empty in the `config` notecard. Add the URL and reset the script. |
| SL Group Verify owner-says "status=401" | Backend `/api/v1/sl/sl-group/verify` rejected the request at the SL-header gate. Confirm the terminal's owner UUID is in `slpa.sl.trusted-owner-keys`. **Heath-only note:** at the time of this writing the path may also be missing from `SecurityConfig` allowlist; check there if 401s persist after the owner-key is correct. |
| SL Group Verify owner-says "status=415" | Backend returned `application/problem+json` for a 4xx. Confirm `SlProblemDetailContentTypeAdvice` covers `/api/v1/sl/sl-group/**` (the path prefix in advice is `/api/v1/sl/**`, so this should be automatic). |

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
