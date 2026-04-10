# Task 07-02: Browse & Search Page

> **Before starting:** read [CONVENTIONS.md](../CONVENTIONS.md) for project-wide rules (no new migrations, Lombok required, vertical slices, feature-based packages).

## Goal

Build the public browse page with filter sidebar, listing cards grid, and sort controls.

## Context

See DESIGN.md Section 4.6. Stitch-generated designs are in:

- `docs/stitch_generated-design/light_mode/browse_auctions/`
- `docs/stitch_generated-design/dark_mode/browse_auctions/`

Read both. `docs/stitch_generated-design/DESIGN.md` is the binding style reference (tonal layering, curator tray glassmorphism for filters, no-line rule). Search API exists from Task 07-01. The "Digital Curator" theme and `components/ui/` primitives are in place from Epic 01.

**Component reuse expected:**

- `components/auction/ListingCard` — the card used here is the **same** component used by the homepage featured section (07-04), the dashboard "My Bids" list, and the seller profile listings list. Build it once with variant props (`variant="compact" | "default" | "featured"`), not three times.
- `components/listing/TagSelector` — if already built in Task 03-05, reuse it. The filter sidebar's tag multi-select is the same component. If it wasn't built yet, build it here as a reusable component.
- `components/browse/FilterSidebar` — composed of smaller filter primitives (`FilterSection`, `RangeInput`, `CheckboxGroup`, `RadioGroup`, `ActiveFilterBadge`).
- `components/browse/SortDropdown` — reuses `Dropdown` primitive.
- `components/ui/EmptyState` — generic empty state component (used by browse, dashboard tabs, search results everywhere).
- `components/ui/Pagination` — reusable pagination primitive.

Do not build a parallel listing card or a separate tag selector just because "this one is for browse." One component, variants via props.

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
- Matches the "Digital Curator" design system in both dark and light mode
- `ListingCard` is a single reusable component (variants via props) imported here and usable by other listing-display tasks

## Notes

- Reference both `light_mode/browse_auctions/code.html` and `dark_mode/browse_auctions/code.html` for layout. Rebuild as composed components.
- Debounce filter inputs (don't fire API call on every keystroke).
- The countdown timers on cards don't need WebSocket - recalculate from `ends_at` on the client using the same `CountdownTimer` component built for the auction detail page. WebSocket live updates are only on the detail page.
- Consider a "map view" toggle as a placeholder for future Phase 2 map search (just the button, not the implementation).
- If the `ListingCard` needs a prop you didn't anticipate, add the prop. Don't fork the component.
