# Listing image reorder + real-page draft editor — design

**Date:** 2026-05-07
**Status:** approved (brainstorm complete)

## Goal

Two intertwined seller experience improvements that ship together:

1. Sellers can reorder the photos on their listing — both during initial creation (wizard) and after the draft is saved (current `/activate` page).
2. The "preview" of a draft listing on `/listings/[publicId]/activate` becomes an actual preview of the real public listing page — same hero, gallery, parcel info, visit-in-SL block, layout-map, bid history, seller card, and right-rail layout that buyers see — with inline click-to-edit for every seller-controlled field, drag-reorder for photos, and a sticky top action bar carrying the listing-fee payment + "List Parcel" + "Delete Draft" actions.

The current edit surface (`/listings/[publicId]/edit`) is removed. All editing of a `DRAFT` auction happens on `/activate`; non-DRAFT branches of `/activate` are unchanged.

## Context

Today the seller experience is split:

- `/listings/create` — single-step wizard (`ListingWizardForm`): parcel lookup, settings, description, tags, photos.
- `/listings/[id]/edit` — the same wizard in `mode="edit"`. Pure edit-the-data form; no buyer-side preview anywhere on the page.
- `/listings/[id]/activate` — lifecycle dispatcher (`ActivateClient`): `DRAFT` shows a small `ListingPreviewCard` (cover photo + headline + price stats grid + tags) plus the listing-fee payment panel; `DRAFT_PAID` / `VERIFICATION_FAILED` / `VERIFICATION_PENDING` / `ACTIVE` / terminal statuses show their respective panels.

`AuctionPhoto.sortOrder` already exists and `AuctionPhotoRepository.findByAuctionIdOrderBySortOrderAsc` already orders by it; uploads assign `sortOrder = currentCount + 1`. There is no reorder endpoint and no reorder UI.

## Architecture

**Approach:** separate seller-preview client component sharing the buyer flow's leaf primitives (Approach 1 from brainstorm). `AuctionDetailClient.tsx` is untouched at the integration level. A new `DraftEditorClient.tsx` composes the same leaf components (`AuctionHero`, `ParcelInfoPanel`, `VisitInSecondLifeBlock`, `ParcelLayoutMapPlaceholder`, `BidHistoryList`, `SellerProfileCard`, plus a new `BidPanelPreview` instead of the buyer's `BidPanel`) into the same 8/4 grid. Each leaf gains additive optional props for the editor's needs; absent those props it renders identically to today's buyer flow.

A single integration-level **drift guard test** snapshots the section ordering on both clients and asserts they match, so a future change to one composition forces the matching change on the other.

## File structure

### Backend

```
backend/src/main/java/com/slparcelauctions/backend/auction/
  AuctionPhotoController.java        # MOD: add PATCH /photos/order endpoint
  AuctionPhotoService.java           # MOD: add reorder(Long auctionId, Long sellerId, List<UUID> orderedPublicIds)
  AuctionPhotoRepository.java        # unchanged
  AuctionPhoto.java                  # unchanged
  dto/
    AuctionPhotoOrderRequest.java    # NEW: { List<UUID> photoPublicIds }
  exception/
    PhotoSetMismatchException.java   # NEW: maps to 400 + code=PHOTO_SET_MISMATCH

backend/src/test/java/.../auction/
  AuctionPhotoServiceTest.java                # MOD: add reorder_* unit cases
  AuctionPhotoControllerIntegrationTest.java  # MOD: add reorder_* slice cases
```

### Frontend — new files

