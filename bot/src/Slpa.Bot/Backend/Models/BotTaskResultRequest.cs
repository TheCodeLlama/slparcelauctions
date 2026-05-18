namespace Slpa.Bot.Backend.Models;

/// <summary>
/// Bot <c>VERIFY_SELL_TO</c> result callback body (spec §5.1). Posted to
/// <c>POST /api/v1/bot/tasks/{taskId}/result</c>. This is the <b>frozen bot
/// wire contract</b> — field names/order mirror the backend
/// <c>BotTaskResultRequest</c> record. Do not rename fields.
/// </summary>
public sealed record BotTaskResultRequest(
    SellToOutcome Outcome,
    Guid? ObservedOwnerUuid,
    Guid? ObservedAuthBuyerUuid,
    long? ObservedSalePrice,
    bool? ObservedForSale);
