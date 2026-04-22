package com.slparcelauctions.backend.auction;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.auction.exception.AuctionNotFoundException;
import com.slparcelauctions.backend.auction.exception.InvalidAuctionStateException;
import com.slparcelauctions.backend.bot.BotMonitorLifecycleService;
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
    private final CancellationLogRepository logRepo;
    private final ListingFeeRefundRepository refundRepo;
    private final UserRepository userRepo;
    private final BotMonitorLifecycleService monitorLifecycle;
    private final Clock clock;

    @Transactional
    public Auction cancel(Long auctionId, String reason) {
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

        // Cancellation log
        logRepo.save(CancellationLog.builder()
                .auction(a)
                .seller(a.getSeller())
                .cancelledFromStatus(from.name())
                .hadBids(hadBids)
                .reason(reason)
                .build());

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

        // cancelled_with_bids counter on ACTIVE cancellations with bids
        if (from == AuctionStatus.ACTIVE && hadBids) {
            User seller = a.getSeller();
            seller.setCancelledWithBids(seller.getCancelledWithBids() + 1);
            userRepo.save(seller);
        }

        a.setStatus(AuctionStatus.CANCELLED);
        Auction saved = auctionRepo.save(a);
        monitorLifecycle.onAuctionClosed(saved);
        log.info("Auction {} cancelled from {} (hadBids={})", a.getId(), from, hadBids);
        return saved;
    }
}
