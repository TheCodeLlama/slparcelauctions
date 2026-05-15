package com.slparcelauctions.backend.wallet.sl.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.EnumSet;
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
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slparcelauctions.backend.escrow.terminal.Terminal;
import com.slparcelauctions.backend.escrow.terminal.TerminalRepository;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupMember;
import com.slparcelauctions.backend.realty.RealtyGroupMemberRepository;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.permission.RealtyGroupPermission;
import com.slparcelauctions.backend.realty.wallet.RealtyGroupLedgerEntry;
import com.slparcelauctions.backend.realty.wallet.RealtyGroupLedgerEntryType;
import com.slparcelauctions.backend.realty.wallet.RealtyGroupLedgerRepository;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Integration tests for {@code POST /api/v1/sl/wallet/group-deposit}. Uses the
 * real SpringBoot context so the L$-bearing refund discipline is exercised end
 * to end — every post-auth failure returns {@code REFUND}, never {@code ERROR}.
 *
 * <p>Auth-stage failures (bad headers / shared-secret mismatch) are owned by
 * {@code WalletSlExceptionHandler} which is package-scoped to
 * {@code com.slparcelauctions.backend.wallet.sl} and therefore covers this
 * sub-package too — bad shared secret returns HTTP 200 with body
 * {@code status:"ERROR", reason:"SECRET_MISMATCH"}.
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
class SlGroupDepositControllerTest {

    private static final String SHARED_SECRET = "dev-escrow-secret-do-not-use-in-prod";
    private static final String TRUSTED_OWNER_KEY = "00000000-0000-0000-0000-000000000001";
    private static final String SHARD = "Production";
    private static final String URL = "/api/v1/sl/wallet/group-deposit";

    @Autowired MockMvc mvc;
    @Autowired UserRepository userRepository;
    @Autowired RealtyGroupRepository groupRepository;
    @Autowired RealtyGroupMemberRepository memberRepository;
    @Autowired RealtyGroupLedgerRepository ledgerRepository;
    @Autowired TerminalRepository terminalRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String terminalId;
    private User leader;

    @BeforeEach
    void seedTerminal() {
        terminalId = "terminal-gdep-" + UUID.randomUUID();
        terminalRepository.save(Terminal.builder()
            .terminalId(terminalId)
            .httpInUrl("https://sim-test.agni.lindenlab.com:12043/cap/" + UUID.randomUUID())
            .regionName("GroupDepositTestRegion")
            .active(true)
            .lastSeenAt(OffsetDateTime.now())
            .build());

        leader = userRepository.save(User.builder()
            .username("gdep-leader-" + UUID.randomUUID().toString().substring(0, 8))
            .email("gdep-leader-" + UUID.randomUUID() + "@test.local")
            .passwordHash("x")
            .displayName("GDepLeader")
            .slAvatarUuid(UUID.randomUUID())
            .build());
    }

    private RealtyGroup seedGroup(User leaderUser, String displayName) {
        String slug = "gdep-" + UUID.randomUUID().toString().substring(0, 8);
        RealtyGroup g = groupRepository.save(RealtyGroup.builder()
            .name(displayName)
            .slug(slug)
            .leaderId(leaderUser.getId())
            .balanceLindens(0L)
            .build());
        memberRepository.save(RealtyGroupMember.builder()
            .groupId(g.getId())
            .userId(leaderUser.getId())
            .joinedAt(OffsetDateTime.now())
            .build());
        return g;
    }

    private String requestBody(String payerUuid, UUID groupPublicId, long amount, String slTxn)
            throws Exception {
        java.util.Map<String, Object> map = new java.util.HashMap<>();
        map.put("terminalId", terminalId);
        map.put("sharedSecret", SHARED_SECRET);
        map.put("payerUuid", payerUuid);
        map.put("groupPublicId", groupPublicId.toString());
        map.put("amount", amount);
        map.put("slTransactionKey", slTxn);
        return objectMapper.writeValueAsString(map);
    }

    private String requestBodyWithSecret(String payerUuid, UUID groupPublicId, long amount,
            String slTxn, String secret) throws Exception {
        java.util.Map<String, Object> map = new java.util.HashMap<>();
        map.put("terminalId", terminalId);
        map.put("sharedSecret", secret);
        map.put("payerUuid", payerUuid);
        map.put("groupPublicId", groupPublicId.toString());
        map.put("amount", amount);
        map.put("slTransactionKey", slTxn);
        return objectMapper.writeValueAsString(map);
    }

