package com.slparcelauctions.backend.admin.infrastructure.reconciliation;

import com.slparcelauctions.backend.escrow.EscrowRepository;
import com.slparcelauctions.backend.escrow.EscrowState;
import com.slparcelauctions.backend.escrow.terminal.Terminal;
import com.slparcelauctions.backend.escrow.terminal.TerminalRepository;
import com.slparcelauctions.backend.notification.NotificationPublisher;
import com.slparcelauctions.backend.user.Role;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.wallet.BidReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "slpa.reconciliation", name = "enabled",
                       havingValue = "true", matchIfMissing = true)
@Slf4j
public class ReconciliationService {

    private static final Duration BALANCE_STALENESS = Duration.ofHours(2);
    private static final Duration RETRY_WAIT = Duration.ofSeconds(30);
    private static final List<EscrowState> LOCKED_STATES = List.of(
            EscrowState.FUNDED, EscrowState.TRANSFER_PENDING,
            EscrowState.DISPUTED, EscrowState.FROZEN);

    private final TerminalRepository terminalRepo;
    private final EscrowRepository escrowRepo;
    private final ReconciliationRunRepository runRepo;
    private final UserRepository userRepo;
    private final NotificationPublisher publisher;
    private final BidReservationRepository reservationRepo;
    private final Clock clock;

    @Scheduled(cron = "${slpa.reconciliation.cron:0 0 3 * * *}", zone = "UTC")
    @Transactional
    public void runDaily() {
        log.info("Reconciliation starting");
        OffsetDateTime now = OffsetDateTime.now(clock);

        // Wallet model: precheck users.reserved_lindens denorm against the
        // bid_reservations live sum. If they disagree, the expected_total
        // computation is unreliable — abort with DENORM_DRIFT.
        long denormReserved = userRepo.sumReservedDenorms();
        long sourceReserved = reservationRepo.sumAllActive();
        if (denormReserved != sourceReserved) {
            long denormDrift = denormReserved - sourceReserved;
            log.error("Reconciliation aborted: DENORM_DRIFT — "
                    + "users.reserved_lindens sum={} vs bid_reservations sum={} (diff={})",
                    denormReserved, sourceReserved, denormDrift);
            ReconciliationRun run = persist(now, 0L, null, denormDrift,
                    ReconciliationStatus.DENORM_DRIFT,
                    "users.reserved_lindens denorm != bid_reservations sum");
            run.setWalletReservedTotal(sourceReserved);
            runRepo.save(run);
            List<Long> adminIds = userRepo.findByRole(Role.ADMIN).stream()
                    .map(User::getId).toList();
            String date = now.format(DateTimeFormatter.ISO_LOCAL_DATE);
            publisher.reconciliationMismatch(adminIds, denormDrift, date);
            return;
        }

        Optional<Long> balanceOpt = freshestBalance(now);
        if (balanceOpt.isEmpty()) {
            persist(now, sumExpected(), null, null,
                    ReconciliationStatus.ERROR,
                    "Balance data stale — terminal may be offline");
            log.error("Reconciliation aborted: balance reading stale (>2h)");
            return;
        }

        long observed = balanceOpt.get();
        long locked = sumExpected();
        if (observed >= locked) {
            persist(now, locked, observed, observed - locked, ReconciliationStatus.BALANCED, null);
            log.info("Reconciliation completed: status=BALANCED, expected={}, observed={}, drift={}",
                    locked, observed, observed - locked);
            return;
        }

        try { Thread.sleep(RETRY_WAIT.toMillis()); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        Optional<Long> retryOpt = freshestBalance(OffsetDateTime.now(clock));
        long retryObserved = retryOpt.orElse(observed);
        long retryLocked = sumExpected();
        if (retryObserved >= retryLocked) {
            persist(now, retryLocked, retryObserved, retryObserved - retryLocked,
                    ReconciliationStatus.BALANCED, null);
            log.info("Reconciliation completed (after retry): status=BALANCED, " +
                    "expected={}, observed={}, drift={}",
                    retryLocked, retryObserved, retryObserved - retryLocked);
            return;
        }

        long drift = retryObserved - retryLocked;
        persist(now, retryLocked, retryObserved, drift,
                ReconciliationStatus.MISMATCH, null);
        log.error("Reconciliation completed: status=MISMATCH, expected={}, observed={}, drift={}",
                retryLocked, retryObserved, drift);

        List<Long> adminIds = userRepo.findByRole(Role.ADMIN).stream()
                .map(User::getId).toList();
        String date = now.format(DateTimeFormatter.ISO_LOCAL_DATE);
        publisher.reconciliationMismatch(adminIds, drift, date);
    }

    private Optional<Long> freshestBalance(OffsetDateTime now) {
        OffsetDateTime cutoff = now.minus(BALANCE_STALENESS);
        return terminalRepo.findAll().stream()
                .filter(t -> t.getLastHeartbeatAt() != null
                        && t.getLastHeartbeatAt().isAfter(cutoff)
                        && t.getLastReportedBalance() != null)
                .max(Comparator.comparing(Terminal::getLastHeartbeatAt))
                .map(Terminal::getLastReportedBalance);
    }

    private long sumLocked() {
        return escrowRepo.sumAmountByStateIn(LOCKED_STATES);
    }

    /**
     * Total L$ the SLPA service avatar is expected to hold:
     * locked escrows (winner's L$ moved into escrow at auction close) +
     * wallet balances (user-deposited L$ awaiting allocation/withdraw).
     * Reservations are partitions of wallet balance; do NOT add separately.
     */
    private long sumExpected() {
        return sumLocked() + userRepo.sumWalletBalances();
    }

    private ReconciliationRun persist(OffsetDateTime ranAt, long expected,
                                       Long observed, Long drift,
                                       ReconciliationStatus status, String errorMessage) {
        long escrowLocked = sumLocked();
        long walletBalance = userRepo.sumWalletBalances();
        long reserved = reservationRepo.sumAllActive();
        return runRepo.save(ReconciliationRun.builder()
                .ranAt(ranAt)
                .expectedLockedSum(expected)
                .observedBalance(observed)
                .drift(drift)
                .status(status)
                .errorMessage(errorMessage)
                .walletBalanceTotal(walletBalance)
                .walletReservedTotal(reserved)
                .escrowLockedTotal(escrowLocked)
                .build());
    }
}
