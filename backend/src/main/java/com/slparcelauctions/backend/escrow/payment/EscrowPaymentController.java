package com.slparcelauctions.backend.escrow.payment;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.escrow.EscrowService;
import com.slparcelauctions.backend.escrow.payment.dto.EscrowPaymentRequest;
import com.slparcelauctions.backend.escrow.payment.dto.SlCallbackResponse;
import com.slparcelauctions.backend.sl.SlHeaderValidator;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * {@code POST /api/v1/sl/escrow/payment} — the in-world escrow terminal
 * posts here after receiving L$ from the auction winner. Spec §13.2.
 *
 * <p>Controller responsibilities are intentionally narrow: validate the
 * SL-grid-injected {@code X-SecondLife-Shard} and {@code X-SecondLife-Owner-Key}
 * headers via {@link SlHeaderValidator}, then delegate to
 * {@link EscrowService#acceptPayment(EscrowPaymentRequest)}. Every other
 * trust check — shared-secret match (constant-time), idempotency on
 * {@code slTransactionKey}, terminal-registered, escrow state, payment
 * deadline, payer UUID match, amount match — lives inside the service so
 * there is exactly one authoritative enforcement point. The dev-profile
 * {@code DevEscrowController} bypasses only the SL-header check; its
 * requests route through the same service method and therefore run through
 * the same body-level validation pipeline.
 *
 * <p>Header or shared-secret failures surface as 403 ProblemDetail shapes
 * through the {@code EscrowExceptionHandler}; every domain decision (OK,
 * REFUND variants, ERROR variants) returns 200 with an LSL-friendly
 * {@link SlCallbackResponse} body so the terminal branches on body content
 * rather than HTTP status.
 */
@RestController
@RequestMapping("/api/v1/sl/escrow")
@RequiredArgsConstructor
public class EscrowPaymentController {

    private final EscrowService escrowService;
    private final SlHeaderValidator headerValidator;

    @PostMapping("/payment")
    public SlCallbackResponse receivePayment(
            @RequestHeader(value = "X-SecondLife-Shard", required = false) String shard,
            @RequestHeader(value = "X-SecondLife-Owner-Key", required = false) String ownerKey,
            @Valid @RequestBody EscrowPaymentRequest req) {
        headerValidator.validate(shard, ownerKey);
        return escrowService.acceptPayment(req);
    }
}
