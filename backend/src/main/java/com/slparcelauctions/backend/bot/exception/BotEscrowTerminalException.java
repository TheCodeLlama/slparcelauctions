package com.slparcelauctions.backend.bot.exception;

import com.slparcelauctions.backend.escrow.EscrowState;

import lombok.Getter;

@Getter
public class BotEscrowTerminalException extends RuntimeException {
    private final Long escrowId;
    private final EscrowState state;

    public BotEscrowTerminalException(Long escrowId, EscrowState state) {
        super("Escrow " + escrowId + " is in terminal state " + state
                + "; cannot apply monitor result");
        this.escrowId = escrowId;
        this.state = state;
    }
}
