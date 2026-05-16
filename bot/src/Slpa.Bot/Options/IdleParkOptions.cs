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

    /// <summary>
    /// Object UUIDs of chairs inside the rectangle (region = <see cref="Region"/>).
    /// When idle the bot sits on one at random. Empty ⇒ rectangle-only
    /// behavior (no sitting). Unparseable entries are skipped + warned once.
    /// </summary>
    public List<string> Chairs { get; set; } = new()
    {
        "d28b2fea-8020-b875-777b-6e432a7d9317",
        "65f7f3e4-1a06-0a07-9233-a3f9a44ff88c",
        "273a9a21-9a23-ca63-58e0-fe817f0a524a",
        "02080632-9fcc-1e1f-36b3-8dd54a694f12",
        "6a8106b7-d771-4c5c-ee19-62b4291de07a",
        "0c852666-669a-9670-e663-380e18d748b7",
        "cd2dbb84-8b18-f28e-c19c-f40468036fc6",
        "ca2c885f-d3fd-2368-1ea7-4c57e014ea5a",
    };
}
