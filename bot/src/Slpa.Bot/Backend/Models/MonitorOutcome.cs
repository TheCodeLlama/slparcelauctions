namespace Slpa.Bot.Backend.Models;

public enum MonitorOutcome
{
    ALL_GOOD,
    AUTH_BUYER_CHANGED,
    PRICE_MISMATCH,
    OWNER_CHANGED,
    ACCESS_DENIED,
    TRANSFER_COMPLETE,
    TRANSFER_READY,
    STILL_WAITING
}
