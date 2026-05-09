# Default Cover Image — Design

**Status:** Draft for review
**Date:** 2026-05-08
**Author:** brainstorming session

## Problem

Sellers who run many parcel auctions often want a consistent first photo on every listing — a banner, watermark, or branded image that signals "this is one of my listings." Today every photo is uploaded per-listing, and the seller's only auto-inserted photo is the SL parcel snapshot at `sortOrder=0`. There is no way to set a recurring "this image goes on all my listings."

This design adds a per-user **default cover image** that is automatically inserted as the first photo (`sortOrder=0`) on every new listing the user creates after setting it. Once inserted, it behaves as an ordinary `AuctionPhoto` row — fully removable and reorderable through the existing drag-reorder UI, with no listing-specific awareness of its origin beyond a `PhotoSource` enum value used for analytics.

While we are in the photo pipeline, we are also tightening the listing-photo dimension cap (4096 → 2048), removing the user-facing 2MB byte cap, and moving the resize step from server-side-only to client-side-first with a server-side backstop. This addresses long-standing UX friction where sellers' phone photos were rejected as "too large" while we were going to compress them anyway.

## Goals

1. Per-user `default cover image`, settable on the profile/account settings page, stored in S3 next to other user-scoped images.
2. Auto-insertion at `sortOrder=0` on every new listing draft created after the cover is set; existing drafts and live listings are not modified.
3. Once inserted, the cover photo is editable, removable, and reorderable like any other listing photo. There is no special-case display behavior — the existing gallery uses `sortOrder` only.
4. Both the default cover image and listing photos resize to a max dimension of 2048px (preserving aspect ratio) before storage.
5. No frontend file-size cap on either upload path. Server keeps a 25MB byte bound as DoS protection only — never surfaced to the user as "file too big."
6. Resize happens client-side first (faster uploads, less server load); the existing server-side `ListingPhotoProcessor` stays as a defense-in-depth backstop for any client that bypasses the resize.

## Non-goals

- Per-listing cover image overrides at the user level (e.g. "use this cover for parcels in Heterocera, that one for Sansara"). Single image per user.
- Retroactive application to existing drafts. The user's words: "for any parcel listing that is *made*" — going forward only.
- Propagating user-default updates to existing listings. Once copied into a listing's photo set, the cover is owned by that listing.
- Changing avatar onboarding (`AvatarCropper` / `cropImage.ts`). That flow has different requirements (square crop, fixed 1024px WebP output) and stays as-is.
- Migrating existing listing photos to the new 2048px cap. Existing rows keep their stored bytes; the cap applies to new uploads.

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│ Settings Page                                            │
│   - Default Cover Image card (empty / uploading / set)  │
│   - File picker → resizeImage() → multipart PUT         │
└─────────────────────────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────┐
│ Backend                                                  │
│   GET    /api/v1/users/me/default-cover                 │
│   PUT    /api/v1/users/me/default-cover  (multipart)    │
│   DELETE /api/v1/users/me/default-cover                 │
│                                                          │
│   UserDefaultCoverService                                │
│     ├─ ListingPhotoProcessor (shared, parameterised)    │
│     │     maxDim=2048, maxBytes=25MB                    │
│     ├─ ObjectStorageService (S3/MinIO)                  │
│     │     users/{userId}/default-cover-{uuid}.{ext}     │
│     └─ User table (3 new columns)                       │
└─────────────────────────────────────────────────────────┘

Listing creation flow:
  1. AuctionService.createDraft(...)
  2. UserDefaultCoverPhotoService.applyTo(auction)   ← new, sortOrder=0
  3. ParcelSnapshotPhotoService.refreshFor(auction)  ← existing, sortOrder=next
  4. Seller uploads via PhotoUploader (resizeImage → multipart POST)
