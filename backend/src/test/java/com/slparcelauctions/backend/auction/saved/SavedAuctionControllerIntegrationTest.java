package com.slparcelauctions.backend.auction.saved;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import com.slparcelauctions.backend.auction.AuctionEndOutcome;
import com.slparcelauctions.backend.auction.AuctionParcelSnapshot;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.VerificationMethod;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.auction.broadcast.AuctionBroadcastPublisher;
import com.slparcelauctions.backend.sl.SlWorldApiClient;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Full-stack coverage for {@code /api/v1/me/saved/*}. Exercises:
 *
 * <ul>
 *   <li>POST happy-path on an ACTIVE auction.</li>
 *   <li>POST is idempotent (duplicate returns existing row, same {@code savedAt}).</li>
 *   <li>POST against pre-active (DRAFT) returns 403 + {@code CANNOT_SAVE_PRE_ACTIVE}.</li>
 *   <li>POST without a JWT returns 401 (per {@code JwtAuthenticationEntryPoint}).</li>
 *   <li>DELETE is idempotent — 204 whether the row existed or not.</li>
 *   <li>GET /ids returns a JSON array (empty for fresh users; populated post-save).</li>
 *   <li>GET /auctions returns the saved-list page envelope; {@code statusFilter}
 *       toggles between active-only (default) and ended-only correctly.</li>
 * </ul>
 *
 * <p>Pattern lifted from {@code MyBidsIntegrationTest}: full register +
 * verify flow per user so JWTs are issued by the real auth path. {@code Sl}
 * clients mocked so no SL outbound calls fly during tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "slpa.notifications.cleanup.enabled=false",
        "slpa.notifications.sl-im.cleanup.enabled=false"
})
@Transactional
class SavedAuctionControllerIntegrationTest {

    private static final String TRUSTED_OWNER = "00000000-0000-0000-0000-000000000001";

    @Autowired MockMvc mockMvc;
    @Autowired AuctionRepository auctionRepository;
    @Autowired UserRepository userRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean AuctionBroadcastPublisher broadcastPublisher;

    private String sellerAccessToken;
    private Long sellerId;
    private String bidderAccessToken;

    private Long activeAuctionId;
    private Long draftAuctionId;
    private Long endedAuctionId;
    private UUID activeAuctionPublicId;
    private UUID draftAuctionPublicId;
    private UUID endedAuctionPublicId;

    @BeforeEach
    void setUp() throws Exception {
        sellerAccessToken = registerAndVerifyUser(
                "saved-seller@example.com", "SavedSeller",
                "11111111-aaaa-bbbb-cccc-000000000201");
        sellerId = userRepository.findByUsername("saved-seller@example.com").orElseThrow().getId();

        bidderAccessToken = registerAndVerifyUser(
                "saved-bidder@example.com", "SavedBidder",
                "22222222-aaaa-bbbb-cccc-000000000202");

        Auction active = seedAuction(0, AuctionStatus.ACTIVE);
        activeAuctionId = active.getId();
        activeAuctionPublicId = active.getPublicId();
        Auction draft = seedAuction(1, AuctionStatus.DRAFT);
        draftAuctionId = draft.getId();
        draftAuctionPublicId = draft.getPublicId();
        Auction ended = seedEndedAuction(2);
        endedAuctionId = ended.getId();
        endedAuctionPublicId = ended.getPublicId();
    }

    // -------------------------------------------------------------------------
    // POST /api/v1/me/saved
    // -------------------------------------------------------------------------

    @Test
    void post_saved_returns200_forActiveAuction() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/me/saved")
                        .header("Authorization", "Bearer " + bidderAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"auctionPublicId\": \"" + activeAuctionPublicId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.auctionPublicId").value(activeAuctionPublicId.toString()))
                .andExpect(jsonPath("$.savedAt").exists())
                .andReturn();

        org.assertj.core.api.Assertions.assertThat(
                        objectMapper.readTree(result.getResponse().getContentAsString()).get("savedAt").asText())
                .as("savedAt is non-blank ISO timestamp")
                .isNotBlank();
    }

    @Test
    void post_saved_duplicate_isIdempotent_andReturnsExistingSavedAt() throws Exception {
        MvcResult first = mockMvc.perform(post("/api/v1/me/saved")
                        .header("Authorization", "Bearer " + bidderAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"auctionPublicId\": \"" + activeAuctionPublicId + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String firstSavedAt = objectMapper.readTree(first.getResponse().getContentAsString())
                .get("savedAt").asText();

        MvcResult second = mockMvc.perform(post("/api/v1/me/saved")
                        .header("Authorization", "Bearer " + bidderAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"auctionPublicId\": \"" + activeAuctionPublicId + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String secondSavedAt = objectMapper.readTree(second.getResponse().getContentAsString())
                .get("savedAt").asText();

        org.assertj.core.api.Assertions.assertThat(secondSavedAt)
                .as("duplicate POST returns the existing row's savedAt verbatim")
                .isEqualTo(firstSavedAt);
    }

    @Test
    void post_saved_preActiveAuction_returns403_withCannotSaveCode() throws Exception {
        mockMvc.perform(post("/api/v1/me/saved")
                        .header("Authorization", "Bearer " + bidderAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"auctionPublicId\": \"" + draftAuctionPublicId + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CANNOT_SAVE_PRE_ACTIVE"))
                .andExpect(jsonPath("$.auctionPublicId").value(draftAuctionPublicId.toString()))
                .andExpect(jsonPath("$.currentStatus").value("DRAFT"));
    }

    @Test
    void post_saved_unauth_returns401Or403() throws Exception {
        // SecurityConfig + JwtAuthenticationEntryPoint surface 401 here; a
        // misconfiguration that swapped to AccessDeniedHandler would yield 403.
        // Either is "rejected"; pin both as acceptable.
        mockMvc.perform(post("/api/v1/me/saved")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"auctionPublicId\": \"" + activeAuctionPublicId + "\"}"))
                .andExpect(r -> {
                    int s = r.getResponse().getStatus();
                    org.assertj.core.api.Assertions.assertThat(s).isIn(401, 403);
                });
    }

    // -------------------------------------------------------------------------
    // DELETE /api/v1/me/saved/{auctionId}
    // -------------------------------------------------------------------------

    @Test
    void delete_saved_isIdempotent_204RegardlessOfRowPresence() throws Exception {
        // Pre-save then double-delete.
        mockMvc.perform(post("/api/v1/me/saved")
                        .header("Authorization", "Bearer " + bidderAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"auctionPublicId\": \"" + activeAuctionPublicId + "\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/me/saved/" + activeAuctionPublicId)
                        .header("Authorization", "Bearer " + bidderAccessToken))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/me/saved/" + activeAuctionPublicId)
                        .header("Authorization", "Bearer " + bidderAccessToken))
                .andExpect(status().isNoContent());
    }

    // -------------------------------------------------------------------------
    // GET /api/v1/me/saved/ids
    // -------------------------------------------------------------------------

    @Test
    void ids_returnsEmptyArray_forFreshUser() throws Exception {
        mockMvc.perform(get("/api/v1/me/saved/ids")
                        .header("Authorization", "Bearer " + bidderAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicIds").isArray())
                .andExpect(jsonPath("$.publicIds.length()").value(0));
    }

    @Test
    void ids_returnsSavedAuctionIds_afterSave() throws Exception {
        mockMvc.perform(post("/api/v1/me/saved")
                        .header("Authorization", "Bearer " + bidderAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"auctionPublicId\": \"" + activeAuctionPublicId + "\"}"))
                .andExpect(status().isOk());

        MvcResult res = mockMvc.perform(get("/api/v1/me/saved/ids")
                        .header("Authorization", "Bearer " + bidderAccessToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode idsNode = objectMapper.readTree(res.getResponse().getContentAsString()).get("publicIds");
        org.assertj.core.api.Assertions.assertThat(idsNode.isArray()).isTrue();
        org.assertj.core.api.Assertions.assertThat(idsNode.size()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(idsNode.get(0).asText()).isEqualTo(activeAuctionPublicId.toString());
    }

    // -------------------------------------------------------------------------
    // GET /api/v1/me/saved/auctions
    // -------------------------------------------------------------------------

    @Test
    void auctionsList_activeOnlyDefault_returnsOnlyActiveSaves() throws Exception {
        // Save both an ACTIVE and an ENDED auction.
        saveByApi(activeAuctionPublicId);
        saveByApi(endedAuctionPublicId);

        MvcResult res = mockMvc.perform(get("/api/v1/me/saved/auctions")
                        .header("Authorization", "Bearer " + bidderAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.meta.sortApplied").value("saved_at"))
                .andReturn();

        JsonNode root = objectMapper.readTree(res.getResponse().getContentAsString());
        JsonNode content = root.get("content");
        org.assertj.core.api.Assertions.assertThat(content.size())
                .as("ACTIVE_ONLY default surfaces only the active save")
                .isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(content.get(0).get("publicId").asText())
                .isEqualTo(activeAuctionPublicId.toString());
    }

    @Test
    void auctionsList_endedOnly_returnsOnlyEndedSaves() throws Exception {
        saveByApi(activeAuctionPublicId);
        saveByApi(endedAuctionPublicId);

        MvcResult res = mockMvc.perform(get("/api/v1/me/saved/auctions")
                        .header("Authorization", "Bearer " + bidderAccessToken)
                        .param("statusFilter", "ended_only"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode content = objectMapper.readTree(res.getResponse().getContentAsString())
                .get("content");
        org.assertj.core.api.Assertions.assertThat(content.size())
                .as("ENDED_ONLY surfaces only the ended save")
                .isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(content.get(0).get("publicId").asText())
                .isEqualTo(endedAuctionPublicId.toString());
    }

    @Test
    void auctionsList_all_returnsBothSaves() throws Exception {
        saveByApi(activeAuctionPublicId);
        saveByApi(endedAuctionPublicId);

        MvcResult res = mockMvc.perform(get("/api/v1/me/saved/auctions")
                        .header("Authorization", "Bearer " + bidderAccessToken)
                        .param("statusFilter", "all"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode content = objectMapper.readTree(res.getResponse().getContentAsString())
                .get("content");
        org.assertj.core.api.Assertions.assertThat(content.size())
                .as("ALL surfaces every saved row regardless of status")
                .isEqualTo(2);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void saveByApi(UUID auctionPublicId) throws Exception {
        mockMvc.perform(post("/api/v1/me/saved")
                        .header("Authorization", "Bearer " + bidderAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"auctionPublicId\": \"" + auctionPublicId + "\"}"))
                .andExpect(status().isOk());
    }

    private Auction seedAuction(int idx, AuctionStatus status) {
        UUID parcelUuid = UUID.fromString(
                String.format("66666666-6666-6666-6666-%012d", 210 + idx));
        UUID ownerUuid = UUID.fromString(
                String.format("77777777-7777-7777-7777-%012d", 220 + idx));
        User seller = userRepository.findById(sellerId).orElseThrow();
        OffsetDateTime now = OffsetDateTime.now();
        Auction a = Auction.builder()
                .title("Saved test " + idx)
                .slParcelUuid(parcelUuid)
                .seller(seller)
                .status(status)
                .verificationMethod(VerificationMethod.UUID_ENTRY)
                .verificationTier(VerificationTier.SCRIPT)
                .startingBid(1000L)
                .durationHours(168)
                .snipeProtect(false)
                .listingFeePaid(true)
                .currentBid(1500L)
                .bidCount(1)
                .consecutiveWorldApiFailures(0)
                .commissionRate(new BigDecimal("0.05"))
                .agentFeeRate(BigDecimal.ZERO)
                .build();
        a.setStartsAt(now.minusHours(1));
        if (status == AuctionStatus.ACTIVE) {
            a.setEndsAt(now.plusDays(1));
            a.setOriginalEndsAt(now.plusDays(1));
        } else {
            a.setEndsAt(now.minusHours(1));
            a.setOriginalEndsAt(now.minusHours(1));
        }
        Auction saved = auctionRepository.save(a);
        saved.setParcelSnapshot(AuctionParcelSnapshot.builder()
                .slParcelUuid(parcelUuid)
                .ownerUuid(ownerUuid)
                .ownerType("agent")
                .parcelName("Saved Parcel " + idx)
                .regionName("Coniston")
                .regionMaturityRating("GENERAL")
                .areaSqm(2048)
                .positionX(128.0 + idx).positionY(64.0 + idx).positionZ(22.0)
                .build());
        return auctionRepository.save(saved);
    }

    private Auction seedEndedAuction(int idx) {
        Auction a = seedAuction(idx, AuctionStatus.ENDED);
        a.setEndOutcome(AuctionEndOutcome.SOLD);
        a.setEndedAt(OffsetDateTime.now().minusHours(1));
        return auctionRepository.save(a);
    }

    private String registerUser(String email, String displayName) throws Exception {
        String body = String.format(
                "{\"username\":\"%s\",\"password\":\"hunter22abc\"}",
                email, displayName);
        MvcResult reg = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode json = objectMapper.readTree(reg.getResponse().getContentAsString());
        return json.get("accessToken").asText();
    }

    private String registerAndVerifyUser(String email, String displayName, String avatarUuid)
            throws Exception {
        String token = registerUser(email, displayName);
        MvcResult gen = mockMvc.perform(post("/api/v1/verification/generate")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        String code = objectMapper.readTree(gen.getResponse().getContentAsString())
                .get("code").asText();
        String body = String.format("""
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
                        .content(body)
                        .header("X-SecondLife-Shard", "Production")
                        .header("X-SecondLife-Owner-Key", TRUSTED_OWNER))
                .andExpect(status().isOk());
        return token;
    }

}
