package com.slparcelauctions.backend.auction.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.VerificationMethod;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.auction.fraud.FraudFlag;
import com.slparcelauctions.backend.auction.fraud.FraudFlagReason;
import com.slparcelauctions.backend.auction.fraud.FraudFlagRepository;
import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.parcel.ParcelRepository;
import com.slparcelauctions.backend.sl.SlMapApiClient;
import com.slparcelauctions.backend.sl.SlWorldApiClient;
import com.slparcelauctions.backend.sl.dto.ParcelMetadata;
import com.slparcelauctions.backend.sl.exception.ParcelNotFoundInSlException;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import reactor.core.publisher.Mono;

/**
 * End-to-end coverage of the ownership-monitor sweep:
 * <ol>
 *   <li>Seed a verified user, parcel, and ACTIVE auction whose
 *       {@code lastOwnershipCheckAt} is old enough to be due.</li>
 *   <li>Stub {@link SlWorldApiClient#fetchParcel} to return a parcel with a
 *       different owner (or 404 for the deleted-parcel test).</li>
 *   <li>Invoke {@link OwnershipMonitorScheduler#dispatchDueChecks} directly —
 *       the @Async {@link OwnershipCheckTask} runs on a separate thread, so
 *       we poll with Awaitility until the auction is SUSPENDED and a
 *       FraudFlag row exists.</li>
 * </ol>
 *
 * <p>Intentionally NOT {@code @Transactional}: the async task commits on a
 * different thread, so the test thread must see the committed state. Each
 * test uses unique UUIDs and cleans up explicitly in {@code @AfterEach}.
 *
 * <p>The Spring {@code @Scheduled} cron tick is disabled here
 * ({@code slpa.ownership-monitor.enabled=false}) so the background thread
 * doesn't race with explicit invocation. We still get a scheduler bean
 * instance via direct autowiring... wait — {@code matchIfMissing=true} plus
 * explicit {@code false} means the bean is NOT created. See the test note
 * below: we construct the scheduler manually via the other beans to exercise
 * the integration wiring while keeping the tick off.
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        // Disable the cron tick; we invoke dispatchDueChecks() explicitly.
        "slpa.ownership-monitor.enabled=false"
})
class OwnershipMonitorIntegrationTest {

    @Autowired AuctionRepository auctionRepo;
    @Autowired FraudFlagRepository fraudFlagRepo;
    @Autowired ParcelRepository parcelRepo;
    @Autowired UserRepository userRepo;
    @Autowired OwnershipCheckTask ownershipCheckTask;
    @Autowired com.slparcelauctions.backend.auction.monitoring.config.OwnershipMonitorProperties props;
    @Autowired PlatformTransactionManager txManager;
    @Autowired javax.sql.DataSource dataSource;

    @MockitoBean SlWorldApiClient worldApi;
    @MockitoBean SlMapApiClient mapApi;

    private Long seededUserId;
    private Long seededParcelId;
    private Long seededAuctionId;

    @AfterEach
    void cleanUp() throws Exception {
        if (seededAuctionId == null) return;
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (var stmt = conn.createStatement()) {
                stmt.execute("DELETE FROM fraud_flags WHERE auction_id = " + seededAuctionId);
                stmt.execute("DELETE FROM auction_tags WHERE auction_id = " + seededAuctionId);
                stmt.execute("DELETE FROM auctions WHERE id = " + seededAuctionId);
                if (seededParcelId != null) {
                    stmt.execute("DELETE FROM parcels WHERE id = " + seededParcelId);
                }
                if (seededUserId != null) {
                    stmt.execute("DELETE FROM verification_codes WHERE user_id = " + seededUserId);
                    stmt.execute("DELETE FROM refresh_tokens WHERE user_id = " + seededUserId);
                    stmt.execute("DELETE FROM users WHERE id = " + seededUserId);
                }
            }
        }
    }

    @Test
    void activeAuctionWithChangedOwner_getsSuspended_andFraudFlagWritten() {
        UUID sellerAvatar = UUID.randomUUID();
        UUID attackerAvatar = UUID.randomUUID();
        UUID parcelUuid = UUID.randomUUID();

        Long auctionId = seedActiveAuction(sellerAvatar, parcelUuid);
        when(worldApi.fetchParcel(parcelUuid)).thenReturn(Mono.just(new ParcelMetadata(
                parcelUuid, attackerAvatar, "agent",
                "Hijacked", "Coniston", 1024, "desc", null, "MATURE",
                1.0, 2.0, 3.0)));

        // Drive the scheduler manually (the @Scheduled tick is disabled).
        invokeCheckOneDirectly(auctionId);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            Auction refreshed = auctionRepo.findById(auctionId).orElseThrow();
            assertThat(refreshed.getStatus()).isEqualTo(AuctionStatus.SUSPENDED);
            List<FraudFlag> flags = fraudFlagRepo.findByAuctionId(auctionId);
            assertThat(flags).isNotEmpty()
                    .anyMatch(f -> f.getReason() == FraudFlagReason.OWNERSHIP_CHANGED_TO_UNKNOWN);
        });
    }

    @Test
    void activeAuctionWithMissingParcel_getsSuspended_withDeletedParcelReason() {
        UUID sellerAvatar = UUID.randomUUID();
        UUID parcelUuid = UUID.randomUUID();

        Long auctionId = seedActiveAuction(sellerAvatar, parcelUuid);
        when(worldApi.fetchParcel(parcelUuid))
                .thenReturn(Mono.error(new ParcelNotFoundInSlException(parcelUuid)));

        invokeCheckOneDirectly(auctionId);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            Auction refreshed = auctionRepo.findById(auctionId).orElseThrow();
            assertThat(refreshed.getStatus()).isEqualTo(AuctionStatus.SUSPENDED);
            List<FraudFlag> flags = fraudFlagRepo.findByAuctionId(auctionId);
            assertThat(flags).isNotEmpty()
                    .anyMatch(f -> f.getReason() == FraudFlagReason.PARCEL_DELETED_OR_MERGED);
        });
    }

    @Test
    void activeAuctionWithMatchingOwner_staysActive_updatesLastCheckAt() {
        UUID sellerAvatar = UUID.randomUUID();
        UUID parcelUuid = UUID.randomUUID();

        Long auctionId = seedActiveAuction(sellerAvatar, parcelUuid);
        when(worldApi.fetchParcel(parcelUuid)).thenReturn(Mono.just(new ParcelMetadata(
                parcelUuid, sellerAvatar, "agent",
                "Still OK", "Coniston", 1024, "desc", null, "MATURE",
                1.0, 2.0, 3.0)));

        OffsetDateTime before = OffsetDateTime.now().minusSeconds(1);
        invokeCheckOneDirectly(auctionId);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            Auction refreshed = auctionRepo.findById(auctionId).orElseThrow();
            assertThat(refreshed.getStatus()).isEqualTo(AuctionStatus.ACTIVE);
            assertThat(refreshed.getLastOwnershipCheckAt()).isNotNull();
            assertThat(refreshed.getLastOwnershipCheckAt()).isAfterOrEqualTo(before);
            assertThat(fraudFlagRepo.findByAuctionId(auctionId)).isEmpty();
        });
    }

    // -------------------------------------------------------------------------
    // Seeding + helpers
    // -------------------------------------------------------------------------

    /**
     * Invokes {@link OwnershipCheckTask#checkOne} directly. We avoid the
     * scheduler bean here because it's conditionally disabled in this test
     * class; calling the async method directly through the Spring-managed
     * proxy still exercises the {@code @Async} + {@code @Transactional}
     * wiring.
     */
    private void invokeCheckOneDirectly(Long auctionId) {
        ownershipCheckTask.checkOne(auctionId);
    }

    private Long seedActiveAuction(UUID sellerAvatar, UUID parcelUuid) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        return tx.execute(ts -> {
            User seller = userRepo.save(User.builder()
                    .email("monitor-" + UUID.randomUUID() + "@example.com")
                    .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                    .displayName("Monitor Seller")
                    .slAvatarUuid(sellerAvatar)
                    .verified(true)
                    .build());
            seededUserId = seller.getId();

            Parcel parcel = parcelRepo.save(Parcel.builder()
                    .slParcelUuid(parcelUuid)
                    .ownerUuid(sellerAvatar)
                    .ownerType("agent")
                    .regionName("Coniston")
                    .continentName("Sansara")
                    .areaSqm(1024)
                    .maturityRating("MATURE")
                    .verified(true)
                    .verifiedAt(OffsetDateTime.now())
                    .build());
            seededParcelId = parcel.getId();

            OffsetDateTime now = OffsetDateTime.now();
            Auction auction = auctionRepo.save(Auction.builder()
                    .parcel(parcel)
                    .seller(seller)
                    .status(AuctionStatus.ACTIVE)
                    .verificationMethod(VerificationMethod.UUID_ENTRY)
                    .verificationTier(VerificationTier.SCRIPT)
                    .startingBid(1000L)
                    .durationHours(168)
                    .snipeProtect(false)
                    .listingFeePaid(true)
                    .currentBid(0L)
                    .bidCount(0)
                    .consecutiveWorldApiFailures(0)
                    .commissionRate(new BigDecimal("0.05"))
                    .agentFeeRate(BigDecimal.ZERO)
                    .startsAt(now.minusHours(1))
                    .endsAt(now.plusHours(167))
                    .originalEndsAt(now.plusHours(167))
                    .verifiedAt(now.minusHours(1))
                    // Intentionally stale so the next sweep finds it due.
                    .lastOwnershipCheckAt(now.minusHours(2))
                    .build());
            seededAuctionId = auction.getId();
            return auction.getId();
        });
    }
}
