package com.slparcelauctions.backend.notification.slim;

import static org.assertj.core.api.Assertions.assertThat;

import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
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
    "slpa.ownership-monitor.enabled=false"
})
class SlImMessageRepositoryTest {

    @Autowired SlImMessageDao dao;
    @Autowired SlImMessageRepository repo;
    @Autowired UserRepository userRepo;
    @Autowired DataSource dataSource;

    @AfterEach
    void cleanup() throws Exception {
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (var stmt = conn.createStatement()) {
                stmt.execute("DELETE FROM sl_im_message");
                stmt.execute("DELETE FROM users WHERE email LIKE 'u-%@test.local'");
            }
        }
    }

    @Test
    void pollPending_returnsOldestFirst() throws InterruptedException {
        User u = userRepo.save(testUser());
        String avatar = UUID.randomUUID().toString();

        var first = dao.upsert(u.getId(), avatar, "[SLPA] first", null);
        Thread.sleep(20);
        var second = dao.upsert(u.getId(), avatar, "[SLPA] second", null);
        Thread.sleep(20);
        var third = dao.upsert(u.getId(), avatar, "[SLPA] third", null);

        List<SlImMessage> rows = repo.pollPending(10);
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).getId()).isEqualTo(first.id());
        assertThat(rows.get(1).getId()).isEqualTo(second.id());
        assertThat(rows.get(2).getId()).isEqualTo(third.id());
    }

    @Test
    void pollPending_honorsLimit() {
        User u = userRepo.save(testUser());
        String avatar = UUID.randomUUID().toString();

        for (int i = 0; i < 15; i++) {
            dao.upsert(u.getId(), avatar, "[SLPA] " + i, null);
        }

        assertThat(repo.pollPending(10)).hasSize(10);
        assertThat(repo.pollPending(5)).hasSize(5);
    }

    @Test
    void pollPending_excludesNonPending() {
        User u = userRepo.save(testUser());
        String avatar = UUID.randomUUID().toString();

        var pending = dao.upsert(u.getId(), avatar, "[SLPA] pending", null);
        var delivered = dao.upsert(u.getId(), avatar, "[SLPA] delivered", null);
        var expired = dao.upsert(u.getId(), avatar, "[SLPA] expired", null);
        var failed = dao.upsert(u.getId(), avatar, "[SLPA] failed", null);

        SlImMessage d = repo.findById(delivered.id()).orElseThrow();
        d.setStatus(SlImMessageStatus.DELIVERED);
        repo.save(d);
        SlImMessage e = repo.findById(expired.id()).orElseThrow();
        e.setStatus(SlImMessageStatus.EXPIRED);
        repo.save(e);
        SlImMessage f = repo.findById(failed.id()).orElseThrow();
        f.setStatus(SlImMessageStatus.FAILED);
        repo.save(f);

        List<SlImMessage> rows = repo.pollPending(10);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getId()).isEqualTo(pending.id());
    }

    @Test
    void countByStatus_returnsAllStatusBuckets() {
        User u = userRepo.save(testUser());
        String avatar = UUID.randomUUID().toString();

        var p1 = dao.upsert(u.getId(), avatar, "[SLPA] p1", "k1");
        var p2 = dao.upsert(u.getId(), avatar, "[SLPA] p2", "k2");
        var d = dao.upsert(u.getId(), avatar, "[SLPA] d", "k3");

        SlImMessage row = repo.findById(d.id()).orElseThrow();
        row.setStatus(SlImMessageStatus.DELIVERED);
        repo.save(row);

        Map<SlImMessageStatus, Long> counts = repo.countByStatus().stream()
            .collect(java.util.stream.Collectors.toMap(
                arr -> (SlImMessageStatus) arr[0],
                arr -> ((Number) arr[1]).longValue()));

        assertThat(counts.get(SlImMessageStatus.PENDING)).isEqualTo(2L);
        assertThat(counts.get(SlImMessageStatus.DELIVERED)).isEqualTo(1L);
    }

    private User testUser() {
        return User.builder()
            .email("u-" + UUID.randomUUID() + "@test.local")
            .passwordHash("hash")
            .build();
    }
}
