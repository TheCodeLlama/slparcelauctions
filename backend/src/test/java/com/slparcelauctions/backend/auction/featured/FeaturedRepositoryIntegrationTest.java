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
import com.slparcelauctions.backend.auction.AuctionParcelSnapshot;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Coverage for the three native-SQL queries that back the homepage rails.
 * Each test seeds its own ACTIVE auction set and verifies row count +
 * ordering. {@code @Transactional} rolls each test back so seeded fixtures
 * don't bleed across tests.
 *
 * <p>Bid timestamps are back-dated via native UPDATE because
 * {@link Bid#getCreatedAt()} is {@code @CreationTimestamp}-managed and any
 * builder value would be overwritten on insert. The {@code em.flush()}
 * after each insert is load-bearing: the JdbcTemplate-style scalar
 * subqueries inside the rail queries read from the JDBC layer directly,
 * not from the JPA write-behind cache.
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "slpa.notifications.cleanup.enabled=false",
        "slpa.notifications.sl-im.cleanup.enabled=false"
})
@Transactional
class FeaturedRepositoryIntegrationTest {

    @Autowired FeaturedRepository featuredRepo;
    @Autowired AuctionRepository auctionRepo;
    @Autowired UserRepository userRepo;
    @Autowired BidRepository bidRepo;

    @PersistenceContext EntityManager em;

    private User seller;

    @BeforeEach
    void seed() {
        seller = userRepo.save(User.builder().username("u-" + UUID.randomUUID().toString().substring(0, 8))
                .email("seller-" + UUID.randomUUID() + "@ex.com")
                .passwordHash("x").slAvatarUuid(UUID.randomUUID())
                .displayName("Seller").verified(true).build());
    }

    // ---------- featured() ----------

    @Test
    void featured_zeroCurated_returnsTopFourByCurrentBidDesc() {
        Auction a1 = seedActive(plusDays(7), minusDays(1)); setCurrentBid(a1, 100L);
        Auction a2 = seedActive(plusDays(7), minusDays(1)); setCurrentBid(a2, 500L);
        Auction a3 = seedActive(plusDays(7), minusDays(1)); setCurrentBid(a3, 300L);
        Auction a4 = seedActive(plusDays(7), minusDays(1)); setCurrentBid(a4, 200L);
        Auction a5 = seedActive(plusDays(7), minusDays(1)); setCurrentBid(a5, 400L);
        em.flush();

        List<Auction> result = featuredRepo.featured();

        assertThat(result).hasSize(4);
        assertThat(result.get(0).getId()).isEqualTo(a2.getId()); // 500
        assertThat(result.get(1).getId()).isEqualTo(a5.getId()); // 400
        assertThat(result.get(2).getId()).isEqualTo(a3.getId()); // 300
        assertThat(result.get(3).getId()).isEqualTo(a4.getId()); // 200
    }

    @Test
    void featured_twoCurated_returnsCuratedThenFill() {
        Auction c1 = seedActive(plusHours(2), minusDays(1)); setFeatured(c1, null); setCurrentBid(c1, 50L);
        Auction c2 = seedActive(plusHours(5), minusDays(1)); setFeatured(c2, null); setCurrentBid(c2, 60L);
        Auction f1 = seedActive(plusDays(7), minusDays(1)); setCurrentBid(f1, 1000L);
        Auction f2 = seedActive(plusDays(7), minusDays(1)); setCurrentBid(f2, 800L);
        Auction f3 = seedActive(plusDays(7), minusDays(1)); setCurrentBid(f3, 900L);
        em.flush();

        List<Auction> result = featuredRepo.featured();

        assertThat(result).hasSize(4);
        assertThat(result.get(0).getId()).isEqualTo(c1.getId());
        assertThat(result.get(1).getId()).isEqualTo(c2.getId());
        assertThat(result.get(2).getId()).isEqualTo(f1.getId()); // 1000
        assertThat(result.get(3).getId()).isEqualTo(f3.getId()); // 900
    }

