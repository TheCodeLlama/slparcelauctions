# SLParcels Terminal — Pay to group (sister script)

Sister script to [`slpa-terminal/`](../slpa-terminal/). Owns the "Pay to
group" flow on the same prim: the typed-name text-box, the pending
group-deposit slot, the `/sl/wallet/group-deposit` POST + retry chain +
refund-on-error.

The wallet and group-pay logic used to live together in
`slpa-terminal.lsl`, but the combined feature set tripped Stack-Heap
Collision on real terminals. Each LSL script in a prim gets its own
64KB heap; splitting into two cooperating scripts doubles the budget
and keeps each script's footprint manageable. Both scripts go in the
SAME prim and share the same `config` notecard.

## Architecture summary

- **Coordination** is via `llMessageLinked` between the two scripts in
  the same prim. Fixed integer protocol codes:
  - `10 PING` — wallet → group at wallet's `state_entry`. "Are you here?"
  - `11 PONG` — group → wallet response. Wallet uses this to decide
    whether to show the "Pay to group" button.
  - `12 START` — wallet → group. "Avatar X just tapped Pay to group."
  - `13 CLAIM` — group → wallet. "Avatar X has typed a group name;
    skip your personal-deposit POST when their next `money()` event
    fires."
  - `14 RELEASE` — group → wallet. "Drop the claim on avatar X."

- **money() fires in BOTH scripts** when an avatar pays the prim. The
  wallet script (running independently) checks its claimed-avatars list
  on every `money()` event; if the payer is in it, wallet skips its
  `/sl/wallet/deposit` POST and lets this script POST
  `/sl/wallet/group-deposit` instead. Single ledger credit per pay.

- **Flow:** touch the prim → wallet's dialog shows
  `[Deposit, Pay to group, Withdraw]` (the third button only appears
  when this script PONGed at startup) → user picks "Pay to group" →
  wallet `llMessageLinked`s START to this script → this script opens
  `llTextBox` "Type the realty group's name" on its own channel → user
  types a name → this script stores the slot + CLAIMs the avatar with
  wallet → user has 60 seconds to right-click → Pay → `money()` fires
  in BOTH scripts → wallet skips, this script POSTs
  `/sl/wallet/group-deposit` with `groupName`.

