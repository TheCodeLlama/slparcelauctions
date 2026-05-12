package com.slparcelauctions.backend.realty.analytics;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
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

import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.auth.JwtService;
import com.slparcelauctions.backend.realty.analytics.dto.MemberCommissionRowDto;
import com.slparcelauctions.backend.realty.exception.RealtyGroupPermissionDeniedException;
import com.slparcelauctions.backend.realty.permission.RealtyGroupPermission;
import com.slparcelauctions.backend.user.Role;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Slice-style coverage for {@link RealtyGroupAnalyticsController}.
 *
 * <p>Mocks {@link GroupCommissionAnalyticsService} so the test exercises the wire-binding
 * + JWT auth chain without spinning up the native query. Status-code mappings for the
 * mocked exceptions flow through the real {@code RealtyExceptionHandler}.
 *
 * <p>Test surface (spec §6.8):
 * <ul>
 *   <li>{@code getCommissions_happyPath_returns200}</li>
 *   <li>{@code getCommissions_unauthenticated_returns401}</li>
 *   <li>{@code getCommissions_notLeaderAndNoPermission_returns403}</li>
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
class RealtyGroupAnalyticsControllerSliceTest {

    private static final UUID GROUP_PUBLIC_ID =
        UUID.fromString("00000000-0000-bbbb-0030-000000000100");
    private static final UUID MEMBER_PUBLIC_ID =
        UUID.fromString("00000000-0000-bbbb-0030-000000000200");

    @Autowired MockMvc mvc;
    @Autowired JwtService jwtService;
    @Autowired UserRepository userRepository;

    @MockitoBean GroupCommissionAnalyticsService analyticsService;

    private User user;

    @BeforeEach
    void seed() {
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        user = userRepository.save(User.builder()
            .username("gca-ctrl-" + suffix)
            .email("gca-ctrl-" + suffix + "@test.local")
            .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
            .displayName("GCA Caller")
            .verified(true)
            .slAvatarUuid(UUID.randomUUID())
            .build());
    }

    private String userToken() {
        return jwtService.issueAccessToken(new AuthPrincipal(
            user.getId(), user.getPublicId(), user.getEmail(), 1L, Role.USER));
    }

    // ─── GET /api/v1/realty-groups/{publicId}/analytics/commissions ───

    @Test
    void getCommissions_happyPath_returns200() throws Exception {
        when(analyticsService.compute(eq(GROUP_PUBLIC_ID), eq(user.getId())))
            .thenReturn(List.of(
                new MemberCommissionRowDto(MEMBER_PUBLIC_ID, "Top Agent", 5_000L, 1_200L)));

        mvc.perform(get("/api/v1/realty-groups/" + GROUP_PUBLIC_ID + "/analytics/commissions")
                .header("Authorization", "Bearer " + userToken()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].memberPublicId").value(MEMBER_PUBLIC_ID.toString()))
           .andExpect(jsonPath("$[0].displayName").value("Top Agent"))
           .andExpect(jsonPath("$[0].lifetimeLindens").value(5000))
           .andExpect(jsonPath("$[0].last30DaysLindens").value(1200));
    }

    @Test
    void getCommissions_unauthenticated_returns401() throws Exception {
        mvc.perform(get("/api/v1/realty-groups/" + GROUP_PUBLIC_ID + "/analytics/commissions"))
           .andExpect(status().isUnauthorized());
    }

    @Test
    void getCommissions_notLeaderAndNoPermission_returns403() throws Exception {
        when(analyticsService.compute(eq(GROUP_PUBLIC_ID), any()))
            .thenThrow(new RealtyGroupPermissionDeniedException(
                RealtyGroupPermission.MANAGE_MEMBERS));

        mvc.perform(get("/api/v1/realty-groups/" + GROUP_PUBLIC_ID + "/analytics/commissions")
                .header("Authorization", "Bearer " + userToken()))
           .andExpect(status().isForbidden());
    }
}
