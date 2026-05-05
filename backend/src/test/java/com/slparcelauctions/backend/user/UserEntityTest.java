package com.slparcelauctions.backend.user;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pure POJO-level assertions for the {@link User} entity's built-in
 * defaults. Keeps the new {@code escrowExpiredUnfulfilled} counter
 * from drifting to {@code null} if a future edit ever removes the
 * {@code @Builder.Default}. See Epic 08 sub-spec 1 §3.4.
 */
class UserEntityTest {

    @Test
    void escrowExpiredUnfulfilledDefaultsToZero() {
        User u = User.builder()
                .email("a@b.com").username("a")
                .passwordHash("x")
                .build();
        assertThat(u.getEscrowExpiredUnfulfilled()).isEqualTo(0);
    }

    @Test
    void completedSalesAndCancelledWithBidsDefaultToZero() {
        // Paired with the escrowExpiredUnfulfilled default so a regression
        // on any of the three completion-rate denominator counters trips
        // the test file.
        User u = User.builder()
                .email("a@b.com").username("a")
                .passwordHash("x")
                .build();
        assertThat(u.getCompletedSales()).isEqualTo(0);
        assertThat(u.getCancelledWithBids()).isEqualTo(0);
    }
}
