package com.slparcelauctions.backend.realty.analytics.dto;

import java.util.UUID;

/**
 * Per-member row of the realty-group commission analytics view (spec §6.8, §15.2).
 *
 * <p>One row per current member of the group, including the leader's own row (the leader
 * may have earned commission rows from listings they personally agented). Empty groups
 * still return a row per member with zeroed totals.
 *
 * <p>Both totals reflect {@code AGENT_COMMISSION_CREDIT} {@code user_ledger} entries whose
 * {@code idempotency_key} ({@code AGCOMM-{auctionId}}) resolves to an auction whose
 * {@code realty_group_id} matches the group (case-1 legacy) or whose
 * {@code realty_group_sl_group_id} resolves to a SL-group registration belonging to the
 * group (case-3). Case-2 was removed by E; auctions with neither linkage are excluded.
 *
 * @param memberPublicId   {@link com.slparcelauctions.backend.user.User#publicId} of the
 *                         member; rendered into the frontend table as a stable identifier.
 * @param displayName      The member's current display name (snapshot at read time).
 * @param lifetimeLindens  Sum of all qualifying commission credits since the user joined.
 *                         Always {@code >= 0}.
 * @param last30DaysLindens Sum of qualifying commission credits whose {@code created_at}
 *                         is within the last 30 days of {@code now()} server-side. Always
 *                         {@code >= 0}.
 */
public record MemberCommissionRowDto(
    UUID memberPublicId,
    String displayName,
    long lifetimeLindens,
    long last30DaysLindens
) {}
