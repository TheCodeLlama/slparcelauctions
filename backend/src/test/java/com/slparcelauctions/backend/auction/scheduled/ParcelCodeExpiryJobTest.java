package com.slparcelauctions.backend.auction.scheduled;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.VerificationMethod;
import com.slparcelauctions.backend.verification.VerificationCode;
import com.slparcelauctions.backend.verification.VerificationCodeRepository;
import com.slparcelauctions.backend.verification.VerificationCodeType;

/**
 * Unit coverage for {@link ParcelCodeExpiryJob}.
 *
 * <p>The job only touches auctions whose {@code status=VERIFICATION_PENDING}
 * AND {@code verificationMethod=REZZABLE}. The repository filter
 * ({@code findByStatusAndVerificationMethod}) already excludes Method A and
 * Method C auctions at the query level; the job defensively relies on that
 * rather than re-checking the method in application code.
 */
class ParcelCodeExpiryJobTest {

    private static final OffsetDateTime NOW = OffsetDateTime.of(
            2026, 4, 16, 12, 0, 0, 0, ZoneOffset.UTC);
    private static final Clock FIXED = Clock.fixed(NOW.toInstant(), ZoneOffset.UTC);

    private AuctionRepository auctionRepo;
    private VerificationCodeRepository codeRepo;
    private ParcelCodeExpiryJob job;

    @BeforeEach
    void setUp() {
        auctionRepo = mock(AuctionRepository.class);
        codeRepo = mock(VerificationCodeRepository.class);
        job = new ParcelCodeExpiryJob(auctionRepo, codeRepo, FIXED);
    }

    @Test
    void stuckRezzableWithExpiredCode_transitionsToVerificationFailed_withNotes() {
        // Sub-spec 2 §7.3: all verification-failure paths (Method A sync
        // mismatch, Method B code expiry, Method C 48-hour timeout) converge on
        // VERIFICATION_FAILED with retry-friendly verificationNotes. No
        // ListingFeeRefund is created — the job doesn't depend on
        // ListingFeeRefundRepository, so refund creation is structurally
        // impossible from this path.
        Auction stuck = buildAuction(1L, VerificationMethod.REZZABLE);
        VerificationCode expired = VerificationCode.builder()
                .id(99L).userId(42L).auctionId(1L)
                .code("111111").type(VerificationCodeType.PARCEL)
                .expiresAt(NOW.minusMinutes(1)).used(false).build();
        when(auctionRepo.findByStatusAndVerificationMethod(
                AuctionStatus.VERIFICATION_PENDING, VerificationMethod.REZZABLE))
                .thenReturn(List.of(stuck));
        when(codeRepo.findByAuctionIdAndTypeAndUsedFalse(1L, VerificationCodeType.PARCEL))
                .thenReturn(List.of(expired));

        job.sweep();

        assertThat(stuck.getStatus()).isEqualTo(AuctionStatus.VERIFICATION_FAILED);
        assertThat(stuck.getVerificationNotes())
                .contains("code expired")
                .contains("retry at no extra cost");
        verify(auctionRepo).save(stuck);
    }

    @Test
    void stuckRezzableWithNoCodes_transitionsToVerificationFailed() {
        // Orphaned PENDING with no codes at all — still treated as "no active code"
        // and failed. Shouldn't happen in practice (dispatchMethodB always issues
        // one) but the sweep must not hang on edge-case inconsistency.
        Auction stuck = buildAuction(2L, VerificationMethod.REZZABLE);
        when(auctionRepo.findByStatusAndVerificationMethod(
                AuctionStatus.VERIFICATION_PENDING, VerificationMethod.REZZABLE))
                .thenReturn(List.of(stuck));
        when(codeRepo.findByAuctionIdAndTypeAndUsedFalse(2L, VerificationCodeType.PARCEL))
                .thenReturn(List.of());

        job.sweep();

        assertThat(stuck.getStatus()).isEqualTo(AuctionStatus.VERIFICATION_FAILED);
        verify(auctionRepo).save(stuck);
    }

