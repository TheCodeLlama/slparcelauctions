# Parcel Scanner Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every newly-active auction triggers a bot scan that persists two sibling rasters (`AuctionParcelLayout` + `AuctionParcelHeightMap`) describing the parcel's shape and elevation. Backend + .NET bot worker only; frontend explicitly out of scope.

**Architecture:** Activation enqueues a `BotTask` of new type `SCAN_PARCEL`. A .NET handler teleports into the region, reads `Simulator.Parcels` for cell membership and `Simulator.Terrain` for elevation, packs both into compact byte arrays (512 B bitmap + 4 KB uint8 quantized heightmap), and POSTs to a new `/api/v1/bot/tasks/{taskId}/scan-result` endpoint. Backend validates and upserts two `@MapsId` siblings of `Auction`. Non-gating: if the bot can't reach the region, the auction still runs.

**Tech Stack:** Spring Boot 4 / Java 24 / Flyway / JPA / Lombok; .NET 8 / LibreMetaverse.

**Spec:** `docs/superpowers/specs/2026-05-23-parcel-scanner-design.md`.

**Migration number:** V45 (V44 = bid increments is the latest on disk).

**Codebase notes baked in from spec-time exploration:**
- `BotTaskType` currently has `VERIFY_SELL_TO` and `VERIFY_BUY_OWNER`. The spec's prose mentions `WITHDRAW_GROUP` as an "existing" type; that is incorrect — `WITHDRAW_GROUP` is a .NET-side role not modelled in this Java enum. The plan adds `SCAN_PARCEL` alongside the actual two existing values.
- `BotTask` has explicit columns (`parcel_uuid`, `region_name`, `position_x/y/z`, etc.). The SCAN_PARCEL task carries its inputs via the existing `parcel_uuid` + `region_name` columns. NO new column or generic payload field is needed.
- `BotTaskTypeCheckConstraintInitializer` re-runs `EnumCheckConstraintSync` on every boot, deriving allowed values straight from the Java enum. Adding `SCAN_PARCEL` to the enum is sufficient; no manual migration of the CHECK is needed.
- Two production sites transition an auction to ACTIVE: `AuctionVerificationService.java:198` (seller-driven verify flow) and `AdminAuctionService.java:57` (admin force-activate). Both must enqueue the scan.

**Per-task verification (every task):**
- Backend tasks: `cd backend; ./mvnw test -Dtest='...'` for the narrow case, plus the relevant integration test class.
- .NET task: `cd bot; dotnet test`.
- Commit + push before declaring done.

---

## File Structure

**Backend — new:**

| File | Responsibility |
|---|---|
| `backend/src/main/resources/db/migration/V45__auction_parcel_scan.sql` | adds `parcel_scan_included` column on `auctions`; creates `auction_parcel_layouts` and `auction_parcel_height_maps` |
| `auction/parcelscan/AuctionParcelLayout.java` | entity, `@MapsId @OneToOne` to Auction, BYTEA `cells` |
| `auction/parcelscan/AuctionParcelLayoutRepository.java` | `existsByAuctionId(Long)`, `findByAuctionId(Long)` |
| `auction/parcelscan/AuctionParcelHeightMap.java` | entity, `@MapsId @OneToOne` to Auction, BYTEA `cells` + `base_meters` + `step_meters` |
| `auction/parcelscan/AuctionParcelHeightMapRepository.java` | mirrors layout repo |
| `auction/parcelscan/ParcelScanService.java` | `enqueueIfEligible(Auction)` + `applyScanResult(taskId, BotScanResultRequest)`, `@Transactional` |
| `auction/parcelscan/dto/BotScanResultRequest.java` | bot to backend POST body |

**Backend — modify:**

| File | Change |
|---|---|
| `auction/Auction.java` | new `parcelScanIncluded` Boolean field (`@Builder.Default = true`) plus two `@OneToOne(mappedBy="auction", fetch=LAZY)` accessors for the rasters |
| `auction/AuctionService.java` | `create` sets `parcelScanIncluded(true)` explicitly on the builder |
| `auction/AuctionVerificationService.java` | call `parcelScanService.enqueueIfEligible(a)` after the `setStatus(ACTIVE)` transition commits |
| `admin/AdminAuctionService.java` | call `parcelScanService.enqueueIfEligible(a)` after the `setStatus(ACTIVE)` transition commits |
| `bot/BotTaskType.java` | add `SCAN_PARCEL` |
| `bot/BotTaskController.java` | add `@PostMapping("/{taskId}/scan-result")` |

**Bot (.NET) — new:**

| File | Responsibility |
|---|---|
| `bot/src/Slpa.Bot/Tasks/Handlers/ScanParcelHandler.cs` | the handler |
| `bot/test/Slpa.Bot.Tests/Tasks/Handlers/ScanParcelHandlerTests.cs` | unit tests with IBotSession fake |

**Bot (.NET) — modify:**

| File | Change |
|---|---|
| `bot/src/Slpa.Bot/Tasks/BotTaskType.cs` | add `ScanParcel` |
| `bot/src/Slpa.Bot/Backend/HttpBackendClient.cs` | new `PostScanResultAsync(long taskId, ScanResultBody body, CancellationToken ct)` |
| the task dispatcher (find via `grep "VerifySellToHandler" bot/src`) | route `ScanParcel` to the new handler |

---

### Task 1: V45 migration + `Auction.parcelScanIncluded` field

**Files:**
- Create: `backend/src/main/resources/db/migration/V45__auction_parcel_scan.sql`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/Auction.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionService.java`

- [ ] **Step 1: Write the migration**

`backend/src/main/resources/db/migration/V45__auction_parcel_scan.sql`:

```sql
-- Per-auction bot-driven parcel scan. Produces two sibling rasters
-- (AuctionParcelLayout + AuctionParcelHeightMap) per ACTIVE auction.
-- See docs/superpowers/specs/2026-05-23-parcel-scanner-design.md.

