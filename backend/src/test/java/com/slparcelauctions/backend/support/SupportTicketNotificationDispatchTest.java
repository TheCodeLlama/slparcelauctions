package com.slparcelauctions.backend.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
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

import com.slparcelauctions.backend.notification.Notification;
import com.slparcelauctions.backend.notification.NotificationCategory;
import com.slparcelauctions.backend.notification.NotificationEvent;
import com.slparcelauctions.backend.notification.NotificationPublisher;
import com.slparcelauctions.backend.notification.NotificationRepository;
import com.slparcelauctions.backend.notification.NotificationWsBroadcasterPort;
import com.slparcelauctions.backend.notification.slim.SlImChannelDispatcher;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Plan Task 5 -- dispatch coverage for the four customer-support notification
 * categories added to {@link NotificationPublisher}.
 *
 * <p>Pattern mirrors {@code NotificationPublisherImplTest}: real publisher bean
 * + real DAO + real Postgres, with {@link SlImChannelDispatcher} mocked so we
 * can assert SL IM dispatch was/was-not invoked per category. The WS
 * broadcaster is also mocked to avoid Stomp-related session noise; the row
 * counts in the repository are the ground-truth signal for in-app delivery.
 *
 * <p>User-facing categories ({@code SUPPORT_TICKET_ADMIN_REPLIED},
 * {@code SUPPORT_TICKET_RESOLVED}) must fire in-app + SL IM. Admin-facing
 * categories ({@code SUPPORT_TICKET_OPENED}, {@code SUPPORT_TICKET_USER_REPLIED})
 * must fire in-app to each admin in the supplied list and NEVER queue an
 * SL IM (admins read the queue page, not their inbox).
 */
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
        "slpa.notifications.sl-im.cleanup.enabled=false",
        "slpa.realty.invitation-expiry.enabled=false"
})
class SupportTicketNotificationDispatchTest {

    @Autowired NotificationPublisher publisher;
    @Autowired NotificationRepository repo;
    @Autowired UserRepository userRepo;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager txManager;

    @MockitoBean
    NotificationWsBroadcasterPort broadcasterPort;
    @MockitoBean
    SlImChannelDispatcher slImChannelDispatcher;

    private TransactionTemplate transactionTemplate;
    private final List<Long> userIds = new ArrayList<>();

    @BeforeEach
    void setup() {
        transactionTemplate = new TransactionTemplate(txManager);
    }

    @AfterEach
    void cleanup() throws Exception {
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (var stmt = conn.createStatement()) {
                for (Long id : userIds) {
                    if (id != null) {
                        stmt.execute("DELETE FROM notification WHERE user_id = " + id);
                        stmt.execute("DELETE FROM refresh_tokens WHERE user_id = " + id);
                        stmt.execute("DELETE FROM users WHERE id = " + id);
                    }
                }
            }
        }
        userIds.clear();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * User with an SL avatar UUID set. The SL IM gate's no-avatar floor would
     * otherwise return SKIP_NO_AVATAR for the user-facing tests; we need the
     * gate to land on QUEUE_BYPASS_PREFS so the dispatcher actually gets a
     * call we can verify. The mocked SlImChannelDispatcher means the gate is
     * not actually consulted here — verification is purely on the
     * NotificationService -> SlImChannelDispatcher.maybeQueue path.
     */
    private User testUser(String prefix) {
        User u = userRepo.save(User.builder()
                .username("u-" + UUID.randomUUID().toString().substring(0, 8))
                .email(prefix + "-" + UUID.randomUUID() + "@example.com")
                .passwordHash("hash")
                .slAvatarUuid(UUID.randomUUID())
                .build());
        userIds.add(u.getId());
        return u;
    }

    private List<Notification> notifFor(Long userId) {
        return repo.findAllByUserId(userId);
    }

    // ── User-facing: in-app + SL IM ───────────────────────────────────────────

    @Test
    void supportTicketAdminReplied_fires_inApp_andSlIm() {
        User user = testUser("admin-replied-user");
        UUID ticketPublicId = UUID.randomUUID();

        transactionTemplate.execute(status -> {
            publisher.supportTicketAdminReplied(user.getId(), ticketPublicId,
                    "Cannot withdraw L$", "Heath Admin");
            return null;
        });

        List<Notification> rows = notifFor(user.getId());
        assertThat(rows).hasSize(1);
        Notification n = rows.get(0);
        assertThat(n.getCategory()).isEqualTo(NotificationCategory.SUPPORT_TICKET_ADMIN_REPLIED);
        assertThat(n.getBody()).contains("Heath Admin replied to your support ticket: Cannot withdraw L$");
        assertThat(n.getBody()).contains("slparcels.com/support/" + ticketPublicId);
        assertThat(n.getBody()).doesNotContain("—"); // em-dash guard
        assertThat(n.getData()).containsEntry("ticketPublicId", ticketPublicId.toString());
        assertThat(n.getData()).containsEntry("subject", "Cannot withdraw L$");
        assertThat(n.getData()).containsEntry("adminDisplayName", "Heath Admin");

        // SL IM dispatch fired once on afterCommit -- SYSTEM-group routing
        // means the gate would return QUEUE_BYPASS_PREFS in production.
        verify(slImChannelDispatcher, times(1)).maybeQueue(any(NotificationEvent.class));
        verify(broadcasterPort).broadcastUpsert(eq(user.getId()), any(), any());
    }

