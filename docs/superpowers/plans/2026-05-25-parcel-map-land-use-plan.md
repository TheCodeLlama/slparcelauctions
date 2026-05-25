# Parcel Map Land Use Mode + 3D Legend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a second 2D rendering mode (Land Use) to the auction-detail parcel map and refresh the 3D view's chrome (relocate toggle, add gradient legend).

**Architecture:** Bot classifies each of 4096 grid cells from `sim.Parcels` metadata (existing `RequestAllSimParcels` trip; no new task type), emits a 4 KB byte array. Backend stores in a new `AuctionParcelLandUse` table (sibling to existing height-map / layout tables). Frontend types extended; new hook + toggle + legend components mirror the existing 3D pattern; `ParcelMap.tsx` and `ParcelMap3D.tsx` rewire their chrome to a row-1-legend / row-2-toggle layout.

**Tech Stack:**
- Bot: .NET 8, LibreMetaverse, xUnit, Moq, FluentAssertions
- Backend: Spring Boot 4, Java 24, JPA / Hibernate, Lombok, Flyway, JUnit 5
- Frontend: Next.js 16, React 19, TypeScript 5, Tailwind 4, Vitest, React Testing Library

---

## Pre-resolved gotchas (do NOT let a reviewer "correct" these back)

These were locked during brainstorming. If a reviewer flags any of them, push back with this list.

1. **Pure RGB primaries** for the Land Use palette: Listed `rgb(0,255,0)`, Abandoned `rgb(0,0,255)`, ForSale `rgb(255,255,0)`, Protected `rgb(255,0,0)`, background white `rgb(255,255,255)`. Not Tailwind 500 tones. User explicit: "I didn't say off red, or pale red, or pink."
2. **No cyan outline in Land Use mode.** Cyan outline stays in Elevation mode.
3. **One byte per cell, no bitpacking.** Values 0..4 (Other / Listed / Abandoned / ForSale / Protected). 4096 bytes.
4. **Toggle row BELOW the legend.** Row 1 = legend, Row 2 = toggle. All 4 mode views.
5. **3D view gains a gradient legend** it doesn't have today.
6. **3D toggle moves from `absolute top-2 right-2` over the canvas to row 2.**
7. **Precedence: Listed > Protected > Abandoned > ForSale > Other.** Listed always wins.
8. **Linden detection = case-insensitive substring match on `Parcel.Name`** ("Abandoned Land", "Protected Land"). NOT owner-UUID match. Player-rename misclassifications are accepted.
9. **No `AuthBuyerID` filter on ForSale.** Directed sales count too.
10. **One-shot at listing creation.** No periodic refresh.
11. **Bundle into existing SCAN_PARCEL task.** No new task type.
12. **Partial-download tolerance:** missing data → Other, structured warning logs.
13. **localStorage key for 2D mode:** `slpa:parcel-map:2d-color` (mirrors existing `slpa:parcel-map:3d-color`). Default `"elevation"`.
14. **ARIA radio-group pattern** for new 2D toggle. Mirror existing `ParcelMap3DColorModeToggle`.
15. **Pre-existing scans get `landUseCellsBase64: null`.** Toggle's Land Use option is `aria-disabled` then.
16. **Hover tooltip in Land Use mode:** "(col\*4, row\*4): Category name" plain text. No per-parcel detail.
17. **The existing 3D mode hook is `useParcelMapColorMode` (asymmetric historical name).** The new 2D hook will be `useParcelMap2DColorMode`. Do NOT rename the existing one.

---

## File Inventory

**New (bot):**
- `bot/src/Slpa.Bot/Tasks/ParcelLandUseCategory.cs` — byte enum (Other=0, Listed=1, Abandoned=2, ForSale=3, Protected=4)
- `bot/src/Slpa.Bot/Tasks/ParcelLandUseClassifier.cs` — pure class with one static method, classifies a 64×64 cell grid into a 4096-byte array
- `bot/tests/Slpa.Bot.Tests/ParcelLandUseClassifierTests.cs`

**New (backend):**
- `backend/src/main/resources/db/migration/V46__auction_parcel_land_use.sql`
- `backend/src/main/java/com/slparcelauctions/backend/auction/parcelscan/AuctionParcelLandUse.java`
- `backend/src/main/java/com/slparcelauctions/backend/auction/parcelscan/AuctionParcelLandUseRepository.java`

**New (frontend):**
- `frontend/src/hooks/useParcelMap2DColorMode.ts`
- `frontend/src/hooks/useParcelMap2DColorMode.test.tsx`
- `frontend/src/components/auction/ParcelMap2DColorModeToggle.tsx`
- `frontend/src/components/auction/ParcelMap2DColorModeToggle.test.tsx`
- `frontend/src/lib/parcelMap/landUseColors.ts`
- `frontend/src/lib/parcelMap/landUseColors.test.ts`
- `frontend/src/components/auction/ParcelMapLandUseLegend.tsx`
- `frontend/src/components/auction/ParcelMap3DLegend.tsx`
- `frontend/src/components/auction/ParcelMap3DLegend.test.tsx`
- `frontend/src/components/auction/ParcelMapLandUseLegend.test.tsx`

**Modified (bot):**
- `bot/src/Slpa.Bot/Backend/Models/ScanResultRequest.cs` — add `LandUseCellsBase64` field
- `bot/src/Slpa.Bot/Sl/ParcelSnapshot.cs` — already has needed fields, no change expected (verify in Task 1)
- `bot/src/Slpa.Bot/Sl/IBotSession.cs` — add `GetAllSimParcelSnapshots()` method
- `bot/src/Slpa.Bot/Sl/LibreMetaverseBotSession.cs` — implement `GetAllSimParcelSnapshots()`
- `bot/src/Slpa.Bot/Tasks/ScanParcelHandler.cs` — call classifier after layout pass, attach `LandUseCellsBase64`
- `bot/tests/Slpa.Bot.Tests/Fakes/FakeBotSession.cs` (or wherever the fake lives) — add policy hook for the new method
- `bot/tests/Slpa.Bot.Tests/ScanParcelHandlerTests.cs` — extend happy-path test for the new field

**Modified (backend):**
- `backend/src/main/java/com/slparcelauctions/backend/auction/parcelscan/dto/BotScanResultRequest.java` — add `landUseCellsBase64`
- `backend/src/main/java/com/slparcelauctions/backend/auction/parcelscan/dto/ParcelScanResponse.java` — add `landUseCellsBase64` (nullable)
- `backend/src/main/java/com/slparcelauctions/backend/auction/parcelscan/ParcelScanService.java` — extend `applyScanResult` to decode + persist
- `backend/src/main/java/com/slparcelauctions/backend/auction/parcelscan/ParcelScanReadService.java` — extend `findForAuction` to assemble nullable field
- `backend/src/test/java/com/slparcelauctions/backend/auction/parcelscan/ParcelScanServiceTest.java` (or equivalent) — extend round-trip test
- `backend/src/test/java/com/slparcelauctions/backend/auction/parcelscan/ParcelScanReadServiceTest.java` (or equivalent) — assert nullable shape

**Modified (frontend):**
- `frontend/src/types/auction.ts` — extend `ParcelScanResponse` interface
- `frontend/src/lib/parcelMap/encoding.ts` — add `decodeLandUseCell` helper
- `frontend/src/components/auction/ParcelMap.tsx` — wire 2D mode + branch paint loop + tooltip + outline gate + rename inline legend → `ParcelMapElevationLegend`
- `frontend/src/components/auction/ParcelMap.test.tsx` — add Land Use mode + outline-hidden cases
- `frontend/src/components/auction/ParcelMap3D.tsx` — relocate existing toggle + render new `ParcelMap3DLegend`
- `frontend/src/components/auction/ParcelMap3D.test.tsx` — assert legend renders, assert toggle no longer overlays canvas

**Untouched:**
- `ParcelMapTabs.tsx`, `useParcelMapView.ts`, `useParcelMapColorMode.ts`, `ParcelMap3DColorModeToggle.tsx`, `ParcelMap3DSkeleton.tsx`, all geometry code in `lib/parcelMap3D/`, `lib/parcelMap/colors.ts` (elevation/slope helpers untouched), `frontend/scripts/verify-no-inline-styles.sh` (Task 10 may need to add an allowlist entry if the new gradient legend uses inline styles)

---

## Task 1: Bot — DTO + ParcelLandUseCategory enum + IBotSession new method

**Files:**
- Modify: `bot/src/Slpa.Bot/Backend/Models/ScanResultRequest.cs`
- Create: `bot/src/Slpa.Bot/Tasks/ParcelLandUseCategory.cs`
- Modify: `bot/src/Slpa.Bot/Sl/IBotSession.cs`
- Modify: `bot/src/Slpa.Bot/Sl/LibreMetaverseBotSession.cs`

- [ ] **Step 1: Extend `ScanResultRequest` with `LandUseCellsBase64`**

Edit `bot/src/Slpa.Bot/Backend/Models/ScanResultRequest.cs` to its final form:

```csharp
namespace Slpa.Bot.Backend.Models;

/// <summary>
/// Body for POST /api/v1/bot/tasks/{taskId}/scan-result.
/// All Base64 fields are standard (non-URL-safe) Base64 with no line breaks.
/// LandUseCellsBase64 decodes to 4096 bytes, one byte per cell, values 0..4
/// (see <see cref="Slpa.Bot.Tasks.ParcelLandUseCategory"/>).
/// </summary>
public sealed record ScanResultRequest(
    int GridSize,
    int CellSizeMeters,
    string LayoutCellsBase64,
    float HeightBaseMeters,
    float HeightStepMeters,
    string HeightCellsBase64,
    string LandUseCellsBase64);
```

- [ ] **Step 2: Create the `ParcelLandUseCategory` enum**

Write `bot/src/Slpa.Bot/Tasks/ParcelLandUseCategory.cs`:

```csharp
namespace Slpa.Bot.Tasks;

/// <summary>
/// Per-cell classification of land use for the 2D parcel-map "Land Use" mode.
/// Encoded as a single byte per cell over the wire; values are stable and
/// must not be renumbered (the backend stores them raw, the frontend keys
/// the palette off the numeric value).
/// </summary>
public enum ParcelLandUseCategory : byte
{
    /// <summary>Default. Player-owned, not for sale, or missing-data fallback.</summary>
    Other = 0,

    /// <summary>Cell belongs to the listed parcel. Wins precedence.</summary>
    Listed = 1,

    /// <summary>Linden-owned parcel whose Name contains "Abandoned Land".</summary>
    Abandoned = 2,

    /// <summary>Player-owned parcel with the ForSale flag set.</summary>
    ForSale = 3,

    /// <summary>Linden-owned parcel whose Name contains "Protected Land".</summary>
    Protected = 4,
}
```

- [ ] **Step 3: Add `GetAllSimParcelSnapshots` to `IBotSession`**

Append this method declaration to `bot/src/Slpa.Bot/Sl/IBotSession.cs` (right after `WaitForRegionTerrainAsync` so parcel-related methods cluster together):

```csharp
/// <summary>
/// Returns a snapshot of every parcel currently cached in the simulator's
/// Parcels dictionary, keyed by LocalID. Use for per-cell classification
/// passes (e.g. land-use category) that need parcel metadata across many
/// cells. Call after <see cref="RequestAllSimParcelsAsync"/> so the cache
/// is populated. Missing LocalIDs (partial-download race) simply won't
/// appear in the returned dict; callers should TryGetValue and treat
/// misses as "unknown". Returns an empty dict if no simulator is resolved.
/// </summary>
IReadOnlyDictionary<uint, ParcelSnapshot> GetAllSimParcelSnapshots();
```

- [ ] **Step 4: Implement `GetAllSimParcelSnapshots` in `LibreMetaverseBotSession`**

Add to `bot/src/Slpa.Bot/Sl/LibreMetaverseBotSession.cs` (place near `GetRegionParcelLocalIds` for cohesion, around line 588):

```csharp
public IReadOnlyDictionary<uint, ParcelSnapshot> GetAllSimParcelSnapshots()
{
    var sim = _client.Network.CurrentSim;
    var result = new Dictionary<uint, ParcelSnapshot>();
    if (sim is null) return result;

    // sim.Parcels is LibreMetaverse's thread-safe InternalDictionary<int, Parcel>;
    // Copy() returns a regular Dictionary snapshot we can iterate without
    // holding the lock. The Parcel objects are immutable per-snapshot in
    // practice so reading fields here is safe.
    foreach (var kvp in sim.Parcels.Copy())
    {
        var p = kvp.Value;
        result[(uint)p.LocalID] = new ParcelSnapshot(
            OwnerId: p.OwnerID,
            GroupId: p.GroupID,
            IsGroupOwned: p.IsGroupOwned,
            AuthBuyerId: p.AuthBuyerID,
            SalePrice: p.SalePrice,
            ForSale: p.Flags.HasFlag(OpenMetaverse.ParcelFlags.ForSale),
            Name: p.Name ?? string.Empty,
            Description: p.Desc ?? string.Empty,
            AreaSqm: p.Area,
            MaxPrims: p.MaxPrims,
            Category: (int)p.Category,
            SnapshotId: p.SnapshotID,
            Flags: (uint)p.Flags);
    }
    return result;
}
```

