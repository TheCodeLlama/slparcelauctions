package com.slparcelauctions.backend.realty.permission;

/**
 * Per-(group, agent) capability flags for the realty groups feature.
 *
 * <p>Stored on {@code realty_group_members.permissions} as a Postgres {@code TEXT[]} of enum
 * names. The leader of a group holds every permission implicitly (the authorizer short-circuits
 * to {@code true} when {@code user_id == realty_groups.leader_id}); a non-leader member only
 * holds the permissions present in their array.
 */
public enum RealtyGroupPermission {
    INVITE_AGENTS,
    REMOVE_AGENTS,
    EDIT_GROUP_PROFILE,
    CONFIGURE_FEES,

    /** Create an auction listing under this group. */
    CREATE_LISTING,

    /** Broker-level: cancel any case-3 listing of this group regardless of who created it. */
    MANAGE_ALL_LISTINGS,

    /** Discretionary spend from the group wallet. */
    SPEND_FROM_GROUP_WALLET,

    /** Initiate a withdrawal from the group wallet. */
    WITHDRAW_FROM_GROUP_WALLET,

    /** View the group's wallet balance + ledger. */
    VIEW_GROUP_TRANSACTIONS,

    /** Sub-project E -- register/unregister SL groups this realty group manages land for. */
    REGISTER_SL_GROUP,

    /**
     * Sub-project F -- per-member operations gated below the leader: bulk-edit member
     * commission rates (§6.7) and view per-member commission analytics (§6.8). Leader
     * holds this implicitly via the authorizer short-circuit.
     */
    MANAGE_MEMBERS;
}
