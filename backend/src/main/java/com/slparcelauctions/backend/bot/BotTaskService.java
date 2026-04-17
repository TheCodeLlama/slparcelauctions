package com.slparcelauctions.backend.bot;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.AuctionStatusConstants;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.auction.exception.InvalidAuctionStateException;
import com.slparcelauctions.backend.auction.exception.ParcelAlreadyListedException;
import com.slparcelauctions.backend.auction.monitoring.OwnershipCheckTimestampInitializer;
import com.slparcelauctions.backend.bot.dto.BotTaskCompleteRequest;
import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.parcel.ParcelRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Owns the lifecycle of {@link BotTask} rows for Method C SALE_TO_BOT
 * verification.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>{@link #createForAuction(Auction)} — enqueue a PENDING task for the
 *       bot worker (Epic 06) to pick up. On retry from VERIFICATION_FAILED,
 *       any prior PENDING/IN_PROGRESS task for the same auction is marked
 *       FAILED with reason {@code "Superseded by retry"} so the queue
 *       stays deduplicated.</li>
 *   <li>{@link #complete(Long, BotTaskCompleteRequest)} — the callback from
 *       the bot worker. On SUCCESS validates {@code authBuyerId} matches the
 *       configured primary-escrow UUID and {@code salePrice} matches the
 *       sentinel, enforces the parcel-lock invariant, refreshes parcel
 *       metadata from the payload, and transitions the auction ACTIVE with
 *       verification tier BOT. On FAILURE marks the task FAILED and the
 *       auction VERIFICATION_FAILED.</li>
 *   <li>{@link #findPending()} / {@link #findPendingOlderThan(Duration)} /
 *       {@link #markTimedOut(BotTask)} — queue query + 48-hour timeout
 *       sweep, driven by {@code BotTaskTimeoutJob}.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BotTaskService {

    private final BotTaskRepository botTaskRepo;
    private final AuctionRepository auctionRepo;
    private final ParcelRepository parcelRepo;
    private final OwnershipCheckTimestampInitializer ownershipInitializer;
    private final Clock clock;

    @Value("${slpa.bot-task.sentinel-price-lindens:999999999}")
    private long sentinelPrice;

    @Value("${slpa.bot-task.primary-escrow-uuid}")
    private UUID primaryEscrowUuid;

    /**
     * Enqueues a fresh PENDING task for {@code auction}. Any prior
     * PENDING/IN_PROGRESS task for the same auction is marked FAILED with
     * reason {@code "Superseded by retry"} so the queue deduplicates across
     * DRAFT_PAID → VERIFICATION_PENDING → VERIFICATION_FAILED → retry loops.
     */
    @Transactional
    public BotTask createForAuction(Auction auction) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        for (BotTaskStatus openStatus : List.of(BotTaskStatus.PENDING, BotTaskStatus.IN_PROGRESS)) {
            for (BotTask prior : botTaskRepo.findByStatusOrderByCreatedAtAsc(openStatus)) {
                if (prior.getAuction().getId().equals(auction.getId())) {
                    prior.setStatus(BotTaskStatus.FAILED);
                    prior.setFailureReason("Superseded by retry");
                    prior.setCompletedAt(now);
                    botTaskRepo.save(prior);
                    log.info("Bot task {} superseded by retry for auction {}",
                            prior.getId(), auction.getId());
                }
            }
        }

        BotTask task = BotTask.builder()
                .taskType(BotTaskType.VERIFY)
                .status(BotTaskStatus.PENDING)
                .auction(auction)
                .parcelUuid(auction.getParcel().getSlParcelUuid())
                .regionName(auction.getParcel().getRegionName())
                .sentinelPrice(sentinelPrice)
                .build();
        task = botTaskRepo.save(task);
        log.info("Bot task created: id={}, auctionId={}, parcelUuid={}",
                task.getId(), auction.getId(), task.getParcelUuid());
        return task;
    }

    /**
     * Handles a bot worker callback. See class-level javadoc for SUCCESS/FAILURE
     * semantics.
     *
     * @throws IllegalArgumentException if the task does not exist, or on SUCCESS
     *         with wrong {@code authBuyerId} / {@code salePrice}
     * @throws IllegalStateException if the task is already terminal (COMPLETED/FAILED)
     * @throws InvalidAuctionStateException if the auction is not in VERIFICATION_PENDING
     * @throws ParcelAlreadyListedException if a concurrent auction holds the parcel
     *         lock (or a DB-level race is detected at save-time)
     */
    @Transactional
    public BotTask complete(Long taskId, BotTaskCompleteRequest body) {
        BotTask task = botTaskRepo.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Bot task not found: " + taskId));
        if (task.getStatus() != BotTaskStatus.PENDING
                && task.getStatus() != BotTaskStatus.IN_PROGRESS) {
            throw new IllegalArgumentException(
                    "Bot task " + taskId + " is not open (status=" + task.getStatus() + ")");
        }
        Auction auction = task.getAuction();
        if (auction.getStatus() != AuctionStatus.VERIFICATION_PENDING) {
            throw new InvalidAuctionStateException(
                    auction.getId(), auction.getStatus(), "BOT_COMPLETE");
        }

        OffsetDateTime now = OffsetDateTime.now(clock);

        if ("FAILURE".equalsIgnoreCase(body.result())) {
            String reason = (body.failureReason() == null || body.failureReason().isBlank())
                    ? "Bot reported failure"
                    : body.failureReason();
            task.setStatus(BotTaskStatus.FAILED);
            task.setFailureReason(reason);
            task.setCompletedAt(now);
            botTaskRepo.save(task);

            auction.setStatus(AuctionStatus.VERIFICATION_FAILED);
            auction.setVerificationNotes("Bot: " + reason);
            auctionRepo.save(auction);
            log.info("Bot task {} FAILED: auctionId={} reason={}",
                    taskId, auction.getId(), reason);
            return task;
        }

        if (!"SUCCESS".equalsIgnoreCase(body.result())) {
            throw new IllegalArgumentException(
                    "result must be SUCCESS or FAILURE, got: " + body.result());
        }

        // SUCCESS path: validate escrow UUID + sentinel price before doing anything.
        if (body.authBuyerId() == null || !body.authBuyerId().equals(primaryEscrowUuid)) {
            throw new IllegalArgumentException(
                    "authBuyerId must equal the SLPA primary escrow UUID");
        }
        if (body.salePrice() == null || body.salePrice().longValue() != sentinelPrice) {
            throw new IllegalArgumentException(
                    "salePrice must equal the sentinel price L$" + sentinelPrice);
        }

        // Service-layer parcel-lock pre-check. If another auction holds the lock,
        // mark the task FAILED with PARCEL_LOCKED and throw the standard 409.
        Parcel parcel = auction.getParcel();
        if (auctionRepo.existsByParcelIdAndStatusInAndIdNot(
                parcel.getId(),
                AuctionStatusConstants.LOCKING_STATUSES,
                auction.getId())) {
            task.setStatus(BotTaskStatus.FAILED);
            task.setFailureReason("PARCEL_LOCKED");
            task.setCompletedAt(now);
            botTaskRepo.save(task);
            auction.setStatus(AuctionStatus.VERIFICATION_FAILED);
            auction.setVerificationNotes("Bot: PARCEL_LOCKED");
            auctionRepo.save(auction);
            Long blockingId = auctionRepo
                    .findFirstByParcelIdAndStatusIn(parcel.getId(),
                            AuctionStatusConstants.LOCKING_STATUSES)
                    .map(Auction::getId)
                    .orElse(-1L);
            throw new ParcelAlreadyListedException(parcel.getId(), blockingId);
        }

        // Refresh parcel metadata from the bot payload.
        if (body.parcelOwner() != null) parcel.setOwnerUuid(body.parcelOwner());
        if (body.areaSqm() != null) parcel.setAreaSqm(body.areaSqm());
        if (body.regionName() != null) parcel.setRegionName(body.regionName());
        if (body.positionX() != null) parcel.setPositionX(body.positionX());
        if (body.positionY() != null) parcel.setPositionY(body.positionY());
        if (body.positionZ() != null) parcel.setPositionZ(body.positionZ());
        parcel.setLastChecked(now);
        parcelRepo.save(parcel);

        // Record result data for audit/debug. Use a mutable map because
        // parcelOwner can be null; Map.of rejects null values.
        Map<String, Object> result = new HashMap<>();
        result.put("authBuyerId", body.authBuyerId().toString());
        result.put("salePrice", body.salePrice());
        result.put("parcelOwner",
                body.parcelOwner() == null ? null : body.parcelOwner().toString());
        task.setStatus(BotTaskStatus.COMPLETED);
        task.setCompletedAt(now);
        task.setResultData(result);
        botTaskRepo.save(task);

        OffsetDateTime endsAt = now.plusHours(auction.getDurationHours());
        auction.setStartsAt(now);
        auction.setEndsAt(endsAt);
        auction.setOriginalEndsAt(endsAt);
        auction.setVerifiedAt(now);
        auction.setVerificationTier(VerificationTier.BOT);
        auction.setStatus(AuctionStatus.ACTIVE);
        // Seed lastOwnershipCheckAt with jitter — see spec §8.2 and Method A.
        ownershipInitializer.onActivated(auction);
        try {
            // saveAndFlush forces the partial unique index to fire inside this
            // try/catch so a concurrent race surfaces as
            // ParcelAlreadyListedException rather than escaping at
            // transaction commit.
            auctionRepo.saveAndFlush(auction);
        } catch (DataIntegrityViolationException e) {
            log.warn("Bot task {} lost parcel-lock race for auction {}: {}",
                    taskId, auction.getId(), e.getMessage());
            throw new ParcelAlreadyListedException(parcel.getId(), -1L);
        }
        log.info("Bot task {} COMPLETED: auctionId={} -> ACTIVE tier=BOT, ends {}",
                taskId, auction.getId(), endsAt);
        return task;
    }

    @Transactional(readOnly = true)
    public List<BotTask> findPending() {
        return botTaskRepo.findByStatusOrderByCreatedAtAsc(BotTaskStatus.PENDING);
    }

    @Transactional(readOnly = true)
    public List<BotTask> findPendingOlderThan(Duration threshold) {
        OffsetDateTime cutoff = OffsetDateTime.now(clock).minus(threshold);
        return botTaskRepo.findByStatusAndCreatedAtBefore(BotTaskStatus.PENDING, cutoff);
    }

    /**
     * Times out a PENDING bot task. Transitions the task to FAILED with
     * reason {@code "TIMEOUT"} and flips the auction back to
     * VERIFICATION_FAILED — but only if the auction is still
     * VERIFICATION_PENDING. The guard is defensive: the auction could have
     * been manually cancelled or otherwise transitioned in between the
     * queue query and this callback.
     */
    @Transactional
    public void markTimedOut(BotTask task) {
        if (task.getStatus() != BotTaskStatus.PENDING
                && task.getStatus() != BotTaskStatus.IN_PROGRESS) {
            log.debug("Skipping timeout for bot task {} (status={})",
                    task.getId(), task.getStatus());
            return;
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        task.setStatus(BotTaskStatus.FAILED);
        task.setFailureReason("TIMEOUT");
        task.setCompletedAt(now);
        botTaskRepo.save(task);

        Auction auction = task.getAuction();
        if (auction.getStatus() == AuctionStatus.VERIFICATION_PENDING) {
            auction.setStatus(AuctionStatus.VERIFICATION_FAILED);
            // Sub-spec 2 §7.3: retry-friendly phrasing consistent with
            // ParcelCodeExpiryJob and Method A sync failures. No refund here —
            // ListingFeeRefund is created only by the cancel endpoint.
            auction.setVerificationNotes(
                    "Sale-to-bot task timed out after 48 hours without a match. "
                            + "You can retry at no extra cost.");
            auctionRepo.save(auction);
        }
        log.info("Bot task {} timed out (auctionId={})",
                task.getId(), auction.getId());
    }
}
