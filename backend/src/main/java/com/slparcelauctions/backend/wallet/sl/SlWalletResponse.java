package com.slparcelauctions.backend.wallet.sl;

/**
 * LSL-parsable response shape for SL-headers-gated wallet endpoints.
 *
 * <p>{@code status} branching:
 * <ul>
 *   <li>{@code OK} — operation completed; L$ flowed as expected.</li>
 *   <li>{@code REFUND} — the deposit endpoint received valid L$ but
 *       cannot honor it (unknown payer, banned user). The LSL script MUST
 *       call {@code llTransferLindenDollars} to bounce the L$ to the payer.</li>
 *   <li>{@code REFUND_BLOCKED} — the withdraw-request endpoint declined
 *       the request (insufficient balance, frozen user). The LSL script
 *       does NOT call {@code llTransferLindenDollars} — no L$ was paid in
 *       this flow, nothing to bounce.</li>
 *   <li>{@code ERROR} — request failed validation before any L$ logic
 *       (bad headers, secret mismatch, unknown terminal). LSL does NOT
 *       refund — could be an attacker probing.</li>
 * </ul>
 *
 * <p>See spec docs/superpowers/specs/2026-04-30-wallet-model-design.md §4.1.
 */
public record SlWalletResponse(
        String status,
        String reason,
        String message,
        Long queueId
) {
    public static SlWalletResponse ok() {
        return new SlWalletResponse("OK", null, null, null);
    }

    public static SlWalletResponse okWithQueue(long queueId) {
        return new SlWalletResponse("OK", null, null, queueId);
    }

    public static SlWalletResponse refund(SlWalletResponseReason reason, String message) {
        return new SlWalletResponse("REFUND", reason.name(), message, null);
    }

    public static SlWalletResponse refundBlocked(SlWalletResponseReason reason, String message) {
        return new SlWalletResponse("REFUND_BLOCKED", reason.name(), message, null);
    }

    public static SlWalletResponse error(SlWalletResponseReason reason, String message) {
        return new SlWalletResponse("ERROR", reason.name(), message, null);
    }
}
