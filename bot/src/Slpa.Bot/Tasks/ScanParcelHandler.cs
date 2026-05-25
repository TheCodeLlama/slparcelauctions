using System.Net;
using Microsoft.Extensions.Logging;
using Slpa.Bot.Backend;
using Slpa.Bot.Backend.Models;
using Slpa.Bot.Sl;

namespace Slpa.Bot.Tasks;

/// <summary>
/// Handles <see cref="BotTaskType.SCAN_PARCEL"/> tasks. Teleports to the
/// parcel's region, waits for the sim's parcel catalog to finish downloading,
/// builds a 512-byte MSB-first layout bitmap and a 4096-byte quantized
/// heightmap, then POSTs both to
/// <c>POST /api/v1/bot/tasks/{taskId}/scan-result</c> via
/// <see cref="IBackendClient.PostScanResultAsync"/>. A 200 or 409 response
/// is treated as success (409 = backend already recorded a prior scan).
/// Any 4xx other than 409 is a terminal failure; the handler logs and returns
/// without posting, leaving the row for the backend's IN_PROGRESS timeout
/// sweep to clean up.
/// </summary>
public sealed class ScanParcelHandler
{
    private readonly IBotSession _session;
    private readonly IBackendClient _backend;
    private readonly ILogger<ScanParcelHandler> _log;

    public ScanParcelHandler(IBotSession session, IBackendClient backend,
        ILogger<ScanParcelHandler> log)
    {
        _session = session;
        _backend = backend;
        _log = log;
    }

