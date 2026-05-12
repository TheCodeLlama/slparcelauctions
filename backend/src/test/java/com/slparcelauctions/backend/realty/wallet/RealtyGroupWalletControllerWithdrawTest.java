package com.slparcelauctions.backend.realty.wallet;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
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
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Integration tests for POST /api/v1/realty/groups/{publicId}/wallet/withdraw.
 *
 * <p>Covers: 202 happy path; 403 missing permission; 410 dissolved group;
 * 422 insufficient balance; 422 leader terms not accepted; 422 leader frozen.
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
@Transactional
class RealtyGroupWalletControllerWithdrawTest {

    @Autowired MockMvc mvc;
    @Autowired JwtService jwtService;
    @Autowired UserRepository userRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    @Autowired RealtyGroupRepository groupRepository;
    @Autowired RealtyGroupMemberRepository memberRepository;

    private User leader;
    private String leaderJwt;
    private RealtyGroup group;

    @BeforeEach
    void seed() {
        // Leader must have wallet terms accepted + SL avatar UUID + not frozen
        leader = userRepository.save(User.builder()
            .username("ww-" + UUID.randomUUID().toString().substring(0, 8))
            .email("ww-" + UUID.randomUUID() + "@test.local")
            .passwordHash("x")
            .displayName("WithdrawLeader")
            .slAvatarUuid(UUID.randomUUID())
            .walletTermsAcceptedAt(OffsetDateTime.now().minusDays(1))
            .build());
        leaderJwt = jwtService.issueAccessToken(
            new AuthPrincipal(leader.getId(), leader.getPublicId(), leader.getEmail(), 0L, Role.USER));

        String slug = "wallet-wd-" + UUID.randomUUID().toString().substring(0, 8);
        group = groupRepository.save(RealtyGroup.builder()
            .name("Withdraw Test Group " + slug)
            .slug(slug)
            .leaderId(leader.getId())
            .balanceLindens(10000L)
            .build());

        memberRepository.save(RealtyGroupMember.builder()
            .groupId(group.getId())
            .userId(leader.getId())
            .joinedAt(OffsetDateTime.now())
            .build());
    }

    // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    // 202 happy path
    // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void withdraw_leader_202_happy_path() throws Exception {
        String body = objectMapper.writeValueAsString(java.util.Map.of(
            "amount", 500,
            "idempotencyKey", UUID.randomUUID().toString(),
            "recipient", "AVATAR"));

