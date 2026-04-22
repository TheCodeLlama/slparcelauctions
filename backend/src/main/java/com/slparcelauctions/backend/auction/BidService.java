package com.slparcelauctions.backend.auction;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.slparcelauctions.backend.auction.broadcast.AuctionBroadcastPublisher;
import com.slparcelauctions.backend.auction.broadcast.AuctionEndedEnvelope;
import com.slparcelauctions.backend.auction.broadcast.BidSettlementEnvelope;
import com.slparcelauctions.backend.auction.dto.BidResponse;
import com.slparcelauctions.backend.auction.exception.AuctionAlreadyEndedException;
import com.slparcelauctions.backend.auction.exception.AuctionNotFoundException;
import com.slparcelauctions.backend.auction.exception.BidTooLowException;
import com.slparcelauctions.backend.auction.exception.InvalidAuctionStateException;
import com.slparcelauctions.backend.auction.exception.NotVerifiedException;
import com.slparcelauctions.backend.auction.exception.SellerCannotBidException;
import com.slparcelauctions.backend.escrow.EscrowService;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserNotFoundException;
import com.slparcelauctions.backend.user.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Bid-placement service. Task 2 implemented the core path; Task 3 layered in
 * snipe-protection extension and inline buy-it-now (now delegated to
 * {@link BidPlacementHelpers#applySnipeAndBuyNow}). Task 4 adds the proxy
 * counter-bid branch (spec §6 steps 7-8): when a competing ACTIVE proxy
 * exists on the auction, a manual bid below the competitor's cap emits an
 * additional {@code PROXY_AUTO} row at {@code min(amount + increment, P_max)},
 * putting the proxy owner back on top; a manual bid strictly above the cap
 * flips the competitor to EXHAUSTED and wins outright. Q3 tie-flip: the
 * comparison is strict {@code >}, so {@code amount == P_max} emits the
 * counter and the proxy retains the win.
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
 * <p><strong>Broadcast hygiene.</strong> Envelopes are published via
 * {@link TransactionSynchronizationManager#registerSynchronization} so
 * subscribers never observe a {@code currentBid} the database hasn't yet
 * committed. On rollback the synchronization's {@code afterCommit}
 * callback is never invoked — the publish is silently skipped, which is
 * exactly what we want. The branch between
 * {@link AuctionBroadcastPublisher#publishSettlement} (ACTIVE-post-bid) and
 * {@link AuctionBroadcastPublisher#publishEnded} (inline buy-it-now close)
 * is captured at registration time — subscribers see exactly one envelope
 * per committed placement. Task 5 swaps the no-op publisher for the real
 * STOMP implementation; the {@code BidService} wiring is stable across
 * that swap.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BidService {

    private final AuctionRepository auctionRepo;
    private final BidRepository bidRepo;
    private final ProxyBidRepository proxyBidRepo;
    private final UserRepository userRepo;
    private final Clock clock;
    private final AuctionBroadcastPublisher publisher;
    private final EscrowService escrowService;

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

        // Steps 6-8 — emit 1 or 2 bid rows depending on whether a competing
        // proxy is active. The detection uses
        // findFirstByAuctionIdAndStatusAndBidderIdNot so the caller's own
        // ACTIVE proxy is ignored (a bidder cannot counter their own proxy).
        // Strict >: a manual bid at exactly the proxy's cap counters; only
        // amount > P_max exhausts the proxy (Q3 flip; proxy wins ties).
        List<Bid> emitted = new ArrayList<>(2);
        Optional<ProxyBid> competingProxyOpt = proxyBidRepo
                .findFirstByAuctionIdAndStatusAndBidderIdNot(
                        auctionId, ProxyBidStatus.ACTIVE, bidderId);

        if (competingProxyOpt.isPresent()) {
            ProxyBid competingProxy = competingProxyOpt.get();
            if (amount > competingProxy.getMaxAmount()) {
                // Caller strictly exceeds competitor's cap: exhaust + win.
                competingProxy.setStatus(ProxyBidStatus.EXHAUSTED);
                competingProxy.setUpdatedAt(OffsetDateTime.now(clock));
                proxyBidRepo.save(competingProxy);
                emitted.add(insertManual(auction, bidder, amount, ipAddress));
            } else {
                // Caller meets or under-shoots competitor's cap: competitor
                // counters at min(amount + increment, P_max). Proxy emits LAST
                // so it's the post-resolution top bidder.
                long counterAmount = Math.min(
                        amount + BidIncrementTable.minIncrement(amount),
                        competingProxy.getMaxAmount());
                emitted.add(insertManual(auction, bidder, amount, ipAddress));
                emitted.add(insertProxyAuto(auction, competingProxy.getBidder(),
                        counterAmount, competingProxy.getId()));
            }
        } else {
            emitted.add(insertManual(auction, bidder, amount, ipAddress));
        }

        // Step 9 — snipe extension + inline buy-it-now. Both evaluate every
        // emitted bid in order. Snipe may stamp newEndsAt on each qualifying
        // bid and push auction.endsAt forward. Buy-it-now may flip the
        // auction to ENDED with endOutcome=BOUGHT_NOW; when that happens
        // every ACTIVE proxy on the auction is marked EXHAUSTED so the
        // partial unique index is cleared and no zombie proxies linger.
        BidPlacementHelpers.applySnipeAndBuyNow(auction, emitted, now, proxyBidRepo);

        // Step 8 — update auction aggregate state. The last emitted bid is
        // always the current top (for Task 3 that's the same as the only
        // emitted bid; for Task 4's proxy counter it's the auto-reply row).
        // These updates happen regardless of whether buy-it-now flipped the
        // status to ENDED — the ENDED state is derivative; the top bidder
        // is still the current bidder for read-path consistency.
        Bid top = emitted.getLast();
        User topBidder = top.getBidder();
        auction.setCurrentBid(top.getAmount());
        auction.setCurrentBidderId(topBidder.getId());
        int nextBidCount = (auction.getBidCount() == null ? 0 : auction.getBidCount()) + emitted.size();
        auction.setBidCount(nextBidCount);
        auctionRepo.save(auction);

        // Inline buy-it-now closes stamp the ESCROW_PENDING row in the same
        // transaction as the status flip so close + escrow are atomic; a
        // rollback reverts both. The single `now` read at step 3 is threaded
        // through applySnipeAndBuyNow (stamps auction.endedAt), the escrow
        // stamp below (anchors the 48h payment deadline), and the
        // AuctionEndedEnvelope.of(auction, winner, now) factory (stamps
        // serverTime) so all three values derive from one clock read —
        // otherwise independent OffsetDateTime.now(clock) reads drift by
        // microseconds under Clock.systemUTC() and break cross-channel
        // event ordering. ESCROW_CREATED is registered on afterCommit
        // inside createForEndedAuction and fires BEFORE the AUCTION_ENDED
        // afterCommit below (registration order).
        final boolean ended = auction.getStatus() == AuctionStatus.ENDED;
        if (ended) {
            escrowService.createForEndedAuction(auction, now);
        }

        // Step 9 — publish the envelope on afterCommit so subscribers never
        // observe an uncommitted state. The envelope variant is chosen NOW
        // (inside the tx, with entities initialised) and captured into the
        // synchronization; the publish call runs post-commit, outside the
        // persistence context. Buy-it-now closes emit AuctionEndedEnvelope;
        // everything else emits BidSettlementEnvelope.
        final BidSettlementEnvelope settlement = ended
                ? null
                : BidSettlementEnvelope.of(auction, emitted, topBidder, clock);
        final AuctionEndedEnvelope endedEnv = ended
                ? AuctionEndedEnvelope.of(auction, topBidder, now)
                : null;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                if (ended) {
                    publisher.publishEnded(endedEnv);
                } else {
                    publisher.publishSettlement(settlement);
                }
            }
        });

        log.info("Bid placed: auctionId={}, bidderId={}, amount={}, newBidCount={}, ended={}",
                auctionId, bidderId, amount, nextBidCount, ended);

        // Step 10 — return the post-commit response. buyNowTriggered is
        // derived from the terminal state + outcome so the controller can
        // surface the inline close to the bidder's UI immediately.
        boolean buyNowTriggered = ended && auction.getEndOutcome() == AuctionEndOutcome.BOUGHT_NOW;
        return BidResponse.from(top, auction, buyNowTriggered);
    }

    private Bid insertManual(Auction auction, User bidder, long amount, String ipAddress) {
        return bidRepo.save(Bid.builder()
                .auction(auction)
                .bidder(bidder)
                .amount(amount)
                .bidType(BidType.MANUAL)
                .proxyBidId(null)
                .snipeExtensionMinutes(null)
                .newEndsAt(null)
                .ipAddress(ipAddress)
                .build());
    }

    private Bid insertProxyAuto(Auction auction, User bidder, long amount, Long proxyBidId) {
        return bidRepo.save(Bid.builder()
                .auction(auction)
                .bidder(bidder)
                .amount(amount)
                .bidType(BidType.PROXY_AUTO)
                .proxyBidId(proxyBidId)
                .snipeExtensionMinutes(null)
                .newEndsAt(null)
                // System-generated bid — no HTTP request context.
                .ipAddress(null)
                .build());
    }
}
