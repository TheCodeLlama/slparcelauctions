package com.slparcelauctions.backend.auction.monitoring;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
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
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OwnershipCheckTask {

    private final AuctionRepository auctionRepo;
    private final SlWorldApiClient worldApi;
    private final SuspensionService suspensionService;
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
        if (auction.getStatus() != AuctionStatus.ACTIVE) {
            log.debug("Ownership check skipped: auction {} status={}",
                    auctionId, auction.getStatus());
            return;
        }

        UUID parcelUuid = auction.getParcel().getSlParcelUuid();
        try {
            ParcelMetadata result = worldApi.fetchParcel(parcelUuid).block();
            if (result == null) {
                // Defensive: block() returning null is degenerate. Treat as a
                // transient World API failure so the counter advances but the
                // auction is not suspended.
                handleTimeout(auction, "empty World API response");
                return;
            }
            UUID expected = auction.getSeller().getSlAvatarUuid();
            UUID actual = result.ownerUuid();
            if (expected != null && expected.equals(actual) && "agent".equalsIgnoreCase(result.ownerType())) {
                auction.setLastOwnershipCheckAt(OffsetDateTime.now(clock));
                auction.setConsecutiveWorldApiFailures(0);
                auctionRepo.save(auction);
                log.debug("Ownership check OK for auction {} (owner={})", auctionId, actual);
            } else {
                suspensionService.suspendForOwnershipChange(auction, result);
            }
        } catch (ParcelNotFoundInSlException e) {
            suspensionService.suspendForDeletedParcel(auction);
        } catch (ExternalApiTimeoutException e) {
            handleTimeout(auction, e.getMessage());
        } catch (RuntimeException e) {
            // Some World API failures propagate as the underlying reactor
            // error (e.g. wrapped in a Reactor-internal exception).
            // SlWorldApiClient already maps known transports to
            // ExternalApiTimeoutException, so anything else surfacing here is
            // unexpected. Log with stack trace and let the next sweep retry.
            log.error("Unexpected error checking auction {}: {}", auctionId, e.getMessage(), e);
        }
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
