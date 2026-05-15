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

    /// <summary>
    /// The bot's current region + (x, y), or null when no sim is resolved
    /// yet (transient post-login). Used by idle-park; never blocks.
    /// </summary>
    BotLocation? CurrentLocation { get; }

    /// <summary>Starts the login loop. Idempotent; returns when state != Starting.</summary>
    Task StartAsync(CancellationToken ct);

    /// <summary>Logs out cleanly. No-op if already offline.</summary>
    Task LogoutAsync(CancellationToken ct);

    /// <summary>
    /// Teleports to <paramref name="regionName"/> at (x, y, z). Awaits the
    /// LibreMetaverse TeleportFinished / TeleportFailed race with a 30s
    /// timeout. Rate-limited per SL's 6/min cap. Throws
    /// <see cref="SessionLostException"/> if the session drops mid-teleport.
    /// When <paramref name="forceMove"/> is false (default) and the bot is
    /// already in the target sim, the teleport is skipped (returns Ok) — this
    /// preserves the documented false-ACCESS_DENIED fix for monitor/verify.
    /// Idle-park passes <c>forceMove: true</c> to relocate within a sim.
    /// </summary>
    Task<TeleportResult> TeleportAsync(
        string regionName, double x, double y, double z,
        CancellationToken ct, bool forceMove = false);

    /// <summary>
    /// Requests ParcelProperties for the parcel at (x, y) within the
    /// bot's current region. Awaits the ParcelProperties event with a 10s
    /// timeout. Returns null on timeout; throws on session loss.
    /// </summary>
    Task<ParcelSnapshot?> ReadParcelAsync(double x, double y, CancellationToken ct);

    /// <summary>
    /// Issues a Self.GiveGroupMoney transfer from the logged-in avatar to the
    /// supplied SL group UUID. Returns synchronously -- LibreMetaverse fires the
    /// transfer and the caller has no acknowledgement to await locally; success
    /// vs. failure surfaces via the in-world money-tracker callback, which the
    /// backend ingests separately. Sub-project G §7.4.
    /// </summary>
    void GiveGroupMoney(Guid slGroupUuid, int amountL, string memo);
}
