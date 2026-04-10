# Task 07-03: Listing Detail Page Enhancements

## Goal

Enhance the auction detail page (Epic 04 Task 06) with full browse-context features: complete parcel metadata display, SLURL links, seller profile card, and SEO.

## Context

The auction detail page exists from Epic 04 Task 06 with bidding UI and WebSocket. This task adds the remaining "discovery" features that make the page complete for both browsing users and search engines.

## What Needs to Happen

- **Server-side rendering:**
  - Page should be SSR (Next.js) with full parcel/auction data for SEO
  - Open Graph meta tags (title, description, image from parcel snapshot) for social sharing
  - Structured data (JSON-LD) for search engines if applicable

- **Full parcel metadata display:**
  - Parcel name, region, area (sqm), maturity rating
  - Verification tier badge with explanation tooltip
  - Parcel tags as pill badges (full set, not truncated)
  - Grid coordinates / region info
  - Parcel snapshot (large hero image)
  - Additional seller-uploaded photos (gallery with lightbox/modal on click)

- **SLURL "Visit in Second Life" section:**
  - Two buttons side by side:
    - "Open in Viewer" → `secondlife:///Region%20Name/x/y/z` (viewer protocol)
    - "View on Map" → `https://maps.secondlife.com/secondlife/Region%20Name/x/y/z`
  - Brief explanation text for users unfamiliar with SLURLs

- **Seller profile card:**
  - Display name, profile picture
  - Rating stars (average) + number of reviews
  - Completed sales count
  - Completion rate percentage
  - Member since date
  - "New Seller" badge if < 3 completed sales
  - Link to full profile page (/users/{id})
  - If listed by a realty group agent (Phase 2): "Listed by [Agent] of [Group]" with group logo

- **Parcel layout map placeholder:**
  - Section with placeholder image or "Parcel map coming soon" message
  - Reserve space for the parcel layout map feature (future)

- **Breadcrumb navigation:**
  - Browse → [Region Name] → [Parcel Name]
  - Back to browse preserves filter state

## Acceptance Criteria

- Page is server-rendered with full content visible without JavaScript
- Open Graph tags generate good social media previews (title, description, snapshot image)
- SLURL links format correctly for any region name (including spaces, special chars)
- Seller profile card shows accurate stats
- Photo gallery works (click to enlarge)
- Breadcrumb navigation works and preserves browse filter state
- Page loads fast (no blocking requests for non-critical data)
- Works in dark/light mode

## Notes

- Region names with spaces need URL encoding in SLURLs. `secondlife:///` uses the format `secondlife:///Region Name/128/128/0` (spaces, not %20).
- The maps.secondlife.com URL uses: `https://maps.secondlife.com/secondlife/Region%20Name/128/128/0` (URL-encoded).
- The seller profile card data should come from the same API call as the auction details (avoid extra round-trips).
- This task is mostly frontend polish. The backend endpoints already exist from previous epics.
