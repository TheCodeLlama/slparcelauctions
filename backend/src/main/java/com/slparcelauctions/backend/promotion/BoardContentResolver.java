package com.slparcelauctions.backend.promotion;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionPhoto;
import com.slparcelauctions.backend.auction.AuctionParcelSnapshot;
import com.slparcelauctions.backend.auction.featured.FeaturedRepository;
import com.slparcelauctions.backend.promotion.dto.FeaturedBoardListingDto;
import com.slparcelauctions.backend.promotion.dto.FeaturedBoardPayloadDto;
import com.slparcelauctions.backend.promotion.dto.FeaturedBoardPayloadDto.Source;

import lombok.RequiredArgsConstructor;

/**
 * Builds the public-facing payload for one board. Composition rules per
 * spec section 4.2: PROMO_01 queue first, fall back to algorithmic featured
 * at a deterministic per-board offset, fall back to placeholder if both
 * empty.
 */
@Service
@RequiredArgsConstructor
public class BoardContentResolver {

    private final FeaturedBoardSlotService slotService;
    private final FeaturedRepository featuredRepository;
    private final PromotionConfigProperties promotionConfig;

    @Transactional(readOnly = true)
    public FeaturedBoardPayloadDto resolve(int boardIndex) {
        int cycleSeconds = (int) promotionConfig.featuredBoardCycle().toSeconds();

        // 1. PROMO_01 queue
        List<FeaturedBoardSlot> queue = slotService.activeQueueFor(boardIndex);
        if (!queue.isEmpty()) {
            List<FeaturedBoardListingDto> listings = queue.stream()
                    .map(s -> toDto(s.getAuction()))
                    .collect(Collectors.toList());
            return new FeaturedBoardPayloadDto(
                    boardIndex, cycleSeconds, listings, Source.PROMO_01);
        }

        // 2. Algorithmic fallback (deterministic per-board offset)
        List<Auction> algo = featuredRepository.featured();
        if (!algo.isEmpty()) {
            int index = (boardIndex - 1) % algo.size();
            Auction pick = algo.get(index);
            return new FeaturedBoardPayloadDto(
                    boardIndex, cycleSeconds, List.of(toDto(pick)), Source.ALGORITHMIC);
        }

        // 3. Placeholder
        return new FeaturedBoardPayloadDto(
                boardIndex, cycleSeconds, List.of(), Source.PLACEHOLDER);
    }

    /**
     * Compute the single listing currently on-screen at now() for the given
     * board's queue. The same formula runs in the browser cycle timer and
     * in the LSL touch handler so both sides agree.
     */
    @Transactional(readOnly = true)
    public FeaturedBoardListingDto currentTouchTarget(int boardIndex) {
        FeaturedBoardPayloadDto payload = resolve(boardIndex);
        if (payload.listings().isEmpty()) return null;
        if (payload.listings().size() == 1) return payload.listings().get(0);
        long epochSeconds = System.currentTimeMillis() / 1000L;
        int idx = (int) Math.floorMod(
                epochSeconds / payload.cycleSeconds(),
                payload.listings().size());
        return payload.listings().get(idx);
    }

    private FeaturedBoardListingDto toDto(Auction a) {
        AuctionParcelSnapshot snap = a.getParcelSnapshot();
        String region = snap != null ? snap.getRegionName() : null;
        Integer sqm = snap != null ? snap.getAreaSqm() : null;
        String slurl = snap != null ? snap.getSlurl() : null;
        String photoUrl = primaryPhotoUrl(a);
        return new FeaturedBoardListingDto(
                a.getPublicId(),
                a.getTitle(),
                region,
                sqm,
                photoUrl,
                a.getCurrentBid(),
                a.getEndsAt(),
                "/auction/" + a.getPublicId(),
                slurl
        );
    }

    /**
     * Returns the URL path for the cover photo of this auction, or null if the
     * auction has no photos. Uses the lazy photos collection already loaded on
     * the entity -- callers inside a @Transactional(readOnly) context will
     * trigger a single SELECT if the collection is not yet initialized.
     *
     * <p>TODO: hook up via AuctionPhotoBatchRepository for batch resolution when
     * integrating multi-listing board payloads on the frontend (spec §7.2 open
     * question).
     */
    private String primaryPhotoUrl(Auction a) {
        List<AuctionPhoto> photos = a.getPhotos();
        if (photos == null || photos.isEmpty()) return null;
        // Sort by sortOrder to get the cover; the collection order is not guaranteed
        // when loaded lazily outside an @EntityGraph fetch.
        return photos.stream()
                .min(java.util.Comparator.comparingInt(AuctionPhoto::getSortOrder))
                .map(p -> "/api/v1/photos/" + p.getPublicId())
                .orElse(null);
    }
}
