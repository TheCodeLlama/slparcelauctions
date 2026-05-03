# Per-Auction Parcel Snapshot Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the shared `parcels` table with a per-auction parcel snapshot owned by each auction, snapshot the SL parcel image as a regular auction photo, and bundle the cancel-500 LazyInit fix + photo-URL absolute-ifying.

**Architecture:** New `AuctionParcelSnapshot` 1:1 child of `Auction` (cascade ALL). New `auction_photos.source` enum with values `SELLER_UPLOAD` / `SL_PARCEL_SNAPSHOT`. New `ParcelSnapshotPhotoService` downloads SL bytes, uploads to S3, upserts the photo row keyed by `(auction_id, source=SL_PARCEL_SNAPSHOT)`. Legacy `Parcel` entity + `parcels` table dropped. Photo-bytes URL flattened to `GET /api/v1/photos/{id}`. `ddl-auto: validate` → `update` and prod DB wiped so Hibernate rebuilds the schema from entities.

**Tech Stack:** Spring Boot 4 / Java 26 / JPA-Hibernate / Postgres / S3 (MinIO in dev) / Next.js 16 frontend / Vitest / JUnit 5.

**Spec:** `docs/superpowers/specs/2026-05-03-per-auction-parcel-snapshot.md` (commit `edf3a4b`).

---

## File map

**Backend — new:**
- `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionParcelSnapshot.java`
- `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionParcelSnapshotRepository.java`
- `backend/src/main/java/com/slparcelauctions/backend/auction/PhotoSource.java`
- `backend/src/main/java/com/slparcelauctions/backend/auction/ParcelSnapshotPhotoService.java`
- `backend/src/main/java/com/slparcelauctions/backend/auction/PhotoController.java`

**Backend — modified:**
- `backend/src/main/java/com/slparcelauctions/backend/auction/Auction.java`
- `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionPhoto.java`
- `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionService.java`
- `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionController.java`
- `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionDtoMapper.java`
- `backend/src/main/java/com/slparcelauctions/backend/auction/dto/AuctionPhotoResponse.java`
- `backend/src/main/java/com/slparcelauctions/backend/parcel/ParcelLookupService.java`
- `backend/src/main/java/com/slparcelauctions/backend/parcel/dto/ParcelResponse.java`
- `backend/src/main/java/com/slparcelauctions/backend/parcel/ParcelController.java`
- `backend/src/main/java/com/slparcelauctions/backend/config/SecurityConfig.java`
- `backend/src/main/resources/application.yml`
- Cascading: every entity / repo / service / controller / test that uses `Parcel`, `parcel.id`, or the `/auctions/*/photos/*/bytes` URL.

**Backend — deleted:**
- `backend/src/main/java/com/slparcelauctions/backend/parcel/Parcel.java`
- `backend/src/main/java/com/slparcelauctions/backend/parcel/ParcelRepository.java`
- (any `parcels`-table-specific test class)

**Frontend — new:**
- `frontend/src/lib/api/url.ts`
- `frontend/src/lib/api/url.test.ts`

**Frontend — modified:**
- `frontend/src/types/parcel.ts` (drop `id` field)
- `frontend/src/types/auction.ts` (parcel block loses `id`)
- Every consumer of `<img src={photo.url}>` (AuctionHero, ListingPreviewCard, etc.).

**Docs:**
- `CLAUDE.md` (relax migration policy, add DB wipe procedure)
- `README.md` (sweep)

---

## Branch / commit strategy

Continuing on `spec/per-auction-parcel-snapshot` (the spec commit lives there). All implementation commits append to this branch. Single PR to `dev`, then `dev` → `main`, then DB wipe + ECS redeploy.

Commit cadence: one commit per task (or two — separating tests from impl when natural).

---

## Phase 1 — Backend entity refactor

### Task 1: New `PhotoSource` enum

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/PhotoSource.java`

- [ ] **Step 1: Write the enum**

```java
package com.slparcelauctions.backend.auction;

/**
 * Where an {@link AuctionPhoto} came from. SL_PARCEL_SNAPSHOT is the
 * parcel image fetched from Second Life on parcel lookup; at most one
 * such row per auction (partial unique index). SELLER_UPLOAD is anything
 * the seller manually uploaded.
 */
public enum PhotoSource {
    SELLER_UPLOAD,
    SL_PARCEL_SNAPSHOT
}
```

- [ ] **Step 2: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auction/PhotoSource.java
git commit -m "feat(auction): add PhotoSource enum"
```

---

