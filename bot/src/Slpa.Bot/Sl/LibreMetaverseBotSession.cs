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
    private readonly TeleportRateLimiter _rateLimiter;
    private CancellationTokenSource? _runCts;
    private Task? _runTask;
    private int _stateValue = (int)SessionState.Starting;

    public LibreMetaverseBotSession(
        IOptions<BotOptions> opts,
        IOptions<RateLimitOptions> rateOpts,
        ILogger<LibreMetaverseBotSession> log)
    {
        _opts = opts.Value;
        _log = log;
        _client = CreateHeadlessClient();
        _rateLimiter = new TeleportRateLimiter(rateOpts.Value.TeleportsPerMinute);
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

    public async Task<TeleportResult> TeleportAsync(
        string regionName, double x, double y, double z, CancellationToken ct)
    {
        if (State != SessionState.Online)
        {
            throw new SessionLostException(
                $"Cannot teleport in state {State}");
        }

        // Shortcut: if the bot is already in the target sim (common on the
        // second+ monitor cycle for the same auction), skip the teleport.
        // LibreMetaverse's Self.Teleport to the same-region returns a weird
        // TeleportFailed with an unrecognized message, which ClassifyFailure
        // would map to TeleportFailureKind.Other and MonitorHandler would
        // then misinterpret as ACCESS_DENIED — bumping the streak and
        // eventually tripping suspension on what's really a happy-path
        // re-read. ReadParcelAsync will re-issue RequestAllSimParcels to
        // get fresh data regardless of whether we teleported.
        var currentSim = _client.Network.CurrentSim;
        if (currentSim is not null
            && string.Equals(currentSim.Name, regionName,
                StringComparison.OrdinalIgnoreCase))
        {
            _log.LogDebug(
                "Already in sim {Region}; skipping teleport.", regionName);
            return TeleportResult.Ok();
        }

        await _rateLimiter.AcquireAsync(ct).ConfigureAwait(false);

        var tcs = new TaskCompletionSource<TeleportResult>(
            TaskCreationOptions.RunContinuationsAsynchronously);
        EventHandler<TeleportEventArgs>? handler = null;
        handler = (_, e) =>
        {
            if (e.Status is TeleportStatus.Finished
                or TeleportStatus.Failed
                or TeleportStatus.Cancelled)
            {
                _client.Self.TeleportProgress -= handler!;
                if (e.Status == TeleportStatus.Failed)
                {
                    _log.LogDebug(
                        "TeleportStatus.Failed raw message: {Message}",
                        e.Message);
                }
                var result = e.Status == TeleportStatus.Finished
                    ? TeleportResult.Ok()
                    : TeleportResult.Fail(ClassifyFailure(e.Message));
                tcs.TrySetResult(result);
            }
        };
        _client.Self.TeleportProgress += handler;
        EventHandler<DisconnectedEventArgs>? disc = null;
        disc = (_, _) => tcs.TrySetException(
            new SessionLostException("Disconnected mid-teleport"));
        _client.Network.Disconnected += disc;

        using var timeoutCts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeoutCts.CancelAfter(TimeSpan.FromSeconds(30));
        var registration = timeoutCts.Token.Register(() =>
            tcs.TrySetResult(TeleportResult.Fail(TeleportFailureKind.Timeout)));
        try
        {
            _client.Self.Teleport(regionName, new Vector3((float)x, (float)y, (float)z));
            return await tcs.Task.ConfigureAwait(false);
        }
        finally
        {
            registration.Dispose();
            _client.Self.TeleportProgress -= handler;
            _client.Network.Disconnected -= disc!;
        }
    }

    public async Task<ParcelSnapshot?> ReadParcelAsync(
        double x, double y, CancellationToken ct)
    {
        if (State != SessionState.Online)
        {
            throw new SessionLostException(
                $"Cannot read parcel in state {State}");
        }

        var sim = _client.Network.CurrentSim;
        if (sim is null) return null;

        // Wait for the simulator's parcel catalog to finish downloading.
        // RequestAllSimParcels fires one ParcelProperties per parcel and
        // finally a SimParcelsDownloaded once every parcel is cached.
        // Without this wait, sim.ParcelMap is zero for the first few
        // seconds after teleport and sim.Parcels is a partial dict —
        // resolving too early returns whichever parcel happened to arrive
        // first, not the one at (x, y). The backend then 400s on the
        // mismatched observation.
        var downloadedTcs = new TaskCompletionSource<bool>(
            TaskCreationOptions.RunContinuationsAsynchronously);
        EventHandler<SimParcelsDownloadedEventArgs>? downloadedHandler = null;
        downloadedHandler = (_, args) =>
        {
            if (args.Simulator == sim)
            {
                downloadedTcs.TrySetResult(true);
            }
        };
        _client.Parcels.SimParcelsDownloaded += downloadedHandler;

        using var timeoutCts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeoutCts.CancelAfter(TimeSpan.FromSeconds(30));
        var timeoutReg = timeoutCts.Token.Register(
            () => downloadedTcs.TrySetResult(false));

        bool downloaded;
        try
        {
            _client.Parcels.RequestAllSimParcels(sim);
            downloaded = await downloadedTcs.Task.ConfigureAwait(false);
        }
        finally
        {
            timeoutReg.Dispose();
            _client.Parcels.SimParcelsDownloaded -= downloadedHandler;
        }

        if (!downloaded)
        {
            _log.LogWarning(
                "SimParcelsDownloaded did not fire within 30s for sim {Sim}; " +
                "reading whatever data is cached so far.",
                sim.Name);
        }

        // ParcelMap maps 4m grid cells -> LocalID. With the catalog fully
        // downloaded this is populated; clamp landing coords into range.
        int row = Math.Clamp((int)(y / 4.0), 0, 63);
        int col = Math.Clamp((int)(x / 4.0), 0, 63);
        int localId = sim.ParcelMap[row, col];
        if (localId == 0)
        {
            _log.LogError(
                "ParcelMap empty at ({X}, {Y}) for sim {Sim} even after " +
                "SimParcelsDownloaded; cannot identify landing parcel.",
                x, y, sim.Name);
            return null;
        }

        // Pull the Parcel object directly from the simulator's cache —
        // populated by ParcelProperties events during the download. No
        // second round-trip needed.
        if (!sim.Parcels.TryGetValue(localId, out var parcel) || parcel is null)
        {
            _log.LogError(
                "Parcel {LocalId} found in ParcelMap at ({X}, {Y}) but not " +
                "in sim.Parcels cache — race hazard, giving up.",
                localId, x, y);
            return null;
        }

        _log.LogInformation(
            "ReadParcel resolved LocalID {LocalId} at ({X},{Y}) in {Sim}: " +
            "name='{Name}' owner={Owner} authBuyer={AuthBuyer} salePrice={Price}",
            localId, x, y, sim.Name, parcel.Name, parcel.OwnerID,
            parcel.AuthBuyerID, parcel.SalePrice);

        return new ParcelSnapshot(
            OwnerId: Guid.Parse(parcel.OwnerID.ToString()),
            GroupId: Guid.Parse(parcel.GroupID.ToString()),
            IsGroupOwned: parcel.IsGroupOwned,
            AuthBuyerId: Guid.Parse(parcel.AuthBuyerID.ToString()),
            SalePrice: parcel.SalePrice,
            Name: parcel.Name ?? string.Empty,
            Description: parcel.Desc ?? string.Empty,
            AreaSqm: parcel.Area,
            MaxPrims: parcel.MaxPrims,
            Category: (int)parcel.Category,
            SnapshotId: Guid.Parse(parcel.SnapshotID.ToString()),
            Flags: (uint)parcel.Flags);
    }

    private static TeleportFailureKind ClassifyFailure(string? message)
    {
        if (string.IsNullOrEmpty(message)) return TeleportFailureKind.Other;
        var m = message.ToLowerInvariant();
        if (m.Contains("denied") || m.Contains("banned")
            || m.Contains("restricted")) return TeleportFailureKind.AccessDenied;
        if (m.Contains("not found") || m.Contains("does not exist"))
            return TeleportFailureKind.RegionNotFound;
        return TeleportFailureKind.Other;
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
