package com.slparcelauctions.backend.wallet.sl;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code POST /api/v1/sl/wallet/withdraw-request}. Posted by the
 * SLPA Terminal after the touch-confirmed withdraw flow. Recipient is
 * identified by {@code payerUuid} (the toucher) and the actual recipient
 * UUID for the {@code TerminalCommand} is always
 * {@code user.slAvatarUuid} (locked at verification, never client-supplied).
 */
public record SlWalletWithdrawRequest(
        @NotBlank String payerUuid,
        @NotNull @Min(1) Long amount,
        @NotBlank String slTransactionKey,
        @NotBlank String terminalId,
        @NotBlank String sharedSecret
) { }
