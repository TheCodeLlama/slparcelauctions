package com.slparcelauctions.backend.wallet.sl;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code POST /api/v1/sl/wallet/deposit}. Posted by the in-world
 * SLParcels Terminal's {@code money()} handler.
 *
 * <p>{@code slTransactionKey} is the LSL {@code llGenerateKey()} value
 * generated once per {@code money()} event and reused across retries —
 * deduplicates retry storms.
 */
public record SlWalletDepositRequest(
        @NotBlank String payerUuid,
        @NotNull @Min(1) Long amount,
        @NotBlank String slTransactionKey,
        @NotBlank String terminalId,
        @NotBlank String sharedSecret
) { }
