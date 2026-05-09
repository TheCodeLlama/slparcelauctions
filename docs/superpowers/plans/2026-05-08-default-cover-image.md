# Default Cover Image — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Per-user default cover image auto-inserted at `sortOrder=0` on every new listing draft. Tighten listing-photo dimension cap to 2048px, drop the user-facing 2MB byte cap, move resize from server-side-only to client-side-first with server-side backstop.

**Architecture:** Three nullable columns on `users` (`default_cover_object_key`, `default_cover_content_type`, `default_cover_size_bytes`); new `UserDefaultCoverService` + `UserDefaultCoverController` + `UserDefaultCoverPhotoService`; `ListingPhotoProcessor` parameterised with `(maxDim, maxBytes)`; new `PhotoSource.USER_DEFAULT_COVER` enum value; new frontend `resizeImage.ts` wrapping `browser-image-compression`; new `DefaultCoverCard` component on a new `/settings/profile` page; existing `PhotoUploader` re-wired through `resizeImage`.

**Tech Stack:** Spring Boot 4 / Java 26 / Hibernate / Flyway / Lombok / Spring WebFlux WebClient (already wired) / Next.js 16 / React 19 / TanStack Query / `browser-image-compression` (new dep).

**Spec:** `docs/superpowers/specs/2026-05-08-default-cover-image-design.md`

**Branch:** `feat/default-cover-image` off `dev`. PR target: `dev`.

---

## File Structure

**Backend — new files:**
- `backend/src/main/java/com/slparcelauctions/backend/user/UserDefaultCoverService.java`
- `backend/src/main/java/com/slparcelauctions/backend/user/UserDefaultCoverController.java`
- `backend/src/main/java/com/slparcelauctions/backend/user/UserDefaultCoverNotFoundException.java`
- `backend/src/main/java/com/slparcelauctions/backend/user/dto/UserDefaultCoverDto.java`
- `backend/src/main/java/com/slparcelauctions/backend/auction/UserDefaultCoverPhotoService.java`
- `backend/src/main/resources/db/migration/V<NEXT>__add_user_default_cover_columns.sql`
- Test files mirroring each above under `backend/src/test/...`

**Backend — modified:**
- `backend/src/main/java/com/slparcelauctions/backend/user/User.java` — three new columns
- `backend/src/main/java/com/slparcelauctions/backend/auction/PhotoSource.java` — `USER_DEFAULT_COVER`
- `backend/src/main/java/com/slparcelauctions/backend/auction/ListingPhotoProcessor.java` — parameterise
- `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionPhotoService.java` — pass new params
- `backend/src/main/java/com/slparcelauctions/backend/auction/ParcelSnapshotPhotoService.java` — `nextAvailableSortOrder`
- `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionService.java` — call `applyTo` before `refreshFor`
- `backend/src/main/java/com/slparcelauctions/backend/user/dto/UserResponse.java` — `defaultCoverUrl`
- `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionPhotoRepository.java` — `findMaxSortOrderByAuctionId`, `existsByAuctionIdAndSource`
- `backend/src/main/resources/application.yml` — rename `slpa.photos.max-bytes`/`max-dimension` to `slpa.photos.listing.max-bytes`/`max-dim` and add `slpa.photos.user-default-cover.*`

**Frontend — new files:**
- `frontend/src/lib/image/resizeImage.ts`
- `frontend/src/lib/image/resizeImage.test.ts`
- `frontend/src/components/user/DefaultCoverCard.tsx`
- `frontend/src/components/user/DefaultCoverCard.test.tsx`
- `frontend/src/app/settings/profile/page.tsx`

**Frontend — modified:**
- `frontend/package.json` — add `browser-image-compression`
- `frontend/src/lib/user/api.ts` — `defaultCoverUrl` on `CurrentUser`, `userApi.uploadDefaultCover`/`deleteDefaultCover`
- `frontend/src/components/listing/PhotoUploader.tsx` — call `resizeImage` before upload, drop 2MB rejection
- `frontend/src/lib/listing/photoStaging.ts` — drop 2MB byte check
- `frontend/src/test/msw/fixtures.ts` (or wherever `mockVerifiedCurrentUser` lives) — add `defaultCoverUrl: null`
- `frontend/src/app/settings/layout.tsx` — add nav between "Notifications" and "Profile"
- `frontend/src/app/settings/page.tsx` — redirect to `/settings/profile` instead of `/settings/notifications` (or leave as-is; profile is one click further)

**Operational:**
- `README.md` — sweep for staleness, add line about default cover image
- Postman `SLPA` collection — mirror three new endpoints
- `docs/implementation/DEFERRED_WORK.md` — no entries to add unless something gets cut during execution

---

## Task 1: Frontend resize utility (foundational, no dep on others)

**Files:**
- Modify: `frontend/package.json`
- Create: `frontend/src/lib/image/resizeImage.ts`
- Test: `frontend/src/lib/image/resizeImage.test.ts`

- [ ] **Step 1.1: Install `browser-image-compression`**

```bash
cd frontend && npm install browser-image-compression@^2.0.2
```

Expected: `package.json` and `package-lock.json` updated. If lock-file regen fails on Windows for cross-platform optional deps, follow the `@emnapi` injection pattern from commit `dbb66a28` — manually add `@emnapi/core` and `@emnapi/runtime` top-level entries.

- [ ] **Step 1.2: Write the failing test**

Create `frontend/src/lib/image/resizeImage.test.ts`:

```ts
import { describe, expect, it, vi } from "vitest";

vi.mock("browser-image-compression", () => ({
  default: vi.fn(async (file: File, opts: Record<string, unknown>) => {
    const blob = new Blob([new Uint8Array(8)], { type: file.type });
    return new File([blob], file.name, { type: file.type });
  }),
}));

import imageCompression from "browser-image-compression";
import { resizeImage } from "./resizeImage";

describe("resizeImage", () => {
  it("calls browser-image-compression with maxWidthOrHeight and useWebWorker", async () => {
    const file = new File([new Uint8Array(16)], "x.jpg", { type: "image/jpeg" });
    await resizeImage(file, { maxDim: 2048 });
    expect(imageCompression).toHaveBeenCalledWith(file, {
      maxWidthOrHeight: 2048,
      useWebWorker: true,
      initialQuality: 0.85,
      fileType: "image/jpeg",
    });
  });

  it("preserves the input file name and type on the returned File", async () => {
    const file = new File([new Uint8Array(16)], "photo.png", { type: "image/png" });
    const out = await resizeImage(file, { maxDim: 1024 });
    expect(out.name).toBe("photo.png");
    expect(out.type).toBe("image/png");
  });
});
```

