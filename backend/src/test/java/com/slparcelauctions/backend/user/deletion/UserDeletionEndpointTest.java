package com.slparcelauctions.backend.user.deletion;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.auth.JwtService;
import com.slparcelauctions.backend.user.Role;
import java.util.UUID;
import com.slparcelauctions.backend.user.deletion.exception.ActiveAuctionsException;
import com.slparcelauctions.backend.user.deletion.exception.InvalidPasswordException;
import com.slparcelauctions.backend.user.deletion.exception.UserAlreadyDeletedException;

/**
 * Controller-layer tests for the user-deletion endpoints. Uses
 * {@code @MockitoBean UserDeletionService} so the test does not require a database
 * — this lets us verify routing, authentication gates, request validation, and the
 * {@link UserDeletionExceptionHandler} response shapes without full DB setup.
 *
 * <p>Endpoints under test:
 * <ul>
 *   <li>{@code DELETE /api/v1/users/me} — self-service</li>
 *   <li>{@code DELETE /api/v1/admin/users/{id}} — admin-initiated</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
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
class UserDeletionEndpointTest {

    @Autowired MockMvc mvc;
    @Autowired JwtService jwtService;

    @MockitoBean UserDeletionService userDeletionService;

    // ------------------------------------------------------------------ //
    //  Token helpers                                                      //
    // ------------------------------------------------------------------ //

    private String userToken(long userId) {
        return jwtService.issueAccessToken(new AuthPrincipal(userId, UUID.randomUUID(), "user@example.com", 1L, Role.USER));
    }

    private String adminToken() {
        return jwtService.issueAccessToken(new AuthPrincipal(99L, UUID.randomUUID(), "admin@example.com", 1L, Role.ADMIN));
    }

    // ================================================================== //
    //  DELETE /api/v1/users/me                                           //
    // ================================================================== //

    @Test
    void deleteSelf_correctPassword_returns204() throws Exception {
        doNothing().when(userDeletionService).deleteSelf(anyLong(), anyString());

        mvc.perform(delete("/api/v1/users/me")
                .header("Authorization", "Bearer " + userToken(42L))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"correct-password\"}"))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteSelf_wrongPassword_returns403WithCode() throws Exception {
        doThrow(new InvalidPasswordException())
                .when(userDeletionService).deleteSelf(anyLong(), anyString());

        mvc.perform(delete("/api/v1/users/me")
                .header("Authorization", "Bearer " + userToken(42L))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"wrong\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INVALID_PASSWORD"));
    }

    @Test
    void deleteSelf_alreadyDeleted_returns410WithCode() throws Exception {
        doThrow(new UserAlreadyDeletedException(42L))
                .when(userDeletionService).deleteSelf(anyLong(), anyString());

        mvc.perform(delete("/api/v1/users/me")
                .header("Authorization", "Bearer " + userToken(42L))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"any\"}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("USER_ALREADY_DELETED"));
    }

    @Test
    void deleteSelf_activeAuctions_returns409WithCodeAndBlockingIds() throws Exception {
        doThrow(new ActiveAuctionsException(List.of(101L, 102L)))
                .when(userDeletionService).deleteSelf(anyLong(), anyString());

        mvc.perform(delete("/api/v1/users/me")
                .header("Authorization", "Bearer " + userToken(42L))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"any\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACTIVE_AUCTIONS"))
                .andExpect(jsonPath("$.blockingIds[0]").value(101))
                .andExpect(jsonPath("$.blockingIds[1]").value(102));
    }

    @Test
    void deleteSelf_missingPassword_returns400() throws Exception {
        mvc.perform(delete("/api/v1/users/me")
                .header("Authorization", "Bearer " + userToken(42L))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteSelf_unauthenticated_returns401() throws Exception {
        mvc.perform(delete("/api/v1/users/me")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"any\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ================================================================== //
    //  DELETE /api/v1/admin/users/{id}                                   //
    // ================================================================== //

    @Test
    void deleteUserAsAdmin_validNote_returns204() throws Exception {
        doNothing().when(userDeletionService).deleteByAdmin(anyLong(), anyLong(), anyString());

        mvc.perform(delete("/api/v1/admin/users/7")
                .header("Authorization", "Bearer " + adminToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"adminNote\":\"GDPR request\"}"))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteUserAsAdmin_alreadyDeleted_returns410WithCode() throws Exception {
        doThrow(new UserAlreadyDeletedException(7L))
                .when(userDeletionService).deleteByAdmin(anyLong(), anyLong(), anyString());

        mvc.perform(delete("/api/v1/admin/users/7")
                .header("Authorization", "Bearer " + adminToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"adminNote\":\"GDPR request\"}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("USER_ALREADY_DELETED"));
    }

    @Test
    void deleteUserAsAdmin_activeAuctions_returns409WithCode() throws Exception {
        doThrow(new ActiveAuctionsException(List.of(201L)))
                .when(userDeletionService).deleteByAdmin(anyLong(), anyLong(), anyString());

        mvc.perform(delete("/api/v1/admin/users/7")
                .header("Authorization", "Bearer " + adminToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"adminNote\":\"cleanup\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACTIVE_AUCTIONS"))
                .andExpect(jsonPath("$.blockingIds[0]").value(201));
    }

    @Test
    void deleteUserAsAdmin_emptyNote_returns400() throws Exception {
        mvc.perform(delete("/api/v1/admin/users/7")
                .header("Authorization", "Bearer " + adminToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"adminNote\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteUserAsRegularUser_returns403() throws Exception {
        mvc.perform(delete("/api/v1/admin/users/7")
                .header("Authorization", "Bearer " + userToken(42L))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"adminNote\":\"Test\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void deleteUserAsAdmin_unauthenticated_returns401() throws Exception {
        mvc.perform(delete("/api/v1/admin/users/7")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"adminNote\":\"Test\"}"))
                .andExpect(status().isUnauthorized());
    }
}
