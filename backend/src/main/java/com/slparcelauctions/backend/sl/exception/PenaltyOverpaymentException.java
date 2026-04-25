package com.slparcelauctions.backend.sl.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Thrown by {@code PenaltyTerminalService.pay} when the requested
 * payment {@code amount} exceeds the seller's outstanding
 * {@code penaltyBalanceOwed}. Spec §7.6: the service refuses to drive
 * the balance negative — the terminal should refund the excess L$
 * in-world. Maps to HTTP 422 via
 * {@link com.slparcelauctions.backend.sl.SlExceptionHandler}.
 *
 * <p>{@code requested} is the L$ the terminal tried to apply;
 * {@code available} is the seller's current outstanding balance at the
 * moment the lock was acquired. Both are surfaced on the ProblemDetail
 * so the LSL script can tell the avatar "you tried to pay L${requested}
 * but only owe L${available}".
 */
@Getter
@RequiredArgsConstructor
public class PenaltyOverpaymentException extends RuntimeException {

    private final long requested;
    private final long available;
}
