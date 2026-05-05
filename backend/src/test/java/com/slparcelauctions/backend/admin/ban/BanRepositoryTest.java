package com.slparcelauctions.backend.admin.ban;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.slparcelauctions.backend.notification.NotificationWsBroadcasterPort;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

@SpringBootTest
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
class BanRepositoryTest {

    @Autowired BanRepository banRepo;
    @Autowired UserRepository userRepo;
    @Autowired PlatformTransactionManager txManager;
    @Autowired DataSource dataSource;

    @MockitoBean NotificationWsBroadcasterPort wsBroadcaster;

    private Long adminUserId;
    private Long savedBanId;

    @BeforeEach
    void seedAdmin() {
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            User admin = userRepo.save(User.builder().username("u-" + UUID.randomUUID().toString().substring(0, 8))
                .email("ban-repo-admin-" + UUID.randomUUID() + "@x.com")
                .passwordHash("x")
                .slAvatarUuid(UUID.randomUUID())
                .build());
            adminUserId = admin.getId();
        });
    }

    @AfterEach
    void cleanup() throws Exception {
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            if (savedBanId != null) {
                banRepo.findById(savedBanId).ifPresent(banRepo::delete);
                savedBanId = null;
            }
        });
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (var st = conn.createStatement()) {
                if (adminUserId != null) {
                    st.execute("DELETE FROM notification WHERE user_id = " + adminUserId);
                    st.execute("DELETE FROM refresh_tokens WHERE user_id = " + adminUserId);
                    st.execute("DELETE FROM users WHERE id = " + adminUserId);
                }
            }
        }
        adminUserId = null;
    }

    // -------------------------------------------------------------------------
    // findActiveByIp
    // -------------------------------------------------------------------------

    @Test
    void findActiveByIp_activePermanentBan_isFound() {
        OffsetDateTime now = OffsetDateTime.now();
        String ip = "10.0.0.1";

        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            User admin = userRepo.findById(adminUserId).orElseThrow();
            Ban ban = banRepo.save(Ban.builder()
                .adminUser(admin)
                .banType(BanType.IP)
                .ipAddress(ip)
                .reasonCategory(BanReasonCategory.SPAM)
                .build());
            savedBanId = ban.getId();
        });

        List<Ban> results = banRepo.findActiveByIp(ip, now);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getIpAddress()).isEqualTo(ip);
    }

    @Test
    void findActiveByAvatar_activePermanentBan_isFound() {
        OffsetDateTime now = OffsetDateTime.now();
        UUID avatarUuid = UUID.randomUUID();

        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            User admin = userRepo.findById(adminUserId).orElseThrow();
            Ban ban = banRepo.save(Ban.builder()
                .adminUser(admin)
                .banType(BanType.AVATAR)
                .slAvatarUuid(avatarUuid)
                .reasonCategory(BanReasonCategory.SHILL_BIDDING)
                .build());
            savedBanId = ban.getId();
        });

        List<Ban> results = banRepo.findActiveByAvatar(avatarUuid, now);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getSlAvatarUuid()).isEqualTo(avatarUuid);
    }

    @Test
    void findActiveByIp_expiredBan_notReturned() {
        OffsetDateTime now = OffsetDateTime.now();
        String ip = "10.0.0.2";

        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            User admin = userRepo.findById(adminUserId).orElseThrow();
            Ban ban = banRepo.save(Ban.builder()
                .adminUser(admin)
                .banType(BanType.IP)
                .ipAddress(ip)
                .reasonCategory(BanReasonCategory.SPAM)
                .expiresAt(now.minusHours(1))   // already expired
                .build());
            savedBanId = ban.getId();
        });

        List<Ban> results = banRepo.findActiveByIp(ip, now);
        assertThat(results).isEmpty();
    }

    @Test
    void findActiveByIp_liftedBan_notReturned() {
        OffsetDateTime now = OffsetDateTime.now();
        String ip = "10.0.0.3";

        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            User admin = userRepo.findById(adminUserId).orElseThrow();
            Ban ban = banRepo.save(Ban.builder()
                .adminUser(admin)
                .banType(BanType.IP)
                .ipAddress(ip)
                .reasonCategory(BanReasonCategory.TOS_ABUSE)
                .liftedAt(now.minusMinutes(5))   // already lifted
                .build());
            savedBanId = ban.getId();
        });

        List<Ban> results = banRepo.findActiveByIp(ip, now);
        assertThat(results).isEmpty();
    }

    @Test
    void bothTypeBan_returnedByBothQueries() {
        OffsetDateTime now = OffsetDateTime.now();
        String ip = "10.0.0.4";
        UUID avatarUuid = UUID.randomUUID();

        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            User admin = userRepo.findById(adminUserId).orElseThrow();
            Ban ban = banRepo.save(Ban.builder()
                .adminUser(admin)
                .banType(BanType.BOTH)
                .ipAddress(ip)
                .slAvatarUuid(avatarUuid)
                .reasonCategory(BanReasonCategory.FRAUDULENT_SELLER)
                .build());
            savedBanId = ban.getId();
        });

        List<Ban> byIp = banRepo.findActiveByIp(ip, now);
        List<Ban> byAvatar = banRepo.findActiveByAvatar(avatarUuid, now);

        assertThat(byIp).hasSize(1);
        assertThat(byAvatar).hasSize(1);
        assertThat(byIp.get(0).getId()).isEqualTo(byAvatar.get(0).getId());
    }

    @Test
    void ipOnlyBan_notReturnedByAvatarQuery() {
        OffsetDateTime now = OffsetDateTime.now();
        String ip = "10.0.0.5";
        UUID someOtherUuid = UUID.randomUUID();

        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            User admin = userRepo.findById(adminUserId).orElseThrow();
            Ban ban = banRepo.save(Ban.builder()
                .adminUser(admin)
                .banType(BanType.IP)
                .ipAddress(ip)
                .reasonCategory(BanReasonCategory.OTHER)
                .build());
            savedBanId = ban.getId();
        });

        // The avatar UUID used in the query has nothing to do with this ban
        List<Ban> byAvatar = banRepo.findActiveByAvatar(someOtherUuid, now);
        assertThat(byAvatar).isEmpty();
    }
}
