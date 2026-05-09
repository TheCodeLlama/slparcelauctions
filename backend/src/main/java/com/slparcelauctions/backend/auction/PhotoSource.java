package com.slparcelauctions.backend.auction;

/**
 * Where an {@link AuctionPhoto} came from. SL_PARCEL_SNAPSHOT is the
 * parcel image fetched from Second Life on parcel lookup; at most one
 * such row per auction (partial unique index). USER_DEFAULT_COVER is the
 * user's persisted default cover image, copied into the listing at draft
 * creation; idempotency is enforced at the application layer (one row
 * per auction with this source). SELLER_UPLOAD is anything the seller
 * manually uploaded.
 */
public enum PhotoSource {
    SELLER_UPLOAD,
    SL_PARCEL_SNAPSHOT,
    USER_DEFAULT_COVER
}
