package com.slparcelauctions.backend.escrow.scheduler;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.slparcelauctions.backend.escrow.Escrow;
import com.slparcelauctions.backend.escrow.EscrowRepository;
import com.slparcelauctions.backend.escrow.EscrowTransactionRepository;
import com.slparcelauctions.backend.escrow.broadcast.EscrowBroadcastPublisher;
import com.slparcelauctions.backend.escrow.broadcast.EscrowPayoutStalledEnvelope;
import com.slparcelauctions.backend.escrow.command.EscrowRetryPolicy;
import com.slparcelauctions.backend.escrow.command.TerminalCommand;
import com.slparcelauctions.backend.escrow.command.TerminalCommandBody;
import com.slparcelauctions.backend.escrow.command.TerminalCommandRepository;
import com.slparcelauctions.backend.escrow.command.TerminalCommandService;
import com.slparcelauctions.backend.escrow.command.TerminalCommandStatus;
import com.slparcelauctions.backend.escrow.command.TerminalHttpClient;
import com.slparcelauctions.backend.escrow.terminal.EscrowConfigProperties;
import com.slparcelauctions.backend.escrow.terminal.Terminal;
import com.slparcelauctions.backend.escrow.terminal.TerminalRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Per-command worker invoked by {@link TerminalCommandDispatcherJob}.
 * Dispatches a QUEUED or retry-ready FAILED command by POSTing it to a
 * live terminal, and handles the IN_FLIGHT staleness sweep for terminals
 * that never call back (spec §7.4).
 *
 * <p>Each call runs in a fresh transaction ({@code REQUIRES_NEW}) under a
 * pessimistic lock on the command row so the dispatcher serialises against
 * {@code TerminalCommandService.applyCallback} — one of the two can hold
 * the row at a time, preventing a callback from landing a COMPLETED flip
 * while the dispatcher is re-POSTing, and vice versa. The explicit
 * propagation setting keeps per-command failures isolated from the
 * surrounding sweep even if a future refactor ever wraps it in
 * {@code @Transactional}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TerminalCommandDispatcherTask {

    private final TerminalCommandRepository cmdRepo;
    private final TerminalRepository terminalRepo;
    private final EscrowRepository escrowRepo;
    private final EscrowTransactionRepository ledgerRepo;
    private final TerminalHttpClient terminalHttp;
    private final EscrowBroadcastPublisher broadcastPublisher;
    private final EscrowConfigProperties props;
    private final Clock clock;

    /**
     * Picks the given command up, bumps the attempt counter, stamps
     * {@code dispatchedAt}, flips to {@code IN_FLIGHT}, and POSTs to a live
     * terminal. On transport failure the row flips to {@code FAILED} with
     * backoff or a stall if attempts are exhausted.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void dispatchOne(Long commandId) {
        TerminalCommand cmd = cmdRepo.findByIdForUpdate(commandId).orElse(null);
        if (cmd == null) return;
        if (!isDispatchable(cmd)) {
            return;
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        if (cmd.getNextAttemptAt() != null && cmd.getNextAttemptAt().isAfter(now)) {
            // Another tick ran in between and pushed the backoff forward.
            return;
        }

        Optional<Terminal> terminalOpt =
                terminalRepo.findAnyLive(now.minus(props.terminalLiveWindow()));
        if (terminalOpt.isEmpty()) {
            // No live terminal: defer 1 minute. We leave the row in its
            // current status (QUEUED or FAILED) — the attempt counter is not
            // bumped because we never POSTed. Spec §7.4.
            cmd.setNextAttemptAt(now.plus(Duration.ofMinutes(1)));
            cmdRepo.save(cmd);
            log.warn("No live terminal for command {}; deferring 1 minute", cmd.getId());
            return;
        }

        Terminal terminal = terminalOpt.get();
        cmd.setTerminalId(terminal.getTerminalId());
        cmd.setStatus(TerminalCommandStatus.IN_FLIGHT);
        cmd.setAttemptCount(cmd.getAttemptCount() + 1);
        cmd.setDispatchedAt(now);
        cmd = cmdRepo.save(cmd);

        TerminalCommandBody body = new TerminalCommandBody(
                cmd.getAction().name(),
                cmd.getPurpose().name(),
                cmd.getRecipientUuid(),
                cmd.getAmount(),
                cmd.getEscrowId(),
                cmd.getListingFeeRefundId(),
                cmd.getIdempotencyKey(),
                props.terminalSharedSecret());

        TerminalHttpClient.TerminalHttpResult result =
                terminalHttp.post(terminal.getHttpInUrl(), body);

        if (result.ack()) {
            log.info("Dispatched command {} to terminal {}: attempt {}/{}",
                    cmd.getId(), cmd.getTerminalId(), cmd.getAttemptCount(),
                    EscrowRetryPolicy.MAX_ATTEMPTS);
            // Leave IN_FLIGHT awaiting callback. Staleness sweep requeues
            // if the terminal never calls back within commandInFlightTimeout.
            return;
        }

        // Transport failure — record backoff or stall.
        cmd.setStatus(TerminalCommandStatus.FAILED);
        cmd.setLastError(result.errorMessage());
        if (cmd.getAttemptCount() >= EscrowRetryPolicy.MAX_ATTEMPTS) {
            cmd.setRequiresManualReview(true);
            cmd = cmdRepo.save(cmd);
            // Mirror the terminal-reported-failure ledger pattern: every
            // exhausted attempt — whether the terminal reported a failure
            // (TerminalCommandService.applyCallback) or the transport stalled
            // out (this branch) — gets a FAILED row so the dispute timeline
            // surfaces both shapes uniformly. The row is keyed to the
            // originating escrow / auction when one exists; listing-fee
            // refunds (no escrow) still get a row with a null escrow ref.
            Escrow escrow = cmd.getEscrowId() == null
                    ? null
                    : escrowRepo.findById(cmd.getEscrowId()).orElse(null);
            ledgerRepo.save(TerminalCommandService.buildFailedLedgerRow(
                    cmd, escrow, result.errorMessage(), null));
            publishStall(cmd, escrow, now);
            log.error("Terminal command {} STALLED after {} attempts (transport): err={}",
                    cmd.getId(), cmd.getAttemptCount(), result.errorMessage());
        } else {
            cmd.setNextAttemptAt(now.plus(
                    EscrowRetryPolicy.backoffFor(cmd.getAttemptCount())));
            cmdRepo.save(cmd);
            log.warn("Terminal POST failed for command {}: attempt {}/{}, nextAttemptAt={}, err={}",
                    cmd.getId(), cmd.getAttemptCount(), EscrowRetryPolicy.MAX_ATTEMPTS,
                    cmd.getNextAttemptAt(), result.errorMessage());
        }
    }

    /**
     * Flips an {@code IN_FLIGHT} command back to {@code FAILED} with
     * {@code nextAttemptAt=now} so the next sweep picks it up for an
     * immediate retry. Called by the job for commands whose
     * {@code dispatchedAt} is older than the configured
     * {@code commandInFlightTimeout} — i.e. the terminal never called back.
     *
     * <p>We do NOT increment {@code attempt_count} here — the attempt was
     * already counted when the dispatcher POSTed. The stale path merely
     * recovers the "waiting for callback" row so it can participate in the
     * retry schedule.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markStaleAndRequeue(Long commandId) {
        TerminalCommand cmd = cmdRepo.findByIdForUpdate(commandId).orElse(null);
        if (cmd == null || cmd.getStatus() != TerminalCommandStatus.IN_FLIGHT) return;
        OffsetDateTime now = OffsetDateTime.now(clock);
        cmd.setStatus(TerminalCommandStatus.FAILED);
        cmd.setLastError("IN_FLIGHT timeout without callback");
        cmd.setNextAttemptAt(now);
        cmdRepo.save(cmd);
        log.warn("Command {} IN_FLIGHT timeout (dispatchedAt={}); requeued for immediate retry",
                cmd.getId(), cmd.getDispatchedAt());
    }

    private boolean isDispatchable(TerminalCommand cmd) {
        if (cmd.getStatus() == TerminalCommandStatus.QUEUED) return true;
        if (cmd.getStatus() == TerminalCommandStatus.FAILED
                && !Boolean.TRUE.equals(cmd.getRequiresManualReview())) {
            return true;
        }
        return false;
    }

    private void publishStall(TerminalCommand cmd, Escrow escrow, OffsetDateTime now) {
        // No escrow row (e.g. listing-fee-refund command, or escrow vanished)
        // — the FAILED ledger row above still captures the stall, but the
        // PAYOUT_STALLED envelope is escrow-scoped so we skip it.
        if (escrow == null) return;
        final EscrowPayoutStalledEnvelope env =
                EscrowPayoutStalledEnvelope.of(cmd, escrow, now);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            broadcastPublisher.publishPayoutStalled(env);
                        }
                    });
        } else {
            // No active transaction (unit-test or caller that forgot to
            // wrap) — best-effort publish synchronously. Production callers
            // always run inside the @Transactional dispatchOne so this
            // branch is defensive.
            broadcastPublisher.publishPayoutStalled(env);
        }
    }
}
