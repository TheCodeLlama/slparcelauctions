# Task 11-02: Escrow Payment Terminal LSL Script

## Goal

Build the in-world escrow terminal that receives L$ payments from auction winners and sends payouts to sellers on backend command.

## Context

See DESIGN.md Section 5.2. This is the most complex LSL script - it handles money in both directions and maintains an HTTP-in URL for backend commands. Backend endpoints exist from Epic 05 Tasks 02 and 04.

## What Needs to Happen

- **Initialization:**
  - On `state_entry`: request `PERMISSION_DEBIT` from owner (one-time, persists until script reset)
  - `run_time_permissions` event: confirm debit permission granted
  - Register HTTP-in URL via `llRequestURL()` (or `llRequestSecureURL()`)
  - `http_request` event with `URL_REQUEST_GRANTED`: store the URL, POST it to backend registration endpoint
  - Floating text: "SLPA Escrow Terminal\nPay to complete your auction"

- **Receiving payments (winner pays escrow):**
  - `money(key payer, integer amount)` event fires when someone pays the object
  - POST to backend: payer UUID, amount, object key, timestamp
  - Backend responds with: accepted (correct winner, correct amount) or rejected (wrong person, wrong amount, no pending escrow)
  - On accepted: `llRegionSayTo(payer, 0, "✓ Payment of L$X received. Escrow funded for [auction].")`
  - On rejected: `llRegionSayTo(payer, 0, "Payment not expected. Contact support for a refund.")`
  - Unexpected payments (no matching escrow): POST to backend, which logs it for manual refund

- **Sending payouts (backend commands via HTTP-in):**
  - Backend POSTs to the terminal's HTTP-in URL with commands
  - Command: PAYOUT - send L$ to a specific avatar
    - Payload: recipient_uuid, amount, transaction_reference, shared_secret
  - Validate shared secret before executing
  - Execute: `llTransferLindenDollars(transaction_ref, recipient_uuid, amount)`
  - `transaction_result(key id, integer success, string data)` event fires with result
  - POST result back to backend callback: transaction_ref, success/failure, data

- **Pay price configuration:**
  - Backend can send SET_PAY_PRICE command via HTTP-in
  - Execute: `llSetPayPrice(default_amount, [button1, button2, button3, button4])`
  - This sets the suggested amounts in the pay dialog for the next expected payment

- **HTTP-in URL re-registration:**
  - `changed(integer change)` event: if `change & CHANGED_REGION_START` → re-register URL
  - On new URL: POST updated URL to backend registration endpoint
  - Backend must handle URL changes (terminal_url field updated)

- **Security:**
  - Validate incoming HTTP-in requests: check shared secret in request body/header
  - Reject requests without valid secret
  - Only process commands from backend
  - Validate shard is Production

- **Edge cases:**
  - Region restart: URL changes, re-register
  - Script reset: re-request permissions, re-register URL
  - Payment during payout: both can happen independently (money event and http_request are separate)
  - llTransferLindenDollars failure: report failure to backend, backend handles retry
  - Rate limit: 30 payments per 30 seconds per region per owner (unlikely to hit, but log if approached)

## Acceptance Criteria

- Terminal receives L$ payments and reports to backend correctly
- Backend can command payouts via HTTP-in and receive transaction results
- Shared secret validated on all incoming HTTP-in commands
- HTTP-in URL re-registers after region restart
- URL changes reported to backend immediately
- PERMISSION_DEBIT granted and maintained
- Pay price buttons configurable from backend
- Unexpected payments reported to backend for manual handling
- Success/failure communicated to payer via local chat
- Production shard check enforced

## Notes

- `PERMISSION_DEBIT` cannot be revoked programmatically once granted. Only script reset or deletion revokes it. This is fine - the terminal is owned by the SLPA service account.
- `llTransferLindenDollars` can ONLY pay avatars, not objects. It returns a key that matches the `transaction_result` event's key parameter.
- `llSetPayPrice(PAY_HIDE, [amount, PAY_HIDE, PAY_HIDE, PAY_HIDE])` shows only one button. Use PAY_HIDE to hide unused buttons.
- The shared secret should be a long random string set as a constant in the script. Rotate by updating the script.
- This script will be larger than the verification terminal. Watch the 64KB limit. Keep string operations efficient.
- The terminal needs enough L$ balance in the owning account to cover payouts. The backend should track this and alert if low.
