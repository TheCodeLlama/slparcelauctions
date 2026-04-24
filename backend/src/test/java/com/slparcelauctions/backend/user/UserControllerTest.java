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
import com.slparcelauctions.backend.user.dto.CreateUserRequest;
import com.slparcelauctions.backend.user.dto.UserProfileResponse;
import com.slparcelauctions.backend.user.dto.UserResponse;

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
    private JwtService jwtService;

    @Test
    void createUser_returns201() throws Exception {
        UserResponse response = new UserResponse(
                1L, "alice@example.com", "Alice", null, null, null, null, null, null, null, null,
                false, null, false, Map.of(), Map.of(), OffsetDateTime.now(), OffsetDateTime.now());
        when(userService.createUser(any(CreateUserRequest.class))).thenReturn(response);

        CreateUserRequest request = new CreateUserRequest("alice@example.com", "password123", "Alice");

        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/users/1"))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.email").value("alice@example.com"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void createUser_invalidEmail_returns400() throws Exception {
        CreateUserRequest request = new CreateUserRequest("not-an-email", "password123", null);

        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.email").exists());
    }

    @Test
    void createUser_shortPassword_returns400() throws Exception {
        CreateUserRequest request = new CreateUserRequest("ok@example.com", "short", null);

        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.password").exists());
    }

    @Test
    void createUser_passwordWithoutDigitOrSymbol_returns400() throws Exception {
        // Long enough (>10) but only letters — must fail the regex.
        CreateUserRequest request = new CreateUserRequest(
                "ok@example.com", "alllettersnoothers", null);

        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.password").exists());
    }

    @Test
    void createUser_passwordWithoutLetter_returns400() throws Exception {
        // Long enough and has digits/symbols, but no letter — must fail the regex.
        CreateUserRequest request = new CreateUserRequest(
                "ok@example.com", "1234567890!", null);

        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.password").exists());
    }

    @Test
    void createUser_duplicateEmail_returns409() throws Exception {
        doThrow(UserAlreadyExistsException.email("dup@example.com"))
                .when(userService).createUser(any(CreateUserRequest.class));

        CreateUserRequest request = new CreateUserRequest("dup@example.com", "password123", null);

        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("User with email already exists: dup@example.com"));
    }

    @Test
    void getUserProfile_returns200() throws Exception {
        UserProfileResponse profile = new UserProfileResponse(
                42L, "Bob", "hello", null, null, null, null,
                false, null, null, 0, 0, 0, null, true, OffsetDateTime.now());
        when(userService.getPublicProfile(42L)).thenReturn(profile);

        mockMvc.perform(get("/api/v1/users/42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.displayName").value("Bob"));
    }

    @Test
    void getUserProfile_notFound_returns404() throws Exception {
        when(userService.getPublicProfile(99L)).thenThrow(new UserNotFoundException(99L));

        mockMvc.perform(get("/api/v1/users/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("User not found: id=99"));
    }

    @Test
    @WithMockAuthPrincipal(userId = 1L)
    void getMe_returnsUserDto_whenAuthenticated() throws Exception {
        UserResponse expected = new UserResponse(
                1L, "test@example.com", "Test User", null, null, null, null, null, null, null, null,
                false, null, false, Map.of(), Map.of(), OffsetDateTime.now(), OffsetDateTime.now());
        when(userService.getUserById(1L)).thenReturn(expected);

        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.email").value(expected.email()));
    }

    @Test
    void deleteCurrentUser_returns501() throws Exception {
        mockMvc.perform(delete("/api/v1/users/me"))
                .andExpect(status().isNotImplemented());
    }
}
