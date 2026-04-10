# Task 10-04: Admin Dashboard

## Goal

Build the admin-only frontend section for managing reports, fraud flags, bans, disputes, and viewing platform stats.

## Context

Backend endpoints exist from Tasks 10-01 through 10-03, plus escrow disputes from Epic 05. Admin role is a flag on the users table. This is a single admin section within the existing Next.js app, not a separate application.

## What Needs to Happen

- **Admin route protection:**
  - All `/admin/*` routes require authenticated user with admin role
  - Non-admin users → redirect to homepage
  - Admin nav link visible only to admin users

- **Admin sidebar navigation:**
  - Dashboard (stats overview)
  - Reports queue
  - Fraud flags
  - Bans
  - Disputes
  - Users

- **Dashboard overview (`/admin`):**
  - Platform stats cards: active listings, total users, active escrows, completed sales (total), L$ volume (total)
  - Counts requiring attention: open reports, open fraud flags, active disputes
  - Quick links to each queue

- **Reports queue (`/admin/reports`):**
  - List of listings with reports, sorted by report count (highest first)
  - Each row: listing name, region, report count, latest report date, listing status
  - Expand/click → view all reports for that listing
  - Per report: reporter name, reason category, subject, details, date
  - Actions: dismiss report (with reason), warn seller (sends notification), suspend listing, cancel listing
  - Action confirmation modals

- **Fraud flags (`/admin/fraud-flags`):**
  - List of open flags, filterable by type (SAME_IP_BIDDING, LAST_SECOND_NEW_ACCOUNT, CANCEL_AND_SELL)
  - Each flag: type badge, auction name, flagged users, key data summary, date
  - Expand → full flag details (JSONB data rendered readably)
  - Actions: dismiss (with notes), take action (link to ban creation or user management)

- **Bans (`/admin/bans`):**
  - Active bans list with: type badge, IP/UUID, reason, created by, created at, expires at
  - Create ban form: type selector, IP input, SL UUID input, reason, expiry (permanent or date picker)
  - Lift ban button with reason modal
  - History tab showing expired/lifted bans

- **Disputes (`/admin/disputes`):**
  - List of escrow disputes (status DISPUTED from Epic 05)
  - Each dispute: auction name, buyer, seller, dispute reason, escrow amount, date
  - Expand → full escrow timeline, both parties' details, dispute text
  - Actions: resolve for buyer (refund), resolve for seller (release payout), notes field

- **User management (`/admin/users`):**
  - Search by: username, email, SL avatar name/UUID
  - User detail view: profile info, verification status, rating stats, listing history, bid history, cancellation history, ban history, fraud flags
  - Quick actions: ban user, suspend from listing, send notification

## Acceptance Criteria

- Admin section accessible only to admin-role users
- Dashboard shows accurate platform stats and queue counts
- Reports queue shows listings sorted by report count with all report details
- Fraud flags display with full context and actionable options
- Ban creation and management works (create, view, lift)
- Dispute queue shows escrow disputes with resolution actions
- User search works and shows comprehensive user history
- All admin actions require confirmation modal
- All admin actions logged (who, what, when)
- Responsive layout, works in dark/light mode

## Notes

- Keep the admin UI functional, not fancy. Tables and forms are fine - no need for complex visualizations.
- Admin role: for MVP, manually set `is_admin = true` in the database. No admin creation UI needed.
- Consider adding an admin action log page (audit trail) as a future enhancement. For now, just persist the logs.
- The dispute resolution actions should trigger the appropriate escrow service methods (refund or release).
