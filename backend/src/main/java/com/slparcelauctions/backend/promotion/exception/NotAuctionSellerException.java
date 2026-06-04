package com.slparcelauctions.backend.promotion.exception;

import java.util.UUID;

/** Caller is not the seller of the targeted auction. HTTP 403 / NOT_AUCTION_SELLER. */
public class NotAuctionSellerException extends RuntimeException {
    public NotAuctionSellerException(UUID auctionPublicId) {
        super("Not the seller of auction " + auctionPublicId);
    }
}
