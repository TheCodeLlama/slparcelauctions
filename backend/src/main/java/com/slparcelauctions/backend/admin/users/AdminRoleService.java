package com.slparcelauctions.backend.admin.users;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.admin.audit.AdminActionService;
import com.slparcelauctions.backend.admin.audit.AdminActionTargetType;
import com.slparcelauctions.backend.admin.audit.AdminActionType;
import com.slparcelauctions.backend.admin.users.exception.SelfDemoteException;
import com.slparcelauctions.backend.admin.users.exception.UserAlreadyAdminException;
import com.slparcelauctions.backend.admin.users.exception.UserNotAdminException;
import com.slparcelauctions.backend.user.Role;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AdminRoleService {

    private final UserRepository userRepo;
    private final AdminActionService adminActionService;

    @Transactional
    public void promote(Long targetUserId, Long callingAdminId, String notes) {
        User target = userRepo.findById(targetUserId)
            .orElseThrow(() -> new IllegalStateException("User not found: " + targetUserId));
        if (target.getRole() == Role.ADMIN) {
            throw new UserAlreadyAdminException(targetUserId);
        }
        target.setRole(Role.ADMIN);
        userRepo.save(target);
        userRepo.bumpTokenVersion(targetUserId);
        adminActionService.record(callingAdminId, AdminActionType.PROMOTE_USER,
            AdminActionTargetType.USER, targetUserId, notes, null);
    }

    @Transactional
    public void demote(Long targetUserId, Long callingAdminId, String notes) {
        if (targetUserId.equals(callingAdminId)) {
            throw new SelfDemoteException();
        }
        User target = userRepo.findById(targetUserId)
            .orElseThrow(() -> new IllegalStateException("User not found: " + targetUserId));
        if (target.getRole() != Role.ADMIN) {
            throw new UserNotAdminException(targetUserId);
        }
        target.setRole(Role.USER);
        userRepo.save(target);
        userRepo.bumpTokenVersion(targetUserId);
        adminActionService.record(callingAdminId, AdminActionType.DEMOTE_USER,
            AdminActionTargetType.USER, targetUserId, notes, null);
    }

    @Transactional
    public void resetFrivolousCounter(Long targetUserId, Long callingAdminId, String notes) {
        User target = userRepo.findById(targetUserId)
            .orElseThrow(() -> new IllegalStateException("User not found: " + targetUserId));
        target.setDismissedReportsCount(0L);
        userRepo.save(target);
        adminActionService.record(callingAdminId, AdminActionType.RESET_FRIVOLOUS_COUNTER,
            AdminActionTargetType.USER, targetUserId, notes, null);
    }
}
