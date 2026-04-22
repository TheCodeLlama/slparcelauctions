package com.slparcelauctions.backend.escrow.command;

/**
 * JSON body POSTed to a terminal's {@code http-in} URL (spec §7.3). Flat
 * fields so the in-world LSL script can pull each value with
 * {@code llJsonGetValue} without nested descent. {@code action} and
 * {@code purpose} are serialised as the enum {@code name()} strings. The
 * {@code sharedSecret} is echoed on the request so the terminal can reject
 * requests not signed with the configured server-side secret, matching the
 * pattern used on inbound SL callbacks.
 */
public record TerminalCommandBody(
        String action,
        String purpose,
        String recipientUuid,
        long amount,
        Long escrowId,
        Long listingFeeRefundId,
        String idempotencyKey,
        String sharedSecret) {
}
