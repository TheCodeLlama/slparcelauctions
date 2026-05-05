package com.slparcelauctions.backend.sl;

import java.net.URI;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.slparcelauctions.backend.auction.exception.AuctionNotFoundException;
import com.slparcelauctions.backend.auction.exception.InvalidAuctionStateException;
import com.slparcelauctions.backend.auction.exception.ParcelAlreadyListedException;
import com.slparcelauctions.backend.sl.exception.AvatarAlreadyLinkedException;
import com.slparcelauctions.backend.sl.exception.InvalidSlHeadersException;
import com.slparcelauctions.backend.sl.exception.PenaltyOverpaymentException;
import com.slparcelauctions.backend.verification.exception.AlreadyVerifiedException;
import com.slparcelauctions.backend.verification.exception.CodeCollisionException;
import com.slparcelauctions.backend.verification.exception.CodeNotFoundException;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * Slice-scoped exception handler for {@code /api/v1/sl/**}. Handles both
 * sl-package exceptions and the verification-package exceptions that bubble
 * through from the {@link com.slparcelauctions.backend.verification.VerificationCodeService#consume}
 * call so the SL-side responses (400 for not-found, 409 for collision) override
 * the verification-side defaults (404 for not-found, 409 for collision).
 *
 * <p><strong>Ordering:</strong> Slice handlers run 100 above the global catch-all
 * ({@code @Order(Ordered.LOWEST_PRECEDENCE - 100)}) so they beat
 * {@link com.slparcelauctions.backend.common.exception.GlobalExceptionHandler}'s
 * {@code @ExceptionHandler(Exception.class)} but can still stack with each other
 * via further tiebreaking. Matches the convention established by
 * {@link com.slparcelauctions.backend.verification.VerificationExceptionHandler}.
 *
 * <p>Spring selects between this handler and {@code VerificationExceptionHandler}
 * by the throwing controller's package: requests handled by an
 * {@code sl}-package controller dispatch here, requests handled by a
 * {@code verification}-package controller dispatch to the verification handler.
 * Both handlers can claim the same exception types - that's deliberate.
 */
@RestControllerAdvice(basePackages = "com.slparcelauctions.backend.sl")
@Order(Ordered.LOWEST_PRECEDENCE - 100)
@Slf4j
public class SlExceptionHandler {

    @ExceptionHandler(InvalidSlHeadersException.class)
    public ProblemDetail handleInvalidHeaders(InvalidSlHeadersException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
        pd.setType(URI.create("https://slpa.example/problems/sl/invalid-headers"));
        pd.setTitle("Invalid SL headers");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "SL_INVALID_HEADERS");
        return pd;
    }

    @ExceptionHandler(AvatarAlreadyLinkedException.class)
    public ProblemDetail handleAvatarLinked(AvatarAlreadyLinkedException e, HttpServletRequest req) {
        log.warn("SL verify rejected: avatar already linked (avatarUuid={})", e.getAvatarUuid());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, "This SL avatar is already linked to another SLParcels account.");
        pd.setType(URI.create("https://slpa.example/problems/sl/avatar-already-linked"));
        pd.setTitle("Avatar already linked");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "SL_AVATAR_ALREADY_LINKED");
        return pd;
    }

    @ExceptionHandler(AlreadyVerifiedException.class)
    public ProblemDetail handleAlreadyVerified(AlreadyVerifiedException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, "This account is already linked to an SL avatar.");
        pd.setType(URI.create("https://slpa.example/problems/sl/already-verified"));
        pd.setTitle("Account already verified");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "SL_ALREADY_VERIFIED");
        return pd;
    }

    @ExceptionHandler(CodeNotFoundException.class)
    public ProblemDetail handleCodeNotFound(CodeNotFoundException e, HttpServletRequest req) {
        // SL path uses 400 (the LSL caller posted a bad code, from its perspective).
        // The verification-package handler would default to 404 for the same exception;
        // this narrower advice takes precedence for requests that reach sl-package
        // controllers because Spring dispatches by the throwing controller's package.
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "Code not found, expired, or already used. Please generate a new code from your dashboard.");
        pd.setType(URI.create("https://slpa.example/problems/sl/code-not-found"));
        pd.setTitle("Verification failed");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "SL_CODE_NOT_FOUND");
        return pd;
    }

    @ExceptionHandler(CodeCollisionException.class)
    public ProblemDetail handleCollision(CodeCollisionException e, HttpServletRequest req) {
        // Already logged at WARN by VerificationCodeService.consume - do not double-log.
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, "Please generate a new code from your dashboard.");
        pd.setType(URI.create("https://slpa.example/problems/sl/code-collision"));
        pd.setTitle("Verification failed");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "SL_CODE_COLLISION");
        return pd;
    }

    // -------------------------------------------------------------------------
    // Method B (POST /api/v1/sl/parcel/verify) — auction-package exceptions.
    // The auction-package {@code AuctionExceptionHandler} is scoped to
    // {@code basePackages = "backend.auction"} and does not apply to this
    // controller; we re-handle the same exception types here so the LSL
    // response stays narrow (no leakage of unrelated auction internals).
    // -------------------------------------------------------------------------

    @ExceptionHandler(AuctionNotFoundException.class)
    public ProblemDetail handleAuctionNotFound(
            AuctionNotFoundException e, HttpServletRequest req) {
        // 400 rather than 404: from the LSL caller's perspective this is a bad
        // code (the caller does not know auction ids). Matches the code-not-found
        // narrowness above.
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "Verification code does not match any active auction.");
        pd.setType(URI.create("https://slpa.example/problems/sl/auction-not-found"));
        pd.setTitle("Verification failed");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "SL_AUCTION_NOT_FOUND");
        return pd;
    }

    @ExceptionHandler(InvalidAuctionStateException.class)
    public ProblemDetail handleInvalidAuctionState(
            InvalidAuctionStateException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                "Auction is not awaiting in-world verification. Re-trigger verification from the dashboard.");
        pd.setType(URI.create("https://slpa.example/problems/sl/auction-invalid-state"));
        pd.setTitle("Auction not pending verification");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "SL_AUCTION_INVALID_STATE");
        pd.setProperty("currentState", e.getCurrentState().name());
        return pd;
    }

    @ExceptionHandler(ParcelAlreadyListedException.class)
    public ProblemDetail handleParcelAlreadyListed(
            ParcelAlreadyListedException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                "This parcel is already part of another active auction.");
        pd.setType(URI.create("https://slpa.example/problems/sl/parcel-already-listed"));
        pd.setTitle("Parcel already listed");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "SL_PARCEL_ALREADY_LISTED");
        return pd;
    }

    /**
     * Penalty payment exceeds the seller's outstanding balance
     * (Epic 08 sub-spec 2 §7.6). 422 because the request is well-formed
     * and authenticated but cannot be applied without driving the
     * balance negative — the LSL terminal should refund the excess
     * L$ in-world. Both {@code requested} and {@code available} surface
     * on the ProblemDetail so the in-world script can render an
     * accurate message ("you tried to pay L${requested} but only owe
     * L${available}").
     */
    @ExceptionHandler(PenaltyOverpaymentException.class)
    public ProblemDetail handlePenaltyOverpayment(
            PenaltyOverpaymentException e, HttpServletRequest req) {
        log.warn("Penalty overpayment rejected: requested=L${} available=L${} path={}",
                e.getRequested(), e.getAvailable(), req.getRequestURI());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "Payment amount exceeds outstanding penalty balance.");
        pd.setType(URI.create("https://slpa.example/problems/sl/penalty-overpayment"));
        pd.setTitle("Penalty overpayment");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "SL_PENALTY_OVERPAYMENT");
        pd.setProperty("requested", e.getRequested());
        pd.setProperty("available", e.getAvailable());
        return pd;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(
            IllegalArgumentException e, HttpServletRequest req) {
        // Method B validation failures (parcel UUID mismatch, owner UUID mismatch)
        // surface as IllegalArgumentException inside SlParcelVerifyService. Map to
        // 400 with the message passed through so the LSL script can surface it on
        // the in-world whisper. Keep this handler narrow: it only runs for
        // controllers in the sl package (advice is package-scoped).
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, e.getMessage());
        pd.setType(URI.create("https://slpa.example/problems/sl/invalid-request"));
        pd.setTitle("Invalid request");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "SL_INVALID_REQUEST");
        return pd;
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrity(
            DataIntegrityViolationException e, HttpServletRequest req) {
        String constraintName = ConstraintNameExtractor.extract(e);
        if (ConstraintNameExtractor.isAvatarUuidUniqueViolation(constraintName)) {
            log.warn("SL verify race: sl_avatar_uuid unique constraint fired ({}). "
                    + "Mapping to AvatarAlreadyLinkedException response.", constraintName);
            // Delegate to the AvatarAlreadyLinkedException handler so the response
            // shape stays in lockstep with the pre-check path. The no-arg constructor
            // is reserved for this race-path mapping where the UUID isn't recoverable
            // from the JDBC exception.
            return handleAvatarLinked(new AvatarAlreadyLinkedException(), req);
        }
        // Unknown constraint - bubble to GlobalExceptionHandler.handleUnexpected (500).
        throw e;
    }
}
