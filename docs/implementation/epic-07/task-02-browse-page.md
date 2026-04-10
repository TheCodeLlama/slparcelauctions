# Task 07-02: Browse & Search Page

> **Before starting:** read [CONVENTIONS.md](../CONVENTIONS.md) for project-wide rules (no new migrations, Lombok required, vertical slices, feature-based packages).

## Goal

Build the public browse page with filter sidebar, listing cards grid, and sort controls.

## Context

See DESIGN.md Section 4.6 and Stitch-generated design at `docs/stitch-generated-design/browse_auctions/`. Search API exists from Task 07-01. Gilded Slate design system in place.

## What Needs to Happen

- **Page at `/browse` (or `/auctions`):**
  - Server-side rendered for SEO (Next.js SSR with initial data)
  - Public access, no auth required

- **Filter sidebar (collapsible on mobile):**
  - Region name text input (for distance search)
  - Distance slider/input (1-50 regions, shown only when region entered)
  - Parcel size range (min/max sqm inputs)
  - Price range (min/max L$ inputs)
  - Maturity rating checkboxes (PG, Moderate, Adult)
  - Parcel tags multi-select (grouped by category: Terrain, Roads/Access, Location, Neighbors, Parcel Features)
  - Reserve status radio (All / Reserve met / Reserve not met / No reserve)
  - Snipe protection toggle (Any / Yes / No)
  - Verification tier checkboxes (Script / Bot / Human)
  - "Ending soon" quick filter (ending within 1h, 6h, 24h)
  - "Clear all filters" button
  - Active filter count badge

- **Sort controls (above grid):**
  - Dropdown: Newest, Ending Soonest, Most Bids, Lowest Price, Largest Area, Nearest (enabled only with distance search)

- **Listing cards grid:**
  - Responsive grid (3-4 columns desktop, 2 tablet, 1 mobile)
  - Each card shows:
    - Parcel snapshot thumbnail
    - Parcel name + region
    - Current bid (L$) + bid count
    - Time remaining (live countdown)
    - Parcel size (sqm)
    - Maturity rating badge
    - Verification tier badge (🔵/🟢/🟡)
    - 🛡️ Snipe protection badge with window duration
    - Reserve status indicator (orange "Reserve not met" / green "Reserve met ✓")
    - Distance from search point (if distance search active)
    - Parcel tags as compact pills (show first 3, "+N more" if overflow)
  - Click card → navigate to auction detail page

- **Pagination:**
  - Page numbers or "Load more" button
  - Show total result count

- **Empty state:**
  - "No auctions match your filters" with suggestion to adjust filters

- **URL state:**
  - Filters and sort reflected in URL query params (shareable, bookmarkable)
  - Browser back/forward works with filter state

## Acceptance Criteria

- Browse page loads with all active listings (SSR)
- All filters update results dynamically
- Distance search works (enter region name → results sorted by proximity)
- Sort options work correctly
- Listing cards display all required info with correct badges
- Countdown timers update in real-time on visible cards
- Filters reflected in URL (shareable links)
- Mobile-responsive: filter sidebar collapses, cards stack vertically
- Empty state shown when no results match
- Pagination works
- Matches Gilded Slate design system (dark/light mode)

## Notes

- Reference `docs/stitch-generated-design/browse_auctions/` for layout.
- Debounce filter inputs (don't fire API call on every keystroke).
- The countdown timers on cards don't need WebSocket - recalculate from `ends_at` on the client. WebSocket live updates are only on the detail page.
- Consider a "map view" toggle as a placeholder for future Phase 2 map search (just the button, not the implementation).
