package com.slparcelauctions.backend.realty.wallet;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

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
 * Integration tests for GET endpoints on {@link RealtyGroupWalletController}.
 *
 * <p>Covers:
 * <ul>
 *   <li>{@code GET /api/v1/realty/groups/{publicId}/wallet} — leader 200, delegate-with-perm 200,
 *       outsider 403, unknown publicId 404, recentLedger present.</li>
 *   <li>{@code GET /api/v1/realty/groups/{publicId}/wallet/ledger} — leader 200, outsider 403.</li>
 * </ul>
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
class RealtyGroupWalletControllerGetTest {

    @Autowired MockMvc mvc;
    @Autowired JwtService jwtService;
    @Autowired UserRepository userRepository;
    @Autowired RealtyGroupRepository groupRepository;
    @Autowired RealtyGroupMemberRepository memberRepository;
    @Autowired RealtyGroupLedgerRepository ledgerRepository;

    private User leader;
    private String leaderJwt;
    private RealtyGroup group;

    @BeforeEach
    void seed() {
        leader = userRepository.save(User.builder()
            .username("wl-" + UUID.randomUUID().toString().substring(0, 8))
            .email("wl-" + UUID.randomUUID() + "@test.local")
            .passwordHash("x").displayName("Leader").build());
        leaderJwt = jwtService.issueAccessToken(
            new AuthPrincipal(leader.getId(), leader.getPublicId(), leader.getEmail(), 0L, Role.USER));

        String slug = "wallet-get-" + UUID.randomUUID().toString().substring(0, 8);
        group = groupRepository.save(RealtyGroup.builder()
            .name("Wallet Get Group " + slug)
            .slug(slug)
            .leaderId(leader.getId())
            .agentFeeRate(BigDecimal.ZERO)
            .balanceLindens(5000L)
            .build());

        // seed the leader's member row
        memberRepository.save(RealtyGroupMember.builder()
            .groupId(group.getId())
            .userId(leader.getId())
            .joinedAt(OffsetDateTime.now())
            .build());
    }

    // ─────────────────────────────────────────────────────────────────
    // GET /api/v1/realty/groups/{publicId}/wallet
    // ─────────────────────────────────────────────────────────────────

    @Test
    void get_wallet_leader_returns_200_with_balance() throws Exception {
        mvc.perform(get("/api/v1/realty/groups/" + group.getPublicId() + "/wallet")
                .header("Authorization", "Bearer " + leaderJwt))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.balance").value(5000))
            .andExpect(jsonPath("$.reserved").value(0))
            .andExpect(jsonPath("$.available").value(5000))
            .andExpect(jsonPath("$.recentLedger").isArray());
    }

    @Test
    void get_wallet_delegate_with_permission_returns_200() throws Exception {
        User delegate = userRepository.save(User.builder()
            .username("wd-" + UUID.randomUUID().toString().substring(0, 8))
            .email("wd-" + UUID.randomUUID() + "@test.local")
            .passwordHash("x").displayName("Delegate").build());
        RealtyGroupMember m = RealtyGroupMember.builder()
            .groupId(group.getId())
            .userId(delegate.getId())
            .joinedAt(OffsetDateTime.now())
            .build();
        m.setPermissionSet(java.util.EnumSet.of(RealtyGroupPermission.VIEW_GROUP_TRANSACTIONS));
        memberRepository.save(m);

        String delegateJwt = jwtService.issueAccessToken(
            new AuthPrincipal(delegate.getId(), delegate.getPublicId(), delegate.getEmail(), 0L, Role.USER));

        mvc.perform(get("/api/v1/realty/groups/" + group.getPublicId() + "/wallet")
                .header("Authorization", "Bearer " + delegateJwt))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.balance").value(5000));
    }

