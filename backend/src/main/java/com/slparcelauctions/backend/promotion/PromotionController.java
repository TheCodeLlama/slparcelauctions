package com.slparcelauctions.backend.promotion;

import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.promotion.dto.PurchaseFeaturedRequest;
import com.slparcelauctions.backend.promotion.dto.PurchaseFeaturedResponse;
import com.slparcelauctions.backend.promotion.exception.NotAuctionSellerException;
import com.slparcelauctions.backend.promotion.exception.PromotionAlreadyActiveException;
import com.slparcelauctions.backend.wallet.exception.InsufficientAvailableBalanceException;

import jakarta.servlet.http.HttpServletRequest;
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

    @ExceptionHandler(PromotionAlreadyActiveException.class)
    public ProblemDetail handleAlreadyActive(PromotionAlreadyActiveException e,
                                             HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        pd.setType(URI.create("https://slpa.example/problems/promotion-already-active"));
        pd.setTitle("Promotion already active");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "PROMOTION_ALREADY_ACTIVE");
        return pd;
    }

    @ExceptionHandler(NotAuctionSellerException.class)
    public ProblemDetail handleNotSeller(NotAuctionSellerException e,
                                         HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
        pd.setType(URI.create("https://slpa.example/problems/not-auction-seller"));
        pd.setTitle("Not auction seller");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "NOT_AUCTION_SELLER");
        return pd;
    }

    /**
     * {@link WalletExceptionHandler} is scoped to {@code wallet.me} only, so
     * {@link InsufficientAvailableBalanceException} thrown from the promotion path
     * would fall through to the global 500 handler without this local mapping.
     */
    @ExceptionHandler(InsufficientAvailableBalanceException.class)
    public ProblemDetail handleInsufficientBalance(InsufficientAvailableBalanceException e,
                                                    HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        pd.setType(URI.create("https://slpa.example/problems/insufficient-available-balance"));
        pd.setTitle("Insufficient available balance");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "INSUFFICIENT_AVAILABLE_BALANCE");
        pd.setProperty("available", e.getAvailable());
        pd.setProperty("requested", e.getRequested());
        return pd;
    }
}
