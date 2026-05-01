package com.slparcelauctions.backend.wallet.me;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;

import com.slparcelauctions.backend.wallet.UserLedgerEntryType;

/**
 * Query-string filter parameters for {@code GET /me/wallet/ledger} (and the
 * forthcoming CSV export). Bound via Spring's {@code @ModelAttribute} so the
 * controller signature stays compact and reusable across the JSON and CSV
 * endpoints; the over-the-wire contract is unchanged from listing the same
 * params individually with {@code @RequestParam}.
 *
 * <p>Component name {@code entryType} (singular) matches the repeated query
 * param shape: {@code ?entryType=DEPOSIT&entryType=BID_RESERVED}.
 *
 * <p>Conversion to {@link LedgerFilter} via {@link #toFilter()} keeps the
 * spec's pluralized internal name ({@code entryTypes}) intact for the
 * specification factory.
 */
public record LedgerFilterParams(
        List<UserLedgerEntryType> entryType,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
        Long amountMin,
        Long amountMax
) {
    public LedgerFilter toFilter() {
        return new LedgerFilter(entryType, from, to, amountMin, amountMax);
    }
}