-- Future-paid-upgrade flag. True today for every new auction; the
-- entitlement check is future work. The DEFAULT exists for migration
-- safety on any pre-existing rows; AuctionService.create writes the
-- value explicitly on every new auction.
ALTER TABLE auctions
  ADD COLUMN parcel_scan_included BOOLEAN NOT NULL DEFAULT true;

-- Region-wide parcel-membership bitmap. 64x64 cells, 4m each = 256m
-- region. Row-major from the south-west corner. cells = packed bitmap,
-- 1 bit per cell, MSB-first within each byte. 4096 bits = 512 bytes.
CREATE TABLE auction_parcel_layouts (
  auction_id       BIGINT PRIMARY KEY REFERENCES auctions(id) ON DELETE CASCADE,
  public_id        UUID NOT NULL UNIQUE,
  grid_size        INT NOT NULL,
  cell_size_meters INT NOT NULL,
  cells            BYTEA NOT NULL,
  scanned_at       TIMESTAMPTZ NOT NULL,
  created_at       TIMESTAMPTZ NOT NULL,
  updated_at       TIMESTAMPTZ NOT NULL,
  version          BIGINT NOT NULL DEFAULT 0
);

-- Region-wide elevation raster on the same grid as the layout. cells
-- = 4096 uint8s, row-major SW-first. Decode:
--   elevationMeters = base_meters + (cell & 0xFF) * step_meters
-- step is auto-fitted at encode time so that 255 * step covers the
-- region's actual elevation range. ~4 KB + 8-byte header total.
CREATE TABLE auction_parcel_height_maps (
  auction_id       BIGINT PRIMARY KEY REFERENCES auctions(id) ON DELETE CASCADE,
  public_id        UUID NOT NULL UNIQUE,
  grid_size        INT NOT NULL,
  cell_size_meters INT NOT NULL,
  base_meters      REAL NOT NULL,
  step_meters      REAL NOT NULL,
  cells            BYTEA NOT NULL,
  scanned_at       TIMESTAMPTZ NOT NULL,
  created_at       TIMESTAMPTZ NOT NULL,
  updated_at       TIMESTAMPTZ NOT NULL,
  version          BIGINT NOT NULL DEFAULT 0
);
```

- [ ] **Step 2: Add the entity field**

In `Auction.java`, near `startingBid` / `bidIncrement`, add:

```java
@Builder.Default
@Column(name = "parcel_scan_included", nullable = false)
private Boolean parcelScanIncluded = true;
```

- [ ] **Step 3: Have AuctionService.create write it explicitly**

In `AuctionService.create`, where the `Auction` builder is assembled (alongside `.bidIncrement(...)`), add:

```java
.parcelScanIncluded(true)
```

(Always true today. The future paid-upgrade refactor swaps this for an entitlement check.)

- [ ] **Step 4: Compile + run a context-loading test**

```bash
cd backend; ./mvnw test -Dtest=AuctionRepositoryLockTest
```
Expected: green, Flyway log shows `now at version v45`.

- [ ] **Step 5: Commit + push**

```bash
git add backend/src/main/resources/db/migration/V45__auction_parcel_scan.sql \
        backend/src/main/java/com/slparcelauctions/backend/auction/Auction.java \
        backend/src/main/java/com/slparcelauctions/backend/auction/AuctionService.java
git commit -m "feat(parcel-scan): V45 migration + Auction.parcelScanIncluded"
git push
```

---

### Task 2: `AuctionParcelLayout` + `AuctionParcelHeightMap` entities + repositories

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/parcelscan/AuctionParcelLayout.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/parcelscan/AuctionParcelLayoutRepository.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/parcelscan/AuctionParcelHeightMap.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/parcelscan/AuctionParcelHeightMapRepository.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/Auction.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/auction/parcelscan/AuctionParcelRasterPersistenceTest.java` (new)

The two entities use `@MapsId` to share their PK with `auctions(id)`. CLAUDE.md's BaseEntity convention explicitly carves out `@MapsId` PKs (see `AuctionParcelSnapshot` for the precedent) — these rasters are NOT extending `BaseEntity` / `BaseMutableEntity`. They carry `id`, `publicId`, `createdAt`, `updatedAt`, `version` as their own fields.

- [ ] **Step 1: Write `AuctionParcelLayout`**

```java
package com.slparcelauctions.backend.auction.parcelscan;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.slparcelauctions.backend.auction.Auction;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Per-auction region-wide parcel-membership bitmap (64x64 cells, 4m each).
 *
 * <p>Row-major from the SW corner: index = row * 64 + col. The {@code cells}
 * byte array packs one bit per cell, MSB-first within each byte. A bit is
 * set iff the cell at that index belongs to the listed parcel. 4096 bits
 * total = 512 bytes.
 *
 * <p>Shares its PK with {@code auctions(id)} via {@link MapsId} — see
 * CLAUDE.md "BaseEntity convention" for why this entity deliberately stays
 * outside the {@code BaseMutableEntity} hierarchy ({@code AuctionParcelSnapshot}
 * is the existing precedent).
 */
@Entity
@Table(name = "auction_parcel_layouts")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class AuctionParcelLayout {

    @Id
    @Column(name = "auction_id")
    private Long id;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "auction_id")
    private Auction auction;

    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    private UUID publicId;

    @Column(name = "grid_size", nullable = false)
    private Integer gridSize;

    @Column(name = "cell_size_meters", nullable = false)
    private Integer cellSizeMeters;

    @Column(name = "cells", nullable = false)
    private byte[] cells;

    @Column(name = "scanned_at", nullable = false)
    private OffsetDateTime scannedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (publicId == null) publicId = UUID.randomUUID();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
```

- [ ] **Step 2: Write `AuctionParcelLayoutRepository`**

```java
package com.slparcelauctions.backend.auction.parcelscan;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AuctionParcelLayoutRepository extends JpaRepository<AuctionParcelLayout, Long> {

    boolean existsByAuctionId(Long auctionId);

    Optional<AuctionParcelLayout> findByAuctionId(Long auctionId);
}
```

