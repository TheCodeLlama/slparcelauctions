package com.slparcelauctions.backend.notification.slim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.slparcelauctions.backend.notification.NotificationCategory;
import com.slparcelauctions.backend.notification.NotificationEvent;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

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
class SlImChannelDispatcherTest {

    @Autowired SlImChannelDispatcher dispatcher;
    @Autowired SlImMessageRepository repo;
    @Autowired UserRepository userRepo;
    @Autowired TransactionTemplate transactionTemplate;

    @BeforeEach
    void clean() {
        repo.deleteAll();
    }

    private User userWithAvatarAndAllGroupsOn() {
        User u = User.builder()
            .email("u-" + UUID.randomUUID() + "@test.local")
            .passwordHash("hash")
            .build();
        u.setSlAvatarUuid(UUID.randomUUID());
        u.setNotifySlImMuted(false);
        Map<String, Object> prefs = new HashMap<>();
        prefs.put("bidding", true);
        prefs.put("auction_result", true);
        prefs.put("escrow", true);
        prefs.put("listing_status", true);
        prefs.put("reviews", true);
        prefs.put("system", true);
        u.setNotifySlIm(prefs);
        return userRepo.save(u);
    }

    @Test
    void maybeQueue_happyPath_insertsRowWithBuiltMessage() {
        User u = userWithAvatarAndAllGroupsOn();
        Map<String, Object> data = Map.of("auctionId", 42, "parcelName", "Hampton",
            "currentBidL", 2000L, "isProxyOutbid", false, "endsAt", "2026-04-26T18:00:00Z");
        NotificationEvent event = new NotificationEvent(
            u.getId(), NotificationCategory.OUTBID,
            "You've been outbid on Hampton", "Current bid is L$2,000.",
            data, "outbid:" + u.getId() + ":42");

        // Run inside a transaction so the dispatcher's REQUIRES_NEW commits independently.
        transactionTemplate.execute(status -> {
            dispatcher.maybeQueue(event);
            return null;
        });

        List<SlImMessage> rows = repo.findAll();
        assertThat(rows).hasSize(1);
        SlImMessage row = rows.get(0);
        assertThat(row.getUserId()).isEqualTo(u.getId());
        assertThat(row.getAvatarUuid()).isEqualTo(u.getSlAvatarUuid().toString());
        assertThat(row.getStatus()).isEqualTo(SlImMessageStatus.PENDING);
        assertThat(row.getMessageText()).startsWith("[SLPA] You've been outbid on Hampton");
        assertThat(row.getMessageText()).contains("Current bid is L$2,000.");
        assertThat(row.getMessageText()).endsWith("/auction/42");
        assertThat(row.getCoalesceKey()).isEqualTo("outbid:" + u.getId() + ":42");
    }

    @Test
    void maybeQueue_userMuted_doesNotQueue() {
        User u = userWithAvatarAndAllGroupsOn();
        u.setNotifySlImMuted(true);
        userRepo.save(u);

        NotificationEvent event = new NotificationEvent(
            u.getId(), NotificationCategory.OUTBID, "title", "body",
            Map.of("auctionId", 42), null);

        transactionTemplate.execute(status -> { dispatcher.maybeQueue(event); return null; });

        assertThat(repo.findAll()).isEmpty();
    }

    @Test
    void maybeQueue_groupDisabled_doesNotQueue() {
        User u = userWithAvatarAndAllGroupsOn();
        Map<String, Object> prefs = new HashMap<>(u.getNotifySlIm());
        prefs.put("bidding", false);
        u.setNotifySlIm(prefs);
        userRepo.save(u);

        NotificationEvent event = new NotificationEvent(
            u.getId(), NotificationCategory.OUTBID, "title", "body",
            Map.of("auctionId", 42), null);

        transactionTemplate.execute(status -> { dispatcher.maybeQueue(event); return null; });

        assertThat(repo.findAll()).isEmpty();
    }

    @Test
    void maybeQueue_noAvatar_doesNotQueue() {
        User u = userWithAvatarAndAllGroupsOn();
        u.setSlAvatarUuid(null);
        userRepo.save(u);

        NotificationEvent event = new NotificationEvent(
            u.getId(), NotificationCategory.OUTBID, "title", "body",
            Map.of("auctionId", 42), null);

        transactionTemplate.execute(status -> { dispatcher.maybeQueue(event); return null; });

        assertThat(repo.findAll()).isEmpty();
    }

    @Test
    void maybeQueue_systemBypassesPrefs_evenWhenMuted() {
        User u = userWithAvatarAndAllGroupsOn();
        u.setNotifySlImMuted(true);
        Map<String, Object> prefs = new HashMap<>(u.getNotifySlIm());
        prefs.put("system", false);
        u.setNotifySlIm(prefs);
        userRepo.save(u);

        NotificationEvent event = new NotificationEvent(
            u.getId(), NotificationCategory.SYSTEM_ANNOUNCEMENT, "Heads up", "body",
            Map.of(), null);

        transactionTemplate.execute(status -> { dispatcher.maybeQueue(event); return null; });

        assertThat(repo.findAll()).hasSize(1);
    }

    @Test
    void maybeQueue_repeatedSameKey_coalescesToSingleRow() {
        User u = userWithAvatarAndAllGroupsOn();
        String key = "outbid:" + u.getId() + ":42";

        for (int i = 1; i <= 5; i++) {
            int currentBidL = 1000 + i * 100;
            NotificationEvent event = new NotificationEvent(
                u.getId(), NotificationCategory.OUTBID,
                "You've been outbid", "Current bid is L$" + currentBidL,
                Map.of("auctionId", 42, "currentBidL", (long) currentBidL,
                    "isProxyOutbid", false, "parcelName", "Hampton",
                    "endsAt", "2026-04-26T18:00:00Z"),
                key);
            transactionTemplate.execute(status -> { dispatcher.maybeQueue(event); return null; });
        }

        List<SlImMessage> rows = repo.findAll();
        assertThat(rows).hasSize(1);
        // Latest body wins:
        assertThat(rows.get(0).getMessageText()).contains("Current bid is L$1500");
    }

    @Test
    void maybeQueueForFanout_propagatesExceptionForCaller() {
        // Force a failure by passing a non-existent userId so userRepo.findById fails.
        long missingUserId = 999_999_999L;

        // Direct call (no requires_new wrapper) — caller is "already inside a REQUIRES_NEW".
        // Verify the exception propagates.
        assertThatThrownBy(() ->
            transactionTemplate.execute(status -> {
                dispatcher.maybeQueueForFanout(missingUserId, NotificationCategory.OUTBID,
                    "title", "body", Map.of("auctionId", 42), null);
                return null;
            }))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("user not found");
    }
}
