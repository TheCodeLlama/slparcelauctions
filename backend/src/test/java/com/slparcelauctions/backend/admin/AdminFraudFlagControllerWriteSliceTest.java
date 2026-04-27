package com.slparcelauctions.backend.admin;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.slparcelauctions.backend.admin.dto.AdminFraudFlagDetailDto;
import com.slparcelauctions.backend.admin.dto.AdminFraudFlagDetailDto.AuctionContextDto;
import com.slparcelauctions.backend.admin.exception.AuctionNotSuspendedException;
import com.slparcelauctions.backend.admin.exception.FraudFlagAlreadyResolvedException;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.fraud.FraudFlagReason;
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
class AdminFraudFlagControllerWriteSliceTest {

    @Autowired MockMvc mvc;
    @Autowired JwtService jwtService;

    @MockitoBean AdminFraudFlagService service;

    private String adminToken() {
        return jwtService.issueAccessToken(new AuthPrincipal(1L, "a@x.com", 1L, Role.ADMIN));
    }

    @Test
    void dismiss_emptyNotes_returns400_VALIDATION_FAILED() throws Exception {
        mvc.perform(post("/api/v1/admin/fraud-flags/42/dismiss")
            .header("Authorization", "Bearer " + adminToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"adminNotes\":\"\"}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void dismiss_alreadyResolved_returns409_ALREADY_RESOLVED() throws Exception {
        when(service.dismiss(anyLong(), anyLong(), anyString()))
            .thenThrow(new FraudFlagAlreadyResolvedException(42L));

        mvc.perform(post("/api/v1/admin/fraud-flags/42/dismiss")
            .header("Authorization", "Bearer " + adminToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"adminNotes\":\"Some notes\"}"))
           .andExpect(status().isConflict())
           .andExpect(jsonPath("$.code").value("ALREADY_RESOLVED"));
    }

    @Test
    void reinstate_auctionNotSuspended_returns409_AUCTION_NOT_SUSPENDED_withCurrentStatus() throws Exception {
        when(service.reinstate(anyLong(), anyLong(), anyString()))
            .thenThrow(new AuctionNotSuspendedException(AuctionStatus.CANCELLED));

        mvc.perform(post("/api/v1/admin/fraud-flags/42/reinstate")
            .header("Authorization", "Bearer " + adminToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"adminNotes\":\"Some notes\"}"))
           .andExpect(status().isConflict())
           .andExpect(jsonPath("$.code").value("AUCTION_NOT_SUSPENDED"))
           .andExpect(jsonPath("$.details.currentStatus").value("CANCELLED"));
    }

    @Test
    void reinstate_admin_returns200() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        AuctionContextDto auctionCtx = new AuctionContextDto(
            99L, "Test Auction", AuctionStatus.ACTIVE,
            now.plusHours(24), null, 7L, "SomeSeller");

        AdminFraudFlagDetailDto detail = new AdminFraudFlagDetailDto(
            42L,
            FraudFlagReason.BOT_PRICE_DRIFT,
            now.minusHours(6),
            now,
            "Admin",
            "Reinstated after review",
            auctionCtx,
            Map.of(),
            Map.of(),
            0L
        );
        when(service.reinstate(anyLong(), anyLong(), anyString())).thenReturn(detail);

        mvc.perform(post("/api/v1/admin/fraud-flags/42/reinstate")
            .header("Authorization", "Bearer " + adminToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"adminNotes\":\"Reinstated after review\"}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.id").value(42))
           .andExpect(jsonPath("$.adminNotes").value("Reinstated after review"));
    }
}
