package com.slparcelauctions.backend.review.exception;

import java.net.URI;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
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
 *
 * <p><strong>Race fallback:</strong> {@code ReviewService#submit} pre-checks
 * duplicates via {@code reviewRepo.findByAuctionIdAndReviewerId}, but two
 * concurrent POSTs from the same reviewer can both pass the check and race
 * to INSERT. Postgres' {@code uq_reviews_auction_reviewer} unique constraint
 * rejects the loser with a {@link DataIntegrityViolationException}; the
 * handler below maps that specific constraint name to the same 409 shape as
 * the pre-check path. Unknown constraints rethrow so
 * {@link com.slparcelauctions.backend.common.exception.GlobalExceptionHandler}
 * still surfaces real bugs as 500.
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

    /**
     * Race fallback for the {@code uq_reviews_auction_reviewer} unique
     * constraint. See class javadoc. Unknown constraints are rethrown so
     * {@link com.slparcelauctions.backend.common.exception.GlobalExceptionHandler}
     * still catches real bugs as 500.
     *
     * <p>Uses Hibernate's {@link ConstraintViolationException#getConstraintName()}
     * via cause-chain walk — NOT {@code jakarta.validation.ConstraintViolationException},
     * which shares a simple name but does not carry a DB constraint name.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrity(DataIntegrityViolationException e,
                                             HttpServletRequest req) {
        String constraintName = extractConstraintName(e);
        if ("uq_reviews_auction_reviewer".equals(constraintName)) {
            log.warn("Review submit race: uq_reviews_auction_reviewer fired on {}. "
                    + "Mapping to ReviewAlreadySubmittedException response.",
                    req.getRequestURI());
            return handleAlreadySubmitted(
                    new ReviewAlreadySubmittedException(
                            "You have already submitted a review for this auction."),
                    req);
        }
        // Unknown constraint — bubble to GlobalExceptionHandler (500).
        throw e;
    }

    /**
     * Walks the cause chain to Hibernate's {@link ConstraintViolationException}
     * to pull the Postgres constraint name. Returns empty string if the chain
     * does not contain one. Kept private (not shared with {@code sl/ConstraintNameExtractor})
     * because the two slices have diverged on detection semantics and sharing
     * would drag in an accidental cross-package dependency.
     */
    private static String extractConstraintName(DataIntegrityViolationException e) {
        Throwable cursor = e;
        while (cursor != null) {
            if (cursor instanceof ConstraintViolationException cve) {
                String name = cve.getConstraintName();
                return name == null ? "" : name;
            }
            cursor = cursor.getCause();
        }
        return "";
    }
}
