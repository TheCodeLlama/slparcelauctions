# Parcel Map (Frontend) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Render the per-auction parcel rasters (layout bitmap + region heightmap) as a single combined canvas on the auction detail page inside `ParcelInfoPanel`, with hover tooltips and keyboard navigation.

**Architecture:** Add one public-no-auth backend endpoint (`GET /api/v1/auctions/{publicId}/parcel-scan`) that returns the two rasters as base64 + the elevation header. Frontend has a React Query hook that 404-resolves to `data: null`. A new `ParcelMap` client component paints a 256x256 canvas: heightmap as green-to-red gradient (parcel-min relative), dim non-parcel cells (60% toward gray), 1-pixel cyan boundary outline around the listed parcel. Hover + arrow keys announce per-cell elevation and in/out-parcel status. Missing scan -> component returns null -> panel collapses silently.

**Tech Stack:** Spring Boot 4 / Java 24; Next.js 16 / React 19 / TypeScript 5 / Tailwind 4 / TanStack Query / Vitest + RTL.

**Spec:** `docs/superpowers/specs/2026-05-24-parcel-map-frontend-design.md`.
**Builds on:** `docs/superpowers/specs/2026-05-23-parcel-scanner-design.md` (the scanner that writes the rasters this consumes).
**Closes:** the "Parcel scanner: frontend raster rendering" entry in `docs/implementation/DEFERRED_WORK.md` (resolution row in Task 5).

**Codebase notes baked in from spec-time exploration:**
- `AuctionController` already has `@GetMapping("/auctions/{publicId}")` (line 89) and other `/auctions/{publicId}/...` endpoints. The new endpoint adds alongside them.
- `AuctionExceptionHandler` already maps `AuctionNotFoundException` to 404 with `code: "AUCTION_NOT_FOUND"` (line 105-114). The new `AuctionParcelScanNotFoundException` follows the exact same pattern with code `PARCEL_SCAN_NOT_FOUND`.
- `SecurityConfig.java` line 196 wires `/api/v1/auctions/*` to permitAll, but `*` matches only a single path segment, so the new `/api/v1/auctions/*/parcel-scan` needs its own explicit matcher (the precedent is `/api/v1/auctions/*/bids` at line 161 and `/api/v1/auctions/*/reviews` at line 242).
- Frontend hooks use `@tanstack/react-query` + a sibling `lib/api/<name>.ts` fetch function (see `useActiveListings.ts` / `lib/api/auctions.ts`). New hook mirrors that split.
- `useActiveListings.ts` uses `staleTime: 60_000`; this hook uses `staleTime: Infinity` (per-auction immutable rasters per the scanner spec).
- The existing `AuctionParcelLayoutRepository` and `AuctionParcelHeightMapRepository` (shipped by the scanner) both expose `findByAuctionId(Long)`; the read service uses those.

**Per-task verification (every task):**
- Backend tasks: `cd backend; ./mvnw test -Dtest='<class>'` for the narrow case.
- Frontend tasks: `cd frontend; npm test -- --run <file>` (Vitest) AND `cd frontend; npm run build` (tsc type-check — Vitest does NOT type-check, a prior feature shipped a build break because of this) AND `cd frontend; npm run verify` (no-hex-colors, no-dark-variants, no-inline-styles, coverage guards).
- Commit + push before declaring done. Push must happen so reviews see the change on GitHub.

---

## File Structure

**Backend — new:**

| File | Responsibility |
|---|---|
| `auction/parcelscan/dto/ParcelScanResponse.java` | Public read DTO record (7 fields) |
| `auction/parcelscan/ParcelScanReadService.java` | `Optional<ParcelScanResponse> findForAuction(UUID publicId)`, `@Transactional(readOnly=true)` |
| `auction/exception/AuctionParcelScanNotFoundException.java` | Thrown by controller when the service returns `Optional.empty()` |
| `backend/src/test/java/com/slparcelauctions/backend/auction/parcelscan/AuctionParcelScanReadControllerTest.java` | `@SpringBootTest + @AutoConfigureMockMvc` integration test |

**Backend — modify:**

| File | Change |
|---|---|
| `auction/AuctionController.java` | new `parcelScan` GET method |
| `auction/exception/AuctionExceptionHandler.java` | new `@ExceptionHandler(AuctionParcelScanNotFoundException.class)` -> 404 / `PARCEL_SCAN_NOT_FOUND` |
| `config/SecurityConfig.java` | new permitAll matcher for the new endpoint |

**Frontend — new:**

| File | Responsibility |
|---|---|
| `frontend/src/lib/parcelMap/colors.ts` | Pure gradient math: `gradientColor(deltaMeters)`, `dimOutside(rgb)`, color constants |
| `frontend/src/lib/parcelMap/colors.test.ts` | Tests anchor deltas + dim lerp |
| `frontend/src/lib/parcelMap/encoding.ts` | Pure binary decode: `decodeBase64ToBytes`, `isCellInParcel`, `decodeElevationCell` |
| `frontend/src/lib/parcelMap/encoding.test.ts` | Tests round-trip + bit layout |
| `frontend/src/lib/api/parcelScan.ts` | `getParcelScan(publicId)` fetch fn; returns `ParcelScanResponse \| null` (null on 404) |
| `frontend/src/hooks/useParcelScan.ts` | React Query wrapper |
| `frontend/src/hooks/useParcelScan.test.tsx` | Hook tests (200, 404, error) |
| `frontend/src/components/auction/ParcelMap.tsx` | Client component: canvas + tooltip + keyboard nav |
| `frontend/src/components/auction/ParcelMap.test.tsx` | Component tests (null, skeleton, canvas mount, mousemove, keyboard) |

**Frontend — modify:**

| File | Change |
|---|---|
| `frontend/src/types/auction.ts` | new `ParcelScanResponse` interface |
| `frontend/src/components/auction/ParcelInfoPanel.tsx` | render `<ParcelMap publicId={auction.publicId} />` after existing parcel-detail content |
| `frontend/src/components/auction/ParcelInfoPanel.test.tsx` | one new case asserting ParcelMap is mounted |

---

### Task 1: Backend — DTO, service, exception, controller, security, integration test

This is one cohesive backend slice. Ship together.

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/parcelscan/dto/ParcelScanResponse.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/parcelscan/ParcelScanReadService.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/exception/AuctionParcelScanNotFoundException.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionController.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/exception/AuctionExceptionHandler.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/config/SecurityConfig.java`
- Create: `backend/src/test/java/com/slparcelauctions/backend/auction/parcelscan/AuctionParcelScanReadControllerTest.java`

- [ ] **Step 1: Write `ParcelScanResponse.java`** (the read DTO record)

```java
package com.slparcelauctions.backend.auction.parcelscan.dto;

import java.time.OffsetDateTime;

/**
 * Public read DTO for {@code GET /api/v1/auctions/{publicId}/parcel-scan}.
 * See docs/superpowers/specs/2026-05-24-parcel-map-frontend-design.md.
 *
 * <p>Both byte arrays are base64-encoded so the response is JSON-safe.
 * {@code layoutCellsBase64} decodes to 512 bytes (4096 bits, MSB-first within
 * each byte, row-major SW-first). {@code heightCellsBase64} decodes to 4096
 * uint8s. Per-cell elevation:
 * <pre>elevationMeters = baseMeters + (cells[i] &amp; 0xFF) * stepMeters</pre>
 */
public record ParcelScanResponse(
        Integer gridSize,
        Integer cellSizeMeters,
        String layoutCellsBase64,
        String heightCellsBase64,
        Float baseMeters,
        Float stepMeters,
        OffsetDateTime scannedAt
) {
}
```

- [ ] **Step 2: Write `AuctionParcelScanNotFoundException.java`**

```java
package com.slparcelauctions.backend.auction.exception;

import java.util.UUID;

/**
 * Thrown by {@code AuctionController.parcelScan} when the auction has no
 * scan rows (either layout or heightmap is absent, or the auction itself
 * is unknown). Maps to 404 / {@code PARCEL_SCAN_NOT_FOUND} via
 * {@link AuctionExceptionHandler}.
 */
