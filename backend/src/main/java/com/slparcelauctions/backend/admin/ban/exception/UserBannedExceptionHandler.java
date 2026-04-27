package com.slparcelauctions.backend.admin.ban.exception;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps {@link UserBannedException} to an HTTP 403 response with
 * {@code code: USER_BANNED} and an optional {@code expiresAt} timestamp.
 *
 * <p>Intentionally NOT scoped to the admin package — this exception is thrown
 * from user-facing enforcement paths (Task 8). {@code @Order(HIGHEST_PRECEDENCE)}
 * ensures it beats any other global handler that might claim the same exception.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class UserBannedExceptionHandler {

    @ExceptionHandler(UserBannedException.class)
    public ProblemDetail handleBanned(UserBannedException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        pd.setTitle("Account suspended");
        pd.setProperty("code", "USER_BANNED");
        pd.setProperty("expiresAt", ex.getExpiresAt() == null ? null : ex.getExpiresAt().toString());
        return pd;
    }
}
