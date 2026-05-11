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

import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupMember;
import com.slparcelauctions.backend.realty.RealtyGroupMemberRepository;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.permission.RealtyGroupPermission;
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
class RealtyGroupAffiliationControllerSliceTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository userRepository;
    @Autowired RealtyGroupRepository groupRepository;
    @Autowired RealtyGroupMemberRepository memberRepository;

    private User user;
    private RealtyGroup leaderGroup;
    private RealtyGroup agentGroup;

    @BeforeEach
    void seed() {
        user = userRepository.save(User.builder()
            .username("u-" + UUID.randomUUID().toString().substring(0, 8))
            .email("u-" + UUID.randomUUID() + "@test.local").passwordHash("x").displayName("User").build());

        // Group the user leads
        String slug1 = "led-" + UUID.randomUUID().toString().substring(0, 8);
        leaderGroup = groupRepository.save(RealtyGroup.builder()
            .name("Led Group " + slug1).slug(slug1).leaderId(user.getId()).build());
        RealtyGroupMember leaderRow = RealtyGroupMember.builder()
            .groupId(leaderGroup.getId()).userId(user.getId()).joinedAt(OffsetDateTime.now()).build();
        leaderRow.setPermissionSet(java.util.EnumSet.noneOf(RealtyGroupPermission.class));
        memberRepository.save(leaderRow);

        // Group the user is an agent in. Need a separate user as the leader.
        User otherLeader = userRepository.save(User.builder()
            .username("o-" + UUID.randomUUID().toString().substring(0, 8))
            .email("o-" + UUID.randomUUID() + "@test.local").passwordHash("x").displayName("Other").build());
        String slug2 = "joined-" + UUID.randomUUID().toString().substring(0, 8);
        agentGroup = groupRepository.save(RealtyGroup.builder()
            .name("Joined Group " + slug2).slug(slug2).leaderId(otherLeader.getId()).build());
        RealtyGroupMember otherLeaderRow = RealtyGroupMember.builder()
            .groupId(agentGroup.getId()).userId(otherLeader.getId()).joinedAt(OffsetDateTime.now()).build();
        otherLeaderRow.setPermissionSet(java.util.EnumSet.noneOf(RealtyGroupPermission.class));
        memberRepository.save(otherLeaderRow);
        RealtyGroupMember agentRow = RealtyGroupMember.builder()
            .groupId(agentGroup.getId()).userId(user.getId()).joinedAt(OffsetDateTime.now()).build();
        agentRow.setPermissionSet(java.util.EnumSet.of(RealtyGroupPermission.INVITE_AGENTS));
        memberRepository.save(agentRow);
    }

    @Test
    void getAffiliations_happyPath_returnsBothGroups_withCorrectRoles() throws Exception {
        mvc.perform(get("/api/v1/users/" + user.getPublicId() + "/realty-groups"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "max-age=60, public"))
            .andExpect(jsonPath("$[?(@.groupPublicId == '" + leaderGroup.getPublicId() + "')].role").value("LEADER"))
            .andExpect(jsonPath("$[?(@.groupPublicId == '" + agentGroup.getPublicId() + "')].role").value("AGENT"));
    }

    @Test
    void getAffiliations_unknownUser_returns404() throws Exception {
        mvc.perform(get("/api/v1/users/" + UUID.randomUUID() + "/realty-groups"))
            .andExpect(status().isNotFound());
    }

    @Test
    void getAffiliations_userWithNoGroups_returnsEmptyArray() throws Exception {
        User loner = userRepository.save(User.builder()
            .username("loner-" + UUID.randomUUID().toString().substring(0, 8))
            .email("loner-" + UUID.randomUUID() + "@test.local")
            .passwordHash("x").displayName("Loner").build());

        mvc.perform(get("/api/v1/users/" + loner.getPublicId() + "/realty-groups"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));
    }
}
