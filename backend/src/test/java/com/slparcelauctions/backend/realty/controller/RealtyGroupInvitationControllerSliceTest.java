package com.slparcelauctions.backend.realty.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
class RealtyGroupInvitationControllerSliceTest {

    @Autowired MockMvc mvc;
    @Autowired JwtService jwtService;
    @Autowired UserRepository userRepository;
    @Autowired RealtyGroupRepository groupRepository;
    @Autowired RealtyGroupMemberRepository memberRepository;
    @Autowired RealtyGroupInvitationRepository invitationRepository;

    private final ObjectMapper json = new ObjectMapper();

    private User leader;
    private User invitee;
    private User outsider;
    private RealtyGroup group;
    private String leaderJwt;
    private String outsiderJwt;

    @BeforeEach
    void seed() {
        leader = userRepository.save(User.builder()
            .username("l-" + UUID.randomUUID().toString().substring(0, 8))
            .email("l-" + UUID.randomUUID() + "@test.local").passwordHash("x").displayName("Leader").build());
        invitee = userRepository.save(User.builder()
            .username("inv-" + UUID.randomUUID().toString().substring(0, 8))
            .email("inv-" + UUID.randomUUID() + "@test.local").passwordHash("x").displayName("Invitee").build());
        outsider = userRepository.save(User.builder()
            .username("out-" + UUID.randomUUID().toString().substring(0, 8))
            .email("out-" + UUID.randomUUID() + "@test.local").passwordHash("x").displayName("Outsider").build());

        String slug = "inv-" + UUID.randomUUID().toString().substring(0, 8);
        group = groupRepository.save(RealtyGroup.builder()
            .name("Inv Group " + slug).slug(slug).leaderId(leader.getId()).build());
        RealtyGroupMember leaderRow = RealtyGroupMember.builder()
            .groupId(group.getId()).userId(leader.getId()).joinedAt(OffsetDateTime.now()).build();
        leaderRow.setPermissionSet(java.util.EnumSet.noneOf(RealtyGroupPermission.class));
        memberRepository.save(leaderRow);

        leaderJwt = jwtService.issueAccessToken(new AuthPrincipal(
            leader.getId(), leader.getPublicId(), leader.getEmail(), 0L, Role.USER));
        outsiderJwt = jwtService.issueAccessToken(new AuthPrincipal(
            outsider.getId(), outsider.getPublicId(), outsider.getEmail(), 0L, Role.USER));
    }

    // ─────────────────────── POST ───────────────────────

    @Test
    void post_invite_leader_returns201_andCreatesPendingRow() throws Exception {
        String body = json.writeValueAsString(java.util.Map.of(
            "invitedUsername", invitee.getUsername(),
            "permissions", java.util.List.of("INVITE_AGENTS")));

        mvc.perform(post("/api/v1/realty-groups/" + group.getPublicId() + "/invitations")
                .header("Authorization", "Bearer " + leaderJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.publicId").exists())
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.groupPublicId").value(group.getPublicId().toString()));
    }

    @Test
    void post_invite_outsider_returns403() throws Exception {
        String body = json.writeValueAsString(java.util.Map.of(
            "invitedUsername", invitee.getUsername(),
            "permissions", java.util.List.of()));

        mvc.perform(post("/api/v1/realty-groups/" + group.getPublicId() + "/invitations")
                .header("Authorization", "Bearer " + outsiderJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("REALTY_GROUP_PERMISSION_DENIED"));
    }

    @Test
    void post_invite_missingUsername_returns400() throws Exception {
        mvc.perform(post("/api/v1/realty-groups/" + group.getPublicId() + "/invitations")
                .header("Authorization", "Bearer " + leaderJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"permissions\":[]}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void post_invite_unauthenticated_returns401() throws Exception {
        mvc.perform(post("/api/v1/realty-groups/" + group.getPublicId() + "/invitations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isUnauthorized());
    }

    // ─────────────────────── GET ───────────────────────

    @Test
    void get_list_leader_returnsAllInvitationsForGroup() throws Exception {
        RealtyGroupInvitation inv = createInvitation(invitee, InvitationStatus.PENDING);
        createInvitation(outsider, InvitationStatus.DECLINED);

        mvc.perform(get("/api/v1/realty-groups/" + group.getPublicId() + "/invitations")
                .header("Authorization", "Bearer " + leaderJwt))
            .andExpect(status().isOk())
            // List contains both rows (newest first).
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[?(@.publicId == '" + inv.getPublicId() + "')].status").value("PENDING"));
    }

    @Test
    void get_list_outsider_returns403() throws Exception {
        createInvitation(invitee, InvitationStatus.PENDING);

        mvc.perform(get("/api/v1/realty-groups/" + group.getPublicId() + "/invitations")
                .header("Authorization", "Bearer " + outsiderJwt))
            .andExpect(status().isForbidden());
    }

    @Test
    void get_list_unauthenticated_returns401() throws Exception {
        mvc.perform(get("/api/v1/realty-groups/" + group.getPublicId() + "/invitations"))
            .andExpect(status().isUnauthorized());
    }

    // ─────────────────────── DELETE ───────────────────────

    @Test
    void delete_revoke_leader_returns204_andFlipsStatus() throws Exception {
        RealtyGroupInvitation inv = createInvitation(invitee, InvitationStatus.PENDING);

        mvc.perform(delete("/api/v1/realty-groups/" + group.getPublicId()
                    + "/invitations/" + inv.getPublicId())
                .header("Authorization", "Bearer " + leaderJwt))
            .andExpect(status().isNoContent());

        RealtyGroupInvitation fresh = invitationRepository.findById(inv.getId()).orElseThrow();
        assertThat(fresh.getStatus()).isEqualTo(InvitationStatus.REVOKED);
    }

    @Test
    void delete_revoke_outsider_returns403() throws Exception {
        RealtyGroupInvitation inv = createInvitation(invitee, InvitationStatus.PENDING);

        mvc.perform(delete("/api/v1/realty-groups/" + group.getPublicId()
                    + "/invitations/" + inv.getPublicId())
                .header("Authorization", "Bearer " + outsiderJwt))
            .andExpect(status().isForbidden());
    }

    private RealtyGroupInvitation createInvitation(User addressedTo, InvitationStatus status) {
        RealtyGroupInvitation inv = RealtyGroupInvitation.builder()
            .groupId(group.getId())
            .invitedUserId(addressedTo.getId())
            .invitedById(leader.getId())
            .status(status)
            .expiresAt(OffsetDateTime.now().plusDays(7))
            .respondedAt(status == InvitationStatus.PENDING ? null : OffsetDateTime.now())
            .build();
        inv.setPermissionSet(java.util.EnumSet.noneOf(RealtyGroupPermission.class));
        return invitationRepository.save(inv);
    }
}
