# Task 08-03: Cancellation Penalties & Anti-Circumvention

## Goal

Implement escalating penalties for sellers who cancel auctions with active bids, and track cancellation history for admin visibility.

## Context

See DESIGN.md Section 8 (Anti-Circumvention). The `cancellation_log` table exists from Epic 01 migrations. Cancellation logic exists from Epic 03 Task 04 - this task adds the penalty layer on top.

## What Needs to Happen

- **Cancellation penalty logic (hook into existing cancel flow):**
  - When a seller cancels an ACTIVE auction:
    - If no bids: no penalty (listing fee already forfeited)
    - If bids exist: escalating penalties based on cancellation history:
      - 1st offense: warning recorded + listing fee forfeited (already the case)
      - 2nd offense: L$500 penalty (deducted from next payout, or flagged as owed)
      - 3rd offense: 30-day listing suspension (cannot create new listings)
      - 4th+ offense: permanent ban from listing (admin review required to lift)

- **Cancellation log:**
  - Record every cancellation with: auction_id, user_id, reason (required text), had_bids (boolean), penalty_applied, cancelled_at
  - Calculate offense count from cancellation_log where had_bids = true

- **Suspension enforcement:**
  - If user is suspended: block listing creation endpoints with clear error message
  - Store suspension_until on user record
  - Suspension check in listing creation flow (Epic 03 Task 04)

- **Penalty deduction:**
  - Store owed penalties on user record (penalty_balance_owed)
  - On next payout (Epic 05 Task 04): deduct penalty from payout amount
  - If no future payouts: penalty remains as debt (admin can write off or enforce)

- **Ownership change detection on cancelled auctions:**
  - After a seller cancels an auction with bids: monitor parcel ownership for 48 hours
  - If ownership changes within 48 hours → strong signal of off-platform deal
  - Create fraud_flag: CANCEL_AND_SELL pattern
  - This uses the existing ownership monitoring (Epic 03 Task 06) - just extend it to watch cancelled auctions briefly

- **REST endpoints:**
  - GET /api/v1/users/me/cancellation-history - seller's own cancellation log
  - GET /api/v1/admin/users/{id}/cancellations - admin view of user's cancellation history (Epic 10)

## Acceptance Criteria

- First cancellation with bids: warning recorded, listing fee forfeited
- Second cancellation with bids: L$500 penalty flagged
- Third cancellation with bids: 30-day suspension applied
- Suspended users cannot create new listings
- Cancellation without bids: no penalty beyond listing fee
- Cancellation reason required and stored
- Penalty balance deducted from future payouts
- Ownership change within 48 hours of cancellation creates fraud flag
- Cancellation history viewable by seller and admin
- Penalty amounts are configurable (not hardcoded)

## Notes

- The L$500 penalty amount should be in application config, not hardcoded. Same for suspension duration.
- The offense counter resets... never? Or after a long clean period? For MVP, it never resets. Admin can manually adjust.
- The 48-hour post-cancellation monitoring is a light addition to the existing ownership polling job. Just add cancelled-with-bids auctions to the watch list temporarily.
- Edge case: seller cancels, gets suspended, suspension expires, cancels again → should be 4th offense (permanent).
