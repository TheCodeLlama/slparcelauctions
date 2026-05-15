namespace Slpa.Bot.Options;

public sealed class HeartbeatOptions
{
    public const string SectionName = "Heartbeat";

    /// <summary>
    /// Send cadence. Backend TTL is 180s, so keep this well under 60s to
    /// guarantee 3 beats per TTL window.
    /// </summary>
    public int IntervalSeconds { get; set; } = 60;
}
