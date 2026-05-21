# Theme Image Variants Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship light/dark image variants per `docs/superpowers/specs/2026-05-21-theme-image-variants-design.md`: group covers, group logos, user default cover, new group default listing picture, plus the auction sort-0 default-cover photo. Single-image upload remains supported via runtime fallback.

**Architecture:** V43 migration renames existing single-image columns to `_light_*` and adds nullable `_dark_*` siblings on 4 tables. Java entities + service + controllers + DTOs follow suit. Frontend ships a `useThemedImage` hook + `<ThemedImage>` primitive that consumes the existing `next-themes` `resolvedTheme` to pick a variant client-side. Auction photo manager exposes a `PhotoVariantsModal` for the default-cover row only.

**Tech Stack:** Spring Boot 4 / Java 24 / Flyway / JPA / Lombok; Next.js 16 / React 19 / TypeScript 5 / Tailwind 4 / `next-themes`; Vitest + RTL; Postman.

**Spec-confirmed column inventory** (`auction_photos`): `object_key` + `content_type` + `size_bytes` are all `NOT NULL` today — the migration renames all three to `_light_*` and adds three nullable `_dark_*` siblings.

**LazyInit defense per coupon hotfix #388:** New `@Transactional` annotations and `@EntityGraph`s are applied where the new endpoint methods walk lazy collections at mapping time. New controller surfaces (group default-listing-picture endpoints; auction photo dark-variant endpoints) each get a non-`@Transactional` regression test class. Existing endpoints gaining variant path params do NOT need new regression tests (the mapper chain is unchanged) — exercise judgment per-task.

---

## File Structure

**Backend — new files:**

| File | Responsibility |
|---|---|
| `backend/src/main/resources/db/migration/V43__theme_image_variants.sql` | DDL: rename + add sibling columns |
| `backend/src/main/java/com/slparcelauctions/backend/realty/controller/RealtyGroupDefaultListingImageController.java` | new POST/DELETE/GET for group default listing picture |
| `backend/src/main/java/com/slparcelauctions/backend/realty/service/RealtyGroupDefaultListingImageService.java` | upload/delete/read for group default listing picture |
| `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionPhotoDarkVariantController.java` | new POST/DELETE for the auction sort-0 dark variant |
| `backend/src/test/java/com/slparcelauctions/backend/realty/controller/RealtyGroupDefaultListingImageControllerSliceTest.java` | controller slice test |
| `backend/src/test/java/com/slparcelauctions/backend/realty/controller/RealtyGroupDefaultListingImageLazyInitRegressionTest.java` | non-`@Transactional` regression test |
| `backend/src/test/java/com/slparcelauctions/backend/auction/AuctionPhotoDarkVariantControllerIntegrationTest.java` | new endpoint integration |
| `backend/src/test/java/com/slparcelauctions/backend/auction/AuctionPhotoDarkVariantLazyInitRegressionTest.java` | non-`@Transactional` regression test |

**Backend — modify:**

| File | Change |
|---|---|
| `backend/src/main/java/com/slparcelauctions/backend/realty/RealtyGroup.java` | rename existing fields to `coverLight*`/`logoLight*`; add `coverDark*`/`logoDark*` + 6 `defaultListing*` fields |
| `backend/src/main/java/com/slparcelauctions/backend/user/User.java` | rename `defaultCover*` → `defaultCoverLight*`; add `defaultCoverDark*` |
| `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionPhoto.java` | rename `objectKey`/`contentType`/`sizeBytes` → `lightObjectKey`/`lightContentType`/`lightSizeBytes`; add `darkObjectKey`/`darkContentType`/`darkSizeBytes` |
| `backend/src/main/java/com/slparcelauctions/backend/realty/controller/RealtyGroupImageController.java` | `/cover/{variant}` and `/logo/{variant}` path-param routes |
| `backend/src/main/java/com/slparcelauctions/backend/realty/service/RealtyGroupImageService.java` | accept variant param; write to correct slot |
| `backend/src/main/java/com/slparcelauctions/backend/user/UserController.java` | `/default-cover/{variant}` path-param routes |
| `backend/src/main/java/com/slparcelauctions/backend/user/UserDefaultCoverService.java` | accept variant param |
| `backend/src/main/java/com/slparcelauctions/backend/auction/UserDefaultCoverPhotoService.java` | **rename file** to `DefaultCoverPhotoService.java`; add group-source branch; copy both variants when present |
| `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionService.java` | update injected field name `userDefaultCoverPhotoService` → `defaultCoverPhotoService` |
| `backend/src/main/java/com/slparcelauctions/backend/auction/PhotoSource.java` | add `GROUP_DEFAULT_COVER` enum value |
| `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionPhotoRepository.java` | adjust any query referencing renamed columns (likely none — JPA queries reference field names not column names) |
| `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionPhotoDto.java` (or wherever the photo DTO is) | rename `objectKey` → `lightUrl`, add `darkUrl`, expand wire shape per spec §4 |
| `backend/src/main/java/com/slparcelauctions/backend/realty/dto/RealtyGroupDto.java` (etc.) | rename `coverUrl`/`logoUrl` → `coverLightUrl`/`coverDarkUrl`/`logoLightUrl`/`logoDarkUrl`; add `defaultListingLightUrl`/`defaultListingDarkUrl` |
| `backend/src/main/java/com/slparcelauctions/backend/realty/browse/RealtyGroupRepository.java` (raw SQL query at L171) | update SQL aliases from `cover_object_key` → `cover_light_object_key` etc. |
| `backend/src/main/java/com/slparcelauctions/backend/realty/browse/RealtyGroupCardProjection.java` | rename fields to match new wire shape |
| `backend/src/main/java/com/slparcelauctions/backend/user/UserResponse.java` | `defaultCoverUrl` → `defaultCoverLightUrl`/`defaultCoverDarkUrl` |
| `backend/src/test/java/com/slparcelauctions/backend/auction/UserDefaultCoverPhotoServiceTest.java` | rename file + add group-source cases |
| Existing controller slice tests for cover/logo/default-cover | update to use `/cover/light` etc. routes; add `/dark` variant cases |

**Frontend — new files:**

| File | Responsibility |
|---|---|
| `frontend/src/lib/theme/useThemedImage.ts` | hook |
| `frontend/src/lib/theme/useThemedImage.test.tsx` | sibling test |
| `frontend/src/components/ui/ThemedImage.tsx` | primitive |
| `frontend/src/components/ui/ThemedImage.test.tsx` | sibling test |
| `frontend/src/components/auction/photo-manager/PhotoVariantsModal.tsx` | modal for the default-cover dual-slot edit |
| `frontend/src/components/auction/photo-manager/PhotoVariantsModal.test.tsx` | sibling test |
| `frontend/src/components/admin/realty-groups/RealtyGroupDefaultListingUpload.tsx` | new group admin section |
| `frontend/src/components/admin/realty-groups/RealtyGroupDefaultListingUpload.test.tsx` | sibling test |

**Frontend — modify:**