    // ─────────────────────────────────────────────────────────────────
    // 1. Leader (implicit DEPOSIT_TO_GROUP_WALLET) deposits to their group.
    // ─────────────────────────────────────────────────────────────────

    @Test
    void member_with_permission_deposits_to_group() throws Exception {
        RealtyGroup g = seedGroup(leader, "Leader Deposit Group");
        String slTxn = UUID.randomUUID().toString();

        mvc.perform(post(URL)
                .header("X-SecondLife-Shard", SHARD)
                .header("X-SecondLife-Owner-Key", TRUSTED_OWNER_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody(leader.getSlAvatarUuid().toString(),
                    g.getPublicId(), 500L, slTxn)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("OK"));

        RealtyGroup reloaded = groupRepository.findById(g.getId()).orElseThrow();
        assertThat(reloaded.getBalanceLindens()).isEqualTo(500L);

        List<RealtyGroupLedgerEntry> rows = ledgerRepository.findAll().stream()
            .filter(r -> r.getGroupId().equals(g.getId()))
            .toList();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getEntryType()).isEqualTo(RealtyGroupLedgerEntryType.MEMBER_DEPOSIT);
        assertThat(rows.get(0).getAmount()).isEqualTo(500L);
        assertThat(rows.get(0).getIdempotencyKey()).isEqualTo(slTxn);
        assertThat(rows.get(0).getSlTransactionId()).isEqualTo(slTxn);
        assertThat(rows.get(0).getActorUserId()).isEqualTo(leader.getId());
    }

    // ─────────────────────────────────────────────────────────────────
    // 2. User without DEPOSIT_TO_GROUP_WALLET — REFUND/PERMISSION_REVOKED.
    // ─────────────────────────────────────────────────────────────────

