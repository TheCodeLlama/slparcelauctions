package com.slparcelauctions.backend.promotion;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.promotion.dto.PurchaseFeaturedRequest;
import com.slparcelauctions.backend.promotion.dto.PurchaseFeaturedResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

/**
 * Seller-facing endpoint: purchase a PROMO-01 featured-board slot for one of
 * the caller's active auctions. Debits {@code slpa.promotions.featured-price-lindens}
 * L$ from the seller's wallet atomically with the slot row write.
 *
 * <p>Path: POST /api/v1/me/promotions/featured
 * Auth: JWT bearer (falls under the authenticated catch-all in SecurityConfig).
 */
@RestController
@RequestMapping("/api/v1/me/promotions")
@RequiredArgsConstructor
public class PromotionController {

    private final PromotionService promotionService;
    private final PromotionConfigProperties promotionConfig;

    @PostMapping("/featured")
    @ResponseStatus(HttpStatus.CREATED)
    public PurchaseFeaturedResponse buyFeatured(
            @AuthenticationPrincipal AuthPrincipal caller,
            @Valid @RequestBody PurchaseFeaturedRequest req) {
        var result = promotionService.purchaseFeatured(
                caller.userId(), req.auctionPublicId());
        return new PurchaseFeaturedResponse(
                result.slot().getPublicId(),
                result.slot().getBoardIndex(),
                result.slot().getPosition(),
                promotionConfig.featuredPriceLindens(),
                result.newBalanceLindens()
        );
    }

}
