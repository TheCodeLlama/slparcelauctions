package com.slparcelauctions.backend.auth;

import com.slparcelauctions.backend.admin.ban.BanCheckService;
import com.slparcelauctions.backend.auth.dto.AuthResult;
import com.slparcelauctions.backend.auth.dto.LoginRequest;
import com.slparcelauctions.backend.auth.dto.RegisterRequest;
import com.slparcelauctions.backend.auth.exception.InvalidCredentialsException;
import com.slparcelauctions.backend.auth.exception.TokenInvalidException;
import com.slparcelauctions.backend.auth.exception.UsernameAlreadyExistsException;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserAlreadyExistsException;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.user.UserService;
import com.slparcelauctions.backend.user.dto.CreateUserRequest;
import com.slparcelauctions.backend.user.dto.UserResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    UserService userService;

    @Mock
    UserRepository userRepository;

    @Mock
    RefreshTokenService refreshTokenService;

    @Mock
    JwtService jwtService;

    @Mock
    PasswordEncoder passwordEncoder;

    @Mock
    BanCheckService banCheckService;

    @Mock
    HttpServletRequest httpRequest;

    AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userService, userRepository, refreshTokenService,
                jwtService, passwordEncoder, banCheckService);
        // lenient: these stubs are only consumed by tests that exercise HTTP paths
        lenient().when(httpRequest.getHeader("User-Agent")).thenReturn("TestAgent/1.0");
        lenient().when(httpRequest.getRemoteAddr()).thenReturn("127.0.0.1");
    }

    // -------------------------------------------------------------------------
    // 1. register — creates user, issues tokens, returns result
    // -------------------------------------------------------------------------

    @Test
    void register_createsUserIssuesTokens() {
        User user = stubUser(42L, "alice");
        UserResponse userResponse = UserResponse.from(user);

        when(userService.createUser(any(CreateUserRequest.class))).thenReturn(userResponse);
        when(userRepository.findByPublicId(any())).thenReturn(Optional.of(user));
        when(jwtService.issueAccessToken(any(AuthPrincipal.class))).thenReturn("access-token");
        when(refreshTokenService.issueForUser(eq(42L), anyString(), anyString()))
                .thenReturn(new RefreshTokenService.IssuedRefreshToken(
                        "raw-refresh-token", OffsetDateTime.now().plusDays(7)));

        AuthResult result = authService.register(
                new RegisterRequest("alice", "Password1!"),
                httpRequest);

        assertThat(result.accessToken()).isEqualTo("access-token");
        assertThat(result.refreshToken()).isEqualTo("raw-refresh-token");
        assertThat(result.user().publicId()).isNotNull();

        verify(userService).createUser(any(CreateUserRequest.class));
        verify(userRepository).findByPublicId(any());
        verify(jwtService).issueAccessToken(any(AuthPrincipal.class));
        verify(refreshTokenService).issueForUser(eq(42L), anyString(), anyString());
    }

    @Test
    void register_userAlreadyExists_translatesToUsernameAlreadyExists() {
        when(userService.createUser(any(CreateUserRequest.class)))
                .thenThrow(UserAlreadyExistsException.username("alice"));

        assertThatThrownBy(() -> authService.register(
                new RegisterRequest("alice", "Password1!"),
                httpRequest))
                .isInstanceOf(UsernameAlreadyExistsException.class);
    }

    // -------------------------------------------------------------------------
    // 2. login — valid credentials return result
    // -------------------------------------------------------------------------

    @Test
    void login_withValidCredentials_returnsResult() {
        User user = stubUser(7L, "bob");

        when(userRepository.findByUsername("bob")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("secret123!", user.getPasswordHash())).thenReturn(true);
        when(jwtService.issueAccessToken(any(AuthPrincipal.class))).thenReturn("access-token");
        when(refreshTokenService.issueForUser(eq(7L), anyString(), anyString()))
                .thenReturn(new RefreshTokenService.IssuedRefreshToken(
                        "raw-refresh-token", OffsetDateTime.now().plusDays(7)));

        AuthResult result = authService.login(
                new LoginRequest("bob", "secret123!"),
                httpRequest);

        assertThat(result.accessToken()).isEqualTo("access-token");
        assertThat(result.refreshToken()).isEqualTo("raw-refresh-token");
        assertThat(result.user().publicId()).isNotNull();

        verify(userRepository).findByUsername("bob");
        verify(passwordEncoder).matches("secret123!", user.getPasswordHash());
        verify(jwtService).issueAccessToken(any(AuthPrincipal.class));
        verify(refreshTokenService).issueForUser(eq(7L), anyString(), anyString());
    }

    // -------------------------------------------------------------------------
    // 3. login — unknown username → InvalidCredentialsException
    // -------------------------------------------------------------------------

    @Test
    void login_withInvalidUsername_throwsInvalidCredentials() {
        when(userRepository.findByUsername("nobody")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(
                new LoginRequest("nobody", "anything123!"),
                httpRequest))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    // -------------------------------------------------------------------------
    // 4. login — wrong password → InvalidCredentialsException
    // -------------------------------------------------------------------------

    @Test
    void login_withWrongPassword_throwsInvalidCredentials() {
        User user = stubUser(5L, "carol");

        when(userRepository.findByUsername("carol")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrongpassword!", user.getPasswordHash())).thenReturn(false);

        assertThatThrownBy(() -> authService.login(
                new LoginRequest("carol", "wrongpassword!"),
                httpRequest))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    // -------------------------------------------------------------------------
    // 5. logout — delegates to revokeByRawToken
    // -------------------------------------------------------------------------

    @Test
    void logout_delegatesToRevoke() {
        authService.logout("some-raw-token");

        verify(refreshTokenService).revokeByRawToken("some-raw-token");
    }

    // -------------------------------------------------------------------------
    // 6. logoutAll — revokes all tokens and bumps token version
    // -------------------------------------------------------------------------

    @Test
    void logoutAll_revokesAllAndBumpsTokenVersion() {
        authService.logoutAll(99L);

        verify(refreshTokenService).revokeAllForUser(99L);
        verify(userService).bumpTokenVersion(99L);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static User stubUser(Long id, String username) {
        return User.builder()
                .id(id)
                .username(username)
                .passwordHash("hashed-password")
                .displayName("Test User")
                .tokenVersion(0L)
                .createdAt(OffsetDateTime.now())
                .build();
    }
}
