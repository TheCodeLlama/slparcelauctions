package com.slparcelauctions.backend.admin.audit;

import com.slparcelauctions.backend.user.Role;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
    "auth.cleanup.enabled=false",
    "slpa.notifications.cleanup.enabled=false",
    "slpa.notifications.sl-im.cleanup.enabled=false",
    "slpa.auction-end.enabled=false",
    "slpa.ownership-monitor.enabled=false",
    "slpa.escrow.ownership-monitor-job.enabled=false",
    "slpa.escrow.timeout-job.enabled=false",
    "slpa.escrow.command-dispatcher-job.enabled=false",
    "slpa.review.scheduler.enabled=false"
})
class AdminAuditLogServiceTest {

    @Autowired AdminAuditLogService service;
    @Autowired AdminActionRepository repo;
    @Autowired AdminActionService recorder;
    @Autowired UserRepository userRepository;

    private final List<Long> actionIds = new ArrayList<>();
    private final List<Long> userIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        actionIds.forEach(id -> repo.findById(id).ifPresent(repo::delete));
        actionIds.clear();
        userIds.forEach(id -> userRepository.findById(id).ifPresent(userRepository::delete));
        userIds.clear();
    }

    @Test
    void listReturnsAllWhenNoFilters() {
        seedAdminAction(AdminActionType.USER_DELETED_BY_ADMIN);
        seedAdminAction(AdminActionType.DISMISS_REPORT);
        Page<AdminAuditLogRow> result = service.list(AdminAuditLogFilters.empty(), 0, 50);
        assertThat(result.getTotalElements()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void listFiltersByActionType() {
        seedAdminAction(AdminActionType.USER_DELETED_BY_ADMIN);
        seedAdminAction(AdminActionType.DISMISS_REPORT);
        Page<AdminAuditLogRow> result = service.list(
                new AdminAuditLogFilters(AdminActionType.DISMISS_REPORT,
                        null, null, null, null, null), 0, 50);
        assertThat(result.getContent()).allMatch(
                r -> r.actionType() == AdminActionType.DISMISS_REPORT);
    }

    @Test
    void listFiltersByQSubstringInNotes() {
        seedAdminActionWithNotes("Spammer banned permanently");
        seedAdminActionWithNotes("Routine review");
        Page<AdminAuditLogRow> result = service.list(
                new AdminAuditLogFilters(null, null, null, null, null, "spammer"), 0, 50);
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).notes()).containsIgnoringCase("Spammer");
    }

    @Test
    void exportCsvStreamReturnsAllMatching() {
        seedAdminAction(AdminActionType.USER_DELETED_BY_ADMIN);
        seedAdminAction(AdminActionType.DISMISS_REPORT);
        long count = service.exportCsvStream(AdminAuditLogFilters.empty()).count();
        assertThat(count).isGreaterThanOrEqualTo(2);
    }

    @Test
    void rowIncludesAdminEmail() {
        Long adminId = seedAdminUser("admin-email-check-" + UUID.randomUUID() + "@example.com");
        seedAdminActionWithAdminId(adminId);
        Page<AdminAuditLogRow> result = service.list(
                new AdminAuditLogFilters(null, null, adminId, null, null, null), 0, 50);
        assertThat(result.getContent()).isNotEmpty();
        String expectedEmail = userRepository.findById(adminId).orElseThrow().getEmail();
        assertThat(result.getContent().get(0).adminEmail()).isEqualTo(expectedEmail);
    }

    // -------------------------------------------------------------------------
    // Seed helpers
    // -------------------------------------------------------------------------

    private AdminAction seedAdminAction(AdminActionType type) {
        Long adminId = seedAdminUser("audit-log-" + UUID.randomUUID() + "@example.com");
        AdminAction saved = recorder.record(
                adminId,
                type,
                AdminActionTargetType.USER,
                42L,
                "test note",
                Map.of("key", "value"));
        actionIds.add(saved.getId());
        return saved;
    }

    private AdminAction seedAdminActionWithNotes(String notes) {
        Long adminId = seedAdminUser("audit-log-" + UUID.randomUUID() + "@example.com");
        AdminAction saved = recorder.record(
                adminId,
                AdminActionType.USER_DELETED_BY_ADMIN,
                AdminActionTargetType.USER,
                42L,
                notes,
                Map.of());
        actionIds.add(saved.getId());
        return saved;
    }

    private AdminAction seedAdminActionWithAdminId(Long adminId) {
        AdminAction saved = recorder.record(
                adminId,
                AdminActionType.USER_DELETED_BY_ADMIN,
                AdminActionTargetType.USER,
                42L,
                "test",
                Map.of());
        actionIds.add(saved.getId());
        return saved;
    }

    private Long seedAdminUser(String email) {
        User admin = userRepository.save(User.builder()
                .email(email).username("u-" + java.util.UUID.randomUUID().toString().substring(0, 8))
                .passwordHash("x")
                .role(Role.ADMIN)
                .tokenVersion(1L)
                .build());
        userIds.add(admin.getId());
        return admin.getId();
    }
}
