package com.slparcelauctions.backend.auth;

import com.slparcelauctions.backend.auth.exception.TokenExpiredException;
import com.slparcelauctions.backend.auth.exception.TokenInvalidException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.List;

/**
 * Best-effort JWT authentication filter. Extracts a Bearer token from the {@code Authorization}
 * header, parses it via {@link JwtService}, and on success sets an {@link AuthPrincipal} into the
 * Spring {@code SecurityContext}. On any validation failure, clears the context and lets the
 * request continue — {@code ExceptionTranslationFilter} downstream produces the 401 for
 * protected endpoints via {@link JwtAuthenticationEntryPoint}.
 *
 * <p><strong>Invariants:</strong>
 * <ul>
 *   <li>The filter NEVER throws.</li>
 *   <li>The filter NEVER writes to the response.</li>
 *   <li>No database hit — the principal is built entirely from JWT claims.</li>
 * </ul>
 *
 * <p>User freshness checks happen at write-path service boundaries via the {@code tv} claim,
 * not here. See spec §6.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                AuthPrincipal principal = jwtService.parseAccessToken(token);
                UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(
                        principal,
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().name())));
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (TokenExpiredException | TokenInvalidException e) {
                SecurityContextHolder.clearContext();
                log.debug("JWT validation failed: {}", e.getMessage());
            }
        }
        chain.doFilter(request, response);
    }
}
