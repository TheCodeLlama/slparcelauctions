package com.slparcelauctions.backend.auction.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionEndOutcome;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.testsupport.TestRegions;

class AuctionSearchResultMapperTest {

    private final AuctionSearchResultMapper mapper = new AuctionSearchResultMapper();

    @Test
    void reserveMet_null_reserve_isTrue() {
        Auction a = auction(1L, 500L, null);
        AuctionSearchResultDto dto = mapOne(a);
        assertThat(dto.reserveMet()).isTrue();
    }

    @Test
    void reserveMet_bidBelowReserve_isFalse() {
        Auction a = auction(2L, 500L, 1000L);
        AuctionSearchResultDto dto = mapOne(a);
        assertThat(dto.reserveMet()).isFalse();
    }

    @Test
    void reserveMet_bidAtOrAboveReserve_isTrue() {
        Auction a = auction(3L, 1000L, 1000L);
        AuctionSearchResultDto dto = mapOne(a);
        assertThat(dto.reserveMet()).isTrue();
    }

    @Test
    void primaryPhotoUrl_fallsBackToParcelSnapshot_whenNoSellerPhotos() {
        Auction a = auction(4L, 500L, null);
        a.getParcel().setSnapshotUrl("/api/parcels/99/snapshot");
        AuctionSearchResultDto dto = mapOne(a);
        assertThat(dto.primaryPhotoUrl()).isEqualTo("/api/parcels/99/snapshot");
    }

    @Test
    void primaryPhotoUrl_usesFirstSellerPhoto_whenAvailable() {
        Auction a = auction(5L, 500L, null);
        AuctionSearchResultDto dto = mapper.toDto(a, Set.of(),
                "/api/v1/auctions/5/photos/42/bytes", null);
        assertThat(dto.primaryPhotoUrl()).isEqualTo("/api/v1/auctions/5/photos/42/bytes");
    }

    @Test
    void sellerAverageRating_populated_fromUser() {
        Auction a = auction(6L, 500L, null);
        a.getSeller().setAvgSellerRating(new BigDecimal("4.82"));
        a.getSeller().setTotalSellerReviews(12);
        AuctionSearchResultDto dto = mapOne(a);
        assertThat(dto.seller().averageRating()).isEqualByComparingTo("4.82");
        assertThat(dto.seller().reviewCount()).isEqualTo(12);
    }

    @Test
    void distanceRegions_populated_whenProvided() {
        Auction a = auction(7L, 500L, null);
        AuctionSearchResultDto dto = mapper.toDto(a, Set.of(), null,
                new BigDecimal("3.2"));
        assertThat(dto.distanceRegions()).isEqualByComparingTo("3.2");
    }

    @Test
    void distanceRegions_null_whenNotProvided() {
        AuctionSearchResultDto dto = mapOne(auction(8L, 500L, null));
        assertThat(dto.distanceRegions()).isNull();
    }

    @Test
    void endOutcome_null_forActiveAuction() {
        AuctionSearchResultDto dto = mapOne(auction(9L, 500L, null));
        assertThat(dto.endOutcome()).isNull();
    }

    @Test
    void endOutcome_populated_whenSetOnAuction() {
        Auction a = auction(10L, 500L, null);
        a.setStatus(AuctionStatus.ENDED);
        a.setEndOutcome(AuctionEndOutcome.SOLD);
        AuctionSearchResultDto dto = mapOne(a);
        assertThat(dto.endOutcome()).isEqualTo(AuctionEndOutcome.SOLD);
    }

    private AuctionSearchResultDto mapOne(Auction a) {
        return mapper.toDto(a, Set.of(), null, null);
    }

    private Auction auction(long id, long currentBid, Long reserve) {
        User seller = User.builder()
                .id(42L)
                .email("seller-" + id + "@example.com")
                .passwordHash("x")
                .displayName("seller")
                .slAvatarUuid(UUID.randomUUID())
                .avgSellerRating(new BigDecimal("4.5"))
                .totalSellerReviews(5)
                .build();
        Parcel p = Parcel.builder()
                .region(TestRegions.mainland())
                .id(99L)
                .slParcelUuid(UUID.randomUUID())
                                .areaSqm(1024)
                                                .positionX(80.0).positionY(104.0).positionZ(89.0)
                .build();
        return Auction.builder()
                .id(id)
                .title("Test")
                .status(AuctionStatus.ACTIVE)
                .parcel(p)
                .seller(seller)
                .startingBid(500L)
                .currentBid(currentBid)
                .reservePrice(reserve)
                .buyNowPrice(null)
                .bidCount(3)
                .endsAt(OffsetDateTime.now().plusDays(1))
                .snipeProtect(false)
                .verificationTier(VerificationTier.BOT)
                .durationHours(168)
                .build();
    }
}