    @Test
    void featured_sixCurated_returnsSoonestFour() {
        Auction[] rows = new Auction[6];
        for (int i = 0; i < 6; i++) {
            rows[i] = seedActive(plusHours(i + 1), minusDays(1));
            setFeatured(rows[i], null);
        }
        em.flush();

        List<Auction> result = featuredRepo.featured();

        assertThat(result).hasSize(4);
        assertThat(result).extracting(Auction::getId)
                .containsExactly(rows[0].getId(), rows[1].getId(), rows[2].getId(), rows[3].getId());
    }

    @Test
    void featured_pastFeaturedUntilExcludedFromCuratedBucket() {
        // Expired featured auction must not appear in the curated band — i.e.
        // it must not pre-empt the slot a non-flagged higher-bid row would own.
        // We saturate the fill candidates with high-bid actives so the expired
        // row would never make the fill cut on its own merits.
        Auction expired = seedActive(plusDays(7), minusDays(1));
        setFeatured(expired, OffsetDateTime.now().minusHours(1));
        setCurrentBid(expired, 1L);
        Auction live = seedActive(plusHours(2), minusDays(1));
        setFeatured(live, OffsetDateTime.now().plusHours(1));
        for (int i = 0; i < 4; i++) {
            Auction filler = seedActive(plusDays(7), minusDays(1));
            setCurrentBid(filler, 10_000L + i);
        }
        em.flush();

        List<Auction> result = featuredRepo.featured();

        assertThat(result).hasSize(4);
        assertThat(result.get(0).getId()).isEqualTo(live.getId()); // curated
        assertThat(result).extracting(Auction::getId).doesNotContain(expired.getId());
    }

    @Test
    void featured_includesNullFeaturedUntil() {
        Auction permanent = seedActive(plusDays(7), minusDays(1));
        setFeatured(permanent, null);
        em.flush();

        List<Auction> result = featuredRepo.featured();

        assertThat(result).extracting(Auction::getId).contains(permanent.getId());
    }

    @Test
    void featured_excludesNonActiveStatuses() {
        Auction draft = seedActive(plusDays(7), minusDays(1));
        setFeatured(draft, null);
        em.createNativeQuery("UPDATE auctions SET status = 'CANCELLED' WHERE id = :id")
                .setParameter("id", draft.getId()).executeUpdate();
        em.flush();

        List<Auction> result = featuredRepo.featured();

        assertThat(result).extracting(Auction::getId).doesNotContain(draft.getId());
    }

    // ---------- endingSoon() ----------

    @Test
    void endingSoon_ordersByEndsAtAsc_limit6() {
        for (int i = 0; i < 8; i++) {
            seedActive(plusHours(i + 1), minusDays(1));
        }
        em.flush();

        List<Auction> result = featuredRepo.endingSoon();

        assertThat(result).hasSize(6);
        for (int i = 0; i < 5; i++) {
            assertThat(result.get(i).getEndsAt())
                    .isBeforeOrEqualTo(result.get(i + 1).getEndsAt());
        }
    }

    // ---------- trending() ----------

    @Test
    void trending_scoresBidsTwoSavesOne_over24h() {
        Auction hot = seedActive(plusDays(7), minusDays(2));
        Auction warm = seedActive(plusDays(7), minusDays(2));
        Auction cold = seedActive(plusDays(7), minusDays(2));

        seedRecentBids(hot, 3);   // 6 + 0 = 6
        seedRecentSaves(hot, 1);  // 6 + 1 = 7
        seedRecentBids(warm, 1);  // 2 + 0 = 2
        seedRecentSaves(warm, 4); // 2 + 4 = 6
        // cold: 0 + 0 = 0
        em.flush();

        List<Auction> result = featuredRepo.trending();

        int hotIdx  = indexOf(result, hot.getId());
        int warmIdx = indexOf(result, warm.getId());
        int coldIdx = indexOf(result, cold.getId());
        assertThat(hotIdx).isLessThan(warmIdx);
        assertThat(warmIdx).isLessThan(coldIdx);
    }

