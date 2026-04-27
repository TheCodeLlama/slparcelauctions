package com.slparcelauctions.backend.admin.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminActionRepository extends JpaRepository<AdminAction, Long> {

    Page<AdminAction> findByTargetTypeAndTargetIdOrderByCreatedAtDesc(
            AdminActionTargetType targetType, Long targetId, Pageable pageable);

    Page<AdminAction> findByAdminUserIdOrderByCreatedAtDesc(Long adminUserId, Pageable pageable);
}
