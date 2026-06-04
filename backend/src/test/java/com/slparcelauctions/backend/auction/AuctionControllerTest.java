package com.slparcelauctions.backend.auction;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slparcelauctions.backend.auction.dto.BrokerCancelRequest;
import com.slparcelauctions.backend.auction.dto.SellerAuctionResponse;
import com.slparcelauctions.backend.auction.exception.AuctionExceptionHandler;
import com.slparcelauctions.backend.auction.exception.AuctionNotFoundException;
import com.slparcelauctions.backend.auction.exception.BrokerCancelNotApplicableException;
import com.slparcelauctions.backend.auth.JwtService;
import com.slparcelauctions.backend.auth.test.WithMockAuthPrincipal;
import com.slparcelauctions.backend.common.exception.GlobalExceptionHandler;
import com.slparcelauctions.backend.escrow.EscrowRepository;
import com.slparcelauctions.backend.realty.exception.RealtyExceptionHandler;
import com.slparcelauctions.backend.realty.exception.RealtyGroupPermissionDeniedException;
import com.slparcelauctions.backend.realty.listing.RealtyGroupListingService;
import com.slparcelauctions.backend.realty.permission.RealtyGroupPermission;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Slice tests for {@link AuctionController}'s broker-cancel endpoint (Realty
 * Groups E spec §5.2). Filters off — the security filter chain is exercised
 * elsewhere; the auth principal is injected via {@code @WithMockAuthPrincipal}.
 *
 * <p>Both the {@link AuctionExceptionHandler} and {@link RealtyExceptionHandler}
 * are imported because the broker-cancel path raises exceptions from both
 * packages: {@link BrokerCancelNotApplicableException} (auction) → 422, and
 * {@link RealtyGroupPermissionDeniedException} (realty) → 403.
 */
