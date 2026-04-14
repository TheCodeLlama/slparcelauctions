package com.slparcelauctions.backend.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slparcelauctions.backend.auth.config.JwtConfig;
import com.slparcelauctions.backend.auth.dto.AuthResult;
import com.slparcelauctions.backend.auth.dto.LoginRequest;
import com.slparcelauctions.backend.auth.dto.RegisterRequest;
import com.slparcelauctions.backend.auth.exception.AuthExceptionHandler;
import com.slparcelauctions.backend.auth.exception.InvalidCredentialsException;
import com.slparcelauctions.backend.auth.test.WithMockAuthPrincipal;
import com.slparcelauctions.backend.common.exception.GlobalExceptionHandler;
import com.slparcelauctions.backend.user.dto.UserResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.OffsetDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AuthController.class)
@AutoConfigureMockMvc(addFilters = false) // filter is wired in SecurityConfig, tested separately
@Import({AuthExceptionHandler.class, GlobalExceptionHandler.class})
@TestPropertySource(properties = {
    "jwt.secret=dGVzdC1zZWNyZXQtdGVzdC1zZWNyZXQtdGVzdC1zZWNyZXQtdGVzdA==",
    "jwt.access-token-lifetime=PT15M",
    "jwt.refresh-token-lifetime=P7D"
})
class AuthControllerTest {

    @Autowired private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean private AuthService authService;
    @MockitoBean private JwtConfig jwtConfig;
    @MockitoBean private JwtService jwtService;

    @BeforeEach
    void setUp() {
        when(jwtConfig.getRefreshTokenLifetime()).thenReturn(Duration.ofDays(7));
    }

    @Test
    void register_returns201WithTokenAndUser() throws Exception {
        UserResponse user = stubUser();
        when(authService.register(any(), any()))
            .thenReturn(new AuthResult("access-token", "refresh-token", user));

        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    new RegisterRequest("new@example.com", "hunter22abc", "Newbie")))
                .with(csrf()))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.accessToken").value("access-token"))
            .andExpect(jsonPath("$.user.email").value("new@example.com"))
            .andExpect(jsonPath("$.refreshToken").doesNotExist())
            .andExpect(header().exists("Set-Cookie"));
    }

    @Test
    void register_returns400OnValidationFailure() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"not-an-email\",\"password\":\"short\",\"displayName\":\"\"}")
                .with(csrf()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.errors").isMap());
    }

    @Test
    void login_returns200WithTokenAndUser() throws Exception {
        UserResponse user = stubUser();
        when(authService.login(any(), any()))
            .thenReturn(new AuthResult("access-token", "refresh-token", user));

        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    new LoginRequest("user@example.com", "correct-pw")))
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").value("access-token"))
            .andExpect(jsonPath("$.refreshToken").doesNotExist())
            .andExpect(header().exists("Set-Cookie"));
    }

    @Test
    void login_returns401OnInvalidCredentials() throws Exception {
        doThrow(new InvalidCredentialsException()).when(authService).login(any(), any());

        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    new LoginRequest("user@example.com", "wrong-pw1")))
                .with(csrf()))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"));
    }

    @Test
    void refresh_returns200WithNewTokenAndRotatedCookie() throws Exception {
        UserResponse user = stubUser();
        when(authService.refresh(anyString(), any()))
            .thenReturn(new AuthResult("new-access", "new-refresh", user));

        mockMvc.perform(post("/api/v1/auth/refresh")
                .cookie(new jakarta.servlet.http.Cookie("refreshToken", "old-raw"))
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").value("new-access"))
            .andExpect(header().exists("Set-Cookie"));
    }

    @Test
    void logout_returns204AlwaysAndClearsCookie() throws Exception {
        doNothing().when(authService).logout(any());

        mockMvc.perform(post("/api/v1/auth/logout")
                .cookie(new jakarta.servlet.http.Cookie("refreshToken", "any"))
                .with(csrf()))
            .andExpect(status().isNoContent())
            .andExpect(header().exists("Set-Cookie"));
    }

    @Test
    @WithMockAuthPrincipal(userId = 42)
    void logoutAll_returns204AndClearsCookieWhenAuthenticated() throws Exception {
        doNothing().when(authService).logoutAll(any());

        mockMvc.perform(post("/api/v1/auth/logout-all").with(csrf()))
            .andExpect(status().isNoContent())
            .andExpect(header().exists("Set-Cookie"));
    }

    private UserResponse stubUser() {
        return new UserResponse(
            1L, "new@example.com", "Newbie", null, null, null, null, null,
            null, null, null, null, OffsetDateTime.now());
    }
}
