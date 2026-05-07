package com.slparcelauctions.backend.admin.ledger;

import java.net.URI;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.slparcelauctions.backend.admin.ledger.exception.AdminLedgerStateException;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * Maps {@link AdminLedgerStateException} to RFC-7807 problem details for the
 * admin ledger controller. The exception's {@code code} field is exposed as
 * the {@code code} property and selects the HTTP status.
 */
@RestControllerAdvice(basePackages = "com.slparcelauctions.backend.admin.ledger")
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@Slf4j
public class AdminLedgerExceptionHandler {

    @ExceptionHandler(AdminLedgerStateException.class)
    public ProblemDetail handleStateException(AdminLedgerStateException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(e.suggestedStatus(), e.getMessage());
        pd.setTitle("Admin ledger query rejected");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", e.getCode());
        return pd;
    }
}
