# Task 02-04: Dashboard - Verification Flow UI

> **Before starting:** read [CONVENTIONS.md](../CONVENTIONS.md) for project-wide rules (no new migrations, Lombok required, vertical slices, feature-based packages).

## Goal

Build the user dashboard page with the SL avatar verification flow, verification status display, and account settings.

## Context

Backend endpoints exist from Tasks 02-01 (code generation) and 02-03 (profile API). The Stitch-generated dashboard design is in `docs/stitch-generated-design/user_dashboard/`. The Gilded Slate design system is established from Epic 01.

## What Needs to Happen

- Build the Dashboard page at `/dashboard` (authenticated route - redirect to /login if not logged in)
- **Verification section** (shown prominently if user is NOT verified):
  - Clear message explaining that SL avatar verification is required to bid or list
  - "Generate Verification Code" button
  - On click: calls POST /api/v1/verification/generate, displays the 6-digit code prominently
  - Show code with countdown timer (15-minute expiry)
  - Instructions: "Go to any SLPA Verification Terminal in Second Life, touch it, and enter this code"
  - After code expires, allow generating a new one
- **Verified status display** (shown if user IS verified):
  - SL avatar name and display name
  - Account age (calculated from born date)
  - Payment info status badge
  - Verification date
  - Green checkmark / "Verified" badge
- **Account settings section:**
  - Display name edit (inline edit or modal)
  - Bio/description edit (textarea)
  - Profile picture upload (click to upload, preview before saving)
  - Email (display only for now)
- **Tab structure for future content:**
  - Set up tabs: "Overview" (verification + settings), "My Bids", "My Listings"
  - "My Bids" and "My Listings" can be placeholder/empty state for now ("No bids yet" / "No listings yet") - these are built in later epics
- Match the Gilded Slate design system (dark/light mode, no solid borders, amber accents)

## Acceptance Criteria

- Dashboard loads for authenticated users, redirects to /login for unauthenticated
- Unverified users see the verification code flow prominently
- Clicking "Generate Code" displays a 6-digit code with a 15-minute countdown
- Already-verified users see their linked SL identity info with a verified badge
- Profile picture upload works (select file, preview, save)
- Display name and bio can be edited and saved
- Tab navigation works (Overview, My Bids, My Listings)
- "My Bids" and "My Listings" tabs show empty state placeholders
- Page works in both dark and light mode
- Responsive on mobile

## Notes

- Reference the Stitch dashboard design for layout structure and visual cues.
- The verification code should be displayed in large, easily readable text (the user needs to type it in-world).
- Consider adding a "copy to clipboard" button next to the code.
- The countdown timer should show minutes:seconds and auto-clear when expired.
