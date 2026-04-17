package com.slparcelauctions.backend.auction.exception;

import java.net.URI;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * Auction-scoped exceptions. Runs before the common GlobalExceptionHandler
 * by having a higher precedence order. Scoped to the auction package so the
 * IllegalArgumentException -> 400 mapping does not catch exceptions from
 * unrelated packages (Redis, WebClient, auth, etc.).
 */
@RestControllerAdvice(basePackages = "com.slparcelauctions.backend.auction")
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@Slf4j
public class AuctionExceptionHandler {

    @ExceptionHandler(InvalidAuctionStateException.class)
    public ProblemDetail handleInvalidState(InvalidAuctionStateException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, e.getMessage());
        pd.setTitle("Invalid Auction State");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "AUCTION_INVALID_STATE");
        pd.setProperty("auctionId", e.getAuctionId());
        pd.setProperty("currentState", e.getCurrentState().name());
        pd.setProperty("attemptedAction", e.getAttemptedAction());
        return pd;
    }

    @ExceptionHandler(AuctionNotFoundException.class)
    public ProblemDetail handleNotFound(AuctionNotFoundException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, e.getMessage());
        pd.setTitle("Auction Not Found");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "AUCTION_NOT_FOUND");
        pd.setProperty("auctionId", e.getAuctionId());
        return pd;
    }

    @ExceptionHandler(ParcelAlreadyListedException.class)
    public ProblemDetail handleParcelAlreadyListed(
            ParcelAlreadyListedException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                "This parcel is currently in an active auction. You can list it again after that auction ends.");
        pd.setTitle("Parcel Already Listed");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "PARCEL_ALREADY_LISTED");
        pd.setProperty("parcelId", e.getParcelId());
        pd.setProperty("blockingAuctionId", e.getBlockingAuctionId());
        return pd;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, e.getMessage());
        pd.setTitle("Invalid Request");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "INVALID_REQUEST");
        return pd;
    }
}
