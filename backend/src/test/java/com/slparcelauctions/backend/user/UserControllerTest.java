package com.slparcelauctions.backend.user;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;

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
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.auth.test.WithMockAuthPrincipal;
import com.slparcelauctions.backend.common.exception.GlobalExceptionHandler;
import com.slparcelauctions.backend.user.deletion.UserDeletionService;
import com.slparcelauctions.backend.user.dto.CreateUserRequest;
import com.slparcelauctions.backend.user.dto.UserProfileResponse;
import com.slparcelauctions.backend.user.dto.UserResponse;
import com.slparcelauctions.backend.user.Role;

@WebMvcTest(controllers = UserController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private AvatarService avatarService;

    @MockitoBean
    private UserDeletionService userDeletionService;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private UserRepository userRepository;

    private static final java.util.UUID USER_PUBLIC_ID =
            java.util.UUID.fromString("00000000-0000-0000-0000-00000000002a");
    private static final java.util.UUID MISSING_USER_PUBLIC_ID =
            java.util.UUID.fromString("00000000-0000-0000-0000-000000000063");

    @Test
    void createUser_returns201() throws Exception {
        java.util.UUID publicId = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001");
        UserResponse response = new UserResponse(
                publicId, "alice", "alice@example.com", "Alice", null, null, null, null, null, null, null, null,
                false, null, false, Map.of(), Map.of(),
                0L, null, false,
                OffsetDateTime.now(), OffsetDateTime.now(), 0L, Role.USER);
        when(userService.createUser(any(CreateUserRequest.class))).thenReturn(response);

        CreateUserRequest request = new CreateUserRequest("alice", "password123");

        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/users/" + publicId))
                .andExpect(jsonPath("$.publicId").value(publicId.toString()))
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void createUser_blankUsername_returns400() throws Exception {
        // Blank fails @NotBlank; username field must be the violation surface.
        CreateUserRequest request = new CreateUserRequest("", "password123!");

        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.username").exists());
    }

    @Test
    void createUser_shortPassword_returns400() throws Exception {
        CreateUserRequest request = new CreateUserRequest("ok", "short");

        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.password").exists());
    }

    @Test
    void createUser_passwordWithoutDigitOrSymbol_returns400() throws Exception {
        // Long enough (>10) but only letters — must fail the regex.
        CreateUserRequest request = new CreateUserRequest("ok", "alllettersnoothers");

        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.password").exists());
    }

    @Test
    void createUser_passwordWithoutLetter_returns400() throws Exception {
        // Long enough and has digits/symbols, but no letter — must fail the regex.
        CreateUserRequest request = new CreateUserRequest("ok", "1234567890!");

        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.password").exists());
    }

    @Test
    void createUser_duplicateUsername_returns409() throws Exception {
        doThrow(UserAlreadyExistsException.username("dup"))
                .when(userService).createUser(any(CreateUserRequest.class));

        CreateUserRequest request = new CreateUserRequest("dup", "password123!");

        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("User with username already exists: dup"));
    }

    @Test
    void getUserProfile_returns200() throws Exception {
        UserProfileResponse profile = new UserProfileResponse(
                USER_PUBLIC_ID, "Bob", "hello", null, null, null, null,
                false, null, null, 0, 0, 0, null, true, OffsetDateTime.now());
        User mockUser = org.mockito.Mockito.mock(User.class);
        when(mockUser.getId()).thenReturn(42L);
        when(userRepository.findByPublicId(USER_PUBLIC_ID)).thenReturn(Optional.of(mockUser));
        when(userService.getPublicProfile(42L)).thenReturn(profile);

        mockMvc.perform(get("/api/v1/users/" + USER_PUBLIC_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicId").value(USER_PUBLIC_ID.toString()))
                .andExpect(jsonPath("$.displayName").value("Bob"));
    }

    @Test
    void getUserProfile_notFound_returns404() throws Exception {
        // MISSING_USER_PUBLIC_ID has no stub → findByPublicId returns empty → 404
        mockMvc.perform(get("/api/v1/users/" + MISSING_USER_PUBLIC_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("User not found: " + MISSING_USER_PUBLIC_ID));
    }

    @Test
    @WithMockAuthPrincipal(userId = 1L)
    void getMe_returnsUserDto_whenAuthenticated() throws Exception {
        java.util.UUID publicId = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001");
        UserResponse expected = new UserResponse(
                publicId, "test", "test@example.com", "Test User", null, null, null, null, null, null, null, null,
                false, null, false, Map.of(), Map.of(),
                0L, null, false,
                OffsetDateTime.now(), OffsetDateTime.now(), 0L, Role.USER);
        when(userService.getUserById(1L)).thenReturn(expected);

        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicId").value(publicId.toString()))
                .andExpect(jsonPath("$.username").value(expected.username()));
    }

    @Test
    @WithMockAuthPrincipal(userId = 1L)
    void deleteCurrentUser_returns204() throws Exception {
        mockMvc.perform(delete("/api/v1/users/me")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"correct-password\"}"))
                .andExpect(status().isNoContent());
    }
}
