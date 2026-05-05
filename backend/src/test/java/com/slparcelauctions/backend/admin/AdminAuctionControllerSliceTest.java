package com.slparcelauctions.backend.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
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

import com.slparcelauctions.backend.admin.AdminAuctionService.AdminAuctionReinstateResult;
import com.slparcelauctions.backend.admin.audit.AdminActionService;
import com.slparcelauctions.backend.admin.audit.AdminActionTargetType;
import com.slparcelauctions.backend.admin.audit.AdminActionType;
import com.slparcelauctions.backend.admin.exception.AuctionNotSuspendedException;
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
class AdminAuctionControllerSliceTest {

    private static final UUID ADMIN_UUID   = UUID.fromString("00000000-0000-aaaa-0000-000000000001");
    private static final UUID USER_UUID    = UUID.fromString("00000000-0000-aaaa-0000-000000000002");
    private static final UUID AUCTION_UUID = UUID.fromString("00000000-0000-aaaa-0000-000000000100");
    private static final Long AUCTION_DB_ID = 100L;

    @Autowired MockMvc mvc;
    @Autowired JwtService jwtService;
    @Autowired UserRepository userRepository;

    @MockitoBean AdminAuctionService adminAuctionService;
    @MockitoBean AdminActionService adminActionService;
    @MockitoBean AuctionRepository auctionRepository;

    private Long adminDbId;
    private Long userDbId;

    @BeforeEach
    void setUp() {
        adminDbId = userRepository.findByPublicId(ADMIN_UUID)
            .orElseGet(() -> userRepository.save(User.builder()
                .publicId(ADMIN_UUID)
                .email("admin-auction-slice@x.com").username("admin-auction-slice")
                .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                .displayName("Admin")
                .role(Role.ADMIN)
                .verified(true)
                .build()))
            .getId();
        userDbId = userRepository.findByPublicId(USER_UUID)
            .orElseGet(() -> userRepository.save(User.builder()
                .publicId(USER_UUID)
                .email("user-auction-slice@x.com").username("user-auction-slice")
                .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                .displayName("User")
                .role(Role.USER)
                .verified(true)
                .build()))
            .getId();

        // Stub auction lookup used by the controller's resolveAuctionId step.
        Auction mockAuction = mock(Auction.class);
        when(mockAuction.getId()).thenReturn(AUCTION_DB_ID);
        when(auctionRepository.findByPublicId(AUCTION_UUID)).thenReturn(Optional.of(mockAuction));
    }

    private String adminToken() {
        return jwtService.issueAccessToken(new AuthPrincipal(adminDbId, ADMIN_UUID, "admin-auction-slice@x.com", 1L, Role.ADMIN));
    }

    private String userToken() {
        return jwtService.issueAccessToken(new AuthPrincipal(userDbId, USER_UUID, "user-auction-slice@x.com", 1L, Role.USER));
    }

    @Test
    void reinstate_anonymous_returns401() throws Exception {
        mvc.perform(post("/api/v1/admin/auctions/" + AUCTION_UUID + "/reinstate")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"notes\":\"some notes\"}"))
           .andExpect(status().isUnauthorized());
    }

    @Test
    void reinstate_userRole_returns403() throws Exception {
        mvc.perform(post("/api/v1/admin/auctions/" + AUCTION_UUID + "/reinstate")
            .header("Authorization", "Bearer " + userToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"notes\":\"some notes\"}"))
           .andExpect(status().isForbidden());
    }

    @Test
    void reinstate_emptyNotes_returns400() throws Exception {
        mvc.perform(post("/api/v1/admin/auctions/" + AUCTION_UUID + "/reinstate")
            .header("Authorization", "Bearer " + adminToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"notes\":\"\"}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void reinstate_notSuspended_returns409() throws Exception {
        when(adminAuctionService.reinstate(eq(AUCTION_DB_ID), any()))
            .thenThrow(new AuctionNotSuspendedException(AuctionStatus.CANCELLED));

        mvc.perform(post("/api/v1/admin/auctions/" + AUCTION_UUID + "/reinstate")
            .header("Authorization", "Bearer " + adminToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"notes\":\"verified\"}"))
           .andExpect(status().isConflict())
           .andExpect(jsonPath("$.code").value("AUCTION_NOT_SUSPENDED"))
           .andExpect(jsonPath("$.details.currentStatus").value("CANCELLED"));
    }

    @Test
    void reinstate_admin_returns200_writesAudit() throws Exception {
        OffsetDateTime newEndsAt = OffsetDateTime.now().plusHours(8);
        User seller = User.builder().id(7L).email("seller@x.com").username("seller").passwordHash("x").displayName("Seller").build();
        Auction auction = Auction.builder()
            .id(AUCTION_DB_ID)
            .status(AuctionStatus.ACTIVE)
            .endsAt(newEndsAt)
            .title("Test Auction")
            .seller(seller)
            .consecutiveWorldApiFailures(0)
            .build();
        AdminAuctionReinstateResult mockResult =
            new AdminAuctionReinstateResult(auction, Duration.ofHours(6), newEndsAt);

        when(adminAuctionService.reinstate(eq(AUCTION_DB_ID), eq(Optional.empty()))).thenReturn(mockResult);

        mvc.perform(post("/api/v1/admin/auctions/" + AUCTION_UUID + "/reinstate")
            .header("Authorization", "Bearer " + adminToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"notes\":\"verified\"}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.auctionId").value(AUCTION_DB_ID))
           .andExpect(jsonPath("$.status").value("ACTIVE"))
           .andExpect(jsonPath("$.suspensionDurationSeconds").value(21600));

        verify(adminActionService).record(
            eq(adminDbId),
            eq(AdminActionType.REINSTATE_LISTING),
            eq(AdminActionTargetType.LISTING),
            eq(AUCTION_DB_ID),
            eq("verified"),
            isNull());
    }
}
