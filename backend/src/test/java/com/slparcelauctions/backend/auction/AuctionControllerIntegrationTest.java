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
import com.slparcelauctions.backend.sl.SlWorldApiClient;
import com.slparcelauctions.backend.sl.exception.ParcelNotFoundInSlException;
import com.slparcelauctions.backend.region.dto.RegionPageData;
import com.slparcelauctions.backend.sl.dto.ParcelMetadata;
import com.slparcelauctions.backend.sl.dto.ParcelPageData;
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
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "slpa.notifications.cleanup.enabled=false",
        "slpa.notifications.sl-im.cleanup.enabled=false"
})
@Transactional
class AuctionControllerIntegrationTest {

    private static final String TRUSTED_OWNER = "00000000-0000-0000-0000-000000000001";

    @Autowired MockMvc mockMvc;
    @Autowired AuctionRepository auctionRepository;
    @Autowired AuctionPhotoRepository photoRepository;
    @Autowired UserRepository userRepository;
    @Autowired ListingFeeRefundRepository refundRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean SlWorldApiClient worldApi;
    /** Verified seller. */
    private String sellerAccessToken;
    private Long sellerId;
    private UUID sellerPublicId;

    /** Another verified user (for non-seller paths). */
    private String otherAccessToken;

    /** Unverified user (for 403 on write paths). */
    private String unverifiedAccessToken;

    /** A parcel UUID owned by the seller (used in create requests and snapshot seeding). */
    private UUID sellerParcelUuid;

