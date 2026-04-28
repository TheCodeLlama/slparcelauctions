package com.slparcelauctions.backend.admin.audit;

import java.time.OffsetDateTime;

public record AdminAuditLogFilters(
        AdminActionType actionType,
        AdminActionTargetType targetType,
        Long adminUserId,
        OffsetDateTime from,
        OffsetDateTime to,
        String q) {

    public static AdminAuditLogFilters empty() {
        return new AdminAuditLogFilters(null, null, null, null, null, null);
    }
}
