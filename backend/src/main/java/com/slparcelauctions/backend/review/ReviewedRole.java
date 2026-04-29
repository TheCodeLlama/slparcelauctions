package com.slparcelauctions.backend.review;

/**
 * Role whose behaviour a {@link Review} rates. Persisted (not derived) on
 * the Review row so aggregate queries over a reviewee's visible reviews
 * use an index-only scan — see
 * {@code idx_reviews_reviewee_visible}.
 *
 * <p>A seller→buyer review (seller rates the winner) carries
 * {@code reviewedRole=BUYER}; on reveal, the reviewee's
 * {@code avgBuyerRating} + {@code totalBuyerReviews} recompute. A
 * winner→seller review carries {@code reviewedRole=SELLER}.
 */
public enum ReviewedRole {
    SELLER,
    BUYER
}
