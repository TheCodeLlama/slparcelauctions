# Theme Image Variants

**Date:** 2026-05-21
**Status:** Awaiting user review.

## 1. Goal

Let users upload light-mode and dark-mode variants of every visual-identity image surface: group covers, group logos, the user's default listing picture, and a new group default listing picture. A single-image upload still works — the renderer falls back to whichever variant exists. The auto-inserted "default cover" photo at sort 0 on every listing carries the variant pair through; all other auction photos (seller uploads, parcel snapshot) remain single-image.

The feature exists because operators want transparent images with theme-colored text (logos, badges, themed UI overlays) that render correctly against both light and dark backgrounds without resorting to neutral-by-compromise designs.

## 2. Data model

One Flyway migration `V43__theme_image_variants.sql`. Column renames + new sibling columns. No data writes.

### `realty_groups`

```sql
ALTER TABLE realty_groups
  RENAME COLUMN cover_object_key   TO cover_light_object_key;
ALTER TABLE realty_groups
  RENAME COLUMN cover_content_type TO cover_light_content_type;
ALTER TABLE realty_groups
  RENAME COLUMN cover_size_bytes   TO cover_light_size_bytes;
ALTER TABLE realty_groups
  ADD COLUMN cover_dark_object_key   VARCHAR(500),
  ADD COLUMN cover_dark_content_type VARCHAR(100),
  ADD COLUMN cover_dark_size_bytes   BIGINT;

ALTER TABLE realty_groups
  RENAME COLUMN logo_object_key   TO logo_light_object_key;
ALTER TABLE realty_groups
  RENAME COLUMN logo_content_type TO logo_light_content_type;
ALTER TABLE realty_groups
  RENAME COLUMN logo_size_bytes   TO logo_light_size_bytes;
ALTER TABLE realty_groups
  ADD COLUMN logo_dark_object_key   VARCHAR(500),
  ADD COLUMN logo_dark_content_type VARCHAR(100),
  ADD COLUMN logo_dark_size_bytes   BIGINT;

-- New: group default listing picture
ALTER TABLE realty_groups
  ADD COLUMN default_listing_light_object_key   VARCHAR(500),
  ADD COLUMN default_listing_light_content_type VARCHAR(100),
  ADD COLUMN default_listing_light_size_bytes   BIGINT,
  ADD COLUMN default_listing_dark_object_key    VARCHAR(500),
  ADD COLUMN default_listing_dark_content_type  VARCHAR(100),
  ADD COLUMN default_listing_dark_size_bytes    BIGINT;
```

### `users`

```sql
ALTER TABLE users
  RENAME COLUMN default_cover_object_key   TO default_cover_light_object_key;
ALTER TABLE users
  RENAME COLUMN default_cover_content_type TO default_cover_light_content_type;
ALTER TABLE users
  RENAME COLUMN default_cover_size_bytes   TO default_cover_light_size_bytes;
ALTER TABLE users
  ADD COLUMN default_cover_dark_object_key   VARCHAR(500),
  ADD COLUMN default_cover_dark_content_type VARCHAR(100),
  ADD COLUMN default_cover_dark_size_bytes   BIGINT;
```

### `auction_photos`

Confirmed columns on the entity: `object_key` (NOT NULL), `content_type` (NOT NULL), `size_bytes` (NOT NULL), plus `sort_order`, `source`, `uploaded_at`. Rename the three image-shape columns to `_light_*` and add nullable `_dark_*` siblings.

```sql
ALTER TABLE auction_photos
  RENAME COLUMN object_key   TO light_object_key;
ALTER TABLE auction_photos
  RENAME COLUMN content_type TO light_content_type;
ALTER TABLE auction_photos
  RENAME COLUMN size_bytes   TO light_size_bytes;
ALTER TABLE auction_photos
  ADD COLUMN dark_object_key   VARCHAR(500),
  ADD COLUMN dark_content_type VARCHAR(50),
  ADD COLUMN dark_size_bytes   BIGINT;
```

The renamed columns retain their `NOT NULL` constraints — every photo row has a light variant. The dark sibling columns are nullable; only USER_DEFAULT_COVER and GROUP_DEFAULT_COVER source rows ever populate them.

