# Task 11-04: SL IM Dispatcher Script

## Goal

Add SL instant message delivery capability to the escrow terminal (or a separate object) - polls the backend for pending IM messages and delivers them via `llInstantMessage`.

## Context

See DESIGN.md Section 11 and Epic 09 Task 03. The backend queues SL IM notifications. This script polls for pending messages and delivers them. Can be added to the escrow terminal script or a separate companion object.

## What Needs to Happen

- **Polling loop:**
  - Timer event: every 60 seconds, poll backend for pending SL IM messages
  - GET /api/v1/internal/sl-im/pending (via `llHTTPRequest`)
  - Authenticate with shared secret in header
  - Backend returns batch of messages (up to 10): recipient_uuid, message_text, message_id

- **Message delivery:**
  - For each pending message: `llInstantMessage(recipient_uuid, message_text)`
  - After sending: POST confirmation to backend: /api/v1/internal/sl-im/{id}/delivered
  - If `llInstantMessage` fails (avatar doesn't exist): POST failure to backend

- **Rate limiting:**
  - `llInstantMessage` has a 2-second forced delay (SL enforces this)
  - Process batch sequentially with delay between each
  - 10 messages per batch × 2 seconds = ~20 seconds processing time per poll cycle
  - With 60-second poll interval: up to 10 messages per minute

- **Integration decision:**
  - Option A: Add to escrow terminal script (saves an object, but adds complexity to an already complex script)
  - Option B: Separate companion object next to the terminal (cleaner separation, easier to debug)
  - Recommend Option B for maintainability

- **HTTP-in for immediate delivery (optional enhancement):**
  - Instead of polling only: also accept incoming HTTP-in pushes from backend for urgent messages
  - Backend can push high-priority messages (auction won, escrow events) for near-instant delivery
  - Lower priority messages still use polling

- **Error handling:**
  - Backend unreachable: skip this poll cycle, try again next interval
  - Empty response (no pending messages): do nothing, wait for next poll
  - Malformed response: log via `llOwnerSay`, skip cycle

## Acceptance Criteria

- Script polls backend every 60 seconds for pending messages
- Messages delivered via `llInstantMessage` to correct recipients
- Delivery confirmations sent back to backend
- 2-second delay between messages respected
- Backend unreachable handled gracefully (no crash, retry next cycle)
- Shared secret validated
- Works alongside escrow terminal (whether same script or companion object)

## Notes

- `llInstantMessage` works even if the recipient is offline - SL stores it for delivery when they log in.
- The 2-second delay is a hard SL limit on `llInstantMessage`. Cannot be bypassed.
- If this is a separate object: it needs its own script but can share the same parcel as the escrow terminal.
- Message text from backend should already be formatted (plain text, under 1024 chars). The script just delivers it.
- For MVP: polling only is fine. HTTP-in push is a nice-to-have if the script isn't already too complex.
- Consider: if the IM dispatcher and escrow terminal are separate objects, they can be on different parcels or even different regions for redundancy.
