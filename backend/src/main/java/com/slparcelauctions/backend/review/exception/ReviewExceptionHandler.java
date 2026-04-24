package com.slparcelauctions.backend.review.exception;

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
 * Slice-scoped exception handler for the {@code review/} package. Uses
 * the Epic 02 convention ({@code @Order(Ordered.LOWEST_PRECEDENCE -
 * 100)}) so slice handlers reliably win over the catch-all in
 * {@code common/exception/GlobalExceptionHandler}. See FOOTGUNS §F.26
 * — alphabetical ordering is not a safe precedence guarantee.
 *
 * <p>Mapping table (Epic 08 sub-spec 1 §4.1):
 * <ul>
 *   <li>{@link ReviewIneligibleException} → 422</li>
 *   <li>{@link ReviewWindowClosedException} → 422 (distinct so the UI
 *       can render a window-specific message)</li>
 *   <li>{@link ReviewAlreadySubmittedException} → 409</li>
 *   <li>{@link ReviewNotFoundException} → 404 (used by Task 2 GETs)</li>
 * </ul>
 */
@RestControllerAdvice(basePackages = "com.slparcelauctions.backend.review")
@Order(Ordered.LOWEST_PRECEDENCE - 100)
@Slf4j
public class ReviewExceptionHandler {

    @ExceptionHandler(ReviewIneligibleException.class)
    public ProblemDetail handleIneligible(ReviewIneligibleException e,
                                          HttpServletRequest req) {
        log.info("Review submission rejected (ineligible) on {}: {}",
                req.getRequestURI(), e.getMessage());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        pd.setType(URI.create("https://slpa.example/problems/review/ineligible"));
        pd.setTitle("Review not eligible");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "REVIEW_INELIGIBLE");
        return pd;
    }

    @ExceptionHandler(ReviewWindowClosedException.class)
    public ProblemDetail handleWindowClosed(ReviewWindowClosedException e,
                                            HttpServletRequest req) {
        log.info("Review submission rejected (window closed) on {}: {}",
                req.getRequestURI(), e.getMessage());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        pd.setType(URI.create("https://slpa.example/problems/review/window-closed"));
        pd.setTitle("Review window closed");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "REVIEW_WINDOW_CLOSED");
        return pd;
    }

    @ExceptionHandler(ReviewAlreadySubmittedException.class)
    public ProblemDetail handleAlreadySubmitted(ReviewAlreadySubmittedException e,
                                                HttpServletRequest req) {
        log.info("Review submission rejected (duplicate) on {}: {}",
                req.getRequestURI(), e.getMessage());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, e.getMessage());
        pd.setType(URI.create("https://slpa.example/problems/review/already-submitted"));
        pd.setTitle("Review already submitted");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "REVIEW_ALREADY_SUBMITTED");
        return pd;
    }

    @ExceptionHandler(ReviewNotFoundException.class)
    public ProblemDetail handleNotFound(ReviewNotFoundException e,
                                        HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, e.getMessage());
        pd.setType(URI.create("https://slpa.example/problems/review/not-found"));
        pd.setTitle("Review not found");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "REVIEW_NOT_FOUND");
        return pd;
    }
}