### Existing-data behavior

After migration, every populated single-image row sits in the `_light_*` slot with `_dark_*` null. The frontend's fallback rule (`useThemedImage`) means dark-theme viewers see the existing image — same as today, where dark mode shows the same image as light mode. No data backfill needed. No semantic regression.

### Constraint posture

No NOT NULL or paired-uploads required. An entity can have both `_light_*` and `_dark_*` null (the "no image set" state). The renderer's fallback rule produces `null` (which surfaces in the UI as the existing empty-state).

### Indexes

None new. The existing image-related indexes (if any) carry over with the renamed columns automatically (Postgres rename-column preserves indexes).

## 3. Apply / lifecycle logic

### Upload (one variant at a time)

`POST /api/v1/admin/realty-groups/{publicId}/cover/{variant}` (variant = `light` | `dark`). Multipart body with `file`. Mirrors for `logo`, `default-listing`, and the user `default-cover` surface.

1. Validate variant is `light` or `dark`. Else `400 INVALID_VARIANT`.
2. Run the file through the existing `ImageUploadValidator` (rejects oversize, bad MIME, polyglots).
3. Encode to WebP via the existing pipeline.
4. Upload to S3 at the variant-suffixed key: e.g. `realty-groups/{publicId}/cover-light.webp` or `realty-groups/{publicId}/cover-dark.webp`.
5. Update only that variant's three columns (`*_light_*` OR `*_dark_*`).
6. Return the surface's DTO with both URLs.

### Delete (one variant at a time)

`DELETE /api/v1/admin/realty-groups/{publicId}/cover/{variant}`. Same shape. Best-effort S3 delete, null the variant's three columns. Idempotent (204 on already-empty variant).

### Variant fallback at render

Backend doesn't know the viewer's theme; it always returns both URLs in the DTO (null when not uploaded). The frontend `useThemedImage(lightUrl, darkUrl)` hook applies the fallback rule:

```ts
function useThemedImage(lightUrl, darkUrl) {
  const { resolvedTheme } = useTheme();
  const primary = resolvedTheme === "dark" ? darkUrl : lightUrl;
  const fallback = resolvedTheme === "dark" ? lightUrl : darkUrl;
  return primary ?? fallback ?? null;
}
```

`next-themes` `resolvedTheme` resolves the `system` preference to the actual effective theme at runtime. On initial render (before mount), `resolvedTheme` is undefined and the hook falls through to `lightUrl ?? darkUrl` — SSR-safe default. A user with a dark preference sees a brief flash of the light variant on first paint before hydration replaces the src — standard `next-themes` trade-off, accepted at our scale.

### Listing auto-insert at sort 0

`UserDefaultCoverPhotoService` is renamed to `DefaultCoverPhotoService` and gains group-source support. At listing creation:

1. Determine the active default-cover source:
   - If the listing is owned by a realty group (group-owned listings have a non-null `realty_group_id` on the auction; check the existing field name during implementation) → read the group's `default_listing_*` columns.
   - Else → read the seller user's `default_cover_*` columns.
2. Read both `_light_*` and `_dark_*` keys from the chosen source.
3. For each populated variant, S3 server-side copy from the source key to a listing-scoped key:
   - Source: `realty-groups/{publicId}/default-listing-{variant}.webp` (or user equivalent)
   - Destination: `auction-photos/{auctionId}/default-cover-{variant}.webp`
4. Insert ONE `auction_photos` row at sort 0 with both `light_object_key` and `dark_object_key` populated (whichever variants were copied).
5. If neither variant exists on the source, insert nothing (preserves the existing "no default cover" behavior).

`PhotoSource` enum gains a new value `GROUP_DEFAULT_COVER` alongside the existing `USER_DEFAULT_COVER`. The variant-aware dark mutation endpoints reject anything other than these two sources.

### Bot-fetched parcel snapshot (sort 1)

Unchanged. The bot fetches one image from the SL World API; there is no second SL snapshot to pair with. Always writes to `light_object_key`; `dark_object_key` stays null. The renderer's fallback rule means dark-theme viewers see the light snapshot.

### Seller-uploaded photos (sort 2+)

