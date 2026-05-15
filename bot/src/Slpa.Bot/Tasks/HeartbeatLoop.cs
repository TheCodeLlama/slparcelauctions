using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;
using Slpa.Bot.Backend;
using Slpa.Bot.Backend.Models;
using Slpa.Bot.Options;
using Slpa.Bot.Sl;

namespace Slpa.Bot.Tasks;

/// <summary>
/// Sends a heartbeat every <see cref="HeartbeatOptions.IntervalSeconds"/>.
/// Runs regardless of session state (so the admin can tell "alive but
/// Reconnecting" from "process dead = TTL expired"). Never crashes — every
/// failure incl. <see cref="AuthConfigException"/> is logged and swallowed;
/// the claim path remains the authoritative 401 handler.
/// </summary>
public sealed class HeartbeatLoop : BackgroundService
{
    private readonly IBotSession _session;
    private readonly IBackendClient _backend;
    private readonly BotActivityState _activity;
    private readonly BotOptions _botOpts;
    private readonly HeartbeatOptions _hbOpts;
    private readonly ILogger<HeartbeatLoop> _log;

    public HeartbeatLoop(
        IBotSession session,
        IBackendClient backend,
        BotActivityState activity,
        IOptions<BotOptions> botOpts,
        IOptions<HeartbeatOptions> hbOpts,
        ILogger<HeartbeatLoop> log)
    {
        _session = session;
        _backend = backend;
        _activity = activity;
        _botOpts = botOpts.Value;
        _hbOpts = hbOpts.Value;
        _log = log;
    }

    protected override Task ExecuteAsync(CancellationToken ct) => RunAsync(ct);

    internal async Task RunAsync(CancellationToken ct)
    {
        var interval = TimeSpan.FromSeconds(_hbOpts.IntervalSeconds);
        while (!ct.IsCancellationRequested)
        {
            try
            {
                await SendOnceAsync(ct).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                return;
            }
            catch (Exception ex)
            {
                _log.LogWarning(ex,
                    "Heartbeat send failed; retrying next interval");
            }

            try
            {
                await Task.Delay(interval, ct).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                return;
            }
        }
    }

    internal async Task SendOnceAsync(CancellationToken ct)
    {
        var snap = _activity.Current;
        var loc = _session.CurrentLocation;
        var req = new BotHeartbeatRequest(
            WorkerName: _botOpts.Username,
            SlUuid: _session.BotUuid.ToString(),
            SessionState: _session.State.ToString(),
            CurrentRegion: loc?.Region,
            CurrentTaskKey: snap.CurrentTaskId?.ToString(),
            CurrentTaskType: snap.CurrentTaskType,
            LastClaimAt: snap.LastClaimAt);
        await _backend.SendHeartbeatAsync(req, ct).ConfigureAwait(false);
    }
}
