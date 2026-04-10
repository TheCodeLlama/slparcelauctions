# Task 04-03: Snipe Protection

> **Before starting:** read [CONVENTIONS.md](../CONVENTIONS.md) for project-wide rules (no new migrations, Lombok required, vertical slices, feature-based packages).

## Goal

Implement the snipe protection system that extends auctions when bids arrive near the end.

## Context

See DESIGN.md Section 4.7 (snipe protection). Sellers opt-in during listing creation (already stored from Epic 03). The auction record has `snipe_protection_enabled`, `snipe_extension_minutes`, `ends_at`, and `original_ends_at` fields.

## What Needs to Happen

- Add snipe protection check to the bid placement flow (hook into BidService from Task 04-01):
  - When a bid is placed, check if auction has snipe protection enabled
  - Calculate if the bid falls within the extension window (bid timestamp > ends_at - snipe_extension_minutes)
  - If yes: extend ends_at by snipe_extension_minutes from NOW (not from original ends_at)
  - Extensions stack: each qualifying bid resets the timer
  - original_ends_at never changes (tracks the auction's original end time)

- Update the auction record on extension:
  - Set new ends_at
  - Log the extension event (for display in bid history and WebSocket broadcast)

- The extension applies equally to manual bids and proxy auto-bids (proxy bids that trigger within the window extend the auction too)

## Acceptance Criteria

- Bid placed within the extension window extends ends_at by the configured minutes
- Bid placed outside the extension window does NOT extend
- Extensions stack: 3 bids in a row within the window each extend the auction
- original_ends_at is preserved (never modified after initial ACTIVE transition)
- Proxy auto-bids that land within the window also trigger extension
- Extension event is logged/recorded for display purposes
- Auction with snipe protection disabled is never extended

## Notes

- Keep this simple and contained - it's a hook in the bid placement flow, not a standalone service.
- The key calculation: `if (now > ends_at - extensionMinutes) { ends_at = now + extensionMinutes }`
- The WebSocket broadcast of the new end time is Task 04-04. This task just updates the database correctly.
- Test edge case: bid arrives at the exact moment the auction is supposed to end.
