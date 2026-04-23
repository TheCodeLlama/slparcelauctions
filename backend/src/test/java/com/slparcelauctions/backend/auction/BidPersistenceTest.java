package com.slparcelauctions.backend.auction;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.parcel.ParcelRepository;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Persistence-level round-trip verification for {@link Bid}. Confirms every
 * mapped column survives a flush/clear/reload cycle and that the nullable
 * columns ({@code proxyBidId}, {@code snipeExtensionMinutes},
 * {@code newEndsAt}, {@code ipAddress}) come back null when unset.
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = "auth.cleanup.enabled=false")
@Transactional
class BidPersistenceTest {

    @Autowired BidRepository bidRepository;
    @Autowired AuctionRepository auctionRepository;
    @Autowired ParcelRepository parcelRepository;
    @Autowired UserRepository userRepository;

    @PersistenceContext EntityManager em;

    @Test
    void save_roundTripsEveryField() {
        User seller = userRepository.save(newUser("seller"));
        User bidder = userRepository.save(newUser("bidder"));
        Parcel parcel = parcelRepository.save(newParcel());
        Auction auction = auctionRepository.save(newAuction(seller, parcel));

        OffsetDateTime newEndsAt = OffsetDateTime.now().plusMinutes(15);
        Bid saved = bidRepository.save(Bid.builder()
                .auction(auction)
                .bidder(bidder)
                .amount(12_500L)
                .bidType(BidType.PROXY_AUTO)
                .proxyBidId(42L)
                .snipeExtensionMinutes(5)
                .newEndsAt(newEndsAt)
                .ipAddress("203.0.113.17")
                .build());

        em.flush();
        em.clear();

        Bid loaded = bidRepository.findById(saved.getId()).orElseThrow();

        assertThat(loaded.getAmount()).isEqualTo(12_500L);
        assertThat(loaded.getBidType()).isEqualTo(BidType.PROXY_AUTO);
        assertThat(loaded.getProxyBidId()).isEqualTo(42L);
        assertThat(loaded.getSnipeExtensionMinutes()).isEqualTo(5);
        assertThat(loaded.getNewEndsAt()).isNotNull();
        assertThat(loaded.getIpAddress()).isEqualTo("203.0.113.17");
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getAuction().getId()).isEqualTo(auction.getId());
        assertThat(loaded.getBidder().getId()).isEqualTo(bidder.getId());
    }

    @Test
    void save_nullableFieldsRoundTripAsNull() {
        User seller = userRepository.save(newUser("seller"));
        User bidder = userRepository.save(newUser("bidder"));
        Parcel parcel = parcelRepository.save(newParcel());
        Auction auction = auctionRepository.save(newAuction(seller, parcel));

        Bid saved = bidRepository.save(Bid.builder()
                .auction(auction)
                .bidder(bidder)
                .amount(1_000L)
                .bidType(BidType.MANUAL)
                .build());

        em.flush();
        em.clear();

        Bid loaded = bidRepository.findById(saved.getId()).orElseThrow();

        assertThat(loaded.getBidType()).isEqualTo(BidType.MANUAL);
        assertThat(loaded.getAmount()).isEqualTo(1_000L);
        assertThat(loaded.getProxyBidId()).isNull();
        assertThat(loaded.getSnipeExtensionMinutes()).isNull();
        assertThat(loaded.getNewEndsAt()).isNull();
        assertThat(loaded.getIpAddress()).isNull();
        assertThat(loaded.getCreatedAt()).isNotNull();
    }

    private static User newUser(String label) {
        return User.builder()
                .email(label + "-" + UUID.randomUUID() + "@example.com")
                .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                .displayName(label + " " + UUID.randomUUID())
                .verified(true)
                .slAvatarUuid(UUID.randomUUID())
                .build();
    }

    private static Parcel newParcel() {
        return Parcel.builder()
                .slParcelUuid(UUID.randomUUID())
                .ownerUuid(UUID.randomUUID())
                .ownerType("agent")
                .regionName("BidTestRegion")
                .continentName("Sansara")
                .areaSqm(1024)
                .maturityRating("MODERATE")
                .verified(true)
                .verifiedAt(OffsetDateTime.now())
                .build();
    }

    private static Auction newAuction(User seller, Parcel parcel) {
        return Auction.builder()
                .parcel(parcel)
                .seller(seller)
                .status(AuctionStatus.ACTIVE)
                .verificationMethod(VerificationMethod.UUID_ENTRY)
                .verificationTier(VerificationTier.SCRIPT)
                .startingBid(1_000L)
                .durationHours(168)
                .snipeProtect(false)
                .listingFeePaid(true)
                .currentBid(0L)
                .bidCount(0)
                .consecutiveWorldApiFailures(0)
                .commissionRate(new BigDecimal("0.05"))
                .agentFeeRate(BigDecimal.ZERO)
                .build();
    }
}
