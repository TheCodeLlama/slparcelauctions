package com.slparcelauctions.backend.auth;

import com.slparcelauctions.backend.admin.ban.BanCheckService;
import com.slparcelauctions.backend.auth.dto.AuthResult;
import com.slparcelauctions.backend.auth.dto.LoginRequest;
import com.slparcelauctions.backend.auth.dto.RegisterRequest;
import com.slparcelauctions.backend.auth.exception.EmailAlreadyExistsException;
import com.slparcelauctions.backend.auth.exception.InvalidCredentialsException;
import com.slparcelauctions.backend.auth.exception.TokenInvalidException;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserAlreadyExistsException;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.user.UserService;
import com.slparcelauctions.backend.user.dto.CreateUserRequest;
import com.slparcelauctions.backend.user.dto.UserResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Orchestration layer for authentication flows: register, login, token refresh, and logout.
 *
 * <p>This service coordinates the user slice ({@link UserService}, {@link UserRepository}),
 * the JWT layer ({@link JwtService}), and the refresh-token layer ({@link RefreshTokenService}).
 * It owns none of the business invariants; it delegates each concern to the appropriate
 * specialist and assembles an {@link AuthResult} for the controller to dispatch.
 *
 * <p>The {@link HttpServletRequest} parameters in {@link #register} and {@link #login} are
 * used to extract {@code User-Agent} and remote IP for refresh-token audit rows.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final UserService userService;
    private final UserRepository userRepository;
    private final RefreshTokenService refreshTokenService;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final BanCheckService banCheckService;

    // -------------------------------------------------------------------------
    // Register
    // -------------------------------------------------------------------------

    /**
     * Creates a new account and issues an access token + refresh token.
     *
     * <p>Delegates to {@link UserService#createUser} for the user record, then loads the
     * entity directly via {@link UserRepository#findById} for the freshly-assigned
     * {@code token_version}. The extra DB read is acceptable — registration is not a hot path.
     *
     * @param request  validated registration payload
     * @param httpReq  used to extract {@code User-Agent} and remote IP for the refresh-token row
     * @return access token, raw refresh token, and user snapshot
     * @throws EmailAlreadyExistsException if the email is already in use
     */
    public AuthResult register(RegisterRequest request, HttpServletRequest httpReq) {
        String ip = httpReq.getRemoteAddr();
        banCheckService.assertNotBanned(ip, null);
        UserResponse created;
        try {
            created = userService.createUser(
                    new CreateUserRequest(request.email(), request.password(), request.displayName()));
        } catch (UserAlreadyExistsException e) {
            throw new EmailAlreadyExistsException(request.email());
        }

        // One extra DB read — register is not a hot path.
        User user = userRepository.findById(created.id())
                .orElseThrow(() -> new IllegalStateException(
                        "User disappeared immediately after creation: id=" + created.id()));

        return buildResult(user, httpReq);
    }

    // -------------------------------------------------------------------------
    // Login
    // -------------------------------------------------------------------------

    /**
     * Authenticates a user by email and password and issues tokens.
     *
     * <p>Both "email not found" and "wrong password" throw {@link InvalidCredentialsException}
     * with an identical message, so the endpoint does not leak email existence through differing
     * response shapes.
     *
     * @param request  validated login payload
     * @param httpReq  used to extract {@code User-Agent} and remote IP for the refresh-token row
     * @return access token, raw refresh token, and user snapshot
     * @throws InvalidCredentialsException if the email is unknown or the password does not match
     */
    public AuthResult login(LoginRequest request, HttpServletRequest httpReq) {
        String ip = httpReq.getRemoteAddr();
        banCheckService.assertNotBanned(ip, null);
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(InvalidCredentialsException::new);

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }

        return buildResult(user, httpReq);
    }

    // -------------------------------------------------------------------------
    // Refresh
    // -------------------------------------------------------------------------

    /**
     * Rotates a refresh token and issues a new access token.
     *
     * <p>Delegates the entire rotation transaction (validation, reuse-detection cascade, revocation,
     * and new-token issuance) to {@link RefreshTokenService#rotate}. If rotation succeeds, the user
     * entity is loaded to obtain a fresh {@code tokenVersion} for the new access token.
     *
     * @param rawRefreshToken the opaque token value from the client's HttpOnly cookie
     * @param httpReq         used to extract {@code User-Agent} and remote IP for the new token row
     * @return new access token, new raw refresh token, and user snapshot
     * @throws TokenInvalidException if the refresh token is missing, unknown, or reuse was detected
     */
    public AuthResult refresh(String rawRefreshToken, HttpServletRequest httpReq) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new TokenInvalidException("Refresh token is missing.");
        }

        String userAgent = httpReq.getHeader("User-Agent");
        String ipAddress = httpReq.getRemoteAddr();

        RefreshTokenService.RotationResult rotation =
                refreshTokenService.rotate(rawRefreshToken, userAgent, ipAddress);

        // happy-path-only; the reuse-cascade path throws before reaching this line
        User user = userRepository.findById(rotation.userId())
                .orElseThrow(() -> new IllegalStateException(
                        "User disappeared during token refresh: id=" + rotation.userId()));

        AuthPrincipal principal = new AuthPrincipal(user.getId(), user.getPublicId(), user.getEmail(), user.getTokenVersion(), user.getRole());
        String newAccessToken = jwtService.issueAccessToken(principal);

        return new AuthResult(newAccessToken, rotation.rawToken(), UserResponse.from(user));
    }

    // -------------------------------------------------------------------------
    // Logout
    // -------------------------------------------------------------------------

    /**
     * Revokes a single refresh token. Idempotent — unknown or already-revoked tokens are
     * silently ignored (FOOTGUNS §B.7).
     *
     * @param rawRefreshToken the opaque token value from the client's HttpOnly cookie
     */
    public void logout(String rawRefreshToken) {
        refreshTokenService.revokeByRawToken(rawRefreshToken);
    }

    // -------------------------------------------------------------------------
    // Logout all devices
    // -------------------------------------------------------------------------

    /**
     * Revokes all refresh tokens for the user and bumps {@code token_version}, immediately
     * invalidating every outstanding access token within the 15-minute window.
     *
     * @param userId the authenticated user's primary key
     */
    public void logoutAll(Long userId) {
        refreshTokenService.revokeAllForUser(userId);
        userService.bumpTokenVersion(userId);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Builds an {@link AuthResult} from a fully-loaded {@link User} entity.
     * Extracts {@code User-Agent} and remote IP from the HTTP request for the refresh-token row.
     */
    private AuthResult buildResult(User user, HttpServletRequest httpReq) {
        String userAgent = httpReq.getHeader("User-Agent");
        String ipAddress = httpReq.getRemoteAddr();

        AuthPrincipal principal = new AuthPrincipal(user.getId(), user.getPublicId(), user.getEmail(), user.getTokenVersion(), user.getRole());
        String accessToken = jwtService.issueAccessToken(principal);

        RefreshTokenService.IssuedRefreshToken issued =
                refreshTokenService.issueForUser(user.getId(), userAgent, ipAddress);

        return new AuthResult(accessToken, issued.rawToken(), UserResponse.from(user));
    }
}
