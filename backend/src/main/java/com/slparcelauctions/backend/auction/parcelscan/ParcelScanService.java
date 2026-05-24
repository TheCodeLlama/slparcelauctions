package com.slparcelauctions.backend.auction.parcelscan;

import java.time.OffsetDateTime;
import java.util.Base64;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.parcelscan.dto.BotScanResultRequest;
import com.slparcelauctions.backend.bot.BotTask;
import com.slparcelauctions.backend.bot.BotTaskRepository;
import com.slparcelauctions.backend.bot.BotTaskService;
import com.slparcelauctions.backend.bot.BotTaskStatus;
import com.slparcelauctions.backend.bot.BotTaskType;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Per-auction parcel scan orchestration. Enqueues a SCAN_PARCEL bot task on
 * eligible auctions (non-gating) and persists the two sibling rasters when
 * the bot reports back. See docs/superpowers/specs/2026-05-23-parcel-scanner-design.md.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ParcelScanService {

    private final AuctionParcelLayoutRepository layoutRepo;
    private final AuctionParcelHeightMapRepository heightRepo;
    private final BotTaskRepository botTaskRepo;
    private final BotTaskService botTaskService;

    /**
     * Enqueue a SCAN_PARCEL bot task for this auction if eligible:
     * scan included AND no raster on file AND no active (PENDING or IN_PROGRESS) task already.
     * A terminally-FAILED or CANCELLED prior task does NOT block re-enqueue.
     */
    @Transactional
    public void enqueueIfEligible(Auction auction) {
        if (auction.getParcelScanIncluded() == null || !auction.getParcelScanIncluded()) return;
        if (layoutRepo.existsByAuctionId(auction.getId())) return;
        if (botTaskRepo.existsPendingByAuctionIdAndType(
                auction.getId(), BotTaskType.SCAN_PARCEL)) return;

        botTaskService.enqueueScanParcel(auction);
    }

    /**
     * Apply a bot scan result. Idempotent on COMPLETED tasks (returns 409,
     * which the bot retry treats as success-already-recorded). Does NOT
     * gate on the auction's current status -- rasters are immutable data
     * tied to the auction record.
     */
    @Transactional
    public void applyScanResult(long taskId, BotScanResultRequest req) {
        BotTask task = botTaskRepo.findById(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "task not found"));
        if (task.getTaskType() != BotTaskType.SCAN_PARCEL) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "task is not SCAN_PARCEL");
        }
        if (task.getStatus() == BotTaskStatus.COMPLETED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "already completed");
        }

        byte[] layoutCells;
        byte[] heightCells;
        try {
            layoutCells = Base64.getDecoder().decode(req.layoutCellsBase64());
            heightCells = Base64.getDecoder().decode(req.heightCellsBase64());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid base64", ex);
        }

        int cells = req.gridSize() * req.gridSize();
        if (layoutCells.length != cells / 8) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "layout length " + layoutCells.length + " != " + (cells / 8));
        }
        if (heightCells.length != cells) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "height length " + heightCells.length + " != " + cells);
        }
        if (!Float.isFinite(req.heightBaseMeters())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "heightBaseMeters not finite");
        }
        if (req.heightStepMeters() <= 0f || !Float.isFinite(req.heightStepMeters())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "heightStepMeters must be > 0 and finite");
        }

        Auction auction = task.getAuction();

        OffsetDateTime now = OffsetDateTime.now();

        layoutRepo.save(AuctionParcelLayout.builder()
                .auction(auction)
                .gridSize(req.gridSize())
                .cellSizeMeters(req.cellSizeMeters())
                .cells(layoutCells)
                .scannedAt(now)
                .build());

        heightRepo.save(AuctionParcelHeightMap.builder()
                .auction(auction)
                .gridSize(req.gridSize())
                .cellSizeMeters(req.cellSizeMeters())
                .baseMeters(req.heightBaseMeters())
                .stepMeters(req.heightStepMeters())
                .cells(heightCells)
                .scannedAt(now)
                .build());

        botTaskService.markCompleted(task);
        log.info("Scan result task {} marked COMPLETED for auction {}", task.getId(), auction.getId());
    }
}
