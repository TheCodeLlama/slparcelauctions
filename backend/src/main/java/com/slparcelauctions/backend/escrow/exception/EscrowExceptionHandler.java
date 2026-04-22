package com.slparcelauctions.backend.escrow.exception;

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
}
