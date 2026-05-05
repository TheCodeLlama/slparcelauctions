package com.slparcelauctions.backend.admin.ban;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionParcelSnapshot;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.VerificationMethod;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.auction.broadcast.AuctionBroadcastPublisher;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Integration tests verifying {@link BanCheckService#assertNotBanned} is wired
 * into all 6 enforcement paths. A real {@link BanRepository} seeds active
 * {@link Ban} rows; {@code StringRedisTemplate} is mocked so every check is a
 * cache-miss → DB query.
 *
 * <p>{@code @Transactional} at the class level rolls back all DB changes after
 * each test so ban rows, users, parcels, and auctions never leak between cases.
 */
@SpringBootTest
@AutoConfigureMockMvc
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
@Transactional
class BanEnforcementIntegrationTest {

    private static final String TRUSTED_OWNER = "00000000-0000-0000-0000-000000000001";

    @Autowired MockMvc mockMvc;
    @Autowired BanRepository banRepo;
    @Autowired UserRepository userRepo;
    @Autowired AuctionRepository auctionRepo;

    @MockitoBean StringRedisTemplate redis;
    @MockitoBean AuctionBroadcastPublisher broadcastPublisher;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // Shared admin user for seeding bans (FK requirement)
    private User adminUser;

    @BeforeEach
    void setUpRedisNoOp() {
        // Stub redis to always cache-miss so BanCheckService goes to the DB.
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
        lenient().when(valueOps.get(anyString())).thenReturn(null);
        lenient().doNothing().when(valueOps).set(anyString(), anyString(), any());

        adminUser = userRepo.save(User.builder().username("u-" + UUID.randomUUID().toString().substring(0, 8))
                .email("ban-admin-" + UUID.randomUUID() + "@x.com")
                .passwordHash("x")
                .slAvatarUuid(UUID.randomUUID())
                .build());
    }

    // -------------------------------------------------------------------------
    // 1. Bid endpoint — AVATAR ban → 403 USER_BANNED
    // -------------------------------------------------------------------------

    @Test
    void bidEndpoint_avatarBan_returns403_USER_BANNED() throws Exception {
        UUID avatarUuid = UUID.randomUUID();
        String bidderToken = registerAndVerifyUser(
                "bid-avatar-banned-" + UUID.randomUUID() + "@x.com", "BidAvatarBanned",
                avatarUuid.toString());
        String sellerToken = registerAndVerifyUser(
                "bid-avatar-seller-" + UUID.randomUUID() + "@x.com", "BidAvatarSeller",
                UUID.randomUUID().toString());
        Auction auction = seedActiveAuction(findUserByToken(sellerToken));

        banRepo.save(Ban.builder()
                .adminUser(adminUser)
                .banType(BanType.AVATAR)
                .slAvatarUuid(avatarUuid)
                .reasonCategory(BanReasonCategory.SHILL_BIDDING)
                .build());

        mockMvc.perform(post("/api/v1/auctions/" + auction.getPublicId() + "/bids")
                        .header("Authorization", "Bearer " + bidderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":1000}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("USER_BANNED"));
    }

    // -------------------------------------------------------------------------
    // 2. Bid endpoint — IP ban → 403
    // -------------------------------------------------------------------------

    @Test
    void bidEndpoint_ipBan_returns403() throws Exception {
        String bidderToken = registerAndVerifyUser(
                "bid-ip-banned-" + UUID.randomUUID() + "@x.com", "BidIpBanned",
                UUID.randomUUID().toString());
        String sellerToken = registerAndVerifyUser(
                "bid-ip-seller-" + UUID.randomUUID() + "@x.com", "BidIpSeller",
                UUID.randomUUID().toString());
        Auction auction = seedActiveAuction(findUserByToken(sellerToken));

        // MockMvc uses 127.0.0.1 as the remote address
        banRepo.save(Ban.builder()
                .adminUser(adminUser)
                .banType(BanType.IP)
                .ipAddress("127.0.0.1")
                .reasonCategory(BanReasonCategory.SPAM)
                .build());

        mockMvc.perform(post("/api/v1/auctions/" + auction.getPublicId() + "/bids")
                        .header("Authorization", "Bearer " + bidderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":1000}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("USER_BANNED"));
    }

    // -------------------------------------------------------------------------
    // 3. Register endpoint — IP ban → 403
    // -------------------------------------------------------------------------

    @Test
    void registerEndpoint_ipBan_returns403() throws Exception {
        banRepo.save(Ban.builder()
                .adminUser(adminUser)
                .banType(BanType.IP)
                .ipAddress("127.0.0.1")
                .reasonCategory(BanReasonCategory.SPAM)
                .build());

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"reg-banned-" + UUID.randomUUID()
                                + "@x.com\",\"password\":\"hunter22abc\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("USER_BANNED"));
    }

    // -------------------------------------------------------------------------
    // 4. Login endpoint — IP ban → 403
    // -------------------------------------------------------------------------

    @Test
    void loginEndpoint_ipBan_returns403() throws Exception {
        // Register first (no ban yet), then add ban, then attempt login
        String email = "login-banned-" + UUID.randomUUID() + "@x.com";
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + email
                                + "\",\"password\":\"hunter22abc\"}"))
                .andExpect(status().isCreated());

        banRepo.save(Ban.builder()
                .adminUser(adminUser)
                .banType(BanType.IP)
                .ipAddress("127.0.0.1")
                .reasonCategory(BanReasonCategory.SPAM)
                .build());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + email + "\",\"password\":\"hunter22abc\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("USER_BANNED"));
    }

    // -------------------------------------------------------------------------
    // 5. Cancel endpoint — AVATAR ban on seller → 403
    // -------------------------------------------------------------------------

    @Test
    void cancelEndpoint_avatarBan_returns403() throws Exception {
        UUID sellerAvatarUuid = UUID.randomUUID();
        String sellerToken = registerAndVerifyUser(
                "cancel-seller-" + UUID.randomUUID() + "@x.com", "CancelSeller",
                sellerAvatarUuid.toString());
        User seller = findUserByToken(sellerToken);
        Auction auction = seedDraftAuction(seller);

        banRepo.save(Ban.builder()
                .adminUser(adminUser)
                .banType(BanType.AVATAR)
                .slAvatarUuid(sellerAvatarUuid)
                .reasonCategory(BanReasonCategory.FRAUDULENT_SELLER)
                .build());

        mockMvc.perform(put("/api/v1/auctions/" + auction.getPublicId() + "/cancel")
                        .header("Authorization", "Bearer " + sellerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"change of mind\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("USER_BANNED"));
    }

    // -------------------------------------------------------------------------
    // 6. BOTH ban — blocks bid AND login
    // -------------------------------------------------------------------------

    @Test
    void bothBan_blocksBidAndLogin() throws Exception {
        UUID avatarUuid = UUID.randomUUID();
        String bidderToken = registerAndVerifyUser(
                "both-bidder-" + UUID.randomUUID() + "@x.com", "BothBidder",
                avatarUuid.toString());
        String sellerToken = registerAndVerifyUser(
                "both-seller-" + UUID.randomUUID() + "@x.com", "BothSeller",
                UUID.randomUUID().toString());
        Auction auction = seedActiveAuction(findUserByToken(sellerToken));

        String email = "both-login-" + UUID.randomUUID() + "@x.com";
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + email
                                + "\",\"password\":\"hunter22abc\"}"))
                .andExpect(status().isCreated());

        banRepo.save(Ban.builder()
                .adminUser(adminUser)
                .banType(BanType.BOTH)
                .ipAddress("127.0.0.1")
                .slAvatarUuid(avatarUuid)
                .reasonCategory(BanReasonCategory.FRAUDULENT_SELLER)
                .build());

        // Bid is blocked by avatar
        mockMvc.perform(post("/api/v1/auctions/" + auction.getPublicId() + "/bids")
                        .header("Authorization", "Bearer " + bidderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":1000}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("USER_BANNED"));

        // Login is blocked by IP
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + email + "\",\"password\":\"hunter22abc\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("USER_BANNED"));
    }

    // -------------------------------------------------------------------------
    // 7. Lifted ban — action succeeds
    // -------------------------------------------------------------------------

    @Test
    void liftedBan_allowsAction() throws Exception {
        Ban ban = banRepo.save(Ban.builder()
                .adminUser(adminUser)
                .banType(BanType.IP)
                .ipAddress("127.0.0.1")
                .reasonCategory(BanReasonCategory.SPAM)
                .liftedAt(OffsetDateTime.now().minusMinutes(5))
                .build());

        // With lifted ban, register should succeed
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"lifted-" + UUID.randomUUID()
                                + "@x.com\",\"password\":\"hunter22abc\"}"))
                .andExpect(status().isCreated());
    }

    // -------------------------------------------------------------------------
    // 8. Expired ban — action succeeds
    // -------------------------------------------------------------------------

    @Test
    void expiredBan_allowsAction() throws Exception {
        banRepo.save(Ban.builder()
                .adminUser(adminUser)
                .banType(BanType.IP)
                .ipAddress("127.0.0.1")
                .reasonCategory(BanReasonCategory.SPAM)
                .expiresAt(OffsetDateTime.now().minusHours(1))
                .build());

        // With expired ban, register should succeed
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"expired-" + UUID.randomUUID()
                                + "@x.com\",\"password\":\"hunter22abc\"}"))
                .andExpect(status().isCreated());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String registerUser(String email, String displayName) throws Exception {
        String body = String.format(
                "{\"username\":\"%s\",\"password\":\"hunter22abc\"}",
                email, displayName);
        MvcResult reg = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated()).andReturn();
        JsonNode json = objectMapper.readTree(reg.getResponse().getContentAsString());
        return json.get("accessToken").asText();
    }

    private String registerAndVerifyUser(String email, String displayName, String avatarUuid)
            throws Exception {
        String token = registerUser(email, displayName);
        MvcResult gen = mockMvc.perform(post("/api/v1/verification/generate")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        String code = objectMapper.readTree(gen.getResponse().getContentAsString())
                .get("code").asText();
        String slVerifyBody = String.format("""
            {
              "verificationCode":"%s",
              "avatarUuid":"%s",
              "avatarName":"%s",
              "displayName":"%s",
              "username":"test.resident",
              "bornDate":"2012-05-15",
              "payInfo":3
            }
            """, code, avatarUuid, displayName, displayName);
        mockMvc.perform(post("/api/v1/sl/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(slVerifyBody)
                        .header("X-SecondLife-Shard", "Production")
                        .header("X-SecondLife-Owner-Key", TRUSTED_OWNER))
                .andExpect(status().isOk());
        return token;
    }

    /**
     * Resolves a user entity by decoding the user ID from the access token returned
     * by registerAndVerifyUser. Since the JWT is opaque here, we load the most recently
     * saved verified user with a matching avatar UUID — or just fetch the last-saved user.
     * Simpler: we look up by email encoded in the setup helpers.
     *
     * <p>This helper is only called for users whose email was passed in to the register
     * helpers above, so we search by verified=true and pick the latest.
     */
    private User findUserByToken(String token) throws Exception {
        // Decode JWT payload to extract sub (userPublicId as UUID string)
        String[] parts = token.split("\\.");
        String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]));
        JsonNode payloadJson = objectMapper.readTree(payload);
        UUID userPublicId = UUID.fromString(payloadJson.get("sub").asText());
        return userRepo.findByPublicId(userPublicId).orElseThrow();
    }

    private Auction seedActiveAuction(User seller) {
        UUID parcelUuid = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        Auction auction = auctionRepo.save(Auction.builder()
                .title("Ban test auction")
                .slParcelUuid(parcelUuid)
                .seller(seller)
                .status(AuctionStatus.ACTIVE)
                .verificationMethod(VerificationMethod.UUID_ENTRY)
                .verificationTier(VerificationTier.SCRIPT)
                .startingBid(1000L)
                .durationHours(24)
                .snipeProtect(false)
                .listingFeePaid(false)
                .currentBid(0L)
                .bidCount(0)
                .consecutiveWorldApiFailures(0)
                .commissionRate(new BigDecimal("0.05"))
                .agentFeeRate(BigDecimal.ZERO)
                .startsAt(now.minusHours(1))
                .endsAt(now.plusDays(1))
                .originalEndsAt(now.plusDays(1))
                .build());
        auction.setParcelSnapshot(AuctionParcelSnapshot.builder()
                .slParcelUuid(parcelUuid)
                .ownerUuid(seller.getSlAvatarUuid())
                .ownerType("agent")
                .parcelName("Ban Test Parcel")
                .regionName("Test Region")
                .regionMaturityRating("GENERAL")
                .areaSqm(512)
                .positionX(128.0).positionY(64.0).positionZ(22.0)
                .build());
        return auctionRepo.save(auction);
    }

    private Auction seedDraftAuction(User seller) {
        UUID parcelUuid = UUID.randomUUID();
        Auction auction = auctionRepo.save(Auction.builder()
                .title("Ban cancel test auction")
                .slParcelUuid(parcelUuid)
                .seller(seller)
                .status(AuctionStatus.DRAFT)
                .startingBid(1000L)
                .durationHours(24)
                .snipeProtect(false)
                .listingFeePaid(false)
                .currentBid(0L)
                .bidCount(0)
                .consecutiveWorldApiFailures(0)
                .commissionRate(new BigDecimal("0.05"))
                .agentFeeRate(BigDecimal.ZERO)
                .build());
        auction.setParcelSnapshot(AuctionParcelSnapshot.builder()
                .slParcelUuid(parcelUuid)
                .ownerUuid(seller.getSlAvatarUuid())
                .ownerType("agent")
                .parcelName("Ban Cancel Test Parcel")
                .regionName("Test Region")
                .regionMaturityRating("GENERAL")
                .areaSqm(512)
                .positionX(128.0).positionY(64.0).positionZ(22.0)
                .build());
        return auctionRepo.save(auction);
    }
}