Unchanged single-image flow. The photo manager upload card writes to `light_object_key`; `dark_object_key` stays null. The renderer falls back the same way.

### Photo manager UX for the default-cover row

The default-cover card (sort 0, `source = USER_DEFAULT_COVER` or `GROUP_DEFAULT_COVER`) is the ONLY card with paired-capable storage exposed in the manager:

- Card renders with a small "Light + Dark" indicator when both variants exist; absent when only one.
- Clicking "Edit" opens a `PhotoVariantsModal` with two slots.
- **Light slot is read-only.** Changing the light variant in the auction's snapshot would diverge from the user's/group's profile state. To change it, the seller edits their profile default cover and recreates the listing (or deletes the auction's sort-0 row and uploads a new photo). The light slot in the modal renders a hover note explaining this.
- **Dark slot is editable.** Upload, replace, delete — calls `POST/DELETE /auctions/{publicId}/photos/{photoPublicId}/dark`. Adding a dark variant post-listing-creation is the supported workflow for sellers who realize after-the-fact that their default cover should be theme-aware.
- "Delete card" removes both variants and the entire row.
- Drag-to-reorder works on the whole card (the pair stays together — sort position is per-row, not per-variant).

All other photo cards (seller uploads, parcel snapshot) stay single-slot, unchanged.

### S3 lifecycle

No new lifecycle rules. Variant-aware keys (`cover-light.webp` vs `cover-dark.webp`) never collide. Delete-variant calls `S3.delete(key)` immediately — no pending state needed (admin-managed surfaces, not user-uploaded ephemeral state).

## 4. Backend endpoints

### Group cover (existing, extended)

```
POST   /api/v1/admin/realty-groups/{publicId}/cover/{variant}        multipart, admin role
DELETE /api/v1/admin/realty-groups/{publicId}/cover/{variant}        admin role
GET    /api/v1/realty-groups/{publicId}/cover/image?variant={light|dark}    permitAll
```

The old non-variant endpoints (`POST /cover`, `DELETE /cover`, `GET /cover/image`) are removed in the same PR. The frontend is the only caller and updates in lockstep.

### Group logo (existing, extended)

```
POST   /api/v1/admin/realty-groups/{publicId}/logo/{variant}
DELETE /api/v1/admin/realty-groups/{publicId}/logo/{variant}
GET    /api/v1/realty-groups/{publicId}/logo/image?variant={light|dark}
```

### Group default listing picture (new)

```
POST   /api/v1/admin/realty-groups/{publicId}/default-listing/{variant}
DELETE /api/v1/admin/realty-groups/{publicId}/default-listing/{variant}
GET    /api/v1/realty-groups/{publicId}/default-listing/image?variant={light|dark}
```

### User default cover (existing, extended)

```
POST   /api/v1/users/{publicId}/default-cover/{variant}              multipart, self-or-admin
DELETE /api/v1/users/{publicId}/default-cover/{variant}
GET    /api/v1/users/{publicId}/default-cover/image?variant={light|dark}
```

### Auction photo dark variant (new)

```
POST   /api/v1/auctions/{publicId}/photos/{photoPublicId}/dark       multipart
DELETE /api/v1/auctions/{publicId}/photos/{photoPublicId}/dark
```

Asymmetric vs the other surfaces: there is no `/photos/{id}/light` mutation. The light slot of an auction photo is set either by the existing seller-upload flow (single-image, writes to `light_object_key`) or by the auto-insert at sort 0 (server-side copy from the default-cover source). The dark slot is the only thing the seller actively manages PER PHOTO ROW post-creation.

Reject `400 INVALID_PHOTO_SOURCE` on any photo whose source is not `USER_DEFAULT_COVER` or `GROUP_DEFAULT_COVER`. Seller uploads (`SELLER_UPLOADED`) and parcel snapshots (`SL_PARCEL_SNAPSHOT`) cannot grow a dark variant.

### DTO wire shape