| File | Change |
|---|---|
| `frontend/src/types/user.ts` (or wherever UserResponse is typed) | rename `defaultCoverUrl` → `defaultCoverLightUrl` + `defaultCoverDarkUrl` |
| `frontend/src/types/realtyGroup.ts` | rename `coverUrl`/`logoUrl` → `*LightUrl`/`*DarkUrl`; add `defaultListing*Url` |
| `frontend/src/types/auction.ts` (photo DTO) | rename `url` → `lightUrl` + add `darkUrl` + add `source` field if not present |
| `frontend/src/lib/api/realtyGroups.ts` | add variant path param + new default-listing endpoints |
| `frontend/src/lib/api/users.ts` (or default-cover specific) | add variant path param |
| `frontend/src/lib/api/auctionPhotos.ts` | add POST/DELETE `/photos/{id}/dark` |
| `frontend/src/components/admin/realty-groups/CoverUpload.tsx` (or equivalent) | dual-slot layout |
| `frontend/src/components/admin/realty-groups/LogoUpload.tsx` | dual-slot layout |
| `frontend/src/components/wallet/DefaultCoverCard.tsx` | dual-slot layout (note: this is in `wallet/` per the survey) |
| `frontend/src/components/listing/photo-manager/*` (find the actual photo manager file) | wire `PhotoVariantsModal` to the sort-0 default-cover card |
| `frontend/src/components/realty/groups/GroupPublicPage.tsx` (or equivalent) | swap `<img>` to `<ThemedImage>` for cover + logo |
| `frontend/src/components/browse/*` and `frontend/src/components/auction/*` photo renderers | swap to `<ThemedImage>` |
| `README.md` | append a "### Theme image variants" section in Task 14 |

---

## Task Execution Order

Tasks 1-7 backend; 8 frontend foundation; 9-13 frontend surfaces; 14 wrap-up (Postman + README + PRs).

**Per-task checklist (every task must obey):**

1. TDD: write failing test first when there is testable behavior; verify the failure; implement; verify green.
2. Commit and **push** before declaring done.
3. New controller methods that return entity-derived DTOs: `@Transactional(readOnly = true)` for reads, `@Transactional` for writes.
4. New single-row repo finders whose result feeds a mapper: `@EntityGraph(attributePaths = {...})` listing the collections the mapper accesses.
5. NEW controller surfaces (group default-listing-picture endpoints, auction photo dark-variant endpoints): non-`@Transactional` regression test class. Existing endpoints gaining variant path params do not need new regression tests — the mapper chain is unchanged.
6. No emojis. No em-dashes in commit messages or any user/admin-visible copy. No AI/Claude/Anthropic mentions.
7. Use `Edit` for existing files; `Write` only for new files.

---

### Task 1: V43 migration + entity field renames

**Files:**
- Create: `backend/src/main/resources/db/migration/V43__theme_image_variants.sql`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/RealtyGroup.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/user/User.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionPhoto.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/browse/RealtyGroupRepository.java` (the raw SQL at L171 that selects `cover_object_key` → must become `cover_light_object_key`)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/browse/RealtyGroupCardProjection.java` (matching field renames)
- Modify: any DTO mapper that reads `objectKey` / `coverObjectKey` etc. — rename to `*Light*` accessors

- [ ] **Step 1: Write the migration**

```sql
-- V43__theme_image_variants.sql

-- realty_groups: cover (rename) + cover_dark (new) + logo (rename) + logo_dark (new) + default_listing (new pair)
ALTER TABLE realty_groups RENAME COLUMN cover_object_key   TO cover_light_object_key;
ALTER TABLE realty_groups RENAME COLUMN cover_content_type TO cover_light_content_type;
ALTER TABLE realty_groups RENAME COLUMN cover_size_bytes   TO cover_light_size_bytes;
ALTER TABLE realty_groups
  ADD COLUMN cover_dark_object_key   VARCHAR(500),
  ADD COLUMN cover_dark_content_type VARCHAR(100),
  ADD COLUMN cover_dark_size_bytes   BIGINT;

ALTER TABLE realty_groups RENAME COLUMN logo_object_key   TO logo_light_object_key;
ALTER TABLE realty_groups RENAME COLUMN logo_content_type TO logo_light_content_type;
ALTER TABLE realty_groups RENAME COLUMN logo_size_bytes   TO logo_light_size_bytes;
ALTER TABLE realty_groups
  ADD COLUMN logo_dark_object_key   VARCHAR(500),
  ADD COLUMN logo_dark_content_type VARCHAR(100),
  ADD COLUMN logo_dark_size_bytes   BIGINT;

ALTER TABLE realty_groups
  ADD COLUMN default_listing_light_object_key   VARCHAR(500),
  ADD COLUMN default_listing_light_content_type VARCHAR(100),
  ADD COLUMN default_listing_light_size_bytes   BIGINT,
  ADD COLUMN default_listing_dark_object_key    VARCHAR(500),
  ADD COLUMN default_listing_dark_content_type  VARCHAR(100),
  ADD COLUMN default_listing_dark_size_bytes    BIGINT;

-- users: default_cover (rename) + default_cover_dark (new)
ALTER TABLE users RENAME COLUMN default_cover_object_key   TO default_cover_light_object_key;
ALTER TABLE users RENAME COLUMN default_cover_content_type TO default_cover_light_content_type;
ALTER TABLE users RENAME COLUMN default_cover_size_bytes   TO default_cover_light_size_bytes;
ALTER TABLE users
  ADD COLUMN default_cover_dark_object_key   VARCHAR(500),
  ADD COLUMN default_cover_dark_content_type VARCHAR(100),
  ADD COLUMN default_cover_dark_size_bytes   BIGINT;

-- auction_photos: existing NOT NULL columns renamed; nullable dark siblings added
ALTER TABLE auction_photos RENAME COLUMN object_key   TO light_object_key;
ALTER TABLE auction_photos RENAME COLUMN content_type TO light_content_type;
ALTER TABLE auction_photos RENAME COLUMN size_bytes   TO light_size_bytes;
ALTER TABLE auction_photos
  ADD COLUMN dark_object_key   VARCHAR(500),
  ADD COLUMN dark_content_type VARCHAR(50),
  ADD COLUMN dark_size_bytes   BIGINT;
```

- [ ] **Step 2: Rename JPA fields on `RealtyGroup`**

Replace existing six logo/cover columns (3+3) with explicit `_light_*` names. Add six new `coverDark*`/`logoDark*` fields and six `defaultListing*` fields. Pattern:

```java
@Column(name = "logo_light_object_key", length = 500)
private String logoLightObjectKey;

@Column(name = "logo_light_content_type", length = 100)
private String logoLightContentType;

@Column(name = "logo_light_size_bytes")
private Long logoLightSizeBytes;

@Column(name = "logo_dark_object_key", length = 500)
private String logoDarkObjectKey;

@Column(name = "logo_dark_content_type", length = 100)
private String logoDarkContentType;

@Column(name = "logo_dark_size_bytes")
private Long logoDarkSizeBytes;

// same for cover_*

@Column(name = "default_listing_light_object_key", length = 500)
private String defaultListingLightObjectKey;

@Column(name = "default_listing_light_content_type", length = 100)
private String defaultListingLightContentType;

@Column(name = "default_listing_light_size_bytes")
private Long defaultListingLightSizeBytes;

@Column(name = "default_listing_dark_object_key", length = 500)
private String defaultListingDarkObjectKey;

@Column(name = "default_listing_dark_content_type", length = 100)
private String defaultListingDarkContentType;

@Column(name = "default_listing_dark_size_bytes")
private Long defaultListingDarkSizeBytes;
```

- [ ] **Step 3: Rename JPA fields on `User`**

```java
@Column(name = "default_cover_light_object_key", length = 500)
private String defaultCoverLightObjectKey;

@Column(name = "default_cover_light_content_type", length = 100)
private String defaultCoverLightContentType;

@Column(name = "default_cover_light_size_bytes")
private Long defaultCoverLightSizeBytes;

@Column(name = "default_cover_dark_object_key", length = 500)
private String defaultCoverDarkObjectKey;

@Column(name = "default_cover_dark_content_type", length = 100)
private String defaultCoverDarkContentType;

@Column(name = "default_cover_dark_size_bytes")
private Long defaultCoverDarkSizeBytes;
```

