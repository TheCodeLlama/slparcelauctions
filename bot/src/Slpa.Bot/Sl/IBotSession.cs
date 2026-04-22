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
}