    @Test
    void permission_revoked_returns_refund() throws Exception {
        RealtyGroup g = seedGroup(leader, "No-Perm Group");
        // Non-member with a linked avatar — assertCan returns false, controller
        // refunds. (Using a non-member is the simplest "no permission" case;
        // an ex-member whose row was deleted hits the same path.)
        User outsider = userRepository.save(User.builder()
            .username("gdep-out-" + UUID.randomUUID().toString().substring(0, 8))
            .email("gdep-out-" + UUID.randomUUID() + "@test.local")
            .passwordHash("x")
            .displayName("Outsider")
            .slAvatarUuid(UUID.randomUUID())
            .build());

        long balanceBefore = g.getBalanceLindens();
        mvc.perform(post(URL)
                .header("X-SecondLife-Shard", SHARD)
                .header("X-SecondLife-Owner-Key", TRUSTED_OWNER_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody(outsider.getSlAvatarUuid().toString(),
                    g.getPublicId(), 200L, UUID.randomUUID().toString())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("REFUND"))
            .andExpect(jsonPath("$.reason").value("PERMISSION_REVOKED"));

        RealtyGroup reloaded = groupRepository.findById(g.getId()).orElseThrow();
        assertThat(reloaded.getBalanceLindens()).isEqualTo(balanceBefore);
    }

    @Test
    void agent_without_permission_returns_refund() throws Exception {
        // Member row exists but DEPOSIT_TO_GROUP_WALLET is not in the
        // permission set — assertCan returns false, controller refunds.
        RealtyGroup g = seedGroup(leader, "Agent No-Perm Group");
        User agent = userRepository.save(User.builder()
            .username("gdep-ag-" + UUID.randomUUID().toString().substring(0, 8))
            .email("gdep-ag-" + UUID.randomUUID() + "@test.local")
            .passwordHash("x")
            .displayName("AgentNoPerm")
            .slAvatarUuid(UUID.randomUUID())
            .build());
        RealtyGroupMember m = RealtyGroupMember.builder()
            .groupId(g.getId())
            .userId(agent.getId())
            .joinedAt(OffsetDateTime.now())
            .build();
        m.setPermissionSet(EnumSet.of(RealtyGroupPermission.VIEW_GROUP_TRANSACTIONS));
        memberRepository.save(m);

        mvc.perform(post(URL)
                .header("X-SecondLife-Shard", SHARD)
                .header("X-SecondLife-Owner-Key", TRUSTED_OWNER_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody(agent.getSlAvatarUuid().toString(),
                    g.getPublicId(), 100L, UUID.randomUUID().toString())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("REFUND"))
            .andExpect(jsonPath("$.reason").value("PERMISSION_REVOKED"));
    }

    // ─────────────────────────────────────────────────────────────────
    // 3. Dissolved group — REFUND/GROUP_DISSOLVED.
    // ─────────────────────────────────────────────────────────────────

    @Test
    void dissolved_group_returns_refund() throws Exception {
        RealtyGroup g = seedGroup(leader, "Dissolved Group");
        g.setDissolvedAt(OffsetDateTime.now().minusDays(1));
        groupRepository.save(g);

        mvc.perform(post(URL)
                .header("X-SecondLife-Shard", SHARD)
                .header("X-SecondLife-Owner-Key", TRUSTED_OWNER_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody(leader.getSlAvatarUuid().toString(),
                    g.getPublicId(), 100L, UUID.randomUUID().toString())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("REFUND"))
            .andExpect(jsonPath("$.reason").value("GROUP_DISSOLVED"));
    }

    // ─────────────────────────────────────────────────────────────────
    // 4. Unknown group publicId — REFUND/UNKNOWN_GROUP.
    // ─────────────────────────────────────────────────────────────────

    @Test
    void unknown_group_returns_refund() throws Exception {
        UUID unknown = UUID.randomUUID();
        mvc.perform(post(URL)
                .header("X-SecondLife-Shard", SHARD)
                .header("X-SecondLife-Owner-Key", TRUSTED_OWNER_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody(leader.getSlAvatarUuid().toString(),
                    unknown, 100L, UUID.randomUUID().toString())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("REFUND"))
            .andExpect(jsonPath("$.reason").value("UNKNOWN_GROUP"));
    }

    // ─────────────────────────────────────────────────────────────────
    // 5. Payer avatar has no linked SLParcels account — REFUND/UNKNOWN_PAYER.
    // ─────────────────────────────────────────────────────────────────

    @Test
    void unknown_payer_returns_refund() throws Exception {
        RealtyGroup g = seedGroup(leader, "Unknown Payer Group");
        UUID strangerAvatar = UUID.randomUUID();

        mvc.perform(post(URL)
                .header("X-SecondLife-Shard", SHARD)
                .header("X-SecondLife-Owner-Key", TRUSTED_OWNER_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody(strangerAvatar.toString(),
                    g.getPublicId(), 100L, UUID.randomUUID().toString())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("REFUND"))
            .andExpect(jsonPath("$.reason").value("UNKNOWN_PAYER"));
    }

    // ─────────────────────────────────────────────────────────────────
    // 6. Idempotent replay on the same slTransactionKey — both OK, single credit.
    // ─────────────────────────────────────────────────────────────────

    @Test
    void idempotent_replay_returns_ok_with_same_ledger() throws Exception {
        RealtyGroup g = seedGroup(leader, "Idempotent Group");
        String slTxn = UUID.randomUUID().toString();
        String body = requestBody(leader.getSlAvatarUuid().toString(),
            g.getPublicId(), 750L, slTxn);

        mvc.perform(post(URL)
                .header("X-SecondLife-Shard", SHARD)
                .header("X-SecondLife-Owner-Key", TRUSTED_OWNER_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("OK"));

        // Replay -- same slTransactionKey. Must still return OK so the LSL
        // script doesn't bounce a duplicate.
        mvc.perform(post(URL)
                .header("X-SecondLife-Shard", SHARD)
                .header("X-SecondLife-Owner-Key", TRUSTED_OWNER_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("OK"));

        RealtyGroup reloaded = groupRepository.findById(g.getId()).orElseThrow();
        assertThat(reloaded.getBalanceLindens()).isEqualTo(750L);

        long ledgerCount = ledgerRepository.findAll().stream()
            .filter(r -> r.getGroupId().equals(g.getId())
                      && r.getEntryType() == RealtyGroupLedgerEntryType.MEMBER_DEPOSIT)
            .count();
        assertThat(ledgerCount).isEqualTo(1);
    }

    // ─────────────────────────────────────────────────────────────────
    // 7. Bad shared secret — 200 + status:"ERROR" reason:"SECRET_MISMATCH"
    //    (mapped by WalletSlExceptionHandler).
    // ─────────────────────────────────────────────────────────────────

    @Test
    void bad_shared_secret_returns_error() throws Exception {
        RealtyGroup g = seedGroup(leader, "Bad Secret Group");
        String body = requestBodyWithSecret(leader.getSlAvatarUuid().toString(),
            g.getPublicId(), 100L, UUID.randomUUID().toString(), "wrong-secret");

        mvc.perform(post(URL)
                .header("X-SecondLife-Shard", SHARD)
                .header("X-SecondLife-Owner-Key", TRUSTED_OWNER_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ERROR"))
            .andExpect(jsonPath("$.reason").value("SECRET_MISMATCH"));
    }
}
