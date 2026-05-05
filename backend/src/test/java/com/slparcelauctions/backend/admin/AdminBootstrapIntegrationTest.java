package com.slparcelauctions.backend.admin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
    "slpa.notifications.sl-im.cleanup.enabled=false",
    "slpa.admin.bootstrap-usernames[0]=present-user",
    "slpa.admin.bootstrap-usernames[1]=present-admin",
    "slpa.admin.bootstrap-usernames[2]=absent"
})
class AdminBootstrapIntegrationTest {

    @Autowired UserRepository userRepository;
    @Autowired AdminBootstrapInitializer initializer;

    private Long presentUserId, presentAdminId;

    @BeforeEach
    void seed() {
        presentUserId = userRepository.save(User.builder()
            .email("present-user@bootstrap.test").username("present-user")
            .passwordHash("x")
            .role(Role.USER)
            .tokenVersion(1L)
            .build()).getId();

        presentAdminId = userRepository.save(User.builder()
            .email("present-admin@bootstrap.test").username("present-admin")
            .passwordHash("x")
            .role(Role.ADMIN)
            .tokenVersion(1L)
            .build()).getId();
    }

    @AfterEach
    void cleanup() {
        userRepository.deleteById(presentUserId);
        userRepository.deleteById(presentAdminId);
    }

    @Test
    void onStartup_promotesUserRowAndLeavesAdminRowUnchanged() {
        initializer.promoteBootstrapAdmins();

        User user = userRepository.findById(presentUserId).orElseThrow();
        User admin = userRepository.findById(presentAdminId).orElseThrow();

        assertThat(user.getRole()).isEqualTo(Role.ADMIN);
        assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
    }

    @Test
    void onSecondCall_stillIdempotent_noErrorOnAlreadyAdmin() {
        initializer.promoteBootstrapAdmins();
        initializer.promoteBootstrapAdmins();

        User user = userRepository.findById(presentUserId).orElseThrow();
        assertThat(user.getRole()).isEqualTo(Role.ADMIN);
    }

    @Test
    void deliberatelyDemotedBootstrapAdmin_isRePromotedOnNextRun() {
        // Spec §10.6: bootstrap is a forward push, not configurable opt-out.
        initializer.promoteBootstrapAdmins();
        User user = userRepository.findById(presentUserId).orElseThrow();
        user.setRole(Role.USER);
        userRepository.save(user);

        initializer.promoteBootstrapAdmins();

        User reloaded = userRepository.findById(presentUserId).orElseThrow();
        assertThat(reloaded.getRole()).isEqualTo(Role.ADMIN);
    }
}
