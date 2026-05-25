package com.slparcelauctions.backend.auction.parcelscan;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Base64;
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
import com.slparcelauctions.backend.auction.parcelscan.dto.ParcelScanResponse;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "slpa.notifications.cleanup.enabled=false",
        "slpa.notifications.sl-im.cleanup.enabled=false",
        "slpa.realty.invitation-expiry.enabled=false"
})
@Transactional
class ParcelScanReadServiceTest {

    @Autowired ParcelScanReadService service;
    @Autowired AuctionRepository auctionRepo;
    @Autowired UserRepository userRepo;
    @Autowired AuctionParcelLayoutRepository layoutRepo;
    @Autowired AuctionParcelHeightMapRepository heightRepo;
    @Autowired AuctionParcelLandUseRepository landUseRepo;

    // --- findForAuction: land-use present ---

    @Test
    void findForAuction_IncludesLandUseCellsBase64_WhenPresent() {
        Auction auction = persistAuction(persistUser("landuse-present"));
        persistLayout(auction);
        persistHeight(auction);
        byte[] landUseCells = new byte[4096];
        landUseCells[0] = 1;
        landUseCells[100] = 4;
        persistLandUse(auction, landUseCells);

        ParcelScanResponse resp = service.findForAuction(auction.getPublicId()).orElseThrow();

        assertThat(resp.landUseCellsBase64()).isNotNull();
        byte[] decoded = Base64.getDecoder().decode(resp.landUseCellsBase64());
        assertThat(decoded).isEqualTo(landUseCells);
    }

    // --- findForAuction: land-use absent (pre-feature scan) ---

    @Test
    void findForAuction_LandUseCellsBase64IsNull_WhenLandUseRowMissing() {
        Auction auction = persistAuction(persistUser("landuse-absent"));
        persistLayout(auction);
        persistHeight(auction);
        // Deliberately NOT persisting land-use to simulate a pre-feature auction.

        ParcelScanResponse resp = service.findForAuction(auction.getPublicId()).orElseThrow();

        assertThat(resp.landUseCellsBase64()).isNull();
    }

    // --- fixtures ---

    private User persistUser(String label) {
        return userRepo.save(User.builder()
                .username("u-" + UUID.randomUUID().toString().substring(0, 8))
                .email(label + "-" + UUID.randomUUID() + "@example.com")
                .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                .displayName(label + " " + UUID.randomUUID())
                .verified(true)
                .slAvatarUuid(UUID.randomUUID())
                .build());
    }

    private Auction persistAuction(User seller) {
        UUID parcelUuid = UUID.randomUUID();
        Auction a = Auction.builder()
                .title("Read service test listing")
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
        return auctionRepo.save(a);
    }

    private void persistLayout(Auction auction) {
        layoutRepo.save(AuctionParcelLayout.builder()
                .auction(auction)
                .gridSize(64)
                .cellSizeMeters(4)
                .cells(new byte[512])
                .scannedAt(OffsetDateTime.now())
                .build());
    }

    private void persistHeight(Auction auction) {
        heightRepo.save(AuctionParcelHeightMap.builder()
                .auction(auction)
                .gridSize(64)
                .cellSizeMeters(4)
                .cells(new byte[4096])
                .baseMeters(22.0f)
                .stepMeters(0.5f)
                .scannedAt(OffsetDateTime.now())
                .build());
    }

    private void persistLandUse(Auction auction, byte[] cells) {
        landUseRepo.save(AuctionParcelLandUse.builder()
                .auction(auction)
                .gridSize(64)
                .cellSizeMeters(4)
                .cells(cells)
                .scannedAt(OffsetDateTime.now())
                .build());
    }
}
