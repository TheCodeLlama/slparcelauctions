package com.slparcelauctions.backend.realty.wallet.dto;

/**
 * Sub-project G §7.3 -- group wallet withdrawal destination.
 * {@link #AVATAR} routes L$ to the group leader's verified SL avatar (the
 * pre-G flow). {@link #SL_GROUP} routes to the currently-registered
 * {@code RealtyGroupSlGroup} for the realty group (bot-fulfilled via
 * {@code Self.GiveGroupMoney}).
 */
public enum GroupWithdrawRecipient {
    AVATAR,
    SL_GROUP
}
