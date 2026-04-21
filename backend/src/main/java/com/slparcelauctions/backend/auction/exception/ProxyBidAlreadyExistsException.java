package com.slparcelauctions.backend.auction.exception;

/**
 * Raised by {@code ProxyBidService.createProxy} when the caller already owns an
 * {@code ACTIVE} proxy on the target auction. The partial unique index
 * {@code proxy_bids_one_active_per_user} provides DB-level enforcement; this
 * exception fires from the application-level pre-check so the error surface
 * stays at 409 CONFLICT instead of a coarser {@code DataIntegrityViolationException}.
 *
 * <p>Mapped to {@code 409 PROXY_BID_ALREADY_EXISTS} by
 * {@code AuctionExceptionHandler}. The remediation is either {@code PUT} to
 * raise the existing proxy's max or {@code DELETE} + re-{@code POST} to start
 * a new one.
 */
public class ProxyBidAlreadyExistsException extends RuntimeException {

    public ProxyBidAlreadyExistsException() {
        super("A proxy bid is already active on this auction; update or cancel it first.");
    }
}
