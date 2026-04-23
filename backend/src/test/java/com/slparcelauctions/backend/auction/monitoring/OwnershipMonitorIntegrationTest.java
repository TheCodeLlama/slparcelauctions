package com.slparcelauctions.backend.auction.monitoring;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
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
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * End-to-end coverage of the ownership-monitor sweep using a real World API
 * stub (WireMock, not a {@link org.springframework.test.context.bean.override.mockito.MockitoBean}):
 * <ol>
 *   <li>Seed a verified user, parcel, and ACTIVE auction whose
 *       {@code lastOwnershipCheckAt} is old enough to be due.</li>
 *   <li>Stub the World API via WireMock (ok / mismatch / 404 / repeated 504
 *       depending on the scenario).</li>
 *   <li>Invoke {@link OwnershipMonitorScheduler#dispatchDueChecks} — the
 *       scheduler queries the repository and hands each due id to the
 *       {@link OwnershipCheckTask}, which runs on Spring's async executor.
 *       We poll with Awaitility until the expected outcome is committed.</li>
 * </ol>
 *
 * <p>The {@code @Scheduled} tick is pushed out to PT24H so the background
 * thread can't race with our explicit {@code dispatchDueChecks()} call; the
 * scheduler bean itself stays enabled so we can autowire it. The test is
 * intentionally NOT {@code @Transactional}: the async task commits on another
 * thread and the test thread must see the committed state. Each test uses
 * unique UUIDs and cleans up explicitly in {@code @AfterEach}.
 *
 * <p>World API client retry + timeout knobs are tightened so the timeout
 * scenario exhausts quickly (3 attempts with 25ms backoff against a 504 stub).
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        // Keep the scheduler bean enabled so we can autowire it, but push the
        // @Scheduled tick far enough out that it cannot race with our explicit
        // dispatchDueChecks() invocation during the test window.
        "slpa.ownership-monitor.enabled=true",
        "slpa.ownership-monitor.scheduler-frequency=PT24H",
        "slpa.ownership-monitor.check-interval-minutes=30",
        // Fast retry/timeout for the World API timeout scenario — 3 attempts
        // with 25ms backoff against a 504 stub surfaces as
        // ExternalApiTimeoutException in well under a second.
        "slpa.world-api.retry-attempts=2",
        "slpa.world-api.retry-backoff-ms=25",
        "slpa.world-api.timeout-ms=2000"
})
class OwnershipMonitorIntegrationTest {

    private static WireMockServer wireMock;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    @DynamicPropertySource
    static void overrideWorldApiBaseUrl(DynamicPropertyRegistry registry) {
        registry.add("slpa.world-api.base-url",
                () -> "http://localhost:" + wireMock.port());
    }

    @Autowired AuctionRepository auctionRepo;
    @Autowired FraudFlagRepository fraudFlagRepo;
    @Autowired ParcelRepository parcelRepo;
    @Autowired UserRepository userRepo;
    @Autowired OwnershipMonitorScheduler scheduler;
    @Autowired PlatformTransactionManager txManager;
    @Autowired javax.sql.DataSource dataSource;

    private Long seededUserId;
    private Long seededParcelId;
    private Long seededAuctionId;

    @AfterEach
    void cleanUp() throws Exception {
        wireMock.resetAll();
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
        seededAuctionId = null;
        seededParcelId = null;
        seededUserId = null;
    }

