package com.slparcelauctions.backend.admin.audit;

import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.auth.JwtService;
import com.slparcelauctions.backend.user.Role;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = {
    "auth.cleanup.enabled=false",
    "slpa.notifications.cleanup.enabled=false",
    "slpa.notifications.sl-im.cleanup.enabled=false",
    "slpa.auction-end.enabled=false",
    "slpa.ownership-monitor.enabled=false",
    "slpa.escrow.ownership-monitor-job.enabled=false",
    "slpa.escrow.timeout-job.enabled=false",
    "slpa.escrow.command-dispatcher-job.enabled=false",
    "slpa.review.scheduler.enabled=false"
})
class AdminAuditLogControllerTest {

    private static final UUID ADMIN_UUID = UUID.fromString("00000000-0000-aaaa-0004-000000000001");
    private static final UUID USER_UUID  = UUID.fromString("00000000-0000-aaaa-0004-000000000002");

    @Autowired MockMvc mvc;
    @Autowired JwtService jwtService;
    @Autowired UserRepository userRepository;

    @MockitoBean AdminAuditLogService auditLogService;

    private Long adminDbId;
    private Long userDbId;

    @BeforeEach
    void seedUsers() {
        adminDbId = userRepository.findByPublicId(ADMIN_UUID)
            .orElseGet(() -> userRepository.save(User.builder()
                .publicId(ADMIN_UUID).email("admin-auditlog@x.com")
                .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                .displayName("Admin").role(Role.ADMIN).verified(true).build()))
            .getId();
        userDbId = userRepository.findByPublicId(USER_UUID)
            .orElseGet(() -> userRepository.save(User.builder()
                .publicId(USER_UUID).email("user-auditlog@x.com")
                .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                .displayName("User").role(Role.USER).verified(true).build()))
            .getId();
    }

    private String adminToken() {
        return jwtService.issueAccessToken(new AuthPrincipal(adminDbId, ADMIN_UUID, "admin-auditlog@x.com", 1L, Role.ADMIN));
    }

    private String userToken() {
        return jwtService.issueAccessToken(new AuthPrincipal(userDbId, USER_UUID, "user-auditlog@x.com", 1L, Role.USER));
    }

    private AdminAuditLogRow sampleRow() {
        return new AdminAuditLogRow(
                1L,
                OffsetDateTime.now(),
                AdminActionType.PROMOTE_USER,
                1L,
                "admin@x.com",
                AdminActionTargetType.USER,
                99L,
                "promoted test user",
                Map.of("reason", "test"));
    }

    // -------------------------------------------------------------------------
    // Auth gates — list
    // -------------------------------------------------------------------------

    @Test
    void list_unauthenticated_returns401() throws Exception {
        mvc.perform(get("/api/v1/admin/audit-log"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void list_asUser_returns403() throws Exception {
        mvc.perform(get("/api/v1/admin/audit-log")
                .header("Authorization", "Bearer " + userToken()))
                .andExpect(status().isForbidden());
    }

    // -------------------------------------------------------------------------
    // Happy-path list
    // -------------------------------------------------------------------------

    @Test
    void list_asAdmin_returnsPaged() throws Exception {
        when(auditLogService.list(any(), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(Collections.emptyList()));

        mvc.perform(get("/api/v1/admin/audit-log")
                .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void list_asAdmin_withData_returnsRows() throws Exception {
        when(auditLogService.list(any(), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of(sampleRow())));

        mvc.perform(get("/api/v1/admin/audit-log")
                .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].actionType").value("PROMOTE_USER"))
                .andExpect(jsonPath("$.content[0].adminEmail").value("admin@x.com"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void list_asAdmin_withFilters_passesThrough() throws Exception {
        when(auditLogService.list(any(), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(Collections.emptyList()));

        mvc.perform(get("/api/v1/admin/audit-log?actionType=PROMOTE_USER&targetType=USER&page=0&size=10")
                .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    // -------------------------------------------------------------------------
    // Auth gates — export
    // -------------------------------------------------------------------------

    @Test
    void exportCsv_unauthenticated_returns401() throws Exception {
        mvc.perform(get("/api/v1/admin/audit-log/export"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void exportCsv_asUser_returns403() throws Exception {
        mvc.perform(get("/api/v1/admin/audit-log/export")
                .header("Authorization", "Bearer " + userToken()))
                .andExpect(status().isForbidden());
    }

    // -------------------------------------------------------------------------
    // Happy-path export
    // -------------------------------------------------------------------------

    @Test
    void exportCsv_asAdmin_hasCsvContentTypeAndAttachment() throws Exception {
        when(auditLogService.exportCsvStream(any())).thenReturn(Stream.empty());

        mvc.perform(get("/api/v1/admin/audit-log/export")
                .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", startsWith("text/csv")))
                .andExpect(header().string("Content-Disposition",
                        startsWith("attachment; filename=\"audit-log-")))
                .andExpect(content().string(
                        startsWith("timestamp,action,admin_email,target_type,target_id,notes,details_json")));
    }

    @Test
    void exportCsv_asAdmin_withRows_writesCsvLines() throws Exception {
        when(auditLogService.exportCsvStream(any())).thenReturn(Stream.of(sampleRow()));

        String body = mvc.perform(get("/api/v1/admin/audit-log/export")
                .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // Header row present
        org.junit.jupiter.api.Assertions.assertTrue(
                body.contains("timestamp,action,admin_email,target_type,target_id,notes,details_json"),
                "CSV header row missing");
        // Data row contains enum name
        org.junit.jupiter.api.Assertions.assertTrue(
                body.contains("PROMOTE_USER"),
                "CSV data row missing enum value");
        // Notes with no special chars appear unquoted
        org.junit.jupiter.api.Assertions.assertTrue(
                body.contains("promoted test user"),
                "CSV data row missing notes");
    }

    @Test
    void exportCsv_asAdmin_notesWithComma_quotesField() throws Exception {
        AdminAuditLogRow rowWithComma = new AdminAuditLogRow(
                2L,
                OffsetDateTime.now(),
                AdminActionType.PROMOTE_USER,
                1L,
                "admin@x.com",
                AdminActionTargetType.USER,
                99L,
                "note, with comma",
                null);
        when(auditLogService.exportCsvStream(any())).thenReturn(Stream.of(rowWithComma));

        String body = mvc.perform(get("/api/v1/admin/audit-log/export")
                .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        org.junit.jupiter.api.Assertions.assertTrue(
                body.contains("\"note, with comma\""),
                "CSV field with comma should be quoted");
    }
}
