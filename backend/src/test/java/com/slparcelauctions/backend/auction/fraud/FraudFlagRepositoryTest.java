package com.slparcelauctions.backend.auction.fraud;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.VerificationMethod;
import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.parcel.ParcelRepository;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.testsupport.TestRegions;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Persistence-level verification of {@link FraudFlag} and
 * {@link FraudFlagRepository}. The {@code @Transactional}-annotated legacy
 * tests (pre-Task 4) roll back automatically. The new count-method tests use
 * explicit {@code @BeforeEach}/{@code @AfterEach} with
 * {@link TransactionTemplate} so they exercise the repository outside the
 * test transaction and see committed rows.
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
    "auth.cleanup.enabled=false",
    "slpa.auction-end.enabled=false",
    "slpa.ownership-monitor.enabled=false",
    "slpa.escrow.ownership-monitor-job.enabled=false",
    "slpa.escrow.timeout-job.enabled=false",
    "slpa.escrow.command-dispatcher-job.enabled=false",
    "slpa.review.scheduler.enabled=false",
    "slpa.notifications.cleanup.enabled=false",
    "slpa.notifications.sl-im.cleanup.enabled=false"
})
class FraudFlagRepositoryTest {

    @Autowired FraudFlagRepository fraudFlagRepository;
    @Autowired ParcelRepository parcelRepository;
    @Autowired AuctionRepository auctionRepository;
    @Autowired UserRepository userRepository;
    @Autowired PlatformTransactionManager txManager;
    @Autowired DataSource dataSource;

    @PersistenceContext EntityManager em;

    // -----------------------------------------------------------------------
    // State for the count-method tests (BeforeEach / AfterEach)
    // -----------------------------------------------------------------------

    private Long seedAuctionId, seedParcelId, seedUserId;
    private FraudFlag flagA, flagB;

    @BeforeEach
    void seedCountData() {
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            User user = userRepository.save(User.builder()
                .email("seed-" + UUID.randomUUID() + "@x.com")
                .passwordHash("x")
                .slAvatarUuid(UUID.randomUUID())
                .build());
            seedUserId = user.getId();

            Parcel parcel = parcelRepository.save(Parcel.builder()
                .region(TestRegions.mainland())
                .slParcelUuid(UUID.randomUUID())
                                .ownerUuid(user.getSlAvatarUuid())
                .areaSqm(512)
                .build());
            seedParcelId = parcel.getId();

            Auction auction = auctionRepository.save(Auction.builder()
                .seller(user).parcel(parcel).title("Test")
                .status(AuctionStatus.SUSPENDED)
                .startingBid(1L)
                .durationHours(24)
                .endsAt(OffsetDateTime.now().plusHours(1))
                .build());
            seedAuctionId = auction.getId();

            flagA = fraudFlagRepository.save(FraudFlag.builder()
                .auction(auction).parcel(parcel)
                .reason(FraudFlagReason.OWNERSHIP_CHANGED_TO_UNKNOWN)
                .detectedAt(OffsetDateTime.now())
                .resolved(false)
                .evidenceJson(Map.of())
                .build());

            flagB = fraudFlagRepository.save(FraudFlag.builder()
                .auction(auction).parcel(parcel)
                .reason(FraudFlagReason.PARCEL_DELETED_OR_MERGED)
                .detectedAt(OffsetDateTime.now())
                .resolved(false)
                .evidenceJson(Map.of())
                .build());
        });
    }

    @AfterEach
    void cleanupCountData() throws Exception {
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            if (seedAuctionId != null) {
                fraudFlagRepository.deleteAll(fraudFlagRepository.findByAuctionId(seedAuctionId));
                auctionRepository.findById(seedAuctionId).ifPresent(auctionRepository::delete);
            }
            if (seedParcelId != null) parcelRepository.findById(seedParcelId).ifPresent(parcelRepository::delete);
        });
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (var st = conn.createStatement()) {
                if (seedUserId != null) {
                    st.execute("DELETE FROM notification WHERE user_id = " + seedUserId);
                    st.execute("DELETE FROM refresh_tokens WHERE user_id = " + seedUserId);
                    st.execute("DELETE FROM users WHERE id = " + seedUserId);
                }
            }
        }
        seedUserId = seedAuctionId = seedParcelId = null;
    }

    // -----------------------------------------------------------------------
    // Legacy tests (transactional — roll back automatically)
    // -----------------------------------------------------------------------

    @Test
    @Transactional
    void save_persistsWithJsonbEvidenceAndRoundTrips() {
        User seller = userRepository.save(User.builder()
                .email("fraud-seller-" + UUID.randomUUID() + "@example.com")
                .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                .displayName("Fraud Seller")
                .verified(false)
                .build());

        Parcel parcel = parcelRepository.save(Parcel.builder()
                .region(TestRegions.mainland())
                .slParcelUuid(UUID.randomUUID())
                .ownerUuid(UUID.randomUUID())
                .ownerType("agent")
                                .areaSqm(1024)
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
    @Transactional
    void findByAuctionId_returnsOnlyFlagsForThatAuction() {
        User seller = userRepository.save(User.builder()
                .email("fraud-seller-" + UUID.randomUUID() + "@example.com")
                .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                .displayName("Fraud Seller")
                .verified(false)
                .build());

        Parcel parcel = parcelRepository.save(Parcel.builder()
                .region(TestRegions.mainland())
                .slParcelUuid(UUID.randomUUID())
                .ownerUuid(UUID.randomUUID())
                .ownerType("agent")
                                .areaSqm(1024)
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

    // -----------------------------------------------------------------------
    // New Task 4 count-method tests
    // -----------------------------------------------------------------------

    @Test
    void countByResolved_falseCountsOpenFlags() {
        long open = fraudFlagRepository.countByResolved(false);
        assertThat(open).isGreaterThanOrEqualTo(2L);
    }

    @Test
    void countByAuctionIdAndResolvedFalseAndIdNot_returnsSiblingOpenCount() {
        long siblings = fraudFlagRepository.countByAuctionIdAndResolvedFalseAndIdNot(
                seedAuctionId, flagA.getId());
        assertThat(siblings).isEqualTo(1L);
    }

    @Test
    void countByAuctionIdAndResolvedFalseAndIdNot_excludesResolved() {
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            flagB.setResolved(true);
            flagB.setResolvedAt(OffsetDateTime.now());
            flagB.setAdminNotes("dismissed");
            fraudFlagRepository.save(flagB);
        });

        long siblings = fraudFlagRepository.countByAuctionIdAndResolvedFalseAndIdNot(
                seedAuctionId, flagA.getId());
        assertThat(siblings).isZero();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

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
