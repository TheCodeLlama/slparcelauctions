package com.slparcelauctions.backend.realty.permission;

/**
 * Per-(group, agent) capability flags for the realty groups feature.
 *
 * <p>Stored on {@code realty_group_members.permissions} as a Postgres {@code TEXT[]} of enum
 * names. The leader of a group holds every permission implicitly (the authorizer short-circuits
 * to {@code true} when {@code user_id == realty_groups.leader_id}); a non-leader member only
 * holds the permissions present in their array.
 *
 * <p>Future sub-projects (C/D/E/F) append new values here:
 * <ul>
 *   <li>C — {@code CREATE_LISTING}, {@code MANAGE_OWN_LISTING}, {@code MANAGE_ALL_LISTINGS}</li>
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

    /** Edit the group's {@code agent_fee_rate} and {@code agent_fee_split}. Stored in A+B;
     *  consumed by sub-project C at auction-completion time. */
    CONFIGURE_FEES;
}
