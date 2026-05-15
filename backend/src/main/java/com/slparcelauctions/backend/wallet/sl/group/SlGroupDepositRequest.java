package com.slparcelauctions.backend.wallet.sl.group;

import java.util.UUID;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code POST /api/v1/sl/wallet/group-deposit}. Posted by the in-world
 * SLParcels Terminal after a {@code money()} event fires AND the payer has a
 * pending group-deposit context slot (selected via the prior
 * {@code /avatar-groups} dialog flow).
 *
 * <p>L$ is already in the script's hands when this is called — every post-auth
 * failure path returns {@code REFUND} (not {@code ERROR}) so the LSL script
 * bounces the L$ back via {@code llTransferLindenDollars}. See design spec §4.3.
 *
 * @param terminalId       registered terminal primary key (typically the SL
 *                         object UUID)
 * @param sharedSecret     {@code slpa.escrow.terminal-shared-secret} body
 *                         carrier (validated by {@code TerminalService})
 * @param payerUuid        SL avatar UUID who paid the L$
 * @param groupPublicId    target realty group's {@code public_id}
 * @param amount           L$ amount; must be {@code >= 1}
 * @param slTransactionKey SL-grid transaction key, doubles as the idempotency
 *                         key on the group ledger and the recorded
 *                         {@code slTransactionId}
 */
public record SlGroupDepositRequest(
        @NotBlank String terminalId,
        @NotBlank String sharedSecret,
        @NotBlank String payerUuid,
        @NotNull UUID groupPublicId,
        @NotNull @Min(1) Long amount,
        @NotBlank String slTransactionKey
) { }
