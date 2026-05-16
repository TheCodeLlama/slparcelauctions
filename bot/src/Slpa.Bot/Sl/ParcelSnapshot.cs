namespace Slpa.Bot.Sl;

/// <summary>
/// Snapshot of ParcelProperties the bot can observe via
/// <see cref="IBotSession.ReadParcelAsync"/>. Retained as session
/// infrastructure for future task types — no current handler consumes it.
/// </summary>
public sealed record ParcelSnapshot(
    Guid OwnerId,
    Guid GroupId,
    bool IsGroupOwned,
    Guid AuthBuyerId,
    long SalePrice,
    string Name,
    string Description,
    int AreaSqm,
    int MaxPrims,
    int Category,
    Guid SnapshotId,
    uint Flags);
