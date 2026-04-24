package com.slparcelauctions.backend.review;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link ReviewFlag}. {@code (reviewId, flaggerId)}
 * uniqueness is enforced at the DB; the {@code existsBy...} helper lets
 * the Task 3 flag-submission path return a 409 without triggering the
 * constraint violation.
 */
public interface ReviewFlagRepository extends JpaRepository<ReviewFlag, Long> {

    boolean existsByReviewIdAndFlaggerId(Long reviewId, Long flaggerId);
}
