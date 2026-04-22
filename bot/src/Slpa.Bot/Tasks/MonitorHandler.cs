using Microsoft.Extensions.Logging;
using Slpa.Bot.Backend;
using Slpa.Bot.Backend.Models;
using Slpa.Bot.Sl;

namespace Slpa.Bot.Tasks;

/// <summary>
/// Handles MONITOR_AUCTION and MONITOR_ESCROW tasks. Mechanical classifier
/// only — compares observed values to expected values and reports the
/// outcome. The backend dispatcher owns interpretation.
/// </summary>
public sealed class MonitorHandler
{
    private readonly IBotSession _session;
    private readonly IBackendClient _backend;
    private readonly ILogger<MonitorHandler> _log;

    public MonitorHandler(
        IBotSession session,
        IBackendClient backend,
        ILogger<MonitorHandler> log)
    {
        _session = session;
        _backend = backend;
        _log = log;
    }

    public async Task HandleAsync(BotTaskResponse task, CancellationToken ct)
    {
        if (string.IsNullOrEmpty(task.RegionName)
            || task.PositionX is null || task.PositionY is null || task.PositionZ is null)
        {
            await _backend.PostMonitorAsync(task.Id,
                new BotMonitorResultRequest(
                    MonitorOutcome.ACCESS_DENIED, null, null, null,
                    "MISSING_COORDS"),
                ct).ConfigureAwait(false);
            return;
        }

        var tp = await _session.TeleportAsync(
            task.RegionName,
            task.PositionX.Value,
            task.PositionY.Value,
            task.PositionZ.Value,
            ct).ConfigureAwait(false);
        if (!tp.Success)
        {
            await _backend.PostMonitorAsync(task.Id,
                new BotMonitorResultRequest(
                    MonitorOutcome.ACCESS_DENIED, null, null, null,
                    tp.Failure?.ToString()),
                ct).ConfigureAwait(false);
            return;
        }

        var snap = await _session.ReadParcelAsync(
            task.PositionX.Value, task.PositionY.Value, ct).ConfigureAwait(false);
        if (snap is null)
        {
            await _backend.PostMonitorAsync(task.Id,
                new BotMonitorResultRequest(
                    MonitorOutcome.ACCESS_DENIED, null, null, null,
                    "PARCEL_READ_TIMEOUT"),
                ct).ConfigureAwait(false);
            return;
        }

        var outcome = Classify(task, snap);
        await _backend.PostMonitorAsync(task.Id,
            new BotMonitorResultRequest(
                Outcome: outcome,
                ObservedOwner: snap.OwnerId,
                ObservedAuthBuyer: snap.AuthBuyerId,
                ObservedSalePrice: snap.SalePrice,
                Note: null),
            ct).ConfigureAwait(false);
        _log.LogInformation("MONITOR {TaskId} ({Type}) reported {Outcome}",
                task.Id, task.TaskType, outcome);
    }

    private static MonitorOutcome Classify(BotTaskResponse task, ParcelSnapshot snap)
    {
        return task.TaskType switch
        {
            BotTaskType.MONITOR_AUCTION => ClassifyAuction(task, snap),
            BotTaskType.MONITOR_ESCROW => ClassifyEscrow(task, snap),
            _ => MonitorOutcome.STILL_WAITING
        };
    }

    private static MonitorOutcome ClassifyAuction(BotTaskResponse task, ParcelSnapshot snap)
    {
        if (task.ExpectedOwnerUuid is { } expOwner && snap.OwnerId != expOwner)
            return MonitorOutcome.OWNER_CHANGED;
        if (task.ExpectedAuthBuyerUuid is { } expAuth && snap.AuthBuyerId != expAuth)
            return MonitorOutcome.AUTH_BUYER_CHANGED;
        if (task.ExpectedSalePriceLindens is { } expPrice && snap.SalePrice != expPrice)
            return MonitorOutcome.PRICE_MISMATCH;
        return MonitorOutcome.ALL_GOOD;
    }

    private static MonitorOutcome ClassifyEscrow(BotTaskResponse task, ParcelSnapshot snap)
    {
        if (task.ExpectedWinnerUuid is { } winner && snap.OwnerId == winner)
            return MonitorOutcome.TRANSFER_COMPLETE;
        if (task.ExpectedSellerUuid is { } seller && snap.OwnerId != seller)
            return MonitorOutcome.OWNER_CHANGED;
        if (task.ExpectedWinnerUuid is { } winnerForAuth
            && snap.AuthBuyerId == winnerForAuth
            && task.ExpectedMaxSalePriceLindens is { } maxPrice
            && snap.SalePrice <= maxPrice)
            return MonitorOutcome.TRANSFER_READY;
        return MonitorOutcome.STILL_WAITING;
    }
}
