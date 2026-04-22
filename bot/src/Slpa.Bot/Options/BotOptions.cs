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
}
