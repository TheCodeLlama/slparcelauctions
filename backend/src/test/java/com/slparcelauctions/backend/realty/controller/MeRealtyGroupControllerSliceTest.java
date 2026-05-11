package com.slparcelauctions.backend.realty.controller;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

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
class MeRealtyGroupControllerSliceTest {

    @Autowired MockMvc mvc;
    @Autowired JwtService jwtService;
    @Autowired UserRepository userRepository;
    @Autowired RealtyGroupRepository groupRepository;
    @Autowired RealtyGroupMemberRepository memberRepository;
    @Autowired RealtyGroupInvitationRepository invitationRepository;

    private User leader;
    private User invitee;
    private User someoneElse;
    private RealtyGroup group;
    private String inviteeJwt;
    private String someoneElseJwt;

    @BeforeEach
    void seed() {
        leader = userRepository.save(User.builder()
            .username("l-" + UUID.randomUUID().toString().substring(0, 8))
            .email("l-" + UUID.randomUUID() + "@test.local").passwordHash("x").displayName("Leader").build());
        invitee = userRepository.save(User.builder()
            .username("i-" + UUID.randomUUID().toString().substring(0, 8))
            .email("i-" + UUID.randomUUID() + "@test.local").passwordHash("x").displayName("Invitee").build());
        someoneElse = userRepository.save(User.builder()
            .username("e-" + UUID.randomUUID().toString().substring(0, 8))
            .email("e-" + UUID.randomUUID() + "@test.local").passwordHash("x").displayName("Other").build());

        String slug = "me-" + UUID.randomUUID().toString().substring(0, 8);
        group = groupRepository.save(RealtyGroup.builder()
            .name("Me Group " + slug).slug(slug).leaderId(leader.getId()).build());
        RealtyGroupMember leaderRow = RealtyGroupMember.builder()
            .groupId(group.getId()).userId(leader.getId()).joinedAt(OffsetDateTime.now()).build();
        leaderRow.setPermissionSet(java.util.EnumSet.noneOf(RealtyGroupPermission.class));
        memberRepository.save(leaderRow);

        inviteeJwt = jwtService.issueAccessToken(new AuthPrincipal(
            invitee.getId(), invitee.getPublicId(), invitee.getEmail(), 0L, Role.USER));
        someoneElseJwt = jwtService.issueAccessToken(new AuthPrincipal(
            someoneElse.getId(), someoneElse.getPublicId(), someoneElse.getEmail(), 0L, Role.USER));
    }

    private RealtyGroupInvitation createInvitation(User addressedTo) {
        RealtyGroupInvitation inv = RealtyGroupInvitation.builder()
            .groupId(group.getId())
            .invitedUserId(addressedTo.getId())
            .invitedById(leader.getId())
            .status(InvitationStatus.PENDING)
            .expiresAt(OffsetDateTime.now().plusDays(7))
            .build();
        inv.setPermissionSet(java.util.EnumSet.of(RealtyGroupPermission.INVITE_AGENTS));
        return invitationRepository.save(inv);
    }

    // ─────────────────────── GET /me/invitations ───────────────────────

    @Test
    void getInvitations_anonymous_returns401() throws Exception {
        mvc.perform(get("/api/v1/me/invitations"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void getInvitations_returnsOnlyMyPending() throws Exception {
        createInvitation(invitee);
        createInvitation(someoneElse);  // not the caller's — must not surface

        mvc.perform(get("/api/v1/me/invitations")
                .header("Authorization", "Bearer " + inviteeJwt))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].groupPublicId").value(group.getPublicId().toString()))
            .andExpect(jsonPath("$[0].status").value("PENDING"));
    }

    // ─────────────────────── POST /me/invitations/{id}/accept ───────────────────────

    @Test
    void postAccept_happyPath_returnsSummaryDto_andCreatesMembership() throws Exception {
        RealtyGroupInvitation inv = createInvitation(invitee);

        mvc.perform(post("/api/v1/me/invitations/" + inv.getPublicId() + "/accept")
                .header("Authorization", "Bearer " + inviteeJwt))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.publicId").value(group.getPublicId().toString()))
            .andExpect(jsonPath("$.slug").value(group.getSlug()));

        assertThat(memberRepository.existsByGroupIdAndUserId(group.getId(), invitee.getId())).isTrue();
        RealtyGroupInvitation fresh = invitationRepository.findById(inv.getId()).orElseThrow();
        assertThat(fresh.getStatus()).isEqualTo(InvitationStatus.ACCEPTED);
    }

    @Test
    void postAccept_addressedToSomeoneElse_returns403() throws Exception {
        RealtyGroupInvitation inv = createInvitation(someoneElse);

        mvc.perform(post("/api/v1/me/invitations/" + inv.getPublicId() + "/accept")
                .header("Authorization", "Bearer " + inviteeJwt))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("REALTY_GROUP_PERMISSION_DENIED"));
    }

    @Test
    void postAccept_unknownInvitation_returns404() throws Exception {
        mvc.perform(post("/api/v1/me/invitations/" + UUID.randomUUID() + "/accept")
                .header("Authorization", "Bearer " + inviteeJwt))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("INVITATION_NOT_FOUND"));
    }

    // ─────────────────────── POST /me/invitations/{id}/decline ───────────────────────

    @Test
    void postDecline_happyPath_returns204_andFlipsStatus() throws Exception {
        RealtyGroupInvitation inv = createInvitation(invitee);

        mvc.perform(post("/api/v1/me/invitations/" + inv.getPublicId() + "/decline")
                .header("Authorization", "Bearer " + inviteeJwt))
            .andExpect(status().isNoContent());

        RealtyGroupInvitation fresh = invitationRepository.findById(inv.getId()).orElseThrow();
        assertThat(fresh.getStatus()).isEqualTo(InvitationStatus.DECLINED);
    }

    @Test
    void postDecline_addressedToSomeoneElse_returns403() throws Exception {
        RealtyGroupInvitation inv = createInvitation(someoneElse);

        mvc.perform(post("/api/v1/me/invitations/" + inv.getPublicId() + "/decline")
                .header("Authorization", "Bearer " + inviteeJwt))
            .andExpect(status().isForbidden());
    }

    // ─────────────────────── GET /me/realty-groups ───────────────────────

    @Test
    void getMyGroups_returnsLeaderGroup() throws Exception {
        String leaderJwt = jwtService.issueAccessToken(new AuthPrincipal(
            leader.getId(), leader.getPublicId(), leader.getEmail(), 0L, Role.USER));

        mvc.perform(get("/api/v1/me/realty-groups")
                .header("Authorization", "Bearer " + leaderJwt))
            .andExpect(status().isOk())
            // At least the seeded group should appear (the shared dev DB may carry others;
            // assert this group is in the list rather than asserting an exact length).
            .andExpect(jsonPath("$[?(@.publicId == '" + group.getPublicId() + "')].slug")
                .value(group.getSlug()));
    }

    @Test
    void getMyGroups_unauthenticated_returns401() throws Exception {
        mvc.perform(get("/api/v1/me/realty-groups"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void getMyGroups_inviteeWithNoMembership_returnsEmptyOrWithoutThisGroup() throws Exception {
        mvc.perform(get("/api/v1/me/realty-groups")
                .header("Authorization", "Bearer " + inviteeJwt))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.publicId == '" + group.getPublicId() + "')]").isEmpty());
    }
}
