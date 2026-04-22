using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;
using OpenMetaverse;
using Slpa.Bot.Options;

namespace Slpa.Bot.Sl;

/// <summary>
/// LibreMetaverse-backed <see cref="IBotSession"/>. On construction the
/// <see cref="GridClient"/> is configured for a headless bot — no textures,
/// no mesh downloads, no sounds, no inventory fetch.
///
/// The login loop watches <see cref="NetworkManager.LoginProgress"/> and
/// <see cref="NetworkManager.Disconnected"/> to drive state transitions.
/// On disconnect, auto-reconnect with exponential backoff (1s, 2s, 4s, 8s…
/// capped at 60s) until the cancellation token fires.
/// </summary>
public sealed class LibreMetaverseBotSession : IBotSession
{
    private static readonly TimeSpan[] ReconnectBackoff =
        new[] { 1, 2, 4, 8, 15, 30, 60 }
            .Select(s => TimeSpan.FromSeconds(s))
            .ToArray();

    private const string LoginUri =
        "https://login.agni.lindenlab.com/cgi-bin/login.cgi";

    private readonly BotOptions _opts;
    private readonly ILogger<LibreMetaverseBotSession> _log;
    private readonly GridClient _client;
    private CancellationTokenSource? _runCts;
    private Task? _runTask;
    private int _stateValue = (int)SessionState.Starting;

    public LibreMetaverseBotSession(
        IOptions<BotOptions> opts,
        ILogger<LibreMetaverseBotSession> log)
    {
        _opts = opts.Value;
        _log = log;
        _client = CreateHeadlessClient();
    }

    public SessionState State => (SessionState)Volatile.Read(ref _stateValue);

    public Guid BotUuid => _opts.BotUuid;

    public Task StartAsync(CancellationToken ct)
    {
        _runCts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        _runTask = Task.Run(() => RunLoop(_runCts.Token), _runCts.Token);
        return Task.CompletedTask;
    }

    public async Task LogoutAsync(CancellationToken ct)
    {
        if (State == SessionState.Error
            || State == SessionState.Starting
            || State == SessionState.Stopped)
        {
            return;
        }
        try
        {
            _client.Network.Logout();
            TransitionTo(SessionState.Stopped);
        }
        catch (Exception ex)
        {
            _log.LogWarning(ex, "Logout failed — may leave zombie session");
            TransitionTo(SessionState.Error);
        }
        _runCts?.Cancel();
        if (_runTask is not null)
        {
            try { await _runTask.ConfigureAwait(false); }
            catch (OperationCanceledException) { /* expected */ }
        }
    }

    public async ValueTask DisposeAsync()
    {
        _runCts?.Cancel();
        if (_runTask is not null)
        {
            try { await _runTask.ConfigureAwait(false); }
            catch { /* swallow on dispose */ }
        }
        _runCts?.Dispose();
    }

    private async Task RunLoop(CancellationToken ct)
    {
        var backoffIdx = 0;
        while (!ct.IsCancellationRequested)
        {
            TransitionTo(SessionState.Starting);
            var loginParams = _client.Network.DefaultLoginParams(
                FirstName(), LastName(), _opts.Password,
                "Slpa.Bot", "1.0");
            loginParams.URI = LoginUri;
            loginParams.Start = _opts.StartLocation;

            bool loggedIn;
            try
            {
                loggedIn = await TryLoginAsync(loginParams, ct).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                return;
            }
            if (!loggedIn)
            {
                var delay = ReconnectBackoff[
                    Math.Min(backoffIdx, ReconnectBackoff.Length - 1)];
                _log.LogWarning("Login failed; retrying in {Delay}", delay);
                backoffIdx++;
                try { await Task.Delay(delay, ct).ConfigureAwait(false); }
                catch (OperationCanceledException) { return; }
                continue;
            }

            backoffIdx = 0;
            TransitionTo(SessionState.Online);
            _log.LogInformation("Bot {Uuid} ONLINE as {User}", BotUuid, _opts.Username);

            try
            {
                await WaitForDisconnectAsync(ct).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                return;
            }
            if (ct.IsCancellationRequested) return;

            TransitionTo(SessionState.Reconnecting);
            _log.LogWarning("Bot {Uuid} disconnected; reconnecting", BotUuid);
        }
    }

    private async Task<bool> TryLoginAsync(LoginParams loginParams, CancellationToken ct)
    {
        var tcs = new TaskCompletionSource<bool>(
            TaskCreationOptions.RunContinuationsAsynchronously);
        EventHandler<LoginProgressEventArgs>? handler = null;
        handler = (_, e) =>
        {
            switch (e.Status)
            {
                case LoginStatus.Success:
                    _client.Network.LoginProgress -= handler;
                    tcs.TrySetResult(true);
                    break;
                case LoginStatus.Failed:
                    _client.Network.LoginProgress -= handler;
                    _log.LogError("Login failed: {Message}", e.Message);
                    tcs.TrySetResult(false);
                    break;
            }
        };
        _client.Network.LoginProgress += handler;
        var registration = ct.Register(() =>
        {
            _client.Network.LoginProgress -= handler;
            tcs.TrySetCanceled(ct);
        });
        try
        {
            _client.Network.BeginLogin(loginParams);
            return await tcs.Task.ConfigureAwait(false);
        }
        finally
        {
            registration.Dispose();
        }
    }

    private async Task WaitForDisconnectAsync(CancellationToken ct)
    {
        var tcs = new TaskCompletionSource<object?>(
            TaskCreationOptions.RunContinuationsAsynchronously);
        EventHandler<DisconnectedEventArgs>? handler = null;
        handler = (_, _) =>
        {
            _client.Network.Disconnected -= handler!;
            tcs.TrySetResult(null);
        };
        _client.Network.Disconnected += handler;
        var registration = ct.Register(() =>
        {
            _client.Network.Disconnected -= handler!;
            tcs.TrySetCanceled(ct);
        });
        try
        {
            await tcs.Task.ConfigureAwait(false);
        }
        finally
        {
            registration.Dispose();
        }
    }

    private void TransitionTo(SessionState next)
    {
        Volatile.Write(ref _stateValue, (int)next);
    }

    private static GridClient CreateHeadlessClient()
    {
        var c = new GridClient();
        c.Settings.MULTIPLE_SIMS = false;
        c.Settings.ALWAYS_REQUEST_PARCEL_ACL = false;
        c.Settings.ALWAYS_REQUEST_PARCEL_DWELL = false;
        c.Settings.ALWAYS_DECODE_OBJECTS = false;
        c.Settings.OBJECT_TRACKING = false;
        c.Settings.AVATAR_TRACKING = false;
        c.Settings.USE_ASSET_CACHE = false;
        c.Settings.SEND_AGENT_APPEARANCE = false;
        c.Settings.SEND_AGENT_UPDATES = true; // required for teleport
        c.Settings.STORE_LAND_PATCHES = false;
        return c;
    }

    private string FirstName()
    {
        var parts = _opts.Username.Split(' ', 2);
        return parts.Length > 0 ? parts[0] : _opts.Username;
    }

    private string LastName()
    {
        var parts = _opts.Username.Split(' ', 2);
        return parts.Length > 1 ? parts[1] : "Resident";
    }
}
