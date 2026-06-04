package com.slparcelauctions.backend.promotion;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.promotion.exception.PromotionAlreadyActiveException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Transactional facade over {@link FeaturedBoardSlotRepository}. Owns the
 * row-level invariants: exactly one active slot per auction, append-only
 * position within a board, releaseTimestamp set on terminal transitions.
 *
 * <p>All public methods participate in the caller's transaction by default
 * (the typical caller is {@code PromotionService.purchaseFeatured} which
 * runs inside the wallet-debit transaction). {@code releaseForAuction} is
 * the one path that callers commonly invoke from {@code afterCommit} hooks
 * -- those callers MUST open a fresh transaction (the default propagation
 * REQUIRED handles that).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FeaturedBoardSlotService {

    private final FeaturedBoardSlotRepository slotRepo;
    private final FeaturedBoardAssignmentService assignmentService;
    private final PromotionConfigProperties promotionConfig;

    /**
     * Create a slot row for {@code auction}, picking the least-loaded board.
     * Must run inside a transaction. Fails fast with
     * {@link PromotionAlreadyActiveException} if the auction already has an
     * active row (enforced by the unique partial index in V47).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public FeaturedBoardSlot assign(Auction auction) {
        if (slotRepo.findActiveByAuctionId(auction.getId()).isPresent()) {
            throw new PromotionAlreadyActiveException(auction.getPublicId());
        }
        Map<Integer, Integer> counts = countsByBoard();
        var pick = assignmentService.assign(promotionConfig.featuredSlotCount(), counts);

        FeaturedBoardSlot slot = FeaturedBoardSlot.builder()
                .publicId(UUID.randomUUID())
                .boardIndex(pick.boardIndex())
                .auction(auction)
                .position(pick.position())
                .assignedAt(OffsetDateTime.now())
                .build();
        slot = slotRepo.save(slot);
        log.info("PROMO-01 slot assigned: auctionId={} boardIndex={} position={} slotId={}",
                auction.getId(), pick.boardIndex(), pick.position(), slot.getId());
        return slot;
    }

    /**
     * Release whatever slot (if any) currently holds {@code auctionId}.
     * Idempotent: no-op if no active row exists. Called by the auction
     * lifecycle (ENDED/CANCELLED/WITHDRAWN) via afterCommit hooks; called
     * directly by the admin force-release endpoint.
     */
    @Transactional
    public void releaseForAuction(long auctionId) {
        slotRepo.findActiveByAuctionId(auctionId).ifPresent(slot -> {
            slot.setReleasedAt(OffsetDateTime.now());
            slotRepo.save(slot);
            log.info("PROMO-01 slot released: auctionId={} slotId={} boardIndex={}",
                    auctionId, slot.getId(), slot.getBoardIndex());
        });
    }

    /**
     * Snapshot per-board active row counts. Cheap on small tables; if the
     * pool grows large enough that this becomes a hot path, swap for a
     * GROUP BY in the repository.
     */
    private Map<Integer, Integer> countsByBoard() {
        Map<Integer, Integer> counts = new HashMap<>();
        for (FeaturedBoardSlot s : slotRepo.allActive()) {
            counts.merge(s.getBoardIndex(), 1, Integer::sum);
        }
        return counts;
    }

    /**
     * Used by the admin curator and the read-side resolver.
     */
    @Transactional(readOnly = true)
    public List<FeaturedBoardSlot> activeQueueFor(int boardIndex) {
        return slotRepo.liveQueue(boardIndex);
    }

    @Transactional(readOnly = true)
    public List<FeaturedBoardSlot> allActive() {
        return slotRepo.allActive();
    }
}