- [ ] **Step 3: Write `AuctionParcelHeightMap`**

Mirror the layout entity. Same `@MapsId` PK shape, same lifecycle hooks. Additional fields: `Float baseMeters` (`@Column(name = "base_meters")`) and `Float stepMeters` (`@Column(name = "step_meters")`). The `cells` byte array stores 4096 uint8s (the JVM `byte` is signed; encoders/decoders use `cell & 0xFF` for unsigned semantics, which the service layer handles — the entity stores the raw bytes verbatim).

```java
package com.slparcelauctions.backend.auction.parcelscan;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.slparcelauctions.backend.auction.Auction;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Per-auction region-wide elevation raster (64x64 cells, 4m each, in meters).
 *
 * <p>Row-major from the SW corner. {@code cells} is 4096 uint8s. Decode:
 * <pre>elevationMeters = baseMeters + (cells[i] &amp; 0xFF) * stepMeters</pre>
 * {@code stepMeters} is auto-fitted at encode time so that {@code 255 * step}
 * covers the region's actual elevation range. ~4 KB + 8-byte header total.
 */
@Entity
@Table(name = "auction_parcel_height_maps")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class AuctionParcelHeightMap {

    @Id
    @Column(name = "auction_id")
    private Long id;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "auction_id")
    private Auction auction;

    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    private UUID publicId;

    @Column(name = "grid_size", nullable = false)
    private Integer gridSize;

    @Column(name = "cell_size_meters", nullable = false)
    private Integer cellSizeMeters;

    @Column(name = "base_meters", nullable = false)
    private Float baseMeters;

    @Column(name = "step_meters", nullable = false)
    private Float stepMeters;

    @Column(name = "cells", nullable = false)
    private byte[] cells;

    @Column(name = "scanned_at", nullable = false)
    private OffsetDateTime scannedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (publicId == null) publicId = UUID.randomUUID();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
```

- [ ] **Step 4: Write `AuctionParcelHeightMapRepository`**

```java
package com.slparcelauctions.backend.auction.parcelscan;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AuctionParcelHeightMapRepository extends JpaRepository<AuctionParcelHeightMap, Long> {

    boolean existsByAuctionId(Long auctionId);

    Optional<AuctionParcelHeightMap> findByAuctionId(Long auctionId);
}
```

- [ ] **Step 5: Wire reverse accessors on `Auction`**

In `Auction.java`, add (alongside any existing `@OneToOne` mappings):

```java
@OneToOne(mappedBy = "auction", fetch = FetchType.LAZY,
        cascade = CascadeType.ALL, orphanRemoval = true, optional = true)
private AuctionParcelLayout parcelLayout;

@OneToOne(mappedBy = "auction", fetch = FetchType.LAZY,
        cascade = CascadeType.ALL, orphanRemoval = true, optional = true)
private AuctionParcelHeightMap parcelHeightMap;
```

(Imports: `jakarta.persistence.CascadeType`, `jakarta.persistence.FetchType`, `jakarta.persistence.OneToOne`, and the two raster types.)

- [ ] **Step 6: Persistence smoke test**

`AuctionParcelRasterPersistenceTest.java` — `@DataJpaTest` (or follow whatever the existing `auction/` package uses for raw persistence tests; if no precedent, use `@SpringBootTest`). Build an auction, save a layout with `cells = new byte[512]`, save a heightmap with `cells = new byte[4096]`, baseMeters = 22.0f, stepMeters = 0.5f. Re-read both via `findByAuctionId` and assert lengths, `publicId` non-null, `createdAt` populated.

- [ ] **Step 7: Run**

```bash
cd backend; ./mvnw test -Dtest=AuctionParcelRasterPersistenceTest
```

- [ ] **Step 8: Commit + push**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auction/parcelscan/ \
        backend/src/main/java/com/slparcelauctions/backend/auction/Auction.java \
        backend/src/test/java/com/slparcelauctions/backend/auction/parcelscan/
git commit -m "feat(parcel-scan): AuctionParcelLayout + AuctionParcelHeightMap entities"
git push
```

---

### Task 3: `BotTaskType.SCAN_PARCEL` + constraint refresh test

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/bot/BotTaskType.java`
- Modify or create: `backend/src/test/java/com/slparcelauctions/backend/bot/BotTaskTypeCheckConstraintInitializerTest.java`

- [ ] **Step 1: Add the enum value**

In `BotTaskType.java`, add after `VERIFY_BUY_OWNER`:

```java
/**
 * Parcel-scan task (spec 2026-05-23). On a newly-ACTIVE auction the bot
 * teleports into the region, reads {@code Simulator.Parcels} for cell
 * membership and {@code Simulator.Terrain} for elevation, and posts
 * the packed rasters back via {@code POST /api/v1/bot/tasks/{id}/scan-result}.
 * Non-gating: a failure leaves the {@code AuctionParcelLayout} +
 * {@code AuctionParcelHeightMap} rows absent and the auction still runs.
 */
SCAN_PARCEL
```

- [ ] **Step 2: Extend constraint initializer test**

Find or create `BotTaskTypeCheckConstraintInitializerTest`. If one exists, add a test method asserting the constraint accepts `SCAN_PARCEL` after the initializer runs. If none exists, write a `@SpringBootTest` (NOT `@WebMvcTest`) that boots the context (so `EnumCheckConstraintSync` runs on `ApplicationReadyEvent`), then asserts the constraint accepts each current enum value by inserting one bot-task row per type and rejects an unknown value.

Minimal test body if creating new:

