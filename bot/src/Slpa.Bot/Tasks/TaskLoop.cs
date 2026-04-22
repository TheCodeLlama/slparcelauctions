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
    private readonly Func<VerifyHandler> _verify;
    private readonly Func<MonitorHandler> _monitor;
    private readonly IBackendClient _backend;
    private readonly ILogger<TaskLoop> _log;

    /// <summary>
    /// Production ctor. Handlers are resolved via DI as singletons.
    /// </summary>
    public TaskLoop(
        IBotSession session,
        IBackendClient backend,
        VerifyHandler verify,
        MonitorHandler monitor,
        ILogger<TaskLoop> log)
        : this(session, backend, () => verify, () => monitor, log)
    {
    }

    /// <summary>
    /// Test-friendly ctor: handlers built per-call by a factory. Lets tests
    /// wire fake handlers without setting up DI.
    /// </summary>
    internal TaskLoop(
        IBotSession session,
        IBackendClient backend,
        Func<VerifyHandler> verify,
        Func<MonitorHandler> monitor,
        ILogger<TaskLoop> log)
    {
        _session = session;
        _backend = backend;
        _verify = verify;
        _monitor = monitor;
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

            if (task is null)
            {
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
        }
    }

    private Task DispatchAsync(BotTaskResponse task, CancellationToken ct)
    {
        return task.TaskType switch
        {
            BotTaskType.VERIFY => _verify().HandleAsync(task, ct),
            BotTaskType.MONITOR_AUCTION => _monitor().HandleAsync(task, ct),
            BotTaskType.MONITOR_ESCROW => _monitor().HandleAsync(task, ct),
            _ => Task.CompletedTask
        };
    }

    private static async Task SafeDelayAsync(TimeSpan delay, CancellationToken ct)
    {
        try { await Task.Delay(delay, ct).ConfigureAwait(false); }
        catch (OperationCanceledException) { /* shutting down */ }
    }
}
