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
    private final SystemUserResolver systemUserResolver;

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

    /**
     * Records a batched, system-actor admin action with no specific target row.
     * Used by scheduled jobs that mutate state on behalf of "the system" (e.g.
     * {@code BulkSuspendedListingExpiryTask} writing a single
     * {@code REALTY_GROUP_BULK_SUSPEND_EXPIRY_RUN} row per execution carrying the
     * cancelled-row count in {@code details}).
     *
     * <p>The audit row is attributed to the {@link SystemUserResolver#getSystemUser()
     * system user}, both as actor (admin_user_id) and as synthetic target
     * ({@code targetType = USER}, {@code targetId = systemUser.id}). The
     * {@code admin_actions.target_id} column is {@code NOT NULL}, so a sentinel is
     * required; pinning it to the system user keeps the audit-log target filter
     * coherent (system actions group under "target = system user").
     */
    @Transactional
    public AdminAction recordSystemAction(
            AdminActionType actionType,
            Map<String, Object> details) {

        User system = systemUserResolver.getSystemUser();
        return repository.save(AdminAction.builder()
            .adminUser(system)
            .actionType(actionType)
            .targetType(AdminActionTargetType.USER)
            .targetId(system.getId())
            .notes(null)
            .details(details == null ? Map.of() : details)
            .build());
    }
}
