package com.slparcelauctions.backend.wallet.sl.group;

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
import com.slparcelauctions.backend.escrow.terminal.Terminal;
import com.slparcelauctions.backend.escrow.terminal.TerminalRepository;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupMember;
import com.slparcelauctions.backend.realty.RealtyGroupMemberRepository;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.permission.RealtyGroupPermission;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Integration tests for {@code POST /api/v1/sl/wallet/avatar-groups}. Uses the
 * real SpringBoot context so the @Query native SQL + Postgres array operator
 * are exercised exactly as they run in production.
 *
 * <p>Auth-stage error mapping is owned by
 * {@code WalletSlExceptionHandler} — bad shared secret returns HTTP 200 with
 * body {@code status:"ERROR", reason:"SECRET_MISMATCH"} so the LSL caller can
 * parse on body content rather than HTTP status.
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
class SlAvatarGroupsControllerTest {

    private static final String SHARED_SECRET = "dev-escrow-secret-do-not-use-in-prod";
    private static final String TRUSTED_OWNER_KEY = "00000000-0000-0000-0000-000000000001";
    private static final String SHARD = "Production";
    private static final String URL = "/api/v1/sl/wallet/avatar-groups";

    @Autowired MockMvc mvc;
    @Autowired UserRepository userRepository;
    @Autowired RealtyGroupRepository groupRepository;
    @Autowired RealtyGroupMemberRepository memberRepository;
    @Autowired TerminalRepository terminalRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String terminalId;
    private User leader;

    @BeforeEach
    void seedTerminal() {
        terminalId = "terminal-avgroups-" + UUID.randomUUID();
        terminalRepository.save(Terminal.builder()
            .terminalId(terminalId)
            .httpInUrl("https://sim-test.agni.lindenlab.com:12043/cap/" + UUID.randomUUID())
            .regionName("AvatarGroupsTestRegion")
            .active(true)
            .lastSeenAt(OffsetDateTime.now())
            .build());

        leader = userRepository.save(User.builder()
            .username("avg-leader-" + UUID.randomUUID().toString().substring(0, 8))
            .email("avg-leader-" + UUID.randomUUID() + "@test.local")
            .passwordHash("x")
            .displayName("AvgLeader")
            .slAvatarUuid(UUID.randomUUID())
            .build());
    }

    private RealtyGroup seedGroup(User leaderUser, String displayName) {
        String slug = "avg-" + UUID.randomUUID().toString().substring(0, 8);
        RealtyGroup g = groupRepository.save(RealtyGroup.builder()
            .name(displayName)
            .slug(slug)
            .leaderId(leaderUser.getId())
            .balanceLindens(0L)
            .build());
        // Leader's row exists for query convenience — permissions are ignored
        // for the leader since the WHERE clause matches them via leader_id.
        memberRepository.save(RealtyGroupMember.builder()
            .groupId(g.getId())
            .userId(leaderUser.getId())
            .joinedAt(OffsetDateTime.now())
            .build());
        return g;
    }

    private String requestBody(String avatarUuid, String after) throws Exception {
        java.util.Map<String, Object> map = new java.util.HashMap<>();
        map.put("terminalId", terminalId);
        map.put("sharedSecret", SHARED_SECRET);
        map.put("avatarUuid", avatarUuid);
        if (after != null) {
            map.put("after", after);
        }
        return objectMapper.writeValueAsString(map);
    }

    // ─────────────────────────────────────────────────────────────────
    // Leader sees their own group
    // ─────────────────────────────────────────────────────────────────