    @Test
    void activeAuctionWithChangedOwner_getsSuspended_andFraudFlagWritten() {
        UUID sellerAvatar = UUID.randomUUID();
        UUID attackerAvatar = UUID.randomUUID();
        UUID parcelUuid = UUID.randomUUID();

        Long auctionId = seedActiveAuction(sellerAvatar, parcelUuid);
        stubWorldApiOwner(parcelUuid, attackerAvatar, "agent", "Hijacked");

        scheduler.dispatchDueChecks();

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
        wireMock.stubFor(get(urlPathMatching("/place/.*"))
                .willReturn(aResponse().withStatus(404)));

        scheduler.dispatchDueChecks();

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            Auction refreshed = auctionRepo.findById(auctionId).orElseThrow();
            assertThat(refreshed.getStatus()).isEqualTo(AuctionStatus.SUSPENDED);
            List<FraudFlag> flags = fraudFlagRepo.findByAuctionId(auctionId);
            assertThat(flags).isNotEmpty()
                    .anyMatch(f -> f.getReason() == FraudFlagReason.PARCEL_DELETED_OR_MERGED);
        });
    }

    @Test
    void activeAuctionWithMatchingOwner_staysActive_updatesLastCheckAt_resetsFailureCounter() {
        UUID sellerAvatar = UUID.randomUUID();
        UUID parcelUuid = UUID.randomUUID();

        // Pre-seed with a non-zero failure counter to prove it's reset to 0 on
        // a successful owner-match check.
        Long auctionId = seedActiveAuction(sellerAvatar, parcelUuid, 3);
        stubWorldApiOwner(parcelUuid, sellerAvatar, "agent", "Still OK");

        OffsetDateTime before = OffsetDateTime.now().minusSeconds(1);
        scheduler.dispatchDueChecks();

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            Auction refreshed = auctionRepo.findById(auctionId).orElseThrow();
            assertThat(refreshed.getStatus()).isEqualTo(AuctionStatus.ACTIVE);
            assertThat(refreshed.getLastOwnershipCheckAt()).isNotNull();
            assertThat(refreshed.getLastOwnershipCheckAt()).isAfterOrEqualTo(before);
            assertThat(refreshed.getConsecutiveWorldApiFailures()).isZero();
            assertThat(fraudFlagRepo.findByAuctionId(auctionId)).isEmpty();
        });
    }

    @Test
    void worldApiTimeout_staysActive_incrementsFailureCounter() {
        UUID sellerAvatar = UUID.randomUUID();
        UUID parcelUuid = UUID.randomUUID();

        Long auctionId = seedActiveAuction(sellerAvatar, parcelUuid);
        // 504 Gateway Timeout repeatedly — the SlWorldApiClient treats 5xx as
        // transient and retries, so exhausting retries surfaces as
        // ExternalApiTimeoutException and the task increments the failure
        // counter without suspending.
        wireMock.stubFor(get(urlPathMatching("/place/.*"))
                .willReturn(aResponse().withStatus(504)));

        OffsetDateTime before = OffsetDateTime.now().minusSeconds(1);
        scheduler.dispatchDueChecks();

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            Auction refreshed = auctionRepo.findById(auctionId).orElseThrow();
            assertThat(refreshed.getStatus()).isEqualTo(AuctionStatus.ACTIVE);
            assertThat(refreshed.getConsecutiveWorldApiFailures()).isEqualTo(1);
            // Timestamp must be stamped even on failure — otherwise the next
            // sweep would re-pick this row immediately (hot loop).
            assertThat(refreshed.getLastOwnershipCheckAt()).isNotNull();
            assertThat(refreshed.getLastOwnershipCheckAt()).isAfterOrEqualTo(before);
            assertThat(fraudFlagRepo.findByAuctionId(auctionId)).isEmpty();
        });
    }

    // -------------------------------------------------------------------------
    // Seeding + helpers
    // -------------------------------------------------------------------------

    private void stubWorldApiOwner(UUID parcelUuid, UUID ownerUuid, String ownerType, String title) {
        String html = """
                <html><head>
                <meta property="og:title" content="%s">
                <meta property="og:description" content="desc">
                <meta property="og:image" content="http://example.com/snap.jpg">
                <meta name="secondlife:region" content="Coniston">
                <meta name="secondlife:parcelid" content="%s">
                <meta name="ownerid" content="%s">
                <meta name="ownertype" content="%s">
                <meta name="area" content="1024">
                <meta name="maturityrating" content="Mature">
                </head><body></body></html>
                """.formatted(title, parcelUuid, ownerUuid, ownerType);
        wireMock.stubFor(get(urlPathMatching("/place/.*"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withBody(html)));
    }

    private Long seedActiveAuction(UUID sellerAvatar, UUID parcelUuid) {
        return seedActiveAuction(sellerAvatar, parcelUuid, 0);
    }

    private Long seedActiveAuction(UUID sellerAvatar, UUID parcelUuid, int consecutiveFailures) {
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
                    .maturityRating("MODERATE")
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
                    .consecutiveWorldApiFailures(consecutiveFailures)
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
