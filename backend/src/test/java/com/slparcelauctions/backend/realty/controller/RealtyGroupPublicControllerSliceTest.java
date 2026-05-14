package com.slparcelauctions.backend.realty.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

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
class RealtyGroupPublicControllerSliceTest {

    @Autowired MockMvc mvc;
    @Autowired JwtService jwtService;
    @Autowired UserRepository userRepository;
    @Autowired RealtyGroupRepository groupRepository;
    @Autowired RealtyGroupMemberRepository memberRepository;

    private User leader;
    private User agent;
    private User outsider;
    private RealtyGroup group;
    private RealtyGroupMember agentRow;
    private String agentJwt;
    private String outsiderJwt;

    @BeforeEach
    void seed() {
        leader = userRepository.save(User.builder()
            .username("l-" + UUID.randomUUID().toString().substring(0, 8))
            .email("l-" + UUID.randomUUID() + "@test.local")
            .passwordHash("x").displayName("Leader").build());
        agent = userRepository.save(User.builder()
            .username("a-" + UUID.randomUUID().toString().substring(0, 8))
            .email("a-" + UUID.randomUUID() + "@test.local")
            .passwordHash("x").displayName("Agent").build());
        outsider = userRepository.save(User.builder()
            .username("o-" + UUID.randomUUID().toString().substring(0, 8))
            .email("o-" + UUID.randomUUID() + "@test.local")
            .passwordHash("x").displayName("Outsider").build());

        String slug = "pub-" + UUID.randomUUID().toString().substring(0, 8);
        group = groupRepository.save(RealtyGroup.builder()
            .name("Public Group " + slug)
            .slug(slug)
            .leaderId(leader.getId())
            .description("A nice group")
            .build());
        RealtyGroupMember leaderRow = RealtyGroupMember.builder()
            .groupId(group.getId()).userId(leader.getId()).joinedAt(OffsetDateTime.now()).build();
        leaderRow.setPermissionSet(java.util.EnumSet.noneOf(RealtyGroupPermission.class));
        memberRepository.save(leaderRow);

        agentRow = RealtyGroupMember.builder()
            .groupId(group.getId()).userId(agent.getId()).joinedAt(OffsetDateTime.now()).build();
        agentRow.setPermissionSet(java.util.EnumSet.of(RealtyGroupPermission.INVITE_AGENTS));
        agentRow = memberRepository.save(agentRow);

        agentJwt = jwtService.issueAccessToken(new AuthPrincipal(
            agent.getId(), agent.getPublicId(), agent.getEmail(), 0L, Role.USER));
        outsiderJwt = jwtService.issueAccessToken(new AuthPrincipal(
            outsider.getId(), outsider.getPublicId(), outsider.getEmail(), 0L, Role.USER));
    }

    // ─────────────────────── GET /{publicId} ───────────────────────

