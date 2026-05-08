package com.slparcelauctions.backend.parceltag.exception;

import java.net.URI;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Package-scoped exception advice for parcel-tag admin operations. Runs at a
 * higher precedence than the global handler so the conflict / not-found
 * mappings here win over generic 500 fallbacks.
 */
@RestControllerAdvice(basePackages = "com.slparcelauctions.backend.parceltag")
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ParcelTagExceptionHandler {

    @ExceptionHandler(ParcelTagCodeConflictException.class)
    public ProblemDetail handleConflict(
            ParcelTagCodeConflictException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                "A parcel tag with this code already exists.");
        pd.setTitle("Parcel Tag Code Conflict");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "PARCEL_TAG_CODE_CONFLICT");
        pd.setProperty("tagCode", e.getTagCode());
        return pd;
    }

    @ExceptionHandler(ParcelTagNotFoundException.class)
    public ProblemDetail handleNotFound(
            ParcelTagNotFoundException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, e.getMessage());
        pd.setTitle("Parcel Tag Not Found");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "PARCEL_TAG_NOT_FOUND");
        pd.setProperty("tagCode", e.getTagCode());
        return pd;
    }

    @ExceptionHandler(ParcelTagCategoryCodeConflictException.class)
    public ProblemDetail handleCategoryConflict(
            ParcelTagCategoryCodeConflictException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                "A parcel tag category with this code already exists.");
        pd.setTitle("Parcel Tag Category Code Conflict");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "PARCEL_TAG_CATEGORY_CODE_CONFLICT");
        pd.setProperty("categoryCode", e.getCategoryCode());
        return pd;
    }

    @ExceptionHandler(ParcelTagCategoryNotFoundException.class)
    public ProblemDetail handleCategoryNotFound(
            ParcelTagCategoryNotFoundException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, e.getMessage());
        pd.setTitle("Parcel Tag Category Not Found");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "PARCEL_TAG_CATEGORY_NOT_FOUND");
        pd.setProperty("categoryCode", e.getCategoryCode());
        return pd;
    }

    @ExceptionHandler(InactiveParcelTagCategoryException.class)
    public ProblemDetail handleInactiveCategory(
            InactiveParcelTagCategoryException e, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "The selected parcel tag category is inactive. Pick another or re-enable it.");
        pd.setTitle("Inactive Parcel Tag Category");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "INACTIVE_PARCEL_TAG_CATEGORY");
        pd.setProperty("categoryCode", e.getCategoryCode());
        return pd;
    }
}