Helper method `hasDefaultCover()` becomes `hasAnyDefaultCover()` checking either variant — keep call sites compiling:

```java
public boolean hasAnyDefaultCover() {
    return defaultCoverLightObjectKey != null || defaultCoverDarkObjectKey != null;
}
```

- [ ] **Step 4: Rename JPA fields on `AuctionPhoto`**

```java
@Column(name = "light_object_key", nullable = false, length = 500)
private String lightObjectKey;

@Column(name = "light_content_type", nullable = false, length = 50)
private String lightContentType;

@Column(name = "light_size_bytes", nullable = false)
private Long lightSizeBytes;

@Column(name = "dark_object_key", length = 500)
private String darkObjectKey;

@Column(name = "dark_content_type", length = 50)
private String darkContentType;

@Column(name = "dark_size_bytes")
private Long darkSizeBytes;
```

- [ ] **Step 5: Update the raw SQL in `RealtyGroupRepository`**

The query at line 171 selects `g.cover_object_key AS coverObjectKey`. Rename:

```java
g.cover_light_object_key AS coverLightObjectKey,
g.cover_dark_object_key  AS coverDarkObjectKey,
g.logo_light_object_key  AS logoLightObjectKey,
g.logo_dark_object_key   AS logoDarkObjectKey,
g.default_listing_light_object_key AS defaultListingLightObjectKey,
g.default_listing_dark_object_key  AS defaultListingDarkObjectKey,
```

Update `RealtyGroupCardProjection.java` to expose the new accessors.

- [ ] **Step 6: Compile + run repository integration tests for affected entities**

```bash
cd backend && ./mvnw test -Dtest='RealtyGroup*RepositoryIntegrationTest,User*RepositoryIntegrationTest,AuctionPhotoRepositoryIntegrationTest'
```

Expected: green; Flyway logs `Successfully applied 1 migration to schema "public", now at version v43`.

If a test fails because a getter name changed, fix the call site in the test file in the same step (mechanical rename only).

- [ ] **Step 7: Run the full backend test suite to surface any unresolved compile/runtime errors**

```bash
cd backend && ./mvnw test
```

Many tests will fail compilation against the renamed entity fields — fix each call site mechanically. Likely sites: `UserDefaultCoverPhotoService`, `RealtyGroupImageService`, `RealtyGroupController` mappers, photo upload code paths, photo manager DTOs.

This is mechanical rename: every `getDefaultCoverObjectKey()` becomes `getDefaultCoverLightObjectKey()`. The Java compiler will list every site that needs fixing.

Existing tests should remain green after the rename — they shouldn't be asserting against specific column shapes, just behavior. If a test fails for a real reason (not rename), debug.

- [ ] **Step 8: Commit + push**

```bash
git add backend/src/main/resources/db/migration/V43__theme_image_variants.sql \
        backend/src/main/java/com/slparcelauctions/backend/realty/RealtyGroup.java \
        backend/src/main/java/com/slparcelauctions/backend/user/User.java \
        backend/src/main/java/com/slparcelauctions/backend/auction/AuctionPhoto.java \
        backend/src/main/java/com/slparcelauctions/backend/realty/browse/ \
        # plus every other file the compiler made you touch
git commit -m "feat(theme-images): V43 migration + entity field renames for light/dark variants"
git push
```

---

### Task 2: `RealtyGroupImageController` variant path-param for cover + logo

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/controller/RealtyGroupImageController.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/service/RealtyGroupImageService.java`
- Modify: existing slice test for `RealtyGroupImageController`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/realty/dto/RealtyGroupDto.java` (or equivalent) — add `coverLightUrl`/`coverDarkUrl`/`logoLightUrl`/`logoDarkUrl`

- [ ] **Step 1: Read the existing controller**

Capture the existing endpoint shapes for `/cover` and `/logo`. The base URL is `/api/v1/admin/realty-groups/{publicId}`.

- [ ] **Step 2: Replace the existing routes with variant path-param routes**

```java
@PostMapping(value = "/{publicId}/cover/{variant}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
@PreAuthorize("@realtyGroupAccessGuard.canManage(authentication, #publicId)")
@Transactional
public ResponseEntity<RealtyGroupDto> uploadCover(
        @PathVariable UUID publicId,
        @PathVariable String variant,
        @RequestParam("file") MultipartFile file) {
    ImageVariant v = ImageVariant.parse(variant);
    RealtyGroup updated = imageService.uploadCover(publicId, v, file);
    return ResponseEntity.ok(mapper.toDto(updated));
}

@DeleteMapping("/{publicId}/cover/{variant}")
@PreAuthorize("@realtyGroupAccessGuard.canManage(authentication, #publicId)")
@Transactional
public ResponseEntity<RealtyGroupDto> deleteCover(
        @PathVariable UUID publicId,
        @PathVariable String variant) {
    ImageVariant v = ImageVariant.parse(variant);
    RealtyGroup updated = imageService.deleteCover(publicId, v);
    return ResponseEntity.ok(mapper.toDto(updated));
}

@GetMapping("/{publicId}/cover/image")
public ResponseEntity<byte[]> coverBytes(
        @PathVariable UUID publicId,
        @RequestParam("variant") String variant) {
    ImageVariant v = ImageVariant.parse(variant);
    StoredObject obj = imageService.fetchCoverBytes(publicId, v);
    return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(obj.contentType()))
            .body(obj.bytes());
}
```

Repeat the same three methods for `/logo/{variant}`.

- [ ] **Step 3: Add `ImageVariant` enum**

```java
// backend/src/main/java/com/slparcelauctions/backend/common/image/ImageVariant.java
package com.slparcelauctions.backend.common.image;

import com.slparcelauctions.backend.common.exception.InvalidVariantException;  // new

public enum ImageVariant {
    LIGHT, DARK;

    public static ImageVariant parse(String value) {
        if (value == null) throw new InvalidVariantException("variant required");
        return switch (value.toLowerCase()) {
            case "light" -> LIGHT;
            case "dark" -> DARK;
            default -> throw new InvalidVariantException(value);
        };
    }

    public String slug() {
        return name().toLowerCase();
    }
}
```

Add a thin `InvalidVariantException` extending the existing project exception hierarchy; map it to a 400 response with `code = INVALID_VARIANT` via the existing `GlobalExceptionHandler` or a slice handler.

- [ ] **Step 4: Update `RealtyGroupImageService`**

Method signatures grow a `variant` param:

```java
@Transactional
public RealtyGroup uploadCover(UUID publicId, ImageVariant variant, MultipartFile file) {
    RealtyGroup group = repo.findByPublicId(publicId).orElseThrow();
    ImageUploadValidator.ValidationResult v = validator.validate(
            file.getBytes(), MAX_BYTES, MAX_DIMENSION);
    String key = "realty-groups/" + publicId + "/cover-" + variant.slug() + ".webp";
    byte[] webp = encoder.encodeToWebp(v.image());
    storage.put(key, webp, "image/webp");

    if (variant == ImageVariant.LIGHT) {
        group.setCoverLightObjectKey(key);
        group.setCoverLightContentType("image/webp");
        group.setCoverLightSizeBytes((long) webp.length);
    } else {
        group.setCoverDarkObjectKey(key);
        group.setCoverDarkContentType("image/webp");
        group.setCoverDarkSizeBytes((long) webp.length);
    }
    return group;
}

@Transactional
public RealtyGroup deleteCover(UUID publicId, ImageVariant variant) {
    RealtyGroup group = repo.findByPublicId(publicId).orElseThrow();
    String key = variant == ImageVariant.LIGHT ? group.getCoverLightObjectKey() : group.getCoverDarkObjectKey();
    if (key != null) {
        try { storage.delete(key); } catch (Exception ignored) {}
    }
    if (variant == ImageVariant.LIGHT) {
        group.setCoverLightObjectKey(null);
        group.setCoverLightContentType(null);
        group.setCoverLightSizeBytes(null);
    } else {
        group.setCoverDarkObjectKey(null);
        group.setCoverDarkContentType(null);
        group.setCoverDarkSizeBytes(null);
    }
    return group;
}
```

