# Task 03-05: Listing Creation & Management UI

> **Before starting:** read [CONVENTIONS.md](../CONVENTIONS.md) for project-wide rules (no new migrations, Lombok required, vertical slices, feature-based packages).

## Goal

Build the frontend pages for creating, editing, previewing, and managing auction listings. This is the seller's primary workflow.

## Context

Backend endpoints exist from Tasks 03-02, 03-03, and 03-04. The "Digital Curator" design system, theme, and `components/ui/` primitives are in place from Epic 01 (see `docs/stitch_generated-design/DESIGN.md` and the primitives built in Task 01-06). This is a complex multi-step form — use `components/ui/` primitives for every field, button, card, chip, and modal.

There is no dedicated Stitch reference page for listing creation, so use the form patterns established by sign-in/sign-up (`docs/stitch_generated-design/{light,dark}_mode/sign_up/`) and the general tonal layering rules in `DESIGN.md`.

**Component reuse is non-negotiable on this page.** The multi-step form will tempt the contractor to build one monolithic `CreateListingPage` component with 500+ lines of inline JSX. Don't. Break it into:

- `components/listing/VerificationMethodPicker` — the Method A/B/C chooser
- `components/listing/ParcelDetailsPanel` — auto-populated read-only parcel view
- `components/listing/AuctionSettingsForm` — bid/reserve/duration/snipe-protection
- `components/listing/TagSelector` — categorized multi-select (reusable by browse/search filters too)
- `components/listing/PhotoUploader` — drag-and-drop multi-file upload
- `components/listing/ListingPreview` — renders exactly like a live listing card (reuses `ListingCard` with a preview flag)
- `components/ui/Stepper` / `components/ui/Wizard` — the multi-step navigation primitive (reusable anywhere with multi-step flows)
- `components/listing/ListingStatusBadge` — reuses `Chip`/`StatusBadge` with listing-specific status mapping

If a primitive is missing from `components/ui/`, add it there. The `TagSelector`, `Stepper`, `PhotoUploader`, and `ListingPreview` are all reused by other tasks (07-02 browse filters, admin moderation, etc.) — build them as first-class components now.

## What Needs to Happen

**Create Listing page (`/listings/create`):**
- Multi-step form or single page with sections:
  1. **Parcel Verification** - Choose verification method:
     - Method A: Enter parcel UUID, submit for verification, see result
     - Method B: Generate a parcel verification code, show instructions for in-world object
     - Method C: Show instructions to set land for sale to SLPAEscrow, parcel UUID input, submit
  2. **Parcel Details** - Auto-populated from verification, seller can review:
     - Parcel name, region, area, maturity (read-only from verification)
     - "Visit in Second Life" SLURL link
  3. **Auction Settings:**
     - Starting bid (L$ input)
     - Reserve price (optional, L$ input with explanation tooltip)
     - Buy-it-now price (optional, L$ input)
     - Duration dropdown (24h, 48h, 72h, 7 days, 14 days)
     - Snipe protection toggle + extension window dropdown (5/10/15/30/60 min)
  4. **Listing Details:**
     - Seller description (rich text or textarea)
     - Parcel tags (multi-select from predefined categories, grouped by category)
     - Additional photos upload (drag-and-drop or file picker, multiple files)
  5. **Review & Submit** - Preview of how the listing will look
- "Save as Draft" button available at any step
- "Submit for Payment" button on final step (transitions to DRAFT_PAID flow)
- Show verification status and next steps clearly at each stage

**Edit Listing page (`/listings/[id]/edit`):**
- Same form as create, pre-populated with existing data
- Only available for DRAFT and DRAFT_PAID status
- Locked/read-only for ACTIVE and later statuses

**My Listings in Dashboard:**
- Wire up the "My Listings" tab from Epic 02's dashboard
- List of user's auctions with status badges (color-coded: Draft=gray, Active=green, Ended=blue, etc.)
- Action buttons per listing based on status: Edit (draft only), Cancel, View
- "Create New Listing" prominent button

**Listing status tracking:**
- Show current status with a visual progress indicator (DRAFT → PAID → VERIFYING → ACTIVE → ...)
- Clear messaging at each stage about what the seller needs to do next

## Acceptance Criteria

- User can navigate the full listing creation flow: choose verification method → verify parcel → set auction params → add description/tags/photos → preview → submit
- Parcel verification auto-populates metadata (name, region, area, snapshot)
- Method C shows clear instructions for Sale-to-Bot with the SLPAEscrow account name
- Tags can be selected from categorized groups
- Draft can be saved and returned to later
- Listing preview accurately shows how the listing will appear to buyers
- My Listings tab shows all user's auctions with correct statuses
- Edit works for draft listings, blocked for active listings
- Cancel works with appropriate confirmation dialog
- Forms validate inputs (starting bid > 0, duration selected, etc.)
- Works in dark and light mode, responsive on mobile

## Notes

- The parcel tag selector should group tags by category (Terrain, Roads/Access, Location, Neighbors, Parcel Features) for usability.
- For photos, a simple multi-file upload with thumbnails is sufficient. No drag-to-reorder needed yet.
- The "payment" step is a placeholder for now - just show "Pay listing fee at an in-world SLPA terminal" with instructions. The actual in-world payment flow is Epic 05.
- Consider a stepper/wizard UI pattern for the multi-step creation flow.
