package com.slparcelauctions.backend.wallet.me;

public record PayPenaltyApiResponse(
        long newBalance,
        long newAvailable,
        long newPenaltyOwed
) { }
