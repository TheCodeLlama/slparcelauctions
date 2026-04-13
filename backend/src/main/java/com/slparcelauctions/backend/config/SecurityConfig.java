package com.slparcelauctions.backend.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.slparcelauctions.backend.auth.JwtAuthenticationEntryPoint;
import com.slparcelauctions.backend.auth.JwtAuthenticationFilter;

import lombok.RequiredArgsConstructor;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final JwtAuthenticationEntryPoint authenticationEntryPoint;

    @Value("${cors.allowed-origin:http://localhost:3000}")
    private String allowedOrigin;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // FOOTGUNS §B.5: matcher order is first-match-wins. More-specific rules MUST come
                // before broader wildcards. Moving a rule down the list can silently open or close
                // endpoints — always add new matchers above the "/api/v1/**" catch-all.
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/v1/health").permitAll()
                        .requestMatchers(
                                "/api/v1/auth/register",
                                "/api/v1/auth/login",
                                // refresh authenticates via HttpOnly cookie inside the handler, not via SecurityContext
                                "/api/v1/auth/refresh",
                                // logout is idempotent and cookie-authenticated inside the handler (FOOTGUNS §B.7)
                                "/api/v1/auth/logout"
                        ).permitAll()
                        // User registration and public profile view are unauthenticated by design.
                        // /api/v1/users/me must remain authenticated — its more-specific rule below
                        // must come before the /{id} wildcard (FOOTGUNS §B.5).
                        .requestMatchers(HttpMethod.POST, "/api/v1/users").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/users/me").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/users/{id}").permitAll()
                        .requestMatchers("/api/v1/auth/logout-all").authenticated()
                        // WebSocket handshake is permitted at the HTTP layer.
                        // Authentication happens at the STOMP CONNECT frame via
                        // JwtChannelInterceptor. Do NOT change this to .authenticated() —
                        // the browser WebSocket API cannot set an Authorization header on
                        // the HTTP upgrade, so gating it here is impossible. See FOOTGUNS §F.16.
                        .requestMatchers("/ws/**").permitAll()
                        // SL-injected headers gate /api/v1/sl/verify - no JWT required.
                        // The SlHeaderValidator component runs inside the request handler
                        // and is the actual trust boundary. FOOTGUNS §B.5: this MUST sit
                        // before the /api/v1/** catch-all (first-match-wins).
                        .requestMatchers(HttpMethod.POST, "/api/v1/sl/verify").permitAll()
                        .requestMatchers("/api/v1/**").authenticated()
                        .anyRequest().denyAll())
                .exceptionHandling(eh -> eh.authenticationEntryPoint(authenticationEntryPoint))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(allowedOrigin));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "X-Requested-With"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
