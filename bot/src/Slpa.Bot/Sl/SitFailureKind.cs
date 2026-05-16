namespace Slpa.Bot.Sl;

public enum SitFailureKind
{
    Timeout,
    /// <summary>Sim rejected the sit (object not sittable / no sit target / access denied).</summary>
    NotSittable,
    Other
}
