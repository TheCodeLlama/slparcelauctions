package com.slparcelauctions.backend.escrow.payment.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Body carried on {@code POST /api/v1/sl/listing-fee/payment} from the
 * in-world listing-fee terminal to the backend when a seller pays the
 * DRAFT-stage listing fee. Production replacement for the dev-only
 * {@code POST /api/v1/dev/auctions/{id}/pay} helper — the dev endpoint
 * stays live for browser-driven testing but the real SL traffic pivots
 * here once the listing-fee terminal is deployed.
 *
 * <p>Shape mirrors {@link EscrowPaymentRequest} deliberately: the LSL
 * script uses the same {@code money()} event + shared-secret fields so
 * both terminal types can share validation helpers on the in-world side.
 * All fields are required; the backend treats a missing or blank value as
 * a malformed request ({@code 400}) rather than a domain REFUND — the
 * terminal shouldn't need to refund on a bug in its own script.
 */
public record ListingFeePaymentRequest(
        @NotNull Long auctionId,
        @NotBlank String payerUuid,
        @NotNull @Min(1) Long amount,
        @NotBlank String slTransactionKey,
        @NotBlank String terminalId,
        @NotBlank String sharedSecret) { }
