package com.slparcelauctions.backend.wallet.sl.group;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code POST /api/v1/sl/wallet/group-deposit}. Posted by the in-world
 * SLParcels Terminal after a {@code money()} event fires AND the payer has a
 * pending group-deposit context slot (the group name the payer typed at the
 * prior {@code Pay to group} text-box prompt).
 *
 * <p>L$ is already in the script's hands when this is called -- every post-auth
 * failure path returns {@code REFUND} (not {@code ERROR}) so the LSL script
 * bounces the L$ back via {@code llTransferLindenDollars}. See design spec section 4.3.
 *
 * <p>The {@code groupName} carries the user-typed display name rather than a
 * UUID because (a) the terminal asks the payer to type the name directly,
 * sparing the script's heap from holding a parsed group-list; (b) the name
 * is the identifier the payer actually knows. Lookup is case-insensitive on
 * the backend; whitespace is trimmed.
 *
 * @param terminalId       registered terminal primary key (typically the SL
 *                         object UUID)
 * @param sharedSecret     {@code slpa.escrow.terminal-shared-secret} body
 *                         carrier (validated by {@code TerminalService})
 * @param payerUuid        SL avatar UUID who paid the L$
 * @param groupName        target realty group's display name (case-insensitive
 *                         match against active groups)
 * @param amount           L$ amount; must be {@code >= 1}
 * @param slTransactionKey SL-grid transaction key, doubles as the idempotency
 *                         key on the group ledger and the recorded
 *                         {@code slTransactionId}
 */
public record SlGroupDepositRequest(
        @NotBlank String terminalId,
        @NotBlank String sharedSecret,
        @NotBlank String payerUuid,
        @NotBlank String groupName,
        @NotNull @Min(1) Long amount,
        @NotBlank String slTransactionKey
) { }
