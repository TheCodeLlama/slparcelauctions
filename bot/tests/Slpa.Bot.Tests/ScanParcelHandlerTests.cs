using System.Net;
using FluentAssertions;
using Microsoft.Extensions.Logging.Abstractions;
using Moq;
using Slpa.Bot.Backend;
using Slpa.Bot.Backend.Models;
using Slpa.Bot.Sl;
using Slpa.Bot.Tasks;
using Xunit;

namespace Slpa.Bot.Tests;

/// <summary>
/// SCAN_PARCEL handler. Drives teleport -> request-all-sim-parcels ->
/// get-parcel-grid -> get-terrain -> post-scan-result via the in-test
/// <see cref="FakeBotSession"/> + a mocked <see cref="IBackendClient"/>;
/// never touches GridClient.
/// </summary>
public sealed class ScanParcelHandlerTests
{
    private readonly Mock<IBackendClient> _backend = new();
    private readonly FakeBotSession _session = new();
    private ScanResultRequest? _captured;

    public ScanParcelHandlerTests()
    {
        _session.SimulateLoginSuccess();
        _backend
            .Setup(b => b.PostScanResultAsync(
                It.IsAny<long>(), It.IsAny<ScanResultRequest>(),
                It.IsAny<CancellationToken>()))
            .Callback<long, ScanResultRequest, CancellationToken>(
                (_, body, _) => _captured = body)
            .ReturnsAsync(new HttpResponseMessage(HttpStatusCode.OK));
        _backend
            .Setup(b => b.PostScanFailedAsync(
                It.IsAny<long>(), It.IsAny<ScanFailedRequest>(),
                It.IsAny<CancellationToken>()))
            .ReturnsAsync(new HttpResponseMessage(HttpStatusCode.NoContent));
    }

    private ScanParcelHandler NewHandler() =>
        new(_session, _backend.Object, NullLogger<ScanParcelHandler>.Instance);

    // ------------------------------------------------------------------ helpers

    private static uint[,] MakeParcelGrid(int ownedLocalId,
        int rowStart, int rowEnd, int colStart, int colEnd,
        uint otherLocalId = 99)
    {
        var grid = new uint[64, 64];
        for (int r = 0; r < 64; r++)
        {
            for (int c = 0; c < 64; c++)
            {
                grid[r, c] = (r >= rowStart && r < rowEnd && c >= colStart && c < colEnd)
                    ? (uint)ownedLocalId
                    : otherLocalId;
            }
        }
        return grid;
    }

    private static float[,] FlatTerrain(float height = 30f)
    {
        var t = new float[64, 64];
        for (int r = 0; r < 64; r++)
            for (int c = 0; c < 64; c++)
                t[r, c] = height;
        return t;
    }

    private static BotTaskResponse BuildTask(
        string regionName = "TestRegion",
        double posX = 128, double posY = 64) => new(
        Id: 42,
        TaskType: BotTaskType.SCAN_PARCEL,
        Status: BotTaskStatus.IN_PROGRESS,
        AuctionId: 7,
        EscrowId: null,
        ParcelUuid: Guid.NewGuid(),
        RegionName: regionName,
        PositionX: posX,
        PositionY: posY,
        PositionZ: 25,
        SentinelPrice: 0,
        AssignedBotUuid: Guid.NewGuid(),
        FailureReason: null,
        NextRunAt: null,
        RecurrenceIntervalSeconds: null,
        CreatedAt: DateTimeOffset.UtcNow,
        CompletedAt: null);

    // ------------------------------------------------------------------ tests

