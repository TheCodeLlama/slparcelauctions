package com.slparcelauctions.backend.admin.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

@ExtendWith(MockitoExtension.class)
class AdminActionServiceTest {

    @Mock AdminActionRepository repository;
    @Mock UserRepository userRepository;

    @InjectMocks AdminActionService service;

    @Test
    void record_buildsRowAndSaves() {
        User admin = User.builder().id(42L).email("admin@x.com").passwordHash("x").build();
        when(userRepository.findById(42L)).thenReturn(Optional.of(admin));

        AdminAction saved = AdminAction.builder()
            .id(1L)
            .adminUser(admin)
            .actionType(AdminActionType.CREATE_BAN)
            .targetType(AdminActionTargetType.USER)
            .targetId(99L)
            .notes("banned for abuse")
            .details(Map.of("reason", "spam"))
            .build();
        when(repository.save(any())).thenReturn(saved);

        ArgumentCaptor<AdminAction> captor = ArgumentCaptor.forClass(AdminAction.class);

        AdminAction result = service.record(
            42L,
            AdminActionType.CREATE_BAN,
            AdminActionTargetType.USER,
            99L,
            "banned for abuse",
            Map.of("reason", "spam")
        );

        verify(repository).save(captor.capture());
        AdminAction captured = captor.getValue();

        assertThat(captured.getAdminUser()).isEqualTo(admin);
        assertThat(captured.getActionType()).isEqualTo(AdminActionType.CREATE_BAN);
        assertThat(captured.getTargetType()).isEqualTo(AdminActionTargetType.USER);
        assertThat(captured.getTargetId()).isEqualTo(99L);
        assertThat(captured.getNotes()).isEqualTo("banned for abuse");
        assertThat(captured.getDetails()).containsEntry("reason", "spam");

        assertThat(result.getId()).isEqualTo(1L);
    }

    @Test
    void record_nullDetails_storedAsEmptyMap() {
        User admin = User.builder().id(7L).email("admin2@x.com").passwordHash("x").build();
        when(userRepository.findById(7L)).thenReturn(Optional.of(admin));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ArgumentCaptor<AdminAction> captor = ArgumentCaptor.forClass(AdminAction.class);

        service.record(7L, AdminActionType.LIFT_BAN, AdminActionTargetType.BAN, 5L, null, null);

        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getDetails()).isEmpty();
    }

    @Test
    void record_adminUserNotFound_throwsIllegalState() {
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
            service.record(999L, AdminActionType.PROMOTE_USER, AdminActionTargetType.USER, 1L, null, null)
        )
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("999");
    }
}
