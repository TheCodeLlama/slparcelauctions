namespace Slpa.Bot.Sl;

/// <summary>
/// Snapshot of ParcelProperties the bot can observe via
/// <see cref="IBotSession.ReadParcelAsync"/>. Consumed by
/// <see cref="Slpa.Bot.Tasks.VerifySellToHandler"/> to verify the seller's
/// "Sell To" configuration; also available to future task types.
/// </summary>
public sealed record ParcelSnapshot(
    Guid OwnerId,
    Guid GroupId,
    bool IsGroupOwned,
    Guid AuthBuyerId,
    long SalePrice,
    bool ForSale,
    string Name,
    string Description,
    int AreaSqm,
    int MaxPrims,
    int Category,
    Guid SnapshotId,
    uint Flags);