```

## Components

### Backend — Data model

Add three columns to `users`:

| Column | Type | Nullable | Notes |
|---|---|---|---|
| `default_cover_object_key` | varchar(500) | yes | `users/{userId}/default-cover-{uuid}.{ext}` |
| `default_cover_content_type` | varchar(100) | yes | e.g. `image/jpeg`, `image/png`, `image/webp` |
| `default_cover_size_bytes` | bigint | yes | post-resize byte count |

All three are `null` together (cover unset) or all non-`null` (cover set). The migration backfills `null` for existing rows.

`User` entity gains matching `@Column` fields plus a derived `boolean hasDefaultCover()` helper for callers that only need a presence check.

Add to `PhotoSource` enum: `USER_DEFAULT_COVER`. Existing values (`SELLER_UPLOAD`, `SL_PARCEL_SNAPSHOT`) unchanged. `AuctionPhoto.source` is `@Enumerated(EnumType.STRING)` (stored as VARCHAR), so adding a new value is a code-only change with no DDL. The enum value is recorded on each auto-inserted `AuctionPhoto` row for analytics/debug; gallery rendering is unchanged (sorts by `sortOrder` only).

### Backend — `ListingPhotoProcessor` parameterisation

Today this processor hard-codes `maxDim=4096` and `maxBytes=2 * 1024 * 1024`. Change signature so callers pass these in, and update the existing wiring:

- Listing-photo path: `(maxDim=2048, maxBytes=25 * 1024 * 1024)` — tightened dim cap, lifted byte cap.
- Default-cover path: `(maxDim=2048, maxBytes=25 * 1024 * 1024)` — same bounds.

Configuration keys `slpa.photos.max-bytes` and `slpa.photos.max-dim` move to `slpa.photos.listing.max-bytes` / `slpa.photos.listing.max-dim` and gain mirror keys `slpa.photos.user-default-cover.max-bytes` / `.max-dim`. Defaults match the values above.

The 25MB cap is a JVM-OOM guard for malicious clients that bypass the client-side resize. It is not surfaced to the user; the upload card has no "file too large" copy.

### Backend — `UserDefaultCoverService`

New service at `backend/src/main/java/com/slparcelauctions/backend/user/UserDefaultCoverService.java` exposing:

- `UserDefaultCoverDto upload(Long userId, MultipartFile file)` — runs the file through `ListingPhotoProcessor`, deletes any existing `users/{userId}/default-cover-*` object, writes the new one to a fresh UUID-suffixed key, updates the three `User` columns, returns the DTO.
- `UserDefaultCoverDto get(Long userId)` — returns `{ url, contentType, sizeBytes }` or throws `UserDefaultCoverNotFoundException` (mapped to 404).
- `void delete(Long userId)` — deletes the S3 object and clears the three columns.

`UserDefaultCoverDto.url` is a presigned S3 URL (matches the avatar pattern). The frontend resolves it via the existing `apiUrl()` helper.

### Backend — `UserDefaultCoverPhotoService`

New service at `backend/src/main/java/com/slparcelauctions/backend/auction/UserDefaultCoverPhotoService.java` exposing:

- `void applyTo(Auction auction)` — copies the user's default cover (if set) into the listing's photo set:
  1. Idempotency guard: if any `AuctionPhoto` already exists for this auction with `source=USER_DEFAULT_COVER`, return immediately. Protects against accidental double-invocation; the design only calls `applyTo` once per draft creation.
  2. Resolve `auction.getSeller()` and read `defaultCoverObjectKey`. If null → no-op return.
  3. Server-side S3 copy from `users/{userId}/default-cover-{uuid}.{ext}` to `listings/{auctionId}/{newUuid}.{sameExt}`.
  4. Insert `AuctionPhoto` with `source=USER_DEFAULT_COVER`, `sortOrder=0`, `objectKey` pointing at the listing-scoped path, `contentType` and `sizeBytes` copied from the user's columns.
  5. On any failure (S3 copy 5xx, IOException), log warn-level and return — do not block listing creation. Same fail-soft posture as `ParcelSnapshotPhotoService`.

This service runs **before** `ParcelSnapshotPhotoService.refreshFor` in the listing-creation pipeline (see "Order of operations" below).

### Backend — `ParcelSnapshotPhotoService` change

Today `ParcelSnapshotPhotoService.refreshFor` hard-codes `sortOrder=0`. Change it to `sortOrder = nextAvailableSortOrder(auctionId)` where the helper queries `MAX(sortOrder)+1` from the `auction_photos` table (or `0` if empty). This way the snapshot lands at `0` when no default cover ran, or `1` when default cover already claimed `0`.

### Backend — `OnboardingController` / settings controller

Add three endpoints under `/api/v1/users/me/default-cover` exposed by a new `UserDefaultCoverController` (kept separate from `OnboardingController` so onboarding stays focused on its forced-step flow):

```
GET    /api/v1/users/me/default-cover   → 200 {url, contentType, sizeBytes} | 404
PUT    /api/v1/users/me/default-cover   → 200 {url, contentType, sizeBytes}
                                        (multipart, field=file)
