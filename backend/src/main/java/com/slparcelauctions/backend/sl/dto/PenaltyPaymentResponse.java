package com.slparcelauctions.backend.sl.dto;

/**
 * Body of a successful {@code POST /api/v1/sl/penalty-payment} response.
 * Spec §7.6: {@code remainingBalance} is the new
 * {@code User.penaltyBalanceOwed} after the payment was applied (or the
 * unchanged value on idempotent replay of a previously recorded
 * {@code slTransactionId}). Suspension stays active until this value
 * reaches zero.
 */
public record PenaltyPaymentResponse(Long remainingBalance) {}
