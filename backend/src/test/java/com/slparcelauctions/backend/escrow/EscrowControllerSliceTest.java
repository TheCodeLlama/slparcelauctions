package com.slparcelauctions.backend.escrow;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.slparcelauctions.backend.auth.JwtAuthenticationEntryPoint;
import com.slparcelauctions.backend.auth.JwtAuthenticationFilter;
import com.slparcelauctions.backend.auth.JwtService;
import com.slparcelauctions.backend.auth.config.JwtConfig;
import com.slparcelauctions.backend.auth.test.WithMockAuthPrincipal;
import com.slparcelauctions.backend.bot.BotSharedSecretAuthorizer;
import com.slparcelauctions.backend.notification.slim.internal.SlImInternalConfig;
import com.slparcelauctions.backend.config.SecurityConfig;
import com.slparcelauctions.backend.escrow.dto.EscrowDisputeRequest;
import com.slparcelauctions.backend.escrow.dto.EscrowStatusResponse;
import com.slparcelauctions.backend.escrow.exception.EscrowAccessDeniedException;
import com.slparcelauctions.backend.escrow.exception.EscrowExceptionHandler;
import com.slparcelauctions.backend.escrow.exception.EscrowNotFoundException;
import com.slparcelauctions.backend.escrow.exception.IllegalEscrowTransitionException;

/**
 * Slice tests for {@link EscrowController}. Stubs {@link EscrowService}
 * return values / thrown exceptions and exercises the real security
 * filter chain so unauthenticated calls 401 via
 * {@link JwtAuthenticationEntryPoint}. Authenticated 200/403/404/409 paths
 * use {@link WithMockAuthPrincipal} to drop an {@code AuthPrincipal} into
 * the SecurityContext without minting real JWTs.
 */
@WebMvcTest(EscrowController.class)
@Import({SlImInternalConfig.class, SecurityConfig.class, JwtAuthenticationFilter.class,
        JwtAuthenticationEntryPoint.class, EscrowExceptionHandler.class})
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "jwt.secret=dGVzdC1zZWNyZXQtdGVzdC1zZWNyZXQtdGVzdC1zZWNyZXQtdGVzdA==",
        "jwt.access-token-lifetime=PT15M",
        "jwt.refresh-token-lifetime=P7D",
        "slpa.notifications.cleanup.enabled=false",
        "slpa.notifications.sl-im.cleanup.enabled=false"
})
class EscrowControllerSliceTest {

    private static final Long AUCTION_ID = 42L;
    private static final Long ESCROW_ID = 7L;
    private static final Long SELLER_ID = 100L;
    private static final Long WINNER_ID = 200L;

    @Autowired MockMvc mockMvc;
    @MockitoBean EscrowService escrowService;
    @MockitoBean JwtService jwtService;
    @MockitoBean JwtConfig jwtConfig;
    // SecurityConfig depends on BotSharedSecretAuthorizer (Epic 06 Task 3).
    @MockitoBean BotSharedSecretAuthorizer botSharedSecretAuthorizer;

    private EscrowStatusResponse stubResponse(EscrowState state) {
        OffsetDateTime now = OffsetDateTime.now();
        return new EscrowStatusResponse(
                ESCROW_ID, AUCTION_ID, state,
                5_000L, 250L, 4_750L,
                now.plusHours(48), null, null, null, null,
                null, null, null,
                null, null, null,
                List.of());
    }

    private MockMultipartFile disputePart(String reasonCategory, String description) {
        String json = String.format(
                "{\"reasonCategory\":\"%s\",\"description\":\"%s\"}",
                reasonCategory, description);
        return new MockMultipartFile("body", "body", MediaType.APPLICATION_JSON_VALUE,
                json.getBytes());
    }

    // ----- GET /escrow -----

