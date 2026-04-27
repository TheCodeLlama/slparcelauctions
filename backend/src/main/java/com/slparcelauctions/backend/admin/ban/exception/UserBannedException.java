package com.slparcelauctions.backend.admin.ban.exception;

import java.time.OffsetDateTime;

import lombok.Getter;

/**
 * Thrown by {@link com.slparcelauctions.backend.admin.ban.BanCheckService} when
 * an active ban is found for the requesting IP or avatar. Mapped to HTTP 403 by
 * {@link UserBannedExceptionHandler} with {@code code: USER_BANNED}.
 *
 * <p>{@code expiresAt == null} means the ban is permanent.
 */
@Getter
public class UserBannedException extends RuntimeException {

    private final OffsetDateTime expiresAt;

    public UserBannedException(OffsetDateTime expiresAt) {
        super("Account is suspended");
        this.expiresAt = expiresAt;
    }
}
