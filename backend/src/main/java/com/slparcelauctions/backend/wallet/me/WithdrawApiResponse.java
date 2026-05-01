package com.slparcelauctions.backend.wallet.me;

public record WithdrawApiResponse(
        long queueId,
        long newBalance,
        long newAvailable,
        String status
) { }
