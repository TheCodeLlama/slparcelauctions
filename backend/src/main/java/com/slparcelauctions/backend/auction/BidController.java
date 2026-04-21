package com.slparcelauctions.backend.auction;

import org.springframework.data.domain.Page;
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
import com.slparcelauctions.backend.auth.AuthPrincipal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * REST surface for bid placement and history on an auction.
 *
 * <ul>
 *   <li>{@code POST /api/v1/auctions/{id}/bids} — place a bid. Requires an
 *       authenticated, SL-verified, non-seller caller. 201 on success; full
 *       error surface maps through {@code AuctionExceptionHandler}.</li>
 *   <li>{@code GET /api/v1/auctions/{id}/bids} — public paged history,
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
@RequestMapping("/api/v1/auctions/{auctionId}/bids")
@RequiredArgsConstructor
public class BidController {

    private final BidService bidService;
    private final BidRepository bidRepo;

    @PostMapping
    public ResponseEntity<BidResponse> placeBid(
            @PathVariable Long auctionId,
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody PlaceBidRequest request,
            HttpServletRequest httpRequest) {
        String ip = extractClientIp(httpRequest);
        BidResponse response = bidService.placeBid(
                auctionId, principal.userId(), request.amount(), ip);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public Page<BidHistoryEntry> bidHistory(
            @PathVariable Long auctionId,
            Pageable pageable) {
        return bidRepo.findByAuctionIdOrderByCreatedAtDesc(auctionId, pageable)
                .map(BidHistoryEntry::from);
    }

    /**
     * Extracts the client IP for abuse-audit persistence. Reads
     * {@code X-Forwarded-For} and returns the leftmost entry (original
     * client), trimmed. Falls back to the socket-level remote address
     * when the header is absent.
     *
     * <p>Per spec §6 "broken-chain case": if the deployment ever runs
     * behind an untrusted proxy that blindly forwards a client-controlled
     * {@code X-Forwarded-For}, the ForwardedHeaderFilter should be
     * disabled upstream. This helper returns whatever Spring's
     * {@code framework} forward-headers strategy has already massaged
     * into the request — the bidder-supplied value is discarded before
     * we ever see it.
     */
    static String extractClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            // Leftmost entry = original client. Commas separate each
            // intermediate proxy; take the first token and trim.
            int comma = xff.indexOf(',');
            String leftmost = comma < 0 ? xff : xff.substring(0, comma);
            return leftmost.trim();
        }
        return request.getRemoteAddr();
    }
}
