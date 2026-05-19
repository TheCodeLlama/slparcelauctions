namespace Slpa.Bot.Backend.Models;

public sealed record BotTaskResponse(
    long Id,
    BotTaskType TaskType,
    BotTaskStatus Status,
    long AuctionId,
    long? EscrowId,
    Guid ParcelUuid,
    string? RegionName,
    double? PositionX,
    double? PositionY,
    double? PositionZ,
    long SentinelPrice,
    Guid? AssignedBotUuid,
    string? FailureReason,
    DateTimeOffset? NextRunAt,
    int? RecurrenceIntervalSeconds,
    DateTimeOffset CreatedAt,
    DateTimeOffset? CompletedAt,
    Guid? RecipientUuid = null,
    long? AmountL = null,
    Guid? ExpectedWinnerUuid = null,
    // VERIFY_BUY_OWNER classification context (2026-05-19):
    //  - ExpectedPreTransferUuid: seller's avatar UUID (case-1) or registered
    //    SL group UUID (case-3). Owner observation matching this UUID +
    //    ExpectedOwnerType => OWNER_STILL_PRE_TRANSFER.
    //  - ExpectedOwnerType: "agent" for case-1 / "group" for case-3. The bot
    //    requires both UUID and ownerType to match before reporting
    //    PRE_TRANSFER; otherwise the residual case is OWNER_IS_STRANGER.
    Guid? ExpectedPreTransferUuid = null,
    string? ExpectedOwnerType = null);
