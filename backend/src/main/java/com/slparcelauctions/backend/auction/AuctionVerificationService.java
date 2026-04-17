package com.slparcelauctions.backend.auction;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.auction.dto.PendingVerification;
import com.slparcelauctions.backend.auction.exception.GroupLandRequiresSaleToBotException;
import com.slparcelauctions.backend.auction.exception.InvalidAuctionStateException;
import com.slparcelauctions.backend.auction.exception.ParcelAlreadyListedException;
import com.slparcelauctions.backend.bot.BotTask;
import com.slparcelauctions.backend.bot.BotTaskRepository;
import com.slparcelauctions.backend.bot.BotTaskService;
import com.slparcelauctions.backend.bot.BotTaskStatus;
import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.sl.SlWorldApiClient;
import com.slparcelauctions.backend.sl.dto.ParcelMetadata;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.verification.VerificationCodeService;
import com.slparcelauctions.backend.verification.dto.ActiveCodeResponse;

import lombok.extern.slf4j.Slf4j;

/**
 * Unified verification dispatch for {@code PUT /auctions/{id}/verify}. Owns the
 * three verification methods:
 * <ul>
 *   <li><b>Method A (UUID_ENTRY)</b> — synchronous inline World API ownership
 *       check. Transitions directly to ACTIVE or VERIFICATION_FAILED.</li>
 *   <li><b>Method B (REZZABLE)</b> — asynchronous in-world LSL object callback;
 *       {@code dispatchMethodB} issues a PARCEL verification code and the
 *       {@code POST /api/v1/sl/parcel/verify} handler finishes the transition.</li>
 *   <li><b>Method C (SALE_TO_BOT)</b> — asynchronous bot worker; {@code dispatchMethodC}
 *       enqueues a {@link BotTask} and {@link BotTaskService#complete} finishes
 *       the transition when the worker (or dev stub) reports back.</li>
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
@Slf4j
public class AuctionVerificationService {

    /** Statuses a seller may trigger verification from. */
    static final Set<AuctionStatus> VERIFY_ALLOWED_FROM = Set.of(
            AuctionStatus.DRAFT_PAID,
            AuctionStatus.VERIFICATION_FAILED);

    private final AuctionService auctionService;
    private final AuctionRepository auctionRepo;
    private final SlWorldApiClient worldApi;
    private final VerificationCodeService verificationCodeService;
    private final BotTaskService botTaskService;
    private final BotTaskRepository botTaskRepo;
    private final Clock clock;
    private final UUID primaryEscrowUuid;
    private final long sentinelPriceLindens;

    public AuctionVerificationService(
            AuctionService auctionService,
            AuctionRepository auctionRepo,
            SlWorldApiClient worldApi,
            VerificationCodeService verificationCodeService,
            BotTaskService botTaskService,
            BotTaskRepository botTaskRepo,
            Clock clock,
            @Value("${slpa.bot-task.primary-escrow-uuid}") UUID primaryEscrowUuid,
            @Value("${slpa.bot-task.sentinel-price-lindens:999999999}") long sentinelPriceLindens) {
        this.auctionService = auctionService;
        this.auctionRepo = auctionRepo;
        this.worldApi = worldApi;
        this.verificationCodeService = verificationCodeService;
        this.botTaskService = botTaskService;
        this.botTaskRepo = botTaskRepo;
        this.clock = clock;
        this.primaryEscrowUuid = primaryEscrowUuid;
        this.sentinelPriceLindens = sentinelPriceLindens;
    }

    /**
     * Entry point. Loads the auction (404s non-sellers), validates the state
     * transition (409 if status is not {@link #VERIFY_ALLOWED_FROM}), applies
     * the group-land gate, persists the seller-chosen verification method,
     * flips to {@code VERIFICATION_PENDING}, clears stale verification notes,
     * and dispatches by method. Method A runs inline and leaves the auction
     * in ACTIVE or VERIFICATION_FAILED before returning.
     *
     * <p>Sub-spec 2 §7.2 — the method is supplied on every verify call (also
     * on retry from VERIFICATION_FAILED). Group-owned parcels must pick
     * SALE_TO_BOT; any other method throws
     * {@link GroupLandRequiresSaleToBotException} (422).
     */
    @Transactional
    public Auction triggerVerification(Long auctionId, VerificationMethod method, Long sellerId) {
        Auction a = auctionService.loadForSeller(auctionId, sellerId);
        if (!VERIFY_ALLOWED_FROM.contains(a.getStatus())) {
            throw new InvalidAuctionStateException(a.getId(), a.getStatus(), "VERIFY");
        }

        // Group-owned land can only be verified via the sale-to-bot path —
        // UUID_ENTRY and REZZABLE both assume the seller's avatar is the owner.
        if ("group".equalsIgnoreCase(a.getParcel().getOwnerType())
                && method != VerificationMethod.SALE_TO_BOT) {
            throw new GroupLandRequiresSaleToBotException();
        }

        a.setVerificationMethod(method);
        a.setStatus(AuctionStatus.VERIFICATION_PENDING);
        // Clear any prior failure note so stale retry explanations from a
        // previous VERIFICATION_FAILED attempt don't leak into the pending
        // state. Any fresh failure will write a new note.
        a.setVerificationNotes(null);
        Auction pending = auctionRepo.save(a);

        return switch (method) {
            case UUID_ENTRY -> dispatchMethodA(pending);
            case REZZABLE -> dispatchMethodB(pending);
            case SALE_TO_BOT -> dispatchMethodC(pending);
        };
    }

    /**
     * Method C: enqueue a bot_task for the SL worker (Epic 06) to verify via a
     * parcel sale-to-bot check. On retry from VERIFICATION_FAILED,
     * {@link BotTaskService#createForAuction} deduplicates by marking any prior
     * open task for this auction {@code FAILED("Superseded by retry")}. Leaves
     * the auction in VERIFICATION_PENDING; the transition to ACTIVE happens in
     * {@link BotTaskService#complete} when the worker (or dev stub at
     * {@code POST /api/v1/dev/bot/tasks/{id}/complete}) reports SUCCESS. If
     * nothing reports back within 48 hours, {@code BotTaskTimeoutJob} flips
     * the auction to VERIFICATION_FAILED.
     */
    private Auction dispatchMethodC(Auction a) {
        botTaskService.createForAuction(a);
        log.info("Method C verification pending: auction {} enqueued for bot worker", a.getId());
        return a;
    }

    /**
     * Method B: generates a PARCEL-type verification code bound to this auction
     * and leaves the auction in {@code VERIFICATION_PENDING}. The seller rezzes
     * an in-world object that posts to {@code POST /api/v1/sl/parcel/verify}
     * with the code + parcel/owner data; that endpoint handles the actual
     * transition to ACTIVE (see {@code SlParcelVerifyService}). If the code
     * expires without a callback, {@code ParcelCodeExpiryJob} transitions the
     * auction to VERIFICATION_FAILED with retry-friendly notes (sub-spec 2
     * §7.3 — every failure path lands in the same state; no automatic refund).
     */
    private Auction dispatchMethodB(Auction a) {
        verificationCodeService.generateForParcel(a.getSeller().getId(), a.getId());
        log.info("Method B verification pending: auction {} awaiting LSL callback", a.getId());
        return a;
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
                    "Ownership check failed: the parcel's owner UUID doesn't match your avatar. "
                            + "Pick another method or correct the UUID.");
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
     * {@link #triggerVerification}), so it always returns null. Method B
     * hydrates the freshly-generated PARCEL code + expiry. Method C surfaces
     * the enqueued bot task id + human-readable instructions.
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
            case REZZABLE -> buildRezzablePending(a);
            case SALE_TO_BOT -> buildBotTaskPending(a);
        };
    }

    private PendingVerification buildRezzablePending(Auction a) {
        Optional<ActiveCodeResponse> active = verificationCodeService
                .findActiveForParcel(a.getSeller().getId(), a.getId());
        return active
                .map(c -> new PendingVerification(
                        VerificationMethod.REZZABLE,
                        c.code(), c.expiresAt(),
                        null, null))
                .orElse(null);
    }

    /**
     * Resolves the most recent open bot task for this auction and formats the
     * seller-visible instructions. Checks PENDING first (the common case) and
     * falls back to IN_PROGRESS so a task the worker has already claimed
     * still surfaces in the response. Returns null if no open task exists —
     * the {@link BotTaskTimeoutJob} or a retry will have already flipped the
     * auction out of VERIFICATION_PENDING by the time this is called.
     */
    private PendingVerification buildBotTaskPending(Auction a) {
        BotTask open = findOpenTask(a, BotTaskStatus.PENDING);
        if (open == null) {
            open = findOpenTask(a, BotTaskStatus.IN_PROGRESS);
        }
        if (open == null) {
            return null;
        }
        String instructions = String.format(
                "Set your parcel for sale to SLPAEscrow Resident (UUID: %s) at L$%d. "
                        + "A verification worker will confirm within 48 hours.",
                primaryEscrowUuid, sentinelPriceLindens);
        return new PendingVerification(
                VerificationMethod.SALE_TO_BOT,
                null, null,
                open.getId(), instructions);
    }

    private BotTask findOpenTask(Auction a, BotTaskStatus status) {
        List<BotTask> tasks = botTaskRepo.findByStatusOrderByCreatedAtAsc(status);
        BotTask latest = null;
        for (BotTask t : tasks) {
            if (!t.getAuction().getId().equals(a.getId())) continue;
            if (latest == null || t.getCreatedAt().isAfter(latest.getCreatedAt())) {
                latest = t;
            }
        }
        return latest;
    }
}
