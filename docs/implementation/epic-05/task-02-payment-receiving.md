# Task 05-02: Escrow Payment Receiving Endpoint

> **Before starting:** read [CONVENTIONS.md](../CONVENTIONS.md) for project-wide rules (no new migrations, Lombok required, vertical slices, feature-based packages).

## Goal

Build the backend endpoint that receives L$ payment confirmations from in-world escrow terminals and transitions escrow to FUNDED.

## Context

See DESIGN.md Section 4.8 (Step 1) and Section 5.2 (Escrow Terminal). The in-world terminal sends an HTTP request when it receives L$ via the `money()` event. The actual LSL script is Epic 11 - this task builds the backend side.

## What Needs to Happen

- Create endpoint: POST /api/v1/sl/escrow/payment
  - Publicly accessible (no JWT - called from SL scripts)
  - Validates SL headers: X-SecondLife-Shard ("Production"), X-SecondLife-Owner-Key (SLPA service account)
  - Accepts: payer_uuid, amount, auction_id, terminal_id, transaction_key (SL transaction reference)

- Validation logic:
  - Auction exists and is in ESCROW_PENDING status
  - Payer UUID matches the auction winner's SL avatar UUID
  - Amount matches the winning bid exactly
  - Transaction key is unique (prevent duplicate processing)
  - Escrow hasn't expired (still within 48-hour window)

- On valid payment:
  - Transition escrow to ESCROW_FUNDED via EscrowService (Task 05-01)
  - Record: funded_at, payer_uuid, amount, terminal_id, sl_transaction_key
  - Return success response to terminal (terminal displays confirmation to user)

- On invalid payment (wrong amount, wrong payer, wrong auction):
  - Do NOT transition escrow
  - Log the unexpected payment with all details
  - Return error response with refund flag (terminal should refund the L$)
  - Create a fraud_flag if payer doesn't match winner

- Idempotency: if the same transaction_key is received twice, return success without re-processing

## Acceptance Criteria

- Valid payment from correct winner for correct amount transitions escrow to FUNDED
- Wrong payer UUID is rejected with refund flag
- Wrong amount is rejected with refund flag
- Payment on non-ESCROW_PENDING auction is rejected
- Duplicate transaction_key is handled idempotently
- Expired escrow payment is rejected
- All payment attempts (valid and invalid) are logged
- Response format is simple enough for LSL script to parse

## Notes

- The terminal needs to know which auction the payment is for. The user flow: winner visits terminal, enters auction ID (or selects from pending list), terminal sets llSetPayPrice to the winning bid, user pays, terminal POSTs to this endpoint.
- The refund flag in the response tells the terminal to execute llTransferLindenDollars back to the payer. The actual refund is the terminal's responsibility.
- Test with curl by manually setting SL headers and posting mock payment data.
