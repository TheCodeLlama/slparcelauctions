package com.slparcelauctions.backend.realty;

/**
 * On leadership transfer, what happens to the outgoing leader.
 *
 * <p>{@link #STAY}: outgoing leader stays in the group as an agent with all permission
 * flags set. {@link #LEAVE}: outgoing leader's member row is deleted in the same
 * transaction as the leadership transfer.
 */
public enum OldLeaderAction {
    STAY,
    LEAVE
}
