package com.slparcelauctions.backend.auction.mybids;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.auth.AuthPrincipal;

import lombok.RequiredArgsConstructor;

/**
 * {@code GET /api/v1/users/me/bids} — the bidder dashboard's sole backend
 * surface. Returns a paginated list of {@link MyBidSummary} rows, one per
 * auction the caller has bid on, with a derived {@link MyBidStatus} per spec
 * §10.
 *
 * <p>Authentication is enforced by the {@code /api/v1/**} catch-all in
 * {@code SecurityConfig}; no {@code @PreAuthorize} needed here. If a
 * non-authenticated request reaches this controller it is a
 * {@code SecurityConfig} misconfiguration — {@link AuthPrincipal} will be
 * {@code null} and the read will NPE. That's the project's standard failure
 * mode for every {@code @AuthenticationPrincipal}-using controller.
 */
@RestController
@RequestMapping("/api/v1/users/me/bids")
@RequiredArgsConstructor
public class MyBidsController {

    private final MyBidsService service;

    @GetMapping
    public Page<MyBidSummary> getMyBids(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(name = "status", required = false) String status,
            @PageableDefault(size = 20) Pageable pageable) {
        return service.getMyBids(principal.userId(), status, pageable);
    }
}
