package com.slparcelauctions.backend.sl.dto;

/**
 * Body of a {@code POST /api/v1/sl/penalty-lookup} response.
 *
 * <p>The endpoint always returns {@code 200}. The terminal branches on
 * {@code penaltyBalanceOwed}: {@code 0} means "no debt" (render the
 * clean "no penalty" message); non-zero means "show pay prompt for this
 * amount". The original spec returned 404 for the no-debt cases, but
 * the SL HTTP outbound layer rewrites 4xx responses whose Content-Type
 * is not in {@code HTTP_ACCEPT} (default {@code text/plain}) into a
 * synthetic 415 — and Spring serialises ProblemDetail as
 * {@code application/problem+json}, which the grid filter rejects. See
 * {@code PenaltyTerminalService.lookup} for the privacy rationale.
 *
 * <p>For the no-debt case (unknown avatar OR known user with zero
 * balance), {@code userId} and {@code displayName} are {@code null}
 * so the two cases are indistinguishable from the wire. Only the
 * non-zero-balance case populates them.
 */
public record PenaltyLookupResponse(
        Long userId,
        String displayName,
        Long penaltyBalanceOwed
) {}
