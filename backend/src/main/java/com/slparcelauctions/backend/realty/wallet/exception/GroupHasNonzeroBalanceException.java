package com.slparcelauctions.backend.realty.wallet.exception;

/**
 * Thrown when a group dissolution is attempted but the group wallet has a nonzero
 * balance or reserved amount. Maps to 409 CONFLICT. Spec §5.5, §9.1.
 *
 * <p>Resolution: the leader must withdraw the remaining balance to their SL avatar
 * before the group can be dissolved.
 */
public class GroupHasNonzeroBalanceException extends RuntimeException {

    public GroupHasNonzeroBalanceException() {
        super("group wallet has nonzero balance; cannot dissolve");
    }
}
