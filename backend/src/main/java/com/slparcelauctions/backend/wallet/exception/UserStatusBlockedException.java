package com.slparcelauctions.backend.wallet.exception;

/**
 * Thrown when a wallet operation is rejected because the user's account
 * status disallows it (banned / frozen / suspended). The terminal-side
 * response on {@code /sl/wallet/deposit} is {@code REFUND/USER_FROZEN}; on
 * {@code /sl/wallet/withdraw-request} it is {@code REFUND_BLOCKED/USER_FROZEN}.
 * On user-facing endpoints, maps to 403.
 */
public class UserStatusBlockedException extends RuntimeException {
    private final Long userId;
    private final String status;

    public UserStatusBlockedException(Long userId, String status) {
        super("user status blocks wallet operation: userId=" + userId + ", status=" + status);
        this.userId = userId;
        this.status = status;
    }

    public Long getUserId() {
        return userId;
    }

    public String getStatus() {
        return status;
    }
}