### Task 2: New `AuctionParcelSnapshot` entity

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionParcelSnapshot.java`

- [ ] **Step 1: Write the entity**

```java
package com.slparcelauctions.backend.auction;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.slparcelauctions.backend.region.Region;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Per-auction snapshot of the parcel-shape SL data at the moment the
 * auction was listed (and refreshed in place on subsequent re-lookups by
 * the same seller). 1:1 with {@link Auction} — primary key IS the
 * auction's id (via @MapsId), no separate snapshot id.
 *
 * <p>Region is referenced by FK for global identity but the seller-visible
 * name + maturity are denormalized so a region rename in SL doesn't
 * retroactively change historical listings.
 */
@Entity
@Table(name = "auction_parcel_snapshots")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuctionParcelSnapshot {

    @Id
    @Column(name = "auction_id")
    private Long auctionId;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "auction_id")
    private Auction auction;

    @Column(name = "sl_parcel_uuid", nullable = false)
    private UUID slParcelUuid;

    @Column(name = "owner_uuid")
    private UUID ownerUuid;

    @Column(name = "owner_type")
    private String ownerType;

    @Column(name = "owner_name")
    private String ownerName;

    @Column(name = "parcel_name")
    private String parcelName;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "region_id")
    private Region region;

    @Column(name = "region_name")
    private String regionName;

    @Column(name = "region_maturity_rating")
    private String regionMaturityRating;

    @Column(name = "area_sqm")
    private Integer areaSqm;

    @Column(name = "position_x")
    private Double positionX;

    @Column(name = "position_y")
    private Double positionY;

    @Column(name = "position_z")
    private Double positionZ;

    @Column(name = "slurl")
    private String slurl;

    @Column(name = "layout_map_url")
    private String layoutMapUrl;

    @Column(name = "layout_map_data", columnDefinition = "text")
    private String layoutMapData;

    @Column(name = "layout_map_at")
    private OffsetDateTime layoutMapAt;

    @Column(name = "verified_at")
    private OffsetDateTime verifiedAt;

    @Column(name = "last_checked")
    private OffsetDateTime lastChecked;
}
```

- [ ] **Step 2: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auction/AuctionParcelSnapshot.java
git commit -m "feat(auction): add AuctionParcelSnapshot entity"
```

---

### Task 3: New `AuctionParcelSnapshotRepository`

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionParcelSnapshotRepository.java`

- [ ] **Step 1: Write the repo**

```java
package com.slparcelauctions.backend.auction;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AuctionParcelSnapshotRepository
        extends JpaRepository<AuctionParcelSnapshot, Long> {
}
```

- [ ] **Step 2: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auction/AuctionParcelSnapshotRepository.java
git commit -m "feat(auction): add AuctionParcelSnapshotRepository"
```

---

### Task 4: Modify `Auction` — drop `parcel`, add `parcelSnapshot` + `slParcelUuid`

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/Auction.java`

- [ ] **Step 1: Replace the parcel reference and add the denormalized UUID mirror**

In the entity, remove the `@ManyToOne ... Parcel parcel` field. Add:

```java
@Column(name = "sl_parcel_uuid", nullable = false)
private UUID slParcelUuid;

@OneToOne(mappedBy = "auction", cascade = CascadeType.ALL, fetch = FetchType.LAZY, orphanRemoval = true)
private AuctionParcelSnapshot parcelSnapshot;

/**
 * Setter override that keeps the denormalized {@link #slParcelUuid}
 * mirror in sync with the snapshot's UUID. The mirror exists because the
 * parcel-locking partial unique index lives on the auctions table —
 * Postgres partial indexes can't span tables.
 */
public void setParcelSnapshot(AuctionParcelSnapshot snapshot) {
    this.parcelSnapshot = snapshot;
    if (snapshot != null) {
        snapshot.setAuction(this);
        this.slParcelUuid = snapshot.getSlParcelUuid();
    }
}
```

Drop the existing `@Index` on `(parcel_id, status)` if present; replace with index on `(sl_parcel_uuid, status)`. Drop the `parcel_id` column from any `@Table(indexes=...)` declarations.

- [ ] **Step 2: Compile clean**

```bash
cd backend && ./mvnw -q compile 2>&1 | tail -10
```

Expected: compile errors only in callers of `getParcel()` / `setParcel()` — those will be fixed in subsequent tasks.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auction/Auction.java
git commit -m "feat(auction): replace Parcel FK with AuctionParcelSnapshot 1:1 child"
```

---

### Task 5: Modify `AuctionPhoto` — add `source` enum field

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionPhoto.java`

- [ ] **Step 1: Add `source` field**

```java
@Enumerated(EnumType.STRING)
@Column(name = "source", nullable = false)
@Builder.Default
private PhotoSource source = PhotoSource.SELLER_UPLOAD;
```

- [ ] **Step 2: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auction/AuctionPhoto.java
git commit -m "feat(auction): add source enum to AuctionPhoto"
```

---

## Phase 2 — Backend service layer

