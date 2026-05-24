package com.slparcelauctions.backend.auction.parcelscan;

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

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionParcelSnapshot;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Persistence-level round-trip verification for {@link AuctionParcelLayout}
 * and {@link AuctionParcelHeightMap}. Confirms:
 * <ul>
 *   <li>Every mapped column survives a flush/clear/reload cycle.</li>
 *   <li>{@code publicId} and {@code createdAt} are populated by the
 *       {@code @PrePersist} hook.</li>
 *   <li>{@code existsByAuctionId} returns true for present rows and
 *       false for absent ones.</li>
 *   <li>{@code findByAuctionId} locates the saved rows.</li>
 * </ul>
 *
 * <p>Mirrors the {@code @SpringBootTest} + {@code @Transactional} pattern
 * used by {@link com.slparcelauctions.backend.auction.BidPersistenceTest}.
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "slpa.notifications.cleanup.enabled=false",
        "slpa.notifications.sl-im.cleanup.enabled=false",
        "slpa.realty.invitation-expiry.enabled=false"
})
@Transactional
class AuctionParcelRasterPersistenceTest {

    @Autowired AuctionParcelLayoutRepository layoutRepository;
    @Autowired AuctionParcelHeightMapRepository heightMapRepository;
    @Autowired AuctionRepository auctionRepository;
    @Autowired UserRepository userRepository;

    @PersistenceContext EntityManager em;

    @Test
    void layout_roundTripsEveryField() {
        Auction auction = auctionRepository.save(newAuction(userRepository.save(newUser("seller-l"))));

        OffsetDateTime scannedAt = OffsetDateTime.now();
        AuctionParcelLayout saved = layoutRepository.save(AuctionParcelLayout.builder()
                .auction(auction)
                .gridSize(64)
                .cellSizeMeters(4)
                .cells(new byte[512])
                .scannedAt(scannedAt)
                .build());

        em.flush();
        em.clear();

        AuctionParcelLayout loaded = layoutRepository.findByAuctionId(auction.getId()).orElseThrow();

        assertThat(loaded.getCells()).hasSize(512);
        assertThat(loaded.getGridSize()).isEqualTo(64);
        assertThat(loaded.getCellSizeMeters()).isEqualTo(4);
        assertThat(loaded.getPublicId()).isNotNull();
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getUpdatedAt()).isNotNull();
        assertThat(loaded.getId()).isEqualTo(auction.getId());
    }

    @Test
    void heightMap_roundTripsEveryField() {
        Auction auction = auctionRepository.save(newAuction(userRepository.save(newUser("seller-h"))));

        OffsetDateTime scannedAt = OffsetDateTime.now();
        AuctionParcelHeightMap saved = heightMapRepository.save(AuctionParcelHeightMap.builder()
                .auction(auction)
                .gridSize(64)
                .cellSizeMeters(4)
                .baseMeters(22.0f)
                .stepMeters(0.5f)
                .cells(new byte[4096])
                .scannedAt(scannedAt)
                .build());

        em.flush();
        em.clear();

        AuctionParcelHeightMap loaded = heightMapRepository.findByAuctionId(auction.getId()).orElseThrow();

        assertThat(loaded.getCells()).hasSize(4096);
        assertThat(loaded.getGridSize()).isEqualTo(64);
        assertThat(loaded.getCellSizeMeters()).isEqualTo(4);
        assertThat(loaded.getBaseMeters()).isEqualTo(22.0f);
        assertThat(loaded.getStepMeters()).isEqualTo(0.5f);
        assertThat(loaded.getPublicId()).isNotNull();
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getUpdatedAt()).isNotNull();
        assertThat(loaded.getId()).isEqualTo(auction.getId());
    }

    @Test
    void existsByAuctionId_returnsTrueForPresentFalseForAbsent() {
        Auction auction = auctionRepository.save(newAuction(userRepository.save(newUser("seller-e"))));

        layoutRepository.save(AuctionParcelLayout.builder()
                .auction(auction)
                .gridSize(64)
                .cellSizeMeters(4)
                .cells(new byte[512])
                .scannedAt(OffsetDateTime.now())
                .build());

        heightMapRepository.save(AuctionParcelHeightMap.builder()
                .auction(auction)
                .gridSize(64)
                .cellSizeMeters(4)
                .baseMeters(0.0f)
                .stepMeters(1.0f)
                .cells(new byte[4096])
                .scannedAt(OffsetDateTime.now())
                .build());

        em.flush();

        assertThat(layoutRepository.existsByAuctionId(auction.getId())).isTrue();
        assertThat(heightMapRepository.existsByAuctionId(auction.getId())).isTrue();

        long absentId = Long.MAX_VALUE;
        assertThat(layoutRepository.existsByAuctionId(absentId)).isFalse();
        assertThat(heightMapRepository.existsByAuctionId(absentId)).isFalse();
    }

    private static User newUser(String label) {
        return User.builder()
                .username("u-" + UUID.randomUUID().toString().substring(0, 8))
                .email(label + "-" + UUID.randomUUID() + "@example.com")
                .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                .displayName(label + " " + UUID.randomUUID())
                .verified(true)
                .slAvatarUuid(UUID.randomUUID())
                .build();
    }

    private static Auction newAuction(User seller) {
        UUID parcelUuid = UUID.randomUUID();
        Auction a = Auction.builder()
                .title("Test listing")
                .slParcelUuid(parcelUuid)
                .seller(seller)
                .status(AuctionStatus.ACTIVE)
                .verificationTier(VerificationTier.SCRIPT)
                .startingBid(1_000L)
                .durationHours(168)
                .snipeProtect(false)
                .listingFeePaid(true)
                .currentBid(0L)
                .bidCount(0)
                .consecutiveWorldApiFailures(0)
                .commissionRate(new BigDecimal("0.05"))
                .build();
        a.setParcelSnapshot(AuctionParcelSnapshot.builder()
                .slParcelUuid(parcelUuid)
                .ownerUuid(UUID.randomUUID())
                .ownerType("agent")
                .parcelName("Test Parcel")
                .regionName("Test Region")
                .regionMaturityRating("GENERAL")
                .areaSqm(1024)
                .positionX(128.0).positionY(64.0).positionZ(22.0)
                .build());
        return a;
    }
}
