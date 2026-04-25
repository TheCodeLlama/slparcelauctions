package com.slparcelauctions.backend.auction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
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
import com.slparcelauctions.backend.auction.dto.AuctionCancelRequest;
import com.slparcelauctions.backend.auction.dto.AuctionCreateRequest;
import com.slparcelauctions.backend.auction.dto.AuctionUpdateRequest;
import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.parcel.ParcelRepository;
import com.slparcelauctions.backend.sl.SlMapApiClient;
import com.slparcelauctions.backend.sl.SlWorldApiClient;
import com.slparcelauctions.backend.sl.dto.GridCoordinates;
import com.slparcelauctions.backend.sl.dto.ParcelMetadata;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import reactor.core.publisher.Mono;

/**
 * Full-stack coverage for {@code /api/v1/auctions*} endpoints.
 *
 * <p>Uses {@code @SpringBootTest} so the full Spring Security filter chain,
 * JWT auth filter, {@code AuctionExceptionHandler}, and
 * {@code GlobalExceptionHandler} all run. External SL HTTP clients are mocked
 * via {@code @MockitoBean} so registering a parcel via the parcel lookup
 * endpoint stays offline.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = "auth.cleanup.enabled=false")
@Transactional
class AuctionControllerIntegrationTest {

    private static final String TRUSTED_OWNER = "00000000-0000-0000-0000-000000000001";

    @Autowired MockMvc mockMvc;
    @Autowired ParcelRepository parcelRepository;
    @Autowired AuctionRepository auctionRepository;
    @Autowired AuctionPhotoRepository photoRepository;
    @Autowired UserRepository userRepository;
    @Autowired ListingFeeRefundRepository refundRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean SlWorldApiClient worldApi;
    @MockitoBean SlMapApiClient mapApi;

    /** Verified seller. */
    private String sellerAccessToken;
    private Long sellerId;

    /** Another verified user (for non-seller paths). */
    private String otherAccessToken;

    /** Unverified user (for 403 on write paths). */
    private String unverifiedAccessToken;

    /** A parcel owned by {@code sellerId}. */
    private Parcel sellerParcel;

    @BeforeEach
    void setUp() throws Exception {
        sellerAccessToken = registerAndVerifyUser(
                "auction-seller@example.com", "Seller",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        sellerId = userRepository.findByEmail("auction-seller@example.com").orElseThrow().getId();

        otherAccessToken = registerAndVerifyUser(
                "auction-other@example.com", "Other",
                "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

        unverifiedAccessToken = registerUser("auction-unverified@example.com", "Unverified");

        sellerParcel = seedParcel();
    }

    // -------------------------------------------------------------------------
    // Create
    // -------------------------------------------------------------------------

    @Test
    void create_validRequest_returns201AndDraft() throws Exception {
        AuctionCreateRequest req = new AuctionCreateRequest(
                sellerParcel.getId(), "Test listing", 1000L, null, null,
                168, false, null, "Nice parcel", null);

        mockMvc.perform(post("/api/v1/auctions")
                .header("Authorization", "Bearer " + sellerAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.sellerId").value(sellerId))
                .andExpect(jsonPath("$.title").value("Test listing"))
                .andExpect(jsonPath("$.startingBid").value(1000))
                .andExpect(jsonPath("$.listingFeePaid").value(false));
    }

    @Test
    void create_whenSellerOwesPenalty_returns403WithCodePenaltyOwed() throws Exception {
        // Epic 08 sub-spec 2 §7.7 — suspension gate on listing creation.
        // Mark the seller as owing a penalty balance and assert the create
        // path 403s with the right ProblemDetail code.
        User seller = userRepository.findById(sellerId).orElseThrow();
        seller.setPenaltyBalanceOwed(1000L);
        userRepository.save(seller);

        AuctionCreateRequest req = new AuctionCreateRequest(
                sellerParcel.getId(), "Test listing", 1000L, null, null,
                168, false, null, null, null);

        mockMvc.perform(post("/api/v1/auctions")
                .header("Authorization", "Bearer " + sellerAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PENALTY_OWED"))
                .andExpect(jsonPath("$.title").value("Listing creation suspended"));
    }

    @Test
    void create_whenSellerTimedSuspended_returns403WithCodeTimedSuspension() throws Exception {
        User seller = userRepository.findById(sellerId).orElseThrow();
        seller.setListingSuspensionUntil(OffsetDateTime.now().plusDays(20));
        userRepository.save(seller);

        AuctionCreateRequest req = new AuctionCreateRequest(
                sellerParcel.getId(), "Test listing", 1000L, null, null,
                168, false, null, null, null);

        mockMvc.perform(post("/api/v1/auctions")
                .header("Authorization", "Bearer " + sellerAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TIMED_SUSPENSION"));
    }

    @Test
    void create_whenSellerBanned_returns403WithCodePermanentBan() throws Exception {
        // Ban shadows penalty + timed — the gate evaluates ban first.
        User seller = userRepository.findById(sellerId).orElseThrow();
        seller.setBannedFromListing(true);
        seller.setListingSuspensionUntil(OffsetDateTime.now().plusDays(20));
        seller.setPenaltyBalanceOwed(2500L);
        userRepository.save(seller);

        AuctionCreateRequest req = new AuctionCreateRequest(
                sellerParcel.getId(), "Test listing", 1000L, null, null,
                168, false, null, null, null);

        mockMvc.perform(post("/api/v1/auctions")
                .header("Authorization", "Bearer " + sellerAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMANENT_BAN"));
    }

    @Test
    void create_asUnverifiedUser_returns403() throws Exception {
        AuctionCreateRequest req = new AuctionCreateRequest(
                sellerParcel.getId(), "Test listing", 1000L, null, null,
                168, false, null, null, null);

        mockMvc.perform(post("/api/v1/auctions")
                .header("Authorization", "Bearer " + unverifiedAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void create_nonExistentParcel_returns400() throws Exception {
        AuctionCreateRequest req = new AuctionCreateRequest(
                999999L, "Test listing", 1000L, null, null,
                168, false, null, null, null);

        mockMvc.perform(post("/api/v1/auctions")
                .header("Authorization", "Bearer " + sellerAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void create_missingTitle_returns400WithProblemDetail() throws Exception {
        // JSR-380 @NotBlank on AuctionCreateRequest.title fires before the
        // service is reached; the global validation handler maps it to a
        // standard problem-detail body with a "code" field.
        String body = """
                {
                  "parcelId": %d,
                  "startingBid": 1000,
                  "durationHours": 168,
                  "snipeProtect": false
                }
                """.formatted(sellerParcel.getId());
        mockMvc.perform(post("/api/v1/auctions")
                        .header("Authorization", "Bearer " + sellerAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").exists());
    }

    @Test
    void create_blankTitle_returns400WithProblemDetail() throws Exception {
        String body = """
                {
                  "parcelId": %d,
                  "title": "   ",
                  "startingBid": 1000,
                  "durationHours": 168,
                  "snipeProtect": false
                }
                """.formatted(sellerParcel.getId());
        mockMvc.perform(post("/api/v1/auctions")
                        .header("Authorization", "Bearer " + sellerAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").exists());
    }

    @Test
    void create_titleTooLong_returns400WithProblemDetail() throws Exception {
        String over120 = "x".repeat(121);
        String body = """
                {
                  "parcelId": %d,
                  "title": "%s",
                  "startingBid": 1000,
                  "durationHours": 168,
                  "snipeProtect": false
                }
                """.formatted(sellerParcel.getId(), over120);
        mockMvc.perform(post("/api/v1/auctions")
                        .header("Authorization", "Bearer " + sellerAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").exists());
    }

    @Test
    void getAuction_includesTitleInResponse() throws Exception {
        Auction a = seedAuction(AuctionStatus.ACTIVE, false, 0);
        a.setTitle("Seaside cottage — rare find");
        auctionRepository.save(a);

        mockMvc.perform(get("/api/v1/auctions/" + a.getId())
                        .header("Authorization", "Bearer " + otherAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Seaside cottage — rare find"));
    }

    // -------------------------------------------------------------------------
    // GET /auctions/{id} — listing-detail enrichments (Epic 07 sub-spec 1)
    // -------------------------------------------------------------------------

    @Test
    void getAuction_includesPhotosArray() throws Exception {
        Long auctionId = seedActiveAuctionWithPhotos(3);
        mockMvc.perform(get("/api/v1/auctions/" + auctionId)
                        .header("Authorization", "Bearer " + otherAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.photos").isArray())
                .andExpect(jsonPath("$.photos.length()").value(3))
                .andExpect(jsonPath("$.photos[0].sortOrder").value(0))
                .andExpect(jsonPath("$.photos[1].sortOrder").value(1))
                .andExpect(jsonPath("$.photos[2].sortOrder").value(2));
    }

    @Test
    void getAuction_sellerBlockIncludesRatingAndCompletionRate() throws Exception {
        Long auctionId = seedActiveAuctionWithSellerRating(
                new BigDecimal("4.82"), 12, 8, 4);

        mockMvc.perform(get("/api/v1/auctions/" + auctionId)
                        .header("Authorization", "Bearer " + otherAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seller.id").value(sellerId))
                .andExpect(jsonPath("$.seller.averageRating").value(4.82))
                .andExpect(jsonPath("$.seller.reviewCount").value(12))
                .andExpect(jsonPath("$.seller.completedSales").value(8))
                .andExpect(jsonPath("$.seller.completionRate").value(0.67))
                .andExpect(jsonPath("$.seller.memberSince").exists())
                .andExpect(jsonPath("$.seller.avatarUrl").value(
                        "/api/v1/users/" + sellerId + "/avatar/256"));
    }

    @Test
    void getAuction_response_doesNotContain_cancelledWithBids() throws Exception {
        Long auctionId = seedActiveAuctionWithSellerRating(
                new BigDecimal("4.5"), 10, 8, 4);

        String body = mockMvc.perform(get("/api/v1/auctions/" + auctionId)
                        .header("Authorization", "Bearer " + otherAccessToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("cancelledWithBids");
    }

    @Test
    void getAuction_completionRate_isNull_forNewSeller() throws Exception {
        Long auctionId = seedActiveAuctionWithSellerRating(null, 0, 0, 0);

        mockMvc.perform(get("/api/v1/auctions/" + auctionId)
                        .header("Authorization", "Bearer " + otherAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seller.completionRate").doesNotExist())
                .andExpect(jsonPath("$.seller.averageRating").doesNotExist())
                .andExpect(jsonPath("$.seller.completedSales").value(0));
    }

    // -------------------------------------------------------------------------
    // GET /auctions/{id}
    // -------------------------------------------------------------------------

    @Test
    void get_asSeller_returnsSellerView() throws Exception {
        Auction a = seedAuction(AuctionStatus.DRAFT, false, 0);

        mockMvc.perform(get("/api/v1/auctions/" + a.getId())
                .header("Authorization", "Bearer " + sellerAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.listingFeePaid").exists())
                .andExpect(jsonPath("$.commissionRate").exists());
    }

    @Test
    void get_preActiveAsNonSeller_returns404() throws Exception {
        Auction a = seedAuction(AuctionStatus.DRAFT, false, 0);

        mockMvc.perform(get("/api/v1/auctions/" + a.getId())
                .header("Authorization", "Bearer " + otherAccessToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AUCTION_NOT_FOUND"));
    }

    @Test
    void get_activeAsNonSeller_returnsPublicView() throws Exception {
        Auction a = seedAuction(AuctionStatus.ACTIVE, false, 0);

        mockMvc.perform(get("/api/v1/auctions/" + a.getId())
                .header("Authorization", "Bearer " + otherAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.hasReserve").exists())
                .andExpect(jsonPath("$.reserveMet").exists())
                // No seller-only fields leak
                .andExpect(jsonPath("$.listingFeePaid").doesNotExist())
                .andExpect(jsonPath("$.commissionRate").doesNotExist())
                .andExpect(jsonPath("$.winnerId").doesNotExist())
                .andExpect(jsonPath("$.verificationNotes").doesNotExist());
    }

    @Test
    void get_cancelledAsNonSeller_returnsEndedWithoutWinnerOrFeeFields() throws Exception {
        Auction a = seedAuction(AuctionStatus.CANCELLED, true, 0);
        a.setWinnerId(null);
        a.setListingFeeAmt(100L);
        a.setCommissionAmt(0L);
        auctionRepository.save(a);

        mockMvc.perform(get("/api/v1/auctions/" + a.getId())
                .header("Authorization", "Bearer " + otherAccessToken))
                .andExpect(status().isOk())
                // CANCELLED collapses to ENDED in public view — privacy guarantee
                .andExpect(jsonPath("$.status").value("ENDED"))
                .andExpect(jsonPath("$.winnerId").doesNotExist())
                .andExpect(jsonPath("$.listingFeeAmt").doesNotExist())
                .andExpect(jsonPath("$.listingFeePaid").doesNotExist());
    }

    @Test
    void get_suspendedAuction_viewedByNonSeller_returns404() throws Exception {
        // Spec §6.4 — suspended listings are hidden from public browse, and
        // direct URL access must match (404, not a public view collapsed to ENDED)
        // so that a bookmarked URL stops resolving once ownership monitoring
        // suspends the listing.
        Auction a = seedAuction(AuctionStatus.ACTIVE, false, 0);
        a.setStatus(AuctionStatus.SUSPENDED);
        auctionRepository.save(a);

        mockMvc.perform(get("/api/v1/auctions/" + a.getId())
                .header("Authorization", "Bearer " + otherAccessToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AUCTION_NOT_FOUND"));
    }

    // -------------------------------------------------------------------------
    // GET /users/me/auctions
    // -------------------------------------------------------------------------

    @Test
    void listMine_returnsSellerAuctions() throws Exception {
        seedAuction(AuctionStatus.DRAFT, false, 0);
        seedAuction(AuctionStatus.ACTIVE, false, 0);

        mockMvc.perform(get("/api/v1/users/me/auctions")
                .header("Authorization", "Bearer " + sellerAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].sellerId").value(sellerId));
    }

    // -------------------------------------------------------------------------
    // PUT /auctions/{id}
    // -------------------------------------------------------------------------

    @Test
    void update_onDraft_returns200() throws Exception {
        Auction a = seedAuction(AuctionStatus.DRAFT, false, 0);
        AuctionUpdateRequest req = new AuctionUpdateRequest(
                null, 2500L, null, null, null, null, null, "updated description", null);

        mockMvc.perform(put("/api/v1/auctions/" + a.getId())
                .header("Authorization", "Bearer " + sellerAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.startingBid").value(2500))
                .andExpect(jsonPath("$.sellerDesc").value("updated description"));
    }

    @Test
    void update_onActive_returns409() throws Exception {
        Auction a = seedAuction(AuctionStatus.ACTIVE, false, 0);
        AuctionUpdateRequest req = new AuctionUpdateRequest(
                null, 2500L, null, null, null, null, null, null, null);

        mockMvc.perform(put("/api/v1/auctions/" + a.getId())
                .header("Authorization", "Bearer " + sellerAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUCTION_INVALID_STATE"))
                .andExpect(jsonPath("$.currentState").value("ACTIVE"));
    }

    // -------------------------------------------------------------------------
    // PUT /auctions/{id}/verify — group-land gate (sub-spec 2 §7.2)
    // -------------------------------------------------------------------------

    @Test
    void verify_groupOwnedParcel_withNonSaleToBotMethod_returns422ProblemDetail() throws Exception {
        // Seed a group-owned parcel for the seller and a DRAFT_PAID auction
        // over it. The verify trigger must reject any method other than
        // SALE_TO_BOT with a 422 ProblemDetail carrying the agreed code/title.
        Parcel groupParcel = seedGroupOwnedParcel();
        Auction a = seedAuctionFor(groupParcel, AuctionStatus.DRAFT_PAID, true);

        mockMvc.perform(put("/api/v1/auctions/" + a.getId() + "/verify")
                .header("Authorization", "Bearer " + sellerAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"method\":\"UUID_ENTRY\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.title").value("Group-owned land requires Sale-to-bot"))
                .andExpect(jsonPath("$.code").value("GROUP_LAND_REQUIRES_SALE_TO_BOT"))
                .andExpect(jsonPath("$.detail").isNotEmpty());
    }

    // -------------------------------------------------------------------------
    // PUT /auctions/{id}/cancel
    // -------------------------------------------------------------------------

    @Test
    void cancel_onDraftPaid_createsRefundRow() throws Exception {
        Auction a = seedAuction(AuctionStatus.DRAFT_PAID, true, 0);
        a.setListingFeeAmt(100L);
        auctionRepository.save(a);

        mockMvc.perform(put("/api/v1/auctions/" + a.getId() + "/cancel")
                .header("Authorization", "Bearer " + sellerAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new AuctionCancelRequest("no longer available"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        List<ListingFeeRefund> refunds = refundRepository.findAll();
        assertThat(refunds).anyMatch(r -> r.getAuction().getId().equals(a.getId())
                && r.getStatus() == RefundStatus.PENDING
                && r.getAmount() == 100L);
    }

    @Test
    void cancel_onCompleted_returns409() throws Exception {
        Auction a = seedAuction(AuctionStatus.COMPLETED, false, 0);

        mockMvc.perform(put("/api/v1/auctions/" + a.getId() + "/cancel")
                .header("Authorization", "Bearer " + sellerAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new AuctionCancelRequest(null))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUCTION_INVALID_STATE"));
    }

    // -------------------------------------------------------------------------
    // GET /auctions/{id}/preview
    // -------------------------------------------------------------------------

    @Test
    void preview_asSeller_returns200() throws Exception {
        Auction a = seedAuction(AuctionStatus.DRAFT, false, 0);

        mockMvc.perform(get("/api/v1/auctions/" + a.getId() + "/preview")
                .header("Authorization", "Bearer " + sellerAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(a.getId()))
                .andExpect(jsonPath("$.sellerId").value(sellerId));
    }

    @Test
    void preview_asNonSeller_returns404() throws Exception {
        Auction a = seedAuction(AuctionStatus.DRAFT, false, 0);

        mockMvc.perform(get("/api/v1/auctions/" + a.getId() + "/preview")
                .header("Authorization", "Bearer " + otherAccessToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AUCTION_NOT_FOUND"));
    }

    // -------------------------------------------------------------------------
    // GET /users/{userId}/auctions — public active-listings (spec §14)
    // -------------------------------------------------------------------------

    @Test
    void getUserAuctions_returnsOnlyActiveForSeller() throws Exception {
        seedAuctionFor(seedExtraParcel(0x71), AuctionStatus.ACTIVE, false, 0,
                VerificationMethod.UUID_ENTRY);
        seedAuctionFor(seedExtraParcel(0x72), AuctionStatus.ACTIVE, false, 0,
                VerificationMethod.UUID_ENTRY);
        seedAuctionFor(seedExtraParcel(0x73), AuctionStatus.DRAFT, false, 0,
                VerificationMethod.UUID_ENTRY);
        seedAuctionFor(seedExtraParcel(0x74), AuctionStatus.SUSPENDED, false, 0,
                VerificationMethod.UUID_ENTRY);

        mockMvc.perform(get("/api/v1/users/" + sellerId + "/auctions")
                .param("status", "ACTIVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.content[1].status").value("ACTIVE"))
                // PublicAuctionResponse shape — no seller-only fields leak
                .andExpect(jsonPath("$.content[0].listingFeePaid").doesNotExist())
                .andExpect(jsonPath("$.content[0].commissionRate").doesNotExist())
                .andExpect(jsonPath("$.content[0].winnerId").doesNotExist())
                .andExpect(jsonPath("$.content[0].verificationNotes").doesNotExist());
    }

    @Test
    void getUserAuctions_suspendedAlwaysExcluded() throws Exception {
        seedAuctionFor(seedExtraParcel(0x81), AuctionStatus.SUSPENDED, false, 0,
                VerificationMethod.UUID_ENTRY);
        seedAuctionFor(seedExtraParcel(0x82), AuctionStatus.SUSPENDED, false, 0,
                VerificationMethod.UUID_ENTRY);

        mockMvc.perform(get("/api/v1/users/" + sellerId + "/auctions")
                .param("status", "ACTIVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void getUserAuctions_emptyWhenNoActive() throws Exception {
        // Seller has only DRAFT listings — no ACTIVE — so the page is empty.
        seedAuctionFor(seedExtraParcel(0x91), AuctionStatus.DRAFT, false, 0,
                VerificationMethod.UUID_ENTRY);

        mockMvc.perform(get("/api/v1/users/" + sellerId + "/auctions")
                .param("status", "ACTIVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void getUserAuctions_pagination() throws Exception {
        seedAuctionFor(seedExtraParcel(0xA1), AuctionStatus.ACTIVE, false, 0,
                VerificationMethod.UUID_ENTRY);
        seedAuctionFor(seedExtraParcel(0xA2), AuctionStatus.ACTIVE, false, 0,
                VerificationMethod.UUID_ENTRY);
        seedAuctionFor(seedExtraParcel(0xA3), AuctionStatus.ACTIVE, false, 0,
                VerificationMethod.UUID_ENTRY);

        mockMvc.perform(get("/api/v1/users/" + sellerId + "/auctions")
                .param("status", "ACTIVE")
                .param("page", "0")
                .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.number").value(0))
                .andExpect(jsonPath("$.size").value(2));
    }

    @Test
    void getUserAuctions_anonymousAccessAllowed() throws Exception {
        seedAuctionFor(seedExtraParcel(0xB1), AuctionStatus.ACTIVE, false, 0,
                VerificationMethod.UUID_ENTRY);

        // No Authorization header — spec §14 marks this endpoint public.
        mockMvc.perform(get("/api/v1/users/" + sellerId + "/auctions")
                .param("status", "ACTIVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1));
    }

    @Test
    void getUserAuctions_rejectsNonActiveStatus() throws Exception {
        mockMvc.perform(get("/api/v1/users/" + sellerId + "/auctions")
                .param("status", "ENDED"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void getUserAuctions_orderedByEndsAtAscending() throws Exception {
        // Stagger endsAt across three ACTIVE auctions — the public profile
        // surfaces soonest-ending first, so the content array must be in
        // ascending endsAt order regardless of insertion order. Seed them
        // out-of-order to make sure the ORDER BY is actually doing the work.
        Auction aLater = seedAuctionFor(seedExtraParcel(0xC2), AuctionStatus.ACTIVE, false, 0,
                VerificationMethod.UUID_ENTRY);
        Auction aSoonest = seedAuctionFor(seedExtraParcel(0xC1), AuctionStatus.ACTIVE, false, 0,
                VerificationMethod.UUID_ENTRY);
        Auction aMiddle = seedAuctionFor(seedExtraParcel(0xC3), AuctionStatus.ACTIVE, false, 0,
                VerificationMethod.UUID_ENTRY);
        OffsetDateTime now = OffsetDateTime.now();
        aSoonest.setEndsAt(now.plusHours(1));
        aMiddle.setEndsAt(now.plusDays(1));
        aLater.setEndsAt(now.plusWeeks(1));
        auctionRepository.save(aSoonest);
        auctionRepository.save(aMiddle);
        auctionRepository.save(aLater);

        mockMvc.perform(get("/api/v1/users/" + sellerId + "/auctions")
                .param("status", "ACTIVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(3))
                .andExpect(jsonPath("$.content[0].id").value(aSoonest.getId()))
                .andExpect(jsonPath("$.content[1].id").value(aMiddle.getId()))
                .andExpect(jsonPath("$.content[2].id").value(aLater.getId()));
    }

    @Test
    void getUserAuctions_nonexistentUser_returns200EmptyPage() throws Exception {
        // Endpoint is intentionally permissive about nonexistent userIds to
        // avoid leaking user existence on this public surface. A 404 would
        // let callers enumerate valid user IDs by diffing status codes.
        mockMvc.perform(get("/api/v1/users/99999999/auctions")
                .param("status", "ACTIVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String registerUser(String email, String displayName) throws Exception {
        String body = String.format(
                "{\"email\":\"%s\",\"password\":\"hunter22abc\",\"displayName\":\"%s\"}",
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

    /**
     * Seeds a parcel by going through the full /parcels/lookup flow once so the parcel row
     * is persisted and {@code verified=true}. We stub the SL HTTP clients to avoid network.
     */
    private Parcel seedParcel() throws Exception {
        UUID parcelUuid = UUID.fromString("33333333-3333-3333-3333-333333333333");
        UUID ownerUuid = UUID.fromString("44444444-4444-4444-4444-444444444444");
        when(worldApi.fetchParcel(parcelUuid)).thenReturn(Mono.just(new ParcelMetadata(
                parcelUuid, ownerUuid, "agent",
                "Seed Parcel", "Coniston",
                1024, "Seed description", "http://example.com/snap.jpg", "MODERATE",
                128.0, 64.0, 22.0)));
        when(mapApi.resolveRegion(any())).thenReturn(Mono.just(new GridCoordinates(260000.0, 254000.0)));

        mockMvc.perform(post("/api/v1/parcels/lookup")
                .header("Authorization", "Bearer " + sellerAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"slParcelUuid\":\"" + parcelUuid + "\"}"))
                .andExpect(status().isOk());

        return parcelRepository.findBySlParcelUuid(parcelUuid).orElseThrow();
    }

    private Auction seedAuction(AuctionStatus status, boolean listingFeePaid, int bidCount) {
        return seedAuctionFor(sellerParcel, status, listingFeePaid, bidCount,
                VerificationMethod.UUID_ENTRY);
    }

    private Auction seedAuctionFor(Parcel parcel, AuctionStatus status, boolean listingFeePaid) {
        // Sub-spec 2 §7.1 — verificationMethod is null until the seller picks
        // one at the verify trigger. Group-owned parcel tests rely on this.
        return seedAuctionFor(parcel, status, listingFeePaid, 0, null);
    }

    private Auction seedAuctionFor(Parcel parcel, AuctionStatus status,
            boolean listingFeePaid, int bidCount, VerificationMethod method) {
        User seller = userRepository.findById(sellerId).orElseThrow();
        Auction a = Auction.builder()
                .title("Test listing")
                .parcel(parcel)
                .seller(seller)
                .status(status)
                .verificationMethod(method)
                .startingBid(1000L)
                .durationHours(168)
                .snipeProtect(false)
                .listingFeePaid(listingFeePaid)
                .currentBid(0L)
                .bidCount(bidCount)
                .commissionRate(new BigDecimal("0.05"))
                .agentFeeRate(BigDecimal.ZERO)
                .build();
        if (status == AuctionStatus.ACTIVE) {
            OffsetDateTime now = OffsetDateTime.now();
            a.setStartsAt(now.minusHours(1));
            a.setEndsAt(now.plusDays(1));
            a.setOriginalEndsAt(now.plusDays(1));
        }
        return auctionRepository.save(a);
    }

    /**
     * Seeds an ACTIVE auction over the default seller parcel and attaches
     * {@code n} {@link AuctionPhoto} rows with sequential sort orders. Returns
     * the auction id so callers can assert on the JSON shape of the photos
     * array surfaced by the listing-detail endpoint.
     */
    private Long seedActiveAuctionWithPhotos(int n) {
        Auction a = seedAuction(AuctionStatus.ACTIVE, false, 0);
        for (int i = 0; i < n; i++) {
            photoRepository.save(AuctionPhoto.builder()
                    .auction(a)
                    .objectKey("listings/" + a.getId() + "/stub-" + i + ".png")
                    .contentType("image/png")
                    .sizeBytes(1L)
                    .sortOrder(i)
                    .build());
        }
        return a.getId();
    }

    /**
     * Seeds an ACTIVE auction and primes the seller's reputation counters so
     * the listing-detail endpoint's seller card has values to surface. The
     * {@code cancelledWithBids} arg is set on the user but must NOT appear in
     * the response — a regression-guard test asserts that explicitly.
     */
    private Long seedActiveAuctionWithSellerRating(
            BigDecimal avgRating, int reviewCount,
            int completedSales, int cancelledWithBids) {
        User seller = userRepository.findById(sellerId).orElseThrow();
        seller.setAvgSellerRating(avgRating);
        seller.setTotalSellerReviews(reviewCount);
        seller.setCompletedSales(completedSales);
        seller.setCancelledWithBids(cancelledWithBids);
        userRepository.save(seller);
        Auction a = seedAuction(AuctionStatus.ACTIVE, false, 0);
        return a.getId();
    }

    /**
     * Seeds an agent-owned verified parcel directly via the repository. Used
     * by tests that need multiple parcels (e.g. multi-auction scenarios) so
     * they don't trip the parcel-locking partial unique index.
     */
    private Parcel seedExtraParcel(int seed) {
        UUID parcelUuid = new UUID(0L, 0x10000000L + seed);
        UUID ownerUuid = new UUID(0L, 0x20000000L + seed);
        Parcel p = Parcel.builder()
                .slParcelUuid(parcelUuid)
                .ownerUuid(ownerUuid)
                .ownerType("agent")
                .regionName("Coniston")
                .continentName("Sansara")
                .areaSqm(1024)
                .verified(true)
                .build();
        return parcelRepository.save(p);
    }

    /**
     * Seeds a group-owned parcel directly via the repository (the /parcels/lookup
     * flow stubs an agent-owner, so bypass it). Group-owned parcels trip the
     * group-land gate on PUT /auctions/{id}/verify for any method other than
     * SALE_TO_BOT — sub-spec 2 §7.2.
     */
    private Parcel seedGroupOwnedParcel() {
        UUID parcelUuid = UUID.fromString("55555555-5555-5555-5555-555555555555");
        UUID groupUuid = UUID.fromString("66666666-6666-6666-6666-666666666666");
        Parcel p = Parcel.builder()
                .slParcelUuid(parcelUuid)
                .ownerUuid(groupUuid)
                .ownerType("group")
                .regionName("Coniston")
                .continentName("Sansara")
                .areaSqm(2048)
                .verified(true)
                .build();
        return parcelRepository.save(p);
    }
}
