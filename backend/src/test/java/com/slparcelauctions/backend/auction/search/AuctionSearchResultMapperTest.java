package com.slparcelauctions.backend.auction.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionEndOutcome;
import com.slparcelauctions.backend.auction.AuctionParcelSnapshot;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.user.User;

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
    void primaryPhotoUrls_nullWhenNoPhotoProvided() {
        Auction a = auction(4L, 500L, null);
        AuctionSearchResultDto dto = mapOne(a);
        assertThat(dto.primaryPhotoLightUrl()).isNull();
        assertThat(dto.primaryPhotoDarkUrl()).isNull();
    }

    @Test
    void primaryPhotoUrls_dualVariant_returnsBothLightAndDark() {
        Auction a = auction(5L, 500L, null);
        AuctionSearchResultDto dto = mapper.toDto(a, Set.of(),
                new PrimaryPhotoUrls(
                        "/api/v1/photos/p5?variant=light",
                        "/api/v1/photos/p5?variant=dark"),
                null);
        assertThat(dto.primaryPhotoLightUrl()).isEqualTo("/api/v1/photos/p5?variant=light");
        assertThat(dto.primaryPhotoDarkUrl()).isEqualTo("/api/v1/photos/p5?variant=dark");
    }

    @Test
    void primaryPhotoUrls_singleVariant_returnsLightWithNullDark() {
        Auction a = auction(11L, 500L, null);
        AuctionSearchResultDto dto = mapper.toDto(a, Set.of(),
                new PrimaryPhotoUrls("/api/v1/photos/p11?variant=light", null),
                null);
        assertThat(dto.primaryPhotoLightUrl()).isEqualTo("/api/v1/photos/p11?variant=light");
        assertThat(dto.primaryPhotoDarkUrl()).isNull();
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
        a.setStatus(AuctionStatus.COMPLETED);
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
                .email("seller-" + id + "@example.com").username("u-" + java.util.UUID.randomUUID().toString().substring(0, 8))
                .passwordHash("x")
                .displayName("seller")
                .slAvatarUuid(UUID.randomUUID())
                .avgSellerRating(new BigDecimal("4.5"))
                .totalSellerReviews(5)
                .build();
        UUID parcelUuid = UUID.randomUUID();
        Auction a = Auction.builder()
                .id(id)
                .title("Test")
                .status(AuctionStatus.ACTIVE)
                .slParcelUuid(parcelUuid)
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
        a.setParcelSnapshot(AuctionParcelSnapshot.builder()
                .slParcelUuid(parcelUuid)
                .regionName("Coniston")
                .regionMaturityRating("MODERATE")
                .areaSqm(1024)
                .positionX(80.0).positionY(104.0).positionZ(89.0)
                .build());
        return a;
    }
}
