package com.slparcelauctions.backend.escrow.scheduler;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.escrow.Escrow;
import com.slparcelauctions.backend.escrow.EscrowRepository;
import com.slparcelauctions.backend.escrow.EscrowService;
import com.slparcelauctions.backend.escrow.EscrowState;
import com.slparcelauctions.backend.escrow.FreezeReason;
import com.slparcelauctions.backend.escrow.terminal.EscrowConfigProperties;
import com.slparcelauctions.backend.sl.SlWorldApiClient;
import com.slparcelauctions.backend.sl.dto.ParcelMetadata;
import com.slparcelauctions.backend.sl.exception.ExternalApiTimeoutException;
import com.slparcelauctions.backend.sl.exception.ParcelNotFoundInSlException;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Per-escrow ownership check worker dispatched by
 * {@link EscrowOwnershipMonitorJob} (spec §4.5). Runs under a pessimistic
 * write lock ({@code findByIdForUpdate}) in a fresh transaction
 * ({@code REQUIRES_NEW}) so the monitor serialises against the dispute,
 * payment, and timeout paths that take the same lock, and so the job's
 * loop-level exception handling can isolate per-escrow failures even if a
 * future refactor ever wraps the outer sweep in {@code @Transactional}.
 *
 * <p>Outcomes, mirroring the auction-level {@code OwnershipCheckTask} but
 * decision-matrix-specific to escrow:
 * <ul>
 *   <li><b>Parcel owner == winner</b> — delegate to
 *       {@link EscrowService#confirmTransfer}; escrow stays
 *       {@code TRANSFER_PENDING} so the payout callback (Task 7) owns the
 *       move to {@code COMPLETED}.</li>
 *   <li><b>Parcel owner == seller</b> — delegate to
 *       {@link EscrowService#stampChecked} and log a reminder line once the
 *       seller is past {@code ownershipReminderDelay} hours post-fund. The
 *       actual seller-reminder email lives in Epic 09.</li>
 *   <li><b>Parcel owner is any other avatar (or {@code group})</b> —
 *       delegate to {@link EscrowService#freezeForFraud} with
 *       {@link FreezeReason#UNKNOWN_OWNER}; refund is queued, fraud flag
 *       raised.</li>
 *   <li><b>{@link ParcelNotFoundInSlException}</b> — delegate to
 *       {@code freezeForFraud} with {@link FreezeReason#PARCEL_DELETED}.</li>
 *   <li><b>{@link ExternalApiTimeoutException}</b> — if the counter would
 *       cross {@code ownershipApiFailureThreshold}, freeze with
 *       {@link FreezeReason#WORLD_API_PERSISTENT_FAILURE}; otherwise
 *       increment the counter and retry next sweep.</li>
 *   <li><b>Unexpected exception</b> — log, treat as transient to avoid
 *       freezing on a single implementation bug.</li>
 * </ul>
 *
 * <p>Dispatched synchronously by the monitor (one escrow at a time) — unlike
 * the auction monitor we don't {@code @Async} because the sweep cardinality
 * is per-TRANSFER_PENDING escrow which is always small. If that assumption
 * changes we can add {@code @Async} without touching the call graph.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EscrowOwnershipCheckTask {

    private final EscrowRepository escrowRepo;
    private final EscrowService escrowService;
    private final SlWorldApiClient worldApi;
    private final UserRepository userRepo;
    private final EscrowConfigProperties props;
    private final Clock clock;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void checkOne(Long escrowId) {
        Escrow escrow = escrowRepo.findByIdForUpdate(escrowId).orElse(null);
        if (escrow == null) {
            log.debug("Escrow ownership check skipped: escrow {} not found", escrowId);
            return;
        }
        if (escrow.getState() != EscrowState.TRANSFER_PENDING) {
            log.debug("Escrow ownership check skipped: escrow {} state={}",
                    escrowId, escrow.getState());
            return;
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        UUID parcelUuid = escrow.getAuction().getParcel().getSlParcelUuid();

        try {
            ParcelMetadata result = worldApi.fetchParcelPage(parcelUuid)
                    .map(com.slparcelauctions.backend.sl.dto.ParcelPageData::parcel)
                    .block();
            if (result == null) {
                // Defensive: a null block() result is degenerate but should not
                // tip us into a freeze on a single run. Treat as transient.
                handleApiFailure(escrow, now, "empty World API response");
                return;
            }

            UUID ownerUuid = result.ownerUuid();
            User winner = userRepo.findById(escrow.getAuction().getWinnerUserId()).orElseThrow();
            UUID winnerUuid = winner.getSlAvatarUuid();
            UUID sellerUuid = escrow.getAuction().getSeller().getSlAvatarUuid();

            if (winnerUuid != null && winnerUuid.equals(ownerUuid)) {
                escrowService.confirmTransfer(escrow, now);
                return;
            }
            if (sellerUuid != null && sellerUuid.equals(ownerUuid)) {
                escrowService.stampChecked(escrow, now);
                maybeLogReminder(escrow, now);
                return;
            }

            // Owner is an unknown third party (or a group, or a null-owner
            // parcel that reparented to somebody else). Freeze the escrow
            // and let the admin review queue decide the refund path.
            Map<String, Object> evidence = new HashMap<>();
            evidence.put("observedOwnerUuid", ownerUuid == null ? "<null>" : ownerUuid.toString());
            evidence.put("expectedWinnerUuid", winnerUuid == null ? "<null>" : winnerUuid.toString());
            evidence.put("expectedSellerUuid", sellerUuid == null ? "<null>" : sellerUuid.toString());
            evidence.put("observedOwnerType", result.ownerType() == null ? "<null>" : result.ownerType());
            escrowService.freezeForFraud(escrow, FreezeReason.UNKNOWN_OWNER, evidence, now);

        } catch (ParcelNotFoundInSlException e) {
            Map<String, Object> evidence = new HashMap<>();
            evidence.put("parcelUuid", parcelUuid.toString());
            evidence.put("worldApiMessage", e.getMessage() == null ? "" : e.getMessage());
            escrowService.freezeForFraud(escrow, FreezeReason.PARCEL_DELETED, evidence, now);
        } catch (ExternalApiTimeoutException e) {
            handleApiFailure(escrow, now, "World API timeout: " + e.getMessage());
        } catch (RuntimeException e) {
            log.error("Escrow {} ownership check unexpected error: {}",
                    escrowId, e.getMessage(), e);
            // Treat unexpected as transient — we don't want a single
            // implementation bug to tip a real escrow into FROZEN.
            handleApiFailure(escrow, now, "unexpected exception: " + e.getClass().getSimpleName());
        }
    }

    private void handleApiFailure(Escrow escrow, OffsetDateTime now, String reason) {
        int prior = escrow.getConsecutiveWorldApiFailures() == null
                ? 0 : escrow.getConsecutiveWorldApiFailures();
        int newCount = prior + 1;
        int threshold = props.ownershipApiFailureThreshold();
        if (newCount >= threshold) {
            // Stamp the final counter on the in-memory entity so
            // freezeForFraud's save() persists it alongside the state
            // transition — otherwise the frozen row carries the pre-increment
            // count and only the evidence JSON reflects the true trigger
            // value, which is operationally confusing during incident review.
            escrow.setConsecutiveWorldApiFailures(newCount);
            Map<String, Object> evidence = new HashMap<>();
            evidence.put("consecutiveFailures", newCount);
            evidence.put("threshold", threshold);
            evidence.put("lastReason", reason);
            escrowService.freezeForFraud(escrow, FreezeReason.WORLD_API_PERSISTENT_FAILURE,
                    evidence, now);
        } else {
            escrowService.incrementWorldApiFailure(escrow, now);
        }
    }

    private void maybeLogReminder(Escrow escrow, OffsetDateTime now) {
        if (escrow.getFundedAt() == null) return;
        Duration sinceFunded = Duration.between(escrow.getFundedAt(), now);
        if (sinceFunded.compareTo(props.ownershipReminderDelay()) >= 0) {
            log.info("seller_transfer_reminder_due=true escrow={} auction={} fundedAt={}",
                    escrow.getId(), escrow.getAuction().getId(), escrow.getFundedAt());
        }
    }
}
