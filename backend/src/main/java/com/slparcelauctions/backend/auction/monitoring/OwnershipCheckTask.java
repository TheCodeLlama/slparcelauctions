package com.slparcelauctions.backend.auction.monitoring;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.CancellationLog;
import com.slparcelauctions.backend.auction.CancellationLogRepository;
import com.slparcelauctions.backend.auction.exception.AuctionNotFoundException;
import com.slparcelauctions.backend.sl.SlWorldApiClient;
import com.slparcelauctions.backend.sl.dto.ParcelMetadata;
import com.slparcelauctions.backend.sl.exception.ExternalApiTimeoutException;
import com.slparcelauctions.backend.sl.exception.ParcelNotFoundInSlException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Per-auction ownership check dispatched by {@link OwnershipMonitorScheduler}.
 * Runs on Spring's async executor (separate thread, fresh transaction) so a
 * slow World API call does not block the scheduler thread from dispatching
 * the next auction. Outcomes (spec §8.2):
 * <ul>
 *   <li><b>Owner match</b> — stamp {@code lastOwnershipCheckAt}, reset the
 *       World API failure counter, save.</li>
 *   <li><b>Owner mismatch</b> — delegate to
 *       {@link SuspensionService#suspendForOwnershipChange}.</li>
 *   <li><b>{@link ParcelNotFoundInSlException}</b> (World API 404) — delegate
 *       to {@link SuspensionService#suspendForDeletedParcel}.</li>
 *   <li><b>{@link ExternalApiTimeoutException}</b> — increment
 *       {@code consecutiveWorldApiFailures}, stamp check time, do not suspend.
 *       The WORLD_API_FAILURE_THRESHOLD flag (spec §8.8) is raised in a later
 *       pass once the counter crosses the configured threshold.</li>
 *   <li><b>Unexpected exception</b> — log and swallow. The scheduler will
 *       retry on the next sweep. An async task that throws would otherwise be
 *       silently lost to the executor's uncaught-exception handler.</li>
 * </ul>
 *
 * <p>{@code @Async} runs the body on a proxy-managed thread, so
 * {@code @Transactional} starts a fresh transaction in that thread. This is
 * the desired behavior: per-auction isolation, no scheduler-thread contention.
 * The non-ACTIVE guard handles the race where an auction was cancelled,
 * suspended, or ended between scheduler dispatch and async execution.
 *
 * <p>{@link #recheckSync(Long)} exposes the same core check logic
 * synchronously for admin tooling (Epic 10 sub-spec 3 Task 16). Both paths
 * share the private {@link #doCheck(Auction)} helper.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OwnershipCheckTask {

    private final AuctionRepository auctionRepo;
    private final SlWorldApiClient worldApi;
    private final SuspensionService suspensionService;
    private final CancellationLogRepository cancellationLogRepo;
    private final Clock clock;

    @Async
    @Transactional
    public void checkOne(Long auctionId) {
        // Pessimistic write lock so an ownership-driven suspension does not race
        // against a concurrent bid-placement or auction-end close. If BidService
        // holds the lock we block until it commits, then the non-ACTIVE guard
        // or a fresh view of currentBid decides the outcome. See
        // BidSuspendRaceTest for the regression pin.
        Auction auction = auctionRepo.findByIdForUpdate(auctionId).orElse(null);
        if (auction == null) {
            log.debug("Ownership check skipped: auction {} not found", auctionId);
            return;
        }
        // Two valid paths: ACTIVE (live ownership monitor) and CANCELLED with
        // an open post-cancel watch window (Epic 08 sub-spec 2 §6). Anything
        // else short-circuits — covers the race where status moves between
        // scheduler dispatch and async execution.
        AuctionStatus status = auction.getStatus();
        boolean activePath = status == AuctionStatus.ACTIVE;
        boolean cancelledWatchPath = status == AuctionStatus.CANCELLED
                && auction.getPostCancelWatchUntil() != null
                && OffsetDateTime.now(clock).isBefore(auction.getPostCancelWatchUntil());
        if (!activePath && !cancelledWatchPath) {
            log.debug("Ownership check skipped: auction {} status={}",
                    auctionId, status);
            return;
        }

        try {
            doCheck(auction);
        } catch (RuntimeException e) {
            // Some World API failures propagate as the underlying reactor
            // error (e.g. wrapped in a Reactor-internal exception).
            // SlWorldApiClient already maps known transports to
            // ExternalApiTimeoutException, so anything else surfacing here is
            // unexpected. Log with stack trace and let the next sweep retry.
            log.error("Unexpected error checking auction {}: {}", auctionId, e.getMessage(), e);
        }
    }

    /**
     * Synchronous ownership recheck for admin tooling. Runs in a fresh
     * transaction ({@link Propagation#REQUIRES_NEW}) so any auto-suspension
     * side-effects commit before the HTTP response is rendered.
     *
     * @throws AuctionNotFoundException if no auction exists with the given id
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OwnershipCheckResult recheckSync(Long auctionId) {
        Auction auction = auctionRepo.findByIdForUpdate(auctionId)
                .orElseThrow(() -> new AuctionNotFoundException(auctionId));
        return doCheck(auction);
    }

    /**
     * Core ownership-check logic shared between {@link #checkOne(Long)} and
     * {@link #recheckSync(Long)}. Fetches parcel metadata from the World API,
     * compares the owner, and delegates to {@link SuspensionService} on
     * mismatch. Returns an {@link OwnershipCheckResult} reflecting the
     * outcome; callers may ignore the result (async path) or surface it
     * (admin sync path).
     *
     * <p>The {@code checkOne} caller has already applied status guards
     * (ACTIVE / CANCELLED+watch). {@code recheckSync} skips those guards
     * intentionally — admin-initiated rechecks apply to any status.
     */
    private OwnershipCheckResult doCheck(Auction auction) {
        UUID parcelUuid = auction.getParcel().getSlParcelUuid();
        OffsetDateTime now = OffsetDateTime.now(clock);
        UUID expected = auction.getSeller().getSlAvatarUuid();
        UUID observed = null;
        boolean ownerMatch = false;

        AuctionStatus status = auction.getStatus();
        boolean activePath = status == AuctionStatus.ACTIVE;
        boolean cancelledWatchPath = status == AuctionStatus.CANCELLED
                && auction.getPostCancelWatchUntil() != null
                && OffsetDateTime.now(clock).isBefore(auction.getPostCancelWatchUntil());

        try {
            ParcelMetadata result = worldApi.fetchParcelPage(parcelUuid)
                    .map(com.slparcelauctions.backend.sl.dto.ParcelPageData::parcel)
                    .block();
            if (result == null) {
                // Defensive: block() returning null is degenerate. Treat as a
                // transient World API failure so the counter advances but the
                // auction is not suspended.
                handleTimeout(auction, "empty World API response");
            } else {
                observed = result.ownerUuid();
                ownerMatch = expected != null
                        && expected.equals(observed)
                        && "agent".equalsIgnoreCase(result.ownerType());

                if (ownerMatch) {
                    auction.setLastOwnershipCheckAt(now);
                    auction.setConsecutiveWorldApiFailures(0);
                    auctionRepo.save(auction);
                    log.debug("Ownership check OK for auction {} (owner={})", auction.getId(), observed);
                } else if (cancelledWatchPath) {
                    // Post-cancel mismatch — raise the CANCEL_AND_SELL flag. Clear
                    // {@code postCancelWatchUntil} on the same row so subsequent
                    // ticks during the original window don't re-flag the same
                    // cancellation. The flag carries the rich evidence map per
                    // spec §6.3 so admin reviewers can score temporal proximity.
                    suspensionService.raiseCancelAndSellFlag(auction, observed,
                            resolveCancelledAt(auction));
                    auction.setLastOwnershipCheckAt(now);
                    auction.setPostCancelWatchUntil(null);
                    auctionRepo.save(auction);
                } else if (activePath) {
                    // ACTIVE mismatch — existing flow. SuspensionService handles
                    // the status flip (ACTIVE → SUSPENDED), the fraud-flag write,
                    // and removes the auction from the watcher query naturally
                    // since the status is no longer ACTIVE.
                    suspensionService.suspendForOwnershipChange(auction, result);
                }
                // For other statuses, record the outcome without side-effect.
            }
        } catch (ParcelNotFoundInSlException e) {
            // Both paths route a deleted parcel through the existing
            // suspension service — for the CANCELLED path the auction is
            // already cancelled, so this is best-effort logging. The flag
            // helps the admin dashboard tie a deleted-parcel signal to the
            // post-cancel window.
            if (activePath) {
                suspensionService.suspendForDeletedParcel(auction);
            } else {
                log.warn("Post-cancel watcher: parcel {} no longer exists in-world for auction {}",
                        parcelUuid, auction.getId());
            }
        } catch (ExternalApiTimeoutException e) {
            handleTimeout(auction, e.getMessage());
        }

        // SuspensionService mutates the auction entity in-memory (setStatus) before
        // persisting, so auction.getStatus() reflects any suspension outcome here.
        return new OwnershipCheckResult(ownerMatch, expected, observed, now, auction.getStatus());
    }

    /**
     * Resolves the cancellation timestamp from the most recent
     * {@link CancellationLog} for the auction. Drives the
     * {@code hoursSinceCancellation} evidence field — computing from the log
     * row (not {@code postCancelWatchUntil - watchHours}) keeps the math
     * robust if the watch-window length is reconfigured between cancel and
     * flag.
     */
    private OffsetDateTime resolveCancelledAt(Auction auction) {
        java.util.List<CancellationLog> latest =
                cancellationLogRepo.findLatestByAuctionId(auction.getId(), PageRequest.of(0, 1));
        return latest.isEmpty() ? null : latest.get(0).getCancelledAt();
    }

    private void handleTimeout(Auction auction, String detail) {
        int prior = auction.getConsecutiveWorldApiFailures() == null
                ? 0
                : auction.getConsecutiveWorldApiFailures();
        auction.setConsecutiveWorldApiFailures(prior + 1);
        auction.setLastOwnershipCheckAt(OffsetDateTime.now(clock));
        auctionRepo.save(auction);
        log.warn("World API timeout for auction {} (consecutive={}): {}",
                auction.getId(), auction.getConsecutiveWorldApiFailures(), detail);
    }
}
