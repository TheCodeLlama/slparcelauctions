package com.slparcelauctions.backend.notification.integration;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.slparcelauctions.backend.notification.NotificationWsBroadcasterPort;
import com.slparcelauctions.backend.notification.ws.AccountStateBroadcaster;
import com.slparcelauctions.backend.sl.PenaltyTerminalService;
import com.slparcelauctions.backend.sl.dto.PenaltyPaymentRequest;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Vertical-slice integration tests for the penalty-cleared WS broadcast.
 *
 * <p>This path is invalidation-only — no Notification row is written.
 * We verify via {@code @MockitoBean AccountStateBroadcaster}.
 * The real implementation wraps the broadcast in an afterCommit hook, so
 * this test validates end-to-end: pay → tx commits → broadcaster called.
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
        "slpa.notifications.sl-im.cleanup.enabled=false"
})
class PenaltyClearedBroadcastIntegrationTest {

    @Autowired PenaltyTerminalService penaltyTerminalService;
    @Autowired UserRepository userRepo;
    @Autowired PlatformTransactionManager txManager;
    @Autowired DataSource dataSource;

    @MockitoBean AccountStateBroadcaster accountStateBroadcaster;
    @MockitoBean NotificationWsBroadcasterPort wsBroadcaster;

    private Long userId;

    @AfterEach
    void cleanup() throws Exception {
        if (userId != null) {
            try (var conn = dataSource.getConnection()) {
                conn.setAutoCommit(true);
                try (var st = conn.createStatement()) {
                    st.execute("DELETE FROM escrow_transactions WHERE payer_id = " + userId);
                    st.execute("DELETE FROM refresh_tokens WHERE user_id = " + userId);
                    st.execute("DELETE FROM users WHERE id = " + userId);
                }
            }
            userId = null;
        }
    }

    private User userWithPenalty(long balance) {
        return new TransactionTemplate(txManager).execute(s -> {
            User u = userRepo.save(User.builder()
                    .email("penalty-" + UUID.randomUUID() + "@test.com")
                    .passwordHash("h")
                    .displayName("PenaltyUser")
                    .slAvatarUuid(UUID.randomUUID())
                    .verified(true)
                    .penaltyBalanceOwed(balance)
                    .cancelledWithBids(0)
                    .build());
            userId = u.getId();
            return u;
        });
    }

    @Test
    void payClearingBalance_broadcastsPenaltyCleared() {
        User u = userWithPenalty(1000L);
        PenaltyPaymentRequest req = new PenaltyPaymentRequest(
                u.getSlAvatarUuid(),
                "sl-txn-clear-" + UUID.randomUUID(),
                1000L, "terminal-1");

        penaltyTerminalService.pay(req);

        verify(accountStateBroadcaster).broadcastPenaltyCleared(u.getId());
    }

    @Test
    void payPartial_doesNotBroadcast() {
        User u = userWithPenalty(1000L);
        PenaltyPaymentRequest req = new PenaltyPaymentRequest(
                u.getSlAvatarUuid(),
                "sl-txn-partial-" + UUID.randomUUID(),
                500L, "terminal-1");

        penaltyTerminalService.pay(req);

        verify(accountStateBroadcaster, never()).broadcastPenaltyCleared(anyLong());
    }
}
