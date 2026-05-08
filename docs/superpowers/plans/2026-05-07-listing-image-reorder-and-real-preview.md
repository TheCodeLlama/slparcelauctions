# Listing image reorder + real-page draft editor — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Sellers can drag-reorder photos in the create wizard and on a unified rich draft editor that replaces both `/listings/[id]/edit` and the small preview card on `/listings/[id]/activate`. The draft editor mirrors the buyer's listing page layout with inline click-to-edit fields, dummied populated bid history / right-rail, and a sticky top action bar carrying List Parcel + Delete Draft.

**Architecture:** Approach 1 from the spec — a new `DraftEditorClient` composes the buyer flow's leaf primitives (`AuctionHero`, `ParcelInfoPanel`, `BidHistoryList`, etc.) into the same 8/4 grid `AuctionDetailClient` uses. Each primitive gains additive optional props so absent those props it renders identically to the buyer flow. New backend endpoint `PATCH /api/v1/auctions/{publicId}/photos/order` does atomic full-list reorder.

**Tech Stack:** Spring Boot 4 / Java 26, Next.js 16 / React 19 / TanStack Query, Vitest, JUnit 5 + MockMvc, `@dnd-kit/core` + `@dnd-kit/sortable` (new dep).

**Spec:** `docs/superpowers/specs/2026-05-07-listing-image-reorder-and-real-preview-design.md` (commit `e2405b60` on dev).

**Branch:** `feat/draft-editor-photo-reorder` off `dev`. Final PR targets `dev` (not main).

---

## Setup

- [ ] **Step 0.1: Create feature branch**

```bash
git fetch origin
git checkout -b feat/draft-editor-photo-reorder origin/dev
git push -u origin feat/draft-editor-photo-reorder
```

---

## Phase 1 — Backend reorder endpoint

### Task 1: PhotoSetMismatchException + AuctionPhotoOrderRequest DTO

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/exception/PhotoSetMismatchException.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/dto/AuctionPhotoOrderRequest.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/exception/AuctionExceptionHandler.java`

- [ ] **Step 1.1: Write the exception class**

Create `PhotoSetMismatchException.java`:

```java
package com.slparcelauctions.backend.auction.exception;

import java.util.Set;
import java.util.UUID;

import lombok.Getter;

/**
 * Raised by {@code AuctionPhotoService.reorder} when the request body's
 * photo publicId set does not exactly match the auction's photo set
 * (extra UUIDs, missing UUIDs, or duplicates). Mapped to HTTP 400 with
 * {@code code=PHOTO_SET_MISMATCH}.
 */
@Getter
public class PhotoSetMismatchException extends RuntimeException {

    private final Set<UUID> expectedPublicIds;
    private final Set<UUID> actualPublicIds;

    public PhotoSetMismatchException(Set<UUID> expected, Set<UUID> actual) {
        super("Photo set mismatch: expected=" + expected + " actual=" + actual);
        this.expectedPublicIds = expected;
        this.actualPublicIds = actual;
    }
}
```

- [ ] **Step 1.2: Write the request DTO**

Create `AuctionPhotoOrderRequest.java`:

```java
package com.slparcelauctions.backend.auction.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

public record AuctionPhotoOrderRequest(
        @NotNull @NotEmpty List<@NotNull UUID> photoPublicIds) {}
```

- [ ] **Step 1.3: Wire the exception handler**

Add to `AuctionExceptionHandler.java` (after the `PhotoLimitExceededException` handler):

```java
@ExceptionHandler(PhotoSetMismatchException.class)
public ProblemDetail handlePhotoSetMismatch(
        PhotoSetMismatchException e, HttpServletRequest req) {
    ProblemDetail pd = ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST,
            "The submitted photo list does not match the auction's photos.");
    pd.setTitle("Photo Set Mismatch");
    pd.setInstance(URI.create(req.getRequestURI()));
    pd.setProperty("code", "PHOTO_SET_MISMATCH");
    return pd;
}
```

Add the import: `import com.slparcelauctions.backend.auction.exception.PhotoSetMismatchException;` (already in same package, so just the static reference works — verify no import needed since the handler is in the same package).

- [ ] **Step 1.4: Compile**

Run: `cd backend && ./mvnw -q compile`
Expected: BUILD SUCCESS.

- [ ] **Step 1.5: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auction/exception/PhotoSetMismatchException.java \
        backend/src/main/java/com/slparcelauctions/backend/auction/dto/AuctionPhotoOrderRequest.java \
        backend/src/main/java/com/slparcelauctions/backend/auction/exception/AuctionExceptionHandler.java
git commit -m "feat(auction): add PhotoSetMismatchException + AuctionPhotoOrderRequest DTO"
```

---

### Task 2: AuctionPhotoService.reorder method (TDD)

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionPhotoService.java`
- Modify: `backend/src/test/java/com/slparcelauctions/backend/auction/AuctionPhotoServiceTest.java`

- [ ] **Step 2.1: Write the failing tests**

Append to `AuctionPhotoServiceTest.java` (inside the class):

```java
@Test
void reorder_happyPath_renumbersSortOrder() {
    UUID photoA = UUID.randomUUID();
    UUID photoB = UUID.randomUUID();
    UUID photoC = UUID.randomUUID();
    AuctionPhoto pA = AuctionPhoto.builder().auction(draftAuction).objectKey("k1")
            .contentType("image/webp").sizeBytes(1L).sortOrder(1).build();
    org.springframework.test.util.ReflectionTestUtils.setField(pA, "publicId", photoA);
    AuctionPhoto pB = AuctionPhoto.builder().auction(draftAuction).objectKey("k2")
            .contentType("image/webp").sizeBytes(1L).sortOrder(2).build();
    org.springframework.test.util.ReflectionTestUtils.setField(pB, "publicId", photoB);
    AuctionPhoto pC = AuctionPhoto.builder().auction(draftAuction).objectKey("k3")
            .contentType("image/webp").sizeBytes(1L).sortOrder(3).build();
    org.springframework.test.util.ReflectionTestUtils.setField(pC, "publicId", photoC);

    when(auctionService.loadForSeller(1L, 42L)).thenReturn(draftAuction);
    when(photoRepo.findByAuctionIdOrderBySortOrderAsc(1L))
            .thenReturn(java.util.List.of(pA, pB, pC))
            .thenReturn(java.util.List.of(pC, pA, pB));

    java.util.List<AuctionPhoto> result =
            service.reorder(1L, 42L, java.util.List.of(photoC, photoA, photoB));

    // Sort orders rewritten in body order.
    assertThat(pC.getSortOrder()).isEqualTo(1);
    assertThat(pA.getSortOrder()).isEqualTo(2);
    assertThat(pB.getSortOrder()).isEqualTo(3);
    assertThat(result).extracting(AuctionPhoto::getPublicId)
            .containsExactly(photoC, photoA, photoB);
}

@Test
void reorder_rejectsExtraUuid() {
    UUID photoA = UUID.randomUUID();
    UUID photoB = UUID.randomUUID();
    UUID strayUuid = UUID.randomUUID();
    AuctionPhoto pA = AuctionPhoto.builder().auction(draftAuction).objectKey("k1")
            .contentType("image/webp").sizeBytes(1L).sortOrder(1).build();
    org.springframework.test.util.ReflectionTestUtils.setField(pA, "publicId", photoA);
    AuctionPhoto pB = AuctionPhoto.builder().auction(draftAuction).objectKey("k2")
            .contentType("image/webp").sizeBytes(1L).sortOrder(2).build();
    org.springframework.test.util.ReflectionTestUtils.setField(pB, "publicId", photoB);

    when(auctionService.loadForSeller(1L, 42L)).thenReturn(draftAuction);
    when(photoRepo.findByAuctionIdOrderBySortOrderAsc(1L))
            .thenReturn(java.util.List.of(pA, pB));

    assertThatThrownBy(() -> service.reorder(1L, 42L,
            java.util.List.of(photoA, photoB, strayUuid)))
        .isInstanceOf(com.slparcelauctions.backend.auction.exception.PhotoSetMismatchException.class);
}

@Test
void reorder_rejectsMissingUuid() {
    UUID photoA = UUID.randomUUID();
    UUID photoB = UUID.randomUUID();
    AuctionPhoto pA = AuctionPhoto.builder().auction(draftAuction).objectKey("k1")
            .contentType("image/webp").sizeBytes(1L).sortOrder(1).build();
    org.springframework.test.util.ReflectionTestUtils.setField(pA, "publicId", photoA);
    AuctionPhoto pB = AuctionPhoto.builder().auction(draftAuction).objectKey("k2")
            .contentType("image/webp").sizeBytes(1L).sortOrder(2).build();
    org.springframework.test.util.ReflectionTestUtils.setField(pB, "publicId", photoB);

    when(auctionService.loadForSeller(1L, 42L)).thenReturn(draftAuction);
    when(photoRepo.findByAuctionIdOrderBySortOrderAsc(1L))
            .thenReturn(java.util.List.of(pA, pB));

    assertThatThrownBy(() -> service.reorder(1L, 42L, java.util.List.of(photoA)))
        .isInstanceOf(com.slparcelauctions.backend.auction.exception.PhotoSetMismatchException.class);
}

@Test
void reorder_rejectsDuplicateUuid() {
    UUID photoA = UUID.randomUUID();
    UUID photoB = UUID.randomUUID();
    AuctionPhoto pA = AuctionPhoto.builder().auction(draftAuction).objectKey("k1")
            .contentType("image/webp").sizeBytes(1L).sortOrder(1).build();
    org.springframework.test.util.ReflectionTestUtils.setField(pA, "publicId", photoA);
    AuctionPhoto pB = AuctionPhoto.builder().auction(draftAuction).objectKey("k2")
            .contentType("image/webp").sizeBytes(1L).sortOrder(2).build();
    org.springframework.test.util.ReflectionTestUtils.setField(pB, "publicId", photoB);

    when(auctionService.loadForSeller(1L, 42L)).thenReturn(draftAuction);
    when(photoRepo.findByAuctionIdOrderBySortOrderAsc(1L))
            .thenReturn(java.util.List.of(pA, pB));

    assertThatThrownBy(() -> service.reorder(1L, 42L,
            java.util.List.of(photoA, photoA)))
        .isInstanceOf(com.slparcelauctions.backend.auction.exception.PhotoSetMismatchException.class);
}

@Test
void reorder_rejectsActiveAuction() {
    User seller = User.builder().id(42L).email("s@example.com").username("s").build();
    Auction active = Auction.builder()
            .title("Test").id(1L).seller(seller).status(AuctionStatus.ACTIVE).build();
    when(auctionService.loadForSeller(1L, 42L)).thenReturn(active);

    assertThatThrownBy(() -> service.reorder(1L, 42L,
            java.util.List.of(UUID.randomUUID())))
        .isInstanceOf(InvalidAuctionStateException.class);
}
```

Add the missing import at the top: `import java.util.UUID;` (verify; may already be present).

- [ ] **Step 2.2: Run the tests, verify they fail**

Run: `cd backend && ./mvnw test -Dtest=AuctionPhotoServiceTest#reorder_happyPath_renumbersSortOrder`
Expected: FAIL — `service.reorder(...)` does not exist.

- [ ] **Step 2.3: Implement the service method**

Add to `AuctionPhotoService.java`, after the existing `delete(...)` method:

