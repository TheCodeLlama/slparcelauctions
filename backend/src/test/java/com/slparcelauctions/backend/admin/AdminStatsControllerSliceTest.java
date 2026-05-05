package com.slparcelauctions.backend.admin;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.slparcelauctions.backend.admin.dto.AdminStatsResponse;
import com.slparcelauctions.backend.admin.dto.AdminStatsResponse.PlatformStats;
import com.slparcelauctions.backend.admin.dto.AdminStatsResponse.QueueStats;
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
class AdminStatsControllerSliceTest {

    private static final UUID ADMIN_UUID = UUID.fromString("00000000-0000-aaaa-0009-000000000001");
    private static final UUID USER_UUID  = UUID.fromString("00000000-0000-aaaa-0009-000000000002");

    @Autowired MockMvc mvc;
    @Autowired JwtService jwtService;
    @Autowired UserRepository userRepository;

    @MockitoBean AdminStatsService statsService;

    private Long adminDbId;
    private Long userDbId;

    @BeforeEach
    void seedUsers() {
        adminDbId = userRepository.findByPublicId(ADMIN_UUID)
            .orElseGet(() -> userRepository.save(User.builder()
                .publicId(ADMIN_UUID).email("admin-stats-ctrl@x.com").username("admin-stats-ctrl")
                .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                .displayName("Admin").role(Role.ADMIN).verified(true).build()))
            .getId();
        userDbId = userRepository.findByPublicId(USER_UUID)
            .orElseGet(() -> userRepository.save(User.builder()
                .publicId(USER_UUID).email("user-stats-ctrl@x.com").username("user-stats-ctrl")
                .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                .displayName("User").role(Role.USER).verified(true).build()))
            .getId();
    }

    @Test
    void stats_anonymous_returns401() throws Exception {
        mvc.perform(get("/api/v1/admin/stats"))
           .andExpect(status().isUnauthorized());
    }

    @Test
    void stats_userRole_returns403() throws Exception {
        String token = jwtService.issueAccessToken(
            new AuthPrincipal(userDbId, USER_UUID, "user-stats-ctrl@x.com", 1L, Role.USER));
        mvc.perform(get("/api/v1/admin/stats")
            .header("Authorization", "Bearer " + token))
           .andExpect(status().isForbidden());
    }

    @Test
    void stats_adminRole_returns200_withPayload() throws Exception {
        AdminStatsResponse fixture = new AdminStatsResponse(
            new QueueStats(7L, 5L, 3L, 1L),
            new PlatformStats(42L, 381L, 12L, 156L, 4_827_500L, 241_375L)
        );
        when(statsService.compute()).thenReturn(fixture);

        String token = jwtService.issueAccessToken(
            new AuthPrincipal(adminDbId, ADMIN_UUID, "admin-stats-ctrl@x.com", 1L, Role.ADMIN));
        mvc.perform(get("/api/v1/admin/stats")
            .header("Authorization", "Bearer " + token))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.queues.openFraudFlags").value(7))
           .andExpect(jsonPath("$.queues.openReports").value(5))
           .andExpect(jsonPath("$.queues.pendingPayments").value(3))
           .andExpect(jsonPath("$.queues.activeDisputes").value(1))
           .andExpect(jsonPath("$.platform.activeListings").value(42))
           .andExpect(jsonPath("$.platform.totalUsers").value(381))
           .andExpect(jsonPath("$.platform.activeEscrows").value(12))
           .andExpect(jsonPath("$.platform.completedSales").value(156))
           .andExpect(jsonPath("$.platform.lindenGrossVolume").value(4_827_500))
           .andExpect(jsonPath("$.platform.lindenCommissionEarned").value(241_375));
    }
}
