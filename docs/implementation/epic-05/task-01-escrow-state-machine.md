# Task 05-01: Escrow State Machine & Transactions

> **Before starting:** read [CONVENTIONS.md](../CONVENTIONS.md) for project-wide rules (no new migrations, Lombok required, vertical slices, feature-based packages).

## Goal

Build the backend escrow state machine that tracks post-auction payment, land transfer, and payout. This is the core orchestration layer - all other escrow tasks plug into it.

## Context

See DESIGN.md Section 4.8. After an auction ends with a winner (Epic 04 Task 05), the escrow flow begins. The `escrow_transactions` table exists from Epic 01 migrations.

## What Needs to Happen

- Create an EscrowService that manages state transitions:
  - ENDED → ESCROW_PENDING: triggered by auction end scheduler (Epic 04), creates escrow_transaction record, sets payment_deadline (now + 48 hours)
  - ESCROW_PENDING → ESCROW_FUNDED: triggered by payment receipt (Task 05-02)
  - ESCROW_FUNDED → TRANSFER_PENDING: triggered immediately after funded, sets transfer_deadline (now + 72 hours), begins ownership monitoring
  - TRANSFER_PENDING → COMPLETED: triggered by ownership transfer confirmation (Task 05-03)
  - Any state → DISPUTED: triggered by either party filing a dispute
  - ESCROW_PENDING → EXPIRED: payment deadline exceeded
  - TRANSFER_PENDING → EXPIRED: transfer deadline exceeded

- Enforce transition rules (no skipping states, no backwards transitions except dispute)

- Create REST endpoints:
  - GET /api/v1/auctions/{id}/escrow - authenticated (seller or winner only), returns escrow status, deadlines, timeline of events
  - POST /api/v1/auctions/{id}/escrow/dispute - authenticated (seller or winner), creates dispute with reason text

- Create a timeout checker (scheduled job, runs every 5 minutes):
  - ESCROW_PENDING past 48-hour deadline → mark EXPIRED, queue refund (no L$ to refund yet, just status)
  - TRANSFER_PENDING past 72-hour deadline → mark EXPIRED, queue L$ refund to winner

- Record all state transitions with timestamps in the escrow_transaction record (funded_at, transfer_confirmed_at, payout_at, expired_at, disputed_at)

- Calculate commission on funded: sale_price * 0.05, minimum L$50. Store commission_amt and payout_amt (sale_price - commission) on escrow record.

## Acceptance Criteria

- Escrow record created automatically when auction ends with a winner
- State transitions follow the correct sequence and are enforced
- Invalid transitions are rejected
- Deadlines calculated correctly (48h payment, 72h transfer)
- Timeout checker catches expired escrows and updates status
- Dispute can be filed from ESCROW_FUNDED or TRANSFER_PENDING
- Commission calculated correctly (5%, L$50 min)
- Escrow status endpoint returns full timeline for authorized users only
- Seller/winner who is not party to the auction cannot view escrow details

## Notes

- The actual L$ refund mechanism is Task 05-04. This task just marks the status and queues the intent.
- Dispute resolution is manual (admin reviews). For now, just record the dispute and change status. Admin tools are Epic 10.
- Hook into the auction end scheduler (Epic 04 Task 05) to auto-create escrow records.
