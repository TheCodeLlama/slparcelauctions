package com.slparcelauctions.backend.auction;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.auction.dto.AuctionCancelRequest;
import com.slparcelauctions.backend.auction.dto.AuctionCreateRequest;
import com.slparcelauctions.backend.auction.dto.AuctionUpdateRequest;
import com.slparcelauctions.backend.auction.dto.PendingVerification;
import com.slparcelauctions.backend.auction.dto.PublicAuctionResponse;
import com.slparcelauctions.backend.auction.dto.SellerAuctionResponse;
import com.slparcelauctions.backend.auction.exception.AuctionNotFoundException;
import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserNotFoundException;
import com.slparcelauctions.backend.user.UserRepository;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Auction CRUD endpoints. Write paths (create/update/cancel) require the caller to be
 * SL-verified — enforced inline by {@link #requireVerified(Long)} before delegating to
 * services. Reads do not require verification: the public view
 * ({@link PublicAuctionResponse}) is available to any authenticated user, and pre-ACTIVE
 * drafts 404 to non-sellers to avoid leaking existence.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AuctionController {

    private final AuctionService auctionService;
    private final AuctionVerificationService verificationService;
    private final CancellationService cancellationService;
    private final AuctionDtoMapper mapper;
    private final UserRepository userRepository;

    @PostMapping("/auctions")
    @ResponseStatus(HttpStatus.CREATED)
    public SellerAuctionResponse create(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody AuctionCreateRequest req) {
        requireVerified(principal.userId());
        Auction created = auctionService.create(principal.userId(), req);
        return mapper.toSellerResponse(created, null);
    }

    @GetMapping("/auctions/{id}")
    public Object get(
            @PathVariable Long id,
            @AuthenticationPrincipal AuthPrincipal principal) {
        Auction a = auctionService.load(id);
        Long userId = principal == null ? null : principal.userId();
        boolean isSeller = userId != null && a.getSeller().getId().equals(userId);
        if (!isSeller) {
            if (isPreActive(a.getStatus())) {
                throw new AuctionNotFoundException(id); // hide existence
            }
            return mapper.toPublicResponse(a);
        }
        return mapper.toSellerResponse(a, null);
    }

    @GetMapping("/users/me/auctions")
    public List<SellerAuctionResponse> listMine(
            @AuthenticationPrincipal AuthPrincipal principal) {
        return auctionService.loadOwnedBy(principal.userId()).stream()
                .map(a -> mapper.toSellerResponse(a, null))
                .toList();
    }

    @PutMapping("/auctions/{id}")
    public SellerAuctionResponse update(
            @PathVariable Long id,
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody AuctionUpdateRequest req) {
        requireVerified(principal.userId());
        Auction updated = auctionService.update(id, principal.userId(), req);
        return mapper.toSellerResponse(updated, null);
    }

    @PutMapping("/auctions/{id}/verify")
    public SellerAuctionResponse verify(
            @PathVariable Long id,
            @AuthenticationPrincipal AuthPrincipal principal) {
        Long userId = principal.userId();
        requireVerified(userId);
        Auction a = verificationService.triggerVerification(id, userId);
        PendingVerification pending = verificationService.buildPendingVerification(a);
        return mapper.toSellerResponse(a, pending);
    }

    @PutMapping("/auctions/{id}/cancel")
    public SellerAuctionResponse cancel(
            @PathVariable Long id,
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody AuctionCancelRequest req) {
        requireVerified(principal.userId());
        Auction a = auctionService.loadForSeller(id, principal.userId());
        Auction cancelled = cancellationService.cancel(a, req.reason());
        return mapper.toSellerResponse(cancelled, null);
    }

    @GetMapping("/auctions/{id}/preview")
    public SellerAuctionResponse preview(
            @PathVariable Long id,
            @AuthenticationPrincipal AuthPrincipal principal) {
        Auction a = auctionService.loadForSeller(id, principal.userId());
        return mapper.toSellerResponse(a, null);
    }

    private boolean isPreActive(AuctionStatus s) {
        return s == AuctionStatus.DRAFT
                || s == AuctionStatus.DRAFT_PAID
                || s == AuctionStatus.VERIFICATION_PENDING
                || s == AuctionStatus.VERIFICATION_FAILED;
    }

    private void requireVerified(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
        if (!Boolean.TRUE.equals(user.getVerified())) {
            throw new AccessDeniedException(
                    "SL avatar verification required to manage auctions.");
        }
    }
}