Note: read `bot/src/Slpa.Bot/Sl/ParcelSnapshot.cs` first to confirm the record's exact constructor parameter names. If a name or type drifts (e.g. `OwnerId` vs `OwnerID`), match the existing record exactly.

- [ ] **Step 5: Build the bot to verify the additions compile**

Run:

```bash
dotnet build C:/Users/heath/Repos/Personal/slpa/bot/Slpa.Bot.sln
```

Expected: success, zero errors. If a `ParcelSnapshot` field name doesn't match, fix the constructor call before proceeding.

- [ ] **Step 6: Commit**

```bash
git -C C:/Users/heath/Repos/Personal/slpa add \
  bot/src/Slpa.Bot/Backend/Models/ScanResultRequest.cs \
  bot/src/Slpa.Bot/Tasks/ParcelLandUseCategory.cs \
  bot/src/Slpa.Bot/Sl/IBotSession.cs \
  bot/src/Slpa.Bot/Sl/LibreMetaverseBotSession.cs

git -C C:/Users/heath/Repos/Personal/slpa commit -m "feat(bot): land-use enum + IBotSession.GetAllSimParcelSnapshots

Adds ParcelLandUseCategory enum (5 values, byte-encoded) and a new
IBotSession method that returns a snapshot of every parcel in the
current sim's cache. Extends ScanResultRequest with the eventual
LandUseCellsBase64 field. No behavior change yet; classifier and
handler integration land in subsequent commits.

Spec: docs/superpowers/specs/2026-05-25-parcel-map-land-use-design.md"
```

---

## Task 2: Bot — ParcelLandUseClassifier + tests

**Files:**
- Create: `bot/src/Slpa.Bot/Tasks/ParcelLandUseClassifier.cs`
- Create: `bot/tests/Slpa.Bot.Tests/ParcelLandUseClassifierTests.cs`

- [ ] **Step 1: Write the failing test file**

Create `bot/tests/Slpa.Bot.Tests/ParcelLandUseClassifierTests.cs`:

```csharp
using System.Collections.Generic;
using FluentAssertions;
using OpenMetaverse;
using Slpa.Bot.Sl;
using Slpa.Bot.Tasks;
using Xunit;

namespace Slpa.Bot.Tests;

public sealed class ParcelLandUseClassifierTests
{
    private static ParcelSnapshot Snap(string name = "", bool forSale = false) =>
        new(
            OwnerId: UUID.Zero,
            GroupId: UUID.Zero,
            IsGroupOwned: false,
            AuthBuyerId: UUID.Zero,
            SalePrice: 0,
            ForSale: forSale,
            Name: name,
            Description: string.Empty,
            AreaSqm: 0,
            MaxPrims: 0,
            Category: 0,
            SnapshotId: UUID.Zero,
            Flags: 0);

    private static uint[,] EmptyGrid()
    {
        var g = new uint[64, 64];
        // All zeros = no LocalID anywhere.
        return g;
    }

    [Fact]
    public void Classify_AllCellsZero_ReturnsAllOther()
    {
        var grid = EmptyGrid();
        var snapshots = new Dictionary<uint, ParcelSnapshot>();

        var result = ParcelLandUseClassifier.Classify(grid, snapshots, listedLocalId: 0);

        result.Should().HaveCount(4096);
        result.Should().AllBeEquivalentTo((byte)ParcelLandUseCategory.Other);
    }

    [Fact]
    public void Classify_ListedParcelCells_ReturnListedRegardlessOfName()
    {
        var grid = EmptyGrid();
        // Cells (10, 5), (10, 6), (11, 5) are the listed parcel.
        grid[10, 5] = 42; grid[10, 6] = 42; grid[11, 5] = 42;
        // Even with a name that would otherwise match Protected, Listed wins.
        var snapshots = new Dictionary<uint, ParcelSnapshot>
        {
            [42] = Snap(name: "Protected Land", forSale: true),
        };

        var result = ParcelLandUseClassifier.Classify(grid, snapshots, listedLocalId: 42);

        result[10 * 64 + 5].Should().Be((byte)ParcelLandUseCategory.Listed);
        result[10 * 64 + 6].Should().Be((byte)ParcelLandUseCategory.Listed);
        result[11 * 64 + 5].Should().Be((byte)ParcelLandUseCategory.Listed);
    }

    [Fact]
    public void Classify_AbandonedLandName_ReturnsAbandoned()
    {
        var grid = EmptyGrid();
        grid[0, 0] = 99;
        var snapshots = new Dictionary<uint, ParcelSnapshot>
        {
            [99] = Snap(name: "Abandoned Land"),
        };

        var result = ParcelLandUseClassifier.Classify(grid, snapshots, listedLocalId: 0);

        result[0].Should().Be((byte)ParcelLandUseCategory.Abandoned);
    }

    [Fact]
    public void Classify_ProtectedLandName_ReturnsProtected()
    {
        var grid = EmptyGrid();
        grid[0, 0] = 100;
        var snapshots = new Dictionary<uint, ParcelSnapshot>
        {
            [100] = Snap(name: "Protected Land"),
        };

        var result = ParcelLandUseClassifier.Classify(grid, snapshots, listedLocalId: 0);

        result[0].Should().Be((byte)ParcelLandUseCategory.Protected);
    }

    [Fact]
    public void Classify_ProtectedWinsOverForSaleFlag()
    {
        var grid = EmptyGrid();
        grid[0, 0] = 101;
        var snapshots = new Dictionary<uint, ParcelSnapshot>
        {
            [101] = Snap(name: "Protected Land", forSale: true),
        };

        var result = ParcelLandUseClassifier.Classify(grid, snapshots, listedLocalId: 0);

        result[0].Should().Be((byte)ParcelLandUseCategory.Protected);
    }

    [Fact]
    public void Classify_AbandonedWinsOverForSaleFlag()
    {
        var grid = EmptyGrid();
        grid[0, 0] = 102;
        var snapshots = new Dictionary<uint, ParcelSnapshot>
        {
            [102] = Snap(name: "Abandoned Land", forSale: true),
        };

        var result = ParcelLandUseClassifier.Classify(grid, snapshots, listedLocalId: 0);

        result[0].Should().Be((byte)ParcelLandUseCategory.Abandoned);
    }

    [Fact]
    public void Classify_ForSaleFlagOnNonLinden_ReturnsForSale()
    {
        var grid = EmptyGrid();
        grid[0, 0] = 200;
        var snapshots = new Dictionary<uint, ParcelSnapshot>
        {
            [200] = Snap(name: "Player Parcel", forSale: true),
        };

        var result = ParcelLandUseClassifier.Classify(grid, snapshots, listedLocalId: 0);

        result[0].Should().Be((byte)ParcelLandUseCategory.ForSale);
    }

    [Fact]
    public void Classify_NameSubstringIsCaseInsensitive()
    {
        var grid = EmptyGrid();
        grid[0, 0] = 300;
        grid[0, 1] = 301;
        var snapshots = new Dictionary<uint, ParcelSnapshot>
        {
            [300] = Snap(name: "abandoned land"),
            [301] = Snap(name: "PROTECTED LAND - main road"),
        };

        var result = ParcelLandUseClassifier.Classify(grid, snapshots, listedLocalId: 0);

        result[0].Should().Be((byte)ParcelLandUseCategory.Abandoned);
        result[1].Should().Be((byte)ParcelLandUseCategory.Protected);
    }

    [Fact]
    public void Classify_LocalIdNotInSnapshots_ReturnsOther()
    {
        // Partial-download tolerance: LocalID exists in grid but not in dict.
        var grid = EmptyGrid();
        grid[5, 5] = 999;
        var snapshots = new Dictionary<uint, ParcelSnapshot>(); // empty

        var result = ParcelLandUseClassifier.Classify(grid, snapshots, listedLocalId: 0);

        result[5 * 64 + 5].Should().Be((byte)ParcelLandUseCategory.Other);
    }

    [Fact]
    public void Classify_LocalIdZero_ReturnsOtherWithoutDictLookup()
    {
        // LocalID 0 is the sentinel for "no parcel data here"; should never
        // hit the snapshots dict.
        var grid = EmptyGrid();
        // grid is all zeros; just make sure the result is Other and no
        // exception fires.
        var snapshots = new Dictionary<uint, ParcelSnapshot>
        {
            [0] = Snap(name: "Protected Land"), // would misclassify if we looked it up
        };

        var result = ParcelLandUseClassifier.Classify(grid, snapshots, listedLocalId: 0);

        result.Should().AllBeEquivalentTo((byte)ParcelLandUseCategory.Other);
    }

    [Fact]
    public void Classify_EmptyName_ReturnsOther()
    {
        var grid = EmptyGrid();
        grid[0, 0] = 400;
        var snapshots = new Dictionary<uint, ParcelSnapshot>
        {
            [400] = Snap(name: string.Empty, forSale: false),
        };

        var result = ParcelLandUseClassifier.Classify(grid, snapshots, listedLocalId: 0);

        result[0].Should().Be((byte)ParcelLandUseCategory.Other);
    }

    [Fact]
    public void Classify_MixedRegion_AssignsCorrectCategoriesPerCell()
    {
        var grid = EmptyGrid();
        grid[0, 0] = 1;   // listed
        grid[0, 1] = 2;   // protected
        grid[0, 2] = 3;   // abandoned
        grid[0, 3] = 4;   // for sale
        grid[0, 4] = 5;   // other (no for-sale flag)
        var snapshots = new Dictionary<uint, ParcelSnapshot>
        {
            [1] = Snap(name: "Doesn't matter, listed wins"),
            [2] = Snap(name: "Protected Land"),
            [3] = Snap(name: "Abandoned Land"),
            [4] = Snap(name: "Player parcel", forSale: true),
            [5] = Snap(name: "Player parcel"),
        };

        var result = ParcelLandUseClassifier.Classify(grid, snapshots, listedLocalId: 1);

        result[0].Should().Be((byte)ParcelLandUseCategory.Listed);
        result[1].Should().Be((byte)ParcelLandUseCategory.Protected);
        result[2].Should().Be((byte)ParcelLandUseCategory.Abandoned);
        result[3].Should().Be((byte)ParcelLandUseCategory.ForSale);
        result[4].Should().Be((byte)ParcelLandUseCategory.Other);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
dotnet test C:/Users/heath/Repos/Personal/slpa/bot/Slpa.Bot.sln \
  --filter "FullyQualifiedName~ParcelLandUseClassifierTests"
```

Expected: build error or test failure (`ParcelLandUseClassifier` does not exist).

- [ ] **Step 3: Write minimal classifier implementation**

Create `bot/src/Slpa.Bot/Tasks/ParcelLandUseClassifier.cs`:

```csharp
using System.Collections.Generic;
using Slpa.Bot.Sl;

namespace Slpa.Bot.Tasks;

/// <summary>
/// Pure per-cell land-use classifier. No I/O, no logging — callers do that.
/// Spec: docs/superpowers/specs/2026-05-25-parcel-map-land-use-design.md.
///
/// Precedence (highest first): Listed &gt; Protected &gt; Abandoned &gt; ForSale &gt; Other.
/// The listed parcel always wins; Linden categories (Protected, Abandoned)
/// beat ForSale because Linden parcels can carry the ForSale flag in odd states.
/// </summary>
public static class ParcelLandUseClassifier
{
    /// <summary>
    /// Classifies a 64x64 grid of parcel LocalIDs into a 4096-byte array of
    /// <see cref="ParcelLandUseCategory"/> values, row-major SW-first.
    /// </summary>
    /// <param name="parcelGrid">64x64 LocalIDs as returned by
    /// <see cref="IBotSession.GetRegionParcelLocalIds"/>. A value of 0 means
    /// no parcel data for that cell (partial-download race or unoccupied);
    /// classified as Other.</param>
    /// <param name="snapshots">Snapshot of every parcel cached in the sim,
    /// keyed by LocalID. As returned by
    /// <see cref="IBotSession.GetAllSimParcelSnapshots"/>. LocalIDs present
    /// in <paramref name="parcelGrid"/> but absent from this dict are
    /// classified as Other (partial-download tolerance).</param>
    /// <param name="listedLocalId">LocalID of the listed parcel, the one
    /// being auctioned. Cells matching this value are classified as Listed
    /// regardless of the parcel's name or flags.</param>
    public static byte[] Classify(
        uint[,] parcelGrid,
        IReadOnlyDictionary<uint, ParcelSnapshot> snapshots,
        uint listedLocalId)
    {
        var cells = new byte[4096];
        for (int row = 0; row < 64; row++)
        {
            for (int col = 0; col < 64; col++)
            {
                cells[row * 64 + col] = (byte)ClassifyCell(
                    parcelGrid[row, col], snapshots, listedLocalId);
            }
        }
        return cells;
    }

    private static ParcelLandUseCategory ClassifyCell(
        uint localId,
        IReadOnlyDictionary<uint, ParcelSnapshot> snapshots,
        uint listedLocalId)
    {
        if (localId == 0) return ParcelLandUseCategory.Other;
        if (localId == listedLocalId) return ParcelLandUseCategory.Listed;
        if (!snapshots.TryGetValue(localId, out var snap))
            return ParcelLandUseCategory.Other;

        var name = snap.Name ?? string.Empty;
        if (name.Contains("Protected Land", System.StringComparison.OrdinalIgnoreCase))
            return ParcelLandUseCategory.Protected;
        if (name.Contains("Abandoned Land", System.StringComparison.OrdinalIgnoreCase))
            return ParcelLandUseCategory.Abandoned;
        if (snap.ForSale)
            return ParcelLandUseCategory.ForSale;
        return ParcelLandUseCategory.Other;
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
dotnet test C:/Users/heath/Repos/Personal/slpa/bot/Slpa.Bot.sln \
  --filter "FullyQualifiedName~ParcelLandUseClassifierTests"
```

