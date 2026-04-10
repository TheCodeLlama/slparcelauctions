# Task 06-02: Teleport & ParcelProperties Reading

## Goal

Implement the core bot capability: teleporting to a target region and reading ParcelProperties data for a parcel, including the fields LSL cannot access (AuthBuyerID, SalePrice).

## Context

See DESIGN.md Section 5.4 and the LibreMetaverse research. The `ParcelProperties` message from the SL viewer protocol contains fields that LSL scripts cannot access, which is why the bot exists. This is the fundamental capability everything else builds on.

## What Needs to Happen

- **Teleport to region:**
  - Accept region name + local coordinates (x, y, z)
  - Use LibreMetaverse's teleport API
  - Handle teleport success/failure
  - Handle access denied (banned, group-only, access list) - report as ACCESS_DENIED, don't retry
  - Handle region not found - report as REGION_NOT_FOUND
  - Track current region per bot (update after successful teleport)
  - Respect rate limit: max 6 teleports per minute per bot (enforce with a token bucket or simple timer)

- **Read ParcelProperties:**
  - After teleporting into a region, request ParcelProperties for the parcel at given coordinates
  - Use LibreMetaverse's ParcelManager to send `ParcelPropertiesRequest`
  - Parse the response and extract:
    - OwnerID (parcel owner UUID)
    - GroupID (group UUID if group-owned)
    - IsGroupOwned (boolean)
    - AuthBuyerID (who the land is set for sale to - UUID or zero)
    - SalePrice (L$ sale price, 0 if not for sale)
    - Name, Description
    - Area (square meters)
    - MaxPrims
    - Category
    - SnapshotID
    - Flags (parcel flags bitmask)
  - Return all fields as a structured result

- **Handle edge cases:**
  - Parcel not found at coordinates (landed outside target)
  - Region restart during teleport (retry once)
  - Timeout waiting for ParcelProperties response

- **Create a simple test command:**
  - Manual trigger: "check parcel at [region] [x,y,z]" that logs full parcel data
  - Useful for development and debugging

## Acceptance Criteria

- Bot can teleport to a named region at specific coordinates
- After teleporting, bot reads full ParcelProperties including AuthBuyerID and SalePrice
- Access denied handled gracefully (no crash, clean error report)
- Rate limit enforced (6 teleports/min max per bot)
- All relevant parcel fields extracted and returned in structured format
- Teleport failures reported with specific error type (access denied, not found, timeout)
- Test command works: given region + coordinates, outputs full parcel data

## Notes

- LibreMetaverse's `GridClient.Parcels.RequestAllSimParcels()` or `GridClient.Parcels.RequestParcelProperties()` for reading parcel data.
- The `ParcelProperties` event fires when parcel data arrives. It's async - need to wait for the callback.
- The bot lands at the specified coordinates, which determines which parcel's data it reads. For parcels not at region center, coordinates matter.
- AuthBuyerID will be `UUID.Zero` if land is not set for sale to a specific buyer.
- SalePrice will be 0 if land is not for sale.
