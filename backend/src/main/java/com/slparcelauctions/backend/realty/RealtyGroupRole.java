package com.slparcelauctions.backend.realty;

/**
 * Computed role of a member within a group.
 *
 * <p>Not stored on {@code realty_group_members}: derived at read time by comparing
 * {@code member.user_id} to {@code realty_groups.leader_id}. The leader is special
 * (one transferable seat per group; holds every permission implicitly). All other
 * members are agents, with permissions governed by their {@code permissions} array.
 */
public enum RealtyGroupRole {
    LEADER,
    AGENT
}
