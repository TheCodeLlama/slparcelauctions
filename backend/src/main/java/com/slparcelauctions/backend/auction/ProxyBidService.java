package com.slparcelauctions.backend.auction;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.slparcelauctions.backend.auction.broadcast.AuctionBroadcastPublisher;
import com.slparcelauctions.backend.auction.broadcast.AuctionEndedEnvelope;
import com.slparcelauctions.backend.auction.broadcast.BidSettlementEnvelope;
import com.slparcelauctions.backend.auction.dto.ProxyBidResponse;
import com.slparcelauctions.backend.auction.exception.AuctionAlreadyEndedException;
import com.slparcelauctions.backend.auction.exception.AuctionNotFoundException;
import com.slparcelauctions.backend.auction.exception.BidTooLowException;
import com.slparcelauctions.backend.auction.exception.CannotCancelWinningProxyException;
import com.slparcelauctions.backend.auction.exception.InvalidAuctionStateException;
import com.slparcelauctions.backend.auction.exception.InvalidProxyMaxException;
import com.slparcelauctions.backend.auction.exception.InvalidProxyStateException;
import com.slparcelauctions.backend.auction.exception.NotVerifiedException;
import com.slparcelauctions.backend.auction.exception.ProxyBidAlreadyExistsException;
import com.slparcelauctions.backend.auction.exception.ProxyBidNotFoundException;
import com.slparcelauctions.backend.auction.exception.SellerCannotBidException;
import com.slparcelauctions.backend.notification.NotificationPublisher;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserNotFoundException;
import com.slparcelauctions.backend.user.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * eBay-style proxy-bidding engine. Bidders submit a maximum amount; the
 * service maintains the current winning position on their behalf up to the
 * cap they specified, out-bidding competitors one increment at a time until
 * someone beats the cap.
 *
 * <p>Endpoints: {@code POST} (createProxy), {@code PUT} (updateProxyMax —
 * handles both ACTIVE-winning increase and EXHAUSTED resurrection),
 * {@code DELETE} (cancelProxy), and {@code GET} (any-status lookup for the
 * caller).
 *
 * <p><strong>Concurrency.</strong> Every write path acquires a pessimistic
 * write lock on the auction row via {@link AuctionRepository#findByIdForUpdate}
 * before any validation, matching {@link BidService}. Two concurrent proxy
 * operations on the same auction therefore serialise at the DB — the loser
 * reads the committed state after the winner's commit lands.
 *
 * <p><strong>Resolution pivot.</strong> {@link #resolveProxyResolution} is
 * shared by create and update paths and handles all four branches laid out
 * in spec §7: no competitor, new-proxy-max strictly greater, strictly less,
 * and equal (earliest {@code createdAt} wins). {@code PROXY_AUTO} rows are
 * always persisted with {@code ipAddress=null} because they were not
 * triggered by an HTTP request.
 *
 * <p><strong>Broadcast hygiene.</strong> Matches {@link BidService} — the
 * envelope is constructed inside the transaction (while entities are live)
 * and published from a {@code TransactionSynchronization.afterCommit}
 * callback. No envelope fires when the update was a silent cap-increase on a
 * currently-winning proxy; the cancel path also skips broadcast because
 * {@code currentBid} does not change.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProxyBidService {

    private final AuctionRepository auctionRepo;
    private final ProxyBidRepository proxyBidRepo;
    private final BidRepository bidRepo;
    private final UserRepository userRepo;
    private final Clock clock;
    private final AuctionBroadcastPublisher publisher;
    private final NotificationPublisher notificationPublisher;

    // -------------------------------------------------------------------------
    // createProxy — spec §7 "POST /proxy-bid"
    // -------------------------------------------------------------------------

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ProxyBidResponse createProxy(Long auctionId, Long bidderId, long maxAmount) {
        Auction auction = auctionRepo.findByIdForUpdate(auctionId)
                .orElseThrow(() -> new AuctionNotFoundException(auctionId));

        User bidder = validateBiddable(auction, bidderId);

        // Step 3 — one ACTIVE per (auction, user). DB partial unique index is
        // the ultimate enforcement; this pre-check surfaces a clean 409.
        if (proxyBidRepo.existsByAuctionIdAndBidderIdAndStatus(
                auctionId, bidderId, ProxyBidStatus.ACTIVE)) {
            throw new ProxyBidAlreadyExistsException();
        }

        // Step 4 — minimum-bid gate. Matches BidService: first bid must clear
        // startingBid, subsequent bids must clear currentBid + minIncrement.
        long minRequired = minRequiredForNextBid(auction);
        if (maxAmount < minRequired) {
            throw new BidTooLowException(minRequired);
        }

        // Step 5 — persist the new proxy row. @CreationTimestamp /
        // @UpdateTimestamp fire here; the resolution helper reads createdAt
        // for the equal-max tiebreak so the save must land first.
        ProxyBid proxy = proxyBidRepo.save(ProxyBid.builder()
                .auction(auction)
                .bidder(bidder)
                .maxAmount(maxAmount)
                .status(ProxyBidStatus.ACTIVE)
                .build());

        // Steps 6-8 — shared resolution + snipe/buy-now + auction aggregate.
        // Single-source-of-truth timestamp for the placement; threaded into
        // applySnipeAndBuyNow (stamps auction.endedAt on buy-it-now close)
        // so it matches whatever the envelope factory consumes on the
        // afterCommit path.
        final Long previousHighBidderIdCreate = auction.getCurrentBidderId();
        List<Bid> emitted = resolveProxyResolution(auction, proxy);
        OffsetDateTime now = OffsetDateTime.now(clock);
        BidPlacementHelpers.applySnipeAndBuyNow(auction, emitted, now, proxyBidRepo);
        updateAuctionTopBidder(auction, emitted);
        auctionRepo.save(auction);

        publishProxyNotifications(auction, proxy, previousHighBidderIdCreate);
        publishAfterCommit(auction, emitted);
        log.info("Proxy created: auctionId={}, bidderId={}, maxAmount={}, emittedBids={}",
                auctionId, bidderId, maxAmount, emitted.size());
        return ProxyBidResponse.from(proxy);
    }

    // -------------------------------------------------------------------------
    // updateProxyMax — spec §7 "PUT /proxy-bid"
    // -------------------------------------------------------------------------

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ProxyBidResponse updateProxyMax(Long auctionId, Long bidderId, long newMax) {
        Auction auction = auctionRepo.findByIdForUpdate(auctionId)
                .orElseThrow(() -> new AuctionNotFoundException(auctionId));

        validateBiddable(auction, bidderId);

        ProxyBid proxy = proxyBidRepo
                .findFirstByAuctionIdAndBidderIdOrderByCreatedAtDesc(auctionId, bidderId)
                .orElseThrow(ProxyBidNotFoundException::new);

        // CANCELLED is terminal — create a new proxy instead.
        if (proxy.getStatus() == ProxyBidStatus.CANCELLED) {
            throw new InvalidProxyStateException(
                    "Cancelled proxy cannot be updated; create a new one");
        }

        final Long previousHighBidderIdUpdate = auction.getCurrentBidderId();
        List<Bid> emitted;

        if (proxy.getStatus() == ProxyBidStatus.ACTIVE) {
            if (Objects.equals(bidderId, auction.getCurrentBidderId())) {
                // Winning owner raises their cap — silent update, no WS publish.
                if (newMax <= auction.getCurrentBid()) {
                    throw new InvalidProxyMaxException(
                            "Max must exceed current winning bid");
                }
                proxy.setMaxAmount(newMax);
                proxyBidRepo.save(proxy);
                log.info("Proxy max raised (winning silent): proxyId={}, newMax={}",
                        proxy.getId(), newMax);
                return ProxyBidResponse.from(proxy);
            }

            // ACTIVE proxy, but caller is not currently winning — unreachable under the
            // pessimistic lock. Any transaction that moved currentBidderId off this caller
            // would have flipped their proxy to EXHAUSTED in the same commit. If we hit
            // this branch, the lock discipline has broken — fail loudly rather than run
            // resolveProxyResolution on inconsistent state.
            throw new IllegalStateException(
                "Unreachable: ACTIVE proxy " + proxy.getId() + " owner " + bidderId
              + " is not the current winner of auction " + auction.getId()
              + " (currentBidderId=" + auction.getCurrentBidderId() + "). "
              + "This indicates a lock acquisition regression.");
        } else {
            // EXHAUSTED — resurrection path. Increase-only.
            if (newMax <= proxy.getMaxAmount()) {
                throw new InvalidProxyMaxException(
                        "Increase only on exhausted proxy");
            }
            long minRequired = minRequiredForNextBid(auction);
            if (newMax < minRequired) {
                throw new BidTooLowException(minRequired);
            }
            proxy.setStatus(ProxyBidStatus.ACTIVE);
            proxy.setMaxAmount(newMax);
            proxyBidRepo.save(proxy);
            emitted = resolveProxyResolution(auction, proxy);
        }

        // Single clock read threaded through the helper (see createProxy
        // for the rationale — keeps auction.endedAt / escrow deadline /
        // envelope serverTime pinned to one instant if buy-it-now fires).
        OffsetDateTime now = OffsetDateTime.now(clock);
        BidPlacementHelpers.applySnipeAndBuyNow(auction, emitted, now, proxyBidRepo);
        updateAuctionTopBidder(auction, emitted);
        auctionRepo.save(auction);

        if (!emitted.isEmpty()) {
            publishProxyNotifications(auction, proxy, previousHighBidderIdUpdate);
            publishAfterCommit(auction, emitted);
        }
        log.info("Proxy max updated: proxyId={}, newMax={}, emittedBids={}",
                proxy.getId(), newMax, emitted.size());
        return ProxyBidResponse.from(proxy);
    }

    // -------------------------------------------------------------------------
    // cancelProxy — spec §7 "DELETE /proxy-bid"
    // -------------------------------------------------------------------------

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void cancelProxy(Long auctionId, Long bidderId) {
        Auction lockedAuction = auctionRepo.findByIdForUpdate(auctionId)
                .orElseThrow(() -> new AuctionNotFoundException(auctionId));

        ProxyBid proxy = proxyBidRepo
                .findFirstByAuctionIdAndBidderIdOrderByCreatedAtDesc(auctionId, bidderId)
                .filter(p -> p.getStatus() == ProxyBidStatus.ACTIVE)
                .orElseThrow(ProxyBidNotFoundException::new);

        // Read currentBidderId from the locked handle directly so the
        // lock-to-read dependency is explicit in the code — a future refactor
        // that moves the lock acquisition can't silently let this fall back to
        // a lazy-loaded proxy.getAuction() traversal.
        if (Objects.equals(bidderId, lockedAuction.getCurrentBidderId())) {
            throw new CannotCancelWinningProxyException();
        }

        proxy.setStatus(ProxyBidStatus.CANCELLED);
        proxyBidRepo.save(proxy);
        log.info("Proxy cancelled: proxyId={}, auctionId={}, bidderId={}",
                proxy.getId(), auctionId, bidderId);
        // No bid rows emitted, no WS publish — currentBid unchanged.
    }

    // -------------------------------------------------------------------------
    // getMyProxy — spec §7 "GET /proxy-bid"
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Optional<ProxyBidResponse> getMyProxy(Long auctionId, Long bidderId) {
        return proxyBidRepo
                .findFirstByAuctionIdAndBidderIdOrderByCreatedAtDesc(auctionId, bidderId)
                .map(ProxyBidResponse::from);
    }

    // -------------------------------------------------------------------------
    // Shared resolution helper — spec §7 resolveProxyResolution
    // -------------------------------------------------------------------------

    /**
     * Settles the new/resurrected proxy against any existing ACTIVE proxy on
     * the auction. Emits 0, 1, or 2 {@link Bid} rows in {@code PROXY_AUTO}
     * type, all with {@code ipAddress=null}. Mutates the loser's
     * {@link ProxyBid#setStatus status} to {@code EXHAUSTED} when a loser is
     * produced. See spec §7 for the branch table.
     */
    List<Bid> resolveProxyResolution(Auction auction, ProxyBid proxy) {
        Optional<ProxyBid> existingOpt = proxyBidRepo
                .findFirstByAuctionIdAndStatusAndBidderIdNot(
                        auction.getId(), ProxyBidStatus.ACTIVE, proxy.getBidder().getId());

        List<Bid> emitted = new ArrayList<>(2);

        if (existingOpt.isEmpty()) {
            // Branch 1 — no competitor. Open at currentBid+increment or
            // startingBid, whichever is higher.
            long currentBid = auction.getCurrentBid() == null ? 0L : auction.getCurrentBid();
            long floor = currentBid > 0L
                    ? currentBid + BidIncrementTable.minIncrement(currentBid)
                    : auction.getStartingBid();
            long openingAmount = Math.max(floor, auction.getStartingBid());
            if (openingAmount > proxy.getMaxAmount()) {
                // Defensive — caller pre-validates in step 4 of create, but if
                // startingBid was raised between the validation and this call
                // under an exotic lock-release scenario we'd still want a clean
                // 400 rather than an insertion of a below-floor PROXY_AUTO row.
                throw new BidTooLowException(openingAmount);
            }
            emitted.add(insertProxyAuto(auction, proxy.getBidder(), openingAmount, proxy.getId()));
            return emitted;
        }

        ProxyBid existing = existingOpt.get();

        if (proxy.getMaxAmount() > existing.getMaxAmount()) {
            // Branch 2 — new proxy wins. Counter at existing.max + increment,
            // capped at new proxy's max so we never bid above our own cap.
            long settleAmount = Math.min(
                    existing.getMaxAmount() + BidIncrementTable.minIncrement(existing.getMaxAmount()),
                    proxy.getMaxAmount());
            existing.setStatus(ProxyBidStatus.EXHAUSTED);
            proxyBidRepo.save(existing);
            emitted.add(insertProxyAuto(auction, proxy.getBidder(), settleAmount, proxy.getId()));
        } else if (proxy.getMaxAmount() < existing.getMaxAmount()) {
            // Branch 3 — existing wins. Emit flush at new-proxy's max + counter
            // at min(new.max + increment, existing.max). New proxy flips to
            // EXHAUSTED immediately.
            long settleAmount = Math.min(
                    proxy.getMaxAmount() + BidIncrementTable.minIncrement(proxy.getMaxAmount()),
                    existing.getMaxAmount());
            proxy.setStatus(ProxyBidStatus.EXHAUSTED);
            proxyBidRepo.save(proxy);
            emitted.add(insertProxyAuto(auction, proxy.getBidder(), proxy.getMaxAmount(), proxy.getId()));
            emitted.add(insertProxyAuto(auction, existing.getBidder(), settleAmount, existing.getId()));
        } else {
            // Branch 4 — equal max. Earliest createdAt wins (tie-break pinned
            // by test). isBefore is strict, so two rows with identical
            // timestamps fall into the else branch (existing wins as the
            // incumbent; arbitrary but deterministic).
            if (existing.getCreatedAt().isBefore(proxy.getCreatedAt())) {
                // Existing is earlier — existing wins. New proxy gets a flush
                // row at its own max, existing keeps ACTIVE at its max.
                proxy.setStatus(ProxyBidStatus.EXHAUSTED);
                proxyBidRepo.save(proxy);
                emitted.add(insertProxyAuto(auction, proxy.getBidder(), proxy.getMaxAmount(), proxy.getId()));
                emitted.add(insertProxyAuto(auction, existing.getBidder(), existing.getMaxAmount(), existing.getId()));
            } else {
                // Proxy is the earlier row (can happen on resurrection of a
                // pre-existing EXHAUSTED row whose createdAt predates the
                // existing ACTIVE row). Proxy wins; existing flips to EXHAUSTED.
                existing.setStatus(ProxyBidStatus.EXHAUSTED);
                proxyBidRepo.save(existing);
                emitted.add(insertProxyAuto(auction, proxy.getBidder(), proxy.getMaxAmount(), proxy.getId()));
            }
        }
        return emitted;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

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

    private long minRequiredForNextBid(Auction auction) {
        long currentBid = auction.getCurrentBid() == null ? 0L : auction.getCurrentBid();
        return currentBid > 0L
                ? currentBid + BidIncrementTable.minIncrement(currentBid)
                : auction.getStartingBid();
    }

    /**
     * Asserts the auction is biddable and the caller is allowed to bid. Returns
     * the resolved {@link User} so callers avoid a second userRepo lookup.
     */
    private User validateBiddable(Auction auction, Long bidderId) {
        if (auction.getStatus() != AuctionStatus.ACTIVE) {
            throw new InvalidAuctionStateException(auction.getId(), auction.getStatus(), "PROXY_BID");
        }
        if (auction.getEndsAt() == null || !auction.getEndsAt().isAfter(OffsetDateTime.now(clock))) {
            throw new AuctionAlreadyEndedException(auction.getEndsAt());
        }
        User bidder = userRepo.findById(bidderId)
                .orElseThrow(() -> new UserNotFoundException(bidderId));
        if (!Boolean.TRUE.equals(bidder.getVerified())) {
            throw new NotVerifiedException();
        }
        if (bidder.getId().equals(auction.getSeller().getId())) {
            throw new SellerCannotBidException();
        }
        return bidder;
    }

    private void updateAuctionTopBidder(Auction auction, List<Bid> emitted) {
        if (emitted.isEmpty()) {
            return;
        }
        Bid top = emitted.getLast();
        auction.setCurrentBid(top.getAmount());
        auction.setCurrentBidderId(top.getBidder().getId());
        int nextBidCount = (auction.getBidCount() == null ? 0 : auction.getBidCount()) + emitted.size();
        auction.setBidCount(nextBidCount);
    }

    /**
     * Emits in-app OUTBID and PROXY_EXHAUSTED notifications for a just-resolved
     * proxy operation. Called inside the @Transactional boundary after
     * resolveProxyResolution + updateAuctionTopBidder so we know the final winner.
     *
     * <ul>
     *   <li>OUTBID — fires when the previous high bidder was displaced by the new
     *       top. isProxyOutbid=true because this path is a proxy resolution.</li>
     *   <li>PROXY_EXHAUSTED — fires when the {@code proxy} itself ended up EXHAUSTED
     *       (it lost the resolution battle, e.g. Branch 3/4a). Separate from OUTBID
     *       because the proxy owner may have been the newcomer, not the prior high.</li>
     * </ul>
     */
    private void publishProxyNotifications(Auction auction, ProxyBid proxy,
                                           Long previousHighBidderId) {
        Long newTopBidderId = auction.getCurrentBidderId();
        // The previous high bidder was displaced if they exist and are no longer on top.
        if (previousHighBidderId != null && !previousHighBidderId.equals(newTopBidderId)) {
            notificationPublisher.outbid(
                    previousHighBidderId,
                    auction.getId(),
                    auction.getTitle(),
                    auction.getCurrentBid(),
                    true,   // isProxyOutbid — this displacement happened via proxy resolution
                    auction.getEndsAt()
            );
        }
        // If the new proxy itself got exhausted immediately (it lost the battle),
        // fire PROXY_EXHAUSTED for the proxy's owner.
        if (proxy.getStatus() == ProxyBidStatus.EXHAUSTED) {
            notificationPublisher.proxyExhausted(
                    proxy.getBidder().getId(),
                    auction.getId(),
                    auction.getTitle(),
                    proxy.getMaxAmount(),
                    auction.getEndsAt()
            );
        }
    }

    /**
     * Registers a post-commit WS publish. Matches {@link BidService} — the
     * envelope is built now (inside the transaction, with entities live) and
     * the publish call runs after commit so subscribers never observe an
     * uncommitted state. Caller must ensure {@code emitted} is non-empty;
     * silent paths (winning cap-raise, cancel) should skip calling this.
     */
    private void publishAfterCommit(Auction auction, List<Bid> emitted) {
        final boolean ended = auction.getStatus() == AuctionStatus.ENDED;
        final User topBidder = emitted.isEmpty() ? null : emitted.getLast().getBidder();
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
    }
}