Expected: all 12 tests pass.

- [ ] **Step 5: Run the full bot test suite**

```bash
dotnet test C:/Users/heath/Repos/Personal/slpa/bot/Slpa.Bot.sln
```

Expected: all tests pass (the new ones plus all existing).

- [ ] **Step 6: Commit**

```bash
git -C C:/Users/heath/Repos/Personal/slpa add \
  bot/src/Slpa.Bot/Tasks/ParcelLandUseClassifier.cs \
  bot/tests/Slpa.Bot.Tests/ParcelLandUseClassifierTests.cs

git -C C:/Users/heath/Repos/Personal/slpa commit -m "feat(bot): ParcelLandUseClassifier pure helper + tests

Classifies a 64x64 grid of parcel LocalIDs into 4096 bytes of
ParcelLandUseCategory values. Listed > Protected > Abandoned > ForSale
> Other; LocalID 0 and dict-miss both fall to Other (partial-download
tolerance). 12 tests cover every precedence path."
```

---

## Task 3: Bot — wire classifier into ScanParcelHandler + integration test

**Files:**
- Modify: `bot/src/Slpa.Bot/Tasks/ScanParcelHandler.cs`
- Modify: `bot/tests/Slpa.Bot.Tests/ScanParcelHandlerTests.cs`
- Modify: bot test fake (location TBD — find via `grep -r "FakeBotSession" bot/tests`)

- [ ] **Step 1: Find the bot test fake**

```bash
grep -rln "class FakeBotSession" C:/Users/heath/Repos/Personal/slpa/bot/tests/
```

Read whatever file it returns. You'll need to add a `GetAllSimParcelSnapshotsPolicy` property + the interface impl, mirroring how the existing parcel/terrain policy hooks work (e.g. `RequestAllSimParcelsPolicy`, `ParcelLocalIdsPolicy`, `TerrainHeightsPolicy` per `ScanParcelHandlerTests.cs`).

- [ ] **Step 2: Add the fake's new policy hook + interface impl**

Pattern (the exact file and indentation will be visible in the fake source):

```csharp
public Func<IReadOnlyDictionary<uint, ParcelSnapshot>>? GetAllSimParcelSnapshotsPolicy { get; set; }

public IReadOnlyDictionary<uint, ParcelSnapshot> GetAllSimParcelSnapshots() =>
    GetAllSimParcelSnapshotsPolicy?.Invoke() ?? new Dictionary<uint, ParcelSnapshot>();
```

- [ ] **Step 3: Extend the happy-path test in `ScanParcelHandlerTests.cs`**

Find the existing `HappyPath_PostsCorrectBody` test (around line 96 per the survey). Add the snapshots policy stub to its setup, then extend the assertions:

```csharp
// In the existing arrange block, after _session.TerrainHeightsPolicy = ...
_session.GetAllSimParcelSnapshotsPolicy = () => new Dictionary<uint, ParcelSnapshot>
{
    [ourLocalId] = MakeSnapshot(name: "The listed parcel"),
};

// ...handler runs, _captured is populated...

// After existing height/layout assertions, add:
var landUse = Convert.FromBase64String(_captured.LandUseCellsBase64);
landUse.Should().HaveCount(4096);

// Every cell in the parcel rectangle should be Listed; everything else Other.
for (int row = 0; row < 64; row++)
{
    for (int col = 0; col < 64; col++)
    {
        bool inParcel = row >= 10 && row <= 20 && col >= 5 && col <= 15;
        var expected = inParcel
            ? (byte)ParcelLandUseCategory.Listed
            : (byte)ParcelLandUseCategory.Other;
        landUse[row * 64 + col].Should().Be(expected,
            "cell ({Row},{Col}) should be {Expected}", row, col, expected);
    }
}
```

`MakeSnapshot` helper to add at class scope if it doesn't already exist:

```csharp
private static ParcelSnapshot MakeSnapshot(string name = "", bool forSale = false) =>
    new(UUID.Zero, UUID.Zero, false, UUID.Zero, 0, forSale,
        name, string.Empty, 0, 0, 0, UUID.Zero, 0);
```

(Adjust constructor positional args to match the actual `ParcelSnapshot` record.)

- [ ] **Step 4: Add a second integration test covering a mixed-category region**

Add to `ScanParcelHandlerTests.cs`:

```csharp
[Fact]
public async Task LandUseCells_ClassifiesMixedRegion()
{
    const uint ourLocalId = 42u;
    const uint abandonedId = 100u;
    const uint protectedId = 101u;
    const uint forSaleId = 200u;
    const uint otherId = 201u;

    _session.RequestAllSimParcelsPolicy = (_, _) => (int)ourLocalId;
    _session.ParcelLocalIdsPolicy = () =>
    {
        var grid = new uint[64, 64];
        // Listed parcel: rows 10..20 cols 5..15
        for (int r = 10; r <= 20; r++)
            for (int c = 5; c <= 15; c++)
                grid[r, c] = ourLocalId;
        // Abandoned blob: top-right corner
        for (int r = 0; r <= 4; r++)
            for (int c = 60; c <= 63; c++)
                grid[r, c] = abandonedId;
        // Protected road: row 30, all cols
        for (int c = 0; c <= 63; c++)
            grid[30, c] = protectedId;
        // For Sale block: rows 40..45, cols 0..4
        for (int r = 40; r <= 45; r++)
            for (int c = 0; c <= 4; c++)
                grid[r, c] = forSaleId;
        // Other (player-owned, no flags): rows 50..52, cols 50..52
        for (int r = 50; r <= 52; r++)
            for (int c = 50; c <= 52; c++)
                grid[r, c] = otherId;
        return grid;
    };
    _session.TerrainHeightsPolicy = () => FlatTerrain(30f);
    _session.GetAllSimParcelSnapshotsPolicy = () => new Dictionary<uint, ParcelSnapshot>
    {
        [ourLocalId] = MakeSnapshot(name: "listed"),
        [abandonedId] = MakeSnapshot(name: "Abandoned Land"),
        [protectedId] = MakeSnapshot(name: "Protected Land"),
        [forSaleId] = MakeSnapshot(name: "for sale player parcel", forSale: true),
        [otherId] = MakeSnapshot(name: "private player parcel"),
    };

    await NewHandler().HandleAsync(BuildTask(), CancellationToken.None);

    _captured.Should().NotBeNull();
    var landUse = Convert.FromBase64String(_captured!.LandUseCellsBase64);
    landUse.Should().HaveCount(4096);

    landUse[10 * 64 + 5].Should().Be((byte)ParcelLandUseCategory.Listed);
    landUse[0 * 64 + 63].Should().Be((byte)ParcelLandUseCategory.Abandoned);
    landUse[30 * 64 + 32].Should().Be((byte)ParcelLandUseCategory.Protected);
    landUse[40 * 64 + 0].Should().Be((byte)ParcelLandUseCategory.ForSale);
    landUse[50 * 64 + 50].Should().Be((byte)ParcelLandUseCategory.Other);
    landUse[63 * 64 + 0].Should().Be((byte)ParcelLandUseCategory.Other); // unset cell
}
```

- [ ] **Step 5: Run tests to verify they fail**

```bash
dotnet test C:/Users/heath/Repos/Personal/slpa/bot/Slpa.Bot.sln \
  --filter "FullyQualifiedName~ScanParcelHandlerTests"
```

Expected: failure — `_captured.LandUseCellsBase64` is empty/missing because the handler hasn't been wired yet.

- [ ] **Step 6: Wire the classifier into `ScanParcelHandler`**

Edit `bot/src/Slpa.Bot/Tasks/ScanParcelHandler.cs`. After the existing Step 3 layout-bitmap loop (around line 93), and before "Step 4: Wait for terrain patches" (line 95), insert the land-use classification pass:

```csharp
// Step 3b: Classify per-cell land use from the same parcel grid + the
// sim's parcel snapshot cache. Mirrors the layout pass's partial-download
// tolerance: missing data classifies as Other.
var snapshots = _session.GetAllSimParcelSnapshots();
var landUseCells = ParcelLandUseClassifier.Classify(
    parcelGrid, snapshots, ourLocalId);

// Diagnostic: count of cells with no parcel data (LocalID 0). High values
// suggest the RequestAllSimParcels download was partial.
int unmappedCells = 0;
for (int row = 0; row < 64; row++)
    for (int col = 0; col < 64; col++)
        if (parcelGrid[row, col] == 0) unmappedCells++;
if (unmappedCells > 0)
{
    _log.LogWarning(
        "SCAN_PARCEL {Id}: {Unmapped}/4096 cells had no parcel LocalID; " +
        "classified as Other (partial sim.Parcels download)",
        task.Id, unmappedCells);
}
```

Then in the body construction (around line 157), add `LandUseCellsBase64`:

```csharp
var body = new ScanResultRequest(
    GridSize: 64,
    CellSizeMeters: 4,
    LayoutCellsBase64: Convert.ToBase64String(layoutCells),
    HeightBaseMeters: baseM,
    HeightStepMeters: step,
    HeightCellsBase64: Convert.ToBase64String(heightCells),
    LandUseCellsBase64: Convert.ToBase64String(landUseCells));
```

- [ ] **Step 7: Run tests to verify they pass**

```bash
dotnet test C:/Users/heath/Repos/Personal/slpa/bot/Slpa.Bot.sln
```

Expected: all tests pass (new land-use tests + all existing).

- [ ] **Step 8: Commit**

```bash
git -C C:/Users/heath/Repos/Personal/slpa add \
  bot/src/Slpa.Bot/Tasks/ScanParcelHandler.cs \
  bot/tests/Slpa.Bot.Tests/ScanParcelHandlerTests.cs \
  bot/tests/Slpa.Bot.Tests/Fakes/

git -C C:/Users/heath/Repos/Personal/slpa commit -m "feat(bot): wire ParcelLandUseClassifier into ScanParcelHandler

Per-cell land-use classification now runs alongside the existing
layout-bitmap pass, using the same sim.Parcels snapshot. Encodes 4096
bytes of category values into the new LandUseCellsBase64 DTO field. Adds
a diagnostic warning if any cells have no parcel data (partial download
race). Two new integration tests cover the happy path + a mixed-category
sample region."
```

---

## Task 4: Backend — Flyway migration + AuctionParcelLandUse entity + repository

