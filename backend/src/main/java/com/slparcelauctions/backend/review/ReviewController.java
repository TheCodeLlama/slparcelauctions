package com.slparcelauctions.backend.review;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.exception.AuctionNotFoundException;
import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.review.dto.AuctionReviewsResponse;
import com.slparcelauctions.backend.review.dto.ReviewDto;
import com.slparcelauctions.backend.review.dto.ReviewSubmitRequest;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserNotFoundException;
import com.slparcelauctions.backend.user.UserRepository;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * REST entry points for the blind-reveal review pipeline. Task 1 shipped
 * the submit endpoint; Task 2 adds the GET list path on the same
 * controller. The GET is public so anon browsers can see the visible
 * reviews, but authenticates a principal when one is present so the
 * response can enrich with the caller's own pending submission +
 * {@code canReview} flag.
 *
 * <p>{@code auctionPublicId} is a UUID — internal Long PKs are not exposed
 * on the web/mobile API surface.
 */
@RestController
@RequestMapping("/api/v1/auctions")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;
    private final UserRepository userRepository;
    private final AuctionRepository auctionRepository;

    /**
     * Submit a review for an auction. Authenticated; the caller's User
     * entity is loaded from the JWT-bearing {@link AuthPrincipal} so
     * the service has a managed reference for the {@code reviewer} FK
     * without another round-trip. Eligibility and duplicate-checks live
     * in the service so the controller stays thin.
     */
    @PostMapping("/{auctionPublicId}/reviews")
    public ResponseEntity<ReviewDto> submit(
            @PathVariable UUID auctionPublicId,
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody ReviewSubmitRequest request) {
        User caller = userRepository.findById(principal.userId())
                .orElseThrow(() -> new UserNotFoundException(principal.userId()));
        Long auctionId = resolveAuctionId(auctionPublicId);
        ReviewDto dto = reviewService.submit(auctionId, caller, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(dto);
    }

    /**
     * Public GET of all reviews for an auction. Anonymous callers get
     * only the visible reviews; authenticated callers who are a party
     * to the completed escrow additionally see {@code myPendingReview},
     * {@code canReview}, and {@code windowClosesAt} so the UI can render
     * the "compose your review" affordance without a second round-trip.
     * Non-party authenticated callers see the same anon-shape response
     * (no pending / no canReview).
     */
    @GetMapping("/{auctionPublicId}/reviews")
    public AuctionReviewsResponse list(
            @PathVariable UUID auctionPublicId,
            @AuthenticationPrincipal AuthPrincipal principal) {
        User caller = principal == null
                ? null
                : userRepository.findById(principal.userId()).orElse(null);
        Long auctionId = resolveAuctionId(auctionPublicId);
        return reviewService.listForAuction(auctionId, caller);
    }

    private Long resolveAuctionId(UUID auctionPublicId) {
        return auctionRepository.findByPublicId(auctionPublicId)
                .map(com.slparcelauctions.backend.auction.Auction::getId)
                .orElseThrow(() -> new AuctionNotFoundException(auctionPublicId));
    }
}
