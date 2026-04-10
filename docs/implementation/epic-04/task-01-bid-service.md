# Task 04-01: Bid Service & Validation

> **Before starting:** read [CONVENTIONS.md](../CONVENTIONS.md) for project-wide rules (no new migrations, Lombok required, vertical slices, feature-based packages).

## Goal

Build the core bid placement service with all validation rules and bid increment logic.

## Context

See DESIGN.md Section 4.7. Users must be verified to bid. Bids must follow increment rules based on current price tiers.

## What Needs to Happen

- Create a BidService with bid placement logic:
  - Validate the user is verified (SL avatar linked)
  - Validate the user is NOT the seller
  - Validate the auction is ACTIVE and not ended
  - Validate bid amount exceeds current highest bid + minimum increment
  - Record bid with user_id, auction_id, amount, timestamp, IP address
  - Update auction's current_bid and current_bidder_id

- Implement bid increment table:
  - L$0 – L$999: minimum increment L$50
  - L$1,000 – L$9,999: minimum increment L$100
  - L$10,000 – L$99,999: minimum increment L$500
  - L$100,000+: minimum increment L$1,000

- Create REST endpoint: POST /api/v1/auctions/{id}/bids
  - Authenticated (JWT required)
  - Accepts: amount (L$)
  - Returns: bid confirmation with new current bid, bid count, time remaining
  - Returns proper errors: 400 (bid too low), 403 (not verified, is seller), 409 (auction ended/not active)

- Create read endpoint: GET /api/v1/auctions/{id}/bids
  - Public, returns bid history for an auction (bidder display name, amount, timestamp)
  - Paginated

- Handle concurrency:
  - Two bids arriving simultaneously should not both succeed if they'd create invalid state
  - Use optimistic locking or database-level constraints to prevent race conditions

## Acceptance Criteria

- Valid bid from verified user is accepted and recorded
- Bid below minimum increment is rejected with clear error
- Seller cannot bid on own auction
- Unverified user cannot bid
- Bid on non-ACTIVE auction is rejected
- Concurrent bids are handled safely (no double-wins, no invalid state)
- Bid history endpoint returns bids in chronological order
- Current bid and bidder updated on the auction record
- Increment table applied correctly at each price tier boundary

## Notes

- This task handles manual bids only. Proxy bidding is Task 04-02.
- Notification of outbid users is deferred to Epic 09. For now, just record the bid correctly.
- IP address is stored for anti-fraud purposes (Epic 10 uses it).
- Consider using SELECT FOR UPDATE or optimistic locking for the bid placement transaction.
