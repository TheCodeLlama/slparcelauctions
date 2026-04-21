package com.slparcelauctions.backend.auction.broadcast;

import java.time.OffsetDateTime;

import com.slparcelauctions.backend.auction.AuctionEndOutcome;

/**
 * WebSocket payload broadcast to {@code /topic/auction/{auctionId}} when the
 * auction transitions to {@code ENDED} — either via the scheduler close path
 * or inline via buy-it-now. Task 2 introduces the record as a stub; the real
 * publisher + factory land in Tasks 3/5/6 where the producing paths are
 * implemented. The shape is pinned here so Task 2's stub publisher interface
 * can compile against the full API surface.
 */
public record AuctionEndedEnvelope(
        String type,
        Long auctionId,
        OffsetDateTime serverTime,
        OffsetDateTime endsAt,
        AuctionEndOutcome endOutcome,
        Long finalBid,
        Long winnerUserId,
        String winnerDisplayName,
        Integer bidCount) {
}
