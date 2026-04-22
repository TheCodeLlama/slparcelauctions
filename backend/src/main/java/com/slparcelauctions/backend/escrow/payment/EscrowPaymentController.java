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
 * posts here after receiving L$ from the auction winner. Trust pipeline:
 * SL-injected headers (validated by {@link SlHeaderValidator}) → body-carried
 * {@code sharedSecret} (re-validated inside {@link EscrowService#acceptPayment}
 * via {@code TerminalService.assertSharedSecret}) → domain checks. Header
 * and secret failures surface as 403 ProblemDetail shapes through the
 * {@code EscrowExceptionHandler}; every domain decision (OK, REFUND
 * variants, ERROR variants) comes back 200 with an LSL-friendly
 * {@link SlCallbackResponse} body so the terminal branches on body content
 * rather than HTTP status. See spec §13.2.
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
