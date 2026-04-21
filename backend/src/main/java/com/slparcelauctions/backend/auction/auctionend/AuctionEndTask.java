package com.slparcelauctions.backend.auction.auctionend;

import java.time.Clock;
import java.time.OffsetDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionEndOutcome;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.ProxyBidRepository;
import com.slparcelauctions.backend.auction.broadcast.AuctionBroadcastPublisher;
import com.slparcelauctions.backend.auction.broadcast.AuctionEndedEnvelope;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Per-auction close worker invoked by {@link AuctionEndScheduler}. Re-acquires
 * the pessimistic lock on the auction row, re-validates the three gates
 * (status=ACTIVE, endsAt<=now, auction still present), classifies the terminal
 * outcome (spec §8), flips the auction to {@link AuctionStatus#ENDED},
 * exhausts every remaining ACTIVE proxy, and schedules an
 * {@link AuctionEndedEnvelope} broadcast on {@code afterCommit}.
 *
 * <p><strong>Why re-check under the lock.</strong> Between the scheduler's
 * {@code findActiveIdsDueForEnd} read and this worker's lock acquisition a
 * bid placement may have committed, pushing {@code endsAt} forward via
 * snipe protection. The re-check skips the close in that case — the next
 * sweep will re-evaluate once the new deadline passes. Similarly the status
 * check handles the case where a concurrent cancellation, suspension, or
 * buy-it-now already closed the auction.
 *
 * <p><strong>Bean separation.</strong> {@code AuctionEndTask} is a separate
 * bean from {@link AuctionEndScheduler} so the {@code @Transactional}
 * boundary is enforced through Spring's AOP proxy. A self-invocation inside
 * the scheduler would bypass the proxy and run the close in a connection-per-
 * statement non-transactional context, losing the pessimistic lock guarantee.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuctionEndTask {

    private final AuctionRepository auctionRepo;
    private final ProxyBidRepository proxyBidRepo;
    private final UserRepository userRepo;
    private final AuctionBroadcastPublisher publisher;
    private final Clock clock;

    /**
     * Closes a single auction under a fresh transaction + pessimistic lock.
     * See class javadoc for the re-check rationale. Safe to call for ids that
     * were already closed by another path — the status gate short-circuits
     * without any mutation.
     */
    @Transactional
    public void closeOne(Long auctionId) {
        Auction auction = auctionRepo.findByIdForUpdate(auctionId).orElse(null);
        if (auction == null) {
            // Race with a hard-delete path (not currently possible in Phase 1
            // but defensive). Nothing to close.
            log.debug("Auction-end skipped: auction {} not found", auctionId);
            return;
        }
        if (auction.getStatus() != AuctionStatus.ACTIVE) {
            // Already closed by buy-it-now, cancellation, suspension, or a
            // previous scheduler run. No-op.
            log.debug("Auction-end skipped: auction {} status={}",
                    auctionId, auction.getStatus());
            return;
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (auction.getEndsAt().isAfter(now)) {
            // Snipe-extended between the scheduler's query and our lock
            // acquisition. Let the next sweep re-evaluate.
            log.debug("Auction-end skipped: auction {} endsAt={} is still in the future",
                    auctionId, auction.getEndsAt());
            return;
        }

        AuctionEndOutcome outcome = classifyOutcome(auction);
        auction.setStatus(AuctionStatus.ENDED);
        auction.setEndOutcome(outcome);
        auction.setEndedAt(now);
        if (outcome == AuctionEndOutcome.SOLD) {
            auction.setWinnerUserId(auction.getCurrentBidderId());
            auction.setFinalBidAmount(auction.getCurrentBid());
        }
        auctionRepo.save(auction);

        // Exhaust any lingering ACTIVE proxies inside the same transaction so
        // the partial-unique-index slot is released atomically with the status
        // flip. Runs BEFORE afterCommit registration so it's part of the unit
        // of work the synchronization is waiting on.
        int exhausted = proxyBidRepo.exhaustAllActiveByAuctionId(auctionId);
        if (exhausted > 0) {
            log.debug("Exhausted {} active proxies on closed auction {}",
                    exhausted, auctionId);
        }

        // Resolve winner display-name for the envelope on the SOLD path. The
        // RESERVE_NOT_MET / NO_BIDS outcomes publish a null winnerDisplayName —
        // the envelope factory already handles the null User case.
        User winner = outcome == AuctionEndOutcome.SOLD
                ? userRepo.findById(auction.getCurrentBidderId()).orElse(null)
                : null;
        final AuctionEndedEnvelope envelope = AuctionEndedEnvelope.of(auction, winner, clock);

        // Publish only after the transaction commits. On rollback the
        // synchronization's afterCommit callback is never invoked, which is
        // the desired behaviour — no phantom envelopes for reverted closes.
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        publisher.publishEnded(envelope);
                    }
                });

        log.info("Auction {} closed: outcome={}, finalBid={}, winnerUserId={}, bidCount={}",
                auctionId, outcome, auction.getFinalBidAmount(),
                auction.getWinnerUserId(), auction.getBidCount());
    }

    /**
     * Resolves the terminal outcome for a just-expired ACTIVE auction.
     * {@code NO_BIDS} wins over {@code RESERVE_NOT_MET} — an auction with no
     * bids never had a reserve comparison. Above the reserve (or with no
     * reserve set at all), {@code SOLD} is the outcome.
     */
    private AuctionEndOutcome classifyOutcome(Auction a) {
        if (a.getBidCount() == null || a.getBidCount() == 0) {
            return AuctionEndOutcome.NO_BIDS;
        }
        if (a.getReservePrice() != null && a.getCurrentBid() < a.getReservePrice()) {
            return AuctionEndOutcome.RESERVE_NOT_MET;
        }
        return AuctionEndOutcome.SOLD;
    }
}
