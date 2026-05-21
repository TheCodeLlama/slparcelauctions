package com.slparcelauctions.backend.support;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Slice-scoped advice that maps {@link SupportTicketException} to an RFC 9457
 * {@link ProblemDetail}. Scoped to the support package and ordered 100 above
 * the global catch-all so it beats {@code GlobalExceptionHandler#handleUnexpected}
 * but stacks cleanly with sibling slice handlers.
 *
 * <p>The discriminator is exposed as {@code code} on the {@code ProblemDetail}
 * so the frontend can pick an i18n key without parsing the human-readable
 * detail string.
 */
@RestControllerAdvice(basePackages = "com.slparcelauctions.backend.support")
@Order(Ordered.LOWEST_PRECEDENCE - 100)
public class SupportTicketExceptionHandler {

    @ExceptionHandler(SupportTicketException.class)
    public ResponseEntity<ProblemDetail> handle(SupportTicketException e) {
        HttpStatus status = switch (e.getCode()) {
            case UNKNOWN_TICKET, ATTACHMENT_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case NOT_OWNER -> HttpStatus.FORBIDDEN;
            case INTERNAL_NOTE_FROM_USER, INVALID_ATTACHMENT, INVALID_CATEGORY -> HttpStatus.BAD_REQUEST;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
        };
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        pd.setTitle("Support ticket error");
        pd.setProperty("code", e.getCode().name());
        return ResponseEntity.status(status).body(pd);
    }
}
