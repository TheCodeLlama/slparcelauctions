namespace Slpa.Bot.Backend.Models;

public sealed record BotMonitorResultRequest(
    MonitorOutcome Outcome,
    Guid? ObservedOwner,
    Guid? ObservedAuthBuyer,
    long? ObservedSalePrice,
    string? Note);
