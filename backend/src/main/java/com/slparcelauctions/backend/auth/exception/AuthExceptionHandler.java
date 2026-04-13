package com.slparcelauctions.backend.auth.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.net.URI;

/**
 * Slice-scoped exception handler for the {@code auth/} package. Scoped via
 * {@code basePackages = "com.slparcelauctions.backend.auth"} so it catches only auth-slice
 * exceptions; the global handler picks up everything else.
 *
 * <p><strong>Ordering:</strong> Slice handlers run 100 above the global catch-all
 * ({@code @Order(Ordered.LOWEST_PRECEDENCE - 100)}) so they beat
 * {@link com.slparcelauctions.backend.common.exception.GlobalExceptionHandler}'s
 * {@code @ExceptionHandler(Exception.class)} but can still stack with each other via
 * further tiebreaking if multiple slice handlers match. Without explicit ordering both
 * advices sit at {@code LOWEST_PRECEDENCE} and ties resolve by bean registration order,
 * which historically worked here only because {@code "auth"} sorts before {@code "common"} —
 * a latent bug waiting for a future advice class to alphabetize earlier.
 *
 * <p>Each handler is explicit (no generic builder helper). The repetition is documentation —
 * these are six distinct security responses and the explicit form makes each one discoverable
 * by searching for its exception type.
 */
@RestControllerAdvice(basePackages = "com.slparcelauctions.backend.auth")
@Order(Ordered.LOWEST_PRECEDENCE - 100)
@RequiredArgsConstructor
@Slf4j
public class AuthExceptionHandler {

    @ExceptionHandler(InvalidCredentialsException.class)
    public ProblemDetail handleInvalidCredentials(InvalidCredentialsException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
            HttpStatus.UNAUTHORIZED, "Email or password is incorrect.");
        pd.setType(URI.create("https://slpa.example/problems/auth/invalid-credentials"));
        pd.setTitle("Invalid credentials");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "AUTH_INVALID_CREDENTIALS");
        return pd;
    }

    @ExceptionHandler(EmailAlreadyExistsException.class)
    public ProblemDetail handleEmailExists(EmailAlreadyExistsException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
            HttpStatus.CONFLICT, e.getMessage());
        pd.setType(URI.create("https://slpa.example/problems/auth/email-exists"));
        pd.setTitle("Email already registered");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "AUTH_EMAIL_EXISTS");
        return pd;
    }

    @ExceptionHandler(TokenExpiredException.class)
    public ProblemDetail handleTokenExpired(TokenExpiredException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
            HttpStatus.UNAUTHORIZED, "Token has expired. Please refresh or log in again.");
        pd.setType(URI.create("https://slpa.example/problems/auth/token-expired"));
        pd.setTitle("Token expired");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "AUTH_TOKEN_EXPIRED");
        return pd;
    }

    @ExceptionHandler(TokenInvalidException.class)
    public ProblemDetail handleTokenInvalid(TokenInvalidException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
            HttpStatus.UNAUTHORIZED, "Token is invalid.");
        pd.setType(URI.create("https://slpa.example/problems/auth/token-invalid"));
        pd.setTitle("Invalid token");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "AUTH_TOKEN_INVALID");
        return pd;
    }

    @ExceptionHandler(RefreshTokenReuseDetectedException.class)
    public ProblemDetail handleRefreshTokenReuse(
            RefreshTokenReuseDetectedException e, HttpServletRequest req) {
        // Already logged at WARN in RefreshTokenService.rotate with IP and User-Agent.
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
            HttpStatus.UNAUTHORIZED,
            "Session invalidated due to suspicious activity. Please log in again.");
        pd.setType(URI.create("https://slpa.example/problems/auth/refresh-token-reused"));
        pd.setTitle("Refresh token reuse detected");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "AUTH_REFRESH_TOKEN_REUSED");
        return pd;
    }

    @ExceptionHandler(AuthenticationStaleException.class)
    public ProblemDetail handleStale(AuthenticationStaleException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
            HttpStatus.UNAUTHORIZED, "Session is no longer valid. Please log in again.");
        pd.setType(URI.create("https://slpa.example/problems/auth/stale-session"));
        pd.setTitle("Session stale");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "AUTH_STALE_SESSION");
        return pd;
    }
}
