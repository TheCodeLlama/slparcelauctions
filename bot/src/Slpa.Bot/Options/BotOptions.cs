namespace Slpa.Bot.Options;

public sealed class BotOptions
{
    public const string SectionName = "Bot";

    /// <summary>"Firstname Lastname" — the SL login form.</summary>
    public string Username { get; set; } = "";

    public string Password { get; set; } = "";

    /// <summary>The bot's SL avatar UUID — included in claim requests.</summary>
    public Guid BotUuid { get; set; }

    /// <summary>"last", "home", or a region name; see LibreMetaverse docs.</summary>
    public string StartLocation { get; set; } = "last";

    /// <summary>
    /// Task-loop backoff applied when the SL session is not Online (login
    /// failed or reconnecting). Also reused for a failed task claim.
    /// </summary>
    public int OfflineBackoffSeconds { get; set; } = 5;

    /// <summary>
    /// Task-loop backoff applied when the backend returns no task — the
    /// bot's poll cadence for new work.
    /// </summary>
    public int EmptyQueueBackoffSeconds { get; set; } = 15;

    /// <summary>
    /// Request timeout for the backend HTTP client. Default 30 s.
    /// </summary>
    public int HttpTimeoutSeconds { get; set; } = 30;

    /// <summary>
    /// Exponential retry backoff ladder (seconds) for transient backend
    /// failures (5xx + network errors). The retry loop indexes this array
    /// by attempt number, clamping to the last element; the array length
    /// also caps the total attempt count. An empty array falls back to a
    /// single-element <c>[1]</c> default.
    /// </summary>
    public int[] HttpRetryBackoffSeconds { get; set; } = { 1, 2, 4, 8, 15 };

    /// <summary>
    /// SL session reconnection backoff ladder (seconds). The login loop
    /// indexes this by consecutive-failure count, clamping to the last
    /// element. An empty array falls back to a single-element <c>[1]</c>
    /// default.
    /// </summary>
    public int[] ReconnectBackoffSeconds { get; set; } = { 1, 2, 4, 8, 15, 30, 60 };
}
