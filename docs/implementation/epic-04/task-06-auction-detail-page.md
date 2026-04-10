# Task 04-06: Auction Detail Page

## Goal

Build the public auction detail page where users view listing info, live bid status, and place bids.

## Context

See DESIGN.md Section 4.6 (Listing page shows) and 4.7 (Bidding flow). The Stitch-generated design is in `docs/stitch-generated-design/auction_detail/`. Backend endpoints exist from Tasks 04-01 through 04-05.

## What Needs to Happen

**Auction detail page at `/auctions/[id]`:**

- **Parcel info section:**
  - Parcel snapshot image (large, hero-style)
  - Parcel name, region, area (sqm), maturity rating badge
  - Verification tier badge (🔵 Script / 🟢 Bot / 🟡 Human)
  - Seller description (rich text)
  - Parcel tags as pill badges
  - Additional seller-uploaded photos (gallery/lightbox)
  - "Visit in Second Life" button with two options: Open in Viewer (secondlife:/// protocol), View on Map (maps.secondlife.com SLURL)

- **Bidding section (sidebar or prominent panel):**
  - Current bid amount (large, prominent)
  - Bid count
  - Live countdown timer (synced to server time)
  - Snipe protection badge (🛡️ with window duration if enabled)
  - Reserve status indicator ("Reserve not met" orange / "Reserve met ✓" green / no badge)
  - Starting bid (if no bids yet)
  - Buy-it-now price (if set, with "Buy Now" button)
  - **Bid input:** L$ amount field + "Place Bid" button
    - Show minimum bid amount (current + increment)
    - Validate before submission
  - **Proxy bid section:** "Set Max Bid" with separate input + explanation
    - Show "You have a proxy bid set at L$X" if active
    - "Update Max" and "Cancel Proxy" buttons
  - Login/register prompt if not authenticated

- **Bid history section:**
  - List of bids (bidder display name, amount, timestamp)
  - Snipe extension events visible in history
  - Paginated or scrollable

- **Seller info section:**
  - Seller profile card (avatar, display name, rating stars, completed sales count)
  - Link to full profile (`/users/{id}`)
  - "New Seller" badge if < 3 completed sales

- **Real-time updates (WebSocket):**
  - Subscribe to `/topic/auction/{id}` on page load
  - Update current bid, count, timer on NEW_BID events
  - Update countdown on SNIPE_EXTENSION events
  - Show "Auction Ended" state on AUCTION_ENDED event
  - Visual feedback on new bids (brief highlight/animation)

- **Ended auction state:**
  - Show final price, winner display name
  - Hide bid input
  - Show "Reserve not met" or "Sold" status
  - Link to escrow status (for winner/seller only)

## Acceptance Criteria

- Auction page loads with all parcel info, seller info, and current bid status
- Countdown timer is accurate and updates in real-time
- Placing a bid updates the page immediately via WebSocket (no refresh needed)
- Proxy bid can be set, updated, and cancelled from the page
- Minimum bid amount displayed and enforced on the form
- Buy-it-now button works and ends auction immediately
- Bid history shows all bids chronologically
- Snipe protection badge visible when enabled
- Reserve status indicator updates as bids cross reserve threshold
- "Visit in Second Life" links work correctly
- Seller profile card links to full profile
- Ended auctions show final state (no bid input)
- Page matches Gilded Slate aesthetic, works in dark/light mode
- Responsive on mobile (bid section prominent, scrollable details)
- Unauthenticated users can view but see login prompt instead of bid input

## Notes

- Reference `docs/stitch-generated-design/auction_detail/` for layout cues.
- The countdown timer should use server time (from WebSocket messages) to stay accurate, not just client clock.
- For mobile: the bid input should be sticky or easily accessible without scrolling through all the details.
- Bid input validation: show the minimum next bid amount as a placeholder or helper text.
