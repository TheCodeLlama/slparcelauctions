package com.slparcelauctions.backend.auction.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.parcel.ParcelRepository;
import com.slparcelauctions.backend.parceltag.ParcelTag;
import com.slparcelauctions.backend.parceltag.ParcelTagRepository;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.testsupport.TestRegions;

/**
 * Integration coverage for {@link AuctionSearchPredicateBuilder} against a
 * live dev Postgres. Each test seeds disjoint fixtures (unique parcel UUIDs,
 * region names containing UUID suffixes, fresh sellers as needed) so the
 * filter under test can be asserted in isolation even though
 * {@code @Transactional} keeps the rows inside the test boundary.
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "slpa.notifications.cleanup.enabled=false",
        "slpa.notifications.sl-im.cleanup.enabled=false"
})
@Transactional
class AuctionSearchPredicateBuilderTest {

    @Autowired AuctionSearchPredicateBuilder builder;
    @Autowired AuctionRepository auctionRepo;
    @Autowired ParcelRepository parcelRepo;
    @Autowired UserRepository userRepo;
    @Autowired ParcelTagRepository tagRepo;

    private User seller;
    private ParcelTag beachfront;
    private ParcelTag roadside;

    @BeforeEach
    void seed() {
        seller = userRepo.save(User.builder()
                .email("seller-" + UUID.randomUUID() + "@example.com")
                .passwordHash("x")
                .slAvatarUuid(UUID.randomUUID())
                .displayName("seller")
                .verified(true)
                .build());

        beachfront = tagRepo.save(ParcelTag.builder()
                .code("beachfront-" + UUID.randomUUID())
                .label("Beachfront")
                .category("Waterfront")
                .sortOrder(1)
                .active(true)
                .build());
        roadside = tagRepo.save(ParcelTag.builder()
                .code("roadside-" + UUID.randomUUID())
                .label("Roadside")
                .category("Access")
                .sortOrder(1)
                .active(true)
                .build());
    }

    @Test
    void noFilters_returnsAllActiveAuctions() {
        Auction a1 = seedActive("Tula-" + UUID.randomUUID(), 1024, "GENERAL");
        Auction a2 = seedActive("Luna-" + UUID.randomUUID(), 2048, "MODERATE");

        AuctionSearchQuery q = AuctionSearchQueryBuilder.newBuilder().build();
        List<Auction> results = auctionRepo.findAll(builder.build(q),
                PageRequest.of(0, 100, Sort.by("id"))).getContent();

        assertThat(results).extracting(Auction::getId)
                .contains(a1.getId(), a2.getId());
        assertThat(results).allMatch(a -> a.getStatus() == AuctionStatus.ACTIVE);
    }

    @Test
    void regionFilter_caseInsensitive() {
        String region = "Tula-" + UUID.randomUUID();
        Auction match = seedActive(region, 1024, "GENERAL");
        Auction other = seedActive("Luna-" + UUID.randomUUID(), 1024, "GENERAL");

        AuctionSearchQuery q = AuctionSearchQueryBuilder.newBuilder()
                .region(region.toLowerCase())
                .build();
        List<Auction> results = auctionRepo.findAll(builder.build(q),
                PageRequest.of(0, 100, Sort.by("id"))).getContent();

        assertThat(results).extracting(Auction::getId)
                .contains(match.getId())
                .doesNotContain(other.getId());
    }

    @Test
    void areaFilter_minAndMax() {
        Auction tooSmall = seedActive("R1-" + UUID.randomUUID(), 500, "GENERAL");
        Auction inRange = seedActive("R2-" + UUID.randomUUID(), 1024, "GENERAL");
        Auction tooBig = seedActive("R3-" + UUID.randomUUID(), 4096, "GENERAL");

        AuctionSearchQuery q = AuctionSearchQueryBuilder.newBuilder()
                .minArea(1000).maxArea(2000)
                .build();
        List<Auction> results = auctionRepo.findAll(builder.build(q),
                PageRequest.of(0, 100, Sort.by("id"))).getContent();

        assertThat(results).extracting(Auction::getId)
                .contains(inRange.getId())
                .doesNotContain(tooSmall.getId(), tooBig.getId());
    }

    @Test
    void maturityFilter_multipleValues() {
        Auction g = seedActive("R1-" + UUID.randomUUID(), 1024, "GENERAL");
        Auction m = seedActive("R2-" + UUID.randomUUID(), 1024, "MODERATE");
        Auction a = seedActive("R3-" + UUID.randomUUID(), 1024, "ADULT");

        AuctionSearchQuery q = AuctionSearchQueryBuilder.newBuilder()
                .maturity(Set.of("GENERAL", "ADULT"))
                .build();
        List<Auction> results = auctionRepo.findAll(builder.build(q),
                PageRequest.of(0, 100, Sort.by("id"))).getContent();

        assertThat(results).extracting(Auction::getId)
                .contains(g.getId(), a.getId())
                .doesNotContain(m.getId());
    }

    @Test
    void tagsFilter_orLogic_matchesAny() {
        Auction beach = seedActiveWithTags(Set.of(beachfront));
        Auction road = seedActiveWithTags(Set.of(roadside));
        Auction neither = seedActive("R-" + UUID.randomUUID(), 1024, "GENERAL");

        AuctionSearchQuery q = AuctionSearchQueryBuilder.newBuilder()
                .tags(Set.of(beachfront, roadside))
                .build();
        List<Auction> results = auctionRepo.findAll(builder.build(q),
                PageRequest.of(0, 100, Sort.by("id"))).getContent();

        assertThat(results).extracting(Auction::getId)
                .contains(beach.getId(), road.getId())
                .doesNotContain(neither.getId());
    }

    @Test
    void tagsFilter_andLogic_requiresAll() {
        Auction both = seedActiveWithTags(Set.of(beachfront, roadside));
        Auction beachOnly = seedActiveWithTags(Set.of(beachfront));

        AuctionSearchQuery q = AuctionSearchQueryBuilder.newBuilder()
                .tags(Set.of(beachfront, roadside))
                .tagsMode(TagsMode.AND)
                .build();
        List<Auction> results = auctionRepo.findAll(builder.build(q),
                PageRequest.of(0, 100, Sort.by("id"))).getContent();

        assertThat(results).extracting(Auction::getId)
                .contains(both.getId())
                .doesNotContain(beachOnly.getId());
    }

    @Test
    void sellerIdFilter() {
        Auction mine = seedActive("R1-" + UUID.randomUUID(), 1024, "GENERAL");
        User otherSeller = userRepo.save(User.builder()
                .email("other-" + UUID.randomUUID() + "@x.com")
                .passwordHash("x")
                .slAvatarUuid(UUID.randomUUID())
                .displayName("other")
                .verified(true)
                .build());
        Auction theirs = seedActiveForSeller(otherSeller);

        AuctionSearchQuery q = AuctionSearchQueryBuilder.newBuilder()
                .sellerId(seller.getId())
                .build();
        List<Auction> results = auctionRepo.findAll(builder.build(q),
                PageRequest.of(0, 100, Sort.by("id"))).getContent();

        assertThat(results).extracting(Auction::getId)
                .contains(mine.getId())
                .doesNotContain(theirs.getId());
    }

    private Auction seedActive(String region, int areaSqm, String maturity) {
        Parcel p = parcelRepo.save(Parcel.builder()
                .region(TestRegions.mainland())
                .slParcelUuid(UUID.randomUUID())
                                .areaSqm(areaSqm)
                                .verified(true)
                .build());
        return auctionRepo.save(Auction.builder()
                .parcel(p)
                .seller(seller)
                .title("Test")
                .status(AuctionStatus.ACTIVE)
                .startingBid(1000L)
                .currentBid(1000L)
                .durationHours(168)
                .endsAt(OffsetDateTime.now().plusDays(7))
                .snipeProtect(false)
                .verificationTier(VerificationTier.BOT)
                .build());
    }

    private Auction seedActiveWithTags(Set<ParcelTag> tags) {
        Auction a = seedActive("R-" + UUID.randomUUID(), 1024, "GENERAL");
        a.setTags(new HashSet<>(tags));
        return auctionRepo.save(a);
    }

    private Auction seedActiveForSeller(User s) {
        Parcel p = parcelRepo.save(Parcel.builder()
                .region(TestRegions.mainland())
                .slParcelUuid(UUID.randomUUID())
                                .areaSqm(1024)
                                .verified(true)
                .build());
        return auctionRepo.save(Auction.builder()
                .parcel(p)
                .seller(s)
                .title("Test")
                .status(AuctionStatus.ACTIVE)
                .startingBid(1000L)
                .currentBid(1000L)
                .durationHours(168)
                .endsAt(OffsetDateTime.now().plusDays(7))
                .snipeProtect(false)
                .verificationTier(VerificationTier.BOT)
                .build());
    }
}
