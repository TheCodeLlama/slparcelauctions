package com.slparcelauctions.backend.review;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.common.PagedResponse;
import com.slparcelauctions.backend.review.dto.PendingReviewDto;
import com.slparcelauctions.backend.review.dto.ReviewDto;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserNotFoundException;
import com.slparcelauctions.backend.user.UserRepository;

import lombok.RequiredArgsConstructor;

/**
 * Reviews endpoints scoped to a user rather than an auction. Two paths:
 * <ul>
 *   <li>{@code GET /users/{publicId}/reviews?role=SELLER|BUYER} — public
 *       paginated list of visible reviews for a user in a specific
 *       role. Backs the profile page's reviews tab.</li>
 *   <li>{@code GET /users/me/pending-reviews} — authenticated; lists
 *       completed escrows where the caller is a party and has not yet
 *       submitted a review within the 14-day window. Backs the
 *       dashboard's "waiting for your review" card.</li>
 * </ul>
 *
 * <p>Split out of {@link ReviewController} because the paths are rooted
 * under {@code /users}, not {@code /auctions}, and mirroring the spec
 * layout keeps the SecurityConfig matchers legible.
 *
 * <p>{@code publicId} in the path is the user's UUID — internal Long PKs
 * are not exposed on the web/mobile API surface.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class UserReviewsController {

    private static final int MAX_PAGE_SIZE = 50;

    private final ReviewService reviewService;
    private final UserRepository userRepository;

    @GetMapping("/users/{publicId}/reviews")
    public PagedResponse<ReviewDto> listForUser(
            @PathVariable("publicId") UUID publicId,
            @RequestParam("role") ReviewedRole role,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "10") int size) {
        int clampedSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int clampedPage = Math.max(page, 0);
        Long userId = userRepository.findByPublicId(publicId)
                .map(User::getId)
                .orElseThrow(() -> new UserNotFoundException(publicId));
        Page<ReviewDto> result = reviewService.listForUser(userId, role,
                PageRequest.of(clampedPage, clampedSize));
        return PagedResponse.from(result);
    }

    @GetMapping("/users/me/pending-reviews")
    public List<PendingReviewDto> listPending(
            @AuthenticationPrincipal AuthPrincipal principal) {
        User caller = userRepository.findById(principal.userId())
                .orElseThrow(() -> new UserNotFoundException(principal.userId()));
        return reviewService.listPendingForCaller(caller);
    }
}
