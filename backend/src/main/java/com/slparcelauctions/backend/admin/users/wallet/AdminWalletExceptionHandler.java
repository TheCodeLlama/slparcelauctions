package com.slparcelauctions.backend.admin.users.wallet;

import java.net.URI;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.slparcelauctions.backend.admin.users.wallet.exception.AdminWalletStateException;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * Maps {@link AdminWalletStateException} to RFC-7807 problem details for the
 * admin wallet controller. The exception's {@code code} field is exposed as
 * the {@code code} property and selects the HTTP status.
 */
@RestControllerAdvice(basePackages = "com.slparcelauctions.backend.admin.users.wallet")
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@Slf4j
public class AdminWalletExceptionHandler {

    @ExceptionHandler(AdminWalletStateException.class)
    public ProblemDetail handleStateException(AdminWalletStateException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(e.suggestedStatus(), e.getMessage());
        pd.setTitle("Admin wallet operation rejected");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", e.getCode());
        return pd;
    }
}
