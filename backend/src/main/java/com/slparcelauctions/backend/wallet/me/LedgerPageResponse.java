package com.slparcelauctions.backend.wallet.me;

import java.util.List;

import com.slparcelauctions.backend.wallet.me.WalletViewResponse.LedgerEntryDto;

/**
 * GET /api/v1/me/wallet/ledger response. Pagination envelope around the
 * filtered ledger entries; reuses {@link LedgerEntryDto} from
 * {@link WalletViewResponse} for the row shape so the frontend has a single
 * row schema across the wallet panel and ledger page.
 */
public record LedgerPageResponse(
        int page,
        int size,
        long totalElements,
        int totalPages,
        List<LedgerEntryDto> entries
) {}
