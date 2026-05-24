using Slpa.Bot.Backend.Models;

namespace Slpa.Bot.Backend;

public interface IBackendClient
{
    /// <summary>
    /// Claim the next due task. Returns null on 204 (empty queue). Hard-fails
    /// on 401 (misconfigured secret) by throwing <see cref="AuthConfigException"/>.
    /// </summary>
    Task<BotTaskResponse?> ClaimAsync(Guid botUuid, CancellationToken ct);

    /// <summary>
    /// Best-effort heartbeat (blocking: awaits the shared bearer auth + 5xx
    /// retry ladder). Throws <see cref="AuthConfigException"/> on 401 (the
    /// caller swallows it — the claim path is the authoritative 401 handler).
    /// </summary>
    Task SendHeartbeatAsync(BotHeartbeatRequest body, CancellationToken ct);

    /// <summary>
    /// Posts a <c>VERIFY_SELL_TO</c> classification back to the backend
    /// (<c>POST /api/v1/bot/tasks/{taskId}/result</c>, 204 on success).
    /// Shares the bearer auth + 5xx retry ladder. Throws
    /// <see cref="AuthConfigException"/> on 401.
    /// </summary>
    Task ReportTaskResultAsync(long taskId, BotTaskResultRequest body, CancellationToken ct);

    /// <summary>
    /// Posts a <c>VERIFY_BUY_OWNER</c> classification back to the backend
    /// (<c>POST /api/v1/bot/tasks/{taskId}/verify-buy-owner-result</c>, 204 on
    /// success). Shares the bearer auth + 5xx retry ladder. Throws
    /// <see cref="AuthConfigException"/> on 401.
    /// </summary>
    Task ReportBuyOwnerResultAsync(long taskId, BuyOwnerResultRequest body, CancellationToken ct);

    /// <summary>
    /// Posts a parcel scan result to the backend
    /// (<c>POST /api/v1/bot/tasks/{taskId}/scan-result</c>). Returns the raw
    /// <see cref="HttpResponseMessage"/> so the caller can inspect the status
    /// code (200 = recorded, 409 = already recorded, 4xx = rejected). The
    /// caller owns disposal. Shares the bearer auth + 5xx retry ladder.
    /// Throws <see cref="AuthConfigException"/> on 401.
    /// </summary>
    Task<HttpResponseMessage> PostScanResultAsync(
        long taskId, ScanResultRequest body, CancellationToken ct);
}

/// <summary>
/// Thrown when the backend returns 401 Unauthorized. Recovery requires
/// operator intervention (rotate the shared secret); retrying is pointless.
/// The task loop catches this + terminates the process so supervisord /
/// compose restarts it (which gives a human a chance to notice log noise).
/// </summary>
public sealed class AuthConfigException : Exception
{
    public AuthConfigException(string message) : base(message) {}
}