@WebMvcTest(controllers = AuctionController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({GlobalExceptionHandler.class, AuctionExceptionHandler.class, RealtyExceptionHandler.class})
class AuctionControllerTest {

    @Autowired private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean private AuctionService auctionService;
    @MockitoBean private RealtyGroupListingService realtyGroupListingService;
    @MockitoBean private AuctionVerificationService verificationService;
    @MockitoBean private CancellationService cancellationService;
    @MockitoBean private AuctionDtoMapper mapper;
    @MockitoBean private UserRepository userRepository;
    @MockitoBean private EscrowRepository escrowRepository;
    @MockitoBean private JwtService jwtService;

    private static final long CALLER_USER_ID = 7L;
    private static final long AUCTION_INTERNAL_ID = 42L;
    private static final UUID AUCTION_PUBLIC_ID =
            UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    @BeforeEach
    void setUp() {
        // requireVerified() hits UserRepository.findById — default the caller
        // to a verified user so each test only needs to override for the
        // unverified-path coverage.
        User verifiedCaller = User.builder()
                .username("broker@example.com")
                .email("broker@example.com")
                .passwordHash("x")
                .verified(true)
                .build();
        when(userRepository.findById(CALLER_USER_ID))
                .thenReturn(Optional.of(verifiedCaller));
    }

    private String body(String reason) throws Exception {
        return objectMapper.writeValueAsString(new BrokerCancelRequest(reason));
    }

    private SellerAuctionResponse stubResponse() {
        // Mapper output is mocked — we only assert the status code + the
        // mapped publicId field round-trips through the controller. Pad
        // unused fields with nulls / zeros to keep the test focused.
        return new SellerAuctionResponse(
                AUCTION_PUBLIC_ID,
                UUID.fromString("00000000-0000-0000-0000-0000000000b1"),
                /* seller */ null,
                "Sample Listing",
                null,
                AuctionStatus.CANCELLED,
                null, null,
                100L, 50L, null, null, 0L, 0,
                null, 0L, null,
                24, false, null,
                null, null, null,
                null,
                java.util.List.of(),
                java.util.List.of(),
                false, null, null, null,
                new java.math.BigDecimal("0.05"), null,
                null, null, null, null,
                /* endOutcome */ null,
                /* finalBidAmount */ null,
                /* winnerDisplayName */ null,
                null, null,
                /* featuredPriceLindens */ 500L,
                /* alreadyFeatured */ false);
    }

    @Test
    @WithMockAuthPrincipal(userId = CALLER_USER_ID)
    void brokerCancel_200OnHappyPath() throws Exception {
        Auction loaded = mock(Auction.class);
        when(loaded.getId()).thenReturn(AUCTION_INTERNAL_ID);
        Auction cancelled = mock(Auction.class);
        when(auctionService.loadAnyByPublicId(eq(AUCTION_PUBLIC_ID))).thenReturn(loaded);
        when(cancellationService.brokerCancel(
                eq(CALLER_USER_ID), eq(AUCTION_INTERNAL_ID), eq("Listing withdrawn at owner request."), anyString()))
                .thenReturn(cancelled);
        when(mapper.toSellerResponse(eq(cancelled))).thenReturn(stubResponse());

        mockMvc.perform(post("/api/v1/auctions/" + AUCTION_PUBLIC_ID + "/broker-cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Listing withdrawn at owner request.")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicId").value(AUCTION_PUBLIC_ID.toString()))
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        verify(cancellationService).brokerCancel(
                eq(CALLER_USER_ID), eq(AUCTION_INTERNAL_ID),
                eq("Listing withdrawn at owner request."), anyString());
    }

    @Test
    @WithMockAuthPrincipal(userId = CALLER_USER_ID)
    void brokerCancel_403WithoutManageAllListings() throws Exception {
        Auction loaded = mock(Auction.class);
        when(loaded.getId()).thenReturn(AUCTION_INTERNAL_ID);
        when(auctionService.loadAnyByPublicId(eq(AUCTION_PUBLIC_ID))).thenReturn(loaded);
        when(cancellationService.brokerCancel(anyLong(), anyLong(), anyString(), anyString()))
                .thenThrow(new RealtyGroupPermissionDeniedException(
                        RealtyGroupPermission.MANAGE_ALL_LISTINGS));

        mockMvc.perform(post("/api/v1/auctions/" + AUCTION_PUBLIC_ID + "/broker-cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Trying to cancel without permission.")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REALTY_GROUP_PERMISSION_DENIED"))
                .andExpect(jsonPath("$.missingPermission").value("MANAGE_ALL_LISTINGS"));
    }

    @Test
    @WithMockAuthPrincipal(userId = CALLER_USER_ID)
    void brokerCancel_422OnNonCase3() throws Exception {
        Auction loaded = mock(Auction.class);
        when(loaded.getId()).thenReturn(AUCTION_INTERNAL_ID);
        when(auctionService.loadAnyByPublicId(eq(AUCTION_PUBLIC_ID))).thenReturn(loaded);
        when(cancellationService.brokerCancel(anyLong(), anyLong(), anyString(), anyString()))
                .thenThrow(new BrokerCancelNotApplicableException(
                        AUCTION_PUBLIC_ID,
                        "Broker-cancel only applies to case-3 (SL-group-owned) listings."));

        mockMvc.perform(post("/api/v1/auctions/" + AUCTION_PUBLIC_ID + "/broker-cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Cancelling a case-1 listing.")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BROKER_CANCEL_NOT_APPLICABLE"))
                .andExpect(jsonPath("$.auctionPublicId").value(AUCTION_PUBLIC_ID.toString()));
    }

    @Test
    @WithMockAuthPrincipal(userId = CALLER_USER_ID)
    void brokerCancel_404OnUnknownAuction() throws Exception {
        when(auctionService.loadAnyByPublicId(eq(AUCTION_PUBLIC_ID)))
                .thenThrow(new AuctionNotFoundException(AUCTION_PUBLIC_ID));

        mockMvc.perform(post("/api/v1/auctions/" + AUCTION_PUBLIC_ID + "/broker-cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Unknown auction.")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AUCTION_NOT_FOUND"));

        // Cancellation service is never reached when the lookup fails.
        verify(cancellationService, never()).brokerCancel(anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    @WithMockAuthPrincipal(userId = CALLER_USER_ID)
    void brokerCancel_400OnEmptyReason() throws Exception {
        mockMvc.perform(post("/api/v1/auctions/" + AUCTION_PUBLIC_ID + "/broker-cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("   ")))
                .andExpect(status().isBadRequest());

        // Bean Validation short-circuits before any service is reached.
        verify(auctionService, never()).loadAnyByPublicId(any());
        verify(cancellationService, never()).brokerCancel(anyLong(), anyLong(), anyString(), anyString());
    }
}
