package com.slparcelauctions.backend.auction;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.auction.exception.InvalidAuctionStateException;
import com.slparcelauctions.backend.auction.exception.ParcelAlreadyListedException;
import com.slparcelauctions.backend.auction.monitoring.OwnershipCheckTimestampInitializer;
import com.slparcelauctions.backend.notification.NotificationPublisher;
import com.slparcelauctions.backend.realty.slgroup.RealtyGroupSlGroup;
import com.slparcelauctions.backend.realty.slgroup.RealtyGroupSlGroupRepository;
import com.slparcelauctions.backend.sl.SlWorldApiClient;
import com.slparcelauctions.backend.sl.dto.ParcelMetadata;
import com.slparcelauctions.backend.sl.dto.ParcelPageData;
import com.slparcelauctions.backend.user.User;

import lombok.extern.slf4j.Slf4j;

/**
 * Single-shot verification handler for {@code PUT /auctions/{publicId}/verify}.
 *
 * <p>One synchronous World API ownership check decides whether the auction
 * transitions to ACTIVE or VERIFICATION_FAILED. There is no intermediate
 * VERIFICATION_PENDING state, no bot task, no rezzable LSL callback, and
 * no seller-chosen "method". The expected owner is derived from the
 * auction:
 * <ul>
 *   <li><b>Case 1 (individual seller)</b> -- parcel must be owned by the
 *       seller's avatar (ownerType == "agent").</li>
 *   <li><b>Case 3 (SL group)</b> -- parcel must be owned by the registered
 *       SL group UUID (ownerType == "group"), looked up via
 *       {@link RealtyGroupSlGroupRepository}.</li>
 * </ul>
 *
 * <p>Every DRAFT_PAID -> ACTIVE path goes through {@link #assertParcelNotLocked}
 * so the service-layer pre-check identifies the blocking auction for a clean
 * 409 response. The Postgres partial unique index
 * ({@code uq_auctions_parcel_locked_status}, created at boot by
 * {@code ParcelLockingIndexInitializer}) is the concurrent-race backstop: a
 * {@link DataIntegrityViolationException} on save is translated back into
 * {@link ParcelAlreadyListedException} with {@code blockingAuctionId=-1}
 * (the winning transaction's ID is not available at catch-time).
 *
 * <p>See {@code docs/superpowers/specs/2026-05-16-ownership-only-verification-design.md}
 * for the design rationale.
 */
@Service
@Slf4j
public class AuctionVerificationService {

    /** Statuses a seller may trigger verification from. */
    static final Set<AuctionStatus> VERIFY_ALLOWED_FROM = Set.of(
            AuctionStatus.DRAFT_PAID,
            AuctionStatus.VERIFICATION_FAILED);

    private final AuctionService auctionService;
    private final AuctionRepository auctionRepo;
    private final SlWorldApiClient worldApi;
    private final RealtyGroupSlGroupRepository slGroupRepo;
    private final OwnershipCheckTimestampInitializer ownershipInitializer;
    private final NotificationPublisher notificationPublisher;
    private final Clock clock;

    public AuctionVerificationService(
            AuctionService auctionService,
            AuctionRepository auctionRepo,
            SlWorldApiClient worldApi,
            RealtyGroupSlGroupRepository slGroupRepo,
            OwnershipCheckTimestampInitializer ownershipInitializer,
            NotificationPublisher notificationPublisher,
            Clock clock) {
        this.auctionService = auctionService;
        this.auctionRepo = auctionRepo;
        this.worldApi = worldApi;
        this.slGroupRepo = slGroupRepo;
        this.ownershipInitializer = ownershipInitializer;
        this.notificationPublisher = notificationPublisher;
        this.clock = clock;
    }

