package com.slparcelauctions.backend.auction;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.slparcelauctions.backend.auction.broadcast.AuctionBroadcastPublisher;
import com.slparcelauctions.backend.auction.broadcast.BidSettlementEnvelope;
import com.slparcelauctions.backend.auction.dto.BidResponse;
import com.slparcelauctions.backend.auction.exception.AuctionAlreadyEndedException;
import com.slparcelauctions.backend.auction.exception.AuctionNotFoundException;
import com.slparcelauctions.backend.auction.exception.BidTooLowException;
import com.slparcelauctions.backend.auction.exception.InvalidAuctionStateException;
import com.slparcelauctions.backend.auction.exception.NotVerifiedException;
import com.slparcelauctions.backend.auction.exception.SellerCannotBidException;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserNotFoundException;
import com.slparcelauctions.backend.user.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Bid-placement service. Task 2 implements the core path only: no proxy
 * counter, no snipe extension, no buy-it-now. Those branches land in
 * Tasks 3 and 4 on top of this scaffolding — the {@code @Transactional}
 * shape, lock acquisition, validation chain, and {@code afterCommit}
 * publish hygiene are all production shape from day one.
 *
 * <p><strong>Concurrency model.</strong> Every placement opens a
 * {@code READ_COMMITTED} transaction and acquires a pessimistic write
 * lock on the auction row via
 * {@link AuctionRepository#findByIdForUpdate(Long)} before any validation
 * runs. This guarantees that two concurrent bids on the same auction are
 * strictly serialised at the database layer — the loser reads the
 * committed {@code currentBid} after the winner's commit lands, which
 * pushes the amount below {@code currentBid + minIncrement} and trips
 * {@link BidTooLowException}. The pin test for this guarantee lives in
 * {@code BidBidRaceTest}.
 *
 * <p><strong>Broadcast hygiene.</strong> The {@link BidSettlementEnvelope}
 * is published via
 * {@link TransactionSynchronizationManager#registerSynchronization} so
 * subscribers never observe a {@code currentBid} the database hasn't yet
 * committed. On rollback the synchronization's {@code afterCommit}
 * callback is never invoked — the publish is silently skipped, which is
 * exactly what we want. Task 5 swaps the no-op publisher for the real
 * STOMP implementation; the {@code BidService} wiring is stable across
 * that swap.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BidService {

    private final AuctionRepository auctionRepo;
    private final BidRepository bidRepo;
    private final UserRepository userRepo;
    private final Clock clock;
    private final AuctionBroadcastPublisher publisher;

    /**
     * Places a manual bid on an auction. See class javadoc for the
     * concurrency model.
     *
     * @param auctionId the auction to bid on
     * @param bidderId  the authenticated bidder's user id
     * @param amount    the proposed bid in L$ (strictly positive; JSR-380
     *                  validation runs in the controller layer)
     * @param ipAddress the bidder's client IP for abuse-audit persistence;
     *                  nullable when the broken-chain case fires
     *                  ({@code X-Forwarded-For} present but from an
     *                  untrusted proxy) — see spec §6 IP capture.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public BidResponse placeBid(Long auctionId, Long bidderId, long amount, String ipAddress) {
        // Step 1 — lock the auction row for update. Holds until commit,
        // serialising every other write path on this auction id.
        Auction auction = auctionRepo.findByIdForUpdate(auctionId)
                .orElseThrow(() -> new AuctionNotFoundException(auctionId));

        // Step 2 — only ACTIVE auctions accept bids. Status is flipped to
        // ENDED / CANCELLED / SUSPENDED by scheduler / cancellation /
        // ownership paths, all of which also take the same lock.
        if (auction.getStatus() != AuctionStatus.ACTIVE) {
            throw new InvalidAuctionStateException(auctionId, auction.getStatus(), "BID");
        }

        // Step 3 — clock check inside the lock. The scheduler may not have
        // closed the row yet (its own tx is waiting on the lock, or due to
        // poll cadence), but we refuse to accept a post-deadline bid.
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (!auction.getEndsAt().isAfter(now)) {
            throw new AuctionAlreadyEndedException(auction.getEndsAt());
        }

        // Step 4 — bidder validation. Two distinct 403s: NOT_VERIFIED is
        // remediable (go verify the SL avatar); SELLER_CANNOT_BID is not.
        User bidder = userRepo.findById(bidderId)
                .orElseThrow(() -> new UserNotFoundException(bidderId));
        if (!Boolean.TRUE.equals(bidder.getVerified())) {
            throw new NotVerifiedException();
        }
        if (bidder.getId().equals(auction.getSeller().getId())) {
            throw new SellerCannotBidException();
        }

        // Step 5 — minimum-bid gate. First bid must clear startingBid;
        // subsequent bids must clear currentBid + minIncrement(currentBid).
        // The increment tier is keyed off the *current* bid, not the
        // proposed amount — see BidIncrementTable javadoc.
        long currentBid = auction.getCurrentBid() == null ? 0L : auction.getCurrentBid();
        long minRequired = currentBid > 0L
                ? currentBid + BidIncrementTable.minIncrement(currentBid)
                : auction.getStartingBid();
        if (amount < minRequired) {
            throw new BidTooLowException(minRequired);
        }

        // Step 6 (Task 2: trivial case only) — emit a single MANUAL bid.
        // Task 4 introduces the proxy-counter branch here.
        Bid bid = Bid.builder()
                .auction(auction)
                .bidder(bidder)
                .amount(amount)
                .bidType(BidType.MANUAL)
                .proxyBidId(null)
                .snipeExtensionMinutes(null)
                .newEndsAt(null)
                .ipAddress(ipAddress)
                .build();
        bid = bidRepo.save(bid);

        // Step 10 — update auction aggregate state. Task 3 will insert the
        // snipe-extension and buy-it-now evaluation between steps 6 and 10;
        // for Task 2 we just bump currentBid / currentBidderId / bidCount.
        auction.setCurrentBid(amount);
        auction.setCurrentBidderId(bidder.getId());
        int nextBidCount = (auction.getBidCount() == null ? 0 : auction.getBidCount()) + 1;
        auction.setBidCount(nextBidCount);
        auctionRepo.save(auction);

        // Step 11 — publish the envelope on afterCommit so subscribers
        // never observe an uncommitted currentBid. The envelope is built
        // now (inside the tx, with the in-memory entities initialised)
        // and captured into the synchronization; the publish call runs
        // post-commit, outside the persistence context.
        final BidSettlementEnvelope envelope = BidSettlementEnvelope.of(
                auction, List.of(bid), bidder, clock);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                publisher.publishSettlement(envelope);
            }
        });

        log.info("Bid placed: auctionId={}, bidderId={}, amount={}, newBidCount={}",
                auctionId, bidderId, amount, nextBidCount);

        // Step 12 — return the post-commit response. Task 2 never triggers
        // buy-it-now, so the flag is always false here.
        return BidResponse.from(bid, auction, false);
    }
}