**Files:**
- Create: `backend/src/main/resources/db/migration/V46__auction_parcel_land_use.sql`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/parcelscan/AuctionParcelLandUse.java`
- Create: `backend/src/main/java/com/slparcelauctions/backend/auction/parcelscan/AuctionParcelLandUseRepository.java`

- [ ] **Step 1: Confirm V46 is the next migration number**

```bash
ls C:/Users/heath/Repos/Personal/slpa/backend/src/main/resources/db/migration/ | sort -V | tail -3
```

Expected last line: `V45__auction_parcel_scan.sql`. If a newer migration has landed since this plan was written, use the next number (V47, V48, ...) and adjust filenames + javadoc accordingly.

- [ ] **Step 2: Write the migration**

Create `backend/src/main/resources/db/migration/V46__auction_parcel_land_use.sql`:

```sql
-- Per-auction region-wide land-use classification raster. 64x64 cells on
-- the same grid as auction_parcel_layouts / auction_parcel_height_maps.
-- cells = 4096 uint8s, row-major SW-first. Values 0..4:
--   0 = Other      (player-owned, not for sale; or missing-data fallback)
--   1 = Listed     (the auctioned parcel's cells; always wins precedence)
--   2 = Abandoned  (Linden, Name contains "Abandoned Land")
--   3 = ForSale    (player-owned, ParcelFlags.ForSale set)
--   4 = Protected  (Linden, Name contains "Protected Land")
--
-- Per-scan capture only -- no periodic refresh. Bidders see the
-- scanned_at timestamp in the UI legend so they can judge data age.
-- See docs/superpowers/specs/2026-05-25-parcel-map-land-use-design.md.
CREATE TABLE auction_parcel_land_use (
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
```

- [ ] **Step 3: Write the entity**

Create `backend/src/main/java/com/slparcelauctions/backend/auction/parcelscan/AuctionParcelLandUse.java`:

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
 * Per-auction region-wide land-use classification raster (64x64 cells,
 * 4 m each, byte-encoded category values 0..4).
 *
 * <p>Row-major from the SW corner. {@code cells} is 4096 uint8s; values are
 * <pre>0=Other, 1=Listed, 2=Abandoned, 3=ForSale, 4=Protected</pre>
 * Captured once per scan; missing for any auction whose scan predates
 * the Land Use feature (those return a null landUseCellsBase64 in the
 * public read DTO).
 *
 * Excluded from the BaseEntity hierarchy because it uses {@code @MapsId}
 * to share its PK with auctions(id), matching the AuctionParcelHeightMap
 * / AuctionParcelLayout pattern.
 */
@Entity
@Table(name = "auction_parcel_land_use")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class AuctionParcelLandUse {

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

- [ ] **Step 4: Write the repository**

Create `backend/src/main/java/com/slparcelauctions/backend/auction/parcelscan/AuctionParcelLandUseRepository.java`:

```java
package com.slparcelauctions.backend.auction.parcelscan;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AuctionParcelLandUseRepository extends JpaRepository<AuctionParcelLandUse, Long> {

    boolean existsByAuctionId(Long auctionId);

    Optional<AuctionParcelLandUse> findByAuctionId(Long auctionId);

    void deleteByAuctionId(Long auctionId);
}
```

- [ ] **Step 5: Compile the backend to verify wiring**

```bash
cd C:/Users/heath/Repos/Personal/slpa/backend && ./mvnw compile
```

Expected: success. (The migration won't run yet — that happens in test or app boot.)

- [ ] **Step 6: Run backend tests to verify the migration applies cleanly**

```bash
cd C:/Users/heath/Repos/Personal/slpa/backend && ./mvnw test
```

Expected: all tests pass (no behavioral change yet; Flyway should apply V46 without error during test bootstrap).

- [ ] **Step 7: Commit**

```bash
git -C C:/Users/heath/Repos/Personal/slpa add \
  backend/src/main/resources/db/migration/V46__auction_parcel_land_use.sql \
  backend/src/main/java/com/slparcelauctions/backend/auction/parcelscan/AuctionParcelLandUse.java \
  backend/src/main/java/com/slparcelauctions/backend/auction/parcelscan/AuctionParcelLandUseRepository.java

git -C C:/Users/heath/Repos/Personal/slpa commit -m "feat(backend): AuctionParcelLandUse entity + V46 migration

New per-auction table sibling to auction_parcel_layouts /
auction_parcel_height_maps; 4096 bytes of category values per scan
(0..4 = Other/Listed/Abandoned/ForSale/Protected). @MapsId shares the
PK with auctions(id), matching the existing height/layout pattern.
No service wiring yet; that lands in subsequent commits."
```

---

## Task 5: Backend — extend write path (BotScanResultRequest + ParcelScanService.applyScanResult + tests)

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/parcelscan/dto/BotScanResultRequest.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/parcelscan/ParcelScanService.java`
- Modify: existing parcel-scan service tests (find via `find backend/src/test -name 'ParcelScanService*Test*'`)

- [ ] **Step 1: Find the parcel-scan service test file**

```bash
find C:/Users/heath/Repos/Personal/slpa/backend/src/test -name "ParcelScanService*"
```

Note the path returned for later edit steps. Read at least the existing happy-path applyScanResult test method to understand the assertion style.

- [ ] **Step 2: Extend `BotScanResultRequest` DTO**

Edit `backend/src/main/java/com/slparcelauctions/backend/auction/parcelscan/dto/BotScanResultRequest.java`:

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
 * change. {@code layoutCellsBase64} decodes to {@code gridSize^2 / 8} bytes,
 * {@code heightCellsBase64} decodes to {@code gridSize^2} bytes, and
 * {@code landUseCellsBase64} decodes to {@code gridSize^2} bytes (one byte
 * per cell, values 0..4). The service validates lengths after decoding.
 */
public record BotScanResultRequest(
        @NotNull @Min(64) @Max(64) Integer gridSize,
        @NotNull @Min(4) @Max(4) Integer cellSizeMeters,
        @NotBlank String layoutCellsBase64,
        @NotNull Float heightBaseMeters,
        @NotNull @Positive Float heightStepMeters,
        @NotBlank String heightCellsBase64,
        @NotBlank String landUseCellsBase64) {
}
```

- [ ] **Step 3: Write the failing test (extend the happy-path round-trip test)**

In the parcel-scan service test (found in Step 1), find the existing applyScanResult happy-path test and add to its arrange/assert blocks. The exact code depends on the existing test shape; the additions:

In the request-building block:

```java
byte[] expectedLandUseCells = new byte[4096];
expectedLandUseCells[0] = 1; // Listed
expectedLandUseCells[4] = 4; // Protected
expectedLandUseCells[5] = 2; // Abandoned
expectedLandUseCells[63 * 64 + 63] = 3; // ForSale

BotScanResultRequest req = new BotScanResultRequest(
    64,
    4,
    Base64.getEncoder().encodeToString(layoutCells),
    20.0f,
    0.05f,
    Base64.getEncoder().encodeToString(heightCells),
    Base64.getEncoder().encodeToString(expectedLandUseCells)); // <-- new
```

After the existing `heightRepo.findByAuctionId(...).get()` assertion, add:

```java
AuctionParcelLandUse landUse = landUseRepo.findByAuctionId(auction.getId()).orElseThrow();
assertThat(landUse.getGridSize()).isEqualTo(64);
assertThat(landUse.getCellSizeMeters()).isEqualTo(4);
assertThat(landUse.getCells()).isEqualTo(expectedLandUseCells);
assertThat(landUse.getScannedAt()).isEqualTo(layout.getScannedAt());
```

Wire the new repo into the test class:

```java
@Autowired private AuctionParcelLandUseRepository landUseRepo;
```

Also add a length-validation test:

```java
@Test
void applyScanResult_RejectsLandUseLengthMismatch() {
    BotTask task = createPendingScanTask();
    byte[] layoutCells = new byte[512];
    byte[] heightCells = new byte[4096];
    byte[] badLandUse = new byte[2048]; // wrong length

    BotScanResultRequest req = new BotScanResultRequest(
        64, 4,
        Base64.getEncoder().encodeToString(layoutCells),
        20.0f, 0.05f,
        Base64.getEncoder().encodeToString(heightCells),
        Base64.getEncoder().encodeToString(badLandUse));

    ResponseStatusException ex = assertThrows(ResponseStatusException.class,
        () -> service.applyScanResult(task.getId(), req));
    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(ex.getReason()).contains("landUse length");
}
```

- [ ] **Step 4: Run the tests to verify they fail**

```bash
cd C:/Users/heath/Repos/Personal/slpa/backend && ./mvnw test -Dtest=ParcelScanService*Test*
```

Expected: compile failure or missing-method failure (`landUseRepo` undefined, `BotScanResultRequest` constructor mismatch on existing tests).

- [ ] **Step 5: Update all existing call sites of `BotScanResultRequest` constructor**

```bash
grep -rln "new BotScanResultRequest(" C:/Users/heath/Repos/Personal/slpa/backend/src
```

Every match needs a new last argument. For tests that don't care about the field, pass a 4096-byte zero array base64-encoded:

```java
Base64.getEncoder().encodeToString(new byte[4096])
```

- [ ] **Step 6: Extend `ParcelScanService.applyScanResult` to decode + persist**

Edit `backend/src/main/java/com/slparcelauctions/backend/auction/parcelscan/ParcelScanService.java`. First, inject the new repo:

```java
private final AuctionParcelLayoutRepository layoutRepo;
private final AuctionParcelHeightMapRepository heightRepo;
private final AuctionParcelLandUseRepository landUseRepo; // <-- new
private final BotTaskRepository botTaskRepo;
private final BotTaskService botTaskService;
```

In `applyScanResult`, after the existing height-cells decode + length check (around line 86), add:

```java
byte[] landUseCells;
try {
    landUseCells = Base64.getDecoder().decode(req.landUseCellsBase64());
} catch (IllegalArgumentException ex) {
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid base64 (landUse)", ex);
}
if (landUseCells.length != cells) {
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "landUse length " + landUseCells.length + " != " + cells);
}
for (int i = 0; i < landUseCells.length; i++) {
    int v = landUseCells[i] & 0xFF;
    if (v > 4) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "landUse value " + v + " at index " + i + " is out of range [0..4]");
    }
}
```

After the existing `heightRepo.save(...)` block, add:

```java
landUseRepo.save(AuctionParcelLandUse.builder()
        .auction(auction)
        .gridSize(req.gridSize())
        .cellSizeMeters(req.cellSizeMeters())
        .cells(landUseCells)
        .scannedAt(now)
        .build());
```

- [ ] **Step 7: Run tests to verify they pass**

```bash
cd C:/Users/heath/Repos/Personal/slpa/backend && ./mvnw test
```

Expected: all tests pass.

- [ ] **Step 8: Commit**

```bash
git -C C:/Users/heath/Repos/Personal/slpa add \
  backend/src/main/java/com/slparcelauctions/backend/auction/parcelscan/dto/BotScanResultRequest.java \
  backend/src/main/java/com/slparcelauctions/backend/auction/parcelscan/ParcelScanService.java \
  backend/src/test/java/

git -C C:/Users/heath/Repos/Personal/slpa commit -m "feat(backend): persist land-use cells in ParcelScanService

Extends BotScanResultRequest with landUseCellsBase64; applyScanResult
decodes, validates (length + value range 0..4), and persists via the
new AuctionParcelLandUseRepository. Length-mismatch + value-range
rejections return 400. Existing test extended for the happy-path
round-trip; new test asserts length-mismatch rejection."
```

---

## Task 6: Backend — extend read path (ParcelScanResponse + ParcelScanReadService + tests)

**Files:**
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/parcelscan/dto/ParcelScanResponse.java`
- Modify: `backend/src/main/java/com/slparcelauctions/backend/auction/parcelscan/ParcelScanReadService.java`
- Modify: existing parcel-scan read service tests

- [ ] **Step 1: Find the read-service tests**

```bash
find C:/Users/heath/Repos/Personal/slpa/backend/src/test -name "ParcelScanReadService*"
```

- [ ] **Step 2: Extend `ParcelScanResponse` DTO**

Edit `backend/src/main/java/com/slparcelauctions/backend/auction/parcelscan/dto/ParcelScanResponse.java`:

```java
package com.slparcelauctions.backend.auction.parcelscan.dto;

import java.time.OffsetDateTime;

/**
 * Public read DTO for {@code GET /api/v1/auctions/{publicId}/parcel-scan}.
 * See docs/superpowers/specs/2026-05-24-parcel-map-frontend-design.md and
 * docs/superpowers/specs/2026-05-25-parcel-map-land-use-design.md.
 *
 * <p>Both byte arrays are base64-encoded so the response is JSON-safe.
 * {@code layoutCellsBase64} decodes to 512 bytes (4096 bits, MSB-first
 * within each byte, row-major SW-first). {@code heightCellsBase64}
 * decodes to 4096 uint8s. Per-cell elevation:
 * <pre>elevationMeters = baseMeters + (cells[i] &amp; 0xFF) * stepMeters</pre>
 *
 * <p>{@code landUseCellsBase64} decodes to 4096 bytes (one byte per cell,
 * values 0..4 per the {@code AuctionParcelLandUse} entity). Null for any
 * auction whose scan predates the Land Use feature; the frontend disables
 * the Land Use toggle option in that case.
 */
public record ParcelScanResponse(
        Integer gridSize,
        Integer cellSizeMeters,
        String layoutCellsBase64,
        String heightCellsBase64,
        Float baseMeters,
        Float stepMeters,
        OffsetDateTime scannedAt,
        String landUseCellsBase64
) {
}
```

- [ ] **Step 3: Write the failing tests**

Add to the read-service test file two cases:

```java
@Test
void findForAuction_IncludesLandUseCellsBase64_WhenPresent() {
    Auction auction = persistAuction();
    persistLayout(auction);
    persistHeight(auction);
    byte[] landUseCells = new byte[4096];
    landUseCells[0] = 1;
    landUseCells[100] = 4;
    persistLandUse(auction, landUseCells);

    ParcelScanResponse resp = service.findForAuction(auction.getPublicId()).orElseThrow();

    assertThat(resp.landUseCellsBase64()).isNotNull();
    byte[] decoded = Base64.getDecoder().decode(resp.landUseCellsBase64());
    assertThat(decoded).isEqualTo(landUseCells);
}

@Test
void findForAuction_LandUseCellsBase64IsNull_WhenLandUseRowMissing() {
    Auction auction = persistAuction();
    persistLayout(auction);
    persistHeight(auction);
    // No land-use row persisted (simulates a pre-feature auction)

    ParcelScanResponse resp = service.findForAuction(auction.getPublicId()).orElseThrow();

    assertThat(resp.landUseCellsBase64()).isNull();
}
```

Helper to add to the test class (or use whatever persistence helper the existing tests use):

```java
private void persistLandUse(Auction auction, byte[] cells) {
    landUseRepo.save(AuctionParcelLandUse.builder()
        .auction(auction)
        .gridSize(64)
        .cellSizeMeters(4)
        .cells(cells)
        .scannedAt(OffsetDateTime.now())
        .build());
}

@Autowired private AuctionParcelLandUseRepository landUseRepo;
```

- [ ] **Step 4: Run tests to verify they fail**

```bash
cd C:/Users/heath/Repos/Personal/slpa/backend && ./mvnw test -Dtest=ParcelScanReadService*Test*
```

Expected: compile failure (constructor mismatch for `ParcelScanResponse`) plus the new tests can't see the assembled field.

- [ ] **Step 5: Update all existing `new ParcelScanResponse(...)` call sites**

