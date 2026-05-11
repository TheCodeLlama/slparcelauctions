package com.slparcelauctions.backend.realty.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.auth.JwtService;
import com.slparcelauctions.backend.realty.InvitationStatus;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupInvitation;
import com.slparcelauctions.backend.realty.RealtyGroupInvitationRepository;
import com.slparcelauctions.backend.realty.RealtyGroupMember;
import com.slparcelauctions.backend.realty.RealtyGroupMemberRepository;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.permission.RealtyGroupPermission;
import com.slparcelauctions.backend.user.Role;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Slice-style coverage for {@link RealtyGroupController}. Uses {@code @SpringBootTest +
 * @AutoConfigureMockMvc} (per the codebase convention — mocking the full security filter
 * chain is harder than seeding a real user + minting a real JWT) and seeds fixtures
 * directly through repositories.
 *
 * <p>Each test issues a fresh JWT for the relevant caller and asserts auth gate +
 * response shape on each endpoint of the controller.
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
class RealtyGroupControllerSliceTest {

    @Autowired MockMvc mvc;
    @Autowired JwtService jwtService;
    @Autowired UserRepository userRepository;
    @Autowired RealtyGroupRepository groupRepository;
    @Autowired RealtyGroupMemberRepository memberRepository;
    @Autowired RealtyGroupInvitationRepository invitationRepository;

    private final ObjectMapper json = new ObjectMapper();

    private User leader;
    private User agent;
    private User outsider;
    private String leaderJwt;
    private String agentJwt;
    private String outsiderJwt;

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
        outsider = userRepository.save(User.builder()
            .username("out-" + UUID.randomUUID().toString().substring(0, 8))
            .email("out-" + UUID.randomUUID() + "@test.local")
            .passwordHash("x").displayName("Outsider").build());