    @BeforeEach
    void setUp() throws Exception {
        sellerAccessToken = registerAndVerifyUser(
                "auction-seller@example.com", "Seller",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        var seller = userRepository.findByUsername("auction-seller@example.com").orElseThrow();
        sellerId = seller.getId();
        sellerPublicId = seller.getPublicId();

        otherAccessToken = registerAndVerifyUser(
                "auction-other@example.com", "Other",
                "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

        unverifiedAccessToken = registerUser("auction-unverified@example.com", "Unverified");

        sellerParcelUuid = seedParcel();
    }

    // -------------------------------------------------------------------------
    // Create
    // -------------------------------------------------------------------------

    @Test
    void create_validRequest_returns201AndDraft() throws Exception {
        AuctionCreateRequest req = new AuctionCreateRequest(
                sellerParcelUuid, "Test listing", 1000L, null, null,
                168, false, null, "Nice parcel", null);

        mockMvc.perform(post("/api/v1/auctions")
                .header("Authorization", "Bearer " + sellerAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.sellerPublicId").value(sellerPublicId.toString()))
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
                sellerParcelUuid, "Test listing", 1000L, null, null,
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
                sellerParcelUuid, "Test listing", 1000L, null, null,
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
                sellerParcelUuid, "Test listing", 1000L, null, null,
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
                sellerParcelUuid, "Test listing", 1000L, null, null,
                168, false, null, null, null);

        mockMvc.perform(post("/api/v1/auctions")
                .header("Authorization", "Bearer " + unverifiedAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_VERIFIED"));
    }

    @Test
    void create_unknownParcelUuid_returnsSlParcelNotFound() throws Exception {
        // Since AuctionCreateRequest now takes a UUID, and the service does a live
        // SL World API lookup, a UUID that the mock doesn't stub returns a 404
        // with code SL_PARCEL_NOT_FOUND (the worldApi mock returns null by default
        // which triggers ParcelNotFoundInSlException inside the lookup chain).
        UUID unknownUuid = UUID.fromString("99999999-9999-9999-9999-999999999999");
        when(worldApi.fetchParcelPage(unknownUuid)).thenReturn(
                Mono.error(new ParcelNotFoundInSlException(unknownUuid)));

        AuctionCreateRequest req = new AuctionCreateRequest(
                unknownUuid, "Test listing", 1000L, null, null,
                168, false, null, null, null);

        mockMvc.perform(post("/api/v1/auctions")
                .header("Authorization", "Bearer " + sellerAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SL_PARCEL_NOT_FOUND"));
    }

    @Test
    void create_missingTitle_returns400WithProblemDetail() throws Exception {
        // JSR-380 @NotBlank on AuctionCreateRequest.title fires before the
        // service is reached; the global validation handler maps it to a
        // standard problem-detail body with a "code" field.
        String body = """
                {
                  "slParcelUuid": "%s",
                  "startingBid": 1000,
                  "durationHours": 168,
                  "snipeProtect": false
                }
                """.formatted(sellerParcelUuid);
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
                  "slParcelUuid": "%s",
                  "title": "   ",
                  "startingBid": 1000,
                  "durationHours": 168,
                  "snipeProtect": false
                }
                """.formatted(sellerParcelUuid);
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
                  "slParcelUuid": "%s",
                  "title": "%s",
                  "startingBid": 1000,
                  "durationHours": 168,
                  "snipeProtect": false
                }
                """.formatted(sellerParcelUuid, over120);
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

        mockMvc.perform(get("/api/v1/auctions/" + a.getPublicId())
                        .header("Authorization", "Bearer " + otherAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Seaside cottage — rare find"));
    }

    // -------------------------------------------------------------------------
    // GET /auctions/{id} — listing-detail enrichments (Epic 07 sub-spec 1)
    // -------------------------------------------------------------------------

    @Test
    void getAuction_includesPhotosArray() throws Exception {
        UUID auctionPublicId = seedActiveAuctionWithPhotos(3);
        mockMvc.perform(get("/api/v1/auctions/" + auctionPublicId)
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
        UUID auctionPublicId = seedActiveAuctionWithSellerRating(
                new BigDecimal("4.82"), 12, 8, 4);

        mockMvc.perform(get("/api/v1/auctions/" + auctionPublicId)
                        .header("Authorization", "Bearer " + otherAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seller.publicId").value(sellerPublicId.toString()))
                .andExpect(jsonPath("$.seller.averageRating").value(4.82))
                .andExpect(jsonPath("$.seller.reviewCount").value(12))
                .andExpect(jsonPath("$.seller.completedSales").value(8))
                .andExpect(jsonPath("$.seller.completionRate").value(0.67))
                .andExpect(jsonPath("$.seller.memberSince").exists())
                .andExpect(jsonPath("$.seller.avatarUrl").value(
                        "/api/v1/users/" + sellerPublicId + "/avatar/256"));
    }

    @Test
    void getAuction_response_doesNotContain_cancelledWithBids() throws Exception {
        UUID auctionPublicId = seedActiveAuctionWithSellerRating(
                new BigDecimal("4.5"), 10, 8, 4);

        String body = mockMvc.perform(get("/api/v1/auctions/" + auctionPublicId)
                        .header("Authorization", "Bearer " + otherAccessToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("cancelledWithBids");
    }

    @Test
    void getAuction_completionRate_isNull_forNewSeller() throws Exception {
        UUID auctionPublicId = seedActiveAuctionWithSellerRating(null, 0, 0, 0);

        mockMvc.perform(get("/api/v1/auctions/" + auctionPublicId)
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

        mockMvc.perform(get("/api/v1/auctions/" + a.getPublicId())
                .header("Authorization", "Bearer " + sellerAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.listingFeePaid").exists())
                .andExpect(jsonPath("$.commissionRate").exists());
    }

    @Test
    void get_preActiveAsNonSeller_returns404() throws Exception {
        Auction a = seedAuction(AuctionStatus.DRAFT, false, 0);

        mockMvc.perform(get("/api/v1/auctions/" + a.getPublicId())
                .header("Authorization", "Bearer " + otherAccessToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AUCTION_NOT_FOUND"));
    }

    @Test
    void get_activeAsNonSeller_returnsPublicView() throws Exception {
        Auction a = seedAuction(AuctionStatus.ACTIVE, false, 0);

        mockMvc.perform(get("/api/v1/auctions/" + a.getPublicId())
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
    void get_activeAnonymous_returnsPublicView() throws Exception {
        // The public auction page is anonymous-safe — viewers can hit the
        // detail endpoint without an Authorization header.
        Auction a = seedAuction(AuctionStatus.ACTIVE, false, 0);

        mockMvc.perform(get("/api/v1/auctions/" + a.getPublicId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.listingFeePaid").doesNotExist());
    }

    @Test
    void get_preActiveAnonymous_returns404() throws Exception {
        // The 404-hide for pre-ACTIVE statuses applies to anonymous callers
        // the same way it does to authenticated non-sellers.
        Auction a = seedAuction(AuctionStatus.DRAFT, false, 0);

        mockMvc.perform(get("/api/v1/auctions/" + a.getPublicId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AUCTION_NOT_FOUND"));
    }

    @Test
    void get_cancelledAsNonSeller_returnsEndedWithoutWinnerOrFeeFields() throws Exception {
        Auction a = seedAuction(AuctionStatus.CANCELLED, true, 0);
        a.setWinnerId(null);
        a.setListingFeeAmt(100L);
        a.setCommissionAmt(0L);
        auctionRepository.save(a);

        mockMvc.perform(get("/api/v1/auctions/" + a.getPublicId())
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

        mockMvc.perform(get("/api/v1/auctions/" + a.getPublicId())
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
                .andExpect(jsonPath("$[0].sellerPublicId").value(sellerPublicId.toString()));
    }

    // -------------------------------------------------------------------------
    // PUT /auctions/{id}
    // -------------------------------------------------------------------------

    @Test
    void update_onDraft_returns200() throws Exception {
        Auction a = seedAuction(AuctionStatus.DRAFT, false, 0);
        AuctionUpdateRequest req = new AuctionUpdateRequest(
                null, null, 2500L, null, null, null, null, null, "updated description", null);

        mockMvc.perform(put("/api/v1/auctions/" + a.getPublicId())
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
                null, null, 2500L, null, null, null, null, null, null, null);

        mockMvc.perform(put("/api/v1/auctions/" + a.getPublicId())
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
        UUID groupParcelUuid = seedGroupOwnedParcel();
        Auction a = seedAuctionFor(groupParcelUuid, "group", AuctionStatus.DRAFT_PAID, true, 0, null);

        mockMvc.perform(put("/api/v1/auctions/" + a.getPublicId() + "/verify")
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

        mockMvc.perform(put("/api/v1/auctions/" + a.getPublicId() + "/cancel")
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

        mockMvc.perform(put("/api/v1/auctions/" + a.getPublicId() + "/cancel")
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

        mockMvc.perform(get("/api/v1/auctions/" + a.getPublicId() + "/preview")
                .header("Authorization", "Bearer " + sellerAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicId").value(a.getPublicId().toString()))
                .andExpect(jsonPath("$.sellerPublicId").value(sellerPublicId.toString()));
    }

    @Test
    void preview_asNonSeller_returns404() throws Exception {
        Auction a = seedAuction(AuctionStatus.DRAFT, false, 0);

        mockMvc.perform(get("/api/v1/auctions/" + a.getPublicId() + "/preview")
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

        mockMvc.perform(get("/api/v1/users/" + sellerPublicId + "/auctions")
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

        mockMvc.perform(get("/api/v1/users/" + sellerPublicId + "/auctions")
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

        mockMvc.perform(get("/api/v1/users/" + sellerPublicId + "/auctions")
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

        mockMvc.perform(get("/api/v1/users/" + sellerPublicId + "/auctions")
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
        mockMvc.perform(get("/api/v1/users/" + sellerPublicId + "/auctions")
                .param("status", "ACTIVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1));
    }

    @Test
    void getUserAuctions_rejectsNonActiveStatus() throws Exception {
        mockMvc.perform(get("/api/v1/users/" + sellerPublicId + "/auctions")
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

        mockMvc.perform(get("/api/v1/users/" + sellerPublicId + "/auctions")
                .param("status", "ACTIVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(3))
                .andExpect(jsonPath("$.content[0].publicId").value(aSoonest.getPublicId().toString()))
                .andExpect(jsonPath("$.content[1].publicId").value(aMiddle.getPublicId().toString()))
                .andExpect(jsonPath("$.content[2].publicId").value(aLater.getPublicId().toString()));
    }

    @Test
    void getUserAuctions_nonexistentUser_returns200EmptyPage() throws Exception {
        // Endpoint is intentionally permissive about nonexistent user publicIds to
        // avoid leaking user existence on this public surface. A 404 would
        // let callers enumerate valid user IDs by diffing status codes.
        mockMvc.perform(get("/api/v1/users/00000000-0000-0000-0000-000099999999/auctions")
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
     * Stubs the SL World API for the seller's default parcel UUID so that
     * POST /api/v1/auctions (create) and PUT /api/v1/auctions/{id}/verify can
     * call {@code parcelLookupService.lookup()} without network access.
     * Returns the parcel UUID to use in {@link AuctionCreateRequest}.
     */
    private UUID seedParcel() throws Exception {
        UUID regionUuid = UUID.randomUUID();
        UUID parcelUuid = UUID.fromString("33333333-3333-3333-3333-333333333333");
        UUID ownerUuid = UUID.fromString("44444444-4444-4444-4444-444444444444");
        when(worldApi.fetchParcelPage(parcelUuid)).thenReturn(
                Mono.just(new ParcelPageData(new ParcelMetadata(
                        parcelUuid,
                ownerUuid,
                "agent",
                null,
                "Seed Parcel",
                "Coniston",
                1024,
                "Seed description",
                "http://example.com/snap.jpg",
                null,
                128.0,
                64.0,
                22.0), regionUuid)));
        when(worldApi.fetchRegionPage(regionUuid)).thenReturn(
                Mono.just(new RegionPageData(regionUuid, "Coniston", 1014.0, 1014.0, "M_NOT")));
        return parcelUuid;
    }

    private Auction seedAuction(AuctionStatus status, boolean listingFeePaid, int bidCount) {
        return seedAuctionFor(sellerParcelUuid, "agent", status, listingFeePaid, bidCount,
                VerificationMethod.UUID_ENTRY);
    }

    private Auction seedAuctionFor(UUID parcelUuid, AuctionStatus status, boolean listingFeePaid) {
        // Sub-spec 2 §7.1 — verificationMethod is null until the seller picks
        // one at the verify trigger. Group-owned parcel tests rely on this.
        return seedAuctionFor(parcelUuid, "agent", status, listingFeePaid, 0, null);
    }

    private Auction seedAuctionFor(UUID parcelUuid, AuctionStatus status,
            boolean listingFeePaid, int bidCount, VerificationMethod method) {
        return seedAuctionFor(parcelUuid, "agent", status, listingFeePaid, bidCount, method);
    }

    private Auction seedAuctionFor(UUID parcelUuid, String ownerType, AuctionStatus status,
            boolean listingFeePaid, int bidCount, VerificationMethod method) {
        User seller = userRepository.findById(sellerId).orElseThrow();
        Auction a = Auction.builder()
                .title("Test listing")
                .slParcelUuid(parcelUuid)
                .seller(seller)
                .status(status)
                .verificationMethod(method)
                .startingBid(1000L)
                .durationHours(168)
                .snipeProtect(false)
                .listingFeePaid(listingFeePaid)
                .currentBid(0L)
                .bidCount(bidCount)
                .consecutiveWorldApiFailures(0)
                .commissionRate(new BigDecimal("0.05"))
                .agentFeeRate(BigDecimal.ZERO)
                .build();
        if (status == AuctionStatus.ACTIVE) {
            OffsetDateTime now = OffsetDateTime.now();
            a.setStartsAt(now.minusHours(1));
            a.setEndsAt(now.plusDays(1));
            a.setOriginalEndsAt(now.plusDays(1));
        }
        a = auctionRepository.save(a);
        a.setParcelSnapshot(AuctionParcelSnapshot.builder()
                .slParcelUuid(parcelUuid)
                .ownerUuid(UUID.fromString("44444444-4444-4444-4444-444444444444"))
                .ownerType(ownerType)
                .parcelName("Test Parcel")
                .regionName("Coniston")
                .regionMaturityRating("GENERAL")
                .areaSqm(1024)
                .positionX(128.0).positionY(64.0).positionZ(22.0)
                .build());
        return auctionRepository.save(a);
    }

    /**
     * Seeds an ACTIVE auction over the default seller parcel and attaches
     * {@code n} {@link AuctionPhoto} rows with sequential sort orders. Returns
     * the auction publicId so callers can assert on the JSON shape of the photos
     * array surfaced by the listing-detail endpoint.
     */
    private UUID seedActiveAuctionWithPhotos(int n) {
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
        return a.getPublicId();
    }

    /**
     * Seeds an ACTIVE auction and primes the seller's reputation counters so
     * the listing-detail endpoint's seller card has values to surface. The
     * {@code cancelledWithBids} arg is set on the user but must NOT appear in
     * the response — a regression-guard test asserts that explicitly.
     */
    private UUID seedActiveAuctionWithSellerRating(
            BigDecimal avgRating, int reviewCount,
            int completedSales, int cancelledWithBids) {
        User seller = userRepository.findById(sellerId).orElseThrow();
        seller.setAvgSellerRating(avgRating);
        seller.setTotalSellerReviews(reviewCount);
        seller.setCompletedSales(completedSales);
        seller.setCancelledWithBids(cancelledWithBids);
        userRepository.save(seller);
        Auction a = seedAuction(AuctionStatus.ACTIVE, false, 0);
        return a.getPublicId();
    }

    /**
     * Returns a unique parcel UUID per seed int. Since parcels are no longer
     * persisted separately, this just provides a unique UUID for each auction.
     */
    private UUID seedExtraParcel(int seed) {
        return new UUID(0L, 0x10000000L + seed);
    }

    /**
     * Returns a UUID representing a group-owned parcel. The ownerType="group"
     * is set on the AuctionParcelSnapshot when calling seedAuctionFor with
     * this UUID and explicitly passing ownerType="group".
     */
    private UUID seedGroupOwnedParcel() {
        return UUID.fromString("55555555-5555-5555-5555-555555555555");
    }
}
