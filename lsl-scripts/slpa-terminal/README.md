# SLPA Terminal (unified payments)

In-world unified payment kiosk for SLPA. Handles three payment types via a
4-option touch menu and accepts backend-initiated payouts/refunds/withdrawals
via HTTP-in.

## Architecture summary

- **Trust:** SL-injected `X-SecondLife-Shard` + `X-SecondLife-Owner-Key` headers
  on outbound, **plus** a `sharedSecret` field in body for register / payment /
  payout-result requests. Inbound HTTP-in: shared-secret check on every
  command.
- **Touch flow:** IDLE → (touch) lock + menu → (selection) AUCTION_ID prompt
  (Escrow/Listing-Fee) or LOOKUP (Penalty) or GIVE (Verifier) → (after
  selection) AWAITING_PAYMENT with appropriate `llSetPayPrice` → (money())
  POST to selected endpoint → release lock; retries run in background.
- **HTTP-in flow:** parallel to touch; backend POSTs `TerminalCommandBody`
  with action PAYOUT/REFUND/WITHDRAW; script validates shared secret, fires
  `llTransferLindenDollars`, reports `transaction_result` to `/payout-result`.
- **Lock:** single-user, 60s TTL. Released eagerly: as soon as the first
  payment POST fires, the lock releases so a second user can touch while
  retries run. "Get Parcel Verifier" releases immediately after `llGiveInventory`.
- **Region restart:** `changed(CHANGED_REGION_START)` triggers `llRequestURL()`
  + re-register. Backend's `terminals.http_in_url` is updated.

## Deployment

**SLPA-team-deployed only.** This script holds a shared secret and PERMISSION_DEBIT.
Never publish on Marketplace.

1. Rez a generic prim at SLPA HQ or an auction venue. Land must permit
   outbound HTTP and `llRequestURL`.
2. Drop `slpa-terminal.lsl` into the prim.
3. Drop a `SLPA Parcel Verifier` object copy into the prim's contents (so
   the "Get Parcel Verifier" menu option works).
4. Drop a copy of `config.notecard.example` renamed to **`config`** (no
   extension). Edit all six URLs and `SHARED_SECRET`.
5. Set the prim's owner to the SLPA service avatar (so
   `X-SecondLife-Owner-Key` matches `slpa.sl.trusted-owner-keys`).
6. Reset the script. The script will request `PERMISSION_DEBIT` from the
   owner — accept the dialog. Confirm:
   - `SLPA Terminal: registered (terminal_id=..., url=...)` startup ping.
   - Floating text "SLPA Terminal\nTouch for options" appears.
7. Smoke-test each menu option once with small amounts.

≥1 active SLPA Terminal must be live for the auction-completion path
(PAYOUT). Multi-instance is fine; backend dispatcher picks any active
terminal for any command.

## Configuration

| Key | Description |
| --- | --- |
| `REGISTER_URL` | Full URL of `/api/v1/sl/terminal/register`. Required. |
| `ESCROW_PAYMENT_URL` | Full URL of `/api/v1/sl/escrow/payment`. Required. |
| `LISTING_FEE_URL` | Full URL of `/api/v1/sl/listing-fee/payment`. Required. |
| `PENALTY_LOOKUP_URL` | Full URL of `/api/v1/sl/penalty-lookup`. Required. |
| `PENALTY_PAYMENT_URL` | Full URL of `/api/v1/sl/penalty-payment`. Required. |
| `PAYOUT_RESULT_URL` | Full URL of `/api/v1/sl/escrow/payout-result`. Required. |
| `SHARED_SECRET` | The shared secret. **Required.** Obtain from `slpa.escrow.terminal-shared-secret`. |
| `TERMINAL_ID` | Optional. Defaults to `(string)llGetKey()`. Use a stable name if you want admin tooling to identify this terminal across restarts. |
| `REGION_NAME` | Optional. Defaults to `llGetRegionName()`. |
| `DEBUG_OWNER_SAY` | Optional. `true`/`false`, default `true`. |

### Rotating the shared secret

1. Update `slpa.escrow.terminal-shared-secret` in the deployment's secret store.
2. Restart the SLPA backend so it picks up the new secret.
3. On every deployed SLPA Terminal: edit the `config` notecard with the new
   `SHARED_SECRET` value. `CHANGED_INVENTORY` auto-resets the script and
   re-registers with the new secret.
4. In-flight `terminal_commands` rows dispatched on the old secret will be
   rejected (terminal returns 403); the dispatcher's existing retry budget
   (4 attempts with 1m/5m/15m backoff) covers the brief rotation window.

## Updating

**Two-place rule for the parcel verifier.** The "Get Parcel Verifier" menu
option `llGiveInventory`s a copy of the parcel verifier from the prim's
contents. When you update `parcel-verifier.lsl`:

