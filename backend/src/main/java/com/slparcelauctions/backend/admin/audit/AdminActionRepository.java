package com.slparcelauctions.backend.admin.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AdminActionRepository extends JpaRepository<AdminAction, Long>,
        JpaSpecificationExecutor<AdminAction> {

    Page<AdminAction> findByTargetTypeAndTargetIdOrderByCreatedAtDesc(
            AdminActionTargetType targetType, Long targetId, Pageable pageable);

    Page<AdminAction> findByAdminUserIdOrderByCreatedAtDesc(Long adminUserId, Pageable pageable);

    /**
     * Admin-audit page filtered to realty-group admin actions, with optional narrowing to a
     * specific group. Supports the {@code entityType=REALTY_GROUP} + {@code entityId={groupPublicId}}
     * query parameters on {@code GET /api/v1/admin/audit} (spec §17).
     *
     * <p>Two filter shapes are exposed through a single SQL:
     * <ul>
     *   <li><b>{@code entityType=REALTY_GROUP} only</b> (groupId is {@code null}): matches every row
     *       whose {@code action_type} begins with {@code REALTY_GROUP_} — i.e. all 16 realty-group
     *       admin actions defined in spec §4.6, including the system-actor batch row
     *       {@code REALTY_GROUP_BULK_SUSPEND_EXPIRY_RUN}.</li>
     *   <li><b>{@code entityType=REALTY_GROUP} + {@code entityId}</b> (groupId resolved by the
     *       controller from the wire UUID): same {@code REALTY_GROUP_*} prefix filter, additionally
     *       narrowed to rows that reference the specific group. A row "references" the group when
     *       either:
     *       <ol>
     *         <li>{@code target_type='REALTY_GROUP' AND target_id=:groupId} — the canonical
     *             linkage used by EDIT/DISSOLVE/MEMBER_REMOVE/SUSPEND/UNSUSPEND/BAN/UNBAN/
     *             BULK_SUSPEND/BULK_REINSTATE/SL_GROUP_FORCE_UNREGISTER/SL_GROUP_DRIFT_ACK/
     *             SL_GROUP_RECHECK/FRAUD_FLAG writers (they all set target_type=REALTY_GROUP and
     *             target_id=group.id), OR</li>
     *         <li>the JSONB evidence map carries {@code "groupId": <long>} — the linkage used by
     *             REPORT_RESOLVE/REPORT_DISMISS writers, which set target_type=REPORT (the
     *             report row, not the group) but record the group's internal id in evidence so
     *             group-scoped queries can still find them.</li>
     *       </ol>
     *       The system-actor batch row ({@code BULK_SUSPEND_EXPIRY_RUN}) is intentionally excluded
     *       when an {@code entityId} is supplied — it is a system-wide batch row that doesn't
     *       reference any specific group's targetId or evidence.</li>
     * </ul>
     */
    @Query(value = """
        SELECT * FROM admin_actions
        WHERE action_type LIKE 'REALTY_GROUP\\_%' ESCAPE '\\'
          AND (
              CAST(:groupId AS bigint) IS NULL
              OR (target_type = 'REALTY_GROUP' AND target_id = :groupId)
              OR (details IS NOT NULL AND details->>'groupId' = CAST(:groupId AS text))
          )
        ORDER BY created_at DESC
        """,
        countQuery = """
        SELECT COUNT(*) FROM admin_actions
        WHERE action_type LIKE 'REALTY_GROUP\\_%' ESCAPE '\\'
          AND (
              CAST(:groupId AS bigint) IS NULL
              OR (target_type = 'REALTY_GROUP' AND target_id = :groupId)
              OR (details IS NOT NULL AND details->>'groupId' = CAST(:groupId AS text))
          )
        """,
        nativeQuery = true)
    Page<AdminAction> findRealtyGroupActions(@Param("groupId") Long groupId, Pageable pageable);
}
