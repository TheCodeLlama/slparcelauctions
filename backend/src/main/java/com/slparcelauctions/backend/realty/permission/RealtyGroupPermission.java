package com.slparcelauctions.backend.realty.permission;

/**
 * Per-(group, agent) capability flags for the realty groups feature.
 *
 * <p>Stored on {@code realty_group_members.permissions} as a Postgres {@code TEXT[]} of enum
 * names. The leader of a group holds every permission implicitly (the authorizer short-circuits
 * to {@code true} when {@code user_id == realty_groups.leader_id}); a non-leader member only
 * holds the permissions present in their array.
 *
 * <p>Future sub-projects (D/E/F) append new values here:
 * <ul>
 *   <li>D — {@code SPEND_FROM_GROUP_WALLET}, {@code WITHDRAW_FROM_GROUP_WALLET}, {@code VIEW_GROUP_TRANSACTIONS}</li>
 *   <li>E — {@code REGISTER_SL_GROUP}</li>
 * </ul>
 * Each new value lands in the sub-project's PR, not here.
 */
public enum RealtyGroupPermission {
    /** Issue + revoke invitations on this group. */
    INVITE_AGENTS,

    /** Remove non-leader members from this group. */
    REMOVE_AGENTS,

    /** Edit the group's profile (name, description, website, logo, cover). Rename remains
     *  gated by the 30-day cooldown for non-admins regardless of this flag. */
    EDIT_GROUP_PROFILE,

    /** Edit the group's {@code agent_fee_rate} and {@code agent_fee_split}. */
    CONFIGURE_FEES,

    /** Create an auction listing under this group. Snapshot of the group's fee terms is
     *  written onto the auction at create time; consumed at SOLD close. */
    CREATE_LISTING,

    /** Manage (edit/pause/cancel) listings the holder personally created on the group's
     *  behalf when they are not the seller. Defined here so the enum is whole; wired by
     *  sub-project E when case-2 (member-owned parcel) ships. No-op in sub-project C. */
    MANAGE_OWN_LISTING,

    /** Broker-level: manage (pause/cancel) any of the group's listings regardless of who
     *  created them. Defined here so the enum is whole; wired by sub-project E. No-op in
     *  sub-project C. */
    MANAGE_ALL_LISTINGS,

    /** Sub-project D -- defined but not wired. Reserved for future discretionary group
     *  spend (advertising, paying member penalties, sponsored auctions). Listing-fee
     *  debits under CREATE_LISTING do not require this permission -- the spend is
     *  intrinsic to the listing. */
    SPEND_FROM_GROUP_WALLET,

    /** Sub-project D -- initiate a withdrawal from the group wallet. Recipient is always
     *  the group leader's verified SL avatar regardless of caller. */
    WITHDRAW_FROM_GROUP_WALLET,

    /** Sub-project D -- view the group's wallet balance + ledger. */
    VIEW_GROUP_TRANSACTIONS;
}
