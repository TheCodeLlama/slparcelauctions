package com.slparcelauctions.backend.verification;

import java.net.URI;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.slparcelauctions.backend.verification.exception.AlreadyVerifiedException;
import com.slparcelauctions.backend.verification.exception.CodeCollisionException;
import com.slparcelauctions.backend.verification.exception.CodeNotFoundException;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * Slice-scoped exception handler for the {@code verification/} package.
 * Scoped via {@code basePackages} so it catches only verification-slice
 * exceptions; the global handler picks up everything else.
 *
 * <p><strong>Ordering:</strong> {@code @Order(Ordered.HIGHEST_PRECEDENCE)} ensures this slice
 * advice is evaluated BEFORE {@link com.slparcelauctions.backend.common.exception.GlobalExceptionHandler},
 * whose {@code @ExceptionHandler(Exception.class)} catch-all would otherwise win the tiebreaker
 * when both advices are at the default {@code LOWEST_PRECEDENCE}. Spring's
 * {@code ExceptionHandlerExceptionResolver} iterates advices in {@code AnnotationAwareOrderComparator}
 * order; without explicit ordering, ties resolve by bean registration order, which is non-deterministic
 * across packages.
 */
@RestControllerAdvice(basePackages = "com.slparcelauctions.backend.verification")
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class VerificationExceptionHandler {

    @ExceptionHandler(AlreadyVerifiedException.class)
    public ProblemDetail handleAlreadyVerified(AlreadyVerifiedException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                "This account is already linked to an SL avatar. Contact support if this is wrong.");
        pd.setType(URI.create("https://slpa.example/problems/verification/already-verified"));
        pd.setTitle("Account already verified");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "VERIFICATION_ALREADY_VERIFIED");
        return pd;
    }

    @ExceptionHandler(CodeNotFoundException.class)
    public ProblemDetail handleCodeNotFound(CodeNotFoundException e, HttpServletRequest req) {
        // 404 when reading /active, 400 when consuming via /sl/verify - status picked by endpoint.
        // Default to 404 here; SlExceptionHandler overrides for the sl-path case inline.
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND,
                "No active verification code found. Generate a new code from your dashboard.");
        pd.setType(URI.create("https://slpa.example/problems/verification/code-not-found"));
        pd.setTitle("No active verification code");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "VERIFICATION_CODE_NOT_FOUND");
        return pd;
    }

    @ExceptionHandler(CodeCollisionException.class)
    public ProblemDetail handleCollision(CodeCollisionException e, HttpServletRequest req) {
        // Already logged WARN in VerificationCodeService.consume with user IDs + code.
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                "Please generate a new code from your dashboard.");
        pd.setType(URI.create("https://slpa.example/problems/verification/code-collision"));
        pd.setTitle("Verification failed");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "VERIFICATION_CODE_COLLISION");
        return pd;
    }
}
