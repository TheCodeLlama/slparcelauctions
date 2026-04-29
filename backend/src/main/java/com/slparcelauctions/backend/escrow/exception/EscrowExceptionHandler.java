package com.slparcelauctions.backend.escrow.exception;

import java.net.URI;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.slparcelauctions.backend.escrow.command.exception.UnknownTerminalCommandException;
import com.slparcelauctions.backend.escrow.dispute.exception.EscrowNotDisputedException;
import com.slparcelauctions.backend.escrow.dispute.exception.EvidenceAlreadySubmittedException;
import com.slparcelauctions.backend.escrow.dispute.exception.EvidenceImageContentTypeException;
import com.slparcelauctions.backend.escrow.dispute.exception.EvidenceImageTooLargeException;
import com.slparcelauctions.backend.escrow.dispute.exception.EvidenceTooManyImagesException;
import com.slparcelauctions.backend.escrow.dispute.exception.NotSellerOfEscrowException;
import com.slparcelauctions.backend.sl.exception.InvalidSlHeadersException;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * Escrow-scoped exception mapper. Runs before
 * {@code GlobalExceptionHandler} via the high-precedence {@code @Order}
 * and is basePackage-scoped so the 403/404/409 mappings here do not
 * shadow unrelated handlers elsewhere (mirrors {@code AuctionExceptionHandler}
 * pattern).
 */
@RestControllerAdvice(basePackages = "com.slparcelauctions.backend.escrow")
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@Slf4j
public class EscrowExceptionHandler {

    @ExceptionHandler(EscrowNotFoundException.class)
    public ProblemDetail handleNotFound(EscrowNotFoundException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        pd.setTitle("Escrow Not Found");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "ESCROW_NOT_FOUND");
        pd.setProperty("auctionId", e.getAuctionId());
        return pd;
    }

    @ExceptionHandler(EscrowAccessDeniedException.class)
    public ProblemDetail handleForbidden(EscrowAccessDeniedException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
        pd.setTitle("Escrow Forbidden");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "ESCROW_FORBIDDEN");
        return pd;
    }

    @ExceptionHandler(IllegalEscrowTransitionException.class)
    public ProblemDetail handleIllegalTransition(
            IllegalEscrowTransitionException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        pd.setTitle("Escrow Invalid Transition");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "ESCROW_INVALID_TRANSITION");
        pd.setProperty("escrowId", e.getEscrowId());
        pd.setProperty("currentState", e.getCurrentState().name());
        pd.setProperty("attemptedTarget", e.getAttemptedTarget().name());
        return pd;
    }

    @ExceptionHandler(TerminalAuthException.class)
    public ProblemDetail handleTerminalAuth(TerminalAuthException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
        pd.setTitle("Terminal Auth Failed");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "SECRET_MISMATCH");
        return pd;
    }

    @ExceptionHandler(UnknownTerminalCommandException.class)
    public ProblemDetail handleUnknownTerminalCommand(
            UnknownTerminalCommandException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        pd.setTitle("Terminal Command Not Found");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "TERMINAL_COMMAND_NOT_FOUND");
        pd.setProperty("idempotencyKey", e.getIdempotencyKey());
        return pd;
    }

    @ExceptionHandler(NotSellerOfEscrowException.class)
    public ProblemDetail handleNotSeller(NotSellerOfEscrowException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
        pd.setTitle("Not Seller of Escrow");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "NOT_SELLER");
        return pd;
    }

    @ExceptionHandler(EscrowNotDisputedException.class)
    public ProblemDetail handleNotDisputed(EscrowNotDisputedException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        pd.setTitle("Escrow Not Disputed");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "ESCROW_NOT_DISPUTED");
        return pd;
    }

    @ExceptionHandler(EvidenceAlreadySubmittedException.class)
    public ProblemDetail handleAlreadySubmitted(
            EvidenceAlreadySubmittedException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        pd.setTitle("Evidence Already Submitted");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "EVIDENCE_ALREADY_SUBMITTED");
        return pd;
    }

    @ExceptionHandler(EvidenceTooManyImagesException.class)
    public ProblemDetail handleTooManyImages(
            EvidenceTooManyImagesException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        pd.setTitle("Too Many Evidence Images");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "EVIDENCE_TOO_MANY_IMAGES");
        return pd;
    }

    @ExceptionHandler(EvidenceImageTooLargeException.class)
    public ProblemDetail handleImageTooLarge(
            EvidenceImageTooLargeException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        pd.setTitle("Evidence Image Too Large");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "EVIDENCE_IMAGE_TOO_LARGE");
        return pd;
    }

    @ExceptionHandler(EvidenceImageContentTypeException.class)
    public ProblemDetail handleBadContentType(
            EvidenceImageContentTypeException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        pd.setTitle("Evidence Image Content Type Not Allowed");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "EVIDENCE_IMAGE_CONTENT_TYPE");
        return pd;
    }

    /**
     * Mirror of {@code SlExceptionHandler.handleInvalidHeaders}, scoped to the
     * escrow package. SL-header validation is delegated to
     * {@link com.slparcelauctions.backend.sl.SlHeaderValidator} inside the
     * escrow SL callback controllers (terminal register + payment + payout),
     * but {@code SlExceptionHandler} is scoped to {@code backend.sl} so it
     * would not catch the exception when thrown from an escrow-package
     * controller. Keeping a parallel mapping here preserves the same
     * 403/SL_INVALID_HEADERS shape without re-scoping the sl-package handler.
     */
    @ExceptionHandler(InvalidSlHeadersException.class)
    public ProblemDetail handleInvalidSlHeaders(
            InvalidSlHeadersException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
        pd.setType(URI.create("https://slpa.example/problems/sl/invalid-headers"));
        pd.setTitle("Invalid SL headers");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "SL_INVALID_HEADERS");
        return pd;
    }
}
