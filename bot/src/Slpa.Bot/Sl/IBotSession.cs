namespace Slpa.Bot.Sl;

/// <summary>
/// Test boundary around LibreMetaverse. Production wiring uses
/// <see cref="LibreMetaverseBotSession"/>; tests fake this interface and
/// never touch <c>GridClient</c>.
/// </summary>
public interface IBotSession : IAsyncDisposable
{
    SessionState State { get; }

    Guid BotUuid { get; }

    /// <summary>Starts the login loop. Idempotent; returns when state != Starting.</summary>
    Task StartAsync(CancellationToken ct);

    /// <summary>Logs out cleanly. No-op if already offline.</summary>
    Task LogoutAsync(CancellationToken ct);

    /// <summary>
    /// Teleports to <paramref name="regionName"/> at (x, y, z). Awaits the
    /// LibreMetaverse TeleportFinished / TeleportFailed race with a 30s
    /// timeout. Rate-limited per SL's 6/min cap. Throws
    /// <see cref="SessionLostException"/> if the session drops mid-teleport.
    /// </summary>
    Task<TeleportResult> TeleportAsync(
        string regionName, double x, double y, double z, CancellationToken ct);

    /// <summary>
    /// Requests ParcelProperties for the parcel at (x, y) within the
    /// bot's current region. Awaits the ParcelProperties event with a 10s
    /// timeout. Returns null on timeout; throws on session loss.
    /// </summary>
    Task<ParcelSnapshot?> ReadParcelAsync(double x, double y, CancellationToken ct);
}
