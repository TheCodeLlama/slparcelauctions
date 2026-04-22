package com.slparcelauctions.backend.escrow.payment;

/**
 * Machine-readable reason code carried on the
 * {@link com.slparcelauctions.backend.escrow.payment.dto.SlCallbackResponse}
 * returned to in-world escrow terminals from
 * {@code POST /api/v1/sl/escrow/payment}. Grouped into two semantic tiers:
 *
 * <ul>
 *   <li><b>REFUND variants</b> — the L$ did land in the terminal but the
 *       payment is not acceptable (wrong payer, wrong amount, expired,
 *       already funded). The terminal MUST execute {@code llGiveMoney} to
 *       push the L$ back to the payer.</li>
 *   <li><b>ERROR variants</b> — the request failed before any money logic
 *       ran (no auction, unknown terminal, secret/header mismatch, or a
 *       replay that previously errored). The terminal does NOT refund on
 *       these; they typically indicate a misconfigured terminal or a tamper
 *       attempt, and the L$ either didn't transfer or is being handled by
 *       another flow.</li>
 * </ul>
 *
 * <p>LSL scripts route on the parent {@code status} first
 * ({@code OK} / {@code REFUND} / {@code ERROR}), then on {@code reason} for
 * telemetry and operator messaging. See spec §13.2.
 */
public enum EscrowCallbackResponseReason {
    // REFUND variants — terminal MUST refund the L$
    WRONG_PAYER,
    WRONG_AMOUNT,
    ESCROW_EXPIRED,
    ALREADY_FUNDED,
    // ERROR variants — terminal does NOT refund
    UNKNOWN_AUCTION,
    UNKNOWN_TERMINAL,
    SECRET_MISMATCH,
    BAD_HEADERS,
    ALREADY_PAID
}
