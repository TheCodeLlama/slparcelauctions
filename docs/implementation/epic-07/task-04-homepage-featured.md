# Task 07-04: Homepage & Featured Listings

> **Before starting:** read [CONVENTIONS.md](../CONVENTIONS.md) for project-wide rules (no new migrations, Lombok required, vertical slices, feature-based packages).

## Goal

Build the public homepage with featured/highlighted auctions, site stats, and call-to-action sections.

## Context

The landing page was built in Task 01-10 using Stitch references at `docs/stitch_generated-design/{light,dark}_mode/landing_page/`. This task builds on top of that page to add **featured auction rows** backed by real data, plus the public stats endpoint.

**Reuse, do not rebuild:**

- The `Hero`, `HowItWorksStep`, `FeatureCard`, `CtaSection` components built in Task 01-10 — import them, don't rewrite them.
- The `ListingCard` built in Task 07-02 — this is the canonical listing card for the entire app. Use it here with whatever variant fits the featured rows (likely `variant="compact"` or a new `variant="featured"` if the visual treatment differs enough).
- `components/ui/EmptyState` from Task 07-02.

New components this task should add:

- `components/marketing/FeaturedRow` — a labeled horizontal row ("Ending Soon", "Just Listed", "Most Active") with a header, "View All" link, and a scrollable/carousel of listing cards. One component, reused three times with different props.
- `components/marketing/StatsBar` — four-stat display (active auctions, total L$ bid, completed sales, registered users). Fetches from `/api/v1/stats/public`.

If the landing page needs a visual change to accommodate the featured rows, update the `Hero` or `AppShell` component — don't fork them.

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
- Matches the "Digital Curator" design in both dark and light mode
- Responsive on mobile (carousel or stack for featured rows)
- Empty state: if no active auctions, show "Be the first to list" message using the shared `EmptyState` component
- `ListingCard`, `Hero`, `HowItWorksStep`, and other shared components are imported, not reimplemented

## Notes

- Reference both `light_mode/landing_page/code.html` and `dark_mode/landing_page/code.html` if the featured rows change the overall landing page layout.
- The featured endpoint should be cheap - cache aggressively since homepage gets the most traffic.
- Stats can be approximate (cached, not real-time). Don't query live counts on every page load.
- The "How it works" section is already built as a component in Task 01-10 - import it, don't rebuild.
