package com.slparcelauctions.backend.admin.ban;

/**
 * Determines which identifier(s) a ban applies to.
 *
 * <ul>
 *   <li>{@code IP}     — matches on ip_address only</li>
 *   <li>{@code AVATAR} — matches on sl_avatar_uuid only</li>
 *   <li>{@code BOTH}   — matches on either; captured in both query paths</li>
 * </ul>
 */
public enum BanType {
    IP,
    AVATAR,
    BOTH
}
