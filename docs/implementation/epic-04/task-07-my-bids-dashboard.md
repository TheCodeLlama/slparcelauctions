# Task 04-07: My Bids Dashboard & Listing Bid Counts

> **Before starting:** read [CONVENTIONS.md](../CONVENTIONS.md) for project-wide rules (no new migrations, Lombok required, vertical slices, feature-based packages).

## Goal

Wire up the "My Bids" dashboard tab and update the "My Listings" tab with live bid data.

## Context

Dashboard tab structure exists from Epic 02 Task 04 (with placeholder content). Bid and auction data is available from Tasks 04-01 through 04-05.

## What Needs to Happen

**My Bids tab (`/dashboard?tab=bids`):**
- List all auctions the user has bid on, ordered by most recent activity
- Each entry shows:
  - Parcel snapshot thumbnail, name, region
  - User's highest bid
  - Current winning bid
  - Bid status badge: "Winning" (green), "Outbid" (red), "Won" (gold), "Lost" (gray), "Reserve Not Met" (orange)
  - Time remaining (live countdown for active auctions)
  - Active proxy bid indicator (if user has a proxy set, show max amount)
  - Link to auction detail page
- Filter by status: Active / Won / Lost / All
- Empty state: "You haven't placed any bids yet. Browse auctions to get started." with link to /browse

**My Listings tab update:**
- Enhance the existing My Listings entries (from Epic 03 Task 05) with bid data:
  - Current bid amount and bid count
  - Time remaining for active listings
  - Winner info for ended listings

**Backend endpoints:**
- GET /api/v1/users/me/bids - returns auctions user has bid on with bid status
  - Include: auction summary, user's highest bid, current bid, status, proxy info
  - Filterable by status (active/won/lost)
  - Paginated

## Acceptance Criteria

- My Bids tab shows all auctions the user has bid on
- Status badges correctly reflect winning/outbid/won/lost states
- Active auctions show live countdown timers
- Proxy bid indicator visible when user has an active proxy
- Filters work (Active / Won / Lost / All)
- Empty state shown when user has no bids
- My Listings tab shows current bid and bid count per listing
- Clicking an entry navigates to the auction detail page
- Paginated for users with many bids
- Works in dark/light mode, responsive on mobile

## Notes

- The "Winning" vs "Outbid" status should update if the user navigates to the page after being outbid - it's based on current state, not cached.
- For active auctions, consider using WebSocket to update status in real-time even on the dashboard (optional, nice-to-have - polling on page load is fine for MVP).
- This is the last task in Epic 04. After this, the full bidding loop is testable end-to-end: create listing → bid → watch real-time → auction ends → see results.
