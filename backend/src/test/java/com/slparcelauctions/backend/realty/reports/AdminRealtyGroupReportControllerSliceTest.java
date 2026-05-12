package com.slparcelauctions.backend.realty.reports;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.auth.JwtService;
import com.slparcelauctions.backend.common.BaseEntity;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.moderation.RealtyGroupSuspensionService;
import com.slparcelauctions.backend.user.Role;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Slice-style coverage for {@link AdminRealtyGroupReportController}.
 *
 * <p>Mirrors the moderation-package slice-test convention
 * ({@code @SpringBootTest + @AutoConfigureMockMvc}) so the real JWT auth chain
 * runs end-to-end. {@link RealtyGroupReportService} is replaced with a Mockito
 * bean — every test case stubs the relevant behaviour or asserts the call
 * happened. {@link ReportDtoMapper} is the real bean so the wire-shape contract
 * is exercised.
 *
 * <p>{@link RealtyGroupSuspensionService} is also wired in as a Mockito bean — not
 * because the controller calls it (it does not), but so the
 * {@code postResolve_withEscalateToSuspendGroup_doesNotItselfSuspend} test case
 * can {@code verifyNoInteractions} on it and prove the controller treats the
 * {@code escalateTo} request field as informational.
 *
 * <p>Sub-project F spec §6.5, §12.2, §12.3. Test surface matches Task 18:
 * <ul>
 *   <li>{@code getList_paginated_returnsRows}</li>
 *   <li>{@code getList_filterByStatus_appliesFilter}</li>
 *   <li>{@code getDetail_returnsFullReport}</li>
 *   <li>{@code postResolve_setsResolved}</li>
 *   <li>{@code postDismiss_incrementsDismissedReportsCount}</li>
 *   <li>{@code postResolve_withEscalateToSuspendGroup_doesNotItselfSuspend}</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = {
    "auth.cleanup.enabled=false",
    "slpa.auction-end.enabled=false",
    "slpa.ownership-monitor.enabled=false",
    "slpa.escrow.ownership-monitor-job.enabled=false",
    "slpa.escrow.timeout-job.enabled=false",
    "slpa.escrow.command-dispatcher-job.enabled=false",
    "slpa.review.scheduler.enabled=false",
    "slpa.notifications.cleanup.enabled=false",
    "slpa.notifications.sl-im.cleanup.enabled=false",
    "slpa.realty.invitation-expiry.enabled=false",
    "slpa.realty.group-suspension-expiry.enabled=false",
    "slpa.realty.group-bulk-suspend.enabled=false",
    "slpa.realty.sl-group.reverify.enabled=false"
})
class AdminRealtyGroupReportControllerSliceTest {

    private static final UUID ADMIN_UUID = UUID.fromString("00000000-0000-cccc-0018-000000000001");
    private static final UUID REPORTER_UUID = UUID.fromString("00000000-0000-cccc-0018-000000000002");
    private static final UUID GROUP_PUBLIC_ID = UUID.fromString("00000000-0000-cccc-0018-000000000100");
    private static final UUID REPORT_PUBLIC_ID = UUID.fromString("00000000-0000-cccc-0018-000000000200");
    private static final UUID OTHER_REPORT_PUBLIC_ID = UUID.fromString("00000000-0000-cccc-0018-000000000201");

