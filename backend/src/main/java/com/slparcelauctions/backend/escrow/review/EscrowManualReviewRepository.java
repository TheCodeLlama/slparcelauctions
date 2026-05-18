package com.slparcelauctions.backend.escrow.review;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EscrowManualReviewRepository extends JpaRepository<EscrowManualReview, Long> {
    Optional<EscrowManualReview> findByEscrowIdAndStatus(Long escrowId, ManualReviewStatus status);
    Optional<EscrowManualReview> findByPublicId(UUID publicId);
    @Query("SELECT r FROM EscrowManualReview r WHERE (:status IS NULL OR r.status = :status) ORDER BY r.createdAt ASC")
    Page<EscrowManualReview> findFiltered(@Param("status") ManualReviewStatus status, Pageable pageable);
    List<EscrowManualReview> findByEscrowIdOrderByCreatedAtDesc(Long escrowId);
}