1. **Marketplace listing**: republish a new revision with the updated `.lsl`.
2. **Every deployed SLPA Terminal's inventory**: drag-drop the new
   `SLPA Parcel Verifier` object into the prim's contents, replacing the old
   copy. `CHANGED_INVENTORY` auto-resets the SLPA Terminal script
   (which re-registers — the inventory swap doesn't break anything else).

Forgetting place 2 leaves users with a stale verifier from the give-on-touch
menu while Marketplace customers get the new version. Track this in the ops
runbook.

Updating the SLPA Terminal script itself: drag-drop the new `slpa-terminal.lsl`
into the prim's contents → `CHANGED_INVENTORY` auto-resets → re-register.
Updating just the notecard: edit values → `CHANGED_INVENTORY` auto-resets →
re-register.

## Operations

In steady state, with `DEBUG_OWNER_SAY=true`:

- `SLPA Terminal: registered (terminal_id=..., url=...)` — startup confirmation.
- `SLPA Terminal: touch from <name>` — user touched the terminal.
- `SLPA Terminal: menu choice <option> by <name>` — menu selection.
- `SLPA Terminal: payment ok <kind> L$<amount> from <payer>` — successful payment POST.
- `SLPA Terminal: payment retry N/5: <status>` — transient failure, retrying.
- `SLPA Terminal: PAYOUT to <recipient> L$<amount> ok` — successful debit.
- `CRITICAL: payment from <payer> L$<amount> key <tx> not acknowledged after 5 retries` — payment recovery failed; manual reconciliation required.
- `CRITICAL: PERMISSION_DEBIT denied — script halted. Owner must re-grant.` — permissions issue.
- `CRITICAL: registration failed after 5 attempts.` — backend unreachable; investigate.

## Troubleshooting

| Symptom | Likely cause |
| --- | --- |
| Startup says `incomplete config` | One of the required notecard keys is empty. |
| Startup says `notecard 'config' missing or unreadable` | Notecard not in inventory or named something other than exactly `config`. |
| `PERMISSION_DEBIT denied` | Owner declined the permission dialog. Reset the script and accept. |
| `URL_REQUEST_DENIED` | Land doesn't allow scripts to request URLs. Move the prim to a region with permissive land settings. |
| Periodic `register retry N/5` | Backend unreachable or rejecting registration. Check `slpa.sl.trusted-owner-keys` includes this terminal's owner. |
| `payment retry N/5` repeatedly | Backend transient or network issue. Self-recovers in most cases. |
| `CRITICAL: payment from ... not acknowledged` | Backend POST never succeeded after 5 retries. Manual reconciliation required — operator must check `escrow_transactions` ledger and refund or recognize manually. |
| `Get Parcel Verifier` does nothing | The SLPA Parcel Verifier object is missing from the terminal's inventory. Drag-drop it back in. |
| Backend command dispatcher logs 403 | Shared secret mismatch. Update notecard, reset. |
| Terminal stuck `<In Use>` | Lock TTL didn't fire. Reset the script. |

## Limits

- LSL listen cap is 65; the script opens at most 2 listens per touch session
  (menu + auction-id-input or code-entry) and removes them on every exit path.
- HTTP-in URLs change on region restart; re-registration is automatic via
  `changed(CHANGED_REGION_START)`.
- `llTransferLindenDollars` rate limit: 30 payments per 30 seconds per owner per
  region. Phase 1 traffic is well under this; alert if approached.
- 60s touch lock means low-volume kiosks rarely hit contention. For high-volume
  venues, deploy multiple SLPA Terminals — backend's dispatcher picks any
  active one for commands.
- Inflight HTTP-in commands cap at 16 concurrent. New commands beyond that
  return 503; backend retry budget handles.
- Bounded payment retry: 10s / 30s / 90s / 5m / 15m, total ~22 minutes of
  trying. After exhaustion the script logs CRITICAL and stops; daily
  reconciliation job (deferred — see `DEFERRED_WORK.md`) catches missed POSTs.

## Security

- The terminal must be owned by an SLPA service avatar listed in
  `slpa.sl.trusted-owner-keys`. Backend rejects header mismatch.
- The shared secret in the notecard is visible to anyone with edit-rights on
  the prim — keep ownership and modify permissions SLPA-team-only.
- A leaked shared secret means an attacker can call `/payout-result` with
  forged "success" outcomes (debiting the escrow ledger without actually
  paying anyone) — rotate immediately if compromise is suspected.
- HMAC-SHA256 per-request auth is on the deferred list (`DEFERRED_WORK.md`)
  for Phase 2 hardening once the LSL terminal is dogfooded.
- Penalty endpoints (`/penalty-lookup`, `/penalty-payment`) are
  header-trust-only on the backend — no shared secret in body. The script
  still has its shared secret loaded for the other endpoints; it just
  doesn't include it in penalty bodies.
