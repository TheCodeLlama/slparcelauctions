package com.slparcelauctions.backend.admin.reports;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

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

import com.slparcelauctions.backend.admin.reports.dto.AdminReportActionRequest;
import com.slparcelauctions.backend.admin.reports.dto.AdminReportDetailDto;
import com.slparcelauctions.backend.admin.reports.dto.AdminReportListingRowDto;
import com.slparcelauctions.backend.admin.reports.exception.ReportNotFoundException;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.auth.JwtService;
import com.slparcelauctions.backend.common.PagedResponse;
import com.slparcelauctions.backend.user.Role;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

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
    "slpa.notifications.sl-im.cleanup.enabled=false"
})
class AdminReportControllerSliceTest {

    private static final UUID ADMIN_UUID = UUID.fromString("00000000-0000-aaaa-0006-000000000001");
    private static final UUID USER_UUID  = UUID.fromString("00000000-0000-aaaa-0006-000000000002");

    @Autowired MockMvc mvc;
    @Autowired JwtService jwtService;
    @Autowired UserRepository userRepository;

    @MockitoBean AdminReportService service;

    private Long adminDbId;
    private Long userDbId;

    @BeforeEach
    void seedUsers() {
        adminDbId = userRepository.findByPublicId(ADMIN_UUID)
            .orElseGet(() -> userRepository.save(User.builder()
                .publicId(ADMIN_UUID).email("admin-report-ctrl@x.com")
                .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                .displayName("Admin").role(Role.ADMIN).verified(true).build()))
            .getId();
        userDbId = userRepository.findByPublicId(USER_UUID)
            .orElseGet(() -> userRepository.save(User.builder()
                .publicId(USER_UUID).email("user-report-ctrl@x.com")
                .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                .displayName("User").role(Role.USER).verified(true).build()))
            .getId();
    }

    private String adminToken() {
        return jwtService.issueAccessToken(new AuthPrincipal(adminDbId, ADMIN_UUID, "admin-report-ctrl@x.com", 1L, Role.ADMIN));
    }

    private String userToken() {
        return jwtService.issueAccessToken(new AuthPrincipal(userDbId, USER_UUID, "user-report-ctrl@x.com", 1L, Role.USER));
    }

    private static final String VALID_NOTES = "{\"notes\":\"Some admin notes\"}";

    // -------------------------------------------------------------------------
    // Auth gate — GET /api/v1/admin/reports
    // -------------------------------------------------------------------------

    @Test
    void list_anonymous_returns401() throws Exception {
        mvc.perform(get("/api/v1/admin/reports"))
           .andExpect(status().isUnauthorized());
    }

    @Test
    void list_userRole_returns403() throws Exception {
        mvc.perform(get("/api/v1/admin/reports")
            .header("Authorization", "Bearer " + userToken()))
           .andExpect(status().isForbidden());
    }

    @Test
    void list_admin_returns200_withPagedShape() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        AdminReportListingRowDto row = new AdminReportListingRowDto(
            10L, "Test Auction", AuctionStatus.ACTIVE, "RegionOne",
            5L, "SellerName", 3L, now);
        PagedResponse<AdminReportListingRowDto> response =
            new PagedResponse<>(List.of(row), 1L, 1, 0, 25);
        when(service.listGrouped(any(), any())).thenReturn(response);

