package com.slparcelauctions.backend.admin.audit;

import java.util.Collections;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.admin.users.dto.AdminUserModerationRowDto;
import com.slparcelauctions.backend.common.PagedResponse;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/admin/audit")
@RequiredArgsConstructor
public class AdminAuditController {

    private static final int MAX_PAGE_SIZE = 100;

    private final AdminActionRepository repo;
    private final RealtyGroupRepository realtyGroupRepository;

    @GetMapping
    public PagedResponse<AdminUserModerationRowDto> list(
            @RequestParam(required = false) AdminActionTargetType targetType,
            @RequestParam(required = false) Long targetId,
            @RequestParam(required = false) Long adminUserId,
            @RequestParam(required = false) EntityType entityType,
            @RequestParam(required = false) UUID entityId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE));
        Page<AdminAction> result;
        if (entityType == EntityType.REALTY_GROUP) {
            // Spec §17: entityType=REALTY_GROUP filters to action_type LIKE 'REALTY_GROUP_%';
            // optional entityId narrows further to one group's actions. entityId arrives as
            // the wire-stable groupPublicId (UUID); resolve it to the Long groupId so the
            // native query can match both target-id-based rows and evidence-JSON references.
            Long groupId = null;
            if (entityId != null) {
                groupId = realtyGroupRepository.findByPublicId(entityId)
                    .map(RealtyGroup::getId)
                    .orElse(null);
                if (groupId == null) {
                    // Unknown publicId — surface as an empty page rather than 404 so the
                    // admin UI's audit tab degrades gracefully when a dissolved-and-purged
                    // group is referenced.
                    result = new PageImpl<>(Collections.emptyList(), pageable, 0);
                    return PagedResponse.from(result.map(this::toDto));
                }
            }
            result = repo.findRealtyGroupActions(groupId, pageable);
        } else if (targetType != null && targetId != null) {
            result = repo.findByTargetTypeAndTargetIdOrderByCreatedAtDesc(targetType, targetId, pageable);
        } else if (adminUserId != null) {
            result = repo.findByAdminUserIdOrderByCreatedAtDesc(adminUserId, pageable);
        } else {
            result = repo.findAll(pageable);
        }
        return PagedResponse.from(result.map(this::toDto));
    }

    /**
     * Entity-scoped filter values exposed via the {@code entityType} query parameter. Distinct
     * from {@link AdminActionTargetType} (which is the row-level {@code admin_actions.target_type}
     * column): {@code entityType} groups rows by their semantic subject across heterogeneous
     * {@code target_type} values, where {@code target_type} is the literal pointer column.
     * Spec §17.
     */
    public enum EntityType {
        REALTY_GROUP
    }

    private AdminUserModerationRowDto toDto(AdminAction a) {
        return new AdminUserModerationRowDto(
            a.getId(), a.getActionType().name(),
            a.getAdminUser().getDisplayName(),
            a.getNotes(), a.getCreatedAt());
    }
}
