package com.slparcelauctions.backend.review;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link ReviewResponse}. One response per
 * review is enforced at the DB via the unique FK; the repository helpers
 * here let the service and DTO-builder paths avoid re-loading a response
 * they already know doesn't exist.
 */
public interface ReviewResponseRepository extends JpaRepository<ReviewResponse, Long> {

    Optional<ReviewResponse> findByReviewId(Long reviewId);

    boolean existsByReviewId(Long reviewId);
}