public class AuctionParcelScanNotFoundException extends RuntimeException {

    public AuctionParcelScanNotFoundException(UUID publicId) {
        super("parcel scan not found for auction " + publicId);
    }
}
```

- [ ] **Step 3: Write `ParcelScanReadService.java`**

```java
package com.slparcelauctions.backend.auction.parcelscan;

import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.parcelscan.dto.ParcelScanResponse;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * Read-only assembler for {@link ParcelScanResponse}. Returns
 * {@code Optional.empty()} if the auction is unknown OR either raster
 * row is absent; the controller maps that to a 404.
 *
 * <p>Rasters are immutable per the parcel-scanner spec ("the auction's
 * permanent record"), so callers can cache responses aggressively.
 */
@Service
@RequiredArgsConstructor
public class ParcelScanReadService {

    private final AuctionRepository auctionRepository;
    private final AuctionParcelLayoutRepository layoutRepository;
    private final AuctionParcelHeightMapRepository heightRepository;

    @Transactional(readOnly = true)
    public Optional<ParcelScanResponse> findForAuction(UUID publicId) {
        Optional<Auction> auctionOpt = auctionRepository.findByPublicId(publicId);
        if (auctionOpt.isEmpty()) {
            return Optional.empty();
        }
        Long auctionId = auctionOpt.get().getId();

        Optional<AuctionParcelLayout> layoutOpt = layoutRepository.findByAuctionId(auctionId);
        Optional<AuctionParcelHeightMap> heightOpt = heightRepository.findByAuctionId(auctionId);
        if (layoutOpt.isEmpty() || heightOpt.isEmpty()) {
            return Optional.empty();
        }

        AuctionParcelLayout layout = layoutOpt.get();
        AuctionParcelHeightMap height = heightOpt.get();

        Base64.Encoder b64 = Base64.getEncoder();
        return Optional.of(new ParcelScanResponse(
                layout.getGridSize(),
                layout.getCellSizeMeters(),
                b64.encodeToString(layout.getCells()),
                b64.encodeToString(height.getCells()),
                height.getBaseMeters(),
                height.getStepMeters(),
                height.getScannedAt()
        ));
    }
}
```

- [ ] **Step 4: Add the `@ExceptionHandler` to `AuctionExceptionHandler.java`**

Find the block at line 105 that handles `AuctionNotFoundException` (it builds a `ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ...)`, sets a `code` property, returns it). Add an immediately-adjacent handler mirroring its shape:

```java
@ExceptionHandler(AuctionParcelScanNotFoundException.class)
public ProblemDetail onParcelScanNotFound(AuctionParcelScanNotFoundException e) {
    ProblemDetail pd = ProblemDetail.forStatusAndDetail(
            HttpStatus.NOT_FOUND, e.getMessage());
    pd.setTitle("Parcel scan not found");
    pd.setProperty("code", "PARCEL_SCAN_NOT_FOUND");
    return pd;
}
```

Add the import for `AuctionParcelScanNotFoundException` (same package as the file -- no import needed if both are in `com.slparcelauctions.backend.auction.exception`).

- [ ] **Step 5: Add the controller endpoint to `AuctionController.java`**

Inject `ParcelScanReadService parcelScanReadService` in the constructor (the class uses `@RequiredArgsConstructor` -- just declare the field). Add the method below `@GetMapping("/auctions/{publicId}/preview")` (around line 201):

```java
@GetMapping("/auctions/{publicId}/parcel-scan")
public ResponseEntity<ParcelScanResponse> parcelScan(@PathVariable UUID publicId) {
    return parcelScanReadService.findForAuction(publicId)
            .map(body -> ResponseEntity.ok()
                    .cacheControl(CacheControl.maxAge(Duration.ofDays(365))
                            .cachePublic()
                            .immutable())
                    .body(body))
            .orElseThrow(() -> new AuctionParcelScanNotFoundException(publicId));
}
```

Add the imports: `org.springframework.http.CacheControl`, `java.time.Duration`, `com.slparcelauctions.backend.auction.parcelscan.ParcelScanReadService`, `com.slparcelauctions.backend.auction.parcelscan.dto.ParcelScanResponse`, `com.slparcelauctions.backend.auction.exception.AuctionParcelScanNotFoundException`.

- [ ] **Step 6: Wire `permitAll` on the new endpoint in `SecurityConfig.java`**

Around line 196 (`requestMatchers(HttpMethod.GET, "/api/v1/auctions/*").permitAll()`), ADD a NEW matcher BEFORE it (more-specific first is the conventional ordering):

```java
.requestMatchers(HttpMethod.GET, "/api/v1/auctions/*/parcel-scan").permitAll()
```

The `*` matches one path segment, so `/auctions/{publicId}/parcel-scan` matches via `*/parcel-scan`. Mirrors the existing `/api/v1/auctions/*/bids` (line 161) and `/api/v1/auctions/*/reviews` (line 242) precedents.

- [ ] **Step 7: Write `AuctionParcelScanReadControllerTest.java`**

`@SpringBootTest + @AutoConfigureMockMvc + @ActiveProfiles("dev") + @TestPropertySource` mirroring `ParcelScanServiceTest.java` (the scanner test class shipped alongside the rasters; reads as the canonical fixture style for the parcel-scan area).

```java
package com.slparcelauctions.backend.auction.parcelscan;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.UUID;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = {
    "slpa.bot-task.enabled=false",
    "slpa.auction-end.enabled=false"
})
@Transactional
class AuctionParcelScanReadControllerTest {

    @Autowired MockMvc mvc;
    @Autowired AuctionRepository auctionRepo;
    @Autowired UserRepository userRepo;
    @Autowired AuctionParcelLayoutRepository layoutRepo;
    @Autowired AuctionParcelHeightMapRepository heightRepo;

    @Test
    void happyPath_returns200WithAllSevenFieldsAndCacheControl() throws Exception {
        Auction a = seedAuction();
        seedLayout(a);
        seedHeightMap(a);

        mvc.perform(get("/api/v1/auctions/{publicId}/parcel-scan", a.getPublicId()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control",
                        org.hamcrest.Matchers.containsString("max-age=31536000")))
                .andExpect(header().string("Cache-Control",
                        org.hamcrest.Matchers.containsString("public")))
                .andExpect(header().string("Cache-Control",
                        org.hamcrest.Matchers.containsString("immutable")))
                .andExpect(jsonPath("$.gridSize").value(64))
                .andExpect(jsonPath("$.cellSizeMeters").value(4))
                .andExpect(jsonPath("$.layoutCellsBase64").isString())
                .andExpect(jsonPath("$.heightCellsBase64").isString())
                .andExpect(jsonPath("$.baseMeters").value(22.0))
                .andExpect(jsonPath("$.stepMeters").value(0.5))
                .andExpect(jsonPath("$.scannedAt").isString());
    }

