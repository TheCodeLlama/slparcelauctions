package com.slparcelauctions.backend.realty.wallet.broadcast;

import java.util.UUID;

/**
 * WebSocket envelope published to {@code /topic/realty/groups/{publicId}} whenever the
 * group wallet balance changes. Spec §11.3.
 */
public record GroupWalletBalanceChangedEnvelope(
    String type,
    UUID groupPublicId,
    long balance,
    long reserved,
    long available,
    String latestEntryType,
    UUID latestEntryPublicId
) {
    public static GroupWalletBalanceChangedEnvelope of(
            UUID groupPublicId, long balance, long reserved, long available,
            String latestEntryType, UUID latestEntryPublicId) {
        return new GroupWalletBalanceChangedEnvelope(
            "GROUP_WALLET_BALANCE_CHANGED",
            groupPublicId, balance, reserved, available,
            latestEntryType, latestEntryPublicId);
    }
}
