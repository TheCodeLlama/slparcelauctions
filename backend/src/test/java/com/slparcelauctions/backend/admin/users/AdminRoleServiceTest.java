package com.slparcelauctions.backend.admin.users;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.slparcelauctions.backend.admin.audit.AdminActionService;
import com.slparcelauctions.backend.admin.audit.AdminActionTargetType;
import com.slparcelauctions.backend.admin.audit.AdminActionType;
import com.slparcelauctions.backend.admin.users.exception.SelfDemoteException;
import com.slparcelauctions.backend.admin.users.exception.UserAlreadyAdminException;
import com.slparcelauctions.backend.admin.users.exception.UserNotAdminException;
import com.slparcelauctions.backend.user.Role;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

@ExtendWith(MockitoExtension.class)
class AdminRoleServiceTest {

    @Mock UserRepository userRepo;
    @Mock AdminActionService adminActionService;

    private AdminRoleService service;

    private static final Long ADMIN_ID = 1L;
    private static final Long TARGET_ID = 2L;

    @BeforeEach
    void setUp() {
        service = new AdminRoleService(userRepo, adminActionService);
    }

    private User buildUser(Long id, Role role) {
        User u = User.builder()
            .email("user" + id + "@x.com")
            .passwordHash("x")
            .displayName("User" + id)
            .build();
        try {
            java.lang.reflect.Field f = User.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(u, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        u.setRole(role);
        return u;
    }

    // -------------------------------------------------------------------------
    // promote
    // -------------------------------------------------------------------------

    @Test
    void promote_alreadyAdmin_throws() {
        User target = buildUser(TARGET_ID, Role.ADMIN);
        when(userRepo.findById(TARGET_ID)).thenReturn(Optional.of(target));

        assertThatThrownBy(() -> service.promote(TARGET_ID, ADMIN_ID, "notes"))
            .isInstanceOf(UserAlreadyAdminException.class)
            .hasMessageContaining(TARGET_ID.toString());
    }

    @Test
    void promote_happyPath_flipsRoleAndBumpsTv_writesAuditRow() {
        User target = buildUser(TARGET_ID, Role.USER);
        when(userRepo.findById(TARGET_ID)).thenReturn(Optional.of(target));
        when(userRepo.save(any())).thenReturn(target);
        when(userRepo.bumpTokenVersion(TARGET_ID)).thenReturn(1);

        service.promote(TARGET_ID, ADMIN_ID, "promoting user");

        assertThat(target.getRole()).isEqualTo(Role.ADMIN);
        verify(userRepo).bumpTokenVersion(TARGET_ID);
        verify(adminActionService).record(
            eq(ADMIN_ID), eq(AdminActionType.PROMOTE_USER),
            eq(AdminActionTargetType.USER), eq(TARGET_ID),
            eq("promoting user"), eq(null));
    }

    // -------------------------------------------------------------------------
    // demote
    // -------------------------------------------------------------------------

    @Test
    void demote_selfDemote_throws() {
        assertThatThrownBy(() -> service.demote(ADMIN_ID, ADMIN_ID, "notes"))
            .isInstanceOf(SelfDemoteException.class);
    }

    @Test
    void demote_notAdmin_throws() {
        User target = buildUser(TARGET_ID, Role.USER);
        when(userRepo.findById(TARGET_ID)).thenReturn(Optional.of(target));

        assertThatThrownBy(() -> service.demote(TARGET_ID, ADMIN_ID, "notes"))
            .isInstanceOf(UserNotAdminException.class)
            .hasMessageContaining(TARGET_ID.toString());
    }

    @Test
    void demote_happyPath_flipsRoleAndBumpsTv_writesAuditRow() {
        User target = buildUser(TARGET_ID, Role.ADMIN);
        when(userRepo.findById(TARGET_ID)).thenReturn(Optional.of(target));
        when(userRepo.save(any())).thenReturn(target);
        when(userRepo.bumpTokenVersion(TARGET_ID)).thenReturn(1);

        service.demote(TARGET_ID, ADMIN_ID, "demoting user");

        assertThat(target.getRole()).isEqualTo(Role.USER);
        verify(userRepo).bumpTokenVersion(TARGET_ID);
        verify(adminActionService).record(
            eq(ADMIN_ID), eq(AdminActionType.DEMOTE_USER),
            eq(AdminActionTargetType.USER), eq(TARGET_ID),
            eq("demoting user"), eq(null));
    }

    // -------------------------------------------------------------------------
    // resetFrivolousCounter
    // -------------------------------------------------------------------------

    @Test
    void resetFrivolousCounter_zeroes_writesAuditRow() {
        User target = buildUser(TARGET_ID, Role.USER);
        target.setDismissedReportsCount(5L);
        when(userRepo.findById(TARGET_ID)).thenReturn(Optional.of(target));
        when(userRepo.save(any())).thenReturn(target);

        service.resetFrivolousCounter(TARGET_ID, ADMIN_ID, "reset counter");

        assertThat(target.getDismissedReportsCount()).isEqualTo(0L);
        verify(adminActionService).record(
            eq(ADMIN_ID), eq(AdminActionType.RESET_FRIVOLOUS_COUNTER),
            eq(AdminActionTargetType.USER), eq(TARGET_ID),
            eq("reset counter"), eq(null));
    }
}
