package com.slparcelauctions.backend.auction.parcelscan;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.exception.AuctionNotFoundException;
import com.slparcelauctions.backend.bot.BotTaskRepository;
import com.slparcelauctions.backend.bot.BotTaskType;

import lombok.RequiredArgsConstructor;

/**
 * Admin endpoints for the parcel-scan subsystem. Lives under
 * {@code /api/v1/admin/} so the SecurityConfig {@code hasRole("ADMIN")} rule
 * gates every method without any per-method {@code @PreAuthorize} annotation.
 */
@RestController
@RequestMapping("/api/v1/admin/parcel-scan")
@RequiredArgsConstructor
public class AdminParcelScanController {

    private final AuctionRepository auctionRepo;
    private final AuctionParcelLayoutRepository layoutRepo;
    private final AuctionParcelHeightMapRepository heightRepo;
    private final BotTaskRepository botTaskRepo;
    private final ParcelScanService parcelScanService;

    /**
     * Re-enqueue a SCAN_PARCEL task for an auction whose previous scan
     * attempt was orphaned (e.g. bot crashed mid-task leaving the row
     * IN_PROGRESS indefinitely). Steps:
     * <ol>
     *   <li>Load auction by publicId. 404 if not found.</li>
     *   <li>Delete any existing {@code auction_parcel_layouts} row.</li>
     *   <li>Delete any existing {@code auction_parcel_height_maps} row.</li>
     *   <li>Delete any non-terminal (PENDING or IN_PROGRESS) SCAN_PARCEL
     *       tasks so {@link ParcelScanService#enqueueIfEligible}'s
     *       duplicate-guard does not block re-enqueue.</li>
     *   <li>Call {@link ParcelScanService#enqueueIfEligible} to create
     *       a fresh PENDING SCAN_PARCEL task.</li>
     * </ol>
     *
     * @param publicId the auction's public UUID
     * @return 204 No Content on success; 404 if the auction is not found
     */
    @PostMapping("/{publicId}/reenqueue")
    @Transactional
    public ResponseEntity<Void> reenqueue(@PathVariable("publicId") UUID publicId) {
        Auction auction = auctionRepo.findByPublicId(publicId)
                .orElseThrow(() -> new AuctionNotFoundException(publicId));

        layoutRepo.deleteByAuctionId(auction.getId());
        heightRepo.deleteByAuctionId(auction.getId());
        botTaskRepo.deletePendingByAuctionIdAndType(auction.getId(), BotTaskType.SCAN_PARCEL);

        parcelScanService.enqueueIfEligible(auction);
        return ResponseEntity.noContent().build();
    }
}
