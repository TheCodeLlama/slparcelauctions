package com.slparcelauctions.backend.auth.dto;

import com.slparcelauctions.backend.user.dto.UserResponse;

/**
 * External client-facing response for the auth endpoints. Contains the access token (which the
 * frontend keeps in memory) and the user profile. Does NOT contain the refresh token — that lives
 * in an HttpOnly cookie set by the controller via the {@code Set-Cookie} header.
 *
 * <p>Paired with {@link AuthResult} (service-internal). The two-record split is load-bearing —
 * the type system prevents refresh-token leakage by making it structurally impossible for a
 * handler that returns this record to ship the refresh token in the body. See FOOTGUNS §B.10.
 *
 * <p>Do NOT merge this with {@link AuthResult} "for simplicity." The split is the structural
 * guard; merging it removes the guard.
 */
public record AuthResponse(String accessToken, UserResponse user) {}
