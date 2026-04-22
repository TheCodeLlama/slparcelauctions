using Slpa.Bot.Backend.Models;

namespace Slpa.Bot.Backend;

public interface IBackendClient
{
    /// <summary>
    /// Claim the next due task. Returns null on 204 (empty queue). Hard-fails
    /// on 401 (misconfigured secret) by throwing <see cref="AuthConfigException"/>.
    /// </summary>
    Task<BotTaskResponse?> ClaimAsync(Guid botUuid, CancellationToken ct);

    /// <summary>Report VERIFY outcome. Task becomes COMPLETED / FAILED.</summary>
    Task CompleteVerifyAsync(long taskId, BotTaskCompleteRequest body, CancellationToken ct);

    /// <summary>Report MONITOR_* outcome. Backend re-arms or cancels the row.</summary>
    Task PostMonitorAsync(long taskId, BotMonitorResultRequest body, CancellationToken ct);
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
