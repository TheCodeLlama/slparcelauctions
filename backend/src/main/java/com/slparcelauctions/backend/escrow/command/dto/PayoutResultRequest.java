package com.slparcelauctions.backend.escrow.command.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Body posted by an in-world terminal after executing a
 * {@link com.slparcelauctions.backend.escrow.command.TerminalCommand} to
 * report the result back to the backend. Flat fields so the LSL script
 * can emit each with {@code llJsonSetValue}.
 *
 * <p>The server looks the command up by {@code idempotencyKey} — the
 * terminal is not trusted to echo our synthetic DB id, and the
 * idempotency key is the value the terminal received on the outbound
 * POST. {@code success=true} plus {@code slTransactionKey} marks a
 * completed transfer; {@code success=false} carries an
 * {@code errorMessage} for operator telemetry and a retry decision.
 *
 * <p>{@code sharedSecret} is validated via the same
 * {@link com.slparcelauctions.backend.escrow.terminal.TerminalService#assertSharedSecret}
 * constant-time check the payment receiver uses, so an attacker with
 * SL-header-scraping cannot forge a payout result.
 */
public record PayoutResultRequest(
        @NotBlank String idempotencyKey,
        boolean success,
        String slTransactionKey,
        String errorMessage,
        @NotBlank String terminalId,
        @NotBlank String sharedSecret) { }