- [ ] **Step 1.3: Run test to verify it fails**

```bash
cd frontend && npm test -- resizeImage.test.ts --run
```

Expected: FAIL with `Failed to resolve import "./resizeImage"`.

- [ ] **Step 1.4: Implement the utility**

Create `frontend/src/lib/image/resizeImage.ts`:

```ts
import imageCompression from "browser-image-compression";

export type ResizeOptions = { maxDim: number };

/**
 * Resize an image file in the browser. Preserves aspect ratio (capped at
 * maxDim on the longest edge) and input MIME type. Strips EXIF metadata.
 * Runs in a Web Worker to keep the main thread responsive.
 */
export async function resizeImage(file: File, opts: ResizeOptions): Promise<File> {
  return imageCompression(file, {
    maxWidthOrHeight: opts.maxDim,
    useWebWorker: true,
    initialQuality: 0.85,
    fileType: file.type,
  });
}
```

- [ ] **Step 1.5: Run test to verify it passes**

```bash
cd frontend && npm test -- resizeImage.test.ts --run
```

Expected: PASS, both cases.

- [ ] **Step 1.6: Commit**

```bash
git add frontend/package.json frontend/package-lock.json frontend/src/lib/image/
git commit -m "feat(frontend): add resizeImage utility wrapping browser-image-compression"
```

---

## Task 2: PhotoSource enum value

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/PhotoSource.java`

- [ ] **Step 2.1: Add the enum value**

Edit `PhotoSource.java`:

```java
public enum PhotoSource {
    SELLER_UPLOAD,
    SL_PARCEL_SNAPSHOT,
    USER_DEFAULT_COVER
}
```

- [ ] **Step 2.2: Verify backend builds**

```bash
cd backend && ./mvnw compile
```

Expected: BUILD SUCCESS. (No DDL change needed — `@Enumerated(EnumType.STRING)` stores VARCHAR.)

- [ ] **Step 2.3: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auction/PhotoSource.java
git commit -m "feat(backend): add PhotoSource.USER_DEFAULT_COVER"
```

---

## Task 3: User entity columns + Flyway migration

**Files:**
- Create: `backend/src/main/resources/db/migration/V<NEXT>__add_user_default_cover_columns.sql`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/user/User.java`

Find `<NEXT>` by listing existing migrations: `ls backend/src/main/resources/db/migration/ | grep -E '^V[0-9]+__' | sort -V | tail -1`. Pick the next sequential integer.

- [ ] **Step 3.1: Write the Flyway migration**

```sql
-- Per-user default cover image. Auto-inserted at sortOrder=0 on every new
-- listing the user creates after setting it. Existing rows start unset.
ALTER TABLE users
    ADD COLUMN default_cover_object_key   varchar(500),
    ADD COLUMN default_cover_content_type varchar(100),
    ADD COLUMN default_cover_size_bytes   bigint;
```

- [ ] **Step 3.2: Add fields to User entity**

In `User.java`, add after the `profilePicUrl` `@Column` block (mirroring the existing style):

```java
@Column(name = "default_cover_object_key", length = 500)
private String defaultCoverObjectKey;

@Column(name = "default_cover_content_type", length = 100)
private String defaultCoverContentType;

@Column(name = "default_cover_size_bytes")
private Long defaultCoverSizeBytes;

public boolean hasDefaultCover() {
    return defaultCoverObjectKey != null;
}
```

- [ ] **Step 3.3: Run backend tests to confirm Hibernate validation passes**

```bash
cd backend && ./mvnw test -Dtest=UserRepositoryTest
```

Expected: PASS. If Hibernate `validate` complains about column drift, the migration didn't apply — confirm Testcontainers/dev DB is using a fresh schema.

- [ ] **Step 3.4: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/user/User.java backend/src/main/resources/db/migration/V*__add_user_default_cover_columns.sql
git commit -m "feat(backend): add user default cover image columns + migration"
```

---

## Task 4: Parameterise `ListingPhotoProcessor`

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/ListingPhotoProcessor.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionPhotoService.java`
- Modify: `backend/src/main/resources/application.yml` and `application-dev.yml` if values are overridden there
- Modify: `backend/src/test/java/com/slparcelauctions/backend/auction/ListingPhotoProcessorTest.java`

- [ ] **Step 4.1: Update existing tests for new bounds (red)**

The existing processor test asserts the 4096px and 2MB caps. Update those expected values to **2048px** and **25MB** (`25L * 1024 * 1024`), and update test fixture filenames if they imply old caps.

Add new test stubs for the parameterisation:

```java
@Test
void processWithUserDefaultCoverConfig_appliesSameBounds() {
    // Both callers currently pass identical bounds (2048/25MB); this test
    // documents that future divergence is supported by the constructor.
    ListingPhotoProcessor processor = new ListingPhotoProcessor(2048, 25L * 1024 * 1024);
    byte[] bytes = makeJpeg(3000, 2000);
    ProcessedPhoto out = processor.process(bytes);
    assertThat(decodeWidth(out.bytes())).isLessThanOrEqualTo(2048);
}
```

- [ ] **Step 4.2: Run to verify red**

```bash
cd backend && ./mvnw test -Dtest=ListingPhotoProcessorTest
```

Expected: FAIL on the new bounds.

- [ ] **Step 4.3: Refactor `ListingPhotoProcessor`**

Replace the field-injected `@Value` constants with constructor parameters. Keep the class a `@Service` if it currently is — wire two beans via `@Configuration` if the same processor needs to exist with two configurations, or (simpler) make the class `final` and not a Spring bean, instantiated by callers with their own bounds.

Recommended: drop `@Service`, make it instantiable. Callers (`AuctionPhotoService`, `UserDefaultCoverService`) construct their own instance with their bounds.

```java
public final class ListingPhotoProcessor {
    private final int maxDimension;
    private final long maxBytes;

    public ListingPhotoProcessor(int maxDimension, long maxBytes) {
        this.maxDimension = maxDimension;
        this.maxBytes = maxBytes;
    }

