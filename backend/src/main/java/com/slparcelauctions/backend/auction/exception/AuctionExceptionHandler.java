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
        // Bid-placement paths hit this when auction.status != ACTIVE — surface
        // the bid-oriented error code so the frontend can distinguish it from
        // a seller-driven update/cancel conflict. All other callers (UPDATE,
        // CANCEL, VERIFY) keep the generic AUCTION_INVALID_STATE code.
        String code = "BID".equals(e.getAttemptedAction())
                ? "AUCTION_NOT_ACTIVE"
                : "AUCTION_INVALID_STATE";
        pd.setProperty("code", code);
        pd.setProperty("auctionId", e.getAuctionId());
        pd.setProperty("currentState", e.getCurrentState().name());
        pd.setProperty("attemptedAction", e.getAttemptedAction());
        return pd;
    }

    @ExceptionHandler(BidTooLowException.class)
    public ProblemDetail handleBidTooLow(BidTooLowException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, e.getMessage());
        pd.setTitle("Bid Too Low");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "BID_TOO_LOW");
        pd.setProperty("minRequired", e.getMinRequired());
        return pd;
    }

    @ExceptionHandler(SellerCannotBidException.class)
    public ProblemDetail handleSellerCannotBid(SellerCannotBidException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.FORBIDDEN, e.getMessage());
        pd.setTitle("Seller Cannot Bid");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "SELLER_CANNOT_BID");
        return pd;
    }

    @ExceptionHandler(NotVerifiedException.class)
    public ProblemDetail handleNotVerified(NotVerifiedException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.FORBIDDEN, e.getMessage());
        pd.setTitle("Not Verified");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "NOT_VERIFIED");
        return pd;
    }

    @ExceptionHandler(AuctionAlreadyEndedException.class)
    public ProblemDetail handleAuctionAlreadyEnded(
            AuctionAlreadyEndedException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, e.getMessage());
        pd.setTitle("Auction Already Ended");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "AUCTION_ALREADY_ENDED");
        pd.setProperty("endsAt", e.getEndsAt().toString());
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

    @ExceptionHandler(ProxyBidAlreadyExistsException.class)
    public ProblemDetail handleProxyBidAlreadyExists(
            ProxyBidAlreadyExistsException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, e.getMessage());
        pd.setTitle("Proxy Bid Already Exists");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "PROXY_BID_ALREADY_EXISTS");
        return pd;
    }

    @ExceptionHandler(ProxyBidNotFoundException.class)
    public ProblemDetail handleProxyBidNotFound(
            ProxyBidNotFoundException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, e.getMessage());
        pd.setTitle("Proxy Bid Not Found");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "PROXY_BID_NOT_FOUND");
        return pd;
    }

    @ExceptionHandler(InvalidProxyStateException.class)
    public ProblemDetail handleInvalidProxyState(
            InvalidProxyStateException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, e.getMessage());
        pd.setTitle("Invalid Proxy State");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "INVALID_PROXY_STATE");
        pd.setProperty("reason", e.getReason());
        return pd;
    }

    @ExceptionHandler(InvalidProxyMaxException.class)
    public ProblemDetail handleInvalidProxyMax(
            InvalidProxyMaxException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, e.getMessage());
        pd.setTitle("Invalid Proxy Max");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "INVALID_PROXY_MAX");
        pd.setProperty("reason", e.getReason());
        return pd;
    }

    @ExceptionHandler(CannotCancelWinningProxyException.class)
    public ProblemDetail handleCannotCancelWinningProxy(
            CannotCancelWinningProxyException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, e.getMessage());
        pd.setTitle("Cannot Cancel Winning Proxy");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "CANNOT_CANCEL_WINNING_PROXY");
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