DELETE /api/v1/users/me/default-cover   → 204
```

`UserResponse` (the `/me` payload) gains `defaultCoverUrl: String?` so the settings page can render the card from the cached `/me` response without a separate round-trip on page load.

### Backend — Listing-creation hook

Wire `UserDefaultCoverPhotoService.applyTo` into the existing draft-creation path (likely `AuctionService.createDraft` or wherever `ParcelSnapshotPhotoService.refreshFor` is currently invoked). Order:

1. Auction draft persisted.
2. `UserDefaultCoverPhotoService.applyTo(auction)` — claims `sortOrder=0` if cover is set.
3. `ParcelSnapshotPhotoService.refreshFor(auction)` — claims `sortOrder=1` (or `0` if no cover ran).
4. Returned to the caller; seller uploads land at `sortOrder=2+` / `1+` / `0+` depending on what auto-inserted.

The 10-photo-per-listing cap (`slpa.photos.max-per-listing`) stays at 10 and counts auto-inserts (matches current behavior). Worst case: cover + snapshot = 2 of 10. Seller can remove either to reclaim slots.

### Frontend — `resizeImage` utility

New file at `frontend/src/lib/image/resizeImage.ts`:

```ts
import imageCompression from "browser-image-compression";

export async function resizeImage(
  file: File,
  opts: { maxDim: number },
): Promise<File> {
  return imageCompression(file, {
    maxWidthOrHeight: opts.maxDim,
    useWebWorker: true,
    initialQuality: 0.85,
    fileType: file.type, // preserve JPEG→JPEG, PNG→PNG, WebP→WebP
  });
}
```

`browser-image-compression` is added to `frontend/package.json`. It runs in a Web Worker (off main thread), handles iPhone EXIF orientation (so portrait photos don't land sideways), strips EXIF metadata for privacy, and returns a `File` ready for `FormData.append`. The avatar cropper's existing `cropImage.ts` is unaffected — it has different output requirements (square crop, fixed 1024px WebP).

### Frontend — Settings page card

New component `frontend/src/components/user/DefaultCoverCard.tsx`. Three states:

- **Empty:** "No default cover set" + a primary `Choose image` button. Picking a file kicks off `resizeImage(file, { maxDim: 2048 })` → multipart PUT immediately. No client-side validation, no preview-then-confirm step.
- **Uploading:** disabled card with spinner during the PUT.
- **Set:** thumbnail preview (rendered via `apiUrl(defaultCoverUrl)`), a `Replace` button (re-runs the picker flow), a `Remove` button (DELETE → returns to empty).

Helper copy under the card: "Used as the first photo of every new listing you create. Existing listings are not affected." This surfaces both the forward-only application and the "removing this won't pull it from existing listings" semantics in one sentence so users don't have to discover them through usage.

The card is added to the existing profile/account settings page (alongside avatar + display name). The plan locates the exact insert point during implementation; visual placement is "below the avatar card, above the display-name card" so personal-image controls cluster together.

### Frontend — `PhotoUploader` integration

Update `frontend/src/components/listing/PhotoUploader.tsx` so each picked file passes through `resizeImage(file, { maxDim: 2048 })` before being added to the multipart request. The existing per-file size check goes away. The progress UI remains; it just measures the post-resize bytes.

Existing per-file validation that rejects non-image MIME types stays. The 2MB rejection copy is removed.

## Data flow

### Setting a default cover

```
Settings page picker
  → resizeImage(file, {maxDim: 2048})
  → POST /api/v1/users/me/default-cover (multipart)
  → ListingPhotoProcessor.process(bytes)        // strip EXIF, re-encode, sanity-cap
  → ObjectStorageService.put("users/{userId}/default-cover-{uuid}.{ext}", bytes)
  → User row updated (3 columns)
  → 200 {url, contentType, sizeBytes}
  → useCurrentUser invalidate → card re-renders in "set" state
