namespace Slpa.Bot.Backend.Models;

/// <summary>
/// Bot <c>VERIFY_BUY_OWNER</c> result callback body. Posted to
/// <c>POST /api/v1/bot/tasks/{taskId}/verify-buy-owner-result</c>. This is the
/// <b>frozen bot wire contract</b> — field names mirror the backend
/// <c>BuyOwnerResultRequest</c> record. Do not rename fields.
/// </summary>
/// <param name="Outcome">Classification of the live owner observation.</param>
/// <param name="ObservedOwnerUuid">The parcel owner the bot observed
/// (best-effort — null for <see cref="BuyOwnerOutcome.PARCEL_DELETED"/> /
/// transient failures). Logged into the fraud-flag evidence for freeze
/// outcomes.</param>
/// <param name="ObservedOwnerType">"agent" or "group" (best-effort, null on
/// failure). Lets admins distinguish stranger from third-party group at a
/// glance in the fraud queue.</param>
public sealed record BuyOwnerResultRequest(
    BuyOwnerOutcome Outcome,
    Guid? ObservedOwnerUuid,
    string? ObservedOwnerType);
