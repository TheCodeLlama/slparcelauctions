package com.slparcelauctions.backend.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
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
import com.slparcelauctions.backend.bot.dto.BotScanFailedRequest;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Integration test for {@code POST /api/v1/bot/tasks/{id}/scan-failed}.
 *
 * <p>Mirrors the auth-header pattern from {@link BotTaskControllerScanResultTest}:
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
class BotTaskControllerScanFailedTest {

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

    // --- happy path ---

    @Test
    void happyPath_pendingScanTask_returns204AndMarksFailed() throws Exception {
        Auction auction = savedAuction(savedUser("happy-failed"));
        BotTask task = botTaskService.enqueueScanParcel(auction);

        mvc.perform(post("/api/v1/bot/tasks/" + task.getId() + "/scan-failed")
                        .header("Authorization", "Bearer " + DEV_BOT_SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BotScanFailedRequest("TERRAIN_NOT_LOADED"))))
                .andExpect(status().isNoContent());

        BotTask failed = botTaskRepo.findById(task.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(BotTaskStatus.FAILED);
        assertThat(failed.getFailureReason()).isEqualTo("TERRAIN_NOT_LOADED");
    }

    // --- 409 cases ---

    @Test
    void nonScanParcelTask_returns409() throws Exception {
        Auction auction = savedAuction(savedUser("wrong-type-failed"));

        BotTask wrongTask = BotTask.builder()
                .taskType(BotTaskType.VERIFY_SELL_TO)
                .status(BotTaskStatus.PENDING)
                .auction(auction)
                .parcelUuid(auction.getSlParcelUuid())
                .sentinelPrice(0L)
                .build();
        wrongTask = botTaskRepo.save(wrongTask);

        mvc.perform(post("/api/v1/bot/tasks/" + wrongTask.getId() + "/scan-failed")
                        .header("Authorization", "Bearer " + DEV_BOT_SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BotScanFailedRequest("TERRAIN_NOT_LOADED"))))
                .andExpect(status().isConflict());
    }

    @Test
    void terminalTask_returns409() throws Exception {
        Auction auction = savedAuction(savedUser("completed-failed"));
        BotTask task = botTaskService.enqueueScanParcel(auction);

        // Force-complete the task directly via the repository
        task.setStatus(BotTaskStatus.COMPLETED);
        botTaskRepo.save(task);

        mvc.perform(post("/api/v1/bot/tasks/" + task.getId() + "/scan-failed")
                        .header("Authorization", "Bearer " + DEV_BOT_SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BotScanFailedRequest("TERRAIN_NOT_LOADED"))))
                .andExpect(status().isConflict());
    }

    // --- 400 cases ---

    @Test
    void blankReason_returns400() throws Exception {
        Auction auction = savedAuction(savedUser("blank-reason"));
        BotTask task = botTaskService.enqueueScanParcel(auction);

        mvc.perform(post("/api/v1/bot/tasks/" + task.getId() + "/scan-failed")
                        .header("Authorization", "Bearer " + DEV_BOT_SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BotScanFailedRequest(""))))
                .andExpect(status().isBadRequest());
    }

    // --- 401 case ---

    @Test
    void missingAuthHeader_returns401() throws Exception {
        mvc.perform(post("/api/v1/bot/tasks/1/scan-failed")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BotScanFailedRequest("TERRAIN_NOT_LOADED"))))
                .andExpect(status().isUnauthorized());
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
                .title("Scan failed controller test listing")
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
