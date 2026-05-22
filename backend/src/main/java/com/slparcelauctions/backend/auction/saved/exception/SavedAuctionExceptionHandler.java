package com.slparcelauctions.backend.auction.saved.exception;

import java.net.URI;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import org.springframework.beans.factory.annotation.Value;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Maps saved-auction exceptions to RFC 7807 {@link ProblemDetail} responses.
 * Scoped to the {@code auction.saved} package so the more general
 * {@code AuctionExceptionHandler} doesn't intercept these first.
 */
@RestControllerAdvice(basePackages = "com.slparcelauctions.backend.auction.saved")
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class SavedAuctionExceptionHandler {

    // Read via @Value rather than the AuctionConfigProperties POJO so this
    // @RestControllerAdvice still wires in @WebMvcTest slices, which load the
    // Environment but not @EnableConfigurationProperties beans.
    private final int savedAuctionsCap;

    public SavedAuctionExceptionHandler(
            @Value("${slpa.auction.saved-auctions-cap}") int savedAuctionsCap) {
        this.savedAuctionsCap = savedAuctionsCap;
    }

    @ExceptionHandler(CannotSavePreActiveException.class)
    public ProblemDetail handleCannotSave(
            CannotSavePreActiveException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
        pd.setTitle("Cannot Save Pre-Active Auction");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "CANNOT_SAVE_PRE_ACTIVE");
        if (e.getAuctionPublicId() != null) {
            pd.setProperty("auctionPublicId", e.getAuctionPublicId().toString());
        }
        pd.setProperty("currentStatus", e.getCurrentStatus());
        return pd;
    }

    @ExceptionHandler(SavedLimitReachedException.class)
    public ProblemDetail handleLimit(
            SavedLimitReachedException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        pd.setTitle("Saved Limit Reached");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "SAVED_LIMIT_REACHED");
        pd.setProperty("cap", savedAuctionsCap);
        return pd;
    }
}