Same pattern for `uploadLogo` / `deleteLogo` / `fetchCoverBytes` / `fetchLogoBytes`.

- [ ] **Step 5: Update `RealtyGroupDto`**

Replace `coverUrl: String` with `coverLightUrl: String` + `coverDarkUrl: String` (and same for logo). Mapper:

```java
String coverLightUrl = group.getCoverLightObjectKey() == null ? null :
        "/api/v1/realty-groups/" + group.getPublicId() + "/cover/image?variant=light";
String coverDarkUrl = group.getCoverDarkObjectKey() == null ? null :
        "/api/v1/realty-groups/" + group.getPublicId() + "/cover/image?variant=dark";
```

- [ ] **Step 6: Update slice tests**

The existing slice tests should be modified to:
- POST `/cover/light` happy path; assert `coverLightUrl` returned
- POST `/cover/dark` happy path; assert `coverDarkUrl` returned
- DELETE `/cover/light` leaves `coverDarkUrl` populated when dark exists
- DELETE `/cover/dark` leaves `coverLightUrl` populated
- GET `/cover/image?variant=invalid` returns 400 + INVALID_VARIANT
- Same matrix for logo

- [ ] **Step 7: Run tests**

```bash
cd backend && ./mvnw test -Dtest='RealtyGroupImageController*'
```

- [ ] **Step 8: Commit + push**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/realty/ \
        backend/src/main/java/com/slparcelauctions/backend/common/image/ \
        backend/src/test/java/com/slparcelauctions/backend/realty/
git commit -m "feat(theme-images): cover + logo upload accept variant path param"
git push
```

---

### Task 3: New group default listing picture surface

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/controller/RealtyGroupDefaultListingImageController.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/realty/service/RealtyGroupDefaultListingImageService.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/realty/controller/RealtyGroupDefaultListingImageControllerSliceTest.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/realty/controller/RealtyGroupDefaultListingImageLazyInitRegressionTest.java`
- Modify: `RealtyGroupDto` (add `defaultListingLightUrl` / `defaultListingDarkUrl`)

- [ ] **Step 1: Controller**

```java
@RestController
@RequestMapping("/api/v1/admin/realty-groups")
@RequiredArgsConstructor
public class RealtyGroupDefaultListingImageController {

    private final RealtyGroupDefaultListingImageService service;
    private final RealtyGroupMapper mapper;

    @PostMapping(value = "/{publicId}/default-listing/{variant}", consumes = MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@realtyGroupAccessGuard.canManage(authentication, #publicId)")
    @Transactional
    public ResponseEntity<RealtyGroupDto> upload(@PathVariable UUID publicId,
                                                   @PathVariable String variant,
                                                   @RequestParam("file") MultipartFile file) {
        ImageVariant v = ImageVariant.parse(variant);
        RealtyGroup updated = service.upload(publicId, v, file);
        return ResponseEntity.ok(mapper.toDto(updated));
    }

    @DeleteMapping("/{publicId}/default-listing/{variant}")
    @PreAuthorize("@realtyGroupAccessGuard.canManage(authentication, #publicId)")
    @Transactional
    public ResponseEntity<RealtyGroupDto> delete(@PathVariable UUID publicId,
                                                   @PathVariable String variant) {
        ImageVariant v = ImageVariant.parse(variant);
        RealtyGroup updated = service.delete(publicId, v);
        return ResponseEntity.ok(mapper.toDto(updated));
    }

    @GetMapping("/{publicId}/default-listing/image")
    public ResponseEntity<byte[]> bytes(@PathVariable UUID publicId,
                                          @RequestParam("variant") String variant) {
        ImageVariant v = ImageVariant.parse(variant);
        StoredObject obj = service.fetchBytes(publicId, v);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(obj.contentType())).body(obj.bytes());
    }
}
```

Note: the public GET endpoint is at `/api/v1/realty-groups/{publicId}/default-listing/image` (no `admin`). The plan above scopes the entire controller to `/admin/...`; refactor by moving the GET to a separate sibling controller at the public path (mirror how `RealtyGroupImageController` exposes its public GETs). Look at the existing pattern in `RealtyGroupImageController` for the split.

- [ ] **Step 2: Service**

Mirror `RealtyGroupImageService` shape. Storage key: `realty-groups/{publicId}/default-listing-{variant}.webp`.

- [ ] **Step 3: Slice test + LazyInit regression**

Slice test covers POST/DELETE/GET per variant. LazyInit regression test seeds a group with both variants, then GETs `/realty-groups/{publicId}` (the entity DTO that exposes the URLs) outside a `@Transactional` test, asserts the response carries both URLs without LazyInit error.

- [ ] **Step 4: Update RealtyGroupDto**

Add:
```java
String defaultListingLightUrl;  // null when not set
String defaultListingDarkUrl;
```

Mapper builds the URLs the same way as cover/logo.

- [ ] **Step 5: Run tests**

```bash
cd backend && ./mvnw test -Dtest='RealtyGroupDefaultListingImage*'
```

- [ ] **Step 6: Commit + push**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/realty/ \
        backend/src/test/java/com/slparcelauctions/backend/realty/
git commit -m "feat(theme-images): group default listing picture endpoints"
git push
```

---

### Task 4: User default cover variant path-param

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/user/UserController.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/user/UserDefaultCoverService.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/user/UserResponse.java`
- Modify: existing integration test

- [ ] **Step 1: UserController routes**

Mirror Task 2's variant pattern for the three default-cover endpoints. Storage key naming: `users/{publicId}/default-cover-{variant}.webp`.

- [ ] **Step 2: Service**

Mirror `RealtyGroupImageService.uploadCover` pattern. Auth posture (self or admin) is unchanged.

- [ ] **Step 3: `UserResponse`**

Replace `defaultCoverUrl` with `defaultCoverLightUrl` + `defaultCoverDarkUrl`. Frontend types will be updated in Task 8/9.

- [ ] **Step 4: Update integration tests**

Same pattern as Task 2 — variant-aware route assertions; no new regression test class needed (existing endpoint surface, just gains a path param).

- [ ] **Step 5: Run + commit + push**

```bash
cd backend && ./mvnw test -Dtest='UserDefaultCover*,UserControllerIntegrationTest'
git add backend/src/main/java/com/slparcelauctions/backend/user/ \
        backend/src/test/java/com/slparcelauctions/backend/user/
git commit -m "feat(theme-images): user default cover accepts variant path param"
git push
```

---

### Task 5: `DefaultCoverPhotoService` refactor + group source

