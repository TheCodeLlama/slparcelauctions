namespace Slpa.Bot.Options;

public sealed class IdleParkOptions
{
    public const string SectionName = "IdlePark";

    /// <summary>Master switch; false disables without losing coords.</summary>
    public bool Enabled { get; set; } = true;

    /// <summary>Target region; blank while enabled disables (with a warning).</summary>
    public string Region { get; set; } = "Hadron";

    public double Corner1X { get; set; } = 44;
    public double Corner1Y { get; set; } = 73;
    public double Corner2X { get; set; } = 30;
    public double Corner2Y { get; set; } = 65;

    /// <summary>Landing altitude.</summary>
    public double Z { get; set; } = 25;

    /// <summary>
    /// Minimum interval between park teleport attempts; also the failed-park
    /// backoff. Default 3 minutes.
    /// </summary>
    public int ParkCooldownSeconds { get; set; } = 180;
}
