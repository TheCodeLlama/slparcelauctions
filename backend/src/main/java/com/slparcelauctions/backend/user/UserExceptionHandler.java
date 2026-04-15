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

import com.slparcelauctions.backend.user.exception.AvatarTooLargeException;
import com.slparcelauctions.backend.user.exception.InvalidAvatarSizeException;
import com.slparcelauctions.backend.user.exception.UnsupportedImageFormatException;

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
 * <p>Avatar exceptions ({@code AvatarTooLargeException}, {@code UnsupportedImageFormatException},
 * {@code InvalidAvatarSizeException}) all originate in the user-package {@code AvatarService}
 * and are therefore handled here. {@code AvatarTooLargeException} in particular is a
 * defensive re-check fired after Spring's multipart parser has already accepted the upload.
 */
@RestControllerAdvice(basePackages = "com.slparcelauctions.backend.user")
@Order(Ordered.LOWEST_PRECEDENCE - 100)
@Slf4j
public class UserExceptionHandler {

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleNotReadable(
            HttpMessageNotReadableException e, HttpServletRequest req) {
        UnrecognizedPropertyException unknown = findCause(e, UnrecognizedPropertyException.class);
        if (unknown != null) {
            return handleUnknownField(unknown, req);
        }
        // KEEP IN SYNC WITH common/exception/GlobalExceptionHandler.handleNotReadable.
        // Spring's advice chain does not re-walk on rethrow, so we cannot delegate —
        // the malformed-request shape is duplicated here by necessity. If the global
        // handler's URI, title, detail, or code changes, mirror it here.
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Request body is missing or malformed.");
        pd.setType(URI.create("https://slpa.example/problems/malformed-request"));
        pd.setTitle("Malformed request");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "MALFORMED_REQUEST");
        return pd;
    }

    @ExceptionHandler(AvatarTooLargeException.class)
    public ProblemDetail handleAvatarTooLarge(
            AvatarTooLargeException e, HttpServletRequest req) {
        log.warn("Avatar too large: {} bytes (max {})", e.getActualBytes(), e.getMaxBytes());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.PAYLOAD_TOO_LARGE, "Avatar must be 2MB or less.");
        pd.setType(URI.create("https://slpa.example/problems/user/upload-too-large"));
        pd.setTitle("Upload too large");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "USER_UPLOAD_TOO_LARGE");
        pd.setProperty("maxBytes", e.getMaxBytes());
        return pd;
    }

    @ExceptionHandler(UnsupportedImageFormatException.class)
    public ProblemDetail handleUnsupportedImageFormat(
            UnsupportedImageFormatException e, HttpServletRequest req) {
        log.warn("Rejected unsupported image format: {}", e.getMessage());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Upload must be a JPEG, PNG, or WebP image.");
        pd.setType(URI.create("https://slpa.example/problems/user/unsupported-image-format"));
        pd.setTitle("Unsupported image format");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "USER_UNSUPPORTED_IMAGE_FORMAT");
        return pd;
    }

    @ExceptionHandler(InvalidAvatarSizeException.class)
    public ProblemDetail handleInvalidAvatarSize(
            InvalidAvatarSizeException e, HttpServletRequest req) {
        log.warn("Rejected invalid avatar size: {}", e.getRequestedSize());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Avatar size must be 64, 128, or 256.");
        pd.setType(URI.create("https://slpa.example/problems/user/invalid-avatar-size"));
        pd.setTitle("Invalid avatar size");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "USER_INVALID_AVATAR_SIZE");
        pd.setProperty("requestedSize", e.getRequestedSize());
        return pd;
    }

    private ProblemDetail handleUnknownField(
            UnrecognizedPropertyException e, HttpServletRequest req) {
        String sanitized = e.getPropertyName() == null ? "<null>"
                : e.getPropertyName().replaceAll("[\\r\\n\\t]", "_");
        if (sanitized.length() > 100) {
            sanitized = sanitized.substring(0, 100) + "...";
        }
        log.warn("Request body rejected unknown field: {}", sanitized);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "Unknown field in request body: '" + sanitized + "'.");
        pd.setType(URI.create("https://slpa.example/problems/user/unknown-field"));
        pd.setTitle("Unknown field");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "USER_UNKNOWN_FIELD");
        pd.setProperty("field", sanitized);
        return pd;
    }

    /**
     * Walks the full cause chain of {@code e} looking for an instance of {@code type}.
     * Returns the first match or null. Used instead of shallow {@code e.getCause()}
     * checks so future Spring or Jackson 3 wrapping additions don't silently break the
     * unknown-field path.
     */
    private static <T extends Throwable> T findCause(Throwable e, Class<T> type) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return type.cast(t);
            }
        }
        return null;
    }
}
