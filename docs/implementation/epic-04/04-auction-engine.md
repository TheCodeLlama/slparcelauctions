# Phase 4: Auction Engine

_Reference: DESIGN.md Sections 4.7, 4.5 (snipe protection fields)_

---

## Goal

Real-time bidding system with proxy bidding and snipe protection. Users can bid on active listings and receive live updates via WebSocket.

---

## What Needs to Happen

### Manual Bidding

- Verified users can place bids on ACTIVE auctions
- Bid validation: must exceed current highest bid by the minimum increment (see increment table in DESIGN.md)
- Seller cannot bid on their own auction
- Bid recorded with timestamp and IP address
- Previous high bidder notified of being outbid

### Proxy Bidding

- User sets a maximum bid amount; system auto-bids the minimum increment above competitors up to the max
- Only the current winning amount is visible publicly, not the proxy max
- Two proxy bidders resolve instantly (higher max wins at lower max + one increment)
- User can increase their max at any time but cannot decrease below current winning bid
- One proxy bid per user per auction (can update or cancel)
- Proxy bids trigger snipe protection the same as manual bids

### Snipe Protection

- Seller opt-in during listing creation (already in Phase 3 fields)
- Configurable extension window: 5, 10, 15, 30, or 60 minutes
- Any bid placed within the extension window before auction end extends the auction by that amount
- Extensions stack - each qualifying bid resets the timer
- Track `original_ends_at` separately from `ends_at`

### Real-Time Updates (WebSocket)

- Live bid updates broadcast to all viewers of a listing
- Current bid amount, bid count, time remaining updated in real-time
- Snipe extension events broadcast (timer reset visible to all viewers)
- Connection management: reconnection, subscription per auction

### Auction End

- Auction transitions from ACTIVE to ENDED when timer expires
- If winning bid meets reserve price → winner determined, both parties notified
- If winning bid below reserve → auction ends with no winner, seller notified
- Buy-it-now: if a bid matches the buy-now price, auction ends immediately

### Dashboard Integration

- "My Bids" view: all auctions user has bid on, with status (winning, outbid, ended)
- "My Listings" view: all user's listings with bid counts and current prices

---

## Acceptance Criteria

- Bids validate correctly (increment rules, seller exclusion, auction active)
- Proxy bidding auto-increments correctly against manual and other proxy bids
- Two competing proxy bids resolve to the correct winner at the correct price
- Snipe protection extends the auction when bids land in the window
- Stacked snipe extensions work (multiple extensions in a row)
- WebSocket broadcasts bid updates to all connected clients viewing that auction
- Auction ends correctly at the scheduled time (or on buy-it-now)
- Reserve price logic works (auction ends with no winner if reserve not met)
- Dashboard shows accurate bid/listing status for the logged-in user

---

## Notes

- Auction end timing should be reliable - consider a scheduler or polling approach rather than relying solely on client-side timers.
- WebSocket messages should include enough data for the frontend to update without re-fetching (current bid, bid count, new end time if extended).
