# Per-Auction Parcel Snapshot — Design

**Date:** 2026-05-03
**Branch target:** `dev` → `main`
**Scope:** single PR, all-in-one

## Goal

Replace the shared `parcels` table with a per-auction parcel snapshot. Each auction owns its own copy of the parcel-shape data so re-fetching SL state for a different auction never retroactively mutates an existing one. Bundle three correctness fixes that came up alongside this work: cancel-500 LazyInit, broken parcel-snapshot images (SL URLs that 404), and uploaded photo URLs that don't resolve in production.

## Background

Earlier in this codebase a `parcels` table was introduced with `(sl_parcel_uuid, owner_uuid, parcel_name, area_sqm, region_id, position_x/y/z, slurl, snapshot_url, verified_at, last_checked, …)` and a FK from `auctions.parcel_id`. The intent was de-dup: many auctions could reference the same parcel row, and lookups would refresh the row in place.

Live testing on 2026-05-03 surfaced the failure mode: every parcel-shape field is mutable in SL. `area_sqm`, position, parcel name, owner name, snapshot URL — all can change between auctions. Refreshing a shared row corrupts every auction that points at it. A historical auction's "this parcel was 1024 sqm at the time" becomes wrong as soon as the parcel splits or the seller renames it.

The correct model: each auction snapshots the parcel state at the moment it's listed, and that snapshot is immutable for that auction (until the seller does an explicit re-lookup, which replaces *their* auction's snapshot only).

## Scope

In:

- New `auction_parcel_snapshots` table (1:1 child of `auctions`).
- Drop `parcels` table.
- Snapshot SL parcel image as a regular auction photo via the existing `auction_photos` table, distinguished by a new `source` column.
- Flatten the photo bytes URL: `GET /api/v1/photos/{id}` (drop `/auctions/{auctionId}/photos/{pid}/bytes`).
- Frontend helper to absolute-ify image URLs against `NEXT_PUBLIC_API_URL`.
- `@Transactional` on `AuctionController.cancel` and `update` (fixes the 500 LazyInit on cancel; preventatively for update).
- `application.yml`: `ddl-auto: validate` → `update`. Wipe prod DB so Hibernate rebuilds from entities. Update `CLAUDE.md` to reflect the relaxed schema-change policy until SLPA has real users.

Out:

- UUID id migration (deferred; user asked to revisit later).
- Slow accept-terms perf investigation (parked).
- Re-snapshotting `Region` data per-auction (we copy `region_name` and `region_maturity_rating` into the snapshot but keep `region_id` as a FK; the global region row stays normalized).

## Schema

### New: `auction_parcel_snapshots`

1:1 with `auctions`. PK and FK are the same column.

| Column | Type | Notes |
|---|---|---|
| `auction_id` | bigint | PK + FK → `auctions.id` ON DELETE CASCADE |
| `sl_parcel_uuid` | uuid | NOT NULL |
| `owner_uuid` | uuid | |
| `owner_type` | varchar | `agent` / `group` |
| `owner_name` | varchar | nullable |
| `parcel_name` | varchar | SL's name, nullable |
| `description` | text | SL's description, nullable |
| `region_id` | bigint | FK → `regions.id` |
| `region_name` | varchar | snapshotted copy |
| `region_maturity_rating` | varchar | snapshotted copy |
| `area_sqm` | int | |
| `position_x` | double precision | |
| `position_y` | double precision | |
| `position_z` | double precision | |
| `slurl` | varchar | derived; cached |
| `layout_map_url` | varchar | nullable |
| `layout_map_data` | text | nullable |
| `layout_map_at` | timestamptz | nullable |
| `verified_at` | timestamptz | when this auction's snapshot was last verified |
| `last_checked` | timestamptz | when the snapshot was last refreshed from SL |

### Modified: `auctions`

- Drop column: `parcel_id`.
- Add column: `sl_parcel_uuid` UUID NOT NULL — denormalized mirror of the snapshot's UUID. Required because the parcel-locking partial unique index lives on `auctions(sl_parcel_uuid) WHERE status IN (LOCKING_STATUSES)`, and Postgres partial indexes can't span tables.
- `Auction.setParcelSnapshot(s)` keeps `auction.slParcelUuid` in sync with `s.getSlParcelUuid()`.

### Modified: `auction_photos`

- Add column: `source` enum-as-varchar, `SELLER_UPLOAD | SL_PARCEL_SNAPSHOT`. NOT NULL, default `SELLER_UPLOAD`.
- Add partial unique index `WHERE source = 'SL_PARCEL_SNAPSHOT'` on `(auction_id)` so an auction has at most one SL-derived photo. Re-lookups overwrite that row in place.

### Dropped: `parcels`

Wiped along with everything else (DB drop + Hibernate rebuild). No data migration.

