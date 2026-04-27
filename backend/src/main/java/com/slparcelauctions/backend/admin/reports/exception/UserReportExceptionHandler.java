package com.slparcelauctions.backend.admin.reports.exception;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Slice-scoped exception handler for the user-facing report endpoints in
 * {@code com.slparcelauctions.backend.admin.reports}. Returns RFC 9457
 * {@link ProblemDetail} for the three user-facing report exceptions.
 *
 * <p><strong>Ordering:</strong> {@code @Order(Ordered.HIGHEST_PRECEDENCE)} guarantees
 * this handler wins over {@code AdminExceptionHandler} (which is scoped to
 * {@code com.slparcelauctions.backend.admin} and therefore technically covers
 * {@code admin.reports} too). The higher precedence ensures the user-facing
 * ProblemDetail shape is returned for requests handled by controllers in this
 * package.
 */
@RestControllerAdvice(basePackages = "com.slparcelauctions.backend.admin.reports")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class UserReportExceptionHandler {

    @ExceptionHandler(CannotReportOwnListingException.class)
    public ProblemDetail handleOwnListing(CannotReportOwnListingException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        pd.setTitle(ex.getMessage());
        pd.setProperty("code", "CANNOT_REPORT_OWN_LISTING");
        return pd;
    }

    @ExceptionHandler(MustBeVerifiedToReportException.class)
    public ProblemDetail handleNotVerified(MustBeVerifiedToReportException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        pd.setTitle(ex.getMessage());
        pd.setProperty("code", "VERIFICATION_REQUIRED");
        return pd;
    }

    @ExceptionHandler(AuctionNotReportableException.class)
    public ProblemDetail handleNotActive(AuctionNotReportableException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        pd.setTitle(ex.getMessage());
        pd.setProperty("code", "AUCTION_NOT_REPORTABLE");
        pd.setProperty("currentStatus", ex.getCurrentStatus().name());
        return pd;
    }
}
