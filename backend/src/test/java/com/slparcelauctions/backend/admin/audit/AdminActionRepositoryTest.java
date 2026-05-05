package com.slparcelauctions.backend.admin.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import com.slparcelauctions.backend.user.Role;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
    "auth.cleanup.enabled=false",
    "slpa.auction-end.enabled=false",
    "slpa.ownership-monitor.enabled=false",
    "slpa.escrow.ownership-monitor-job.enabled=false",
    "slpa.escrow.timeout-job.enabled=false",
    "slpa.escrow.command-dispatcher-job.enabled=false",
    "slpa.review.scheduler.enabled=false",
    "slpa.notifications.cleanup.enabled=false",
    "slpa.notifications.sl-im.cleanup.enabled=false"
})
class AdminActionRepositoryTest {

    @Autowired AdminActionService adminActionService;
    @Autowired AdminActionRepository adminActionRepository;
    @Autowired UserRepository userRepository;

    private Long adminId;
    private List<Long> actionIds;

    @BeforeEach
    void seed() {
        String suffix = UUID.randomUUID().toString();
        User admin = userRepository.save(User.builder()
            .email("audit-admin-" + suffix + "@x.com").username("u-" + java.util.UUID.randomUUID().toString().substring(0, 8))
            .passwordHash("x")
            .role(Role.ADMIN)
            .tokenVersion(1L)
            .build());
        adminId = admin.getId();

        AdminAction a1 = adminActionService.record(
            adminId,
            AdminActionType.CREATE_BAN,
            AdminActionTargetType.USER,
            101L,
            "first action",
            null
        );
        AdminAction a2 = adminActionService.record(
            adminId,
            AdminActionType.LIFT_BAN,
            AdminActionTargetType.USER,
            101L,
            "second action",
            null
        );
        AdminAction a3 = adminActionService.record(
            adminId,
            AdminActionType.PROMOTE_USER,
            AdminActionTargetType.USER,
            202L,
            "different target",
            null
        );

        actionIds = List.of(a1.getId(), a2.getId(), a3.getId());
    }

    @AfterEach
    void cleanup() {
        if (actionIds != null) {
            actionIds.forEach(id -> adminActionRepository.findById(id).ifPresent(adminActionRepository::delete));
        }
        if (adminId != null) {
            userRepository.findById(adminId).ifPresent(userRepository::delete);
        }
    }

    @Test
    void findByTargetTypeAndTargetId_returnsOnlyMatchingRows_newestFirst() {
        Page<AdminAction> page = adminActionRepository.findByTargetTypeAndTargetIdOrderByCreatedAtDesc(
            AdminActionTargetType.USER,
            101L,
            PageRequest.of(0, 10)
        );

        assertThat(page.getTotalElements()).isEqualTo(2);
        List<AdminAction> content = page.getContent();
        assertThat(content).hasSize(2);
        assertThat(content.get(0).getCreatedAt()).isAfterOrEqualTo(content.get(1).getCreatedAt());
        assertThat(content).allMatch(a -> a.getTargetId().equals(101L));
        assertThat(content).allMatch(a -> a.getTargetType() == AdminActionTargetType.USER);
    }

    @Test
    void findByAdminUserId_returnsAllActionsForAdmin_newestFirst() {
        Page<AdminAction> page = adminActionRepository.findByAdminUserIdOrderByCreatedAtDesc(
            adminId,
            PageRequest.of(0, 10)
        );

        assertThat(page.getTotalElements()).isGreaterThanOrEqualTo(3);
        List<AdminAction> content = page.getContent();
        for (int i = 0; i < content.size() - 1; i++) {
            assertThat(content.get(i).getCreatedAt())
                .isAfterOrEqualTo(content.get(i + 1).getCreatedAt());
        }
        assertThat(content).allMatch(a -> a.getAdminUser().getId().equals(adminId));
    }

    @Test
    void record_withNullDetails_storesEmptyMap() {
        Page<AdminAction> page = adminActionRepository.findByTargetTypeAndTargetIdOrderByCreatedAtDesc(
            AdminActionTargetType.USER,
            101L,
            PageRequest.of(0, 10)
        );

        assertThat(page.getContent())
            .allMatch(a -> a.getDetails() != null && a.getDetails().isEmpty());
    }
}
