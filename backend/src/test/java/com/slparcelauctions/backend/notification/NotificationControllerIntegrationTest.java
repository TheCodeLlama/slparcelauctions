package com.slparcelauctions.backend.notification;

import static com.slparcelauctions.backend.notification.NotificationCategory.AUCTION_WON;
import static com.slparcelauctions.backend.notification.NotificationCategory.OUTBID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slparcelauctions.backend.user.UserRepository;
import org.springframework.transaction.support.TransactionTemplate;

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
        "slpa.notifications.sl-im.cleanup.enabled=false"
})
class NotificationControllerIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired NotificationDao dao;
    @Autowired NotificationRepository repo;
    @Autowired UserRepository userRepo;
    @Autowired DataSource dataSource;
    @Autowired TransactionTemplate txTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private Long aliceId;
    private Long bobId;
    private String aliceJwt;
    private String bobJwt;

    @BeforeEach
    void seed() throws Exception {
        String aliceSuffix = UUID.randomUUID().toString();
        String bobSuffix = UUID.randomUUID().toString();
        aliceJwt = registerUser("notif-alice-" + aliceSuffix + "@example.com", "Alice");
        aliceId = userRepo.findByUsername("notif-alice-" + aliceSuffix + "@example.com")
                .orElseThrow().getId();
        bobJwt = registerUser("notif-bob-" + bobSuffix + "@example.com", "Bob");
        bobId = userRepo.findByUsername("notif-bob-" + bobSuffix + "@example.com")
                .orElseThrow().getId();
    }

    @AfterEach
    void cleanup() throws Exception {
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (var stmt = conn.createStatement()) {
                for (Long id : new Long[]{aliceId, bobId}) {
                    if (id != null) {
                        stmt.execute("DELETE FROM notification WHERE user_id = " + id);
                        stmt.execute("DELETE FROM refresh_tokens WHERE user_id = " + id);
                        stmt.execute("DELETE FROM users WHERE id = " + id);
                    }
                }
            }
        }
        aliceId = null;
        bobId = null;
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    @Test
    void list_returnsPagedResponseSortedByUpdatedAtDesc() throws Exception {
        dao.upsert(aliceId, OUTBID, "first", "b", java.util.Map.of(), null);
        Thread.sleep(10);
        dao.upsert(aliceId, AUCTION_WON, "second", "b", java.util.Map.of(), null);

        mvc.perform(get("/api/v1/notifications").header("Authorization", "Bearer " + aliceJwt))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].title").value("second"))
            .andExpect(jsonPath("$.content[1].title").value("first"));
    }

    @Test
    void list_unreadOnlyTrue_filtersOutReadRows() throws Exception {
        var n = dao.upsert(aliceId, OUTBID, "t", "b", java.util.Map.of(), null);
        long notifId = internalId(n.publicId());
        txTemplate.executeWithoutResult(status -> repo.markRead(notifId, aliceId));
        dao.upsert(aliceId, AUCTION_WON, "t2", "b", java.util.Map.of(), null);

        mvc.perform(get("/api/v1/notifications?unreadOnly=true")
                .header("Authorization", "Bearer " + aliceJwt))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content").value(hasSize(1)))
            .andExpect(jsonPath("$.content[0].category").value("AUCTION_WON"));
    }

    @Test
    void list_groupFilter_returnsOnlyThatGroupsCategories() throws Exception {
        dao.upsert(aliceId, OUTBID, "bidding", "b", java.util.Map.of(), null);
        dao.upsert(aliceId, AUCTION_WON, "result", "b", java.util.Map.of(), null);

        mvc.perform(get("/api/v1/notifications?group=BIDDING")
                .header("Authorization", "Bearer " + aliceJwt))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content").value(hasSize(1)))
            .andExpect(jsonPath("$.content[0].title").value("bidding"));
    }

    @Test
    void list_invalidGroup_returns400() throws Exception {
        mvc.perform(get("/api/v1/notifications?group=NOT_A_GROUP")
                .header("Authorization", "Bearer " + aliceJwt))
            .andExpect(status().isBadRequest());
    }

    @Test
    void list_sizeOver100_returns400() throws Exception {
        mvc.perform(get("/api/v1/notifications?size=101")
                .header("Authorization", "Bearer " + aliceJwt))
            .andExpect(status().isBadRequest());
    }

    @Test
    void unreadCount_withoutBreakdown_returnsCountOnly() throws Exception {
        dao.upsert(aliceId, OUTBID, "t", "b", java.util.Map.of(), null);

        mvc.perform(get("/api/v1/notifications/unread-count")
                .header("Authorization", "Bearer " + aliceJwt))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.count").value(1L))
            .andExpect(jsonPath("$.byGroup").doesNotExist());
    }

    @Test
    void unreadCount_withBreakdownGroup_returnsByGroup() throws Exception {
        dao.upsert(aliceId, OUTBID, "t1", "b", java.util.Map.of(), null);
        dao.upsert(aliceId, OUTBID, "t2", "b", java.util.Map.of(), null); // different row, no coalesce (null key)
        dao.upsert(aliceId, AUCTION_WON, "t3", "b", java.util.Map.of(), null);

        mvc.perform(get("/api/v1/notifications/unread-count?breakdown=group")
                .header("Authorization", "Bearer " + aliceJwt))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.count").value(3L))
            .andExpect(jsonPath("$.byGroup.bidding").value(2L))
            .andExpect(jsonPath("$.byGroup.auction_result").value(1L));
    }

    @Test
    void markRead_returnsNoContent() throws Exception {
        var n = dao.upsert(aliceId, OUTBID, "t", "b", java.util.Map.of(), null);

        mvc.perform(put("/api/v1/notifications/" + n.publicId() + "/read")
                .header("Authorization", "Bearer " + aliceJwt))
            .andExpect(status().isNoContent());
    }

    @Test
    void markRead_alreadyRead_returnsNoContent() throws Exception {
        var n = dao.upsert(aliceId, OUTBID, "t", "b", java.util.Map.of(), null);
        mvc.perform(put("/api/v1/notifications/" + n.publicId() + "/read")
                .header("Authorization", "Bearer " + aliceJwt))
            .andExpect(status().isNoContent());
        // Second call — idempotent
        mvc.perform(put("/api/v1/notifications/" + n.publicId() + "/read")
                .header("Authorization", "Bearer " + aliceJwt))
            .andExpect(status().isNoContent());
    }

    @Test
    void markRead_crossUser_returns404() throws Exception {
        var n = dao.upsert(aliceId, OUTBID, "t", "b", java.util.Map.of(), null);

        mvc.perform(put("/api/v1/notifications/" + n.publicId() + "/read")
                .header("Authorization", "Bearer " + bobJwt))
            .andExpect(status().isNotFound());
    }

    @Test
    void markAllRead_returnsCount() throws Exception {
        dao.upsert(aliceId, OUTBID, "t", "b", java.util.Map.of(), null);
        dao.upsert(aliceId, AUCTION_WON, "t", "b", java.util.Map.of(), null);

        mvc.perform(put("/api/v1/notifications/read-all")
                .header("Authorization", "Bearer " + aliceJwt))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.markedRead").value(2));
    }

    @Test
    void markAllRead_withGroup_onlyMarksThatGroup() throws Exception {
        dao.upsert(aliceId, OUTBID, "t", "b", java.util.Map.of(), null);
        dao.upsert(aliceId, AUCTION_WON, "t", "b", java.util.Map.of(), null);

        mvc.perform(put("/api/v1/notifications/read-all?group=BIDDING")
                .header("Authorization", "Bearer " + aliceJwt))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.markedRead").value(1));

        // The AUCTION_WON row is still unread:
        long stillUnread = repo.countByUserIdAndReadFalse(aliceId);
        assertThat(stillUnread).isEqualTo(1L);
    }

    @Test
    void list_unauthenticated_returns401() throws Exception {
        mvc.perform(get("/api/v1/notifications"))
            .andExpect(status().isUnauthorized());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Resolves the internal Long PK from an UpsertResult's publicId. */
    private long internalId(UUID publicId) {
        return repo.findByPublicId(publicId).orElseThrow().getId();
    }

    private String registerUser(String email, String displayName) throws Exception {
        String body = String.format(
                "{\"username\":\"%s\",\"password\":\"hunter22abc\"}",
                email, displayName);
        MvcResult reg = mvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isCreated()).andReturn();
        JsonNode json = objectMapper.readTree(reg.getResponse().getContentAsString());
        return json.get("accessToken").asText();
    }
}