**Files:**
- Rename: `backend/src/main/java/com/slparcelauctions/backend/auction/UserDefaultCoverPhotoService.java` → `DefaultCoverPhotoService.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionService.java` (rename injected field)
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/PhotoSource.java` (add `GROUP_DEFAULT_COVER`)
- Rename: `backend/src/test/java/com/slparcelauctions/backend/auction/UserDefaultCoverPhotoServiceTest.java` → `DefaultCoverPhotoServiceTest.java`

- [ ] **Step 1: Rename + restructure the service**

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class DefaultCoverPhotoService {

    private final AuctionPhotoRepository photoRepo;
    private final ObjectStorageService storage;

    public void applyTo(Auction auction) {
        if (photoRepo.existsByAuctionIdAndSource(auction.getId(), PhotoSource.USER_DEFAULT_COVER)) return;
        if (photoRepo.existsByAuctionIdAndSource(auction.getId(), PhotoSource.GROUP_DEFAULT_COVER)) return;

        DefaultCoverSource src = pickSource(auction);
        if (src == null) return;  // no default cover anywhere

        AuctionPhoto photo = AuctionPhoto.builder()
                .auctionId(auction.getId())
                .sortOrder(0)
                .source(src.photoSource())
                .build();

        if (src.lightObjectKey() != null) {
            String dst = "auction-photos/" + auction.getId() + "/default-cover-light.webp";
            try { storage.copy(src.lightObjectKey(), dst); }
            catch (Exception e) { log.warn("Failed to copy light default-cover for auction {}", auction.getId(), e); return; }
            photo.setLightObjectKey(dst);
            photo.setLightContentType(src.lightContentType());
            photo.setLightSizeBytes(src.lightSizeBytes());
        }
        if (src.darkObjectKey() != null) {
            String dst = "auction-photos/" + auction.getId() + "/default-cover-dark.webp";
            try { storage.copy(src.darkObjectKey(), dst); }
            catch (Exception e) {
                log.warn("Failed to copy dark default-cover for auction {}", auction.getId(), e);
                // light succeeded; keep going with light-only
            }
            photo.setDarkObjectKey(dst);
            photo.setDarkContentType(src.darkContentType());
            photo.setDarkSizeBytes(src.darkSizeBytes());
        }
        if (photo.getLightObjectKey() == null && photo.getDarkObjectKey() == null) return;
        // If only dark was provided (rare; user uploaded only the dark slot), promote it into light
        // slot so the NOT NULL constraint holds. The renderer's fallback rule still works because
        // the row carries the available variant.
        if (photo.getLightObjectKey() == null) {
            photo.setLightObjectKey(photo.getDarkObjectKey());
            photo.setLightContentType(photo.getDarkContentType());
            photo.setLightSizeBytes(photo.getDarkSizeBytes());
            photo.setDarkObjectKey(null);
            photo.setDarkContentType(null);
            photo.setDarkSizeBytes(null);
        }
        photoRepo.save(photo);
    }

    private DefaultCoverSource pickSource(Auction auction) {
        if (auction.getRealtyGroupId() != null) {
            RealtyGroup g = realtyGroupRepo.findById(auction.getRealtyGroupId()).orElse(null);
            if (g != null && (g.getDefaultListingLightObjectKey() != null || g.getDefaultListingDarkObjectKey() != null)) {
                return DefaultCoverSource.fromGroup(g);
            }
        }
        User u = userRepo.findById(auction.getSellerId()).orElse(null);
        if (u != null && (u.getDefaultCoverLightObjectKey() != null || u.getDefaultCoverDarkObjectKey() != null)) {
            return DefaultCoverSource.fromUser(u);
        }
        return null;
    }

    private record DefaultCoverSource(
            PhotoSource photoSource,
            String lightObjectKey, String lightContentType, Long lightSizeBytes,
            String darkObjectKey,  String darkContentType,  Long darkSizeBytes) {
        static DefaultCoverSource fromUser(User u) {
            return new DefaultCoverSource(PhotoSource.USER_DEFAULT_COVER,
                    u.getDefaultCoverLightObjectKey(), u.getDefaultCoverLightContentType(), u.getDefaultCoverLightSizeBytes(),
                    u.getDefaultCoverDarkObjectKey(),  u.getDefaultCoverDarkContentType(),  u.getDefaultCoverDarkSizeBytes());
        }
        static DefaultCoverSource fromGroup(RealtyGroup g) {
            return new DefaultCoverSource(PhotoSource.GROUP_DEFAULT_COVER,
                    g.getDefaultListingLightObjectKey(), g.getDefaultListingLightContentType(), g.getDefaultListingLightSizeBytes(),
                    g.getDefaultListingDarkObjectKey(),  g.getDefaultListingDarkContentType(),  g.getDefaultListingDarkSizeBytes());
        }
    }
}
```

Inject `RealtyGroupRepository` and `UserRepository` as needed (constructor-injected).

- [ ] **Step 2: Update `PhotoSource`**

```java
public enum PhotoSource {
    USER_DEFAULT_COVER, GROUP_DEFAULT_COVER, SL_PARCEL_SNAPSHOT, SELLER_UPLOADED;
}
```

If the project uses a CHECK constraint sync helper for this enum (check via grep), the new value gets picked up automatically on next boot.

- [ ] **Step 3: Update `AuctionService`**

Rename field `userDefaultCoverPhotoService` → `defaultCoverPhotoService`. Call site `applyTo(a)` unchanged.

- [ ] **Step 4: Update + extend test file**

Rename `UserDefaultCoverPhotoServiceTest.java` → `DefaultCoverPhotoServiceTest.java`. Add cases:
- User has both variants → row has both keys
- User has only light → row has light only
- User has only dark → row has light only (promoted from dark)
- Group-owned listing + group has both variants → row source = GROUP_DEFAULT_COVER + both keys
- Group-owned + group has none + user has variants → falls back to user
- Neither → no row inserted

- [ ] **Step 5: Run tests**

```bash
cd backend && ./mvnw test -Dtest='DefaultCoverPhotoServiceTest,AuctionService*Test'
```

- [ ] **Step 6: Commit + push**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auction/ \
        backend/src/test/java/com/slparcelauctions/backend/auction/
git commit -m "feat(theme-images): DefaultCoverPhotoService rename + group source + variant copy"
git push
```

---

### Task 6: AuctionPhotoDto + photo manager DTO update

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionPhotoDto.java` (or wherever the photo DTO lives — find via grep `objectKey` in DTO-shaped files)
- Modify: the photo mapper that builds the URL
- Modify: any existing test that asserts the photo URL shape

- [ ] **Step 1: Update the DTO**

Replace single `url` field with `lightUrl` + `darkUrl`, add `source`. Mapper:

```java
String lightUrl = photo.getLightObjectKey() == null ? null :
        "/api/v1/photos/" + photo.getPublicId() + "/bytes?variant=light";
String darkUrl = photo.getDarkObjectKey() == null ? null :
        "/api/v1/photos/" + photo.getPublicId() + "/bytes?variant=dark";
```

Update the GET `/api/v1/photos/{publicId}/bytes` endpoint to accept `?variant=light|dark`:

```java
@GetMapping("/{publicId}/bytes")
public ResponseEntity<byte[]> bytes(@PathVariable UUID publicId, @RequestParam(value = "variant", defaultValue = "light") String variant) {
    ImageVariant v = ImageVariant.parse(variant);
    StoredObject obj = service.fetch(publicId, v);
    return ResponseEntity.ok().contentType(MediaType.parseMediaType(obj.contentType())).body(obj.bytes());
}
```

`defaultValue = "light"` keeps any legacy caller working — variant defaults to light.

- [ ] **Step 2: Update tests + photo service**

The photo service's `fetch(publicId)` becomes `fetch(publicId, variant)`. For non-default-cover rows, only `light` is populated; calling with `variant=dark` on a single-variant row returns... 404? Or falls back?

Decision: return 404 on a missing variant. The frontend's `useThemedImage` hook reads BOTH URLs from the DTO; if `darkUrl === null`, it never requests dark. If a third party guesses the URL `?variant=dark` on a single-variant row, 404 is correct.

- [ ] **Step 3: Run tests + commit**