    /**
     * Entry point. Loads the auction (404s non-sellers), validates the state
     * transition (409 if status is not {@link #VERIFY_ALLOWED_FROM}), and
     * runs a synchronous World API ownership check. The auction always lands
     * in ACTIVE (match) or VERIFICATION_FAILED (any miss / error). The method
     * never throws on a verification miss -- the {@code verificationNotes}
     * field carries the human-readable reason for the seller to retry.
     */
    @Transactional
    public Auction triggerVerification(Long auctionId, Long sellerId) {
        Auction a = auctionService.loadForSeller(auctionId, sellerId);
        if (!VERIFY_ALLOWED_FROM.contains(a.getStatus())) {
            throw new InvalidAuctionStateException(a.getId(), a.getStatus(), "VERIFY");
        }
        // Clear any prior failure note so stale retry explanations don't
        // leak into the current attempt. A fresh failure will write a new
        // note.
        a.setVerificationNotes(null);

        UUID expectedOwner;
        String expectedOwnerType;
        if (a.getRealtyGroupSlGroupId() != null) {
            RealtyGroupSlGroup reg = slGroupRepo.findById(a.getRealtyGroupSlGroupId()).orElse(null);
            if (reg == null) {
                return failVerification(a,
                        "The SL group registration linked to this listing is missing. "
                                + "Contact support or reattach the registration.");
            }
            expectedOwner = reg.getSlGroupUuid();
            expectedOwnerType = "group";
        } else {
            User seller = a.getSeller();
            UUID sellerAvatar = seller == null ? null : seller.getSlAvatarUuid();
            if (sellerAvatar == null) {
                return failVerification(a,
                        "Your account is not linked to an SL avatar. "
                                + "Finish SL verification, then retry.");
            }
            expectedOwner = sellerAvatar;
            expectedOwnerType = "agent";
        }

        ParcelMetadata fresh;
        try {
            fresh = worldApi.fetchParcelPage(a.getSlParcelUuid())
                    .map(ParcelPageData::parcel)
                    .block();
        } catch (RuntimeException e) {
            log.warn("Ownership verification World API lookup failed for auction {}: {}",
                    a.getId(), e.getMessage());
            return failVerification(a, "World API lookup failed: " + e.getMessage());
        }
        if (fresh == null) {
            // block() returning null is degenerate; guard defensively.
            return failVerification(a, "World API lookup failed: empty response");
        }

        UUID observedOwner = fresh.ownerUuid();
        String observedType = fresh.ownerType();

        if (!"group".equalsIgnoreCase(expectedOwnerType)
                && "group".equalsIgnoreCase(observedType)) {
            return failVerification(a,
                    "The parcel is currently owned by a group, but this listing expects "
                            + "an individually-owned parcel. Deed the parcel back to your "
                            + "avatar or re-list it under your realty group.");
        }
        if ("group".equalsIgnoreCase(expectedOwnerType)
                && !"group".equalsIgnoreCase(observedType)) {
            return failVerification(a,
                    "The parcel is no longer owned by a group. Re-deed it to the "
                            + "registered SL group, then retry.");
        }
        if (observedOwner == null || !observedOwner.equals(expectedOwner)) {
            String detail = "group".equalsIgnoreCase(expectedOwnerType)
                    ? "the parcel is owned by a different group"
                    : "the parcel is owned by a different avatar";
            return failVerification(a, "Ownership check failed: " + detail + ".");
        }

        // Service-layer parcel-lock pre-check. Identifies the blocking auction
        // for the 409 response. The Postgres partial unique index catches any
        // concurrent race that slips past this check.
        assertParcelNotLocked(a);

        OffsetDateTime now = OffsetDateTime.now(clock);
        AuctionParcelSnapshot snapshot = a.getParcelSnapshot();
        snapshot.setOwnerUuid(observedOwner);
        snapshot.setOwnerType(observedType);
        snapshot.setLastChecked(now);

        OffsetDateTime ends = now.plusHours(a.getDurationHours());
        a.setStartsAt(now);
        a.setEndsAt(ends);
        a.setOriginalEndsAt(ends);
        a.setVerifiedAt(now);
        a.setVerificationTier(VerificationTier.SCRIPT);
        a.setStatus(AuctionStatus.ACTIVE);
        a.setConsecutiveOwnerMismatches(0);
        // Seed lastOwnershipCheckAt with jitter so the next scheduler sweep
        // does not slam the World API with every freshly-activated listing at
        // once. See OwnershipCheckTimestampInitializer.
        ownershipInitializer.onActivated(a);

        try {
            // saveAndFlush forces the INSERT/UPDATE to hit Postgres now so the partial
            // unique index violation surfaces as DataIntegrityViolationException inside
            // this try/catch, rather than deferring to transaction-commit time where it
            // would escape as an unhandled error.
            Auction saved = auctionRepo.saveAndFlush(a);
            notificationPublisher.listingVerified(
                    saved.getSeller().getId(), saved.getId(), saved.getTitle());
            log.info("Ownership verification succeeded: auction {} -> ACTIVE, ends {}",
                    saved.getId(), ends);
            return saved;
        } catch (DataIntegrityViolationException e) {
            // Postgres partial unique index tripped -- concurrent race lost.
            log.warn("Ownership verification lost parcel-lock race for auction {}: {}",
                    a.getId(), e.getMessage());
            throw new ParcelAlreadyListedException(a.getId(), -1L);
        }
    }

    private Auction failVerification(Auction a, String note) {
        a.setVerificationNotes(note);
        a.setStatus(AuctionStatus.VERIFICATION_FAILED);
        Auction saved = auctionRepo.save(a);
        log.info("Ownership verification failed for auction {}: {}", saved.getId(), note);
        return saved;
    }

    /**
     * Throws {@link ParcelAlreadyListedException} if any other auction on the
     * same parcel is in a {@link AuctionStatusConstants#LOCKING_STATUSES locking
     * status}. Excludes the candidate auction itself (which is still
     * DRAFT_PAID / VERIFICATION_FAILED at this point and therefore not
     * locking).
     */
    private void assertParcelNotLocked(Auction candidate) {
        UUID slParcelUuid = candidate.getSlParcelUuid();
        boolean exists = auctionRepo.existsBySlParcelUuidAndStatusInAndIdNot(
                slParcelUuid, AuctionStatusConstants.LOCKING_STATUSES, candidate.getId());
        if (!exists) return;

        Long blockingId = auctionRepo
                .findFirstBySlParcelUuidAndStatusIn(slParcelUuid, AuctionStatusConstants.LOCKING_STATUSES)
                .map(Auction::getId)
                .orElse(-1L);
        throw new ParcelAlreadyListedException(candidate.getId(), blockingId);
    }
}
