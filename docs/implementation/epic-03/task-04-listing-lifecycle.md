# Task 03-04: Listing Creation & Lifecycle Management

> **Before starting:** read [CONVENTIONS.md](../CONVENTIONS.md) for project-wide rules (no new migrations, Lombok required, vertical slices, feature-based packages).

## Goal

Build the full listing creation flow and status lifecycle management: creating drafts, paying listing fees, triggering verification, transitioning to active, and handling cancellations.

## Context

See DESIGN.md Sections 4.5, 4.5.1, 4.5.2. Parcel verification methods exist from Tasks 03-02 and 03-03. This task ties everything together into the listing lifecycle.

## What Needs to Happen

**Listing creation:**
- Create endpoint: POST /api/v1/auctions
  - Authenticated, user must be verified
  - Creates auction in DRAFT status
  - Accepts seller-provided fields: starting_bid, reserve_price (optional), buy_now_price (optional), duration_hours (24/48/72/168/336), snipe_protect (boolean), snipe_window_min (5/10/15/30/60 if enabled), seller_desc, parcel tags (array of tag enum values)
  - Parcel must already be verified (from Task 03-02 or 03-03) OR verification is triggered inline
  - Set commission_rate to configured default (0.05)

**Listing lifecycle transitions:**
- PUT /api/v1/auctions/{id}/pay - records listing fee payment (DRAFT → DRAFT_PAID)
  - In the real flow, payment comes from in-world terminal. For now, accept a mock payment confirmation.
  - Stores listing_fee_amt, listing_fee_txn reference, listing_fee_paid_at
- PUT /api/v1/auctions/{id}/verify - triggers verification (DRAFT_PAID → VERIFICATION_PENDING)
  - Dispatches to the appropriate verification method based on what the seller chose
- Verification callbacks (from Tasks 03-02/03-03) transition VERIFICATION_PENDING → ACTIVE:
  - Set starts_at to now, ends_at to starts_at + duration_hours
  - Set original_ends_at = ends_at (for snipe extension tracking)
  - Set verification_tier based on method used
- PUT /api/v1/auctions/{id}/cancel - seller cancellation
  - From DRAFT or DRAFT_PAID: allowed, listing fee refunded if paid
  - From ACTIVE: allowed only if auction has not ended, listing fee NOT refunded
  - Creates cancellation_log entry
  - Cancellation with active bids increments seller's cancelled_with_bids counter

**Listing read endpoints:**
- GET /api/v1/auctions/{id} - public view of a single listing (all public fields + parcel data)
- GET /api/v1/auctions/{id}/preview - authenticated, seller only, shows how listing will look before going active
- GET /api/v1/users/me/auctions - authenticated, returns user's own listings (all statuses)

**Listing update:**
- PUT /api/v1/auctions/{id} - seller can edit draft listings (DRAFT or DRAFT_PAID only)
  - Cannot edit once VERIFICATION_PENDING or later
  - Can update: seller_desc, starting_bid, reserve_price, buy_now_price, duration, snipe settings, tags

**Parcel tags:**
- Store selected tags in auction_tags join table
- Accept array of parcel_tag enum values during creation/update
- Validate tags against the enum

## Acceptance Criteria

- Listing can be created in DRAFT status with all seller fields
- Draft listings can be edited (description, pricing, duration, snipe settings, tags)
- Listing fee payment transitions DRAFT → DRAFT_PAID
- Verification trigger transitions DRAFT_PAID → VERIFICATION_PENDING
- Successful verification transitions to ACTIVE with correct starts_at/ends_at
- Failed verification returns to DRAFT with fee refund flag set
- Seller can cancel from DRAFT/DRAFT_PAID (with refund) or ACTIVE (no refund)
- Cancellation with bids increments the seller's cancellation counter
- Parcel tags are stored and returned correctly
- Preview endpoint shows draft listing to seller only
- Public listing endpoint returns all expected fields for an ACTIVE listing
- Non-ACTIVE listings are NOT returned by public endpoints (except to the seller)
- Auction end time is calculated from ACTIVE transition, not creation time

## Notes

- The listing fee amount should be configurable. Start with a flat fee (e.g., L$100).
- The "pay" step is a placeholder until the escrow terminal is built (Epic 05). For now, accept a mock/admin payment confirmation.
- Parcel photo upload (seller's additional photos beyond the SL snapshot) can be simple file upload similar to profile pic (Task 02-03). Store alongside listing.
- Don't implement the actual auction ending/winner determination logic here - that's Epic 04. Just set ends_at correctly.