```

### Creating a listing with a default cover

```
AuctionService.createDraft(seller, parcel, ...)
  → AuctionRepository.save(auction)               // sortOrder pool empty
  → UserDefaultCoverPhotoService.applyTo(auction)
      → seller has cover? yes
      → S3 server-side copy: users/{seller.id}/...  →  listings/{auction.id}/...
      → AuctionPhotoRepository.save(photo at sortOrder=0)
  → ParcelSnapshotPhotoService.refreshFor(auction)
      → fetch SL parcel snapshot bytes
      → ObjectStorageService.put(listings/{auction.id}/sl-snapshot.jpg)
      → AuctionPhotoRepository.save(photo at sortOrder=1)   // was 0; now nextAvailable
  → return auction
```

### Uploading a listing photo

```
PhotoUploader picker (one file or many)
  → for each file: resizeImage(file, {maxDim: 2048})
  → POST /api/v1/auctions/{auctionPublicId}/photos (multipart)
  → ListingPhotoProcessor.process(bytes)        // server-side backstop
  → ObjectStorageService.put("listings/{auctionId}/{uuid}.{ext}")
  → AuctionPhoto saved at next sortOrder
```

## Error handling

- **Settings page upload fails (network, 5xx):** card returns to its prior state (empty or set), inline toast surfaces "Couldn't upload. Try again." No partial state — the User row is only updated after S3 write succeeds.
- **`UserDefaultCoverPhotoService.applyTo` S3 copy fails:** log warn, no `AuctionPhoto` row created, listing creation continues. The seller can manually upload the cover from the listing edit page if they care. Same fail-soft posture as `ParcelSnapshotPhotoService` today.
- **User deletes their default cover while listings exist:** `users/{userId}/default-cover-*` S3 object is deleted; `auction_photos` rows pointing at `listings/{auctionId}/...` paths are independent and untouched.
- **Replace flow (PUT when cover already set):** delete the old `users/{userId}/default-cover-{oldUuid}.{ext}` object after the new one is written. If the delete fails after the new write succeeds, log warn — orphaned object eventually cleaned up by the existing S3 lifecycle policy (or, if no policy, accept the leak; not a security/correctness issue).
- **Concurrent replace from two browser tabs:** last write wins on the User row. The losing tab's S3 object is orphaned; same lifecycle/leak posture as above.
- **Auction created with a cover, cover later changed by the user, auction's photo deleted by the seller, seller re-adds cover via "Add from default":** out of scope. The current spec only auto-inserts at draft creation; there is no "re-add my default" affordance on existing listings. If a user wants the current default on an existing listing they upload it manually.

## Security

- Multipart upload reuses the existing JWT auth filter and `@AuthenticationPrincipal` resolution — same posture as listing photo upload.
- `ListingPhotoProcessor.process` strips EXIF/IPTC/XMP metadata server-side; the client-side resize via `browser-image-compression` strips it in the browser. Defense in depth: even if a client sends a file with embedded GPS or device serial, the bytes that land in S3 are clean.
- 25MB server-side byte cap on multipart upload prevents an attacker from OOMing the JVM during decode by pushing a huge file. The cap is enforced at the Spring multipart layer (`spring.servlet.multipart.max-file-size`) and as a defensive check in the processor.
- S3 path is `users/{userId}/default-cover-{uuid}.{ext}` — UUID suffix prevents enumeration (an attacker who knows the userId can't guess the URL of someone's cover image). Presigned URLs returned from `GET /default-cover` expire on the standard avatar TTL.
- The auto-insert path is server-side only — the client never tells the backend "use my default cover for this listing." The backend reads from the authenticated user's row directly. No way for a malicious client to inject another user's image.

## Observability

- `UserDefaultCoverService.upload/delete` log info-level on success with `userId` + bytes/key.
- `UserDefaultCoverPhotoService.applyTo` logs info-level on the apply path (with `auctionId` + `userId`) and warn-level on copy failure.
- `PhotoSource = USER_DEFAULT_COVER` is queryable in the `auction_photos` table for "how many listings auto-inserted a default cover this week" analytics.

## Testing strategy

### Backend unit tests

- `UserDefaultCoverServiceTest`:
  - `upload_persistsRowAndS3Object`
  - `upload_replacesExistingObjectAndUpdatesKey`
  - `upload_invalidImage_throws`
  - `upload_overSizedBytes_throws`
  - `get_unset_throws404Marker`
  - `get_set_returnsDto`
  - `delete_clearsColumnsAndS3`
  - `delete_unsetIsIdempotent`

- `UserDefaultCoverPhotoServiceTest`:
  - `applyTo_userHasNoCover_isNoOp`
  - `applyTo_userHasCover_insertsAtSortOrder0`
  - `applyTo_s3CopyFails_logsAndReturns_doesNotInsertRow`
  - `applyTo_runsBeforeSnapshot_snapshotLandsAtSortOrder1` (integration with `ParcelSnapshotPhotoService`)

- `ListingPhotoProcessorTest` updated:
  - existing 4096px / 2MB assertions become 2048px / 25MB
  - new test: `process_within2048AndUnder25MB_succeeds`
  - new test: `process_over2048Dim_resizes`
  - new test: `process_over25MB_throws`

### Backend slice / integration tests

- `UserDefaultCoverControllerSliceTest`: 200/404/204 paths for the three endpoints, multipart parsing.
- `AuctionDraftCreationIntegrationTest`: full end-to-end with cover set + snapshot fetching, asserts both rows exist with correct `sortOrder` values.

### Frontend tests

- `resizeImage.test.ts`: mocks `browser-image-compression`, verifies `(maxDim, useWebWorker, fileType)` are passed through, asserts return value is a `File` with correct name/type.
- `DefaultCoverCard.test.tsx`: empty → choose → uploading → set transition; replace flow; remove flow; error toast on failed upload. MSW fixture for the three endpoints.
- `PhotoUploader.test.tsx` updated: verify `resizeImage` is called for each picked file before the multipart POST. Existing 2MB rejection assertion is removed.
- `mockVerifiedCurrentUser` MSW fixture extended with `defaultCoverUrl: null` (and an `mockVerifiedCurrentUserWithCover` variant for tests that need the "set" state).

## Migration

Single Flyway migration `V<next>__add_user_default_cover_columns.sql`:

```sql
ALTER TABLE users
    ADD COLUMN default_cover_object_key varchar(500),
    ADD COLUMN default_cover_content_type varchar(100),
    ADD COLUMN default_cover_size_bytes bigint;
