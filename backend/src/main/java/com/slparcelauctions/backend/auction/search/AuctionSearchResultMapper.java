package com.slparcelauctions.backend.auction.search;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.parceltag.ParcelTag;
import com.slparcelauctions.backend.user.User;

/**
 * Merges a paginated {@link Auction} result with the per-row tag and photo
 * batches (and an optional distance map) into {@link AuctionSearchResultDto}
 * cards for the browse / search response. See Epic 07 sub-spec 1 §5.1.1.
 *
 * <p>Two server-side decisions live here, by design:
 * <ul>
 *   <li>{@code reserveMet} = reservePrice IS NULL OR currentBid &gt;= reservePrice.
 *       The frontend gets one authoritative flag, never recomputes it.</li>
 *   <li>{@code primaryPhotoUrl} = first seller-uploaded photo URL when present,
 *       else {@code parcel.snapshotUrl}. The fallback is server-side so
 *       browsers don't double-fetch when no seller photos exist.</li>
 * </ul>
 *
 * <p>The {@link Parcel} entity has no separate "name" column; the SL parcel
 * name is often low-signal ("Object", "Gov Linden's 1024"), so the DTO's
 * {@code name} slot reuses {@code regionName} for now. If a richer parcel
 * name source appears later, swap that one expression.
 */
@Component
public class AuctionSearchResultMapper {

    public List<AuctionSearchResultDto> toDtos(
            Collection<Auction> page,
            Map<Long, Set<ParcelTag>> tagsByAuctionId,
            Map<Long, String> photoUrlsByAuctionId,
            Map<Long, BigDecimal> distancesByAuctionId) {

        List<AuctionSearchResultDto> dtos = new ArrayList<>(page.size());
        for (Auction a : page) {
            Set<ParcelTag> tags = tagsByAuctionId == null
                    ? Set.of()
                    : tagsByAuctionId.getOrDefault(a.getId(), Set.of());
            String photoUrl = photoUrlsByAuctionId == null
                    ? null
                    : photoUrlsByAuctionId.get(a.getId());
            BigDecimal distance = distancesByAuctionId == null
                    ? null
                    : distancesByAuctionId.get(a.getId());
            dtos.add(toDto(a, tags, photoUrl, distance));
        }
        return dtos;
    }

    public AuctionSearchResultDto toDto(
            Auction a,
            Set<ParcelTag> tags,
            String primaryPhotoUrl,
            BigDecimal distance) {

        Parcel p = a.getParcel();
        User s = a.getSeller();

        boolean reserveMet = a.getReservePrice() == null
                || (a.getCurrentBid() != null
                        && a.getCurrentBid() >= a.getReservePrice());

        String photoUrl = primaryPhotoUrl != null
                ? primaryPhotoUrl
                : (p == null ? null : p.getSnapshotUrl());

        ParcelSummaryDto parcelDto = p == null ? null : new ParcelSummaryDto(
                p.getId(),
                // No separate parcel "name" column on the entity; reuse regionName.
                p.getRegionName(),
                p.getRegionName(),
                p.getAreaSqm(),
                p.getMaturityRating(),
                p.getSnapshotUrl(),
                p.getGridX(),
                p.getGridY(),
                p.getPositionX(),
                p.getPositionY(),
                p.getPositionZ(),
                sortedTagsList(tags));

        SellerSummaryDto sellerDto = s == null ? null : new SellerSummaryDto(
                s.getId(),
                s.getDisplayName(),
                avatarUrl(s),
                s.getAvgSellerRating(),
                s.getTotalSellerReviews());

        boolean snipeProtect = Boolean.TRUE.equals(a.getSnipeProtect());

        return new AuctionSearchResultDto(
                a.getId(),
                a.getTitle(),
                a.getStatus(),
                a.getEndOutcome(),
                parcelDto,
                photoUrl,
                sellerDto,
                a.getVerificationTier(),
                a.getCurrentBid(),
                a.getStartingBid(),
                a.getReservePrice(),
                reserveMet,
                a.getBuyNowPrice(),
                a.getBidCount(),
                a.getEndsAt(),
                snipeProtect,
                a.getSnipeWindowMin(),
                distance);
    }

    /**
     * {@link ParcelTag} is an entity, not naturally {@link Comparable}; sort by
     * id for a stable client-visible order. Null ids (transient tags) sink to
     * the front via the natural ordering of {@code Long.compare(0, ...)}.
     */
    private List<ParcelTag> sortedTagsList(Set<ParcelTag> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        return tags.stream()
                .sorted(Comparator.comparingLong(
                        t -> t.getId() == null ? 0L : t.getId()))
                .toList();
    }

    private String avatarUrl(User u) {
        return u.getId() == null ? null : "/api/v1/users/" + u.getId() + "/avatar/256";
    }
}
