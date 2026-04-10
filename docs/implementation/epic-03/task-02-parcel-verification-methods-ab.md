# Task 03-02: Parcel Verification - Methods A & B

## Goal

Build the backend logic for the two script-level parcel verification methods: manual UUID entry (Method A) and rezzable object callback (Method B). Both result in a Script-Verified listing.

## Context

See DESIGN.md Section 4.2 (Methods A & B). The World API client from Task 03-01 is used for Method A. Both methods only work for individually-owned land (not group-owned).

## What Needs to Happen

**Method A (Manual UUID Entry):**
- Create endpoint: POST /api/v1/parcels/verify/uuid
  - Authenticated (JWT required)
  - User must be verified (SL avatar linked)
  - Accepts: parcel UUID
- Backend calls World API client to fetch parcel metadata
- Validates:
  - `ownertype` is "agent" (reject group-owned with clear error message directing to Method C)
  - `ownerid` matches the user's verified SL avatar UUID
  - Parcel exists (not 404)
- On success: creates a parcel record populated with World API metadata, marks as verified
- Resolves grid coordinates via Map API and stores grid_x, grid_y
- Checks Grid Survey for Mainland estate type (reject non-Mainland with message)

**Method B (Rezzable Object Callback):**
- Create endpoint: POST /api/v1/sl/parcel/verify
  - Publicly accessible (no JWT - called from SL scripts)
  - Validates X-SecondLife-Shard ("Production") and X-SecondLife-Owner-Key (SLPA service account)
- Accepts: verification_code, parcel_uuid, owner_uuid, parcel_name, area_sqm, description, prim_capacity
- Validates:
  - Verification code is valid (type='PARCEL')
  - Owner UUID is NOT a group UUID (ownertype check via World API)
  - Owner UUID matches the code holder's SL avatar UUID
- On success: creates parcel record, marks verified, resolves grid coordinates
- Cross-references with World API for additional metadata (snapshot, maturity)

**Shared logic:**
- Create a ParcelVerificationService that both methods use
- De-duplicate: if a parcel UUID already exists in the parcels table, check if it's the same owner (allow re-verification by same user, reject if owned by someone else on SLPA)
- Generate SLURL from region name and parcel coordinates
- Set verification_tier to 'SCRIPT' on the resulting auction

## Acceptance Criteria

- Method A: User submits parcel UUID → backend fetches World API → parcel created with correct metadata if ownership matches
- Method A: Rejects group-owned parcels with helpful error pointing to Method C
- Method A: Rejects parcels not owned by the authenticated user
- Method B: LSL callback with valid code + headers → parcel created and verified
- Method B: Rejects group-owned parcels
- Both methods: grid coordinates resolved and stored
- Both methods: non-Mainland parcels rejected (if Grid Survey available)
- Both methods: duplicate parcel UUID handled correctly
- Parcel records include all available metadata (name, region, area, description, snapshot, maturity, coordinates)

## Notes

- Method B requires a PARCEL type verification code. Extend the verification code service from Epic 02 to support type='PARCEL' - user generates a parcel verification code from the listing creation page.
- World API may return limited data for some parcels - handle missing fields gracefully.
- SLURL format: `https://maps.secondlife.com/secondlife/Region%20Name/x/y/z` - encode region name for URL.
