package com.slparcelauctions.backend.auction.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.slparcelauctions.backend.auction.CancellationLog;
import com.slparcelauctions.backend.auction.CancellationOffenseKind;

/**
 * Single row in the seller's paginated cancellation history (Epic 08 sub-spec 2
 * §7.4). All fields are read directly from the snapshotted
 * {@link CancellationLog} columns — no live recomputation against today's
 * ladder. {@link #penaltyApplied} is null when the log row's
 * {@code penaltyKind} is {@link CancellationOffenseKind#NONE} (pre-active or
 * active-without-bids cancellation, or a pre-sub-spec-2 row); the frontend
 * renders a "No penalty" badge in that case.
 */
public record CancellationHistoryDto(
        UUID auctionPublicId,
        String auctionTitle,
        String primaryPhotoUrl,
        String cancelledFromStatus,
        Boolean hadBids,
        String reason,
        OffsetDateTime cancelledAt,
        PenaltyApplied penaltyApplied) {

    public record PenaltyApplied(
            CancellationOffenseKind kind,
            Long amountL) {}

    /**
     * Builds the DTO from the persisted log row plus the resolved primary
     * photo URL. The log carries the snapshotted kind/amount; the resolver
     * upstream picks the auction's first-sort-order photo or falls back to
     * the parcel snapshot.
     */
    public static CancellationHistoryDto from(CancellationLog log, String primaryPhotoUrl) {
        PenaltyApplied applied = (log.getPenaltyKind() == null
                || log.getPenaltyKind() == CancellationOffenseKind.NONE)
                ? null
                : new PenaltyApplied(log.getPenaltyKind(), log.getPenaltyAmountL());
        return new CancellationHistoryDto(
                log.getAuction().getPublicId(),
                log.getAuction().getTitle(),
                primaryPhotoUrl,
                log.getCancelledFromStatus(),
                log.getHadBids(),
                log.getReason(),
                log.getCancelledAt(),
                applied);
    }
}
