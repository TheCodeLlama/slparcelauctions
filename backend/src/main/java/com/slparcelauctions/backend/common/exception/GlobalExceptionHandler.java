package com.slparcelauctions.backend.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.slparcelauctions.backend.auction.exception.NotVerifiedException;
import com.slparcelauctions.backend.sl.ParcelIngestException;
import com.slparcelauctions.backend.sl.exception.ExternalApiTimeoutException;
import com.slparcelauctions.backend.sl.exception.NotMainlandException;
import com.slparcelauctions.backend.sl.exception.ParcelNotFoundInSlException;
import com.slparcelauctions.backend.sl.exception.RegionNotFoundException;
import com.slparcelauctions.backend.user.UserAlreadyExistsException;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Cross-cutting exception handler for all exceptions not claimed by a slice-scoped handler
 * (e.g. {@code AuthExceptionHandler}, {@code VerificationExceptionHandler}). Produces RFC 9457
 * {@link ProblemDetail} responses.
 *
 * <p><strong>Ordering:</strong> Explicitly {@code @Order(Ordered.LOWEST_PRECEDENCE)} (the default
 * value, but documented for intent). Slice handlers run first; this is the last-resort catch-all.
 *
 * <p>Does NOT handle {@code AuthenticationException}: that is owned by
 * {@code JwtAuthenticationEntryPoint} which intercepts before the advice chain fires.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException e,
                                          HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Request validation failed.");
        pd.setType(URI.create("https://slpa.example/problems/validation"));
        pd.setTitle("Validation failed");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "VALIDATION_FAILED");

        Map<String, String> errors = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(fe ->
                errors.put(fe.getField(), fe.getDefaultMessage()));
        pd.setProperty("errors", errors);
        return pd;
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException e,
                                             HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "Invalid value '" + e.getValue() + "' for parameter '" + e.getName() + "'.");
        pd.setType(URI.create("https://slpa.example/problems/type-mismatch"));
        pd.setTitle("Type mismatch");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "TYPE_MISMATCH");
        return pd;
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ProblemDetail handleMethodValidation(HandlerMethodValidationException e,
                                                HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Request parameter validation failed.");
        pd.setType(URI.create("https://slpa.example/problems/validation"));
        pd.setTitle("Validation failed");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "VALIDATION_FAILED");
        return pd;
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(ConstraintViolationException e,
                                                    HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Request parameter validation failed.");
        pd.setType(URI.create("https://slpa.example/problems/validation"));
        pd.setTitle("Validation failed");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "VALIDATION_FAILED");
        return pd;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleNotReadable(HttpMessageNotReadableException e,
                                           HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Request body is missing or malformed.");
        pd.setType(URI.create("https://slpa.example/problems/malformed-request"));
        pd.setTitle("Malformed request");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "MALFORMED_REQUEST");
        return pd;
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException e,
                                            HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.FORBIDDEN, "You do not have permission to perform this action.");
        pd.setType(URI.create("https://slpa.example/problems/access-denied"));
        pd.setTitle("Access denied");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "ACCESS_DENIED");
        return pd;
    }

    /**
     * Mirrors the auction-package handler so {@link NotVerifiedException}
     * thrown from non-auction controllers (e.g. {@code ParcelController})
     * surfaces the same {@code NOT_VERIFIED} code instead of falling through
     * to the {@code Exception.class} 500 catch-all. The auction-package
     * handler retains the local mapping for in-package controllers; this
     * global mapping is the safety net for everything else.
     */
    @ExceptionHandler(NotVerifiedException.class)
    public ProblemDetail handleNotVerified(NotVerifiedException e,
                                           HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.FORBIDDEN, e.getMessage());
        pd.setType(URI.create("https://slpa.example/problems/not-verified"));
        pd.setTitle("Not Verified");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "NOT_VERIFIED");
        return pd;
    }

    @ExceptionHandler(UserAlreadyExistsException.class)
    public ProblemDetail handleUserAlreadyExists(UserAlreadyExistsException e,
                                                  HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, e.getMessage());
        pd.setType(URI.create("https://slpa.example/problems/conflict"));
        pd.setTitle("Conflict");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "CONFLICT");
        return pd;
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ProblemDetail handleNoSuchElement(NoSuchElementException e,
                                             HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, e.getMessage());
        pd.setType(URI.create("https://slpa.example/problems/not-found"));
        pd.setTitle("Not found");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "NOT_FOUND");
        return pd;
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail handleNotFound(ResourceNotFoundException e,
                                        HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, e.getMessage());
        pd.setType(URI.create("https://slpa.example/problems/resource-not-found"));
        pd.setTitle("Resource not found");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "RESOURCE_NOT_FOUND");
        return pd;
    }

    @ExceptionHandler(ResourceGoneException.class)
    public ProblemDetail handleGone(ResourceGoneException e,
                                    HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.GONE, e.getMessage());
        pd.setType(URI.create("https://slpa.example/problems/resource-gone"));
        pd.setTitle("Resource gone");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "RESOURCE_GONE");
        return pd;
    }

    /**
     * Handles Spring's multipart size limit exception. This exception is thrown by the
     * {@code StandardServletMultipartResolver} during request body parsing, BEFORE any
     * {@code @RestController} is invoked, so slice-scoped advice in the user package
     * never sees it. Must live in the global handler. Same URI and code as
     * {@code UserExceptionHandler.handleAvatarTooLarge} so clients cannot distinguish
     * which layer caught the oversized upload. See FOOTGUNS section F.28.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ProblemDetail handleMaxUploadSize(MaxUploadSizeExceededException e,
                                             HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.PAYLOAD_TOO_LARGE, "Avatar must be 2MB or less.");
        pd.setType(URI.create("https://slpa.example/problems/user/upload-too-large"));
        pd.setTitle("Upload too large");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "USER_UPLOAD_TOO_LARGE");
        return pd;
    }

    /**
     * Handles a missing required multipart part. Spring throws this when a
     * {@code @RequestParam} {@code MultipartFile} is required but the client sent
     * a multipart request that didn't include it. Maps to 400 Bad Request so the
     * client can correct the upload.
     */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ProblemDetail handleMissingRequestPart(MissingServletRequestPartException e,
                                                  HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "Required multipart part '" + e.getRequestPartName() + "' is missing.");
        pd.setType(URI.create("https://slpa.example/problems/missing-request-part"));
        pd.setTitle("Missing request part");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "MISSING_REQUEST_PART");
        pd.setProperty("part", e.getRequestPartName());
        return pd;
    }

    @ExceptionHandler(ParcelNotFoundInSlException.class)
    public ProblemDetail handleParcelNotFoundInSl(ParcelNotFoundInSlException e,
                                                  HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND,
                "Parcel does not exist in Second Life or has been deleted.");
        pd.setType(URI.create("https://slpa.example/problems/sl/parcel-not-found"));
        pd.setTitle("Parcel Not Found");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "SL_PARCEL_NOT_FOUND");
        pd.setProperty("slParcelUuid", e.getParcelUuid().toString());
        return pd;
    }

    @ExceptionHandler(RegionNotFoundException.class)
    public ProblemDetail handleRegionNotFound(RegionNotFoundException e,
                                              HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND,
                "Region does not exist in Second Life.");
        pd.setType(URI.create("https://slpa.example/problems/sl/region-not-found"));
        pd.setTitle("Region Not Found");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "SL_REGION_NOT_FOUND");
        pd.setProperty("regionName", e.getRegionName());
        return pd;
    }

    @ExceptionHandler(NotMainlandException.class)
    public ProblemDetail handleNotMainland(NotMainlandException e,
                                           HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "Only Mainland parcels are supported at this time.");
        pd.setType(URI.create("https://slpa.example/problems/sl/not-mainland"));
        pd.setTitle("Not Mainland");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "NOT_MAINLAND");
        pd.setProperty("gridX", e.getGridX());
        pd.setProperty("gridY", e.getGridY());
        return pd;
    }

    // Parcel-page parser failed to extract a required field from the SL World
    // API response. Surface as 422 (the upstream payload was unprocessable)
    // rather than letting it fall through to the catch-all 500 — defense in
    // depth so future drift in SL's HTML contract presents as a clean error
    // instead of an opaque correlation-ID + alert page. Sister-class to
    // {@link ParcelNotFoundInSlException} (404) and {@link NotMainlandException} (422).
    @ExceptionHandler(ParcelIngestException.class)
    public ProblemDetail handleParcelIngestFailed(ParcelIngestException e,
                                                  HttpServletRequest req) {
        log.warn("Parcel ingest failed at path={}: {}", req.getRequestURI(), e.getMessage());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "Could not parse parcel information from the Second Life World API. The page may have an unexpected format.");
        pd.setType(URI.create("https://slpa.example/problems/sl/parcel-ingest-failed"));
        pd.setTitle("Parcel ingest failed");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "PARCEL_INGEST_FAILED");
        return pd;
    }

    @ExceptionHandler(ExternalApiTimeoutException.class)
    public ProblemDetail handleExternalApiTimeout(ExternalApiTimeoutException e,
                                                  HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.GATEWAY_TIMEOUT,
                "An external Second Life service is unreachable. Please try again.");
        pd.setType(URI.create("https://slpa.example/problems/sl/api-timeout"));
        pd.setTitle("External API Timeout");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "SL_API_TIMEOUT");
        pd.setProperty("api", e.getApi());
        return pd;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException e,
                                               HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, e.getMessage());
        pd.setType(URI.create("https://slpa.example/problems/bad-request"));
        pd.setTitle("Bad request");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "BAD_REQUEST");
        return pd;
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ProblemDetail handleNoResource(NoResourceFoundException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Resource not found.");
        pd.setType(URI.create("https://slpa.example/problems/not-found"));
        pd.setTitle("Not found");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "NOT_FOUND");
        return pd;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception e, HttpServletRequest req) {
        String correlationId = UUID.randomUUID().toString();
        log.error("Unhandled exception [correlationId={}, path={}]",
                correlationId, req.getRequestURI(), e);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.");
        pd.setType(URI.create("https://slpa.example/problems/internal-server-error"));
        pd.setTitle("Internal server error");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "INTERNAL_SERVER_ERROR");
        pd.setProperty("correlationId", correlationId);
        return pd;
    }
}
