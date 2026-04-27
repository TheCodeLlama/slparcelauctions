package com.slparcelauctions.backend.auction;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.slparcelauctions.backend.admin.ban.BanCheckService;
import com.slparcelauctions.backend.auction.broadcast.AuctionBroadcastPublisher;
import com.slparcelauctions.backend.auction.dto.AuctionCancelledEnvelope;
import com.slparcelauctions.backend.auction.exception.AuctionNotFoundException;
import com.slparcelauctions.backend.auction.exception.InvalidAuctionStateException;
import com.slparcelauctions.backend.bot.BotMonitorLifecycleService;
import com.slparcelauctions.backend.notification.NotificationPublisher;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Handles /cancel transitions. Cancellation is allowed from pre-live and live
 * states; disallowed from ENDED+ (transfer in progress) and terminal states.
 * Refund records are created for any state where listingFeePaid=true. Bid counter
 * increments only when cancelling an ACTIVE auction with bids.
 *
 * <p><strong>Concurrency model.</strong> The method accepts an id rather than a
 * pre-loaded entity and re-fetches under {@link AuctionRepository#findByIdForUpdate}
 * so that a cancellation racing against {@code BidService.placeBid} or
 * {@code AuctionEndTask.closeOne} serialises at the database row lock. The
 * loser sees whichever status the winner committed (ACTIVE → CANCELLED or
 * ACTIVE → ENDED) and surfaces a {@link InvalidAuctionStateException}. See
 * {@code BidCancelRaceTest} for the pin.
 *
 * <p><strong>Penalty ladder (Epic 08 sub-spec 2).</strong> When an
 * {@code ACTIVE} auction with bids is cancelled, the seller's prior
 * cancelled-with-bids count drives a four-step ladder
 * (see {@link CancellationOffenseKind}). The seller row is pessimistically
 * locked BEFORE the count read so two concurrent cancellations on the same
 * seller serialise and observe distinct ladder indices. The selected
 * {@link CancellationOffenseKind} and L$ amount are snapshotted onto the
 * {@link CancellationLog} row as immutable historical fact.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CancellationService {

    private static final Set<AuctionStatus> CANCELLABLE = Set.of(
            AuctionStatus.DRAFT,
            AuctionStatus.DRAFT_PAID,
            AuctionStatus.VERIFICATION_PENDING,
            AuctionStatus.VERIFICATION_FAILED,
            AuctionStatus.ACTIVE);

    private final AuctionRepository auctionRepo;
    private final BidRepository bidRepo;
    private final CancellationLogRepository logRepo;
    private final ListingFeeRefundRepository refundRepo;
    private final UserRepository userRepo;
    private final BotMonitorLifecycleService monitorLifecycle;
    private final AuctionBroadcastPublisher broadcastPublisher;
    private final NotificationPublisher notificationPublisher;
    private final CancellationPenaltyProperties penaltyProps;
    private final BanCheckService banCheckService;
    private final Clock clock;

    @Transactional
    public Auction cancel(Long auctionId, String reason, String ipAddress) {
        Auction a = auctionRepo.findByIdForUpdate(auctionId)
                .orElseThrow(() -> new AuctionNotFoundException(auctionId));
        if (!CANCELLABLE.contains(a.getStatus())) {
            throw new InvalidAuctionStateException(a.getId(), a.getStatus(), "CANCEL");
        }
        if (a.getStatus() == AuctionStatus.ACTIVE) {
            if (a.getEndsAt() != null && OffsetDateTime.now(clock).isAfter(a.getEndsAt())) {
                throw new InvalidAuctionStateException(a.getId(), a.getStatus(), "CANCEL_AFTER_END");
            }
        }

        AuctionStatus from = a.getStatus();
        boolean hadBids = a.getBidCount() != null && a.getBidCount() > 0;
        boolean activeWithBids = from == AuctionStatus.ACTIVE && hadBids;

        // Pessimistic lock on the seller row — must precede the COUNT so two
        // concurrent cancellations on the same seller serialise here and
        // observe distinct prior-offense counts. Pre-active and active-without-
        // bids cancellations also acquire the lock to keep the path uniform;
        // the lock is cheap and avoids branching that would matter only on
        // the cold path.
        User seller = userRepo.findByIdForUpdate(a.getSeller().getId())
                .orElseThrow(); // FK guarantees existence

        banCheckService.assertNotBanned(ipAddress, seller.getSlAvatarUuid());

        // Pre-INSERT count → ladder index → consequence snapshot. Indices are
        // clamped at 3 so the 4th-and-beyond offenses all snapshot
        // PERMANENT_BAN. This call MUST run before saving the new log row
        // (off-by-one trap).
        CancellationOffenseKind kind;
        Long amountL;
        if (activeWithBids) {
            long prior = logRepo.countPriorOffensesWithBids(seller.getId());
            int index = (int) Math.min(prior, 3L);
            switch (index) {
                case 0 -> {
                    kind = CancellationOffenseKind.WARNING;
                    amountL = null;
                }
                case 1 -> {
                    kind = CancellationOffenseKind.PENALTY;
                    amountL = penaltyProps.penalty().secondOffenseL();
                }
                case 2 -> {
                    kind = CancellationOffenseKind.PENALTY_AND_30D;
                    amountL = penaltyProps.penalty().thirdOffenseL();
                }
                default -> {
                    kind = CancellationOffenseKind.PERMANENT_BAN;
                    amountL = null;
                }
            }
        } else {
            kind = CancellationOffenseKind.NONE;
            amountL = null;
        }

        // Snapshot the consequence onto the log row — immutable historical
        // fact, never recomputed from live state.
        logRepo.save(CancellationLog.builder()
                .auction(a)
                .seller(seller)
                .cancelledFromStatus(from.name())
                .hadBids(hadBids)
                .reason(reason)
                .penaltyKind(kind)
                .penaltyAmountL(amountL)
                .build());

        // Apply consequence + arm the post-cancel ownership-watcher window.
        if (activeWithBids) {
            seller.setCancelledWithBids(seller.getCancelledWithBids() + 1);
            OffsetDateTime now = OffsetDateTime.now(clock);
            switch (kind) {
                case PENALTY -> seller.setPenaltyBalanceOwed(
                        seller.getPenaltyBalanceOwed() + amountL);
                case PENALTY_AND_30D -> {
                    seller.setPenaltyBalanceOwed(
                            seller.getPenaltyBalanceOwed() + amountL);
                    seller.setListingSuspensionUntil(
                            now.plusDays(penaltyProps.penalty().thirdOffenseSuspensionDays()));
                }
                case PERMANENT_BAN -> seller.setBannedFromListing(true);
                default -> {
                    // WARNING / NONE — log only, no consequence.
                }
            }
            userRepo.save(seller);
            a.setPostCancelWatchUntil(now.plusHours(penaltyProps.postCancelWatchHours()));
        }

        // Refund record if fee was paid and this is a pre-live cancellation
        if (Boolean.TRUE.equals(a.getListingFeePaid())
                && from != AuctionStatus.ACTIVE) {
            refundRepo.save(ListingFeeRefund.builder()
                    .auction(a)
                    .amount(a.getListingFeeAmt() == null ? 0L : a.getListingFeeAmt())
                    .status(RefundStatus.PENDING)
                    .build());
            log.info("Listing fee refund (PENDING) created for auction {}", a.getId());
        }

        a.setStatus(AuctionStatus.CANCELLED);
        Auction saved = auctionRepo.save(a);
        monitorLifecycle.onAuctionClosed(saved);

        // Fan-out LISTING_CANCELLED_BY_SELLER to each bidder who ever bid on
        // this auction. The publisher registers an afterCommit hook internally,
        // so bidder notifications fire only when the cancellation commits.
        // An empty list is a no-op; a stale bidderId causes a contained warning
        // (no FK-violation aborts the remaining recipients).
        List<Long> allBidderIds = bidRepo.findDistinctBidderUserIdsByAuctionId(a.getId());
        notificationPublisher.listingCancelledBySellerFanout(
                a.getId(), allBidderIds, a.getTitle(), reason);

        log.info("Auction {} cancelled from {} (hadBids={}, kind={})",
                a.getId(), from, hadBids, kind);

        // Register the WS broadcast for afterCommit only — never inside the
        // tx. Subscribers must never observe a cancellation that rolls back
        // on a late DB failure. Mirrors the pattern used by ReviewService.
        AuctionCancelledEnvelope envelope = AuctionCancelledEnvelope.of(
                saved, hadBids, OffsetDateTime.now(clock));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            broadcastPublisher.publishCancelled(envelope);
                        }
                    });
        } else {
            // Slice tests / non-tx callers — fire immediately.
            broadcastPublisher.publishCancelled(envelope);
        }

        return saved;
    }

    /**
     * Admin-initiated cancellation. Skips the penalty ladder entirely —
     * staff removal is not a seller offense. The {@link CancellationLog} row
     * is written with {@code cancelledByAdminId} set so that
     * {@code countPriorOffensesWithBids} (which filters {@code IS NULL}) does
     * not count it against the seller's ladder.
     *
     * <p>The seller receives a distinct {@code LISTING_REMOVED_BY_ADMIN}
     * notification. Bidders receive the existing
     * {@code listingCancelledBySellerFanout} (whose body copy is cause-neutral
     * as of sub-spec 2 Task 3). {@code seller.cancelledWithBids} is NOT
     * incremented.
     */
    @Transactional
    public Auction cancelByAdmin(Long auctionId, Long adminUserId, String notes) {
        Auction a = auctionRepo.findByIdForUpdate(auctionId)
                .orElseThrow(() -> new AuctionNotFoundException(auctionId));
        if (!CANCELLABLE.contains(a.getStatus())) {
            throw new InvalidAuctionStateException(a.getId(), a.getStatus(), "ADMIN_CANCEL");
        }

        boolean hadBids = a.getBidCount() != null && a.getBidCount() > 0;
        AuctionStatus from = a.getStatus();

        // No penalty ladder. No seller-row lock — no seller-side state changes.
        logRepo.save(CancellationLog.builder()
                .auction(a)
                .seller(a.getSeller())
                .cancelledFromStatus(from.name())
                .hadBids(hadBids)
                .reason(notes)
                .penaltyKind(CancellationOffenseKind.NONE)
                .penaltyAmountL(null)
                .cancelledByAdminId(adminUserId)
                .build());

        a.setStatus(AuctionStatus.CANCELLED);
        Auction saved = auctionRepo.save(a);
        monitorLifecycle.onAuctionClosed(saved);

        notificationPublisher.listingRemovedByAdmin(
                a.getSeller().getId(), a.getId(), a.getTitle(), notes);

        if (hadBids) {
            List<Long> bidderIds = bidRepo.findDistinctBidderUserIdsByAuctionId(a.getId());
            notificationPublisher.listingCancelledBySellerFanout(
                    a.getId(), bidderIds, a.getTitle(), notes);
        }

        log.info("Auction {} admin-cancelled from {} by adminUserId={} (hadBids={})",
                a.getId(), from, adminUserId, hadBids);

        AuctionCancelledEnvelope envelope = AuctionCancelledEnvelope.of(
                saved, hadBids, OffsetDateTime.now(clock));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            broadcastPublisher.publishCancelled(envelope);
                        }
                    });
        } else {
            broadcastPublisher.publishCancelled(envelope);
        }

        return saved;
    }
}