    @Test
    void getByPublicId_anonymous_returns200_hidesPermissionsAndJoinedAt() throws Exception {
        mvc.perform(get("/api/v1/realty-groups/" + group.getPublicId()))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "max-age=60, public"))
            .andExpect(jsonPath("$.publicId").value(group.getPublicId().toString()))
            .andExpect(jsonPath("$.name").exists())
            .andExpect(jsonPath("$.slug").value(group.getSlug()))
            .andExpect(jsonPath("$.leader.userPublicId").value(leader.getPublicId().toString()))
            .andExpect(jsonPath("$.agents.length()").value(2))
            // Anonymous viewers see no permissions / joinedAt on any agent card.
            .andExpect(jsonPath("$.agents[0].permissions").doesNotExist())
            .andExpect(jsonPath("$.agents[0].joinedAt").doesNotExist())
            .andExpect(jsonPath("$.agents[1].permissions").doesNotExist());
    }

    @Test
    void getByPublicId_memberOfGroup_seesPermissionsAndJoinedAt() throws Exception {
        mvc.perform(get("/api/v1/realty-groups/" + group.getPublicId())
                .header("Authorization", "Bearer " + agentJwt))
            .andExpect(status().isOk())
            // Agent card with INVITE_AGENTS must surface its permission set + join date.
            .andExpect(jsonPath("$.agents[?(@.userPublicId == '" + agent.getPublicId() + "')].permissions").exists())
            .andExpect(jsonPath("$.agents[?(@.userPublicId == '" + agent.getPublicId() + "')].joinedAt").exists());
    }

    @Test
    void getByPublicId_authenticated_setsNoStoreSoFreshWritesAreNotMaskedByCache() throws Exception {
        // Response body for an authenticated caller depends on membership +
        // admin role (commission rate, joinedAt, permissions all gated). A
        // shared cache with a public max-age would either leak the member
        // view to anonymous viewers or hide a fresh PATCH (e.g. a leader
        // updating a commission rate then re-fetching). The fix sets
        // Cache-Control: no-store for any authenticated request so each
        // member/admin response is fresh from origin.
        mvc.perform(get("/api/v1/realty-groups/" + group.getPublicId())
                .header("Authorization", "Bearer " + agentJwt))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void getBySlug_authenticated_setsNoStore() throws Exception {
        mvc.perform(get("/api/v1/realty-groups/by-slug/" + group.getSlug())
                .header("Authorization", "Bearer " + agentJwt))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void getMembers_authenticated_setsNoStore() throws Exception {
        mvc.perform(get("/api/v1/realty-groups/" + group.getPublicId() + "/members")
                .header("Authorization", "Bearer " + agentJwt))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void getMembers_anonymous_setsPublicMaxAge() throws Exception {
        mvc.perform(get("/api/v1/realty-groups/" + group.getPublicId() + "/members"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "max-age=60, public"));
    }

    @Test
    void getByPublicId_nonMemberAuthenticated_hidesPermissionsAndJoinedAt() throws Exception {
        mvc.perform(get("/api/v1/realty-groups/" + group.getPublicId())
                .header("Authorization", "Bearer " + outsiderJwt))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.agents[0].permissions").doesNotExist())
            .andExpect(jsonPath("$.agents[0].joinedAt").doesNotExist());
    }

    @Test
    void getByPublicId_unknown_returns404() throws Exception {
        mvc.perform(get("/api/v1/realty-groups/" + UUID.randomUUID()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("REALTY_GROUP_NOT_FOUND"));
    }

    @Test
    void getByPublicId_dissolved_returns410() throws Exception {
        group.setDissolvedAt(OffsetDateTime.now());
        groupRepository.save(group);

        mvc.perform(get("/api/v1/realty-groups/" + group.getPublicId()))
            .andExpect(status().isGone())
            .andExpect(jsonPath("$.code").value("GROUP_DISSOLVED"));
    }

    // ─────────────────────── GET /by-slug/{slug} ───────────────────────

    @Test
    void getBySlug_anonymous_returns200_andCachingHeader() throws Exception {
        mvc.perform(get("/api/v1/realty-groups/by-slug/" + group.getSlug()))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "max-age=60, public"))
            .andExpect(jsonPath("$.slug").value(group.getSlug()));
    }

    @Test
    void getBySlug_dissolved_returns410() throws Exception {
        group.setDissolvedAt(OffsetDateTime.now());
        groupRepository.save(group);

        mvc.perform(get("/api/v1/realty-groups/by-slug/" + group.getSlug()))
            .andExpect(status().isGone())
            .andExpect(jsonPath("$.code").value("GROUP_DISSOLVED"));
    }

    @Test
    void getBySlug_unknown_returns404() throws Exception {
        mvc.perform(get("/api/v1/realty-groups/by-slug/never-existed-" + UUID.randomUUID()))
            .andExpect(status().isNotFound());
    }

    // ─────────────────────── GET /{publicId}/members ───────────────────────

    @Test
    void getMembers_anonymous_returnsRoster_withoutPrivateFields() throws Exception {
        mvc.perform(get("/api/v1/realty-groups/" + group.getPublicId() + "/members"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            // Permission column hidden for the anonymous viewer.
            .andExpect(jsonPath("$[0].permissions").doesNotExist())
            .andExpect(jsonPath("$[0].joinedAt").doesNotExist());
    }

    @Test
    void getMembers_memberOfGroup_seesPrivateFields() throws Exception {
        mvc.perform(get("/api/v1/realty-groups/" + group.getPublicId() + "/members")
                .header("Authorization", "Bearer " + agentJwt))
            .andExpect(status().isOk())
            // At least one agent card carries permissions + joinedAt.
            .andExpect(jsonPath("$[?(@.userPublicId == '" + agent.getPublicId() + "')].joinedAt").exists());
    }

    @Test
    void getMembers_dissolved_returns410() throws Exception {
        group.setDissolvedAt(OffsetDateTime.now());
        groupRepository.save(group);

        mvc.perform(get("/api/v1/realty-groups/" + group.getPublicId() + "/members"))
            .andExpect(status().isGone());
    }
}