```java
@SpringBootTest
class BotTaskTypeCheckConstraintInitializerTest {

    @Autowired JdbcTemplate jdbc;

    @ParameterizedTest
    @ValueSource(strings = {"VERIFY_SELL_TO", "VERIFY_BUY_OWNER", "SCAN_PARCEL"})
    void constraintAcceptsCurrentEnumValues(String taskType) {
        // Insert a minimal row with this task_type; the constraint should not throw.
        // Use a unique sentinel and rollback so we don't pollute other tests.
        // ... insert SQL matching whatever NOT NULLs bot_tasks demands today.
        // If a full insert is too heavy, use jdbc.queryForObject:
        //   "SELECT ?::text WHERE EXISTS (...check constraint definition)"
        // — but the simpler insertion approach is preferable as it actually
        // exercises the constraint.
    }

    @Test
    void constraintRejectsUnknownValue() {
        assertThatThrownBy(() -> jdbc.update(
            "INSERT INTO bot_tasks (..., task_type, ...) VALUES (..., 'NOT_A_REAL_TYPE', ...)"))
            .isInstanceOf(DataIntegrityViolationException.class);
    }
}
```

If a `BotTask` row is heavy to build by hand-rolled SQL, instead trust the existing initializer test pattern (look for any other `*CheckConstraintInitializerTest` in the codebase — `AuctionStatusCheckConstraintInitializerTest` is the precedent per F.43 in FOOTGUNS) and mirror it exactly.

- [ ] **Step 3: Run**

```bash
cd backend; ./mvnw test -Dtest=BotTaskTypeCheckConstraintInitializerTest
```

- [ ] **Step 4: Commit + push**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/bot/BotTaskType.java \
        backend/src/test/java/com/slparcelauctions/backend/bot/BotTaskTypeCheckConstraintInitializerTest.java
git commit -m "feat(parcel-scan): BotTaskType.SCAN_PARCEL"
git push
```

---

### Task 4: `ParcelScanService` + DTO + apply-result validation

**Files:**
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/parcelscan/dto/BotScanResultRequest.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/parcelscan/ParcelScanService.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/auction/parcelscan/ParcelScanServiceTest.java`

- [ ] **Step 1: Write `BotScanResultRequest`**

```java
package com.slparcelauctions.backend.auction.parcelscan.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Bot-to-backend body for {@code POST /api/v1/bot/tasks/{taskId}/scan-result}.
 *
 * <p>{@code gridSize} and {@code cellSizeMeters} are constrained to the
 * current scan resolution (64 / 4). Raising the cap is a schema-versioned
 * change. {@code layoutCellsBase64} decodes to {@code gridSize^2 / 8} bytes
 * and {@code heightCellsBase64} decodes to {@code gridSize^2} bytes; the
 * service validates this after decoding.
 */
public record BotScanResultRequest(
        @NotNull @Min(64) @Max(64) Integer gridSize,
        @NotNull @Min(4) @Max(4) Integer cellSizeMeters,
        @NotBlank String layoutCellsBase64,
        @NotNull Float heightBaseMeters,
        @NotNull @Positive Float heightStepMeters,
        @NotBlank String heightCellsBase64) {
}
```

- [ ] **Step 2: Write `ParcelScanService` skeleton**

```java
package com.slparcelauctions.backend.auction.parcelscan;

import java.time.OffsetDateTime;
import java.util.Base64;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.parcelscan.dto.BotScanResultRequest;
import com.slparcelauctions.backend.bot.BotTask;
import com.slparcelauctions.backend.bot.BotTaskRepository;
import com.slparcelauctions.backend.bot.BotTaskService;
import com.slparcelauctions.backend.bot.BotTaskStatus;
import com.slparcelauctions.backend.bot.BotTaskType;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class ParcelScanService {

    private final AuctionParcelLayoutRepository layoutRepo;
    private final AuctionParcelHeightMapRepository heightRepo;
    private final BotTaskRepository botTaskRepo;
    private final BotTaskService botTaskService;
    private final AuctionRepository auctionRepo;

    /**
     * Enqueue a SCAN_PARCEL bot task for this auction if eligible:
     * scan included AND no raster on file AND no pending task already.
     * A terminally-FAILED prior task does NOT block re-enqueue.
     */
    @Transactional
    public void enqueueIfEligible(Auction auction) {
        if (auction.getParcelScanIncluded() == null || !auction.getParcelScanIncluded()) return;
        if (layoutRepo.existsByAuctionId(auction.getId())) return;
        if (botTaskRepo.existsPendingByAuctionIdAndType(
                auction.getId(), BotTaskType.SCAN_PARCEL)) return;

        botTaskService.enqueueScanParcel(auction);
    }

    /**
     * Apply a bot scan result. Idempotent on COMPLETED tasks (returns 409,
     * which the bot retry treats as success-already-recorded). Does NOT
     * gate on the auction's current status — rasters are immutable data
     * tied to the auction record.
     */
    @Transactional
    public void applyScanResult(long taskId, BotScanResultRequest req) {
        BotTask task = botTaskRepo.findById(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "task not found"));
        if (task.getTaskType() != BotTaskType.SCAN_PARCEL) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "task is not SCAN_PARCEL");
        }
        if (task.getStatus() == BotTaskStatus.COMPLETED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "already completed");
        }

        byte[] layoutCells = Base64.getDecoder().decode(req.layoutCellsBase64());
        byte[] heightCells = Base64.getDecoder().decode(req.heightCellsBase64());

        int cells = req.gridSize() * req.gridSize();
        if (layoutCells.length != cells / 8) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "layout length " + layoutCells.length + " != " + (cells / 8));
        }
        if (heightCells.length != cells) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "height length " + heightCells.length + " != " + cells);
        }
        if (!Float.isFinite(req.heightBaseMeters())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "heightBaseMeters not finite");
        }
        if (req.heightStepMeters() <= 0f || !Float.isFinite(req.heightStepMeters())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "heightStepMeters must be > 0");
        }

        Auction auction = auctionRepo.findById(task.getAuctionId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "auction not found"));

        OffsetDateTime now = OffsetDateTime.now();

        layoutRepo.save(AuctionParcelLayout.builder()
                .auction(auction)
                .gridSize(req.gridSize())
                .cellSizeMeters(req.cellSizeMeters())
                .cells(layoutCells)
                .scannedAt(now)
                .build());

        heightRepo.save(AuctionParcelHeightMap.builder()
                .auction(auction)
                .gridSize(req.gridSize())
                .cellSizeMeters(req.cellSizeMeters())
                .baseMeters(req.heightBaseMeters())
                .stepMeters(req.heightStepMeters())
                .cells(heightCells)
                .scannedAt(now)
                .build());

        botTaskService.markCompleted(task);
    }
}
```

