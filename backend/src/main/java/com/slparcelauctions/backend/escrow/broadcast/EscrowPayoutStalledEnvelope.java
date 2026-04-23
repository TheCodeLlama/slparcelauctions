package com.slparcelauctions.backend.escrow.broadcast;

import java.time.OffsetDateTime;

import com.slparcelauctions.backend.escrow.Escrow;
import com.slparcelauctions.backend.escrow.EscrowState;
import com.slparcelauctions.backend.escrow.command.TerminalCommand;

/**
 * WebSocket envelope broadcast when a {@link TerminalCommand} exhausts its
 * retry budget (4 attempts with 1m/5m/15m backoff per spec §7.4) and gets
 * flipped to {@code requires_manual_review = true}. No escrow state change
 * accompanies the stall — the escrow remains in whatever state triggered
 * the command (typically {@code TRANSFER_PENDING} for a payout). The admin
 * queue picks up the stall and decides the recovery path.
 *
 * <p>Clients surface the stall as an incident banner rather than a status
 * change; {@code attemptCount} + {@code lastError} give operators the quick
 * triage info without having to pop open the command's audit history.
 */
public record EscrowPayoutStalledEnvelope(
        String type,
        Long auctionId,
        Long escrowId,
        EscrowState state,
        Integer attemptCount,
        String lastError,
        OffsetDateTime serverTime) implements EscrowEnvelope {

    public static EscrowPayoutStalledEnvelope of(TerminalCommand cmd, Escrow escrow,
            OffsetDateTime serverTime) {
        return new EscrowPayoutStalledEnvelope(
                "ESCROW_PAYOUT_STALLED",
                escrow.getAuction().getId(),
                escrow.getId(),
                escrow.getState(),
                cmd.getAttemptCount(),
                cmd.getLastError(),
                serverTime);
    }
}