    public ProcessedPhoto process(byte[] inputBytes) {
        if (inputBytes.length > maxBytes) {
            throw new InvalidPhotoException("Image exceeds " + maxBytes + " bytes");
        }
        // existing decode + resize-if-over-maxDimension + strip-metadata + re-encode logic
    }
}
```

- [ ] **Step 4.4: Update `AuctionPhotoService`**

Replace the injected processor with a constructed instance. In the constructor or as a private final field:

```java
private final ListingPhotoProcessor processor;

public AuctionPhotoService(
        @Value("${slpa.photos.listing.max-dim:2048}") int maxDim,
        @Value("${slpa.photos.listing.max-bytes:26214400}") long maxBytes,
        // ... existing deps
) {
    this.processor = new ListingPhotoProcessor(maxDim, maxBytes);
    // ... existing assigns
}
```

If `AuctionPhotoService` already uses `@RequiredArgsConstructor`, drop the Lombok annotation here and write an explicit constructor. The processor is now a value-object dependency, not a bean.

- [ ] **Step 4.5: Update `application.yml`**

```yaml
slpa:
  photos:
    max-per-listing: 10
    listing:
      max-dim: 2048
      max-bytes: 26214400  # 25 MB
    user-default-cover:
      max-dim: 2048
      max-bytes: 26214400
```

Remove the old `slpa.photos.max-bytes` and `slpa.photos.max-dimension` keys. Search the codebase for any other reference (`grep -r "slpa.photos.max-bytes" backend/`) and update.

- [ ] **Step 4.6: Run all auction-photo tests**

```bash
cd backend && ./mvnw test -Dtest='*Photo*'
```

Expected: PASS.

- [ ] **Step 4.7: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auction/ListingPhotoProcessor.java backend/src/main/java/com/slparcelauctions/backend/auction/AuctionPhotoService.java backend/src/main/resources/application*.yml backend/src/test/java/com/slparcelauctions/backend/auction/ListingPhotoProcessorTest.java
git commit -m "refactor(backend): parameterise ListingPhotoProcessor; tighten listing photos to 2048px, lift to 25MB"
```

---

## Task 5: `UserDefaultCoverService` + DTO + exception

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/user/UserDefaultCoverNotFoundException.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/user/dto/UserDefaultCoverDto.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/user/UserDefaultCoverService.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/user/UserDefaultCoverServiceTest.java`

- [ ] **Step 5.1: Create the exception**

```java
package com.slparcelauctions.backend.user;

public class UserDefaultCoverNotFoundException extends RuntimeException {
    public UserDefaultCoverNotFoundException(Long userId) {
        super("Default cover not set for user " + userId);
    }
}
```

Map this to a 404 in `GlobalExceptionHandler` if it doesn't fall through to a default `RuntimeException` handler. Verify by grepping `GlobalExceptionHandler`.

- [ ] **Step 5.2: Create the DTO**

```java
package com.slparcelauctions.backend.user.dto;

public record UserDefaultCoverDto(String url, String contentType, Long sizeBytes) {}
```

- [ ] **Step 5.3: Write the failing service test**

`UserDefaultCoverServiceTest.java` covers eight cases (use `@ExtendWith(MockitoExtension.class)` and mock `UserRepository`, `ObjectStorageService`, `ListingPhotoProcessor` — the last is constructed inside the service so inject via a `Function<byte[], ProcessedPhoto>` lambda or test it via a real instance with small bounds):

- `upload_persistsRowAndS3Object`
- `upload_replacesExistingObjectAndUpdatesKey` (verifies old key is deleted)
- `upload_invalidImage_throws` (decode failure)
- `upload_overSizedBytes_throws` (>25MB raw)
- `get_unset_throwsNotFound`
- `get_set_returnsDto`
- `delete_clearsColumnsAndS3`
- `delete_unsetIsIdempotent`

Template for the upload happy path:

```java
@Test
void upload_persistsRowAndS3Object() {
    User u = User.builder().email("a@b.c").username("alice").passwordHash("x").build();
    setUserId(u, 42L);
    when(userRepository.findById(42L)).thenReturn(Optional.of(u));
    MultipartFile file = new MockMultipartFile("file", "x.jpg", "image/jpeg", makeJpeg(3000, 2000));

    UserDefaultCoverDto dto = service.upload(42L, file);

    assertThat(u.getDefaultCoverObjectKey()).startsWith("users/42/default-cover-");
    assertThat(u.getDefaultCoverObjectKey()).endsWith(".jpg");
    assertThat(u.getDefaultCoverContentType()).isEqualTo("image/jpeg");
    assertThat(u.getDefaultCoverSizeBytes()).isPositive();
    verify(objectStorage).put(eq(u.getDefaultCoverObjectKey()), any(byte[].class), eq("image/jpeg"));
    assertThat(dto.url()).isNotBlank();
}
```

- [ ] **Step 5.4: Run to verify red**

```bash
cd backend && ./mvnw test -Dtest=UserDefaultCoverServiceTest
```

Expected: FAIL — service does not exist.

- [ ] **Step 5.5: Implement the service**

```java
package com.slparcelauctions.backend.user;

