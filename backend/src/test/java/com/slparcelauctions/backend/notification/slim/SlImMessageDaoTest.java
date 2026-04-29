package com.slparcelauctions.backend.notification.slim;

import static org.assertj.core.api.Assertions.assertThat;

import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
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
class SlImMessageDaoTest {

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
    void upsert_freshKey_insertsRow_returnsWasUpdateFalse() {
        User u = userRepo.save(testUser());
        String avatar = UUID.randomUUID().toString();

        var result = dao.upsert(u.getId(), avatar, "[SLPA] outbid msg", "outbid:1:42");

        assertThat(result.wasUpdate()).isFalse();
        assertThat(result.id()).isPositive();

        SlImMessage row = repo.findById(result.id()).orElseThrow();
        assertThat(row.getUserId()).isEqualTo(u.getId());
        assertThat(row.getAvatarUuid()).isEqualTo(avatar);
        assertThat(row.getMessageText()).isEqualTo("[SLPA] outbid msg");
        assertThat(row.getCoalesceKey()).isEqualTo("outbid:1:42");
        assertThat(row.getStatus()).isEqualTo(SlImMessageStatus.PENDING);
        assertThat(row.getAttempts()).isZero();
    }

    @Test
    void upsert_secondCallSameKey_updatesPendingRow_returnsWasUpdateTrue() {
        User u = userRepo.save(testUser());
        String avatar = UUID.randomUUID().toString();

        var first = dao.upsert(u.getId(), avatar, "[SLPA] first", "outbid:1:42");
        var second = dao.upsert(u.getId(), avatar, "[SLPA] second", "outbid:1:42");

        assertThat(second.wasUpdate()).isTrue();
        assertThat(second.id()).isEqualTo(first.id());

        SlImMessage row = repo.findById(first.id()).orElseThrow();
        assertThat(row.getMessageText()).isEqualTo("[SLPA] second");
        // updated_at bumped past created_at
        assertThat(row.getUpdatedAt()).isAfterOrEqualTo(row.getCreatedAt());
    }

    @Test
    void upsert_afterDelivered_insertsFreshRow() {
        User u = userRepo.save(testUser());
        String avatar = UUID.randomUUID().toString();

        var first = dao.upsert(u.getId(), avatar, "[SLPA] first", "outbid:1:42");

        // Mark first row DELIVERED via direct repo write (real path is Task 4 controller)
        SlImMessage delivered = repo.findById(first.id()).orElseThrow();
        delivered.setStatus(SlImMessageStatus.DELIVERED);
        repo.save(delivered);

        var second = dao.upsert(u.getId(), avatar, "[SLPA] second", "outbid:1:42");
        assertThat(second.wasUpdate()).isFalse();
        assertThat(second.id()).isNotEqualTo(first.id());
        // Both rows persist; partial index excludes the DELIVERED one from the predicate.
        assertThat(repo.findAll()).hasSize(2);
    }

    @Test
    void upsert_afterExpired_insertsFreshRow() {
        User u = userRepo.save(testUser());
        String avatar = UUID.randomUUID().toString();

        var first = dao.upsert(u.getId(), avatar, "[SLPA] first", "outbid:1:42");

        SlImMessage expired = repo.findById(first.id()).orElseThrow();
        expired.setStatus(SlImMessageStatus.EXPIRED);
        repo.save(expired);

        var second = dao.upsert(u.getId(), avatar, "[SLPA] second", "outbid:1:42");
        assertThat(second.wasUpdate()).isFalse();
        assertThat(second.id()).isNotEqualTo(first.id());
    }

    @Test
    void upsert_afterFailed_insertsFreshRow() {
        User u = userRepo.save(testUser());
        String avatar = UUID.randomUUID().toString();

        var first = dao.upsert(u.getId(), avatar, "[SLPA] first", "outbid:1:42");

        SlImMessage failed = repo.findById(first.id()).orElseThrow();
        failed.setStatus(SlImMessageStatus.FAILED);
        repo.save(failed);

        var second = dao.upsert(u.getId(), avatar, "[SLPA] second", "outbid:1:42");
        assertThat(second.wasUpdate()).isFalse();
        assertThat(second.id()).isNotEqualTo(first.id());
    }

    @Test
    void upsert_nullCoalesceKey_neverCollidesWithItself() {
        User u = userRepo.save(testUser());
        String avatar = UUID.randomUUID().toString();

        var first = dao.upsert(u.getId(), avatar, "[SLPA] one", null);
        var second = dao.upsert(u.getId(), avatar, "[SLPA] two", null);

        assertThat(first.wasUpdate()).isFalse();
        assertThat(second.wasUpdate()).isFalse();
        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(repo.findAll()).hasSize(2);
    }

    @Test
    void upsert_differentUsersSameKey_doNotCollide() {
        User a = userRepo.save(testUser());
        User b = userRepo.save(testUser());
        String avatarA = UUID.randomUUID().toString();
        String avatarB = UUID.randomUUID().toString();

        var rA = dao.upsert(a.getId(), avatarA, "[SLPA] A", "outbid:1:42");
        var rB = dao.upsert(b.getId(), avatarB, "[SLPA] B", "outbid:1:42");

        assertThat(rA.wasUpdate()).isFalse();
        assertThat(rB.wasUpdate()).isFalse();
        assertThat(rA.id()).isNotEqualTo(rB.id());
    }

    private User testUser() {
        return User.builder()
            .email("u-" + UUID.randomUUID() + "@test.local")
            .passwordHash("hash")
            .build();
    }
}
