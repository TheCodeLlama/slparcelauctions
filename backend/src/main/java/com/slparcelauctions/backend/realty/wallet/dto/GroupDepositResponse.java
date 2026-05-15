package com.slparcelauctions.backend.realty.wallet.dto;

/**
 * Response body for POST /api/v1/realty/groups/{publicId}/wallet/deposit.
 * Spec §4.1.
 *
 * <p>Mirrors the result returned by
 * {@code RealtyGroupWalletService.depositFromMemberWallet}: the two paired
 * ledger row ids (group + depositor's personal) plus the post-transfer
 * available balances for both wallets. On an idempotent replay the ids are
 * the originals and the availabilities reflect the current snapshot.
 */
public record GroupDepositResponse(
        Long groupLedgerEntryId,
        Long personalLedgerEntryId,
        long newGroupAvailable,
        long newPersonalAvailable) {
}
