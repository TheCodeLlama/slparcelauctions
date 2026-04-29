package com.slparcelauctions.backend.escrow.dto;

import com.slparcelauctions.backend.escrow.EscrowDisputeReasonCategory;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Payload for {@code POST /api/v1/auctions/{id}/escrow/dispute}. The
 * {@code reasonCategory} lands on {@code escrows.dispute_reason_category}
 * and drives operator tooling filters; the free-text {@code description}
 * is the caller's narrative, length-bounded so dispute rows don't grow
 * unbounded. {@code slTransactionKey} is required when
 * {@code reasonCategory == PAYMENT_NOT_CREDITED} and is validated at the
 * service layer. Spec §4.4.
 */
public record EscrowDisputeRequest(
        @NotNull EscrowDisputeReasonCategory reasonCategory,
        @NotNull @Size(min = 10, max = 2000) String description,
        @Size(max = 64) String slTransactionKey) { }
