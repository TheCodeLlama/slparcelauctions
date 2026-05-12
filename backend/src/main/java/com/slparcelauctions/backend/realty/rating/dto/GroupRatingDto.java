package com.slparcelauctions.backend.realty.rating.dto;

/**
 * Aggregated star rating for a realty group, derived from {@code reviews}
 * rows that join to auctions linked to the group either directly (case-1
 * via {@code auctions.realty_group_id}) or through the SL-group registry
 * (case-3 via {@code auctions.realty_group_sl_group_id} -&gt;
 * {@code realty_group_sl_groups.realty_group_id}).
 *
 * <p>{@code averageRating} is {@code null} when no reviews exist — the
 * SQL {@code AVG(...)} over an empty result set is NULL, and the
 * frontend renders the "No reviews yet" empty state from that signal
 * rather than from a 0.0 placeholder which would be visually
 * indistinguishable from a one-star group.
 *
 * <p>Spec: {@code docs/superpowers/specs/2026-05-12-realty-groups-admin-moderation-design.md} §16.
 */
public record GroupRatingDto(Double averageRating, long reviewCount) {}
