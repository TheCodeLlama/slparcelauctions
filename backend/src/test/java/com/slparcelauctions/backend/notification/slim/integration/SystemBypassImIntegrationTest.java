package com.slparcelauctions.backend.notification.slim.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.slparcelauctions.backend.notification.NotificationCategory;
import com.slparcelauctions.backend.notification.NotificationEvent;
import com.slparcelauctions.backend.notification.NotificationService;
import com.slparcelauctions.backend.notification.slim.SlImMessageRepository;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import java.util.HashMap;
import java.util.List;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Verifies that SYSTEM_ANNOUNCEMENT notifications bypass mute / group-disabled preferences
 * and reach users with avatars, but still skip users without avatars.
 */
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
class SystemBypassImIntegrationTest {

    @Autowired NotificationService notificationService;
    @Autowired SlImMessageRepository slImRepo;
    @Autowired UserRepository userRepo;
    @Autowired PlatformTransactionManager txManager;
    @Autowired DataSource dataSource;

    private final List<Long> userIds = new java.util.ArrayList<>();

    @BeforeEach
    void clean() {
        slImRepo.deleteAll();
    }

    @AfterEach
    void cleanup() throws Exception {
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (var st = conn.createStatement()) {
                for (Long id : userIds) {
                    if (id != null) {
                        st.execute("DELETE FROM sl_im_message WHERE user_id = " + id);
                        st.execute("DELETE FROM notification WHERE user_id = " + id);
                        st.execute("DELETE FROM refresh_tokens WHERE user_id = " + id);
                        st.execute("DELETE FROM users WHERE id = " + id);
                    }
                }
            }
        }
        userIds.clear();
    }

    @Test
    void systemAnnouncement_mutedUserWithAvatar_getsIm() {
        User u = saveUserAllOff(true);  // all groups off + has avatar
        u.setNotifySlImMuted(true);
        userRepo.save(u);

        publishSystem(u.getId(), "All systems normal", "Everything is fine.");

        var rows = slImRepo.findAll().stream()
            .filter(m -> m.getUserId().equals(u.getId())).toList();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getMessageText()).contains("[SLParcels] All systems normal");
    }

    @Test
    void systemAnnouncement_mutedUserNoAvatar_skipsIm() {
        User u = saveUserAllOff(false);  // all groups off + NO avatar
        u.setNotifySlImMuted(true);
        userRepo.save(u);

        publishSystem(u.getId(), "All systems normal", "Everything is fine.");

        assertThat(slImRepo.findAll().stream()
            .filter(m -> m.getUserId().equals(u.getId())).count()).isZero();
    }

    @Test
    void systemAnnouncement_groupKeyExplicitlyFalse_stillBypasses() {
        User u = saveUserAllOff(true);
        Map<String, Object> prefs = new HashMap<>(u.getNotifySlIm());
        prefs.put("system", false);  // explicitly false
        u.setNotifySlIm(prefs);
        userRepo.save(u);

        publishSystem(u.getId(), "All systems normal", "Everything is fine.");

        // SYSTEM bypasses prefs; the IM is queued.
        assertThat(slImRepo.findAll().stream()
            .filter(m -> m.getUserId().equals(u.getId())).count()).isEqualTo(1L);
    }

    private void publishSystem(long userId, String title, String body) {
        NotificationEvent event = new NotificationEvent(
            userId, NotificationCategory.SYSTEM_ANNOUNCEMENT,
            title, body, Map.of(), /* coalesceKey */ null);
        new TransactionTemplate(txManager).executeWithoutResult(status ->
            notificationService.publish(event));
    }

    private User saveUserAllOff(boolean hasAvatar) {
        User u = User.builder()
            .email("u-" + UUID.randomUUID() + "@test.local")
            .passwordHash("hash").build();
        if (hasAvatar) u.setSlAvatarUuid(UUID.randomUUID());
        u.setNotifySlImMuted(false);
        Map<String, Object> prefs = new HashMap<>();
        for (var g : List.of("bidding", "auction_result", "escrow",
                              "listing_status", "reviews", "system")) {
            prefs.put(g, false);
        }
        u.setNotifySlIm(prefs);
        User saved = userRepo.save(u);
        userIds.add(saved.getId());
        return saved;
    }
}
