package com.slparcelauctions.backend.user.deletion;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
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
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
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

    private static final UUID ADMIN_UUID  = UUID.fromString("00000000-0000-aaaa-0010-000000000001");
    private static final UUID USER_UUID   = UUID.fromString("00000000-0000-aaaa-0010-000000000002");
    private static final UUID TARGET_UUID = UUID.fromString("00000000-0000-aaaa-0010-000000000007");

    @Autowired MockMvc mvc;
    @Autowired JwtService jwtService;
    @Autowired UserRepository userRepository;

    @MockitoBean UserDeletionService userDeletionService;

    private Long adminDbId;
    private Long userDbId;

    @BeforeEach
    void seedUsers() {
        adminDbId = userRepository.findByPublicId(ADMIN_UUID)
            .orElseGet(() -> userRepository.save(User.builder()
                .publicId(ADMIN_UUID).email("admin-deletion-ep@example.com").username("admin-deletion-ep")
                .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                .displayName("Admin").role(Role.ADMIN).verified(true).build()))
            .getId();
        userDbId = userRepository.findByPublicId(USER_UUID)
            .orElseGet(() -> userRepository.save(User.builder()
                .publicId(USER_UUID).email("user-deletion-ep@example.com").username("user-deletion-ep")
                .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                .displayName("User").role(Role.USER).verified(true).build()))
            .getId();
        // Seed the target user so resolveUserId(TARGET_UUID) succeeds in the controller.
        userRepository.findByPublicId(TARGET_UUID)
            .orElseGet(() -> userRepository.save(User.builder()
                .publicId(TARGET_UUID).email("target-deletion-ep@example.com").username("target-deletion-ep")
                .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                .displayName("Target").role(Role.USER).verified(true).build()));
    }

    // ------------------------------------------------------------------ //
    //  Token helpers                                                      //
    // ------------------------------------------------------------------ //

    private String userToken() {
        return jwtService.issueAccessToken(new AuthPrincipal(userDbId, USER_UUID, "user-deletion-ep@example.com", 1L, Role.USER));
    }

    private String adminToken() {
        return jwtService.issueAccessToken(new AuthPrincipal(adminDbId, ADMIN_UUID, "admin-deletion-ep@example.com", 1L, Role.ADMIN));
    }

    // ================================================================== //
    //  DELETE /api/v1/users/me                                           //
    // ================================================================== //

    @Test
    void deleteSelf_correctPassword_returns204() throws Exception {
        doNothing().when(userDeletionService).deleteSelf(anyLong(), anyString());

        mvc.perform(delete("/api/v1/users/me")
                .header("Authorization", "Bearer " + userToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"correct-password\"}"))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteSelf_wrongPassword_returns403WithCode() throws Exception {
        doThrow(new InvalidPasswordException())
                .when(userDeletionService).deleteSelf(anyLong(), anyString());

        mvc.perform(delete("/api/v1/users/me")
                .header("Authorization", "Bearer " + userToken())
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
                .header("Authorization", "Bearer " + userToken())
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
                .header("Authorization", "Bearer " + userToken())
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
                .header("Authorization", "Bearer " + userToken())
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

        mvc.perform(delete("/api/v1/admin/users/" + TARGET_UUID + "")
                .header("Authorization", "Bearer " + adminToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"adminNote\":\"GDPR request\"}"))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteUserAsAdmin_alreadyDeleted_returns410WithCode() throws Exception {
        doThrow(new UserAlreadyDeletedException(7L))
                .when(userDeletionService).deleteByAdmin(anyLong(), anyLong(), anyString());

        mvc.perform(delete("/api/v1/admin/users/" + TARGET_UUID + "")
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

        mvc.perform(delete("/api/v1/admin/users/" + TARGET_UUID + "")
                .header("Authorization", "Bearer " + adminToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"adminNote\":\"cleanup\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACTIVE_AUCTIONS"))
                .andExpect(jsonPath("$.blockingIds[0]").value(201));
    }

    @Test
    void deleteUserAsAdmin_emptyNote_returns400() throws Exception {
        mvc.perform(delete("/api/v1/admin/users/" + TARGET_UUID + "")
                .header("Authorization", "Bearer " + adminToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"adminNote\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteUserAsRegularUser_returns403() throws Exception {
        mvc.perform(delete("/api/v1/admin/users/" + TARGET_UUID + "")
                .header("Authorization", "Bearer " + userToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"adminNote\":\"Test\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void deleteUserAsAdmin_unauthenticated_returns401() throws Exception {
        mvc.perform(delete("/api/v1/admin/users/" + TARGET_UUID + "")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"adminNote\":\"Test\"}"))
                .andExpect(status().isUnauthorized());
    }
}