        leaderJwt = jwtService.issueAccessToken(new AuthPrincipal(
            leader.getId(), leader.getPublicId(), leader.getEmail(), 0L, Role.USER));
        agentJwt = jwtService.issueAccessToken(new AuthPrincipal(
            agent.getId(), agent.getPublicId(), agent.getEmail(), 0L, Role.USER));
        outsiderJwt = jwtService.issueAccessToken(new AuthPrincipal(
            outsider.getId(), outsider.getPublicId(), outsider.getEmail(), 0L, Role.USER));
    }

    // ─────────────────────── POST / ───────────────────────

    @Test
    void post_create_happyPath_returns201_andLeaderPayload() throws Exception {
        String name = "Mainland Realty " + UUID.randomUUID().toString().substring(0, 8);
        String body = json.writeValueAsString(Map_of("name", name));

        mvc.perform(post("/api/v1/realty-groups")
                .header("Authorization", "Bearer " + leaderJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.publicId").exists())
            .andExpect(jsonPath("$.slug").exists())
            .andExpect(jsonPath("$.leader.userPublicId").value(leader.getPublicId().toString()))
            .andExpect(jsonPath("$.agents.length()").value(1))
            .andExpect(jsonPath("$.memberCount").value(1));
    }

    @Test
    void post_create_unauthenticated_returns401() throws Exception {
        mvc.perform(post("/api/v1/realty-groups")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"X\"}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void post_create_missingName_returns400() throws Exception {
        mvc.perform(post("/api/v1/realty-groups")
                .header("Authorization", "Bearer " + leaderJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest());
    }

    // ─────────────────────── PATCH /{publicId} ───────────────────────

    @Test
    void patch_update_leader_canUpdateDescription() throws Exception {
        RealtyGroup g = createGroup(leader, "Coast Realty");

        mvc.perform(patch("/api/v1/realty-groups/" + g.getPublicId())
                .header("Authorization", "Bearer " + leaderJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"description\":\"New tagline\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.description").value("New tagline"));
    }

    @Test
    void patch_update_outsider_returns403() throws Exception {
        RealtyGroup g = createGroup(leader, "Coast Realty 2");

        mvc.perform(patch("/api/v1/realty-groups/" + g.getPublicId())
                .header("Authorization", "Bearer " + outsiderJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"description\":\"Hijack\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("REALTY_GROUP_PERMISSION_DENIED"));
    }

    @Test
    void patch_update_dissolvedGroup_returns410() throws Exception {
        RealtyGroup g = createGroup(leader, "Already Dissolved");
        g.setDissolvedAt(OffsetDateTime.now());
        groupRepository.save(g);

        mvc.perform(patch("/api/v1/realty-groups/" + g.getPublicId())
                .header("Authorization", "Bearer " + leaderJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"description\":\"x\"}"))
            .andExpect(status().isGone())
            .andExpect(jsonPath("$.code").value("GROUP_DISSOLVED"));
    }

    // ─────────────────────── DELETE /{publicId} ───────────────────────

    @Test
    void delete_dissolve_leader_returns204() throws Exception {
        RealtyGroup g = createGroup(leader, "To Be Dissolved");

        mvc.perform(delete("/api/v1/realty-groups/" + g.getPublicId())
                .header("Authorization", "Bearer " + leaderJwt))
            .andExpect(status().isNoContent());

        RealtyGroup fresh = groupRepository.findById(g.getId()).orElseThrow();
        assertThat(fresh.getDissolvedAt()).isNotNull();
    }

    @Test
    void delete_dissolve_nonLeader_returns403() throws Exception {
        RealtyGroup g = createGroup(leader, "Cant Dissolve");

        mvc.perform(delete("/api/v1/realty-groups/" + g.getPublicId())
                .header("Authorization", "Bearer " + outsiderJwt))
            .andExpect(status().isForbidden());
    }

    // ─────────────────────── /{publicId}/leave ───────────────────────

    @Test
    void post_leave_agent_returns204_andRemovesMembership() throws Exception {
        RealtyGroup g = createGroup(leader, "Leave Group");
        addMember(g, agent);

        mvc.perform(post("/api/v1/realty-groups/" + g.getPublicId() + "/leave")
                .header("Authorization", "Bearer " + agentJwt))
            .andExpect(status().isNoContent());

        assertThat(memberRepository.existsByGroupIdAndUserId(g.getId(), agent.getId())).isFalse();
    }

    @Test
    void post_leave_leader_returns409() throws Exception {
        RealtyGroup g = createGroup(leader, "Solo Leader");

        mvc.perform(post("/api/v1/realty-groups/" + g.getPublicId() + "/leave")
                .header("Authorization", "Bearer " + leaderJwt))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("LEADER_CANNOT_LEAVE"));
    }

    // ─────────────────────── /{publicId}/transfer-leadership ───────────────────────

    @Test
    void post_transferLeadership_happyPath_returns200_andSwapsLeader() throws Exception {
        RealtyGroup g = createGroup(leader, "Transfer Group");
        RealtyGroupMember agentRow = addMember(g, agent);

        String body = json.writeValueAsString(Map_of(
            "newLeaderPublicId", agentRow.getPublicId().toString(),
            "oldLeaderAction", "STAY"));

        mvc.perform(post("/api/v1/realty-groups/" + g.getPublicId() + "/transfer-leadership")
                .header("Authorization", "Bearer " + leaderJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.leader.userPublicId").value(agent.getPublicId().toString()));
    }

    @Test
    void post_transferLeadership_nonLeader_returns403() throws Exception {
        RealtyGroup g = createGroup(leader, "Transfer Group 2");
        RealtyGroupMember agentRow = addMember(g, agent);

        String body = json.writeValueAsString(Map_of(
            "newLeaderPublicId", agentRow.getPublicId().toString(),
            "oldLeaderAction", "STAY"));

        mvc.perform(post("/api/v1/realty-groups/" + g.getPublicId() + "/transfer-leadership")
                .header("Authorization", "Bearer " + agentJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isForbidden());
    }

    // ─────────────────────── DELETE /{publicId}/members/{memberPublicId} ───────────────────────

    @Test
    void delete_removeMember_leader_returns204() throws Exception {
        RealtyGroup g = createGroup(leader, "Remove Agent");
        RealtyGroupMember agentRow = addMember(g, agent);

        mvc.perform(delete("/api/v1/realty-groups/" + g.getPublicId()
                    + "/members/" + agentRow.getPublicId())
                .header("Authorization", "Bearer " + leaderJwt))
            .andExpect(status().isNoContent());

        assertThat(memberRepository.existsByGroupIdAndUserId(g.getId(), agent.getId())).isFalse();
    }

    @Test
    void delete_removeMember_outsider_returns403() throws Exception {
        RealtyGroup g = createGroup(leader, "Cant Remove Agent");
        RealtyGroupMember agentRow = addMember(g, agent);

        mvc.perform(delete("/api/v1/realty-groups/" + g.getPublicId()
                    + "/members/" + agentRow.getPublicId())
                .header("Authorization", "Bearer " + outsiderJwt))
            .andExpect(status().isForbidden());
    }

    @Test
    void delete_removeMember_targetingLeader_returns409() throws Exception {
        RealtyGroup g = createGroup(leader, "Leader Member Row");
        RealtyGroupMember leaderRow = memberRepository
            .findByGroupIdAndUserId(g.getId(), leader.getId()).orElseThrow();

        mvc.perform(delete("/api/v1/realty-groups/" + g.getPublicId()
                    + "/members/" + leaderRow.getPublicId())
                .header("Authorization", "Bearer " + leaderJwt))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CANNOT_REMOVE_LEADER"));
    }

    // ─────────────────────── PATCH /{publicId}/members/{memberPublicId}/permissions ───────────────────────

    @Test
    void patch_memberPerms_leader_returns200_andUpdatedPerms() throws Exception {
        RealtyGroup g = createGroup(leader, "Perms Group");
        RealtyGroupMember agentRow = addMember(g, agent);

        String body = json.writeValueAsString(
            Map_of("permissions", List.of("INVITE_AGENTS", "REMOVE_AGENTS")));

        mvc.perform(patch("/api/v1/realty-groups/" + g.getPublicId()
                    + "/members/" + agentRow.getPublicId() + "/permissions")
                .header("Authorization", "Bearer " + leaderJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.memberPublicId").value(agentRow.getPublicId().toString()))
            .andExpect(jsonPath("$.permissions.length()").value(2));
    }

    @Test
    void patch_memberPerms_nonLeader_returns403() throws Exception {
        RealtyGroup g = createGroup(leader, "Perms Group 2");
        RealtyGroupMember agentRow = addMember(g, agent);

        String body = json.writeValueAsString(
            Map_of("permissions", List.of("INVITE_AGENTS")));

        mvc.perform(patch("/api/v1/realty-groups/" + g.getPublicId()
                    + "/members/" + agentRow.getPublicId() + "/permissions")
                .header("Authorization", "Bearer " + agentJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isForbidden());
    }

    @Test
    void patch_memberPerms_missingPermissionsField_returns400() throws Exception {
        RealtyGroup g = createGroup(leader, "Perms Group 3");
        RealtyGroupMember agentRow = addMember(g, agent);

        mvc.perform(patch("/api/v1/realty-groups/" + g.getPublicId()
                    + "/members/" + agentRow.getPublicId() + "/permissions")
                .header("Authorization", "Bearer " + leaderJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest());
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
        return memberRepository.save(row);
    }

    /** Tiny shim so the test reads `Map.of`-style without static-importing the JDK one. */
    @SafeVarargs
    private static <K, V> java.util.LinkedHashMap<K, V> Map_of(Object... kv) {
        java.util.LinkedHashMap<K, V> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            @SuppressWarnings("unchecked") K k = (K) kv[i];
            @SuppressWarnings("unchecked") V v = (V) kv[i + 1];
            m.put(k, v);
        }
        return m;
    }

    /** Silences "field is never read" if these aren't exercised in some shards. */
    @SuppressWarnings("unused")
    private void touchUnused(RealtyGroupInvitation a, InvitationStatus b) { /* noop */ }
}
