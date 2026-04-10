# Task 05-04: Payout & Refund Commands

> **Before starting:** read [CONVENTIONS.md](../CONVENTIONS.md) for project-wide rules (no new migrations, Lombok required, vertical slices, feature-based packages).

## Goal

Build the backend logic for sending payout and refund commands to in-world escrow terminals via HTTP-in, and tracking transaction results.

## Context

See DESIGN.md Section 4.8 (Step 4) and Section 5.2 (Escrow Terminal). The backend sends HTTP requests to the terminal's registered HTTP-in URL. The terminal executes `llTransferLindenDollars()` and reports the result back.

## What Needs to Happen

**Payout flow (after successful transfer):**
- When escrow reaches COMPLETED (ownership confirmed):
  - Calculate payout: winning_bid - commission_amt
  - Send HTTP POST to the escrow terminal's registered HTTP-in URL:
    - Action: PAYOUT
    - Recipient UUID: seller's SL avatar UUID
    - Amount: payout amount (L$)
    - Escrow ID: for tracking
    - Shared secret: for terminal authentication
  - Terminal executes llTransferLindenDollars and reports back
- Create callback endpoint: POST /api/v1/sl/escrow/payout-result
  - Accepts: escrow_id, success (boolean), transaction_key, error_message
  - Validates SL headers
  - On success: record payout_at, payout_txn_key, mark escrow fully complete
  - On failure: log error, retry (see retry logic below)

**Refund flow (timeout or fraud):**
- When escrow expires or is frozen:
  - Send HTTP POST to terminal's HTTP-in URL:
    - Action: REFUND
    - Recipient UUID: winner's SL avatar UUID
    - Amount: original payment amount
    - Escrow ID: for tracking
    - Shared secret
  - Same callback endpoint handles refund results
  - Record refund_at, refund_txn_key

**Retry logic:**
- If payout/refund fails: retry up to 3 times with exponential backoff (1 min, 5 min, 15 min)
- If terminal URL is stale (connection refused/timeout): check for re-registered URL, retry with new URL
- After 3 failures: escalate to manual review (admin notification)

**Terminal URL management:**
- Terminals register their HTTP-in URLs via POST /api/v1/sl/terminal/register (already defined in epic prompt)
- Track: terminal_id, http_in_url, registered_at, last_seen_at
- URLs change on SL region restart - terminals must re-register
- When sending commands, use the most recently registered URL for the assigned terminal
- If no terminal URL available: queue the command, process when a terminal re-registers

## Acceptance Criteria

- Successful transfer triggers payout command to terminal
- Payout callback records success with transaction key
- Expired escrow triggers refund command to terminal
- Refund callback records success with transaction key
- Failed payout retries up to 3 times with backoff
- Stale terminal URL detected and retried with fresh URL
- After 3 failures, manual review flagged
- Terminal registration endpoint accepts and stores HTTP-in URLs
- Terminal re-registration updates the URL (not duplicates)
- Queued commands processed when terminal comes back online
- Shared secret validated on both outgoing commands and incoming callbacks
- Full happy path testable with mock terminal responses

## Notes

- The shared secret should be configurable and rotated periodically. Start with a static configured secret for MVP.
- The in-world terminal script (Epic 11) handles the actual L$ transfer. This task just sends the instruction and processes the result.
- llTransferLindenDollars has a rate limit of 30 transfers per 30 seconds per region per owner. The backend should respect this when batching payouts.
- For testing without a real terminal: create a mock endpoint that simulates terminal behavior (receives command, returns success callback after a delay).