```java
@Transactional
public java.util.List<AuctionPhoto> reorder(
        Long auctionId, Long sellerId, java.util.List<UUID> orderedPhotoPublicIds) {
    Auction auction = auctionService.loadForSeller(auctionId, sellerId);
    if (auction.getStatus() != AuctionStatus.DRAFT
            && auction.getStatus() != AuctionStatus.DRAFT_PAID) {
        throw new InvalidAuctionStateException(
                auctionId, auction.getStatus(), "REORDER_PHOTOS");
    }

    java.util.List<AuctionPhoto> existing =
            photoRepo.findByAuctionIdOrderBySortOrderAsc(auctionId);
    java.util.Set<UUID> expected = existing.stream()
            .map(AuctionPhoto::getPublicId)
            .collect(java.util.stream.Collectors.toSet());
    java.util.Set<UUID> bodySet = new java.util.HashSet<>(orderedPhotoPublicIds);
    if (bodySet.size() != orderedPhotoPublicIds.size()
            || !expected.equals(bodySet)) {
        throw new com.slparcelauctions.backend.auction.exception
                .PhotoSetMismatchException(expected, bodySet);
    }

    java.util.Map<UUID, AuctionPhoto> byPublicId = existing.stream()
            .collect(java.util.stream.Collectors.toMap(
                    AuctionPhoto::getPublicId, p -> p));
    for (int i = 0; i < orderedPhotoPublicIds.size(); i++) {
        AuctionPhoto p = byPublicId.get(orderedPhotoPublicIds.get(i));
        p.setSortOrder(i + 1);
    }
    photoRepo.saveAll(byPublicId.values());
    log.info("Auction {} photos reordered: {}", auctionId, orderedPhotoPublicIds);
    return photoRepo.findByAuctionIdOrderBySortOrderAsc(auctionId);
}
```

- [ ] **Step 2.4: Run the full test class**

Run: `cd backend && ./mvnw test -Dtest=AuctionPhotoServiceTest`
Expected: PASS — all reorder tests + existing upload/delete tests pass.

- [ ] **Step 2.5: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auction/AuctionPhotoService.java \
        backend/src/test/java/com/slparcelauctions/backend/auction/AuctionPhotoServiceTest.java
git commit -m "feat(auction): add AuctionPhotoService.reorder with set-equality validation"
```

---

### Task 3: AuctionPhotoController.reorder endpoint (TDD)

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionPhotoController.java`
- Modify: `backend/src/test/java/com/slparcelauctions/backend/auction/AuctionPhotoControllerIntegrationTest.java`

- [ ] **Step 3.1: Write the failing tests**

Append to the class in `AuctionPhotoControllerIntegrationTest.java` (immediately after the class's existing tests; reuse its existing helpers/fixtures pattern). Add a helper method first if not already available, then the tests:

```java
@Test
@org.springframework.security.test.context.support.WithMockUser
void reorder_happyPath_returns200WithReorderedDtoArray() throws Exception {
    // Arrange — create seller + draft auction with 3 photos.
    SetupResult setup = createDraftAuctionWithPhotos(3);
    java.util.List<UUID> reverseOrder = setup.photoPublicIds.reversed();

    String body = objectMapper.writeValueAsString(
            java.util.Map.of("photoPublicIds", reverseOrder));

    mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .patch("/api/v1/auctions/" + setup.auctionPublicId + "/photos/order")
                    .with(org.springframework.security.test.web.servlet.request
                            .SecurityMockMvcRequestPostProcessors
                            .user(setup.sellerEmail).roles("USER"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].publicId").value(reverseOrder.get(0).toString()))
            .andExpect(jsonPath("$[0].sortOrder").value(1))
            .andExpect(jsonPath("$[2].publicId").value(reverseOrder.get(2).toString()))
            .andExpect(jsonPath("$[2].sortOrder").value(3));
}

@Test
@org.springframework.security.test.context.support.WithMockUser
void reorder_setMismatch_returns400WithCode() throws Exception {
    SetupResult setup = createDraftAuctionWithPhotos(2);
    java.util.List<UUID> withStray =
            java.util.List.of(setup.photoPublicIds.get(0),
                              setup.photoPublicIds.get(1),
                              UUID.randomUUID());

    String body = objectMapper.writeValueAsString(
            java.util.Map.of("photoPublicIds", withStray));

    mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .patch("/api/v1/auctions/" + setup.auctionPublicId + "/photos/order")
                    .with(org.springframework.security.test.web.servlet.request
                            .SecurityMockMvcRequestPostProcessors
                            .user(setup.sellerEmail).roles("USER"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("PHOTO_SET_MISMATCH"));
}

// ... see existing test class for the exact SetupResult/createDraftAuctionWithPhotos
// pattern. Reuse the existing helpers; if there is no existing photo-creation
// helper, add one near the top of the class:

private record SetupResult(UUID auctionPublicId, java.util.List<UUID> photoPublicIds, String sellerEmail) {}

private SetupResult createDraftAuctionWithPhotos(int n) throws Exception {
    User seller = User.builder().email("seller-" + UUID.randomUUID() + "@example.com")
            .username("seller-" + UUID.randomUUID().toString().substring(0, 8))
            .build();
    seller = userRepository.save(seller);

    Auction auction = Auction.builder()
            .title("Test")
            .seller(seller)
            .status(AuctionStatus.DRAFT)
            .startingBid(BigDecimal.ONE)
            .durationHours(24)
            .build();
    auction = auctionRepository.save(auction);
    createdAuctionIds.add(auction.getId());

    java.util.List<UUID> publicIds = new ArrayList<>();
    for (int i = 1; i <= n; i++) {
        AuctionPhoto p = AuctionPhoto.builder()
                .auction(auction)
                .objectKey("listings/" + auction.getId() + "/" + UUID.randomUUID() + ".webp")
                .contentType("image/webp")
                .sizeBytes(123L)
                .sortOrder(i)
                .build();
        p = photoRepository.save(p);
        publicIds.add(p.getPublicId());
    }
    return new SetupResult(auction.getPublicId(), publicIds, seller.getEmail());
}
```

If the existing test file already has matching helpers (look near top of class), reuse those instead.

- [ ] **Step 3.2: Run the tests, verify they fail**

Run: `cd backend && ./mvnw test -Dtest=AuctionPhotoControllerIntegrationTest#reorder_happyPath_returns200WithReorderedDtoArray`
Expected: FAIL — endpoint doesn't exist (404).

- [ ] **Step 3.3: Implement the controller endpoint**

Add to `AuctionPhotoController.java`, after the existing `delete(...)` method:

```java
@org.springframework.web.bind.annotation.PatchMapping(
        path = "/order",
        consumes = MediaType.APPLICATION_JSON_VALUE)
public java.util.List<AuctionPhotoResponse> reorder(
        @PathVariable UUID auctionPublicId,
        @org.springframework.web.bind.annotation.RequestBody
        @jakarta.validation.Valid
        com.slparcelauctions.backend.auction.dto.AuctionPhotoOrderRequest body,
        @AuthenticationPrincipal AuthPrincipal principal) {
    Long auctionId = resolveAuctionId(auctionPublicId);
    java.util.List<AuctionPhoto> reordered = service.reorder(
            auctionId, principal.userId(), body.photoPublicIds());
    return reordered.stream().map(AuctionPhotoResponse::from).toList();
}
```

- [ ] **Step 3.4: Run the full test class**

Run: `cd backend && ./mvnw test -Dtest=AuctionPhotoControllerIntegrationTest`
Expected: PASS — new reorder tests pass + existing upload/delete tests still pass.

- [ ] **Step 3.5: Smoke-run the whole backend test suite**

Run: `cd backend && ./mvnw -q test`
Expected: BUILD SUCCESS.

- [ ] **Step 3.6: Commit**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auction/AuctionPhotoController.java \
        backend/src/test/java/com/slparcelauctions/backend/auction/AuctionPhotoControllerIntegrationTest.java
git commit -m "feat(auction): add PATCH /api/v1/auctions/{publicId}/photos/order endpoint"
```

---

## Phase 2 — Frontend deps + API helper

### Task 4: Install @dnd-kit deps

**Files:**
- Modify: `frontend/package.json`
- Modify: `frontend/package-lock.json`

- [ ] **Step 4.1: Install**

Run: `cd frontend && npm install @dnd-kit/core@^6.3.1 @dnd-kit/sortable@^8.0.0`

- [ ] **Step 4.2: Verify install**

Check `frontend/package.json` `dependencies` includes `@dnd-kit/core` and `@dnd-kit/sortable`.

- [ ] **Step 4.3: Commit**

```bash
git add frontend/package.json frontend/package-lock.json
git commit -m "chore(frontend): add @dnd-kit/core + @dnd-kit/sortable for drag-reorder"
```

---

### Task 5: auctionPhotos.reorder API helper (TDD)

**Files:**
- Modify: `frontend/src/lib/api/auctionPhotos.ts`
- Create: `frontend/src/lib/api/auctionPhotos.test.ts`

- [ ] **Step 5.1: Write the failing test**

Create `frontend/src/lib/api/auctionPhotos.test.ts`:

```typescript
import { describe, expect, it, beforeAll, afterAll, afterEach } from "vitest";
import { setupServer } from "msw/node";
import { http, HttpResponse } from "msw";
import { reorderPhotos } from "./auctionPhotos";

const server = setupServer();
beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

describe("reorderPhotos", () => {
  it("PATCHes /api/v1/auctions/{id}/photos/order and returns the reordered DTO array", async () => {
    server.use(
      http.patch("*/api/v1/auctions/abc-123/photos/order", async ({ request }) => {
        const body = (await request.json()) as { photoPublicIds: string[] };
        expect(body.photoPublicIds).toEqual(["p2", "p1"]);
        return HttpResponse.json([
          { publicId: "p2", url: "/api/v1/photos/p2", contentType: "image/webp",
            sizeBytes: 100, sortOrder: 1, uploadedAt: "2026-05-07T00:00:00Z" },
          { publicId: "p1", url: "/api/v1/photos/p1", contentType: "image/webp",
            sizeBytes: 100, sortOrder: 2, uploadedAt: "2026-05-07T00:00:00Z" },
        ]);
      }),
    );
    const result = await reorderPhotos("abc-123", ["p2", "p1"]);
    expect(result).toHaveLength(2);
    expect(result[0].publicId).toBe("p2");
    expect(result[0].sortOrder).toBe(1);
  });
});
```

- [ ] **Step 5.2: Run, verify failure**

Run: `cd frontend && npm test -- auctionPhotos.test.ts`
Expected: FAIL — `reorderPhotos` not exported.

- [ ] **Step 5.3: Add the helper**

Append to `frontend/src/lib/api/auctionPhotos.ts`:

```typescript
/**
 * PATCH /api/v1/auctions/{auctionPublicId}/photos/order — atomic full-list
 * reorder. Body's photoPublicIds set must equal the auction's current
 * photo set; mismatch returns 400 PHOTO_SET_MISMATCH.
 */
export function reorderPhotos(
  auctionPublicId: string,
  photoPublicIds: string[],
): Promise<AuctionPhotoDto[]> {
  return api.patch<AuctionPhotoDto[]>(
    `/api/v1/auctions/${auctionPublicId}/photos/order`,
    { photoPublicIds },
  );
}
```

If `api.patch` doesn't exist on the shared client, check `frontend/src/lib/api/index.ts`. If only `get/post/delete/put` exist, add `patch` symmetrically (one-line addition to the helper).

- [ ] **Step 5.4: Run, verify pass**

Run: `cd frontend && npm test -- auctionPhotos.test.ts`
Expected: PASS.

- [ ] **Step 5.5: Commit**

```bash
git add frontend/src/lib/api/auctionPhotos.ts frontend/src/lib/api/auctionPhotos.test.ts \
        frontend/src/lib/api/index.ts
git commit -m "feat(api): add reorderPhotos helper for PATCH /photos/order"
```

---

### Task 6: useReorderAuctionPhotos hook (TDD)

**Files:**
- Create: `frontend/src/hooks/useReorderAuctionPhotos.ts`
- Create: `frontend/src/hooks/useReorderAuctionPhotos.test.tsx`

- [ ] **Step 6.1: Write the failing test**

Create `useReorderAuctionPhotos.test.tsx`:

```typescript
import { describe, expect, it, beforeAll, afterAll, afterEach } from "vitest";
import { setupServer } from "msw/node";
import { http, HttpResponse } from "msw";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useReorderAuctionPhotos } from "./useReorderAuctionPhotos";

