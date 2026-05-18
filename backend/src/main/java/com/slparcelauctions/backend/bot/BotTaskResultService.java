package com.slparcelauctions.backend.bot;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.bot.dto.BotTaskResultRequest;
import com.slparcelauctions.backend.escrow.Escrow;
import com.slparcelauctions.backend.escrow.EscrowRepository;
import com.slparcelauctions.backend.escrow.EscrowService;
import com.slparcelauctions.backend.escrow.FreezeReason;
import com.slparcelauctions.backend.escrow.review.EscrowManualReview;
import com.slparcelauctions.backend.escrow.review.EscrowManualReviewRepository;
import com.slparcelauctions.backend.escrow.review.ManualReviewReason;
import com.slparcelauctions.backend.escrow.review.ManualReviewRole;
import com.slparcelauctions.backend.escrow.review.ManualReviewStatus;
import com.slparcelauctions.backend.escrow.review.ManualReviewStep;
import com.slparcelauctions.backend.escrow.terminal.EscrowConfigProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Applies a bot {@code VERIFY_SELL_TO} result callback to the escrow
 * (spec §5.3, plan Task 3.2). The escrow row is taken under a
 * {@code PESSIMISTIC_WRITE} lock ({@link EscrowRepository#findByIdForUpdate})
 * so the outcome serialises against the manual verify path, the ownership
 * monitor, the timeout job and the payment callback that take the same lock.
 *
 * <p><b>Idempotent on terminal task state.</b> Applying a result to an
 * already-{@code COMPLETED}/{@code CANCELLED}/{@code FAILED} task (or a
 * missing task) is a no-op — the bot may legitimately re-POST after a
 * network blip, and the recurring task may have been resolved by the manual
 * path in the interim.
 *
 * <p>Outcome matrix (spec §5.3):
 * <ul>
 *   <li>{@code SELL_TO_OK} → {@link EscrowService#confirmSellTo}; task COMPLETED.</li>
 *   <li>{@code OWNER_ALREADY_WINNER} → {@code confirmSellTo} then
 *       {@link EscrowService#confirmTransfer}; task COMPLETED (the full
 *       seller→buyer transfer completed before we observed Step 2).</li>
 *   <li>{@code SELL_TO_NOT_SET}/{@code WRONG_BUYER}/{@code PRICE_NOT_ZERO}
 *       (definitive negative) → record {@code sellToLastResult} +
 *       {@code sellToLastCheckedAt}; consume one {@code sellToVerifyAttempts}
 *       and clear {@code manualVerifyPending} only if it was set;
 *       reschedule.</li>
 *   <li>{@code ACCESS_DENIED}/{@code BOT_ERROR} → increment
 *       {@code consecutiveSellToBotFailures}, no attempt consumed, clear
 *       {@code manualVerifyPending}; at threshold open an idempotent
 *       {@code EscrowManualReview(BOT_PERSISTENT_FAILURE)}; reschedule on the
 *       fast {@code sellToBotRetryBackoff} (the parcel is fine — the bot just
 *       couldn't observe it, so a 30m wait needlessly stalls a healthy
 *       transfer).</li>
 *   <li>{@code PARCEL_NOT_FOUND} → same streak; at threshold
 *       {@link EscrowService#freezeForFraud}{@code (PARCEL_DELETED)};
 *       reschedule on the normal {@code sellToBotRecurrence}.</li>
 * </ul>
 *
 * <p>Reschedule = {@code nextRunAt = now + backoff} only while
 * {@code transferDeadline} is in the future; once the deadline has elapsed
 * the recurring task is {@code CANCELLED} (the timeout job expires/refunds
 * the escrow — no point re-checking a parcel whose deadline has passed). The
 * backoff is {@code sellToBotRetryBackoff} for transient infra failures
 * ({@code BOT_ERROR}/{@code ACCESS_DENIED}) and {@code sellToBotRecurrence}
 * for definitive negatives and {@code PARCEL_NOT_FOUND}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BotTaskResultService {

    private final BotTaskRepository botTaskRepo;
    private final EscrowRepository escrowRepo;
    private final EscrowService escrowService;
    private final EscrowManualReviewRepository manualReviewRepo;
    private final EscrowConfigProperties props;
    private final Clock clock;

    @Transactional
    public void apply(long taskId, BotTaskResultRequest body) {
        BotTask task = botTaskRepo.findById(taskId).orElse(null);
        if (task == null) {
            log.debug("Bot result for unknown task {} — no-op", taskId);
            return;
        }
        if (isTerminal(task.getStatus())) {
            log.debug("Bot result for terminal task {} (status {}) — idempotent no-op",
                    taskId, task.getStatus());
            return;
        }
        if (task.getEscrow() == null) {
            log.warn("Bot result for task {} with no escrow — no-op", taskId);
            return;
        }

        Escrow escrow = escrowRepo.findByIdForUpdate(task.getEscrow().getId())
                .orElse(null);
        if (escrow == null) {
            log.warn("Bot result for task {}: escrow {} not found — no-op",
                    taskId, task.getEscrow().getId());
            return;
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        SellToOutcome outcome = body.outcome();
        log.info("Applying bot result {} for task {} (escrow {}, auction {})",
                outcome, taskId, escrow.getId(), escrow.getAuction().getId());

        switch (outcome) {
            case SELL_TO_OK -> {
                escrowService.confirmSellTo(escrow, now);
                completeTask(task, now);
            }
            case OWNER_ALREADY_WINNER -> {
                escrowService.confirmSellTo(escrow, now);
                escrowService.confirmTransfer(escrow, now);
                completeTask(task, now);
            }
            case SELL_TO_NOT_SET, WRONG_BUYER, PRICE_NOT_ZERO ->
                    definitiveNegative(task, escrow, outcome, now);
            case ACCESS_DENIED, BOT_ERROR ->
                    infraFailure(task, escrow, outcome, now, false);
            case PARCEL_NOT_FOUND ->
                    infraFailure(task, escrow, outcome, now, true);
        }
    }

    private void definitiveNegative(BotTask task, Escrow escrow,
                                    SellToOutcome outcome, OffsetDateTime now) {
        escrow.setSellToLastResult(outcome.name());
        escrow.setSellToLastCheckedAt(now);
        boolean manualPending = Boolean.TRUE.equals(escrow.getManualVerifyPending());
        if (manualPending) {
            escrow.setSellToVerifyAttempts(nz(escrow.getSellToVerifyAttempts()) + 1);
            escrow.setManualVerifyPending(false);
        }
        escrowRepo.save(escrow);
        reschedule(task, escrow, now, props.sellToBotRecurrence());
    }

    private void infraFailure(BotTask task, Escrow escrow, SellToOutcome outcome,
                              OffsetDateTime now, boolean parcelGone) {
        int newCount = nz(escrow.getConsecutiveSellToBotFailures()) + 1;
        escrow.setConsecutiveSellToBotFailures(newCount);
        // Infra failures never burn a manual attempt; clear the arming flag
        // so a stale "manual pending" doesn't consume an attempt on a later
        // genuine definitive-negative the seller didn't ask for.
        escrow.setManualVerifyPending(false);
        escrowRepo.save(escrow);

        int threshold = props.sellToBotFailureThreshold();
        if (newCount >= threshold) {
            if (parcelGone) {
                Map<String, Object> evidence = new HashMap<>();
                evidence.put("consecutiveSellToBotFailures", newCount);
                evidence.put("threshold", threshold);
                evidence.put("lastOutcome", outcome.name());
                evidence.put("source", "bot-verify-sell-to");
                escrowService.freezeForFraud(
                        escrow, FreezeReason.PARCEL_DELETED, evidence, now);
                // Frozen escrow already routes to the admin fraud queue —
                // the recurring task is dead, cancel it.
                cancelTask(task);
                return;
            }
            openManualReviewIfAbsent(escrow);
        }
        // PARCEL_NOT_FOUND keeps the slow recurrence (deliberate negative —
        // a missing parcel won't reappear in 2 minutes). BOT_ERROR /
        // ACCESS_DENIED are transient: the parcel is fine, the bot just
        // couldn't observe it, so retry fast instead of stalling a healthy
        // transfer for a full recurrence interval.
        Duration backoff = parcelGone
                ? props.sellToBotRecurrence()
                : props.sellToBotRetryBackoff();
        reschedule(task, escrow, now, backoff);
    }

    private void openManualReviewIfAbsent(Escrow escrow) {
        boolean exists = manualReviewRepo
                .findByEscrowIdAndStatus(escrow.getId(), ManualReviewStatus.OPEN)
                .isPresent();
        if (exists) {
            log.debug("Manual review already open for escrow {} — idempotent no-op",
                    escrow.getId());
            return;
        }
        EscrowManualReview review = EscrowManualReview.builder()
                .escrow(escrow)
                .requestedByUserId(null)
                .requestedRole(ManualReviewRole.SYSTEM)
                .step(ManualReviewStep.SET_SELL_TO)
                .reason(ManualReviewReason.BOT_PERSISTENT_FAILURE)
                .status(ManualReviewStatus.OPEN)
                .build();
        manualReviewRepo.save(review);
        log.warn("Auto-opened manual review for escrow {} (auction {}): "
                + "persistent bot Set-Sell-To failure",
                escrow.getId(), escrow.getAuction().getId());
    }

    /**
     * Reschedule the recurring task {@code backoff} from now, but only while
     * the transfer deadline is in the future; otherwise cancel it (the
     * timeout job expires/refunds). Callers pass {@code sellToBotRetryBackoff}
     * for transient infra failures and {@code sellToBotRecurrence} for
     * definitive negatives and {@code PARCEL_NOT_FOUND}.
     */
    private void reschedule(BotTask task, Escrow escrow, OffsetDateTime now,
                            Duration backoff) {
        OffsetDateTime deadline = escrow.getTransferDeadline();
        if (deadline != null && deadline.isAfter(now)) {
            task.setNextRunAt(now.plus(backoff));
            task.setStatus(BotTaskStatus.PENDING);
            botTaskRepo.save(task);
        } else {
            cancelTask(task);
        }
    }

    private void completeTask(BotTask task, OffsetDateTime now) {
        task.setStatus(BotTaskStatus.COMPLETED);
        task.setCompletedAt(now);
        botTaskRepo.save(task);
    }

    private void cancelTask(BotTask task) {
        task.setStatus(BotTaskStatus.CANCELLED);
        botTaskRepo.save(task);
    }

    private static boolean isTerminal(BotTaskStatus s) {
        return s == BotTaskStatus.COMPLETED
                || s == BotTaskStatus.CANCELLED
                || s == BotTaskStatus.FAILED;
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
