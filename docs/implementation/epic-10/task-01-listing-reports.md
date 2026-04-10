# Task 10-01: Listing Report System

> **Before starting:** read [CONVENTIONS.md](../CONVENTIONS.md) for project-wide rules (no new migrations, Lombok required, vertical slices, feature-based packages).

## Goal

Allow verified users to report listings, and surface reports to admins with the 3-report threshold alert.

## Context

See DESIGN.md Section 8. The `listing_reports` table exists from Epic 01 migrations. Reports are informational only - they never automatically affect listing status.

## What Needs to Happen

- **Report submission:**
  - POST /api/v1/auctions/{id}/report - submit a report (authenticated, verified users only)
  - Fields: subject (text), reason_category (enum: MISLEADING_DESCRIPTION, WRONG_LOCATION, WRONG_SIZE, SCAM_SUSPECTED, INAPPROPRIATE_CONTENT, OTHER), details (text, max 1000 chars)
  - Validation: auction must be ACTIVE, user must be verified, one report per user per listing, cannot report own listing

- **3-report threshold alert:**
  - After each new report: count unique reporters for that listing
  - If count reaches 3: send email to admin address (use notification service from Epic 09)
  - Only alert once per listing (flag `admin_alerted` on listing or track in reports)

- **Report query endpoints (for admin, Task 10-04):**
  - GET /api/v1/admin/reports - paginated list of all reports, sortable by report count per listing
  - GET /api/v1/admin/reports/listing/{id} - all reports for a specific listing
  - PUT /api/v1/admin/reports/{id}/dismiss - dismiss a report (with reason)
  - Reports grouped by listing with aggregate count

- **Frontend report button:**
  - Flag/report icon on auction detail page (visible to verified, logged-in users)
  - Click → modal with: subject input, reason category dropdown, details textarea
  - Confirmation: "Report submitted. Our team will review it."
  - If already reported: button shows "Reported ✓" (disabled)

## Acceptance Criteria

- Verified users can submit reports on active listings
- One report per user per listing enforced
- Cannot report own listing
- Non-verified users see disabled report button with tooltip
- 3 unique reports triggers admin email (once per listing)
- Reports never auto-pause or auto-cancel listings
- Admin endpoints return reports grouped by listing with counts
- Report modal works, shows confirmation, disables after submission
- Reason category required, details optional

## Notes

- The reason categories help admins triage quickly. Don't overthink them - OTHER covers edge cases.
- The admin email for 3-report threshold can go to a configured admin email address. Use the email channel from Epic 09.
- No auto-pause is an explicit design decision to prevent griefing via coordinated false reports.
