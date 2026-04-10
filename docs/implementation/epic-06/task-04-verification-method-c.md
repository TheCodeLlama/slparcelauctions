# Task 06-04: Method C Verification Handler

> **Before starting:** read [CONVENTIONS.md](../CONVENTIONS.md) for project-wide rules (no new migrations, Lombok required, vertical slices, feature-based packages).

## Goal

Implement the bot-side logic for Sale-to-Bot verification (Method C) - the bot checks that the parcel is set for sale to the SLPAEscrow account at the sentinel price.

## Context

See DESIGN.md Section 4.2 (Method C). When a seller creates a listing with Method C, the Java backend creates a VERIFY task. The bot teleports in and checks ParcelProperties to confirm the seller has authority.

## What Needs to Happen

- **Verification checks (on VERIFY task):**
  1. Teleport to the parcel's region at the parcel coordinates
  2. Read ParcelProperties
  3. Validate ALL of the following:
     - `AuthBuyerID` == primary escrow account UUID (SLPAEscrow)
     - `SalePrice` == L$999,999,999 (sentinel price)
     - `OwnerID` matches the expected seller UUID (or `IsGroupOwned` is true and `GroupID` matches for group-owned land)
  4. Report pass/fail with full parcel data to callback

- **For group-owned land:**
  - The sale-setting act proves the seller has `LandSetSale` group permission
  - Check `IsGroupOwned == true` and `GroupID` matches expected group
  - The seller's individual UUID won't be the OwnerID - the group UUID will be

- **Failure reasons (specific, not generic):**
  - AUTH_BUYER_MISMATCH: land set for sale but to wrong UUID
  - PRICE_MISMATCH: land set for sale but at wrong price
  - NOT_FOR_SALE: AuthBuyerID is zero and/or SalePrice is 0
  - OWNER_MISMATCH: parcel owner doesn't match expected seller/group
  - ACCESS_DENIED: bot couldn't teleport in
  - PARCEL_NOT_FOUND: couldn't find parcel at given coordinates

- **Result payload to backend callback:**
  - task_id, verification_result (PASS/FAIL), failure_reason (if FAIL)
  - Full parcel data snapshot: owner, group, auth_buyer, sale_price, area, name, description, category

## Acceptance Criteria

- Bot correctly identifies a parcel set for sale to SLPAEscrow at L$999,999,999 as PASS
- Wrong AuthBuyerID returns FAIL with AUTH_BUYER_MISMATCH
- Wrong price returns FAIL with PRICE_MISMATCH
- Land not for sale returns FAIL with NOT_FOR_SALE
- Group-owned land verification works (checks GroupID instead of OwnerID)
- Access denied handled gracefully with specific failure reason
- Full parcel data included in result regardless of pass/fail
- Callback delivered to Java backend

## Notes

- The sentinel price L$999,999,999 is protection for the seller - it's too high for anyone to accidentally buy. The bot just needs to confirm it matches.
- This is the same teleport + read flow from Task 06-02, with specific validation logic on top. Reuse the core capabilities.
- The Java backend (Epic 03 Task 03) handles what to do with the result (transition listing status, notify seller, etc.). The bot just checks and reports.
