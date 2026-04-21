package com.slparcelauctions.backend.auction.dto;

import java.time.OffsetDateTime;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.Bid;
import com.slparcelauctions.backend.auction.BidType;

/**
 * Response body for {@code POST /api/v1/auctions/{id}/bids}. Carries the
 * just-emitted bid plus the auction-level fields the caller needs to render
 * the post-commit state without another {@code GET} — current top amount,
 * the possibly-extended {@code endsAt}, the bid counter, and (Task 3+)
 * snipe / buy-it-now outcome flags. Task 2 always returns
 * {@code buyNowTriggered = false} and {@code snipeExtensionMinutes = null}
 * because the inline close and snipe paths aren't wired yet.
 *
 * <p>The {@code from} factory pulls the snipe fields off the {@link Bid}
 * row rather than the auction because the per-row evaluation in Task 3
 * stamps {@code snipeExtensionMinutes}/{@code newEndsAt} on the bid row
 * that triggered the extension. That keeps the response stable as more
 * bid-emission branches land in later tasks.
 */
public record BidResponse(
        Long bidId,
        Long auctionId,
        Long amount,
        BidType bidType,
        Integer bidCount,
        OffsetDateTime endsAt,
        OffsetDateTime originalEndsAt,
        Integer snipeExtensionMinutes,
        OffsetDateTime newEndsAt,
        boolean buyNowTriggered) {

    public static BidResponse from(Bid bid, Auction auction, boolean buyNowTriggered) {
        return new BidResponse(
                bid.getId(),
                auction.getId(),
                bid.getAmount(),
                bid.getBidType(),
                auction.getBidCount(),
                auction.getEndsAt(),
                auction.getOriginalEndsAt(),
                bid.getSnipeExtensionMinutes(),
                bid.getNewEndsAt(),
                buyNowTriggered);
    }
}
