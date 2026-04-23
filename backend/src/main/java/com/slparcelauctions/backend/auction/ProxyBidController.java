package com.slparcelauctions.backend.auction;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.auction.dto.ProxyBidResponse;
import com.slparcelauctions.backend.auction.dto.SetProxyBidRequest;
import com.slparcelauctions.backend.auction.exception.ProxyBidNotFoundException;
import com.slparcelauctions.backend.auth.AuthPrincipal;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * REST surface for a bidder's proxy ("automatic") bidding instructions on a
 * single auction. Four verbs, one per proxy lifecycle operation:
 *
 * <ul>
 *   <li>{@code POST /api/v1/auctions/{id}/proxy-bid} — create a new ACTIVE
 *       proxy for the caller, triggering the full
 *       {@code resolveProxyResolution} against any existing competitor.</li>
 *   <li>{@code PUT /api/v1/auctions/{id}/proxy-bid} — update the caller's
 *       most recent proxy. Handles both the silent cap-raise (winning ACTIVE)
 *       and resurrection (EXHAUSTED → ACTIVE) branches.</li>
 *   <li>{@code DELETE /api/v1/auctions/{id}/proxy-bid} — cancel the caller's
 *       ACTIVE proxy. 409s if the caller is currently winning.</li>
 *   <li>{@code GET /api/v1/auctions/{id}/proxy-bid} — fetch the caller's most
 *       recent proxy for this auction regardless of status.</li>
 * </ul>
 *
 * <p>Authentication for every route is enforced by the {@code /api/v1/**}
 * catch-all in {@code SecurityConfig}. The verified + not-seller checks run
 * inside {@link ProxyBidService} under the pessimistic lock so ordering is
 * preserved.
 */
@RestController
@RequestMapping("/api/v1/auctions/{auctionId}/proxy-bid")
@RequiredArgsConstructor
public class ProxyBidController {

    private final ProxyBidService proxyBidService;

    @PostMapping
    public ResponseEntity<ProxyBidResponse> createProxy(
            @PathVariable Long auctionId,
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody SetProxyBidRequest request) {
        ProxyBidResponse response = proxyBidService.createProxy(
                auctionId, principal.userId(), request.maxAmount());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping
    public ResponseEntity<ProxyBidResponse> updateProxy(
            @PathVariable Long auctionId,
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody SetProxyBidRequest request) {
        ProxyBidResponse response = proxyBidService.updateProxyMax(
                auctionId, principal.userId(), request.maxAmount());
        return ResponseEntity.ok(response);
    }

    @DeleteMapping
    public ResponseEntity<Void> cancelProxy(
            @PathVariable Long auctionId,
            @AuthenticationPrincipal AuthPrincipal principal) {
        proxyBidService.cancelProxy(auctionId, principal.userId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    public ResponseEntity<ProxyBidResponse> getMyProxy(
            @PathVariable Long auctionId,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ResponseEntity.ok(proxyBidService.getMyProxy(auctionId, principal.userId())
                .orElseThrow(ProxyBidNotFoundException::new));
    }
}
