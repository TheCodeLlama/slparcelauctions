package com.slparcelauctions.backend.auction.parcelscan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionParcelSnapshot;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.auction.parcelscan.dto.BotScanResultRequest;
import com.slparcelauctions.backend.bot.BotTask;
import com.slparcelauctions.backend.bot.BotTaskRepository;
import com.slparcelauctions.backend.bot.BotTaskService;
import com.slparcelauctions.backend.bot.BotTaskStatus;
import com.slparcelauctions.backend.bot.BotTaskType;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "slpa.notifications.cleanup.enabled=false",
        "slpa.notifications.sl-im.cleanup.enabled=false",
        "slpa.realty.invitation-expiry.enabled=false"
})
@Transactional
class ParcelScanServiceTest {

    @Autowired ParcelScanService parcelScanService;
    @Autowired AuctionParcelLayoutRepository layoutRepo;
    @Autowired AuctionParcelHeightMapRepository heightRepo;
    @Autowired BotTaskRepository botTaskRepo;
    @Autowired BotTaskService botTaskService;
    @Autowired AuctionRepository auctionRepo;
    @Autowired UserRepository userRepo;

    @PersistenceContext EntityManager em;

    // --- enqueueIfEligible ---

    @Test
    void enqueue_skipsWhenScanNotIncluded() {
        Auction auction = savedAuction(savedUser("skip-scan"));
        auction.setParcelScanIncluded(false);
        auctionRepo.save(auction);
        em.flush();

        parcelScanService.enqueueIfEligible(auction);
        em.flush();

        assertThat(botTaskRepo.existsPendingByAuctionIdAndType(
                auction.getId(), BotTaskType.SCAN_PARCEL)).isFalse();
    }

    @Test
    void enqueue_skipsWhenLayoutAlreadyExists() {
        Auction auction = savedAuction(savedUser("skip-layout"));

        layoutRepo.save(AuctionParcelLayout.builder()
                .auction(auction)
                .gridSize(64)
                .cellSizeMeters(4)
                .cells(new byte[512])
                .scannedAt(java.time.OffsetDateTime.now())
                .build());
        em.flush();

        parcelScanService.enqueueIfEligible(auction);
        em.flush();

        long count = botTaskRepo.findAll().stream()
                .filter(t -> t.getTaskType() == BotTaskType.SCAN_PARCEL
                        && t.getAuction().getId().equals(auction.getId()))
                .count();
        assertThat(count).isZero();
    }

    @Test
    void enqueue_skipsWhenPendingTaskAlreadyExists() {
        Auction auction = savedAuction(savedUser("skip-pending"));

        // First call creates the task
        parcelScanService.enqueueIfEligible(auction);
        em.flush();

        long countBefore = botTaskRepo.findAll().stream()
                .filter(t -> t.getTaskType() == BotTaskType.SCAN_PARCEL
                        && t.getAuction().getId().equals(auction.getId()))
                .count();
        assertThat(countBefore).isEqualTo(1);

        // Second call must be a no-op
        parcelScanService.enqueueIfEligible(auction);
        em.flush();

        long countAfter = botTaskRepo.findAll().stream()
                .filter(t -> t.getTaskType() == BotTaskType.SCAN_PARCEL
                        && t.getAuction().getId().equals(auction.getId()))
                .count();
        assertThat(countAfter).isEqualTo(1);
    }

    @Test
    void enqueue_proceedsAfterTerminalFailure() {
        Auction auction = savedAuction(savedUser("after-fail"));

        // Pre-insert a FAILED SCAN_PARCEL task
        BotTask failedTask = BotTask.builder()
                .taskType(BotTaskType.SCAN_PARCEL)
                .status(BotTaskStatus.FAILED)
                .auction(auction)
                .parcelUuid(auction.getSlParcelUuid())
                .regionName("Test Region")
                .sentinelPrice(0L)
                .build();
        botTaskRepo.save(failedTask);
        em.flush();

        // enqueue should still proceed
        parcelScanService.enqueueIfEligible(auction);
        em.flush();

        long count = botTaskRepo.findAll().stream()
                .filter(t -> t.getTaskType() == BotTaskType.SCAN_PARCEL
                        && t.getAuction().getId().equals(auction.getId())
                        && t.getStatus() == BotTaskStatus.PENDING)
                .count();
        assertThat(count).isEqualTo(1);
    }

    // --- applyScanResult ---

    @Test
    void apply_happyPath_persistsBothRastersAndCompletesTask() {
        Auction auction = savedAuction(savedUser("happy"));
        BotTask task = botTaskService.enqueueScanParcel(auction);
        em.flush();

        byte[] layoutCells = new byte[512];
        byte[] heightCells = new byte[4096];
        BotScanResultRequest req = new BotScanResultRequest(
                64, 4,
                Base64.getEncoder().encodeToString(layoutCells),
                22.0f, 0.5f,
                Base64.getEncoder().encodeToString(heightCells));

        parcelScanService.applyScanResult(task.getId(), req);
        em.flush();
        em.clear();

        assertThat(layoutRepo.existsByAuctionId(auction.getId())).isTrue();
        assertThat(heightRepo.existsByAuctionId(auction.getId())).isTrue();

        BotTask completed = botTaskRepo.findById(task.getId()).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(BotTaskStatus.COMPLETED);
        assertThat(completed.getCompletedAt()).isNotNull();
    }

