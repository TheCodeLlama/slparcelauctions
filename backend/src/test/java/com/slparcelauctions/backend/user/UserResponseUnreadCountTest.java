package com.slparcelauctions.backend.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import com.slparcelauctions.backend.notification.NotificationCategory;
import com.slparcelauctions.backend.notification.NotificationDao;
import com.slparcelauctions.backend.user.dto.UserResponse;

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
class UserResponseUnreadCountTest {

    @Autowired UserService userService;
    @Autowired NotificationDao notificationDao;
    @Autowired UserRepository userRepo;
    @Autowired DataSource dataSource;

    private Long aliceId;
    private Long bobId;

    @BeforeEach
    void createUsers() {
        aliceId = userRepo.save(User.builder().username("u-" + UUID.randomUUID().toString().substring(0, 8))
                .email("uruc-alice-" + UUID.randomUUID() + "@example.com")
                .passwordHash("hash").build()).getId();
        bobId = userRepo.save(User.builder().username("u-" + UUID.randomUUID().toString().substring(0, 8))
                .email("uruc-bob-" + UUID.randomUUID() + "@example.com")
                .passwordHash("hash").build()).getId();
    }

    @AfterEach
    void cleanup() throws Exception {
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (var stmt = conn.createStatement()) {
                for (Long id : new Long[]{aliceId, bobId}) {
                    if (id != null) {
                        stmt.execute("DELETE FROM notification WHERE user_id = " + id);
                        stmt.execute("DELETE FROM users WHERE id = " + id);
                    }
                }
            }
        }
        aliceId = null;
        bobId = null;
    }

    @Test
    void unreadCount_zeroForFreshUser() {
        User alice = userRepo.findById(aliceId).orElseThrow();
        UserResponse r = userService.toResponse(alice);
        assertThat(r.unreadNotificationCount()).isEqualTo(0L);
    }

    @Test
    void unreadCount_countsOnlyUnreadOwnedRows() {
        User alice = userRepo.findById(aliceId).orElseThrow();
        User bob = userRepo.findById(bobId).orElseThrow();

        notificationDao.upsert(alice.getId(), NotificationCategory.OUTBID, "t", "b", Map.of(), null);
        notificationDao.upsert(alice.getId(), NotificationCategory.AUCTION_WON, "t", "b", Map.of(), null);
        notificationDao.upsert(bob.getId(), NotificationCategory.OUTBID, "t", "b", Map.of(), null);

        UserResponse r = userService.toResponse(alice);
        assertThat(r.unreadNotificationCount()).isEqualTo(2L);
    }
}
