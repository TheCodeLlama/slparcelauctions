package com.slparcelauctions.backend.auction.featured;

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
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.Bid;
import com.slparcelauctions.backend.auction.BidRepository;
import com.slparcelauctions.backend.auction.BidType;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.parcel.ParcelRepository;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Coverage for the three native-SQL queries that back the featured rows.
 * Each test seeds its own ACTIVE auction set and verifies row count +
 * ordering. The {@code mostActive} test back-dates synthetic bids via a
 * native UPDATE because {@link Bid#getCreatedAt()} is
 * {@code @CreationTimestamp}-managed and the builder value would be
 * overwritten on insert.
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = "auth.cleanup.enabled=false")
@Transactional
class FeaturedRepositoryIntegrationTest {

    @Autowired FeaturedRepository featuredRepo;
    @Autowired AuctionRepository auctionRepo;
    @Autowired ParcelRepository parcelRepo;
    @Autowired UserRepository userRepo;
    @Autowired BidRepository bidRepo;

    @PersistenceContext EntityManager em;

    private User seller;

    @BeforeEach
    void seed() {
        seller = userRepo.save(User.builder()
                .email("seller-" + UUID.randomUUID() + "@ex.com")
                .passwordHash("x").slAvatarUuid(UUID.randomUUID())
                .displayName("Seller").verified(true).build());
    }

    @Test
    void endingSoon_ordersByEndsAtAsc_limit6() {
        for (int i = 0; i < 8; i++) {
            seedActive(OffsetDateTime.now().plusHours(i + 1),
                    OffsetDateTime.now().minusDays(1));
        }
        List<Auction> result = featuredRepo.endingSoon();
        assertThat(result).hasSize(6);
        for (int i = 0; i < 5; i++) {
            assertThat(result.get(i).getEndsAt())
                    .isBeforeOrEqualTo(result.get(i + 1).getEndsAt());
        }
    }

    @Test
    void justListed_ordersByStartsAtDesc_limit6() {
        for (int i = 0; i < 8; i++) {
            seedActive(OffsetDateTime.now().plusDays(7),
                    OffsetDateTime.now().minusHours(i));
        }
        List<Auction> result = featuredRepo.justListed();
        assertThat(result).hasSize(6);
        for (int i = 0; i < 5; i++) {
            assertThat(result.get(i).getStartsAt())
                    .isAfterOrEqualTo(result.get(i + 1).getStartsAt());
        }
    }

    @Test
    void mostActive_ordersBy6hBidCountDesc_limit6() {
        Auction hot = seedActive(OffsetDateTime.now().plusDays(7),
                OffsetDateTime.now().minusDays(3));
        Auction cold = seedActive(OffsetDateTime.now().plusDays(7),
                OffsetDateTime.now().minusDays(3));

        // 5 recent bids on hot, 1 on cold. All bids land within the last
        // 6 hours naturally — minutes-ago offsets are well inside the
        // INTERVAL window the query uses, so back-dating isn't needed
        // for the ordering assertion. We still flush so the COUNT(*)
        // subquery sees the rows.
        for (int i = 0; i < 5; i++) {
            seedBid(hot, 1000L + i);
        }
        seedBid(cold, 1000L);
        em.flush();

        List<Auction> result = featuredRepo.mostActive();
        assertThat(result.get(0).getId()).isEqualTo(hot.getId());
    }

    private Auction seedActive(OffsetDateTime endsAt, OffsetDateTime startsAt) {
        Parcel p = parcelRepo.save(Parcel.builder()
                .slParcelUuid(UUID.randomUUID())
                .regionName("R-" + UUID.randomUUID())
                .areaSqm(1024).maturityRating("GENERAL").verified(true).build());
        return auctionRepo.save(Auction.builder()
                .parcel(p).seller(seller).title("Test")
                .status(AuctionStatus.ACTIVE)
                .startingBid(1000L).currentBid(1000L)
                .endsAt(endsAt).startsAt(startsAt)
                .durationHours(168)
                .snipeProtect(false)
                .verificationTier(VerificationTier.BOT)
                .build());
    }

    private Bid seedBid(Auction auction, long amount) {
        return bidRepo.save(Bid.builder()
                .auction(auction).bidder(seller)
                .amount(amount).bidType(BidType.MANUAL)
                .build());
    }
}
