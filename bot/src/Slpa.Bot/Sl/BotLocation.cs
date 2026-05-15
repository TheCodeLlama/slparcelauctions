namespace Slpa.Bot.Sl;

/// <summary>
/// The bot's current region + (x, y) within that region. Z is irrelevant to
/// the idle-park rectangle test, so it is not carried.
/// </summary>
public sealed record BotLocation(string Region, double X, double Y);
