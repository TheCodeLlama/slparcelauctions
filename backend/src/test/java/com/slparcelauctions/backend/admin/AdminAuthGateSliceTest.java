package com.slparcelauctions.backend.admin;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.auth.JwtService;
import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.user.Role;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Smoke test the admin gate: /api/v1/admin/** must be 401 anon, 403 USER, 404 ADMIN
 * (404 because the path doesn't exist yet — but that's AFTER the gate passes).
 *
 * <p>Each authenticated test saves a minimal User so the auth filter can resolve
 * the JWT subject (publicId) to a real DB row. Tests are transactional so rows
 * are rolled back after each test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Transactional
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
class AdminAuthGateSliceTest {

    @Autowired MockMvc mvc;
    @Autowired JwtService jwtService;
    @Autowired UserRepository userRepository;

    /** Saves a minimal User and returns an access token whose subject is that user's publicId. */
    private String tokenFor(String email, Role role) {
        User user = userRepository.save(
            User.builder()
                .email(email)
                .passwordHash("irrelevant")
                .role(role)
                .build());
        return jwtService.issueAccessToken(
            new AuthPrincipal(user.getId(), user.getPublicId(), email, 1L, role));
    }

    @Test
    void anonymous_returns401() throws Exception {
        mvc.perform(get("/api/v1/admin/probe-task-1"))
           .andExpect(status().isUnauthorized());
    }

    @Test
    void userRole_returns403() throws Exception {
        String token = tokenFor("u@x.com", Role.USER);
        mvc.perform(get("/api/v1/admin/probe-task-1")
            .header("Authorization", "Bearer " + token))
           .andExpect(status().isForbidden());
    }

    @Test
    void adminRole_returns404_pathDoesNotExistButGatePassed() throws Exception {
        String token = tokenFor("a@x.com", Role.ADMIN);
        mvc.perform(get("/api/v1/admin/probe-task-1")
            .header("Authorization", "Bearer " + token))
           .andExpect(status().isNotFound());
    }
}
