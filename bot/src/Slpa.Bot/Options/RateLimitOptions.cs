namespace Slpa.Bot.Options;

public sealed class RateLimitOptions
{
    public const string SectionName = "RateLimit";

    public int TeleportsPerMinute { get; set; } = 6;
}
