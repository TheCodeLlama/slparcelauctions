package com.slparcelauctions.backend.auction.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/auctions/{publicId}/broker-cancel} — a
 * broker-initiated cancellation of a case-3 (SL-group-owned) listing. The
 * {@code reason} is snapshotted onto the {@code CancellationLog} row and fed
 * to the listing-agent notification body. See Realty Groups E spec §5.2.
 */
public record BrokerCancelRequest(
        @NotBlank @Size(max = 500) String reason
) {}
