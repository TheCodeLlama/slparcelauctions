package com.slparcelauctions.backend.realty.wallet;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.EnumSet;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
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
 * Integration tests for POST /api/v1/realty/groups/{publicId}/wallet/deposit.
 *
 * <p>Covers: 200 leader happy path; 403 missing permission; 200 agent with
 * permission; 400 insufficient balance; 400 amount above configured max;
 * 410 dissolved group; idempotent replay returns the same ledger ids.
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
class RealtyGroupDepositControllerTest {

    @Autowired MockMvc mvc;
    @Autowired JwtService jwtService;
    @Autowired UserRepository userRepository;
    @Autowired RealtyGroupRepository groupRepository;
    @Autowired RealtyGroupMemberRepository memberRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private User leader;
    private String leaderJwt;
    private RealtyGroup group;

    @BeforeEach
    void seed() {
        leader = userRepository.save(User.builder()
            .username("dep-" + UUID.randomUUID().toString().substring(0, 8))
            .email("dep-" + UUID.randomUUID() + "@test.local")
            .passwordHash("x")
            .displayName("DepositLeader")
            .balanceLindens(5_000L)
            .build());
        leaderJwt = jwtService.issueAccessToken(
            new AuthPrincipal(leader.getId(), leader.getPublicId(), leader.getEmail(), 0L, Role.USER));

        String slug = "wallet-dep-" + UUID.randomUUID().toString().substring(0, 8);
        group = groupRepository.save(RealtyGroup.builder()
            .name("Deposit Test Group " + slug)
            .slug(slug)
            .leaderId(leader.getId())
            .balanceLindens(0L)
            .build());

        memberRepository.save(RealtyGroupMember.builder()
            .groupId(group.getId())
            .userId(leader.getId())
            .joinedAt(OffsetDateTime.now())
            .build());
    }

    // ─────────────────────────────────────────────────────────────────
    // 200 leader happy path
    // ─────────────────────────────────────────────────────────────────

