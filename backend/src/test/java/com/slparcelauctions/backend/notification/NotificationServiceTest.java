package com.slparcelauctions.backend.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.NoSuchElementException;
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
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import com.slparcelauctions.backend.notification.NotificationDao.UpsertResult;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Integration coverage for {@link NotificationService}. The
 * {@link NotificationWsBroadcasterPort} is replaced with a {@code @MockitoBean}
 * so assertions can verify broadcast timing (after-commit, not during tx).
 *
 * <p>These tests use a real Postgres connection (dev profile) to verify the
 * transaction propagation semantics — unit mocks cannot observe the
 * {@code TransactionSynchronizationManager} afterCommit hook behaviour.
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
        "slpa.notifications.cleanup.enabled=false"
})
class NotificationServiceTest {

    @Autowired NotificationService service;
    @Autowired NotificationRepository repo;
    @Autowired UserRepository userRepository;
    @Autowired DataSource dataSource;
    @Autowired TransactionTemplate txTemplate;

    @MockitoBean
    NotificationWsBroadcasterPort wsBroadcaster;

    private Long userId;
    private Long otherUserId;

    @BeforeEach
    void createUsers() {
        userId = userRepository.save(User.builder()
                .email("notif-svc-a-" + UUID.randomUUID() + "@example.com")
                .passwordHash("hash").build()).getId();
        otherUserId = userRepository.save(User.builder()
                .email("notif-svc-b-" + UUID.randomUUID() + "@example.com")
                .passwordHash("hash").build()).getId();
    }

    @AfterEach
    void cleanup() throws Exception {
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (var stmt = conn.createStatement()) {
                for (Long id : new Long[]{userId, otherUserId}) {
                    if (id != null) {
                        stmt.execute("DELETE FROM notification WHERE user_id = " + id);
                        stmt.execute("DELETE FROM users WHERE id = " + id);
                    }
                }
            }
        }
        userId = null;
        otherUserId = null;
    }

    // ── helper ────────────────────────────────────────────────────────────────

    private NotificationEvent buildEvent(long uid, NotificationCategory cat, String coalesceKey) {
        return new NotificationEvent(uid, cat, "Title", "Body", Map.of("k", "v"), coalesceKey);
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    @Test
    void publish_writesInTxAndRegistersAfterCommitBroadcast() {
        // Run inside a transaction — MANDATORY propagation requires it
        UpsertResult result = txTemplate.execute(status ->
                service.publish(buildEvent(userId, NotificationCategory.OUTBID, "outbid:1")));

        assertThat(result).isNotNull();
        assertThat(result.wasUpdate()).isFalse();

        // Broadcaster must have been called after commit (not inside tx)
        verify(wsBroadcaster).broadcastUpsert(eq(userId), eq(result), any());
        verify(wsBroadcaster, never()).broadcastReadStateChanged(anyLong());
    }

    @Test
    void publish_whenParentRollsBackNotificationAlsoRollsBack() {
        // Force a rollback after publishing
        txTemplate.executeWithoutResult(status -> {
            service.publish(buildEvent(userId, NotificationCategory.OUTBID, "outbid:rollback"));
            status.setRollbackOnly();
        });

        // Row must not exist — transaction was rolled back
        assertThat(repo.countByUserIdAndReadFalse(userId)).isEqualTo(0L);
        // Broadcaster must NOT have been called (no afterCommit when rolled back)
        verify(wsBroadcaster, never()).broadcastUpsert(anyLong(), any(), any());
    }

    @Test
    void publish_outsideTransaction_throws() {
        // No active transaction → Propagation.MANDATORY must throw
        assertThatThrownBy(() ->
                service.publish(buildEvent(userId, NotificationCategory.OUTBID, "outbid:notx")))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void markRead_idempotentOnAlreadyRead() {
        // Seed one notification
        txTemplate.executeWithoutResult(status ->
                service.publish(buildEvent(userId, NotificationCategory.OUTBID, "outbid:idem")));
        long notifId = repo.findForUserUnfiltered(userId, false, org.springframework.data.domain.PageRequest.of(0, 1))
                .getContent().get(0).getId();

        // First mark-read
        txTemplate.executeWithoutResult(status -> service.markRead(userId, notifId));
        verify(wsBroadcaster).broadcastReadStateChanged(userId);

        org.mockito.Mockito.clearInvocations(wsBroadcaster);

        // Second mark-read on already-read row → no broadcast
        txTemplate.executeWithoutResult(status -> service.markRead(userId, notifId));
        verify(wsBroadcaster, never()).broadcastReadStateChanged(anyLong());
    }

    @Test
    void markRead_onCrossUserRow_throwsNoSuchElement() {
        // Seed a notification for userId
        txTemplate.executeWithoutResult(status ->
                service.publish(buildEvent(userId, NotificationCategory.OUTBID, "outbid:cross")));
        long notifId = repo.findForUserUnfiltered(userId, false, org.springframework.data.domain.PageRequest.of(0, 1))
                .getContent().get(0).getId();

        // otherUserId trying to mark userId's notification → must throw
        assertThatThrownBy(() ->
                txTemplate.executeWithoutResult(status ->
                        service.markRead(otherUserId, notifId)))
                .isInstanceOf(NoSuchElementException.class);

        verify(wsBroadcaster, never()).broadcastReadStateChanged(anyLong());
    }

    @Test
    void markRead_onUnread_firesBroadcastAfterCommit() {
        txTemplate.executeWithoutResult(status ->
                service.publish(buildEvent(userId, NotificationCategory.AUCTION_WON, "won:1")));
        long notifId = repo.findForUserUnfiltered(userId, false, org.springframework.data.domain.PageRequest.of(0, 1))
                .getContent().get(0).getId();

        org.mockito.Mockito.clearInvocations(wsBroadcaster);

        txTemplate.executeWithoutResult(status -> service.markRead(userId, notifId));

        verify(wsBroadcaster).broadcastReadStateChanged(userId);
        verify(wsBroadcaster, never()).broadcastUpsert(anyLong(), any(), any());
    }

    @Test
    void markAllRead_withGroupFilter_onlyMarksThatGroup() {
        txTemplate.executeWithoutResult(status -> {
            service.publish(buildEvent(userId, NotificationCategory.OUTBID, "outbid:g1"));
            service.publish(buildEvent(userId, NotificationCategory.PROXY_EXHAUSTED, null));
            service.publish(buildEvent(userId, NotificationCategory.AUCTION_WON, "won:g1"));
        });

        org.mockito.Mockito.clearInvocations(wsBroadcaster);

        int affected = txTemplate.execute(status ->
                service.markAllRead(userId, NotificationGroup.BIDDING));

        assertThat(affected).isEqualTo(2);
        assertThat(repo.countByUserIdAndReadFalse(userId)).isEqualTo(1L);
        verify(wsBroadcaster).broadcastReadStateChanged(userId);
    }
}
