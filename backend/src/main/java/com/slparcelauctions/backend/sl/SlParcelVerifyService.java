package com.slparcelauctions.backend.sl;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.AuctionStatusConstants;
import com.slparcelauctions.backend.auction.VerificationMethod;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.auction.exception.AuctionNotFoundException;
import com.slparcelauctions.backend.auction.exception.InvalidAuctionStateException;
import com.slparcelauctions.backend.auction.exception.ParcelAlreadyListedException;
import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.parcel.ParcelRepository;
import com.slparcelauctions.backend.sl.dto.SlParcelVerifyRequest;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.verification.VerificationCode;
import com.slparcelauctions.backend.verification.VerificationCodeRepository;
import com.slparcelauctions.backend.verification.VerificationCodeType;
import com.slparcelauctions.backend.verification.exception.CodeNotFoundException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Handles the LSL rezzable-object callback {@code POST /api/v1/sl/parcel/verify}.
 * Validates SL-injected headers, the 6-digit PARCEL code, the seller's
 * ownership claim, parcel-lock, then transitions the auction to ACTIVE and
 * refreshes parcel metadata from the in-world report.
 *
 * <p>The endpoint is {@code permitAll} at the HTTP layer (Spring Security cannot
 * authenticate the grid's outbound {@code llHTTPRequest}); {@link SlHeaderValidator}
 * is the actual trust boundary.
 *
 * <p>The Postgres partial unique index on {@code parcel_id} for locking-status
 * auctions is the concurrent-race backstop (same invariant as Method A in
 * {@code AuctionVerificationService}). A {@link DataIntegrityViolationException}
 * on save is translated into {@link ParcelAlreadyListedException} with
 * {@code blockingAuctionId=-1} (the winning transaction's id is not available
 * at catch-time).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SlParcelVerifyService {

    private final SlHeaderValidator headerValidator;
    private final VerificationCodeRepository codeRepo;
    private final AuctionRepository auctionRepo;
    private final ParcelRepository parcelRepo;
    private final Clock clock;

    @Transactional
    public void verify(String shardHeader, String ownerKeyHeader, SlParcelVerifyRequest body) {
        headerValidator.validate(shardHeader, ownerKeyHeader);

        OffsetDateTime now = OffsetDateTime.now(clock);
        List<VerificationCode> matches = codeRepo
                .findByCodeAndTypeAndUsedFalseAndExpiresAtAfter(
                        body.verificationCode(), VerificationCodeType.PARCEL, now);
        if (matches.isEmpty()) {
            throw new CodeNotFoundException(body.verificationCode());
        }
        if (matches.size() > 1) {
            // PARCEL codes collide across auctions the same way PLAYER codes can
            // collide across users. Void ALL matches so the code cannot be reused,
            // then reject with the same narrow 400 as the not-found case — we do
            // not want to leak which auction ids the code could have referenced.
            matches.forEach(c -> c.setUsed(true));
            codeRepo.saveAll(matches);
            log.warn("PARCEL verification code collision: code={} auctions={} - voiding all matches",
                    body.verificationCode(),
                    matches.stream().map(VerificationCode::getAuctionId).toList());
            throw new CodeNotFoundException(body.verificationCode());
        }
        VerificationCode code = matches.get(0);

        Long auctionId = code.getAuctionId();
        if (auctionId == null) {
            // Defensive: a PARCEL row without an auction binding is malformed data.
            // generateForParcel always sets auctionId; a null here means the row
            // was inserted outside the service or the migration drifted. Fail loud.
            log.error("PARCEL code {} (id={}) has null auction_id; refusing to verify",
                    code.getCode(), code.getId());
            throw new IllegalStateException(
                    "PARCEL code has no auction binding: id=" + code.getId());
        }

        Auction auction = auctionRepo.findById(auctionId)
                .orElseThrow(() -> new AuctionNotFoundException(auctionId));
        if (auction.getVerificationMethod() != VerificationMethod.REZZABLE) {
            // Defensive: a PARCEL code exists for an auction whose method is no
            // longer REZZABLE. Seller switched methods mid-flight; refuse and
            // void the now-meaningless code.
            code.setUsed(true);
            codeRepo.save(code);
            throw new InvalidAuctionStateException(
                    auctionId, auction.getStatus(), "SL_PARCEL_VERIFY");
        }
        if (auction.getStatus() != AuctionStatus.VERIFICATION_PENDING) {
            throw new InvalidAuctionStateException(
                    auctionId, auction.getStatus(), "SL_PARCEL_VERIFY");
        }

        Parcel parcel = auction.getParcel();
        if (!parcel.getSlParcelUuid().equals(body.parcelUuid())) {
            throw new IllegalArgumentException(
                    "Parcel UUID mismatch: code is bound to parcel " + parcel.getSlParcelUuid()
                            + " but the in-world object reports " + body.parcelUuid());
        }
        User seller = auction.getSeller();
        if (seller.getSlAvatarUuid() == null
                || !body.ownerUuid().equals(seller.getSlAvatarUuid())) {
            throw new IllegalArgumentException(
                    "Owner UUID mismatch: in-world parcel owner " + body.ownerUuid()
                            + " is not the SL avatar linked to the listing seller.");
        }

        // Service-layer parcel-lock pre-check. Identifies the blocking auction
        // for the 409 response; the Postgres partial unique index catches any
        // concurrent race that slips past this check (handled in the catch
        // below around saveAndFlush).
        if (auctionRepo.existsByParcelIdAndStatusInAndIdNot(
                parcel.getId(), AuctionStatusConstants.LOCKING_STATUSES, auction.getId())) {
            Long blockingId = auctionRepo
                    .findFirstByParcelIdAndStatusIn(
                            parcel.getId(), AuctionStatusConstants.LOCKING_STATUSES)
                    .map(Auction::getId)
                    .orElse(-1L);
            throw new ParcelAlreadyListedException(parcel.getId(), blockingId);
        }

        code.setUsed(true);
        codeRepo.save(code);

        // Refresh parcel metadata from the in-world report. Only overwrite
        // fields the LSL object supplied — null on the wire means "unchanged".
        if (body.areaSqm() != null) parcel.setAreaSqm(body.areaSqm());
        if (body.description() != null) parcel.setDescription(body.description());
        if (body.regionPosX() != null) parcel.setPositionX(body.regionPosX());
        if (body.regionPosY() != null) parcel.setPositionY(body.regionPosY());
        if (body.regionPosZ() != null) parcel.setPositionZ(body.regionPosZ());
        parcel.setOwnerUuid(body.ownerUuid());
        parcel.setOwnerType("agent");
        parcel.setLastChecked(now);
        parcelRepo.save(parcel);

        OffsetDateTime endsAt = now.plusHours(auction.getDurationHours());
        auction.setStartsAt(now);
        auction.setEndsAt(endsAt);
        auction.setOriginalEndsAt(endsAt);
        auction.setVerifiedAt(now);
        auction.setVerificationTier(VerificationTier.SCRIPT);
        auction.setStatus(AuctionStatus.ACTIVE);

        try {
            // saveAndFlush mirrors Method A: force the INSERT/UPDATE to hit
            // Postgres now so the partial-unique-index violation surfaces as a
            // DataIntegrityViolationException inside this try/catch rather than
            // deferring to commit time where it would escape as an unhandled error.
            auctionRepo.saveAndFlush(auction);
        } catch (DataIntegrityViolationException e) {
            log.warn("Method B verification lost parcel-lock race for auction {}: {}",
                    auction.getId(), e.getMessage());
            throw new ParcelAlreadyListedException(parcel.getId(), -1L);
        }
        log.info("Method B verification complete: auction {} -> ACTIVE, ends {}",
                auction.getId(), endsAt);
    }
}