```
frontend/src/app/listings/(verified)/[publicId]/activate/
  DraftEditorClient.tsx              # composes editor; owns inline-edit + reorder mutations
  ActivateClient.tsx                 # MOD: DRAFT branch renders <DraftEditorClient/> instead of card+panel

frontend/src/components/listing/draft-editor/
  DraftActionBar.tsx                 # sticky top: fee + wallet + List Parcel + Delete Draft
  DraftSampleDataBanner.tsx          # "Preview shows sample bids — your live listing starts empty"
  EditableTitle.tsx
  EditableDescription.tsx
  EditableTags.tsx
  EditableSettingsModal.tsx          # whole-group settings edit (starting/reserve/buy-now/duration)
  EditableParcelModal.tsx            # parcel reselect with re-snapshot confirm
  EditablePhotoGallery.tsx           # wraps AuctionHero w/ drag-reorder + delete X + add tile
  BidPanelPreview.tsx                # read-only sample-data variant of BidPanel
  SampleBidHistory.ts                # frozen array of 4-5 sample BidHistoryEntry rows
  DeleteDraftModal.tsx               # rename of CancelListingModal w/ DRAFT-specific copy
  draftEditorMutations.ts            # per-field React Query mutations w/ optimistic update

frontend/src/hooks/
  useInlineEdit.ts                   # state machine: idle/editing/saving + Esc/Enter/blur handlers
  useReorderAuctionPhotos.ts         # mutation wrapper for PATCH /photos/order
```

### Frontend — modifications

```
frontend/src/components/auction/AuctionHero.tsx
  ADD: optional onReorder?(publicIds: string[]), onDelete?(publicId: string), onAdd?(file: File)
  When set: render drag handles, delete X, add-photo tile. Otherwise identical to today.

frontend/src/components/auction/ParcelInfoPanel.tsx
  ADD: optional editable?: { onTitleChange, onDescriptionChange, onTagsChange,
                             onSettingsChange, onParcelChange }
  When set: swap the relevant text rendering for the Editable* wrappers.

frontend/src/components/auction/BidHistoryList.tsx
  ADD: optional sampleEntries?: BidHistoryEntry[]
  When set: render the entries instead of the live query, show a "Sample" pill in the header.

frontend/src/components/listing/PhotoUploader.tsx
  ADD: drag-reorder of staged[] via @dnd-kit/sortable. No API change — order at
       bulk-upload time becomes persisted sortOrder via AuctionPhotoService.upload.

frontend/src/lib/api/auctionPhotos.ts
  ADD: reorder(auctionPublicId: string, photoPublicIds: string[]) → AuctionPhotoResponse[]

frontend/src/lib/admin/queryKeys.ts (or equivalent if scoped)
  -- N/A; the auction cache key already exists.

frontend/package.json
  ADD: @dnd-kit/core, @dnd-kit/sortable
```

### Frontend — removed

```
frontend/src/app/listings/(verified)/[publicId]/edit/page.tsx
  Deleted. No redirect. In-app links re-pointed to /activate via search-and-replace.
```

## Backend — reorder endpoint

### `PATCH /api/v1/auctions/{auctionPublicId}/photos/order`

**Request body**

```json
{ "photoPublicIds": ["uuid", "uuid", "uuid"] }
```

**Behavior**

1. `AuctionPhotoController.reorder` resolves `auctionPublicId` → `auctionId` (404 if missing).
2. Calls `AuctionPhotoService.reorder(auctionId, sellerId, photoPublicIds)`.
3. Service loads via `AuctionService.loadForSeller(auctionId, sellerId)` (403/404 on non-seller — existing pattern).
4. Service rejects if `auction.status` is not `DRAFT` or `DRAFT_PAID` → `InvalidAuctionStateException` (existing 409 mapping).
5. Service loads the auction's photo set, validates `set(body.photoPublicIds) == set(auction.photos)`. Mismatch → new `PhotoSetMismatchException` → 400 with `code=PHOTO_SET_MISMATCH`.
6. Service walks the body in order, sets `photo.sortOrder = i + 1` on each. Persists via JPA save loop (the dirty-checking flush at transaction commit batches the updates; per-row save calls are not needed). Native batch is optional but not required for 10-item lists.
7. Returns `List<AuctionPhotoResponse>` ordered by the new `sortOrder`.

**Response**

```json
[
  { "publicId": "...", "url": "/api/v1/photos/...", "contentType": "image/webp",
    "sizeBytes": 12345, "sortOrder": 1, "uploadedAt": "..." },
  ...
]
```

**Error codes**

