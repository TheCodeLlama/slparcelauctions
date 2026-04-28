package com.slparcelauctions.backend.admin.audit;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/audit-log")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Slf4j
public class AdminAuditLogController {

    private final AdminAuditLogService service;

    @GetMapping
    public Page<AdminAuditLogRow> list(
            @RequestParam(required = false) AdminActionType actionType,
            @RequestParam(required = false) AdminActionTargetType targetType,
            @RequestParam(required = false) Long adminUserId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        AdminAuditLogFilters filters = new AdminAuditLogFilters(
                actionType, targetType, adminUserId, from, to, q);
        return service.list(filters, page, size);
    }

    @GetMapping("/export")
    public void exportCsv(
            @RequestParam(required = false) AdminActionType actionType,
            @RequestParam(required = false) AdminActionTargetType targetType,
            @RequestParam(required = false) Long adminUserId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) String q,
            HttpServletResponse response) throws IOException {
        AdminAuditLogFilters filters = new AdminAuditLogFilters(
                actionType, targetType, adminUserId, from, to, q);

        String filename = "audit-log-" + LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE) + ".csv";
        response.setContentType("text/csv");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + filename + "\"");

        try (PrintWriter writer = response.getWriter()) {
            writer.println("timestamp,action,admin_email,target_type,target_id,notes,details_json");
            service.exportCsvStream(filters).forEach(row -> writer.println(formatCsvRow(row)));
        }
    }

    private String formatCsvRow(AdminAuditLogRow row) {
        // AdminAuditLogRow.occurredAt() is the DTO accessor (entity field is createdAt,
        // but the record field is named occurredAt — verified in Task 4).
        return String.join(",",
                csvEscape(row.occurredAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)),
                csvEscape(row.actionType().name()),
                csvEscape(row.adminEmail() != null ? row.adminEmail() : ""),
                csvEscape(row.targetType() != null ? row.targetType().name() : ""),
                csvEscape(row.targetId() != null ? row.targetId().toString() : ""),
                csvEscape(row.notes() != null ? row.notes() : ""),
                csvEscape(serializeDetails(row.details())));
    }

    private String csvEscape(String value) {
        if (value == null) return "";
        boolean needsQuoting = value.contains(",") || value.contains("\"")
                || value.contains("\n") || value.contains("\r");
        if (!needsQuoting) return value;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private String serializeDetails(Map<String, Object> details) {
        if (details == null || details.isEmpty()) return "";
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(details);
        } catch (Exception e) {
            log.warn("Failed to serialize audit log details to JSON", e);
            return "";
        }
    }
}