NOTE: this code references three new methods that don't exist yet:
- `BotTaskRepository.existsPendingByAuctionIdAndType(auctionId, type)` — add this method as a `@Query` returning a boolean. Pending means status not in `{COMPLETED, FAILED}` (verify the exact `BotTaskStatus` enum values via grep before writing). Read `BotTaskRepository` first; if a similar method already exists for a different type, mirror its shape.
- `BotTaskService.enqueueScanParcel(Auction auction)` — creates a `BotTask` with `taskType=SCAN_PARCEL`, `parcelUuid=auction.slParcelUuid`, `regionName=<auction's region>`, plus whatever other required columns the existing enqueue methods on this service set. Read `BotTaskService` for the precedent — if there's an `enqueueVerifyBuyOwner` or similar, mirror its construction pattern. The auction's region name comes from `auction.getParcel().getRegionName()` or whatever the existing pattern is — grep for `regionName` reads in the auction package to find the canonical source.
- `BotTaskService.markCompleted(BotTask task)` — if this exists, reuse it; if not, set status and `completedAt` directly and `botTaskRepo.save(task)`.

Add the three to their respective classes as separate edits in this same task. The plan separates them out so the implementer doesn't forget any.

- [ ] **Step 3: Write `ParcelScanServiceTest`**

`@SpringBootTest` with `@Autowired` of the service + repos. Use `@Transactional` on the test class for auto-rollback. Cases:

```java
@Test void enqueue_skipsWhenScanNotIncluded() { /* set parcelScanIncluded=false, assert no task created */ }

@Test void enqueue_skipsWhenLayoutAlreadyExists() { /* pre-insert a layout, assert no task created */ }

@Test void enqueue_skipsWhenPendingTaskAlreadyExists() { /* pre-insert a PENDING SCAN_PARCEL task, assert no new task created */ }

@Test void enqueue_proceedsAfterTerminalFailure() { /* pre-insert a FAILED SCAN_PARCEL task, assert a new PENDING task IS created */ }

@Test void apply_happyPath_persistsBothRastersAndCompletesTask() { /* build a valid request with 512-byte layout + 4096-byte height, apply, assert both rows present, task COMPLETED */ }

@Test void apply_layoutLengthMismatch_returns400() { /* layout != 512 bytes, assert ResponseStatusException with BAD_REQUEST */ }

@Test void apply_heightLengthMismatch_returns400() { /* height != 4096 bytes, assert ResponseStatusException with BAD_REQUEST */ }

@Test void apply_nonFiniteBase_returns400() { /* heightBaseMeters = NaN, assert BAD_REQUEST */ }

@Test void apply_nonPositiveStep_returns400() { /* heightStepMeters = 0f, assert BAD_REQUEST */ }

@Test void apply_replayAfterSuccess_returns409() { /* apply once, apply again, second call throws CONFLICT, no second row */ }

@Test void apply_wrongTaskType_returns409() { /* task is VERIFY_SELL_TO, assert CONFLICT */ }
```

- [ ] **Step 4: Run**

```bash
cd backend; ./mvnw test -Dtest=ParcelScanServiceTest
```

- [ ] **Step 5: Commit + push**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auction/parcelscan/ \
        backend/src/main/java/com/slparcelauctions/backend/bot/ \
        backend/src/test/java/com/slparcelauctions/backend/auction/parcelscan/
git commit -m "feat(parcel-scan): ParcelScanService + apply-result validation"
git push
```

---

### Task 5: Wire activation in `AuctionVerificationService` + `AdminAuctionService`

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/AuctionVerificationService.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/admin/AdminAuctionService.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/auction/parcelscan/AuctionActivationScanEnqueueIntegrationTest.java` (new)

- [ ] **Step 1: Wire `AuctionVerificationService`**

Inject `ParcelScanService`. After `a.setStatus(AuctionStatus.ACTIVE);` at line ~198 commits (i.e. as the last call before the method returns or as a transactional-after-commit hook — match whatever pattern the existing post-activation work uses; if there is none, add the call directly after the `setStatus`), add:

```java
parcelScanService.enqueueIfEligible(a);
```

- [ ] **Step 2: Wire `AdminAuctionService`**

Same change at line ~57.

- [ ] **Step 3: Integration test**

`AuctionActivationScanEnqueueIntegrationTest.java` — `@SpringBootTest`, `@Transactional`. Cases:

```java
@Test void activate_enqueuesScanTask() { /* activate via verification service, assert one PENDING SCAN_PARCEL task with this auction's id and parcelUuid */ }

@Test void activate_skipsScanWhenIncludedFalse() { /* set parcelScanIncluded=false, activate, assert no SCAN_PARCEL task */ }

@Test void activate_skipsScanWhenLayoutAlreadyExists() { /* pre-insert a layout, activate, assert no new SCAN_PARCEL task */ }

@Test void activate_adminPathAlsoEnqueues() { /* same via AdminAuctionService */ }
```

- [ ] **Step 4: Run**

```bash
cd backend; ./mvnw test -Dtest=AuctionActivationScanEnqueueIntegrationTest
```

- [ ] **Step 5: Commit + push**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/auction/AuctionVerificationService.java \
        backend/src/main/java/com/slparcelauctions/backend/admin/AdminAuctionService.java \
        backend/src/test/java/com/slparcelauctions/backend/auction/parcelscan/
git commit -m "feat(parcel-scan): enqueue scan task on auction activation"
git push
```

---

### Task 6: `BotTaskController` `/scan-result` endpoint

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/bot/BotTaskController.java`
- Test: `backend/src/test/java/com/slparcelauctions/backend/bot/BotTaskControllerScanResultTest.java` (new)

