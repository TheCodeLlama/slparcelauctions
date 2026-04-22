package com.slparcelauctions.backend.escrow.payment.dto;

import com.slparcelauctions.backend.escrow.payment.EscrowCallbackResponseReason;

/**
 * LSL-friendly response body for SL-callback endpoints. Kept intentionally
 * flat (three top-level string/enum fields) so the in-world script can
 * {@code llJsonGetValue} each field without nested descent. The
 * {@code status} string is the primary branching discriminator
 * ({@code OK} / {@code REFUND} / {@code ERROR}); {@code reason} is a
 * machine-readable enum used for telemetry and operator messaging;
 * {@code message} is a human-friendly one-liner the terminal can display or
 * log. See spec §13.2.
 *
 * <p>{@code reason} and {@code message} are {@code null} on the success
 * case ({@code status="OK"}) so the LSL side can treat any non-null value
 * as a failure signal.
 */
public record SlCallbackResponse(String status, EscrowCallbackResponseReason reason, String message) {
    public static SlCallbackResponse ok() {
        return new SlCallbackResponse("OK", null, null);
    }
    public static SlCallbackResponse refund(EscrowCallbackResponseReason reason, String msg) {
        return new SlCallbackResponse("REFUND", reason, msg);
    }
    public static SlCallbackResponse error(EscrowCallbackResponseReason reason, String msg) {
        return new SlCallbackResponse("ERROR", reason, msg);
    }
}
