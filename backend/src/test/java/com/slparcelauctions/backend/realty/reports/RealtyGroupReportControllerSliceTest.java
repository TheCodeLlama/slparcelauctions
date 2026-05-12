package com.slparcelauctions.backend.realty.reports;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.auth.JwtService;
import com.slparcelauctions.backend.common.BaseEntity;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.reports.exception.AlreadyReportedException;
import com.slparcelauctions.backend.realty.reports.exception.CannotReportOwnGroupException;
import com.slparcelauctions.backend.realty.reports.exception.ReportRateLimitedException;
import com.slparcelauctions.backend.user.Role;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Slice-style coverage for {@link RealtyGroupReportController}.
 *
 * <p>Mirrors the {@code @SpringBootTest + @AutoConfigureMockMvc} convention used by
 * the other realty controllers (Task 10 / Task 14) so the real JWT auth chain runs
 * end-to-end — this gives authentic 401 behaviour without manual security stubbing.
 * The {@link RealtyGroupReportService} is replaced with a Mockito bean; every test
 * case stubs the relevant return value or thrown exception.
 *
 * <p>Sub-project F spec §6.1, §12.1. Test surface matches Task 17:
 * <ul>
 *   <li>{@code postReport_happyPath_returns201}</li>
 *   <li>{@code postReport_unauthenticated_returns401}</li>
 *   <li>{@code postReport_alreadyReported_returns409AlreadyReported}</li>
 *   <li>{@code postReport_ownGroup_returns409CannotReportOwn}</li>
 *   <li>{@code postReport_rateLimited_returns429}</li>
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
class RealtyGroupReportControllerSliceTest {

    private static final UUID REPORTER_UUID = UUID.fromString("00000000-0000-cccc-0017-000000000001");
    private static final UUID GROUP_PUBLIC_ID = UUID.fromString("00000000-0000-cccc-0017-000000000100");
    private static final UUID REPORT_PUBLIC_ID = UUID.fromString("00000000-0000-cccc-0017-000000000200");

    @Autowired MockMvc mvc;
    @Autowired JwtService jwtService;
    @Autowired UserRepository userRepository;

    @MockitoBean RealtyGroupReportService reportService;

    private Long reporterDbId;

    @BeforeEach
    void seedReporter() {
        reporterDbId = userRepository.findByPublicId(REPORTER_UUID)
            .orElseGet(() -> userRepository.save(User.builder()
                .publicId(REPORTER_UUID).email("reporter-rgrpt-ctrl@x.com").username("reporter-rgrpt-ctrl")
                .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                .displayName("Reporter").role(Role.USER).verified(true).build()))
            .getId();
    }

    private String reporterToken() {
        return jwtService.issueAccessToken(new AuthPrincipal(
            reporterDbId, REPORTER_UUID, "reporter-rgrpt-ctrl@x.com", 1L, Role.USER));
    }

    // ─────────────────────── POST /api/v1/realty-groups/{publicId}/reports ───────────────────────

