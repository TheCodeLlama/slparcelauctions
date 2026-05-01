package com.slparcelauctions.backend.wallet.sl;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.slparcelauctions.backend.escrow.exception.TerminalAuthException;
import com.slparcelauctions.backend.sl.exception.InvalidSlHeadersException;

import lombok.extern.slf4j.Slf4j;

/**
 * Maps SL-headers-validation exceptions to {@link SlWalletResponse} wire
 * shapes for the wallet SL endpoints. The existing
 * {@code com.slparcelauctions.backend.sl.SlExceptionHandler} is package-scoped
 * to {@code com.slparcelauctions.backend.sl} which does NOT include
 * {@code com.slparcelauctions.backend.wallet.sl} — so wallet endpoints need
 * their own advice with the same exception types but LSL-friendly wire
 * bodies (SlWalletResponse instead of RFC 7807 ProblemDetail).
 *
 * <p>Returns the body with HTTP 200 so LSL parses on body content
 * ({@code status} field) rather than HTTP status. Matches the convention
 * the LSL terminal expects.
 */
@RestControllerAdvice(basePackages = "com.slparcelauctions.backend.wallet.sl")
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
@Slf4j
public class WalletSlExceptionHandler {

    @ExceptionHandler(InvalidSlHeadersException.class)
    public ResponseEntity<SlWalletResponse> handleInvalidHeaders(InvalidSlHeadersException e) {
        log.warn("Wallet SL endpoint rejected: bad headers — {}", e.getMessage());
        return ResponseEntity.ok(SlWalletResponse.error(
                SlWalletResponseReason.BAD_HEADERS, e.getMessage()));
    }

    @ExceptionHandler(TerminalAuthException.class)
    public ResponseEntity<SlWalletResponse> handleTerminalAuth(TerminalAuthException e) {
        log.warn("Wallet SL endpoint rejected: shared-secret mismatch");
        return ResponseEntity.ok(SlWalletResponse.error(
                SlWalletResponseReason.SECRET_MISMATCH, "Shared secret did not match."));
    }
}
