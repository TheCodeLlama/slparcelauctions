package com.slparcelauctions.backend.auction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.slparcelauctions.backend.auction.dto.PublicAuctionResponse;
import com.slparcelauctions.backend.auction.dto.PublicAuctionStatus;
import com.slparcelauctions.backend.auction.dto.SellerAuctionResponse;
import com.slparcelauctions.backend.escrow.EscrowRepository;
import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.user.User;

class AuctionDtoMapperTest {

    private final AuctionPhotoRepository photoRepo = mock(AuctionPhotoRepository.class);
    private final EscrowRepository escrowRepo = mock(EscrowRepository.class);
    private final AuctionDtoMapper mapper = new AuctionDtoMapper(photoRepo, escrowRepo);

    {
        when(photoRepo.findByAuctionIdOrderBySortOrderAsc(any())).thenReturn(List.of());
        when(escrowRepo.findByAuctionId(any())).thenReturn(java.util.Optional.empty());
    }

    @Test
    void toPublicStatus_activeStaysActive() {
        assertThat(mapper.toPublicStatus(AuctionStatus.ACTIVE)).isEqualTo(PublicAuctionStatus.ACTIVE);
    }

    @ParameterizedTest
    @EnumSource(value = AuctionStatus.class, names = {
            "ENDED", "ESCROW_PENDING", "ESCROW_FUNDED",
            "TRANSFER_PENDING", "COMPLETED", "CANCELLED", "EXPIRED", "DISPUTED"})
    void toPublicStatus_postActiveCollapsesToEnded(AuctionStatus status) {
        assertThat(mapper.toPublicStatus(status)).isEqualTo(PublicAuctionStatus.ENDED);
    }

    @ParameterizedTest
    @EnumSource(value = AuctionStatus.class, names = {
            "DRAFT", "DRAFT_PAID", "VERIFICATION_PENDING", "VERIFICATION_FAILED"})
    void toPublicStatus_preActiveThrows(AuctionStatus status) {
        assertThatThrownBy(() -> mapper.toPublicStatus(status))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(status.name());
    }

    @Test
    void toPublicResponse_hidesSellerOnlyFields() {
        Auction a = buildAuction(AuctionStatus.ACTIVE);
        a.setWinnerId(77L);
        a.setListingFeeAmt(100L);
        a.setListingFeeTxn("txn-42");
        a.setVerificationNotes("internal note");
        a.setCommissionAmt(500L);
        a.setReservePrice(5000L);
        a.setCurrentBid(4000L);

        PublicAuctionResponse public_ = mapper.toPublicResponse(a);

        // Reserve fields: boolean flags, NOT the raw amount
        assertThat(public_.hasReserve()).isTrue();
        assertThat(public_.reserveMet()).isFalse();
        // DTO has no raw reservePrice field — compile-time enforced
    }

    @Test
    void toPublicResponse_reserveMet_whenCurrentBidReachesReserve() {
        Auction a = buildAuction(AuctionStatus.ACTIVE);
        a.setReservePrice(5000L);
        a.setCurrentBid(5000L);

        PublicAuctionResponse public_ = mapper.toPublicResponse(a);

        assertThat(public_.hasReserve()).isTrue();
        assertThat(public_.reserveMet()).isTrue();
    }

    @Test
    void toPublicResponse_noReserve_hasReserveFalse() {
        Auction a = buildAuction(AuctionStatus.ACTIVE);
        a.setReservePrice(null);
        a.setCurrentBid(1000L);

        PublicAuctionResponse public_ = mapper.toPublicResponse(a);

        assertThat(public_.hasReserve()).isFalse();
        assertThat(public_.reserveMet()).isFalse();
    }

    @Test
    void toSellerResponse_includesAllSellerOnlyFields() {
        Auction a = buildAuction(AuctionStatus.VERIFICATION_PENDING);
        a.setVerificationMethod(VerificationMethod.UUID_ENTRY);
        a.setListingFeePaid(true);
        a.setListingFeeAmt(100L);
        a.setCommissionRate(new BigDecimal("0.0500"));

        SellerAuctionResponse seller = mapper.toSellerResponse(a, null);

        assertThat(seller.status()).isEqualTo(AuctionStatus.VERIFICATION_PENDING);
        assertThat(seller.listingFeePaid()).isTrue();
        assertThat(seller.listingFeeAmt()).isEqualTo(100L);
        assertThat(seller.commissionRate()).isEqualTo(new BigDecimal("0.0500"));
        assertThat(seller.pendingVerification()).isNull();
    }

    @Test
    void toSellerResponse_includesBidSummaryFieldsZeroedWhenNoBids() {
        Auction a = buildAuction(AuctionStatus.DRAFT);
        a.setCurrentBid(0L);
        a.setBidCount(0);

        SellerAuctionResponse seller = mapper.toSellerResponse(a, null);

        assertThat(seller.currentHighBid()).isNull();
        assertThat(seller.bidderCount()).isEqualTo(0L);
    }

    @Test
    void toPublicResponse_active_includesBidSummary() {
        Auction a = buildAuction(AuctionStatus.ACTIVE);
        a.setCurrentBid(0L);
        a.setBidCount(0);

        PublicAuctionResponse dto = mapper.toPublicResponse(a);

        assertThat(dto.currentHighBid()).isNull();
        assertThat(dto.bidderCount()).isEqualTo(0L);
    }

    @Test
    void toPublicResponse_nonZeroBid_mapsCurrentBidToBigDecimalAndRealBidderCount() {
        Auction a = buildAuction(AuctionStatus.ACTIVE);
        a.setCurrentBid(500L);
        a.setBidCount(3);

        PublicAuctionResponse dto = mapper.toPublicResponse(a);

        assertThat(dto.currentHighBid()).isEqualByComparingTo(BigDecimal.valueOf(500));
        assertThat(dto.bidderCount()).isEqualTo(3L);
    }

    private Auction buildAuction(AuctionStatus status) {
        User seller = User.builder().id(42L).email("s@example.com").build();
        Parcel parcel = Parcel.builder()
                .id(100L).slParcelUuid(UUID.randomUUID())
                .regionName("Coniston").continentName("Sansara")
                .verified(true).build();
        return Auction.builder()
                .title("Test listing")
                .id(1L).seller(seller).parcel(parcel)
                .status(status)
                .verificationMethod(VerificationMethod.UUID_ENTRY)
                .startingBid(1000L).durationHours(168)
                .snipeProtect(false).snipeWindowMin(null)
                .listingFeePaid(false)
                .currentBid(0L).bidCount(0)
                .commissionRate(new BigDecimal("0.0500"))
                .agentFeeRate(new BigDecimal("0.0000"))
                .tags(new HashSet<>())
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();
    }
}
