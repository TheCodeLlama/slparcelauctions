package com.slparcelauctions.backend.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
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

import com.slparcelauctions.backend.admin.AdminAuctionService.AdminAuctionReinstateResult;
import com.slparcelauctions.backend.admin.audit.AdminActionService;
import com.slparcelauctions.backend.admin.audit.AdminActionTargetType;
import com.slparcelauctions.backend.admin.audit.AdminActionType;
import com.slparcelauctions.backend.admin.exception.AuctionNotSuspendedException;
import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.auth.JwtService;
import com.slparcelauctions.backend.user.Role;
import com.slparcelauctions.backend.user.User;

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

    @Autowired MockMvc mvc;
    @Autowired JwtService jwtService;

    @MockitoBean AdminAuctionService adminAuctionService;
    @MockitoBean AdminActionService adminActionService;

    private String adminToken() {
        return jwtService.issueAccessToken(new AuthPrincipal(99L, "admin@x.com", 1L, Role.ADMIN));
    }

    private String userToken() {
        return jwtService.issueAccessToken(new AuthPrincipal(5L, "user@x.com", 1L, Role.USER));
    }

    @Test
    void reinstate_anonymous_returns401() throws Exception {
        mvc.perform(post("/api/v1/admin/auctions/100/reinstate")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"notes\":\"some notes\"}"))
           .andExpect(status().isUnauthorized());
    }

    @Test
    void reinstate_userRole_returns403() throws Exception {
        mvc.perform(post("/api/v1/admin/auctions/100/reinstate")
            .header("Authorization", "Bearer " + userToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"notes\":\"some notes\"}"))
           .andExpect(status().isForbidden());
    }

    @Test
    void reinstate_emptyNotes_returns400() throws Exception {
        mvc.perform(post("/api/v1/admin/auctions/100/reinstate")
            .header("Authorization", "Bearer " + adminToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"notes\":\"\"}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void reinstate_notSuspended_returns409() throws Exception {
        when(adminAuctionService.reinstate(eq(100L), any()))
            .thenThrow(new AuctionNotSuspendedException(AuctionStatus.CANCELLED));

        mvc.perform(post("/api/v1/admin/auctions/100/reinstate")
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
        User seller = User.builder().id(7L).email("seller@x.com").passwordHash("x").displayName("Seller").build();
        Auction auction = Auction.builder()
            .id(100L)
            .status(AuctionStatus.ACTIVE)
            .endsAt(newEndsAt)
            .title("Test Auction")
            .seller(seller)
            .consecutiveWorldApiFailures(0)
            .build();
        AdminAuctionReinstateResult mockResult =
            new AdminAuctionReinstateResult(auction, Duration.ofHours(6), newEndsAt);

        when(adminAuctionService.reinstate(eq(100L), eq(Optional.empty()))).thenReturn(mockResult);

        mvc.perform(post("/api/v1/admin/auctions/100/reinstate")
            .header("Authorization", "Bearer " + adminToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"notes\":\"verified\"}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.auctionId").value(100))
           .andExpect(jsonPath("$.status").value("ACTIVE"))
           .andExpect(jsonPath("$.suspensionDurationSeconds").value(21600));

        verify(adminActionService).record(
            eq(99L),
            eq(AdminActionType.REINSTATE_LISTING),
            eq(AdminActionTargetType.LISTING),
            eq(100L),
            eq("verified"),
            isNull());
    }
}