    @Test
    void unknownAuction_returns404() throws Exception {
        mvc.perform(get("/api/v1/auctions/{publicId}/parcel-scan", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PARCEL_SCAN_NOT_FOUND"));
    }

    @Test
    void missingLayout_returns404() throws Exception {
        Auction a = seedAuction();
        seedHeightMap(a); // no layout

        mvc.perform(get("/api/v1/auctions/{publicId}/parcel-scan", a.getPublicId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PARCEL_SCAN_NOT_FOUND"));
    }

    @Test
    void missingHeightMap_returns404() throws Exception {
        Auction a = seedAuction();
        seedLayout(a); // no heightmap

        mvc.perform(get("/api/v1/auctions/{publicId}/parcel-scan", a.getPublicId()))
                .andExpect(status().isNotFound());
    }

    @Test
    void publicAccess_no Auth_returns200() throws Exception {
        Auction a = seedAuction();
        seedLayout(a);
        seedHeightMap(a);

        // Same request as happyPath, no Authorization header anywhere.
        mvc.perform(get("/api/v1/auctions/{publicId}/parcel-scan", a.getPublicId()))
                .andExpect(status().isOk());
    }

    private Auction seedAuction() {
        // Build the User and Auction with the existing fixture pattern.
        // Look at ParcelScanServiceTest.newUser / newAuction in
        // backend/src/test/java/com/slparcelauctions/backend/auction/parcelscan/ParcelScanServiceTest.java
        // and copy verbatim. The pattern produces an Auction in a status
        // that has a publicId and an id - which status doesn't matter
        // for this endpoint (the controller doesn't gate on status).
        // ... fixture body lifted from ParcelScanServiceTest ...
        throw new UnsupportedOperationException("copy newAuction from ParcelScanServiceTest");
    }

    private void seedLayout(Auction a) {
        layoutRepo.save(AuctionParcelLayout.builder()
                .auction(a)
                .gridSize(64)
                .cellSizeMeters(4)
                .cells(new byte[512])
                .scannedAt(OffsetDateTime.now())
                .build());
    }

    private void seedHeightMap(Auction a) {
        heightRepo.save(AuctionParcelHeightMap.builder()
                .auction(a)
                .gridSize(64)
                .cellSizeMeters(4)
                .baseMeters(22.0f)
                .stepMeters(0.5f)
                .cells(new byte[4096])
                .scannedAt(OffsetDateTime.now())
                .build());
    }
}
```

NOTE: the `seedAuction()` placeholder above is the ONE bit you should look up from `ParcelScanServiceTest.java` in the same package (it has working `newUser` / `newAuction` helpers that build a valid Auction). Copy those helpers into this test class verbatim. The `throw new UnsupportedOperationException` is a placeholder to make the failure obvious if you forget. Also fix the typo `no Auth` in the test method name to `noAuth`.

- [ ] **Step 8: Run + fix any seedAuction()-helper-lifting issues**

```
cd backend; ./mvnw test -Dtest=AuctionParcelScanReadControllerTest
```

Expected: 5 tests pass.

- [ ] **Step 9: Commit + push**

```
git add backend/src/main/java/com/slparcelauctions/backend/auction/ \
        backend/src/main/java/com/slparcelauctions/backend/config/SecurityConfig.java \
        backend/src/test/java/com/slparcelauctions/backend/auction/parcelscan/
git commit -m "feat(parcel-map): GET /api/v1/auctions/{publicId}/parcel-scan"
git push
```

---

### Task 2: Frontend pure helpers — encoding + colors

Pure functions with no React, no DOM. TDD here is cheap and high-value: the bit-layout and gradient math are the parts most likely to drift.

**Files:**
- Create: `frontend/src/lib/parcelMap/encoding.ts`
- Create: `frontend/src/lib/parcelMap/encoding.test.ts`
- Create: `frontend/src/lib/parcelMap/colors.ts`
- Create: `frontend/src/lib/parcelMap/colors.test.ts`

- [ ] **Step 1: Write `encoding.test.ts` first**

```ts
import { describe, it, expect } from "vitest";
import {
  decodeBase64ToBytes,
  isCellInParcel,
  decodeElevationCell,
} from "./encoding";

describe("decodeBase64ToBytes", () => {
  it("decodes the empty string to a zero-length array", () => {
    expect(decodeBase64ToBytes("")).toEqual(new Uint8Array(0));
  });

  it("round-trips a 4-byte payload", () => {
    // 0x80 0x40 0x20 0x10 -> base64 "gEAgEA=="
    expect(decodeBase64ToBytes("gEAgEA==")).toEqual(
      new Uint8Array([0x80, 0x40, 0x20, 0x10]),
    );
  });

  it("decodes a 512-byte zero payload to 512 zero bytes", () => {
    // 4 chars of base64 per 3 input bytes, padded to multiple of 4
    const zeros512 = btoa(String.fromCharCode(...new Uint8Array(512)));
    expect(decodeBase64ToBytes(zeros512).length).toBe(512);
    expect(decodeBase64ToBytes(zeros512).every((b) => b === 0)).toBe(true);
  });
});

describe("isCellInParcel", () => {
  it("reads bit 7 (MSB) of byte 0 as cell (0, 0)", () => {
    const cells = new Uint8Array(512);
    cells[0] = 0x80; // 1000_0000
    expect(isCellInParcel(cells, 0, 0)).toBe(true);
    expect(isCellInParcel(cells, 0, 1)).toBe(false);
  });

  it("reads bit 0 (LSB) of byte 0 as cell (0, 7)", () => {
    const cells = new Uint8Array(512);
    cells[0] = 0x01;
    expect(isCellInParcel(cells, 0, 7)).toBe(true);
    expect(isCellInParcel(cells, 0, 6)).toBe(false);
  });

  it("indexes byte (row * 8) for col 0..7", () => {
    const cells = new Uint8Array(512);
    cells[1 * 8] = 0x80; // row 1, col 0
    expect(isCellInParcel(cells, 1, 0)).toBe(true);
  });

  it("returns false for the last cell when its byte is zero", () => {
    const cells = new Uint8Array(512);
    expect(isCellInParcel(cells, 63, 63)).toBe(false);
  });
});

describe("decodeElevationCell", () => {
  it("returns base when cell byte is 0", () => {
    const cells = new Uint8Array(4096);
    expect(decodeElevationCell(cells, 0, 0, 22.0, 0.5)).toBeCloseTo(22.0, 6);
  });

  it("returns base + 255 * step when cell byte is 0xFF", () => {
    const cells = new Uint8Array(4096);
    cells[10 * 64 + 5] = 0xff;
    expect(decodeElevationCell(cells, 10, 5, 22.0, 0.5)).toBeCloseTo(
      22.0 + 255 * 0.5,
      6,
    );
  });

  it("treats the cell byte as unsigned (0xFF, not -1)", () => {
    const cells = new Uint8Array(4096);
    cells[0] = 0xff;
    expect(decodeElevationCell(cells, 0, 0, 0, 1)).toBeCloseTo(255, 6);
  });
});
```

- [ ] **Step 2: Run the test, expect failures**

```
cd frontend; npm test -- --run src/lib/parcelMap/encoding.test.ts
```

Expected: all tests fail (no implementation).

- [ ] **Step 3: Write `encoding.ts`**

```ts
/**
 * Pure binary decoders for the parcel-scan rasters returned by the
 * GET /api/v1/auctions/{publicId}/parcel-scan endpoint. Mirrors the
 * backend's encoding contract (see AuctionParcelLayout.java and
 * AuctionParcelHeightMap.java Javadoc). 64x64 cells, 4 m per cell.
 *
 * Layout bitmap: 1 bit per cell, MSB-first within each byte, row-major
 * SW-first (row 0 = south, col 0 = west). 4096 bits = 512 bytes.
 *
 * Heightmap: 4096 uint8s, same row-major SW-first. Decode:
 *   elevationMeters = baseMeters + (cells[i] & 0xFF) * stepMeters
 */

/** Decode a base64 string to a Uint8Array using the runtime's native atob. */
export function decodeBase64ToBytes(s: string): Uint8Array {
  if (s.length === 0) return new Uint8Array(0);
  const binary = atob(s);
  const out = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) {
    out[i] = binary.charCodeAt(i);
  }
  return out;
}

/**
 * Returns true iff the cell at (row, col) belongs to the listed parcel.
 * row and col are 0..63 inclusive. The bitmap is MSB-first within each
 * byte; bit index = row*64 + col; byte index = floor(bitIndex / 8);
 * bit-in-byte position (from MSB) = 7 - (bitIndex % 8).
 */
export function isCellInParcel(
  layoutCells: Uint8Array,
  row: number,
  col: number,
): boolean {
  const bitIndex = row * 64 + col;
  const byteIndex = bitIndex >> 3;
  const bitInByte = 7 - (bitIndex & 7);
  return ((layoutCells[byteIndex] >> bitInByte) & 1) === 1;
}

/**
 * Decode the elevation at (row, col) in meters above sea level.
 * row and col are 0..63 inclusive.
 */
export function decodeElevationCell(
  heightCells: Uint8Array,
  row: number,
  col: number,
  baseMeters: number,
  stepMeters: number,
): number {
  return baseMeters + (heightCells[row * 64 + col] & 0xff) * stepMeters;
}
```

- [ ] **Step 4: Run encoding tests, expect green**

```
cd frontend; npm test -- --run src/lib/parcelMap/encoding.test.ts
```

- [ ] **Step 5: Write `colors.test.ts`**

```ts
import { describe, it, expect } from "vitest";
import { gradientColor, dimOutside, MAP_COLORS } from "./colors";

describe("gradientColor", () => {
  it("returns solid green when delta <= 0", () => {
    expect(gradientColor(-2)).toEqual(MAP_COLORS.green);
    expect(gradientColor(0)).toEqual(MAP_COLORS.green);
  });

  it("returns yellow at delta = 4 (terraforming limit)", () => {
    expect(gradientColor(4)).toEqual(MAP_COLORS.yellow);
  });

  it("returns red at delta = 8 (un-flattenable spread)", () => {
    expect(gradientColor(8)).toEqual(MAP_COLORS.red);
  });

  it("returns solid red when delta > 8", () => {
    expect(gradientColor(12)).toEqual(MAP_COLORS.red);
  });

  it("lerps green->yellow at delta = 2 (midpoint of first segment)", () => {
    const g = MAP_COLORS.green;
    const y = MAP_COLORS.yellow;
    const mid = gradientColor(2);
    expect(mid.r).toBeCloseTo((g.r + y.r) / 2, 0);
    expect(mid.g).toBeCloseTo((g.g + y.g) / 2, 0);
    expect(mid.b).toBeCloseTo((g.b + y.b) / 2, 0);
  });

  it("lerps yellow->red at delta = 6 (midpoint of second segment)", () => {
    const y = MAP_COLORS.yellow;
    const r = MAP_COLORS.red;
    const mid = gradientColor(6);
    expect(mid.r).toBeCloseTo((y.r + r.r) / 2, 0);
    expect(mid.g).toBeCloseTo((y.g + r.g) / 2, 0);
    expect(mid.b).toBeCloseTo((y.b + r.b) / 2, 0);
  });
});

describe("dimOutside", () => {
  it("lerps a given rgb 60% toward neutral gray (120, 120, 120)", () => {
    // Green (34, 197, 94) at 60% toward gray (120, 120, 120):
    // result = green * 0.4 + gray * 0.6
    const dimmed = dimOutside({ r: 34, g: 197, b: 94 });
    expect(dimmed.r).toBe(Math.round(34 * 0.4 + 120 * 0.6));
    expect(dimmed.g).toBe(Math.round(197 * 0.4 + 120 * 0.6));
    expect(dimmed.b).toBe(Math.round(94 * 0.4 + 120 * 0.6));
  });

  it("returns gray for any gray input (no-op)", () => {
    const dimmed = dimOutside({ r: 120, g: 120, b: 120 });
    expect(dimmed).toEqual({ r: 120, g: 120, b: 120 });
  });
});
```

- [ ] **Step 6: Run the test, expect failures**

```
cd frontend; npm test -- --run src/lib/parcelMap/colors.test.ts
```

- [ ] **Step 7: Write `colors.ts`**

```ts
/**
 * Pure gradient + dim math for the parcel map. No DOM, no React.
 *
 * Gradient anchors are pinned to SL terraforming semantics:
 *   delta = 0         green   - the parcel's lowest cell (the reference)
 *   delta = 4 m       yellow  - the per-parcel raise/lower limit
 *   delta = 8 m       red     - un-flattenable spread (>8 m can't be levelled)
 *   delta > 8 m       red     - saturated
 *
 * Linear lerp between anchors. Outside-the-parcel cells are dimmed toward
 * neutral gray (60% toward gray) so the parcel pops visually.
 *
 * Colors are rgb triples (not hex) to keep the no-hex-colors verify guard
 * happy. The RGB values reference Tailwind's green-500 / yellow-500 / red-500
 * / cyan-400 for visual consistency with the rest of the app.
 */
export interface Rgb {
  r: number;
  g: number;
  b: number;
}

export const MAP_COLORS = {
  green: { r: 34, g: 197, b: 94 }, // Tailwind green-500
  yellow: { r: 234, g: 179, b: 8 }, // Tailwind yellow-500
  red: { r: 239, g: 68, b: 68 }, // Tailwind red-500
  cyan: { r: 34, g: 211, b: 238 }, // Tailwind cyan-400 (boundary outline)
  neutral: { r: 120, g: 120, b: 120 }, // dim target
} as const;

/** Per-cell color from an elevation delta (cell elevation - parcel min). */
export function gradientColor(deltaMeters: number): Rgb {
  if (deltaMeters <= 0) return { ...MAP_COLORS.green };
  if (deltaMeters >= 8) return { ...MAP_COLORS.red };
  if (deltaMeters <= 4) {
    const t = deltaMeters / 4;
    return lerp(MAP_COLORS.green, MAP_COLORS.yellow, t);
  }
  const t = (deltaMeters - 4) / 4;
  return lerp(MAP_COLORS.yellow, MAP_COLORS.red, t);
}

/** Dim a per-cell color 60% toward neutral gray. */
export function dimOutside(color: Rgb): Rgb {
  return lerp(color, MAP_COLORS.neutral, 0.6);
}

function lerp(a: Rgb, b: Rgb, t: number): Rgb {
  return {
    r: Math.round(a.r + (b.r - a.r) * t),
    g: Math.round(a.g + (b.g - a.g) * t),
    b: Math.round(a.b + (b.b - a.b) * t),
  };
}
```

- [ ] **Step 8: Run colors tests, expect green**

```
cd frontend; npm test -- --run src/lib/parcelMap/colors.test.ts
```

- [ ] **Step 9: Full local guard check (npm run build + verify catch hex / typing issues)**

```
cd frontend; npm run build
cd frontend; npm run verify
```

Both must pass. If `npm run verify` flags the rgb literals as hex (they aren't, but the regex might be loose), lift the constants into CSS custom properties on `globals.css` and read via `getComputedStyle` -- but try the rgb-literal path first since it's simpler.

- [ ] **Step 10: Commit + push**

```
git add frontend/src/lib/parcelMap/
git commit -m "feat(parcel-map): encoding + color helpers"
git push
```

---

### Task 3: Frontend types + API fn + React Query hook

Glue between the backend endpoint and the React component.

**Files:**
- Modify: `frontend/src/types/auction.ts`
- Create: `frontend/src/lib/api/parcelScan.ts`
- Create: `frontend/src/hooks/useParcelScan.ts`
- Create: `frontend/src/hooks/useParcelScan.test.tsx`

- [ ] **Step 1: Add `ParcelScanResponse` to `frontend/src/types/auction.ts`**

Place it near the other auction-response interfaces (search for `PublicAuctionResponse` to find the right region):

```ts
export interface ParcelScanResponse {
  gridSize: number;
  cellSizeMeters: number;
  layoutCellsBase64: string;
  heightCellsBase64: string;
  baseMeters: number;
  stepMeters: number;
  scannedAt: string;
}
```

- [ ] **Step 2: Write `frontend/src/lib/api/parcelScan.ts`**

Follow the sibling pattern from `frontend/src/lib/api/auctions.ts` -- read it first to confirm the `apiUrl` helper and error-handling conventions:

```ts
import { apiUrl } from "@/lib/api/url";
import type { ParcelScanResponse } from "@/types/auction";

/**
 * Fetch the parcel-scan rasters for an auction. Returns null on 404
 * (no scan rows yet, scan failed, or parcelScanIncluded=false on the
 * auction). Throws on any other non-2xx response so React Query
 * surfaces it as an error.
 *
 * Response is cache-friendly (Cache-Control: public, immutable,
 * max-age=365d) so a re-mount within the page session is free.
 */
export async function getParcelScan(
  publicId: string,
): Promise<ParcelScanResponse | null> {
  const r = await fetch(apiUrl(`/api/v1/auctions/${publicId}/parcel-scan`));
  if (r.status === 404) return null;
  if (!r.ok) {
    throw new Error(`getParcelScan ${publicId} failed: ${r.status}`);
  }
  return (await r.json()) as ParcelScanResponse;
}
```

- [ ] **Step 3: Write `frontend/src/hooks/useParcelScan.ts`**

```ts
"use client";

import { useQuery } from "@tanstack/react-query";
import { getParcelScan } from "@/lib/api/parcelScan";
import type { ParcelScanResponse } from "@/types/auction";

/**
 * Query-key factory. Scoped by auction publicId so two open auction
 * detail pages don't collide in the cache.
 */
export function parcelScanKey(publicId: string): readonly unknown[] {
  return ["auction", publicId, "parcel-scan"] as const;
}

/**
 * React Query wrapper around {@link getParcelScan}. Rasters are immutable
 * per-auction per the parcel-scanner spec, so {@code staleTime} and
 * {@code gcTime} are set to Infinity - a re-mount within the same React
 * Query cache lifetime never re-fetches.
 *
 * {@code data: null} signals "no scan available" (404 from the endpoint);
 * the {@code ParcelMap} component branches on that to render nothing.
 */
export function useParcelScan(publicId: string) {
  return useQuery<ParcelScanResponse | null>({
    queryKey: parcelScanKey(publicId),
    queryFn: () => getParcelScan(publicId),
    staleTime: Infinity,
    gcTime: Infinity,
  });
}
```

- [ ] **Step 4: Write `frontend/src/hooks/useParcelScan.test.tsx`**

Follow the existing hook-test pattern. Search `frontend/src/hooks/useActiveListings.test.tsx` (or any sibling) for the right wrapper (likely a `QueryClientProvider` factory). Test bodies:

```tsx
import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ReactNode } from "react";

import { useParcelScan } from "./useParcelScan";
import * as parcelScanApi from "@/lib/api/parcelScan";

function wrapper({ children }: { children: ReactNode }) {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return <QueryClientProvider client={qc}>{children}</QueryClientProvider>;
}

const publicId = "11111111-1111-1111-1111-111111111111";

describe("useParcelScan", () => {
  beforeEach(() => vi.restoreAllMocks());

  it("returns the scan payload on 200", async () => {
    const payload = {
      gridSize: 64,
      cellSizeMeters: 4,
      layoutCellsBase64: "AAAA",
      heightCellsBase64: "BBBB",
      baseMeters: 22.5,
      stepMeters: 0.5,
      scannedAt: "2026-05-24T04:57:31Z",
    };
    vi.spyOn(parcelScanApi, "getParcelScan").mockResolvedValue(payload);

    const { result } = renderHook(() => useParcelScan(publicId), { wrapper });
    await waitFor(() => expect(result.current.isPending).toBe(false));
    expect(result.current.data).toEqual(payload);
    expect(result.current.isError).toBe(false);
  });

  it("returns data: null on 404", async () => {
    vi.spyOn(parcelScanApi, "getParcelScan").mockResolvedValue(null);

    const { result } = renderHook(() => useParcelScan(publicId), { wrapper });
    await waitFor(() => expect(result.current.isPending).toBe(false));
    expect(result.current.data).toBeNull();
    expect(result.current.isError).toBe(false);
  });

  it("surfaces isError on non-404 failures", async () => {
    vi.spyOn(parcelScanApi, "getParcelScan").mockRejectedValue(
      new Error("500 boom"),
    );

    const { result } = renderHook(() => useParcelScan(publicId), { wrapper });
    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(result.current.data).toBeUndefined();
  });
});
```

- [ ] **Step 5: Run + verify**

```
cd frontend; npm test -- --run src/hooks/useParcelScan.test.tsx
cd frontend; npm run build
```

- [ ] **Step 6: Commit + push**

```
git add frontend/src/types/auction.ts \
        frontend/src/lib/api/parcelScan.ts \
        frontend/src/hooks/useParcelScan.ts \
        frontend/src/hooks/useParcelScan.test.tsx
git commit -m "feat(parcel-map): useParcelScan hook + API fn"
git push
```

---

### Task 4: `ParcelMap` component + tests

The big one. Canvas paint + tooltip + keyboard.

**Files:**
- Create: `frontend/src/components/auction/ParcelMap.tsx`
- Create: `frontend/src/components/auction/ParcelMap.test.tsx`

READ `frontend/AGENTS.md` before writing this. The repo runs Next.js 16 / React 19; check if `useEffect` patterns differ from older Next versions you may know.

- [ ] **Step 1: Write `ParcelMap.tsx`**

```tsx
"use client";

import { useEffect, useMemo, useRef, useState } from "react";

import { cn } from "@/lib/cn";
import { useParcelScan } from "@/hooks/useParcelScan";
import {
  decodeBase64ToBytes,
  decodeElevationCell,
  isCellInParcel,
} from "@/lib/parcelMap/encoding";
import {
  MAP_COLORS,
  dimOutside,
  gradientColor,
  type Rgb,
} from "@/lib/parcelMap/colors";

const GRID = 64;
const CELL_PX = 4;
const CANVAS_PX = GRID * CELL_PX; // 256

interface Props {
  publicId: string;
  className?: string;
}

interface Stats {
  parcelMin: number;
  parcelMax: number;
  parcelCellCount: number;
}

interface CellInfo {
  row: number;
  col: number;
  elevM: number;
  inParcel: boolean;
}

/**
 * Combined parcel + region heightmap canvas. Renders nothing if the auction
 * has no scan rows (404 from the read endpoint). Spec:
 * docs/superpowers/specs/2026-05-24-parcel-map-frontend-design.md.
 */
export function ParcelMap({ publicId, className }: Props) {
  const { data, isPending, isError } = useParcelScan(publicId);
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const [hover, setHover] = useState<CellInfo | null>(null);
  const [focusCell, setFocusCell] = useState<{ row: number; col: number } | null>(null);

  const decoded = useMemo(() => {
    if (!data) return null;
    return {
      layoutCells: decodeBase64ToBytes(data.layoutCellsBase64),
      heightCells: decodeBase64ToBytes(data.heightCellsBase64),
      baseMeters: data.baseMeters,
      stepMeters: data.stepMeters,
    };
  }, [data]);

  const stats: Stats | null = useMemo(() => {
    if (!decoded) return null;
    let min = Number.POSITIVE_INFINITY;
    let max = Number.NEGATIVE_INFINITY;
    let count = 0;
    for (let row = 0; row < GRID; row++) {
      for (let col = 0; col < GRID; col++) {
        if (!isCellInParcel(decoded.layoutCells, row, col)) continue;
        const e = decodeElevationCell(
          decoded.heightCells, row, col,
          decoded.baseMeters, decoded.stepMeters,
        );
        if (e < min) min = e;
        if (e > max) max = e;
        count++;
      }
    }
    if (count === 0) {
      // Defensive: a layout with no cells set shouldn't happen in practice,
      // but a zero parcelMin would NaN-propagate elsewhere. Bail to null.
      return null;
    }
    return { parcelMin: min, parcelMax: max, parcelCellCount: count };
  }, [decoded]);

  // Paint the canvas whenever decoded data or focus cell changes.
  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas || !decoded || !stats) return;
    const ctx = canvas.getContext("2d");
    if (!ctx) return;

    paintCells(ctx, decoded, stats);
    paintBoundary(ctx, decoded.layoutCells);
    if (focusCell) paintFocusCursor(ctx, focusCell.row, focusCell.col);
  }, [decoded, stats, focusCell]);

  if (isPending) {
    return (
      <div
        className={cn(
          "aspect-square w-full max-w-[320px] animate-pulse rounded-md bg-bg-subtle",
          className,
        )}
        aria-hidden="true"
      />
    );
  }

  if (isError || !data || !decoded || !stats) {
    return null;
  }

  const liveAnnouncement = announcementFor(hover ?? cellInfoFor(focusCell, decoded, stats));

  return (
    <figure className={cn("relative flex flex-col gap-2", className)}>
      <canvas
        ref={canvasRef}
        width={CANVAS_PX}
        height={CANVAS_PX}
        tabIndex={0}
        role="application"
        aria-label="Region parcel and elevation map, 64 by 64 cells"
        className="aspect-square w-full max-w-[320px] rounded-md border border-border-subtle [image-rendering:pixelated]"
        onMouseMove={(e) => setHover(cellInfoForEvent(e, decoded, stats))}
        onMouseLeave={() => setHover(null)}
        onKeyDown={(e) => {
          const next = moveFocus(focusCell, e.key);
          if (next) {
            e.preventDefault();
            setFocusCell(next);
          }
        }}
      />
      <figcaption className="text-xs text-fg-muted">
        Parcel covers {stats.parcelCellCount} of 4096 cells. Elevation
        range {stats.parcelMin.toFixed(1)} m to {stats.parcelMax.toFixed(1)} m.
      </figcaption>
      {hover && <ParcelMapTooltip {...hover} />}
      <div role="status" aria-live="polite" className="sr-only">
        {liveAnnouncement}
      </div>
    </figure>
  );
}

