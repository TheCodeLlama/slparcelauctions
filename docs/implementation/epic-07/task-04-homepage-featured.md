# Task 07-04: Homepage & Featured Listings

> **Before starting:** read [CONVENTIONS.md](../CONVENTIONS.md) for project-wide rules (no new migrations, Lombok required, vertical slices, feature-based packages).

## Goal

Build the public homepage with featured/highlighted auctions, site stats, and call-to-action sections.

## Context

Landing page design exists at `docs/stitch-generated-design/landing_page/`. The site needs a compelling entry point that showcases active auctions and explains the platform.

## What Needs to Happen

- **Homepage at `/`:**
  - Server-side rendered for SEO

- **Hero section:**
  - Headline + tagline explaining SLPA
  - "Browse Auctions" and "List Your Land" CTAs
  - Background visual (SL landscape or abstract)

- **Featured auctions section:**
  - "Ending Soon" row: 4-6 auctions closest to ending
  - "Just Listed" row: 4-6 newest listings
  - "Most Active" row: 4-6 auctions with most bids
  - Each uses the same listing card component from Task 07-02
  - "View All" link → browse page with appropriate sort pre-selected

- **Site stats section (optional but nice):**
  - Active auctions count
  - Total L$ in active bids
  - Completed sales count
  - Registered users count
  - Stats from a lightweight backend endpoint: GET /api/v1/stats/public

- **How it works section:**
  - 3-4 step overview: Verify → List → Bid → Transfer
  - Simple icons/illustrations + brief text per step

- **Backend endpoint:**
  - GET /api/v1/auctions/featured - returns featured listings grouped by category (ending_soon, newest, most_active)
  - Limited to 6 per category
  - Cached (Redis, 60-second TTL)

## Acceptance Criteria

- Homepage loads with featured auctions populated from real data
- Three featured sections show correct auctions (ending soon, newest, most active)
- Listing cards are clickable and navigate to detail pages
- "View All" links go to browse page with correct sort pre-applied
- How-it-works section explains the platform clearly
- CTAs link to browse and listing creation pages
- SSR for SEO
- Matches Gilded Slate design, works in dark/light mode
- Responsive on mobile (carousel or stack for featured rows)
- Empty state: if no active auctions, show "Be the first to list" message

## Notes

- Reference `docs/stitch-generated-design/landing_page/` for layout.
- The featured endpoint should be cheap - cache aggressively since homepage gets the most traffic.
- Stats can be approximate (cached, not real-time). Don't query live counts on every page load.
- The "How it works" section is static content - no backend needed.
