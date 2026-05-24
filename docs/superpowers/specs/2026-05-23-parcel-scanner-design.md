# Parcel Scanner

**Date:** 2026-05-23
**Status:** Awaiting user review.
**Supersedes:** DESIGN.md section 5.5 ("Parcel Layout Map Generator"), specifically the four "generation approaches (to be determined)" — this spec picks approach 2 (bot via LibreMetaverse) and extends scope with an elevation heightmap. The §5.5 TODO list is closed by this spec.

## 1. Goal

Every newly-active auction gets two sibling rasters describing its parcel:

- **`AuctionParcelLayout`** — a 64x64 bitmap covering the parcel's region, with bits set for the cells that belong to the listed parcel. Discrete, per-cell parcel-membership.
- **`AuctionParcelHeightMap`** — a 64x64 quantized elevation raster over the same grid, in meters above sea level.

Both rows are owned by the auction (one-to-one, per-auction not per-parcel — relisting the same parcel produces a fresh scan). Frontend display is OUT OF SCOPE; this spec only gets the data into the backend. The rasters are scanned by a backend-dispatched bot worker via LibreMetaverse, not by a seller-rezzed LSL object.

## 2. Future-paid-upgrade hook

A per-auction boolean `auctions.parcel_scan_included` (NOT NULL DEFAULT true) gates whether the scan is enqueued. Today, `AuctionService.create` always sets it to true. Tomorrow, paid-upgrade work flips the default and adds the entitlement check at create time. No other code path changes: the scan enqueue and the bot handler both stay agnostic. Anything downstream that wants to surface "no scan available" reads `parcelScanIncluded` and the absence of the two rasters.

## 3. Data model

### 3.1 Schema (Flyway V45)

