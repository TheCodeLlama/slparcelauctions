package com.slparcelauctions.backend.bot;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.bot.dto.BotTaskResultRequest;
import com.slparcelauctions.backend.bot.dto.BuyOwnerResultRequest;
import com.slparcelauctions.backend.escrow.Escrow;
import com.slparcelauctions.backend.escrow.EscrowManualActionService;
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

    /**
     * Bot {@code VERIFY_BUY_OWNER} result callback. Applies the bot-dispatched
     * verify-purchase outcome matrix (see {@link BuyOwnerOutcome}):
     *
     * <ul>
     *   <li>{@code OWNER_IS_WINNER} → {@link EscrowService#confirmTransfer};
     *       clear {@code manualVerifyPending}; task COMPLETED.</li>
     *   <li>{@code OWNER_STILL_PRE_TRANSFER} → consume the
     *       {@code buyVerifySellerAttempts} or {@code buyVerifyBuyerAttempts}
     *       counter depending on the {@code requestingRole} payload stamped at
     *       dispatch; {@link EscrowService#stampChecked}; clear
     *       {@code manualVerifyPending}; task COMPLETED. Definitive negative
     *       per attempt — the manual flow is not a recurring schedule, so the
     *       task does not reschedule.</li>
     *   <li>{@code OWNER_IS_STRANGER} →
     *       {@link EscrowService#freezeForFraud}{@code (UNKNOWN_OWNER)};
     *       clear pending; task COMPLETED.</li>
     *   <li>{@code PARCEL_DELETED} →
     *       {@link EscrowService#freezeForFraud}{@code (PARCEL_DELETED)};
     *       clear pending; task COMPLETED.</li>
     *   <li>{@code WORLD_API_FAILURE} / {@code UNKNOWN_ERROR} → transient. Clear
     *       {@code manualVerifyPending} so the user can retry; no attempt
     *       consumed; task FAILED. The 30-min background ownership polling
     *       remains the system-driven safety net for these escrows.</li>
     * </ul>
     *
     * <p>Idempotent on terminal task state. Tasks are not rescheduled — manual
     * verify is a one-shot per user click, unlike the recurring Set-Sell-To
     * task.
     */
    @Transactional
    public void applyVerifyBuyOwnerResult(long taskId, BuyOwnerResultRequest body) {
        BotTask task = botTaskRepo.findById(taskId).orElse(null);
        if (task == null) {
            log.debug("Buy-owner result for unknown task {} — no-op", taskId);
            return;
        }
        if (task.getTaskType() != BotTaskType.VERIFY_BUY_OWNER) {
            log.warn("Buy-owner result for task {} of wrong type {} — no-op",
                    taskId, task.getTaskType());
            return;
        }
        if (isTerminal(task.getStatus())) {
            log.debug("Buy-owner result for terminal task {} (status {}) — idempotent no-op",
                    taskId, task.getStatus());
            return;
        }
        if (task.getEscrow() == null) {
            log.warn("Buy-owner result for task {} with no escrow — no-op", taskId);
            return;
        }

        Escrow escrow = escrowRepo.findByIdForUpdate(task.getEscrow().getId())
                .orElse(null);
        if (escrow == null) {
            log.warn("Buy-owner result for task {}: escrow {} not found — no-op",
                    taskId, task.getEscrow().getId());
            return;
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        BuyOwnerOutcome outcome = body.outcome();
        String requestingRole = extractRequestingRole(task);
        log.info("Applying buy-owner result {} for task {} (escrow {}, auction {}, role {})",
                outcome, taskId, escrow.getId(), escrow.getAuction().getId(), requestingRole);

        switch (outcome) {
            case OWNER_IS_WINNER -> {
                escrow.setManualVerifyPending(false);
                escrowRepo.save(escrow);
                escrowService.confirmTransfer(escrow, now);
                completeTask(task, now);
            }
            case OWNER_STILL_PRE_TRANSFER -> {
                if (EscrowManualActionService.REQUESTING_ROLE_SELLER.equals(requestingRole)) {
                    escrow.setBuyVerifySellerAttempts(
                            nz(escrow.getBuyVerifySellerAttempts()) + 1);
                } else {
                    // Default to buyer-counter for missing / "BUYER" / unknown
                    // — caller-side dispatch always stamps the role, but if the
                    // payload is ever absent we prefer to charge the buyer
                    // counter (which is the more common manual-verify caller).
                    escrow.setBuyVerifyBuyerAttempts(
                            nz(escrow.getBuyVerifyBuyerAttempts()) + 1);
                }
                escrow.setManualVerifyPending(false);
                escrowRepo.save(escrow);
                escrowService.stampChecked(escrow, now);
                completeTask(task, now);
            }
            case OWNER_IS_STRANGER -> {
                escrow.setManualVerifyPending(false);
                escrowRepo.save(escrow);
                Map<String, Object> evidence = new HashMap<>();
                evidence.put("observedOwnerUuid",
                        body.observedOwnerUuid() == null ? "<null>"
                                : body.observedOwnerUuid().toString());
                evidence.put("observedOwnerType",
                        body.observedOwnerType() == null ? "<null>"
                                : body.observedOwnerType());
                evidence.put("source", "bot-verify-buy-owner");
                escrowService.freezeForFraud(escrow, FreezeReason.UNKNOWN_OWNER, evidence, now);
                completeTask(task, now);
            }
            case PARCEL_DELETED -> {
                escrow.setManualVerifyPending(false);
                escrowRepo.save(escrow);
                Map<String, Object> evidence = new HashMap<>();
                evidence.put("source", "bot-verify-buy-owner");
                escrowService.freezeForFraud(escrow, FreezeReason.PARCEL_DELETED, evidence, now);
                completeTask(task, now);
            }
            case WORLD_API_FAILURE, UNKNOWN_ERROR -> {
                // Transient: no attempt consumed, no state change beyond
                // clearing the pending flag so the user can retry.
                escrow.setManualVerifyPending(false);
                escrowRepo.save(escrow);
                task.setStatus(BotTaskStatus.FAILED);
                task.setFailureReason(outcome.name());
                task.setCompletedAt(now);
                botTaskRepo.save(task);
            }
        }
    }

    private static String extractRequestingRole(BotTask task) {
        Map<String, Object> payload = task.getResultData();
        if (payload == null) {
            return null;
        }
        Object role = payload.get(EscrowManualActionService.REQUESTING_ROLE_KEY);
        return role == null ? null : role.toString();
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
