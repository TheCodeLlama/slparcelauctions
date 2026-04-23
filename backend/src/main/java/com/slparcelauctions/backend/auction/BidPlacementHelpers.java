package com.slparcelauctions.backend.auction;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Static helpers shared between {@link BidService} (manual bid path) and
 * {@code ProxyBidService} (proxy create / update / resurrection paths).
 *
 * <p>Both services emit a list of {@link Bid} rows inside the same transaction
 * and then need to evaluate snipe-protection stacking and inline buy-it-now
 * against every emitted row in order. Keeping that logic in a stateless
 * helper (rather than cross-service method calls or a shared base class)
 * avoids a cycle between the two services' Spring beans and keeps each
 * service's transactional boundary single-owner.
 *
 * <p><strong>Hydration contract.</strong> {@code applySnipeAndBuyNow} mutates
 * the {@link Auction} aggregate in-place (endsAt, status, endOutcome,
 * winnerUserId, finalBidAmount, endedAt) and may stamp snipe extension
 * fields on individual {@link Bid} rows via their dirty-checked JPA
 * entity state. Callers are responsible for calling
 * {@code auctionRepo.save(auction)} afterwards; the {@link Bid} rows are
 * already managed by JPA so their mutations flush on commit.
 */
public final class BidPlacementHelpers {

    private BidPlacementHelpers() {
        // no instances
    }

    /**
     * Evaluates snipe-protection extension and inline buy-it-now against the
     * just-emitted bid rows. See {@link BidService}'s class-level javadoc
     * (pre-extraction) for the per-row stacking rationale and the "last
     * emitted is the current top" convention used by the buy-it-now winner
     * resolution. Behaviour is identical to the original
     * {@code BidService.applySnipeAndBuyNow} method.
     *
     * <p>The {@code now} argument MUST be the same instant the caller uses as
     * its top-level "this placement" timestamp — specifically, the same value
     * passed to {@code EscrowService.createForEndedAuction(auction, now)} and
     * to {@code AuctionEndedEnvelope.of(auction, winner, now)}. Threading a
     * single read through all three anchors keeps the persisted
     * {@code auction.endedAt}, the escrow's {@code paymentDeadline} (endedAt
     * + 48h), and the broadcast envelope's {@code serverTime} byte-identical
     * — otherwise three independent {@code OffsetDateTime.now(clock)} reads
     * separated by microseconds drift under {@code Clock.systemUTC()} and
     * break cross-channel event ordering.
     *
     * @param auction      the in-memory auction aggregate; mutated in-place
     * @param emitted      ordered list of bid rows emitted by the caller
     *                     (manual-first, proxy-counter-last for manual paths;
     *                     proxy-resolution-order for proxy paths)
     * @param now          the placement's single-source-of-truth timestamp;
     *                     stamped onto {@code auction.endedAt} when inline
     *                     buy-it-now fires
     * @param proxyBidRepo repository used to exhaust every remaining ACTIVE
     *                     proxy on the auction when buy-it-now fires
     */
    public static void applySnipeAndBuyNow(
            Auction auction,
            List<Bid> emitted,
            OffsetDateTime now,
            ProxyBidRepository proxyBidRepo) {
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
        // post-resolution top), not the first-to-hit.
        if (auction.getBuyNowPrice() != null) {
            for (Bid bid : emitted) {
                if (bid.getAmount() >= auction.getBuyNowPrice()) {
                    Bid top = emitted.getLast();
                    auction.setStatus(AuctionStatus.ENDED);
                    auction.setEndOutcome(AuctionEndOutcome.BOUGHT_NOW);
                    auction.setWinnerUserId(top.getBidder().getId());
                    auction.setFinalBidAmount(top.getAmount());
                    auction.setEndedAt(now);
                    proxyBidRepo.exhaustAllActiveByAuctionId(auction.getId());
                    return;
                }
            }
        }
    }
}
