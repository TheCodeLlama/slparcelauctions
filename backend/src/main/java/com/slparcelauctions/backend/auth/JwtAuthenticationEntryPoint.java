package com.slparcelauctions.backend.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * Writes a ProblemDetail JSON body when a protected endpoint is reached without an authenticated
 * SecurityContext. Used by Spring Security via
 * {@code http.exceptionHandling(eh -> eh.authenticationEntryPoint(this))} in {@code SecurityConfig}.
 *
 * <p><strong>FOOTGUNS §B.2:</strong> {@code AuthenticationEntryPoint} runs outside Spring's
 * message converter chain. The body must be serialized manually via the injected
 * {@link ObjectMapper}. Relying on {@code @ResponseBody} or {@code ProblemDetail} auto-conversion
 * yields an empty 401 body and the frontend's {@code ApiError} parser falls back to a generic
 * "401 Unauthorized" string.
 *
 * <p>Also sets {@code Cache-Control: no-store} — auth failures must never be cached by
 * intermediaries (CDNs, proxies).
 */
@Component
@Slf4j
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    // ObjectMapper is thread-safe after configuration. Inline construction sidesteps a Spring
    // Boot 4 auto-config issue where the auto-configured ObjectMapper bean wasn't available to
    // this component's constructor during test context load. ProblemDetail uses only basic
    // types (String, URI, Instant-to-ISO, Map) so we don't need Spring's extra serializers.
    // See FOOTGUNS §B.2 — the entry point already bypasses the message converter chain, so
    // using a dedicated ObjectMapper here is consistent with that pattern.
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
            HttpStatus.UNAUTHORIZED,
            "Authentication is required to access this resource.");
        pd.setType(URI.create("https://slpa.example/problems/auth/token-missing"));
        pd.setTitle("Authentication required");
        pd.setInstance(URI.create(request.getRequestURI()));
        pd.setProperty("code", "AUTH_TOKEN_MISSING");

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-store");
        objectMapper.writeValue(response.getWriter(), pd);
    }
}
