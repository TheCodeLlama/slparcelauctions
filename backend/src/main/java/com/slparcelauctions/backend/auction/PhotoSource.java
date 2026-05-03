package com.slparcelauctions.backend.auction;

/**
 * Where an {@link AuctionPhoto} came from. SL_PARCEL_SNAPSHOT is the
 * parcel image fetched from Second Life on parcel lookup; at most one
 * such row per auction (partial unique index). SELLER_UPLOAD is anything
 * the seller manually uploaded.
 */
public enum PhotoSource {
    SELLER_UPLOAD,
    SL_PARCEL_SNAPSHOT
}