```sql
ALTER TABLE auctions
  ADD COLUMN parcel_scan_included BOOLEAN NOT NULL DEFAULT true;

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

The Postgres `chk_bot_task_type` constraint is rewritten at boot by the existing `BotTaskTypeCheckConstraintInitializer` to include `SCAN_PARCEL`; the migration leaves the existing check untouched.

### 3.2 Encoding

**Both rasters use the same 64x64 region grid.** 64 cells per side x 4 meters per cell = 256 meters, the full SL region. Row-major from the south-west corner: index `(row * 64 + col)`, row 0 is the southernmost row, col 0 is the westernmost column. Both rasters cover the entire region, not just the parcel's cells; this keeps them index-aligned and gives the future frontend the region-context view for free.

**`auction_parcel_layouts.cells`** — packed bitmap, 1 bit per cell, MSB-first within each byte. 64*64 = 4096 bits = 512 bytes. Bit set iff the cell at that index belongs to the listed parcel.

**`auction_parcel_height_maps.cells`** — 4096 uint8s, one per cell. Decode:

```
elevationMeters = base_meters + (cellByte & 0xFF) * step_meters
```

`base_meters` is the minimum elevation observed in the region (`float32`). `step_meters` is auto-fitted at encode time:

```
step_meters = max(0.001f, (regionMaxMeters - regionMinMeters) / 255f)
```

Result: 4096 + 8-byte header = ~4 KB per heightmap. Cm precision on flat regions (5m range -> step ~= 0.02m), ~1 m steps on the steepest mainland mountain regions (~200m range -> step ~= 0.8m). 0.25m matches the SL terrain-editor brush granularity; no relevant precision is lost.

`grid_size` and `cell_size_meters` are recorded on both tables so a future resolution change (e.g. to a 128x128 grid) is a schema-versioned migration, not a silent reinterpretation.

### 3.3 Entities

`AuctionParcelLayout` and `AuctionParcelHeightMap` both extend `BaseMutableEntity` and share their PK with `auctions(id)` via Lombok `@MapsId` + `@OneToOne(optional=false, fetch=LAZY)` to `Auction`. Both are deliberately excluded from positional record DTOs that mirror `Auction` for the same reason `AuctionParcelSnapshot` is (see CLAUDE.md "BaseEntity convention" — the `@MapsId` PK shape is incompatible with the `BaseEntity` UUID+Long pair, so the rasters keep their own shape and the auction-side accessors are lazy `@OneToOne(mappedBy="auction")` fields).

The `parcelScanIncluded` flag is a plain `Boolean` field on `Auction` with `@Builder.Default = true`. `AuctionService.create` always assigns it explicitly. A future paid-upgrade refactor changes the assignment logic; the column default exists only for migration safety on any pre-existing rows.

## 4. Bot task lifecycle

### 4.1 New task type

`BotTaskType` gains a third value, `SCAN_PARCEL`, alongside `WITHDRAW_GROUP` and `VERIFY_SELL_TO`. The .NET worker gains a matching `BotTaskType.ScanParcel` and a new handler.

### 4.2 Enqueue

A scan task is enqueued from the activation path when an auction transitions to `ACTIVE`, gated by:

1. `auction.parcelScanIncluded == true`.
2. `!auctionParcelLayoutRepository.existsByAuctionId(auction.id)` — no scan already on file.
3. No pending `SCAN_PARCEL` task already exists for this auction.

A single `BotTask` row is created with `taskType=SCAN_PARCEL` and payload `{auctionPublicId, slParcelUuid, regionName}` (carried via the existing `BotTask.taskPayload` JSONB-ish field; the exact column reuses whatever the existing two task types use). One task per auction per lifetime; re-activation after a suspend never re-enqueues because rule 2 trips. There is no periodic rescan in this spec.

### 4.3 Claim + work

The bot polls the existing `POST /api/v1/bot/tasks/claim` and gets a `BotTaskResponse` whose discriminator now includes `SCAN_PARCEL`. The handler does:

1. **Teleport** into the region using `regionName`. Landing at the default arrival point is sufficient; the bot does not need to stand on the parcel itself. `ParcelOverlay` and `Simulator.Terrain` are region-scoped data the sim sends to any connected agent.
2. **Resolve the local parcel ID.** `client.Parcels.RequestAllSimParcels()`, wait for `SimParcelsDownloaded`, find the entry whose `GlobalID == slParcelUuid`, record its `LocalID`. If no match, fail the task with reason `PARCEL_NOT_FOUND_IN_REGION`.
3. **Build the layout bitmap.** Walk `Simulator.Parcels` (a region map keyed by `(x, y)` at 4 m granularity). For each of the 4096 cells, set bit iff `cellLocalID == ourLocalID`. Pack MSB-first into a 512-byte array.
4. **Build the heightmap.** Read `Simulator.Terrain` (the 256x256 heightfield delivered as 16 x 16 m patches). For each 4 m cell, sample elevation at the cell's center (i.e. terrain row `r*4+2`, col `c*4+2`). Compute `regionMin` and `regionMax` across all 4096 sampled values, derive `step = max(0.001, (regionMax - regionMin) / 255)`, and quantize each cell to `round((sample - regionMin) / step)` clamped to `[0, 255]`. Emit `(base = regionMin, step, cells[4096])`.
5. **POST the result** to `POST /api/v1/bot/tasks/{taskId}/scan-result` with the body in section 4.4.
6. **Park.** Continue normal idle-park / next-task loop.

### 4.4 Result endpoint

`POST /api/v1/bot/tasks/{taskId}/scan-result` on `BotTaskController` (new method, parallel to the existing `/result` and `/verify-buy-owner-result`). Bot-only path (shared secret + existing bot-auth filter).

Request body (`BotScanResultRequest`):

```json
{
  "gridSize": 64,
  "cellSizeMeters": 4,
  "layoutCellsBase64": "...",
  "heightBaseMeters": 22.5,
  "heightStepMeters": 0.78,
  "heightCellsBase64": "..."
}
```

Bean Validation: `gridSize` and `cellSizeMeters` `@NotNull` with explicit `@Min`/`@Max` matching today's `gridSize == 64`, `cellSizeMeters == 4` (a single allowed value each, asserted at the controller boundary; later resolution changes raise the cap with a migration). `layoutCellsBase64` and `heightCellsBase64` `@NotBlank`. `heightStepMeters > 0`, `heightBaseMeters` finite.

Service (`ParcelScanService.applyScanResult`, `@Transactional`):

1. Load the `BotTask`. Reject with 404 if missing; 409 if its type is not `SCAN_PARCEL`; 409 if already `COMPLETED` (idempotent no-op — the bot may legitimately retry on a flaky network response).
2. Decode the two base64 strings to byte arrays. Validate `layoutCells.length == gridSize * gridSize / 8` and `heightCells.length == gridSize * gridSize`; reject with 400 on mismatch.
3. Sanity-check `heightStepMeters > 0` and `Float.isFinite(heightBaseMeters)`; reject with 400 on violation.
4. Upsert `AuctionParcelLayout` and `AuctionParcelHeightMap` for the task's `auctionId`, both with `scanned_at = now`.
5. Mark the task `COMPLETED`.

The service does NOT gate on the auction's current status. Persisting rasters for an auction that has been `CANCELLED` or `SUSPENDED` between activation and scan-result-arrival is acceptable — the rasters are immutable data tied to the auction record, and no consumer downstream reads them conditionally on status.

Idempotency: re-POSTing the same payload after a successful apply returns 409 in step 1, with no DB writes. The bot's retry policy treats 409 as success-already-recorded.

### 4.5 Failure modes

- **Estate ban / region offline / teleport failure** -> bot reports `FAILED` via the existing failure result endpoint with reason `REGION_UNREACHABLE`. `BotTaskRetryPolicy` backoff applies; after exhaustion the task moves to terminal `FAILED`. No rasters are written; no seller-facing notification (non-gating, paid-upgrade work owns the seller UX).
- **`PARCEL_NOT_FOUND_IN_REGION`** -> the parcel was deleted between activation and scan, OR the region's parcels haven't loaded after a timeout. Same retry behaviour; on terminal failure, no rasters.
- **Malformed bot payload** (length mismatch, non-finite floats) -> backend returns 400, the task stays at its current attempt count, the bot's normal failure handling kicks in.

No seller-visible signal in this slice. Adding a "scan unavailable" indicator is a frontend concern and is tracked by the paid-upgrade follow-up.

### 4.6 Bot pool

SCAN_PARCEL joins the existing pool (`bot-1` ... `bot-N`) alongside SL IM dispatch, WITHDRAW_GROUP, VERIFY_SELL_TO, and idle parking. No new bot account. Region-grouping in the existing task-queue dispatcher already keeps teleport cost reasonable.

## 5. Backend surface summary

New components:

- `backend/src/main/java/com/slparcelauctions/backend/auction/parcelscan/AuctionParcelLayout.java`
- `backend/src/main/java/com/slparcelauctions/backend/auction/parcelscan/AuctionParcelLayoutRepository.java`
- `backend/src/main/java/com/slparcelauctions/backend/auction/parcelscan/AuctionParcelHeightMap.java`
- `backend/src/main/java/com/slparcelauctions/backend/auction/parcelscan/AuctionParcelHeightMapRepository.java`
- `backend/src/main/java/com/slparcelauctions/backend/auction/parcelscan/ParcelScanService.java`
- `backend/src/main/java/com/slparcelauctions/backend/auction/parcelscan/dto/BotScanResultRequest.java`
- `backend/src/main/java/com/slparcelauctions/backend/auction/parcelscan/dto/BotScanTaskPayload.java`
- `backend/src/main/resources/db/migration/V45__auction_parcel_scan.sql`

Modified:

- `Auction.java` -- new `parcelScanIncluded` field, two new `@OneToOne(mappedBy="auction")` accessors for the rasters.
- `AuctionService.create` -- writes `parcelScanIncluded = true` (single line today).
- Auction activation path -- calls `parcelScanService.enqueueIfEligible(auction)` after the `ACTIVE` transition commits.
- `BotTaskType.java` -- adds `SCAN_PARCEL`.
- `BotTaskController.java` -- adds `POST /{taskId}/scan-result`.
- `BotTaskTypeCheckConstraintInitializer` -- adds `SCAN_PARCEL` to the allowed-values list it rewrites on boot.

Bot worker (.NET):

- `bot/src/Slpa.Bot/Tasks/Handlers/ScanParcelHandler.cs` -- the handler described in section 4.3.
- `bot/src/Slpa.Bot/Tasks/Handlers/ScanParcelHandlerTests.cs` -- IBotSession-based unit tests (no GridClient).
- `bot/src/Slpa.Bot/Backend/HttpBackendClient.cs` -- new `PostScanResultAsync(taskId, payload, ct)` method.
- `bot/src/Slpa.Bot/Tasks/BotTaskType.cs` -- adds `ScanParcel`.

No public/seller-facing read API in this slice. An admin GET will land with the frontend work.

## 6. Tests

### 6.1 Backend

- `ParcelScanServiceTest` -- `enqueueIfEligible` is idempotent (no duplicate task; skips when layout already exists; skips when `parcelScanIncluded == false`). `applyScanResult` validates length mismatches with `IllegalArgumentException` (400), rejects non-finite header values, treats a replay after success as a no-op (409).
- `BotTaskControllerScanResultTest` -- `@SpringBootTest + @AutoConfigureMockMvc` (NOT `@WebMvcTest`). Happy path returns 200 and persists both rows; non-SCAN_PARCEL task returns 409; already-completed task returns 409; malformed base64 returns 400; oversized cells returns 400.
- `AuctionActivationScanEnqueueIntegrationTest` -- activating an auction enqueues exactly one `SCAN_PARCEL` task; activating with `parcelScanIncluded=false` enqueues none; re-activating after suspend enqueues none because rasters already exist.
- Extend `BotTaskTypeCheckConstraintInitializerTest` -- assert `SCAN_PARCEL` is permitted after initializer runs.

### 6.2 Bot

- `ScanParcelHandlerTests` -- `IBotSession` fake (the .NET suite's standard test double, per `bot/README.md`). Cases:
  - Happy path: handler teleports to region, resolves local parcel id, packs the layout bitmap correctly, quantizes elevation with the documented `base + step * cell` formula, POSTs the right body.
  - `PARCEL_NOT_FOUND_IN_REGION`: parcel global id absent from the simulator's parcel list -> handler posts `FAILED` with the right reason.
  - `REGION_UNREACHABLE`: teleport throws -> handler posts `FAILED` with the right reason.
  - Heightmap quantization edge cases: a flat region (`regionMax == regionMin`) clamps `step` to `0.001f`; a 200 m range region produces a step ~0.78 m with no overflow above 255.

### 6.3 Postman

Add a sub-collection or update the existing bot section: a `POST /api/v1/bot/tasks/{taskId}/scan-result` request with the shared-secret header, valid base64 payloads, and a `pm.test` asserting 200. A second request asserting 400 on a length mismatch.

## 7. Out of scope

- **Frontend rendering** of either raster. Explicitly deferred; this spec only persists the data.
- **Periodic rescans** (e.g. detecting subdivision after activation). The auction's scan is taken once at activation and is the auction's permanent record.
- **Paid-upgrade entitlement** logic. The `parcel_scan_included` column exists today as a hard-true default; the entitlement check is future work.
- **Per-cell admin GET endpoint** for support/debug. Will land with the frontend work.
- **LSL fallback for estate-banned regions.** Bot-only today; if estate bans become a real volume problem, a seller-rezzed scanner is a future fallback path.

## 8. Decision log (2026-05-23)

- **Two outputs in one scan pass, stored side-by-side as separate entities.** One `SCAN_PARCEL` task produces both rasters atomically; no separate scan task per output.
- **Per-auction not per-parcel.** Relisting the same parcel scans fresh; no shared cache.
- **Bot via LibreMetaverse**, not seller-rezzed LSL. Bot only needs to teleport into the region (not the parcel), so parcel-level access restrictions don't apply; only estate/region-level bans block.
- **Async at activation, non-gating.** Listing activation does not wait on the scan.
- **`parcel_scan_included` flag baked in today** so the future paid-upgrade flip is a one-liner.
- **uint8 auto-scaled elevation encoding** (4 KB + 8 B per heightmap) over float32 (16 KB) or uint16 fixed (8 KB). 4x reduction with no meaningful precision loss for heatmap render.
- **Full 64x64 region for both rasters**, not parcel-only cells. Index-aligned, gives the future frontend region context.
- **Per-type result endpoint** (`/scan-result`) over a polymorphic `/result`. Matches the existing precedent (`/result`, `/verify-buy-owner-result`).