// -- internals ----------------------------------------------------------

interface Decoded {
  layoutCells: Uint8Array;
  heightCells: Uint8Array;
  baseMeters: number;
  stepMeters: number;
}

function paintCells(
  ctx: CanvasRenderingContext2D,
  decoded: Decoded,
  stats: Stats,
) {
  const img = ctx.createImageData(CANVAS_PX, CANVAS_PX);
  for (let row = 0; row < GRID; row++) {
    for (let col = 0; col < GRID; col++) {
      const elev = decodeElevationCell(
        decoded.heightCells, row, col,
        decoded.baseMeters, decoded.stepMeters,
      );
      let color: Rgb = gradientColor(elev - stats.parcelMin);
      if (!isCellInParcel(decoded.layoutCells, row, col)) {
        color = dimOutside(color);
      }
      // Paint a CELL_PX x CELL_PX block. SW-first means canvas y=0 is
      // the visual TOP of the canvas, but row 0 is the SOUTH edge. Flip
      // when writing: canvasY = (GRID - 1 - row) * CELL_PX.
      const canvasY = (GRID - 1 - row) * CELL_PX;
      const canvasX = col * CELL_PX;
      for (let dy = 0; dy < CELL_PX; dy++) {
        for (let dx = 0; dx < CELL_PX; dx++) {
          const idx = ((canvasY + dy) * CANVAS_PX + (canvasX + dx)) * 4;
          img.data[idx] = color.r;
          img.data[idx + 1] = color.g;
          img.data[idx + 2] = color.b;
          img.data[idx + 3] = 255;
        }
      }
    }
  }
  ctx.putImageData(img, 0, 0);
}

