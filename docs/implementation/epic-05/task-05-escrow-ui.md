# Task 05-05: Escrow Status UI

> **Before starting:** read [CONVENTIONS.md](../CONVENTIONS.md) for project-wide rules (no new migrations, Lombok required, vertical slices, feature-based packages).

## Goal

Build the frontend escrow tracking interface for both buyers and sellers, showing the current state and next steps at each stage.

## Context

Backend escrow endpoints exist from Tasks 05-01 through 05-04. This page is linked from the auction detail page (after auction ends) and from the dashboard.

## What Needs to Happen

**Escrow status page at `/auctions/[id]/escrow`:**
- Accessible only to the auction's seller and winner (redirect others to auction detail)
- Visual progress tracker showing all escrow states:
  - ESCROW_PENDING → ESCROW_FUNDED → TRANSFER_PENDING → COMPLETED
  - Current step highlighted, completed steps checked, future steps grayed
  - DISPUTED / EXPIRED shown as red state if applicable

- **Step-by-step content (varies by role and current state):**

  **ESCROW_PENDING (winner view):**
  - "Pay L$[amount] at an SLPA Escrow Terminal in Second Life"
  - Instructions for finding/using the terminal
  - Countdown: time remaining before 48-hour deadline
  - Warning as deadline approaches

  **ESCROW_PENDING (seller view):**
  - "Waiting for buyer to pay escrow"
  - Countdown to deadline
  - "What happens if buyer doesn't pay" info

  **ESCROW_FUNDED / TRANSFER_PENDING (seller view):**
  - "Transfer your land to [winner display name]"
  - Step-by-step instructions: About Land → Sell Land → Set buyer → Set price L$0
  - For Bot-Verified: reminder to first remove the Sale-to-Bot setting, then set sale to winner
  - Countdown: time remaining before 72-hour deadline

  **ESCROW_FUNDED / TRANSFER_PENDING (winner view):**
  - "Waiting for seller to transfer land"
  - Countdown to deadline
  - "File a dispute" option if issues arise

  **COMPLETED (both):**
  - "Transaction complete!" with sale details
  - Payout amount shown to seller (after commission)
  - Commission amount shown
  - Link to leave a review (if review system exists)

  **EXPIRED (both):**
  - Explanation of what happened and next steps
  - Refund status for winner

  **DISPUTED (both):**
  - Dispute filed indicator
  - "SLPA staff is reviewing this transaction"
  - Dispute reason displayed

- **File a Dispute button** (available in ESCROW_FUNDED and TRANSFER_PENDING):
  - Opens modal/form: reason category + description text
  - Calls POST /api/v1/auctions/{id}/escrow/dispute

**Dashboard integration:**
- Add escrow status to My Bids and My Listings entries where applicable
- Won auctions show "Pay Escrow" or "Awaiting Transfer" status
- Sold auctions show "Awaiting Payment" or "Transfer Land" status
- Link directly to escrow page from dashboard

## Acceptance Criteria

- Escrow page shows correct state and role-appropriate instructions
- Progress tracker accurately reflects current escrow state
- Countdown timers work for both deadlines (48h payment, 72h transfer)
- Dispute form submits and updates the page state
- COMPLETED state shows final amounts including commission
- EXPIRED state explains what happened
- Dashboard entries link to escrow page and show escrow status
- Page updates when state changes (polling or manual refresh)
- Only seller and winner can access the escrow page
- Works in dark/light mode, responsive on mobile

## Notes

- For Bot-Verified listings in TRANSFER_PENDING: the seller needs specific instructions to first remove the Sale-to-Bot setting before setting the land for sale to the winner. Make these instructions clear and prominent.
- The "time remaining" countdowns should be prominent - missing a deadline has real consequences.
- Consider auto-refreshing the page periodically (every 30 seconds) or adding a "Refresh Status" button, since state changes happen asynchronously.