    [Fact]
    public async Task HappyPath_PostsCorrectBody()
    {
        // Parcel LocalID = 42, occupies rows 10..20 cols 5..15.
        const uint ourLocalId = 42u;
        _session.RequestAllSimParcelsPolicy = (_, _) => (int)ourLocalId;
        _session.ParcelLocalIdsPolicy = () => MakeParcelGrid((int)ourLocalId, 10, 21, 5, 16);
        _session.TerrainHeightsPolicy = () => FlatTerrain(30f);

        await NewHandler().HandleAsync(BuildTask(), CancellationToken.None);

        _backend.Verify(b => b.PostScanResultAsync(
                42L,
                It.IsAny<ScanResultRequest>(),
                It.IsAny<CancellationToken>()),
            Times.Once);

        _captured.Should().NotBeNull();
        _captured!.GridSize.Should().Be(64);
        _captured.CellSizeMeters.Should().Be(4);

        // HeightBaseMeters and HeightStepMeters: flat 30f -> step clamped to 0.001f.
        _captured.HeightBaseMeters.Should().BeApproximately(30f, 0.0001f);
        _captured.HeightStepMeters.Should().BeApproximately(0.001f, 0.0001f);

        // Layout: bits set for rows 10..20 cols 5..15 (inclusive).
        var layout = Convert.FromBase64String(_captured.LayoutCellsBase64);
        layout.Should().HaveCount(512);

        // Verify all set bits correspond to our owned cells.
        for (int row = 0; row < 64; row++)
        {
            for (int col = 0; col < 64; col++)
            {
                int bitIndex = row * 64 + col;
                int byteIdx = bitIndex / 8;
                int bitInByte = 7 - (bitIndex % 8);
                bool isSet = (layout[byteIdx] & (1 << bitInByte)) != 0;
                bool shouldBeSet = row >= 10 && row <= 20 && col >= 5 && col <= 15;
                isSet.Should().Be(shouldBeSet,
                    $"bit at row={row} col={col} should be {shouldBeSet}");
            }
        }

        // HeightCells: flat terrain -> all zeros.
        var heights = Convert.FromBase64String(_captured.HeightCellsBase64);
        heights.Should().HaveCount(4096);
        heights.Should().AllBeEquivalentTo((byte)0);
    }

    [Fact]
    public async Task ParcelNotFoundInRegion_DoesNotPostScanResult()
    {
        // RequestAllSimParcelsPolicy returns null -> parcel not found.
        _session.RequestAllSimParcelsPolicy = (_, _) => null;

        await NewHandler().HandleAsync(BuildTask(), CancellationToken.None);

        _backend.Verify(b => b.PostScanResultAsync(
                It.IsAny<long>(), It.IsAny<ScanResultRequest>(),
                It.IsAny<CancellationToken>()),
            Times.Never);
    }

    [Fact]
    public async Task TeleportFails_DoesNotPostScanResult()
    {
        _session.TeleportPolicy = _ => TeleportResult.Fail(TeleportFailureKind.RegionNotFound);
        var parcelsCalled = false;
        _session.RequestAllSimParcelsPolicy = (_, _) => { parcelsCalled = true; return 1; };

        await NewHandler().HandleAsync(BuildTask(), CancellationToken.None);

        _backend.Verify(b => b.PostScanResultAsync(
                It.IsAny<long>(), It.IsAny<ScanResultRequest>(),
                It.IsAny<CancellationToken>()),
            Times.Never);
        parcelsCalled.Should().BeFalse();
    }

    [Fact]
    public async Task HeightmapQuantization_200mRange_ProducesCorrectStep()
    {
        // Half the cells at 30f, half at 230f -> range = 200, step = 200/255.
        const uint ourLocalId = 5u;
        _session.RequestAllSimParcelsPolicy = (_, _) => (int)ourLocalId;
        // All cells owned by our parcel.
        var allOwned = new uint[64, 64];
        for (int r = 0; r < 64; r++)
            for (int c = 0; c < 64; c++)
                allOwned[r, c] = ourLocalId;
        _session.ParcelLocalIdsPolicy = () => allOwned;

        var terrain = new float[64, 64];
        for (int r = 0; r < 64; r++)
            for (int c = 0; c < 64; c++)
                terrain[r, c] = (r < 32) ? 30f : 230f;
        _session.TerrainHeightsPolicy = () => terrain;

        await NewHandler().HandleAsync(BuildTask(), CancellationToken.None);

        _captured.Should().NotBeNull();
        _captured!.HeightBaseMeters.Should().BeApproximately(30f, 0.0001f);
        float expectedStep = 200f / 255f;
        _captured.HeightStepMeters.Should().BeApproximately(expectedStep, 0.0001f);

        var heights = Convert.FromBase64String(_captured.HeightCellsBase64);
        heights.Should().HaveCount(4096);
        // Min rows (r < 32) -> quantized 0.
        heights[0 * 64 + 0].Should().Be(0);
        // Max rows (r >= 32) -> quantized 255.
        heights[32 * 64 + 0].Should().Be(255);
    }

