using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using Slpa.Bot.Backend;
using Slpa.Bot.Backend.Models;
using Slpa.Bot.Sl;

namespace Slpa.Bot.Tasks;

/// <summary>
/// Main driver. Claim → dispatch to handler → loop. Dual backoff: 5 s when
/// the session is not Online; 15 s when the queue is empty. Handler
/// exceptions are logged but never reported back to the backend — the
/// IN_PROGRESS timeout sweep cleans stalled rows.
/// </summary>
public sealed class TaskLoop : BackgroundService
{
    private static readonly TimeSpan OfflineBackoff = TimeSpan.FromSeconds(5);
    private static readonly TimeSpan EmptyQueueBackoff = TimeSpan.FromSeconds(15);

    private readonly IBotSession _session;
    private readonly Func<WithdrawGroupHandler> _withdrawGroup;
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
        WithdrawGroupHandler withdrawGroup,
        ILogger<TaskLoop> log)
        : this(session, backend, idleParker, activity,
            () => withdrawGroup, log)
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
        Func<WithdrawGroupHandler> withdrawGroup,
        ILogger<TaskLoop> log)
    {
        _session = session;
        _backend = backend;
        _idleParker = idleParker;
        _activity = activity;
        _withdrawGroup = withdrawGroup;
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
                await SafeDelayAsync(OfflineBackoff, ct).ConfigureAwait(false);
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
                await SafeDelayAsync(OfflineBackoff, ct).ConfigureAwait(false);
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
                await SafeDelayAsync(EmptyQueueBackoff, ct).ConfigureAwait(false);
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
            _ => Task.CompletedTask
        };
    }

    private static async Task SafeDelayAsync(TimeSpan delay, CancellationToken ct)
    {
        try { await Task.Delay(delay, ct).ConfigureAwait(false); }
        catch (OperationCanceledException) { /* shutting down */ }
    }
}