```jsonc
// RealtyGroupDto (excerpt)
{
  "coverLightUrl":          "/api/v1/realty-groups/.../cover/image?variant=light",
  "coverDarkUrl":           "/api/v1/realty-groups/.../cover/image?variant=dark" | null,
  "logoLightUrl":           "...",
  "logoDarkUrl":            "..." | null,
  "defaultListingLightUrl": "...",
  "defaultListingDarkUrl":  "..." | null
}

// UserResponse (excerpt)
{
  "defaultCoverLightUrl": "/api/v1/users/.../default-cover/image?variant=light" | null,
  "defaultCoverDarkUrl":  "..." | null
}

// AuctionPhotoDto
{
  "publicId":  "...",
  "lightUrl":  "/api/v1/photos/.../bytes?variant=light",
  "darkUrl":   "/api/v1/photos/.../bytes?variant=dark" | null,
  "source":    "USER_DEFAULT_COVER" | "GROUP_DEFAULT_COVER" | "SELLER_UPLOADED" | "SL_PARCEL_SNAPSHOT",
  "sortOrder": 0
}
```

The frontend's existing `Page<AuctionPhotoDto>` envelopes carry the new shape transparently — no envelope change.

### Errors

- `INVALID_VARIANT` (400): variant path-param is not `light` or `dark`.
- `INVALID_PHOTO_SOURCE` (400): attempted to upload a dark variant on a non-default-cover photo row.
- Existing validator-driven errors (oversize, bad MIME, polyglot) reuse their current codes.

### Authorization

- Group endpoints: existing group-admin `@PreAuthorize` carries over unchanged.
- User default cover: existing self-or-admin rule unchanged.
- Auction photo dark variant: same rule as the existing photo manager (seller or admin).

## 5. Frontend UI

### `useThemedImage(lightUrl, darkUrl)` hook

File: `frontend/src/lib/theme/useThemedImage.ts`. Pure client-side fallback resolver. Behavior in spec §3 above. Sibling test asserts: dark theme → dark URL when both, fallback to light when dark null, light theme → light URL, fallback to dark when light null, both null returns null, theme toggle re-renders.

### `<ThemedImage>` convenience component

File: `frontend/src/components/ui/ThemedImage.tsx`. Renders `<img src={apiUrl(useThemedImage(lightSrc, darkSrc)) ?? undefined}>` with the project's existing `apiUrl(path)` wrapper. Forwards `alt`, `className`, `loading`, `onClick`, etc. Renders nothing when both URLs null. Sibling test included.

### Group admin — cover & logo upload

Existing dropzone becomes a "Light mode" + "Dark mode" dual-slot card. Each slot is independently uploadable, replaceable, and deletable. Below each slot pair, a small live preview wraps `<ThemedImage>` so the preview reflects the user's current theme as they toggle.

### Group admin — default listing picture (new section)

New "Default listing picture" section on the group admin page. Same dual-slot pattern. Subtitle copy: "Used as the first photo on every listing created on behalf of this group. Light and dark variants are optional — if you upload only one, it will be used in both themes."

### User profile — default cover

`frontend/src/components/wallet/DefaultCoverCard.tsx` (and any sibling edit page) becomes dual-slot. The user's existing single-image upload sits in the light slot post-migration. Dark slot is empty until they add one. Subtitle: "Auto-inserted as the first photo on every listing you create."

### Group public page

Cover banner: `<ThemedImage lightSrc={group.coverLightUrl} darkSrc={group.coverDarkUrl}>`. Logo: same pattern.

### Listing browse + detail page

The auction photo gallery uses `<ThemedImage lightSrc={photo.lightUrl} darkSrc={photo.darkUrl}>` for every photo. Most photos have `darkUrl === null` so the fallback rule renders the light variant in both themes (no behavior change vs today). The sort-0 default-cover photo, when carrying both variants, swaps when the user toggles theme — the canonical use case (transparent logo with theme-colored text).

### Photo manager (listing edit)

- Default-cover card (sort 0, USER_DEFAULT_COVER / GROUP_DEFAULT_COVER): renders with a small "Light + Dark" badge when both variants present; absent when only one. "Edit" opens `PhotoVariantsModal`:
  - Light slot: read-only thumbnail with a small note ("Edit your profile default cover to change this. Or delete the photo and upload a new one to start fresh.").
  - Dark slot: editable. Upload, replace, delete. Calls `POST/DELETE /auctions/.../photos/.../dark`.
  - Drag-to-reorder unchanged; sort position is per-row.