- **Refund discipline** (per [CLAUDE.md](../../CLAUDE.md) "always refund
  on deposit error"): L$ is in the script's hands by the time `money()`
  fires. Every post-auth failure — `REFUND`/`UNKNOWN_GROUP` (typo),
  permission revoked, group dissolved, suspended, frozen depositor,
  unparseable response, retry exhaustion, expired slot — bounces the
  L$ back via `llTransferLindenDollars`.

- **Retry chain** mirrors the wallet's personal-deposit chain: 10s /
  30s / 90s / 5m / 15m. Idempotent on `slTransactionKey`. After the
  five-try chain exhausts, the script refunds and logs CRITICAL for
  ops reconciliation.

- **No HTTP-in.** This script doesn't register a URL with the backend;
  the wallet script's HTTP-in URL is the prim's single dispatch
  endpoint. This script only makes outbound `llHTTPRequest` calls.

- **PERMISSION_DEBIT** is per-script; this script requests its own
  grant at `state_entry`. On first reset after adding this script to a
  prim, accept a SECOND debit dialog (the wallet's grant alone doesn't
  let this script `llTransferLindenDollars`).

## Deployment

1. **Wallet script must be present first.** This script is a sister to
   `slpa-terminal.lsl` and assumes the same prim already runs the
   wallet script (config notecard, terminal registration, etc.).
2. Drop `slpa-terminal-group.lsl` into the prim's contents. The script
   auto-resets via `CHANGED_INVENTORY`.
3. Accept the **PERMISSION_DEBIT** dialog when prompted (separate grant
   from the wallet script's).
4. Confirm on owner-say:
   - `SLParcels Group Pay: config loaded.`
   - The wallet script's startup ping will trigger a PONG; the touch
     menu now includes "Pay to group".
5. Smoke-test:
   - Touch → Pay to group → type a real group's display name → wait
     for "You have 60 seconds..." chat → right-click → Pay → L$10.
     Confirm `ok L$10 to '<group name>'` owner-say and the
     `MEMBER_DEPOSIT` row on the group's wallet ledger.
   - Touch → Pay to group → type a non-existent name → Pay → L$10.
     Confirm `refunded (UNKNOWN_GROUP) L$10` and the L$ comes back.
   - Touch → Pay to group → type a name → wait > 65 seconds → Pay →
     L$10. Confirm the slot-expired refund path fires.

## Configuration

Reads the SAME `config` notecard as the wallet script. Recognised keys:

| Key | Description |
| --- | --- |
| `GROUP_DEPOSIT_URL` | Full URL of `/api/v1/sl/wallet/group-deposit`. **Required** for this script to do anything useful. |
| `SHARED_SECRET` | Same secret as the wallet script's. **Required.** |
| `TERMINAL_ID` | Optional; defaults to `(string)llGetKey()`. Same value the wallet script uses. |
| `REGION_NAME` | Optional; defaults to `llGetRegionName()`. |
| `DEBUG_MODE` | Optional; `true`/`false`. Default `true`. Controls per-event owner-say. |

All other keys (REGISTER_URL, DEPOSIT_URL, etc.) are silently ignored —
those belong to the wallet script.

## Operations

In steady state with `DEBUG_MODE=true`:

- `SLParcels Group Pay: config loaded.` — startup confirmation.
- `SLParcels Group Pay: ok L$<N> to '<group name>'` — successful
  deposit.
- `SLParcels Group Pay: refunded (<REASON>) L$<N> to <payer>` — backend
  returned REFUND; L$ bounced.
- `SLParcels Group Pay: refunded on ERROR (<REASON>) L$<N> to <payer>` —
  backend returned ERROR; L$ bounced defensively (the
  always-refund-on-deposit-error rule).
- `SLParcels Group Pay: retry N/5: status=<code>` — transient backend
  error; retrying on the same `slTransactionKey`.
- `SLParcels Group Pay: slot expired before pay, refunded L$<N> to
  <payer>` — payer paid more than 60s after typing the group name; L$
  bounced rather than crediting their personal wallet (wallet skipped
  due to a stale CLAIM).
- `CRITICAL: SLParcels Group Pay: deposit ... not acknowledged after 5
  retries; refunded payer` — backend POST never succeeded after five
  retries; bounced L$ and logged for ops reconciliation.
- `CRITICAL: SLParcels Group Pay: PERMISSION_DEBIT denied.` — owner
  declined the debit dialog. Refunds-on-error won't work. Reset and
  accept.
- `CRITICAL: SLParcels Group Pay: incomplete config notecard.` — one
  of GROUP_DEPOSIT_URL / SHARED_SECRET / TERMINAL_ID is missing or
  empty.

## Troubleshooting

| Symptom | Likely cause |
| --- | --- |
| Touch menu doesn't show "Pay to group" | This script not present in the prim, or its `state_entry` halted before sending PONG. Check owner-say for `incomplete config notecard` or `PERMISSION_DEBIT denied`. |
| "Pay to group" button does nothing | Wallet sent START but this script's `link_message` handler didn't fire — either the script is halted or PERMISSION_DEBIT was denied. Reset and re-accept the debit dialog. |
| Pay L$ went to the payer's personal wallet, not the group | The 60-second slot expired before they paid, OR the avatar never typed a name (text-box was cancelled). The wallet's claim was released, so personal deposit took over. |
| `REFUND/UNKNOWN_GROUP` on a name that should exist | Backend's case-insensitive match against active groups returned empty. The group is dissolved, suspended, or the name contains a typo (check whitespace and Unicode characters). |
| Both scripts log "deposit ok" for the same pay | Coordination bug — CLAIM didn't propagate. Reset both scripts; check timing of the wallet's startup PING. |

## Limits

- **One concurrent group-deposit per terminal.** A second `money()`
  arriving while the first is mid-retry chain refunds immediately. In
  practice deposit POSTs are sub-second when the backend is healthy, so
  this only matters during a backend outage.
- **Single-slot text-box state.** A second avatar starting "Pay to
  group" clobbers the first's pending text-box; the first's typed
  response (if any) is silently ignored.
- **`llTransferLindenDollars` rate limit** is shared with the wallet
  script (30 payments per 30s per owner per region). Refund-heavy
  failure modes could approach the cap during an outage.

## Security

- Holds the same shared secret as the wallet script (read from the same
  notecard). Anyone with edit-rights on the prim can read both.
- The `groupName` field is user-typed and sent verbatim to the backend;
  the backend does the case-insensitive match against active group
  names. Validation happens server-side, so a malformed name just
  results in a REFUND.
- HMAC-SHA256 per-request auth is on the deferred list for Phase 2
  hardening once the LSL terminal is dogfooded — same posture as the
  wallet script.
