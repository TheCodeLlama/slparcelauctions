package com.slparcelauctions.backend.escrow.payment;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.escrow.payment.dto.ListingFeePaymentRequest;
import com.slparcelauctions.backend.escrow.payment.dto.SlCallbackResponse;
import com.slparcelauctions.backend.sl.SlHeaderValidator;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * {@code POST /api/v1/sl/listing-fee/payment} — production endpoint the
 * in-world listing-fee terminal POSTs to after a seller pays the DRAFT
 * listing fee. Spec §10.3.
 *
 * <p>Controller responsibilities mirror
 * {@link EscrowPaymentController}: validate the SL-grid-injected
 * {@code X-SecondLife-Shard} and {@code X-SecondLife-Owner-Key} headers
 * via {@link SlHeaderValidator}, then delegate to
 * {@link ListingFeePaymentService#acceptPayment(ListingFeePaymentRequest)}.
 * Every other trust check (shared secret, idempotency, terminal registered,
 * auction state, payer match, amount match) lives inside the service so
 * the enforcement point is singular.
 *
 * <p>The dev-profile {@code POST /api/v1/dev/auctions/{id}/pay} remains
 * live for browser-driven testing; production SL traffic uses this
 * endpoint. Header or shared-secret failures surface as 403 ProblemDetail
 * via {@code EscrowExceptionHandler}; every domain decision (OK, REFUND
 * variants, ERROR variants) returns 200 with a flat
 * {@link SlCallbackResponse} body for LSL-friendly parsing.
 */
@RestController
@RequestMapping("/api/v1/sl/listing-fee")
@RequiredArgsConstructor
public class ListingFeePaymentController {

    private final ListingFeePaymentService listingFeePaymentService;
    private final SlHeaderValidator headerValidator;

    @PostMapping("/payment")
    public SlCallbackResponse receivePayment(
            @RequestHeader(value = "X-SecondLife-Shard", required = false) String shard,
            @RequestHeader(value = "X-SecondLife-Owner-Key", required = false) String ownerKey,
            @Valid @RequestBody ListingFeePaymentRequest req) {
        headerValidator.validate(shard, ownerKey);
        return listingFeePaymentService.acceptPayment(req);
    }
}
