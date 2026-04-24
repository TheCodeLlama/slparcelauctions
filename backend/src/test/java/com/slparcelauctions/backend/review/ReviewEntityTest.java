package com.slparcelauctions.backend.review;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.slparcelauctions.backend.user.User;

/**
 * Pure POJO-level assertions for the {@link Review} entity's
 * {@code @Builder.Default} contract. Protects the {@code visible=false}
 * and {@code flagCount=0} defaults — a regression here would silently
 * surface unrevealed reviews on the public list endpoint.
 */
class ReviewEntityTest {

    @Test
    void visibleDefaultsFalseAndFlagCountZero() {
        User u = User.builder().email("x@y.z").passwordHash("x").build();
        Review r = Review.builder()
                .reviewer(u)
                .reviewee(u)
                .reviewedRole(ReviewedRole.SELLER)
                .rating(5)
                .build();
        assertThat(r.getVisible()).isFalse();
        assertThat(r.getFlagCount()).isEqualTo(0);
        assertThat(r.getRevealedAt()).isNull();
    }
}