```bash
grep -rln "new ParcelScanResponse(" C:/Users/heath/Repos/Personal/slpa/backend/src
```

Each existing call needs `null` as the new last argument (or the actual base64 string in cases that care).

- [ ] **Step 6: Extend `ParcelScanReadService.findForAuction` to include the new field**

Edit `backend/src/main/java/com/slparcelauctions/backend/auction/parcelscan/ParcelScanReadService.java`:

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

@Service
@RequiredArgsConstructor
public class ParcelScanReadService {

    private final AuctionRepository auctionRepository;
    private final AuctionParcelLayoutRepository layoutRepository;
    private final AuctionParcelHeightMapRepository heightRepository;
    private final AuctionParcelLandUseRepository landUseRepository; // <-- new

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
        // landUse may be absent for pre-feature scans; null-through to the DTO.
        Optional<AuctionParcelLandUse> landUseOpt = landUseRepository.findByAuctionId(auctionId);

        Base64.Encoder b64 = Base64.getEncoder();
        return Optional.of(new ParcelScanResponse(
                layout.getGridSize(),
                layout.getCellSizeMeters(),
                b64.encodeToString(layout.getCells()),
                b64.encodeToString(height.getCells()),
                height.getBaseMeters(),
                height.getStepMeters(),
                height.getScannedAt(),
                landUseOpt.map(lu -> b64.encodeToString(lu.getCells())).orElse(null)
        ));
    }
}
```

- [ ] **Step 7: Run tests to verify they pass**

```bash
cd C:/Users/heath/Repos/Personal/slpa/backend && ./mvnw test
```

Expected: all tests pass.

- [ ] **Step 8: Commit**

```bash
git -C C:/Users/heath/Repos/Personal/slpa add \
  backend/src/main/java/com/slparcelauctions/backend/auction/parcelscan/dto/ParcelScanResponse.java \
  backend/src/main/java/com/slparcelauctions/backend/auction/parcelscan/ParcelScanReadService.java \
  backend/src/test/java/

git -C C:/Users/heath/Repos/Personal/slpa commit -m "feat(backend): expose landUseCellsBase64 on parcel-scan read DTO

ParcelScanResponse gains a nullable landUseCellsBase64. Pre-feature
scans (those without a sibling auction_parcel_land_use row) get null
so the frontend can disable the Land Use toggle option. Two new tests
cover both the present + absent paths."
```

---

## Task 7: Frontend — types + encoding helper + landUseColors module + tests

**Files:**
- Modify: `frontend/src/types/auction.ts`
- Modify: `frontend/src/lib/parcelMap/encoding.ts`
- Create: `frontend/src/lib/parcelMap/landUseColors.ts`
- Create: `frontend/src/lib/parcelMap/landUseColors.test.ts`

- [ ] **Step 1: Read frontend/AGENTS.md** (per repo convention, required before any frontend code)

```bash
cat C:/Users/heath/Repos/Personal/slpa/frontend/AGENTS.md
```

- [ ] **Step 2: Extend the `ParcelScanResponse` interface**

Edit `frontend/src/types/auction.ts`. Find the existing interface (around line 553) and extend:

```typescript
export interface ParcelScanResponse {
  gridSize: number;
  cellSizeMeters: number;
  layoutCellsBase64: string;
  heightCellsBase64: string;
  baseMeters: number;
  stepMeters: number;
  scannedAt: string;
  /**
   * Per-cell land-use category bytes (4096 bytes base64-encoded, values 0..4
   * per LAND_USE_CATEGORY). Null for scans captured before the Land Use
   * feature shipped; the Land Use toggle option is disabled in that case.
   */
  landUseCellsBase64: string | null;
}
```

- [ ] **Step 3: Write the failing test for `landUseColors`**

Create `frontend/src/lib/parcelMap/landUseColors.test.ts`:

```typescript
import { describe, it, expect } from "vitest";
import {
  LAND_USE_COLORS,
  LAND_USE_CATEGORY,
  landUseCellColor,
  landUseCategoryLabel,
} from "./landUseColors";

describe("LAND_USE_COLORS palette is pure RGB primaries", () => {
  it("listed = pure green", () => {
    expect(LAND_USE_COLORS.listed).toEqual({ r: 0, g: 255, b: 0 });
  });
  it("abandoned = pure blue", () => {
    expect(LAND_USE_COLORS.abandoned).toEqual({ r: 0, g: 0, b: 255 });
  });
  it("forSale = pure yellow", () => {
    expect(LAND_USE_COLORS.forSale).toEqual({ r: 255, g: 255, b: 0 });
  });
  it("protected = pure red", () => {
    expect(LAND_USE_COLORS.protected).toEqual({ r: 255, g: 0, b: 0 });
  });
  it("other = pure white", () => {
    expect(LAND_USE_COLORS.other).toEqual({ r: 255, g: 255, b: 255 });
  });
});

describe("LAND_USE_CATEGORY numeric values match the bot/backend contract", () => {
  it("Other = 0", () => expect(LAND_USE_CATEGORY.Other).toBe(0));
  it("Listed = 1", () => expect(LAND_USE_CATEGORY.Listed).toBe(1));
  it("Abandoned = 2", () => expect(LAND_USE_CATEGORY.Abandoned).toBe(2));
  it("ForSale = 3", () => expect(LAND_USE_CATEGORY.ForSale).toBe(3));
  it("Protected = 4", () => expect(LAND_USE_CATEGORY.Protected).toBe(4));
});

describe("landUseCellColor maps every value 0..4 to the right swatch", () => {
  it("0 -> other", () => expect(landUseCellColor(0)).toEqual(LAND_USE_COLORS.other));
  it("1 -> listed", () => expect(landUseCellColor(1)).toEqual(LAND_USE_COLORS.listed));
  it("2 -> abandoned", () => expect(landUseCellColor(2)).toEqual(LAND_USE_COLORS.abandoned));
  it("3 -> forSale", () => expect(landUseCellColor(3)).toEqual(LAND_USE_COLORS.forSale));
  it("4 -> protected", () => expect(landUseCellColor(4)).toEqual(LAND_USE_COLORS.protected));
  it("falls back to other for out-of-range values (defensive)", () => {
    expect(landUseCellColor(99)).toEqual(LAND_USE_COLORS.other);
    expect(landUseCellColor(-1)).toEqual(LAND_USE_COLORS.other);
  });
});

describe("landUseCategoryLabel returns user-facing names", () => {
  it("0 -> 'Other (private)'", () => expect(landUseCategoryLabel(0)).toBe("Other (private)"));
  it("1 -> 'Listed parcel'", () => expect(landUseCategoryLabel(1)).toBe("Listed parcel"));
  it("2 -> 'Abandoned (claimable)'", () =>
    expect(landUseCategoryLabel(2)).toBe("Abandoned (claimable)"));
  it("3 -> 'For sale in-world'", () => expect(landUseCategoryLabel(3)).toBe("For sale in-world"));
  it("4 -> 'Protected (Linden)'", () => expect(landUseCategoryLabel(4)).toBe("Protected (Linden)"));
  it("falls back for out-of-range", () =>
    expect(landUseCategoryLabel(99)).toBe("Other (private)"));
});
```

- [ ] **Step 4: Run tests to verify they fail**

```bash
cd C:/Users/heath/Repos/Personal/slpa/frontend && npm test -- landUseColors
```

Expected: module-not-found.

- [ ] **Step 5: Write the `landUseColors.ts` module**

Create `frontend/src/lib/parcelMap/landUseColors.ts`:

```typescript
/**
 * Categorical palette for the parcel map's 2D Land Use mode. Each cell is
 * one of five categories; the rgb tuples are pure primaries on pure white
 * so they read as a flat categorical overlay (distinct visual register
 * from the elevation gradient).
 *
 * Numeric values are the wire contract: the bot encodes 4096 bytes with
 * these exact values, the backend stores them raw, and this module is the
 * single point where the frontend turns them into pixels. Do not renumber.
 *
 * Spec: docs/superpowers/specs/2026-05-25-parcel-map-land-use-design.md
 */
import type { Rgb } from "./colors";

export const LAND_USE_CATEGORY = {
  Other: 0,
  Listed: 1,
  Abandoned: 2,
  ForSale: 3,
  Protected: 4,
} as const;

export type LandUseCategory =
  (typeof LAND_USE_CATEGORY)[keyof typeof LAND_USE_CATEGORY];

export const LAND_USE_COLORS: Readonly<Record<
  "other" | "listed" | "abandoned" | "forSale" | "protected",
  Rgb
>> = {
  other: { r: 255, g: 255, b: 255 },
  listed: { r: 0, g: 255, b: 0 },
  abandoned: { r: 0, g: 0, b: 255 },
  forSale: { r: 255, g: 255, b: 0 },
  protected: { r: 255, g: 0, b: 0 },
} as const;

/**
 * Per-cell color from a raw byte value (0..4). Out-of-range inputs fall
 * back to `other` so a corrupt payload never throws at the render site.
 */
export function landUseCellColor(value: number): Rgb {
  switch (value) {
    case LAND_USE_CATEGORY.Listed:
      return { ...LAND_USE_COLORS.listed };
    case LAND_USE_CATEGORY.Abandoned:
      return { ...LAND_USE_COLORS.abandoned };
    case LAND_USE_CATEGORY.ForSale:
      return { ...LAND_USE_COLORS.forSale };
    case LAND_USE_CATEGORY.Protected:
      return { ...LAND_USE_COLORS.protected };
    case LAND_USE_CATEGORY.Other:
    default:
      return { ...LAND_USE_COLORS.other };
  }
}

/**
 * User-facing label for the hover tooltip in Land Use mode. Plain text;
 * no per-parcel name or owner detail (deferred per spec out-of-scope).
 */
export function landUseCategoryLabel(value: number): string {
  switch (value) {
    case LAND_USE_CATEGORY.Listed:
      return "Listed parcel";
    case LAND_USE_CATEGORY.Abandoned:
      return "Abandoned (claimable)";
    case LAND_USE_CATEGORY.ForSale:
      return "For sale in-world";
    case LAND_USE_CATEGORY.Protected:
      return "Protected (Linden)";
    case LAND_USE_CATEGORY.Other:
    default:
      return "Other (private)";
  }
}
```

- [ ] **Step 6: Extend `encoding.ts` with `decodeLandUseCell`**

Edit `frontend/src/lib/parcelMap/encoding.ts`. Append:

```typescript
/**
 * Read a single land-use category byte at (row, col) from the 4096-byte
 * decoded payload. Row-major SW-first, same indexing as the height map.
 */
export function decodeLandUseCell(
  landUseCells: Uint8Array,
  row: number,
  col: number,
): number {
  return landUseCells[row * 64 + col] ?? 0;
}
```

- [ ] **Step 7: Run frontend test + build + verify**

```bash
cd C:/Users/heath/Repos/Personal/slpa/frontend && npm test -- landUseColors encoding
cd C:/Users/heath/Repos/Personal/slpa/frontend && npm run build
cd C:/Users/heath/Repos/Personal/slpa/frontend && npm run verify
```

Expected: all green.

- [ ] **Step 8: Commit**

```bash
git -C C:/Users/heath/Repos/Personal/slpa add \
  frontend/src/types/auction.ts \
  frontend/src/lib/parcelMap/encoding.ts \
  frontend/src/lib/parcelMap/landUseColors.ts \
  frontend/src/lib/parcelMap/landUseColors.test.ts

git -C C:/Users/heath/Repos/Personal/slpa commit -m "feat(frontend): landUseColors module + types + encoding helper

Pure RGB primaries on pure white for the Land Use categorical mode.
LAND_USE_CATEGORY constants pin the wire values to the bot/backend
contract. landUseCellColor + landUseCategoryLabel give the renderer
+ tooltip a single source of truth. Types and encoding helpers wired
end-to-end; no rendering yet."
```

---

## Task 8: Frontend — `useParcelMap2DColorMode` hook + tests

**Files:**
- Create: `frontend/src/hooks/useParcelMap2DColorMode.ts`
- Create: `frontend/src/hooks/useParcelMap2DColorMode.test.tsx`

- [ ] **Step 1: Write the failing tests**

Create `frontend/src/hooks/useParcelMap2DColorMode.test.tsx`:

```typescript
import { describe, it, expect, beforeEach } from "vitest";
import { renderHook, act } from "@testing-library/react";
import { useParcelMap2DColorMode } from "./useParcelMap2DColorMode";

const STORAGE_KEY = "slpa:parcel-map:2d-color";