    @Test
    void supportTicketResolved_fires_inApp_andSlIm() {
        User user = testUser("resolved-user");
        UUID ticketPublicId = UUID.randomUUID();

        transactionTemplate.execute(status -> {
            publisher.supportTicketResolved(user.getId(), ticketPublicId,
                    "Refund question");
            return null;
        });

        List<Notification> rows = notifFor(user.getId());
        assertThat(rows).hasSize(1);
        Notification n = rows.get(0);
        assertThat(n.getCategory()).isEqualTo(NotificationCategory.SUPPORT_TICKET_RESOLVED);
        assertThat(n.getBody()).contains(
                "Your support ticket has been marked resolved: Refund question");
        assertThat(n.getBody()).contains("slparcels.com/support/" + ticketPublicId);
        assertThat(n.getBody()).doesNotContain("—");
        assertThat(n.getData()).containsEntry("ticketPublicId", ticketPublicId.toString());
        assertThat(n.getData()).containsEntry("subject", "Refund question");

        verify(slImChannelDispatcher, times(1)).maybeQueue(any(NotificationEvent.class));
        verify(broadcasterPort).broadcastUpsert(eq(user.getId()), any(), any());
    }

    // ── Admin-facing fan-out: in-app only ─────────────────────────────────────

    @Test
    void supportTicketOpened_fires_inApp_only_to_each_admin() {
        User adminA = testUser("admin-opened-a");
        User adminB = testUser("admin-opened-b");
        User adminC = testUser("admin-opened-c");
        UUID ticketPublicId = UUID.randomUUID();

        transactionTemplate.execute(status -> {
            publisher.supportTicketOpened(
                    List.of(adminA.getId(), adminB.getId(), adminC.getId()),
                    ticketPublicId,
                    "Cannot list parcel",
                    "Alice Resident",
                    "LISTING");
            return null;
        });

        // Each admin gets their own row, none shared.
        assertThat(notifFor(adminA.getId())).hasSize(1);
        assertThat(notifFor(adminB.getId())).hasSize(1);
        assertThat(notifFor(adminC.getId())).hasSize(1);
        Notification rowA = notifFor(adminA.getId()).get(0);
        assertThat(rowA.getCategory()).isEqualTo(NotificationCategory.SUPPORT_TICKET_OPENED);
        assertThat(rowA.getBody()).isEqualTo(
                "Alice Resident opened a new LISTING support ticket: Cannot list parcel");
        assertThat(rowA.getBody()).doesNotContain("—");
        assertThat(rowA.getData()).containsEntry("ticketPublicId", ticketPublicId.toString());
        assertThat(rowA.getData()).containsEntry("subject", "Cannot list parcel");
        assertThat(rowA.getData()).containsEntry("submitterDisplayName", "Alice Resident");
        assertThat(rowA.getData()).containsEntry("category", "LISTING");

        // WS broadcast fired once per admin.
        verify(broadcasterPort, times(3)).broadcastUpsert(anyLong(), any(), any());

        // Admin fan-out path NEVER hits the SL IM dispatcher -- admins read the
        // queue page + sidebar badge, in-world IMs would be spam.
        verify(slImChannelDispatcher, never()).maybeQueue(any(NotificationEvent.class));
        verify(slImChannelDispatcher, never()).maybeQueueForFanout(
                anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void supportTicketUserReplied_fires_inApp_only() {
        User adminA = testUser("admin-reply-a");
        User adminB = testUser("admin-reply-b");
        UUID ticketPublicId = UUID.randomUUID();

        transactionTemplate.execute(status -> {
            publisher.supportTicketUserReplied(
                    List.of(adminA.getId(), adminB.getId()),
                    ticketPublicId,
                    "Cannot withdraw L$",
                    "Bob Resident");
            return null;
        });

        assertThat(notifFor(adminA.getId())).hasSize(1);
        assertThat(notifFor(adminB.getId())).hasSize(1);
        Notification rowA = notifFor(adminA.getId()).get(0);
        assertThat(rowA.getCategory()).isEqualTo(NotificationCategory.SUPPORT_TICKET_USER_REPLIED);
        assertThat(rowA.getBody()).isEqualTo(
                "Bob Resident replied to a support ticket: Cannot withdraw L$");
        assertThat(rowA.getBody()).doesNotContain("—");
        assertThat(rowA.getData()).containsEntry("ticketPublicId", ticketPublicId.toString());
        assertThat(rowA.getData()).containsEntry("submitterDisplayName", "Bob Resident");

        verify(broadcasterPort, times(2)).broadcastUpsert(anyLong(), any(), any());
        verify(slImChannelDispatcher, never()).maybeQueue(any(NotificationEvent.class));
        verify(slImChannelDispatcher, never()).maybeQueueForFanout(
                anyLong(), any(), any(), any(), any(), any());
    }

    // ── Null/empty input guards ───────────────────────────────────────────────

    @Test
    void supportTicketOpened_empty_admin_list_isNoOp() {
        UUID ticketPublicId = UUID.randomUUID();

        transactionTemplate.execute(status -> {
            publisher.supportTicketOpened(List.of(), ticketPublicId,
                    "x", "x", "BUG");
            return null;
        });

        verify(broadcasterPort, never()).broadcastUpsert(anyLong(), any(), any());
        verify(slImChannelDispatcher, never()).maybeQueue(any(NotificationEvent.class));
    }
}
