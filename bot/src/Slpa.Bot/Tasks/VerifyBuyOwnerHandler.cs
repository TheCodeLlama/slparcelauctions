using Microsoft.Extensions.Logging;
using Slpa.Bot.Backend;
using Slpa.Bot.Backend.Models;
using Slpa.Bot.Sl;

namespace Slpa.Bot.Tasks;

/// <summary>
/// Handles <see cref="BotTaskType.VERIFY_BUY_OWNER"/> tasks (bot-dispatch
/// verify-purchase, 2026-05-18; full classification 2026-05-19). Teleports
/// onto the parcel, reads <see cref="ParcelSnapshot"/>, classifies the live
/// owner against the expected winner UUID + expected pre-transfer owner
/// stamped on the task, and POSTs a <see cref="BuyOwnerOutcome"/> back to
/// the backend via <see cref="IBackendClient.ReportBuyOwnerResultAsync"/>.
///
/// <para><b>Classification matrix.</b> The backend stamps the task with both
/// <c>expectedWinnerUuid</c> and <c>expectedPreTransferUuid</c>
/// (seller avatar for case-1 / registered SL group UUID for case-3) plus
/// <c>expectedOwnerType</c> ("agent" / "group"):
/// <list type="bullet">
///   <item><description>Owner UUID == winner AND ownerType == "agent" →
///   <see cref="BuyOwnerOutcome.OWNER_IS_WINNER"/>.</description></item>
///   <item><description>Owner UUID == expected pre-transfer UUID AND
///   ownerType == expected pre-transfer ownerType →
///   <see cref="BuyOwnerOutcome.OWNER_STILL_PRE_TRANSFER"/> (consumes one
///   manual-verify attempt).</description></item>
///   <item><description>Otherwise →
///   <see cref="BuyOwnerOutcome.OWNER_IS_STRANGER"/> (backend freezes the
///   escrow for fraud review).</description></item>
/// </list>
/// A defensive UUID-match-but-type-mismatch case (shouldn't happen in SL but
/// possible if the pre-transfer entity changed group status mid-flight) also
/// maps to <c>OWNER_IS_STRANGER</c> — the safer side, since a flipped owner
/// type implies a real ownership change.</para>
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
                    outcome = Classify(
                        observedOwner: snap.OwnerId,
                        observedOwnerType: observedOwnerType,
                        expectedWinner: task.ExpectedWinnerUuid.Value,
                        expectedPreTransfer: task.ExpectedPreTransferUuid,
                        expectedPreTransferType: task.ExpectedOwnerType);
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

    /// <summary>
    /// Maps the observed (owner, ownerType) pair against the task's expected
    /// winner / pre-transfer context. See the class docstring for the full
    /// matrix. <paramref name="expectedPreTransfer"/> / <paramref name="expectedPreTransferType"/>
    /// may be null on legacy / malformed tasks — without them we cannot
    /// safely classify PRE_TRANSFER, so any non-winner owner is reported as
    /// STRANGER (the backend's existing fraud-evidence path will surface the
    /// observed UUID for the admin queue).
    /// </summary>
    internal static BuyOwnerOutcome Classify(
        Guid observedOwner,
        string observedOwnerType,
        Guid expectedWinner,
        Guid? expectedPreTransfer,
        string? expectedPreTransferType)
    {
        if (observedOwner == expectedWinner && observedOwnerType == "agent")
        {
            return BuyOwnerOutcome.OWNER_IS_WINNER;
        }

        if (expectedPreTransfer is not null
            && observedOwner == expectedPreTransfer.Value
            && expectedPreTransferType is not null
            && observedOwnerType == expectedPreTransferType)
        {
            return BuyOwnerOutcome.OWNER_STILL_PRE_TRANSFER;
        }

        return BuyOwnerOutcome.OWNER_IS_STRANGER;
    }
}