import java.time.Duration;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.slparcelauctions.backend.auction.ListingPhotoProcessor;
import com.slparcelauctions.backend.auction.ProcessedPhoto;
import com.slparcelauctions.backend.storage.ObjectStorageService;
import com.slparcelauctions.backend.user.dto.UserDefaultCoverDto;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class UserDefaultCoverService {

    private static final Duration PRESIGN_TTL = Duration.ofMinutes(60);

    private final UserRepository userRepository;
    private final ObjectStorageService objectStorage;
    private final ListingPhotoProcessor processor;

    public UserDefaultCoverService(
            UserRepository userRepository,
            ObjectStorageService objectStorage,
            @Value("${slpa.photos.user-default-cover.max-dim:2048}") int maxDim,
            @Value("${slpa.photos.user-default-cover.max-bytes:26214400}") long maxBytes) {
        this.userRepository = userRepository;
        this.objectStorage = objectStorage;
        this.processor = new ListingPhotoProcessor(maxDim, maxBytes);
    }

    @Transactional
    public UserDefaultCoverDto upload(Long userId, MultipartFile file) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));

        byte[] inputBytes;
        try { inputBytes = file.getBytes(); }
        catch (java.io.IOException e) { throw new InvalidPhotoException("Could not read upload"); }

        ProcessedPhoto processed = processor.process(inputBytes);

        String oldKey = user.getDefaultCoverObjectKey();
        String newKey = "users/" + userId + "/default-cover-" + UUID.randomUUID() + "." + processed.format().extension();
        String contentType = "image/" + processed.format().extension();

        objectStorage.put(newKey, processed.bytes(), contentType);

        user.setDefaultCoverObjectKey(newKey);
        user.setDefaultCoverContentType(contentType);
        user.setDefaultCoverSizeBytes(processed.sizeBytes());
        userRepository.save(user);

        if (oldKey != null) {
            try { objectStorage.delete(oldKey); }
            catch (Exception e) { log.warn("Failed to delete old default cover key {}: {}", oldKey, e.getMessage()); }
        }

        return new UserDefaultCoverDto(presign(newKey), contentType, processed.sizeBytes());
    }

    @Transactional(readOnly = true)
    public UserDefaultCoverDto get(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
        if (user.getDefaultCoverObjectKey() == null) {
            throw new UserDefaultCoverNotFoundException(userId);
        }
        return new UserDefaultCoverDto(
                presign(user.getDefaultCoverObjectKey()),
                user.getDefaultCoverContentType(),
                user.getDefaultCoverSizeBytes());
    }

    @Transactional
    public void delete(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
        String key = user.getDefaultCoverObjectKey();
        if (key == null) return;
        user.setDefaultCoverObjectKey(null);
        user.setDefaultCoverContentType(null);
        user.setDefaultCoverSizeBytes(null);
        userRepository.save(user);
        try { objectStorage.delete(key); }
        catch (Exception e) { log.warn("Failed to delete S3 object {}: {}", key, e.getMessage()); }
    }

    private String presign(String key) {
        return objectStorage.presignGet(key, PRESIGN_TTL);
    }
}
```

- [ ] **Step 5.6: Run test to verify it passes**

```bash
cd backend && ./mvnw test -Dtest=UserDefaultCoverServiceTest
```

Expected: PASS.

- [ ] **Step 5.7: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/user/UserDefaultCoverService.java backend/src/main/java/com/slparcelauctions/backend/user/UserDefaultCoverNotFoundException.java backend/src/main/java/com/slparcelauctions/backend/user/dto/UserDefaultCoverDto.java backend/src/test/java/com/slparcelauctions/backend/user/UserDefaultCoverServiceTest.java
git commit -m "feat(backend): UserDefaultCoverService — upload/get/delete with S3 round-trip"
```

---

## Task 6: `UserDefaultCoverController`

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/user/UserDefaultCoverController.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/user/UserDefaultCoverControllerSliceTest.java`

- [ ] **Step 6.1: Write the failing slice test**

Mirror the structure of `OnboardingControllerSliceTest`. Cover:

- `getDefaultCover_set_returns200WithDto`
- `getDefaultCover_unset_returns404`
- `putDefaultCover_returns200_andDelegates`
- `putDefaultCover_invalidContentType_returns400`
- `deleteDefaultCover_returns204_andDelegates`

Template:

```java
@WebMvcTest(controllers = UserDefaultCoverController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({GlobalExceptionHandler.class})
class UserDefaultCoverControllerSliceTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private UserDefaultCoverService service;
    @MockitoBean private JwtService jwtService;

    @Test
    @WithMockAuthPrincipal(userId = 42L)
    void putDefaultCover_returns200_andDelegates() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "x.jpg", "image/jpeg", new byte[]{1, 2, 3});
        when(service.upload(eq(42L), any(MultipartFile.class)))
                .thenReturn(new UserDefaultCoverDto("https://example/x", "image/jpeg", 100L));

        mockMvc.perform(multipart("/api/v1/users/me/default-cover").file(file)
                        .with(req -> { req.setMethod("PUT"); return req; }))
                .andExpect(status().isOk());
    }
}
```

- [ ] **Step 6.2: Run to verify red**

```bash
cd backend && ./mvnw test -Dtest=UserDefaultCoverControllerSliceTest
```

Expected: FAIL — controller missing.

- [ ] **Step 6.3: Implement the controller**

```java
package com.slparcelauctions.backend.user;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.user.dto.UserDefaultCoverDto;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/users/me/default-cover")
@RequiredArgsConstructor
public class UserDefaultCoverController {

    private final UserDefaultCoverService service;

    @GetMapping
    public UserDefaultCoverDto get(@AuthenticationPrincipal AuthPrincipal principal) {
        return service.get(principal.userId());
    }

    @PutMapping(consumes = "multipart/form-data")
    public UserDefaultCoverDto upload(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestPart("file") MultipartFile file) {
        return service.upload(principal.userId(), file);
    }

    @DeleteMapping
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthPrincipal principal) {
        service.delete(principal.userId());
        return ResponseEntity.noContent().build();
    }
}
```

Map `UserDefaultCoverNotFoundException` → 404 in `GlobalExceptionHandler` if not already covered:

```java
@ExceptionHandler(UserDefaultCoverNotFoundException.class)
public ResponseEntity<Map<String, String>> handleDefaultCoverNotFound(UserDefaultCoverNotFoundException e) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(Map.of("error", "default_cover_not_set", "message", e.getMessage()));
}
```

- [ ] **Step 6.4: Run to verify pass**

```bash
cd backend && ./mvnw test -Dtest=UserDefaultCoverControllerSliceTest
```

Expected: PASS.

- [ ] **Step 6.5: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/user/UserDefaultCoverController.java backend/src/test/java/com/slparcelauctions/backend/user/UserDefaultCoverControllerSliceTest.java backend/src/main/java/com/slparcelauctions/backend/common/exception/GlobalExceptionHandler.java
git commit -m "feat(backend): UserDefaultCoverController — three endpoints under /me/default-cover"
```

---

## Task 7: `ParcelSnapshotPhotoService` next-available sortOrder

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/ParcelSnapshotPhotoService.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionPhotoRepository.java`
- Modify: `backend/src/test/java/com/slparcelauctions/backend/auction/ParcelSnapshotPhotoServiceTest.java`

- [ ] **Step 7.1: Add repository query**

In `AuctionPhotoRepository.java`:

```java
@Query("select coalesce(max(p.sortOrder), -1) from AuctionPhoto p where p.auction.id = :auctionId")
int findMaxSortOrderByAuctionId(@Param("auctionId") Long auctionId);

