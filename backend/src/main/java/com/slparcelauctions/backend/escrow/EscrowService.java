package com.slparcelauctions.backend.escrow;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.escrow.broadcast.EscrowBroadcastPublisher;
import com.slparcelauctions.backend.escrow.broadcast.EscrowCreatedEnvelope;
import com.slparcelauctions.backend.escrow.exception.IllegalEscrowTransitionException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Central orchestrator for escrow lifecycle transitions (spec §4.2). Tasks
 * 2-9 progressively add methods — this task adds
 * {@link #createForEndedAuction} which stamps the ESCROW_PENDING row in the
 * same transaction that closes the auction and schedules the
 * {@code ESCROW_CREATED} broadcast on afterCommit.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EscrowService {

    static final Map<EscrowState, Set<EscrowState>> ALLOWED_TRANSITIONS = Map.of(
            EscrowState.ESCROW_PENDING,
                    Set.of(EscrowState.FUNDED, EscrowState.EXPIRED, EscrowState.DISPUTED),
            EscrowState.FUNDED,
                    Set.of(EscrowState.TRANSFER_PENDING, EscrowState.DISPUTED),
            EscrowState.TRANSFER_PENDING,
                    Set.of(EscrowState.COMPLETED, EscrowState.EXPIRED,
                           EscrowState.FROZEN, EscrowState.DISPUTED),
            EscrowState.COMPLETED, Set.of(),
            EscrowState.EXPIRED, Set.of(),
            EscrowState.DISPUTED, Set.of(),
            EscrowState.FROZEN, Set.of()
    );

    private static final long PAYMENT_DEADLINE_HOURS = 48;

    private final EscrowRepository escrowRepo;
    private final EscrowTransactionRepository ledgerRepo;
    private final EscrowCommissionCalculator commission;
    private final Clock clock;
    private final EscrowBroadcastPublisher broadcastPublisher;

    public static boolean isAllowed(EscrowState from, EscrowState to) {
        return ALLOWED_TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    public static void enforceTransitionAllowed(Long escrowId, EscrowState from, EscrowState to) {
        if (!isAllowed(from, to)) {
            throw new IllegalEscrowTransitionException(escrowId, from, to);
        }
    }

    /**
     * Creates the Escrow row for an auction that just closed with
     * {@code endOutcome ∈ {SOLD, BOUGHT_NOW}}. Called from
     * {@code AuctionEndTask.closeOne} and from the inline buy-it-now close
     * path in {@code BidService} — both run inside the caller's transaction,
     * hence the {@link Propagation#MANDATORY} contract: a stray call outside
     * a transaction is a bug we want to fail fast rather than silently
     * persist with no close-transaction to roll back with.
     *
     * <p>The {@code ESCROW_CREATED} envelope is captured inside the
     * transaction (while {@code saved} is managed) and registered on
     * {@code afterCommit} so subscribers never observe a row that gets
     * rolled back with the close. On rollback the synchronization's
     * {@code afterCommit} callback is never invoked — no phantom envelope
     * for a reverted creation.
     *
     * <p>Caller passes {@code endedAt} explicitly rather than reading the
     * clock here so the {@code paymentDeadline} is anchored to the exact
     * instant the auction row records as {@code endedAt}. Reading
     * {@code OffsetDateTime.now(clock)} here could drift microseconds from
     * the caller's {@code now}, breaking cross-channel event ordering and
     * subtly shifting the 48h deadline. Spec §4.3.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Escrow createForEndedAuction(Auction auction, OffsetDateTime endedAt) {
        long finalBid = auction.getFinalBidAmount();
        Escrow escrow = Escrow.builder()
                .auction(auction)
                .state(EscrowState.ESCROW_PENDING)
                .finalBidAmount(finalBid)
                .commissionAmt(commission.commission(finalBid))
                .payoutAmt(commission.payout(finalBid))
                .paymentDeadline(endedAt.plusHours(PAYMENT_DEADLINE_HOURS))
                .consecutiveWorldApiFailures(0)
                .build();
        Escrow saved = escrowRepo.save(escrow);

        final EscrowCreatedEnvelope envelope = EscrowCreatedEnvelope.of(saved, endedAt);
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        broadcastPublisher.publishCreated(envelope);
                    }
                });

        log.info("Escrow {} created for auction {} (final L${}, commission L${}, payout L${})",
                saved.getId(), auction.getId(), finalBid, saved.getCommissionAmt(), saved.getPayoutAmt());
        return saved;
    }
}
