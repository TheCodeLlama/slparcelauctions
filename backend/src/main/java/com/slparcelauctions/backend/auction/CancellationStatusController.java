package com.slparcelauctions.backend.auction;

import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.auction.dto.CancellationHistoryDto;
import com.slparcelauctions.backend.auction.dto.CancellationStatusResponse;
import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.common.PagedResponse;

import lombok.RequiredArgsConstructor;

/**
 * Read-only endpoints backing the cancel modal and the seller's cancellation
 * history view (Epic 08 sub-spec 2 §7.3 / §7.4). Both paths require a JWT —
 * see SecurityConfig — and read the caller off the
 * {@link AuthPrincipal} (FOOTGUNS §B.1: never use {@code UserDetails}).
 */
@RestController
@RequestMapping("/api/v1/users/me")
@RequiredArgsConstructor
public class CancellationStatusController {

    private final CancellationStatusService service;

    @GetMapping("/cancellation-status")
    public CancellationStatusResponse status(
            @AuthenticationPrincipal AuthPrincipal principal) {
        return service.statusFor(principal.userId());
    }

    @GetMapping("/cancellation-history")
    public PagedResponse<CancellationHistoryDto> history(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        // PageRequest size is clamped at the service layer
        // (CancellationStatusService.MAX_PAGE_SIZE = 50) so a caller
        // requesting size=9999 still gets a 50-row page.
        return PagedResponse.from(
                service.historyFor(principal.userId(), PageRequest.of(page, size)));
    }
}