### Task 6: Strip `ParcelLookupService` persistence

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/parcel/ParcelLookupService.java`
- Delete (eventually): `backend/src/main/java/com/slparcelauctions/backend/parcel/Parcel.java`
- Delete (eventually): `backend/src/main/java/com/slparcelauctions/backend/parcel/ParcelRepository.java`

- [ ] **Step 1: Replace the service body**

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class ParcelLookupService {

    private final SlWorldApiClient worldApi;
    private final RegionService regionService;
    private final Clock clock;

    /**
     * Stateless SL parcel lookup. Returns the parcel-shape DTO + the SL
     * snapshot URL so callers can persist a per-auction snapshot. Does
     * NOT write to any parcels table — there is no parcels table.
     */
    public ParcelLookupResult lookup(UUID slParcelUuid) {
        ParcelPageData parcelPage = worldApi.fetchParcelPage(slParcelUuid).block();
        RegionPageData regionPage = worldApi.fetchRegionPage(parcelPage.regionUuid()).block();
        Region region = regionService.upsert(regionPage);

        ParcelMetadata meta = parcelPage.parcel();
        OffsetDateTime now = OffsetDateTime.now(clock);
        String slurl = buildSlurl(region.getName(), meta.positionX(), meta.positionY(), meta.positionZ());

        ParcelResponse response = ParcelResponse.builder()
                .slParcelUuid(slParcelUuid)
                .ownerUuid(meta.ownerUuid())
                .ownerType(meta.ownerType())
                .ownerName(meta.ownerName())
                .parcelName(meta.parcelName())
                .regionId(region.getId())
                .regionName(region.getName())
                .regionMaturityRating(region.getMaturityRating())
                .description(meta.description())
                .areaSqm(meta.areaSqm())
                .positionX(meta.positionX())
                .positionY(meta.positionY())
                .positionZ(meta.positionZ())
                .slurl(slurl)
                .verified(true)
                .verifiedAt(now)
                .lastChecked(now)
                .snapshotUrl(meta.snapshotUrl())
                .build();

        log.info("Parcel lookup: uuid={} region_id={} region={}",
                slParcelUuid, region.getId(), region.getName());
        return new ParcelLookupResult(response, region);
    }

    private String buildSlurl(String regionName, Double x, Double y, Double z) {
        String encoded = URLEncoder.encode(regionName == null ? "" : regionName, StandardCharsets.UTF_8);
        return "https://maps.secondlife.com/secondlife/" + encoded
                + "/" + (x == null ? 128 : x.intValue())
                + "/" + (y == null ? 128 : y.intValue())
                + "/" + (z == null ? 22 : z.intValue());
    }

    public record ParcelLookupResult(ParcelResponse response, Region region) {}
}
```

The `ParcelLookupResult` record carries the resolved `Region` entity alongside the DTO so callers (AuctionService) can FK-link without re-querying.

- [ ] **Step 2: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/parcel/ParcelLookupService.java
git commit -m "feat(parcel): make ParcelLookupService stateless"
```

---

### Task 7: New `ParcelSnapshotPhotoService`

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/ParcelSnapshotPhotoService.java`

- [ ] **Step 1: Write the service**

```java
package com.slparcelauctions.backend.auction;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import com.slparcelauctions.backend.storage.PhotoStorageService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * Downloads the SL parcel snapshot URL, uploads bytes to S3, and upserts
 * an {@link AuctionPhoto} row tagged {@link PhotoSource#SL_PARCEL_SNAPSHOT}.
 * Idempotent: re-calling for the same auction overwrites the existing
 * SL_PARCEL_SNAPSHOT row's bytes (id stable, URL stable).
 *
 * <p>404 / timeout / non-image content → silent no-op. The auction simply
 * doesn't have an SL-derived photo; the seller can upload one manually.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ParcelSnapshotPhotoService {

    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(5);
    private static final long MAX_BYTES = 5 * 1024 * 1024L;

    private final WebClient.Builder webClientBuilder;
    private final AuctionPhotoRepository photoRepo;
    private final PhotoStorageService storage;

    @Transactional
    public void refreshFor(Auction auction, String slSnapshotUrl) {
        if (slSnapshotUrl == null || slSnapshotUrl.isBlank()) return;

        byte[] bytes;
        try {
            bytes = webClientBuilder.build().get()
                    .uri(slSnapshotUrl)
                    .retrieve()
                    .bodyToMono(byte[].class)
                    .timeout(DOWNLOAD_TIMEOUT)
                    .block();
        } catch (Exception e) {
            log.info("SL snapshot download failed for auction {}: {}",
                    auction.getId(), e.getMessage());
            return;
        }
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) {
            log.info("SL snapshot empty or oversized for auction {}: bytes={}",
                    auction.getId(), bytes == null ? null : bytes.length);
            return;
        }

        Optional<AuctionPhoto> existing = photoRepo
                .findFirstByAuctionIdAndSource(auction.getId(), PhotoSource.SL_PARCEL_SNAPSHOT);

        AuctionPhoto photo = existing.orElseGet(() -> AuctionPhoto.builder()
                .auction(auction)
                .source(PhotoSource.SL_PARCEL_SNAPSHOT)
                .sortOrder(0)
                .uploadedAt(OffsetDateTime.now())
                .build());
        photo.setContentType("image/jpeg");
        photo.setSizeBytes((long) bytes.length);
        photo.setUploadedAt(OffsetDateTime.now());

        AuctionPhoto saved = photoRepo.save(photo);
        storage.put(saved.getId(), bytes, "image/jpeg");
        log.info("SL snapshot saved for auction {}: photoId={} bytes={}",
                auction.getId(), saved.getId(), bytes.length);
    }
}
```

