package com.slparcelauctions.backend.user;

import java.net.URI;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import tools.jackson.databind.exc.UnrecognizedPropertyException;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * Slice-scoped exception handler for the {@code user/} package. Uses the Epic 02
 * sub-spec 1 convention: {@code @Order(Ordered.LOWEST_PRECEDENCE - 100)} so it
 * wins over {@code GlobalExceptionHandler} but can stack predictably with other
 * slice handlers.
 *
 * <p><strong>Jackson exception wrapping:</strong> Spring's message converter catches
 * {@link UnrecognizedPropertyException} and rethrows it as
 * {@link HttpMessageNotReadableException} with the Jackson exception as cause. We
 * therefore subscribe to {@code HttpMessageNotReadableException}, inspect the cause,
 * and only tailor the response when it's an unknown-field case — otherwise we
 * rethrow so {@code GlobalExceptionHandler.handleNotReadable} can produce the
 * generic {@code malformed-request} ProblemDetail. This keeps the slice handler
 * narrowly targeted at the privilege-escalation scenario.
 *
 * <p><strong>Note:</strong> {@code MaxUploadSizeExceededException} is NOT handled
 * here. It is thrown by Spring's multipart resolver before the request reaches any
 * {@code @RestController}, so package-scoped advice never sees it. That handler
 * lives in {@code common/exception/GlobalExceptionHandler.java}. See FOOTGUNS
 * §F.28.
 *
 * <p>Handlers for {@code AvatarTooLargeException}, {@code UnsupportedImageFormatException},
 * and {@code InvalidAvatarSizeException} will be added in Task 4b.
 */
@RestControllerAdvice(basePackages = "com.slparcelauctions.backend.user")
@Order(Ordered.LOWEST_PRECEDENCE - 100)
@Slf4j
public class UserExceptionHandler {

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleNotReadable(
            HttpMessageNotReadableException e, HttpServletRequest req) {
        Throwable cause = e.getCause();
        if (cause instanceof UnrecognizedPropertyException unknown) {
            return handleUnknownField(unknown, req);
        }
        // Non-unknown-field malformed request — mirror GlobalExceptionHandler.handleNotReadable
        // so user/ controllers get consistent malformed-request responses without needing a
        // rethrow-then-redispatch dance (Spring does not re-walk the handler chain on rethrow).
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Request body is missing or malformed.");
        pd.setType(URI.create("https://slpa.example/problems/malformed-request"));
        pd.setTitle("Malformed request");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "MALFORMED_REQUEST");
        return pd;
    }

    private ProblemDetail handleUnknownField(
            UnrecognizedPropertyException e, HttpServletRequest req) {
        log.warn("Request body rejected unknown field: {}", e.getPropertyName());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "Unknown field in request body: '" + e.getPropertyName() + "'.");
        pd.setType(URI.create("https://slpa.example/problems/user/unknown-field"));
        pd.setTitle("Unknown field");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "USER_UNKNOWN_FIELD");
        pd.setProperty("field", e.getPropertyName());
        return pd;
    }
}
