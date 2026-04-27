package com.slparcelauctions.backend.notification.slim.internal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Shared-secret auth filter for {@code /api/v1/internal/sl-im/**}. Constant-time
 * comparison via {@link MessageDigest#isEqual(byte[], byte[])} prevents secret
 * inference via response timing.
 *
 * <p>NOT annotated with {@code @Component} — registered as a manual {@code @Bean}
 * by {@link SlImInternalConfig} so that {@code @WebMvcTest} slice tests do not
 * auto-detect it and fail on missing {@code SlImInternalProperties}.
 */
@RequiredArgsConstructor
public class SlImTerminalAuthFilter extends OncePerRequestFilter {

    private final SlImInternalProperties props;
    private static final String PATH_PREFIX = "/api/v1/internal/sl-im/";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        return !req.getRequestURI().startsWith(PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
        throws IOException, ServletException {
        String header = req.getHeader("Authorization");
        String expected = "Bearer " + props.sharedSecret();
        boolean ok = header != null && MessageDigest.isEqual(
            header.getBytes(StandardCharsets.UTF_8),
            expected.getBytes(StandardCharsets.UTF_8));
        if (!ok) {
            res.setStatus(HttpStatus.UNAUTHORIZED.value());
            res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            res.getWriter().write(
                "{\"type\":\"about:blank\",\"title\":\"Unauthorized\",\"status\":401}");
            return;
        }
        chain.doFilter(req, res);
    }
}
