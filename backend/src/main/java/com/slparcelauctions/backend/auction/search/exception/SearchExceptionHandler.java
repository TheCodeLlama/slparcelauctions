package com.slparcelauctions.backend.auction.search.exception;

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
 * Maps semantic-validation exceptions raised by the search package to
 * RFC 7807 {@link ProblemDetail} 400 responses. Scoped to the search
 * package via {@code basePackages} so it never overshoots and catches
 * unrelated request errors. Runs at {@link Ordered#HIGHEST_PRECEDENCE}
 * + 10 so it wins over the application-wide advice (which is at the
 * default {@link Ordered#LOWEST_PRECEDENCE}) when both could match.
 */
@RestControllerAdvice(basePackages = "com.slparcelauctions.backend.auction.search")
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@Slf4j
public class SearchExceptionHandler {

    @ExceptionHandler(InvalidFilterValueException.class)
    public ProblemDetail handleInvalidFilter(InvalidFilterValueException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        pd.setTitle("Invalid Filter Value");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "INVALID_FILTER_VALUE");
        pd.setProperty("field", e.getField());
        pd.setProperty("rejectedValue", e.getRejectedValue());
        pd.setProperty("allowedValues", e.getAllowedValues());
        return pd;
    }

    @ExceptionHandler(InvalidRangeException.class)
    public ProblemDetail handleInvalidRange(InvalidRangeException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        pd.setTitle("Invalid Range");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "INVALID_RANGE");
        pd.setProperty("field", e.getField());
        return pd;
    }

    @ExceptionHandler(NearestRequiresNearRegionException.class)
    public ProblemDetail handleNearestNeedsNearRegion(
            NearestRequiresNearRegionException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        pd.setTitle("Invalid Sort");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "NEAREST_REQUIRES_NEAR_REGION");
        return pd;
    }

    @ExceptionHandler(DistanceRequiresNearRegionException.class)
    public ProblemDetail handleDistanceNeedsNearRegion(
            DistanceRequiresNearRegionException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        pd.setTitle("Invalid Filter");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "DISTANCE_REQUIRES_NEAR_REGION");
        return pd;
    }
}