    @Test
    @WithMockAuthPrincipal(userId = 100)
    void getStatus_sellerAuthenticated_returns200() throws Exception {
        when(escrowService.getStatus(eq(AUCTION_ID), eq(SELLER_ID)))
                .thenReturn(stubResponse(EscrowState.ESCROW_PENDING));

        mockMvc.perform(get("/api/v1/auctions/" + AUCTION_ID + "/escrow"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.escrowId").value(ESCROW_ID))
                .andExpect(jsonPath("$.auctionId").value(AUCTION_ID))
                .andExpect(jsonPath("$.state").value("ESCROW_PENDING"));
    }

    @Test
    @WithMockAuthPrincipal(userId = 200)
    void getStatus_winnerAuthenticated_returns200() throws Exception {
        when(escrowService.getStatus(eq(AUCTION_ID), eq(WINNER_ID)))
                .thenReturn(stubResponse(EscrowState.ESCROW_PENDING));

        mockMvc.perform(get("/api/v1/auctions/" + AUCTION_ID + "/escrow"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.escrowId").value(ESCROW_ID));
    }

    @Test
    @WithMockAuthPrincipal(userId = 999)
    void getStatus_randomUser_returns403() throws Exception {
        when(escrowService.getStatus(eq(AUCTION_ID), eq(999L)))
                .thenThrow(new EscrowAccessDeniedException());

        mockMvc.perform(get("/api/v1/auctions/" + AUCTION_ID + "/escrow"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ESCROW_FORBIDDEN"));
    }

    @Test
    @WithMockAuthPrincipal(userId = 100)
    void getStatus_noEscrowRow_returns404() throws Exception {
        when(escrowService.getStatus(eq(AUCTION_ID), anyLong()))
                .thenThrow(new EscrowNotFoundException(AUCTION_ID));

        mockMvc.perform(get("/api/v1/auctions/" + AUCTION_ID + "/escrow"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ESCROW_NOT_FOUND"))
                .andExpect(jsonPath("$.auctionId").value(AUCTION_ID));
    }

    @Test
    void getStatus_anonymous_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/auctions/" + AUCTION_ID + "/escrow"))
                .andExpect(status().isUnauthorized());
    }

    // ----- POST /escrow/dispute -----

    @Test
    @WithMockAuthPrincipal(userId = 100)
    void fileDispute_validBody_returns200() throws Exception {
        when(escrowService.fileDispute(eq(AUCTION_ID), any(EscrowDisputeRequest.class),
                eq(SELLER_ID), anyList()))
                .thenReturn(stubResponse(EscrowState.DISPUTED));

        mockMvc.perform(multipart("/api/v1/auctions/" + AUCTION_ID + "/escrow/dispute")
                        .file(disputePart("SELLER_NOT_RESPONSIVE",
                                "Seller has not responded to any messages for 3 days.")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("DISPUTED"));
    }

    @Test
    @WithMockAuthPrincipal(userId = 100)
    void fileDispute_emptyBodyPart_returns400() throws Exception {
        MockMultipartFile emptyBody = new MockMultipartFile("body", "body",
                MediaType.APPLICATION_JSON_VALUE, "{}".getBytes());
        mockMvc.perform(multipart("/api/v1/auctions/" + AUCTION_ID + "/escrow/dispute")
                        .file(emptyBody))
                .andExpect(status().isBadRequest());
        Mockito.verifyNoInteractions(escrowService);
    }

    @Test
    @WithMockAuthPrincipal(userId = 100)
    void fileDispute_descriptionTooShort_returns400() throws Exception {
        mockMvc.perform(multipart("/api/v1/auctions/" + AUCTION_ID + "/escrow/dispute")
                        .file(disputePart("OTHER", "short")))
                .andExpect(status().isBadRequest());
        Mockito.verifyNoInteractions(escrowService);
    }

    @Test
    @WithMockAuthPrincipal(userId = 999)
    void fileDispute_randomUser_returns403() throws Exception {
        when(escrowService.fileDispute(eq(AUCTION_ID), any(EscrowDisputeRequest.class),
                eq(999L), anyList()))
                .thenThrow(new EscrowAccessDeniedException());

        mockMvc.perform(multipart("/api/v1/auctions/" + AUCTION_ID + "/escrow/dispute")
                        .file(disputePart("OTHER",
                                "Ten characters minimum description here.")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ESCROW_FORBIDDEN"));
    }

    @Test
    @WithMockAuthPrincipal(userId = 100)
    void fileDispute_fromTerminalState_returns409() throws Exception {
        when(escrowService.fileDispute(eq(AUCTION_ID), any(EscrowDisputeRequest.class),
                eq(SELLER_ID), anyList()))
                .thenThrow(new IllegalEscrowTransitionException(
                        ESCROW_ID, EscrowState.COMPLETED, EscrowState.DISPUTED));

        mockMvc.perform(multipart("/api/v1/auctions/" + AUCTION_ID + "/escrow/dispute")
                        .file(disputePart("OTHER",
                                "Ten characters minimum description here.")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ESCROW_INVALID_TRANSITION"))
                .andExpect(jsonPath("$.currentState").value("COMPLETED"))
                .andExpect(jsonPath("$.attemptedTarget").value("DISPUTED"));
    }
}
