package com.slparcelauctions.backend.coupon;

import java.net.URI;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Slice-scoped advice that maps {@link CouponException} to an RFC 9457
 * {@link ProblemDetail}. Mirrors the pattern used by
 * {@code AuthExceptionHandler}: scoped to the coupon package and ordered
 * 100 above the global catch-all so it beats
 * {@code GlobalExceptionHandler#handleUnexpected} but stacks cleanly
 * with sibling slice handlers.
 *
 * <p>Status mapping by {@link CouponRedemptionError}:
 * <ul>
 *   <li>{@code UNKNOWN_CODE} - 404 Not Found</li>
 *   <li>{@code NOT_ELIGIBLE} - 403 Forbidden</li>
 *   <li>Everything else (already-redeemed, expired, paused, max
 *       reached, immutable-field, lifetime-required, signup-window-
 *       paired, inactive) - 409 Conflict (the operation is well-formed
 *       but rejected by a coupon-state rule)</li>
 * </ul>
 *
 * <p>The discriminator is exposed as {@code code} on the
 * {@code ProblemDetail} so the frontend can pick an i18n key without
 * parsing the human-readable detail string.
 */
@RestControllerAdvice(basePackages = "com.slparcelauctions.backend.coupon")
@Order(Ordered.LOWEST_PRECEDENCE - 100)
public class CouponExceptionHandler {

    @ExceptionHandler(CouponException.class)
    public ProblemDetail handle(CouponException e, HttpServletRequest req) {
        HttpStatus status = switch (e.getCode()) {
            case UNKNOWN_CODE -> HttpStatus.NOT_FOUND;
            case NOT_ELIGIBLE -> HttpStatus.FORBIDDEN;
            case ALREADY_REDEEMED, EXPIRED, PAUSED, MAX_REACHED, INACTIVE,
                    IMMUTABLE_FIELD, LIFETIME_REQUIRED, SIGNUP_WINDOW_PAIRED -> HttpStatus.CONFLICT;
        };
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        pd.setType(URI.create("https://slpa.example/problems/coupon/" + slugify(e.getCode())));
        pd.setTitle("Coupon error");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", e.getCode().name());
        return pd;
    }

    private static String slugify(CouponRedemptionError code) {
        return code.name().toLowerCase().replace('_', '-');
    }
}
