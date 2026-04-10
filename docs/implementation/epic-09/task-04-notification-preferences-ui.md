# Task 09-04: Notification Preferences UI

> **Before starting:** read [CONVENTIONS.md](../CONVENTIONS.md) for project-wide rules (no new migrations, Lombok required, vertical slices, feature-based packages).

## Goal

Build the settings page where users configure their notification preferences per category and channel.

## Context

Preferences stored as JSONB on users table. Backend preference checking exists from Task 09-01. This is the frontend for managing those preferences.

## What Needs to Happen

- **Settings page at `/settings/notifications`:**
  - Accessible from user dashboard / account settings

- **Preference grid:**
  - Rows: notification categories (grouped logically)
    - Bidding: Outbid, Proxy bid exhausted
    - Auctions: Auction won, Auction lost, Auction ended (as seller)
    - Escrow: Escrow funded, Transfer confirmed, Payout received, Escrow expired
    - Listings: Listing verified, Listing suspended, Listing cancelled
    - Reviews: Review received, Response window closing
    - System: Announcements
  - Columns: Email toggle, SL IM toggle
  - Each cell is a toggle switch
  - Website column shown but grayed out / always on (visual indicator that these can't be disabled)

- **Global controls (above the grid):**
  - "Mute all emails" master toggle
  - "Mute all SL IMs" master toggle
  - When master toggle is OFF: individual toggles grayed out / disabled

- **Quiet hours section (below grid):**
  - "SL IM quiet hours" with start time and end time pickers
  - Times in SLT (Pacific) with label
  - Enable/disable toggle for quiet hours
  - Example: "No SL IMs between 11:00 PM and 8:00 AM SLT"

- **Backend endpoint:**
  - PUT /api/v1/users/me/notification-preferences - save all preferences as JSONB
  - GET /api/v1/users/me/notification-preferences - load current preferences (with defaults for unset categories)

- **Save behavior:**
  - Auto-save on toggle change (no explicit save button) with debounce
  - Toast confirmation: "Preferences saved"
  - Optimistic UI: toggle immediately, revert on save failure

## Acceptance Criteria

- All notification categories displayed with email and SL IM toggles
- Global mute toggles disable/gray out individual toggles
- Quiet hours configurable with time pickers
- Preferences persist after page reload
- Auto-save on change with confirmation toast
- Default preferences shown for new users who haven't configured anything
- Responsive layout (grid collapses cleanly on mobile)
- Works in dark/light mode

## Notes

- The JSONB structure should be straightforward: `{ "email": { "OUTBID": true, "AUCTION_WON": true, ... }, "sl_im": { ... }, "email_muted": false, "sl_im_muted": false, "quiet_hours": { "enabled": true, "start": "23:00", "end": "08:00" } }`
- Category labels should be human-friendly, not enum names. Map OUTBID → "Outbid notifications", etc.
- Consider a "Reset to defaults" button for users who get lost in the toggles.
