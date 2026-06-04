package com.slparcelauctions.backend.promotion;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.slparcelauctions.backend.auth.test.WithMockAuthPrincipal;
import com.slparcelauctions.backend.promotion.exception.NotAuctionSellerException;
import com.slparcelauctions.backend.promotion.exception.PromotionAlreadyActiveException;
import com.slparcelauctions.backend.wallet.exception.InsufficientAvailableBalanceException;

/**
 * Slice-style tests for {@link PromotionController}. Uses {@code @SpringBootTest} +
 * {@code @MockitoBean} to stub {@link PromotionService} while running the real
 * security filter chain. {@link WithMockAuthPrincipal} inserts an
 * {@code AuthPrincipal} into the SecurityContext without minting real JWTs.
 * Heavy background jobs are disabled via {@link TestPropertySource}.
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
    "slpa.notifications.sl-im.cleanup.enabled=false",
    "slpa.realty.invitation-expiry.enabled=false"
})
class PromotionControllerSliceTest {

    private static final UUID AUCTION_PUBLIC_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000042");
    private static final UUID SLOT_PUBLIC_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000007");
    private static final long SELLER_ID = 100L;

    @Autowired MockMvc mvc;

    @MockitoBean PromotionService promotionService;

    private String requestBody() {
        return "{\"auctionPublicId\":\"" + AUCTION_PUBLIC_ID + "\"}";
    }

    private PromotionService.PurchaseResult stubResult() {
        FeaturedBoardSlot slot = Mockito.mock(FeaturedBoardSlot.class);
        Mockito.when(slot.getPublicId()).thenReturn(SLOT_PUBLIC_ID);
        Mockito.when(slot.getBoardIndex()).thenReturn(1);
        Mockito.when(slot.getPosition()).thenReturn(0);
        return new PromotionService.PurchaseResult(slot, 4_500L);
    }

    // ----- Happy path -----

    @Test
    @WithMockAuthPrincipal(userId = SELLER_ID)
    void buyFeatured_authenticated_returns201WithBody() throws Exception {
        PromotionService.PurchaseResult result = stubResult();
        when(promotionService.purchaseFeatured(eq(SELLER_ID), eq(AUCTION_PUBLIC_ID)))
                .thenReturn(result);

        mvc.perform(post("/api/v1/me/promotions/featured")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.slotPublicId").value(SLOT_PUBLIC_ID.toString()))
                .andExpect(jsonPath("$.boardIndex").value(1))
                .andExpect(jsonPath("$.position").value(0))
                .andExpect(jsonPath("$.priceLindens").value(500))
                .andExpect(jsonPath("$.newBalanceLindens").value(4_500));
    }

    // ----- Domain exception: already active -----

    @Test
    @WithMockAuthPrincipal(userId = SELLER_ID)
    void buyFeatured_alreadyActive_returns409() throws Exception {
        when(promotionService.purchaseFeatured(eq(SELLER_ID), any(UUID.class)))
                .thenThrow(new PromotionAlreadyActiveException(AUCTION_PUBLIC_ID));

        mvc.perform(post("/api/v1/me/promotions/featured")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROMOTION_ALREADY_ACTIVE"));
    }

    // ----- Domain exception: not the seller -----

    @Test
    @WithMockAuthPrincipal(userId = 999L)
    void buyFeatured_notSeller_returns403() throws Exception {
        when(promotionService.purchaseFeatured(eq(999L), any(UUID.class)))
                .thenThrow(new NotAuctionSellerException(AUCTION_PUBLIC_ID));

        mvc.perform(post("/api/v1/me/promotions/featured")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_AUCTION_SELLER"));
    }

    // ----- Domain exception: insufficient balance -----

    @Test
    @WithMockAuthPrincipal(userId = SELLER_ID)
    void buyFeatured_insufficientBalance_returns422() throws Exception {
        when(promotionService.purchaseFeatured(eq(SELLER_ID), any(UUID.class)))
                .thenThrow(new InsufficientAvailableBalanceException(100L, 500L));

        mvc.perform(post("/api/v1/me/promotions/featured")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_AVAILABLE_BALANCE"))
                .andExpect(jsonPath("$.available").value(100))
                .andExpect(jsonPath("$.requested").value(500));
    }

    // ----- Anonymous caller -----

    @Test
    void buyFeatured_anonymous_returns401() throws Exception {
        mvc.perform(post("/api/v1/me/promotions/featured")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody()))
                .andExpect(status().isUnauthorized());
    }

    // ----- Bean Validation: null auctionPublicId -----

    @Test
    @WithMockAuthPrincipal(userId = SELLER_ID)
    void buyFeatured_nullAuctionPublicId_returns400() throws Exception {
        mvc.perform(post("/api/v1/me/promotions/featured")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"auctionPublicId\":null}"))
                .andExpect(status().isBadRequest());
    }
}