    @Test
    void apply_layoutLengthMismatch_returns400() {
        Auction auction = savedAuction(savedUser("layout-len"));
        BotTask task = botTaskService.enqueueScanParcel(auction);
        em.flush();

        byte[] badLayout = new byte[256]; // should be 512
        byte[] heightCells = new byte[4096];
        BotScanResultRequest req = new BotScanResultRequest(
                64, 4,
                Base64.getEncoder().encodeToString(badLayout),
                22.0f, 0.5f,
                Base64.getEncoder().encodeToString(heightCells));

        assertThatThrownBy(() -> parcelScanService.applyScanResult(task.getId(), req))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void apply_heightLengthMismatch_returns400() {
        Auction auction = savedAuction(savedUser("height-len"));
        BotTask task = botTaskService.enqueueScanParcel(auction);
        em.flush();

        byte[] layoutCells = new byte[512];
        byte[] badHeight = new byte[1024]; // should be 4096
        BotScanResultRequest req = new BotScanResultRequest(
                64, 4,
                Base64.getEncoder().encodeToString(layoutCells),
                22.0f, 0.5f,
                Base64.getEncoder().encodeToString(badHeight));

        assertThatThrownBy(() -> parcelScanService.applyScanResult(task.getId(), req))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void apply_invalidBase64_returns400() {
        Auction auction = savedAuction(savedUser("bad-b64"));
        BotTask task = botTaskService.enqueueScanParcel(auction);
        em.flush();

        BotScanResultRequest req = new BotScanResultRequest(
                64, 4,
                "!!!not-base64",
                22.0f, 0.5f,
                Base64.getEncoder().encodeToString(new byte[4096]));

        assertThatThrownBy(() -> parcelScanService.applyScanResult(task.getId(), req))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void apply_nonFiniteBase_returns400() {
        Auction auction = savedAuction(savedUser("nan-base"));
        BotTask task = botTaskService.enqueueScanParcel(auction);
        em.flush();

        BotScanResultRequest req = new BotScanResultRequest(
                64, 4,
                Base64.getEncoder().encodeToString(new byte[512]),
                Float.NaN, 0.5f,
                Base64.getEncoder().encodeToString(new byte[4096]));

        assertThatThrownBy(() -> parcelScanService.applyScanResult(task.getId(), req))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void apply_nonPositiveStep_returns400() {
        Auction auction = savedAuction(savedUser("zero-step"));
        BotTask task = botTaskService.enqueueScanParcel(auction);
        em.flush();

        BotScanResultRequest req = new BotScanResultRequest(
                64, 4,
                Base64.getEncoder().encodeToString(new byte[512]),
                22.0f, 0f,
                Base64.getEncoder().encodeToString(new byte[4096]));

        assertThatThrownBy(() -> parcelScanService.applyScanResult(task.getId(), req))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void apply_replayAfterSuccess_returns409() {
        Auction auction = savedAuction(savedUser("replay"));
        BotTask task = botTaskService.enqueueScanParcel(auction);
        em.flush();

        BotScanResultRequest req = new BotScanResultRequest(
                64, 4,
                Base64.getEncoder().encodeToString(new byte[512]),
                22.0f, 0.5f,
                Base64.getEncoder().encodeToString(new byte[4096]));

        // First apply succeeds
        parcelScanService.applyScanResult(task.getId(), req);
        em.flush();

        // Second apply must return 409
        assertThatThrownBy(() -> parcelScanService.applyScanResult(task.getId(), req))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));

        // Row counts remain 1 each
        em.clear();
        assertThat(layoutRepo.existsByAuctionId(auction.getId())).isTrue();
        assertThat(heightRepo.existsByAuctionId(auction.getId())).isTrue();
    }

    @Test
    void apply_wrongTaskType_returns409() {
        Auction auction = savedAuction(savedUser("wrong-type"));

        // Build a VERIFY_SELL_TO task directly -- minimal required fields
        BotTask wrongTask = BotTask.builder()
                .taskType(BotTaskType.VERIFY_SELL_TO)
                .status(BotTaskStatus.PENDING)
                .auction(auction)
                .parcelUuid(auction.getSlParcelUuid())
                .sentinelPrice(0L)
                .build();
        wrongTask = botTaskRepo.save(wrongTask);
        em.flush();

        final long taskId = wrongTask.getId();
        BotScanResultRequest req = new BotScanResultRequest(
                64, 4,
                Base64.getEncoder().encodeToString(new byte[512]),
                22.0f, 0.5f,
                Base64.getEncoder().encodeToString(new byte[4096]));

        assertThatThrownBy(() -> parcelScanService.applyScanResult(taskId, req))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));
    }

    // --- fixtures ---

    private User savedUser(String label) {
        return userRepo.save(User.builder()
                .username("u-" + UUID.randomUUID().toString().substring(0, 8))
                .email(label + "-" + UUID.randomUUID() + "@example.com")
                .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                .displayName(label + " " + UUID.randomUUID())
                .verified(true)
                .slAvatarUuid(UUID.randomUUID())
                .build());
    }

    private Auction savedAuction(User seller) {
        UUID parcelUuid = UUID.randomUUID();
        Auction a = Auction.builder()
                .title("Scan test listing")
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
}
