# Task 06-05: Auction & Escrow Monitoring Handlers

## Goal

Implement the bot-side logic for recurring monitoring during active auctions (30-min checks) and escrow periods (15-min checks).

## Context

See DESIGN.md Sections 5.4 and 4.8. Bot-Verified listings get ongoing monitoring beyond the baseline World API polling. The bot checks richer data (sale status, AuthBuyerID) that the World API cannot provide.

## What Needs to Happen

**Auction monitoring (MONITOR_AUCTION tasks, every 30 min):**
- Teleport to parcel, read ParcelProperties
- Check that the listing is still valid:
  - `AuthBuyerID` still matches SLPAEscrow UUID
  - `SalePrice` still L$999,999,999
  - `OwnerID` still matches expected seller (or group)
- Report to backend:
  - ALL_GOOD: everything matches, auction can continue
  - AUTH_BUYER_CHANGED: seller removed or changed the sale-to-bot setting (listing should be paused)
  - OWNER_CHANGED: land ownership transferred during auction (listing should be cancelled, potential fraud)
  - ACCESS_DENIED: bot can no longer access the parcel (seller revoked access, flag for review)

**Escrow monitoring (MONITOR_ESCROW tasks, every 15 min):**
- Teleport to parcel, read ParcelProperties
- Check ownership transfer status:
  - `OwnerID` == winner UUID → TRANSFER_COMPLETE (report to backend for payout)
  - `OwnerID` == seller UUID → STILL_WAITING (continue monitoring)
  - `OwnerID` == unknown UUID → FRAUD_DETECTED (report to backend for freeze)
- Also check if seller set up the sale correctly:
  - `AuthBuyerID` == winner UUID and `SalePrice` == 0 → seller has prepared the transfer correctly
  - Report this state so backend can show "Seller has set land for sale to you" in the escrow UI
- After 24 hours without transfer: include REMINDER_DUE flag in report so backend can nudge seller

**Shared behavior:**
- Both handlers reuse the teleport + ParcelProperties read from Task 06-02
- Both report via callback to Java backend
- Both handle access denied gracefully

## Acceptance Criteria

- Auction monitoring detects AuthBuyerID changes and reports them
- Auction monitoring detects ownership changes and reports them
- Escrow monitoring detects ownership transfer to winner
- Escrow monitoring detects fraudulent transfer to third party
- Escrow monitoring detects correct sale setup (AuthBuyerID == winner, price == 0)
- 24-hour reminder flag included when seller hasn't transferred
- Access denied during monitoring reported as a distinct status
- All results include full parcel data snapshot
- Callbacks delivered reliably to Java backend

## Notes

- The Java backend handles the business logic (pause listing, freeze escrow, notify users). The bot just checks and reports facts.
- Auction monitoring stops when the auction ends (Java backend cancels the MONITOR_AUCTION task).
- Escrow monitoring stops when escrow completes or expires (Java backend cancels the MONITOR_ESCROW task).
- During escrow: the transition from "land set for sale to SLPAEscrow" to "land set for sale to winner" is expected and normal. The bot should report it as a state, not an error.