    @Test
    void trending_excludesEventsOutside24h() {
        Auction noisyOld = seedActive(plusDays(7), minusDays(2));
        Auction silentRecent = seedActive(plusDays(7), minusDays(2));

        seedBidAt(noisyOld, OffsetDateTime.now().minusDays(3));     // outside 24h
        seedSaveAt(silentRecent, OffsetDateTime.now().minusHours(1)); // inside
        em.flush();

        List<Auction> result = featuredRepo.trending();

        int recentIdx = indexOf(result, silentRecent.getId());
        int oldIdx    = indexOf(result, noisyOld.getId());
        assertThat(recentIdx).isLessThan(oldIdx);
    }

    // ---------- helpers ----------

    private static OffsetDateTime plusHours(int h) { return OffsetDateTime.now().plusHours(h); }
    private static OffsetDateTime plusDays(int d)  { return OffsetDateTime.now().plusDays(d); }
    private static OffsetDateTime minusDays(int d) { return OffsetDateTime.now().minusDays(d); }

    private void setFeatured(Auction a, OffsetDateTime until) {
        em.createNativeQuery(
                "UPDATE auctions SET is_featured = TRUE, featured_until = :u WHERE id = :id")
                .setParameter("u", until)
                .setParameter("id", a.getId())
                .executeUpdate();
    }

    private void setCurrentBid(Auction a, long amount) {
        em.createNativeQuery("UPDATE auctions SET current_bid = :b WHERE id = :id")
                .setParameter("b", amount)
                .setParameter("id", a.getId())
                .executeUpdate();
    }

    private void seedRecentBids(Auction a, int n) {
        for (int i = 0; i < n; i++) seedBidAt(a, OffsetDateTime.now().minusMinutes(5 + i));
    }

    private void seedBidAt(Auction a, OffsetDateTime when) {
        Bid bid = bidRepo.save(Bid.builder()
                .auction(a).bidder(seller)
                .amount(100L).bidType(BidType.MANUAL)
                .build());
        em.flush();
        em.createNativeQuery("UPDATE bids SET created_at = :t WHERE id = :id")
                .setParameter("t", when)
                .setParameter("id", bid.getId())
                .executeUpdate();
    }

    private void seedRecentSaves(Auction a, int n) {
        for (int i = 0; i < n; i++) seedSaveAt(a, OffsetDateTime.now().minusMinutes(5 + i));
    }

    private void seedSaveAt(Auction a, OffsetDateTime when) {
        User saver = userRepo.save(User.builder()
                .username("u-" + UUID.randomUUID().toString().substring(0, 8))
                .email("saver-" + UUID.randomUUID() + "@ex.com")
                .passwordHash("x").slAvatarUuid(UUID.randomUUID())
                .displayName("S").verified(true).build());
        em.flush();
        em.createNativeQuery(
                "INSERT INTO saved_auctions (public_id, version, created_at, updated_at, user_id, auction_id, saved_at) " +
                "VALUES (gen_random_uuid(), 0, NOW(), NOW(), :u, :a, :t)")
                .setParameter("u", saver.getId())
                .setParameter("a", a.getId())
                .setParameter("t", when)
                .executeUpdate();
    }

    private static int indexOf(List<Auction> rows, Long id) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).getId().equals(id)) return i;
        }
        return -1;
    }

    private Auction seedActive(OffsetDateTime endsAt, OffsetDateTime startsAt) {
        UUID parcelUuid = UUID.randomUUID();
        Auction auction = auctionRepo.save(Auction.builder()
                .slParcelUuid(parcelUuid).seller(seller).title("Test")
                .status(AuctionStatus.ACTIVE)
                .startingBid(1000L).currentBid(1000L)
                .endsAt(endsAt).startsAt(startsAt)
                .durationHours(168)
                .snipeProtect(false)
                .consecutiveWorldApiFailures(0)
                .verificationTier(VerificationTier.BOT)
                .build());
        auction.setParcelSnapshot(AuctionParcelSnapshot.builder()
                .slParcelUuid(parcelUuid)
                .ownerUuid(seller.getSlAvatarUuid())
                .ownerType("agent")
                .parcelName("Featured Test Parcel")
                .regionName("Test Region")
                .regionMaturityRating("GENERAL")
                .areaSqm(1024)
                .positionX(128.0).positionY(64.0).positionZ(22.0)
                .build());
        return auctionRepo.save(auction);
    }
}