    [Fact]
    public async Task HeightmapQuantization_FlatRegion_ClampsStepToMinimum()
    {
        const uint ourLocalId = 7u;
        _session.RequestAllSimParcelsPolicy = (_, _) => (int)ourLocalId;
        var allOwned = new uint[64, 64];
        for (int r = 0; r < 64; r++)
            for (int c = 0; c < 64; c++)
                allOwned[r, c] = ourLocalId;
        _session.ParcelLocalIdsPolicy = () => allOwned;
        _session.TerrainHeightsPolicy = () => FlatTerrain(30f);

        await NewHandler().HandleAsync(BuildTask(), CancellationToken.None);

        _captured.Should().NotBeNull();
        _captured!.HeightStepMeters.Should().BeApproximately(0.001f, 0.0001f);
        var heights = Convert.FromBase64String(_captured.HeightCellsBase64);
        heights.Should().AllBeEquivalentTo((byte)0);
    }

    [Fact]
    public async Task BackendReturns409_TreatedAsSuccess_NoFailurePost()
    {
        // 409 = already recorded; handler should treat as success and not retry or fail.
        const uint ourLocalId = 3u;
        _session.RequestAllSimParcelsPolicy = (_, _) => (int)ourLocalId;
        _session.ParcelLocalIdsPolicy = () => new uint[64, 64];
        _session.TerrainHeightsPolicy = () => FlatTerrain(0f);

        _backend
            .Setup(b => b.PostScanResultAsync(
                It.IsAny<long>(), It.IsAny<ScanResultRequest>(),
                It.IsAny<CancellationToken>()))
            .ReturnsAsync(new HttpResponseMessage(HttpStatusCode.Conflict));

        await NewHandler().HandleAsync(BuildTask(), CancellationToken.None);

        // PostScanResultAsync was called exactly once (no retry).
        _backend.Verify(b => b.PostScanResultAsync(
                It.IsAny<long>(), It.IsAny<ScanResultRequest>(),
                It.IsAny<CancellationToken>()),
            Times.Once);
    }

    [Fact]
    public async Task BackendReturns409OnPostFailed_TreatedAsSuccess()
    {
        // 409 on scan-failed = backend already recorded this failure; should not warn.
        const uint ourLocalId = 30u;
        _session.RequestAllSimParcelsPolicy = (_, _) => (int)ourLocalId;
        _session.ParcelLocalIdsPolicy = () => new uint[64, 64];
        _session.TerrainHeightsPolicy = () => FlatTerrain(10f);

        // Mark one cell as not loaded so PostFailedAsync is invoked.
        _session.TerrainHeightsLoadedPolicy = () =>
        {
            var loaded = new bool[64, 64];
            for (int r = 0; r < 64; r++)
                for (int c = 0; c < 64; c++)
                    loaded[r, c] = true;
            loaded[0, 0] = false;
            return loaded;
        };

        _backend
            .Setup(b => b.PostScanFailedAsync(
                It.IsAny<long>(), It.IsAny<ScanFailedRequest>(),
                It.IsAny<CancellationToken>()))
            .ReturnsAsync(new HttpResponseMessage(HttpStatusCode.Conflict));

        // Should complete without throwing or posting a scan result.
        var act = async () => await NewHandler().HandleAsync(BuildTask(), CancellationToken.None);
        await act.Should().NotThrowAsync();

        _backend.Verify(b => b.PostScanFailedAsync(
                It.IsAny<long>(), It.IsAny<ScanFailedRequest>(),
                It.IsAny<CancellationToken>()),
            Times.Once);
        _backend.Verify(b => b.PostScanResultAsync(
                It.IsAny<long>(), It.IsAny<ScanResultRequest>(),
                It.IsAny<CancellationToken>()),
            Times.Never);
    }

