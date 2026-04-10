# Task 07-01: Search & Filter API

## Goal

Build the backend search endpoint that supports all the filter, sort, and distance search capabilities.

## Context

See DESIGN.md Section 4.6. Parcels and auctions exist from Epics 01-03 with grid coordinates stored. This endpoint powers the browse page.

## What Needs to Happen

- Create endpoint: GET /api/v1/auctions/search
  - Public (no auth required)
  - Returns paginated list of ACTIVE auctions with parcel data

- **Filter parameters (all optional, combinable):**
  - `region` - exact region name match
  - `min_area` / `max_area` - parcel size range (sqm)
  - `min_price` / `max_price` - current bid range (L$)
  - `maturity` - PG, Moderate, Adult (multi-value)
  - `tags` - parcel tag enum values (multi-value, AND or OR logic - default OR)
  - `reserve_status` - all / reserve_met / reserve_not_met / no_reserve
  - `snipe_protection` - true / false / any
  - `verification_tier` - script / bot / human (multi-value)
  - `ending_within` - auctions ending within N hours (for "ending soon" filter)
  - `near_region` - region name for distance search (resolves to grid coords)
  - `distance` - max distance in regions from near_region (default 10)

- **Sort options:**
  - `newest` - most recently listed (default)
  - `ending_soonest` - closest to ending
  - `most_bids` - highest bid count
  - `lowest_price` - lowest current bid
  - `largest_area` - biggest parcels
  - `nearest` - requires near_region parameter, sort by Euclidean distance

- **Distance search:**
  - Resolve `near_region` to grid coordinates via Grid Survey API client (Epic 03)
  - Calculate Euclidean distance: sqrt((x1-x2)² + (y1-y2)²) using stored grid_x/grid_y
  - Filter to parcels within `distance` regions
  - Include calculated distance in response

- **Response format:**
  - Paginated (page/size or cursor-based)
  - Each result: auction summary + parcel summary + bid summary
  - Include: parcel snapshot URL, name, region, area, maturity, tags, current bid, bid count, time remaining (calculated), snipe protection (enabled + window), verification tier, reserve status, distance (if distance search)

- **Performance:**
  - Database-level filtering and sorting (not in-memory)
  - Index on commonly filtered columns (status, maturity, grid_x/grid_y, ends_at)
  - Consider caching hot queries with Redis (short TTL, 30-60 seconds)

## Acceptance Criteria

- All filters work individually and in combination
- Distance search resolves region names and returns results sorted by proximity
- All sort options produce correct ordering
- Pagination works correctly
- Response includes all fields needed for listing cards
- Endpoint performs well with hundreds of listings (query plan uses indexes)
- Invalid filter values return clear 400 errors
- Public access (no auth required)

## Notes

- The distance calculation can be done in PostgreSQL: `sqrt(power(grid_x - :x, 2) + power(grid_y - :y, 2))`
- For the `near_region` lookup: cache Grid Survey results since region coordinates rarely change.
- "Ending soon" is a useful derived filter - consider ending_within=2 as a common preset.
- Tags filter: OR logic is more useful as default (show parcels with ANY of the selected tags).