- [ ] **Step 1: Add the endpoint**

Inject `ParcelScanService`. Add the method alongside the existing `/result` and `/verify-buy-owner-result` handlers:

```java
@PostMapping("/{taskId}/scan-result")
public ResponseEntity<Void> scanResult(@PathVariable Long taskId,
                                       @Valid @RequestBody BotScanResultRequest body) {
    parcelScanService.applyScanResult(taskId, body);
    return ResponseEntity.ok().build();
}
```

(Import `BotScanResultRequest` from the parcelscan DTO package.)

- [ ] **Step 2: Integration test**

`BotTaskControllerScanResultTest.java` — `@SpringBootTest + @AutoConfigureMockMvc` (NEVER `@WebMvcTest`). Use the bot-shared-secret header pattern the existing bot endpoints test classes use (read `BotTaskControllerTest` or whatever the existing class is named and mirror its setup). Cases:

```java
@Test void scanResult_happyPath_returns200AndPersistsBothRows() { /* valid body, assert 200 + two rows exist + task COMPLETED */ }

@Test void scanResult_nonScanParcelTask_returns409() { /* task is VERIFY_SELL_TO */ }

@Test void scanResult_completedTask_returns409() { /* task already COMPLETED */ }

@Test void scanResult_layoutLengthMismatch_returns400() { /* 511-byte layout */ }

@Test void scanResult_heightLengthMismatch_returns400() { /* 4095-byte height */ }

@Test void scanResult_invalidBase64_returns400() { /* "not-base64!" */ }

@Test void scanResult_nonPositiveStep_returns400() { /* heightStepMeters = 0 */ }

@Test void scanResult_missingAuthHeader_returns401() { /* if the existing bot auth filter requires the shared secret */ }
```

Build the 512-byte layout via `new byte[512]` (all zero is fine for validation) and base64-encode. Same for the 4096-byte height.

- [ ] **Step 3: Run**

```bash
cd backend; ./mvnw test -Dtest=BotTaskControllerScanResultTest
```

- [ ] **Step 4: Commit + push**

```bash
git add backend/src/main/java/com/slparcelauctions/backend/bot/BotTaskController.java \
        backend/src/test/java/com/slparcelauctions/backend/bot/BotTaskControllerScanResultTest.java
git commit -m "feat(parcel-scan): POST /bot/tasks/{id}/scan-result endpoint"
git push
```

---

### Task 7: .NET bot worker — `ScanParcelHandler` + backend client + dispatch

**Files:**
- Modify: `bot/src/Slpa.Bot/Tasks/BotTaskType.cs`
- Create: `bot/src/Slpa.Bot/Tasks/Handlers/ScanParcelHandler.cs`
- Create: `bot/test/Slpa.Bot.Tests/Tasks/Handlers/ScanParcelHandlerTests.cs`
- Modify: `bot/src/Slpa.Bot/Backend/HttpBackendClient.cs`
- Modify: the task dispatcher / handler-routing file (find via `grep -rln "VerifySellToHandler\|VERIFY_SELL_TO" bot/src`)

**Before writing any code:** READ `bot/README.md` AND `bot/AGENTS.md` (if present). The testing convention is hard: every handler test must drive an `IBotSession` fake; tests must NOT touch `GridClient` directly. The existing `VerifySellToHandlerTests` (or equivalent) is the canonical precedent — mirror its setup.

- [ ] **Step 1: Add `ScanParcel` to `BotTaskType.cs`**

```csharp
public enum BotTaskType
{
    // ... existing values
    ScanParcel,
}
```

Match the JSON serialization that the backend's `SCAN_PARCEL` round-trips through. If the bot uses `[JsonStringEnumConverter]`, the C# `ScanParcel` will serialize as `"ScanParcel"` not `"SCAN_PARCEL"` — verify the existing types use a converter that produces SCREAMING_SNAKE_CASE. If not, add `[EnumMember(Value = "SCAN_PARCEL")]` to the new value (and check that the existing values use the same convention).

- [ ] **Step 2: Add `IBotSession.RequestAllSimParcelsAsync` + `GetRegionParcels` + `GetTerrainHeightsRegion` (interface)**