function paintBoundary(
  ctx: CanvasRenderingContext2D,
  layoutCells: Uint8Array,
) {
  ctx.fillStyle = `rgb(${MAP_COLORS.cyan.r}, ${MAP_COLORS.cyan.g}, ${MAP_COLORS.cyan.b})`;
  for (let row = 0; row < GRID; row++) {
    for (let col = 0; col < GRID; col++) {
      if (!isCellInParcel(layoutCells, row, col)) continue;
      const canvasY = (GRID - 1 - row) * CELL_PX;
      const canvasX = col * CELL_PX;

      // North neighbor (row+1): edge along the cell's TOP in canvas space
      if (row === GRID - 1 || !isCellInParcel(layoutCells, row + 1, col)) {
        ctx.fillRect(canvasX, canvasY, CELL_PX, 1);
      }
      // South neighbor (row-1): edge along the cell's BOTTOM in canvas space
      if (row === 0 || !isCellInParcel(layoutCells, row - 1, col)) {
        ctx.fillRect(canvasX, canvasY + CELL_PX - 1, CELL_PX, 1);
      }
      // West neighbor (col-1): edge along the cell's LEFT
      if (col === 0 || !isCellInParcel(layoutCells, row, col - 1)) {
        ctx.fillRect(canvasX, canvasY, 1, CELL_PX);
      }
      // East neighbor (col+1): edge along the cell's RIGHT
      if (col === GRID - 1 || !isCellInParcel(layoutCells, row, col + 1)) {
        ctx.fillRect(canvasX + CELL_PX - 1, canvasY, 1, CELL_PX);
      }
    }
  }
}

