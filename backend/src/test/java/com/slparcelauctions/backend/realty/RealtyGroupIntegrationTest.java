package com.slparcelauctions.backend.realty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.auth.JwtService;
import com.slparcelauctions.backend.realty.permission.RealtyGroupPermission;
import com.slparcelauctions.backend.realty.service.RealtyGroupExpiryJob;
import com.slparcelauctions.backend.user.Role;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * End-to-end integration tests for the realty groups slice (spec §11, plan Task 32).
 *
 * <p>Drives the full REST surface against a real Postgres database, asserting the
 * cross-service invariants the unit + slice tests can't cover on their own:
 *
 * <ul>
 *   <li>Full leader/agent lifecycle from create through dissolve.</li>
 *   <li>Multi-group membership ({@code findActiveByMemberUserId} returns the right set).</li>
 *   <li>30-day rename cooldown enforced for non-admin renames, bypassed for admin renames,
 *       and admin renames don't bump the leader's cooldown clock.</li>
 *   <li>Case-insensitive name uniqueness via the partial unique index on {@code name_lower}.</li>
 *   <li>Soft-delete + immediate name/slug reuse — the partial unique indexes exclude
 *       dissolved rows so a brand-new group can claim a freshly-dissolved name.</li>
 *   <li>Expiry job flips overdue PENDING invitations to EXPIRED.</li>
 *   <li>Admin force-dissolve preserves member rows (audit).</li>
 *   <li>Leader cannot leave; transferring leadership first unblocks the (now former) leader.</li>
 *   <li>Transfer-leadership target must be a current member of the same group.</li>
 *   <li>Seat-limit enforcement at the invite endpoint.</li>
 * </ul>
 *
 * <p><b>Test cleanup.</b> Every realty user this test seeds is given the email pattern
 * {@code rgint-%@test.local}. The {@link #cleanupRealtyChain()} {@code @AfterEach} clears
 * the realty FK chain for those users so the test users themselves can then be deleted —
 * mirroring the pattern that {@code SlImMessageDaoTest} added in Phase 9. This keeps
 * realty test data from leaking into the rest of the suite (the FK chain blocks
 * {@code users} deletes when realty rows hold a reference).
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
    // Keep the expiry-job bean alive so the test can invoke it directly; the @Scheduled
    // tick may still fire once but it's a no-op against rows the test didn't seed as
    // overdue. The other realty integration scenarios are unaffected.
    "slpa.realty.invitation-expiry.enabled=true"
})
class RealtyGroupIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JwtService jwtService;
    @Autowired UserRepository userRepository;
    @Autowired RealtyGroupRepository groupRepository;
    @Autowired RealtyGroupMemberRepository memberRepository;
    @Autowired RealtyGroupInvitationRepository invitationRepository;
    @Autowired RealtyGroupExpiryJob expiryJob;
    @Autowired DataSource dataSource;

    /**
     * Jackson is on the test classpath via spring-boot-starter-test; instantiate inline
     * rather than {@code @Autowired} because the JsonAutoConfiguration {@code ObjectMapper}
     * isn't always exposed as a top-level bean in the test slice.
     */
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper =
        new com.fasterxml.jackson.databind.ObjectMapper();

    private static final String TEST_EMAIL_PREFIX = "rgint-";

    private User leader;
    private User invitee;
    private User admin;
    private String leaderJwt;
    private String inviteeJwt;
    private String adminJwt;

    @BeforeEach
    void seed() {
        leader = persistUser("leader");
        invitee = persistUser("invitee");
        admin = userRepository.save(buildUser("admin").role(Role.ADMIN).build());

        leaderJwt = issueJwt(leader, Role.USER);
        inviteeJwt = issueJwt(invitee, Role.USER);
        adminJwt = issueJwt(admin, Role.ADMIN);
    }

    @AfterEach
    void cleanupRealtyChain() throws Exception {
        // Mirrors the SlImMessageDaoTest cleanup: clear every row referencing a
        // test-local user before the user-delete cascade is allowed to run. Notifications
        // are inserted as a side effect of realty mutations, so they need clearing too.
        // sl_im_message has ON DELETE CASCADE on users.id (V13) but we clear it explicitly
        // to keep cross-suite isolation tight. Email pattern is scoped to this test class
        // so we never touch fixtures owned by another suite.
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (var stmt = conn.createStatement()) {
                stmt.execute("""
                    DELETE FROM admin_actions
                     WHERE admin_user_id IN (SELECT id FROM users WHERE email LIKE 'rgint-%@test.local')
                    """);
                stmt.execute("""
                    DELETE FROM notification
                     WHERE user_id IN (SELECT id FROM users WHERE email LIKE 'rgint-%@test.local')
                    """);
                stmt.execute("""
                    DELETE FROM sl_im_message
                     WHERE user_id IN (SELECT id FROM users WHERE email LIKE 'rgint-%@test.local')
                    """);
                stmt.execute("""
                    DELETE FROM realty_group_invitations
                     WHERE invited_user_id IN (SELECT id FROM users WHERE email LIKE 'rgint-%@test.local')
                        OR invited_by_id IN (SELECT id FROM users WHERE email LIKE 'rgint-%@test.local')
                    """);
                stmt.execute("""
                    DELETE FROM realty_group_members
                     WHERE user_id IN (SELECT id FROM users WHERE email LIKE 'rgint-%@test.local')
                    """);
                stmt.execute("""
                    DELETE FROM realty_groups
                     WHERE leader_id IN (SELECT id FROM users WHERE email LIKE 'rgint-%@test.local')
                    """);
                stmt.execute("DELETE FROM users WHERE email LIKE 'rgint-%@test.local'");
            }
        }
    }

    // ─────────────────────── Scenario 1: full lifecycle ───────────────────────

    @Test
    void fullLifecycle_createInviteAcceptEditPermissionsRemoveDissolve() throws Exception {
        // Step 1: leader creates a group.
        UUID groupPid = createGroup(leaderJwt, "Lifecycle Realty " + suffix());
        RealtyGroup group = groupRepository.findByPublicId(groupPid).orElseThrow();
        assertThat(memberRepository.countByGroupId(group.getId()))
            .as("creator should be the sole leader member after create")
            .isEqualTo(1);

        // Step 2: leader invites the invitee with INVITE_AGENTS perms.
        UUID invitationPid = invite(leaderJwt, groupPid, invitee.getUsername(),
            List.of("INVITE_AGENTS"));
        assertThat(invitationRepository.findByPublicId(invitationPid).orElseThrow().getStatus())
            .isEqualTo(InvitationStatus.PENDING);

        // Step 3: invitee accepts.
        mvc.perform(post("/api/v1/me/invitations/" + invitationPid + "/accept")
                .header("Authorization", "Bearer " + inviteeJwt))
            .andExpect(status().isOk());
        assertThat(memberRepository.existsByGroupIdAndUserId(group.getId(), invitee.getId()))
            .as("invitee should be a member after accept")
            .isTrue();
        assertThat(invitationRepository.findByPublicId(invitationPid).orElseThrow().getStatus())
            .isEqualTo(InvitationStatus.ACCEPTED);

        // Step 4: leader edits the invitee's permissions (add REMOVE_AGENTS).
        RealtyGroupMember inviteeMember = memberRepository
            .findByGroupIdAndUserId(group.getId(), invitee.getId()).orElseThrow();
        mvc.perform(patch("/api/v1/realty-groups/" + groupPid + "/members/"
                    + inviteeMember.getPublicId() + "/permissions")
                .header("Authorization", "Bearer " + leaderJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"permissions\":[\"INVITE_AGENTS\",\"REMOVE_AGENTS\"]}"))
            .andExpect(status().isOk());
        RealtyGroupMember refreshed = memberRepository.findById(inviteeMember.getId()).orElseThrow();
        assertThat(refreshed.permissionSet())
            .containsExactlyInAnyOrder(
                RealtyGroupPermission.INVITE_AGENTS,
                RealtyGroupPermission.REMOVE_AGENTS);

        // Step 5: leader removes the invitee.
        mvc.perform(delete("/api/v1/realty-groups/" + groupPid + "/members/"
                    + inviteeMember.getPublicId())
                .header("Authorization", "Bearer " + leaderJwt))
            .andExpect(status().isNoContent());
        assertThat(memberRepository.existsByGroupIdAndUserId(group.getId(), invitee.getId()))
            .as("invitee should no longer be a member after remove")
            .isFalse();

        // Step 6: leader dissolves the group.
        mvc.perform(delete("/api/v1/realty-groups/" + groupPid)
                .header("Authorization", "Bearer " + leaderJwt))
            .andExpect(status().isNoContent());
        RealtyGroup dissolved = groupRepository.findById(group.getId()).orElseThrow();
        assertThat(dissolved.getDissolvedAt())
            .as("dissolved_at should be set after the leader dissolves")
            .isNotNull();
    }

    // ─────────────────────── Scenario 2: multi-group membership ───────────────────────

    @Test
    void multiGroupMembership_userAppearsInEveryGroupTheyJoin() throws Exception {
        // userA leads G1 and G3, is an agent in G2 (created by leader, invitee accepts).
        User userA = persistUser("multi-a");
        String userAJwt = issueJwt(userA, Role.USER);

        UUID g1 = createGroup(userAJwt, "Multi G1 " + suffix());
        UUID g3 = createGroup(userAJwt, "Multi G3 " + suffix());

        UUID g2 = createGroup(leaderJwt, "Multi G2 " + suffix());
        UUID invitePid = invite(leaderJwt, g2, userA.getUsername(), List.of());
        mvc.perform(post("/api/v1/me/invitations/" + invitePid + "/accept")
                .header("Authorization", "Bearer " + userAJwt))
            .andExpect(status().isOk());

        // GET /me/realty-groups returns all three.
        MvcResult result = mvc.perform(get("/api/v1/me/realty-groups")
                .header("Authorization", "Bearer " + userAJwt))
            .andExpect(status().isOk())
            .andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(body)
            .as("user A's three groups should all appear in /me/realty-groups")
            .contains(g1.toString())
            .contains(g2.toString())
            .contains(g3.toString());

        // findActiveByMemberUserId returns the right set at the repo layer too.
        List<RealtyGroup> active = groupRepository.findActiveByMemberUserId(userA.getId());
        assertThat(active)
            .as("repo-level multi-group lookup should return all three groups")
            .hasSize(3)
            .extracting(RealtyGroup::getPublicId)
            .containsExactlyInAnyOrder(g1, g2, g3);
    }

    // ─────────────────────── Scenario 3: rename cooldown ───────────────────────

    @Test
    void renameCooldown_nonAdminWithin30Days_returns409() throws Exception {
        UUID groupPid = createGroup(leaderJwt, "Cooldown Recent " + suffix());
        RealtyGroup g = groupRepository.findByPublicId(groupPid).orElseThrow();
        // Simulate: leader renamed yesterday — well inside the 30-day cooldown.
        g.setLastRenamedAt(OffsetDateTime.now().minus(Duration.ofDays(1)));
        groupRepository.save(g);

        mvc.perform(patch("/api/v1/realty-groups/" + groupPid)
                .header("Authorization", "Bearer " + leaderJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Cooldown New Name " + suffix() + "\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("GROUP_RENAME_COOLDOWN"))
            .andExpect(jsonPath("$.cooldownEndsAt").exists());
    }

    @Test
    void renameCooldown_nonAdminAfter30Days_succeedsAndBumpsClock() throws Exception {
        UUID groupPid = createGroup(leaderJwt, "Cooldown Aged " + suffix());
        RealtyGroup g = groupRepository.findByPublicId(groupPid).orElseThrow();
        OffsetDateTime longAgo = OffsetDateTime.now().minus(Duration.ofDays(31));
        g.setLastRenamedAt(longAgo);
        groupRepository.save(g);

        String newName = "Cooldown Renamed " + suffix();
        OffsetDateTime before = OffsetDateTime.now().minusSeconds(2);

        mvc.perform(patch("/api/v1/realty-groups/" + groupPid)
                .header("Authorization", "Bearer " + leaderJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + newName + "\"}"))
            .andExpect(status().isOk());

        RealtyGroup fresh = groupRepository.findById(g.getId()).orElseThrow();
        assertThat(fresh.getName()).isEqualTo(newName);
        assertThat(fresh.getLastRenamedAt())
            .as("non-admin successful rename should bump last_renamed_at to now")
            .isAfter(before);
        // Slug recomputed.
        assertThat(fresh.getSlug()).contains("cooldown-renamed");
    }

    @Test
    void renameCooldown_adminBypass_doesNotBumpLeaderClock() throws Exception {
        UUID groupPid = createGroup(leaderJwt, "Admin Cooldown " + suffix());
        RealtyGroup g = groupRepository.findByPublicId(groupPid).orElseThrow();
        OffsetDateTime priorClock = OffsetDateTime.now().minus(Duration.ofDays(1));
        g.setLastRenamedAt(priorClock);
        groupRepository.save(g);

        String adminName = "Admin Renamed " + suffix();
        mvc.perform(patch("/api/v1/admin/realty-groups/" + groupPid)
                .header("Authorization", "Bearer " + adminJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + adminName + "\"}"))
            .andExpect(status().isOk());

        RealtyGroup fresh = groupRepository.findById(g.getId()).orElseThrow();
        assertThat(fresh.getName()).isEqualTo(adminName);
        // last_renamed_at must NOT be bumped by admin renames — the leader's cooldown
        // ledger only advances on leader-initiated renames per spec §3.5.
        assertThat(fresh.getLastRenamedAt())
            .as("admin rename must not bump last_renamed_at")
            .isCloseTo(priorClock, within1Second());
    }

    // ─────────────────────── Scenario 4: case-insensitive name conflict ───────────────────────

    @Test
    void caseInsensitiveNameConflict_blockedWhenActive_allowedAfterDissolve() throws Exception {
        String baseName = "Mainland Realty " + suffix();

        UUID firstPid = createGroup(leaderJwt, baseName);

        // CREATE with an upper-cased variant — must collide on the partial unique index
        // (name_lower CITEXT).
        mvc.perform(post("/api/v1/realty-groups")
                .header("Authorization", "Bearer " + leaderJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + baseName.toUpperCase() + "\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("GROUP_NAME_TAKEN"));

        // Dissolve the first group, then re-create with the same name — the partial unique
        // index excludes dissolved rows, so the second creation must succeed.
        mvc.perform(delete("/api/v1/realty-groups/" + firstPid)
                .header("Authorization", "Bearer " + leaderJwt))
            .andExpect(status().isNoContent());

        mvc.perform(post("/api/v1/realty-groups")
                .header("Authorization", "Bearer " + leaderJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + baseName.toUpperCase() + "\"}"))
            .andExpect(status().isCreated());
    }

    // ─────────────────────── Scenario 6: soft-delete + name reuse ───────────────────────

    @Test
    void softDelete_nameAndSlugImmediatelyReusable() throws Exception {
        String name = "Reusable Co " + suffix();
        UUID firstPid = createGroup(leaderJwt, name);
        RealtyGroup first = groupRepository.findByPublicId(firstPid).orElseThrow();
        String firstSlug = first.getSlug();

        mvc.perform(delete("/api/v1/realty-groups/" + firstPid)
                .header("Authorization", "Bearer " + leaderJwt))
            .andExpect(status().isNoContent());

        // Fresh group with the same name → succeeds and reuses the slug.
        UUID secondPid = createGroup(leaderJwt, name);
        RealtyGroup second = groupRepository.findByPublicId(secondPid).orElseThrow();
        assertThat(second.getSlug())
            .as("dissolved row's slug should be immediately reusable by a fresh group")
            .isEqualTo(firstSlug);
    }

    // ─────────────────────── Scenario 7: expiry job ───────────────────────

    @Test
    void expiryJob_flipsOverduePendingInvitationsToExpired() throws Exception {
        UUID groupPid = createGroup(leaderJwt, "Expiry Job " + suffix());
        UUID invitePid = invite(leaderJwt, groupPid, invitee.getUsername(), List.of());

        // Walk the invitation's expires_at back to an hour ago.
        RealtyGroupInvitation inv = invitationRepository.findByPublicId(invitePid).orElseThrow();
        inv.setExpiresAt(OffsetDateTime.now().minusHours(1));
        invitationRepository.save(inv);

        expiryJob.expirePendingInvitations();

        RealtyGroupInvitation fresh = invitationRepository.findByPublicId(invitePid).orElseThrow();
        assertThat(fresh.getStatus())
            .as("overdue PENDING invitation should be flipped to EXPIRED by the job")
            .isEqualTo(InvitationStatus.EXPIRED);
        assertThat(fresh.getRespondedAt()).isNotNull();
    }

    // ─────────────────────── Scenario 8: admin force-dissolve ───────────────────────

    @Test
    void adminForceDissolve_preservesMemberRows_andUnlocksNameReuse() throws Exception {
        UUID groupPid = createGroup(leaderJwt, "Force Dissolve " + suffix());

        // Seed two extra agents on top of the leader.
        User a2 = persistUser("a2");
        User a3 = persistUser("a3");
        UUID i2 = invite(leaderJwt, groupPid, a2.getUsername(), List.of());
        UUID i3 = invite(leaderJwt, groupPid, a3.getUsername(), List.of());
        mvc.perform(post("/api/v1/me/invitations/" + i2 + "/accept")
                .header("Authorization", "Bearer " + issueJwt(a2, Role.USER))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/me/invitations/" + i3 + "/accept")
                .header("Authorization", "Bearer " + issueJwt(a3, Role.USER))).andExpect(status().isOk());

        RealtyGroup g = groupRepository.findByPublicId(groupPid).orElseThrow();
        assertThat(memberRepository.countByGroupId(g.getId())).isEqualTo(3);

        mvc.perform(delete("/api/v1/admin/realty-groups/" + groupPid)
                .header("Authorization", "Bearer " + adminJwt))
            .andExpect(status().isNoContent());

        RealtyGroup dissolved = groupRepository.findById(g.getId()).orElseThrow();
        assertThat(dissolved.getDissolvedAt()).isNotNull();
        // Member rows are preserved for the audit trail (force-dissolve is soft-delete on
        // the group; member rows live on for historical lookup).
        assertThat(memberRepository.countByGroupId(g.getId()))
            .as("member rows must survive admin force-dissolve for audit")
            .isEqualTo(3);

        // Name reuse: a brand-new group with the same name must be creatable post-dissolve.
        mvc.perform(post("/api/v1/realty-groups")
                .header("Authorization", "Bearer " + leaderJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + dissolved.getName() + "\"}"))
            .andExpect(status().isCreated());
    }

    // ─────────────────────── Scenario 9: leader cannot leave; transfer first ───────────────────────

    @Test
    void leaderCannotLeave_butCanLeaveAfterTransferringLeadership() throws Exception {
        UUID groupPid = createGroup(leaderJwt, "Leader Leave " + suffix());
        // Invitee joins as an agent so there's a valid transfer target.
        UUID invitePid = invite(leaderJwt, groupPid, invitee.getUsername(), List.of());
        mvc.perform(post("/api/v1/me/invitations/" + invitePid + "/accept")
                .header("Authorization", "Bearer " + inviteeJwt))
            .andExpect(status().isOk());

        // Leader's POST /leave → 409 LEADER_CANNOT_LEAVE.
        mvc.perform(post("/api/v1/realty-groups/" + groupPid + "/leave")
                .header("Authorization", "Bearer " + leaderJwt))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("LEADER_CANNOT_LEAVE"));

        // Transfer leadership to the invitee, leader STAYs.
        RealtyGroup g = groupRepository.findByPublicId(groupPid).orElseThrow();
        RealtyGroupMember inviteeMember = memberRepository
            .findByGroupIdAndUserId(g.getId(), invitee.getId()).orElseThrow();
        mvc.perform(post("/api/v1/realty-groups/" + groupPid + "/transfer-leadership")
                .header("Authorization", "Bearer " + leaderJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"newLeaderPublicId\":\"" + inviteeMember.getPublicId()
                    + "\",\"oldLeaderAction\":\"STAY\"}"))
            .andExpect(status().isOk());

        // Former leader can now leave.
        mvc.perform(post("/api/v1/realty-groups/" + groupPid + "/leave")
                .header("Authorization", "Bearer " + leaderJwt))
            .andExpect(status().isNoContent());
        assertThat(memberRepository.existsByGroupIdAndUserId(g.getId(), leader.getId())).isFalse();
    }

    // ─────────────────────── Scenario 10: transfer target not a member ───────────────────────

    @Test
    void transferLeadership_targetIsNotAMember_returns400() throws Exception {
        UUID groupPid = createGroup(leaderJwt, "Transfer Outsider " + suffix());

        // The invitee user exists but has never been invited / accepted — they have no
        // member row in this group. The transfer-target check resolves member rows by
        // publicId, so we pass a random UUID standing in for an outsider; the service
        // surfaces TRANSFER_TARGET_NOT_MEMBER (400).
        mvc.perform(post("/api/v1/realty-groups/" + groupPid + "/transfer-leadership")
                .header("Authorization", "Bearer " + leaderJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"newLeaderPublicId\":\"" + UUID.randomUUID()
                    + "\",\"oldLeaderAction\":\"STAY\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("TRANSFER_TARGET_NOT_MEMBER"));
    }

    // ─────────────────────── Scenario 12: seat limit enforcement ───────────────────────

    @Test
    void seatLimit_enforcedAtInvite_returns409WhenFull() throws Exception {
        UUID groupPid = createGroup(leaderJwt, "Seat Limit " + suffix());
        RealtyGroup g = groupRepository.findByPublicId(groupPid).orElseThrow();
        // Tighten the seat limit to 2 (leader + one agent slot). Add one agent so the
        // member count hits the cap, then an invite for a third user must 409.
        g.setMemberSeatLimit(2);
        groupRepository.save(g);

        // Seed one agent directly in the member table (already counts toward the cap).
        RealtyGroupMember agentRow = RealtyGroupMember.builder()
            .groupId(g.getId()).userId(invitee.getId()).joinedAt(OffsetDateTime.now()).build();
        agentRow.setPermissionSet(java.util.EnumSet.noneOf(RealtyGroupPermission.class));
        memberRepository.save(agentRow);

        // Now invite a third user — count == limit, so the invite must 409.
        User outsider = persistUser("outsider");
        mvc.perform(post("/api/v1/realty-groups/" + groupPid + "/invitations")
                .header("Authorization", "Bearer " + leaderJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"invitedUsername\":\"" + outsider.getUsername()
                    + "\",\"permissions\":[]}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("SEAT_LIMIT_REACHED"));
    }

    @Test
    void seatLimit_inviteSucceedsWhenSeatsRemain_butAcceptRaceRejectsWhenLimitTightenedMidflight()
            throws Exception {
        // Spec §3.5 / §11 race smoke: invite created while seat free, then the limit is
        // tightened (simulating a concurrent accept landing first), then accept re-checks
        // and 409s — covering the accept-time race-check branch.
        UUID groupPid = createGroup(leaderJwt, "Seat Race " + suffix());
        RealtyGroup g = groupRepository.findByPublicId(groupPid).orElseThrow();
        // Start with seats free.
        g.setMemberSeatLimit(50);
        groupRepository.save(g);

        UUID invitePid = invite(leaderJwt, groupPid, invitee.getUsername(), List.of());

        // Tighten the limit so the seat is no longer available at accept time.
        RealtyGroup tight = groupRepository.findById(g.getId()).orElseThrow();
        tight.setMemberSeatLimit(1);  // leader-only — no room for the invitee.
        groupRepository.save(tight);

        mvc.perform(post("/api/v1/me/invitations/" + invitePid + "/accept")
                .header("Authorization", "Bearer " + inviteeJwt))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("SEAT_LIMIT_REACHED"));
    }

    // ─────────────────────── helpers ───────────────────────

    /** Issue a POST /api/v1/realty-groups and return the created publicId. */
    private UUID createGroup(String jwt, String name) throws Exception {
        MvcResult res = mvc.perform(post("/api/v1/realty-groups")
                .header("Authorization", "Bearer " + jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\"}"))
            .andExpect(status().isCreated())
            .andReturn();
        var node = objectMapper.readTree(res.getResponse().getContentAsString());
        return UUID.fromString(node.get("publicId").asText());
    }

    /** Issue a POST /invitations and return the created invitation publicId. */
    private UUID invite(String jwt, UUID groupPid, String username, List<String> perms)
            throws Exception {
        String permsJson = perms.isEmpty()
            ? "[]"
            : "[\"" + String.join("\",\"", perms) + "\"]";
        MvcResult res = mvc.perform(post("/api/v1/realty-groups/" + groupPid + "/invitations")
                .header("Authorization", "Bearer " + jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"invitedUsername\":\"" + username
                    + "\",\"permissions\":" + permsJson + "}"))
            .andExpect(status().isCreated())
            .andReturn();
        var node = objectMapper.readTree(res.getResponse().getContentAsString());
        return UUID.fromString(node.get("publicId").asText());
    }

    private User persistUser(String label) {
        return userRepository.save(buildUser(label).build());
    }

    private User.UserBuilder<?, ?> buildUser(String label) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return User.builder()
            .username(label + "-" + suffix)
            .email(TEST_EMAIL_PREFIX + label + "-" + UUID.randomUUID() + "@test.local")
            .passwordHash("x")
            .displayName(label);
    }

    private String issueJwt(User u, Role role) {
        return jwtService.issueAccessToken(new AuthPrincipal(
            u.getId(), u.getPublicId(), u.getEmail(), 0L, role));
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static org.assertj.core.data.TemporalUnitOffset within1Second() {
        return new org.assertj.core.data.TemporalUnitWithinOffset(
            1, java.time.temporal.ChronoUnit.SECONDS);
    }
}