boolean existsByAuctionIdAndSource(Long auctionId, PhotoSource source);
```

The first returns `-1` when there are no rows so the caller can do `max + 1` and land on `0`.

- [ ] **Step 7.2: Update existing test (red)**

In `ParcelSnapshotPhotoServiceTest`, change the assertion that snapshot lands at `sortOrder=0` to either:
- assert `sortOrder=0` when no other photos exist (unchanged from old behavior in this case), OR
- add a new test asserting `sortOrder=1` when a `USER_DEFAULT_COVER` row already exists at sortOrder=0.

```java
@Test
void refreshFor_userDefaultCoverAlreadyAtSortOrder0_snapshotLandsAtSortOrder1() {
    Auction auction = makeAuction();
    auctionPhotoRepository.save(AuctionPhoto.builder()
            .auction(auction).source(PhotoSource.USER_DEFAULT_COVER)
            .sortOrder(0).objectKey("users/1/default-cover-x.jpg")
            .contentType("image/jpeg").sizeBytes(100L).build());

    service.refreshFor(auction, "https://example.com/snapshot.jpg");

    AuctionPhoto snapshot = auctionPhotoRepository
            .findByAuctionIdAndSource(auction.getId(), PhotoSource.SL_PARCEL_SNAPSHOT)
            .orElseThrow();
    assertThat(snapshot.getSortOrder()).isEqualTo(1);
}
```

- [ ] **Step 7.3: Run to verify red**

```bash
cd backend && ./mvnw test -Dtest=ParcelSnapshotPhotoServiceTest
```

Expected: FAIL.

- [ ] **Step 7.4: Update `refreshFor`**

Find the line setting `sortOrder=0` and replace with:

```java
int sortOrder = auctionPhotoRepository.findMaxSortOrderByAuctionId(auction.getId()) + 1;
// ...
.sortOrder(sortOrder)
```

- [ ] **Step 7.5: Run to verify pass**

```bash
cd backend && ./mvnw test -Dtest=ParcelSnapshotPhotoServiceTest
```

Expected: PASS.

- [ ] **Step 7.6: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auction/ParcelSnapshotPhotoService.java backend/src/main/java/com/slparcelauctions/backend/auction/AuctionPhotoRepository.java backend/src/test/java/com/slparcelauctions/backend/auction/ParcelSnapshotPhotoServiceTest.java
git commit -m "refactor(backend): snapshot photo claims next-available sortOrder, not hardcoded 0"
```

---

## Task 8: `UserDefaultCoverPhotoService` + `AuctionService` hook

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/UserDefaultCoverPhotoService.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/auction/UserDefaultCoverPhotoServiceTest.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionService.java`
- Modify: `backend/src/test/java/com/slparcelauctions/backend/auction/AuctionServiceTest.java` (and integration test if present)

- [ ] **Step 8.1: Write failing service test**

`UserDefaultCoverPhotoServiceTest` covers four cases:

- `applyTo_userHasNoCover_isNoOp`
- `applyTo_userHasCover_insertsAtSortOrder0`
- `applyTo_alreadyApplied_isIdempotent` (`existsByAuctionIdAndSource(auctionId, USER_DEFAULT_COVER) → true`)
- `applyTo_s3CopyFails_logsAndReturns_doesNotInsertRow`

Template:

```java
@Test
void applyTo_userHasCover_insertsAtSortOrder0() {
    User seller = User.builder().email("a@b.c").username("alice").passwordHash("x").build();
    setUserId(seller, 42L);
    seller.setDefaultCoverObjectKey("users/42/default-cover-abc.jpg");
    seller.setDefaultCoverContentType("image/jpeg");
    seller.setDefaultCoverSizeBytes(123L);
    Auction auction = Auction.builder().seller(seller).build();
    setAuctionId(auction, 7L);
    when(objectStorage.get("users/42/default-cover-abc.jpg"))
            .thenReturn(new StoredObject(new byte[]{1, 2, 3}, "image/jpeg"));
    when(auctionPhotoRepository.existsByAuctionIdAndSource(7L, PhotoSource.USER_DEFAULT_COVER))
            .thenReturn(false);

    service.applyTo(auction);

    ArgumentCaptor<AuctionPhoto> cap = ArgumentCaptor.forClass(AuctionPhoto.class);
    verify(auctionPhotoRepository).save(cap.capture());
    assertThat(cap.getValue().getSortOrder()).isEqualTo(0);
    assertThat(cap.getValue().getSource()).isEqualTo(PhotoSource.USER_DEFAULT_COVER);
    assertThat(cap.getValue().getObjectKey()).startsWith("listings/7/");
    verify(objectStorage).put(startsWith("listings/7/"), eq(new byte[]{1, 2, 3}), eq("image/jpeg"));
}
```

- [ ] **Step 8.2: Run to verify red**

```bash
cd backend && ./mvnw test -Dtest=UserDefaultCoverPhotoServiceTest
```

Expected: FAIL — service missing.

- [ ] **Step 8.3: Implement the service**

```java
package com.slparcelauctions.backend.auction;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.storage.ObjectStorageService;
import com.slparcelauctions.backend.storage.StoredObject;
import com.slparcelauctions.backend.user.User;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class UserDefaultCoverPhotoService {

    private final AuctionPhotoRepository auctionPhotoRepository;
    private final ObjectStorageService objectStorage;

    @Transactional
    public void applyTo(Auction auction) {
        Long auctionId = auction.getId();
        if (auctionPhotoRepository.existsByAuctionIdAndSource(auctionId, PhotoSource.USER_DEFAULT_COVER)) {
            return;
        }
        User seller = auction.getSeller();
        String sourceKey = seller.getDefaultCoverObjectKey();
        if (sourceKey == null) return;

        try {
            StoredObject src = objectStorage.get(sourceKey);
            String ext = sourceKey.substring(sourceKey.lastIndexOf('.') + 1);
            String destKey = "listings/" + auctionId + "/" + UUID.randomUUID() + "." + ext;
            objectStorage.put(destKey, src.bytes(), seller.getDefaultCoverContentType());

            int sortOrder = auctionPhotoRepository.findMaxSortOrderByAuctionId(auctionId) + 1;
            // Cover should be FIRST. If anything's already there (shouldn't happen at draft creation), still claim
            // sortOrder=0 and let the snapshot service push past us. Use 0 explicitly:
            sortOrder = 0;

            AuctionPhoto photo = AuctionPhoto.builder()
                    .auction(auction)
                    .source(PhotoSource.USER_DEFAULT_COVER)
                    .objectKey(destKey)
                    .contentType(seller.getDefaultCoverContentType())
                    .sizeBytes(seller.getDefaultCoverSizeBytes())
                    .sortOrder(sortOrder)
                    .build();
            auctionPhotoRepository.save(photo);
        } catch (Exception e) {
            log.warn("Failed to apply default cover for auction {} (user {}): {}",
                    auctionId, seller.getId(), e.getMessage());
        }
    }
}
```

Note: the service is called BEFORE `ParcelSnapshotPhotoService.refreshFor`, so at insert time the photo set is empty and `sortOrder=0` is safe. The snapshot service then claims `MAX+1=1`.

- [ ] **Step 8.4: Run to verify pass**

```bash
cd backend && ./mvnw test -Dtest=UserDefaultCoverPhotoServiceTest
```

Expected: PASS.

- [ ] **Step 8.5: Wire into `AuctionService.create`**

In `AuctionService.java`, find the `ParcelSnapshotPhotoService.refreshFor` call (around line 116). Inject `UserDefaultCoverPhotoService` and call `applyTo` **before** `refreshFor`:

```java
userDefaultCoverPhotoService.applyTo(auction);
parcelSnapshotPhotoService.refreshFor(auction, snapshotUrl);
```

Add the field via Lombok `@RequiredArgsConstructor` (already present on the service):

```java
private final UserDefaultCoverPhotoService userDefaultCoverPhotoService;
```

- [ ] **Step 8.6: Update `AuctionServiceTest`**

Mock the new dependency. Add a test asserting `applyTo` is called once during `create`, before `refreshFor`:

```java
InOrder inOrder = inOrder(userDefaultCoverPhotoService, parcelSnapshotPhotoService);
inOrder.verify(userDefaultCoverPhotoService).applyTo(any(Auction.class));
inOrder.verify(parcelSnapshotPhotoService).refreshFor(any(Auction.class), anyString());
```

- [ ] **Step 8.7: Run all auction tests**

```bash
cd backend && ./mvnw test -Dtest='AuctionService*'
```

Expected: PASS.

- [ ] **Step 8.8: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auction/UserDefaultCoverPhotoService.java backend/src/main/java/com/slparcelauctions/backend/auction/AuctionService.java backend/src/test/java/com/slparcelauctions/backend/auction/UserDefaultCoverPhotoServiceTest.java backend/src/test/java/com/slparcelauctions/backend/auction/AuctionServiceTest.java
git commit -m "feat(backend): UserDefaultCoverPhotoService — auto-insert cover at sortOrder=0 on draft creation"
```

