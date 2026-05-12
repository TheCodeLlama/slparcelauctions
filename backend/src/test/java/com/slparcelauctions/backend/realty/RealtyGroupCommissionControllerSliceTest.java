package com.slparcelauctions.backend.realty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
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
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.auth.JwtService;
import com.slparcelauctions.backend.realty.permission.RealtyGroupPermission;
import com.slparcelauctions.backend.user.Role;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Slice-style coverage for the bulk per-member commission-rate PATCH endpoint
 * ({@code PATCH /api/v1/realty-groups/{publicId}/members/commission-rates}). Mirrors the
 * {@code RealtyGroupControllerSliceTest} setup pattern: real Spring context, JWT minted
 * via {@link JwtService}, fixtures seeded through repositories.
 *
 * <p>The endpoint itself lives on {@code RealtyGroupController}; this file is split out so
 * the bulk-commission surface owns its own slice scope per the F implementation plan.
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
    "slpa.realty.invitation-expiry.enabled=false"
})
class RealtyGroupCommissionControllerSliceTest {

    @Autowired MockMvc mvc;
    @Autowired JwtService jwtService;
    @Autowired UserRepository userRepository;
    @Autowired RealtyGroupRepository groupRepository;
    @Autowired RealtyGroupMemberRepository memberRepository;

    private final ObjectMapper json = new ObjectMapper();

    private User leader;
    private User agent;
    private String leaderJwt;

    @BeforeEach
    void seed() {
        leader = userRepository.save(User.builder()
            .username("leader-" + UUID.randomUUID().toString().substring(0, 8))
            .email("leader-" + UUID.randomUUID() + "@test.local")
            .passwordHash("x").displayName("Leader").build());
        agent = userRepository.save(User.builder()
            .username("agent-" + UUID.randomUUID().toString().substring(0, 8))
            .email("agent-" + UUID.randomUUID() + "@test.local")
            .passwordHash("x").displayName("Agent").build());

        leaderJwt = jwtService.issueAccessToken(new AuthPrincipal(
            leader.getId(), leader.getPublicId(), leader.getEmail(), 0L, Role.USER));
    }

    // ─────────────────────── happy path ───────────────────────

    @Test
    void patchCommissionRates_happyPath_returns204() throws Exception {
        RealtyGroup g = createGroup(leader, "Commission Group");
        RealtyGroupMember agentRow = addMember(g, agent);

        String body = json.writeValueAsString(java.util.Map.of(
            "memberRates", List.of(java.util.Map.of(
                "memberPublicId", agentRow.getPublicId().toString(),
                "rate", "0.0750"))));

        mvc.perform(patch("/api/v1/realty-groups/" + g.getPublicId() + "/members/commission-rates")
                .header("Authorization", "Bearer " + leaderJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isNoContent());

        RealtyGroupMember refreshed = memberRepository.findById(agentRow.getId()).orElseThrow();
        assertThat(refreshed.getAgentCommissionRate()).isEqualByComparingTo("0.0750");
    }

    // ─────────────────────── auth ───────────────────────

    @Test
    void patchCommissionRates_unauthenticated_returns401() throws Exception {
        RealtyGroup g = createGroup(leader, "Commission Group Unauth");
        RealtyGroupMember agentRow = addMember(g, agent);

        String body = json.writeValueAsString(java.util.Map.of(
            "memberRates", List.of(java.util.Map.of(
                "memberPublicId", agentRow.getPublicId().toString(),
                "rate", "0.0750"))));

        mvc.perform(patch("/api/v1/realty-groups/" + g.getPublicId() + "/members/commission-rates")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isUnauthorized());
    }

    // ─────────────────────── validation ───────────────────────

    @Test
    void patchCommissionRates_invalidRate_returns400() throws Exception {
        RealtyGroup g = createGroup(leader, "Commission Group Invalid");
        RealtyGroupMember agentRow = addMember(g, agent);

        // @DecimalMin("0.0") on the DTO catches the negative rate at validation time.
        String body = json.writeValueAsString(java.util.Map.of(
            "memberRates", List.of(java.util.Map.of(
                "memberPublicId", agentRow.getPublicId().toString(),
                "rate", "-0.0100"))));

        mvc.perform(patch("/api/v1/realty-groups/" + g.getPublicId() + "/members/commission-rates")
                .header("Authorization", "Bearer " + leaderJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest());

        // The rejected request must not have leaked any write through.
        RealtyGroupMember refreshed = memberRepository.findById(agentRow.getId()).orElseThrow();
        assertThat(refreshed.getAgentCommissionRate()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void patchCommissionRates_memberNotInGroup_returns400() throws Exception {
        RealtyGroup g = createGroup(leader, "Commission Group MissingMember");

        UUID strangerPid = UUID.randomUUID();
        String body = json.writeValueAsString(java.util.Map.of(
            "memberRates", List.of(java.util.Map.of(
                "memberPublicId", strangerPid.toString(),
                "rate", "0.0500"))));

        mvc.perform(patch("/api/v1/realty-groups/" + g.getPublicId() + "/members/commission-rates")
                .header("Authorization", "Bearer " + leaderJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("MEMBER_NOT_IN_GROUP"))
            .andExpect(jsonPath("$.memberPublicId").value(strangerPid.toString()));
    }

    // ─────────────────────── helpers ───────────────────────

    private RealtyGroup createGroup(User leaderUser, String name) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String uniqueName = name + " " + suffix;
        RealtyGroup g = groupRepository.save(RealtyGroup.builder()
            .name(uniqueName)
            .slug(uniqueName.toLowerCase().replaceAll("[^a-z0-9]+", "-"))
            .leaderId(leaderUser.getId())
            .build());
        RealtyGroupMember leaderRow = RealtyGroupMember.builder()
            .groupId(g.getId()).userId(leaderUser.getId()).joinedAt(OffsetDateTime.now()).build();
        leaderRow.setPermissionSet(java.util.EnumSet.noneOf(RealtyGroupPermission.class));
        memberRepository.save(leaderRow);
        return g;
    }

    private RealtyGroupMember addMember(RealtyGroup g, User u) {
        RealtyGroupMember row = RealtyGroupMember.builder()
            .groupId(g.getId()).userId(u.getId()).joinedAt(OffsetDateTime.now()).build();
        row.setPermissionSet(java.util.EnumSet.noneOf(RealtyGroupPermission.class));
        row.setAgentCommissionRate(BigDecimal.ZERO);
        return memberRepository.save(row);
    }
}