    @Test
    void leader_can_deposit() throws Exception {
        String body = objectMapper.writeValueAsString(java.util.Map.of(
            "amount", 1_000,
            "memo", "Reimbursement",
            "idempotencyKey", UUID.randomUUID().toString()));

        mvc.perform(post("/api/v1/realty/groups/" + group.getPublicId() + "/wallet/deposit")
                .header("Authorization", "Bearer " + leaderJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.groupLedgerEntryId").isNumber())
            .andExpect(jsonPath("$.personalLedgerEntryId").isNumber())
            .andExpect(jsonPath("$.newGroupAvailable").value(1_000))
            .andExpect(jsonPath("$.newPersonalAvailable").value(4_000));
    }

    // ─────────────────────────────────────────────────────────────────
    // 403 agent without DEPOSIT_TO_GROUP_WALLET
    // ─────────────────────────────────────────────────────────────────

    @Test
    void agent_without_permission_is_forbidden() throws Exception {
        User agent = userRepository.save(User.builder()
            .username("dep-na-" + UUID.randomUUID().toString().substring(0, 8))
            .email("dep-na-" + UUID.randomUUID() + "@test.local")
            .passwordHash("x")
            .displayName("NoPermAgent")
            .balanceLindens(5_000L)
            .build());
        RealtyGroupMember m = RealtyGroupMember.builder()
            .groupId(group.getId())
            .userId(agent.getId())
            .joinedAt(OffsetDateTime.now())
            .build();
        m.setPermissionSet(EnumSet.of(RealtyGroupPermission.VIEW_GROUP_TRANSACTIONS));
        memberRepository.save(m);

        String agentJwt = jwtService.issueAccessToken(
            new AuthPrincipal(agent.getId(), agent.getPublicId(), agent.getEmail(), 0L, Role.USER));

        String body = objectMapper.writeValueAsString(java.util.Map.of(
            "amount", 500,
            "idempotencyKey", UUID.randomUUID().toString()));

        mvc.perform(post("/api/v1/realty/groups/" + group.getPublicId() + "/wallet/deposit")
                .header("Authorization", "Bearer " + agentJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isForbidden());
    }

    // ─────────────────────────────────────────────────────────────────
    // 200 agent with DEPOSIT_TO_GROUP_WALLET
    // ─────────────────────────────────────────────────────────────────

    @Test
    void agent_with_permission_can_deposit() throws Exception {
        User agent = userRepository.save(User.builder()
            .username("dep-pa-" + UUID.randomUUID().toString().substring(0, 8))
            .email("dep-pa-" + UUID.randomUUID() + "@test.local")
            .passwordHash("x")
            .displayName("PermAgent")
            .balanceLindens(2_500L)
            .build());
        RealtyGroupMember m = RealtyGroupMember.builder()
            .groupId(group.getId())
            .userId(agent.getId())
            .joinedAt(OffsetDateTime.now())
            .build();
        m.setPermissionSet(EnumSet.of(RealtyGroupPermission.DEPOSIT_TO_GROUP_WALLET));
        memberRepository.save(m);

        String agentJwt = jwtService.issueAccessToken(
            new AuthPrincipal(agent.getId(), agent.getPublicId(), agent.getEmail(), 0L, Role.USER));

        String body = objectMapper.writeValueAsString(java.util.Map.of(
            "amount", 750,
            "idempotencyKey", UUID.randomUUID().toString()));

        mvc.perform(post("/api/v1/realty/groups/" + group.getPublicId() + "/wallet/deposit")
                .header("Authorization", "Bearer " + agentJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.newGroupAvailable").value(750))
            .andExpect(jsonPath("$.newPersonalAvailable").value(1_750));
    }

    // ─────────────────────────────────────────────────────────────────
    // 400 insufficient balance
    // ─────────────────────────────────────────────────────────────────

    @Test
    void rejects_insufficient_balance() throws Exception {
        // Reset leader's balance to L$10
        leader.setBalanceLindens(10L);
        userRepository.save(leader);

        String body = objectMapper.writeValueAsString(java.util.Map.of(
            "amount", 100,
            "idempotencyKey", UUID.randomUUID().toString()));

        mvc.perform(post("/api/v1/realty/groups/" + group.getPublicId() + "/wallet/deposit")
                .header("Authorization", "Bearer " + leaderJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("INSUFFICIENT_BALANCE")))
            .andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE"));
    }

    // ─────────────────────────────────────────────────────────────────
    // 400 amount above configured max
    // ─────────────────────────────────────────────────────────────────

    @Test
    void rejects_amount_above_max() throws Exception {
        // Default ceiling is 500_000; send 1_000_000.
        // Give the leader enough balance so the range check (which runs first)
        // is the one that trips — otherwise the insufficient-balance check
        // could mask the assertion.
        leader.setBalanceLindens(2_000_000L);
        userRepository.save(leader);

        String body = objectMapper.writeValueAsString(java.util.Map.of(
            "amount", 1_000_000,
            "idempotencyKey", UUID.randomUUID().toString()));

        mvc.perform(post("/api/v1/realty/groups/" + group.getPublicId() + "/wallet/deposit")
                .header("Authorization", "Bearer " + leaderJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("AMOUNT_OUT_OF_RANGE")))
            .andExpect(jsonPath("$.code").value("AMOUNT_OUT_OF_RANGE"))
            .andExpect(jsonPath("$.min").value(1))
            .andExpect(jsonPath("$.max").value(500_000));
    }

    // ─────────────────────────────────────────────────────────────────
    // 410 dissolved group
    // ─────────────────────────────────────────────────────────────────

    @Test
    void dissolved_group_returns_410() throws Exception {
        group.setDissolvedAt(OffsetDateTime.now().minusHours(1));
        groupRepository.save(group);

        String body = objectMapper.writeValueAsString(java.util.Map.of(
            "amount", 500,
            "idempotencyKey", UUID.randomUUID().toString()));

        mvc.perform(post("/api/v1/realty/groups/" + group.getPublicId() + "/wallet/deposit")
                .header("Authorization", "Bearer " + leaderJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isGone())
            .andExpect(jsonPath("$.code").value("GROUP_DISSOLVED"));
    }

    // ─────────────────────────────────────────────────────────────────
    // Idempotent replay returns same ledger ids
    // ─────────────────────────────────────────────────────────────────

    @Test
    void idempotent_replay_returns_same_ledger_ids() throws Exception {
        UUID idemKey = UUID.randomUUID();
        String body = objectMapper.writeValueAsString(java.util.Map.of(
            "amount", 250,
            "idempotencyKey", idemKey.toString()));

        MvcResult first = mvc.perform(
                post("/api/v1/realty/groups/" + group.getPublicId() + "/wallet/deposit")
                    .header("Authorization", "Bearer " + leaderJwt)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isOk())
            .andReturn();

        MvcResult second = mvc.perform(
                post("/api/v1/realty/groups/" + group.getPublicId() + "/wallet/deposit")
                    .header("Authorization", "Bearer " + leaderJwt)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isOk())
            .andReturn();

        JsonNode firstJson = objectMapper.readTree(first.getResponse().getContentAsString());
        JsonNode secondJson = objectMapper.readTree(second.getResponse().getContentAsString());

        // Same ledger row ids on replay — service short-circuits the second call.
        if (firstJson.get("groupLedgerEntryId").asLong()
                != secondJson.get("groupLedgerEntryId").asLong()) {
            throw new AssertionError("expected same groupLedgerEntryId on replay");
        }
        if (firstJson.get("personalLedgerEntryId").asLong()
                != secondJson.get("personalLedgerEntryId").asLong()) {
            throw new AssertionError("expected same personalLedgerEntryId on replay");
        }
        // Balances unchanged between the two calls (only one debit/credit happened).
        if (firstJson.get("newGroupAvailable").asLong()
                != secondJson.get("newGroupAvailable").asLong()) {
            throw new AssertionError("expected unchanged newGroupAvailable on replay");
        }
        if (firstJson.get("newPersonalAvailable").asLong()
                != secondJson.get("newPersonalAvailable").asLong()) {
            throw new AssertionError("expected unchanged newPersonalAvailable on replay");
        }
    }
}
