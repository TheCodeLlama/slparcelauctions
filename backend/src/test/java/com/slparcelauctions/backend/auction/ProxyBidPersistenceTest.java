package com.slparcelauctions.backend.auction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
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
 * Persistence-level verification for {@link ProxyBid}. Confirms:
 * <ul>
 *   <li>Round-trip of every mapped column.</li>
 *   <li>The {@code proxy_bids_one_active_per_user} partial unique index
 *       rejects a second {@link ProxyBidStatus#ACTIVE} row for the same
 *       {@code (auction, user)} pair while allowing any number of
 *       {@code EXHAUSTED}/{@code CANCELLED} rows.</li>
 * </ul>
 *
 * <p>The partial unique index is created by
 * {@link com.slparcelauctions.backend.auction.config.ProxyBidPartialUniqueIndexInitializer} on
 * {@link org.springframework.boot.context.event.ApplicationReadyEvent} — so
 * loading the Spring context is sufficient to see it.
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "slpa.notifications.cleanup.enabled=false"
})
class ProxyBidPersistenceTest {

    @Autowired ProxyBidRepository proxyBidRepository;
    @Autowired AuctionRepository auctionRepository;
    @Autowired ParcelRepository parcelRepository;
    @Autowired UserRepository userRepository;

    @PersistenceContext EntityManager em;

    @Test
    @Transactional
    void save_roundTripsEveryField() {
        User seller = userRepository.save(newUser("seller"));
        User bidder = userRepository.save(newUser("bidder"));
        Parcel parcel = parcelRepository.save(newParcel());
        Auction auction = auctionRepository.save(newAuction(seller, parcel));

        ProxyBid saved = proxyBidRepository.save(ProxyBid.builder()
                .auction(auction)
                .bidder(bidder)
                .maxAmount(25_000L)
                .status(ProxyBidStatus.ACTIVE)
                .build());

        em.flush();
        em.clear();

        ProxyBid loaded = proxyBidRepository.findById(saved.getId()).orElseThrow();

        assertThat(loaded.getMaxAmount()).isEqualTo(25_000L);
        assertThat(loaded.getStatus()).isEqualTo(ProxyBidStatus.ACTIVE);
        assertThat(loaded.getAuction().getId()).isEqualTo(auction.getId());
        assertThat(loaded.getBidder().getId()).isEqualTo(bidder.getId());
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getUpdatedAt()).isNotNull();
    }

    /**
     * Exercises the partial unique index directly: non-active rows stack
     * freely, a second ACTIVE row is rejected. Cleans up after itself via
     * {@code deleteAll} on each repository — this test is intentionally NOT
     * wrapped in {@code @Transactional} so the DB actually commits the inserts
     * and the unique-index collision surfaces as
     * {@link DataIntegrityViolationException}.
     */
    @Test
    void partialUniqueIndexAllowsMultipleNonActiveButRejectsSecondActive() {
        User seller = userRepository.save(newUser("seller"));
        User bidder = userRepository.save(newUser("bidder"));
        Parcel parcel = parcelRepository.save(newParcel());
        Auction auction = auctionRepository.save(newAuction(seller, parcel));

        try {
            ProxyBid active = proxyBidRepository.saveAndFlush(ProxyBid.builder()
                    .auction(auction)
                    .bidder(bidder)
                    .maxAmount(10_000L)
                    .status(ProxyBidStatus.ACTIVE)
                    .build());

            ProxyBid exhausted = proxyBidRepository.saveAndFlush(ProxyBid.builder()
                    .auction(auction)
                    .bidder(bidder)
                    .maxAmount(9_000L)
                    .status(ProxyBidStatus.EXHAUSTED)
                    .build());

            ProxyBid cancelled = proxyBidRepository.saveAndFlush(ProxyBid.builder()
                    .auction(auction)
                    .bidder(bidder)
                    .maxAmount(8_000L)
                    .status(ProxyBidStatus.CANCELLED)
                    .build());

            assertThat(active.getId()).isNotNull();
            assertThat(exhausted.getId()).isNotNull();
            assertThat(cancelled.getId()).isNotNull();

            assertThatThrownBy(() -> proxyBidRepository.saveAndFlush(ProxyBid.builder()
                        .auction(auction)
                        .bidder(bidder)
                        .maxAmount(11_000L)
                        .status(ProxyBidStatus.ACTIVE)
                        .build()))
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            proxyBidRepository.deleteAll(
                    proxyBidRepository.findAll().stream()
                            .filter(p -> p.getAuction().getId().equals(auction.getId()))
                            .toList());
            auctionRepository.delete(auction);
            parcelRepository.delete(parcel);
            userRepository.delete(bidder);
            userRepository.delete(seller);
        }
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
                .regionName("ProxyTestRegion")
                .continentName("Sansara")
                .areaSqm(1024)
                .maturityRating("MODERATE")
                .verified(true)
                .verifiedAt(OffsetDateTime.now())
                .build();
    }

    private static Auction newAuction(User seller, Parcel parcel) {
        return Auction.builder()
                .title("Test listing")
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
