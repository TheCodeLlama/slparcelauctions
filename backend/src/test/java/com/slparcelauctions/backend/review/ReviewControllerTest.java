package com.slparcelauctions.backend.review;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auth.JwtService;
import com.slparcelauctions.backend.auth.test.WithMockAuthPrincipal;
import com.slparcelauctions.backend.common.exception.GlobalExceptionHandler;
import com.slparcelauctions.backend.review.dto.AuctionReviewsResponse;
import com.slparcelauctions.backend.review.dto.ReviewDto;
import com.slparcelauctions.backend.review.dto.ReviewSubmitRequest;
import com.slparcelauctions.backend.review.exception.ReviewAlreadySubmittedException;
import com.slparcelauctions.backend.review.exception.ReviewExceptionHandler;
import com.slparcelauctions.backend.review.exception.ReviewIneligibleException;
import com.slparcelauctions.backend.review.exception.ReviewWindowClosedException;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Slice tests for {@link ReviewController}. Mirrors the shape of
 * {@code UserControllerTest}: filters off (the security filter chain is
 * exercised in {@code SecurityConfigTest}), slice + global exception
 * handlers imported so status-code mappings resolve, and both the
 * service and user repository mocked.
 */
@WebMvcTest(controllers = ReviewController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({GlobalExceptionHandler.class, ReviewExceptionHandler.class})
class ReviewControllerTest {

    @Autowired private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean private ReviewService reviewService;
    @MockitoBean private UserRepository userRepository;
    @MockitoBean private AuctionRepository auctionRepository;
    @MockitoBean private JwtService jwtService;

    private static final java.util.UUID REVIEW_ID = java.util.UUID.fromString("00000000-0000-0000-0000-0000000004d2");
    private static final java.util.UUID AUCTION_PUBLIC_ID = java.util.UUID.fromString("00000000-0000-0000-0000-00000000022b");
    private static final long AUCTION_LONG_ID = 555L;

    private ReviewDto sampleDto() {
        return new ReviewDto(
                REVIEW_ID,
                AUCTION_PUBLIC_ID,
                "Lakefront",
                "/api/v1/auctions/" + AUCTION_PUBLIC_ID + "/photos/1/bytes",
                java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "Viewer",
                "/api/v1/users/1/avatar/256",
                java.util.UUID.fromString("00000000-0000-0000-0000-00000000000a"),
                ReviewedRole.SELLER,
                5,
                "Great",
                false,
                true,
                OffsetDateTime.now(),
                null,
                null);
    }

    /** Stubs the AuctionRepository to resolve AUCTION_PUBLIC_ID → AUCTION_LONG_ID. */
    private void stubAuctionLookup() {
        Auction mockAuction = org.mockito.Mockito.mock(Auction.class);
        when(mockAuction.getId()).thenReturn(AUCTION_LONG_ID);
        when(auctionRepository.findByPublicId(AUCTION_PUBLIC_ID))
                .thenReturn(Optional.of(mockAuction));
    }

    @Test
    @WithMockAuthPrincipal(userId = 1L)
    void submitReview_returns201_withDto() throws Exception {
        User caller = User.builder().id(1L).email("test@example.com").passwordHash("x").build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(caller));
        stubAuctionLookup();
        when(reviewService.submit(eq(AUCTION_LONG_ID), any(User.class), any(ReviewSubmitRequest.class)))
                .thenReturn(sampleDto());

        ReviewSubmitRequest req = new ReviewSubmitRequest(5, "Great");
        mockMvc.perform(post("/api/v1/auctions/" + AUCTION_PUBLIC_ID + "/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.publicId").value(REVIEW_ID.toString()))
                .andExpect(jsonPath("$.auctionPublicId").value(AUCTION_PUBLIC_ID.toString()))
                .andExpect(jsonPath("$.pending").value(true))
                .andExpect(jsonPath("$.visible").value(false));
    }

    @Test
    @WithMockAuthPrincipal(userId = 1L)
    void submitReview_ratingOutOfRange_returns400() throws Exception {
        ReviewSubmitRequest req = new ReviewSubmitRequest(6, null);
        mockMvc.perform(post("/api/v1/auctions/" + AUCTION_PUBLIC_ID + "/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.rating").exists());
    }

    @Test
    @WithMockAuthPrincipal(userId = 1L)
    void submitReview_ratingZero_returns400() throws Exception {
        ReviewSubmitRequest req = new ReviewSubmitRequest(0, null);
        mockMvc.perform(post("/api/v1/auctions/" + AUCTION_PUBLIC_ID + "/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.rating").exists());
    }

    @Test
    @WithMockAuthPrincipal(userId = 1L)
    void submitReview_ratingMissing_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/auctions/" + AUCTION_PUBLIC_ID + "/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.rating").exists());
    }

    @Test
    @WithMockAuthPrincipal(userId = 1L)
    void submitReview_textTooLong_returns400() throws Exception {
        String tooLong = "a".repeat(501);
        ReviewSubmitRequest req = new ReviewSubmitRequest(5, tooLong);
        mockMvc.perform(post("/api/v1/auctions/" + AUCTION_PUBLIC_ID + "/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.text").exists());
    }

    @Test
    @WithMockAuthPrincipal(userId = 1L)
    void submitReview_ineligible_returns422_REVIEW_INELIGIBLE() throws Exception {
        User caller = User.builder().id(1L).email("test@example.com").passwordHash("x").build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(caller));
        stubAuctionLookup();
        when(reviewService.submit(eq(AUCTION_LONG_ID), any(User.class), any(ReviewSubmitRequest.class)))
                .thenThrow(new ReviewIneligibleException("Reviews are only accepted once the escrow has completed."));

        ReviewSubmitRequest req = new ReviewSubmitRequest(5, null);
        mockMvc.perform(post("/api/v1/auctions/" + AUCTION_PUBLIC_ID + "/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REVIEW_INELIGIBLE"));
    }

    @Test
    @WithMockAuthPrincipal(userId = 1L)
    void submitReview_windowClosed_returns422_REVIEW_WINDOW_CLOSED() throws Exception {
        User caller = User.builder().id(1L).email("test@example.com").passwordHash("x").build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(caller));
        stubAuctionLookup();
        when(reviewService.submit(eq(AUCTION_LONG_ID), any(User.class), any(ReviewSubmitRequest.class)))
                .thenThrow(new ReviewWindowClosedException("Window closed"));

        ReviewSubmitRequest req = new ReviewSubmitRequest(5, null);
        mockMvc.perform(post("/api/v1/auctions/" + AUCTION_PUBLIC_ID + "/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REVIEW_WINDOW_CLOSED"));
    }

    @Test
    @WithMockAuthPrincipal(userId = 1L)
    void submitReview_duplicate_returns409_REVIEW_ALREADY_SUBMITTED() throws Exception {
        User caller = User.builder().id(1L).email("test@example.com").passwordHash("x").build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(caller));
        stubAuctionLookup();
        when(reviewService.submit(eq(AUCTION_LONG_ID), any(User.class), any(ReviewSubmitRequest.class)))
                .thenThrow(new ReviewAlreadySubmittedException("Already submitted"));

        ReviewSubmitRequest req = new ReviewSubmitRequest(5, null);
        mockMvc.perform(post("/api/v1/auctions/" + AUCTION_PUBLIC_ID + "/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVIEW_ALREADY_SUBMITTED"));
    }

    @Test
    void listReviews_anon_returns200_withVisibleReviewsOnly() throws Exception {
        stubAuctionLookup();
        when(reviewService.listForAuction(eq(AUCTION_LONG_ID), isNull()))
                .thenReturn(new AuctionReviewsResponse(
                        List.of(sampleDto()), null, false, null));

        mockMvc.perform(get("/api/v1/auctions/" + AUCTION_PUBLIC_ID + "/reviews"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviews").isArray())
                .andExpect(jsonPath("$.reviews[0].publicId").value(REVIEW_ID.toString()))
                .andExpect(jsonPath("$.myPendingReview").doesNotExist())
                .andExpect(jsonPath("$.canReview").value(false))
                .andExpect(jsonPath("$.windowClosesAt").doesNotExist());
    }

    @Test
    @WithMockAuthPrincipal(userId = 1L)
    void listReviews_authenticatedParty_returns200_withEnrichedShape() throws Exception {
        User caller = User.builder().id(1L).email("test@example.com").passwordHash("x").build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(caller));
        stubAuctionLookup();
        OffsetDateTime windowCloses = OffsetDateTime.now().plusDays(13);
        when(reviewService.listForAuction(eq(AUCTION_LONG_ID), any(User.class)))
                .thenReturn(new AuctionReviewsResponse(
                        List.of(), null, true, windowCloses));

        mockMvc.perform(get("/api/v1/auctions/" + AUCTION_PUBLIC_ID + "/reviews"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canReview").value(true))
                .andExpect(jsonPath("$.windowClosesAt").exists());
    }
}
