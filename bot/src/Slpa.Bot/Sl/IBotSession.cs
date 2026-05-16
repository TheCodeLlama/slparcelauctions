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

    /// <summary>
    /// True when the bot is seated on a chair it successfully sat on this
    /// rest (belief-based — set by <see cref="SitAsync"/>, cleared by any
    /// teleport or disconnect). Object tracking is off on the headless
    /// client so a localID-&gt;UUID mapping is unreliable; this belief is the
    /// idle-park "still parked?" signal.
    /// </summary>
    bool IsSeated { get; }

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
    /// Sits the bot on the object <paramref name="chairUuid"/>. Mirrors
    /// <see cref="TeleportAsync"/>'s event-race: issues RequestSit, awaits
    /// the AvatarSitResponse for that object vs. a ~15s timeout, throws
    /// <see cref="SessionLostException"/> if the session drops mid-sit.
    /// Scripted sit targets seat the avatar region-wide, so being in the
    /// rectangle (guaranteed by the prior teleport) is sufficient.
    /// </summary>
    Task<SitResult> SitAsync(Guid chairUuid, CancellationToken ct);

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
