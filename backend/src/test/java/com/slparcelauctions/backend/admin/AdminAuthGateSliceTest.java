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

import com.slparcelauctions.backend.auth.JwtService;
import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.user.Role;
import java.util.UUID;

/**
 * Smoke test the admin gate: /api/v1/admin/** must be 401 anon, 403 USER, 404 ADMIN
 * (404 because the path doesn't exist yet — but that's AFTER the gate passes).
 */
@SpringBootTest
@AutoConfigureMockMvc
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
class AdminAuthGateSliceTest {

    @Autowired MockMvc mvc;
    @Autowired JwtService jwtService;

    @Test
    void anonymous_returns401() throws Exception {
        mvc.perform(get("/api/v1/admin/probe-task-1"))
           .andExpect(status().isUnauthorized());
    }

    @Test
    void userRole_returns403() throws Exception {
        String token = jwtService.issueAccessToken(
            new AuthPrincipal(1L, UUID.randomUUID(), "u@x.com", 1L, Role.USER));
        mvc.perform(get("/api/v1/admin/probe-task-1")
            .header("Authorization", "Bearer " + token))
           .andExpect(status().isForbidden());
    }

    @Test
    void adminRole_returns404_pathDoesNotExistButGatePassed() throws Exception {
        String token = jwtService.issueAccessToken(
            new AuthPrincipal(1L, UUID.randomUUID(), "a@x.com", 1L, Role.ADMIN));
        mvc.perform(get("/api/v1/admin/probe-task-1")
            .header("Authorization", "Bearer " + token))
           .andExpect(status().isNotFound());
    }
}
