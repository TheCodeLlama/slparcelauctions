package com.slparcelauctions.backend.sl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import com.slparcelauctions.backend.escrow.EscrowTransactionRepository;
import com.slparcelauctions.backend.escrow.EscrowTransactionType;
import com.slparcelauctions.backend.sl.dto.PenaltyPaymentRequest;
import com.slparcelauctions.backend.sl.exception.PenaltyOverpaymentException;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Epic 08 sub-spec 2 Task 3 regression: two concurrent penalty payments
 * targeting the SAME seller from two different terminals must serialise
 * on the User-row pessimistic lock acquired inside
 * {@link PenaltyTerminalService#pay}. Without the lock, both transactions
 * could read the same outstanding balance and both apply, double-debiting
 * the seller below zero.
 *
 * <p>Mirrors the harness style of {@code CancelLadderRaceTest}: NOT
 * {@code @Transactional}, so each thread runs its own transaction and
 * the row lock actually contends.
 *
 * <p>Setup: seller owes L$1000. Two threads each attempt to pay L$600
 * with distinct {@code slTransactionId}s. Accepted outcome:
 * <ol>
 *   <li>One thread commits, leaving balance = 400 and one
 *       LISTING_PENALTY_PAYMENT ledger row.</li>
 *   <li>The second thread sees balance = 400, tries 600, hits
 *       {@link PenaltyOverpaymentException}; no second ledger row,
 *       balance unchanged at 400.</li>
 * </ol>
 * Total ledger rows for this seller = 1; balance never goes negative.
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "slpa.auction-end.enabled=false",
        "slpa.ownership-monitor.enabled=false",
        "slpa.escrow.ownership-monitor-job.enabled=false",
        "slpa.notifications.cleanup.enabled=false",
        "slpa.notifications.sl-im.cleanup.enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PenaltyTerminalServiceConcurrencyIntegrationTest {

    @Autowired PenaltyTerminalService service;
    @Autowired UserRepository userRepository;
    @Autowired EscrowTransactionRepository ledgerRepository;
    @Autowired DataSource dataSource;

    private Long sellerId;
    private UUID avatarUuid;

    @AfterEach
    void cleanup() throws Exception {
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (var stmt = conn.createStatement()) {
                if (sellerId != null) {
                    stmt.execute(
                            "DELETE FROM escrow_transactions WHERE payer_id = " + sellerId);
                    stmt.execute("DELETE FROM users WHERE id = " + sellerId);
                }
            }
        }
        sellerId = null;
        avatarUuid = null;
    }

    @Test
    void pay_simultaneousAtTwoTerminals_serializesViaUserRowLock() throws Exception {
        setup(1000L);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicReference<Throwable> err1 = new AtomicReference<>();
        AtomicReference<Throwable> err2 = new AtomicReference<>();

        Runnable pay1 = () -> {
            ready.countDown();
            try {
                go.await();
                service.pay(new PenaltyPaymentRequest(
                        avatarUuid, "sl-txn-race-1", 600L, "terminal-A"));
            } catch (Throwable t) {
                err1.set(t);
            }
        };
        Runnable pay2 = () -> {
            ready.countDown();
            try {
                go.await();
                service.pay(new PenaltyPaymentRequest(
                        avatarUuid, "sl-txn-race-2", 600L, "terminal-B"));
            } catch (Throwable t) {
                err2.set(t);
            }
        };

        Thread t1 = new Thread(pay1, "race-pay-1");
        Thread t2 = new Thread(pay2, "race-pay-2");
        t1.setDaemon(true);
        t2.setDaemon(true);
        t1.start();
        t2.start();

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        go.countDown();

        t1.join(TimeUnit.SECONDS.toMillis(20));
        t2.join(TimeUnit.SECONDS.toMillis(20));

        // Exactly one thread succeeds; the other must throw
        // PenaltyOverpaymentException because 1000 - 600 = 400 < 600.
        Throwable e1 = err1.get();
        Throwable e2 = err2.get();
        boolean firstSucceeded = e1 == null;
        boolean secondSucceeded = e2 == null;
        assertThat(firstSucceeded ^ secondSucceeded)
                .as("exactly one of the two payments must succeed; the other must overpay")
                .isTrue();
        Throwable failure = firstSucceeded ? e2 : e1;
        assertThat(failure)
                .as("the losing thread must hit overpayment, not a generic error")
                .isInstanceOf(PenaltyOverpaymentException.class);

        // Final balance is 1000 - 600 = 400; never negative.
        User reloaded = userRepository.findById(sellerId).orElseThrow();
        assertThat(reloaded.getPenaltyBalanceOwed())
                .as("balance must not go negative; one payment of 600 lands")
                .isEqualTo(400L);

        // Exactly one ledger row for the seller.
        long ledgerRows = ledgerRepository.findAll().stream()
                .filter(t -> t.getPayer() != null
                        && sellerId.equals(t.getPayer().getId()))
                .filter(t -> t.getType() == EscrowTransactionType.LISTING_PENALTY_PAYMENT)
                .count();
        assertThat(ledgerRows)
                .as("exactly one LISTING_PENALTY_PAYMENT ledger row must land")
                .isEqualTo(1);
    }

    @Test
    void pay_sequentialPartials_bothLand() throws Exception {
        setup(1000L);

        // First partial: 600 → 400 remaining.
        var resp1 = service.pay(new PenaltyPaymentRequest(
                avatarUuid, "sl-txn-seq-1", 600L, "terminal-A"));
        assertThat(resp1.remainingBalance()).isEqualTo(400L);

        // Second partial: 400 → 0 remaining.
        var resp2 = service.pay(new PenaltyPaymentRequest(
                avatarUuid, "sl-txn-seq-2", 400L, "terminal-B"));
        assertThat(resp2.remainingBalance()).isEqualTo(0L);

        User reloaded = userRepository.findById(sellerId).orElseThrow();
        assertThat(reloaded.getPenaltyBalanceOwed()).isEqualTo(0L);

        long ledgerRows = ledgerRepository.findAll().stream()
                .filter(t -> t.getPayer() != null
                        && sellerId.equals(t.getPayer().getId()))
                .filter(t -> t.getType() == EscrowTransactionType.LISTING_PENALTY_PAYMENT)
                .count();
        assertThat(ledgerRows)
                .as("both partial payments must produce exactly one ledger row each")
                .isEqualTo(2);
    }

    @Test
    void pay_idempotentReplay_doesNotDoubleDebit() throws Exception {
        setup(1000L);

        var first = service.pay(new PenaltyPaymentRequest(
                avatarUuid, "sl-txn-idem", 600L, "terminal-A"));
        assertThat(first.remainingBalance()).isEqualTo(400L);

        // Replay of the same slTransactionId — must short-circuit.
        var replay = service.pay(new PenaltyPaymentRequest(
                avatarUuid, "sl-txn-idem", 600L, "terminal-A"));
        assertThat(replay.remainingBalance())
                .as("replay must return the current balance, not re-decrement")
                .isEqualTo(400L);

        User reloaded = userRepository.findById(sellerId).orElseThrow();
        assertThat(reloaded.getPenaltyBalanceOwed())
                .as("balance must reflect exactly one payment")
                .isEqualTo(400L);

        long ledgerRows = ledgerRepository.findAll().stream()
                .filter(t -> t.getPayer() != null
                        && sellerId.equals(t.getPayer().getId()))
                .filter(t -> t.getType() == EscrowTransactionType.LISTING_PENALTY_PAYMENT)
                .count();
        assertThat(ledgerRows)
                .as("idempotent replay must not write a second ledger row")
                .isEqualTo(1);
    }

    private void setup(long initialBalance) {
        avatarUuid = UUID.randomUUID();
        User seller = userRepository.save(User.builder()
                .email("penalty-payer-" + UUID.randomUUID() + "@example.com")
                .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                .displayName("Penalty Payer")
                .slAvatarUuid(avatarUuid)
                .verified(true)
                .penaltyBalanceOwed(initialBalance)
                .bannedFromListing(false)
                .build());
        this.sellerId = seller.getId();
    }
}
