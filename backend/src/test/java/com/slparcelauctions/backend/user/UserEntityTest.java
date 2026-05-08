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

    @Test
    void getDisplayNameReturnsDisplayNameWhenSet() {
        User u = User.builder()
                .email("a@b.com").username("loginname")
                .passwordHash("x")
                .displayName("Chosen Name")
                .build();
        assertThat(u.getDisplayName()).isEqualTo("Chosen Name");
    }

    @Test
    void getDisplayNameFallsBackToUsernameWhenDisplayNameNull() {
        User u = User.builder()
                .email("a@b.com").username("loginname")
                .passwordHash("x")
                .build();
        assertThat(u.getDisplayName()).isEqualTo("loginname");
    }

    @Test
    void onboardingFlagsDefaultToFalse() {
        // Forced post-verify onboarding gates depend on these flags
        // initialising to false on every new account.
        User u = User.builder()
                .email("a@b.com").username("a")
                .passwordHash("x")
                .build();
        assertThat(u.getAvatarStepCompleted()).isFalse();
        assertThat(u.getDisplayNameStepCompleted()).isFalse();
    }

    @Test
    void getDisplayNameFallsBackToUsernameWhenDisplayNameBlank() {
        // Empty / whitespace-only strings (from trimmed updates or older
        // rows) are treated the same as null so a seller card never renders
        // an empty name.
        User u = User.builder()
                .email("a@b.com").username("loginname")
                .passwordHash("x")
                .displayName("   ")
                .build();
        assertThat(u.getDisplayName()).isEqualTo("loginname");
    }
}
