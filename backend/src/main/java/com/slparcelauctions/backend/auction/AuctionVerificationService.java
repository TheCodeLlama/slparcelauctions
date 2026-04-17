package com.slparcelauctions.backend.auction;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.auction.dto.PendingVerification;
import com.slparcelauctions.backend.auction.exception.InvalidAuctionStateException;
import com.slparcelauctions.backend.auction.exception.ParcelAlreadyListedException;
import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.sl.SlWorldApiClient;
import com.slparcelauctions.backend.sl.dto.ParcelMetadata;
import com.slparcelauctions.backend.user.User;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Unified verification dispatch for {@code PUT /auctions/{id}/verify}. Owns the
 * three verification methods:
 * <ul>
 *   <li><b>Method A (UUID_ENTRY)</b> — synchronous inline World API ownership
 *       check. Transitions directly to ACTIVE or VERIFICATION_FAILED.</li>
 *   <li><b>Method B (REZZABLE)</b> — wired in Task 7.</li>
 *   <li><b>Method C (SALE_TO_BOT)</b> — wired in Task 8.</li>
 * </ul>
 *
 * <p>Every DRAFT_PAID → ACTIVE path goes through {@link #assertParcelNotLocked}
 * so the service-layer pre-check identifies the blocking auction for a clean
 * 409 response. The Postgres partial unique index
 * ({@code uq_auctions_parcel_locked_status}, created at boot by
 * {@code ParcelLockingIndexInitializer}) is the concurrent-race backstop: a
 * {@link DataIntegrityViolationException} on save is translated back into
 * {@link ParcelAlreadyListedException} with {@code blockingAuctionId=-1}
 * (the winning transaction's ID is not available at catch-time).
 *
 * <p>See spec §8.3 for the parcel-lock invariant and §9 for Method A flow.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuctionVerificationService {

    /** Statuses a seller may trigger verification from. */
    static final Set<AuctionStatus> VERIFY_ALLOWED_FROM = Set.of(
            AuctionStatus.DRAFT_PAID,
            AuctionStatus.VERIFICATION_FAILED);

    private final AuctionService auctionService;
    private final AuctionRepository auctionRepo;
    private final SlWorldApiClient worldApi;
    private final Clock clock;

    /**
     * Entry point. Loads the auction (404s non-sellers), validates the state
     * transition (409 if status is not {@link #VERIFY_ALLOWED_FROM}), flips to
     * {@code VERIFICATION_PENDING}, clears stale verification notes, and
     * dispatches by verification method. Method A runs inline and leaves the
     * auction in ACTIVE or VERIFICATION_FAILED before returning.
     */
    @Transactional
    public Auction triggerVerification(Long auctionId, Long sellerId) {
        Auction a = auctionService.loadForSeller(auctionId, sellerId);
        if (!VERIFY_ALLOWED_FROM.contains(a.getStatus())) {
            throw new InvalidAuctionStateException(a.getId(), a.getStatus(), "VERIFY");
        }
        a.setStatus(AuctionStatus.VERIFICATION_PENDING);
        a.setVerificationNotes(null);
        Auction pending = auctionRepo.save(a);

        VerificationMethod method = pending.getVerificationMethod();
        if (method == null) {
            throw new IllegalStateException(
                    "Auction " + pending.getId() + " has no verificationMethod set; cannot verify.");
        }
        return switch (method) {
            case UUID_ENTRY -> dispatchMethodA(pending);
            case REZZABLE -> throw new UnsupportedOperationException("Method B wired in Task 7");
            case SALE_TO_BOT -> throw new UnsupportedOperationException("Method C wired in Task 8");
        };
    }

    /**
     * Method A: World API ownership check. On success, transitions to ACTIVE
     * (after parcel-lock clearance). On any failure — World API error, group
     * owner, or owner UUID mismatch — records a note and transitions to
     * VERIFICATION_FAILED. Never throws; the auction always ends up in one of
     * the two terminal states for this flow.
     */
    private Auction dispatchMethodA(Auction a) {
        Parcel parcel = a.getParcel();
        User seller = a.getSeller();

        ParcelMetadata fresh;
        try {
            fresh = worldApi.fetchParcel(parcel.getSlParcelUuid()).block();
        } catch (RuntimeException e) {
            log.warn("Method A World API lookup failed for auction {}: {}",
                    a.getId(), e.getMessage());
            return failVerification(a, "World API lookup failed: " + e.getMessage());
        }
        if (fresh == null) {
            // block() returning null is degenerate but guard defensively
            return failVerification(a, "World API lookup failed: empty response");
        }

        if (!"agent".equalsIgnoreCase(fresh.ownerType())) {
            return failVerification(a,
                    "Method A rejects group-owned parcels. Use Method C (Sale-to-Bot).");
        }

        UUID freshOwner = fresh.ownerUuid();
        UUID sellerAvatar = seller.getSlAvatarUuid();
        if (freshOwner == null || sellerAvatar == null || !freshOwner.equals(sellerAvatar)) {
            return failVerification(a,
                    "Ownership mismatch: parcel owner " + freshOwner
                            + " does not match seller avatar " + sellerAvatar + ".");
        }

        // Service-layer parcel-lock pre-check. Identifies the blocking auction
        // for the 409 response. The Postgres partial unique index catches any
        // concurrent race that slips past this check.
        assertParcelNotLocked(a);

        // Refresh parcel ownership fields from fresh World API data.
        parcel.setOwnerUuid(fresh.ownerUuid());
        parcel.setOwnerType(fresh.ownerType());
        parcel.setLastChecked(OffsetDateTime.now(clock));

        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime ends = now.plusHours(a.getDurationHours());
        a.setStartsAt(now);
        a.setEndsAt(ends);
        a.setOriginalEndsAt(ends);
        a.setVerifiedAt(now);
        a.setVerificationTier(VerificationTier.SCRIPT);
        a.setStatus(AuctionStatus.ACTIVE);

        try {
            // saveAndFlush forces the INSERT/UPDATE to hit Postgres now so the partial
            // unique index violation surfaces as DataIntegrityViolationException inside
            // this try/catch, rather than deferring to transaction-commit time where it
            // would escape as an unhandled error.
            Auction saved = auctionRepo.saveAndFlush(a);
            log.info("Method A verification succeeded: auction {} -> ACTIVE, ends {}",
                    saved.getId(), ends);
            return saved;
        } catch (DataIntegrityViolationException e) {
            // Postgres partial unique index tripped — concurrent race lost.
            log.warn("Method A verification lost parcel-lock race for auction {}: {}",
                    a.getId(), e.getMessage());
            throw new ParcelAlreadyListedException(parcel.getId(), -1L);
        }
    }

    private Auction failVerification(Auction a, String note) {
        a.setVerificationNotes(note);
        a.setStatus(AuctionStatus.VERIFICATION_FAILED);
        Auction saved = auctionRepo.save(a);
        log.info("Method A verification failed for auction {}: {}", saved.getId(), note);
        return saved;
    }

    /**
     * Throws {@link ParcelAlreadyListedException} if any other auction on the
     * same parcel is in a {@link AuctionStatusConstants#LOCKING_STATUSES locking
     * status}. Excludes the candidate auction itself (which is currently in
     * {@code VERIFICATION_PENDING} and therefore not locking).
     */
    private void assertParcelNotLocked(Auction candidate) {
        Long parcelId = candidate.getParcel().getId();
        boolean exists = auctionRepo.existsByParcelIdAndStatusInAndIdNot(
                parcelId, AuctionStatusConstants.LOCKING_STATUSES, candidate.getId());
        if (!exists) return;

        Long blockingId = auctionRepo
                .findFirstByParcelIdAndStatusIn(parcelId, AuctionStatusConstants.LOCKING_STATUSES)
                .map(Auction::getId)
                .orElse(-1L);
        throw new ParcelAlreadyListedException(parcelId, blockingId);
    }

    /**
     * Returns the pending-verification payload for the seller response. Method
     * A is synchronous (never stays in VERIFICATION_PENDING after
     * {@link #triggerVerification}), so it always returns null. Methods B and C
     * will be filled in in Tasks 7 and 8.
     */
    @Transactional(readOnly = true)
    public PendingVerification buildPendingVerification(Auction a) {
        if (a.getStatus() != AuctionStatus.VERIFICATION_PENDING) {
            return null;
        }
        VerificationMethod method = a.getVerificationMethod();
        if (method == null) {
            return null;
        }
        return switch (method) {
            case UUID_ENTRY -> null;      // Method A is synchronous; never pending.
            case REZZABLE -> null;        // Wired in Task 7.
            case SALE_TO_BOT -> null;     // Wired in Task 8.
        };
    }
}
