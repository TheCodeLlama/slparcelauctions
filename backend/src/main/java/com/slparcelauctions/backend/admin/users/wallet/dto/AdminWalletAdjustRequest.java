package com.slparcelauctions.backend.admin.users.wallet.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Manual ADJUSTMENT ledger entry. {@code amount} is signed (positive credits,
 * negative debits). Zero amounts are rejected at the service layer.
 *
 * <p>{@code overrideReservationFloor} must be set explicitly to push balance
 * below the user's {@code reservedLindens} (which would leave bid reservations
 * under-reserved). The frontend exposes a checkbox for this; the backend
 * independently enforces the same rule.
 */
public record AdminWalletAdjustRequest(
    @NotNull Long amount,
    @NotBlank String notes,
    boolean overrideReservationFloor
) {}