    @Test
    void get_wallet_outsider_returns_403() throws Exception {
        User outsider = userRepository.save(User.builder()
            .username("wo-" + UUID.randomUUID().toString().substring(0, 8))
            .email("wo-" + UUID.randomUUID() + "@test.local")
            .passwordHash("x").displayName("Outsider").build());
        String outsiderJwt = jwtService.issueAccessToken(
            new AuthPrincipal(outsider.getId(), outsider.getPublicId(), outsider.getEmail(), 0L, Role.USER));

        mvc.perform(get("/api/v1/realty/groups/" + group.getPublicId() + "/wallet")
                .header("Authorization", "Bearer " + outsiderJwt))
            .andExpect(status().isForbidden());
    }

    @Test
    void get_wallet_unknown_publicId_returns_404() throws Exception {
        mvc.perform(get("/api/v1/realty/groups/" + UUID.randomUUID() + "/wallet")
                .header("Authorization", "Bearer " + leaderJwt))
            .andExpect(status().isNotFound());
    }

    @Test
    void get_wallet_unauthenticated_returns_401() throws Exception {
        mvc.perform(get("/api/v1/realty/groups/" + group.getPublicId() + "/wallet"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void get_wallet_leader_sees_recent_ledger() throws Exception {
        // Seed a ledger entry for this group
        ledgerRepository.save(RealtyGroupLedgerEntry.builder()
            .groupId(group.getId())
            .entryType(RealtyGroupLedgerEntryType.AGENT_FEE_CREDIT)
            .amount(1200L)
            .balanceAfter(6200L)
            .reservedAfter(0L)
            .refType("AUCTION")
            .refId(99999L) // non-existent auction — resolves to null refPublicId
            .build());

        mvc.perform(get("/api/v1/realty/groups/" + group.getPublicId() + "/wallet")
                .header("Authorization", "Bearer " + leaderJwt))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.recentLedger").isArray())
            .andExpect(jsonPath("$.recentLedger[0].entryType").value("AGENT_FEE_CREDIT"))
            .andExpect(jsonPath("$.recentLedger[0].amount").value(1200));
    }

    // ─────────────────────────────────────────────────────────────────
    // GET /api/v1/realty/groups/{publicId}/wallet/ledger
    // ─────────────────────────────────────────────────────────────────

    @Test
    void get_ledger_leader_returns_200_empty_array() throws Exception {
        mvc.perform(get("/api/v1/realty/groups/" + group.getPublicId() + "/wallet/ledger")
                .header("Authorization", "Bearer " + leaderJwt))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray());
    }

    @Test
    void get_ledger_outsider_returns_403() throws Exception {
        User outsider = userRepository.save(User.builder()
            .username("wol-" + UUID.randomUUID().toString().substring(0, 8))
            .email("wol-" + UUID.randomUUID() + "@test.local")
            .passwordHash("x").displayName("LedgerOutsider").build());
        String outsiderJwt = jwtService.issueAccessToken(
            new AuthPrincipal(outsider.getId(), outsider.getPublicId(), outsider.getEmail(), 0L, Role.USER));

        mvc.perform(get("/api/v1/realty/groups/" + group.getPublicId() + "/wallet/ledger")
                .header("Authorization", "Bearer " + outsiderJwt))
            .andExpect(status().isForbidden());
    }

    @Test
    void get_ledger_unknown_publicId_returns_404() throws Exception {
        mvc.perform(get("/api/v1/realty/groups/" + UUID.randomUUID() + "/wallet/ledger")
                .header("Authorization", "Bearer " + leaderJwt))
            .andExpect(status().isNotFound());
    }

    @Test
    void get_ledger_respects_limit_param() throws Exception {
        // Seed 5 ledger entries
        for (int i = 0; i < 5; i++) {
            ledgerRepository.save(RealtyGroupLedgerEntry.builder()
                .groupId(group.getId())
                .entryType(RealtyGroupLedgerEntryType.ADJUSTMENT)
                .amount(10L + i)
                .balanceAfter(5010L + i)
                .reservedAfter(0L)
                .build());
        }

        mvc.perform(get("/api/v1/realty/groups/" + group.getPublicId() + "/wallet/ledger")
                .param("limit", "2")
                .header("Authorization", "Bearer " + leaderJwt))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2));
    }
}
