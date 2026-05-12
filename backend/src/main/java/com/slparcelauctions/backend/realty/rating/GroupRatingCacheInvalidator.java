package com.slparcelauctions.backend.realty.rating;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.realty.slgroup.RealtyGroupSlGroupRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Application-event listener that drops the {@link GroupRatingService}
 * cache entry for the realty group affected by a new review. Resolves
 * the auction-&gt;group linkage in one of two ways:
 *
 * <ol>
 *   <li>Case-1 direct: {@code Auction.realtyGroupId} points at the group.</li>
 *   <li>Case-3 indirect: {@code Auction.realtyGroupSlGroupId} points at a
 *       {@code RealtyGroupSlGroup} row whose {@code realtyGroupId} is the
 *       owning group.</li>
 * </ol>
 *
 * <p>If neither linkage is present — i.e. a plain non-group auction — the
 * event is a no-op. Listener runs after the originating transaction has
 * been published (Spring's default {@code @EventListener} semantics),
 * which is acceptable here because the cache key is best-effort: a small
 * window where the cache reflects a stale aggregate self-heals on the
 * next read after the 1h TTL.
 *
 * <p>Spec: {@code docs/superpowers/specs/2026-05-12-realty-groups-admin-moderation-design.md} §16.2.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GroupRatingCacheInvalidator {

    private final GroupRatingService ratingService;
    private final AuctionRepository auctionRepo;
    private final RealtyGroupSlGroupRepository slGroupRepo;

    @EventListener
    public void on(ReviewCreatedEvent event) {
        auctionRepo.findById(event.auctionId()).ifPresent(a -> {
            if (a.getRealtyGroupId() != null) {
                log.debug("Review {} on auction {} -> invalidating realty group {} (case-1)",
                        event.reviewId(), event.auctionId(), a.getRealtyGroupId());
                ratingService.invalidate(a.getRealtyGroupId());
                return;
            }
            if (a.getRealtyGroupSlGroupId() != null) {
                slGroupRepo.findById(a.getRealtyGroupSlGroupId()).ifPresent(rsg -> {
                    log.debug("Review {} on auction {} -> invalidating realty group {} (case-3 via sl group {})",
                            event.reviewId(), event.auctionId(), rsg.getRealtyGroupId(), rsg.getId());
                    ratingService.invalidate(rsg.getRealtyGroupId());
                });
            }
        });
    }
}
