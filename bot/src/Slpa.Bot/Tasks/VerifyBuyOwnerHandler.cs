using Microsoft.Extensions.Logging;
using Slpa.Bot.Backend;
using Slpa.Bot.Backend.Models;
using Slpa.Bot.Sl;

namespace Slpa.Bot.Tasks;

/// <summary>
/// Handles <see cref="BotTaskType.VERIFY_BUY_OWNER"/> tasks (bot-dispatch
/// verify-purchase, 2026-05-18). Teleports onto the parcel, reads
/// <see cref="ParcelSnapshot"/>, classifies the live owner against the
/// expected winner UUID stamped on the task, and POSTs a
/// <see cref="BuyOwnerOutcome"/> back to the backend via
/// <see cref="IBackendClient.ReportBuyOwnerResultAsync"/>.
///
/// <para><b>Classification (with the data the task currently carries).</b> The
/// dispatching backend stamps only <c>expectedWinnerUuid</c> on the
/// <c>VERIFY_BUY_OWNER</c> task — not the seller / registered-group UUID. The
/// handler can therefore only distinguish "owner == winner" from "owner !=
/// winner", and reports the residual case as
/// <see cref="BuyOwnerOutcome.OWNER_STILL_PRE_TRANSFER"/> (a definitive
/// negative that consumes a manual-verify attempt). True
/// <see cref="BuyOwnerOutcome.OWNER_IS_STRANGER"/> classification would need
/// the seller / group UUID on the task payload — the 30-minute World API
/// ownership monitor remains the authoritative path for fraud detection in
/// the meantime, so the conservative PRE_TRANSFER default keeps a misidentified
/// stranger from auto-freezing a healthy escrow.</para>
///
/// <para><b>Failure mapping.</b> Region-not-found → <see cref="BuyOwnerOutcome.PARCEL_DELETED"/>;
/// teleport-access-denied / read-parcel null → <see cref="BuyOwnerOutcome.WORLD_API_FAILURE"/>
/// (transient — the parcel itself may be fine, the bot just couldn't observe it).
/// Unexpected exceptions → <see cref="BuyOwnerOutcome.UNKNOWN_ERROR"/>.</para>
/// </summary>
public sealed class VerifyBuyOwnerHandler
{
    private readonly IBotSession _session;
    private readonly IBackendClient _backend;
    private readonly ILogger<VerifyBuyOwnerHandler> _log;

    public VerifyBuyOwnerHandler(IBotSession session, IBackendClient backend,
        ILogger<VerifyBuyOwnerHandler> log)
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
            _log.LogWarning("VERIFY_BUY_OWNER {Id} missing region/pos/winner", task.Id);
            await _backend.ReportBuyOwnerResultAsync(task.Id,
                new BuyOwnerResultRequest(BuyOwnerOutcome.UNKNOWN_ERROR, null, null), ct)
                .ConfigureAwait(false);
            return;
        }

        BuyOwnerOutcome outcome;
        Guid? observedOwner = null;
        string? observedOwnerType = null;

        try
        {
            var tp = await _session.TeleportAsync(task.RegionName,
                task.PositionX.Value, task.PositionY.Value,
                task.PositionZ ?? 0, ct).ConfigureAwait(false);
            if (!tp.Success)
            {
                outcome = tp.Failure switch
                {
                    TeleportFailureKind.RegionNotFound => BuyOwnerOutcome.PARCEL_DELETED,
                    // ACCESS_DENIED and other failures are transient observation
                    // problems on the bot's side, not evidence the parcel itself
                    // has changed hands — let the user retry rather than burning
                    // an attempt or auto-freezing.
                    _ => BuyOwnerOutcome.WORLD_API_FAILURE
                };
            }
            else
            {
                var snap = await _session.ReadParcelAsync(
                    task.PositionX.Value, task.PositionY.Value, ct).ConfigureAwait(false);
                if (snap is null)
                {
                    outcome = BuyOwnerOutcome.WORLD_API_FAILURE;
                }
                else
                {
                    observedOwner = snap.OwnerId;
                    observedOwnerType = snap.IsGroupOwned ? "group" : "agent";
                    if (snap.OwnerId == task.ExpectedWinnerUuid.Value)
                    {
                        outcome = BuyOwnerOutcome.OWNER_IS_WINNER;
                    }
                    else
                    {
                        // Conservative default: without expectedSellerUuid /
                        // expectedGroupUuid on the task payload we cannot
                        // reliably distinguish seller/group (PRE_TRANSFER) from
                        // a true STRANGER. The 30-min World API ownership
                        // monitor is the authoritative stranger-detector;
                        // misclassifying a seller as a stranger here would
                        // wrongly freeze a healthy escrow, so the safer
                        // default is PRE_TRANSFER (consumes one attempt).
                        outcome = BuyOwnerOutcome.OWNER_STILL_PRE_TRANSFER;
                    }
                }
            }
        }
        catch (OperationCanceledException)
        {
            throw;
        }
        catch (SessionLostException)
        {
            // Re-throw so the TaskLoop's standard session-lost handling kicks
            // in (logs + lets the backend sweep clean the stalled row).
            throw;
        }
        catch (Exception ex)
        {
            _log.LogError(ex, "VERIFY_BUY_OWNER {Id} unexpected error", task.Id);
            outcome = BuyOwnerOutcome.UNKNOWN_ERROR;
        }

        await _backend.ReportBuyOwnerResultAsync(task.Id,
            new BuyOwnerResultRequest(outcome, observedOwner, observedOwnerType), ct)
            .ConfigureAwait(false);
        _log.LogInformation("VERIFY_BUY_OWNER {Id} -> {Outcome}", task.Id, outcome);
    }
}
