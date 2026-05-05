package com.slparcelauctions.backend.notification.slim.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.slparcelauctions.backend.notification.slim.SlImMessage;
import com.slparcelauctions.backend.notification.slim.SlImMessageDao;
import com.slparcelauctions.backend.notification.slim.SlImMessageRepository;
import com.slparcelauctions.backend.notification.slim.SlImMessageStatus;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = {
    "slpa.notifications.cleanup.enabled=false",
    "slpa.notifications.sl-im.cleanup.enabled=false",
    "slpa.escrow.scheduler.enabled=false",
    "slpa.escrow.timeout-job.enabled=false",
    "slpa.escrow.command-dispatcher-job.enabled=false",
    "slpa.escrow.ownership-monitor-job.enabled=false",
    "slpa.review.scheduler.enabled=false",
    "slpa.refresh-token-cleanup.enabled=false",
    "auth.cleanup.enabled=false",
    "slpa.auction-end.enabled=false",
    "slpa.ownership-monitor.enabled=false",
    "slpa.notifications.sl-im.dispatcher.shared-secret=test-secret-12345",
    "slpa.notifications.sl-im.dispatcher.max-batch-limit=50"
})
class SlImInternalControllerTest {

    @Autowired MockMvc mvc;
    @Autowired SlImMessageDao dao;
    @Autowired SlImMessageRepository repo;
    @Autowired UserRepository userRepo;

    private static final String AUTH = "Bearer test-secret-12345";

    private User user;
    private String avatar;

    @BeforeEach
    void seed() {
        repo.deleteAll();
        user = userRepo.save(User.builder().username("u-" + UUID.randomUUID().toString().substring(0, 8))
            .email("u-" + UUID.randomUUID() + "@test.local")
            .passwordHash("hash").build());
        avatar = UUID.randomUUID().toString();
    }

    // --- /pending ---

    @Test
    void pending_returnsBatchOldestFirst() throws Exception {
        var first = dao.upsert(user.getId(), avatar, "[SLParcels] first", null);
        Thread.sleep(10);
        var second = dao.upsert(user.getId(), avatar, "[SLParcels] second", "key2");

        mvc.perform(get("/api/v1/internal/sl-im/pending?limit=10").header("Authorization", AUTH))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.messages.length()").value(2))
            .andExpect(jsonPath("$.messages[0].id").value((int) first.id()))
            .andExpect(jsonPath("$.messages[1].id").value((int) second.id()))
            .andExpect(jsonPath("$.messages[0].messageText").value("[SLParcels] first"))
            .andExpect(jsonPath("$.messages[0].avatarUuid").value(avatar));
    }

    @Test
    void pending_limitOverMax_returns400() throws Exception {
        mvc.perform(get("/api/v1/internal/sl-im/pending?limit=51").header("Authorization", AUTH))
            .andExpect(status().isBadRequest());
    }

    @Test
    void pending_limitBelowOne_returns400() throws Exception {
        mvc.perform(get("/api/v1/internal/sl-im/pending?limit=0").header("Authorization", AUTH))
            .andExpect(status().isBadRequest());
    }

