using Microsoft.Extensions.Logging;
using Slpa.Bot.Backend;
using Slpa.Bot.Backend.Models;
using Slpa.Bot.Sl;

namespace Slpa.Bot.Tasks;

/// <summary>
/// Spec §5 — handles VERIFY_SELL_TO tasks. Teleports onto the parcel, reads
/// ParcelProperties, classifies the seller's "Sell to + L$0" set-up against
/// the expected winner, and POSTs a <see cref="SellToOutcome"/> back to the
/// backend via <see cref="IBackendClient.ReportTaskResultAsync"/>. The
/// backend's escrow state machine owns the consequences; the bot only
/// observes + reports. The for-sale signal is read from the strongly-typed
/// <c>OpenMetaverse.ParcelFlags.ForSale</c> bit (1&lt;&lt;2) in
/// <see cref="IBotSession.ReadParcelAsync"/> and surfaced as
/// <see cref="ParcelSnapshot.ForSale"/> — note this is distinct from
/// <c>ParcelFlags.ForSaleObjects</c> (1&lt;&lt;7, 0x00000080).
/// </summary>
public sealed class VerifySellToHandler
{
    private readonly IBotSession _session;
    private readonly IBackendClient _backend;
    private readonly ILogger<VerifySellToHandler> _log;

    public VerifySellToHandler(IBotSession session, IBackendClient backend,
        ILogger<VerifySellToHandler> log)
    {
        _session = session;
        _backend = backend;
        _log = log;
    }

    public async Task HandleAsync(BotTaskResponse task, CancellationToken ct)
    {
        if (task.RegionName is null || task.PositionX is null || task.PositionY is null
            || task.ExpectedWinnerUuid is null)
        {
            _log.LogWarning("VERIFY_SELL_TO {Id} missing region/pos/winner", task.Id);
            return;
        }

        SellToOutcome outcome;
        Guid? owner = null, auth = null;
        long? price = null;
        bool? forSale = null;
        var tp = await _session.TeleportAsync(task.RegionName, task.PositionX.Value,
            task.PositionY.Value, task.PositionZ ?? 0, ct);
        if (!tp.Success)
        {
            outcome = tp.Failure == TeleportFailureKind.RegionNotFound
                ? SellToOutcome.PARCEL_NOT_FOUND
                : tp.Failure == TeleportFailureKind.AccessDenied
                    ? SellToOutcome.ACCESS_DENIED
                    : SellToOutcome.BOT_ERROR;
        }
        else
        {
            var snap = await _session.ReadParcelAsync(
                task.PositionX.Value, task.PositionY.Value, ct);
            if (snap is null)
            {
                outcome = SellToOutcome.BOT_ERROR;
            }
            else
            {
                owner = snap.OwnerId;
                auth = snap.AuthBuyerId;
                price = snap.SalePrice;
                forSale = snap.ForSale;
                if (snap.OwnerId == task.ExpectedWinnerUuid.Value)
                    outcome = SellToOutcome.OWNER_ALREADY_WINNER;
                else if (forSale != true || snap.AuthBuyerId == Guid.Empty)
                    outcome = SellToOutcome.SELL_TO_NOT_SET;
                else if (snap.AuthBuyerId != task.ExpectedWinnerUuid.Value)
                    outcome = SellToOutcome.WRONG_BUYER;
                else if (snap.SalePrice != 0)
                    outcome = SellToOutcome.PRICE_NOT_ZERO;
                else
                    outcome = SellToOutcome.SELL_TO_OK;
            }
        }

        await _backend.ReportTaskResultAsync(task.Id,
            new BotTaskResultRequest(outcome, owner, auth, price, forSale), ct);
        _log.LogInformation("VERIFY_SELL_TO {Id} -> {Outcome}", task.Id, outcome);
    }
}
