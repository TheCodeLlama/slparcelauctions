package com.slparcelauctions.backend.realty.rating;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.common.PagedResponse;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.exception.RealtyGroupNotFoundException;
import com.slparcelauctions.backend.realty.rating.dto.GroupReviewRowDto;

import lombok.RequiredArgsConstructor;

/**
 * Sub-project G §13 — paginated public reviews list for a realty group.
 *
 * <p>Anonymous-accessible; matches the auth posture of the user-side public
 * reviews endpoint ({@code GET /api/v1/users/{publicId}/reviews}). Returns
 * an empty page when the group has no reviews. Dissolved groups 404 the
 * same as any other lookup ({@link RealtyGroupRepository#findByPublicIdAndDissolvedAtIsNull}).
 *
 * <p>Page size is clamped server-side to a sensible maximum so a hostile
 * client cannot ask for an unbounded dataset.
 */
@RestController
@RequestMapping("/api/v1/realty/groups/{publicId}/reviews")
@RequiredArgsConstructor
public class RealtyGroupReviewsController {

    private static final int MAX_PAGE_SIZE = 50;

    private final GroupRatingService ratingService;
    private final RealtyGroupRepository groupRepo;

    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<PagedResponse<GroupReviewRowDto>> list(
            @PathVariable("publicId") UUID groupPublicId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {

        RealtyGroup group = groupRepo.findByPublicIdAndDissolvedAtIsNull(groupPublicId)
            .orElseThrow(() -> new RealtyGroupNotFoundException(groupPublicId));

        int clampedPage = Math.max(page, 0);
        int clampedSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);

        Page<GroupReviewRowDto> p = ratingService.listReviews(
            group.getId(), PageRequest.of(clampedPage, clampedSize));
        return ResponseEntity.ok(PagedResponse.from(p));
    }
}
