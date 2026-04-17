package com.slparcelauctions.backend.auction.scheduled;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.VerificationMethod;
import com.slparcelauctions.backend.verification.VerificationCodeRepository;
import com.slparcelauctions.backend.verification.VerificationCodeType;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Reverts stuck Method B (REZZABLE) auctions when their PARCEL verification
 * code has expired without an LSL callback.
 *
 * <p>Sweeps every {@code slpa.verification.parcel-code-expiry-check-interval}
 * (default {@code PT5M}). For every {@code VERIFICATION_PENDING} auction with
 * {@code verificationMethod = REZZABLE}, if there is no unexpired unused
 * PARCEL code still in the table, the auction is transitioned back to
 * {@code DRAFT_PAID} and annotated so the seller can click Verify again for
 * a fresh code. There is no refund: the seller still owes the listing fee
 * and has not consumed an escrow slot yet.
 *
 * <p>Auctions with other verification methods are left alone — Method A is
 * synchronous (never stays in VERIFICATION_PENDING past the initial request)
 * and Method C has its own 48-hour timeout job (Task 8).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ParcelCodeExpiryJob {

    private final AuctionRepository auctionRepo;
    private final VerificationCodeRepository codeRepo;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${slpa.verification.parcel-code-expiry-check-interval:PT5M}")
    @Transactional
    public void sweep() {
        List<Auction> pending = auctionRepo.findByStatusAndVerificationMethod(
                AuctionStatus.VERIFICATION_PENDING, VerificationMethod.REZZABLE);
        if (pending.isEmpty()) return;

        OffsetDateTime now = OffsetDateTime.now(clock);
        int reverted = 0;
        for (Auction a : pending) {
            boolean hasActiveCode = codeRepo
                    .findByAuctionIdAndTypeAndUsedFalse(a.getId(), VerificationCodeType.PARCEL)
                    .stream()
                    .anyMatch(c -> c.getExpiresAt().isAfter(now));
            if (!hasActiveCode) {
                a.setStatus(AuctionStatus.DRAFT_PAID);
                a.setVerificationNotes(
                        "Verification code expired without callback. Click Verify again.");
                auctionRepo.save(a);
                reverted++;
                log.info("Reverted Method B auction {} to DRAFT_PAID (PARCEL code expired)",
                        a.getId());
            }
        }
        if (reverted > 0) {
            log.info("ParcelCodeExpiryJob: reverted {} auction(s) this sweep", reverted);
        }
    }
}
