package com.slparcelauctions.backend.promotion;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.slparcelauctions.backend.promotion.exception.InvalidBoardIndexException;
import com.slparcelauctions.backend.promotion.exception.NotAuctionSellerException;
import com.slparcelauctions.backend.promotion.exception.PromotionAlreadyActiveException;
import com.slparcelauctions.backend.promotion.exception.SlotAlreadyReleasedException;
import com.slparcelauctions.backend.wallet.exception.InsufficientAvailableBalanceException;

/**
 * Domain exception handler scoped to the promotion package. Mirrors the
 * AuctionExceptionHandler / WalletExceptionHandler / RealtyExceptionHandler
 * pattern used elsewhere in the codebase.
 */
@RestControllerAdvice(basePackages = "com.slparcelauctions.backend.promotion")
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class PromotionExceptionHandler {

    @ExceptionHandler(PromotionAlreadyActiveException.class)
    public ProblemDetail handleAlreadyActive(PromotionAlreadyActiveException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        pd.setProperty("code", "PROMOTION_ALREADY_ACTIVE");
        return pd;
    }

    @ExceptionHandler(NotAuctionSellerException.class)
    public ProblemDetail handleNotSeller(NotAuctionSellerException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
        pd.setProperty("code", "NOT_AUCTION_SELLER");
        return pd;
    }

    @ExceptionHandler(InvalidBoardIndexException.class)
    public ProblemDetail handleInvalidBoardIndex(InvalidBoardIndexException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        pd.setProperty("code", "INVALID_BOARD_INDEX");
        return pd;
    }

    @ExceptionHandler(SlotAlreadyReleasedException.class)
    public ProblemDetail handleSlotAlreadyReleased(SlotAlreadyReleasedException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        pd.setProperty("code", "SLOT_ALREADY_RELEASED");
        return pd;
    }

    @ExceptionHandler(InsufficientAvailableBalanceException.class)
    public ProblemDetail handleInsufficientBalance(InsufficientAvailableBalanceException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        pd.setProperty("code", "INSUFFICIENT_AVAILABLE_BALANCE");
        pd.setProperty("available", e.getAvailable());
        pd.setProperty("requested", e.getRequested());
        return pd;
    }
}