- All other photo cards (SELLER_UPLOADED, SL_PARCEL_SNAPSHOT) stay single-slot unchanged.

### SSR safety

Every page that renders one of the surfaces uses `export const dynamic = "force-dynamic"` if it isn't already. The `useThemedImage` hook is client-only (`"use client"`); SSR renders the light variant via the fallback rule, then re-renders with the correct variant after hydration. No image-render endpoint changes (the GET routes are still `permitAll` so the browser's image fetcher doesn't need a JWT).

## 6. Migration plan

Single Flyway migration `V43__theme_image_variants.sql`. DDL only. See spec §2 for the full SQL. Migration runs at startup; Hibernate `validate` is the safety net (entity field names + migration column names must align).

**Java entity field renames in the same commit as the migration:**

- `RealtyGroup.java`: `coverObjectKey` → `coverLightObjectKey`, same for content/size; add `coverDark*` fields. Same for logo. Add six `defaultListing*` fields.
- `User.java`: `defaultCoverObjectKey` → `defaultCoverLightObjectKey`, same for content/size; add `defaultCoverDark*`.
- `AuctionPhoto.java`: `objectKey` → `lightObjectKey`; add `darkObjectKey`.

All call sites updated in the same commit (mechanical Java refactor — IDE-style rename).

**No data backfill.** No existing rows need touching; rename preserves data.

## 7. Configuration

None new. The variant suffix in S3 keys (`cover-light.webp`, `cover-dark.webp`, `default-listing-light.webp`, `default-cover-dark.webp`) is a code-only convention; no application.yml, no SSM, no Terraform.

## 8. Testing

### Backend

**Existing slice/integration tests** for each image-upload controller gain variant coverage:

- `RealtyGroupImageControllerSliceTest`:
  - POST `/cover/light` and `/cover/dark` upload independently
  - DELETE `/cover/dark` leaves light intact; DELETE `/cover/light` leaves dark intact
  - GET `/cover/image?variant=light` and `?variant=dark` return the right bytes
  - GET `/cover/image?variant=invalid` returns 400 `INVALID_VARIANT`
  - Same matrix for logo and the new default-listing endpoints
- `UserDefaultCoverControllerIntegrationTest` (new or extended): same matrix for `/users/{publicId}/default-cover/{variant}`.

**`DefaultCoverPhotoServiceTest`** (renamed from `UserDefaultCoverPhotoServiceTest`):
- User-owned listing + user has both variants → auction_photos row at sort 0 carries both light + dark keys
- User-owned listing + user has only light → row has light only, dark null
- User-owned listing + user has only dark → row has dark only, light null
- Group-owned listing + group has both variants → group variants win
- Group-owned listing + group has no default + user has variants → fall back to user
- Neither has any → no sort-0 row inserted

**`AuctionPhotoDarkVariantControllerIntegrationTest`** (new):
- POST happy path: dark attaches; response shows both URLs
- POST 400 `INVALID_PHOTO_SOURCE` on SELLER_UPLOADED row
- POST 400 `INVALID_PHOTO_SOURCE` on SL_PARCEL_SNAPSHOT row
- DELETE strips dark; light untouched
- DELETE idempotent (204 on already-null dark)
- Non-seller non-admin returns 403

**S3 / storage:**
- `S3ObjectStorageServiceTest`: no new methods needed; existing `put`, `delete`, `get`, `copy` work unchanged. The variant suffix in keys is a caller concern.
- `DefaultCoverPhotoServiceTest` extension verifies the auto-insert calls `S3.copy(...)` twice when both variants present, with the right source + destination key naming.

**LazyInit regression coverage:** existing image GET endpoints are by-id, don't load lazy collections, and the DTO mappers carrying the new URLs operate on already-loaded entities. No new regression test class required (lesson from the coupon hotfix still in force, just not applicable here).

### Frontend

- `useThemedImage.test.tsx`: theme switching, both-null, only-light, only-dark, fallback rules.
- `ThemedImage.test.tsx`: rendering, alt/className/onClick passthrough, nothing-when-both-null.

Per-surface tests:

