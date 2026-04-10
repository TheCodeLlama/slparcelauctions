# Phase 5: Escrow System

> **Before starting:** read [CONVENTIONS.md](../CONVENTIONS.md) for project-wide rules (no new migrations, Lombok required, vertical slices, feature-based packages).

_Reference: DESIGN.md Section 4.8_

---

## Goal

Manage the post-auction escrow flow: winner pays L$ via in-world terminal, backend monitors land ownership transfer, then triggers payout to seller minus commission. All L$ flows happen in-world via LSL scripts - the backend tracks state and orchestrates.

---

## What Needs to Happen

### Escrow State Machine

After an auction ends with a winner, the listing moves through:
- ENDED → ESCROW_PENDING (waiting for winner to pay)
- ESCROW_PENDING → ESCROW_FUNDED (payment received from terminal)
- ESCROW_FUNDED → TRANSFER_PENDING (monitoring for land ownership change)
- TRANSFER_PENDING → COMPLETED (ownership confirmed, payout triggered)

With error states:
- DISPUTED (either party raises a dispute)
- EXPIRED (timeouts exceeded)

### Payment Receiving

- `POST /api/v1/sl/escrow/payment` endpoint receives payment confirmation from in-world escrow terminal
- Validates: payer UUID matches auction winner, amount matches winning bid, SL headers valid
- Unexpected payments trigger an automatic refund command
- Marks escrow as FUNDED

### Ownership Monitoring

- After escrow is funded, backend begins polling `world.secondlife.com/place/{parcel_uuid}` every 5 minutes
- Three possible outcomes:
  - Owner changed to winner → transfer confirmed, trigger payout
  - Owner unchanged (still seller) → continue polling
  - Owner changed to unknown third party → freeze escrow, flag as fraud, notify admin
- For bot-verified listings, the bot service (Phase 6) provides additional monitoring at 15-minute intervals

### Payout

- Backend sends payout command to escrow terminal via HTTP-in
- Terminal executes `llTransferLindenDollars` to seller
- Terminal reports transaction result back to backend
- Commission calculated and recorded (5% of sale price, minimum L$50)
- Seller receives sale price minus commission
- If payout fails: retry with backoff, escalate to manual review after 3 failures

### Timeouts

- Winner has 48 hours to pay escrow after auction ends
- Seller has 72 hours to transfer land after escrow is funded
- On timeout: automatic cancellation, L$ refunded to buyer

### Terminal Registration

- `POST /api/v1/sl/terminal/register` endpoint for escrow terminals to register their HTTP-in URLs
- URLs change on region restart - terminals must re-register
- Backend tracks which terminal holds which pending transaction
- Stale URL detection and graceful retry

---

## Acceptance Criteria

- Escrow state transitions work correctly through the full flow (ENDED → COMPLETED)
- Payment endpoint validates payer, amount, and SL headers
- Unexpected payments are flagged for refund
- Ownership polling detects transfer to winner and triggers payout
- Ownership change to unknown party freezes escrow and creates fraud flag
- Timeout expiry triggers automatic cancellation and refund
- Payout command sent to terminal via HTTP-in with proper authentication
- Payout result (success/failure) recorded from terminal callback
- Commission calculated correctly (5%, L$50 minimum)
- Terminal registration and re-registration works
- Full happy path testable end-to-end (with mock terminal responses)

---

## Notes

- The actual in-world escrow terminal script is in Phase 11. For this phase, build the backend orchestration and test with mock HTTP-in responses.
- The payout command to the terminal should include a shared secret for authentication.
- Consider a reconciliation mechanism: sum of pending escrow amounts should match the expected SL account balance.