    @Test
    void stuckRezzableWithStillActiveCode_leftAlone() {
        Auction fresh = buildAuction(3L, VerificationMethod.REZZABLE);
        VerificationCode live = VerificationCode.builder()
                .id(100L).userId(42L).auctionId(3L)
                .code("222222").type(VerificationCodeType.PARCEL)
                .expiresAt(NOW.plusMinutes(10)).used(false).build();
        when(auctionRepo.findByStatusAndVerificationMethod(
                AuctionStatus.VERIFICATION_PENDING, VerificationMethod.REZZABLE))
                .thenReturn(List.of(fresh));
        when(codeRepo.findByAuctionIdAndTypeAndUsedFalse(3L, VerificationCodeType.PARCEL))
                .thenReturn(List.of(live));

        job.sweep();

        assertThat(fresh.getStatus()).isEqualTo(AuctionStatus.VERIFICATION_PENDING);
        verify(auctionRepo, never()).save(fresh);
    }

    @Test
    void multipleAuctions_onlyExpiredOnesFailed() {
        Auction expiredOne = buildAuction(10L, VerificationMethod.REZZABLE);
        Auction liveOne = buildAuction(11L, VerificationMethod.REZZABLE);

        when(auctionRepo.findByStatusAndVerificationMethod(
                AuctionStatus.VERIFICATION_PENDING, VerificationMethod.REZZABLE))
                .thenReturn(List.of(expiredOne, liveOne));
        when(codeRepo.findByAuctionIdAndTypeAndUsedFalse(10L, VerificationCodeType.PARCEL))
                .thenReturn(List.of(VerificationCode.builder()
                        .expiresAt(NOW.minusMinutes(5)).used(false).build()));
        when(codeRepo.findByAuctionIdAndTypeAndUsedFalse(11L, VerificationCodeType.PARCEL))
                .thenReturn(List.of(VerificationCode.builder()
                        .expiresAt(NOW.plusMinutes(5)).used(false).build()));

        job.sweep();

        assertThat(expiredOne.getStatus()).isEqualTo(AuctionStatus.VERIFICATION_FAILED);
        assertThat(liveOne.getStatus()).isEqualTo(AuctionStatus.VERIFICATION_PENDING);
        verify(auctionRepo).save(expiredOne);
        verify(auctionRepo, never()).save(liveOne);
    }

    @Test
    void noStuckAuctions_shortCircuits_noCodeLookups() {
        when(auctionRepo.findByStatusAndVerificationMethod(
                AuctionStatus.VERIFICATION_PENDING, VerificationMethod.REZZABLE))
                .thenReturn(List.of());

        job.sweep();

        verifyNoInteractions(codeRepo);
    }

    @Test
    void methodAAndMethodCPending_notReturnedByFilter() {
        // The repository filter hard-narrows to REZZABLE, so Method A and
        // Method C auctions in VERIFICATION_PENDING are never even loaded.
        // Stubbing an empty result models the real-world query outcome and
        // asserts the job leaves those auctions alone.
        when(auctionRepo.findByStatusAndVerificationMethod(
                AuctionStatus.VERIFICATION_PENDING, VerificationMethod.REZZABLE))
                .thenReturn(List.of());

        job.sweep();

        verify(auctionRepo, never()).save(org.mockito.ArgumentMatchers.any());
        verifyNoInteractions(codeRepo);
    }

    private Auction buildAuction(Long id, VerificationMethod method) {
        return Auction.builder()
                .id(id).status(AuctionStatus.VERIFICATION_PENDING)
                .verificationMethod(method)
                .startingBid(1000L).durationHours(168)
                .snipeProtect(false).listingFeePaid(true)
                .currentBid(0L).bidCount(0)
                .commissionRate(new BigDecimal("0.05"))
                .agentFeeRate(BigDecimal.ZERO)
                .tags(new HashSet<>())
                .build();
    }
}
