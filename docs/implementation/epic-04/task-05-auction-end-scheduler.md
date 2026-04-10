# Task 04-05: Auction End Scheduler & Buy-It-Now

> **Before starting:** read [CONVENTIONS.md](../CONVENTIONS.md) for project-wide rules (no new migrations, Lombok required, vertical slices, feature-based packages).

## Goal

Build a reliable server-side scheduler that ends auctions on time and handles buy-it-now instant purchases.

## Context

See DESIGN.md Section 4.8. Auctions end when their `ends_at` timestamp is reached. The scheduler must be reliable - missed endings would be a critical bug. Buy-it-now is an instant end triggered by a bid matching the buy_now_price.

## What Needs to Happen

**Auction End Scheduler:**
- Create a scheduled job that runs frequently (every 10-30 seconds) to check for auctions past their ends_at
- Query: all ACTIVE auctions where ends_at <= now
- For each expired auction:
  - Transition status: ACTIVE → ENDED
  - Determine outcome:
    - If winning bid >= reserve_price (or no reserve): winner determined → status ENDED with winner info
    - If winning bid < reserve_price: no winner → ENDED with "reserve not met" flag
    - If no bids: ENDED with "no bids" flag
  - Record winner_id, winning_bid on the auction record
  - Broadcast AUCTION_ENDED via WebSocket (Task 04-04)
  - Create notification placeholders for seller and winner (actual delivery in Epic 09)

**Buy-It-Now:**
- When a bid is placed (hook into BidService), check if amount >= buy_now_price (if set)
- If yes: immediately end the auction
  - Transition ACTIVE → ENDED
  - Set winner to the buy-now bidder
  - Broadcast AUCTION_ENDED with BUY_NOW event type
  - All pending proxy bids are irrelevant (auction is over)

**Edge cases:**
- Snipe extension: the scheduler must re-check ends_at each cycle (it may have changed since last check)
- Concurrent bid at exact end time: bid should be accepted if auction is still ACTIVE when the bid is processed
- Multiple auctions ending in the same cycle: process all of them

## Acceptance Criteria

- Auctions transition to ENDED within 30 seconds of their ends_at timestamp
- Winner correctly determined based on highest bid vs reserve price
- Reserve not met: auction ends with no winner, appropriate status recorded
- No bids: auction ends with no winner
- Buy-it-now bid immediately ends the auction with the bidder as winner
- AUCTION_ENDED broadcast sent for every ending (scheduled or buy-it-now)
- Scheduler handles snipe-extended auctions correctly (checks current ends_at, not stale)
- Multiple auctions ending simultaneously are all processed

## Notes

- Reliability is critical. A cron/scheduled task approach (Spring's @Scheduled) is fine for MVP. For production scale, consider a more robust job scheduler (Quartz, etc.) but don't over-engineer now.
- The scheduler query should use a database-level timestamp comparison, not in-memory checks.
- After ending, the auction moves into escrow flow (Epic 05). This task just handles the ACTIVE → ENDED transition.
- Log every auction end event for audit purposes.
