# Task 09-01: Notification Service & Event Dispatching

## Goal

Build the core notification service that receives events from other services and dispatches to the correct channels based on user preferences.

## Context

See DESIGN.md Section 11. Notification preferences stored as JSONB on users table (Epic 01 migrations). This service is the central hub - other services fire events into it, it handles routing.

## What Needs to Happen

- **NotificationService:**
  - Accept notification events with: recipient_user_id, category, title, body, data (JSON payload with relevant IDs/links)
  - Look up recipient's notification preferences
  - Dispatch to applicable channels: website (always), email (if enabled for category), SL IM (if enabled for category, respecting quiet hours)

- **Notification categories (enum):**
  - OUTBID, PROXY_EXHAUSTED
  - AUCTION_WON, AUCTION_LOST
  - AUCTION_ENDED_SELLER
  - ESCROW_FUNDED, ESCROW_TRANSFER_CONFIRMED, ESCROW_PAYOUT, ESCROW_EXPIRED
  - LISTING_VERIFIED, LISTING_SUSPENDED, LISTING_CANCELLED
  - REVIEW_RECEIVED, REVIEW_RESPONSE_WINDOW_CLOSING
  - REALTY_GROUP_INVITATION, REALTY_GROUP_MEMBER_CHANGE (placeholder for Phase 2)
  - SYSTEM_ANNOUNCEMENT

- **Preference checking:**
  - Read JSONB preferences from user record
  - Default preferences (if not explicitly set): email ON for all except marketing, SL IM ON for auction_won, escrow events, and listing status
  - Global mute overrides: if email_muted = true, skip all email regardless of per-category settings
  - Quiet hours for SL IM: if current SLT time is within user's quiet hours window, queue for later delivery (or skip)

- **Website notification persistence:**
  - Store in `notifications` table: id, user_id, category, title, body, data (JSON), read (boolean), created_at
  - Unread count endpoint: GET /api/v1/notifications/unread-count

- **REST endpoints:**
  - GET /api/v1/notifications - paginated notification feed (authenticated)
  - PUT /api/v1/notifications/{id}/read - mark as read
  - PUT /api/v1/notifications/read-all - mark all as read
  - GET /api/v1/notifications/unread-count - returns count

- **Integration hooks (fire notifications from existing services):**
  - Add notification calls to: bid service (outbid, proxy exhausted), auction end scheduler (won/lost, ended for seller), escrow service (all state transitions), verification service (verified/failed), review service (received, response window)
  - These are simple method calls from existing services into NotificationService

## Acceptance Criteria

- Notification events dispatched to correct channels based on user preferences
- Website notifications always stored regardless of other preferences
- Global mute prevents all email/SL IM delivery
- Default preferences applied for users who haven't configured settings
- Notification feed endpoint returns paginated results
- Mark read / mark all read works
- Unread count accurate
- Notification calls integrated into at least: bid service (outbid), auction end (won/lost), escrow (funded/payout)
- New notifications table created (if not in Epic 01 migrations, add migration)

## Notes

- The NotificationService should be async (don't block the calling service while dispatching to email/SL IM).
- For SL IM quiet hours: simplest approach is to just skip the IM (they get the website notification regardless). Don't queue for delayed delivery in MVP.
- The `data` JSON field on notifications holds context: auction_id, parcel_name, bid_amount, etc. Frontend uses this to render rich notification cards with links.
