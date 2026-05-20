package com.slparcelauctions.backend.wallet.dormancy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * Lightweight wiring test for {@link UserWalletDormancyJob}: verifies the
 * sweep delegates the two repository result lists to {@code task.flag} and
 * {@code task.escalateOrAutoReturn} respectively, and that a failure on one
 * user does not break the sweep for the rest. The eligibility predicates
 * themselves (positive balance, no active reservation holding all funds,
 * inactivity window) are owned by the native queries on
 * {@code UserRepository} -- their behaviour is exercised by the
 * task-level test using the same fixture data.
 */
class UserWalletDormancyJobTest {

    private final UserRepository userRepo = mock(UserRepository.class);
    private final UserWalletDormancyTask task = mock(UserWalletDormancyTask.class);
    private final UserWalletDormancyJob job = new UserWalletDormancyJob(
        userRepo, task, /* windowDays */ 30, /* phaseDurationDays */ 7);

    @Test
    void sweepFlagsNewlyEligibleAndEscalatesPhaseDueUsers() {
        User eligible1 = userWithId(10L);
        User eligible2 = userWithId(11L);
        User phaseDue = userWithId(20L);

        when(userRepo.findEligibleForDormancyFlag(30))
            .thenReturn(List.of(eligible1, eligible2));
        when(userRepo.findDormancyPhaseDue(7))
            .thenReturn(List.of(phaseDue));

        job.sweep();

        ArgumentCaptor<User> flagged = ArgumentCaptor.forClass(User.class);
        verify(task, times(2)).flag(flagged.capture(), any());
        ArgumentCaptor<User> escalated = ArgumentCaptor.forClass(User.class);
        verify(task, times(1)).escalateOrAutoReturn(escalated.capture(), any());

        org.assertj.core.api.Assertions.assertThat(flagged.getAllValues())
            .containsExactlyInAnyOrder(eligible1, eligible2);
        org.assertj.core.api.Assertions.assertThat(escalated.getAllValues())
            .containsExactly(phaseDue);
    }

    @Test
    void sweepSkipsUserOnFailureAndContinues() {
        User bad = userWithId(7L);
        User good = userWithId(8L);
        when(userRepo.findEligibleForDormancyFlag(anyInt()))
            .thenReturn(List.of(bad, good));
        when(userRepo.findDormancyPhaseDue(anyInt())).thenReturn(List.of());

        doThrow(new RuntimeException("kaboom")).when(task).flag(eq(bad), any());

        job.sweep();

        // The failure on `bad` doesn't stop `good` from being flagged.
        verify(task).flag(eq(bad), any());
        verify(task).flag(eq(good), any());
    }

    @Test
    void sweepWithNoEligibleUsersIsNoop() {
        when(userRepo.findEligibleForDormancyFlag(anyInt())).thenReturn(List.of());
        when(userRepo.findDormancyPhaseDue(anyInt())).thenReturn(List.of());

        job.sweep();

        verify(task, never()).flag(any(), any());
        verify(task, never()).escalateOrAutoReturn(any(), any());
    }

    private static User userWithId(long id) {
        try {
            User u = User.builder()
                .username("u-" + id)
                .passwordHash("x")
                .build();
            java.lang.reflect.Field f =
                com.slparcelauctions.backend.common.BaseEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(u, id);
            return u;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
