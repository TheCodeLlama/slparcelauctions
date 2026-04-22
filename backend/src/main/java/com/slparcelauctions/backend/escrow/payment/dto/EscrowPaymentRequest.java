package com.slparcelauctions.backend.escrow.payment.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Body carried on {@code POST /api/v1/sl/escrow/payment} from an in-world
 * escrow terminal to the backend. The LSL script pushes each field from the
 * {@code money()} event plus the operator-configured
 * {@code sharedSecret} / {@code terminalId}. All fields are required; the
 * backend treats a missing or blank value as a malformed request
 * ({@code 400}) rather than a domain REFUND — the terminal shouldn't need
 * to refund on a bug in its own script. See spec §13.2 for the request
 * shape and §7.5 for terminal identification.
 */
public record EscrowPaymentRequest(
        @NotNull Long auctionId,
        @NotBlank String payerUuid,
        @NotNull @Min(1) Long amount,
        @NotBlank String slTransactionKey,
        @NotBlank String terminalId,
        @NotBlank String sharedSecret) { }
