# Phase 3: Parcel Management

_Reference: DESIGN.md Sections 4.2, 4.5, 4.5.1, 4.5.2, 6.2, 6.3, 6.4_

---

## Goal

Verified users can verify parcel ownership and create auction listings. Supports three verification methods: manual UUID entry, rezzable object callback, and sale-to-bot. Listings go through a lifecycle from draft to active.

---

## What Needs to Happen

### Parcel Verification - Method A (Manual UUID Entry)

- User provides a parcel UUID on the website
- Backend calls `world.secondlife.com/place/{parcel_uuid}` and parses HTML meta tags (ownerid, ownertype, area, region, description, snapshot, maturity)
- Verifies `ownerid` matches the user's verified SL avatar UUID
- Rejects group-owned land for this method (`ownertype == "group"`)
- Auto-populates parcel record with metadata from World API

### Parcel Verification - Method B (Rezzable Object Callback)

- `POST /api/v1/sl/parcel/verify` endpoint receives parcel data from an in-world LSL object
- Accepts: parcel UUID, owner UUID, parcel name, area, description, prim capacity
- Validates SL headers (same as player verification)
- Cross-references with World API for additional verification
- Rejects group-owned land for this method

### Parcel Verification - Method C (Sale-to-Bot)

- User selects "Sale-to-Bot Verification" when creating a listing
- Website instructs user to set land for sale to the primary escrow account (SLPAEscrow Resident) at L$999,999,999
- Backend dispatches a verification task (bot integration comes in Phase 6 - for now, create the task queue and allow manual/mock completion)
- Verification checks: AuthBuyerID matches primary escrow UUID, SalePrice is L$999,999,999, ownership matches
- This is the ONLY method that works for group-owned land

### Listing Creation & Lifecycle

- Create listing in DRAFT status with all seller-provided fields (starting bid, reserve price, buy-now price, duration, snipe protection settings, description, photos, parcel tags)
- Listing preview endpoint (see how it will look before going live)
- Listing fee payment tracking (DRAFT → DRAFT_PAID transition)
- Verification trigger (DRAFT_PAID → VERIFICATION_PENDING)
- Verification callback (VERIFICATION_PENDING → ACTIVE or back to DRAFT on failure)
- Auction clock starts when listing goes ACTIVE (not at creation)
- Listing fee refunded on verification failure
- Seller can cancel from any draft state (full refund) or ACTIVE (no refund)

### Grid Coordinate Integration

- When a parcel is listed, resolve region name to grid coordinates using the Map API
- Store grid_x, grid_y on the parcel record
- Optionally query Grid Survey API for supplementary data (estate type, region age)
- Reject non-Mainland parcels (estate type must be "Mainland")

### Parcel Tags

- Parcel tags from the predefined enum (27 tags across 5 categories)
- Seller selects applicable tags during listing creation
- Tags stored in auction_tags join table

---

## Acceptance Criteria

- Method A: User enters parcel UUID → backend fetches World API → parcel verified and record created with metadata
- Method B: API endpoint accepts LSL callback data → parcel verified
- Method C: Listing created with Sale-to-Bot verification type → verification task queued → can be completed (manually for now) → listing goes ACTIVE
- Group-owned land rejected for Methods A/B, accepted for Method C
- Listing lifecycle transitions work correctly (DRAFT → DRAFT_PAID → VERIFICATION_PENDING → ACTIVE)
- Verification failure refunds listing fee and returns to DRAFT
- Cancellation from draft states refunds fee; cancellation from ACTIVE does not
- Grid coordinates resolved and stored for every parcel
- Non-Mainland parcels rejected during verification
- Parcel tags can be added/removed during listing creation
- Listing preview shows accurate representation of the final listing

---

## Notes

- The actual bot that performs Method C verification is in Phase 6. For Phase 3, create the task queue infrastructure and verification workflow, but allow tasks to be completed via a test/admin endpoint.
- The LSL rezzable object script is in Phase 11. Test Method B with curl/Postman.
- World API is unofficial and may be slow or occasionally unavailable. Handle errors gracefully.
