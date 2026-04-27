package com.slparcelauctions.backend.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
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
import com.slparcelauctions.backend.bot.BotSharedSecretAuthorizer;
import com.slparcelauctions.backend.notification.slim.internal.SlImTerminalAuthFilter;

import lombok.RequiredArgsConstructor;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final JwtAuthenticationEntryPoint authenticationEntryPoint;
    private final BotSharedSecretAuthorizer botSharedSecretAuthorizer;
    private final SlImTerminalAuthFilter slImTerminalAuthFilter;

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
                        // Public listing-fee config (Epic 03 sub-spec 2 §7.6). The browse
                        // page and unauthenticated /activate landing both render the
                        // current fee so sellers see the exact cost before paying.
                        // FOOTGUNS §B.5: MUST sit before the /api/v1/** catch-all.
                        .requestMatchers(HttpMethod.GET, "/api/v1/config/listing-fee").permitAll()
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
                        // Method B (REZZABLE) LSL callback — same trust model as
                        // /api/v1/sl/verify: header-validated inside the handler,
                        // no JWT (the SL grid cannot authenticate). FOOTGUNS §B.5.
                        .requestMatchers(HttpMethod.POST, "/api/v1/sl/parcel/verify").permitAll()
                        // Escrow SL-facing endpoints (Epic 05 sub-spec 1 Tasks 4/5/7/9).
                        // Same dual-layer trust model as /api/v1/sl/verify: SL headers
                        // (X-SecondLife-Shard / X-SecondLife-Owner-Key) validated by
                        // SlHeaderValidator inside the handler, plus a body-carried
                        // sharedSecret verified by TerminalService.assertSharedSecret
                        // against slpa.escrow.terminal-shared-secret. All four paths
                        // are whitelisted now (not only the one Task 4 ships) so
                        // Tasks 5/7/9 do not have to re-touch SecurityConfig.
                        // FOOTGUNS §B.5: MUST sit before the /api/v1/** catch-all.
                        .requestMatchers(HttpMethod.POST, "/api/v1/sl/terminal/register").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/sl/escrow/payment").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/sl/escrow/payout-result").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/sl/listing-fee/payment").permitAll()
                        // Cancellation-penalty terminal endpoints (Epic 08 sub-spec 2
                        // Tasks 3 §7.5 / §7.6). Same trust model as the escrow
                        // terminal endpoints above: permitAll at the HTTP layer,
                        // SlHeaderValidator inside PenaltyTerminalController is the
                        // actual security boundary (X-SecondLife-Shard +
                        // X-SecondLife-Owner-Key). LSL scripts cannot present a
                        // JWT, so this is the only viable trust gate for terminal
                        // traffic. FOOTGUNS §B.5: MUST sit before the /api/v1/**
                        // catch-all (first-match-wins).
                        .requestMatchers(HttpMethod.POST, "/api/v1/sl/penalty-lookup").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/sl/penalty-payment").permitAll()
                        // --- New in Epic 02 sub-spec 2a ---
                        // Public avatar proxy. Must come before the /api/v1/** catch-all
                        // and before /api/v1/users/{id} (also public). FOOTGUNS section B.5
                        // matcher ordering.
                        .requestMatchers(HttpMethod.GET, "/api/v1/users/{id}/avatar/{size}").permitAll()
                        // Authenticated avatar upload.
                        .requestMatchers(HttpMethod.POST, "/api/v1/users/me/avatar").authenticated()
                        // Authenticated profile edit (explicit for grep-ability; catch-all also covers it).
                        .requestMatchers(HttpMethod.PUT, "/api/v1/users/me").authenticated()
                        // --- End Epic 02 sub-spec 2a additions ---
                        // --- New in Epic 03 sub-spec 1 Task 9 ---
                        // Public parcel tag reference. Anon browse uses this to
                        // render tag filters on /browse, so it must sit before
                        // the /api/v1/** authenticated catch-all.
                        // FOOTGUNS §B.5: matcher order is first-match-wins.
                        .requestMatchers(HttpMethod.GET, "/api/v1/parcel-tags").permitAll()
                        // Public listing-photo byte proxy. Must come before the
                        // /api/v1/** catch-all and before the seller-only upload
                        // endpoint. FOOTGUNS §B.5: matcher order is first-match-wins.
                        .requestMatchers(HttpMethod.GET, "/api/v1/auctions/*/photos/*/bytes").permitAll()
                        // Authenticated seller-only upload + delete.
                        .requestMatchers(HttpMethod.POST, "/api/v1/auctions/*/photos").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/auctions/*/photos/**").authenticated()
                        // Public bid history (Epic 04 sub-spec 1). Spec §4 marks
                        // GET /auctions/{id}/bids as public — bid identity is
                        // public per DESIGN.md §1589-1591. Must sit before the
                        // /api/v1/** catch-all. FOOTGUNS §B.5.
                        .requestMatchers(HttpMethod.GET, "/api/v1/auctions/*/bids").permitAll()
                        // Public browse + search (Epic 07 sub-spec 1 §5.1).
                        // Anonymous callers can list active auctions; the
                        // service-side filters never surface non-ACTIVE rows
                        // and the response is wrapped in a 30s public
                        // Cache-Control by AuctionSearchController.
                        // FOOTGUNS §B.5: this MUST sit before the
                        // /api/v1/** catch-all (first-match-wins).
                        .requestMatchers(HttpMethod.GET, "/api/v1/auctions/search").permitAll()
                        // Public featured rows (Epic 07 sub-spec 1 §5.2).
                        // Three sibling paths under /featured/ — the single-
                        // segment "*" matches /ending-soon, /just-listed, and
                        // /most-active without admitting deeper paths. Same
                        // 60s public Cache-Control posture as the controller.
                        // FOOTGUNS §B.5: matcher order is first-match-wins,
                        // so this rule MUST sit before /api/v1/**.
                        .requestMatchers(HttpMethod.GET, "/api/v1/auctions/featured/*").permitAll()
                        // Public bundled stats (Epic 07 sub-spec 1 §5.4).
                        // Anonymous homepage callers need the four-count
                        // snapshot; the response is wrapped in a 60s public
                        // Cache-Control matching the underlying Redis TTL.
                        // FOOTGUNS §B.5: this MUST sit before the
                        // /api/v1/** catch-all (first-match-wins).
                        .requestMatchers(HttpMethod.GET, "/api/v1/stats/public").permitAll()
                        // Public user-scoped active listings (Epic 04 sub-spec 2 §14).
                        // Anonymous access is allowed; SUSPENDED and pre-ACTIVE
                        // statuses are filtered server-side in the repository query
                        // so the response shape never leaks seller-only state.
                        // FOOTGUNS §B.5: this MUST sit before the /api/v1/**
                        // catch-all and — because first-match-wins — before any
                        // /api/v1/users/** authenticated rules.
                        .requestMatchers(HttpMethod.GET, "/api/v1/users/*/auctions").permitAll()
                        // --- End Epic 03 sub-spec 1 Task 9 additions ---
                        // Review submit endpoint (Epic 08 sub-spec 1 Task 1).
                        // JWT-required so ReviewController can read the
                        // caller's userId off the AuthPrincipal. Explicit
                        // so Task 2's GET /reviews paths (public) can be
                        // added in-place without disturbing the POST rule.
                        // FOOTGUNS §B.5: this MUST sit before the
                        // /api/v1/** catch-all (first-match-wins).
                        // Cancellation preview + history (Epic 08 sub-spec 2
                        // Task 2). Both are seller-private — JWT required so
                        // CancellationStatusController can read the caller's
                        // userId off the AuthPrincipal. The /me/* paths sit
                        // BEFORE the /api/v1/users/{id}-style wildcards above
                        // because matcher order is first-match-wins
                        // (FOOTGUNS §B.5) and well above the /api/v1/**
                        // catch-all so the contract is explicit at this
                        // surface.
                        .requestMatchers(HttpMethod.GET, "/api/v1/users/me/cancellation-status").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/users/me/cancellation-history").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auctions/*/reviews").authenticated()
                        // Review read endpoints (Epic 08 sub-spec 1 Task 2).
                        // GET /auctions/{id}/reviews and
                        // GET /users/{id}/reviews are public — the service
                        // enriches the auction-scoped response when a
                        // principal is present but non-party or anon
                        // callers see only visible reviews.
                        // GET /users/me/pending-reviews is authenticated
                        // so only the owner sees their pending queue.
                        // The /me/ rule MUST come before the /{id}
                        // wildcard (first-match-wins, FOOTGUNS §B.5).
                        .requestMatchers(HttpMethod.GET, "/api/v1/auctions/*/reviews").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/users/me/pending-reviews").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/users/*/reviews").permitAll()
                        // Review secondary actions (Epic 08 sub-spec 1
                        // Task 3). Both require a JWT so the controller
                        // can read the caller's userId off the
                        // AuthPrincipal for the reviewee / reviewer
                        // identity check in the service layer. Explicit
                        // rules (rather than relying on the /api/v1/**
                        // catch-all) keep the auth contract for this slice
                        // readable alongside the other review matchers
                        // and simplifies adjusting these paths later.
                        // FOOTGUNS §B.5: must sit before /api/v1/**.
                        .requestMatchers(HttpMethod.POST, "/api/v1/reviews/*/respond").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/reviews/*/flag").authenticated()
                        // Bot worker queue + callback surface (Epic 06 Task 3).
                        // Authentication is a shared bearer token validated by
                        // BotSharedSecretAuthorizer (constant-time compare via
                        // MessageDigest.isEqual). The .access(...) matcher is the
                        // single trust boundary for every /api/v1/bot/** path:
                        // /claim, /pending, /{id}/verify, and /{id}/monitor
                        // (Task 5) all gate through here. The Task 4 deprecated
                        // PUT /{id} shim was removed in Task 12.
                        // FOOTGUNS §B.5: matcher order is first-match-wins, so
                        // this rule MUST sit before the /api/v1/** catch-all.
                        .requestMatchers("/api/v1/bot/**").access((authentication, ctx) ->
                                new AuthorizationDecision(
                                        botSharedSecretAuthorizer.isAuthorized(ctx.getRequest())))
                        // Dev simulate helper - permit at HTTP layer always. The bean is only
                        // registered under @Profile("dev"); in prod the handler doesn't exist so
                        // the request 404s (falling through Spring MVC rather than Spring Security).
                        // FOOTGUNS §B.5: this MUST sit before the /api/v1/** catch-all.
                        .requestMatchers("/api/v1/dev/**").permitAll()
                        // SL IM terminal polling endpoints (Epic 09 sub-spec 3 Task 4).
                        // Authentication is a shared bearer token validated by
                        // SlImTerminalAuthFilter (constant-time compare via
                        // MessageDigest.isEqual). JWT is not used here — the in-world
                        // dispatcher script cannot obtain a user JWT.
                        // CSRF is already globally disabled (AbstractHttpConfigurer::disable
                        // above) so no additional ignoringRequestMatchers is needed.
                        // FOOTGUNS §B.5: MUST sit before the /api/v1/** catch-all.
                        .requestMatchers("/api/v1/internal/sl-im/**").permitAll()
                        .requestMatchers("/api/v1/**").authenticated()
                        .anyRequest().denyAll())
                .exceptionHandling(eh -> eh.authenticationEntryPoint(authenticationEntryPoint))
                // SlImTerminalAuthFilter runs before the JWT filter so it can short-circuit
                // /api/v1/internal/sl-im/** with a 401 before JWT processing begins.
                // JwtAuthenticationFilter has no registered Spring Security filter order, so
                // we position both custom filters before UsernamePasswordAuthenticationFilter
                // (a well-known sentinel with a registered order). SlImTerminalAuthFilter's
                // shouldNotFilter() returns true for all non-internal paths, keeping the
                // ordering harmless for the rest of the filter chain.
                .addFilterBefore(slImTerminalAuthFilter, UsernamePasswordAuthenticationFilter.class)
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