        mvc.perform(post("/api/v1/realty/groups/" + group.getPublicId() + "/wallet/withdraw")
                .header("Authorization", "Bearer " + leaderJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.queueId").isNumber())
            .andExpect(jsonPath("$.estimatedFulfillmentSeconds").isNumber());
    }

    // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    // 403 missing permission
    // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void withdraw_member_without_permission_returns_403() throws Exception {
        User delegate = userRepository.save(User.builder()
            .username("wwm-" + UUID.randomUUID().toString().substring(0, 8))
            .email("wwm-" + UUID.randomUUID() + "@test.local")
            .passwordHash("x").displayName("NoPermDelegate").build());
        RealtyGroupMember m = RealtyGroupMember.builder()
            .groupId(group.getId())
            .userId(delegate.getId())
            .joinedAt(OffsetDateTime.now())
            .build();
        // VIEW_GROUP_TRANSACTIONS only â€” no WITHDRAW_FROM_GROUP_WALLET
        m.setPermissionSet(java.util.EnumSet.of(RealtyGroupPermission.VIEW_GROUP_TRANSACTIONS));
        memberRepository.save(m);

        String delegateJwt = jwtService.issueAccessToken(
            new AuthPrincipal(delegate.getId(), delegate.getPublicId(), delegate.getEmail(), 0L, Role.USER));

        String body = objectMapper.writeValueAsString(java.util.Map.of(
            "amount", 500,
            "idempotencyKey", UUID.randomUUID().toString(),
            "recipient", "AVATAR"));

        mvc.perform(post("/api/v1/realty/groups/" + group.getPublicId() + "/wallet/withdraw")
                .header("Authorization", "Bearer " + delegateJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isForbidden());
    }

    // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    // 410 dissolved group
    // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void withdraw_dissolved_group_returns_410() throws Exception {
        // Dissolve the group
        group.setDissolvedAt(OffsetDateTime.now().minusHours(1));
        groupRepository.save(group);

        String body = objectMapper.writeValueAsString(java.util.Map.of(
            "amount", 500,
            "idempotencyKey", UUID.randomUUID().toString(),
            "recipient", "AVATAR"));

        mvc.perform(post("/api/v1/realty/groups/" + group.getPublicId() + "/wallet/withdraw")
                .header("Authorization", "Bearer " + leaderJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isGone())
            .andExpect(jsonPath("$.code").value("GROUP_DISSOLVED"));
    }

    // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    // 422 insufficient balance
    // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void withdraw_insufficient_balance_returns_422() throws Exception {
        String body = objectMapper.writeValueAsString(java.util.Map.of(
            "amount", 99999,  // group only has 10000
            "idempotencyKey", UUID.randomUUID().toString(),
            "recipient", "AVATAR"));

        mvc.perform(post("/api/v1/realty/groups/" + group.getPublicId() + "/wallet/withdraw")
                .header("Authorization", "Bearer " + leaderJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("INSUFFICIENT_GROUP_BALANCE"));
    }

    // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    // 422 leader terms not accepted
    // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void withdraw_leader_terms_not_accepted_returns_422() throws Exception {
        // Clear wallet terms on the leader
        leader.setWalletTermsAcceptedAt(null);
        userRepository.save(leader);

        String body = objectMapper.writeValueAsString(java.util.Map.of(
            "amount", 500,
            "idempotencyKey", UUID.randomUUID().toString(),
            "recipient", "AVATAR"));

        mvc.perform(post("/api/v1/realty/groups/" + group.getPublicId() + "/wallet/withdraw")
                .header("Authorization", "Bearer " + leaderJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("LEADER_TERMS_NOT_ACCEPTED"));
    }

    // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    // 422 leader frozen
    // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void withdraw_leader_frozen_returns_422() throws Exception {
        // Freeze the leader's wallet
        leader.setWalletFrozenAt(OffsetDateTime.now().minusHours(1));
        userRepository.save(leader);

        String body = objectMapper.writeValueAsString(java.util.Map.of(
            "amount", 500,
            "idempotencyKey", UUID.randomUUID().toString(),
            "recipient", "AVATAR"));

        mvc.perform(post("/api/v1/realty/groups/" + group.getPublicId() + "/wallet/withdraw")
                .header("Authorization", "Bearer " + leaderJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("LEADER_FROZEN"));
    }

    // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    // 404 unknown publicId
    // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void withdraw_unknown_publicId_returns_404() throws Exception {
        String body = objectMapper.writeValueAsString(java.util.Map.of(
            "amount", 500,
            "idempotencyKey", UUID.randomUUID().toString(),
            "recipient", "AVATAR"));

        mvc.perform(post("/api/v1/realty/groups/" + UUID.randomUUID() + "/wallet/withdraw")
                .header("Authorization", "Bearer " + leaderJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isNotFound());
    }

    // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    // 422 validation: amount must be positive
    // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void withdraw_zero_amount_returns_400() throws Exception {
        String body = objectMapper.writeValueAsString(java.util.Map.of(
            "amount", 0,
            "idempotencyKey", UUID.randomUUID().toString(),
            "recipient", "AVATAR"));

        mvc.perform(post("/api/v1/realty/groups/" + group.getPublicId() + "/wallet/withdraw")
                .header("Authorization", "Bearer " + leaderJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest());
    }
}
