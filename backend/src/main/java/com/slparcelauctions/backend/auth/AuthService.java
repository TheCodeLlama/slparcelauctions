package com.slparcelauctions.backend.auth;

import com.slparcelauctions.backend.admin.ban.BanCheckService;
import com.slparcelauctions.backend.auth.dto.AuthResult;
import com.slparcelauctions.backend.auth.dto.LoginRequest;
import com.slparcelauctions.backend.auth.dto.RegisterRequest;
import com.slparcelauctions.backend.auth.exception.InvalidCredentialsException;
import com.slparcelauctions.backend.auth.exception.TokenInvalidException;
import com.slparcelauctions.backend.auth.exception.UsernameAlreadyExistsException;
import com.slparcelauctions.backend.realty.RealtyGroupMemberRepository;
import com.slparcelauctions.backend.realty.wallet.dormancy.GroupWalletDormancyTask;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserAlreadyExistsException;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.user.UserService;
import com.slparcelauctions.backend.user.dto.CreateUserRequest;
import com.slparcelauctions.backend.user.dto.UserResponse;
import com.slparcelauctions.backend.wallet.dormancy.UserWalletDormancyTask;
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
    private final RealtyGroupMemberRepository realtyGroupMemberRepository;
    private final GroupWalletDormancyTask groupWalletDormancyTask;
    private final UserWalletDormancyTask userWalletDormancyTask;

    // -------------------------------------------------------------------------
    // Register
    // -------------------------------------------------------------------------

    /**
     * Creates a new account and issues an access token + refresh token.
     *
     * <p>Delegates to {@link UserService#createUser} for the user record, then loads the
     * entity directly via {@link UserRepository#findByPublicId} for the freshly-assigned
     * {@code token_version}. The extra DB read is acceptable — registration is not a hot path.
     *
     * @param request  validated registration payload
     * @param httpReq  used to extract {@code User-Agent} and remote IP for the refresh-token row
     * @return access token, raw refresh token, and user snapshot
     * @throws UsernameAlreadyExistsException if the username is already in use
     */
    public AuthResult register(RegisterRequest request, HttpServletRequest httpReq) {
        String ip = httpReq.getRemoteAddr();
        banCheckService.assertNotBanned(ip, null);
        UserResponse created;
        try {
            created = userService.createUser(
                    new CreateUserRequest(request.username(), request.password()));
        } catch (UserAlreadyExistsException e) {
            throw new UsernameAlreadyExistsException(request.username());
        }

        // One extra DB read — register is not a hot path.
        User user = userRepository.findByPublicId(created.publicId())
                .orElseThrow(() -> new IllegalStateException(
                        "User disappeared immediately after creation: publicId=" + created.publicId()));

        return buildResult(user, httpReq);
    }

    // -------------------------------------------------------------------------
    // Login
    // -------------------------------------------------------------------------

    /**
     * Authenticates a user by username and password and issues tokens.
     *
     * <p>Both "username not found" and "wrong password" throw {@link InvalidCredentialsException}
     * with an identical message, so the endpoint does not leak username existence through differing
     * response shapes.
     *
     * @param request  validated login payload
     * @param httpReq  used to extract {@code User-Agent} and remote IP for the refresh-token row
     * @return access token, raw refresh token, and user snapshot
     * @throws InvalidCredentialsException if the username is unknown or the password does not match
     */
    public AuthResult login(LoginRequest request, HttpServletRequest httpReq) {
        String ip = httpReq.getRemoteAddr();
        banCheckService.assertNotBanned(ip, null);
        User user = userRepository.findByUsername(request.username())
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

        AuthPrincipal principal = new AuthPrincipal(user.getId(), user.getPublicId(), user.getUsername(), user.getTokenVersion(), user.getRole());
        String newAccessToken = jwtService.issueAccessToken(principal);

        clearWalletDormancyForUser(user.getId());
        clearGroupDormancyForUser(user.getId());

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
     *
     * <p>Also clears group-wallet dormancy state for any group the user belongs to (spec §10.4
     * reset path 1 — member login). Most users are in 0 groups so this is a lightweight no-op.
     */
    private AuthResult buildResult(User user, HttpServletRequest httpReq) {
        String userAgent = httpReq.getHeader("User-Agent");
        String ipAddress = httpReq.getRemoteAddr();

        AuthPrincipal principal = new AuthPrincipal(user.getId(), user.getPublicId(), user.getUsername(), user.getTokenVersion(), user.getRole());
        String accessToken = jwtService.issueAccessToken(principal);

        RefreshTokenService.IssuedRefreshToken issued =
                refreshTokenService.issueForUser(user.getId(), userAgent, ipAddress);

        clearWalletDormancyForUser(user.getId());
        clearGroupDormancyForUser(user.getId());

        return new AuthResult(accessToken, issued.rawToken(), UserResponse.from(user));
    }

    /**
     * Clears user-wallet dormancy for the given user on login / refresh
     * (spec docs/superpowers/specs/2026-05-19-user-wallet-dormancy-design.md
     * §4 reset path 1). Failures are caught and logged; a dormancy-clear
     * failure must never block a login.
     */
    private void clearWalletDormancyForUser(Long userId) {
        try {
            userWalletDormancyTask.clearForUser(userId);
        } catch (Exception ex) {
            log.warn("failed to clear user wallet dormancy on login for userId={}: {}",
                userId, ex.toString());
        }
    }

    /**
     * Clears group-wallet dormancy for every group the given user belongs to.
     * Called on login and refresh-token rotation so any member activity resets the
     * group's dormancy clock (spec §10.4 reset path 1).
     *
     * <p>Failures are caught and logged; a dormancy-clear failure must never block a login.
     */
    private void clearGroupDormancyForUser(Long userId) {
        try {
            for (Long groupId : realtyGroupMemberRepository.findGroupIdsByUserId(userId)) {
                groupWalletDormancyTask.clearForGroup(groupId);
            }
        } catch (Exception ex) {
            log.warn("failed to clear group dormancy on login for userId={}: {}", userId, ex.toString());
        }
    }
}
