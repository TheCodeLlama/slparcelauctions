package com.slparcelauctions.backend.admin.reports;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.Optional;
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

import com.slparcelauctions.backend.admin.reports.dto.MyReportResponse;
import com.slparcelauctions.backend.admin.reports.exception.AuctionNotReportableException;
import com.slparcelauctions.backend.admin.reports.exception.CannotReportOwnListingException;
import com.slparcelauctions.backend.admin.reports.exception.MustBeVerifiedToReportException;
import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.auth.JwtService;
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
class UserReportControllerSliceTest {

    private static final UUID USER_UUID = UUID.fromString("00000000-0000-aaaa-0007-000000000001");
    private static final UUID AUCTION_PUBLIC_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final long AUCTION_LONG_ID = 1L;

    @Autowired MockMvc mvc;
    @Autowired JwtService jwtService;
    @Autowired UserRepository userRepository;

    @MockitoBean UserReportService service;
    @MockitoBean AuctionRepository auctionRepository;

    private Long userDbId;

    @BeforeEach
    void seedUsers() {
        userDbId = userRepository.findByPublicId(USER_UUID)
            .orElseGet(() -> userRepository.save(User.builder()
                .publicId(USER_UUID).email("user-report-user@x.com")
                .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                .displayName("User").role(Role.USER).verified(true).build()))
            .getId();
    }

    private String userToken() {
        return jwtService.issueAccessToken(new AuthPrincipal(userDbId, USER_UUID, "user-report-user@x.com", 1L, Role.USER));
    }

    private void stubAuctionLookup() {
        Auction mockAuction = org.mockito.Mockito.mock(Auction.class);
        when(mockAuction.getId()).thenReturn(AUCTION_LONG_ID);
        when(auctionRepository.findByPublicId(AUCTION_PUBLIC_ID))
                .thenReturn(Optional.of(mockAuction));
    }

    private static final String VALID_BODY = """
            {"subject":"Bad listing","reason":"INACCURATE_DESCRIPTION","details":"Details here."}
            """;

    // -------------------------------------------------------------------------
    // Auth gate
    // -------------------------------------------------------------------------

    @Test
    void report_anonymous_returns401() throws Exception {
        mvc.perform(post("/api/v1/auctions/" + AUCTION_PUBLIC_ID + "/report")
            .contentType(MediaType.APPLICATION_JSON)
            .content(VALID_BODY))
           .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------------
    // Bean Validation
    // -------------------------------------------------------------------------

    @Test
    void report_emptySubject_returns400() throws Exception {
        stubAuctionLookup();
        mvc.perform(post("/api/v1/auctions/" + AUCTION_PUBLIC_ID + "/report")
            .header("Authorization", "Bearer " + userToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"subject\":\"\",\"reason\":\"OTHER\",\"details\":\"some details\"}"))
           .andExpect(status().isBadRequest());
    }

    @Test
    void report_blankDetails_returns400() throws Exception {
        stubAuctionLookup();
        mvc.perform(post("/api/v1/auctions/" + AUCTION_PUBLIC_ID + "/report")
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
        stubAuctionLookup();
        OffsetDateTime now = OffsetDateTime.now();
        UUID reportPublicId = UUID.fromString("00000000-0000-0000-0000-000000000099");
        MyReportResponse resp = new MyReportResponse(
            reportPublicId, "Bad listing", ListingReportReason.INACCURATE_DESCRIPTION,
            "Details here.", ListingReportStatus.OPEN, now, now);
        when(service.upsertReport(eq(AUCTION_LONG_ID), anyLong(), any())).thenReturn(resp);

        mvc.perform(post("/api/v1/auctions/" + AUCTION_PUBLIC_ID + "/report")
            .header("Authorization", "Bearer " + userToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content(VALID_BODY))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.publicId").value(reportPublicId.toString()))
           .andExpect(jsonPath("$.subject").value("Bad listing"))
           .andExpect(jsonPath("$.reason").value("INACCURATE_DESCRIPTION"))
           .andExpect(jsonPath("$.status").value("OPEN"));
    }

    // -------------------------------------------------------------------------
    // Exception handler wiring
    // -------------------------------------------------------------------------

    @Test
    void report_unverifiedReporter_returns403_VERIFICATION_REQUIRED() throws Exception {
        stubAuctionLookup();
        when(service.upsertReport(any(), any(), any()))
            .thenThrow(new MustBeVerifiedToReportException());

        mvc.perform(post("/api/v1/auctions/" + AUCTION_PUBLIC_ID + "/report")
            .header("Authorization", "Bearer " + userToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content(VALID_BODY))
           .andExpect(status().isForbidden())
           .andExpect(jsonPath("$.code").value("VERIFICATION_REQUIRED"));
    }

    @Test
    void report_ownListing_returns409_CANNOT_REPORT_OWN_LISTING() throws Exception {
        stubAuctionLookup();
        when(service.upsertReport(any(), any(), any()))
            .thenThrow(new CannotReportOwnListingException());

        mvc.perform(post("/api/v1/auctions/" + AUCTION_PUBLIC_ID + "/report")
            .header("Authorization", "Bearer " + userToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content(VALID_BODY))
           .andExpect(status().isConflict())
           .andExpect(jsonPath("$.code").value("CANNOT_REPORT_OWN_LISTING"));
    }

    @Test
    void report_nonActiveAuction_returns409_AUCTION_NOT_REPORTABLE() throws Exception {
        stubAuctionLookup();
        when(service.upsertReport(any(), any(), any()))
            .thenThrow(new AuctionNotReportableException(AuctionStatus.CANCELLED));

        mvc.perform(post("/api/v1/auctions/" + AUCTION_PUBLIC_ID + "/report")
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
        stubAuctionLookup();
        when(service.findMyReport(eq(AUCTION_LONG_ID), anyLong())).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/auctions/" + AUCTION_PUBLIC_ID + "/my-report")
            .header("Authorization", "Bearer " + userToken()))
           .andExpect(status().isNoContent());
    }

    @Test
    void myReport_withRow_returns200() throws Exception {
        stubAuctionLookup();
        OffsetDateTime now = OffsetDateTime.now();
        UUID myReportPublicId = UUID.fromString("00000000-0000-0000-0000-000000000007");
        MyReportResponse resp = new MyReportResponse(
            myReportPublicId, "Subject", ListingReportReason.TOS_VIOLATION,
            "Details.", ListingReportStatus.REVIEWED, now, now);
        when(service.findMyReport(eq(AUCTION_LONG_ID), anyLong())).thenReturn(Optional.of(resp));

        mvc.perform(get("/api/v1/auctions/" + AUCTION_PUBLIC_ID + "/my-report")
            .header("Authorization", "Bearer " + userToken()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.publicId").value(myReportPublicId.toString()))
           .andExpect(jsonPath("$.reason").value("TOS_VIOLATION"))
           .andExpect(jsonPath("$.status").value("REVIEWED"));
    }
}
