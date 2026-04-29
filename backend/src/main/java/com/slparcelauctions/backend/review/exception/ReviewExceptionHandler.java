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
 * <p>Mapping table (Epic 08 sub-spec 1 §4.1 / §4.3 / §4.4):
 * <ul>
 *   <li>{@link ReviewIneligibleException} → 422</li>
 *   <li>{@link ReviewWindowClosedException} → 422 (distinct so the UI
 *       can render a window-specific message)</li>
 *   <li>{@link ReviewAlreadySubmittedException} → 409</li>
 *   <li>{@link ReviewResponseAlreadyExistsException} → 409 (Task 3)</li>
 *   <li>{@link ReviewFlagAlreadyExistsException} → 409 (Task 3)</li>
 *   <li>{@link ReviewNotFoundException} → 404 (used by Task 2 GETs and
 *       the Task 3 action endpoints)</li>
 * </ul>
 *
 * <p><strong>Race fallback:</strong> every write path pre-checks
 * uniqueness, but two concurrent POSTs from the same caller can both pass
 * the check and race to INSERT. Postgres rejects the loser with a
 * {@link DataIntegrityViolationException}; the handler below inspects the
 * constraint name and maps three known constraints to the same 409 shape
 * as the pre-check path:
 * <ul>
 *   <li>{@code uq_reviews_auction_reviewer} — double-submit on Task 1</li>
 *   <li>{@code uq_review_responses_review} / {@code review_id} unique FK
 *       on {@code review_responses} — double-respond on Task 3</li>
 *   <li>{@code uq_review_flags_review_flagger} — double-flag on Task 3</li>
 * </ul>
 * Unknown constraints rethrow so
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

    @ExceptionHandler(ReviewResponseAlreadyExistsException.class)
    public ProblemDetail handleResponseAlreadyExists(ReviewResponseAlreadyExistsException e,
                                                     HttpServletRequest req) {
        log.info("Review response rejected (duplicate) on {}: {}",
                req.getRequestURI(), e.getMessage());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, e.getMessage());
        pd.setType(URI.create("https://slpa.example/problems/review/response-already-exists"));
        pd.setTitle("Review response already exists");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "REVIEW_RESPONSE_ALREADY_EXISTS");
        return pd;
    }

    @ExceptionHandler(ReviewFlagAlreadyExistsException.class)
    public ProblemDetail handleFlagAlreadyExists(ReviewFlagAlreadyExistsException e,
                                                 HttpServletRequest req) {
        log.info("Review flag rejected (duplicate) on {}: {}",
                req.getRequestURI(), e.getMessage());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, e.getMessage());
        pd.setType(URI.create("https://slpa.example/problems/review/flag-already-exists"));
        pd.setTitle("Review flag already exists");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "REVIEW_FLAG_ALREADY_EXISTS");
        return pd;
    }

    /**
     * Race fallback for the three known review-package unique constraints.
     * See class javadoc. Unknown constraints are rethrown so
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
        if ("uq_review_flags_review_flagger".equals(constraintName)) {
            log.warn("Review flag race: uq_review_flags_review_flagger fired on {}. "
                    + "Mapping to ReviewFlagAlreadyExistsException response.",
                    req.getRequestURI());
            return handleFlagAlreadyExists(
                    new ReviewFlagAlreadyExistsException(extractReviewIdFromUri(req)),
                    req);
        }
        // The ReviewResponse FK is simply the review_id-unique column on
        // review_responses — no named table-level constraint. Postgres
        // reports its auto-generated name (typically
        // review_responses_review_id_key). Match either the conventional
        // name or a generated suffix so the 409 maps consistently.
        if (constraintName != null
                && (constraintName.equals("uq_review_responses_review")
                        || constraintName.endsWith("review_responses_review_id_key"))) {
            log.warn("Review response race: {} fired on {}. "
                    + "Mapping to ReviewResponseAlreadyExistsException response.",
                    constraintName, req.getRequestURI());
            return handleResponseAlreadyExists(
                    new ReviewResponseAlreadyExistsException(extractReviewIdFromUri(req)),
                    req);
        }
        // Unknown constraint — bubble to GlobalExceptionHandler (500).
        throw e;
    }

    /**
     * Best-effort parse of the review-id path variable from
     * {@code /api/v1/reviews/{id}/...}. Used only to construct a
     * user-facing error message — if the URI shape is unexpected we
     * fall back to {@code null} so the 409 renders without an id
     * rather than throwing from inside an exception handler.
     */
    private static Long extractReviewIdFromUri(HttpServletRequest req) {
        String uri = req.getRequestURI();
        String prefix = "/api/v1/reviews/";
        int start = uri.indexOf(prefix);
        if (start < 0) {
            return null;
        }
        int idStart = start + prefix.length();
        int idEnd = uri.indexOf('/', idStart);
        String token = idEnd < 0 ? uri.substring(idStart) : uri.substring(idStart, idEnd);
        try {
            return Long.parseLong(token);
        } catch (NumberFormatException ex) {
            return null;
        }
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