### Parcel-locking index

Today: `uq_auctions_parcel_locked_status` on `auctions(parcel_id) WHERE status IN (...)`.
After: same shape on `auctions(sl_parcel_uuid) WHERE status IN (...)`. The status filter set is unchanged.

## Entity model

- **Drop**: `Parcel`, `ParcelRepository`, the persistence half of `ParcelLookupService`.
- **New**: `AuctionParcelSnapshot` mapped to `auction_parcel_snapshots`. `@OneToOne(mappedBy = "parcelSnapshot", fetch = LAZY)` from `Auction`. Cascade `ALL` on `Auction → Snapshot`.
- **Modified `Auction`**: drop `parcel` field, add `parcelSnapshot` field, add `slParcelUuid` field. The setter for `parcelSnapshot` writes through to `slParcelUuid` so the denormalized mirror never drifts.
- **Modified `AuctionPhoto`**: add `source` enum field with default `SELLER_UPLOAD`.
- **Region**: unchanged.

## Service-layer changes

### `ParcelLookupService` becomes stateless

`lookup(slParcelUuid)`:

1. Fetches SL parcel page + region page (existing logic).
2. Resolves the region (creates or updates the global `regions` row — region IS still de-duped because regions are global identifiers, not the listing's mutable subject).
3. Returns a DTO carrying the parcel-shape fields **plus** the SL snapshot URL string.
4. Persists nothing to a parcels table — there is no parcels table.

### New `ParcelSnapshotPhotoService`

Handles "given an auction and an SL snapshot URL, ensure the auction has a current `SL_PARCEL_SNAPSHOT` photo row":

1. Download bytes from SL with a 5-second timeout.
2. On 404 or timeout: silently no-op. Caller sees no exception. Snapshot photo simply doesn't exist for this auction; the seller can manually upload one.
3. On success: upload bytes to S3 (same bucket as user-uploaded photos, key shape `photos/{photoId}.jpg` after the row is inserted).
4. Upsert by `(auction_id, source = SL_PARCEL_SNAPSHOT)` — first call inserts, subsequent calls replace bytes in place. The `auction_photos.id` of that row stays stable so the photo URL doesn't change.

### `AuctionService.create` / `update`

- On create: build `Auction` with its `AuctionParcelSnapshot` child from the wizard's lookup payload. Save the auction (cascade saves the snapshot). Then call `ParcelSnapshotPhotoService` to download + upload + insert the SL photo row.
- On update with a re-lookup: replace the snapshot child fields, re-call `ParcelSnapshotPhotoService` (which overwrites the existing `SL_PARCEL_SNAPSHOT` row's bytes).
- The SL snapshot download is **synchronous** within the save call, with a 5s budget. We accept up-to-5s slowdown on save in exchange for not adding async polling complexity.

### `AuctionDtoMapper`

Same wire shape externally. `parcel: ParcelResponse` is now read from `auction.parcelSnapshot` instead of the old `Parcel` entity. Photos array continues to come from `auction_photos` and now includes the SL-derived row alongside seller uploads.

### New `PhotoController`

`GET /api/v1/photos/{id}` — public read, streams bytes from S3. Replaces `GET /api/v1/auctions/*/photos/*/bytes`. Security config rule moves from the old path to `/api/v1/photos/*`.

## DTO and URL changes

### Photos

- Wire URL: `/api/v1/photos/{id}`. Old `/auctions/{auctionId}/photos/{photoId}/bytes` is removed.
- `AuctionPhotoResponse.url` now emits the new path.
- Frontend `apiUrl(path)` helper prefixes `NEXT_PUBLIC_API_URL` to any path beginning with `/`. Applied everywhere `<img src={photo.url}>` renders. (Without it, the relative URL resolves against `slparcels.com` instead of `slpa.app`.)

### Parcel response shape

The `parcel: { ... }` block is sourced from the snapshot child instead of the deleted `Parcel` entity. One intentional wire change: **`parcel.id` is dropped** from the response. There's no parcel row anymore — the snapshot is identified by its parent `auction.id`. Every other field (`slParcelUuid`, `regionName`, `areaSqm`, `positionX/Y/Z`, `slurl`, `description`, `verifiedAt`, `lastChecked`, etc.) stays the same. Frontend types lose the `id: number` field on the parcel block; nothing else changes.

## Cancel + update LazyInit fix

`AuctionController.cancel` and `AuctionController.update` get `@Transactional` (mirror of `verify`). With `spring.jpa.open-in-view: false` (existing setting), the response mapper has to run inside an open session — otherwise lazy loads on `auction.parcelSnapshot.region`, `auction.tags`, `auction.seller` blow up with `LazyInitializationException` after the inner service `@Transactional` commits. This is the exact bug the user hit on cancel: 500 returned, but the cancel had already committed.

## `application.yml` and `CLAUDE.md`

`application.yml`:

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: update    # was: validate
```

`CLAUDE.md` — replace the migration discipline paragraph with:

> Schema is managed by Hibernate (`ddl-auto: update`) until SLPA has real users. Entity changes do NOT require a Flyway migration. Breaking schema changes (drops, renames, type changes Hibernate can't apply) are handled by wiping the DB and letting Hibernate rebuild from entities. Re-enable Flyway-first discipline before launch.

The existing Flyway migrations (`backend/src/main/resources/db/migration/`) stay on disk for now; they're a no-op against a wiped DB once Hibernate has materialized the schema.

## DB wipe procedure

```bash
# Connect to prod RDS via psql with the slpa-prod profile.
PGPASSWORD=<secret> psql -h <rds-endpoint> -U slpa -d slpa -c \
  "DROP SCHEMA public CASCADE; CREATE SCHEMA public;"

# Force ECS task replacement so the running backend reconnects to the
# fresh schema and Hibernate rebuilds.
aws ecs update-service --profile slpa-prod \
  --cluster slpa-prod --service slpa-backend --force-new-deployment
```

Document this in CLAUDE.md alongside the policy change so it's repeatable.

## Test surface

### Backend

- Drop `ParcelLookupServiceTest` persistence assertions; replace with stateless-DTO-shape tests.
- Drop `ParcelControllerIntegrationTest` cache-hit assertions (no cache anymore).
- New `AuctionServiceCreateSnapshotTest` — verifies create-flow snapshots the parcel into the `auction_parcel_snapshots` row + creates an `SL_PARCEL_SNAPSHOT` photo on save.
- New `ParcelSnapshotPhotoServiceTest` — happy path, SL 404, SL timeout, replace-in-place on second call.
- New `AuctionControllerCancelLazyInitTest` — repro the 500 (without the `@Transactional` controller fix it 500s; with it, returns the seller response).
- Existing `AuctionControllerIntegrationTest` photo URL assertions update to the flat `/api/v1/photos/{id}` path.
- Parcel-locking unique index test moves to `auctions(sl_parcel_uuid)`.

### Frontend

- New `apiUrl.test.ts` — `/foo` becomes `${NEXT_PUBLIC_API_URL}/foo`; absolute URLs pass through.
- Update photo URL assertions in `AuctionHero.test.tsx`, `ListingPreviewCard.test.tsx`, etc.
- No type changes (we deferred UUIDs).

## File map (new + modified)

Backend:

- `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionParcelSnapshot.java` — new entity
- `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionParcelSnapshotRepository.java` — new
- `backend/src/main/java/com/slparcelauctions/backend/auction/Auction.java` — drop parcel field, add snapshot + slParcelUuid
- `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionPhoto.java` — add source field
- `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionService.java` — integrate snapshot in create/update
- `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionController.java` — `@Transactional` on cancel and update
- `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionDtoMapper.java` — read parcel-shape from snapshot
- `backend/src/main/java/com/slparcelauctions/backend/parcel/ParcelLookupService.java` — strip persistence
- `backend/src/main/java/com/slparcelauctions/backend/parcel/Parcel.java` — DELETE
- `backend/src/main/java/com/slparcelauctions/backend/parcel/ParcelRepository.java` — DELETE
- `backend/src/main/java/com/slparcelauctions/backend/auction/ParcelSnapshotPhotoService.java` — new
- `backend/src/main/java/com/slparcelauctions/backend/auction/PhotoController.java` — new (replaces bytes endpoint on AuctionPhotoController)
- `backend/src/main/java/com/slparcelauctions/backend/config/SecurityConfig.java` — `/api/v1/photos/*` permitAll, drop the old `/auctions/*/photos/*/bytes` rule
- `backend/src/main/resources/application.yml` — ddl-auto: update

Frontend:

- `frontend/src/lib/api/url.ts` — new `apiUrl(path)` helper
- `frontend/src/components/auction/AuctionHero.tsx` — wrap photo.url with apiUrl
- `frontend/src/components/listing/ListingPreviewCard.tsx` — wrap photo.url with apiUrl
- (any other `<img src={photo.url}>` consumers)

Docs:

- `CLAUDE.md` — relax migration policy paragraph + add DB wipe procedure
- `README.md` — note the schema rebuild + parcel-snapshot architectural shift in the Epic 03 sub-spec 2 paragraph

## Open / parked

- UUID id migration — deferred to a future PR.
- Slow accept-terms POST — parked; revisit if it persists after the deploy.
- Region-data snapshotting depth — region_name and maturity rating are snapshotted; everything else stays normalized. Will revisit if SL renames cause user-visible weirdness.
