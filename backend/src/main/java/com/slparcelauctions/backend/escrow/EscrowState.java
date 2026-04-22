package com.slparcelauctions.backend.escrow;

/**
 * Per-auction escrow lifecycle states (spec §4.1). {@code FUNDED} is a
 * transient internal state — in current code it always atomically advances
 * to {@code TRANSFER_PENDING} in the same transaction, so external observers
 * see the flip directly from {@code ESCROW_PENDING} to {@code TRANSFER_PENDING}.
 * Terminal states: {@code COMPLETED}, {@code EXPIRED}, {@code DISPUTED},
 * {@code FROZEN}. No resume paths in sub-spec 1.
 */
public enum EscrowState {
    ESCROW_PENDING,
    FUNDED,
    TRANSFER_PENDING,
    COMPLETED,
    DISPUTED,
    EXPIRED,
    FROZEN;

    /**
     * Terminal states never transition to another state. Bot monitor
     * callbacks on a terminal escrow row are rejected with
     * {@link com.slparcelauctions.backend.bot.exception.BotEscrowTerminalException}
     * (spec §6.2).
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == EXPIRED
                || this == DISPUTED || this == FROZEN;
    }
}
