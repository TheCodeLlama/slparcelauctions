package com.slparcelauctions.backend.notification.slim.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.slparcelauctions.backend.notification.NotificationPublisher;
import com.slparcelauctions.backend.notification.slim.SlImMessage;
import com.slparcelauctions.backend.notification.slim.SlImMessageRepository;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import java.time.OffsetDateTime;
import java.util.HashMap;
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
 * Vertical-slice integration tests verifying that escrow-event notifications
 * produce SL IM rows for the correct recipients, with gate conditions respected.
 *
 * <p>Rather than driving through EscrowService (which requires terminals,
 * shared secrets, and complex fixture setup), these tests drive directly through
 * {@link NotificationPublisher} — the same publish path used by EscrowService
 * internally. This tests the full chain: publisher → NotificationService.publish
 * → afterCommit → SlImChannelDispatcher → sl_im_message row.
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
class EscrowImIntegrationTest {

    @Autowired NotificationPublisher notificationPublisher;
    @Autowired SlImMessageRepository slImRepo;
    @Autowired UserRepository userRepo;
    @Autowired PlatformTransactionManager txManager;
    @Autowired DataSource dataSource;

    private final java.util.List<Long> userIds = new java.util.ArrayList<>();

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

    // --- Tests ---

    @Test
    void escrowFunded_queuesSellerOnly_notWinner() {
        User seller = saveUser(true);
        User winner = saveUser(true);
        long auctionId = 1001L;
        long escrowId  = 2001L;

        // Simulate: winner pays into escrow → seller gets ESCROW_FUNDED notification
        new TransactionTemplate(txManager).executeWithoutResult(status ->
            notificationPublisher.escrowFunded(
                seller.getId(), auctionId, escrowId,
                "TestParcel", OffsetDateTime.now().plusHours(72)));

        long sellerRows = slImRepo.findAll().stream()
            .filter(m -> m.getUserId().equals(seller.getId())).count();
        long winnerRows = slImRepo.findAll().stream()
            .filter(m -> m.getUserId().equals(winner.getId())).count();
        assertThat(sellerRows).isEqualTo(1L);
        assertThat(winnerRows).isZero();
    }

    @Test
    void transferConfirmed_queuesBothParties() {
        User seller = saveUser(true);
        User winner = saveUser(true);
        long auctionId = 1002L;
        long escrowId  = 2002L;

        // Simulate: transfer confirmed → both seller and winner notified
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            notificationPublisher.escrowTransferConfirmed(
                seller.getId(), auctionId, escrowId, "TestParcel");
            notificationPublisher.escrowTransferConfirmed(
                winner.getId(), auctionId, escrowId, "TestParcel");
        });

        long sellerRows = slImRepo.findAll().stream()
            .filter(m -> m.getUserId().equals(seller.getId())).count();
        long winnerRows = slImRepo.findAll().stream()
            .filter(m -> m.getUserId().equals(winner.getId())).count();
        assertThat(sellerRows).isEqualTo(1L);
        assertThat(winnerRows).isEqualTo(1L);
    }

    @Test
    void transferConfirmed_winnerMuted_onlySellerGetsIm() {
        User seller = saveUser(true);
        User winner = saveUser(true);
        winner.setNotifySlImMuted(true);
        userRepo.save(winner);

        long auctionId = 1003L;
        long escrowId  = 2003L;

        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            notificationPublisher.escrowTransferConfirmed(
                seller.getId(), auctionId, escrowId, "TestParcel");
            notificationPublisher.escrowTransferConfirmed(
                winner.getId(), auctionId, escrowId, "TestParcel");
        });

        long sellerRows = slImRepo.findAll().stream()
            .filter(m -> m.getUserId().equals(seller.getId())).count();
        long winnerRows = slImRepo.findAll().stream()
            .filter(m -> m.getUserId().equals(winner.getId())).count();
        assertThat(sellerRows).isEqualTo(1L);
        assertThat(winnerRows).isZero();
    }

    @Test
    void disputed_queuesBothParties() {
        User seller = saveUser(true);
        User winner = saveUser(true);
        long auctionId = 1004L;
        long escrowId  = 2004L;

        // Simulate: dispute filed → both parties get ESCROW_DISPUTED notification
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            notificationPublisher.escrowDisputed(
                seller.getId(), auctionId, escrowId, "TestParcel", "OWNERSHIP_DISPUTE");
            notificationPublisher.escrowDisputed(
                winner.getId(), auctionId, escrowId, "TestParcel", "OWNERSHIP_DISPUTE");
        });

        long sellerRows = slImRepo.findAll().stream()
            .filter(m -> m.getUserId().equals(seller.getId())).count();
        long winnerRows = slImRepo.findAll().stream()
            .filter(m -> m.getUserId().equals(winner.getId())).count();
        assertThat(sellerRows).isEqualTo(1L);
        assertThat(winnerRows).isEqualTo(1L);
    }

    @Test
    void payout_queuesSellerOnly() {
        User seller = saveUser(true);
        User winner = saveUser(true);  // winner not notified of payout
        long auctionId = 1005L;
        long escrowId  = 2005L;

        // Simulate: payout completed → seller only gets ESCROW_PAYOUT notification
        new TransactionTemplate(txManager).executeWithoutResult(status ->
            notificationPublisher.escrowPayout(
                seller.getId(), auctionId, escrowId, "TestParcel", 5000L));

        long sellerRows = slImRepo.findAll().stream()
            .filter(m -> m.getUserId().equals(seller.getId())).count();
        long winnerRows = slImRepo.findAll().stream()
            .filter(m -> m.getUserId().equals(winner.getId())).count();
        assertThat(sellerRows).isEqualTo(1L);
        assertThat(winnerRows).isZero();
    }

    // --- Fixture helpers ---

    private User saveUser(boolean hasAvatar) {
        User u = User.builder().username("u-" + UUID.randomUUID().toString().substring(0, 8))
            .email("u-" + UUID.randomUUID() + "@test.local")
            .passwordHash("hash")
            .build();
        if (hasAvatar) {
            u.setSlAvatarUuid(UUID.randomUUID());
        }
        u.setNotifySlImMuted(false);
        // Use default notifySlIm which has escrow=true
        User saved = userRepo.save(u);
        userIds.add(saved.getId());
        return saved;
    }
}
