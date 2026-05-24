using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;
using Slpa.Bot.Backend;
using Slpa.Bot.Backend.Models;
using Slpa.Bot.Options;
using Slpa.Bot.Sl;

namespace Slpa.Bot.Tasks;

/// <summary>
/// Main driver. Claim → dispatch to handler → loop. Dual backoff: the
/// configured offline backoff when the session is not Online; the
/// empty-queue backoff when the queue is empty (both from
/// <see cref="BotOptions"/>). Handler exceptions are logged but never
/// reported back to the backend — the IN_PROGRESS timeout sweep cleans
/// stalled rows.
/// </summary>
public sealed class TaskLoop : BackgroundService
{
    private readonly TimeSpan _offlineBackoff;
    private readonly TimeSpan _emptyQueueBackoff;

    private readonly IBotSession _session;
    private readonly Func<WithdrawGroupHandler> _withdrawGroup;
    private readonly Func<VerifySellToHandler> _verifySellTo;
    private readonly Func<VerifyBuyOwnerHandler> _verifyBuyOwner;
    private readonly Func<ScanParcelHandler> _scanParcel;
    private readonly IBackendClient _backend;
    private readonly IIdleParker _idleParker;
    private readonly BotActivityState _activity;
    private readonly ILogger<TaskLoop> _log;

    /// <summary>
    /// Production ctor. Handlers are resolved via DI as singletons.
    /// </summary>
    public TaskLoop(
        IBotSession session,
        IBackendClient backend,
        IIdleParker idleParker,
        BotActivityState activity,
        IOptions<BotOptions> botOpts,
        WithdrawGroupHandler withdrawGroup,
        VerifySellToHandler verifySellTo,
        VerifyBuyOwnerHandler verifyBuyOwner,
        ScanParcelHandler scanParcel,
        ILogger<TaskLoop> log)
        : this(session, backend, idleParker, activity, botOpts.Value,
            () => withdrawGroup, () => verifySellTo, () => verifyBuyOwner,
            () => scanParcel, log)
    {
    }

    /// <summary>
    /// Test-friendly ctor: handlers built per-call by a factory. Lets tests
    /// wire fake handlers without setting up DI.
    /// </summary>
    internal TaskLoop(
        IBotSession session,
        IBackendClient backend,
        IIdleParker idleParker,
        BotActivityState activity,
        BotOptions botOpts,
        Func<WithdrawGroupHandler> withdrawGroup,
        Func<VerifySellToHandler> verifySellTo,
        Func<VerifyBuyOwnerHandler> verifyBuyOwner,
        Func<ScanParcelHandler> scanParcel,
        ILogger<TaskLoop> log)
    {
        _session = session;
        _backend = backend;
        _idleParker = idleParker;
        _activity = activity;
        _offlineBackoff = TimeSpan.FromSeconds(botOpts.OfflineBackoffSeconds);
        _emptyQueueBackoff = TimeSpan.FromSeconds(botOpts.EmptyQueueBackoffSeconds);
        _withdrawGroup = withdrawGroup;
        _verifySellTo = verifySellTo;
        _verifyBuyOwner = verifyBuyOwner;
        _scanParcel = scanParcel;
        _log = log;
    }

    /// <summary>
    /// BackgroundService hook. Delegates to <see cref="RunAsync"/> so tests
    /// can drive the loop directly without instantiating a full host.
    /// </summary>
    protected override Task ExecuteAsync(CancellationToken ct) => RunAsync(ct);

    /// <summary>
    /// Exposed to tests (and the hosted-service machinery) so the loop body
    /// can be driven with a test-owned cancellation token without going
    /// through <c>IHostedService.StartAsync</c>.
    /// </summary>
    internal async Task RunAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            if (_session.State != SessionState.Online)
            {
                await SafeDelayAsync(_offlineBackoff, ct).ConfigureAwait(false);
                continue;
            }

            BotTaskResponse? task;
            try
            {
                task = await _backend.ClaimAsync(_session.BotUuid, ct).ConfigureAwait(false);
            }
            catch (AuthConfigException ex)
            {
                _log.LogCritical(ex, "Auth config error; exiting");
                return;
            }
            catch (OperationCanceledException)
            {
                return;
            }
            catch (Exception ex)
            {
                _log.LogWarning(ex, "Claim failed; backing off");
                await SafeDelayAsync(_offlineBackoff, ct).ConfigureAwait(false);
                continue;
            }

            _activity.RecordClaim(task, DateTimeOffset.UtcNow);

            if (task is null)
            {
                try
                {
                    await _idleParker.ParkIfNeededAsync(ct).ConfigureAwait(false);
                }
                catch (OperationCanceledException)
                {
                    return;
                }
                await SafeDelayAsync(_emptyQueueBackoff, ct).ConfigureAwait(false);
                continue;
            }

            try
            {
                await DispatchAsync(task, ct).ConfigureAwait(false);
            }
            catch (SessionLostException ex)
            {
                _log.LogWarning(ex, "Session lost mid-task {Id}; backend sweep will clean up",
                        task.Id);
            }
            catch (OperationCanceledException)
            {
                return;
            }
            catch (Exception ex)
            {
                _log.LogError(ex, "Handler crashed on task {Id}; no callback", task.Id);
            }
            finally
            {
                _activity.Clear();
            }
        }
    }

    private Task DispatchAsync(BotTaskResponse task, CancellationToken ct)
    {
        return task.TaskType switch
        {
            BotTaskType.WITHDRAW_GROUP => _withdrawGroup().HandleAsync(task, ct),
            BotTaskType.VERIFY_SELL_TO => _verifySellTo().HandleAsync(task, ct),
            BotTaskType.VERIFY_BUY_OWNER => _verifyBuyOwner().HandleAsync(task, ct),
            BotTaskType.SCAN_PARCEL => _scanParcel().HandleAsync(task, ct),
            _ => Task.CompletedTask
        };
    }

    private static async Task SafeDelayAsync(TimeSpan delay, CancellationToken ct)
    {
        try { await Task.Delay(delay, ct).ConfigureAwait(false); }
        catch (OperationCanceledException) { /* shutting down */ }
    }
}
