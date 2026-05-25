package com.slparcelauctions.backend.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionParcelSnapshot;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.auction.parcelscan.AuctionParcelHeightMapRepository;
import com.slparcelauctions.backend.auction.parcelscan.AuctionParcelLayoutRepository;
import com.slparcelauctions.backend.auction.parcelscan.dto.BotScanResultRequest;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Integration test for {@code POST /api/v1/bot/tasks/{id}/scan-result}.
 *
 * <p>Mirrors the auth-header pattern from {@link BotTaskControllerAuthIntegrationTest}:
 * bot endpoints require {@code Authorization: Bearer <secret>} matching
 * {@code slpa.bot.shared-secret} from {@code application-dev.yml}.
 *
 * <p>No {@code @Transactional} -- each MockMvc request commits its own
 * transaction, so auto-rollback does not apply. Each test creates data with
 * random UUIDs/emails so rows do not collide across the suite run.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "slpa.notifications.cleanup.enabled=false",
        "slpa.notifications.sl-im.cleanup.enabled=false",
        "slpa.realty.invitation-expiry.enabled=false"
})
class BotTaskControllerScanResultTest {

    /**
     * Matches {@code slpa.bot.shared-secret} in {@code application-dev.yml}.
     * Intentionally inlined as a literal: the test proves the wiring reads
     * this specific config value.
     */
    private static final String DEV_BOT_SECRET = "dev-bot-shared-secret";

    @Autowired MockMvc mvc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    @Autowired UserRepository userRepo;
    @Autowired AuctionRepository auctionRepo;
    @Autowired BotTaskRepository botTaskRepo;
    @Autowired BotTaskService botTaskService;
    @Autowired AuctionParcelLayoutRepository layoutRepo;
    @Autowired AuctionParcelHeightMapRepository heightRepo;

    // --- happy path ---

    @Test
    void scanResult_happyPath_returns200AndPersistsBothRows() throws Exception {
        Auction auction = savedAuction(savedUser("happy"));
        BotTask task = botTaskService.enqueueScanParcel(auction);

        BotScanResultRequest body = validRequest();

        mvc.perform(post("/api/v1/bot/tasks/" + task.getId() + "/scan-result")
                        .header("Authorization", "Bearer " + DEV_BOT_SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk());

        assertThat(layoutRepo.existsByAuctionId(auction.getId())).isTrue();
        assertThat(heightRepo.existsByAuctionId(auction.getId())).isTrue();

        BotTask completed = botTaskRepo.findById(task.getId()).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(BotTaskStatus.COMPLETED);
        assertThat(completed.getCompletedAt()).isNotNull();
    }

    // --- 409 cases ---

    @Test
    void scanResult_nonScanParcelTask_returns409() throws Exception {
        Auction auction = savedAuction(savedUser("wrong-type"));

        BotTask wrongTask = BotTask.builder()
                .taskType(BotTaskType.VERIFY_SELL_TO)
                .status(BotTaskStatus.PENDING)
                .auction(auction)
                .parcelUuid(auction.getSlParcelUuid())
                .sentinelPrice(0L)
                .build();
        wrongTask = botTaskRepo.save(wrongTask);

        mvc.perform(post("/api/v1/bot/tasks/" + wrongTask.getId() + "/scan-result")
                        .header("Authorization", "Bearer " + DEV_BOT_SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isConflict());
    }

    @Test
    void scanResult_completedTask_returns409() throws Exception {
        Auction auction = savedAuction(savedUser("completed"));
        BotTask task = botTaskService.enqueueScanParcel(auction);

        // Force-complete the task directly via the repository
        task.setStatus(BotTaskStatus.COMPLETED);
        botTaskRepo.save(task);

        mvc.perform(post("/api/v1/bot/tasks/" + task.getId() + "/scan-result")
                        .header("Authorization", "Bearer " + DEV_BOT_SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isConflict());
    }

    // --- 400 cases ---

    @Test
    void scanResult_layoutLengthMismatch_returns400() throws Exception {
        Auction auction = savedAuction(savedUser("layout-len"));
        BotTask task = botTaskService.enqueueScanParcel(auction);

        byte[] badLayout = new byte[256]; // expected 512
        BotScanResultRequest body = new BotScanResultRequest(
                64, 4,
                Base64.getEncoder().encodeToString(badLayout),
                22.0f, 0.5f,
                Base64.getEncoder().encodeToString(new byte[4096]));

        mvc.perform(post("/api/v1/bot/tasks/" + task.getId() + "/scan-result")
                        .header("Authorization", "Bearer " + DEV_BOT_SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void scanResult_heightLengthMismatch_returns400() throws Exception {
        Auction auction = savedAuction(savedUser("height-len"));
        BotTask task = botTaskService.enqueueScanParcel(auction);

        byte[] badHeight = new byte[2048]; // expected 4096
        BotScanResultRequest body = new BotScanResultRequest(
                64, 4,
                Base64.getEncoder().encodeToString(new byte[512]),
                22.0f, 0.5f,
                Base64.getEncoder().encodeToString(badHeight));

        mvc.perform(post("/api/v1/bot/tasks/" + task.getId() + "/scan-result")
                        .header("Authorization", "Bearer " + DEV_BOT_SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void scanResult_invalidBase64_returns400() throws Exception {
        Auction auction = savedAuction(savedUser("bad-b64"));
        BotTask task = botTaskService.enqueueScanParcel(auction);

        BotScanResultRequest body = new BotScanResultRequest(
                64, 4,
                "!!!not-base64",
                22.0f, 0.5f,
                Base64.getEncoder().encodeToString(new byte[4096]));

        mvc.perform(post("/api/v1/bot/tasks/" + task.getId() + "/scan-result")
                        .header("Authorization", "Bearer " + DEV_BOT_SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void scanResult_nonPositiveStep_returns400() throws Exception {
        Auction auction = savedAuction(savedUser("zero-step"));
        BotTask task = botTaskService.enqueueScanParcel(auction);

        // heightStepMeters = 0 violates @Positive on the DTO record (bean validation)
        // and also the explicit service guard -- either fires a 400
        BotScanResultRequest body = new BotScanResultRequest(
                64, 4,
                Base64.getEncoder().encodeToString(new byte[512]),
                22.0f, 0f,
                Base64.getEncoder().encodeToString(new byte[4096]));

        mvc.perform(post("/api/v1/bot/tasks/" + task.getId() + "/scan-result")
                        .header("Authorization", "Bearer " + DEV_BOT_SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    // --- 401 case ---

    @Test
    void scanResult_missingAuthHeader_returns401() throws Exception {
        mvc.perform(post("/api/v1/bot/tasks/1/scan-result")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isUnauthorized());
    }

    // --- fixtures ---

    private BotScanResultRequest validRequest() {
        byte[] layoutCells = new byte[512];
        byte[] heightCells = new byte[4096];
        return new BotScanResultRequest(
                64, 4,
                Base64.getEncoder().encodeToString(layoutCells),
                22.0f, 0.5f,
                Base64.getEncoder().encodeToString(heightCells));
    }

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
                .title("Scan controller test listing")
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
                .parcelScanIncluded(true)
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
