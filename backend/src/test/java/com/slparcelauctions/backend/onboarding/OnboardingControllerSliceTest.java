package com.slparcelauctions.backend.onboarding;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slparcelauctions.backend.auth.JwtService;
import com.slparcelauctions.backend.auth.test.WithMockAuthPrincipal;
import com.slparcelauctions.backend.common.exception.GlobalExceptionHandler;
import com.slparcelauctions.backend.sl.SlProfilePhotoService;
import com.slparcelauctions.backend.user.Role;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.user.dto.UserResponse;

/**
 * Slice tests for {@link OnboardingController}. Mocks the service +
 * scraper layer, drives the controller through {@code @WithMockAuthPrincipal}
 * (filters disabled — the security catch-all is exercised separately).
 */
@WebMvcTest(controllers = OnboardingController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({GlobalExceptionHandler.class})
class OnboardingControllerSliceTest {

    @Autowired private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean private OnboardingService onboardingService;
    @MockitoBean private SlProfilePhotoService slProfilePhotoService;
    @MockitoBean private UserRepository userRepository;
    @MockitoBean private JwtService jwtService;

    @Test
    @WithMockAuthPrincipal(userId = 42L)
    void skipAvatar_returns200_andDelegates() throws Exception {
        when(onboardingService.skipAvatar(42L)).thenReturn(stubUserResponse());

        mockMvc.perform(post("/api/v1/users/me/onboarding/avatar/skip"))
                .andExpect(status().isOk());

        verify(onboardingService).skipAvatar(42L);
    }

    @Test
    @WithMockAuthPrincipal(userId = 42L)
    void setDisplayName_validValue_returns200() throws Exception {
        when(onboardingService.setDisplayName(eq(42L), eq("Alice"))).thenReturn(stubUserResponse());

        mockMvc.perform(post("/api/v1/users/me/onboarding/display-name")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("displayName", "Alice"))))
                .andExpect(status().isOk());

        verify(onboardingService).setDisplayName(42L, "Alice");
    }

    @Test
    @WithMockAuthPrincipal(userId = 42L)
    void setDisplayName_nullValue_returns200() throws Exception {
        when(onboardingService.setDisplayName(eq(42L), eq(null))).thenReturn(stubUserResponse());

        mockMvc.perform(post("/api/v1/users/me/onboarding/display-name")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\": null}"))
                .andExpect(status().isOk());

        verify(onboardingService).setDisplayName(42L, null);
    }

    @Test
    @WithMockAuthPrincipal(userId = 42L)
    void setDisplayName_emptyString_returns200() throws Exception {
        when(onboardingService.setDisplayName(eq(42L), eq(""))).thenReturn(stubUserResponse());

        mockMvc.perform(post("/api/v1/users/me/onboarding/display-name")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\": \"\"}"))
                .andExpect(status().isOk());

        verify(onboardingService).setDisplayName(42L, "");
    }

    @Test
    @WithMockAuthPrincipal(userId = 42L)
    void setDisplayName_over50Chars_returns400_andDoesNotInvokeService() throws Exception {
        String longName = "A".repeat(51);

        mockMvc.perform(post("/api/v1/users/me/onboarding/display-name")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("displayName", longName))))
                .andExpect(status().isBadRequest());

        verify(onboardingService, never()).setDisplayName(any(), any());
    }

    @Test
    @WithMockAuthPrincipal(userId = 42L)
    void setDisplayName_unknownExtraField_returns400() throws Exception {
        // Canary for the global fail-on-unknown-properties flag.
        mockMvc.perform(post("/api/v1/users/me/onboarding/display-name")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\": \"Alice\", \"role\": \"ADMIN\"}"))
                .andExpect(status().isBadRequest());

        verify(onboardingService, never()).setDisplayName(any(), any());
    }

    @Test
    @WithMockAuthPrincipal(userId = 42L)
    void slProfilePhoto_userHasNoSlAvatarUuid_returns404() throws Exception {
        User u = freshUser();
        u.setSlAvatarUuid(null);
        when(userRepository.findById(42L)).thenReturn(Optional.of(u));

        mockMvc.perform(get("/api/v1/users/me/onboarding/sl-profile-photo"))
                .andExpect(status().isNotFound());

        verify(slProfilePhotoService, never()).fetchProfilePhoto(any());
    }

    @Test
    @WithMockAuthPrincipal(userId = 42L)
    void slProfilePhoto_serviceReturnsBytes_returns200WithImage() throws Exception {
        User u = freshUser();
        UUID avatar = UUID.randomUUID();
        u.setSlAvatarUuid(avatar);
        byte[] bytes = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
        when(userRepository.findById(42L)).thenReturn(Optional.of(u));
        when(slProfilePhotoService.fetchProfilePhoto(avatar)).thenReturn(Optional.of(bytes));

        mockMvc.perform(get("/api/v1/users/me/onboarding/sl-profile-photo"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_JPEG))
                .andExpect(content().bytes(bytes))
                .andExpect(header().string("Cache-Control",
                        org.hamcrest.Matchers.containsString("max-age=60")));
    }

    @Test
    @WithMockAuthPrincipal(userId = 42L)
    void slProfilePhoto_serviceReturnsEmpty_returns404() throws Exception {
        User u = freshUser();
        UUID avatar = UUID.randomUUID();
        u.setSlAvatarUuid(avatar);
        when(userRepository.findById(42L)).thenReturn(Optional.of(u));
        when(slProfilePhotoService.fetchProfilePhoto(avatar)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/users/me/onboarding/sl-profile-photo"))
                .andExpect(status().isNotFound());

        verify(slProfilePhotoService, times(1)).fetchProfilePhoto(avatar);
    }

    private static User freshUser() {
        User u = User.builder()
                .email("alice@example.com")
                .username("alice")
                .passwordHash("x")
                .build();
        // BaseMutableEntity assigns id only on persist; tests that mock the
        // repo don't touch it, so we leave id null.
        return u;
    }

    private static UserResponse stubUserResponse() {
        return new UserResponse(
                UUID.randomUUID(), "alice", "alice@example.com", "Alice",
                null, null, null, null, null, null, null, null, null,
                true, null, false,
                true, true,
                Map.of(), Map.of(),
                0L, null, false,
                null, null, 0L, Role.USER);
    }
}
