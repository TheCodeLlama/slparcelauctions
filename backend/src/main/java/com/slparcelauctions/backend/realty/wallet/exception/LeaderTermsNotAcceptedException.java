package com.slparcelauctions.backend.realty.wallet.exception;

import java.util.UUID;
import lombok.Getter;

/**
 * Thrown when a withdrawal is initiated but the group leader has not yet accepted
 * the user-wallet terms of service. Maps to 422 UNPROCESSABLE_ENTITY. Spec §5.5.
 *
 * <p>The leader is the L$ recipient for group withdrawals; their wallet must have
 * accepted ToS before the receiving leg can accept L$.
 */
@Getter
public class LeaderTermsNotAcceptedException extends RuntimeException {

    private final UUID leaderPublicId;

    public LeaderTermsNotAcceptedException(UUID leaderPublicId) {
        super("group leader has not accepted wallet ToS: leader=" + leaderPublicId);
        this.leaderPublicId = leaderPublicId;
    }
}
