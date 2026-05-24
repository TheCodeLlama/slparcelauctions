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
        "b5c8a4c0-d0fb-1580-6fa8-36a6374d2ff4",
        "22bf826a-4417-c637-0817-88832307c8e2",
        "a24e89f1-ae46-95a4-a87f-e0e2a4809f74",
        "78978fa7-2c43-c2f2-5b1a-89bffadbafc7",
        "cabbcc98-e89b-bd4b-76d0-c0a0343205e4",
        "861de5a1-92ec-c9c3-124f-e2f74de342dd",
        "24584ae7-dfa1-2be0-4bfa-589d422d62e9",
        "828d2785-722c-a755-28bd-9b1460001117",
    };
}
