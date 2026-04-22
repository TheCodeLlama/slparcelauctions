using Microsoft.Extensions.Logging;
using Slpa.Bot.Backend;
using Slpa.Bot.Backend.Models;
using Slpa.Bot.Sl;

namespace Slpa.Bot.Tasks;

/// <summary>
/// Handles VERIFY tasks. Observation-only: teleports, reads parcel, reports
/// to the backend without interpreting whether the observation constitutes
/// pass or fail. The backend validates against the sentinel price + escrow
/// UUID (see <c>BotTaskService.complete</c>).
/// </summary>
public sealed class VerifyHandler
{
    private readonly IBotSession _session;
    private readonly IBackendClient _backend;
    private readonly ILogger<VerifyHandler> _log;

    public VerifyHandler(
        IBotSession session,
        IBackendClient backend,
        ILogger<VerifyHandler> log)
    {
        _session = session;
        _backend = backend;
        _log = log;
    }

    public async Task HandleAsync(BotTaskResponse task, CancellationToken ct)
    {
        var tp = await _session.TeleportAsync(
            task.RegionName ?? string.Empty,
            task.PositionX ?? 128,
            task.PositionY ?? 128,
            task.PositionZ ?? 20,
            ct).ConfigureAwait(false);
        if (!tp.Success)
        {
            await _backend.CompleteVerifyAsync(task.Id,
                Failure(tp.Failure?.ToString() ?? "Unknown teleport failure"),
                ct).ConfigureAwait(false);
            return;
        }

        var snapshot = await _session.ReadParcelAsync(
            task.PositionX ?? 128, task.PositionY ?? 128, ct).ConfigureAwait(false);
        if (snapshot is null)
        {
            await _backend.CompleteVerifyAsync(task.Id,
                Failure("PARCEL_READ_TIMEOUT"), ct).ConfigureAwait(false);
            return;
        }

        await _backend.CompleteVerifyAsync(task.Id,
            new BotTaskCompleteRequest(
                Result: "SUCCESS",
                AuthBuyerId: snapshot.AuthBuyerId,
                SalePrice: snapshot.SalePrice,
                ParcelOwner: snapshot.OwnerId,
                ParcelName: snapshot.Name,
                AreaSqm: snapshot.AreaSqm,
                RegionName: task.RegionName,
                PositionX: task.PositionX,
                PositionY: task.PositionY,
                PositionZ: task.PositionZ,
                FailureReason: null),
            ct).ConfigureAwait(false);
        _log.LogInformation("VERIFY {TaskId} reported SUCCESS", task.Id);
    }

    private static BotTaskCompleteRequest Failure(string reason) =>
        new("FAILURE", null, null, null, null, null, null, null, null, null, reason);
}