    public async Task HandleAsync(BotTaskResponse task, CancellationToken ct)
    {
        if (task.RegionName is null || task.PositionX is null || task.PositionY is null)
        {
            _log.LogWarning("SCAN_PARCEL {Id} missing region/position; skipping", task.Id);
            return;
        }

        // Step 1: Teleport to the region.
        var tp = await _session.TeleportAsync(
            task.RegionName,
            task.PositionX.Value,
            task.PositionY.Value,
            task.PositionZ ?? 25,
            ct).ConfigureAwait(false);

        if (!tp.Success)
        {
            _log.LogWarning(
                "SCAN_PARCEL {Id} teleport to {Region} failed: {Failure}; " +
                "leaving for backend sweep",
                task.Id, task.RegionName, tp.Failure);
            return;
        }

        // Step 2: Wait for full sim parcel download and identify this parcel's LocalID.
        int? localId = await _session.RequestAllSimParcelsAsync(
            task.PositionX.Value, task.PositionY.Value, ct).ConfigureAwait(false);

        if (localId is null)
        {
            _log.LogWarning(
                "SCAN_PARCEL {Id} parcel at ({X},{Y}) in {Region} not found in ParcelMap; " +
                "leaving for backend sweep",
                task.Id, task.PositionX, task.PositionY, task.RegionName);
            return;
        }

        uint ourLocalId = (uint)localId.Value;

        // Step 3: Build the 512-byte layout bitmap. Row 0 = south edge of the
        // region (y = 0..4 m); row 63 = north edge. Col 0 = west, col 63 = east.
        // Row-major SW-first, MSB-first within each byte (matches the
        // AuctionParcelLayout.cells contract in the Java entity Javadoc).
        var parcelGrid = _session.GetRegionParcelLocalIds();
        var layoutCells = new byte[512];
        for (int row = 0; row < 64; row++)
        {
            for (int col = 0; col < 64; col++)
            {
                if (parcelGrid[row, col] == ourLocalId)
                {
                    int bitIndex = row * 64 + col;
                    int byteIndex = bitIndex / 8;
                    int bitInByte = 7 - (bitIndex % 8); // MSB-first
                    layoutCells[byteIndex] |= (byte)(1 << bitInByte);
                }
            }
        }

        // Step 4: Wait for terrain patches to stream in, then sample heights.
        var patchCount = await _session.WaitForRegionTerrainAsync(ct).ConfigureAwait(false);
        _log.LogInformation("SCAN_PARCEL {Id}: terrain patches received = {Count}/256",
            task.Id, patchCount);

        var terrain = _session.GetRegionTerrainHeights();

        // Fail fast if any cells did not load: the data would be silently wrong.
        int unloadedCells = 0;
        for (int row = 0; row < 64; row++)
            for (int col = 0; col < 64; col++)
                if (!terrain.Loaded[row, col]) unloadedCells++;

        if (unloadedCells > 0)
        {
            _log.LogWarning(
                "SCAN_PARCEL {Id}: {Unloaded}/4096 cells did not load after wait; posting FAILED",
                task.Id, unloadedCells);
            await PostFailedAsync(task, "TERRAIN_NOT_LOADED", ct).ConfigureAwait(false);
            return;
        }

        // Diagnostic: if all cells loaded but heights are uniformly the same value,
        // that is unexpected for any non-trivial region and suggests a code bug
        // (e.g. TerrainHeightAtPoint returning true with a stale zero, or a
        // coordinate-mismatch) rather than the terrain-load timing race.
        float diagMin = float.MaxValue, diagMax = float.MinValue;
        for (int row = 0; row < 64; row++)
            for (int col = 0; col < 64; col++)
            {
                if (terrain.Heights[row, col] < diagMin) diagMin = terrain.Heights[row, col];
                if (terrain.Heights[row, col] > diagMax) diagMax = terrain.Heights[row, col];
            }
        if (diagMin == diagMax)
        {
            _log.LogWarning(
                "SCAN_PARCEL {Id}: all 4096 cells loaded but heights are uniformly {H} m. " +
                "This is unexpected for any non-trivial region and suggests a code bug, " +
                "not the terrain-load timing race.",
                task.Id, diagMin);
        }

        // Build the quantized 4096-byte heightmap from terrain.Heights.
        float regionMin = diagMin;
        float regionMax = diagMax;

        float step = MathF.Max(0.001f, (regionMax - regionMin) / 255f);
        float baseM = regionMin;
        var heightCells = new byte[4096];
        for (int row = 0; row < 64; row++)
        {
            for (int col = 0; col < 64; col++)
            {
                float v = terrain.Heights[row, col];
                int q = (int)MathF.Round((v - baseM) / step);
                if (q < 0) q = 0;
                if (q > 255) q = 255;
                heightCells[row * 64 + col] = (byte)q;
            }
        }

        // Step 5: POST the scan result to the backend.
        var body = new ScanResultRequest(
            GridSize: 64,
            CellSizeMeters: 4,
            LayoutCellsBase64: Convert.ToBase64String(layoutCells),
            HeightBaseMeters: baseM,
            HeightStepMeters: step,
            HeightCellsBase64: Convert.ToBase64String(heightCells));

        HttpResponseMessage resp;
        try
        {
            resp = await _backend.PostScanResultAsync(task.Id, body, ct)
                .ConfigureAwait(false);
        }
        catch (HttpRequestException ex)
        {
            _log.LogWarning(ex,
                "SCAN_PARCEL {Id} backend POST failed (network); " +
                "leaving for backend sweep",
                task.Id);
            return;
        }

        using (resp)
        {
            if (resp.StatusCode == HttpStatusCode.OK ||
                resp.StatusCode == HttpStatusCode.Conflict)
            {
                // 200 = recorded; 409 = already recorded by a prior attempt.
                _log.LogInformation(
                    "SCAN_PARCEL {Id} -> {Status} ({Region} localId={LocalId})",
                    task.Id, (int)resp.StatusCode, task.RegionName, ourLocalId);
                return;
            }

            _log.LogWarning(
                "SCAN_PARCEL {Id} backend returned {Status}; " +
                "leaving for backend sweep",
                task.Id, (int)resp.StatusCode);
        }
    }

    private async Task PostFailedAsync(
        BotTaskResponse task, string reason, CancellationToken ct)
    {
        HttpResponseMessage resp;
        try
        {
            resp = await _backend.PostScanFailedAsync(
                task.Id,
                new ScanFailedRequest(reason),
                ct).ConfigureAwait(false);
        }
        catch (HttpRequestException ex)
        {
            _log.LogWarning(ex,
                "SCAN_PARCEL {Id} backend fail-report POST failed (network); " +
                "leaving for backend sweep",
                task.Id);
            return;
        }

        using (resp)
        {
            if (resp.StatusCode == HttpStatusCode.NoContent ||
                resp.StatusCode == HttpStatusCode.Conflict)
            {
                // 204 = recorded; 409 = already recorded by a prior attempt.
                _log.LogInformation(
                    "SCAN_PARCEL {Id} -> FAILED ({Reason}) [{Status}]",
                    task.Id, reason, (int)resp.StatusCode);
                return;
            }

            _log.LogWarning(
                "SCAN_PARCEL {Id} fail-report backend returned {Status}; " +
                "leaving for backend sweep",
                task.Id, (int)resp.StatusCode);
        }
    }
}
