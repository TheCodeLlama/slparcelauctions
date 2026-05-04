package com.slparcelauctions.backend.wallet.me;

import java.util.UUID;

public record WithdrawApiResponse(
        UUID queuePublicId,
        long newBalance,
        long newAvailable,
        String status
) { }
