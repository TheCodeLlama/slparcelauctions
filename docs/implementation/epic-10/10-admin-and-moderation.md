# Phase 10: Admin & Moderation

_Reference: DESIGN.md Sections 8 (Listing Reports, Shill Bidding Prevention, Bans, Fraud Flags)_

---

## Goal

Admin dashboard for managing reports, fraud flags, bans, and platform moderation. No automated actions - all moderation is manual.

---

## What Needs to Happen

### Listing Reports

- Users can report ACTIVE listings (subject, reason category, details)
- One report per user per listing (prevents report bombing)
- Must be verified to report; cannot report own listing
- 3 unique reports on a single listing triggers admin email alert
- Reports NEVER automatically affect listing status (no auto-pause)
- Admin can: dismiss report, warn seller, suspend listing, cancel listing, ban seller

### Fraud Flags

- Automatic flagging (no automatic action):
  - Same IP across multiple bidding accounts on same auction
  - Last-second bids from new accounts (< 3 completed auctions)
- Flags appear in admin dashboard with details (IP, timing, related accounts)
- Admin can: review, dismiss, or take action
- All admin actions logged

### Bans

- Ban by IP address, SL avatar UUID, or both
- Banned IPs cannot register, log in, or place bids
- Banned SL avatars cannot re-verify with a new web account
- Bans can be permanent or time-limited (expiry date)
- Ban reason required

### Admin Dashboard

- Queue of reported listings sorted by report count
- Queue of open fraud flags
- User management (search by username/email/SL avatar, view history)
- Ban management (create, view, expire)
- Cancellation history per seller
- Escrow dispute queue
- Basic platform stats (active listings, completed auctions, total volume)

---

## Acceptance Criteria

- Users can report listings with required fields
- Duplicate reports from same user on same listing are rejected
- 3 reports on a listing triggers admin email
- Reports do not affect listing status without admin action
- Fraud flags generated automatically for matching patterns
- Admin can review, dismiss, and take action on reports and fraud flags
- Bans enforce correctly (blocked at login, registration, and bidding)
- Ban expiry works (time-limited bans auto-expire)
- Admin actions are logged with admin user ID and timestamp
- Admin dashboard accessible only to admin-role users

---

## Notes

- Admin role should be a simple role flag on the users table. No complex RBAC needed for MVP.
- Consider a simple admin-only frontend section rather than a separate admin app.
- Frivolous reporter tracking is a nice-to-have, not required for MVP.
