# Phase 7: Browse & Search

_Reference: DESIGN.md Section 4.6_

---

## Goal

Public-facing listing discovery. Users can browse, filter, sort, and search active auctions. Listing detail pages show all parcel information, live bid status, and SLURL links.

---

## What Needs to Happen

### Browse / Search Page

- Filterable listing grid showing all ACTIVE auctions
- Filters: region/location, parcel size range, price range, maturity rating, parcel tags (multi-select), reserve status, auction status (active, ending soon), snipe protection (yes/no), verification tier (Script/Bot/Human)
- Sort options: newest, ending soonest, most bids, lowest price, largest area, nearest
- Distance search: user enters a region name → resolve to grid coordinates → find parcels within N regions, sorted by distance
- Listing cards show: snapshot, name, region, current bid, bid count, time remaining, size, maturity badge, verification badge, snipe protection badge, reserve status indicator, parcel tags

### Listing Detail Page

- Full parcel metadata + seller description
- Live current bid + bid count (WebSocket-driven)
- Countdown timer (real-time, reflects snipe extensions)
- Bid history
- "Visit in Second Life" button with two options: Open in Viewer (secondlife:/// protocol) and View on Map (maps.secondlife.com SLURL)
- Seller profile summary (name, rating, completion rate, member since)
- Parcel layout map placeholder (the actual generation is a future feature - show a placeholder or snapshot for now)
- Bid form (for verified users)

### SEO / Public Access

- Listing pages should be server-rendered for SEO (Next.js SSR)
- Listing cards should be crawlable
- No authentication required to browse or view listings (only to bid)

---

## Acceptance Criteria

- Browse page loads and displays active listings with correct data
- All filters work correctly and can be combined
- Distance search resolves region names to coordinates and sorts by proximity
- Sort options produce correct ordering
- Listing detail page shows all parcel data, live bid updates, and countdown
- SLURL links work correctly (both viewer protocol and maps.secondlife.com)
- Pages are server-rendered and accessible without login
- Bidding requires authentication (redirect to login if not authenticated)
- Mobile-responsive layout

---

## Notes

- The distance search depends on grid coordinates being stored on parcels (Phase 3).
- Consider pagination or infinite scroll for large result sets.
- The countdown timer should account for snipe extensions in real-time.
