package com.slparcelauctions.backend.bot;

/**
 * Bot {@code VERIFY_BUY_OWNER} task classification result. The bot teleports
 * to the parcel and reports the live owner so the backend can run the same
 * confirm-transfer / consume-attempt / freeze decision matrix that the inline
 * {@code EscrowManualActionService.verifyTransfer} used before manual verify
 * switched to bot dispatch.
 *
 * <p>This is the <b>frozen bot wire contract</b> — the .NET bot mirrors these
 * names byte-for-byte. Do not rename or reorder values.
 *
 * <ul>
 *   <li>{@link #OWNER_IS_WINNER} — parcel owner is the auction winner. Confirm
 *       the transfer.</li>
 *   <li>{@link #OWNER_STILL_PRE_TRANSFER} — parcel owner is still the seller
 *       (or the realty group, for group-listed parcels). Definitive negative:
 *       consumes one of the requesting role's manual attempts.</li>
 *   <li>{@link #OWNER_IS_STRANGER} — parcel owner is some unknown third party.
 *       Triggers a {@code freezeForFraud(UNKNOWN_OWNER)}.</li>
 *   <li>{@link #PARCEL_DELETED} — the bot could not locate the parcel
 *       (returned/abandoned/region-down). Triggers
 *       {@code freezeForFraud(PARCEL_DELETED)}.</li>
 *   <li>{@link #WORLD_API_FAILURE} — the bot got an upstream/SL-side failure
 *       trying to observe the parcel. Transient: no attempt consumed, the
 *       pending flag is cleared so the user can retry.</li>
 *   <li>{@link #UNKNOWN_ERROR} — catch-all for unexpected bot errors. Same
 *       transient handling as {@code WORLD_API_FAILURE}.</li>
 * </ul>
 */
public enum BuyOwnerOutcome {
    OWNER_IS_WINNER,
    OWNER_STILL_PRE_TRANSFER,
    OWNER_IS_STRANGER,
    PARCEL_DELETED,
    WORLD_API_FAILURE,
    UNKNOWN_ERROR
}