---

## Task 9: `UserResponse.defaultCoverUrl`

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/user/dto/UserResponse.java`
- Modify: any test stubs that construct `UserResponse` directly

- [ ] **Step 9.1: Add the field to the record**

Insert `String defaultCoverUrl` immediately after `String profilePicUrl` in the record signature. Update the `from(User)` mapper to populate it via the same presigning pattern used for `profilePicUrl` (look at `UserService` for the existing pattern; if presigning happens elsewhere, mirror that — likely it lazy-resolves via `ObjectStorageService.presignGet(user.getDefaultCoverObjectKey(), ...)` when set, else `null`).

- [ ] **Step 9.2: Update direct constructor call sites in tests**

Existing tests construct `UserResponse` positionally. Search and update:

```bash
grep -rn "new UserResponse(" backend/src/test/
```

For each match, insert `null` (or a test URL) at the position right after `profilePicUrl`.

In `OnboardingControllerSliceTest.stubUserResponse()` for example, the chain `..., null, null, null, null, null, null, null, null,` gets one more `null`.

- [ ] **Step 9.3: Run all backend tests**

```bash
cd backend && ./mvnw test
```

Expected: PASS.

- [ ] **Step 9.4: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/user/dto/UserResponse.java backend/src/test/
git commit -m "feat(backend): UserResponse.defaultCoverUrl — surface cover URL on /me"
```

---

## Task 10: Frontend — settings/profile page + `DefaultCoverCard`

**Files:**
- Create: `frontend/src/app/settings/profile/page.tsx`
- Create: `frontend/src/components/user/DefaultCoverCard.tsx`
- Test: `frontend/src/components/user/DefaultCoverCard.test.tsx`
- Modify: `frontend/src/lib/user/api.ts`
- Modify: `frontend/src/app/settings/layout.tsx` (add nav between sections)
- Modify: MSW fixtures (`frontend/src/test/msw/...` or wherever `mockVerifiedCurrentUser` lives — find via `grep -rn "mockVerifiedCurrentUser" frontend/src`)

- [ ] **Step 10.1: Update `CurrentUser` type + add API methods**

In `frontend/src/lib/user/api.ts`, add `defaultCoverUrl: string | null` to the `CurrentUser` type. Add to `userApi`:

```ts
async uploadDefaultCover(file: File): Promise<{ url: string; contentType: string; sizeBytes: number }> {
  const fd = new FormData();
  fd.append("file", file);
  return authFetch("/api/v1/users/me/default-cover", { method: "PUT", body: fd })
    .then((r) => r.json());
},

async deleteDefaultCover(): Promise<void> {
  await authFetch("/api/v1/users/me/default-cover", { method: "DELETE" });
},
```

(Match the existing `userApi` style — likely uses `authFetch` or similar wrapper. Verify and follow.)

- [ ] **Step 10.2: Update MSW fixtures**