| HTTP | Code | When |
|------|------|------|
| 400 | `PHOTO_SET_MISMATCH` | body's set ≠ auction's set (extra/missing/duplicate UUIDs) |
| 400 | `VALIDATION_ERROR` | empty `photoPublicIds`, malformed UUIDs |
| 401 | — | unauthenticated |
| 403 | — | non-seller (existing 404 pattern actually — leaks no draft existence) |
| 404 | — | auction or any photo not found (also returned for non-seller per existing pattern) |
| 409 | `INVALID_AUCTION_STATE` | status is not `DRAFT` or `DRAFT_PAID` |

The `403 vs 404 on non-seller` choice mirrors `AuctionPhotoService.fetchBytes` and `AuctionService.loadForSeller`: non-sellers get 404 to avoid leaking draft existence.

## Frontend — `DraftEditorClient`

### Composition

Renders the same 8/4 layout as `AuctionDetailClient`:

```
<DraftSampleDataBanner />
<DraftActionBar fee={...} wallet={...} onListParcel={...} onDeleteDraft={...} />
<main className="max-w-7xl mx-auto px-4 lg:px-8 pt-8 lg:pt-24 pb-24 lg:pb-12">
  <BreadcrumbNav region={...} title={...} />
  <div className="mt-6 grid grid-cols-1 lg:grid-cols-12 gap-6 lg:gap-12">
    <div className="lg:col-span-8 space-y-8 lg:space-y-12">
      <EditablePhotoGallery photos={...} onReorder={...} onDelete={...} onAdd={...} />
      <ParcelInfoPanel auction={...} editable={{ onTitleChange, onDescriptionChange,
                                                  onTagsChange, onSettingsChange, onParcelChange }} />
      <VisitInSecondLifeBlock ... />
      <ParcelLayoutMapPlaceholder />
      <BidHistoryList auctionPublicId={publicId} sampleEntries={SAMPLE_BIDS} />
      <SellerProfileCard seller={...} />
    </div>
    <aside className="hidden lg:block lg:col-span-4">
      <div className="sticky top-24">
        <BidPanelPreview auction={...} sampleBids={SAMPLE_BIDS} />
      </div>
    </aside>
  </div>
  {/* Mobile inline copy of the right-rail preview content. The desktop
      <aside> above is hidden <lg via `hidden lg:block`; this <lg-only
      block renders the same preview at the end of the main column so
      sellers on mobile still see the populated-state right-rail content. */}
  <div className="lg:hidden mt-8">
    <BidPanelPreview auction={...} sampleBids={SAMPLE_BIDS} />
  </div>
</main>
```

