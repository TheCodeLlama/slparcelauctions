# Task 05-03: Land Transfer Ownership Monitoring

## Goal

Build the escrow-specific ownership polling that detects when the seller transfers land to the winner, triggering payout.

## Context

See DESIGN.md Section 4.8 (Step 3). After escrow is funded, backend polls the World API every 5 minutes to detect ownership change. This is separate from the general listing ownership monitor (Epic 03 Task 06) - this one is specifically for escrow resolution.

## What Needs to Happen

- Create a scheduled job that runs every 5 minutes:
  - Query all escrow_transactions in TRANSFER_PENDING status (ESCROW_FUNDED auto-transitions to TRANSFER_PENDING)
  - For each: call World API `place/{parcel_uuid}` and check `ownerid`

- Handle three ownership outcomes:
  - **Owner == winner UUID**: Transfer confirmed!
    - Transition escrow to COMPLETED state (triggers payout - Task 05-04)
    - Record transfer_confirmed_at timestamp
    - Log the confirmation event
  - **Owner == seller UUID**: Still waiting
    - Update last_checked timestamp
    - If 24+ hours since funded and seller hasn't transferred: create a reminder notification placeholder
  - **Owner == unknown third party**: Fraud!
    - Freeze escrow immediately (set to DISPUTED or a frozen sub-state)
    - Create fraud_flag record with details (old owner, new owner, parcel info)
    - Queue L$ refund to winner
    - Flag seller account for admin review

- Handle edge cases:
  - World API returns 404 (parcel deleted/merged during escrow): freeze, flag, refund
  - World API is down: skip this cycle, retry next. Track consecutive failures.

- Rate limiting: batch requests with delays between parcels (same approach as Epic 03 Task 06)

## Acceptance Criteria

- Ownership change to winner triggers escrow completion within 5 minutes
- Ownership unchanged continues polling without side effects
- Ownership change to unknown party freezes escrow and creates fraud flag
- Deleted parcel (404) freezes escrow and flags
- World API downtime handled gracefully (retries next cycle)
- 24-hour reminder triggered if seller hasn't transferred
- Job can be manually triggered for testing
- Transfer confirmation recorded with timestamp
- Consecutive API failures tracked (don't create fraud flags for API errors)

## Notes

- This job and the general ownership monitor (Epic 03 Task 06) both poll the World API. Consider sharing the World API client and rate limiting logic, but keep the jobs separate - they have different intervals (5 min vs 30 min) and different response handling.
- For Bot-Verified listings, the bot service (Epic 06) provides additional 15-minute checks with richer data (sale status, AuthBuyerID). This World API poll is the baseline for ALL escrow transactions.
- The 72-hour transfer deadline is enforced by the timeout checker in Task 05-01, not this job. This job just detects successful transfers.
