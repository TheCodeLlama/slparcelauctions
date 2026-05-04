package com.slparcelauctions.backend.auction;

import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.auction.dto.BidHistoryEntry;
import com.slparcelauctions.backend.auction.dto.BidResponse;
import com.slparcelauctions.backend.auction.dto.PlaceBidRequest;
import com.slparcelauctions.backend.auction.exception.AuctionNotFoundException;
import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.common.PagedResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * REST surface for bid placement and history on an auction.
 *
 * <ul>
 *   <li>{@code POST /api/v1/auctions/{auctionPublicId}/bids} — place a bid. Requires an
 *       authenticated, SL-verified, non-seller caller. 201 on success; full
 *       error surface maps through {@code AuctionExceptionHandler}.</li>
 *   <li>{@code GET /api/v1/auctions/{auctionPublicId}/bids} — public paged history,
 *       newest-first. No auth required — bid identity is public per
 *       DESIGN.md §1589-1591. The path-level {@code permitAll} is wired in
 *       {@code SecurityConfig}.</li>
 * </ul>
 *
 * <p>Authentication for the POST path is enforced by the
 * {@code /api/v1/**} catch-all in {@code SecurityConfig} — any valid JWT
 * gets past Spring Security; the verified + not-seller checks run inside
 * {@link BidService#placeBid} so the lock-acquire-then-validate ordering
 * is preserved.
 */
@RestController
@RequestMapping("/api/v1/auctions/{auctionPublicId}/bids")
@RequiredArgsConstructor
public class BidController {

    private final BidService bidService;
    private final BidRepository bidRepo;
    private final AuctionRepository auctionRepository;

    @PostMapping
    public ResponseEntity<BidResponse> placeBid(
            @PathVariable UUID auctionPublicId,
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody PlaceBidRequest request,
            HttpServletRequest httpRequest) {
        // IP capture for abuse-audit persistence. With
        // server.forward-headers-strategy=framework, Spring's
        // ForwardedHeaderFilter has already resolved X-Forwarded-For into
        // getRemoteAddr() when the request arrives from a trusted proxy.
        // Without a proxy in front (local dev, direct browser) this is the
        // socket peer, which is what we want. The trust decision lives in
        // the Spring filter chain — see application.yml for the deployment
        // contract that makes this safe.
        String ip = httpRequest.getRemoteAddr();
        Long auctionId = resolveAuctionId(auctionPublicId);
        BidResponse response = bidService.placeBid(
                auctionId, principal.userId(), request.amount(), ip);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public PagedResponse<BidHistoryEntry> bidHistory(
            @PathVariable UUID auctionPublicId,
            Pageable pageable) {
        Long auctionId = resolveAuctionId(auctionPublicId);
        return PagedResponse.from(bidRepo.findByAuctionIdOrderByCreatedAtDesc(auctionId, pageable)
                .map(BidHistoryEntry::from));
    }

    private Long resolveAuctionId(UUID auctionPublicId) {
        return auctionRepository.findByPublicId(auctionPublicId)
                .map(Auction::getId)
                .orElseThrow(() -> new AuctionNotFoundException(auctionPublicId));
    }
}
