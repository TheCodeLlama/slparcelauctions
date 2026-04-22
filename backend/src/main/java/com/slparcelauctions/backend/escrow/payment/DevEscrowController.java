package com.slparcelauctions.backend.escrow.payment;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.escrow.EscrowService;
import com.slparcelauctions.backend.escrow.payment.dto.EscrowPaymentRequest;
import com.slparcelauctions.backend.escrow.payment.dto.SlCallbackResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Dev-only helper that simulates a terminal payment by delegating to
 * {@link EscrowService#acceptPayment(EscrowPaymentRequest)} without the SL
 * header gate. Useful for frontend smoke tests against a local backend
 * without a live in-world terminal.
 *
 * <p>Two-layer gating (same pattern as {@code DevAuctionEndController}):
 * <ol>
 *   <li>{@link Profile @Profile("dev")} — the bean does not exist in
 *       non-dev profiles, so the route 404s in prod.</li>
 *   <li>{@code SecurityConfig} already whitelists {@code /api/v1/dev/**};
 *       the profile gate is the real trust boundary.</li>
 * </ol>
 *
 * <p>The caller must still pass a valid {@code sharedSecret} in the body
 * and the {@code terminalId} must be a registered terminal — the service
 * enforces both regardless of the entry point.
 */
@RestController
@RequestMapping("/api/v1/dev/escrow")
@Profile("dev")
@RequiredArgsConstructor
public class DevEscrowController {

    private final EscrowService escrowService;

    @PostMapping("/{auctionId}/simulate-payment")
    public SlCallbackResponse simulatePayment(
            @PathVariable Long auctionId,
            @Valid @RequestBody EscrowPaymentRequest req) {
        // Overlay the path auctionId onto the request body so the URL and
        // payload can't disagree. Every other field is forwarded as-is.
        EscrowPaymentRequest effective = new EscrowPaymentRequest(
                auctionId, req.payerUuid(), req.amount(),
                req.slTransactionKey(), req.terminalId(), req.sharedSecret());
        return escrowService.acceptPayment(effective);
    }
}