    [Fact]
    public async Task BackendReturns400_DoesNotRetry()
    {
        const uint ourLocalId = 8u;
        _session.RequestAllSimParcelsPolicy = (_, _) => (int)ourLocalId;
        _session.ParcelLocalIdsPolicy = () => new uint[64, 64];
        _session.TerrainHeightsPolicy = () => FlatTerrain(0f);

        _backend
            .Setup(b => b.PostScanResultAsync(
                It.IsAny<long>(), It.IsAny<ScanResultRequest>(),
                It.IsAny<CancellationToken>()))
            .ReturnsAsync(new HttpResponseMessage(HttpStatusCode.BadRequest));

        await NewHandler().HandleAsync(BuildTask(), CancellationToken.None);

        // Called once; no retry on 4xx.
        _backend.Verify(b => b.PostScanResultAsync(
                It.IsAny<long>(), It.IsAny<ScanResultRequest>(),
                It.IsAny<CancellationToken>()),
            Times.Once);
    }

    [Fact]
    public async Task BackendNetworkException_DoesNotPropagate()
    {
        const uint ourLocalId = 9u;
        _session.RequestAllSimParcelsPolicy = (_, _) => (int)ourLocalId;
        _session.ParcelLocalIdsPolicy = () => new uint[64, 64];
        _session.TerrainHeightsPolicy = () => FlatTerrain(0f);

        _backend
            .Setup(b => b.PostScanResultAsync(
                It.IsAny<long>(), It.IsAny<ScanResultRequest>(),
                It.IsAny<CancellationToken>()))
            .ThrowsAsync(new HttpRequestException("network down"));

        var act = async () =>
            await NewHandler().HandleAsync(BuildTask(), CancellationToken.None);

        // The handler catches HttpRequestException and returns cleanly.
        await act.Should().NotThrowAsync();
    }

    [Fact]
    public async Task MissingRegionName_DoesNotTeleport_DoesNotPost()
    {
        // Build a task with null RegionName.
        var task = BuildTask() with { RegionName = null };
        var teleportCalled = false;
        _session.TeleportPolicy = _ => { teleportCalled = true; return TeleportResult.Ok(); };

        await NewHandler().HandleAsync(task, CancellationToken.None);

        teleportCalled.Should().BeFalse();
        _backend.Verify(b => b.PostScanResultAsync(
                It.IsAny<long>(), It.IsAny<ScanResultRequest>(),
                It.IsAny<CancellationToken>()),
            Times.Never);
    }

    [Fact]
    public async Task LayoutBitmapEncoding_IsMsbFirstPerByte()
    {
        // Only cell (0, 0) is owned. In MSB-first encoding, bit 0 of the first byte
        // corresponds to row=0, col=0. That is bit index 0 -> byte 0, bit position 7.
        // So byte[0] should be 0x80 (10000000 in binary).
        const uint ourLocalId = 11u;
        _session.RequestAllSimParcelsPolicy = (_, _) => (int)ourLocalId;
        var grid = new uint[64, 64]; // all zeros
        grid[0, 0] = ourLocalId;    // only cell (row=0, col=0)
        _session.ParcelLocalIdsPolicy = () => grid;
        _session.TerrainHeightsPolicy = () => FlatTerrain(0f);

        await NewHandler().HandleAsync(BuildTask(), CancellationToken.None);

        _captured.Should().NotBeNull();
        var layout = Convert.FromBase64String(_captured!.LayoutCellsBase64);
        layout[0].Should().Be(0x80, "bit index 0 -> byte 0, bit 7 (MSB-first)");
        layout.Skip(1).Should().AllBeEquivalentTo((byte)0,
            "no other cells should be set");
    }