describe("useParcelMap2DColorMode", () => {
  beforeEach(() => {
    window.localStorage.clear();
  });

  it("returns 'elevation' when localStorage is empty", () => {
    const { result } = renderHook(() => useParcelMap2DColorMode());
    expect(result.current[0]).toBe("elevation");
  });

  it("returns 'landuse' when localStorage already holds 'landuse'", () => {
    window.localStorage.setItem(STORAGE_KEY, "landuse");
    const { result } = renderHook(() => useParcelMap2DColorMode());
    expect(result.current[0]).toBe("landuse");
  });

  it("ignores junk values and falls back to 'elevation'", () => {
    window.localStorage.setItem(STORAGE_KEY, "asdf");
    const { result } = renderHook(() => useParcelMap2DColorMode());
    expect(result.current[0]).toBe("elevation");
  });

  it("setMode writes to localStorage and updates returned state", () => {
    const { result } = renderHook(() => useParcelMap2DColorMode());
    act(() => result.current[1]("landuse"));
    expect(result.current[0]).toBe("landuse");
    expect(window.localStorage.getItem(STORAGE_KEY)).toBe("landuse");
  });

  it("setMode back to 'elevation' updates both state and storage", () => {
    window.localStorage.setItem(STORAGE_KEY, "landuse");
    const { result } = renderHook(() => useParcelMap2DColorMode());
    act(() => result.current[1]("elevation"));
    expect(result.current[0]).toBe("elevation");
    expect(window.localStorage.getItem(STORAGE_KEY)).toBe("elevation");
  });
});
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd C:/Users/heath/Repos/Personal/slpa/frontend && npm test -- useParcelMap2DColorMode
```

Expected: module-not-found.

- [ ] **Step 3: Write the hook**

Create `frontend/src/hooks/useParcelMap2DColorMode.ts` (mirrors `useParcelMapColorMode.ts` exactly):

```typescript
"use client";

import { useCallback, useEffect, useState } from "react";

export type ParcelMap2DColorMode = "elevation" | "landuse";

const STORAGE_KEY = "slpa:parcel-map:2d-color";
const DEFAULT_MODE: ParcelMap2DColorMode = "elevation";

function readStoredMode(): ParcelMap2DColorMode {
  if (typeof window === "undefined") return DEFAULT_MODE;
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    if (raw === "elevation" || raw === "landuse") return raw;
    return DEFAULT_MODE;
  } catch {
    // localStorage can throw in privacy modes or quota-exceeded scenarios.
    return DEFAULT_MODE;
  }
}

/**
 * localStorage-backed color-mode choice for the parcel-map 2D view. Returns
 * the current mode and a setter that mirrors writes to localStorage.
 *
 * SSR-safe via a two-phase mount: the initial render returns DEFAULT_MODE on
 * BOTH the server pass and the client hydration pass, so the markup matches
 * and React does not warn about a hydration mismatch. After hydration the
 * useEffect runs, reads the stored value, and re-renders if it differs.
 * Mirrors {@link useParcelMapColorMode} (which is the 3D-mode peer).
 */
export function useParcelMap2DColorMode(): [
  ParcelMap2DColorMode,
  (next: ParcelMap2DColorMode) => void,
] {
  const [mode, setMode] = useState<ParcelMap2DColorMode>(DEFAULT_MODE);

  // Deliberate two-phase mount: see JSDoc above.
  /* eslint-disable react-hooks/set-state-in-effect */
  useEffect(() => {
    setMode(readStoredMode());
  }, []);
  /* eslint-enable react-hooks/set-state-in-effect */

  const update = useCallback((next: ParcelMap2DColorMode) => {
    setMode(next);
    if (typeof window === "undefined") return;
    try {
      window.localStorage.setItem(STORAGE_KEY, next);
    } catch {
      // localStorage write can throw under quota or privacy modes; swallow
      // so the in-session mode change still takes effect.
    }
  }, []);

  return [mode, update];
}
```

- [ ] **Step 4: Run tests + build + verify**

```bash
cd C:/Users/heath/Repos/Personal/slpa/frontend && npm test -- useParcelMap2DColorMode
cd C:/Users/heath/Repos/Personal/slpa/frontend && npm run build
cd C:/Users/heath/Repos/Personal/slpa/frontend && npm run verify
```

Expected: all green.

- [ ] **Step 5: Commit**

```bash
git -C C:/Users/heath/Repos/Personal/slpa add \
  frontend/src/hooks/useParcelMap2DColorMode.ts \
  frontend/src/hooks/useParcelMap2DColorMode.test.tsx

git -C C:/Users/heath/Repos/Personal/slpa commit -m "feat(frontend): useParcelMap2DColorMode hook

localStorage-backed 'elevation' | 'landuse' selection for the 2D parcel
map. Two-phase mount matches the existing 3D mode hook so hydration is
clean. Storage key slpa:parcel-map:2d-color; default 'elevation'."
```

---

## Task 9: Frontend — `ParcelMap2DColorModeToggle` component + tests

**Files:**
- Create: `frontend/src/components/auction/ParcelMap2DColorModeToggle.tsx`
- Create: `frontend/src/components/auction/ParcelMap2DColorModeToggle.test.tsx`

- [ ] **Step 1: Write the failing tests**

Create `frontend/src/components/auction/ParcelMap2DColorModeToggle.test.tsx`:

```typescript
import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import { ParcelMap2DColorModeToggle } from "./ParcelMap2DColorModeToggle";

describe("ParcelMap2DColorModeToggle", () => {
  it("renders an ARIA radio group with the correct aria-label", () => {
    render(<ParcelMap2DColorModeToggle mode="elevation" onChange={() => {}} />);
    expect(screen.getByRole("radiogroup", { name: "Color by" })).toBeInTheDocument();
  });

  it("renders two radio buttons labelled Elevation and Land Use", () => {
    render(<ParcelMap2DColorModeToggle mode="elevation" onChange={() => {}} />);
    expect(screen.getByRole("radio", { name: "Elevation" })).toBeInTheDocument();
    expect(screen.getByRole("radio", { name: "Land Use" })).toBeInTheDocument();
  });

  it("aria-checked reflects the current mode prop", () => {
    const { rerender } = render(
      <ParcelMap2DColorModeToggle mode="elevation" onChange={() => {}} />,
    );
    expect(screen.getByRole("radio", { name: "Elevation" }))
      .toHaveAttribute("aria-checked", "true");
    expect(screen.getByRole("radio", { name: "Land Use" }))
      .toHaveAttribute("aria-checked", "false");
    rerender(<ParcelMap2DColorModeToggle mode="landuse" onChange={() => {}} />);
    expect(screen.getByRole("radio", { name: "Elevation" }))
      .toHaveAttribute("aria-checked", "false");
    expect(screen.getByRole("radio", { name: "Land Use" }))
      .toHaveAttribute("aria-checked", "true");
  });

  it("clicking a radio fires onChange with the corresponding mode value", async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    render(<ParcelMap2DColorModeToggle mode="elevation" onChange={onChange} />);
    await user.click(screen.getByRole("radio", { name: "Land Use" }));
    expect(onChange).toHaveBeenCalledWith("landuse");
  });

  it("Arrow-Right cycles selection and focus", async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    render(<ParcelMap2DColorModeToggle mode="elevation" onChange={onChange} />);
    screen.getByRole("radio", { name: "Elevation" }).focus();
    await user.keyboard("{ArrowRight}");
    expect(onChange).toHaveBeenCalledWith("landuse");
    expect(screen.getByRole("radio", { name: "Land Use" })).toHaveFocus();
  });

  it("Arrow-Left from Land Use wraps back to Elevation", async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    render(<ParcelMap2DColorModeToggle mode="landuse" onChange={onChange} />);
    screen.getByRole("radio", { name: "Land Use" }).focus();
    await user.keyboard("{ArrowLeft}");
    expect(onChange).toHaveBeenCalledWith("elevation");
    expect(screen.getByRole("radio", { name: "Elevation" })).toHaveFocus();
  });

  it("roving tabindex: active=0, inactive=-1", () => {
    render(<ParcelMap2DColorModeToggle mode="elevation" onChange={() => {}} />);
    expect(screen.getByRole("radio", { name: "Elevation" }))
      .toHaveAttribute("tabindex", "0");
    expect(screen.getByRole("radio", { name: "Land Use" }))
      .toHaveAttribute("tabindex", "-1");
  });

  it("renders Land Use as aria-disabled when landUseAvailable is false", () => {
    render(
      <ParcelMap2DColorModeToggle
        mode="elevation"
        onChange={() => {}}
        landUseAvailable={false}
      />,
    );
    expect(screen.getByRole("radio", { name: "Land Use" }))
      .toHaveAttribute("aria-disabled", "true");
  });

  it("does not call onChange when the disabled Land Use option is clicked", async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    render(
      <ParcelMap2DColorModeToggle
        mode="elevation"
        onChange={onChange}
        landUseAvailable={false}
      />,
    );
    await user.click(screen.getByRole("radio", { name: "Land Use" }));
    expect(onChange).not.toHaveBeenCalled();
  });
});
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd C:/Users/heath/Repos/Personal/slpa/frontend && npm test -- ParcelMap2DColorModeToggle
```

Expected: module-not-found.

- [ ] **Step 3: Write the component**

Create `frontend/src/components/auction/ParcelMap2DColorModeToggle.tsx` (mirrors the 3D toggle structure):

```typescript
"use client";

import { type KeyboardEvent } from "react";

import { cn } from "@/lib/cn";
import { type ParcelMap2DColorMode } from "@/hooks/useParcelMap2DColorMode";

export interface ParcelMap2DColorModeToggleProps {
  mode: ParcelMap2DColorMode;
  onChange: (next: ParcelMap2DColorMode) => void;
  /** When false, the Land Use option is aria-disabled and clicks are suppressed. */
  landUseAvailable?: boolean;
  className?: string;
}

const MODES: ReadonlyArray<{ value: ParcelMap2DColorMode; label: string }> = [
  { value: "elevation", label: "Elevation" },
  { value: "landuse", label: "Land Use" },
];

/**
 * Inline button group for the 2D parcel-map color mode. ARIA radio group
 * pattern (not tabs) -- the user is picking how to color the same scene.
 * Mirrors {@link ParcelMap3DColorModeToggle}. The Land Use option can be
 * disabled for legacy scans whose response carries a null landUseCellsBase64.
 *
 * Spec: docs/superpowers/specs/2026-05-25-parcel-map-land-use-design.md
 */
export function ParcelMap2DColorModeToggle({
  mode,
  onChange,
  landUseAvailable = true,
  className,
}: ParcelMap2DColorModeToggleProps) {
  const isDisabled = (value: ParcelMap2DColorMode) =>
    value === "landuse" && !landUseAvailable;

  const handleKeyDown = (e: KeyboardEvent<HTMLButtonElement>) => {
    if (e.key !== "ArrowLeft" && e.key !== "ArrowRight") return;
    e.preventDefault();
    const idx = MODES.findIndex((m) => m.value === mode);
    if (idx === -1) return;
    const delta = e.key === "ArrowRight" ? 1 : -1;
    const next = MODES[(idx + delta + MODES.length) % MODES.length];
    if (isDisabled(next.value)) return;
    onChange(next.value);
    const group = e.currentTarget.parentElement;
    group?.querySelector<HTMLButtonElement>(`button[data-mode="${next.value}"]`)?.focus();
  };

  return (
    <div
      role="radiogroup"
      aria-label="Color by"
      className={cn(
        "inline-flex gap-1 rounded-md border border-border-subtle bg-surface-raised p-1",
        className,
      )}
    >
      {MODES.map((m) => {
        const selected = mode === m.value;
        const disabled = isDisabled(m.value);
        return (
          <button
            key={m.value}
            type="button"
            role="radio"
            data-mode={m.value}
            aria-checked={selected}
            aria-disabled={disabled || undefined}
            tabIndex={selected ? 0 : -1}
            onClick={() => {
              if (disabled) return;
              onChange(m.value);
            }}
            onKeyDown={handleKeyDown}
            className={cn(
              "px-2 py-1 text-xs font-medium transition-colors rounded",
              "focus:outline-none focus-visible:ring-1 focus-visible:ring-brand",
              selected ? "text-brand" : "text-fg-muted hover:text-fg",
              disabled && "opacity-50 cursor-not-allowed hover:text-fg-muted",
            )}
          >
            {m.label}
          </button>
        );
      })}
    </div>
  );
}
```

- [ ] **Step 4: Run tests + build + verify**

```bash
cd C:/Users/heath/Repos/Personal/slpa/frontend && npm test -- ParcelMap2DColorModeToggle
cd C:/Users/heath/Repos/Personal/slpa/frontend && npm run build
cd C:/Users/heath/Repos/Personal/slpa/frontend && npm run verify
```

Expected: all green.

- [ ] **Step 5: Commit**

```bash
git -C C:/Users/heath/Repos/Personal/slpa add \
  frontend/src/components/auction/ParcelMap2DColorModeToggle.tsx \
  frontend/src/components/auction/ParcelMap2DColorModeToggle.test.tsx

git -C C:/Users/heath/Repos/Personal/slpa commit -m "feat(frontend): ParcelMap2DColorModeToggle component

ARIA radio-group toggle for the 2D parcel-map mode (Elevation /
Land Use). Mirrors ParcelMap3DColorModeToggle exactly. Adds a
landUseAvailable=false path that renders Land Use as aria-disabled
+ swallows clicks, used when the scan response lacks landUseCellsBase64."
```

---

## Task 10: Frontend — `ParcelMap3DLegend` + `ParcelMapLandUseLegend` components + tests

**Files:**
- Create: `frontend/src/components/auction/ParcelMap3DLegend.tsx`
- Create: `frontend/src/components/auction/ParcelMap3DLegend.test.tsx`
- Create: `frontend/src/components/auction/ParcelMapLandUseLegend.tsx`
- Create: `frontend/src/components/auction/ParcelMapLandUseLegend.test.tsx`
- Possibly modify: `frontend/scripts/verify-no-inline-styles.sh` (add allowlist entry for the new legend gradient style)

- [ ] **Step 1: Write the failing test for `ParcelMap3DLegend`**

Create `frontend/src/components/auction/ParcelMap3DLegend.test.tsx`:

```typescript
import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { ParcelMap3DLegend } from "./ParcelMap3DLegend";

