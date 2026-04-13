package com.slparcelauctions.backend.sl.dto;

/**
 * Response body for {@code POST /api/v1/sl/verify}. Kept small on purpose:
 * the LSL caller only needs to know success + which user/avatar was linked
 * so the in-world script can give the player feedback.
 */
public record SlVerifyResponse(boolean verified, Long userId, String slAvatarName) {}
