# Phase 8: Ratings & Reputation

> **Before starting:** read [CONVENTIONS.md](../CONVENTIONS.md) for project-wide rules (no new migrations, Lombok required, vertical slices, feature-based packages).

_Reference: DESIGN.md Sections 8 (Anti-Circumvention, Ratings & Reviews)_

---

## Goal

Post-auction rating system and anti-circumvention measures. Both buyers and sellers rate each other after completed auctions. Cancellation penalties discourage misuse.

---

## What Needs to Happen

### Ratings & Reviews

- After an auction completes (escrow released), both parties can rate each other
- Rating: 1-5 stars + optional text review (max 500 chars)
- Ratings are blind until both parties submit OR 14-day window expires
- After both submit (or timeout), ratings become visible simultaneously
- If only one party rates, that rating goes public after 14 days
- Reviewee can respond to a review (one response, public)
- Cannot rate unless auction completed through escrow
- One rating per auction per party, no edits after submission
- Flagged reviews go to admin queue

### Profile Reputation Display

- Seller profile: average rating, total reviews, completion rate (completed / total listed)
- Buyer profile: average rating, total reviews, auctions won
- Listing cards show seller's rating summary
- "New Seller" badge for sellers with fewer than 3 completed auctions

### Cancellation Penalties

- Track cancellation history per seller
- Escalating penalties for cancelling auctions with active bids:
  - First offense: warning + listing fee forfeited
  - Second offense: L$500 penalty deducted from future payouts
  - Third offense: 30-day listing suspension
  - Persistent pattern: permanent ban
- Cancellation reason required (free text, logged for admin review)
- "Cancelled with bids" counter visible only to admins

### Completion Rate

- Calculated as: completed auctions / total auctions that went ACTIVE
- Displayed on seller profile
- Excludes auctions cancelled before going active (no penalty for cancelling drafts)

---

## Acceptance Criteria

- Both parties can submit ratings after a completed auction
- Ratings remain hidden until both submit or 14 days pass
- Ratings display correctly on user profiles (averages, counts)
- Completion rate calculated and displayed accurately
- Cancellation of auctions with bids triggers the correct escalating penalty
- "New Seller" badge shows for sellers with < 3 completed auctions
- Review responses work (one per review, visible publicly)
- Flagged reviews appear in admin queue
- Rating submission blocked for non-escrow-completed auctions

---

## Notes

- The review visibility timing (blind until both submit or 14 days) should use a scheduled job or check-on-access approach.
- Cancellation penalty amounts (L$500) should be configurable, not hardcoded.
