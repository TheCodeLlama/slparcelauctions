package com.slparcelauctions.backend.admin.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.admin.users.dto.AdminUserModerationRowDto;
import com.slparcelauctions.backend.common.PagedResponse;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/admin/audit")
@RequiredArgsConstructor
public class AdminAuditController {

    private static final int MAX_PAGE_SIZE = 100;

    private final AdminActionRepository repo;

    @GetMapping
    public PagedResponse<AdminUserModerationRowDto> list(
            @RequestParam(required = false) AdminActionTargetType targetType,
            @RequestParam(required = false) Long targetId,
            @RequestParam(required = false) Long adminUserId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE));
        Page<AdminAction> result;
        if (targetType != null && targetId != null) {
            result = repo.findByTargetTypeAndTargetIdOrderByCreatedAtDesc(targetType, targetId, pageable);
        } else if (adminUserId != null) {
            result = repo.findByAdminUserIdOrderByCreatedAtDesc(adminUserId, pageable);
        } else {
            result = repo.findAll(pageable);
        }
        return PagedResponse.from(result.map(this::toDto));
    }

    private AdminUserModerationRowDto toDto(AdminAction a) {
        return new AdminUserModerationRowDto(
            a.getId(), a.getActionType().name(),
            a.getAdminUser().getDisplayName(),
            a.getNotes(), a.getCreatedAt());
    }
}
