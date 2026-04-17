package com.slparcelauctions.backend.auction.exception;

import java.net.URI;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.slparcelauctions.backend.user.exception.ImageTooLargeException;
import com.slparcelauctions.backend.user.exception.UnsupportedImageFormatException;

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

    /**
     * Group-owned parcels cannot be verified by UUID_ENTRY or REZZABLE — only
     * SALE_TO_BOT can transfer the parcel to the escrow bot. Sub-spec 2 §7.2.
     * 422 (not 400) so the frontend can distinguish a semantic constraint
     * violation from a malformed request and surface a targeted remediation
     * message.
     */
    @ExceptionHandler(GroupLandRequiresSaleToBotException.class)
    public ProblemDetail handleGroupLand(
            GroupLandRequiresSaleToBotException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        pd.setTitle("Group-owned land requires Sale-to-bot");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "GROUP_LAND_REQUIRES_SALE_TO_BOT");
        return pd;
    }

    /**
     * Photo uploads reuse the shared {@code ImageUploadValidator} which throws
     * {@link UnsupportedImageFormatException} for format + size + dimension
     * failures. The validator's exception lives in {@code user.exception}
     * for backward compatibility with the avatar pipeline, but the auction
     * controller's slice handler needs its own mapping because
     * {@code UserExceptionHandler} is scoped to the user package.
     */
    @ExceptionHandler(UnsupportedImageFormatException.class)
    public ProblemDetail handleUnsupportedImageFormat(
            UnsupportedImageFormatException e, HttpServletRequest req) {
        log.warn("Rejected listing photo: {}", e.getMessage());
        // The shared validator signals byte-count-reject via the dedicated
        // ImageTooLargeException subclass (still catchable here as the parent).
        // Use instanceof rather than a string-prefix match so the mapping is
        // stable even if the validator's message wording changes.
        if (e instanceof ImageTooLargeException) {
            ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "Photo file exceeds the per-upload byte limit.");
            pd.setTitle("Photo Too Large");
            pd.setInstance(URI.create(req.getRequestURI()));
            pd.setProperty("code", "LISTING_PHOTO_TOO_LARGE");
            return pd;
        }
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "Photo must be a valid JPEG, PNG, or WebP within size limits.");
        pd.setTitle("Invalid Listing Photo");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "LISTING_PHOTO_INVALID");
        return pd;
    }

    @ExceptionHandler(PhotoLimitExceededException.class)
    public ProblemDetail handlePhotoLimitExceeded(
            PhotoLimitExceededException e, HttpServletRequest req) {
        log.warn("Photo limit hit: {}/{}", e.getCurrentCount(), e.getMaxAllowed());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.PAYLOAD_TOO_LARGE, e.getMessage());
        pd.setTitle("Photo Limit Exceeded");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "PHOTO_LIMIT_EXCEEDED");
        pd.setProperty("currentCount", e.getCurrentCount());
        pd.setProperty("maxAllowed", e.getMaxAllowed());
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
