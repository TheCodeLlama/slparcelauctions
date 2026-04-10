# Task 02-05: Public User Profile Page

## Goal

Build the public-facing user profile page where anyone can view a user's verified identity, reputation, and listing history.

## Context

Backend GET /api/v1/users/{id} endpoint exists from Task 02-03. This page is linked from auction listings (seller profile), bid history, and reviews.

## What Needs to Happen

- Build profile page at `/users/[id]`
- Display:
  - Profile picture (or default avatar placeholder)
  - Display name (with verified badge if SL-verified)
  - SL avatar name (if verified)
  - Member since date
  - Bio/description
  - Reputation stats: average seller rating (stars), average buyer rating (stars), total completed sales, total reviews
  - "New Seller" badge if completed_sales < 3
  - Completion rate (completed / (completed + cancelled_with_bids)) displayed as percentage
- Placeholder sections for future content:
  - "Recent Reviews" (empty state for now - "No reviews yet")
  - "Active Listings" (empty state for now - "No active listings")
  - Realty group badge (if applicable, Phase 2 - just leave space)
- Page is publicly accessible (no authentication required)
- Handle invalid/non-existent user IDs gracefully (404 page)

## Acceptance Criteria

- `/users/{id}` displays the correct user's public profile
- Verified users show their SL avatar name and verified badge
- Unverified users show "Unverified" status (no SL info)
- Reputation stats display correctly (stars, counts, completion rate)
- "New Seller" badge appears for users with fewer than 3 completed sales
- Non-existent user ID shows a 404 page
- Page works without authentication
- Responsive on mobile
- Works in both dark and light mode

## Notes

- Star ratings: use filled/empty star icons. Show to one decimal (e.g., "4.7 ★")
- If a user has no ratings yet, show "No ratings yet" instead of 0 stars.
- This page will become more useful once reviews and listings are built, but having the shell now means other features can link to it immediately.
