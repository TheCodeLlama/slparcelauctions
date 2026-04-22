package com.slparcelauctions.backend.escrow.dto;

import java.time.OffsetDateTime;

/**
 * One row in the timeline view rendered on the dashboard escrow detail
 * page. The {@code kind} discriminator is {@code STATE_TRANSITION} for
 * escrow state-column stamps ({@code createdAt}, {@code fundedAt}, etc.)
 * and {@code LEDGER_*} for {@link com.slparcelauctions.backend.escrow.EscrowTransaction}
 * ledger rows. {@code amount} is non-null only for {@code LEDGER_*}
 * entries and for the funding / payout state stamps whose L$ value is
 * unambiguous from the escrow row itself.
 */
public record EscrowTimelineEntry(
        String kind,
        String label,
        OffsetDateTime at,
        Long amount,
        String details) { }
