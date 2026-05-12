package com.slparcelauctions.backend.auction.monitoring;

/**
 * What kicked a listing into the {@code SUSPENDED} status, recorded on a
 * {@link ListingSuspension} row.
 *
 * <ul>
 *   <li>{@code AUTO_OWNERSHIP_CHANGE} -- ownership monitor saw the parcel
 *       transfer to an untrusted avatar.</li>
 *   <li>{@code AUTO_PARCEL_DELETED} -- ownership monitor saw the parcel
 *       disappear (deleted, merged, returned to Linden Lab).</li>
 *   <li>{@code ADMIN_INDIVIDUAL} -- an admin suspended a single listing from
 *       the auction admin surface.</li>
 *   <li>{@code ADMIN_GROUP_BULK} -- admin bulk-suspended every active listing
 *       attributed to a realty group as part of a group-suspension action.</li>
 * </ul>
 *
 * <p>Sub-project F spec §4.3.
 */
public enum ListingSuspensionCause {
    AUTO_OWNERSHIP_CHANGE,
    AUTO_PARCEL_DELETED,
    ADMIN_INDIVIDUAL,
    ADMIN_GROUP_BULK
}
