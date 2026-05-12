package com.slparcelauctions.backend.realty.wallet.dto;

import java.util.List;

/**
 * Response DTO for GET /api/v1/realty/groups/{publicId}/wallet.
 * Spec §5.1 / §5.6.
 */
public record GroupWalletDto(
        long balance,
        long reserved,
        long available,
        List<GroupLedgerEntryDto> recentLedger) {
}
