package com.slparcelauctions.backend.sl;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.sl.dto.PenaltyLookupRequest;
import com.slparcelauctions.backend.sl.dto.PenaltyLookupResponse;
import com.slparcelauctions.backend.sl.dto.PenaltyPaymentRequest;
import com.slparcelauctions.backend.sl.dto.PenaltyPaymentResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * In-world LSL terminal endpoints for cancellation-penalty payment
 * (Epic 08 sub-spec 2 §7.5 / §7.6). Mirrors the trust model of
 * {@link SlVerificationController} and the escrow terminal endpoints:
 * the paths are {@code permitAll} at the Spring Security layer and the
 * actual security boundary is {@link SlHeaderValidator} validating the
 * SL-grid-injected {@code X-SecondLife-Shard} +
 * {@code X-SecondLife-Owner-Key} headers inside the handler. LSL
 * scripts cannot present a JWT (avatars have no web session), so this
 * is the only viable trust gate for terminal traffic.
 *
 * <p>{@code /penalty-lookup} is a read-only debt query —
 * {@link PenaltyTerminalService#lookup} returns the outstanding balance
 * or 404 if the avatar is unknown / owes nothing. {@code /penalty-payment}
 * applies a partial-or-full payment with idempotency on
 * {@code slTransactionId}; benign retries return the current balance,
 * overpayment beyond the outstanding balance returns 422.
 */
@RestController
@RequestMapping("/api/v1/sl")
@RequiredArgsConstructor
public class PenaltyTerminalController {

    private final PenaltyTerminalService service;
    private final SlHeaderValidator headerValidator;

    @PostMapping("/penalty-lookup")
    public PenaltyLookupResponse lookup(
            @RequestHeader(value = "X-SecondLife-Shard", required = false) String shard,
            @RequestHeader(value = "X-SecondLife-Owner-Key", required = false) String ownerKey,
            @Valid @RequestBody PenaltyLookupRequest req) {
        headerValidator.validate(shard, ownerKey);
        return service.lookup(req.slAvatarUuid());
    }

    @PostMapping("/penalty-payment")
    public PenaltyPaymentResponse pay(
            @RequestHeader(value = "X-SecondLife-Shard", required = false) String shard,
            @RequestHeader(value = "X-SecondLife-Owner-Key", required = false) String ownerKey,
            @Valid @RequestBody PenaltyPaymentRequest req) {
        headerValidator.validate(shard, ownerKey);
        return service.pay(req);
    }
}