The handler depends on three operations the test fake will stub:
- Teleport (probably already on `IBotSession`).
- Request and await the parcel list for the current region, returning a collection of `(LocalID, GlobalID)` pairs.
- Read the per-cell parcel-LocalID grid for the current region as a `uint[,]` of `64 x 64`.
- Read the per-cell elevation grid as a `float[,]` of `64 x 64` (averaged or center-sampled from `Simulator.Terrain`'s 256x256 floats — handler does the resampling).

If those methods don't already exist on `IBotSession`, add them. Implementation in the real `LibreMetaverseBotSession` uses `Client.Parcels.RequestAllSimParcels()` + the `SimParcelsDownloaded` event, and `Simulator.Terrain` for the heights. Real-implementation correctness is verified by manual smoke; unit tests stay on `IBotSession`.

- [ ] **Step 3: Write `ScanParcelHandler`**

The handler's contract:
- Input: a `BotTask` with `TaskType=ScanParcel`, `ParcelUuid` (the SL global parcel UUID), `RegionName`.
- Behaviour:
  1. Teleport to `RegionName` (default landing point).
  2. `await session.RequestAllSimParcelsAsync(ct)`. Find the `LocalID` whose `GlobalID == task.ParcelUuid`. If none, post FAILED with reason `PARCEL_NOT_FOUND_IN_REGION` and return.
  3. `var parcelGrid = session.GetRegionParcels();` (`uint[64,64]`). Build a 512-byte bitmap: for `(row, col)` in `0..64`, set bit `(7 - (col % 8))` of byte `(row * 8 + col / 8)` iff `parcelGrid[row, col] == ourLocalID`. (Row-major SW-first, MSB-first within each byte.)
  4. `var terrain = session.GetTerrainHeightsRegion();` (`float[64,64]`, center-sampled by the session impl: terrain row `r*4+2`, col `c*4+2`). Compute `min` and `max` over all 4096 values. `var step = Math.Max(0.001f, (max - min) / 255f);` `var base = min;` Build 4096-byte `cells`: `cells[row*64+col] = (byte)Math.Clamp(Math.Round((terrain[row, col] - base) / step), 0, 255);`
  5. POST via `httpBackend.PostScanResultAsync(task.Id, new ScanResultBody { GridSize = 64, CellSizeMeters = 4, LayoutCellsBase64 = Convert.ToBase64String(layoutCells), HeightBaseMeters = base, HeightStepMeters = step, HeightCellsBase64 = Convert.ToBase64String(heightCells) }, ct);`
  6. If the POST throws (e.g. teleport blew the connection mid-scan, network failure), surface as `REGION_UNREACHABLE` via the failure-post path. If the POST returns 4xx, treat as a terminal failure with the response status as the reason. If 409, treat as already-recorded and exit successfully.

If teleport throws (`SimulatorBanned`, timeout, etc.) in step 1, post FAILED with reason `REGION_UNREACHABLE` and return.

- [ ] **Step 4: Add `HttpBackendClient.PostScanResultAsync`**

```csharp
public async Task<HttpResponseMessage> PostScanResultAsync(long taskId, ScanResultBody body, CancellationToken ct)
{
    return await _http.PostAsJsonAsync($"/api/v1/bot/tasks/{taskId}/scan-result", body, ct);
}
```

Where `ScanResultBody` is a record matching the backend DTO field names and JSON casing.

- [ ] **Step 5: Wire dispatch**

In the task dispatcher (the file the grep in the "Files" header found), route `BotTaskType.ScanParcel` to a new instance of `ScanParcelHandler`. Mirror the existing `VerifySellToHandler` / `VerifyBuyOwnerHandler` registration.

- [ ] **Step 6: Write `ScanParcelHandlerTests`**

Use the `IBotSession` fake. Test cases:

```csharp
[Fact] public async Task HappyPath_PostsCorrectBody() {
    // Fake session returns:
    //   parcels list with GlobalID == our taskUuid having LocalID 42
    //   GetRegionParcels returns a 64x64 grid where rows 10..20, cols 5..15 are 42
    //   GetTerrainHeightsRegion returns a flat 30.0f grid
    // Run handler. Assert PostScanResultAsync was called once with:
    //   - GridSize=64, CellSizeMeters=4
    //   - LayoutCellsBase64 decodes to a 512-byte array with the right bits set
    //   - HeightBaseMeters=30.0f, HeightStepMeters=0.001f (clamped), HeightCellsBase64 decodes to 4096 zeros
}

[Fact] public async Task ParcelNotFoundInRegion_PostsFailure() {
    // Fake session returns a parcels list without our GlobalID.
    // Assert PostFailureAsync called with reason "PARCEL_NOT_FOUND_IN_REGION".
    // Assert PostScanResultAsync NOT called.
}

[Fact] public async Task RegionUnreachable_PostsFailure() {
    // Fake session.TeleportAsync throws SimulatorBannedException.
    // Assert PostFailureAsync called with reason "REGION_UNREACHABLE".
}

[Fact] public async Task HeightmapQuantization_RangeIs200m_ProducesExpectedStep() {
    // Terrain grid spans 30..230m. Run handler.
    // Assert HeightStepMeters ≈ 200f/255f ≈ 0.7843f.
    // Assert min cell = 0, max cell = 255.
    // Assert decoding: base + cell * step ≈ original (within step granularity).
}

[Fact] public async Task HeightmapQuantization_FlatRegion_ClampsStep() {
    // Terrain is all 30.0f. step = max(0.001f, 0/255) = 0.001f.
    // All cells = 0. Assert HeightStepMeters == 0.001f.
}

[Fact] public async Task BackendReturns409_TreatedAsSuccess() {
    // PostScanResultAsync returns HttpResponseMessage with status 409.
    // Assert handler does NOT post failure; treats as success.
}
```

- [ ] **Step 7: Run**

```bash
cd bot; dotnet test
```

(Run the full bot suite; the handler touches dispatcher wiring, so a focused run misses regressions.)

- [ ] **Step 8: Commit + push**

```bash
git add bot/src bot/test
git commit -m "feat(parcel-scan): ScanParcelHandler + backend POST + dispatch"
git push
```

---

### Task 8: Postman + README + PR into dev

**Files:**
- Modify: SLPA Postman collection (cloud)
- Modify: `README.md`
- Modify: `bot/README.md`
- Modify: `lsl-scripts/README.md` only if it claims an LSL scanner is on the roadmap (it does not today, but check)
- Modify: `docs/initial-design/DESIGN.md` section 5.5 to mark the design decisions as made (point to the spec)
- Modify: `docs/implementation/DEFERRED_WORK.md` only if anything was deferred

- [ ] **Step 1: Postman**

In the SLPA collection (id `8070328-cf3e1190-cb71-4af3-8e2e-70c82dfee288`): under the Bot folder, add a `POST /api/v1/bot/tasks/{{botTaskId}}/scan-result` request. Body: valid JSON with `gridSize=64`, `cellSizeMeters=4`, `heightBaseMeters=22.5`, `heightStepMeters=0.5`, plus base64-encoded 512-byte and 4096-byte zero-filled arrays (`AAAA...` is fine for the placeholder; Postman dynamic variables or a pre-request script can generate it). Add `pm.test` asserting status 200. Add a sibling request with a 511-byte layout asserting status 400.

If the Postman MCP tools error out or risk corrupting the collection, leave it and note "Postman update deferred" in the report. Do not half-edit.

- [ ] **Step 2: README sweep**

Root `README.md`: add a short paragraph (in the Epic 04 / auction subsystem section or wherever bot tasks are described) noting that every newly-active auction triggers a bot SCAN_PARCEL task producing two sibling rasters (layout bitmap + heightmap), non-gating, with a pointer to the spec.

`bot/README.md`: extend the "Current roles" list to include SCAN_PARCEL with a one-line summary. Update any retiring-context language about "no role in active-state monitoring" to still hold (it does — scanning is a one-off at activation, not active-state polling).

`docs/initial-design/DESIGN.md` section 5.5: replace the "Generation approaches (to be determined)" block and the TODO list with a short "Implemented 2026-05-23: bot via LibreMetaverse + uint8 quantized heightmap. See spec." Keep the prior text in git history; remove from the live doc.

- [ ] **Step 3: DEFERRED_WORK check**

Per the spec section 7, three items are explicitly out of scope (frontend rendering; periodic rescan; paid-upgrade entitlement; admin GET; LSL fallback). If they aren't already in DEFERRED_WORK.md, append rows for them (the file is append-only — never mutate prior rows).

- [ ] **Step 4: Full suites**

```bash
cd backend; ./mvnw test
cd bot; dotnet test
```

If anything is red, STOP and report. Do NOT open the PR with red tests.

The backend full suite is known-flaky under Postgres "too many clients" on a single dev Postgres. If 5+ unrelated `@SpringBootTest` classes ERROR (not FAIL) with `FATAL: sorry, too many clients already`, re-run those classes in isolation to confirm they're environmental, then proceed. Any genuine assertion FAILURE blocks the PR.

- [ ] **Step 5: Commit docs**

```bash
git add README.md bot/README.md docs/initial-design/DESIGN.md
# docs/implementation/DEFERRED_WORK.md only if changed
git commit -m "docs: parcel scanner — README + DESIGN.md sweep"
git push
```

- [ ] **Step 6: Open PR into dev and merge it**

```bash
gh pr create --base dev --head feat/parcel-scanner \
  --title "feat(auction): bot-driven parcel scanner (layout + heightmap)" \
  --body "$(cat <<'EOF'
## Summary
- Implements per-auction bot-driven parcel scanner per docs/superpowers/specs/2026-05-23-parcel-scanner-design.md.
- New auctions.parcel_scan_included column + auction_parcel_layouts + auction_parcel_height_maps tables (Flyway V45).
- New BotTaskType.SCAN_PARCEL; bot teleports into the region, reads Simulator.Parcels for cell membership and Simulator.Terrain for elevation, posts packed rasters (512 B bitmap + 4 KB uint8 quantized heightmap) to a new POST /api/v1/bot/tasks/{id}/scan-result.
- Non-gating: activation enqueues, the auction does not wait on the scan; estate-banned regions leave the rasters absent.
- Frontend rendering is explicitly out of scope (tracked in DEFERRED_WORK).

## Test plan
- [x] backend ./mvnw test green
- [x] bot dotnet test green
EOF
)"
```

Capture the PR number, merge with `--merge`:

```bash
gh pr merge <PR_NUMBER> --merge
gh pr view <PR_NUMBER> --json state,mergeCommit -q '.state + " " + (.mergeCommit.oid // "none")'
```

Confirm `MERGED`. Do NOT open or merge a dev->main PR — the user handles that.

---

## Self-review

**Spec coverage:**
- Spec section 1 (goal) — overall scope, both rasters per auction.
- Spec section 2 (paid-upgrade hook) — Task 1 (`parcel_scan_included` column + entity field + `AuctionService.create` write).
- Spec section 3 (data model: schema + encoding + entities) — Tasks 1 (migration + column) and 2 (entities + repositories + reverse accessors).
- Spec section 4 (bot task lifecycle: enum, enqueue, claim/work, result, failure, pool) — Tasks 3 (enum + constraint test), 4 (service apply + DTO), 5 (enqueue wiring), 6 (result endpoint), 7 (bot handler + dispatch).
- Spec section 5 (backend surface summary) — covered across Tasks 1-6.
- Spec section 6 (tests: backend + bot + Postman) — Tasks 2 (persistence), 4 (service), 5 (activation), 6 (controller), 7 (.NET), 8 (Postman).
- Spec section 7 (out of scope) — Task 8 step 3 (DEFERRED_WORK).

**Placeholder scan:** Two "find via grep" handoffs (the .NET task dispatcher in Task 7; any `*CheckConstraintInitializerTest` precedent in Task 3) include the grep command so the implementer locates the exact file. No "TBD" / "TODO" / "implement later" anywhere. Every code step shows the actual code, including the entity classes in full.

**Type consistency:**
- `AuctionParcelLayout` / `AuctionParcelHeightMap` (Java) — same names used everywhere they appear.
- `BotTaskType.SCAN_PARCEL` (Java) ↔ `BotTaskType.ScanParcel` (C#), with the JSON-serialization caveat called out in Task 7 step 1.
- `BotScanResultRequest` field names: `gridSize`, `cellSizeMeters`, `layoutCellsBase64`, `heightBaseMeters`, `heightStepMeters`, `heightCellsBase64`. Same on backend and bot.
- Service method names: `ParcelScanService.enqueueIfEligible(Auction)` + `applyScanResult(long, BotScanResultRequest)` — used identically across Tasks 4, 5, and 6.
- `BotTaskRepository.existsPendingByAuctionIdAndType(Long, BotTaskType)` and `BotTaskService.enqueueScanParcel(Auction)` + `markCompleted(BotTask)` — defined in Task 4 and not referenced elsewhere by other names.

**Resolved during plan-writing:**
- The spec's loose reference to `WITHDRAW_GROUP` as an existing `BotTaskType` is incorrect — the actual enum has only `VERIFY_SELL_TO` and `VERIFY_BUY_OWNER`. Plan adds `SCAN_PARCEL` alongside those two and ignores the spec's incorrect mention.
- `BotTask` carries inputs via explicit columns (`parcel_uuid`, `region_name`), not a generic JSONB payload. Plan reuses those columns; no new column needed.
- Two activation sites (`AuctionVerificationService:198`, `AdminAuctionService:57`); both wired in Task 5.
