package com.slparcelauctions.backend.auth;

import com.slparcelauctions.backend.auth.config.JwtConfig;
import com.slparcelauctions.backend.auth.dto.AuthResponse;
import com.slparcelauctions.backend.auth.dto.AuthResult;
import com.slparcelauctions.backend.auth.dto.LoginRequest;
import com.slparcelauctions.backend.auth.dto.RegisterRequest;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import java.time.Duration;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private static final String REFRESH_COOKIE_NAME = "refreshToken";
    private static final String REFRESH_COOKIE_PATH = "/api/v1/auth";

    private final AuthService authService;
    private final JwtConfig jwtConfig;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody RegisterRequest request,
                                 HttpServletRequest httpReq,
                                 HttpServletResponse httpResp) {
        AuthResult result = authService.register(request, httpReq);
        setRefreshCookie(httpResp, result.refreshToken(), jwtConfig.getRefreshTokenLifetime());
        return new AuthResponse(result.accessToken(), result.user());
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request,
                              HttpServletRequest httpReq,
                              HttpServletResponse httpResp) {
        AuthResult result = authService.login(request, httpReq);
        setRefreshCookie(httpResp, result.refreshToken(), jwtConfig.getRefreshTokenLifetime());
        return new AuthResponse(result.accessToken(), result.user());
    }

    @PostMapping("/refresh")
    public AuthResponse refresh(HttpServletRequest httpReq, HttpServletResponse httpResp) {
        String rawRefreshToken = readRefreshCookie(httpReq);
        AuthResult result = authService.refresh(rawRefreshToken, httpReq);
        setRefreshCookie(httpResp, result.refreshToken(), jwtConfig.getRefreshTokenLifetime());
        return new AuthResponse(result.accessToken(), result.user());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest httpReq, HttpServletResponse httpResp) {
        String rawRefreshToken = readRefreshCookie(httpReq);
        // Idempotent — never throws, always 204. See FOOTGUNS §B.7.
        authService.logout(rawRefreshToken);
        clearRefreshCookie(httpResp);
    }

    @PostMapping("/logout-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logoutAll(@AuthenticationPrincipal AuthPrincipal principal,
                          HttpServletResponse httpResp) {
        authService.logoutAll(principal.userId());
        clearRefreshCookie(httpResp);
    }

    private void setRefreshCookie(HttpServletResponse resp, String token, Duration lifetime) {
        ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE_NAME, token)
            .httpOnly(true)
            .secure(true)
            .sameSite("None")
            .path(REFRESH_COOKIE_PATH)
            .maxAge(lifetime)
            .build();
        resp.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void clearRefreshCookie(HttpServletResponse resp) {
        ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE_NAME, "")
            .httpOnly(true)
            .secure(true)
            .sameSite("None")
            .path(REFRESH_COOKIE_PATH)
            .maxAge(0)
            .build();
        resp.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private String readRefreshCookie(HttpServletRequest req) {
        if (req.getCookies() == null) return null;
        for (Cookie c : req.getCookies()) {
            if (REFRESH_COOKIE_NAME.equals(c.getName())) return c.getValue();
        }
        return null;
    }
}
