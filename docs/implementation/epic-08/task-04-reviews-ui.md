# Task 08-04: Reviews & Reputation UI

## Goal

Build the frontend for submitting reviews, displaying ratings on profiles, and showing reputation throughout the site.

## Context

Backend exists from Tasks 08-01 and 08-02. Reviews need to appear on the escrow completion page, user profiles, and listing cards.

## What Needs to Happen

- **Review submission (on escrow completion page, Epic 05 Task 05):**
  - After escrow COMPLETED: show a review form for the other party
  - Star rating selector (1-5, clickable stars)
  - Optional text review (textarea, 500 char limit with counter)
  - Submit button
  - After submission: show "Your review will be visible when the other party submits theirs, or after 14 days"
  - If already submitted: show "Review submitted ✓" with their own review text (read-only)
  - If both submitted (reviews visible): show both reviews

- **Review display on user profile (`/users/{id}`):**
  - Reviews tab or section
  - List of visible reviews received (as seller and as buyer)
  - Each review shows: reviewer display name, star rating, text, date, auction link
  - Seller response shown below the review (if any)
  - "Respond" button for the reviewee (one response per review)
  - Paginated

- **Rating summary on profile:**
  - Average rating (star display + numeric, e.g., "★★★★☆ 4.2")
  - Total reviews count
  - Rating breakdown (optional: bar chart showing 5-star, 4-star, etc. distribution)
  - Completion rate as percentage
  - "New Seller" badge when < 3 completed sales

- **Rating on listing cards (browse page):**
  - Seller's average rating shown as compact stars + count (e.g., "★ 4.5 (23)")
  - "New Seller" badge for new sellers

- **Rating on auction detail page (seller card):**
  - Same as profile summary but compact
  - Link to full profile for more reviews

- **Flag review:**
  - Small flag icon/link on each review
  - Click → modal with reason selection + text
  - Confirmation message after flagging

## Acceptance Criteria

- Review form appears on completed escrow page for both parties
- Star selector works (hover preview, click to set)
- Text review with character counter
- Submitted reviews show correct pending/visible status
- User profile shows all visible reviews with averages
- Review responses display correctly under the original review
- Rating summary visible on listing cards and auction detail seller card
- "New Seller" badge appears and disappears at the right threshold
- Flag functionality works
- Works in dark/light mode, responsive on mobile

## Notes

- The star selector component is reusable - build it as a shared component.
- Empty state for profiles with no reviews: "No reviews yet" message.
- The review form should only appear once the user navigates to the escrow page after completion. Don't force it as a popup or modal on the auction detail page.
- Consider email notification prompting users to leave reviews (Epic 09), but for now the form is just available on the escrow page.
