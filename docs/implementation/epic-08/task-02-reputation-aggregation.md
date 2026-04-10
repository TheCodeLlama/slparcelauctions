# Task 08-02: Reputation Aggregation & Profile Stats

> **Before starting:** read [CONVENTIONS.md](../CONVENTIONS.md) for project-wide rules (no new migrations, Lombok required, vertical slices, feature-based packages).

## Goal

Calculate and maintain aggregate reputation stats (average rating, completion rate, review count) on user profiles.

## Context

Reviews exist from Task 08-01. The users table has fields for rating aggregates from Epic 01 migrations. These stats are displayed on profiles and listing cards.

## What Needs to Happen

- **Update user stats when reviews become visible:**
  - Recalculate `avg_rating_as_seller` and `avg_rating_as_buyer` (or a single combined rating)
  - Update `total_reviews_received` count
  - Use a trigger or service-level update when review visibility changes

- **Completion rate calculation:**
  - Formula: completed auctions / total auctions that reached ACTIVE status
  - Excludes: drafts, cancelled-before-active
  - Includes: completed, expired (buyer didn't pay), cancelled-with-bids
  - Update on: auction completion, auction cancellation, escrow expiry
  - Store as `completion_rate` on user record (decimal, 0.0-1.0)

- **Profile stats endpoint:**
  - GET /api/v1/users/{id}/stats - returns:
    - Average seller rating + count
    - Average buyer rating + count
    - Completion rate (as seller)
    - Total completed sales
    - Total completed purchases
    - Total active listings
    - Member since date
    - "New Seller" flag (< 3 completed sales)

- **Listing card seller summary:**
  - Ensure the auction search API (Epic 07) includes seller rating summary in results
  - Embed: seller display name, avg rating, review count, "New Seller" badge

- **"New Seller" badge logic:**
  - Show on profiles and listing cards when completed_sales < 3
  - Remove automatically when third sale completes

## Acceptance Criteria

- Average rating updates correctly when new reviews become visible
- Completion rate calculated correctly (completed / total active)
- Stats endpoint returns accurate data
- "New Seller" badge shown when < 3 completed sales, hidden at 3+
- Listing cards in search results include seller rating summary
- Stats update promptly after relevant events (review revealed, auction completed, auction cancelled)
- Rating averages are weighted correctly (not skewed by old data)

## Notes

- Completion rate should be recalculated, not maintained incrementally, to avoid drift. But for performance, cache it and recalculate on relevant events.
- Consider whether to show separate buyer/seller ratings or a combined rating. Separate is more useful for a marketplace.
- The stats are read-heavy (every listing card, every profile view) - keep them denormalized on the user record rather than calculating on every request.