```bash
cd backend && ./mvnw test -Dtest='AuctionPhoto*Test,Photo*Test'
git add backend/src/main/java/com/slparcelauctions/backend/auction/
git commit -m "feat(theme-images): AuctionPhotoDto carries lightUrl + darkUrl; bytes endpoint variant param"
git push
```

---

### Task 7: AuctionPhotoDarkVariantController

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionPhotoDarkVariantController.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/auction/AuctionPhotoDarkVariantControllerIntegrationTest.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/auction/AuctionPhotoDarkVariantLazyInitRegressionTest.java`

- [ ] **Step 1: Controller**

```java
@RestController
@RequestMapping("/api/v1/auctions/{publicId}/photos/{photoPublicId}/dark")
@RequiredArgsConstructor
public class AuctionPhotoDarkVariantController {

    private final AuctionPhotoDarkVariantService service;
    private final AuctionPhotoMapper mapper;

    @PostMapping(consumes = MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@auctionPhotoAccessGuard.canManage(authentication, #publicId)")
    @Transactional
    public AuctionPhotoDto upload(@PathVariable UUID publicId,
                                    @PathVariable UUID photoPublicId,
                                    @RequestParam("file") MultipartFile file) {
        AuctionPhoto updated = service.uploadDark(publicId, photoPublicId, file);
        return mapper.toDto(updated);
    }

    @DeleteMapping
    @PreAuthorize("@auctionPhotoAccessGuard.canManage(authentication, #publicId)")
    @Transactional
    public AuctionPhotoDto delete(@PathVariable UUID publicId, @PathVariable UUID photoPublicId) {
        AuctionPhoto updated = service.deleteDark(publicId, photoPublicId);
        return mapper.toDto(updated);
    }
}
```

Service `uploadDark`:
1. Look up photo by `photoPublicId`.
2. Verify the parent auction matches `publicId`.
3. Verify `source IN (USER_DEFAULT_COVER, GROUP_DEFAULT_COVER)`. Else throw `InvalidPhotoSourceException` mapped to 400 with `code = INVALID_PHOTO_SOURCE`.
4. Validate + encode + upload to `auction-photos/{auctionId}/{photoPublicId}-dark.webp`.
5. Set `darkObjectKey`, `darkContentType`, `darkSizeBytes`.

`deleteDark`:
1. Look up + verify source.
2. Best-effort S3 delete on `darkObjectKey`.
3. Null the three dark columns.

- [ ] **Step 2: Integration test + LazyInit regression**

Integration test covers all 6 cases from spec §8. LazyInit regression seeds a paired default-cover row, GETs `/auctions/{publicId}` (the auction DTO with photos) outside a `@Transactional` test, asserts both URLs render.

- [ ] **Step 3: Run + commit + push**

```bash
cd backend && ./mvnw test -Dtest='AuctionPhotoDarkVariant*'
git add backend/src/main/java/com/slparcelauctions/backend/auction/AuctionPhotoDarkVariantController.java \
        backend/src/main/java/com/slparcelauctions/backend/auction/AuctionPhotoDarkVariantService.java \
        backend/src/test/java/com/slparcelauctions/backend/auction/AuctionPhotoDarkVariant*
git commit -m "feat(theme-images): auction photo dark-variant POST/DELETE for default-cover rows"
git push
```

---

### Task 8: `useThemedImage` hook + `<ThemedImage>` primitive

**Files:**
- Create: `frontend/src/lib/theme/useThemedImage.ts`
- Create: `frontend/src/lib/theme/useThemedImage.test.tsx`
- Create: `frontend/src/components/ui/ThemedImage.tsx`
- Create: `frontend/src/components/ui/ThemedImage.test.tsx`

- [ ] **Step 1: Hook**

```ts
// frontend/src/lib/theme/useThemedImage.ts
"use client";
import { useTheme } from "next-themes";

export function useThemedImage(
  lightUrl: string | null | undefined,
  darkUrl: string | null | undefined,
): string | null {
  const { resolvedTheme } = useTheme();
  const primary = resolvedTheme === "dark" ? darkUrl : lightUrl;
  const fallback = resolvedTheme === "dark" ? lightUrl : darkUrl;
  return primary ?? fallback ?? null;
}
```

- [ ] **Step 2: Hook test**

```tsx
import { describe, it, expect } from "vitest";
import { renderHook } from "@testing-library/react";
import { ThemeProvider } from "next-themes";
import { useThemedImage } from "./useThemedImage";

function wrapper(theme: "light" | "dark") {
  return ({ children }: { children: React.ReactNode }) => (
    <ThemeProvider forcedTheme={theme} attribute="data-theme">
      {children}
    </ThemeProvider>
  );
}

describe("useThemedImage", () => {
  it("returns dark when both present in dark theme", () => {
    const { result } = renderHook(() => useThemedImage("L", "D"), { wrapper: wrapper("dark") });
    expect(result.current).toBe("D");
  });
  it("returns light when both present in light theme", () => {
    const { result } = renderHook(() => useThemedImage("L", "D"), { wrapper: wrapper("light") });
    expect(result.current).toBe("L");
  });
  it("falls back to light in dark theme when dark is null", () => {
    const { result } = renderHook(() => useThemedImage("L", null), { wrapper: wrapper("dark") });
    expect(result.current).toBe("L");
  });
  it("falls back to dark in light theme when light is null", () => {
    const { result } = renderHook(() => useThemedImage(null, "D"), { wrapper: wrapper("light") });
    expect(result.current).toBe("D");
  });
  it("returns null when both null", () => {
    const { result } = renderHook(() => useThemedImage(null, null), { wrapper: wrapper("light") });
    expect(result.current).toBeNull();
  });
});
```

- [ ] **Step 3: ThemedImage primitive**

```tsx
// frontend/src/components/ui/ThemedImage.tsx
"use client";
import type { ImgHTMLAttributes } from "react";
import { apiUrl } from "@/lib/api/url";
import { useThemedImage } from "@/lib/theme/useThemedImage";

type Props = {
  lightSrc: string | null | undefined;
  darkSrc: string | null | undefined;
  alt: string;
} & Omit<ImgHTMLAttributes<HTMLImageElement>, "src">;

export function ThemedImage({ lightSrc, darkSrc, alt, ...imgProps }: Props) {
  const chosen = useThemedImage(lightSrc, darkSrc);
  if (!chosen) return null;
  return <img src={apiUrl(chosen) ?? undefined} alt={alt} {...imgProps} />;
}
```

- [ ] **Step 4: ThemedImage test**

Cover: renders nothing when both null; renders `<img>` with the light variant in light theme; renders dark variant in dark theme; passes through `alt` + `className`.

- [ ] **Step 5: Run + commit + push**

```bash
cd frontend && npm test -- --run useThemedImage ThemedImage
cd frontend && npm run verify

git add frontend/src/lib/theme/ frontend/src/components/ui/ThemedImage*
git commit -m "feat(theme-images): useThemedImage hook + ThemedImage primitive"
git push
```

---

### Task 9: Group admin cover + logo upload — dual-slot UI

**Files:**
- Modify: existing `CoverUpload.tsx` and `LogoUpload.tsx` (or whatever they're called — find under `frontend/src/components/admin/realty-groups/` or via `grep cover_url frontend/src`)
- Modify: corresponding test files
- Modify: `frontend/src/lib/api/realtyGroups.ts` to accept variant + add new default-listing endpoints (defer default-listing to Task 10; cover + logo here)

- [ ] **Step 1: Update the API client**

```ts
export function uploadGroupCover(publicId: string, variant: "light" | "dark", file: File) {
  const fd = new FormData();
  fd.append("file", file);
  return api.post<RealtyGroupDto>(`/api/v1/admin/realty-groups/${publicId}/cover/${variant}`, fd);
}
export function deleteGroupCover(publicId: string, variant: "light" | "dark") {
  return api.delete<RealtyGroupDto>(`/api/v1/admin/realty-groups/${publicId}/cover/${variant}`);
}
// Same for logo
```

- [ ] **Step 2: Dual-slot card component**

The existing single-slot card becomes a paired card with two slots labeled "Light mode" and "Dark mode." Each slot has its own upload, replace, delete buttons. Below the pair, a small `<ThemedImage>` preview honors the user's theme.

- [ ] **Step 3: Update types**

`frontend/src/types/realtyGroup.ts`: `coverUrl` → `coverLightUrl: string | null; coverDarkUrl: string | null;` (and logo). Add `defaultListingLightUrl` / `defaultListingDarkUrl` (left null until Task 10's controller is in place).

- [ ] **Step 4: Tests**

Update existing tests to cover dual-slot rendering, independent upload per slot, independent delete per slot.

- [ ] **Step 5: Run + commit + push**

```bash
cd frontend && npm test -- --run CoverUpload LogoUpload
cd frontend && npm run verify

git add frontend/src/lib/api/realtyGroups.ts frontend/src/types/realtyGroup.ts \
        frontend/src/components/admin/realty-groups/
git commit -m "feat(theme-images): group cover + logo dual-slot admin UI"
git push
```

---

### Task 10: Group default listing picture — admin section

**Files:**
- Create: `frontend/src/components/admin/realty-groups/RealtyGroupDefaultListingUpload.tsx`
- Create: `frontend/src/components/admin/realty-groups/RealtyGroupDefaultListingUpload.test.tsx`
- Modify: the group admin page that hosts cover/logo upload to render the new section
- Modify: `frontend/src/lib/api/realtyGroups.ts` to add the three default-listing endpoints

- [ ] **Step 1: API client**

```ts
export function uploadGroupDefaultListing(publicId: string, variant: "light" | "dark", file: File) {
  const fd = new FormData();
  fd.append("file", file);
  return api.post<RealtyGroupDto>(`/api/v1/admin/realty-groups/${publicId}/default-listing/${variant}`, fd);
}
export function deleteGroupDefaultListing(publicId: string, variant: "light" | "dark") {
  return api.delete<RealtyGroupDto>(`/api/v1/admin/realty-groups/${publicId}/default-listing/${variant}`);
}
```

- [ ] **Step 2: Component**

Same dual-slot pattern as Task 9. Subtitle: "Used as the first photo on every listing created on behalf of this group. Light and dark variants are optional - if you upload only one, it will be used in both themes." (ASCII hyphen, not em-dash.)

- [ ] **Step 3: Render on the group admin page**

Find the page (`frontend/src/app/admin/groups/[publicId]/page.tsx` or similar). Insert the new component after the existing cover + logo upload sections.

- [ ] **Step 4: Test + verify + commit**

```bash
cd frontend && npm test -- --run RealtyGroupDefaultListingUpload
cd frontend && npm run verify
git add frontend/src/components/admin/realty-groups/RealtyGroupDefaultListingUpload* \
        frontend/src/lib/api/realtyGroups.ts \
        frontend/src/app/admin/groups/
git commit -m "feat(theme-images): group default listing picture admin section"
git push
```

---

### Task 11: User `DefaultCoverCard` dual-slot

**Files:**
- Modify: `frontend/src/components/wallet/DefaultCoverCard.tsx`
- Modify: sibling test
- Modify: `frontend/src/types/user.ts` (rename `defaultCoverUrl` to `defaultCoverLightUrl` + `defaultCoverDarkUrl`)
- Modify: any API helper that hits the user default-cover endpoint

- [ ] **Step 1: Update types**

```ts
export interface UserResponse {
  // ...
  defaultCoverLightUrl: string | null;
  defaultCoverDarkUrl: string | null;
  // ...
}
```

- [ ] **Step 2: Update API client**

```ts
export function uploadUserDefaultCover(publicId: string, variant: "light" | "dark", file: File) {
  const fd = new FormData();
  fd.append("file", file);
  return api.post<UserResponse>(`/api/v1/users/${publicId}/default-cover/${variant}`, fd);
}
export function deleteUserDefaultCover(publicId: string, variant: "light" | "dark") {
  return api.delete<UserResponse>(`/api/v1/users/${publicId}/default-cover/${variant}`);
}
```

- [ ] **Step 3: DefaultCoverCard layout**

Same dual-slot pattern. Subtitle: "Auto-inserted as the first photo on every listing you create."

- [ ] **Step 4: Test + verify + commit**

```bash
cd frontend && npm test -- --run DefaultCoverCard
cd frontend && npm run verify
git add frontend/src/components/wallet/DefaultCoverCard* \
        frontend/src/types/user.ts \
        frontend/src/lib/api/
git commit -m "feat(theme-images): user default cover dual-slot card"
git push
```

---

### Task 12: `PhotoVariantsModal` + photo manager wiring

**Files:**
- Create: `frontend/src/components/auction/photo-manager/PhotoVariantsModal.tsx`
- Create: `frontend/src/components/auction/photo-manager/PhotoVariantsModal.test.tsx`
- Modify: existing photo manager component to gate the modal open behind the default-cover card
- Modify: `frontend/src/lib/api/auctionPhotos.ts` (add POST/DELETE `/photos/{id}/dark`)
- Modify: `frontend/src/types/auction.ts` photo DTO (rename `url` → `lightUrl`, add `darkUrl`, add `source`)

- [ ] **Step 1: API client**

```ts
export function uploadPhotoDarkVariant(auctionPublicId: string, photoPublicId: string, file: File) {
  const fd = new FormData();
  fd.append("file", file);
  return api.post<AuctionPhotoDto>(`/api/v1/auctions/${auctionPublicId}/photos/${photoPublicId}/dark`, fd);
}
export function deletePhotoDarkVariant(auctionPublicId: string, photoPublicId: string) {
  return api.delete<AuctionPhotoDto>(`/api/v1/auctions/${auctionPublicId}/photos/${photoPublicId}/dark`);
}
```

- [ ] **Step 2: Modal**

Two slots. Light slot is read-only thumbnail with hover note "Edit your profile default cover to change this. Or delete the photo and upload a new one to start fresh." Dark slot is editable (upload, replace, delete).

- [ ] **Step 3: Photo manager wiring**

The default-cover card (sort 0, `source = USER_DEFAULT_COVER` or `GROUP_DEFAULT_COVER`) gets a small "Light + Dark" badge when both variants exist (or "Light + add Dark" affordance when only light). Clicking "Edit" opens the modal.

All other cards stay unchanged.

- [ ] **Step 4: Test + verify + commit**

```bash
cd frontend && npm test -- --run PhotoVariantsModal
cd frontend && npm run verify
git add frontend/src/components/auction/photo-manager/ \
        frontend/src/lib/api/auctionPhotos.ts \
        frontend/src/types/auction.ts
git commit -m "feat(theme-images): photo manager PhotoVariantsModal for default-cover dark variant"
git push
```

---

### Task 13: Swap public render surfaces to `<ThemedImage>`

**Files:**
- Modify: every component that today renders a group cover, group logo, or auction photo. Find via `grep -rn "coverUrl\|logoUrl\|photo.url" frontend/src/components` and `grep -rn "coverUrl\|logoUrl" frontend/src/app`.

- [ ] **Step 1: Group public page**

```tsx
<ThemedImage
  lightSrc={group.coverLightUrl}
  darkSrc={group.coverDarkUrl}
  alt={`${group.name} cover`}
  className="..."
/>
<ThemedImage
  lightSrc={group.logoLightUrl}
  darkSrc={group.logoDarkUrl}
  alt={`${group.name} logo`}
  className="..."
/>
```

- [ ] **Step 2: Browse + auction-detail photo galleries**

Each photo render: replace `<img src={apiUrl(photo.url)}>` with `<ThemedImage lightSrc={photo.lightUrl} darkSrc={photo.darkUrl}>`.

- [ ] **Step 3: Anywhere else a `coverUrl` / `logoUrl` is consumed in a render path**

Search exhaustively; replace each.

- [ ] **Step 4: Test + verify + commit**

```bash
cd frontend && npm test
cd frontend && npm run verify
git add frontend/src/components/ frontend/src/app/
git commit -m "feat(theme-images): swap public surfaces to ThemedImage (group page, browse, auction detail)"
git push
```

---

### Task 14: Postman + README + smoke + PR + merge dev+main

**Files:**
- Postman collection (cloud)
- `README.md`
- `docs/implementation/DEFERRED_WORK.md` (only if anything was deferred — should be empty)

- [ ] **Step 1: Postman**

Each existing image-upload request gains `/{variant}` path param. New requests for: group default-listing-picture POST/DELETE per variant; auction photo dark-variant POST/DELETE. Variable-chain `groupPublicId`, `userPublicId`, `auctionPublicId`, `photoPublicId` across an end-to-end flow.

- [ ] **Step 2: README sweep**

Add a `### Theme image variants` section under the existing feature blocks. Bullets:

```markdown
### Theme image variants

- Group covers, group logos, user default listing picture, and a new group default listing picture each accept optional light + dark uploads. A single-image upload still works; the renderer falls back to whichever variant exists.
- Listing photo manager: the auto-inserted "default cover" photo at sort 0 carries the light/dark pair through; sellers can add a dark variant post-listing-creation via the `PhotoVariantsModal`. All other auction photos remain single-image.
- Frontend uses `useThemedImage(lightUrl, darkUrl)` + `<ThemedImage>` to resolve the right variant per `next-themes` `resolvedTheme`.
- Spec: `docs/superpowers/specs/2026-05-21-theme-image-variants-design.md`.
```

- [ ] **Step 3: DEFERRED_WORK check**

Scan new code for TODO/FIXME/XXX markers:

```bash
grep -rn "TODO\|FIXME\|XXX" backend/src/main/java/com/slparcelauctions/backend/realty/ backend/src/main/java/com/slparcelauctions/backend/auction/ backend/src/main/java/com/slparcelauctions/backend/common/image/ frontend/src/lib/theme/ frontend/src/components/auction/photo-manager/
```

Document any found in DEFERRED_WORK.md. If none, no change needed.

- [ ] **Step 4: Run full test suites**

```bash
cd backend && ./mvnw test
cd frontend && npm test
cd frontend && npm run verify
```

All green required. STOP if any fail; don't open PRs with red tests.

- [ ] **Step 5: Commit README + (if any) DEFERRED_WORK**

```bash
git add README.md
# only add DEFERRED_WORK.md if changed
git commit -m "docs: README sweep for theme image variants"
git push
```

- [ ] **Step 6: Open PR feat → dev**

```bash
gh pr create --base dev --head feat/theme-image-variants \
  --title "feat(theme-images): light/dark image variants for groups + user + auction sort-0" \
  --body "$(cat <<'EOF'
## Summary
- Implements theme image variants per docs/superpowers/specs/2026-05-21-theme-image-variants-design.md.
- New tables: none. Migration V43 renames existing single-image columns to `*_light_*` and adds nullable `*_dark_*` siblings on `realty_groups`, `users`, and `auction_photos`.
- New group default listing picture surface (entity columns + admin endpoints + DTO fields).
- Auction sort-0 default-cover row carries both variants; the new POST/DELETE `/auctions/{id}/photos/{photoId}/dark` lets sellers manage the dark variant post-listing-creation.
- Frontend: `useThemedImage` hook + `<ThemedImage>` primitive resolve the right variant from `next-themes` `resolvedTheme`. All public render surfaces (group page, browse, auction detail) use the primitive.
- Photo manager: `PhotoVariantsModal` for the default-cover card only; all other cards remain single-slot.

## LazyInit defense
New controller methods are `@Transactional`; new controller surfaces (group default-listing-picture, auction photo dark variant) ship with non-`@Transactional` regression test classes.

## Test plan
- [x] backend ./mvnw test full suite green
- [x] frontend npm test full suite green
- [x] frontend npm run verify green
- [ ] Manual smoke (post-deploy): admin uploads transparent dark-text PNG to group cover light slot + light-text PNG to dark slot; toggle theme on the public group page; image swaps. User sets paired default cover; creates a listing; sort-0 photo on browse honors theme.
EOF
)"
```

Capture PR URL + number. Merge:

```bash
gh pr merge --merge <PR_NUMBER>
gh pr view <PR_NUMBER> --json state,mergeCommit -q '.state + " mergeCommit=" + (.mergeCommit.oid // "none")'
```

- [ ] **Step 7: Open PR dev → main and merge**

```bash
gh pr create --base main --head dev \
  --title "release: theme image variants" \
  --body "Promotes the theme-image-variants feature from dev to main. Tests + verify green."
gh pr merge --merge <PR_NUMBER>
gh pr view <PR_NUMBER> --json state,mergeCommit -q '.state + " mergeCommit=" + (.mergeCommit.oid // "none")'
```

- [ ] **Step 8: Monitor deploy**

```bash
gh run list --branch main --workflow 'deploy backend' --limit 1 --json status,conclusion,databaseId,url
```

Capture run id; report URL.

---

## Self-Review

**Spec coverage:**
- §1 Goal — PR body covers; the feature is described holistically across tasks
- §2 Data model — Task 1
- §3 Apply / lifecycle logic — Tasks 2, 3, 4, 5, 6, 7 across the upload, delete, fallback, auto-insert, manager paths
- §4 Backend endpoints — Tasks 2 (cover + logo), 3 (default listing), 4 (user default cover), 6 (photo bytes variant param), 7 (auction photo dark)
- §5 Frontend UI — Tasks 8 (primitives), 9 (group admin), 10 (group default listing), 11 (user default cover), 12 (photo manager), 13 (public surfaces)
- §6 Migration plan — Task 1
- §7 Configuration — no new config; spec §7 explicitly says "none new"
- §8 Testing — distributed across every task; each backend task explicitly adds the regression test class where required
- §9 Out of scope — Task 14 DEFERRED_WORK check confirms nothing deferred
- §10 Decision log — context only

**Placeholder scan:** No "TBD"/"TODO"/"implement later". Every code step has the actual code. Two places hand off implementation detail to "find via grep" — for files that may be named differently across branches; the grep command is included so the implementer locates the right file.

**Type consistency:**
- `ImageVariant` enum introduced in Task 2; reused in Tasks 3, 4, 6, 7.
- `coverLightObjectKey` / `coverDarkObjectKey` / etc. column names match across migration (Task 1) + entity (Task 1) + service (Tasks 2, 3, 4, 5) + DTO (Task 2, 3, 4) + frontend types (Tasks 9, 10, 11).
- `defaultListing*` column inventory matches between migration + entity + service + DTO + frontend.
- `auction_photos.light_*` / `dark_*` rename matches across migration + entity + service (Task 5 + 6) + DTO (Task 6) + frontend types (Task 12).
- `PhotoSource.GROUP_DEFAULT_COVER` enum value introduced in Task 5; consumed in Task 7 (the dark-variant controller's source check).
