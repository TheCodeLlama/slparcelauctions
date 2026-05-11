package com.slparcelauctions.backend.auction.search.suggest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
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
import com.slparcelauctions.backend.region.Region;
import com.slparcelauctions.backend.region.RegionRepository;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import jakarta.persistence.EntityManager;

@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "slpa.notifications.cleanup.enabled=false",
        "slpa.notifications.sl-im.cleanup.enabled=false",
    "slpa.realty.invitation-expiry.enabled=false"
})
@Transactional
class SearchSuggestRepositoryTest {

    @Autowired SearchSuggestRepository repo;
    @Autowired UserRepository userRepo;
    @Autowired RegionRepository regionRepo;
    @Autowired AuctionRepository auctionRepo;
    // Repository under test reads via JdbcTemplate, which bypasses
    // Hibernate's write-behind cache. We need an explicit flush
    // before each query so the inserts are visible to the JDBC view.
    @Autowired EntityManager em;

    private User seller;

    @BeforeEach
    void setUp() {
        seller = userRepo.save(User.builder()
                .username("u-" + UUID.randomUUID().toString().substring(0, 8))
                .email("seller-" + UUID.randomUUID() + "@ex.com")
                .passwordHash("x")
                .slAvatarUuid(UUID.randomUUID())
                .displayName("Seller")
                .verified(true).build());
    }

    @Test
    void matchesByTitle() {
        seedActive("Tula", "Generic Plot", "Premium Waterfront");
        seedActive("Luna", "Generic Plot", "Skybox parking");
        em.flush();
        List<SuggestListingDto> hits = repo.findListings("waterfront", 5);
        assertThat(hits).extracting(SuggestListingDto::title)
                .containsExactly("Premium Waterfront");
    }

    @Test
    void matchesByParcelName() {
        seedActive("Tula", "Beachfront retreat", "Generic Title");
        seedActive("Luna", "Industrial Lot", "Other Title");
        em.flush();
        List<SuggestListingDto> hits = repo.findListings("beachfront", 5);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).parcelName()).isEqualTo("Beachfront retreat");
    }

    @Test
    void matchesByRegionName() {
        seedActive("Tula", "PlotA", "Generic");
        seedActive("Luna", "PlotB", "Other");
        em.flush();
        List<SuggestListingDto> hits = repo.findListings("tula", 5);
        assertThat(hits).extracting(SuggestListingDto::regionName)
                .containsExactly("Tula");
    }

    @Test
    void cappedAtLimit() {
        for (int i = 0; i < 8; i++) {
            seedActive("Region" + i, "PlotX", "Waterfront " + i);
        }
        em.flush();
        List<SuggestListingDto> hits = repo.findListings("waterfront", 5);
        assertThat(hits).hasSize(5);
    }

    @Test
    void excludesNonActive() {
        Auction draft = seedActive("Tula", "x", "Waterfront Draft");
        draft.setStatus(AuctionStatus.DRAFT);
        auctionRepo.save(draft);
        em.flush();
        List<SuggestListingDto> hits = repo.findListings("waterfront", 5);
        assertThat(hits).isEmpty();
    }

    @Test
    void regionsExcludeRegionsWithoutActiveAuctions() {
        regionRepo.save(Region.builder()
                .slUuid(UUID.randomUUID()).name("EmptyRegion")
                .gridX(0.0).gridY(0.0).maturityRating("GENERAL").build());
        seedActive("Tula", "x", "Listed");
        em.flush();
        List<SuggestRegionDto> hits = repo.findRegions("region", 3);
        assertThat(hits).extracting(SuggestRegionDto::name)
                .doesNotContain("EmptyRegion");
    }

    @Test
    void regionsCount_reflectsActiveAuctions() {
        seedActive("Tula", "PlotA", "L1");
        seedActive("Tula", "PlotB", "L2");
        em.flush();
        List<SuggestRegionDto> hits = repo.findRegions("tula", 3);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).activeAuctionCount()).isEqualTo(2);
    }

    @Test
    void totalListings_countsAllMatches_notCappedAtLimit() {
        for (int i = 0; i < 8; i++) {
            seedActive("Region" + i, "PlotY", "Waterfront " + i);
        }
        em.flush();
        int total = repo.countListings("waterfront");
        assertThat(total).isEqualTo(8);
    }

    private Auction seedActive(String regionName, String parcelName, String title) {
        Region region = regionRepo.findByNameIgnoreCase(regionName)
                .orElseGet(() -> regionRepo.save(Region.builder()
                        .slUuid(UUID.randomUUID()).name(regionName)
                        .gridX(0.0).gridY(0.0)
                        .maturityRating("GENERAL").build()));
        UUID parcelUuid = UUID.randomUUID();
        Auction a = auctionRepo.save(Auction.builder()
                .slParcelUuid(parcelUuid)
                .seller(seller).title(title)
                .status(AuctionStatus.ACTIVE)
                .startingBid(1000L).currentBid(1000L)
                .endsAt(OffsetDateTime.now().plusDays(7))
                .durationHours(168)
                .snipeProtect(false)
                .verificationTier(VerificationTier.BOT)
                .build());
        a.setParcelSnapshot(AuctionParcelSnapshot.builder()
                .slParcelUuid(parcelUuid)
                .region(region)
                .regionName(regionName)
                .regionMaturityRating("GENERAL")
                .areaSqm(1024)
                .ownerType("agent")
                .ownerName("Owner")
                .parcelName(parcelName)
                .build());
        return auctionRepo.save(a);
    }
}
