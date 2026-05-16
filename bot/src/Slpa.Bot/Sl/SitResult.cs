namespace Slpa.Bot.Sl;

public sealed record SitResult(bool Success, SitFailureKind? Failure)
{
    public static SitResult Ok() => new(true, null);
    public static SitResult Fail(SitFailureKind kind) => new(false, kind);
}
