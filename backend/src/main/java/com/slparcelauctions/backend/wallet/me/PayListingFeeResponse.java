package com.slparcelauctions.backend.wallet.me;

public record PayListingFeeResponse(
        long newBalance,
        long newAvailable,
        String auctionStatus
) { }