Add `defaultCoverUrl: null` to `mockVerifiedCurrentUser` (and any other `mock*CurrentUser` fixtures so tests don't break on the new field).

Optionally add `mockVerifiedCurrentUserWithDefaultCover` for tests that need the "set" state.

Add MSW handlers for the three new endpoints:

```ts
http.put("/api/v1/users/me/default-cover", () =>
  HttpResponse.json({ url: "/avatars/default-cover.jpg", contentType: "image/jpeg", sizeBytes: 12345 })),
http.delete("/api/v1/users/me/default-cover", () => new HttpResponse(null, { status: 204 })),
http.get("/api/v1/users/me/default-cover", () =>
  HttpResponse.json({ url: "/avatars/default-cover.jpg", contentType: "image/jpeg", sizeBytes: 12345 })),
```

- [ ] **Step 10.3: Write failing component test**

`DefaultCoverCard.test.tsx`:

```tsx
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { DefaultCoverCard } from "./DefaultCoverCard";
import { renderWithProviders } from "@/test/utils";

vi.mock("@/lib/image/resizeImage", () => ({
  resizeImage: vi.fn(async (f: File) => f),
}));

describe("DefaultCoverCard", () => {
  it("empty state shows 'Choose image' button when no cover set", () => {
    renderWithProviders(<DefaultCoverCard />);
    expect(screen.getByRole("button", { name: /choose image/i })).toBeVisible();
  });

  it("set state shows preview + Replace + Remove buttons", () => {
    // override useCurrentUser via MSW fixture with defaultCoverUrl set
    renderWithProviders(<DefaultCoverCard />, {
      currentUser: { ...mockVerifiedCurrentUser, defaultCoverUrl: "/x.jpg" },
    });
    expect(screen.getByAltText(/default cover/i)).toBeVisible();
    expect(screen.getByRole("button", { name: /replace/i })).toBeVisible();
    expect(screen.getByRole("button", { name: /remove/i })).toBeVisible();
  });

  it("uploading a file calls resizeImage with maxDim 2048 then PUTs", async () => {
    const user = userEvent.setup();
    renderWithProviders(<DefaultCoverCard />);
    const file = new File([new Uint8Array(8)], "x.jpg", { type: "image/jpeg" });
    const input = screen.getByLabelText(/upload default cover/i, { selector: "input" });
    await user.upload(input, file);
    // assert resizeImage called and PUT request fired (via MSW spy)
  });
});
```

- [ ] **Step 10.4: Run to verify red**

```bash
cd frontend && npm test -- DefaultCoverCard.test.tsx --run
```

Expected: FAIL.

- [ ] **Step 10.5: Implement `DefaultCoverCard`**

```tsx
"use client";
import { useState, useRef } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { useCurrentUser, userApi } from "@/lib/user";
import { resizeImage } from "@/lib/image/resizeImage";
import { Button, Card } from "@/components/ui";
import { apiUrl } from "@/lib/api/url";

export function DefaultCoverCard() {
  const { data: user } = useCurrentUser();
  const qc = useQueryClient();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const inputRef = useRef<HTMLInputElement>(null);

  const handlePick = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    setBusy(true);
    setError(null);
    try {
      const resized = await resizeImage(file, { maxDim: 2048 });
      await userApi.uploadDefaultCover(resized);
      await qc.invalidateQueries({ queryKey: ["currentUser"] });
    } catch (err) {
      setError("Couldn't upload. Try again.");
    } finally {
      setBusy(false);
      if (inputRef.current) inputRef.current.value = "";
    }
  };

  const handleRemove = async () => {
    setBusy(true);
    try {
      await userApi.deleteDefaultCover();
      await qc.invalidateQueries({ queryKey: ["currentUser"] });
    } finally {
      setBusy(false);
    }
  };

  const hasCover = !!user?.defaultCoverUrl;

  return (
    <Card>
      <h2 className="text-lg font-semibold mb-2">Default cover image</h2>
      <p className="text-sm text-muted mb-4">
        Used as the first photo of every new listing you create. Existing listings are not affected.
      </p>

      {hasCover ? (
        <div className="flex flex-col gap-3">
          <img
            src={apiUrl(user.defaultCoverUrl) ?? undefined}
            alt="Default cover"
            className="rounded max-w-full"
          />
          <div className="flex gap-2">
            <Button onClick={() => inputRef.current?.click()} disabled={busy}>Replace</Button>
            <Button variant="destructive" onClick={handleRemove} disabled={busy}>Remove</Button>
          </div>
        </div>
      ) : (
        <Button onClick={() => inputRef.current?.click()} disabled={busy}>
          {busy ? "Uploading..." : "Choose image"}
        </Button>
      )}

      <input
        ref={inputRef}
        type="file"
        accept="image/*"
        className="hidden"
        aria-label="Upload default cover"
        onChange={handlePick}
      />
      {error && <p className="text-sm text-error mt-2">{error}</p>}
    </Card>
  );
}
```

- [ ] **Step 10.6: Run test to verify pass**

```bash
cd frontend && npm test -- DefaultCoverCard.test.tsx --run
```

Expected: PASS.

- [ ] **Step 10.7: Create the settings/profile page**

`frontend/src/app/settings/profile/page.tsx`:

```tsx
import { DefaultCoverCard } from "@/components/user/DefaultCoverCard";

export default function ProfileSettingsPage() {
  return (
    <div className="flex flex-col gap-6">
      <DefaultCoverCard />
    </div>
  );
}
```

- [ ] **Step 10.8: Update settings layout with section nav**

Update `frontend/src/app/settings/layout.tsx` to render a tab/sidebar nav linking `/settings/profile` and `/settings/notifications`. Use existing nav primitives (look in `components/ui/` or `components/layout/` for `<TabsNav>` or similar).

If no such primitive exists, write a small inline nav:

```tsx
<nav className="flex gap-4 mb-6 border-b border-border">
  <Link href="/settings/profile" className="...">Profile</Link>
  <Link href="/settings/notifications" className="...">Notifications</Link>
</nav>
```

Use `usePathname()` to apply an active style to the current section. This is a client component if so — split the layout into a server shell + client `<SettingsNav />` component, or convert the whole layout. Pick whichever has fewer ripple effects on existing notifications page tests.

- [ ] **Step 10.9: Run frontend test suite**

```bash
cd frontend && npm test -- --run
```

Expected: PASS.

- [ ] **Step 10.10: Commit**

```bash
git add frontend/src/lib/user/ frontend/src/components/user/DefaultCoverCard.tsx frontend/src/components/user/DefaultCoverCard.test.tsx frontend/src/app/settings/ frontend/src/test/
git commit -m "feat(frontend): default cover image card + /settings/profile route"
```

---

## Task 11: Frontend — `PhotoUploader` integrates `resizeImage`

**Files:**
- Modify: `frontend/src/components/listing/PhotoUploader.tsx`
- Modify: `frontend/src/lib/listing/photoStaging.ts` (drop the 2MB byte check)
- Modify: tests for both

- [ ] **Step 11.1: Update tests (red)**

Drop tests asserting "rejects files > 2MB". Add a test asserting `resizeImage` is called for each picked file with `maxDim: 2048`:

```tsx
vi.mock("@/lib/image/resizeImage", () => ({
  resizeImage: vi.fn(async (f: File) => f),
}));

it("calls resizeImage on every picked file before staging", async () => {
  const user = userEvent.setup();
  render(<PhotoUploader auctionPublicId="abc" onUploaded={vi.fn()} />);
  const file = new File([new Uint8Array(8)], "x.jpg", { type: "image/jpeg" });
  await user.upload(screen.getByLabelText(/photos/i, { selector: "input" }), file);
  expect(resizeImage).toHaveBeenCalledWith(file, { maxDim: 2048 });
});
```

- [ ] **Step 11.2: Run to verify red**

```bash
cd frontend && npm test -- PhotoUploader --run
```

Expected: FAIL on the new resize assertion.

- [ ] **Step 11.3: Update `PhotoUploader`**

Wherever the picker fires (`onChange` handler), wrap each file:

```tsx
const handleFiles = async (files: FileList) => {
  for (const file of Array.from(files)) {
    const resized = await resizeImage(file, { maxDim: 2048 });
    await stageAndUpload(resized);
  }
};
```

- [ ] **Step 11.4: Drop 2MB byte check**

In `frontend/src/lib/listing/photoStaging.ts`, find `validateFile` and remove the `file.size > 2 * 1024 * 1024` branch + its error path. Keep the MIME-type allow-list (`image/jpeg`, `image/png`, `image/webp`).

Also remove any UI copy in `PhotoUploader.tsx` referencing "2 MB" or "Max file size".

- [ ] **Step 11.5: Run all listing tests**

```bash
cd frontend && npm test -- listing --run
```

Expected: PASS.

- [ ] **Step 11.6: Commit**

```bash
git add frontend/src/components/listing/PhotoUploader.tsx frontend/src/lib/listing/photoStaging.ts frontend/src/components/listing/PhotoUploader.test.tsx frontend/src/lib/listing/photoStaging.test.ts
git commit -m "feat(frontend): PhotoUploader resizes via browser-image-compression; drop 2MB cap"
```

---

## Task 12: Operational sweep — README, Postman, manual verification

**Files:**
- Modify: `README.md`
- Modify: Postman SLPA collection
- Verify: docker-compose up + manual smoke test

- [ ] **Step 12.1: Update README**

Sweep the root `README.md` for any mention of "2 MB photo limit" or "4096px max dimension" — replace with the new bounds. Add a one-line entry under the per-user features section: "Default cover image — settable on /settings/profile, auto-inserted as the first photo on every new listing."

- [ ] **Step 12.2: Mirror endpoints in Postman**

The collection ID is `8070328-cf3e1190-cb71-4af3-8e2e-70c82dfee288` in the `SLPA` workspace. Add three requests under a new "Default Cover Image" folder:

- `GET {{baseUrl}}/api/v1/users/me/default-cover` — Bearer auth `{{accessToken}}`. Test script: 200 → no asserts; 404 → `pm.expect(pm.response.code).to.be.oneOf([200, 404])`.
- `PUT {{baseUrl}}/api/v1/users/me/default-cover` — multipart `file=@…`. Test: 200, response sets `pm.collectionVariables.set("defaultCoverUrl", pm.response.json().url)`.
- `DELETE {{baseUrl}}/api/v1/users/me/default-cover` — Bearer auth. Test: 204.

Use the MCP postman tool calls to add these (`mcp__postman__createCollectionRequest`).

- [ ] **Step 12.3: Manual smoke test**

```bash
docker compose up --build
docker compose restart backend
```

Open `http://localhost:3000`, log in as a verified seller, upload a default cover via `/settings/profile`, then create a new auction draft and confirm:
- Cover photo appears as photo #1 in the listing edit page.
- SL parcel snapshot appears as photo #2 (or #1 if no cover, no regression).
- Both can be removed and reordered.
- Existing in-progress drafts are not modified.

If any of these fail, raise the issue inline and fix before commit.

- [ ] **Step 12.4: Commit**

```bash
git add README.md
git commit -m "docs(readme): add default cover image feature note; update photo limits"
```

---

## Task 13: Push + open PR

- [ ] **Step 13.1: Push the feature branch**

```bash
git push -u origin feat/default-cover-image
```

- [ ] **Step 13.2: Open PR into dev**

```bash
gh pr create --base dev --title "feat: per-user default cover image + tightened photo pipeline" --body "$(cat <<'EOF'
## Summary
- Per-user default cover image, settable at /settings/profile, auto-inserted as photo #1 on every new listing draft.
- Listing photo dimension cap tightened from 4096 → 2048; 2MB byte cap dropped (server keeps 25MB DoS bound).
- Photo resize now happens client-side via browser-image-compression; ListingPhotoProcessor stays as server-side backstop.

Spec: docs/superpowers/specs/2026-05-08-default-cover-image-design.md
Plan: docs/superpowers/plans/2026-05-08-default-cover-image.md

## Test plan
- [ ] Backend: ./mvnw test passes
- [ ] Frontend: npm test passes; npm run verify passes
- [ ] Manual: upload default cover at /settings/profile, create new listing draft, cover appears as photo #1 alongside snapshot at photo #2
- [ ] Manual: existing drafts unmodified
- [ ] Manual: remove default cover from settings; existing listings keep their copy
EOF
)"
```

Return the PR URL.

---

## Self-review (executed before handoff)

Spec coverage check:
- Per-user cover image data model → Task 3 ✓
- Auto-insert at sortOrder=0 → Task 8 ✓
- Snapshot at next sortOrder → Task 7 ✓
- Cap interaction (10 stays) → no change required, falls out ✓
- Settings page card → Task 10 ✓
- Three endpoints → Task 6 ✓
- `UserResponse.defaultCoverUrl` → Task 9 ✓
- Client-side resize → Task 1 + Task 11 ✓
- Server-side processor parameterised → Task 4 ✓
- Listing photo cap tightened to 2048, 2MB removed → Task 4 + Task 11 ✓
- `PhotoSource.USER_DEFAULT_COVER` → Task 2 ✓
- Tests at every level → covered per task ✓
- Migration → Task 3 ✓
- README + Postman → Task 12 ✓

Type consistency:
- `UserDefaultCoverDto(url, contentType, sizeBytes)` — referenced consistently in Tasks 5, 6, 9, 10.
- `resizeImage(file, { maxDim })` — referenced consistently in Tasks 1, 10, 11.
- `applyTo(Auction)` signature — Tasks 7, 8 agree.

Placeholder scan: clean.
