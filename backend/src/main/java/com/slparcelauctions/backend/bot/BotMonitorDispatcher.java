package com.slparcelauctions.backend.bot;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.slparcelauctions.backend.auction.fraud.FraudFlagReason;
import com.slparcelauctions.backend.auction.monitoring.SuspensionService;
import com.slparcelauctions.backend.bot.dto.BotMonitorResultRequest;
import com.slparcelauctions.backend.escrow.EscrowService;
import com.slparcelauctions.backend.escrow.FreezeReason;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Interprets {@link MonitorOutcome} observations emitted by the bot worker
 * and dispatches to the appropriate downstream service. Returns a
 * {@link DispatchOutcome} indicating whether the caller should re-arm the
 * monitor row — paths that trigger lifecycle hooks (suspend, freeze,
 * confirmTransfer) return {@code shouldReArm=false} so the hook's
 * CANCELLED write is not overwritten. Spec §6.
 *
 * <p>Streak tracking for repeated ACCESS_DENIED observations lives in the
 * JSONB {@code result_data.accessDeniedStreak} key. On any non-denial
 * outcome the counter resets to 0; on {@link MonitorOutcome#ACCESS_DENIED}
 * it increments, and upon hitting
 * {@link #ACCESS_DENIED_STREAK_THRESHOLD} the dispatcher escalates
 * (suspend on MONITOR_AUCTION, markReviewRequired on MONITOR_ESCROW).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BotMonitorDispatcher {

    static final int ACCESS_DENIED_STREAK_THRESHOLD = 3;
    private static final String STREAK_KEY = "accessDeniedStreak";
    private static final String TRANSFER_READY_KEY = "transferReady";

    private final SuspensionService suspensionService;
    private final EscrowService escrowService;
    private final Clock clock;

    public DispatchOutcome dispatch(BotTask task, BotMonitorResultRequest req) {
        ensureResultData(task);
        return switch (task.getTaskType()) {
            case MONITOR_AUCTION -> dispatchAuction(task, req);
            case MONITOR_ESCROW -> dispatchEscrow(task, req);
            case VERIFY -> throw new IllegalStateException(
                    "VERIFY task " + task.getId() + " on monitor dispatcher");
        };
    }

    private DispatchOutcome dispatchAuction(BotTask task, BotMonitorResultRequest req) {
        switch (req.outcome()) {
            case ALL_GOOD:
                resetStreak(task);
                return new DispatchOutcome(true, "ALL_GOOD");
            case AUTH_BUYER_CHANGED:
                resetStreak(task);
                suspensionService.suspendForBotObservation(
                        task.getAuction(),
                        FraudFlagReason.BOT_AUTH_BUYER_REVOKED,
                        evidence(req));
                return new DispatchOutcome(false, "SUSPENDED_AUTH_BUYER_REVOKED");
            case PRICE_MISMATCH:
                resetStreak(task);
                suspensionService.suspendForBotObservation(
                        task.getAuction(),
                        FraudFlagReason.BOT_PRICE_DRIFT,
                        evidence(req));
                return new DispatchOutcome(false, "SUSPENDED_PRICE_DRIFT");
            case OWNER_CHANGED:
                resetStreak(task);
                suspensionService.suspendForBotObservation(
                        task.getAuction(),
                        FraudFlagReason.BOT_OWNERSHIP_CHANGED,
                        evidence(req));
                return new DispatchOutcome(false, "SUSPENDED_OWNERSHIP");
            case ACCESS_DENIED:
                int newStreak = bumpStreak(task);
                if (newStreak >= ACCESS_DENIED_STREAK_THRESHOLD) {
                    suspensionService.suspendForBotObservation(
                            task.getAuction(),
                            FraudFlagReason.BOT_ACCESS_REVOKED,
                            evidence(req));
                    return new DispatchOutcome(false, "SUSPENDED_ACCESS_REVOKED");
                }
                return new DispatchOutcome(true, "ACCESS_DENIED_STREAK_" + newStreak);
            case TRANSFER_COMPLETE:
            case TRANSFER_READY:
            case STILL_WAITING:
                log.warn("Escrow outcome {} received on auction task {}; ignoring",
                        req.outcome(), task.getId());
                resetStreak(task);
                return new DispatchOutcome(true, "ESCROW_OUTCOME_ON_AUCTION");
            default:
                throw new IllegalStateException(
                        "Unknown MonitorOutcome " + req.outcome());
        }
    }

    private DispatchOutcome dispatchEscrow(BotTask task, BotMonitorResultRequest req) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        switch (req.outcome()) {
            case STILL_WAITING:
                resetStreak(task);
                return new DispatchOutcome(true, "STILL_WAITING");
            case TRANSFER_READY:
                resetStreak(task);
                boolean firstTransition =
                        !Boolean.TRUE.equals(task.getResultData().get(TRANSFER_READY_KEY));
                task.getResultData().put(TRANSFER_READY_KEY, true);
                if (firstTransition) {
                    escrowService.publishTransferReadyObserved(task.getEscrow());
                }
                return new DispatchOutcome(true, "TRANSFER_READY");
            case TRANSFER_COMPLETE:
                resetStreak(task);
                escrowService.confirmTransfer(task.getEscrow(), now);
                return new DispatchOutcome(false, "CONFIRMED_TRANSFER");
            case OWNER_CHANGED:
                resetStreak(task);
                if (req.observedOwner() != null
                        && req.observedOwner().equals(task.getExpectedWinnerUuid())) {
                    // Backend re-check: the worker's classifier lost the
                    // winner-match race. Treat as TRANSFER_COMPLETE.
                    escrowService.confirmTransfer(task.getEscrow(), now);
                    return new DispatchOutcome(false, "CONFIRMED_TRANSFER_VIA_OWNER_CHANGED");
                }
                escrowService.freezeForFraud(
                        task.getEscrow(),
                        FreezeReason.BOT_OWNERSHIP_CHANGED,
                        evidence(req),
                        now);
                return new DispatchOutcome(false, "FROZEN_OWNERSHIP");
            case AUTH_BUYER_CHANGED:
                resetStreak(task);
                if (req.observedAuthBuyer() != null
                        && req.observedAuthBuyer().equals(task.getExpectedWinnerUuid())) {
                    // Seller configured sale-to-winner. Backend re-check:
                    // treat as TRANSFER_READY.
                    boolean first =
                            !Boolean.TRUE.equals(task.getResultData().get(TRANSFER_READY_KEY));
                    task.getResultData().put(TRANSFER_READY_KEY, true);
                    if (first) {
                        escrowService.publishTransferReadyObserved(task.getEscrow());
                    }
                    return new DispatchOutcome(true, "TRANSFER_READY_VIA_AUTH_BUYER");
                }
                log.info("AUTH_BUYER_CHANGED during escrow {} (observed={}); "
                                + "seller reconfiguring",
                        task.getEscrow().getId(), req.observedAuthBuyer());
                return new DispatchOutcome(true, "AUTH_BUYER_CHANGED_INFO");
            case PRICE_MISMATCH:
                resetStreak(task);
                log.info("PRICE_MISMATCH during escrow {} (observed={}); "
                                + "seller adjusting",
                        task.getEscrow().getId(), req.observedSalePrice());
                return new DispatchOutcome(true, "PRICE_MISMATCH_INFO");
            case ACCESS_DENIED:
                int newStreak = bumpStreak(task);
                if (newStreak >= ACCESS_DENIED_STREAK_THRESHOLD) {
                    escrowService.markReviewRequired(task.getEscrow());
                    return new DispatchOutcome(false, "REVIEW_REQUIRED_ACCESS_DENIED");
                }
                return new DispatchOutcome(true, "ACCESS_DENIED_STREAK_" + newStreak);
            case ALL_GOOD:
                log.warn("ALL_GOOD outcome on escrow task {}; ignoring",
                        task.getId());
                resetStreak(task);
                return new DispatchOutcome(true, "AUCTION_OUTCOME_ON_ESCROW");
            default:
                throw new IllegalStateException(
                        "Unknown MonitorOutcome " + req.outcome());
        }
    }

    private static void ensureResultData(BotTask task) {
        if (task.getResultData() == null) task.setResultData(new HashMap<>());
    }

    private static void resetStreak(BotTask task) {
        task.getResultData().put(STREAK_KEY, 0);
    }

    private static int bumpStreak(BotTask task) {
        Object priorObj = task.getResultData().get(STREAK_KEY);
        int prior = priorObj instanceof Number n ? n.intValue() : 0;
        int next = prior + 1;
        task.getResultData().put(STREAK_KEY, next);
        return next;
    }

    private static Map<String, Object> evidence(BotMonitorResultRequest req) {
        Map<String, Object> ev = new HashMap<>();
        if (req.observedOwner() != null) ev.put("observedOwner", req.observedOwner().toString());
        if (req.observedAuthBuyer() != null) ev.put("observedAuthBuyer", req.observedAuthBuyer().toString());
        if (req.observedSalePrice() != null) ev.put("observedSalePrice", req.observedSalePrice());
        if (req.note() != null) ev.put("note", req.note());
        return ev;
    }
}
