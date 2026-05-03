package com.slparcelauctions.backend.wallet.exception;

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
 * Maps wallet-domain exceptions to HTTP wire shapes.
 *
 * <p>Scoped to the wallet package so a stray {@code IllegalArgumentException}
 * from an unrelated layer doesn't accidentally get caught here.
 *
 * <p>Note: SL-headers controllers ({@code /sl/wallet/...}) catch their own
 * exceptions and return {@link com.slparcelauctions.backend.wallet.sl.SlWalletResponse}
 * shapes directly — this advice only maps user-facing endpoint exceptions.
 */
@RestControllerAdvice(basePackages = "com.slparcelauctions.backend.wallet.me")
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@Slf4j
public class WalletExceptionHandler {

    @ExceptionHandler(InsufficientAvailableBalanceException.class)
    public ProblemDetail handleInsufficientBalance(
            InsufficientAvailableBalanceException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        pd.setTitle("Insufficient available balance");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "INSUFFICIENT_AVAILABLE_BALANCE");
        pd.setProperty("available", e.getAvailable());
        pd.setProperty("requested", e.getRequested());
        return pd;
    }

    @ExceptionHandler(PenaltyOutstandingException.class)
    public ProblemDetail handlePenalty(PenaltyOutstandingException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        pd.setTitle("Penalty outstanding");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "PENALTY_OUTSTANDING");
        pd.setProperty("owed", e.getOwed());
        return pd;
    }

    @ExceptionHandler(AmountExceedsOwedException.class)
    public ProblemDetail handleAmountExceedsOwed(AmountExceedsOwedException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        pd.setTitle("Amount exceeds owed");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "AMOUNT_EXCEEDS_OWED");
        pd.setProperty("owed", e.getOwed());
        pd.setProperty("requested", e.getRequested());
        return pd;
    }

    @ExceptionHandler(UserStatusBlockedException.class)
    public ProblemDetail handleUserBlocked(UserStatusBlockedException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.FORBIDDEN, e.getMessage());
        pd.setTitle("User account blocked");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "USER_BLOCKED");
        return pd;
    }

    @ExceptionHandler(WalletTermsNotAcceptedException.class)
    public ProblemDetail handleTermsNotAccepted(
            WalletTermsNotAcceptedException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.FORBIDDEN, e.getMessage());
        pd.setTitle("Wallet terms not accepted");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "WALLET_TERMS_NOT_ACCEPTED");
        return pd;
    }
}
