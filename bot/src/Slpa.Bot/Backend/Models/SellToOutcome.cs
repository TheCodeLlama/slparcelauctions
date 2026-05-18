namespace Slpa.Bot.Backend.Models;

/// <summary>
/// Bot <c>VERIFY_SELL_TO</c> task classification result (spec §5.2). This is
/// the <b>frozen bot wire contract</b> — these names mirror the backend
/// <c>SellToOutcome</c> Java enum byte-for-byte. Do not rename or reorder.
/// </summary>
public enum SellToOutcome
{
    SELL_TO_OK, OWNER_ALREADY_WINNER, SELL_TO_NOT_SET, WRONG_BUYER,
    PRICE_NOT_ZERO, ACCESS_DENIED, PARCEL_NOT_FOUND, BOT_ERROR
}