const server = setupServer();
beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function wrapper(qc: QueryClient) {
  return ({ children }: { children: React.ReactNode }) => (
    <QueryClientProvider client={qc}>{children}</QueryClientProvider>
  );
}

describe("useReorderAuctionPhotos", () => {
  it("calls PATCH /photos/order and resolves with the new DTO array", async () => {
    server.use(
      http.patch("*/api/v1/auctions/abc/photos/order", async () =>
        HttpResponse.json([
          { publicId: "p2", url: "/api/v1/photos/p2", contentType: "image/webp",
            sizeBytes: 1, sortOrder: 1, uploadedAt: "x" },
        ])),
    );
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const { result } = renderHook(() => useReorderAuctionPhotos("abc"), {
      wrapper: wrapper(qc),
    });
    await result.current.mutateAsync(["p2"]);
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});
```

- [ ] **Step 6.2: Run, verify failure**

Run: `cd frontend && npm test -- useReorderAuctionPhotos`
Expected: FAIL.

- [ ] **Step 6.3: Implement**

Create `frontend/src/hooks/useReorderAuctionPhotos.ts`:

```typescript
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { reorderPhotos } from "@/lib/api/auctionPhotos";
import { auctionKey } from "@/hooks/useAuction";
import type { AuctionPhotoDto } from "@/types/auction";

/**
 * Mutation wrapper for PATCH /api/v1/auctions/{auctionPublicId}/photos/order.
 *
 * Optimistic update strategy: callers pass the new ordered photo array via
 * the mutation variables; on settle the auction cache is invalidated so the
 * server response is the canonical source. Optimistic cache writes happen
 * in the calling component (EditablePhotoGallery) where the auction shape
 * is already in scope, not here.
 */
export function useReorderAuctionPhotos(auctionPublicId: string) {
  const qc = useQueryClient();
  return useMutation<AuctionPhotoDto[], unknown, string[]>({
    mutationFn: (orderedPublicIds: string[]) =>
      reorderPhotos(auctionPublicId, orderedPublicIds),
    onSettled: () => {
      qc.invalidateQueries({ queryKey: auctionKey(auctionPublicId) });
    },
  });
}
```

- [ ] **Step 6.4: Run, verify pass**

Run: `cd frontend && npm test -- useReorderAuctionPhotos`
Expected: PASS.

- [ ] **Step 6.5: Commit**

```bash
git add frontend/src/hooks/useReorderAuctionPhotos.ts \
        frontend/src/hooks/useReorderAuctionPhotos.test.tsx
git commit -m "feat(hook): add useReorderAuctionPhotos mutation wrapper"
```

---

## Phase 3 — Modify leaf primitives (additive props)

### Task 7: AuctionHero — add onReorder/onDelete/onAdd props

**Files:**
- Modify: `frontend/src/components/auction/AuctionHero.tsx`
- Modify (or create alongside): `frontend/src/components/auction/AuctionHero.test.tsx`

- [ ] **Step 7.1: Read the current AuctionHero**

Run: read `frontend/src/components/auction/AuctionHero.tsx` to understand its current shape. Pin the current props interface.

- [ ] **Step 7.2: Write failing test for onDelete callback**

Add a test (or new file) asserting that when an `onDelete` callback is passed, each thumbnail gets a delete X button that calls `onDelete(photoPublicId)`:

```typescript
it("renders delete X on each thumbnail when onDelete is provided", async () => {
  const onDelete = vi.fn();
  renderWithProviders(
    <AuctionHero
      photos={[
        { publicId: "p1", url: "/api/v1/photos/p1", contentType: "image/webp",
          sizeBytes: 1, sortOrder: 1, uploadedAt: "x" },
      ]}
      snapshotUrl={null}
      regionName="X"
      onDelete={onDelete}
    />,
  );
  const btn = await screen.findByLabelText("Remove photo p1");
  await userEvent.click(btn);
  expect(onDelete).toHaveBeenCalledWith("p1");
});

it("does not render delete X when onDelete is omitted", () => {
  renderWithProviders(
    <AuctionHero
      photos={[
        { publicId: "p1", url: "/api/v1/photos/p1", contentType: "image/webp",
          sizeBytes: 1, sortOrder: 1, uploadedAt: "x" },
      ]}
      snapshotUrl={null}
      regionName="X"
    />,
  );
  expect(screen.queryByLabelText(/Remove photo/)).toBeNull();
});
```

- [ ] **Step 7.3: Run, verify failure**

Run: `cd frontend && npm test -- AuctionHero`
Expected: FAIL.

- [ ] **Step 7.4: Implement onDelete prop**

Modify `AuctionHero.tsx`:
- Add to props: `onDelete?: (photoPublicId: string) => void`, `onAdd?: () => void` (the file picker click — actual file picking lives in the wrapper), `onReorder?: (orderedPublicIds: string[]) => void`.
- When `onDelete` is set: each thumbnail in the strip gets an absolute-positioned X button (`Trash2` icon) with `aria-label={\`Remove photo ${publicId}\`}`. On click → `onDelete(publicId)`.
- When `onAdd` is set and photos.length < 10: append an "Add" tile at the end of the strip with `aria-label="Add photo"` calling `onAdd()`.
- `onReorder` wiring is added in Task 13 (EditablePhotoGallery wraps and adds DnD context); for now keep the prop on the type but optional.

Do not change the rendered output when none of these props are passed — the existing buyer flow must be byte-identical.

- [ ] **Step 7.5: Run, verify pass**

Run: `cd frontend && npm test -- AuctionHero`
Expected: PASS — new tests pass + any existing AuctionHero tests still pass.

- [ ] **Step 7.6: Commit**

```bash
git add frontend/src/components/auction/AuctionHero.tsx \
        frontend/src/components/auction/AuctionHero.test.tsx
git commit -m "feat(auction-hero): add optional onDelete/onAdd props for editor mode"
```

---

### Task 8: BidHistoryList — add sampleEntries prop

**Files:**
- Modify: `frontend/src/components/auction/BidHistoryList.tsx`
- Modify: `frontend/src/components/auction/BidHistoryList.test.tsx`

- [ ] **Step 8.1: Write failing test**

```typescript
it("renders sampleEntries with a Sample pill when provided, suppressing the live query", async () => {
  const sample: BidHistoryEntry[] = [
    { bidPublicId: "s1", bidderPublicId: "b1", bidderDisplayName: "Sample Bidder 1",
      amount: 100, placedAt: "2026-05-07T00:00:00Z", snipeExtensionMinutes: null },
  ];
  renderWithProviders(
    <BidHistoryList auctionPublicId="abc" sampleEntries={sample} />,
  );
  expect(screen.getByText("Sample Bidder 1")).toBeInTheDocument();
  expect(screen.getByText(/Sample/i)).toBeInTheDocument();
});
```

- [ ] **Step 8.2: Run, verify failure**

Run: `cd frontend && npm test -- BidHistoryList`
Expected: FAIL.

- [ ] **Step 8.3: Implement**

Modify `BidHistoryList.tsx`:
- Add prop: `sampleEntries?: BidHistoryEntry[]`.
- When `sampleEntries` is set: render those entries directly, skip the `useBidHistory` query (or pass `enabled: false`), and render a small `Sample` pill in the section header next to the title (use existing badge styling — match the style of the Sample pill we'll add to BidPanelPreview).

- [ ] **Step 8.4: Run, verify pass**

Run: `cd frontend && npm test -- BidHistoryList`
Expected: PASS.

- [ ] **Step 8.5: Commit**

```bash
git add frontend/src/components/auction/BidHistoryList.tsx \
        frontend/src/components/auction/BidHistoryList.test.tsx
git commit -m "feat(bid-history): add optional sampleEntries prop for preview mode"
```

---

## Phase 4 — Inline edit primitives

### Task 9: useInlineEdit hook (TDD)

**Files:**
- Create: `frontend/src/hooks/useInlineEdit.ts`
- Create: `frontend/src/hooks/useInlineEdit.test.ts`

- [ ] **Step 9.1: Write failing tests**

Create `useInlineEdit.test.ts`:

```typescript
import { describe, expect, it, vi } from "vitest";
import { act, renderHook } from "@testing-library/react";
import { useInlineEdit } from "./useInlineEdit";

describe("useInlineEdit", () => {
  it("starts in idle state, transitions to editing on startEdit", () => {
    const { result } = renderHook(() => useInlineEdit({
      initialValue: "hello",
      onSave: vi.fn(),
    }));
    expect(result.current.state).toBe("idle");
    act(() => result.current.startEdit());
    expect(result.current.state).toBe("editing");
    expect(result.current.draft).toBe("hello");
  });

  it("transitions editing -> saving -> idle on successful save", async () => {
    const onSave = vi.fn().mockResolvedValue(undefined);
    const { result } = renderHook(() => useInlineEdit({
      initialValue: "hello",
      onSave,
    }));
    act(() => result.current.startEdit());
    act(() => result.current.setDraft("hello world"));
    await act(async () => {
      await result.current.commit();
    });
    expect(onSave).toHaveBeenCalledWith("hello world");
    expect(result.current.state).toBe("idle");
    expect(result.current.error).toBeNull();
  });

  it("transitions to error state on save failure, keeps editor open", async () => {
    const onSave = vi.fn().mockRejectedValue(new Error("boom"));
    const { result } = renderHook(() => useInlineEdit({
      initialValue: "hello",
      onSave,
    }));
    act(() => result.current.startEdit());
    act(() => result.current.setDraft("oops"));
    await act(async () => {
      try { await result.current.commit(); } catch {}
    });
    expect(result.current.state).toBe("editing");
    expect(result.current.error).toBe("boom");
    expect(result.current.draft).toBe("oops");
  });

  it("cancel returns to idle and discards draft", () => {
    const { result } = renderHook(() => useInlineEdit({
      initialValue: "hello",
      onSave: vi.fn(),
    }));
    act(() => result.current.startEdit());
    act(() => result.current.setDraft("garbage"));
    act(() => result.current.cancel());
    expect(result.current.state).toBe("idle");
  });

  it("commit is a no-op when draft equals initialValue", async () => {
    const onSave = vi.fn();
    const { result } = renderHook(() => useInlineEdit({
      initialValue: "hello",
      onSave,
    }));
    act(() => result.current.startEdit());
    await act(async () => {
      await result.current.commit();
    });
    expect(onSave).not.toHaveBeenCalled();
    expect(result.current.state).toBe("idle");
  });
});
```

- [ ] **Step 9.2: Run, verify failure**

Run: `cd frontend && npm test -- useInlineEdit`
Expected: FAIL.

- [ ] **Step 9.3: Implement**

Create `frontend/src/hooks/useInlineEdit.ts`:

```typescript
"use client";
import { useState, useCallback } from "react";

export type InlineEditState = "idle" | "editing" | "saving";

export interface UseInlineEditArgs<T> {
  initialValue: T;
  onSave: (value: T) => Promise<void>;
  /** Optional equality check; defaults to Object.is. */
  isEqual?: (a: T, b: T) => boolean;
}

export interface UseInlineEditReturn<T> {
  state: InlineEditState;
  draft: T;
  error: string | null;
  startEdit: () => void;
  setDraft: (next: T) => void;
  commit: () => Promise<void>;
  cancel: () => void;
}

/**
 * Tiny state machine for click-to-edit fields.
 *
 * idle -> startEdit -> editing -> commit -> saving -> idle (success)
 *                                        -> editing (failure, error set)
 *                  -> cancel -> idle (draft discarded)
 *
 * commit() is a no-op when draft equals initialValue (saves a round-trip
 * when the seller opens an editor and clicks away without changing the
 * value).
 */
export function useInlineEdit<T>(args: UseInlineEditArgs<T>): UseInlineEditReturn<T> {
  const isEqual = args.isEqual ?? ((a, b) => Object.is(a, b));
  const [state, setState] = useState<InlineEditState>("idle");
  const [draft, setDraftState] = useState<T>(args.initialValue);
  const [error, setError] = useState<string | null>(null);

  const startEdit = useCallback(() => {
    setDraftState(args.initialValue);
    setError(null);
    setState("editing");
  }, [args.initialValue]);

  const setDraft = useCallback((next: T) => {
    setDraftState(next);
  }, []);

  const cancel = useCallback(() => {
    setError(null);
    setState("idle");
  }, []);

  const commit = useCallback(async () => {
    if (state !== "editing") return;
    if (isEqual(draft, args.initialValue)) {
      setState("idle");
      return;
    }
    setState("saving");
    try {
      await args.onSave(draft);
      setState("idle");
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Save failed");
      setState("editing");
      throw e;
    }
  }, [state, draft, args, isEqual]);

  return { state, draft, error, startEdit, setDraft, commit, cancel };
}
```

- [ ] **Step 9.4: Run, verify pass**

Run: `cd frontend && npm test -- useInlineEdit`
Expected: PASS.

- [ ] **Step 9.5: Commit**

```bash
git add frontend/src/hooks/useInlineEdit.ts frontend/src/hooks/useInlineEdit.test.ts
git commit -m "feat(hook): add useInlineEdit state machine for click-to-edit fields"
```

---

### Task 10: draftEditorMutations hook bundle (TDD)

**Files:**
- Create: `frontend/src/components/listing/draft-editor/draftEditorMutations.ts`
- Create: `frontend/src/components/listing/draft-editor/draftEditorMutations.test.tsx`

This task creates one file exporting per-field mutations against the existing `PATCH /api/v1/auctions/{publicId}` endpoint. Each returns a function `(value) => Promise<void>` ready for `useInlineEdit`'s `onSave`.

- [ ] **Step 10.1: Verify the existing patch endpoint**

Run: `Grep` for `auctions.update|patchAuction|updateAuction` in `frontend/src/lib/api`. Pin the existing helper name + its body shape. The wizard's "Save as Draft" already calls this — find the call site.

- [ ] **Step 10.2: Write the failing test**

```typescript
import { describe, expect, it, beforeAll, afterAll, afterEach } from "vitest";
import { setupServer } from "msw/node";
import { http, HttpResponse } from "msw";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useDraftEditorMutations } from "./draftEditorMutations";

const server = setupServer();
beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function wrapper(qc: QueryClient) {
  return ({ children }: { children: React.ReactNode }) => (
    <QueryClientProvider client={qc}>{children}</QueryClientProvider>
  );
}

describe("useDraftEditorMutations", () => {
  it("saveTitle PATCHes the auction with { title }", async () => {
    let received: unknown = null;
    server.use(
      http.patch("*/api/v1/auctions/abc", async ({ request }) => {
        received = await request.json();
        return HttpResponse.json({ publicId: "abc", title: "new" });
      }),
    );
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const { result } = renderHook(() => useDraftEditorMutations("abc"), {
      wrapper: wrapper(qc),
    });
    await result.current.saveTitle("new");
    await waitFor(() => expect(received).toEqual({ title: "new" }));
  });
});
```

- [ ] **Step 10.3: Run, verify failure**

Run: `cd frontend && npm test -- draftEditorMutations`
Expected: FAIL.

- [ ] **Step 10.4: Implement**

Create `draftEditorMutations.ts`:

```typescript
"use client";
import { useQueryClient } from "@tanstack/react-query";
import { auctionKey } from "@/hooks/useAuction";
import type { ParcelTagCode, SellerAuctionResponse } from "@/types/auction";
import { api } from "@/lib/api";

interface AuctionPatchBody {
  title?: string;
  sellerDesc?: string;
  tags?: ParcelTagCode[];
  startingBid?: number;
  reservePrice?: number | null;
  buyNowPrice?: number | null;
  durationHours?: number;
  parcelLookupKey?: string;
}

/**
 * Per-field save helpers backed by the existing
 * {@code PATCH /api/v1/auctions/{publicId}} endpoint. Each helper accepts
 * the new value, fires the patch, replaces the auction cache with the
 * response, and resolves. Errors propagate so {@link useInlineEdit} can
 * route them into its error slot.
 */
export function useDraftEditorMutations(auctionPublicId: string) {
  const qc = useQueryClient();
  const send = async (body: AuctionPatchBody) => {
    const updated = await api.patch<SellerAuctionResponse>(
      `/api/v1/auctions/${auctionPublicId}`,
      body,
    );
    qc.setQueryData(auctionKey(auctionPublicId), updated);
  };

  return {
    saveTitle: (title: string) => send({ title }),
    saveDescription: (sellerDesc: string) => send({ sellerDesc }),
    saveTags: (tags: ParcelTagCode[]) => send({ tags }),
    saveSettings: (s: {
      startingBid: number;
      reservePrice: number | null;
      buyNowPrice: number | null;
      durationHours: number;
    }) => send(s),
    saveParcel: (parcelLookupKey: string) => send({ parcelLookupKey }),
  };
}
```

The body shape (`parcelLookupKey`, `tags: ParcelTagCode[]`) must match what the wizard's existing draft-save call sends — verify by reading `useListingDraft.ts` and align field names. If the codebase uses different names (e.g., `tagCodes`), use those.

- [ ] **Step 10.5: Run, verify pass**

Run: `cd frontend && npm test -- draftEditorMutations`
Expected: PASS.

- [ ] **Step 10.6: Commit**

```bash
git add frontend/src/components/listing/draft-editor/draftEditorMutations.ts \
        frontend/src/components/listing/draft-editor/draftEditorMutations.test.tsx
git commit -m "feat(draft-editor): add per-field save mutations (title/desc/tags/settings/parcel)"
```

---

### Task 11: EditableTitle (TDD)

**Files:**
- Create: `frontend/src/components/listing/draft-editor/EditableTitle.tsx`
- Create: `frontend/src/components/listing/draft-editor/EditableTitle.test.tsx`

- [ ] **Step 11.1: Failing test**

```typescript
import { describe, expect, it, vi } from "vitest";
import { renderWithProviders, screen, userEvent } from "@/test/render";
import { EditableTitle } from "./EditableTitle";

describe("EditableTitle", () => {
  it("clicks to edit, saves on blur, calls onSave with the new value", async () => {
    const onSave = vi.fn().mockResolvedValue(undefined);
    renderWithProviders(<EditableTitle value="Old" onSave={onSave} />);
    await userEvent.click(screen.getByText("Old"));
    const input = screen.getByRole("textbox");
    await userEvent.clear(input);
    await userEvent.type(input, "New title");
    input.blur();
    await screen.findByText("New title");
    expect(onSave).toHaveBeenCalledWith("New title");
  });

  it("Esc cancels and discards changes", async () => {
    const onSave = vi.fn();
    renderWithProviders(<EditableTitle value="Old" onSave={onSave} />);
    await userEvent.click(screen.getByText("Old"));
    const input = screen.getByRole("textbox");
    await userEvent.clear(input);
    await userEvent.type(input, "Garbage{Escape}");
    expect(screen.getByText("Old")).toBeInTheDocument();
    expect(onSave).not.toHaveBeenCalled();
  });

  it("Enter saves", async () => {
    const onSave = vi.fn().mockResolvedValue(undefined);
    renderWithProviders(<EditableTitle value="Old" onSave={onSave} />);
    await userEvent.click(screen.getByText("Old"));
    await userEvent.type(screen.getByRole("textbox"), "{Enter}");
    expect(onSave).toHaveBeenCalledWith("Old");
  });

  it("renders inline error on save failure, keeps editor open", async () => {
    const onSave = vi.fn().mockRejectedValue(new Error("Title too long"));
    renderWithProviders(<EditableTitle value="Old" onSave={onSave} />);
    await userEvent.click(screen.getByText("Old"));
    const input = screen.getByRole("textbox");
    await userEvent.clear(input);
    await userEvent.type(input, "x");
    input.blur();
    expect(await screen.findByText(/Title too long/)).toBeInTheDocument();
    expect(screen.getByRole("textbox")).toBeInTheDocument();
  });
});
```

- [ ] **Step 11.2: Run, verify failure**

Run: `cd frontend && npm test -- EditableTitle`
Expected: FAIL.

- [ ] **Step 11.3: Implement**

```typescript
"use client";
import { useRef, useEffect } from "react";
import { useInlineEdit } from "@/hooks/useInlineEdit";
import { FormError } from "@/components/ui/FormError";

export interface EditableTitleProps {
  value: string;
  onSave: (next: string) => Promise<void>;
  className?: string;
}

export function EditableTitle({ value, onSave, className }: EditableTitleProps) {
  const edit = useInlineEdit<string>({ initialValue: value, onSave });
  const inputRef = useRef<HTMLInputElement | null>(null);

  useEffect(() => {
    if (edit.state === "editing") inputRef.current?.focus();
  }, [edit.state]);

  if (edit.state === "idle") {
    return (
      <button
        type="button"
        onClick={edit.startEdit}
        className={className ?? "text-2xl font-bold tracking-tight text-fg text-left hover:opacity-80"}
        data-testid="editable-title"
      >
        {value || "(unnamed parcel)"}
      </button>
    );
  }

  return (
    <div className="flex flex-col gap-1">
      <input
        ref={inputRef}
        type="text"
        value={edit.draft}
        onChange={(e) => edit.setDraft(e.target.value)}
        onBlur={() => { edit.commit().catch(() => {}); }}
        onKeyDown={(e) => {
          if (e.key === "Enter") {
            e.preventDefault();
            edit.commit().catch(() => {});
          } else if (e.key === "Escape") {
            edit.cancel();
          }
        }}
        disabled={edit.state === "saving"}
        maxLength={120}
        className="text-2xl font-bold tracking-tight text-fg bg-bg-subtle ring-1 ring-border-subtle rounded-lg px-3 py-2 focus:outline-none focus-visible:ring-2 focus-visible:ring-brand"
      />
      <FormError message={edit.error ?? undefined} />
    </div>
  );
}
```

- [ ] **Step 11.4: Run, verify pass**

Run: `cd frontend && npm test -- EditableTitle`
Expected: PASS.

- [ ] **Step 11.5: Commit**

```bash
git add frontend/src/components/listing/draft-editor/EditableTitle.tsx \
        frontend/src/components/listing/draft-editor/EditableTitle.test.tsx
git commit -m "feat(draft-editor): add EditableTitle inline-edit field"
```

---

### Task 12: EditableDescription (TDD)

**Files:**
- Create: `frontend/src/components/listing/draft-editor/EditableDescription.tsx`
- Create: `frontend/src/components/listing/draft-editor/EditableDescription.test.tsx`

- [ ] **Step 12.1: Failing test**

Mirror `EditableTitle.test.tsx` but for a `<textarea>`. Cases:
- click to edit, type, blur → onSave called
- Esc → cancel
- Enter inserts newline (NOT save) — assert `onSave` not called and the draft contains a `\n`
- error renders inline

- [ ] **Step 12.2: Run, verify failure**

Run: `cd frontend && npm test -- EditableDescription`
Expected: FAIL.

- [ ] **Step 12.3: Implement**

Mirror `EditableTitle` but use `<textarea>` with `rows={6}` (or auto-resize), `maxLength={5000}`, and **only** save on blur (not on Enter). Esc still cancels. Same `useInlineEdit` plumbing.

```typescript
"use client";
import { useRef, useEffect } from "react";
import { useInlineEdit } from "@/hooks/useInlineEdit";
import { FormError } from "@/components/ui/FormError";

export interface EditableDescriptionProps {
  value: string;
  onSave: (next: string) => Promise<void>;
}

export function EditableDescription({ value, onSave }: EditableDescriptionProps) {
  const edit = useInlineEdit<string>({ initialValue: value, onSave });
  const ref = useRef<HTMLTextAreaElement | null>(null);

  useEffect(() => {
    if (edit.state === "editing") ref.current?.focus();
  }, [edit.state]);

  if (edit.state === "idle") {
    return (
      <button
        type="button"
        onClick={edit.startEdit}
        className="whitespace-pre-wrap text-sm text-fg text-left w-full hover:opacity-80"
        data-testid="editable-description"
      >
        {value || "Click to add a description"}
      </button>
    );
  }

  return (
    <div className="flex flex-col gap-1 w-full">
      <textarea
        ref={ref}
        rows={6}
        value={edit.draft}
        onChange={(e) => edit.setDraft(e.target.value)}
        onBlur={() => { edit.commit().catch(() => {}); }}
        onKeyDown={(e) => {
          if (e.key === "Escape") edit.cancel();
        }}
        disabled={edit.state === "saving"}
        maxLength={5000}
        className="w-full resize-y rounded-lg bg-bg-subtle px-4 py-3 text-fg ring-1 ring-border-subtle focus:outline-none focus-visible:ring-2 focus-visible:ring-brand"
      />
      <FormError message={edit.error ?? undefined} />
    </div>
  );
}
```

- [ ] **Step 12.4: Run, verify pass + commit**

Run: `cd frontend && npm test -- EditableDescription`
Expected: PASS.

```bash
git add frontend/src/components/listing/draft-editor/EditableDescription.tsx \
        frontend/src/components/listing/draft-editor/EditableDescription.test.tsx
git commit -m "feat(draft-editor): add EditableDescription inline-edit textarea"
```

---

### Task 13: EditableTags (TDD)

**Files:**
- Create: `frontend/src/components/listing/draft-editor/EditableTags.tsx`
- Create: `frontend/src/components/listing/draft-editor/EditableTags.test.tsx`

- [ ] **Step 13.1: Read existing `TagSelector`**

Run: read `frontend/src/components/listing/TagSelector.tsx` for the prop shape (selected/onChange + the tag catalog source).

- [ ] **Step 13.2: Failing test**

```typescript
it("opens a popover with TagSelector and saves on Done", async () => {
  const onSave = vi.fn().mockResolvedValue(undefined);
  renderWithProviders(
    <EditableTags
      value={[{ code: "WATERFRONT", label: "Waterfront" }]}
      onSave={onSave}
    />,
  );
  await userEvent.click(screen.getByTestId("editable-tags-trigger"));
  // pick a tag (TagSelector renders checkboxes/pills) ...
  await userEvent.click(screen.getByText("Done"));
  expect(onSave).toHaveBeenCalled();
});
```

- [ ] **Step 13.3: Run, verify failure**

Run: `cd frontend && npm test -- EditableTags`

- [ ] **Step 13.4: Implement**

Use a `Popover` (`@headlessui/react`) wrapping `TagSelector`. Idle state: render the existing tag chips (matching `ListingPreviewCard`'s tag styling) + a small `+ Add` chip → opens popover. Popover contains `TagSelector` + a Done button. Done → `commit()`. Click outside → `commit()` (or `cancel()` if no changes). Esc → `cancel()`.

The exact code follows the same shape as `EditableTitle`/`EditableDescription` but with a Popover/Dialog frame.

- [ ] **Step 13.5: Run, verify pass + commit**

```bash
git add frontend/src/components/listing/draft-editor/EditableTags.tsx \
        frontend/src/components/listing/draft-editor/EditableTags.test.tsx
git commit -m "feat(draft-editor): add EditableTags popover wrapper"
```

---

### Task 14: EditableSettingsModal (TDD)

**Files:**
- Create: `frontend/src/components/listing/draft-editor/EditableSettingsModal.tsx`
- Create: `frontend/src/components/listing/draft-editor/EditableSettingsModal.test.tsx`

- [ ] **Step 14.1: Read existing `AuctionSettingsForm`**

Run: read `frontend/src/components/listing/AuctionSettingsForm.tsx` for its props + value shape. The modal will render this form inside a `<Dialog>`.

- [ ] **Step 14.2: Failing test**

```typescript
it("opens the modal, edits all four fields, saves the group together", async () => {
  const onSave = vi.fn().mockResolvedValue(undefined);
  renderWithProviders(
    <EditableSettingsModal
      value={{ startingBid: 100, reservePrice: null, buyNowPrice: null, durationHours: 24 }}
      onSave={onSave}
    />,
  );
  await userEvent.click(screen.getByTestId("editable-settings-trigger"));
  // change starting bid in the modal form ...
  await userEvent.click(screen.getByRole("button", { name: /save/i }));
  expect(onSave).toHaveBeenCalledWith(expect.objectContaining({
    startingBid: expect.any(Number),
  }));
});
```

- [ ] **Step 14.3: Run, verify failure**

Run: `cd frontend && npm test -- EditableSettingsModal`

- [ ] **Step 14.4: Implement**

Component renders a clickable trigger (the price stats grid laid out the same way as `ListingPreviewCard`'s `<dl>`). Click → `<Dialog>` + the existing `AuctionSettingsForm` controlled by local state + Save / Cancel buttons. Save → `onSave(value)` → close modal on success; keep open + show error on failure.

- [ ] **Step 14.5: Run, verify pass + commit**

```bash
git add frontend/src/components/listing/draft-editor/EditableSettingsModal.tsx \
        frontend/src/components/listing/draft-editor/EditableSettingsModal.test.tsx
git commit -m "feat(draft-editor): add EditableSettingsModal whole-group settings edit"
```

---

### Task 15: EditableParcelModal (TDD)

**Files:**
- Create: `frontend/src/components/listing/draft-editor/EditableParcelModal.tsx`
- Create: `frontend/src/components/listing/draft-editor/EditableParcelModal.test.tsx`

- [ ] **Step 15.1: Read existing `ParcelLookupField`**

Run: read `frontend/src/components/listing/ParcelLookupField.tsx`. Note the lookup-success callback signature.

- [ ] **Step 15.2: Failing test**

```typescript
it("opens modal, picks a different parcel, requires re-snapshot confirm, then saves", async () => {
  const onSave = vi.fn().mockResolvedValue(undefined);
  renderWithProviders(
    <EditableParcelModal
      currentParcelLookupKey="region|10,20"
      onSave={onSave}
    />,
  );
  await userEvent.click(screen.getByTestId("editable-parcel-trigger"));
  // simulate a new parcel selected via ParcelLookupField ...
  await userEvent.click(screen.getByRole("button", { name: /confirm change/i }));
  expect(onSave).toHaveBeenCalled();
});
```

- [ ] **Step 15.3: Run, verify failure**

Run: `cd frontend && npm test -- EditableParcelModal`

- [ ] **Step 15.4: Implement**

Modal containing `ParcelLookupField` + a "Re-snapshot will discard your current snapshot" warning + Confirm / Cancel buttons. Confirm calls `onSave(parcelLookupKey)` and closes on success.

- [ ] **Step 15.5: Run, verify pass + commit**

```bash
git add frontend/src/components/listing/draft-editor/EditableParcelModal.tsx \
        frontend/src/components/listing/draft-editor/EditableParcelModal.test.tsx
git commit -m "feat(draft-editor): add EditableParcelModal with re-snapshot confirm"
```

---

## Phase 5 — ParcelInfoPanel editable wrapper

### Task 16: ParcelInfoPanel — editable prop

**Files:**
- Modify: `frontend/src/components/auction/ParcelInfoPanel.tsx`
- Modify: `frontend/src/components/auction/ParcelInfoPanel.test.tsx`

- [ ] **Step 16.1: Read current ParcelInfoPanel**

Run: read `frontend/src/components/auction/ParcelInfoPanel.tsx`. Pin every region that the editor needs to make editable: title slot, parcel/region row, settings/price stats grid, description block, tags row.

- [ ] **Step 16.2: Failing test**

```typescript
it("renders Editable* wrappers for each editable region when `editable` is provided", async () => {
  const onTitleChange = vi.fn();
  renderWithProviders(
    <ParcelInfoPanel
      auction={fixtureAuction}
      editable={{
        onTitleChange,
        onDescriptionChange: vi.fn(),
        onTagsChange: vi.fn(),
        onSettingsChange: vi.fn(),
        onParcelChange: vi.fn(),
      }}
    />,
  );
  expect(screen.getByTestId("editable-title")).toBeInTheDocument();
  expect(screen.getByTestId("editable-description")).toBeInTheDocument();
  expect(screen.getByTestId("editable-tags-trigger")).toBeInTheDocument();
  expect(screen.getByTestId("editable-settings-trigger")).toBeInTheDocument();
  expect(screen.getByTestId("editable-parcel-trigger")).toBeInTheDocument();
});

it("renders identically when editable is omitted (buyer flow)", () => {
  const { container } = renderWithProviders(
    <ParcelInfoPanel auction={fixtureAuction} />,
  );
  expect(container.querySelector('[data-testid^="editable-"]')).toBeNull();
});
```

- [ ] **Step 16.3: Run, verify failure**

Run: `cd frontend && npm test -- ParcelInfoPanel`

- [ ] **Step 16.4: Implement**

Add prop:

```typescript
interface ParcelInfoPanelEditable {
  onTitleChange: (next: string) => Promise<void>;
  onDescriptionChange: (next: string) => Promise<void>;
  onTagsChange: (next: ParcelTag[]) => Promise<void>;
  onSettingsChange: (next: SettingsValue) => Promise<void>;
  onParcelChange: (next: string) => Promise<void>;
}

interface ParcelInfoPanelProps {
  auction: ...;
  reportButton?: ReactNode;
  editable?: ParcelInfoPanelEditable;
}
```

When `editable` is set, swap each region for the matching Editable* component, wired to the corresponding callback. When omitted, render identically to today.

- [ ] **Step 16.5: Run, verify pass + commit**

```bash
git add frontend/src/components/auction/ParcelInfoPanel.tsx \
        frontend/src/components/auction/ParcelInfoPanel.test.tsx
git commit -m "feat(parcel-info): add optional editable prop for draft editor mode"
```

---

## Phase 6 — Photo gallery + sample data + bid panel preview

### Task 17: SampleBidHistory (TDD)

**Files:**
- Create: `frontend/src/components/listing/draft-editor/SampleBidHistory.ts`
- Create: `frontend/src/components/listing/draft-editor/SampleBidHistory.test.ts`

- [ ] **Step 17.1: Failing test**

```typescript
import { describe, expect, it } from "vitest";
import { SAMPLE_BIDS, sampleCurrentBid, sampleBidderCount } from "./SampleBidHistory";

describe("SampleBidHistory", () => {
  it("exports a frozen array of 4-5 entries", () => {
    expect(SAMPLE_BIDS.length).toBeGreaterThanOrEqual(4);
    expect(SAMPLE_BIDS.length).toBeLessThanOrEqual(5);
    expect(Object.isFrozen(SAMPLE_BIDS)).toBe(true);
  });

  it("amounts are monotonically increasing in placement order", () => {
    const ordered = [...SAMPLE_BIDS].sort(
      (a, b) => new Date(a.placedAt).getTime() - new Date(b.placedAt).getTime());
    for (let i = 1; i < ordered.length; i++) {
      expect(ordered[i].amount).toBeGreaterThan(ordered[i - 1].amount);
    }
  });

  it("sampleCurrentBid is the max amount", () => {
    const max = Math.max(...SAMPLE_BIDS.map((b) => b.amount));
    expect(sampleCurrentBid()).toBe(max);
  });

  it("sampleBidderCount is the unique bidder publicId count", () => {
    const unique = new Set(SAMPLE_BIDS.map((b) => b.bidderPublicId)).size;
    expect(sampleBidderCount()).toBe(unique);
  });
});
```

- [ ] **Step 17.2: Run, verify failure**

Run: `cd frontend && npm test -- SampleBidHistory`

- [ ] **Step 17.3: Implement**

```typescript
import type { BidHistoryEntry } from "@/types/auction";

export const SAMPLE_BIDS: ReadonlyArray<BidHistoryEntry> = Object.freeze([
  { bidPublicId: "sample-1", bidderPublicId: "sample-bidder-a",
    bidderDisplayName: "Sample Bidder A", amount: 1000,
    placedAt: new Date(Date.now() - 90 * 60_000).toISOString(),
    snipeExtensionMinutes: null },
  { bidPublicId: "sample-2", bidderPublicId: "sample-bidder-b",
    bidderDisplayName: "Sample Bidder B", amount: 1150,
    placedAt: new Date(Date.now() - 60 * 60_000).toISOString(),
    snipeExtensionMinutes: null },
  { bidPublicId: "sample-3", bidderPublicId: "sample-bidder-a",
    bidderDisplayName: "Sample Bidder A", amount: 1300,
    placedAt: new Date(Date.now() - 30 * 60_000).toISOString(),
    snipeExtensionMinutes: null },
  { bidPublicId: "sample-4", bidderPublicId: "sample-bidder-c",
    bidderDisplayName: "Sample Bidder C", amount: 1500,
    placedAt: new Date(Date.now() - 10 * 60_000).toISOString(),
    snipeExtensionMinutes: null },
]);

export function sampleCurrentBid(): number {
  return Math.max(...SAMPLE_BIDS.map((b) => b.amount));
}

export function sampleBidderCount(): number {
  return new Set(SAMPLE_BIDS.map((b) => b.bidderPublicId)).size;
}
```

- [ ] **Step 17.4: Run, verify pass + commit**

```bash
git add frontend/src/components/listing/draft-editor/SampleBidHistory.ts \
        frontend/src/components/listing/draft-editor/SampleBidHistory.test.ts
git commit -m "feat(draft-editor): add SAMPLE_BIDS frozen fixture for preview mode"
```

---

### Task 18: BidPanelPreview (TDD)

**Files:**
- Create: `frontend/src/components/listing/draft-editor/BidPanelPreview.tsx`
- Create: `frontend/src/components/listing/draft-editor/BidPanelPreview.test.tsx`

- [ ] **Step 18.1: Read current BidPanel for visual reference**

Run: read `frontend/src/components/auction/BidPanel.tsx`. Pin the visual structure (current bid line, buy now line, time remaining, bid input). The preview should look as similar as possible without the WebSocket/auth wiring.

- [ ] **Step 18.2: Failing test**

```typescript
it("renders sample bid amount, sample bidder count, disabled bid input, and Sample pill", () => {
  renderWithProviders(
    <BidPanelPreview
      auction={{
        startingBid: 500,
        buyNowPrice: 5000,
        reservePrice: null,
        durationHours: 48,
      }}
    />,
  );
  expect(screen.getByText(/L\$1,500/)).toBeInTheDocument(); // sampleCurrentBid()
  expect(screen.getByText(/3 bidders/)).toBeInTheDocument(); // sampleBidderCount()
  expect(screen.getByText(/Sample/i)).toBeInTheDocument();
  const input = screen.getByRole("spinbutton");
  expect(input).toBeDisabled();
});
```

- [ ] **Step 18.3: Run, verify failure**

Run: `cd frontend && npm test -- BidPanelPreview`

- [ ] **Step 18.4: Implement**

A standalone component (no auth read, no WebSocket, no mutations). Layout mirrors `BidPanel` visually. Uses `sampleCurrentBid()` + `sampleBidderCount()` from `SampleBidHistory`. Time-remaining derived from `auction.durationHours` ("Runs for X hours when activated"). Bid input rendered but disabled with "Listing not yet active" helper text. `Sample` pill in the header.

- [ ] **Step 18.5: Run, verify pass + commit**

```bash
git add frontend/src/components/listing/draft-editor/BidPanelPreview.tsx \
        frontend/src/components/listing/draft-editor/BidPanelPreview.test.tsx
git commit -m "feat(draft-editor): add BidPanelPreview with sample populated state"
```

---

### Task 19: EditablePhotoGallery (TDD)

**Files:**
- Create: `frontend/src/components/listing/draft-editor/EditablePhotoGallery.tsx`
- Create: `frontend/src/components/listing/draft-editor/EditablePhotoGallery.test.tsx`

- [ ] **Step 19.1: Failing test**

```typescript
it("calls onReorder with the new ordered publicIds when a drag completes", async () => {
  const onReorder = vi.fn().mockResolvedValue(undefined);
  renderWithProviders(
    <EditablePhotoGallery
      photos={[
        { publicId: "p1", url: "/api/v1/photos/p1", contentType: "image/webp",
          sizeBytes: 1, sortOrder: 1, uploadedAt: "x" },
        { publicId: "p2", url: "/api/v1/photos/p2", contentType: "image/webp",
          sizeBytes: 1, sortOrder: 2, uploadedAt: "x" },
      ]}
      snapshotUrl={null}
      regionName="X"
      onReorder={onReorder}
      onDelete={vi.fn()}
      onAdd={vi.fn()}
    />,
  );
  // Simulate dnd-kit drag-end via the component's exposed test hook (or
  // call the internal handler directly via a test ref). dnd-kit's drag
  // events are notoriously hard to simulate via userEvent — instead expose
  // an `onDragEndForTest` prop or split the handler into a pure function
  // and test the function alone.
});
```

NOTE: dnd-kit drag events don't simulate well in jsdom. **Test the pure reorder function in isolation** (input = current photos + drag-end event ids, output = new ordered publicIds), and exercise the wiring with a small "exposed-for-test" handler. This is a pragmatic concession — the actual dnd-kit interaction is verified manually and via the smoke step at the end.

```typescript
// in EditablePhotoGallery.tsx, export the pure reorder helper:
export function applyDragEnd(
  photos: AuctionPhotoDto[],
  activeId: string,
  overId: string | null,
): string[] | null {
  if (!overId || activeId === overId) return null;
  const oldIndex = photos.findIndex((p) => p.publicId === activeId);
  const newIndex = photos.findIndex((p) => p.publicId === overId);
  if (oldIndex < 0 || newIndex < 0) return null;
  const next = [...photos];
  const [moved] = next.splice(oldIndex, 1);
  next.splice(newIndex, 0, moved);
  return next.map((p) => p.publicId);
}
```

Test cases for the pure helper:
- moves last to first → `["p3", "p1", "p2"]`
- noop when `activeId === overId` → null
- noop when `overId == null` → null
- moves middle to end → expected order

- [ ] **Step 19.2: Run, verify failure**

Run: `cd frontend && npm test -- EditablePhotoGallery`

- [ ] **Step 19.3: Implement**

Wraps `AuctionHero` inside `<DndContext>` + `<SortableContext>` from `@dnd-kit/core` and `@dnd-kit/sortable`. Each thumbnail in the strip becomes a `useSortable` element with a drag handle. `onDragEnd` calls `applyDragEnd` and feeds the result into `props.onReorder`. Uploads-in-flight (no `publicId`) are filtered out of the sortable set. Deletion confirm dialog wraps `props.onDelete`. Add tile (visible when `photos.length < 10`) opens a hidden `<input type="file">` and calls `props.onAdd(file)`.

- [ ] **Step 19.4: Run, verify pass + commit**

```bash
git add frontend/src/components/listing/draft-editor/EditablePhotoGallery.tsx \
        frontend/src/components/listing/draft-editor/EditablePhotoGallery.test.tsx
git commit -m "feat(draft-editor): add EditablePhotoGallery with drag-reorder + delete + add"
```

---

## Phase 7 — Top action bar + delete-draft modal + sample-data banner

### Task 20: DraftActionBar (TDD)

**Files:**
- Create: `frontend/src/components/listing/draft-editor/DraftActionBar.tsx`
- Create: `frontend/src/components/listing/draft-editor/DraftActionBar.test.tsx`

- [ ] **Step 20.1: Read existing `ActivateListingPanel`**

Run: read `frontend/src/components/listing/ActivateListingPanel.tsx`. Note the listing-fee mutation hook + the wallet-balance read it uses. The new bar reuses both.

- [ ] **Step 20.2: Failing test**

```typescript
it("renders fee + wallet, List Parcel button, Delete Draft link", () => {
  renderWithProviders(
    <DraftActionBar
      listingFee={100}
      walletBalance={5000}
      onListParcel={vi.fn()}
      onDeleteDraft={vi.fn()}
      isListing={false}
    />,
  );
  expect(screen.getByText(/Listing fee/i)).toBeInTheDocument();
  expect(screen.getByRole("button", { name: /List parcel/i })).toBeInTheDocument();
  expect(screen.getByRole("button", { name: /Delete draft/i })).toBeInTheDocument();
});

it("disables List Parcel while isListing=true", () => {
  renderWithProviders(
    <DraftActionBar
      listingFee={100} walletBalance={5000}
      onListParcel={vi.fn()} onDeleteDraft={vi.fn()}
      isListing={true}
    />,
  );
  expect(screen.getByRole("button", { name: /List parcel/i })).toBeDisabled();
});
```

- [ ] **Step 20.3: Run, verify failure + implement + commit**

Run: `cd frontend && npm test -- DraftActionBar`

Implement a sticky bar (`sticky top-0 z-40`) with:
- Left: "Listing fee: L$X · Wallet: L$Y"
- Right: Delete Draft danger button + List Parcel primary button
- `isListing` prop disables the primary

```bash
git add frontend/src/components/listing/draft-editor/DraftActionBar.tsx \
        frontend/src/components/listing/draft-editor/DraftActionBar.test.tsx
git commit -m "feat(draft-editor): add DraftActionBar sticky top action surface"
```

---

### Task 21: DraftSampleDataBanner

**Files:**
- Create: `frontend/src/components/listing/draft-editor/DraftSampleDataBanner.tsx`

- [ ] **Step 21.1: Implement (no behavior, single line of copy)**

```typescript
export function DraftSampleDataBanner() {
  return (
    <div
      role="note"
      data-testid="draft-sample-data-banner"
      className="rounded-lg bg-brand-soft px-4 py-2 text-xs text-brand"
    >
      This is a preview with sample bids and activity. Your live listing will start empty.
    </div>
  );
}
```

- [ ] **Step 21.2: Commit**

```bash
git add frontend/src/components/listing/draft-editor/DraftSampleDataBanner.tsx
git commit -m "feat(draft-editor): add DraftSampleDataBanner explainer banner"
```

---

### Task 22: DeleteDraftModal (TDD)

**Files:**
- Create: `frontend/src/components/listing/draft-editor/DeleteDraftModal.tsx`
- Create: `frontend/src/components/listing/draft-editor/DeleteDraftModal.test.tsx`

- [ ] **Step 22.1: Failing test**

```typescript
it("renders the DRAFT-specific copy and calls cancelAuction on confirm", async () => {
  const onClose = vi.fn();
  const auction = { publicId: "abc", status: "DRAFT" } as SellerAuctionResponse;
  // mock cancelAuction via MSW
  renderWithProviders(<DeleteDraftModal open onClose={onClose} auction={auction} />);
  expect(screen.getByText(/Delete this draft/i)).toBeInTheDocument();
  expect(screen.getByText(/can't be undone/i)).toBeInTheDocument();
  await userEvent.click(screen.getByRole("button", { name: /Delete draft/i }));
  // assert toast + redirect (or onClose called)
});
```

- [ ] **Step 22.2: Run, verify failure + implement + commit**

Run: `cd frontend && npm test -- DeleteDraftModal`

Implement a smaller version of `CancelListingModal` — same `cancelAuction` mutation, simpler copy ("Delete this draft? This can't be undone."), no consequence-aware variants (DRAFT never has bids by definition for the relevant branches). Same redirect-and-toast behavior on success.

```bash
git add frontend/src/components/listing/draft-editor/DeleteDraftModal.tsx \
        frontend/src/components/listing/draft-editor/DeleteDraftModal.test.tsx
git commit -m "feat(draft-editor): add DeleteDraftModal for DRAFT-status confirmations"
```

---

## Phase 8 — Wire it all up

### Task 23: DraftEditorClient (TDD integration)

**Files:**
- Create: `frontend/src/app/listings/(verified)/[publicId]/activate/DraftEditorClient.tsx`
- Create: `frontend/src/app/listings/(verified)/[publicId]/activate/DraftEditorClient.test.tsx`

- [ ] **Step 23.1: Failing test**

```typescript
import { renderWithProviders, screen } from "@/test/render";
import { DraftEditorClient } from "./DraftEditorClient";

const fixture: SellerAuctionResponse = { /* DRAFT auction with parcel + 2 photos + tags + settings */ };

it("renders the full real-page composition with sample data and the action bar", () => {
  renderWithProviders(<DraftEditorClient auction={fixture} />);

  // Real-page layout primitives
  expect(screen.getByTestId("draft-sample-data-banner")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: /List parcel/i })).toBeInTheDocument();
  expect(screen.getByRole("button", { name: /Delete draft/i })).toBeInTheDocument();
  expect(screen.getByTestId("editable-title")).toBeInTheDocument();
  expect(screen.getByTestId("editable-description")).toBeInTheDocument();
  expect(screen.getByTestId("editable-tags-trigger")).toBeInTheDocument();
  expect(screen.getByTestId("editable-settings-trigger")).toBeInTheDocument();
  expect(screen.getByTestId("editable-parcel-trigger")).toBeInTheDocument();
  // Sample-data sections
  expect(screen.getAllByText(/Sample/i).length).toBeGreaterThan(0);
});
```

- [ ] **Step 23.2: Run, verify failure**

Run: `cd frontend && npm test -- DraftEditorClient`

- [ ] **Step 23.3: Implement**

The composition follows the spec's layout snippet exactly:

```typescript
"use client";
import { useState } from "react";
import { useRouter } from "next/navigation";
import { useToast } from "@/components/ui/Toast";
import { BreadcrumbNav } from "@/components/auction/BreadcrumbNav";
import { ParcelInfoPanel } from "@/components/auction/ParcelInfoPanel";
import { VisitInSecondLifeBlock } from "@/components/auction/VisitInSecondLifeBlock";
import { ParcelLayoutMapPlaceholder } from "@/components/auction/ParcelLayoutMapPlaceholder";
import { BidHistoryList } from "@/components/auction/BidHistoryList";
import { SellerProfileCard } from "@/components/auction/SellerProfileCard";
import { EditablePhotoGallery } from "@/components/listing/draft-editor/EditablePhotoGallery";
import { BidPanelPreview } from "@/components/listing/draft-editor/BidPanelPreview";
import { DraftActionBar } from "@/components/listing/draft-editor/DraftActionBar";
import { DraftSampleDataBanner } from "@/components/listing/draft-editor/DraftSampleDataBanner";
import { DeleteDraftModal } from "@/components/listing/draft-editor/DeleteDraftModal";
import { SAMPLE_BIDS } from "@/components/listing/draft-editor/SampleBidHistory";
import { useDraftEditorMutations } from "@/components/listing/draft-editor/draftEditorMutations";
import { useReorderAuctionPhotos } from "@/hooks/useReorderAuctionPhotos";
import { uploadPhoto, deletePhoto } from "@/lib/api/auctionPhotos";
// ...

export function DraftEditorClient({ auction }: { auction: SellerAuctionResponse }) {
  const m = useDraftEditorMutations(auction.publicId);
  const reorder = useReorderAuctionPhotos(auction.publicId);
  const [deleteOpen, setDeleteOpen] = useState(false);
  // listingFee + wallet read via the same hooks ActivateListingPanel uses today
  // listing-fee mutation lifted from ActivateListingPanel

  return (
    <>
      <DraftSampleDataBanner />
      <DraftActionBar
        listingFee={auction.listingFeeAmt}
        walletBalance={...}
        isListing={...}
        onListParcel={...}
        onDeleteDraft={() => setDeleteOpen(true)}
      />
      <main className="max-w-7xl mx-auto px-4 lg:px-8 pt-8 lg:pt-24 pb-24 lg:pb-12">
        <BreadcrumbNav region={auction.parcel.regionName} title={auction.title} />
        <div className="mt-6 grid grid-cols-1 lg:grid-cols-12 gap-6 lg:gap-12">
          <div className="lg:col-span-8 space-y-8 lg:space-y-12">
            <EditablePhotoGallery
              photos={auction.photos}
              snapshotUrl={auction.parcel.snapshotUrl}
              regionName={auction.parcel.regionName}
              onReorder={(ids) => reorder.mutateAsync(ids).then(() => undefined)}
              onDelete={(publicId) => deletePhoto(auction.publicId, publicId).then(...)}
              onAdd={(file) => uploadPhoto(auction.publicId, file).then(...)}
            />
            <ParcelInfoPanel
              auction={auction}
              editable={{
                onTitleChange: m.saveTitle,
                onDescriptionChange: m.saveDescription,
                onTagsChange: (tags) => m.saveTags(tags.map((t) => t.code)),
                onSettingsChange: m.saveSettings,
                onParcelChange: m.saveParcel,
              }}
            />
            <VisitInSecondLifeBlock {...auction.parcel} />
            <ParcelLayoutMapPlaceholder />
            <BidHistoryList auctionPublicId={auction.publicId} sampleEntries={SAMPLE_BIDS} />
            <SellerProfileCard seller={...} />
          </div>
          <aside className="hidden lg:block lg:col-span-4">
            <div className="sticky top-24">
              <BidPanelPreview auction={auction} />
            </div>
          </aside>
        </div>
        <div className="lg:hidden mt-8">
          <BidPanelPreview auction={auction} />
        </div>
      </main>
      <DeleteDraftModal
        open={deleteOpen}
        onClose={() => setDeleteOpen(false)}
        auction={auction}
      />
    </>
  );
}
```

- [ ] **Step 23.4: Run, verify pass**

Run: `cd frontend && npm test -- DraftEditorClient`

- [ ] **Step 23.5: Commit**

```bash
git add frontend/src/app/listings/\(verified\)/\[publicId\]/activate/DraftEditorClient.tsx \
        frontend/src/app/listings/\(verified\)/\[publicId\]/activate/DraftEditorClient.test.tsx
git commit -m "feat(draft-editor): wire DraftEditorClient composition"
```

---

### Task 24: ActivateClient — route DRAFT branch to DraftEditorClient

**Files:**
- Modify: `frontend/src/app/listings/(verified)/[publicId]/activate/ActivateClient.tsx`

- [ ] **Step 24.1: Failing test addition**

In `ActivateClient.test.tsx` (existing): add a case asserting that when status is DRAFT, `DraftEditorClient` is rendered (assert via `data-testid="draft-sample-data-banner"`); when status is DRAFT_PAID, the verification picker is rendered (existing case kept as-is).

- [ ] **Step 24.2: Run, verify failure**

Run: `cd frontend && npm test -- ActivateClient`

- [ ] **Step 24.3: Implement**

Replace the DRAFT branch in `ActivateClient.tsx` (lines ~132-137) — change:

```typescript
{auction.status === "DRAFT" && (
  <>
    <ListingPreviewCard auction={auction} isPreview />
    <ActivateListingPanel auctionPublicId={auction.publicId} />
  </>
)}
```

to:

```typescript
{auction.status === "DRAFT" && <DraftEditorClient auction={auction} />}
```

Also remove the cancel link + `CancelListingModal` from the DRAFT branch (it's in the action bar now). Keep them for non-DRAFT branches.

Add the new import: `import { DraftEditorClient } from "./DraftEditorClient";`.
Remove unused imports of `ListingPreviewCard` and `ActivateListingPanel` if no other branch references them.

The outer wrapper (`max-w-3xl flex flex-col gap-6 p-6`) is no longer right for DRAFT (DraftEditorClient owns its own layout). Hoist the `<ActivateStatusStepper>` above the dispatch and only render the constrained wrapper for non-DRAFT branches:

```typescript
return (
  <>
    {auction.status === "DRAFT" && <DraftEditorClient auction={auction} />}
    {auction.status !== "DRAFT" && (
      <div className="mx-auto flex max-w-3xl flex-col gap-6 p-6">
        <ActivateStatusStepper status={auction.status} />
        {(auction.status === "DRAFT_PAID" || auction.status === "VERIFICATION_FAILED") && (
          <VerificationMethodPicker ... />
        )}
        {auction.status === "VERIFICATION_PENDING" && <VerificationInProgressPanel auction={auction} />}
        <div className="border-t border-border-subtle pt-4">
          <button type="button" onClick={() => setCancelOpen(true)} className="text-xs ...">
            Cancel this listing
          </button>
        </div>
        <CancelListingModal open={cancelOpen} onClose={() => setCancelOpen(false)} auction={auction} />
      </div>
    )}
  </>
);
```

- [ ] **Step 24.4: Run, verify pass**

Run: `cd frontend && npm test -- ActivateClient`

- [ ] **Step 24.5: Commit**

```bash
git add frontend/src/app/listings/\(verified\)/\[publicId\]/activate/ActivateClient.tsx \
        frontend/src/app/listings/\(verified\)/\[publicId\]/activate/ActivateClient.test.tsx
git commit -m "feat(activate): route DRAFT branch to DraftEditorClient"
```

---

## Phase 9 — Wizard photo reorder

### Task 25: PhotoUploader drag-reorder

**Files:**
- Modify: `frontend/src/components/listing/PhotoUploader.tsx`
- Modify: `frontend/src/components/listing/PhotoUploader.test.tsx`

- [ ] **Step 25.1: Failing test**

```typescript
it("calls onStagedChange with the new order when a drag completes", async () => {
  const onStagedChange = vi.fn();
  const initial = [
    { id: "a", file: new File(["x"], "a.png"), objectUrl: "blob:a", error: null,
      uploadedPhotoId: null },
    { id: "b", file: new File(["y"], "b.png"), objectUrl: "blob:b", error: null,
      uploadedPhotoId: null },
  ];
  // Test the pure helper exposed for test, same pattern as EditablePhotoGallery.
  const next = applyStagedDragEnd(initial, "b", "a");
  expect(next?.map((p) => p.id)).toEqual(["b", "a"]);
});
```

- [ ] **Step 25.2: Run, verify failure**

Run: `cd frontend && npm test -- PhotoUploader`

- [ ] **Step 25.3: Implement**

Wrap the existing `<ul>` of staged thumbnails in `<DndContext>` + `<SortableContext>`. Each `<li>` is `useSortable`-driven with a drag handle. `onDragEnd` calls `applyStagedDragEnd` (export the pure helper) and feeds the result into `onStagedChange`. Items with `p.error` remain sortable (their error overlay covers the handle, but we leave it draggable so order can still be fixed before re-validation).

- [ ] **Step 25.4: Run, verify pass + commit**

Run: `cd frontend && npm test -- PhotoUploader`

```bash
git add frontend/src/components/listing/PhotoUploader.tsx \
        frontend/src/components/listing/PhotoUploader.test.tsx
git commit -m "feat(wizard): add drag-reorder to staged PhotoUploader"
```

---

## Phase 10 — Cleanup + drift guard + Postman

### Task 26: Remove `/listings/[id]/edit` route + redirect dashboard links

**Files:**
- Delete: `frontend/src/app/listings/(verified)/[publicId]/edit/page.tsx`
- Modify: any in-app caller of `/listings/[id]/edit`

- [ ] **Step 26.1: Find callers**

Run: `Grep` for `/listings/.*?/edit` in `frontend/src`.

- [ ] **Step 26.2: Replace each caller**

Each `/listings/${id}/edit` becomes `/listings/${id}/activate`.

- [ ] **Step 26.3: Delete the route file**

```bash
rm frontend/src/app/listings/\(verified\)/\[publicId\]/edit/page.tsx
rmdir frontend/src/app/listings/\(verified\)/\[publicId\]/edit
```

- [ ] **Step 26.4: Run the frontend test suite**

Run: `cd frontend && npm test`
Expected: PASS — no dangling links to `/edit`.

- [ ] **Step 26.5: Commit**

```bash
git add -A
git commit -m "refactor(listings): remove /edit route, point in-app links to /activate"
```

---

### Task 27: Drift guard test

**Files:**
- Create: `frontend/src/app/listings/(verified)/[publicId]/activate/draft-editor-drift-guard.test.tsx`

- [ ] **Step 27.1: Implement the test**

```typescript
import { describe, expect, it } from "vitest";
import { renderWithProviders, within } from "@/test/render";
import { AuctionDetailClient } from "@/app/auction/[publicId]/AuctionDetailClient";
import { DraftEditorClient } from "./DraftEditorClient";

const SECTION_TESTIDS = [
  "auction-hero",
  "parcel-info-panel",
  "visit-in-second-life",
  "parcel-layout-map",
  "bid-history-section",
  "seller-profile-card",
];

function sectionOrder(container: HTMLElement): string[] {
  return SECTION_TESTIDS.filter((id) => container.querySelector(`[data-testid="${id}"]`));
}

describe("draft editor drift guard", () => {
  it("renders sections in the same order as AuctionDetailClient", () => {
    const fixture = /* DRAFT auction matching the buyer-fixture shape */;
    const buyerFixture = /* PUBLIC auction with same parcel + photos + tags */;

    const buyer = renderWithProviders(
      <AuctionDetailClient initialAuction={buyerFixture} initialBidPage={...} />,
    );
    const draftEditor = renderWithProviders(<DraftEditorClient auction={fixture} />);

    expect(sectionOrder(draftEditor.container)).toEqual(sectionOrder(buyer.container));
  });
});
```

If the existing leaf primitives don't carry `data-testid` for their section wrappers, add them as part of this task — additive, doesn't change rendering.

- [ ] **Step 27.2: Run, verify pass + commit**

Run: `cd frontend && npm test -- draft-editor-drift-guard`

```bash
git add frontend/src/app/listings/\(verified\)/\[publicId\]/activate/draft-editor-drift-guard.test.tsx
# plus any leaf primitive that grew a data-testid
git commit -m "test(draft-editor): add section-order drift guard between buyer + seller views"
```

---

### Task 28: Postman — mirror PATCH /photos/order

**Files:** Postman collection (external — no repo file).

- [ ] **Step 28.1: Add the request**

Use `mcp__postman__createCollectionRequest` (or add manually via the Postman UI):
- Collection: `8070328-cf3e1190-cb71-4af3-8e2e-70c82dfee288`
- Folder: `Auction Photos`
- Method: `PATCH`
- URL: `{{baseUrl}}/api/v1/auctions/{{auctionPublicId}}/photos/order`
- Auth: bearer `{{accessToken}}`
- Body: `{ "photoPublicIds": ["{{photo1PublicId}}", "{{photo2PublicId}}"] }`
- Tests script: assert `pm.response.code === 200` + the response array's first publicId equals `{{photo1PublicId}}`.

- [ ] **Step 28.2: Smoke test by running the request against the dev backend**

Send the request — confirm 200 + reordered shape.

(No git commit needed — Postman is external state.)

---

## Phase 11 — Final verification + PR

### Task 29: Full test sweep

- [ ] **Step 29.1: Run frontend test suite**

Run: `cd frontend && npm test`
Expected: PASS (zero failures).

- [ ] **Step 29.2: Run frontend verify guards**

Run: `cd frontend && npm run verify`
Expected: all guards pass.

- [ ] **Step 29.3: Run backend test suite**

Run: `cd backend && ./mvnw -q test`
Expected: BUILD SUCCESS.

- [ ] **Step 29.4: Smoke run via docker compose**

Run: `docker compose up --build -d` (from repo root)
Then manually:
- Open `/listings/create`, upload 3 photos, drag-reorder, click Save & Continue → /activate.
- On /activate (DRAFT): drag a photo to position 0, watch hero update; click the title → edit → blur → see new title persist on refresh; open Settings modal → change starting bid → save; click Delete Draft → confirm → redirect to /dashboard/listings.
- Repeat with a fresh draft, click List Parcel → fee modal → confirm → status flips to DRAFT_PAID → verification picker shows.

- [ ] **Step 29.5: Update root README**

Per CLAUDE.md memory: every task sweeps the root README for staleness. Touch `README.md` only if the section that describes seller listing creation is now stale; otherwise no change required.

If README needs updating, commit:

```bash
git add README.md
git commit -m "docs(readme): describe unified DRAFT editor on /activate"
```

---

### Task 30: Open PR to dev

- [ ] **Step 30.1: Push final commits**

```bash
git push origin feat/draft-editor-photo-reorder
```

- [ ] **Step 30.2: Open PR**

```bash
gh pr create --base dev --head feat/draft-editor-photo-reorder \
  --title "feat: photo reorder + real-page draft editor on /activate" \
  --body "$(cat <<'EOF'
## Summary
- Sellers can drag-reorder photos in the create wizard and on a unified rich draft editor that replaces both `/listings/[id]/edit` and the small preview card on `/listings/[id]/activate`
- New `PATCH /api/v1/auctions/{publicId}/photos/order` endpoint with set-equality validation
- `/listings/[id]/activate` for DRAFT now renders the full real-page composition (hero, parcel info, visit-in-SL, layout map, bid history, seller card) with inline click-to-edit, dummied populated bid history + right-rail, and a sticky top action bar with List Parcel + Delete Draft
- `/listings/[id]/edit` route removed; in-app links repointed to `/activate`

## Spec
docs/superpowers/specs/2026-05-07-listing-image-reorder-and-real-preview-design.md

## Test plan
- [x] Backend unit + integration tests for AuctionPhotoService.reorder + the controller endpoint
- [x] Frontend tests for useInlineEdit, every Editable* wrapper, EditablePhotoGallery (pure helper), BidPanelPreview, SampleBidHistory, draftEditorMutations, useReorderAuctionPhotos, DraftEditorClient integration
- [x] Drift guard test asserting buyer + seller section orders match
- [x] Frontend verify guards pass
- [x] Manual smoke: drag-reorder in wizard + activate, inline edits, List Parcel + Delete Draft flows
EOF
)"
```

- [ ] **Step 30.3: Stop**

Per CLAUDE.md memory `feedback_no_merge_to_main.md`: do NOT merge to main. PR is reviewed and merged into `dev` by the user, then the user reviews + merges to main themselves.

---

## Self-review

**Spec coverage:**
- ✅ Backend reorder endpoint (`PATCH /photos/order`) — Task 1-3
- ✅ `PhotoSetMismatchException` + `code=PHOTO_SET_MISMATCH` mapping — Task 1
- ✅ Atomic full-list reorder with set-equality check — Task 2
- ✅ DRAFT-or-DRAFT_PAID status gate — Task 2
- ✅ Frontend reorder helper + hook — Task 5-6
- ✅ AuctionHero additive props (onReorder/onDelete/onAdd) — Task 7
- ✅ ParcelInfoPanel `editable` prop — Task 16
- ✅ BidHistoryList `sampleEntries` prop — Task 8
- ✅ useInlineEdit state machine — Task 9
- ✅ Per-field mutations (title/desc/tags/settings/parcel) — Task 10
- ✅ EditableTitle / EditableDescription / EditableTags / EditableSettingsModal / EditableParcelModal — Task 11-15
- ✅ EditablePhotoGallery with drag-reorder + delete + add — Task 19
- ✅ SAMPLE_BIDS + sampleCurrentBid + sampleBidderCount — Task 17
- ✅ BidPanelPreview — Task 18
- ✅ DraftActionBar (sticky top, fee + wallet + List Parcel + Delete Draft) — Task 20
- ✅ DraftSampleDataBanner — Task 21
- ✅ DeleteDraftModal — Task 22
- ✅ DraftEditorClient composition — Task 23
- ✅ ActivateClient DRAFT branch routes to DraftEditorClient — Task 24
- ✅ Wizard PhotoUploader drag-reorder — Task 25
- ✅ `/edit` route removed + in-app links repointed — Task 26
- ✅ Drift guard test — Task 27
- ✅ Postman collection updated — Task 28
- ✅ Final test sweep + smoke + PR to dev — Task 29-30

**Placeholder scan:** No "TBD/TODO/etc." patterns. The "verify by reading X" steps in Tasks 7, 10, 13, 14, 15, 16, 20 are deliberate — they're "look this up because the existing helper name might vary," not unfinished plan content. Each is followed by the concrete change to make.

**Type consistency:**
- `applyDragEnd` (Task 19) and `applyStagedDragEnd` (Task 25) — separate names, intentional, since they operate on different types (`AuctionPhotoDto[]` vs `StagedPhoto[]`).
- `useInlineEdit<T>` shape consistent across all Editable* tasks.
- `useDraftEditorMutations` returns `{ saveTitle, saveDescription, saveTags, saveSettings, saveParcel }` — matches the `editable` prop callbacks in Task 16.
- `BidHistoryEntry` shape consistent across SampleBidHistory (Task 17) and BidHistoryList sampleEntries (Task 8).
