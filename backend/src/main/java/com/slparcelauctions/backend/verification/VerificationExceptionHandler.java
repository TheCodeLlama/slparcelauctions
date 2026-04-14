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
 * <p><strong>Ordering:</strong> Slice handlers run 100 above the global catch-all
 * ({@code @Order(Ordered.LOWEST_PRECEDENCE - 100)}) so they beat
 * {@link com.slparcelauctions.backend.common.exception.GlobalExceptionHandler}'s
 * {@code @ExceptionHandler(Exception.class)} but can still stack with each other
 * via further tiebreaking if multiple slice handlers match. Without explicit
 * ordering, both advices sit at {@code LOWEST_PRECEDENCE} and ties resolve by
 * bean registration order, which is non-deterministic across packages.
 */
@RestControllerAdvice(basePackages = "com.slparcelauctions.backend.verification")
@Order(Ordered.LOWEST_PRECEDENCE - 100)
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
        // This handler maps CodeNotFoundException to 404 for any controller in the
        // verification/ package (the only current caller is GET /active, where 404 is
        // the correct semantic).
        //
        // Task 3's SlExceptionHandler will register a separate @RestControllerAdvice
        // scoped to basePackages = "com.slparcelauctions.backend.sl". When a
        // sl-package controller (e.g. POST /api/v1/sl/verify) throws
        // CodeNotFoundException, Spring will select the sl-scoped handler via
        // @RestControllerAdvice package filtering, not this one - that handler will
        // map to 400 with a SL-flavored message. Both handlers catch the same
        // exception type; selection is by the controller's package, not by endpoint.
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
