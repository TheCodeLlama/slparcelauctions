package com.slparcelauctions.backend.auction.fraud;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.VerificationMethod;
import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.parcel.ParcelRepository;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Persistence-level verification that {@link FraudFlag} round-trips cleanly
 * against the real Postgres dev database — specifically that the
 * {@code jsonb evidence_json} column is written and re-read as a typed
 * {@code Map<String, Object>} via Hibernate's JSON JDBC type.
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "slpa.notifications.cleanup.enabled=false"
})
@Transactional
class FraudFlagRepositoryTest {

    @Autowired FraudFlagRepository fraudFlagRepository;
    @Autowired ParcelRepository parcelRepository;
    @Autowired AuctionRepository auctionRepository;
    @Autowired UserRepository userRepository;

    @PersistenceContext EntityManager em;

    @Test
    void save_persistsWithJsonbEvidenceAndRoundTrips() {
        User seller = userRepository.save(User.builder()
                .email("fraud-seller-" + UUID.randomUUID() + "@example.com")
                .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                .displayName("Fraud Seller")
                .verified(false)
                .build());

        Parcel parcel = parcelRepository.save(Parcel.builder()
                .slParcelUuid(UUID.randomUUID())
                .ownerUuid(UUID.randomUUID())
                .ownerType("agent")
                .regionName("TestRegion")
                .areaSqm(1024)
                .maturityRating("MODERATE")
                .verified(true)
                .verifiedAt(OffsetDateTime.now())
                .build());

        Auction auction = auctionRepository.save(Auction.builder()
                .title("Test listing")
                .parcel(parcel)
                .seller(seller)
                .status(AuctionStatus.DRAFT)
                .verificationMethod(VerificationMethod.UUID_ENTRY)
                .startingBid(1000L)
                .durationHours(168)
                .snipeProtect(false)
                .listingFeePaid(false)
                .currentBid(0L)
                .bidCount(0)
                .commissionRate(new BigDecimal("0.05"))
                .agentFeeRate(BigDecimal.ZERO)
                .build());

        UUID expectedOwner = UUID.randomUUID();
        UUID detectedOwner = UUID.randomUUID();
        FraudFlag saved = fraudFlagRepository.save(FraudFlag.builder()
                .auction(auction)
                .parcel(parcel)
                .reason(FraudFlagReason.OWNERSHIP_CHANGED_TO_UNKNOWN)
                .detectedAt(OffsetDateTime.now())
                .evidenceJson(Map.of(
                        "expected_owner", expectedOwner.toString(),
                        "detected_owner", detectedOwner.toString()))
                .resolved(false)
                .build());

        em.flush();
        em.clear();

        FraudFlag loaded = fraudFlagRepository.findById(saved.getId()).orElseThrow();

        assertThat(loaded.getReason()).isEqualTo(FraudFlagReason.OWNERSHIP_CHANGED_TO_UNKNOWN);
        assertThat(loaded.isResolved()).isFalse();
        assertThat(loaded.getResolvedAt()).isNull();
        assertThat(loaded.getResolvedBy()).isNull();
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getDetectedAt()).isNotNull();
        assertThat(loaded.getEvidenceJson())
                .containsEntry("expected_owner", expectedOwner.toString())
                .containsEntry("detected_owner", detectedOwner.toString());
        assertThat(loaded.getAuction().getId()).isEqualTo(auction.getId());
        assertThat(loaded.getParcel().getId()).isEqualTo(parcel.getId());
    }

    @Test
    void findByAuctionId_returnsOnlyFlagsForThatAuction() {
        User seller = userRepository.save(User.builder()
                .email("fraud-seller-" + UUID.randomUUID() + "@example.com")
                .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                .displayName("Fraud Seller")
                .verified(false)
                .build());

        Parcel parcel = parcelRepository.save(Parcel.builder()
                .slParcelUuid(UUID.randomUUID())
                .ownerUuid(UUID.randomUUID())
                .ownerType("agent")
                .regionName("TestRegion")
                .areaSqm(1024)
                .maturityRating("MODERATE")
                .verified(true)
                .verifiedAt(OffsetDateTime.now())
                .build());

        Auction auctionA = auctionRepository.save(buildDraft(seller, parcel));
        Auction auctionB = auctionRepository.save(buildDraft(seller, parcel));

        fraudFlagRepository.save(FraudFlag.builder()
                .auction(auctionA).parcel(parcel)
                .reason(FraudFlagReason.WORLD_API_FAILURE_THRESHOLD)
                .detectedAt(OffsetDateTime.now())
                .resolved(false).build());
        fraudFlagRepository.save(FraudFlag.builder()
                .auction(auctionB).parcel(parcel)
                .reason(FraudFlagReason.PARCEL_DELETED_OR_MERGED)
                .detectedAt(OffsetDateTime.now())
                .resolved(false).build());

        em.flush();
        em.clear();

        assertThat(fraudFlagRepository.findByAuctionId(auctionA.getId()))
                .hasSize(1)
                .allSatisfy(f -> assertThat(f.getReason())
                        .isEqualTo(FraudFlagReason.WORLD_API_FAILURE_THRESHOLD));
        assertThat(fraudFlagRepository.findByAuctionId(auctionB.getId()))
                .hasSize(1)
                .allSatisfy(f -> assertThat(f.getReason())
                        .isEqualTo(FraudFlagReason.PARCEL_DELETED_OR_MERGED));
    }

    private Auction buildDraft(User seller, Parcel parcel) {
        return Auction.builder()
                .title("Test listing")
                .parcel(parcel)
                .seller(seller)
                .status(AuctionStatus.DRAFT)
                .verificationMethod(VerificationMethod.UUID_ENTRY)
                .startingBid(1000L)
                .durationHours(168)
                .snipeProtect(false)
                .listingFeePaid(false)
                .currentBid(0L)
                .bidCount(0)
                .commissionRate(new BigDecimal("0.05"))
                .agentFeeRate(BigDecimal.ZERO)
                .build();
    }
}
