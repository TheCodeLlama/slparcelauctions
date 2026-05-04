package com.slparcelauctions.backend.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.slparcelauctions.backend.admin.dto.AdminFraudFlagDetailDto;
import com.slparcelauctions.backend.admin.dto.AdminFraudFlagDetailDto.AuctionContextDto;
import com.slparcelauctions.backend.admin.dto.AdminFraudFlagSummaryDto;
import com.slparcelauctions.backend.admin.exception.FraudFlagNotFoundException;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.fraud.FraudFlagReason;
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
class AdminFraudFlagControllerListSliceTest {

    private static final UUID ADMIN_UUID = UUID.fromString("00000000-0000-aaaa-0001-000000000001");
    private static final UUID USER_UUID  = UUID.fromString("00000000-0000-aaaa-0001-000000000002");

    @Autowired MockMvc mvc;
    @Autowired JwtService jwtService;
    @Autowired UserRepository userRepository;

    @MockitoBean AdminFraudFlagService service;

    private Long adminDbId;
    private Long userDbId;

    @BeforeEach
    void seedUsers() {
        adminDbId = userRepository.findByPublicId(ADMIN_UUID)
            .orElseGet(() -> userRepository.save(User.builder()
                .publicId(ADMIN_UUID).email("admin-fraud-list@x.com")
                .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                .displayName("Admin").role(Role.ADMIN).verified(true).build()))
            .getId();
        userDbId = userRepository.findByPublicId(USER_UUID)
            .orElseGet(() -> userRepository.save(User.builder()
                .publicId(USER_UUID).email("user-fraud-list@x.com")
                .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                .displayName("User").role(Role.USER).verified(true).build()))
            .getId();
    }

    private String adminToken() {
        return jwtService.issueAccessToken(new AuthPrincipal(adminDbId, ADMIN_UUID, "admin-fraud-list@x.com", 1L, Role.ADMIN));
    }

    private String userToken() {
        return jwtService.issueAccessToken(new AuthPrincipal(userDbId, USER_UUID, "user-fraud-list@x.com", 1L, Role.USER));
    }

    // -------------------------------------------------------------------------
    // Auth gate tests
    // -------------------------------------------------------------------------

    @Test
    void list_anonymous_returns401() throws Exception {
        mvc.perform(get("/api/v1/admin/fraud-flags"))
           .andExpect(status().isUnauthorized());
    }

    @Test
    void list_userRole_returns403() throws Exception {
        mvc.perform(get("/api/v1/admin/fraud-flags")
            .header("Authorization", "Bearer " + userToken()))
           .andExpect(status().isForbidden());
    }

    // -------------------------------------------------------------------------
    // Happy-path list
    // -------------------------------------------------------------------------

    @Test
    void list_admin_default_returnsContent() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        AdminFraudFlagSummaryDto dto = new AdminFraudFlagSummaryDto(
            42L,
            FraudFlagReason.BOT_PRICE_DRIFT,
            now,
            99L,
            "Test Auction",
            AuctionStatus.SUSPENDED,
            "TestRegion",
            null,
            false,
            null,
            null
        );
        PagedResponse<AdminFraudFlagSummaryDto> page =
            PagedResponse.from(new PageImpl<>(List.of(dto), PageRequest.of(0, 25), 1));

        when(service.list(eq("open"), any(), any())).thenReturn(page);

        mvc.perform(get("/api/v1/admin/fraud-flags")
            .header("Authorization", "Bearer " + adminToken()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.content[0].id").value(42))
           .andExpect(jsonPath("$.content[0].reason").value("BOT_PRICE_DRIFT"))
           .andExpect(jsonPath("$.content[0].auctionStatus").value("SUSPENDED"))
           .andExpect(jsonPath("$.totalElements").value(1))
           .andExpect(jsonPath("$.size").value(25));
    }

    @Test
    void list_clampsPageSize_to100() throws Exception {
        PagedResponse<AdminFraudFlagSummaryDto> page =
            PagedResponse.from(new PageImpl<>(List.of(), PageRequest.of(0, 100), 0));

        when(service.list(any(), any(), any())).thenReturn(page);

        mvc.perform(get("/api/v1/admin/fraud-flags?size=999")
            .header("Authorization", "Bearer " + adminToken()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.size").value(100));
    }

    // -------------------------------------------------------------------------
    // Detail endpoint
    // -------------------------------------------------------------------------

    @Test
    void detail_admin_returns200() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        AuctionContextDto auctionCtx = new AuctionContextDto(
            99L, "Test Auction", AuctionStatus.SUSPENDED,
            now.plusHours(24), now.minusHours(1), 7L, "SomeSeller");

        AdminFraudFlagDetailDto detail = new AdminFraudFlagDetailDto(
            42L,
            FraudFlagReason.OWNERSHIP_CHANGED_TO_UNKNOWN,
            now,
            null,
            null,
            null,
            auctionCtx,
            Map.of("observedOwnerUuid", "00000000-0000-0000-0000-000000000001"),
            Map.of(),
            2L
        );

        when(service.detail(42L)).thenReturn(detail);

        mvc.perform(get("/api/v1/admin/fraud-flags/42")
            .header("Authorization", "Bearer " + adminToken()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.id").value(42))
           .andExpect(jsonPath("$.reason").value("OWNERSHIP_CHANGED_TO_UNKNOWN"))
           .andExpect(jsonPath("$.siblingOpenFlagCount").value(2))
           .andExpect(jsonPath("$.auction.id").value(99))
           .andExpect(jsonPath("$.auction.sellerDisplayName").value("SomeSeller"));
    }

    @Test
    void detail_notFound_returns404() throws Exception {
        when(service.detail(999L)).thenThrow(new FraudFlagNotFoundException(999L));

        mvc.perform(get("/api/v1/admin/fraud-flags/999")
            .header("Authorization", "Bearer " + adminToken()))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.code").value("FLAG_NOT_FOUND"));
    }
}