function paintFocusCursor(
  ctx: CanvasRenderingContext2D,
  row: number,
  col: number,
) {
  ctx.strokeStyle = "rgb(255, 255, 255)";
  ctx.lineWidth = 1;
  const canvasY = (GRID - 1 - row) * CELL_PX;
  const canvasX = col * CELL_PX;
  // strokeRect centers the stroke on the path. Inset by 0.5 px so the
  // 1-px line lands fully inside the cell.
  ctx.strokeRect(canvasX + 0.5, canvasY + 0.5, CELL_PX - 1, CELL_PX - 1);
}

function cellInfoForEvent(
  e: React.MouseEvent<HTMLCanvasElement>,
  decoded: Decoded,
  stats: Stats,
): CellInfo {
  const rect = e.currentTarget.getBoundingClientRect();
  const xPx = e.clientX - rect.left;
  const yPx = e.clientY - rect.top;
  // CSS may have scaled the canvas; use the displayed bounding-rect
  // dimensions, NOT canvas.width / canvas.height.
  const col = clamp(Math.floor((xPx / rect.width) * GRID), 0, GRID - 1);
  const visualRow = clamp(Math.floor((yPx / rect.height) * GRID), 0, GRID - 1);
  // Flip back from canvas-y (top-down) to row (SW-first, north).
  const row = GRID - 1 - visualRow;
  return cellInfoFor({ row, col }, decoded, stats)!;
}

function cellInfoFor(
  cell: { row: number; col: number } | null,
  decoded: Decoded,
  _stats: Stats,
): CellInfo | null {
  if (!cell) return null;
  const elevM = decodeElevationCell(
    decoded.heightCells, cell.row, cell.col,
    decoded.baseMeters, decoded.stepMeters,
  );
  const inParcel = isCellInParcel(decoded.layoutCells, cell.row, cell.col);
  return { row: cell.row, col: cell.col, elevM, inParcel };
}

function announcementFor(info: CellInfo | null): string {
  if (!info) return "";
  const where = info.inParcel ? "in parcel" : "outside parcel";
  return `Cell ${info.row}, ${info.col}. Elevation ${info.elevM.toFixed(1)} meters. ${where}.`;
}

function moveFocus(
  current: { row: number; col: number } | null,
  key: string,
): { row: number; col: number } | null {
  const base = current ?? { row: 32, col: 32 };
  switch (key) {
    case "ArrowUp": return { ...base, row: clamp(base.row + 1, 0, GRID - 1) };
    case "ArrowDown": return { ...base, row: clamp(base.row - 1, 0, GRID - 1) };
    case "ArrowRight": return { ...base, col: clamp(base.col + 1, 0, GRID - 1) };
    case "ArrowLeft": return { ...base, col: clamp(base.col - 1, 0, GRID - 1) };
    default: return null;
  }
}

function clamp(v: number, lo: number, hi: number): number {
  return Math.max(lo, Math.min(hi, v));
}

function ParcelMapTooltip({ row, col, elevM, inParcel }: CellInfo) {
  return (
    <div
      className="absolute left-2 top-2 pointer-events-none rounded-md border border-border-subtle bg-surface-raised px-2 py-1 text-xs text-fg shadow"
      role="presentation"
    >
      <div>Cell ({row}, {col})</div>
      <div>Elevation {elevM.toFixed(1)} m</div>
      <div>{inParcel ? "In parcel" : "Outside parcel"}</div>
    </div>
  );
}
```

NOTE on the SW-flip: the parcel-scanner spec defines row 0 as the SOUTH edge of the region. A canvas's y=0 is its TOP. The component flips `canvasY = (GRID - 1 - row) * CELL_PX` so the rendered image has north pointing UP on screen, which is what a human reader expects. The hover and keyboard math flips back symmetrically.

- [ ] **Step 2: Write `ParcelMap.test.tsx`**

```tsx
import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";

