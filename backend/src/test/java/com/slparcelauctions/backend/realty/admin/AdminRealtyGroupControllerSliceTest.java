package com.slparcelauctions.backend.realty.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import com.slparcelauctions.backend.admin.audit.AdminActionService;
import com.slparcelauctions.backend.admin.audit.AdminActionTargetType;
import com.slparcelauctions.backend.admin.audit.AdminActionType;
import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.auth.JwtService;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupMember;
import com.slparcelauctions.backend.realty.RealtyGroupMemberRepository;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.permission.RealtyGroupPermission;
import com.slparcelauctions.backend.user.Role;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Slice-style coverage for {@link AdminRealtyGroupController}.
 *
 * <p>Uses {@code @SpringBootTest + @AutoConfigureMockMvc} (matching the rest of the realty
 * slice tests — the security filter chain is easier to exercise against a real JWT than to
 * mock end-to-end), seeds fixtures directly, and spies the audit-log service so we can
 * verify the {@link AdminActionService#record} calls without breaking the rest of the
 * transactional flow.
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
class AdminRealtyGroupControllerSliceTest {

    @Autowired MockMvc mvc;
    @Autowired JwtService jwtService;
    @Autowired UserRepository userRepository;
    @Autowired RealtyGroupRepository groupRepository;
    @Autowired RealtyGroupMemberRepository memberRepository;

    @MockitoSpyBean AdminActionService adminActionService;

    private User admin;
    private User leader;
    private User agent;
    private String adminJwt;
    private String leaderJwt;

    @BeforeEach
    void seed() {
        admin = userRepository.save(User.builder()
            .username("admin-" + UUID.randomUUID().toString().substring(0, 8))
            .email("admin-" + UUID.randomUUID() + "@test.local")
            .passwordHash("x").displayName("Admin").role(Role.ADMIN).build());
        leader = userRepository.save(User.builder()
            .username("leader-" + UUID.randomUUID().toString().substring(0, 8))
            .email("leader-" + UUID.randomUUID() + "@test.local")
            .passwordHash("x").displayName("Leader").role(Role.USER).build());
        agent = userRepository.save(User.builder()
            .username("agent-" + UUID.randomUUID().toString().substring(0, 8))
            .email("agent-" + UUID.randomUUID() + "@test.local")
            .passwordHash("x").displayName("Agent").role(Role.USER).build());

        adminJwt = jwtService.issueAccessToken(new AuthPrincipal(
            admin.getId(), admin.getPublicId(), admin.getEmail(), 0L, Role.ADMIN));
        leaderJwt = jwtService.issueAccessToken(new AuthPrincipal(
            leader.getId(), leader.getPublicId(), leader.getEmail(), 0L, Role.USER));
    }

    // ─────────────────────── GET / (list) ───────────────────────

    @Test
    void list_nonAdmin_returns403() throws Exception {
        mvc.perform(get("/api/v1/admin/realty-groups")
                .header("Authorization", "Bearer " + leaderJwt))
            .andExpect(status().isForbidden());
    }

    @Test
    void list_anonymous_returns401() throws Exception {
        mvc.perform(get("/api/v1/admin/realty-groups"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void list_admin_returnsPagedResponse_andOmitsDissolvedByDefault() throws Exception {
        RealtyGroup active = createGroup(leader, "Active List Group");
        RealtyGroup dissolved = createGroup(leader, "Dissolved List Group");
        dissolved.setDissolvedAt(OffsetDateTime.now());
        groupRepository.save(dissolved);

        mvc.perform(get("/api/v1/admin/realty-groups?size=100")
                .header("Authorization", "Bearer " + adminJwt))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content").exists())
            .andExpect(jsonPath("$.totalElements").exists())
            // Active group present
            .andExpect(jsonPath("$.content[?(@.publicId == '" + active.getPublicId() + "')]").exists())
            // Dissolved one absent under the default active filter
            .andExpect(jsonPath("$.content[?(@.publicId == '" + dissolved.getPublicId() + "')]").doesNotExist());
    }

    @Test
    void list_admin_statusDissolved_returnsOnlyDissolved() throws Exception {
        RealtyGroup active = createGroup(leader, "List Active Only");
        RealtyGroup dissolved = createGroup(leader, "List Dissolved Only");
        dissolved.setDissolvedAt(OffsetDateTime.now());
        groupRepository.save(dissolved);

        mvc.perform(get("/api/v1/admin/realty-groups?status=dissolved&size=100")
                .header("Authorization", "Bearer " + adminJwt))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[?(@.publicId == '" + active.getPublicId() + "')]").doesNotExist())
            .andExpect(jsonPath("$.content[?(@.publicId == '" + dissolved.getPublicId() + "')]").exists());
    }

    @Test
    void list_admin_searchSubstring_caseInsensitive() throws Exception {
        String marker = "marker-" + UUID.randomUUID().toString().substring(0, 6);
        RealtyGroup hit = createGroup(leader, "Tagged " + marker);
        createGroup(leader, "Unrelated Group");

        mvc.perform(get("/api/v1/admin/realty-groups?search=" + marker.toUpperCase() + "&size=100")
                .header("Authorization", "Bearer " + adminJwt))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[?(@.publicId == '" + hit.getPublicId() + "')]").exists());
    }

    // ─────────────────────── GET /{publicId} ───────────────────────

    @Test
    void detail_admin_includesDissolvedGroups() throws Exception {
        RealtyGroup g = createGroup(leader, "Already Dissolved Detail");
        g.setDissolvedAt(OffsetDateTime.now());
        groupRepository.save(g);

        mvc.perform(get("/api/v1/admin/realty-groups/" + g.getPublicId())
                .header("Authorization", "Bearer " + adminJwt))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.publicId").value(g.getPublicId().toString()));
    }

    @Test
    void detail_admin_unknownPublicId_returns404() throws Exception {
        mvc.perform(get("/api/v1/admin/realty-groups/" + UUID.randomUUID())
                .header("Authorization", "Bearer " + adminJwt))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("REALTY_GROUP_NOT_FOUND"));
    }

    @Test
    void detail_nonAdmin_returns403() throws Exception {
        RealtyGroup g = createGroup(leader, "Detail Forbidden");
        mvc.perform(get("/api/v1/admin/realty-groups/" + g.getPublicId())
                .header("Authorization", "Bearer " + leaderJwt))
            .andExpect(status().isForbidden());
    }

    // ─────────────────────── PATCH /{publicId} ───────────────────────

    @Test
    void update_admin_bypassesRenameCooldown() throws Exception {
        // Seed a group whose last_renamed_at is 1 day ago — well inside the 30-day cooldown
        // that the user-facing update path enforces. The admin endpoint must succeed.
        RealtyGroup g = createGroup(leader, "Cooldown Original");
        g.setLastRenamedAt(OffsetDateTime.now().minus(Duration.ofDays(1)));
        groupRepository.save(g);

        String renamed = "Cooldown Renamed " + UUID.randomUUID().toString().substring(0, 8);

        mvc.perform(patch("/api/v1/admin/realty-groups/" + g.getPublicId())
                .header("Authorization", "Bearer " + adminJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + renamed + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value(renamed));

        verify(adminActionService).record(
            eq(admin.getId()),
            eq(AdminActionType.REALTY_GROUP_EDIT),
            eq(AdminActionTargetType.REALTY_GROUP),
            eq(g.getId()),
            any(),
            any());
    }

    @Test
    void update_admin_unknownPublicId_returns404() throws Exception {
        mvc.perform(patch("/api/v1/admin/realty-groups/" + UUID.randomUUID())
                .header("Authorization", "Bearer " + adminJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"description\":\"x\"}"))
            .andExpect(status().isNotFound());
    }

    @Test
    void update_nonAdmin_returns403() throws Exception {
        RealtyGroup g = createGroup(leader, "Admin Update Forbidden");
        mvc.perform(patch("/api/v1/admin/realty-groups/" + g.getPublicId())
                .header("Authorization", "Bearer " + leaderJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"description\":\"x\"}"))
            .andExpect(status().isForbidden());
    }

    // ─────────────────────── DELETE /{publicId} (force-dissolve) ───────────────────────

    @Test
    void dissolve_admin_returns204_andRecordsAudit() throws Exception {
        RealtyGroup g = createGroup(leader, "Force Dissolved Group");

        mvc.perform(delete("/api/v1/admin/realty-groups/" + g.getPublicId())
                .header("Authorization", "Bearer " + adminJwt))
            .andExpect(status().isNoContent());

        RealtyGroup fresh = groupRepository.findById(g.getId()).orElseThrow();
        assertThat(fresh.getDissolvedAt()).isNotNull();

        verify(adminActionService).record(
            eq(admin.getId()),
            eq(AdminActionType.REALTY_GROUP_DISSOLVE),
            eq(AdminActionTargetType.REALTY_GROUP),
            eq(g.getId()),
            any(),
            any());
    }

    @Test
    void dissolve_nonAdmin_returns403() throws Exception {
        RealtyGroup g = createGroup(leader, "Cant Force Dissolve");
        mvc.perform(delete("/api/v1/admin/realty-groups/" + g.getPublicId())
                .header("Authorization", "Bearer " + leaderJwt))
            .andExpect(status().isForbidden());
    }

    // ─────────────────────── DELETE /{publicId}/members/{memberPublicId} ───────────────────────

    @Test
    void removeMember_admin_removesAgent_returns204() throws Exception {
        RealtyGroup g = createGroup(leader, "Remove Agent Group");
        RealtyGroupMember agentRow = addMember(g, agent);

        mvc.perform(delete("/api/v1/admin/realty-groups/" + g.getPublicId()
                    + "/members/" + agentRow.getPublicId())
                .header("Authorization", "Bearer " + adminJwt))
            .andExpect(status().isNoContent());

        assertThat(memberRepository.existsByGroupIdAndUserId(g.getId(), agent.getId())).isFalse();
        verify(adminActionService).record(
            eq(admin.getId()),
            eq(AdminActionType.REALTY_GROUP_MEMBER_REMOVE),
            eq(AdminActionTargetType.REALTY_GROUP),
            eq(g.getId()),
            any(),
            any());
    }

    @Test
    void removeMember_admin_targetingLeader_withoutReplacement_returns409() throws Exception {
        RealtyGroup g = createGroup(leader, "Leader Member Row Group");
        RealtyGroupMember leaderRow = memberRepository
            .findByGroupIdAndUserId(g.getId(), leader.getId()).orElseThrow();

        mvc.perform(delete("/api/v1/admin/realty-groups/" + g.getPublicId()
                    + "/members/" + leaderRow.getPublicId())
                .header("Authorization", "Bearer " + adminJwt))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CANNOT_REMOVE_LEADER"));
    }

    @Test
    void removeMember_admin_targetingLeader_withValidReplacement_returns204_andSwapsLeader() throws Exception {
        RealtyGroup g = createGroup(leader, "Force Transfer Group");
        RealtyGroupMember agentRow = addMember(g, agent);
        RealtyGroupMember leaderRow = memberRepository
            .findByGroupIdAndUserId(g.getId(), leader.getId()).orElseThrow();

        mvc.perform(delete("/api/v1/admin/realty-groups/" + g.getPublicId()
                    + "/members/" + leaderRow.getPublicId()
                    + "?newLeaderPublicId=" + agentRow.getPublicId())
                .header("Authorization", "Bearer " + adminJwt))
            .andExpect(status().isNoContent());

        RealtyGroup fresh = groupRepository.findById(g.getId()).orElseThrow();
        assertThat(fresh.getLeaderId()).isEqualTo(agent.getId());
        // The (former) leader's row was deleted.
        assertThat(memberRepository.existsByGroupIdAndUserId(g.getId(), leader.getId())).isFalse();
        // The agent's row remains and is the new leader's row.
        assertThat(memberRepository.existsByGroupIdAndUserId(g.getId(), agent.getId())).isTrue();
    }

    @Test
    void removeMember_admin_targetingLeader_withNonMemberReplacement_returns400() throws Exception {
        RealtyGroup g = createGroup(leader, "Bad Replacement Group");
        RealtyGroupMember leaderRow = memberRepository
            .findByGroupIdAndUserId(g.getId(), leader.getId()).orElseThrow();

        // Random UUID, not a member of this group.
        mvc.perform(delete("/api/v1/admin/realty-groups/" + g.getPublicId()
                    + "/members/" + leaderRow.getPublicId()
                    + "?newLeaderPublicId=" + UUID.randomUUID())
                .header("Authorization", "Bearer " + adminJwt))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("TRANSFER_TARGET_NOT_MEMBER"));
    }

    @Test
    void removeMember_admin_targetingLeader_withCrossGroupReplacement_returns400() throws Exception {
        RealtyGroup g1 = createGroup(leader, "Cross Group A");
        RealtyGroup g2 = createGroup(leader, "Cross Group B");
        RealtyGroupMember g2AgentRow = addMember(g2, agent);
        RealtyGroupMember g1LeaderRow = memberRepository
            .findByGroupIdAndUserId(g1.getId(), leader.getId()).orElseThrow();

        // newLeaderPublicId points at a member of a different group → rejected.
        mvc.perform(delete("/api/v1/admin/realty-groups/" + g1.getPublicId()
                    + "/members/" + g1LeaderRow.getPublicId()
                    + "?newLeaderPublicId=" + g2AgentRow.getPublicId())
                .header("Authorization", "Bearer " + adminJwt))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("TRANSFER_TARGET_NOT_MEMBER"));
    }

    @Test
    void removeMember_admin_unknownMember_returns404() throws Exception {
        RealtyGroup g = createGroup(leader, "Unknown Member Group");
        mvc.perform(delete("/api/v1/admin/realty-groups/" + g.getPublicId()
                    + "/members/" + UUID.randomUUID())
                .header("Authorization", "Bearer " + adminJwt))
            .andExpect(status().isNotFound());
    }

    @Test
    void removeMember_nonAdmin_returns403() throws Exception {
        RealtyGroup g = createGroup(leader, "Member Remove Forbidden");
        RealtyGroupMember agentRow = addMember(g, agent);
        mvc.perform(delete("/api/v1/admin/realty-groups/" + g.getPublicId()
                    + "/members/" + agentRow.getPublicId())
                .header("Authorization", "Bearer " + leaderJwt))
            .andExpect(status().isForbidden());
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
}
