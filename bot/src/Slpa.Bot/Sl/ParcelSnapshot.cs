namespace Slpa.Bot.Sl;

/// <summary>
/// Snapshot of ParcelProperties the bot can observe. Mirrors the backend's
/// BotTaskCompleteRequest shape — the worker passes this straight through
/// to the VERIFY callback.
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