```

No backfill — all three columns are nullable and start `null` for existing rows.

No second migration for the `PhotoSource` enum — `AuctionPhoto.source` is `@Enumerated(EnumType.STRING)` (stored as VARCHAR), so the new value is a code-only change.

## Rollout

1. Backend deploys: new endpoints, new service, listing-creation hook, processor parameterisation.
2. Frontend deploys: `browser-image-compression` added, `resizeImage` utility, settings card, `PhotoUploader` integrated.
3. No feature flag — small, safe, fail-closed at every step.

The two-step deploy (backend → Amplify) means a brief window where the frontend ships before the backend has the new endpoints. The settings card handles 404 from `/me` (when `defaultCoverUrl` is missing from the response) by rendering the empty state, and 404 from `GET /default-cover` by treating it as unset. The listing-photo path keeps working throughout (the new processor bounds are backward-compatible — existing 4096-cap photos are already in S3 and untouched).

## Out-of-scope follow-ups

- **Avatar onboarding unification**: `cropImage.ts` and `resizeImage.ts` could share underlying helpers. Defer until there's a third caller.
- **Per-user multiple covers / templates**: not requested. Defer until a user asks.
- **Re-apply default to an existing listing** ("set this as my default and apply to all my drafts"): not requested. Defer.
- **Migrate existing 4096-cap listing photos to 2048**: not free (requires re-processing every existing `auction_photos` row); the cap is forward-only on new uploads. Defer indefinitely unless storage cost forces it.