describe("ParcelMap3DLegend", () => {
  it("renders elevation labels in elevation mode", () => {
    render(<ParcelMap3DLegend mode="elevation" maxDelta={27} />);
    expect(screen.getByText("0 m")).toBeInTheDocument();
    expect(screen.getByText("+27 m")).toBeInTheDocument();
  });

  it("rounds the maxDelta label to the nearest integer meter", () => {
    render(<ParcelMap3DLegend mode="elevation" maxDelta={27.6} />);
    expect(screen.getByText("+28 m")).toBeInTheDocument();
  });

  it("renders slope labels in slope mode (maxDelta ignored)", () => {
    render(<ParcelMap3DLegend mode="slope" maxDelta={0} />);
    expect(screen.getByText("0°")).toBeInTheDocument();
    expect(screen.getByText("45°")).toBeInTheDocument();
  });

  it("renders an accessible label naming the legend", () => {
    render(<ParcelMap3DLegend mode="elevation" maxDelta={10} />);
    expect(screen.getByRole("group", { name: /3D map color scale/i })).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Write the failing test for `ParcelMapLandUseLegend`**

Create `frontend/src/components/auction/ParcelMapLandUseLegend.test.tsx`:

```typescript
import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { ParcelMapLandUseLegend } from "./ParcelMapLandUseLegend";

describe("ParcelMapLandUseLegend", () => {
  it("renders four category swatches with labels", () => {
    render(<ParcelMapLandUseLegend />);
    expect(screen.getByText("Listed")).toBeInTheDocument();
    expect(screen.getByText("Abandoned")).toBeInTheDocument();
    expect(screen.getByText("For Sale")).toBeInTheDocument();
    expect(screen.getByText("Protected")).toBeInTheDocument();
  });

  it("renders an accessible group label", () => {
    render(<ParcelMapLandUseLegend />);
    expect(screen.getByRole("group", { name: /Land Use legend/i })).toBeInTheDocument();
  });
});
```

- [ ] **Step 3: Run tests to verify they fail**

```bash
cd C:/Users/heath/Repos/Personal/slpa/frontend && npm test -- ParcelMap3DLegend ParcelMapLandUseLegend
```

Expected: module-not-found.

- [ ] **Step 4: Write `ParcelMap3DLegend.tsx`**

Create `frontend/src/components/auction/ParcelMap3DLegend.tsx`:

```typescript
"use client";

import { cn } from "@/lib/cn";

export interface ParcelMap3DLegendProps {
  mode: "elevation" | "slope";
  /** Used only in elevation mode; ignored in slope (which is fixed 0..45 deg). */
  maxDelta: number;
  className?: string;
}

/**
 * Gradient-bar legend for the 3D parcel-map. Renders a green->red CSS
 * gradient that matches the in-canvas vertex coloring + the mode-specific
 * end labels. Mirrors the existing 2D elevation legend in shape and uses
 * the same allowlisted inline-style pattern for the gradient bar.
 *
 * Spec: docs/superpowers/specs/2026-05-25-parcel-map-land-use-design.md
 */
export function ParcelMap3DLegend({ mode, maxDelta, className }: ParcelMap3DLegendProps) {
  const leftLabel = mode === "elevation" ? "0 m" : "0°";
  const rightLabel =
    mode === "elevation" ? `+${Math.round(maxDelta)} m` : "45°";

  return (
    <div
      role="group"
      aria-label="3D map color scale"
      className={cn("flex items-center gap-2 text-xs text-fg-muted", className)}
    >
      <span>{leftLabel}</span>
      <div
        className="h-2 flex-1 rounded-sm"
        // Inline style: gradient is data-driven, not a static theme color.
        // Allowlisted in scripts/verify-no-inline-styles.sh.
        style={{
          background:
            "linear-gradient(to right, rgb(34, 197, 94), rgb(239, 68, 68))",
        }}
      />
      <span>{rightLabel}</span>
    </div>
  );
}
```

- [ ] **Step 5: Write `ParcelMapLandUseLegend.tsx`**

Create `frontend/src/components/auction/ParcelMapLandUseLegend.tsx`:

```typescript
"use client";

import { cn } from "@/lib/cn";
import { LAND_USE_COLORS } from "@/lib/parcelMap/landUseColors";

const SWATCHES: ReadonlyArray<{ label: string; rgb: { r: number; g: number; b: number } }> = [
  { label: "Listed", rgb: LAND_USE_COLORS.listed },
  { label: "Abandoned", rgb: LAND_USE_COLORS.abandoned },
  { label: "For Sale", rgb: LAND_USE_COLORS.forSale },
  { label: "Protected", rgb: LAND_USE_COLORS.protected },
];

export interface ParcelMapLandUseLegendProps {
  className?: string;
}

/**
 * Four-swatch legend for the 2D parcel-map's Land Use mode. Pure
 * presentational; the color values come from {@link LAND_USE_COLORS} so
 * the legend swatches always match the rendered cells.
 *
 * Spec: docs/superpowers/specs/2026-05-25-parcel-map-land-use-design.md
 */
export function ParcelMapLandUseLegend({ className }: ParcelMapLandUseLegendProps) {
  return (
    <div
      role="group"
      aria-label="Land Use legend"
      className={cn("flex flex-wrap items-center gap-3 text-xs text-fg-muted", className)}
    >
      {SWATCHES.map((s) => (
        <span key={s.label} className="inline-flex items-center gap-1">
          <span
            className="h-3 w-3 border border-border-subtle"
            // Inline style: rgb tuple is from the data palette, not a theme
            // color. Allowlisted in scripts/verify-no-inline-styles.sh.
            style={{ background: `rgb(${s.rgb.r}, ${s.rgb.g}, ${s.rgb.b})` }}
            aria-hidden="true"
          />
          <span>{s.label}</span>
        </span>
      ))}
    </div>
  );
}
```

- [ ] **Step 6: Add the two new component paths to the inline-styles allowlist**

Edit `frontend/scripts/verify-no-inline-styles.sh`. Find the existing `ALLOWLIST=(` block and add:

```bash
  # Gradient bar background; data-driven not a theme color
  "src/components/auction/ParcelMap3DLegend.tsx"
  # Per-category swatch background; data palette in landUseColors.ts
  "src/components/auction/ParcelMapLandUseLegend.tsx"
```

- [ ] **Step 7: Run tests + build + verify**

```bash
cd C:/Users/heath/Repos/Personal/slpa/frontend && npm test -- ParcelMap3DLegend ParcelMapLandUseLegend
cd C:/Users/heath/Repos/Personal/slpa/frontend && npm run build
cd C:/Users/heath/Repos/Personal/slpa/frontend && npm run verify
```

Expected: all green. If `verify` fails on inline-styles, double-check the allowlist entries were added exactly (paths must match `src/...` relative form, not `frontend/src/...`).

- [ ] **Step 8: Commit**

```bash
git -C C:/Users/heath/Repos/Personal/slpa add \
  frontend/src/components/auction/ParcelMap3DLegend.tsx \
  frontend/src/components/auction/ParcelMap3DLegend.test.tsx \
  frontend/src/components/auction/ParcelMapLandUseLegend.tsx \
  frontend/src/components/auction/ParcelMapLandUseLegend.test.tsx \
  frontend/scripts/verify-no-inline-styles.sh

git -C C:/Users/heath/Repos/Personal/slpa commit -m "feat(frontend): ParcelMap3DLegend + ParcelMapLandUseLegend components

3D legend is a green->red gradient bar with mode-specific end labels
(elevation: 0 m / +N m; slope: 0 deg / 45 deg). Land Use legend is four
labelled swatches keyed to LAND_USE_COLORS. Both render inline styles
for data-driven backgrounds; both paths added to the no-inline-styles
allowlist."
```

---

## Task 11: Frontend — wire `ParcelMap.tsx` (Land Use paint + toggle + legend swap + tooltip + outline gate)

**Files:**
- Modify: `frontend/src/components/auction/ParcelMap.tsx`
- Modify: `frontend/src/components/auction/ParcelMap.test.tsx`

This is the largest task. Read both files in full before editing — the existing `ParcelMapLegend` is defined inline in `ParcelMap.tsx`.

- [ ] **Step 1: Read both files**

```bash
cat C:/Users/heath/Repos/Personal/slpa/frontend/src/components/auction/ParcelMap.tsx
cat C:/Users/heath/Repos/Personal/slpa/frontend/src/components/auction/ParcelMap.test.tsx
```

Identify: the paint loop function (likely `paintCells`), the boundary-painting function (`paintBoundary`), the hover state setter, the JSX render block, and the inline `ParcelMapLegend` function.

- [ ] **Step 2: Write failing tests in `ParcelMap.test.tsx`**

Add cases (the existing test patterns use a `payloadWithParcelAt` helper for mocking `useParcelScan`; extend it to accept an optional `landUseCellsBase64`):

```typescript
import { ParcelMap2DColorModeToggle } from "./ParcelMap2DColorModeToggle";

// ... in the existing describe block, add:

it("renders the Elevation/Land Use mode toggle below the legend", () => {
  setupSuccessfulScan(); // existing helper, or adapt payloadWithParcelAt
  render(<ParcelMap publicId="abc-123" />);
  expect(screen.getByRole("radiogroup", { name: "Color by" })).toBeInTheDocument();
  expect(screen.getByRole("radio", { name: "Elevation" })).toBeInTheDocument();
  expect(screen.getByRole("radio", { name: "Land Use" })).toBeInTheDocument();
});

it("renders the Land Use option as aria-disabled when the response has null landUseCellsBase64", () => {
  setupScan({ landUseCellsBase64: null });
  render(<ParcelMap publicId="abc-123" />);
  expect(screen.getByRole("radio", { name: "Land Use" }))
    .toHaveAttribute("aria-disabled", "true");
});

it("renders the elevation legend by default", () => {
  setupSuccessfulScan();
  render(<ParcelMap publicId="abc-123" />);
  expect(screen.getByText(/0 m/)).toBeInTheDocument();
  // No category swatches in elevation mode
  expect(screen.queryByText("Listed")).toBeNull();
});

it("swaps to the Land Use legend when the toggle is set to landuse", async () => {
  const user = userEvent.setup();
  setupScan({ landUseCellsBase64: btoa(String.fromCharCode(...new Uint8Array(4096))) });
  render(<ParcelMap publicId="abc-123" />);
  await user.click(screen.getByRole("radio", { name: "Land Use" }));
  expect(screen.getByText("Listed")).toBeInTheDocument();
  expect(screen.getByText("Abandoned")).toBeInTheDocument();
  expect(screen.getByText("For Sale")).toBeInTheDocument();
  expect(screen.getByText("Protected")).toBeInTheDocument();
});

it("hides the cyan parcel outline in Land Use mode", async () => {
  // The outline is painted via paintBoundary; we assert by verifying the
  // 2D context method spy was NOT called with the cyan strokeStyle when
  // mode is landuse, OR by checking a data-testid wrapper attribute the
  // component exposes for this. Implementer chooses.
  // ...
});
```

Adjust `setupSuccessfulScan` / `setupScan` helpers as needed; the existing test file should already have a parcel-scan mocking pattern around `useParcelScan` — extend it to set the new `landUseCellsBase64` field.

- [ ] **Step 3: Run tests to verify they fail**

```bash
cd C:/Users/heath/Repos/Personal/slpa/frontend && npm test -- ParcelMap.test
```

Expected: failure (toggle not present, no Land Use legend, etc.).

- [ ] **Step 4: Wire `useParcelMap2DColorMode` + toggle into `ParcelMap.tsx`**

In `ParcelMap.tsx`, add imports near the existing imports:

```typescript
import { useParcelMap2DColorMode } from "@/hooks/useParcelMap2DColorMode";
import { ParcelMap2DColorModeToggle } from "./ParcelMap2DColorModeToggle";
import { ParcelMapLandUseLegend } from "./ParcelMapLandUseLegend";
import {
  decodeBase64ToBytes,
  // ... existing
  decodeLandUseCell,
} from "@/lib/parcelMap/encoding";
import { landUseCellColor, landUseCategoryLabel } from "@/lib/parcelMap/landUseColors";
```

Inside the `ParcelMap` component function, near the top with the other hooks:

```typescript
const [colorMode, setColorMode] = useParcelMap2DColorMode();
const landUseAvailable = data?.landUseCellsBase64 != null;
const activeMode = landUseAvailable ? colorMode : "elevation";
```

- [ ] **Step 5: Decode the land-use cells when present**

In the existing `decoded = useMemo(...)` block, extend the returned object:

```typescript
const decoded = useMemo(() => {
  if (!data) return null;
  return {
    layoutCells: decodeBase64ToBytes(data.layoutCellsBase64),
    heightCells: decodeBase64ToBytes(data.heightCellsBase64),
    baseMeters: data.baseMeters,
    stepMeters: data.stepMeters,
    landUseCells: data.landUseCellsBase64
      ? decodeBase64ToBytes(data.landUseCellsBase64)
      : null,
  };
}, [data]);
```

- [ ] **Step 6: Branch the paint loop on the active mode**

Find `paintCells(ctx, decoded, stats)`. Refactor to take the `mode` parameter and branch:

```typescript
function paintCells(
  ctx: CanvasRenderingContext2D,
  decoded: Decoded,
  stats: Stats,
  mode: "elevation" | "landuse",
): void {
  const image = ctx.createImageData(GRID * CELL_PX, GRID * CELL_PX);
  const maxDelta = stats.regionMax - stats.parcelMin;
  for (let row = 0; row < GRID; row++) {
    for (let col = 0; col < GRID; col++) {
      // Choose the color for this cell.
      let rgb;
      if (mode === "landuse" && decoded.landUseCells) {
        rgb = landUseCellColor(decodeLandUseCell(decoded.landUseCells, row, col));
        // No dim-outside treatment in Land Use mode: every category shows
        // at full saturation regardless of parcel membership.
      } else {
        const elev = decodeElevationCell(
          decoded.heightCells, row, col, decoded.baseMeters, decoded.stepMeters,
        );
        const delta = elev - stats.parcelMin;
        const inParcel = isCellInParcel(decoded.layoutCells, row, col);
        const base = gradientColor(delta, maxDelta);
        rgb = inParcel ? base : dimOutside(base);
      }
      // Paint the CELL_PX x CELL_PX block. Canvas y=0 is north; flip:
      const canvasYTop = (GRID - 1 - row) * CELL_PX;
      for (let py = 0; py < CELL_PX; py++) {
        for (let px = 0; px < CELL_PX; px++) {
          const idx = ((canvasYTop + py) * GRID * CELL_PX + (col * CELL_PX + px)) * 4;
          image.data[idx] = rgb.r;
          image.data[idx + 1] = rgb.g;
          image.data[idx + 2] = rgb.b;
          image.data[idx + 3] = 255;
        }
      }
    }
  }
  ctx.putImageData(image, 0, 0);
}
```

(The exact loop structure should match what's already in the file — preserve the existing index math.)

In the useEffect that calls paint:

```typescript
useEffect(() => {
  // ... existing setup
  paintCells(ctx, decoded, stats, activeMode);
  if (activeMode === "elevation") {
    paintBoundary(ctx, decoded.layoutCells);
  }
  // else: no boundary in Land Use mode.
}, [decoded, stats, activeMode]);
```

- [ ] **Step 7: Branch the hover tooltip on active mode**

In the hover state-setting handler, when computing `cellInfo` for the tooltip, branch on `activeMode`:

```typescript
// existing: cellInfo: { row, col, elevM }
// extend to optionally include category in Land Use mode
const landUseValue =
  activeMode === "landuse" && decoded.landUseCells
    ? decodeLandUseCell(decoded.landUseCells, row, col)
    : null;
setHover({
  pixelX: e.nativeEvent.offsetX,
  pixelY: e.nativeEvent.offsetY,
  cellInfo: { row, col, elevM, landUseValue },
});
```

Then in `ParcelMapTooltip`, render the category label when present:

```typescript
function ParcelMapTooltip({ row, col, elevM, landUseValue, inParcel, pixelX, pixelY }: TooltipProps) {
  const worldX = col * 4;
  const worldY = row * 4;
  const isLandUse = landUseValue != null;
  const detail = isLandUse
    ? landUseCategoryLabel(landUseValue)
    : `${elevM.toFixed(1)} m${inParcel ? " (in parcel)" : ""}`;
  return (
    <div
      role="tooltip"
      className="absolute z-10 rounded bg-bg-subtle border border-border-subtle px-2 py-1 text-xs text-fg-muted pointer-events-none"
      style={{ left: pixelX + 12, top: pixelY + 12 }}
    >
      ({worldX}, {worldY}): {detail}
    </div>
  );
}
```

- [ ] **Step 8: Rename existing inline `ParcelMapLegend` → `ParcelMapElevationLegend` + swap on mode**

Find the existing inline `function ParcelMapLegend({ maxDelta }: ...)` and rename it to `ParcelMapElevationLegend`. In the JSX, replace:

```tsx
<ParcelMapLegend maxDelta={...} />
```

with:

```tsx
<div className="flex flex-col gap-1 w-[256px]">
  {activeMode === "landuse"
    ? <ParcelMapLandUseLegend />
    : <ParcelMapElevationLegend maxDelta={stats ? stats.regionMax - stats.parcelMin : 0} />}
  <ParcelMap2DColorModeToggle
    mode={activeMode}
    onChange={setColorMode}
    landUseAvailable={landUseAvailable}
  />
</div>
```

(Adjust the wrapper class to match the existing chrome spacing.)

- [ ] **Step 9: Run tests + build + verify**

```bash
cd C:/Users/heath/Repos/Personal/slpa/frontend && npm test -- ParcelMap.test
cd C:/Users/heath/Repos/Personal/slpa/frontend && npm test
cd C:/Users/heath/Repos/Personal/slpa/frontend && npm run build
cd C:/Users/heath/Repos/Personal/slpa/frontend && npm run verify
```

Expected: all green.

- [ ] **Step 10: Commit**

```bash
git -C C:/Users/heath/Repos/Personal/slpa add \
  frontend/src/components/auction/ParcelMap.tsx \
  frontend/src/components/auction/ParcelMap.test.tsx

git -C C:/Users/heath/Repos/Personal/slpa commit -m "feat(frontend): wire Land Use mode into ParcelMap

Adds the Elevation/Land Use mode toggle below the legend row + branches
the paint loop, hover tooltip, and outline rendering on active mode.
ParcelMapElevationLegend renamed from the old inline ParcelMapLegend
for symmetry with ParcelMapLandUseLegend. Cyan parcel outline now
renders only in Elevation mode (the green Listed fill marks the parcel
in Land Use mode). Tooltip shows category name in Land Use, elevation
in Elevation."
```

---

## Task 12: Frontend — wire `ParcelMap3D.tsx` (relocate toggle + add legend)

**Files:**
- Modify: `frontend/src/components/auction/ParcelMap3D.tsx`
- Modify: `frontend/src/components/auction/ParcelMap3D.test.tsx`

- [ ] **Step 1: Write failing tests in `ParcelMap3D.test.tsx`**

Append cases:

```typescript
it("renders the 3D legend below the canvas", () => {
  setupSuccessful3DScan(); // existing helper
  render(<ParcelMap3D publicId="abc-123" />);
  // Legend is keyed off mode; default is elevation, so "0 m" + "+N m" appear.
  expect(screen.getByText(/0 m/)).toBeInTheDocument();
  expect(screen.getByText(/\+\d+ m/)).toBeInTheDocument();
});

it("renders the slope legend in slope mode", async () => {
  const user = userEvent.setup();
  setupSuccessful3DScan();
  render(<ParcelMap3D publicId="abc-123" />);
  await user.click(screen.getByRole("radio", { name: "Slope" }));
  expect(screen.getByText("0°")).toBeInTheDocument();
  expect(screen.getByText("45°")).toBeInTheDocument();
});

it("toggle no longer sits inside the canvas overlay", () => {
  setupSuccessful3DScan();
  const { container } = render(<ParcelMap3D publicId="abc-123" />);
  // The previous placement had absolute positioning; assert toggle's
  // parent is NOT a class containing 'absolute'.
  const toggle = screen.getByRole("radiogroup", { name: "Color by" });
  const parent = toggle.parentElement!;
  expect(parent.className).not.toMatch(/\babsolute\b/);
});
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd C:/Users/heath/Repos/Personal/slpa/frontend && npm test -- ParcelMap3D.test
```

Expected: failure (no legend, toggle still in absolute wrapper).

- [ ] **Step 3: Edit `ParcelMap3D.tsx`**

Find the existing toggle wrapper (lines ~193-195):

```tsx
<div className="absolute top-2 right-2">
  <ParcelMap3DColorModeToggle mode={colorMode} onChange={setColorMode} />
</div>
```

Replace the entire return block's chrome arrangement. Wrap the Canvas + new chrome row in a flex column:

```tsx
return (
  <div className={cn("flex flex-col items-center gap-2", className)}>
    <div
      role="img"
      aria-label="Interactive 3D region and parcel elevation map"
      className="relative aspect-square w-full max-w-[320px] bg-bg-subtle border border-border-subtle"
    >
      <Canvas>
        {/* ... existing Canvas children unchanged */}
      </Canvas>
    </div>
    <div className="flex flex-col gap-1 w-full max-w-[320px]">
      <ParcelMap3DLegend
        mode={colorMode}
        maxDelta={bounds && stats ? bounds.rMax - stats.parcelMin : 0}
      />
      <ParcelMap3DColorModeToggle mode={colorMode} onChange={setColorMode} />
    </div>
  </div>
);
```

Add the import:

```typescript
import { ParcelMap3DLegend } from "./ParcelMap3DLegend";
```

Remove the old `<div className="absolute top-2 right-2">` wrapper entirely (the toggle now lives in the new flex column).

- [ ] **Step 4: Run tests + build + verify**

```bash
cd C:/Users/heath/Repos/Personal/slpa/frontend && npm test -- ParcelMap3D.test
cd C:/Users/heath/Repos/Personal/slpa/frontend && npm test
cd C:/Users/heath/Repos/Personal/slpa/frontend && npm run build
cd C:/Users/heath/Repos/Personal/slpa/frontend && npm run verify
```

Expected: all green.

- [ ] **Step 5: Commit**

```bash
git -C C:/Users/heath/Repos/Personal/slpa add \
  frontend/src/components/auction/ParcelMap3D.tsx \
  frontend/src/components/auction/ParcelMap3D.test.tsx

git -C C:/Users/heath/Repos/Personal/slpa commit -m "feat(frontend): 3D parcel map legend + toggle relocation

Existing top-right floating toggle moves to a row below the canvas, in
the new flex-column chrome arrangement. Adds ParcelMap3DLegend in a row
above the toggle: gradient bar with 0 m / +N m labels in elevation mode,
0 deg / 45 deg labels in slope mode. Canvas wrapper unchanged."
```

---

## Task 13: End-to-end — open PR feat/parcel-map-land-use → dev

- [ ] **Step 1: Final whole-suite verification**

```bash
cd C:/Users/heath/Repos/Personal/slpa/bot && dotnet test
cd C:/Users/heath/Repos/Personal/slpa/backend && ./mvnw test
cd C:/Users/heath/Repos/Personal/slpa/frontend && npm test
cd C:/Users/heath/Repos/Personal/slpa/frontend && npm run build
cd C:/Users/heath/Repos/Personal/slpa/frontend && npm run verify
cd C:/Users/heath/Repos/Personal/slpa/frontend && npm run lint
```

Expected: all green. If lint warns on touched files, fix and recommit before opening PR.

- [ ] **Step 2: Push the branch**

```bash
git -C C:/Users/heath/Repos/Personal/slpa push origin feat/parcel-map-land-use
```

- [ ] **Step 3: Open the PR**

```bash
gh pr create --base dev --head feat/parcel-map-land-use \
  --title "feat: parcel-map Land Use mode + 3D legend + toggle relocation" \
  --body "Closes part of #414 (long-running parcel-map feature work).

## Summary

Adds a second 2D rendering mode to the auction-detail parcel map: each
cell is colored by its in-world land-use category (Listed / Abandoned /
For Sale / Protected / Other). Same chrome refresh applied to the 3D
view, which also gains a gradient legend it was missing.

## Pipeline

- Bot: ScanParcelHandler classifies each of 4096 cells from sim.Parcels
  (same trip, no new task type). 4 KB byte array per scan.
- Backend: new AuctionParcelLandUse entity + V46 migration; ingest +
  read DTOs extended; nullable field for pre-feature scans.
- Frontend: new useParcelMap2DColorMode hook + ParcelMap2DColorModeToggle
  + ParcelMapLandUseLegend + landUseColors module. ParcelMap.tsx branches
  paint/tooltip/outline on mode; pre-feature scans render Land Use as
  aria-disabled.
- 3D chrome: existing top-right floating toggle moves below the canvas;
  new ParcelMap3DLegend renders gradient + end labels per mode.

## Locked design decisions (per spec)

- Pure RGB primaries on white background (no Tailwind 500 tones).
- 4 categories (no Public/Directed for-sale split, no group-owned).
- Linden detection by Parcel.Name substring match.
- One-shot at listing creation (no periodic refresh).
- Listed > Protected > Abandoned > ForSale > Other precedence.
- No cyan outline in Land Use mode.

## Post-merge verification

After merge to main + Amplify deploy, scan a known mainland region with
mixed land use; visually compare to in-world View > About Land. File any
misclassifications as follow-up issues against this spec.

Spec: docs/superpowers/specs/2026-05-25-parcel-map-land-use-design.md
Plan: docs/superpowers/plans/2026-05-25-parcel-map-land-use-plan.md"
```

- [ ] **Step 4: Merge the PR (Claude merges dev PRs autonomously per repo convention)**

```bash
gh pr merge --merge feat/parcel-map-land-use
```

- [ ] **Step 5: Do NOT open dev → main PR**

That step is the user's responsibility per the repo's branch / PR workflow. Notify the user that the dev PR is merged and the feature is awaiting their dev → main review.
