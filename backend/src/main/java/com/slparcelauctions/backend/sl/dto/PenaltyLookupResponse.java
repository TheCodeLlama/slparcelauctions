package com.slparcelauctions.backend.sl.dto;

/**
 * Body of a successful {@code POST /api/v1/sl/penalty-lookup} response.
 * Spec §7.5: returned only when an SLPA user with this avatar UUID has a
 * non-zero {@code penaltyBalanceOwed}; otherwise the controller returns
 * 404 (no debt found / unknown avatar) so the terminal can render a clean
 * "no penalty" message without branching on the body shape.
 */
public record PenaltyLookupResponse(
        Long userId,
        String displayName,
        Long penaltyBalanceOwed
) {}
