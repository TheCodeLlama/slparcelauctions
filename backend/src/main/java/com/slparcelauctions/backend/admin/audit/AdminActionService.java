package com.slparcelauctions.backend.admin.audit;

import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AdminActionService {

    private final AdminActionRepository repository;
    private final UserRepository userRepository;

    @Transactional
    public AdminAction record(
            Long adminUserId,
            AdminActionType actionType,
            AdminActionTargetType targetType,
            Long targetId,
            String notes,
            Map<String, Object> details) {

        User admin = userRepository.findById(adminUserId)
            .orElseThrow(() -> new IllegalStateException("Admin user not found: " + adminUserId));

        return repository.save(AdminAction.builder()
            .adminUser(admin)
            .actionType(actionType)
            .targetType(targetType)
            .targetId(targetId)
            .notes(notes)
            .details(details == null ? Map.of() : details)
            .build());
    }
}