    @Test
    void leader_sees_their_group() throws Exception {
        RealtyGroup g = seedGroup(leader, "Leader Group");

        mvc.perform(post(URL)
                .header("X-SecondLife-Shard", SHARD)
                .header("X-SecondLife-Owner-Key", TRUSTED_OWNER_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody(leader.getSlAvatarUuid().toString(), null)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.groups.length()").value(1))
            .andExpect(jsonPath("$.groups[0].publicId").value(g.getPublicId().toString()))
            .andExpect(jsonPath("$.groups[0].name").value("Leader Group"))
            .andExpect(jsonPath("$.hasMore").value(false))
            .andExpect(jsonPath("$.nextAfter").doesNotExist());
    }

    // ─────────────────────────────────────────────────────────────────
    // Agent without DEPOSIT_TO_GROUP_WALLET — empty list
    // ─────────────────────────────────────────────────────────────────

    @Test
    void agent_without_permission_sees_empty() throws Exception {
        RealtyGroup g = seedGroup(leader, "No-Perm Group");

        User agent = userRepository.save(User.builder()
            .username("avg-np-" + UUID.randomUUID().toString().substring(0, 8))
            .email("avg-np-" + UUID.randomUUID() + "@test.local")
            .passwordHash("x")
            .displayName("NoPermAgent")
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
                .content(requestBody(agent.getSlAvatarUuid().toString(), null)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.groups.length()").value(0))
            .andExpect(jsonPath("$.hasMore").value(false));
    }

    // ─────────────────────────────────────────────────────────────────
    // Agent with DEPOSIT_TO_GROUP_WALLET sees the group
    // ─────────────────────────────────────────────────────────────────

    @Test
    void agent_with_permission_sees_group() throws Exception {
        RealtyGroup g = seedGroup(leader, "Perm Group");

        User agent = userRepository.save(User.builder()
            .username("avg-pa-" + UUID.randomUUID().toString().substring(0, 8))
            .email("avg-pa-" + UUID.randomUUID() + "@test.local")
            .passwordHash("x")
            .displayName("PermAgent")
            .slAvatarUuid(UUID.randomUUID())
            .build());
        RealtyGroupMember m = RealtyGroupMember.builder()
            .groupId(g.getId())
            .userId(agent.getId())
            .joinedAt(OffsetDateTime.now())
            .build();
        m.setPermissionSet(EnumSet.of(RealtyGroupPermission.DEPOSIT_TO_GROUP_WALLET));
        memberRepository.save(m);

        mvc.perform(post(URL)
                .header("X-SecondLife-Shard", SHARD)
                .header("X-SecondLife-Owner-Key", TRUSTED_OWNER_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody(agent.getSlAvatarUuid().toString(), null)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.groups.length()").value(1))
            .andExpect(jsonPath("$.groups[0].publicId").value(g.getPublicId().toString()))
            .andExpect(jsonPath("$.groups[0].name").value("Perm Group"))
            .andExpect(jsonPath("$.hasMore").value(false));
    }

    // ─────────────────────────────────────────────────────────────────
    // Avatar UUID with no linked SLParcels user — empty list, not 4xx
    // ─────────────────────────────────────────────────────────────────

    @Test
    void unknown_avatar_returns_empty_list() throws Exception {
        UUID unlinked = UUID.randomUUID();

        mvc.perform(post(URL)
                .header("X-SecondLife-Shard", SHARD)
                .header("X-SecondLife-Owner-Key", TRUSTED_OWNER_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody(unlinked.toString(), null)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.groups.length()").value(0))
            .andExpect(jsonPath("$.hasMore").value(false))
            .andExpect(jsonPath("$.nextAfter").doesNotExist());
    }

    // ─────────────────────────────────────────────────────────────────
    // Pagination: 13 groups → page1 = 12 + hasMore + nextAfter, page2 = 1
    // ─────────────────────────────────────────────────────────────────

    @Test
    void pagination_returns_hasMore_and_nextAfter() throws Exception {
        // Names are alphabetised lowercase — use a stable prefix + zero-padded
        // index so the lexicographic order matches the controller's ordering
        // contract. PAGE_SIZE is 12, so 13 groups spill to a second page.
        for (int i = 1; i <= 13; i++) {
            seedGroup(leader, String.format("Page Group %02d", i));
        }

        MvcResult first = mvc.perform(post(URL)
                .header("X-SecondLife-Shard", SHARD)
                .header("X-SecondLife-Owner-Key", TRUSTED_OWNER_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody(leader.getSlAvatarUuid().toString(), null)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.groups.length()").value(12))
            .andExpect(jsonPath("$.hasMore").value(true))
            .andExpect(jsonPath("$.nextAfter").value("Page Group 12"))
            .andReturn();

        JsonNode firstJson = objectMapper.readTree(first.getResponse().getContentAsString());
        String cursor = firstJson.get("nextAfter").asText();

        mvc.perform(post(URL)
                .header("X-SecondLife-Shard", SHARD)
                .header("X-SecondLife-Owner-Key", TRUSTED_OWNER_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody(leader.getSlAvatarUuid().toString(), cursor)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.groups.length()").value(1))
            .andExpect(jsonPath("$.groups[0].name").value("Page Group 13"))
            .andExpect(jsonPath("$.hasMore").value(false))
            .andExpect(jsonPath("$.nextAfter").doesNotExist());
    }

    // ─────────────────────────────────────────────────────────────────
    // Bad shared secret — 200 + status:"ERROR" reason:"SECRET_MISMATCH"
    // ─────────────────────────────────────────────────────────────────

    @Test
    void bad_shared_secret_returns_error_body() throws Exception {
        java.util.Map<String, Object> map = new java.util.HashMap<>();
        map.put("terminalId", terminalId);
        map.put("sharedSecret", "wrong-secret");
        map.put("avatarUuid", leader.getSlAvatarUuid().toString());
        String body = objectMapper.writeValueAsString(map);

        // WalletSlExceptionHandler returns HTTP 200 with body status=ERROR so
        // the LSL caller parses on the body field — that handler is
        // package-scoped to com.slparcelauctions.backend.wallet.sl and covers
        // this sub-package too.
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
