# Phase 9: Notifications

> **Before starting:** read [CONVENTIONS.md](../CONVENTIONS.md) for project-wide rules (no new migrations, Lombok required, vertical slices, feature-based packages).

_Reference: DESIGN.md Section 11_

---

## Goal

User-configurable notification system across three channels: website (always on), email (optional), and SL IM (optional). Users control what they receive per category.

---

## What Needs to Happen

### Notification Categories

- Bidding (outbid, proxy exhausted)
- Auction won/lost
- Auction ended (seller)
- Escrow events (funded, confirmed, payout)
- Listing status (suspended, cancelled, verified)
- Reviews (received, response window closing)
- Realty group (invitations, member changes) - wire up for Phase 2
- Marketing (featured listings, tips) - placeholder, not active yet

### Channels

- **Website:** Always on, cannot be disabled. In-app notification feed + WebSocket push for real-time.
- **Email:** Per-category toggle, default ON for most categories. Unsubscribe link in every email.
- **SL IM:** Per-category toggle, default ON for important events only. Quiet hours setting (no IMs between user-configured times).

### User Preferences

- Per-category toggles for email and SL IM
- Global mute switches (mute all email, mute all SL IM)
- Quiet hours for SL IMs (start/end time in SLT)
- Settings page on frontend
- Preferences stored as JSONB on the users table

### Notification Dispatch

- Backend checks user preferences before sending each notification
- Notification service receives events from other services (auction engine, escrow, etc.) and dispatches to configured channels
- Email via a standard email service (SendGrid, SES, or similar)
- SL IM dispatch: queue messages for delivery via in-world terminal (the terminal calls `llInstantMessage`)
- Website notifications persisted for the notification feed

---

## Acceptance Criteria

- Users can configure notification preferences per category per channel
- Global mute switches override per-category settings
- Quiet hours prevent SL IMs during configured window
- Notifications dispatched to correct channels based on user preferences
- Website notification feed shows all notifications (paginated)
- WebSocket push delivers real-time website notifications
- Email notifications include unsubscribe link
- SL IM messages queued for terminal delivery
- Notification preferences page works on frontend

---

## Notes

- SL IM delivery depends on having an in-world terminal script running (Phase 11). For this phase, queue the messages and provide an endpoint the terminal can poll or receive via HTTP-in.
- This phase can start as soon as Phase 2 is complete and expand incrementally as other phases add new notification events.
- Email provider choice is up to you.
