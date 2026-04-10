# Task 11-03: Parcel Verifier Object LSL Script

## Goal

Build the rezzable parcel verifier object that sellers drop on their parcel to verify ownership (Methods A and B).

## Context

See DESIGN.md Section 5.3. Seller gets this object from the terminal location or SL Marketplace. They rez it on their parcel, it reads parcel data, POSTs to backend, and self-destructs. Backend endpoint exists from Epic 03 Task 02.

## What Needs to Happen

- **On rez (`on_rez` event):**
  - Immediately gather parcel data at current position via `llGetParcelDetails`:
    - `PARCEL_DETAILS_NAME` - parcel name
    - `PARCEL_DETAILS_DESC` - parcel description
    - `PARCEL_DETAILS_OWNER` - owner UUID (avatar or group)
    - `PARCEL_DETAILS_GROUP` - group UUID
    - `PARCEL_DETAILS_AREA` - area in sqm
    - `PARCEL_DETAILS_ID` - parcel UUID
    - `PARCEL_DETAILS_SEE_AVATARS` - privacy setting
  - Also gather: region name (`llGetRegionName()`), region corner coordinates (`llGetRegionCorner()`), object position (`llGetPos()`)
  - Owner of the object: `llGetOwner()` (the seller who rezzed it)

- **POST to backend:**
  - `llHTTPRequest` POST to backend endpoint
  - Body: JSON with all parcel data + rezzer UUID + parcel UUID
  - SL auto-injects: `X-SecondLife-Owner-Key` (rezzer), `X-SecondLife-Shard`, `X-SecondLife-Region`
  - `http_response` event handles result

- **Result handling:**
  - On success (200): `llOwnerSay("✓ Parcel verified! [Parcel Name] has been linked to your listing.")` → `llDie()` (self-destruct)
  - On failure (4xx): `llOwnerSay("✗ Verification failed: [reason from response]")` → `llDie()`
  - On timeout/error: `llOwnerSay("✗ Could not reach SLPA servers. Please try again.")` → `llDie()`

- **Validation (script-side):**
  - Check that `PARCEL_DETAILS_OWNER` matches `llGetOwner()` (or is a group the owner belongs to)
  - If mismatch: `llOwnerSay("✗ You don't own this parcel. Please rez this object on your own land.")` → `llDie()`
  - Check shard is Production

- **Self-destruct timing:**
  - Always self-destruct after reporting result (success or failure)
  - Timeout: if no HTTP response within 30 seconds, notify owner and self-destruct
  - Never leave objects cluttering the parcel

- **Group-owned land handling:**
  - If `PARCEL_DETAILS_OWNER` is a group UUID (not an avatar): still send data to backend
  - Backend handles group-owned verification logic (cross-references with Method C)
  - Script-side: skip the owner match check if parcel is group-owned (check if owner UUID != rezzer UUID AND group UUID is non-null → likely group-owned, let backend decide)

## Acceptance Criteria

- Rez → reads parcel data → POSTs to backend → notifies owner → self-destructs
- All parcel detail fields sent correctly
- Owner mismatch detected and reported (for non-group land)
- Group-owned land data sent to backend without owner check rejection
- Self-destructs in all cases (success, failure, timeout)
- No objects left behind
- Production shard only
- Clear user feedback via llOwnerSay

## Notes

- `llGetParcelDetails` only works for the parcel the object is physically ON. This is intentional - it proves the seller can rez on that parcel.
- `llDie()` completely removes the object from the world. No cleanup needed.
- The object should be transferable (so sellers can receive it) but not copyable by random people. Set permissions: transfer yes, copy no, modify no.
- For SL Marketplace distribution: create a listing for the free object. Alternatively, have the verification terminal give a copy to the toucher.
- This is the simplest of the three scripts. Should fit easily in the 64KB limit.
- `llGetParcelDetails` returns a list. Access elements by index, not by name. Order matches the order of the requested params.