    [Fact]
    public async Task WaitForRegionTerrain_IsCalledBeforeSampling()
    {
        // Verify WaitForRegionTerrainAsync is invoked exactly once and before
        // GetRegionTerrainHeights in the call log.
        const uint ourLocalId = 20u;
        _session.RequestAllSimParcelsPolicy = (_, _) => (int)ourLocalId;
        _session.ParcelLocalIdsPolicy = () => new uint[64, 64];
        _session.TerrainHeightsPolicy = () => FlatTerrain(10f);

        await NewHandler().HandleAsync(BuildTask(), CancellationToken.None);

        var waitIdx = _session.CallLog.IndexOf(nameof(IBotSession.WaitForRegionTerrainAsync));
        var sampleIdx = _session.CallLog.IndexOf(nameof(IBotSession.GetRegionTerrainHeights));
        waitIdx.Should().BeGreaterThanOrEqualTo(0, "WaitForRegionTerrainAsync must be called");
        sampleIdx.Should().BeGreaterThanOrEqualTo(0, "GetRegionTerrainHeights must be called");
        waitIdx.Should().BeLessThan(sampleIdx,
            "WaitForRegionTerrainAsync must be called before GetRegionTerrainHeights");
        _session.CallLog.Count(c => c == nameof(IBotSession.WaitForRegionTerrainAsync))
            .Should().Be(1, "WaitForRegionTerrainAsync should be called exactly once");
    }

    [Fact]
    public async Task TerrainNotLoaded_PostsFailureAndDoesNotPostScanResult()
    {
        // When some cells fail to load (Loaded[row,col] = false), handler should
        // POST scan-failed with TERRAIN_NOT_LOADED and not post a scan result.
        const uint ourLocalId = 21u;
        _session.RequestAllSimParcelsPolicy = (_, _) => (int)ourLocalId;
        _session.ParcelLocalIdsPolicy = () => new uint[64, 64];
        _session.TerrainHeightsPolicy = () => FlatTerrain(10f);

        // Mark one cell as not loaded.
        _session.TerrainHeightsLoadedPolicy = () =>
        {
            var loaded = new bool[64, 64];
            for (int r = 0; r < 64; r++)
                for (int c = 0; c < 64; c++)
                    loaded[r, c] = true;
            loaded[0, 0] = false; // one cell failed to load
            return loaded;
        };

        await NewHandler().HandleAsync(BuildTask(), CancellationToken.None);

        _backend.Verify(b => b.PostScanFailedAsync(
                42L,
                It.Is<ScanFailedRequest>(r => r.Reason == "TERRAIN_NOT_LOADED"),
                It.IsAny<CancellationToken>()),
            Times.Once);
        _backend.Verify(b => b.PostScanResultAsync(
                It.IsAny<long>(), It.IsAny<ScanResultRequest>(),
                It.IsAny<CancellationToken>()),
            Times.Never);
    }

    [Fact]
    public async Task AllCellsLoaded_HappyPath_PostsResult()
    {
        // When all cells load successfully, the handler should post a scan result
        // and not post a failure.
        const uint ourLocalId = 22u;
        _session.RequestAllSimParcelsPolicy = (_, _) => (int)ourLocalId;
        var allOwned = new uint[64, 64];
        for (int r = 0; r < 64; r++)
            for (int c = 0; c < 64; c++)
                allOwned[r, c] = ourLocalId;
        _session.ParcelLocalIdsPolicy = () => allOwned;

        // Varied terrain so step != clamped minimum.
        var terrain = new float[64, 64];
        for (int r = 0; r < 64; r++)
            for (int c = 0; c < 64; c++)
                terrain[r, c] = 30f + r;
        _session.TerrainHeightsPolicy = () => terrain;
        // TerrainHeightsLoadedPolicy defaults to all-true.

        await NewHandler().HandleAsync(BuildTask(), CancellationToken.None);

        _backend.Verify(b => b.PostScanResultAsync(
                42L,
                It.IsAny<ScanResultRequest>(),
                It.IsAny<CancellationToken>()),
            Times.Once);
        _backend.Verify(b => b.PostScanFailedAsync(
                It.IsAny<long>(), It.IsAny<ScanFailedRequest>(),
                It.IsAny<CancellationToken>()),
            Times.Never);
    }
}
