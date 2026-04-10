# Task 08-01: Review Service & Blind Rating Logic

## Goal

Build the backend service for submitting, storing, and revealing reviews with the blind rating mechanic.

## Context

See DESIGN.md Section 8 (Ratings & Reviews). The `reviews` table exists from Epic 01 migrations. Both buyer and seller rate each other after escrow completes.

## What Needs to Happen

- **ReviewService with blind rating logic:**
  - After escrow COMPLETED: both seller and winner become eligible to review
  - Each party submits independently: 1-5 stars + optional text (max 500 chars)
  - Reviews stored immediately but marked as NOT visible
  - Visibility triggers:
    - Both parties have submitted → reveal both simultaneously
    - 14-day window expires → reveal whatever was submitted
  - One review per party per auction, no edits after submission

- **REST endpoints:**
  - POST /api/v1/auctions/{id}/reviews - submit review (authenticated, must be seller or winner of a COMPLETED auction)
    - Accepts: rating (1-5), text (optional, max 500 chars), role (as_seller or as_buyer)
    - Returns: confirmation with visibility status ("visible when other party reviews or after 14 days")
  - GET /api/v1/auctions/{id}/reviews - get reviews for an auction
    - Public: returns only visible reviews
    - If user is a party: also shows their own submitted review even if not yet visible, with "pending" status
  - POST /api/v1/reviews/{id}/respond - submit a response to a review you received (one response per review, max 500 chars)
  - POST /api/v1/reviews/{id}/flag - flag a review for admin review (with reason)

- **Blind reveal scheduled job:**
  - Run every hour (or daily)
  - Find reviews where: submitted > 14 days ago AND still not visible AND the other party hasn't submitted
  - Mark as visible

- **Validation:**
  - Cannot review unless auction completed through escrow (status COMPLETED)
  - Cannot review if already reviewed this auction
  - Cannot review your own side (seller reviews buyer, buyer reviews seller)
  - Cannot respond to a review you wrote (only to reviews about you)

## Acceptance Criteria

- Both parties can submit reviews after COMPLETED auction
- Reviews hidden until both submit or 14 days pass
- Both reviews revealed simultaneously when second party submits
- 14-day timeout reveals single-sided reviews
- One review per party per auction enforced
- Review response works (one per review received)
- Flag creates admin queue entry
- GET endpoint respects visibility rules
- Cannot review non-COMPLETED auctions
- Cannot edit submitted reviews

## Notes

- The "reveal both simultaneously" is key to preventing retaliation ratings. Neither party sees what the other wrote until both are locked in.
- The 14-day window starts from escrow completion, not from first review submission.
- The scheduled job for 14-day reveals can be simple - just scan for old unrevealed reviews. Low volume, doesn't need to be optimized.