import { ParcelMap } from "./ParcelMap";
import * as parcelScanApi from "@/lib/api/parcelScan";

function wrap(ui: React.ReactElement) {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(<QueryClientProvider client={qc}>{ui}</QueryClientProvider>);
}

const publicId = "22222222-2222-2222-2222-222222222222";

function payloadWithParcelAt(row: number, col: number) {
  const layoutBytes = new Uint8Array(512);
  const bitIndex = row * 64 + col;
  layoutBytes[bitIndex >> 3] |= 1 << (7 - (bitIndex & 7));
  const heightBytes = new Uint8Array(4096); // all zero -> elev = base for every cell
  const toBase64 = (b: Uint8Array) =>
    btoa(String.fromCharCode(...b));
  return {
    gridSize: 64,
    cellSizeMeters: 4,
    layoutCellsBase64: toBase64(layoutBytes),
    heightCellsBase64: toBase64(heightBytes),
    baseMeters: 22.0,
    stepMeters: 0.5,
    scannedAt: "2026-05-24T04:57:31Z",
  };
}

describe("ParcelMap", () => {
  beforeEach(() => vi.restoreAllMocks());

  it("renders the skeleton while pending", () => {
    vi.spyOn(parcelScanApi, "getParcelScan").mockImplementation(
      () => new Promise(() => {}),
    );
    const { container } = wrap(<ParcelMap publicId={publicId} />);
    expect(container.querySelector(".animate-pulse")).not.toBeNull();
  });

  it("returns null when the endpoint returns 404", async () => {
    vi.spyOn(parcelScanApi, "getParcelScan").mockResolvedValue(null);
    const { container } = wrap(<ParcelMap publicId={publicId} />);
    // Wait one microtask for the query to resolve.
    await new Promise((r) => setTimeout(r, 0));
    expect(container.querySelector("canvas")).toBeNull();
    expect(container.querySelector("figcaption")).toBeNull();
  });

  it("renders the canvas + figcaption summary on loaded data", async () => {
    vi.spyOn(parcelScanApi, "getParcelScan").mockResolvedValue(
      payloadWithParcelAt(10, 10),
    );
    wrap(<ParcelMap publicId={publicId} />);
    expect(await screen.findByRole("application")).toBeInTheDocument();
    const fig = screen.getByText(/Parcel covers 1 of 4096 cells/i);
    expect(fig).toBeInTheDocument();
    expect(fig.textContent).toMatch(/Elevation range 22\.0 m to 22\.0 m/);
  });

  it("ArrowRight from initial focus (32, 32) moves to (32, 33) and updates the aria-live region", async () => {
    vi.spyOn(parcelScanApi, "getParcelScan").mockResolvedValue(
      payloadWithParcelAt(32, 33),
    );
    wrap(<ParcelMap publicId={publicId} />);
    const canvas = await screen.findByRole("application");
    canvas.focus();
    await userEvent.keyboard("{ArrowRight}");
    // Live region content = "Cell 32, 33. Elevation 22.0 meters. in parcel."
    const live = screen
      .getAllByRole("status")
      .find((n) => n.getAttribute("aria-live") === "polite");
    expect(live?.textContent).toMatch(/Cell 32, 33/);
    expect(live?.textContent).toMatch(/in parcel/);
  });
});
```

Canvas test mocking: the codebase may already have a Vitest canvas mock setup; check `frontend/vitest.setup.ts` (or wherever the test setup lives) for an existing `HTMLCanvasElement.prototype.getContext` shim. If there isn't one, add a minimal stub in the test file's `beforeEach`:

```ts
beforeEach(() => {
  vi.spyOn(HTMLCanvasElement.prototype, "getContext").mockReturnValue({
    createImageData: () => ({ data: new Uint8ClampedArray(CANVAS_PX * CANVAS_PX * 4) }),
    putImageData: vi.fn(),
    fillRect: vi.fn(),
    strokeRect: vi.fn(),
    fillStyle: "",
    strokeStyle: "",
    lineWidth: 1,
  } as unknown as CanvasRenderingContext2D);
});
```

- [ ] **Step 3: Run + verify**

```
cd frontend; npm test -- --run src/components/auction/ParcelMap.test.tsx
cd frontend; npm run build
cd frontend; npm run verify
```

All three must pass. If `npm run verify` flags the `rgb(34, 197, 94)` string in `colors.ts` as a hex color (it shouldn't -- the guard pattern targets `#xxxxxx`), no action. If it flags the inline `rgb(...)` template literal in `paintBoundary`, lift it to a precomputed string constant.

- [ ] **Step 4: Commit + push**

```
git add frontend/src/components/auction/ParcelMap.tsx \
        frontend/src/components/auction/ParcelMap.test.tsx
git commit -m "feat(parcel-map): ParcelMap component"
git push
```

---

### Task 5: Wire into `ParcelInfoPanel` + Postman + README + DEFERRED_WORK + PR

**Files:**
- Modify: `frontend/src/components/auction/ParcelInfoPanel.tsx`
- Modify: `frontend/src/components/auction/ParcelInfoPanel.test.tsx`
- Modify: SLPA Postman collection (cloud)
- Modify: `README.md`
- Modify: `docs/implementation/DEFERRED_WORK.md`

- [ ] **Step 1: Mount `ParcelMap` in `ParcelInfoPanel.tsx`**