`AuctionEndedRow` is omitted — DRAFT auctions never have an `ENDED` outcome.
Mobile chrome `StickyBidBar` + `BidSheet` (the buyer's mobile pattern) is **not** rendered for the seller preview. The seller's only sticky chrome on mobile is the top action bar; `BidPanelPreview` instead renders inline at the end of the main column on `<lg` so the populated right-rail content is still visible without doubling up sticky surfaces.

### Inline edit interactions

Per-field click-to-edit. Lifecycle for each editor:

1. **Idle** — rendered as the buyer would see it.
2. **Editing** — input mounted, focused, original value seeded.
3. **Saving** — optimistic update applied to the auction cache; mutation in flight via `PATCH /api/v1/auctions/{publicId}` with the partial body.
4. **Success** — cache replaced with server response; editor returns to idle.
5. **Error** — cache rolled back (snapshot via `onMutate`); inline `FormError` rendered next to the field with the `ProblemDetail` message; editor stays open with the seller's last-typed value preserved.

Editor mapping:

| Region | Editor | Save trigger | Cancel trigger |
|---|---|---|---|
| Title | inline `<input>` matching headline typography | blur or Enter | Esc |
| Description | inline `<textarea>`, auto-resize | blur (Enter inserts newline) | Esc |
| Tags | popover with existing `TagSelector` + Done button | Done button or click outside | Esc |
| Settings (4 fields) | modal with existing `AuctionSettingsForm` | modal Save | modal Cancel/X |
| Parcel | modal with `ParcelLookupField` + re-snapshot confirm | modal Confirm change | modal Cancel |

Settings ship as a group because their validations are coupled (`buyNow > reserve > startingBid`). Inline-editing each price independently would let sellers leave invalid intermediate states.

Parcel reselect prompts an extra confirm step ("Re-snapshot will discard your current snapshot") because a parcel change also changes the snapshot URL and area / region metadata.

The `useInlineEdit<T>` hook owns the state machine; each editor wires a controlled input + the hook's handlers. Mutations live in `draftEditorMutations.ts` and all use the existing `PATCH /api/v1/auctions/{publicId}` endpoint — no new auction-level endpoints are needed.

### Photo gallery — `EditablePhotoGallery`

Wraps `AuctionHero` and adds three editor affordances:

- **Drag-reorder** — each thumbnail in the strip is a `useSortable` element from `@dnd-kit/sortable` (`verticalListSortingStrategy` works for the existing grid layout). Drag handle in the corner; pointer + keyboard activation. `onDragEnd` calls `useReorderAuctionPhotos`, which optimistically reorders the auction cache's `photos` array and fires `PATCH /photos/order`. Hero's primary slot re-derives from `photos[0]` on every render, so dragging to position 0 promotes a photo to the cover automatically.
- **Delete X** — top-right corner of each thumbnail. Click → small confirm dialog → `DELETE /photos/{publicId}` (existing endpoint). Delete is disabled while a drag is in progress (`isDragging` from `useSortable`).
- **Add tile** — appears at the end of the strip when `photos.length < 10`. Click → file picker → `POST /photos` (existing endpoint). New photo lands at end of strip with `sortOrder = count + 1` (existing upload service behavior).

Uploads-in-flight (no `publicId` yet) are excluded from the sortable set; they appear as ghosted tiles until the upload completes.

### Sticky top action bar — `DraftActionBar`

```
┌─ DraftActionBar (sticky top-0, z-40) ────────────────────────────────┐
│  Listing fee: L$X · Wallet: L$Y       [Delete Draft] [List Parcel]   │
└──────────────────────────────────────────────────────────────────────┘
```

- **List Parcel** — primary button. Click → `Confirm listing` modal showing fee + wallet + a confirm button. On confirm → existing listing-fee mutation (the one `ActivateListingPanel` calls today). Status flips to `DRAFT_PAID` → `ActivateClient`'s dispatcher re-renders into `VerificationMethodPicker`. Disabled while mutation is in flight.
- **Delete Draft** — danger link. Click → `DeleteDraftModal` (renamed `CancelListingModal` with copy "Delete this draft? This can't be undone."). On confirm → existing cancel endpoint → toast + redirect to `/dashboard/listings`. For `DRAFT` the existing cancel is effectively a hard-delete (no L$ has moved, no buyers have seen it).

The bar reads the same fee + wallet hooks `ActivateListingPanel` reads today — no new backend reads needed.

### Sample data

`SampleBidHistory.ts` exports a frozen `BidHistoryEntry[]` of 4-5 synthetic rows with monotonically-increasing amounts, descending `placedAt` offsets from `Date.now()`, and bidder display names like `"Sample Bidder N"`. `BidPanelPreview` derives:

- `currentBid` = max amount in array
- `bidderCount` = unique bidder publicIds in array
- `endsAt` = `now + auction.durationHours * 3600 * 1000` (so the preview countdown reads "Xh Xm" matching the seller's chosen duration)

so all sample-driven sections are internally consistent. The bid input on `BidPanelPreview` is `disabled` with the message "Listing not yet active." `BidPanelPreview` does **not** subscribe to WebSocket and does **not** read auth.

`BidHistoryList` shows the same `SAMPLE_BIDS` array when its new `sampleEntries` prop is set, with a small `Sample` pill in the section header. `DraftSampleDataBanner` at the top of the page reads:

> This is a preview with sample bids and activity. Your live listing will start empty.

## Wizard photo reorder — `/listings/create`

`PhotoUploader.tsx` already owns the staged array and an `onStagedChange` callback. The change wraps the existing thumbnail grid in `<DndContext> + <SortableContext>` and makes each `<li>` a `useSortable` element with a drag handle. `onDragEnd` reorders the staged array and calls `onStagedChange`. No API change — the seller's order at "Save & continue" time is the order `bulkUpload(staged)` walks, and `AuctionPhotoService.upload`'s existing `currentCount + 1` assignment makes that the persisted `sortOrder`.

## Routing changes

- **Delete** `frontend/src/app/listings/(verified)/[publicId]/edit/page.tsx`. No redirect — the route is seller-only with no email links, no SL-terminal links, no SEO.
- **Search-and-replace** all in-app `/listings/[id]/edit` links to `/listings/[id]/activate`. Realistically only `/dashboard/listings` row actions are affected; an exhaustive grep at implementation time will catch any others.
- **Edit** `ActivateClient.tsx` so the `DRAFT` branch renders `<DraftEditorClient />` instead of `<ListingPreviewCard isPreview /> + <ActivateListingPanel />`. All other branches untouched.

## Error handling & edge cases

### Inline-edit failures

- **Validation (400)** — backend `ProblemDetail` message renders inline next to the field; editor stays open.
- **Auth/ownership (403/404)** — toast + redirect to `/dashboard/listings`.
- **Conflict (409)** — auction transitioned out of `DRAFT` (e.g., another tab paid the fee). Refetch via `useActivateAuction`; the dispatcher renders the next-status panel. Toast: "This listing has moved past draft. Editing is closed."

### Photo reorder edge cases

- **Photo deleted in another tab during reorder** — backend's set-equality check returns 400 `PHOTO_SET_MISMATCH`. Frontend refetches the auction, re-renders against the canonical photo set, toasts "Photos changed in another window — your reorder was discarded."
- **Photo added in another tab during reorder** — same shape; same recovery.
- **Reorder while upload is in-flight** — uploading thumbnails (no `publicId`) are excluded from the sortable set; the new photo lands at end of strip on its upload-success render.
- **Reorder request fails (network/500)** — optimistic rollback to pre-drag order; toast "Couldn't save photo order. Try again." No retry — seller re-drags.

### Add/delete photo edge cases

- **Upload exceeds 10** — add tile is hidden when `photos.length >= 10`. Backend's `PhotoLimitExceededException` is defense-in-depth.
- **Last photo deleted** — hero falls back to parcel snapshot URL (existing `AuctionHero` behavior).

### Settings/parcel modal

- **Save fails** — modal stays open with inline error; cancel/X still available.
- **Parcel reselect** — backend rejects parcel changes only on `DRAFT_PAID`+. On `DRAFT` it accepts and re-snapshots. Lookup-side errors (parcel not found, owner mismatch) surface via existing `ParcelLookupField`.

### List Parcel

- Button disables itself while the listing-fee mutation is in flight (existing `ActivateListingPanel` behavior lifted into the action bar). Backend endpoint is idempotent so a missed disable is harmless.

### Loss of work

- Inline edits save per-field on blur — no "unsaved changes" exit guard needed.
- Settings + parcel modals hold draft-of-modal state in their own local state and discard on cancel; intentional.

## Testing

### Backend

- **`AuctionPhotoServiceTest.reorder_*`** — Mockito unit cases:
  - happy path: renumbers `sortOrder` 1..N
  - rejects non-seller (loadForSeller throws)
  - rejects on `ACTIVE` / `ENDED` / etc. (any non-DRAFT/DRAFT_PAID)
  - rejects body missing a UUID, body with extra UUID, body with duplicate UUIDs, empty body
- **`AuctionPhotoControllerIntegrationTest.reorder_*`** — `@SpringBootTest` slice:
  - 200 + reordered DTO array
  - 400 with `code=PHOTO_SET_MISMATCH` on set mismatch
  - 404 on non-seller (existing pattern)
  - 409 with `code=INVALID_AUCTION_STATE` on `ACTIVE`
  - 401 anonymous

No Flyway migration — `sort_order` already exists.

### Frontend

- **`useInlineEdit.test.ts`** — state machine transitions; Esc-cancels-pending-edit; Enter/blur saves.
- **`EditableTitle.test.tsx`, `EditableDescription.test.tsx`, `EditableTags.test.tsx`** — render in idle, click → editing, type → save → assert `PATCH /api/v1/auctions/{publicId}` called with the expected partial body. Mutation reject → rollback + inline error.
- **`EditableSettingsModal.test.tsx`, `EditableParcelModal.test.tsx`** — open/close, validation error path, success → auction query invalidated. Parcel modal: re-snapshot confirm step.
- **`EditablePhotoGallery.test.tsx`** — drag-reorder calls reorder mutation with new order; reorder failure rolls back cache + toast; delete X confirm + delete mutation; add tile hidden at 10 photos.
- **`BidPanelPreview.test.tsx`** — sample data renders, bid input is `disabled`, no WebSocket subscription, no real auth read.
- **`SampleBidHistory.test.ts`** — internal consistency: `currentBid = max amount`, `bidderCount = unique bidder publicIds`.
- **`DraftEditorClient.test.tsx`** — integration: full real-page composition mounts; top action bar visible; sample-data banner visible; "Sample" pills on `BidHistoryList` and `BidPanelPreview`; edit a field → mutation called → cache updated.
- **`PhotoUploader.test.tsx`** — extend with drag-reorder cases; `onStagedChange` called with new order.
- **`drift-guard.test.tsx`** — render both `AuctionDetailClient` and `DraftEditorClient` against equivalent fixture auctions; assert section testids appear in identical order. Catches future drift.

### Postman

`PATCH /api/v1/auctions/{publicId}/photos/order` mirrored into the `Auction Photos` folder of the SLPA collection, threading `{{auctionId}}` and `{{photoId}}`. Test script asserts 200 + response body contains the expected reordered IDs.

### Manual smoke

- Wizard: drag-reorder staged photos before save; verify `sortOrder` matches drop order after persist.
- Activate page DRAFT: edit each field type in place; drag a photo to position 0; click List Parcel + walk through fee modal; click Delete Draft + confirm.
- Multi-tab: open the activate page in two tabs; delete a photo in tab 1; drag-reorder in tab 2; verify `PHOTO_SET_MISMATCH` recovery toast.

## Out of scope

- Reorder during `DRAFT_PAID`+ statuses. Backend rejects today and we keep it that way. No UX path needed — once you've paid the listing fee, photo order is frozen along with the rest of the data.
- Bulk multi-select in the gallery (shift-click range, drag-select).
- Explicit "Set as cover" button — dragging to position 0 is the cover-set affordance.
- Image cropping / rotation editor.
- Reordering parcel snapshot relative to seller-uploaded photos. Snapshots are a separate `PhotoSource` and are not currently surfaced in the gallery for reorder.

## Acceptance

- Sellers can drag-reorder staged photos in `/listings/create` before saving.
- Sellers can drag-reorder persisted photos on `/listings/[id]/activate` while the auction is `DRAFT`. Drop order persists after refresh.
- `/listings/[id]/activate` for `DRAFT` renders the same hero / parcel info / visit-in-SL / layout map / bid history / seller card composition the buyer sees on `/auction/[id]`, with sample populated bid history + read-only `BidPanel`-shaped right rail.
- Every seller-controlled field on the activate-page preview is click-to-edit and saves on blur/Enter/modal-confirm.
- Sticky top action bar shows listing fee + wallet, "List Parcel" runs the existing fee mutation + transitions to `DRAFT_PAID`, "Delete Draft" hard-deletes via the existing cancel endpoint.
- `/listings/[id]/edit` is removed; in-app links re-pointed to `/activate`.
- All existing buyer-flow tests on `AuctionDetailClient` and the leaf primitives still pass.
- Drift guard test passes.
- New backend reorder endpoint mirrored into the SLPA Postman collection.
