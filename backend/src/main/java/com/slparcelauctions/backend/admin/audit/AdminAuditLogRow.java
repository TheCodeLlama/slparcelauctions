package com.slparcelauctions.backend.admin.audit;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AdminAuditLogRow(
        Long id,
        OffsetDateTime occurredAt,
        AdminActionType actionType,
        Long adminUserId,
        String adminEmail,
        AdminActionTargetType targetType,
        Long targetId,
        String notes,
        Map<String, Object> details) {
}
