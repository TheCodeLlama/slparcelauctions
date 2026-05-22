package com.slparcelauctions.backend.auction;

/**
 * Where an {@link AuctionPhoto} came from. SL_PARCEL_SNAPSHOT is the
 * parcel image fetched from Second Life on parcel lookup; at most one
 * such row per auction (partial unique index). USER_DEFAULT_COVER is the
 * user's persisted default cover image, copied into the listing at draft
 * creation; idempotency is enforced at the application layer (one row
 * per auction with this source). GROUP_DEFAULT_COVER is the realty
 * group's persisted default-listing image, copied into a group-owned
 * listing at draft creation; takes precedence over a user default cover
 * when the auction's {@code realtyGroupId} is set. SELLER_UPLOAD is
 * anything the seller manually uploaded.
 */
public enum PhotoSource {
    SELLER_UPLOAD,
    SL_PARCEL_SNAPSHOT,
    USER_DEFAULT_COVER,
    GROUP_DEFAULT_COVER
}
