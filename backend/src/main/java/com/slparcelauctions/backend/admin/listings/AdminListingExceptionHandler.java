package com.slparcelauctions.backend.admin.listings;

import java.net.URI;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.slparcelauctions.backend.admin.listings.exception.AdminListingStateException;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * Maps {@link AdminListingStateException} to RFC-7807 problem details for the
 * admin listings controller. The exception's {@code code} field is exposed as
 * the {@code code} property and selects the HTTP status.
 */
@RestControllerAdvice(basePackages = "com.slparcelauctions.backend.admin.listings")
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@Slf4j
public class AdminListingExceptionHandler {

    @ExceptionHandler(AdminListingStateException.class)
    public ProblemDetail handleStateException(AdminListingStateException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(e.suggestedStatus(), e.getMessage());
        pd.setTitle("Admin listing operation rejected");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", e.getCode());
        return pd;
    }
}