- Group admin upload form (cover, logo, default-listing): dual-slot rendering, independent upload per slot, delete per slot, live preview honors theme.
- User `DefaultCoverCard` tests: same.
- `PhotoVariantsModal` tests: opens with both slots when both variants exist; light slot read-only with hover note; dark slot upload/replace/delete hits the right API endpoints.
- Browse + auction detail gallery tests: theme toggle changes the rendered src for paired photos; single-variant photos stay stable across theme toggles.

### Postman

Each existing image-upload request in the SLPA Postman collection gains the `/{variant}` path param. New requests for:
- Group default-listing-picture POST + DELETE per variant
- Auction photo dark-variant POST + DELETE

Variable-chain: upload light → upload dark → fetch DTO showing both → delete dark → fetch DTO showing only light.

### Manual smoke (release checklist)

- Admin uploads a transparent PNG group cover with dark text in the light slot and a sibling PNG with light text in the dark slot. Toggle theme on the public group page — the cover image swaps.
- User sets a paired default cover, creates a listing — sort-0 photo on the new listing displays the correct variant per theme on browse + auction detail.
- Group member creates a listing on behalf of a group with paired default-listing-pictures — auction shows the group variant.
- Seller deletes the dark variant on a listing's default-cover row via the photo manager — auction in dark theme falls back to the light variant.
- Existing groups with single-image cover uploads (pre-migration) display unchanged in both themes.

## 9. Out of scope

- Theme-aware seller-uploaded gallery photos. Only the default-cover row (sort 0) carries paired variants. Seller's manual gallery uploads remain single-image.
- Theme-aware bot-fetched parcel snapshot. Single image only.
- Theme-aware avatars (`profilePicUrl`). Single image.
- Server-side image transformation (auto-inverting a light image to make a dark version, color-shift heuristics). Manual upload only.
- "Auto-detect theme from image colors" or any suggestion engine for which slot a given image belongs in. The slot label is the user's choice.
- Variant-aware lifecycle for the existing S3 `pending/` uploads (the support-ticket attachment system is unaffected).
- Migration of historic `auction_photos` rows to populate `dark_object_key` for legacy default-cover snapshots. Old rows stay light-only; sellers manually add a dark variant via the photo manager if they want one.

## 10. Decision log

Captured during brainstorming (2026-05-21):

- **Fallback semantics** (Q1 = A). Single image acts as both modes via runtime fallback. Rejected explicit "single-image vs paired" mode toggle (forces choice) and "both required if either" (worst UX).
- **Schema model** (Q2 = A → refined). Paired model with two equal slots `coverLightUrl`/`coverDarkUrl`; neither required; either can be uploaded as the only image. Rejected triplet (neutral + light + dark) as a slot nobody uses.
- **Group default listing picture precedence** (Q3 = A). Group's default overrides user's personal default on group-owned listings; falls back to user when group has none.
- **Auction photo extension** (Q3 follow-up). Extended `auction_photos` to support paired variants on the sort-0 default-cover row only; seller uploads and parcel snapshot remain single-image. Driven by the transparency + theme-colored-text use case.
- **Photo manager UX** (Q4 = A). One card = one logical photo with up to two slots. Refined: only the default-cover card actually exposes the dual-slot model; other cards are single-slot. The default-cover card's light slot is read-only (snapshot from profile); dark slot is editable.
- **Endpoint shape**. Path param per variant (`/cover/{light|dark}`). Mirrors `/grants/{grantPublicId}/revoke` precedent in the codebase.
- **Schema migration approach**. Rename existing columns to `_light_*` + add `_dark_*` siblings. Cleaner symmetric naming. Renames are pure DDL; no data writes. The frontend wire shape (`coverLightUrl` / `coverDarkUrl`) matches the schema.
- **Variant DTO shape**. Backend returns both URLs explicitly (null when not uploaded); client-side `useThemedImage` resolves per theme. No server-side theme detection (browser image fetcher has no JWT, so we can't read user prefs anyway).
- **Bot parcel snapshot**. Single image. The bot has no second SL snapshot to pair with.
- **Auto-insert snapshot copies both variants**. S3 server-side copy from source to listing-scoped key for each populated variant.
