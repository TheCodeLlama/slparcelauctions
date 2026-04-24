package com.slparcelauctions.backend.review;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.review.dto.ReviewDto;
import com.slparcelauctions.backend.review.dto.ReviewSubmitRequest;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserNotFoundException;
import com.slparcelauctions.backend.user.UserRepository;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * REST entry points for the blind-reveal review pipeline. Task 1 ships
 * only the submit endpoint; Task 2 adds the GET list path on the same
 * controller.
 */
@RestController
@RequestMapping("/api/v1/auctions")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;
    private final UserRepository userRepository;

    /**
     * Submit a review for an auction. Authenticated; the caller's User
     * entity is loaded from the JWT-bearing {@link AuthPrincipal} so
     * the service has a managed reference for the {@code reviewer} FK
     * without another round-trip. Eligibility and duplicate-checks live
     * in the service so the controller stays thin.
     */
    @PostMapping("/{auctionId}/reviews")
    public ResponseEntity<ReviewDto> submit(
            @PathVariable Long auctionId,
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody ReviewSubmitRequest request) {
        User caller = userRepository.findById(principal.userId())
                .orElseThrow(() -> new UserNotFoundException(principal.userId()));
        ReviewDto dto = reviewService.submit(auctionId, caller, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(dto);
    }
}
