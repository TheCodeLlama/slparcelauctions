package com.slparcelauctions.backend.auth.dto;

import com.slparcelauctions.backend.user.dto.UserResponse;

/**
 * Internal service-to-controller transfer object carrying the full result of a successful
 * authentication event (register, login, or token refresh).
 *
 * <p><strong>Why this is not the JSON response body:</strong> {@code AuthResult} contains
 * <em>three</em> fields including a raw {@code refreshToken}. The refresh token must <strong>never
 * appear in the JSON body</strong> — it belongs exclusively in an {@code HttpOnly; Secure; SameSite=Strict}
 * cookie. Serialising it into the response body would expose it to JavaScript and defeat the
 * {@code HttpOnly} protection. See FOOTGUNS §B.10.
 *
 * <p>The controller layer is responsible for:
 * <ol>
 *   <li>Placing {@code refreshToken} in the {@code Set-Cookie} header.</li>
 *   <li>Building an {@link com.slparcelauctions.backend.auth.dto.AuthResponse AuthResponse}
 *       (two fields only: {@code accessToken} + {@code user}) for the JSON body.</li>
 * </ol>
 *
 * <p>Keeping these two records separate makes the two-field / three-field split a compile-time
 * constraint rather than a runtime convention — you cannot accidentally serialise
 * {@code AuthResult} as JSON and leak the refresh token.
 *
 * @param accessToken  signed JWT access token; place in the {@code Authorization: Bearer} flow
 * @param refreshToken raw opaque refresh token; <strong>cookie only, never in the JSON body</strong>
 * @param user         full user snapshot for the response body
 */
public record AuthResult(
        String accessToken,
        String refreshToken,
        UserResponse user) {
}
