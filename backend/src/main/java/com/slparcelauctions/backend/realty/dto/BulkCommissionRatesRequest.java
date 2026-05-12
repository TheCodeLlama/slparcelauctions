package com.slparcelauctions.backend.realty.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * Atomic-batch payload for {@code PATCH /api/v1/realty-groups/{publicId}/members/commission-rates}.
 *
 * <p>Every entry's {@code rate} replaces that member's stored {@code agent_commission_rate}.
 * The batch is applied inside a single transaction — any failure (member not in the group,
 * negative rate) rolls back the whole batch so the caller can re-submit with corrections
 * rather than reasoning about partial state. Spec §6.7, §15.1.
 */
public record BulkCommissionRatesRequest(
    @NotEmpty @Valid List<MemberRate> memberRates
) {
    public record MemberRate(
        @NotNull UUID memberPublicId,
        @NotNull @DecimalMin("0.0") BigDecimal rate
    ) {}
}