    @Test
    void postReport_happyPath_returns201() throws Exception {
        RealtyGroupReport saved = buildReport(REPORT_PUBLIC_ID, GROUP_PUBLIC_ID,
            RealtyGroupReportReason.FRAUDULENT_LISTINGS, RealtyGroupReportStatus.OPEN);
        when(reportService.submit(
                eq(GROUP_PUBLIC_ID),
                anyLong(),
                eq(RealtyGroupReportReason.FRAUDULENT_LISTINGS),
                any(String.class)))
            .thenReturn(saved);

        mvc.perform(post("/api/v1/realty-groups/" + GROUP_PUBLIC_ID + "/reports")
                .header("Authorization", "Bearer " + reporterToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "reason": "FRAUDULENT_LISTINGS",
                      "details": "Multiple parcels listed that the group does not own."
                    }
                    """))
           .andExpect(status().isCreated())
           .andExpect(jsonPath("$.publicId").value(REPORT_PUBLIC_ID.toString()))
           .andExpect(jsonPath("$.groupPublicId").value(GROUP_PUBLIC_ID.toString()))
           .andExpect(jsonPath("$.reason").value("FRAUDULENT_LISTINGS"))
           .andExpect(jsonPath("$.status").value("OPEN"))
           .andExpect(jsonPath("$.createdAt").exists());
    }

    @Test
    void postReport_unauthenticated_returns401() throws Exception {
        // No Authorization header — the JWT filter rejects before the controller runs.
        // POST falls through SecurityConfig's permitAll GET matchers to the
        // /api/v1/** authenticated catch-all (see SecurityConfig §realty-groups).
        mvc.perform(post("/api/v1/realty-groups/" + GROUP_PUBLIC_ID + "/reports")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "reason": "SPAM",
                      "details": "Group is spamming the platform."
                    }
                    """))
           .andExpect(status().isUnauthorized());
    }

    @Test
    void postReport_alreadyReported_returns409AlreadyReported() throws Exception {
        when(reportService.submit(eq(GROUP_PUBLIC_ID), anyLong(), any(), any(String.class)))
            .thenThrow(new AlreadyReportedException(GROUP_PUBLIC_ID));

        mvc.perform(post("/api/v1/realty-groups/" + GROUP_PUBLIC_ID + "/reports")
                .header("Authorization", "Bearer " + reporterToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "reason": "HARASSMENT",
                      "details": "Duplicate submission past the in-service pre-check."
                    }
                    """))
           .andExpect(status().isConflict())
           .andExpect(jsonPath("$.code").value("ALREADY_REPORTED"))
           .andExpect(jsonPath("$.groupPublicId").value(GROUP_PUBLIC_ID.toString()));
    }

    @Test
    void postReport_ownGroup_returns409CannotReportOwn() throws Exception {
        when(reportService.submit(eq(GROUP_PUBLIC_ID), anyLong(), any(), any(String.class)))
            .thenThrow(new CannotReportOwnGroupException(GROUP_PUBLIC_ID));

        mvc.perform(post("/api/v1/realty-groups/" + GROUP_PUBLIC_ID + "/reports")
                .header("Authorization", "Bearer " + reporterToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "reason": "OTHER",
                      "details": "Reporter is a member of this group."
                    }
                    """))
           .andExpect(status().isConflict())
           .andExpect(jsonPath("$.code").value("CANNOT_REPORT_OWN_GROUP"))
           .andExpect(jsonPath("$.groupPublicId").value(GROUP_PUBLIC_ID.toString()));
    }

    @Test
    void postReport_rateLimited_returns429() throws Exception {
        when(reportService.submit(eq(GROUP_PUBLIC_ID), anyLong(), any(), any(String.class)))
            .thenThrow(new ReportRateLimitedException(5));

        mvc.perform(post("/api/v1/realty-groups/" + GROUP_PUBLIC_ID + "/reports")
                .header("Authorization", "Bearer " + reporterToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "reason": "SPAM",
                      "details": "Quota burst from the same reporter."
                    }
                    """))
           .andExpect(status().isTooManyRequests())
           .andExpect(header().exists("Retry-After"))
           // Retry-After is delta-seconds; the next UTC-midnight reset is at most
           // 86400s away (one full day) and strictly positive.
           .andExpect(header().string("Retry-After",
               Matchers.matchesPattern("^[1-9][0-9]{0,4}$")))
           .andExpect(jsonPath("$.code").value("REPORT_RATE_LIMITED"))
           .andExpect(jsonPath("$.dailyLimit").value(5))
           .andExpect(jsonPath("$.retryAfterSeconds").isNumber());
    }

    // ─────────────────────── helpers ───────────────────────

    /**
     * Build a fully-populated {@link RealtyGroupReport} with deterministic
     * {@code publicId} so the wire-shape assertions can pin exact values. The
     * embedded {@link RealtyGroup} is similarly stamped so
     * {@code ReportDto.from(...)} sees the expected {@code groupPublicId}.
     */
    private RealtyGroupReport buildReport(
            UUID publicId,
            UUID groupPublicId,
            RealtyGroupReportReason reason,
            RealtyGroupReportStatus statusValue) {
        RealtyGroup group = RealtyGroup.builder()
            .name("Sample Group").slug("sample-group-" + UUID.randomUUID())
            .leaderId(reporterDbId).build();
        setPublicId(group, groupPublicId);

        User reporter = userRepository.findByPublicId(REPORTER_UUID).orElseThrow();
        RealtyGroupReport row = RealtyGroupReport.builder()
            .realtyGroup(group)
            .reporter(reporter)
            .reason(reason)
            .details("Sample report details that satisfy the 10-character minimum.")
            .status(statusValue)
            .build();
        setPublicId(row, publicId);
        setCreatedAt(row, OffsetDateTime.of(2026, 5, 12, 10, 0, 0, 0, ZoneOffset.UTC));
        return row;
    }

    /**
     * BaseEntity.publicId has no public setter — reflection is the simplest way
     * to pin a stable id for assertions. Used only in tests.
     */
    private static void setPublicId(BaseEntity entity, UUID publicId) {
        try {
            Field f = BaseEntity.class.getDeclaredField("publicId");
            f.setAccessible(true);
            f.set(entity, publicId);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new IllegalStateException("Failed to set publicId via reflection", e);
        }
    }

    /**
     * {@code BaseEntity.createdAt} is normally populated by Hibernate's
     * {@code @CreationTimestamp} on flush. For unflushed test fixtures we
     * pin it via reflection so the response DTO's {@code createdAt} is non-null
     * (Jackson would otherwise serialize it as missing, failing
     * {@code jsonPath("$.createdAt").exists()}).
     */
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