Read `frontend/src/components/auction/ParcelInfoPanel.tsx`. After the existing parcel-detail content (look for the closing tag of the last sub-section before the panel's outer-most closing tag), add:

```tsx
<ParcelMap publicId={auction.publicId} />
```

Add the import: `import { ParcelMap } from "@/components/auction/ParcelMap";`. The component returns `null` on missing data, so no conditional wrapping needed.

- [ ] **Step 2: Add a test case to `ParcelInfoPanel.test.tsx`**

Add one new test asserting `ParcelMap` is mounted. The simplest assertion: when the panel renders, the canvas (`role="application"`) or the skeleton (`.animate-pulse`) is in the DOM. Since `ParcelMap` makes a live React Query call, the test wrapper needs a `QueryClientProvider` -- if the existing test doesn't have one, mock `useParcelScan` instead:

```tsx
import * as parcelScanHook from "@/hooks/useParcelScan";

it("mounts ParcelMap with the auction's publicId", async () => {
  vi.spyOn(parcelScanHook, "useParcelScan").mockReturnValue({
    data: null,
    isPending: false,
    isError: false,
  } as ReturnType<typeof parcelScanHook.useParcelScan>);
  render(<ParcelInfoPanel auction={mockAuction()} />);
  expect(parcelScanHook.useParcelScan).toHaveBeenCalledWith(mockAuction().publicId);
});
```

(`mockAuction` is presumably the existing test fixture in the same test file; if it has a different name, look in the file and use that.)

- [ ] **Step 3: Run + verify**

```
cd frontend; npm test -- --run src/components/auction/ParcelInfoPanel.test.tsx
cd frontend; npm run build
cd frontend; npm run verify
```

- [ ] **Step 4: Postman**

Use the `mcp__postman__*` tools. Collection id `8070328-cf3e1190-cb71-4af3-8e2e-70c82dfee288`, workspace `SLPA`. Add to the "Parcel & Listings" folder (or wherever the auction-detail requests live):

- Request "Get parcel scan" -- `GET {{baseUrl}}/api/v1/auctions/{{auctionId}}/parcel-scan`. No auth headers (public endpoint). Test:
  ```js
  pm.test("status 200", () => pm.response.to.have.status(200));
  const b = pm.response.json();
  pm.test("has 7 fields", () => {
    ["gridSize", "cellSizeMeters", "layoutCellsBase64",
     "heightCellsBase64", "baseMeters", "stepMeters", "scannedAt"]
      .forEach((k) => pm.expect(b).to.have.property(k));
  });
  ```
- Request "Get parcel scan (no scan, expect 404)" -- same URL but pointed at a `{{auctionIdWithoutScan}}` env var (or hardcode a known auction id with no scan rows). Test asserts status 404.

If the `mcp__postman__*` tools error out OR risk corrupting the collection, leave Postman alone and note "Postman update deferred" in your report. Postman is not worth breaking.

- [ ] **Step 5: README sweep**

Read root `README.md`. Add a short note in the auction / Epic 04 section (or wherever the parcel scanner is described) that the visitor-facing detail page now renders the combined parcel + region heightmap inside `ParcelInfoPanel`, with hover + keyboard cell-level details. Pointer to the new spec: `docs/superpowers/specs/2026-05-24-parcel-map-frontend-design.md`. No em-dashes, no emojis.

- [ ] **Step 6: Resolve the DEFERRED_WORK entry**

Read `docs/implementation/DEFERRED_WORK.md`. Find the entry `### Parcel scanner: frontend raster rendering` (added in the scanner spec's Task 8). Per memory `feedback_ledgers_immutable`, DO NOT delete or mutate the entry. Append a `-- RESOLVED (2026-05-24)` suffix to the heading AND a Resolution paragraph in the body. Mirror the pattern used for the existing `### Parcel layout map generation -- RESOLVED (2026-05-23)` entry that the scanner work added.

Suggested resolution paragraph:

```
- **Resolution (2026-05-24):** Implemented per spec
  `docs/superpowers/specs/2026-05-24-parcel-map-frontend-design.md`.
  Frontend `ParcelMap` component renders both rasters as a single combined
  canvas inside `ParcelInfoPanel`, backed by a new public-no-auth
  `GET /api/v1/auctions/{publicId}/parcel-scan` endpoint.
```

The "Parcel scanner: per-cell admin GET endpoint" entry STAYS open -- this work shipped the public read endpoint only, not an admin-specific one.

- [ ] **Step 7: Full suites green check**

```
cd backend; ./mvnw test
cd frontend; npm test -- --run
cd frontend; npm run build
cd frontend; npm run verify
```

Backend known-flaky: if 5+ unrelated `@SpringBootTest` classes ERROR with `FATAL: sorry, too many clients already` Postgres pool exhaustion, that's environmental. Re-run any errored class in isolation to confirm green; document the flake in your report. Any genuine assertion FAILURE blocks the PR.

- [ ] **Step 8: Commit docs**

```
git add README.md docs/implementation/DEFERRED_WORK.md \
        frontend/src/components/auction/ParcelInfoPanel.tsx \
        frontend/src/components/auction/ParcelInfoPanel.test.tsx
git commit -m "feat(parcel-map): wire ParcelMap into ParcelInfoPanel + docs"
git push
```

Do NOT `git add -A` (the repo has untracked scratch files `bid-err.txt`, `docs/cacheditems-problems.md`, `docs/cacheditems.md`, and `.scratch/` contents that must NOT be committed).

- [ ] **Step 9: Open PR into dev and merge it**

```
gh pr create --base dev --head feat/parcel-map-frontend \
  --title "feat(auction): parcel map on auction detail page" \
  --body "$(cat <<'EOF'
## Summary
Implements the visitor-facing parcel map per docs/superpowers/specs/2026-05-24-parcel-map-frontend-design.md. Closes the "Parcel scanner: frontend raster rendering" deferred item.

- New public-no-auth `GET /api/v1/auctions/{publicId}/parcel-scan` returning the two rasters as base64 + the elevation header (Cache-Control public/max-age=365d/immutable).
- New `ParcelMap` client component renders both rasters as a single combined canvas inside `ParcelInfoPanel`: heightmap as green-to-red gradient (parcel-min relative), non-parcel cells dimmed 60% toward gray, 1-pixel cyan boundary outline around the listed parcel.
- Hover tooltip + keyboard arrow-key navigation with aria-live announcements (per-cell elevation + in/out parcel).
- 404 path leaves the section empty - no placeholder copy when the scan is missing.

## Test plan
- [x] backend ./mvnw test green for AuctionParcelScanReadControllerTest (5 cases)
- [x] frontend npm test + npm run build + npm run verify green
EOF
)"
```

Capture the PR number, merge with `--merge`:

```
gh pr merge <PR_NUMBER> --merge
gh pr view <PR_NUMBER> --json state,mergeCommit -q '.state + " " + (.mergeCommit.oid // "none")'
```

Confirm `MERGED`. Do NOT open or merge a dev->main PR -- the user handles that.

---

## Self-review

**Spec coverage:**
- Spec section 2 (architecture) -- distributed across Tasks 1-4.
- Spec section 3 (backend endpoint, service, DTO, security) -- Task 1.
- Spec section 4.1-4.3 (frontend file structure + types) -- Task 3 step 1.
- Spec section 4.4 (useParcelScan hook) -- Task 3 steps 2-4.
- Spec section 4.5 (ParcelMap component render contract) -- Task 4 step 1.
- Spec section 4.6 (render rules: gradient + dim + boundary outline) -- Task 4 step 1 (paint helpers); Task 2 (gradient + dim math + tests).
- Spec section 4.7 (interactivity: hover + keyboard + aria-live) -- Task 4 step 1.
- Spec section 4.8 (encoding helpers) -- Task 2.
- Spec section 5 (fallback + accessibility) -- Task 4 step 1 (null returns; aria + figcaption); Task 2 (color choices).
- Spec section 6.1 (backend tests) -- Task 1 step 7.
- Spec section 6.2 (frontend tests) -- distributed across Tasks 2, 3, 4, 5.
- Spec section 6.3 (Postman) -- Task 5 step 4.
- Spec section 7 (out of scope) -- nothing snuck in; Task 5 step 6 closes the DEFERRED_WORK entry.

**Placeholder scan:** No "TBD" / "fill in details" / "similar to Task N". Three "look up the existing pattern" instructions remain:
- Task 1 step 7 says to copy `newUser`/`newAuction` from `ParcelScanServiceTest.java` -- intentional, the helpers exist and copying is faster than reinventing.
- Task 3 step 4 says to follow the wrapper pattern of `useActiveListings.test.tsx` -- intentional, the test infrastructure is established and a divergent wrapper would create drift.
- Task 4 step 2 says to look for an existing canvas mock setup in the Vitest test config -- intentional, the test setup file may already shim it.

All three include enough specific text that the implementer can't be lost: the file paths are exact and the precedent class names are real (already grep-confirmed during plan-writing).

**Type consistency:**
- `ParcelScanResponse` -- 7 fields, same names + types on backend record and frontend interface (Tasks 1 + 3).
- `decodeBase64ToBytes`, `isCellInParcel`, `decodeElevationCell` -- defined in Task 2 step 3, consumed in Task 4 step 1. Same signatures.
- `gradientColor`, `dimOutside`, `MAP_COLORS`, `Rgb` -- defined Task 2 step 7, consumed Task 4 step 1. Same shapes.
- `useParcelScan(publicId: string)` -- defined Task 3 step 3, consumed Task 4 step 1. `data: ParcelScanResponse | null` triple shape used consistently.
- `parcelScanKey(publicId)` -- exported alongside the hook (Task 3 step 3) for future cache-invalidation callers; not used by this slice but in-pattern with sibling hooks.

**Resolved during plan-writing:**
- The new endpoint needs its OWN `permitAll` matcher because `/api/v1/auctions/*` only matches one path segment, so `/{publicId}/parcel-scan` would otherwise require auth. Confirmed by reading `SecurityConfig.java` lines 161 + 196 + 242. Task 1 step 6 has the explicit matcher addition.
- `AuctionExceptionHandler` already maps `AuctionNotFoundException` to 404 with `code: "AUCTION_NOT_FOUND"`. The new `AuctionParcelScanNotFoundException` mirrors that pattern with code `PARCEL_SCAN_NOT_FOUND`. No new exception-handler infrastructure needed.
- The SW-flip in the canvas paint (row 0 = south in the spec, but canvas y=0 is top) is documented inline in Task 4 step 1's NOTE. The hover and keyboard math flip symmetrically.
