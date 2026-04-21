package com.slparcelauctions.backend.auction.exception;

/**
 * Raised by bidding paths when the caller has authenticated but has not yet
 * completed SL avatar verification. Mapped to {@code 403 NOT_VERIFIED} by
 * {@code AuctionExceptionHandler} — distinct from the generic
 * {@code AccessDeniedException} used on seller-only write paths (which maps
 * to {@code ACCESS_DENIED}) so the client can surface a targeted verification
 * prompt rather than a generic forbidden message.
 *
 * <p>The spec (§11) describes this as "existing from Epic 02 — reuse", but
 * the Epic 02 implementation shipped with {@code AccessDeniedException}
 * inline-throws rather than a dedicated exception. Task 2 introduces the
 * named exception for the bid-placement path; later tasks can migrate the
 * other write paths onto it if the {@code NOT_VERIFIED} error code becomes
 * canonical.
 */
public class NotVerifiedException extends RuntimeException {

    public NotVerifiedException() {
        super("Bidding requires a verified SL avatar.");
    }

    public NotVerifiedException(String message) {
        super(message);
    }
}
