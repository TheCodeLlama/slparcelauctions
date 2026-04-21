package com.slparcelauctions.backend.auction;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

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
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserNotFoundException;
import com.slparcelauctions.backend.user.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Bid-placement service. Task 2 implemented the core path; Task 3 layers in
 * snipe-protection extension and inline buy-it-now via
 * {@link #applySnipeAndBuyNow(Auction, List)}. Task 4 will add the proxy
 * counter-bid branch; the applySnipeAndBuyNow loop is already per-emitted-
 * row so the second emitted bid in a proxy settlement is evaluated against
 * the already-extended {@code endsAt} without any further changes here.
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

        // Step 6 — emit one MANUAL bid. Task 4 will fan this into a 1-or-2
        // row emission when a competing proxy is active; applySnipeAndBuyNow
        // already iterates the emitted list, so no further changes are
        // needed here for that extension.
        List<Bid> emitted = new ArrayList<>(2);
        Bid manual = Bid.builder()
                .auction(auction)
                .bidder(bidder)
                .amount(amount)
                .bidType(BidType.MANUAL)
                .proxyBidId(null)
                .snipeExtensionMinutes(null)
                .newEndsAt(null)
                .ipAddress(ipAddress)
                .build();
        manual = bidRepo.save(manual);
        emitted.add(manual);

        // Step 7 — snipe extension + inline buy-it-now. Both evaluate every
        // emitted bid in order. Snipe may stamp newEndsAt on each qualifying
        // bid and push auction.endsAt forward. Buy-it-now may flip the
        // auction to ENDED with endOutcome=BOUGHT_NOW; when that happens
        // every ACTIVE proxy on the auction is marked EXHAUSTED so the
        // partial unique index is cleared and no zombie proxies linger.
        applySnipeAndBuyNow(auction, emitted);

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

        // Step 9 — publish the envelope on afterCommit so subscribers never
        // observe an uncommitted state. The envelope variant is chosen NOW
        // (inside the tx, with entities initialised) and captured into the
        // synchronization; the publish call runs post-commit, outside the
        // persistence context. Buy-it-now closes emit AuctionEndedEnvelope;
        // everything else emits BidSettlementEnvelope.
        final boolean ended = auction.getStatus() == AuctionStatus.ENDED;
        final BidSettlementEnvelope settlement = ended
                ? null
                : BidSettlementEnvelope.of(auction, emitted, topBidder, clock);
        final AuctionEndedEnvelope endedEnv = ended
                ? AuctionEndedEnvelope.of(auction, topBidder, clock)
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

    /**
     * Evaluates snipe-protection extension and inline buy-it-now against the
     * just-emitted bid rows. Runs inside the bid placement transaction,
     * BEFORE the auction row is saved — any mutations to {@code auction}
     * (endsAt, status, endOutcome, winnerUserId, finalBidAmount, endedAt) or
     * to individual {@link Bid} rows (snipeExtensionMinutes, newEndsAt) are
     * persisted by the subsequent {@code auctionRepo.save} / JPA dirty
     * checking on the already-saved bid rows.
     *
     * <p><strong>Snipe loop.</strong> For each emitted bid in order, if
     * {@code Duration.between(bid.createdAt, auction.endsAt) <= window} the
     * auction's {@code endsAt} is pushed to {@code bid.createdAt + window}
     * and the bid row is stamped with the extension details. A negative
     * duration (bid AFTER endsAt) is skipped defensively — step 3 of
     * {@link #placeBid} already rejects post-deadline bids so this shouldn't
     * trigger in production. Each bid evaluates against the endsAt as it
     * stands at that point in the loop, so extensions stack across multiple
     * in-window bids (important for Task 4's proxy counter case).
     *
     * <p><strong>Buy-it-now loop.</strong> If any emitted bid meets or
     * exceeds {@code buyNowPrice} the auction is flipped to {@code ENDED}
     * with {@code endOutcome=BOUGHT_NOW}. The winner is the last emitted
     * bid (the current top after proxy resolution), not the first to hit
     * buy-now — this matters in Task 4 where a proxy counter could be the
     * bid that actually crosses the threshold. Remaining ACTIVE proxies on
     * the auction are exhausted so the partial unique index is cleared.
     */
    private void applySnipeAndBuyNow(Auction auction, List<Bid> emitted) {
        // Snipe protection — per-emitted-row extension that stacks.
        if (Boolean.TRUE.equals(auction.getSnipeProtect()) && auction.getSnipeWindowMin() != null) {
            Duration window = Duration.ofMinutes(auction.getSnipeWindowMin());
            for (Bid bid : emitted) {
                Duration remaining = Duration.between(bid.getCreatedAt(), auction.getEndsAt());
                // Negative remaining: bid timestamp is AFTER endsAt.
                // Defensive skip — step 3 of placeBid already rejects this.
                if (remaining.isNegative()) {
                    continue;
                }
                if (remaining.compareTo(window) <= 0) {
                    OffsetDateTime newEnd = bid.getCreatedAt().plus(window);
                    bid.setSnipeExtensionMinutes(auction.getSnipeWindowMin());
                    bid.setNewEndsAt(newEnd);
                    auction.setEndsAt(newEnd);
                }
            }
        }

        // Inline buy-it-now — first bid that meets or exceeds buyNowPrice
        // closes the auction. Winner is always the LAST emitted (the
        // post-resolution top), not the first-to-hit — see javadoc.
        if (auction.getBuyNowPrice() != null) {
            for (Bid bid : emitted) {
                if (bid.getAmount() >= auction.getBuyNowPrice()) {
                    Bid top = emitted.getLast();
                    auction.setStatus(AuctionStatus.ENDED);
                    auction.setEndOutcome(AuctionEndOutcome.BOUGHT_NOW);
                    auction.setWinnerUserId(top.getBidder().getId());
                    auction.setFinalBidAmount(top.getAmount());
                    auction.setEndedAt(OffsetDateTime.now(clock));
                    proxyBidRepo.exhaustAllActiveByAuctionId(auction.getId());
                    return;
                }
            }
        }
    }
}
