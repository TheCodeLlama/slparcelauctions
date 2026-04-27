package com.slparcelauctions.backend.admin.reports;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.slparcelauctions.backend.admin.reports.dto.MyReportResponse;
import com.slparcelauctions.backend.admin.reports.exception.AuctionNotReportableException;
import com.slparcelauctions.backend.admin.reports.exception.CannotReportOwnListingException;
import com.slparcelauctions.backend.admin.reports.exception.MustBeVerifiedToReportException;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.auth.JwtService;
import com.slparcelauctions.backend.user.Role;

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
class UserReportControllerSliceTest {

    @Autowired MockMvc mvc;
    @Autowired JwtService jwtService;

    @MockitoBean UserReportService service;

    private String userToken() {
        return jwtService.issueAccessToken(new AuthPrincipal(42L, "u@x.com", 1L, Role.USER));
    }

    private static final String VALID_BODY = """
            {"subject":"Bad listing","reason":"INACCURATE_DESCRIPTION","details":"Details here."}
            """;

    // -------------------------------------------------------------------------
    // Auth gate
    // -------------------------------------------------------------------------

    @Test
    void report_anonymous_returns401() throws Exception {
        mvc.perform(post("/api/v1/auctions/1/report")
            .contentType(MediaType.APPLICATION_JSON)
            .content(VALID_BODY))
           .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------------
    // Bean Validation
    // -------------------------------------------------------------------------

    @Test
    void report_emptySubject_returns400() throws Exception {
        mvc.perform(post("/api/v1/auctions/1/report")
            .header("Authorization", "Bearer " + userToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"subject\":\"\",\"reason\":\"OTHER\",\"details\":\"some details\"}"))
           .andExpect(status().isBadRequest());
    }

    @Test
    void report_blankDetails_returns400() throws Exception {
        mvc.perform(post("/api/v1/auctions/1/report")
            .header("Authorization", "Bearer " + userToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"subject\":\"A subject\",\"reason\":\"OTHER\",\"details\":\"   \"}"))
           .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------------------
    // Happy path
    // -------------------------------------------------------------------------

    @Test
    void report_validInput_returns200_withMyReportResponse() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        MyReportResponse resp = new MyReportResponse(
            99L, "Bad listing", ListingReportReason.INACCURATE_DESCRIPTION,
            "Details here.", ListingReportStatus.OPEN, now, now);
        when(service.upsertReport(eq(1L), eq(42L), any())).thenReturn(resp);

        mvc.perform(post("/api/v1/auctions/1/report")
            .header("Authorization", "Bearer " + userToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content(VALID_BODY))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.id").value(99))
           .andExpect(jsonPath("$.subject").value("Bad listing"))
           .andExpect(jsonPath("$.reason").value("INACCURATE_DESCRIPTION"))
           .andExpect(jsonPath("$.status").value("OPEN"));
    }

    // -------------------------------------------------------------------------
    // Exception handler wiring
    // -------------------------------------------------------------------------

    @Test
    void report_unverifiedReporter_returns403_VERIFICATION_REQUIRED() throws Exception {
        when(service.upsertReport(any(), any(), any()))
            .thenThrow(new MustBeVerifiedToReportException());

        mvc.perform(post("/api/v1/auctions/1/report")
            .header("Authorization", "Bearer " + userToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content(VALID_BODY))
           .andExpect(status().isForbidden())
           .andExpect(jsonPath("$.code").value("VERIFICATION_REQUIRED"));
    }

    @Test
    void report_ownListing_returns409_CANNOT_REPORT_OWN_LISTING() throws Exception {
        when(service.upsertReport(any(), any(), any()))
            .thenThrow(new CannotReportOwnListingException());

        mvc.perform(post("/api/v1/auctions/1/report")
            .header("Authorization", "Bearer " + userToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content(VALID_BODY))
           .andExpect(status().isConflict())
           .andExpect(jsonPath("$.code").value("CANNOT_REPORT_OWN_LISTING"));
    }

    @Test
    void report_nonActiveAuction_returns409_AUCTION_NOT_REPORTABLE() throws Exception {
        when(service.upsertReport(any(), any(), any()))
            .thenThrow(new AuctionNotReportableException(AuctionStatus.CANCELLED));

        mvc.perform(post("/api/v1/auctions/1/report")
            .header("Authorization", "Bearer " + userToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content(VALID_BODY))
           .andExpect(status().isConflict())
           .andExpect(jsonPath("$.code").value("AUCTION_NOT_REPORTABLE"))
           .andExpect(jsonPath("$.currentStatus").value("CANCELLED"));
    }

    // -------------------------------------------------------------------------
    // GET /my-report
    // -------------------------------------------------------------------------

    @Test
    void myReport_noRow_returns204() throws Exception {
        when(service.findMyReport(eq(1L), eq(42L))).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/auctions/1/my-report")
            .header("Authorization", "Bearer " + userToken()))
           .andExpect(status().isNoContent());
    }

    @Test
    void myReport_withRow_returns200() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        MyReportResponse resp = new MyReportResponse(
            7L, "Subject", ListingReportReason.TOS_VIOLATION,
            "Details.", ListingReportStatus.REVIEWED, now, now);
        when(service.findMyReport(eq(1L), eq(42L))).thenReturn(Optional.of(resp));

        mvc.perform(get("/api/v1/auctions/1/my-report")
            .header("Authorization", "Bearer " + userToken()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.id").value(7))
           .andExpect(jsonPath("$.reason").value("TOS_VIOLATION"))
           .andExpect(jsonPath("$.status").value("REVIEWED"));
    }
}
