namespace Slpa.Bot.Backend.Models;

/// <summary>
/// Bot <c>VERIFY_BUY_OWNER</c> task classification result. Mirrors the backend
/// <c>BuyOwnerOutcome</c> Java enum byte-for-byte — this is the <b>frozen bot
/// wire contract</b>. Do not rename or reorder values.
///
/// <list type="bullet">
///   <item><see cref="OWNER_IS_WINNER"/> — parcel owner is the auction winner.
///     Backend confirms the transfer.</item>
///   <item><see cref="OWNER_STILL_PRE_TRANSFER"/> — parcel owner is still the
///     seller (or the registered SL group for case-3 listings). Definitive
///     negative: consumes one of the requesting role's manual attempts.</item>
///   <item><see cref="OWNER_IS_STRANGER"/> — parcel owner is some unknown third
///     party. Triggers a fraud freeze (UNKNOWN_OWNER).</item>
///   <item><see cref="PARCEL_DELETED"/> — the bot could not locate the parcel
///     (returned / abandoned / region down). Triggers a fraud freeze
///     (PARCEL_DELETED).</item>
///   <item><see cref="WORLD_API_FAILURE"/> — transient SL-side / observation
///     failure. No attempt consumed; the manual-verify pending flag is cleared
///     so the user can retry.</item>
///   <item><see cref="UNKNOWN_ERROR"/> — catch-all for unexpected bot errors.
///     Same transient handling as <see cref="WORLD_API_FAILURE"/>.</item>
/// </list>
/// </summary>
public enum BuyOwnerOutcome
{
    OWNER_IS_WINNER,
    OWNER_STILL_PRE_TRANSFER,
    OWNER_IS_STRANGER,
    PARCEL_DELETED,
    WORLD_API_FAILURE,
    UNKNOWN_ERROR
}
