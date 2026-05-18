package com.slparcelauctions.backend.review.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.review.Review;
import com.slparcelauctions.backend.review.ReviewedRole;
import com.slparcelauctions.backend.user.User;

/**
 * Regression for the prod bug: {@link ReviewDto#of} built the reviewer
 * avatar URL from the numeric DB {@code id}, but
 * {@code GET /api/v1/users/{publicId}/avatar/{size}} parses the path
 * variable as a {@code UUID} — so {@code /api/v1/users/42/avatar/256}
 * 404'd and the reviewer {@code <img>} fell back to alt-text.
 */
class ReviewDtoTest {

    private User user(long id, UUID publicId, String displayName) {
        return User.builder()
                .id(id)
                .publicId(publicId)
                .email(displayName + "@example.com")
                .username(displayName.toLowerCase())
                .passwordHash("x")
                .displayName(displayName)
                .build();
    }

    @Test
    void of_reviewerAvatarUrl_usesPublicIdNotNumericId() {
        UUID reviewerPublicId = UUID.randomUUID();
        User reviewer = user(42L, reviewerPublicId, "Reviewer");
        User reviewee = user(7L, UUID.randomUUID(), "Reviewee");
        Auction auction = Auction.builder()
                .title("Lakefront")
                .seller(reviewee)
                .slParcelUuid(UUID.randomUUID())
                .build();
        Review r = Review.builder()
                .auction(auction)
                .reviewer(reviewer)
                .reviewee(reviewee)
                .reviewedRole(ReviewedRole.SELLER)
                .rating(5)
                .visible(true)
                .build();

        ReviewDto dto = ReviewDto.of(r, null, Optional.empty(), null);

        // The exact prod-bug assertion: the URL must contain the UUID
        // publicId and must NOT contain the numeric DB id "42".
        assertThat(dto.reviewerAvatarUrl())
                .isEqualTo("/api/v1/users/" + reviewerPublicId + "/avatar/256");
        assertThat(dto.reviewerAvatarUrl()).doesNotContain("/users/42/");

        // The id path segment must parse as a UUID (and equal the publicId).
        String url = dto.reviewerAvatarUrl();
        String idSegment = url.substring(
                "/api/v1/users/".length(), url.indexOf("/avatar/"));
        assertThat(UUID.fromString(idSegment)).isEqualTo(reviewerPublicId);
    }
}
