namespace Slpa.Bot.Sl;

public sealed record TeleportResult(bool Success, TeleportFailureKind? Failure)
{
    public static TeleportResult Ok() => new(true, null);
    public static TeleportResult Fail(TeleportFailureKind kind) => new(false, kind);
}
