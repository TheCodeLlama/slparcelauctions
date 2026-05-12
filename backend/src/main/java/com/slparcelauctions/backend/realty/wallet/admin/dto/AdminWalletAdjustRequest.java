package com.slparcelauctions.backend.realty.wallet.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Sub-project G section 7.2 -- admin realty-group wallet adjustment request body.
 * {@code amount} is signed: positive credits, negative debits. Zero is
 * rejected at the service layer ({@code IllegalArgumentException} -> 400) rather
 * than via bean-validation so the rule lives next to the rest of the wallet
 * invariant set.
 *
 * <p>{@code reason} is required and capped at 500 chars; both rules are enforced
 * here as bean-validation constraints so the wire-level rejection surfaces as a
 * structured 400 before the call ever reaches the service layer.
 */
public record AdminWalletAdjustRequest(
        long amount,
        @NotBlank @Size(max = 500) String reason) {
}
