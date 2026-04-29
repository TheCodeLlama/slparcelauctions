package com.slparcelauctions.backend.user.deletion;

import java.util.List;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.slparcelauctions.backend.admin.users.AdminUserController;
import com.slparcelauctions.backend.user.UserController;
import com.slparcelauctions.backend.user.deletion.exception.ActiveAuctionsException;
import com.slparcelauctions.backend.user.deletion.exception.ActiveHighBidsException;
import com.slparcelauctions.backend.user.deletion.exception.ActiveProxyBidsException;
import com.slparcelauctions.backend.user.deletion.exception.DeletionPreconditionException;
import com.slparcelauctions.backend.user.deletion.exception.InvalidPasswordException;
import com.slparcelauctions.backend.user.deletion.exception.OpenEscrowsException;
import com.slparcelauctions.backend.user.deletion.exception.UserAlreadyDeletedException;

/**
 * Exception handler for user-deletion errors originating from either the self-service
 * ({@link UserController}) or admin ({@link AdminUserController}) deletion endpoints.
 *
 * <p>Uses {@code assignableTypes} to cover exactly the two controllers that invoke
 * {@link UserDeletionService}, without widening the scope of the existing package-scoped
 * {@code UserExceptionHandler} or {@code AdminExceptionHandler}.
 *
 * <p>{@code @Order(Ordered.HIGHEST_PRECEDENCE)} ensures this handler wins over both
 * the package-scoped handlers and {@code GlobalExceptionHandler} when one of the
 * deletion exceptions is thrown.
 */
@RestControllerAdvice(assignableTypes = {UserController.class, AdminUserController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class UserDeletionExceptionHandler {

    @ExceptionHandler(UserAlreadyDeletedException.class)
    public ResponseEntity<DeletionPreconditionError> handleAlreadyDeleted(UserAlreadyDeletedException ex) {
        return ResponseEntity.status(HttpStatus.GONE)
                .body(new DeletionPreconditionError("USER_ALREADY_DELETED", ex.getMessage(), List.of()));
    }

    @ExceptionHandler(InvalidPasswordException.class)
    public ResponseEntity<DeletionPreconditionError> handleInvalidPassword(InvalidPasswordException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new DeletionPreconditionError("INVALID_PASSWORD", ex.getMessage(), List.of()));
    }

    @ExceptionHandler({
        ActiveAuctionsException.class,
        OpenEscrowsException.class,
        ActiveHighBidsException.class,
        ActiveProxyBidsException.class
    })
    public ResponseEntity<DeletionPreconditionError> handlePrecondition(RuntimeException ex) {
        DeletionPreconditionException dpe = (DeletionPreconditionException) ex;
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new DeletionPreconditionError(
                        dpe.getCode(),
                        ex.getMessage(),
                        dpe.getBlockingIds()));
    }
}
