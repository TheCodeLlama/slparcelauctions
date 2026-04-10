# Task 04-02: Proxy Bidding

> **Before starting:** read [CONVENTIONS.md](../CONVENTIONS.md) for project-wide rules (no new migrations, Lombok required, vertical slices, feature-based packages).

## Goal

Implement eBay-style proxy bidding where users set a maximum bid and the system auto-bids the minimum increment above competitors up to that max.

## Context

See DESIGN.md Section 4.7 (Proxy Bidding). Proxy bids are stored in the `proxy_bids` table. The BidService from Task 04-01 handles recording actual bids - this task adds the proxy layer on top.

## What Needs to Happen

- Create proxy bid logic:
  - User sets a max bid amount
  - System places an actual bid at: current_bid + minimum_increment (or starting_bid if first bid)
  - The max amount is stored privately in `proxy_bids`, never shown publicly
  - When a new manual bid comes in, the proxy system auto-responds:
    - If new bid < proxy max: system bids new_bid + increment (up to proxy max)
    - If new bid >= proxy max: proxy bidder is outbid, notified
  - Two competing proxy bidders resolve instantly: higher max wins at (lower max + one increment)
  - If both have the same max: first proxy bidder wins (earlier timestamp)

- Create REST endpoints:
  - POST /api/v1/auctions/{id}/proxy-bid - set proxy max (creates or updates)
  - PUT /api/v1/auctions/{id}/proxy-bid - update max (can only increase, never decrease below current winning)
  - DELETE /api/v1/auctions/{id}/proxy-bid - cancel proxy bid (only if user is not currently winning)
  - GET /api/v1/auctions/{id}/proxy-bid - authenticated, returns user's own proxy bid if one exists (max amount, created_at)

- One proxy bid per user per auction (enforced)

- Integration with BidService:
  - When a manual bid is placed (Task 04-01), check for active proxy bids and auto-respond
  - When a proxy bid is created/updated, immediately resolve against current state
  - Proxy auto-bids should create real bid records (so they show in bid history)

- Proxy bids trigger snipe protection identically to manual bids (Task 04-03)

## Acceptance Criteria

- User can set a proxy max and system places bid at correct starting amount
- When outbid by a manual bid, proxy auto-responds up to max
- Two proxy bidders resolve instantly to correct price (lower max + increment, higher max wins)
- Equal max amounts: first proxy bidder wins
- User can increase their max but cannot decrease below current winning bid
- User can cancel proxy if they are NOT the current winner
- Only the current winning amount is visible publicly, never the proxy max
- GET proxy-bid returns the user's own max (not visible to others)
- Proxy auto-bids create real bid records in bid history
- One proxy bid per user per auction enforced

## Notes

- The proxy resolution logic is the most complex part. Think carefully about the order of operations when a new bid arrives and multiple proxies exist.
- When a proxy bid is placed or updated, it should immediately check: is there already a higher bid? If so, the proxy should auto-bid up to its max right away. If another proxy exists, they should resolve immediately.
- Proxy bids create real bid records so the bid history is accurate and snipe protection can trigger normally.
