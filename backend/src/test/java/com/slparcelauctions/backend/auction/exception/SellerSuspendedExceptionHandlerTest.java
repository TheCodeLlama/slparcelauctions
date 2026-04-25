package com.slparcelauctions.backend.auction.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Unit coverage for the {@code SellerSuspendedException} → 403 ProblemDetail
 * mapping in {@link AuctionExceptionHandler} (Epic 08 sub-spec 2 §7.7). The
 * handler must:
 * <ul>
 *   <li>Set status 403 (not 422) — the resource is forbidden, not malformed.</li>
 *   <li>Carry the {@link SuspensionReason} enum value as the {@code code}
 *       property so the frontend can branch on it.</li>
 *   <li>Pin the problem-type URI to the documented seller-suspended URL.</li>
 *   <li>Set distinct {@code detail} copy per reason.</li>
 * </ul>
 */
class SellerSuspendedExceptionHandlerTest {

    private final AuctionExceptionHandler handler = new AuctionExceptionHandler();

    @Test
    void handlesPenaltyOwed_returns403WithCodePenaltyOwed() {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/auctions");
        SellerSuspendedException ex = new SellerSuspendedException(SuspensionReason.PENALTY_OWED);

        ProblemDetail pd = handler.handleSellerSuspended(ex, req);

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(pd.getProperties()).containsEntry("code", "PENALTY_OWED");
        assertThat(pd.getTitle()).isEqualTo("Listing creation suspended");
        assertThat(pd.getType().toString())
                .isEqualTo("https://slpa.example/problems/seller-suspended");
        assertThat(pd.getDetail())
                .contains("outstanding penalty balance")
                .contains("SLPA terminal");
    }

    @Test
    void handlesTimedSuspension_returns403WithCodeTimed() {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/auctions");
        SellerSuspendedException ex = new SellerSuspendedException(SuspensionReason.TIMED_SUSPENSION);

        ProblemDetail pd = handler.handleSellerSuspended(ex, req);

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(pd.getProperties()).containsEntry("code", "TIMED_SUSPENSION");
        assertThat(pd.getDetail()).contains("temporarily suspended");
    }

    @Test
    void handlesPermanentBan_returns403WithCodeBan() {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/auctions");
        SellerSuspendedException ex = new SellerSuspendedException(SuspensionReason.PERMANENT_BAN);

        ProblemDetail pd = handler.handleSellerSuspended(ex, req);

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(pd.getProperties()).containsEntry("code", "PERMANENT_BAN");
        assertThat(pd.getDetail()).contains("permanently suspended");
    }
}
