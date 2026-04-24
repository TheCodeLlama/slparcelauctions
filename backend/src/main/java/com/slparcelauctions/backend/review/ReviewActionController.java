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
import com.slparcelauctions.backend.review.dto.ReviewFlagRequest;
import com.slparcelauctions.backend.review.dto.ReviewResponseDto;
import com.slparcelauctions.backend.review.dto.ReviewResponseSubmitRequest;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserNotFoundException;
import com.slparcelauctions.backend.user.UserRepository;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Review secondary-action endpoints (Epic 08 sub-spec 1 Task 3): post one
 * reviewee response, or raise a moderation flag against someone else's
 * review. Split from {@link ReviewController} because the paths root at
 * {@code /api/v1/reviews/{id}/*} rather than
 * {@code /api/v1/auctions/{id}/reviews}; keeping the two classes separate
 * avoids the two {@code @RequestMapping} prefixes colliding on a single
 * controller and mirrors the {@link UserReviewsController} split that
 * Task 2 already established.
 *
 * <p>Both endpoints gate behind the JWT filter — the caller's {@link User}
 * is loaded from the {@link AuthPrincipal} at the controller boundary so
 * the service layer has a managed reference for the reviewee-check /
 * reviewer-check and the FKs on {@code ReviewResponse} / {@code ReviewFlag}.
 * Per FOOTGUNS §B.1, this codebase uses {@link AuthPrincipal}, never
 * {@code UserDetails}.
 */
@RestController
@RequestMapping("/api/v1/reviews")
@RequiredArgsConstructor
public class ReviewActionController {

    private final ReviewService reviewService;
    private final UserRepository userRepository;

    /**
     * Reviewee posts a one-time response to a review about them. Service
     * rejects non-reviewees with 403 and a second response on the same
     * review with 409; request validation ({@code @NotBlank},
     * {@code @Size(max=500)}) fires at the controller boundary before the
     * service is called.
     */
    @PostMapping("/{id}/respond")
    public ResponseEntity<ReviewResponseDto> respond(
            @PathVariable("id") Long reviewId,
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody ReviewResponseSubmitRequest request) {
        User caller = userRepository.findById(principal.userId())
                .orElseThrow(() -> new UserNotFoundException(principal.userId()));
        ReviewResponseDto dto = reviewService.respondTo(reviewId, caller, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(dto);
    }

    /**
     * Any authenticated user other than the review's author may flag the
     * review for admin review. Class-level
     * {@link com.slparcelauctions.backend.review.exception.ElaborationRequiredWhenOther}
     * enforces the "{@code elaboration} required when {@code reason=OTHER}"
     * cross-field rule at request-bind time. 204 No Content mirrors the
     * spec §4.4 contract; the flag is a fire-and-forget signal for
     * moderators.
     */
    @PostMapping("/{id}/flag")
    public ResponseEntity<Void> flag(
            @PathVariable("id") Long reviewId,
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody ReviewFlagRequest request) {
        User caller = userRepository.findById(principal.userId())
                .orElseThrow(() -> new UserNotFoundException(principal.userId()));
        reviewService.flag(reviewId, caller, request);
        return ResponseEntity.noContent().build();
    }
}