    @Test
    void pending_missingAuth_returns401() throws Exception {
        mvc.perform(get("/api/v1/internal/sl-im/pending"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void pending_wrongAuth_returns401() throws Exception {
        mvc.perform(get("/api/v1/internal/sl-im/pending").header("Authorization", "Bearer wrong"))
            .andExpect(status().isUnauthorized());
    }

    // --- /delivered state machine ---

    @Test
    void delivered_pendingRow_transitionsTo204AndSetsDeliveredAt() throws Exception {
        var r = dao.upsert(user.getId(), avatar, "[SLParcels] x", null);

        mvc.perform(post("/api/v1/internal/sl-im/" + r.id() + "/delivered").header("Authorization", AUTH))
            .andExpect(status().isNoContent());

        SlImMessage row = repo.findById(r.id()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(SlImMessageStatus.DELIVERED);
        assertThat(row.getDeliveredAt()).isNotNull();
        assertThat(row.getAttempts()).isEqualTo(1);
    }

    @Test
    void delivered_alreadyDelivered_idempotent204() throws Exception {
        var r = dao.upsert(user.getId(), avatar, "[SLParcels] x", null);
        mvc.perform(post("/api/v1/internal/sl-im/" + r.id() + "/delivered").header("Authorization", AUTH))
            .andExpect(status().isNoContent());
        SlImMessage afterFirst = repo.findById(r.id()).orElseThrow();
        var deliveredAtBefore = afterFirst.getDeliveredAt();

        mvc.perform(post("/api/v1/internal/sl-im/" + r.id() + "/delivered").header("Authorization", AUTH))
            .andExpect(status().isNoContent());

        SlImMessage row = repo.findById(r.id()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(SlImMessageStatus.DELIVERED);
        // delivered_at NOT re-stamped on idempotent call:
        assertThat(row.getDeliveredAt()).isEqualTo(deliveredAtBefore);
        // attempts NOT incremented (the WHERE clause excluded DELIVERED):
        assertThat(row.getAttempts()).isEqualTo(1);
    }

    @Test
    void delivered_onFailedRow_returns409() throws Exception {
        var r = dao.upsert(user.getId(), avatar, "[SLParcels] x", null);
        SlImMessage row = repo.findById(r.id()).orElseThrow();
        row.setStatus(SlImMessageStatus.FAILED);
        repo.save(row);

        mvc.perform(post("/api/v1/internal/sl-im/" + r.id() + "/delivered").header("Authorization", AUTH))
            .andExpect(status().isConflict());
    }

    @Test
    void delivered_onExpiredRow_returns409() throws Exception {
        var r = dao.upsert(user.getId(), avatar, "[SLParcels] x", null);
        SlImMessage row = repo.findById(r.id()).orElseThrow();
        row.setStatus(SlImMessageStatus.EXPIRED);
        repo.save(row);

        mvc.perform(post("/api/v1/internal/sl-im/" + r.id() + "/delivered").header("Authorization", AUTH))
            .andExpect(status().isConflict());
    }

    @Test
    void delivered_missing_returns404() throws Exception {
        mvc.perform(post("/api/v1/internal/sl-im/999999/delivered").header("Authorization", AUTH))
            .andExpect(status().isNotFound());
    }

    @Test
    void delivered_unauthorized_returns401() throws Exception {
        var r = dao.upsert(user.getId(), avatar, "[SLParcels] x", null);
        mvc.perform(post("/api/v1/internal/sl-im/" + r.id() + "/delivered"))
            .andExpect(status().isUnauthorized());
    }

    // --- /failed state machine ---

    @Test
    void failed_pendingRow_transitionsTo204() throws Exception {
        var r = dao.upsert(user.getId(), avatar, "[SLParcels] x", null);

        mvc.perform(post("/api/v1/internal/sl-im/" + r.id() + "/failed").header("Authorization", AUTH))
            .andExpect(status().isNoContent());

        SlImMessage row = repo.findById(r.id()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(SlImMessageStatus.FAILED);
        assertThat(row.getDeliveredAt()).isNull();
        assertThat(row.getAttempts()).isEqualTo(1);
    }

    @Test
    void failed_alreadyFailed_idempotent204() throws Exception {
        var r = dao.upsert(user.getId(), avatar, "[SLParcels] x", null);
        mvc.perform(post("/api/v1/internal/sl-im/" + r.id() + "/failed").header("Authorization", AUTH))
            .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/internal/sl-im/" + r.id() + "/failed").header("Authorization", AUTH))
            .andExpect(status().isNoContent());
    }

    @Test
    void failed_onDeliveredRow_returns409() throws Exception {
        var r = dao.upsert(user.getId(), avatar, "[SLParcels] x", null);
        SlImMessage row = repo.findById(r.id()).orElseThrow();
        row.setStatus(SlImMessageStatus.DELIVERED);
        repo.save(row);

        mvc.perform(post("/api/v1/internal/sl-im/" + r.id() + "/failed").header("Authorization", AUTH))
            .andExpect(status().isConflict());
    }

    @Test
    void failed_onExpiredRow_returns409() throws Exception {
        var r = dao.upsert(user.getId(), avatar, "[SLParcels] x", null);
        SlImMessage row = repo.findById(r.id()).orElseThrow();
        row.setStatus(SlImMessageStatus.EXPIRED);
        repo.save(row);

        mvc.perform(post("/api/v1/internal/sl-im/" + r.id() + "/failed").header("Authorization", AUTH))
            .andExpect(status().isConflict());
    }

    @Test
    void failed_missing_returns404() throws Exception {
        mvc.perform(post("/api/v1/internal/sl-im/999999/failed").header("Authorization", AUTH))
            .andExpect(status().isNotFound());
    }
}
