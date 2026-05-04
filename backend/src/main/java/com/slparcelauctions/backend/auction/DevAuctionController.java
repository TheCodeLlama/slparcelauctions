package com.slparcelauctions.backend.auction;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.auction.dto.DevPayRequest;
import com.slparcelauctions.backend.auction.dto.SellerAuctionResponse;
import com.slparcelauctions.backend.auction.exception.InvalidAuctionStateException;
import com.slparcelauctions.backend.auth.AuthPrincipal;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Dev-profile-only stub for the listing-fee payment callback that arrives in
 * Epic 05 from the in-world payment terminal. Exposes
 * {@code POST /api/v1/dev/auctions/{id}/pay} which transitions a DRAFT auction
 * to DRAFT_PAID with a mock payment payload.
 *
 * <p>Three-layer gating (mirrors {@code DevSlSimulateController}):
 * <ol>
 *   <li>{@link Profile @Profile("dev")} — bean is not instantiated outside dev.</li>
 *   <li>{@code SecurityConfig} permits {@code /api/v1/dev/**} unconditionally —
 *       the profile gate is the real trust boundary; in prod no handler exists
 *       so the request 404s at the MVC layer.</li>
 *   <li>Explicit seller check via
 *       {@link AuctionService#loadForSeller(Long, Long)} which 404s if the
 *       auction is not owned by the authenticated user — so a random signed-in
 *       user cannot mark someone else's draft paid.</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/v1/dev/auctions")
@RequiredArgsConstructor
@Profile("dev")
@Slf4j
public class DevAuctionController {

    private final AuctionService auctionService;
    private final AuctionRepository auctionRepo;
    private final AuctionDtoMapper mapper;
    private final Clock clock;

    @Value("${slpa.listing-fee.amount-lindens:100}")
    private Long defaultListingFee;

    @PostMapping("/{publicId}/pay")
    @Transactional
    public SellerAuctionResponse pay(
            @PathVariable UUID publicId,
            @Valid @RequestBody(required = false) DevPayRequest body,
            @AuthenticationPrincipal AuthPrincipal principal) {
        if (principal == null) {
            // /api/v1/dev/** is permitAll in SecurityConfig (the @Profile("dev") gate is the
            // real trust boundary), so the JWT filter chain will not reject anonymous requests
            // at the filter layer. Enforce authentication explicitly here; the global handler
            // maps AccessDeniedException to 403 with code=ACCESS_DENIED.
            throw new AccessDeniedException(
                    "Authentication required to simulate listing-fee payment.");
        }
        Long userId = principal.userId();
        Long id = auctionRepo.findByPublicId(publicId)
                .map(Auction::getId)
                .orElseThrow(() -> new com.slparcelauctions.backend.auction.exception.AuctionNotFoundException(publicId));
        Auction a = auctionService.loadForSeller(id, userId);
        if (a.getStatus() != AuctionStatus.DRAFT) {
            throw new InvalidAuctionStateException(a.getId(), a.getStatus(), "DEV_PAY");
        }

        Long amount = (body != null && body.amount() != null) ? body.amount() : defaultListingFee;
        String txnRef = (body != null && body.txnRef() != null && !body.txnRef().isBlank())
                ? body.txnRef()
                : "dev-mock-" + UUID.randomUUID();

        a.setListingFeePaid(true);
        a.setListingFeeAmt(amount);
        a.setListingFeeTxn(txnRef);
        a.setListingFeePaidAt(OffsetDateTime.now(clock));
        a.setStatus(AuctionStatus.DRAFT_PAID);
        Auction saved = auctionRepo.save(a);
        log.info("Dev-paid auction {}: amount={}, txnRef={}", saved.getId(), amount, txnRef);
        return mapper.toSellerResponse(saved, null);
    }
}
