package com.slparcelauctions.backend.admin.exception;

import java.util.Map;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "com.slparcelauctions.backend.admin")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AdminExceptionHandler {

    @ExceptionHandler(FraudFlagNotFoundException.class)
    public ResponseEntity<AdminApiError> handleNotFound(FraudFlagNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(AdminApiError.of("FLAG_NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(FraudFlagAlreadyResolvedException.class)
    public ResponseEntity<AdminApiError> handleAlreadyResolved(FraudFlagAlreadyResolvedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(AdminApiError.of("ALREADY_RESOLVED", ex.getMessage()));
    }

    @ExceptionHandler(AuctionNotSuspendedException.class)
    public ResponseEntity<AdminApiError> handleNotSuspended(AuctionNotSuspendedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(AdminApiError.of(
                "AUCTION_NOT_SUSPENDED", ex.getMessage(),
                Map.of("currentStatus",
                    ex.getCurrentStatus() == null ? "null" : ex.getCurrentStatus().name())));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<AdminApiError> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
            .map(f -> f.getField() + ": " + f.getDefaultMessage())
            .findFirst().orElse("Invalid request");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(AdminApiError.of("VALIDATION_FAILED", message));
    }
}
