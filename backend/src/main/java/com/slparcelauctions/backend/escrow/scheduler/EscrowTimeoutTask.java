package com.slparcelauctions.backend.escrow.scheduler;

import java.time.OffsetDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.escrow.Escrow;
import com.slparcelauctions.backend.escrow.EscrowRepository;
import com.slparcelauctions.backend.escrow.EscrowService;
import com.slparcelauctions.backend.escrow.EscrowState;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Per-escrow timeout worker dispatched by {@link EscrowTimeoutJob} (spec
 * §4.6). Re-locks the escrow row under {@code PESSIMISTIC_WRITE} and
 * re-validates the state + deadline before calling the service's
 * {@code expirePayment} / {@code expireTransfer} method (which fires the
 * state transition, refund queue, and broadcast).
 *
 * <p>The re-validation defuses races between the sweep's snapshot read and
 * this task: a concurrent payment acceptance could have flipped
 * ESCROW_PENDING to TRANSFER_PENDING, or an admin tool could have pushed
 * the deadline forward. Short-circuiting in either case leaves the escrow
 * untouched; the next sweep will re-decide from the refreshed row.
 *
 * <p>Per-escrow work runs in a fresh transaction ({@code REQUIRES_NEW}) so
 * the sweep's loop-level exception handling can isolate failures — a single
 * bad escrow can't stall the rest of the sweep. The explicit propagation
 * setting enforces this invariant even if a future refactor ever wraps the
 * surrounding {@code sweep()} in {@code @Transactional}; the default
 * {@code REQUIRED} would silently join the outer transaction and one
 * failure would taint the whole sweep. Mirrors the shape of
 * {@link EscrowOwnershipCheckTask}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EscrowTimeoutTask {

    private final EscrowRepository escrowRepo;
    private final EscrowService escrowService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void expirePayment(Long escrowId, OffsetDateTime now) {
        Escrow escrow = escrowRepo.findByIdForUpdate(escrowId).orElse(null);
        if (escrow == null || escrow.getState() != EscrowState.ESCROW_PENDING) {
            log.debug("Escrow {} payment-timeout skipped: state changed between sweep and lock",
                    escrowId);
            return;
        }
        if (escrow.getPaymentDeadline() == null || escrow.getPaymentDeadline().isAfter(now)) {
            log.debug("Escrow {} payment-timeout skipped: deadline not yet past", escrowId);
            return;
        }
        escrowService.expirePayment(escrow, now);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void expireTransfer(Long escrowId, OffsetDateTime now) {
        Escrow escrow = escrowRepo.findByIdForUpdate(escrowId).orElse(null);
        if (escrow == null || escrow.getState() != EscrowState.TRANSFER_PENDING) {
            log.debug("Escrow {} transfer-timeout skipped: state changed between sweep and lock",
                    escrowId);
            return;
        }
        if (escrow.getTransferDeadline() == null || escrow.getTransferDeadline().isAfter(now)) {
            log.debug("Escrow {} transfer-timeout skipped: deadline not yet past", escrowId);
            return;
        }
        // Note: the payout-in-flight guard was already applied by the
        // findExpiredTransferPendingIds query. We do not re-check it here
        // because a PAYOUT command created between the query and this lock
        // would be a benign race (the rare case where ownership confirm
        // + queue happens in the same 5min window as the deadline passes).
        // If it becomes an issue in practice, tighten by re-querying
        // countActivePayoutCommands here.
        escrowService.expireTransfer(escrow, now);
    }
}
