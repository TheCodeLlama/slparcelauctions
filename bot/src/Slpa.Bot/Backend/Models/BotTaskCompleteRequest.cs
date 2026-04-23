namespace Slpa.Bot.Backend.Models;

/// <summary>
/// Mirrors backend com.slparcelauctions.backend.bot.dto.BotTaskCompleteRequest.
/// Either SUCCESS (populates observations) or FAILURE (populates failureReason).
/// </summary>
public sealed record BotTaskCompleteRequest(
    string Result,
    Guid? AuthBuyerId,
    long? SalePrice,
    Guid? ParcelOwner,
    string? ParcelName,
    int? AreaSqm,
    string? RegionName,
    double? PositionX,
    double? PositionY,
    double? PositionZ,
    string? FailureReason);