    private static final OffsetDateTime CREATED_AT =
        OffsetDateTime.of(2026, 5, 12, 10, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime RESOLVED_AT =
        OffsetDateTime.of(2026, 5, 12, 11, 0, 0, 0, ZoneOffset.UTC);

    @Autowired MockMvc mvc;
    @Autowired JwtService jwtService;
    @Autowired UserRepository userRepository;

    @MockitoBean RealtyGroupReportService reportService;
    // Wired as a bean so we can verify the controller never touches it when an
    // admin resolves with escalateTo=SUSPEND_GROUP. The field is purely
    // informational — suspension lives behind a separate admin endpoint.
    @MockitoBean RealtyGroupSuspensionService suspensionService;

    private Long adminDbId;
    private User adminUser;
    private User reporterUser;

    @BeforeEach
    void seedUsers() {
        adminUser = userRepository.findByPublicId(ADMIN_UUID)
            .orElseGet(() -> userRepository.save(User.builder()
                .publicId(ADMIN_UUID).email("admin-rgrep-ctrl@x.com").username("admin-rgrep-ctrl")
                .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                .displayName("Admin Mod").role(Role.ADMIN).verified(true).build()));
        adminDbId = adminUser.getId();
        reporterUser = userRepository.findByPublicId(REPORTER_UUID)
            .orElseGet(() -> userRepository.save(User.builder()
                .publicId(REPORTER_UUID).email("reporter-rgrep-ctrl@x.com").username("reporter-rgrep-ctrl")
                .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                .displayName("The Reporter").role(Role.USER).verified(true).build()));
    }

    private String adminToken() {
        return jwtService.issueAccessToken(new AuthPrincipal(
            adminDbId, ADMIN_UUID, "admin-rgrep-ctrl@x.com", 1L, Role.ADMIN));
    }

    // ─────────────────────── GET /api/v1/admin/realty-groups/reports ───────────────────────

    @Test
    void getList_paginated_returnsRows() throws Exception {
        RealtyGroupReport row1 = buildReport(REPORT_PUBLIC_ID,
            RealtyGroupReportReason.FRAUDULENT_LISTINGS, RealtyGroupReportStatus.OPEN);
        RealtyGroupReport row2 = buildReport(OTHER_REPORT_PUBLIC_ID,
            RealtyGroupReportReason.SPAM, RealtyGroupReportStatus.RESOLVED);
        Page<RealtyGroupReport> page = new PageImpl<>(
            List.of(row1, row2),
            PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt")),
            2);
        when(reportService.findAll(eq((RealtyGroupReportStatus) null), any(Pageable.class)))
            .thenReturn(page);

        mvc.perform(get("/api/v1/admin/realty-groups/reports")
                .header("Authorization", "Bearer " + adminToken()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.content.length()").value(2))
           .andExpect(jsonPath("$.totalElements").value(2))
           .andExpect(jsonPath("$.content[0].publicId").value(REPORT_PUBLIC_ID.toString()))
           .andExpect(jsonPath("$.content[0].groupPublicId").value(GROUP_PUBLIC_ID.toString()))
           .andExpect(jsonPath("$.content[0].groupName").value("Sample Group"))
           .andExpect(jsonPath("$.content[0].reason").value("FRAUDULENT_LISTINGS"))
           .andExpect(jsonPath("$.content[0].status").value("OPEN"))
           .andExpect(jsonPath("$.content[0].reporter.publicId").value(REPORTER_UUID.toString()))
           .andExpect(jsonPath("$.content[0].reporter.displayName").value("The Reporter"))
           .andExpect(jsonPath("$.content[1].publicId").value(OTHER_REPORT_PUBLIC_ID.toString()))
           .andExpect(jsonPath("$.content[1].status").value("RESOLVED"));
    }

    @Test
    void getList_filterByStatus_appliesFilter() throws Exception {
        RealtyGroupReport open = buildReport(REPORT_PUBLIC_ID,
            RealtyGroupReportReason.HARASSMENT, RealtyGroupReportStatus.OPEN);
        Page<RealtyGroupReport> page = new PageImpl<>(
            List.of(open),
            PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt")),
            1);
        when(reportService.findAll(eq(RealtyGroupReportStatus.OPEN), any(Pageable.class)))
            .thenReturn(page);

        mvc.perform(get("/api/v1/admin/realty-groups/reports")
                .param("status", "OPEN")
                .header("Authorization", "Bearer " + adminToken()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.content.length()").value(1))
           .andExpect(jsonPath("$.content[0].status").value("OPEN"));

        // Controller must pass the status param through to the service. The list
        // endpoint is the only knob the admin UI has to filter by triage state.
        verify(reportService).findAll(eq(RealtyGroupReportStatus.OPEN), any(Pageable.class));
    }

    // ─────────────────────── GET /api/v1/admin/realty-groups/reports/{publicId} ───────────────────────

    @Test
    void getDetail_returnsFullReport() throws Exception {
        RealtyGroupReport report = buildReport(REPORT_PUBLIC_ID,
            RealtyGroupReportReason.IMPERSONATION, RealtyGroupReportStatus.OPEN);
        report.setDetails("This group claims to own parcels they don't.");
        when(reportService.find(REPORT_PUBLIC_ID)).thenReturn(report);

        mvc.perform(get("/api/v1/admin/realty-groups/reports/" + REPORT_PUBLIC_ID)
                .header("Authorization", "Bearer " + adminToken()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.publicId").value(REPORT_PUBLIC_ID.toString()))
           .andExpect(jsonPath("$.group.publicId").value(GROUP_PUBLIC_ID.toString()))
           .andExpect(jsonPath("$.group.name").value("Sample Group"))
           .andExpect(jsonPath("$.reporter.publicId").value(REPORTER_UUID.toString()))
           .andExpect(jsonPath("$.reporter.displayName").value("The Reporter"))
           .andExpect(jsonPath("$.reason").value("IMPERSONATION"))
           .andExpect(jsonPath("$.details").value("This group claims to own parcels they don't."))
           .andExpect(jsonPath("$.status").value("OPEN"))
           .andExpect(jsonPath("$.resolvedByAdmin").doesNotExist())
           .andExpect(jsonPath("$.resolvedAt").doesNotExist())
           .andExpect(jsonPath("$.resolutionNotes").doesNotExist());
    }

    // ─────────────────────── POST /api/v1/admin/realty-groups/reports/{publicId}/resolve ───────────────────────

    @Test
    void postResolve_setsResolved() throws Exception {
        RealtyGroupReport resolved = buildReport(REPORT_PUBLIC_ID,
            RealtyGroupReportReason.FRAUDULENT_LISTINGS, RealtyGroupReportStatus.RESOLVED);
        resolved.setResolvedByAdmin(adminUser);
        resolved.setResolvedAt(RESOLVED_AT);
        resolved.setResolutionNotes("Confirmed — issued warning + watch flag.");
        when(reportService.resolve(eq(REPORT_PUBLIC_ID), anyLong(), any()))
            .thenReturn(resolved);

        mvc.perform(post("/api/v1/admin/realty-groups/reports/" + REPORT_PUBLIC_ID + "/resolve")
                .header("Authorization", "Bearer " + adminToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "notes": "Confirmed — issued warning + watch flag.",
                      "escalateTo": null
                    }
                    """))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.publicId").value(REPORT_PUBLIC_ID.toString()))
           .andExpect(jsonPath("$.status").value("RESOLVED"))
           .andExpect(jsonPath("$.resolvedByAdmin.publicId").value(ADMIN_UUID.toString()))
           .andExpect(jsonPath("$.resolvedAt").value("2026-05-12T11:00:00Z"))
           .andExpect(jsonPath("$.resolutionNotes").value("Confirmed — issued warning + watch flag."));

        verify(reportService).resolve(
            eq(REPORT_PUBLIC_ID),
            eq(adminDbId),
            eq("Confirmed — issued warning + watch flag."));
    }

    // ─────────────────────── POST /api/v1/admin/realty-groups/reports/{publicId}/dismiss ───────────────────────

    @Test
    void postDismiss_incrementsDismissedReportsCount() throws Exception {
        // The increment itself lives inside RealtyGroupReportService.dismiss — the
        // controller's job is to invoke it. We verify the service is called with
        // the expected arguments; the service test (Task 16) covers the actual
        // counter bump.
        RealtyGroupReport dismissed = buildReport(REPORT_PUBLIC_ID,
            RealtyGroupReportReason.SPAM, RealtyGroupReportStatus.DISMISSED);
        dismissed.setResolvedByAdmin(adminUser);
        dismissed.setResolvedAt(RESOLVED_AT);
        dismissed.setResolutionNotes("Not actionable — single instance.");
        when(reportService.dismiss(eq(REPORT_PUBLIC_ID), anyLong(), any()))
            .thenReturn(dismissed);

        mvc.perform(post("/api/v1/admin/realty-groups/reports/" + REPORT_PUBLIC_ID + "/dismiss")
                .header("Authorization", "Bearer " + adminToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "notes": "Not actionable — single instance."
                    }
                    """))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.publicId").value(REPORT_PUBLIC_ID.toString()))
           .andExpect(jsonPath("$.status").value("DISMISSED"))
           .andExpect(jsonPath("$.resolutionNotes").value("Not actionable — single instance."));

        verify(reportService).dismiss(
            eq(REPORT_PUBLIC_ID),
            eq(adminDbId),
            eq("Not actionable — single instance."));
    }

    // ─────────────────────── escalateTo is informational ───────────────────────

    @Test
    void postResolve_withEscalateToSuspendGroup_doesNotItselfSuspend() throws Exception {
        RealtyGroupReport resolved = buildReport(REPORT_PUBLIC_ID,
            RealtyGroupReportReason.FRAUDULENT_LISTINGS, RealtyGroupReportStatus.RESOLVED);
        resolved.setResolvedByAdmin(adminUser);
        resolved.setResolvedAt(RESOLVED_AT);
        resolved.setResolutionNotes("Confirmed fraud — frontend will follow up with suspend modal.");
        when(reportService.resolve(eq(REPORT_PUBLIC_ID), anyLong(), any()))
            .thenReturn(resolved);

        mvc.perform(post("/api/v1/admin/realty-groups/reports/" + REPORT_PUBLIC_ID + "/resolve")
                .header("Authorization", "Bearer " + adminToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "notes": "Confirmed fraud — frontend will follow up with suspend modal.",
                      "escalateTo": "SUSPEND_GROUP"
                    }
                    """))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.status").value("RESOLVED"));

        // The controller resolves the report.
        verify(reportService).resolve(eq(REPORT_PUBLIC_ID), eq(adminDbId), any());
        // The escalateTo field is informational — the resolve endpoint never
        // itself touches the suspension service. The frontend uses the field to
        // decide whether to open a suspend modal next.
        verifyNoInteractions(suspensionService);
        verify(reportService, never()).resolve(any(), any(), eq("SUSPEND_GROUP"));
    }

    // ─────────────────────── helpers ───────────────────────

    /**
     * Build a fully-populated {@link RealtyGroupReport} entity with deterministic
     * {@code publicId} so wire-shape assertions can pin exact values. The embedded
     * {@code reporter} references the seeded reporter user so the
     * {@link com.slparcelauctions.backend.admin.dto.AdminSummaryDto} carries a real
     * {@code publicId} + {@code displayName}.
     */
    private RealtyGroupReport buildReport(
            UUID publicId,
            RealtyGroupReportReason reason,
            RealtyGroupReportStatus status) {
        RealtyGroup group = RealtyGroup.builder()
            .name("Sample Group").slug("sample-group").leaderId(adminDbId).build();
        setPublicId(group, GROUP_PUBLIC_ID);

        RealtyGroupReport row = RealtyGroupReport.builder()
            .realtyGroup(group)
            .reporter(reporterUser)
            .reason(reason)
            .details("Sample report body.")
            .status(status)
            .build();
        setPublicId(row, publicId);
        setCreatedAt(row, CREATED_AT);
        return row;
    }

    /** BaseEntity.publicId has no public setter — reflection is the simplest way. */
    private static void setPublicId(BaseEntity entity, UUID publicId) {
        try {
            Field f = BaseEntity.class.getDeclaredField("publicId");
            f.setAccessible(true);
            f.set(entity, publicId);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new IllegalStateException("Failed to set publicId via reflection", e);
        }
    }

    /** BaseEntity.createdAt has no public setter — reflection per the publicId pattern. */
    private static void setCreatedAt(BaseEntity entity, OffsetDateTime createdAt) {
        try {
            Field f = BaseEntity.class.getDeclaredField("createdAt");
            f.setAccessible(true);
            f.set(entity, createdAt);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new IllegalStateException("Failed to set createdAt via reflection", e);
        }
    }
}