        mvc.perform(get("/api/v1/admin/reports")
            .header("Authorization", "Bearer " + adminToken()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.content[0].auctionId").value(10))
           .andExpect(jsonPath("$.content[0].auctionTitle").value("Test Auction"))
           .andExpect(jsonPath("$.content[0].openReportCount").value(3))
           .andExpect(jsonPath("$.totalElements").value(1));
    }

    // -------------------------------------------------------------------------
    // Auth gate — GET /api/v1/admin/reports/{id}
    // -------------------------------------------------------------------------

    @Test
    void findOne_anonymous_returns401() throws Exception {
        mvc.perform(get("/api/v1/admin/reports/1"))
           .andExpect(status().isUnauthorized());
    }

    @Test
    void findOne_userRole_returns403() throws Exception {
        mvc.perform(get("/api/v1/admin/reports/1")
            .header("Authorization", "Bearer " + userToken()))
           .andExpect(status().isForbidden());
    }

    @Test
    void findOne_notFound_returns404_REPORT_NOT_FOUND() throws Exception {
        when(service.findOne(anyLong())).thenThrow(new ReportNotFoundException(999L));

        mvc.perform(get("/api/v1/admin/reports/999")
            .header("Authorization", "Bearer " + adminToken()))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.code").value("REPORT_NOT_FOUND"));
    }

    @Test
    void findOne_admin_returns200() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        AdminReportDetailDto detail = new AdminReportDetailDto(
            42L, ListingReportReason.SHILL_BIDDING, "Subject", "Details",
            ListingReportStatus.OPEN, null, now, now, null,
            7L, "ReporterName", 0L, null);
        when(service.findOne(42L)).thenReturn(detail);

        mvc.perform(get("/api/v1/admin/reports/42")
            .header("Authorization", "Bearer " + adminToken()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.id").value(42))
           .andExpect(jsonPath("$.reason").value("SHILL_BIDDING"))
           .andExpect(jsonPath("$.status").value("OPEN"));
    }

    // -------------------------------------------------------------------------
    // Auth gate — GET /api/v1/admin/reports/listing/{auctionId}
    // -------------------------------------------------------------------------

    @Test
    void findByListing_anonymous_returns401() throws Exception {
        mvc.perform(get("/api/v1/admin/reports/listing/1"))
           .andExpect(status().isUnauthorized());
    }

    @Test
    void findByListing_userRole_returns403() throws Exception {
        mvc.perform(get("/api/v1/admin/reports/listing/1")
            .header("Authorization", "Bearer " + userToken()))
           .andExpect(status().isForbidden());
    }

    // -------------------------------------------------------------------------
    // POST /{id}/dismiss — auth gate + validation + not found
    // -------------------------------------------------------------------------

    @Test
    void dismiss_anonymous_returns401() throws Exception {
        mvc.perform(post("/api/v1/admin/reports/1/dismiss")
            .contentType(MediaType.APPLICATION_JSON)
            .content(VALID_NOTES))
           .andExpect(status().isUnauthorized());
    }

    @Test
    void dismiss_userRole_returns403() throws Exception {
        mvc.perform(post("/api/v1/admin/reports/1/dismiss")
            .header("Authorization", "Bearer " + userToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content(VALID_NOTES))
           .andExpect(status().isForbidden());
    }

    @Test
    void dismiss_emptyNotes_returns400_VALIDATION_FAILED() throws Exception {
        mvc.perform(post("/api/v1/admin/reports/1/dismiss")
            .header("Authorization", "Bearer " + adminToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"notes\":\"\"}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void dismiss_reportNotFound_returns404_REPORT_NOT_FOUND() throws Exception {
        when(service.dismiss(anyLong(), anyLong(), anyString()))
            .thenThrow(new ReportNotFoundException(999L));

        mvc.perform(post("/api/v1/admin/reports/999/dismiss")
            .header("Authorization", "Bearer " + adminToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content(VALID_NOTES))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.code").value("REPORT_NOT_FOUND"));
    }

    // -------------------------------------------------------------------------
    // POST /listing/{auctionId}/warn-seller — auth gate + validation
    // -------------------------------------------------------------------------

    @Test
    void warnSeller_anonymous_returns401() throws Exception {
        mvc.perform(post("/api/v1/admin/reports/listing/1/warn-seller")
            .contentType(MediaType.APPLICATION_JSON)
            .content(VALID_NOTES))
           .andExpect(status().isUnauthorized());
    }

    @Test
    void warnSeller_userRole_returns403() throws Exception {
        mvc.perform(post("/api/v1/admin/reports/listing/1/warn-seller")
            .header("Authorization", "Bearer " + userToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content(VALID_NOTES))
           .andExpect(status().isForbidden());
    }

    @Test
    void warnSeller_emptyNotes_returns400() throws Exception {
        mvc.perform(post("/api/v1/admin/reports/listing/1/warn-seller")
            .header("Authorization", "Bearer " + adminToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"notes\":\"\"}"))
           .andExpect(status().isBadRequest());
    }

    @Test
    void warnSeller_admin_returns200() throws Exception {
        doNothing().when(service).warnSeller(anyLong(), anyLong(), anyString());

        mvc.perform(post("/api/v1/admin/reports/listing/1/warn-seller")
            .header("Authorization", "Bearer " + adminToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content(VALID_NOTES))
           .andExpect(status().isOk());
    }

    // -------------------------------------------------------------------------
    // POST /listing/{auctionId}/suspend — auth gate
    // -------------------------------------------------------------------------

    @Test
    void suspend_anonymous_returns401() throws Exception {
        mvc.perform(post("/api/v1/admin/reports/listing/1/suspend")
            .contentType(MediaType.APPLICATION_JSON)
            .content(VALID_NOTES))
           .andExpect(status().isUnauthorized());
    }

    @Test
    void suspend_userRole_returns403() throws Exception {
        mvc.perform(post("/api/v1/admin/reports/listing/1/suspend")
            .header("Authorization", "Bearer " + userToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content(VALID_NOTES))
           .andExpect(status().isForbidden());
    }

    // -------------------------------------------------------------------------
    // POST /listing/{auctionId}/cancel — auth gate
    // -------------------------------------------------------------------------

    @Test
    void cancel_anonymous_returns401() throws Exception {
        mvc.perform(post("/api/v1/admin/reports/listing/1/cancel")
            .contentType(MediaType.APPLICATION_JSON)
            .content(VALID_NOTES))
           .andExpect(status().isUnauthorized());
    }

    @Test
    void cancel_userRole_returns403() throws Exception {
        mvc.perform(post("/api/v1/admin/reports/listing/1/cancel")
            .header("Authorization", "Bearer " + userToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content(VALID_NOTES))
           .andExpect(status().isForbidden());
    }
}