`PhotoStorageService` is the existing S3/MinIO put helper used by uploaded photos — verify the interface name during impl; if it's `S3PhotoStorageService` or similar, use that. The repo finder method is added in Task 8.

- [ ] **Step 2: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auction/ParcelSnapshotPhotoService.java
git commit -m "feat(auction): add ParcelSnapshotPhotoService for SL image -> S3"
```

---

### Task 8: Add `findFirstByAuctionIdAndSource` to `AuctionPhotoRepository`

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionPhotoRepository.java`

- [ ] **Step 1: Add the finder**

```java
Optional<AuctionPhoto> findFirstByAuctionIdAndSource(Long auctionId, PhotoSource source);
```

- [ ] **Step 2: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auction/AuctionPhotoRepository.java
git commit -m "feat(auction): add findFirstByAuctionIdAndSource finder"
```

---

### Task 9: Modify `AuctionService` — integrate snapshot in `create` and `update`

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionService.java`

- [ ] **Step 1: Replace `create` and `update` to build the snapshot from the lookup payload, save the auction (cascade saves snapshot), then call `ParcelSnapshotPhotoService.refreshFor`**

The create path receives a `slParcelUuid` (or the wizard's saved parcel data); rather than `parcelRepo.findById(parcelId)`, it now calls `ParcelLookupService.lookup(slParcelUuid)`, builds an `AuctionParcelSnapshot` from the result, attaches it to the new `Auction`, saves, then calls `parcelSnapshotPhotoService.refreshFor(auction, lookupResult.response().snapshotUrl())`.

The update path: when the request asks to refresh the parcel (re-lookup), call `ParcelLookupService.lookup` again, replace the snapshot's fields in place, re-call `refreshFor`.

`AuctionCreateRequest` switches from `parcelId` to `slParcelUuid`; same for `AuctionUpdateRequest` (already takes the lookup-derived shape). DTO migration is one-time — no users.

Pseudocode for `create`:

```java
public Auction create(Long sellerId, AuctionCreateRequest req, String ip) {
    requireVerified(sellerId);
    User seller = userRepo.findById(sellerId).orElseThrow();
    sellerSuspensionService.assertNotSuspended(seller);

    ParcelLookupService.ParcelLookupResult lookup = parcelLookupService.lookup(req.slParcelUuid());

    Auction auction = Auction.builder()
            .seller(seller)
            .title(req.title())
            .startingBid(req.startingBid())
            .reservePrice(req.reservePrice())
            .buyNowPrice(req.buyNowPrice())
            .durationHours(req.durationHours())
            .snipeProtect(Boolean.TRUE.equals(req.snipeProtect()))
            .snipeWindowMin(req.snipeWindowMin())
            .sellerDesc(req.sellerDesc())
            .status(AuctionStatus.DRAFT)
            .commissionRate(commissionDefaultRate)
            .agentFeeRate(BigDecimal.ZERO)
            .build();

    auction.setParcelSnapshot(buildSnapshot(lookup));
    applyTags(auction, req.tags());
    Auction saved = auctionRepo.save(auction);

    parcelSnapshotPhotoService.refreshFor(saved, lookup.response().snapshotUrl());
    return saved;
}

private AuctionParcelSnapshot buildSnapshot(ParcelLookupService.ParcelLookupResult lookup) {
    ParcelResponse r = lookup.response();
    return AuctionParcelSnapshot.builder()
            .slParcelUuid(r.slParcelUuid())
            .ownerUuid(r.ownerUuid())
            .ownerType(r.ownerType())
            .ownerName(r.ownerName())
            .parcelName(r.parcelName())
            .description(r.description())
            .region(lookup.region())
            .regionName(r.regionName())
            .regionMaturityRating(r.regionMaturityRating())
            .areaSqm(r.areaSqm())
            .positionX(r.positionX())
            .positionY(r.positionY())
            .positionZ(r.positionZ())
            .slurl(r.slurl())
            .verifiedAt(r.verifiedAt())
            .lastChecked(r.lastChecked())
            .build();
}
```

Update path: `if (req.refreshParcel()) { ... lookup ... apply to existing snapshot ... refreshFor }`. If the existing model just always refreshes on update when `slParcelUuid` is passed, replicate that behavior.

- [ ] **Step 2: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auction/AuctionService.java
git commit -m "feat(auction): integrate parcel snapshot in create/update"
```

---

## Phase 3 — Backend controllers + DTOs

### Task 10: Update `AuctionDtoMapper` — read parcel-shape from snapshot, drop `parcel.id`

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionDtoMapper.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/parcel/dto/ParcelResponse.java`

- [ ] **Step 1: Drop the `id` field from `ParcelResponse`**

Remove `Long id` from the record. Update the `from(...)` factory: the new `from(AuctionParcelSnapshot s)` overload reads `s.getSlParcelUuid()`, `s.getRegion().getId()` (or the snapshotted `regionId`), etc.

- [ ] **Step 2: Update mapper to use `auction.getParcelSnapshot()`**

In `AuctionDtoMapper`:

```java
ParcelResponse parcel = ParcelResponse.from(a.getParcelSnapshot());
```

Drop any `a.getParcel()` calls — replaced.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auction/AuctionDtoMapper.java backend/src/main/java/com/slparcelauctions/backend/parcel/dto/ParcelResponse.java
git commit -m "feat(auction): map parcel response from AuctionParcelSnapshot"
```

---

### Task 11: New `PhotoController` at `/api/v1/photos/{id}`

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/PhotoController.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/dto/AuctionPhotoResponse.java`

- [ ] **Step 1: Write the controller**

```java
package com.slparcelauctions.backend.auction;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.storage.PhotoStorageService;
import com.slparcelauctions.backend.storage.PhotoStorageService.StoredObject;

import lombok.RequiredArgsConstructor;

/**
 * Public photo bytes endpoint. Single flat URL serves both
 * seller-uploaded and SL_PARCEL_SNAPSHOT photos — the photo id is the
 * canonical identifier; auction membership is internal.
 */
@RestController
@RequestMapping("/api/v1/photos")
@RequiredArgsConstructor
public class PhotoController {

    private final AuctionPhotoRepository photoRepo;
    private final PhotoStorageService storage;

    @GetMapping("/{id}")
    public ResponseEntity<byte[]> get(@PathVariable Long id) {
        AuctionPhoto photo = photoRepo.findById(id)
                .orElseThrow(() -> new AuctionPhotoNotFoundException(id));
        StoredObject obj = storage.get(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(photo.getContentType()))
                .body(obj.bytes());
    }
}
```

- [ ] **Step 2: Update `AuctionPhotoResponse` URL builder**

```java
String url = "/api/v1/photos/" + p.getId();
```

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auction/PhotoController.java backend/src/main/java/com/slparcelauctions/backend/auction/dto/AuctionPhotoResponse.java
git commit -m "feat(auction): flat /api/v1/photos/{id} endpoint"
```

---

### Task 12: Remove old bytes endpoint from `AuctionPhotoController`

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionPhotoController.java`

- [ ] **Step 1: Drop the `/auctions/{id}/photos/{photoId}/bytes` GET handler**

The controller keeps the upload (POST) and delete (DELETE) handlers. Just removes the bytes GET.

- [ ] **Step 2: Also update the SQL projection in `AuctionPhotoBatchRepositoryImpl`**

```java
'/api/v1/photos/' || id AS url,
```

(was `'/api/v1/auctions/' || auction_id || '/photos/' || id || '/bytes'`)

- [ ] **Step 3: Update the URL builder in `CancellationStatusService`**

```java
.map(p -> "/api/v1/photos/" + p.getId())
```

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auction
git commit -m "refactor(auction): drop legacy /auctions/photos/bytes URL shape"
```

---

### Task 13: `@Transactional` on `AuctionController.cancel` + `update`

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionController.java`

- [ ] **Step 1: Add the annotation**

Mirror the existing `verify` method:

```java
@PutMapping("/auctions/{id}/cancel")
@org.springframework.transaction.annotation.Transactional
public SellerAuctionResponse cancel(...) { ... }

@PutMapping("/auctions/{id}")
@org.springframework.transaction.annotation.Transactional
public SellerAuctionResponse update(...) { ... }
```

- [ ] **Step 2: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auction/AuctionController.java
git commit -m "fix(auction): @Transactional on cancel + update to bracket lazy loads"
```

---

### Task 14: `SecurityConfig` — `/api/v1/photos/*` permitAll, drop old bytes path rule

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/config/SecurityConfig.java`

- [ ] **Step 1: Replace the rule**

Drop:

```java
.requestMatchers(HttpMethod.GET, "/api/v1/auctions/*/photos/*/bytes").permitAll()
```

Add (in the same position, before the catch-all):

```java
.requestMatchers(HttpMethod.GET, "/api/v1/photos/*").permitAll()
```

- [ ] **Step 2: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/config/SecurityConfig.java
git commit -m "refactor(security): permitAll on /api/v1/photos/*"
```

---

## Phase 4 — Backend config

### Task 15: `application.yml` ddl-auto: validate → update

**Files:**
- Modify: `backend/src/main/resources/application.yml`

- [ ] **Step 1: Switch the value**

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: update    # was: validate (suspended until users exist)
```

- [ ] **Step 2: Commit**

```bash
git add backend/src/main/resources/application.yml
git commit -m "chore(backend): ddl-auto validate -> update (no users yet)"
```

---

## Phase 5 — Drop legacy Parcel + repo + tests

### Task 16: Delete the legacy `Parcel` entity + repository

**Files:**
- Delete: `backend/src/main/java/com/slparcelauctions/backend/parcel/Parcel.java`
- Delete: `backend/src/main/java/com/slparcelauctions/backend/parcel/ParcelRepository.java`
- Modify: every caller — search and replace `Parcel` (entity) usage with `AuctionParcelSnapshot` or remove.

- [ ] **Step 1: Search for callers**

```bash
grep -rln "import com.slparcelauctions.backend.parcel.Parcel\b\|ParcelRepository" backend/src
```

- [ ] **Step 2: Update each caller**

Most call sites are accessing parcel data through `auction.getParcel().getXxx()`. Replace with `auction.getParcelSnapshot().getXxx()`. A handful of services directly inject `ParcelRepository` — drop the dependency, switch to `AuctionParcelSnapshotRepository` if a true CRUD path remains, or remove entirely if the path was only there to refresh-in-place (which is now a no-op).

Targets known in advance:
- `OwnershipCheckTask` (parcel ownership polling) — switches from looking up parcels by id to looking up snapshots by auction id. Its job is "for this auction, re-check the SL state and compare to the snapshot."
- `BotTaskService` / verification flow — accesses parcel UUID through the auction now.
- Any test setup helper that builds a Parcel.

- [ ] **Step 3: Delete the files**

```bash
rm backend/src/main/java/com/slparcelauctions/backend/parcel/Parcel.java
rm backend/src/main/java/com/slparcelauctions/backend/parcel/ParcelRepository.java
```

- [ ] **Step 4: `./mvnw -q compile` until clean**

```bash
cd backend && ./mvnw -q compile 2>&1 | tail -20
```

Expected: no compile errors.

- [ ] **Step 5: Commit**

```bash
git add backend
git commit -m "refactor(parcel): drop legacy Parcel entity + repo"
```

---

### Task 17: Update backend tests — fixtures + assertions

**Files:**
- Modify: every test that builds a `Parcel` row, asserts on `parcel.id`, hits `/auctions/*/photos/*/bytes`, or relies on the cache-hit-on-second-lookup behavior.

- [ ] **Step 1: ParcelLookupServiceTest — replace cache-hit assertions with stateless-DTO assertions**

Test names that contained `cacheHits` or similar get renamed and rewritten. The service no longer takes a `ParcelRepository`, so the constructor mock list shrinks.

- [ ] **Step 2: ParcelControllerIntegrationTest — same**

Drop the second-call-uses-cache test. Both calls re-fetch from SL.

- [ ] **Step 3: AuctionControllerIntegrationTest — fixtures + URL paths**

Replace `seedParcel()` (which lookups + persists a parcels row) with `seedSnapshot()` that returns the SL UUID after stubbing the SL HTTP client. Replace `parcel.id`-based create-request assertions with `slParcelUuid`. Update photo URL assertions to `/api/v1/photos/{id}`.

- [ ] **Step 4: MeWalletPayListingFeeTest — same parcel fixture replacement**

Same `seedParcel` rewrite.

- [ ] **Step 5: Run backend tests**

```bash
cd backend && ./mvnw test 2>&1 | grep -E "Tests run|BUILD|ERROR|FAIL" | tail -10
```

Expected: BUILD SUCCESS, all tests pass.

- [ ] **Step 6: Commit**

```bash
git add backend/src/test
git commit -m "test(backend): fixtures + assertions for per-auction snapshot"
```

---

## Phase 6 — Frontend

### Task 18: New `apiUrl` helper + tests

**Files:**
- Create: `frontend/src/lib/api/url.ts`
- Create: `frontend/src/lib/api/url.test.ts`

- [ ] **Step 1: Write the test first**

```ts
import { describe, it, expect, vi } from "vitest";
import { apiUrl } from "./url";

describe("apiUrl", () => {
  it("prefixes a relative path with NEXT_PUBLIC_API_URL", () => {
    vi.stubEnv("NEXT_PUBLIC_API_URL", "https://slpa.app");
    expect(apiUrl("/api/v1/photos/3")).toBe("https://slpa.app/api/v1/photos/3");
  });

  it("passes absolute http(s) URLs through unchanged", () => {
    vi.stubEnv("NEXT_PUBLIC_API_URL", "https://slpa.app");
    expect(apiUrl("https://example.com/foo.jpg")).toBe(
      "https://example.com/foo.jpg",
    );
  });

  it("falls back to localhost when NEXT_PUBLIC_API_URL is unset", () => {
    vi.stubEnv("NEXT_PUBLIC_API_URL", "");
    expect(apiUrl("/api/v1/photos/3")).toBe("http://localhost:8080/api/v1/photos/3");
  });

  it("returns null on null/undefined input", () => {
    expect(apiUrl(null)).toBeNull();
    expect(apiUrl(undefined)).toBeNull();
  });
});
```

- [ ] **Step 2: Run, expect fail**

```bash
cd frontend && npm test -- --run url.test 2>&1 | tail -5
```

- [ ] **Step 3: Implement**

```ts
const BASE = process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080";

/**
 * Resolves an opaque-path URL emitted by the backend (e.g.
 * "/api/v1/photos/3") against the configured API base. Absolute
 * http(s) URLs pass through unchanged. Returns null for null/undefined
 * input so callers can render a no-op placeholder without branching.
 */
export function apiUrl(path: string | null | undefined): string | null {
  if (path == null) return null;
  if (/^https?:\/\//i.test(path)) return path;
  return `${BASE}${path}`;
}
```

- [ ] **Step 4: Run, expect pass**

```bash
npm test -- --run url.test 2>&1 | tail -5
```

- [ ] **Step 5: Commit**

```bash
git add frontend/src/lib/api/url.ts frontend/src/lib/api/url.test.ts
git commit -m "feat(frontend): apiUrl helper for absolute-ifying backend paths"
```

---

### Task 19: Apply `apiUrl` to every `<img src={photo.url}>` site

**Files:**
- Modify: `frontend/src/components/auction/AuctionHero.tsx`
- Modify: `frontend/src/components/listing/ListingPreviewCard.tsx`
- Modify: any other component that renders `photo.url` or `parcel.snapshotUrl` into `<img>`.

- [ ] **Step 1: Search**

```bash
grep -rln "src={.*\.url}\|src={.*snapshotUrl}\|src={cover}" frontend/src/components frontend/src/app
```

- [ ] **Step 2: Wrap every match**

Each `<img src={photo.url}>` becomes `<img src={apiUrl(photo.url) ?? undefined}>`. Add `import { apiUrl } from "@/lib/api/url";` at top.

The parcel snapshot URL was also rendered directly in some places (e.g. inside `ListingPreviewCard` via the `cover` derivation). Those are now coming through as auction photos since the SL snapshot is just a photo — verify by reading the data flow in each component.

- [ ] **Step 3: Run frontend tests**

```bash
npm test 2>&1 | tail -5
```

Expected: pass. Update any test that asserts on the unwrapped relative URL.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/components frontend/src/app
git commit -m "fix(frontend): apiUrl-wrap all image src attributes"
```

---

### Task 20: Drop `parcel.id` from frontend types

**Files:**
- Modify: `frontend/src/types/parcel.ts`
- Modify: `frontend/src/types/auction.ts`
- Modify: any consumer that reads `parcel.id`.

- [ ] **Step 1: Drop the field**

```ts
// types/parcel.ts
export interface ParcelDto {
  // id removed — parcel is identified by its parent auction id
  slParcelUuid: string;
  // ...
}
```

- [ ] **Step 2: Search consumers**

```bash
grep -rn "parcel\.id\|\.parcel\.id" frontend/src
```

Remove or replace each — the only legitimate use is debugging output, since parcels no longer have their own identity.

- [ ] **Step 3: Run tests + build**

```bash
npm test 2>&1 | tail -5
npm run build 2>&1 | tail -5
```

- [ ] **Step 4: Commit**

```bash
git add frontend/src/types frontend/src
git commit -m "refactor(frontend): drop parcel.id (snapshot is auction-scoped)"
```

---

## Phase 7 — Docs

### Task 21: Update `CLAUDE.md`

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Replace the migration paragraph**

Find the current sentence about "Hibernate runs in `ddl-auto: validate`" and the migration discipline. Replace with:

```markdown
- **Database migrations**: `ddl-auto: update` until SLPA has real users. Entity changes do NOT require a Flyway migration. Breaking schema changes are handled by wiping the DB and letting Hibernate rebuild from entities. The legacy `backend/src/main/resources/db/migration/V<N>__*.sql` files stay on disk but are no-ops against a wiped DB. Re-enable Flyway-first discipline before launch.

**DB wipe procedure (prod):**

```bash
PGPASSWORD=<secret> psql -h <rds-endpoint> -U slpa -d slpa -c \
  "DROP SCHEMA public CASCADE; CREATE SCHEMA public;"
aws ecs update-service --profile slpa-prod \
  --cluster slpa-prod --service slpa-backend --force-new-deployment
```
```

- [ ] **Step 2: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: ddl-auto: update + DB wipe procedure"
```

---

### Task 22: Update `README.md`

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Sweep**

Scan the README for staleness from the parcel refactor:
- Any mention of the `parcels` table → reword for `auction_parcel_snapshots`
- Any mention of `/api/v1/auctions/*/photos/*/bytes` → reword for `/api/v1/photos/*`
- Any mention of "shared parcel row" semantics → reword for per-auction snapshot semantics

Add a one-paragraph note on the architectural shift in the Epic 03 sub-spec 2 paragraph.

- [ ] **Step 2: Commit**

```bash
git add README.md
git commit -m "docs(readme): per-auction parcel snapshot architecture sweep"
```

---

## Phase 8 — Deploy + DB wipe

### Task 23: Final test sweep

- [ ] **Step 1: Backend full suite**

```bash
cd backend && ./mvnw test 2>&1 | grep -E "Tests run:.*Failures|BUILD" | tail -5
```

Expected: BUILD SUCCESS.

- [ ] **Step 2: Frontend full suite + build + lint + verify**

```bash
cd frontend
npm test 2>&1 | tail -5
npm run build 2>&1 | tail -5
npm run lint 2>&1 | tail -5
npm run verify 2>&1 | tail -5
```

Expected: all pass.

- [ ] **Step 3: Push**

```bash
git push origin spec/per-auction-parcel-snapshot
```

---

### Task 24: PR to dev → merge

- [ ] **Step 1: Open PR**

```bash
gh pr create --base dev --title "feat: per-auction parcel snapshot + cancel-500 fix + flat photo URL" --body "<from spec doc>"
```

- [ ] **Step 2: Merge**

```bash
gh pr merge <number> --squash --delete-branch
```

---

### Task 25: PR dev → main → merge

- [ ] **Step 1: Open**

```bash
git checkout dev && git pull --ff-only
gh pr create --base main --head dev --title "<same title>" --body "<short>"
```

- [ ] **Step 2: Merge**

```bash
gh pr merge <number> --merge --delete-branch=false
```

---

### Task 26: DB wipe + force ECS redeploy

- [ ] **Step 1: Wait for backend deploy GHA workflow to land main commit**

```bash
gh run list --branch main --limit 1 --json status,conclusion,workflowName
```

Expected: backend deploy completed before issuing the wipe (otherwise the old code reconnects to the wiped schema and fails validate).

- [ ] **Step 2: Drop schema and force task replacement**

```bash
PGPASSWORD=$(aws secretsmanager get-secret-value --profile slpa-prod \
  --secret-id slpa/prod/db --query SecretString --output text | jq -r .password) \
  psql -h $RDS_ENDPOINT -U slpa -d slpa -c \
  "DROP SCHEMA public CASCADE; CREATE SCHEMA public;"

aws ecs update-service --profile slpa-prod \
  --cluster slpa-prod --service slpa-backend --force-new-deployment
```

- [ ] **Step 3: Verify**

```bash
# Wait ~2 min for the new task to come up
curl -s -o /dev/null -w "%{http_code}\n" https://slpa.app/actuator/health
```

Expected: 200.

---

## Self-review checklist

**Spec coverage:**
- New `auction_parcel_snapshots` table → Tasks 2-3 (entity + repo)
- Drop `parcels` table → Task 16 (delete entity + repo); Hibernate drops table on next ddl-auto:update against wiped DB
- `auction_photos.source` enum → Tasks 1 + 5
- Replace SL_PARCEL_SNAPSHOT in place → Task 7 (`refreshFor` upsert by `(auction_id, source)`)
- Flat `/api/v1/photos/{id}` URL → Tasks 11 + 12 + 14
- Frontend `apiUrl` helper → Tasks 18 + 19
- Cancel @Transactional fix → Task 13
- ddl-auto: update → Task 15
- DB wipe → Task 26
- CLAUDE.md update → Task 21
- README sweep → Task 22
- Drop `parcel.id` from wire → Tasks 10 + 20
- Region snapshotting (region_name + maturity) → Task 2 (entity columns) + Task 9 (populated in `buildSnapshot`)
- Parcel-locking partial unique index moves to `(sl_parcel_uuid, status)` → Task 4 (Auction entity index declaration)

**Placeholder scan:** No "TBD"/"TODO"/"implement later" found. Search-and-replace passes are described as concrete steps with grep commands.

**Type consistency:** `AuctionParcelSnapshot` is the entity name throughout. `parcelSnapshot` is the field name on `Auction`. `slParcelUuid` is the denormalized UUID column on both. `PhotoSource` is the enum (singular, no `enum` suffix). `ParcelLookupResult` is the new record returned by the lookup service. All consistent.
