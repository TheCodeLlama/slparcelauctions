package com.slparcelauctions.backend.auth;

import com.slparcelauctions.backend.auth.config.JwtConfig;
import com.slparcelauctions.backend.auth.test.JwtTestFactory;
import com.slparcelauctions.backend.user.Role;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import javax.crypto.SecretKey;
import java.time.Duration;
import java.util.Base64;
import java.util.Collection;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class JwtAuthenticationFilterTest {

    private static final String SECRET = base64Of32RandomBytes();

    private JwtAuthenticationFilter filter;
    private JwtTestFactory testFactory;
    private FilterChain chain;

    private static String base64Of32RandomBytes() {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    @BeforeEach
    void setUp() {
        JwtConfig config = new JwtConfig();
        config.setSecret(SECRET);
        config.setAccessTokenLifetime(Duration.ofMinutes(15));
        config.setRefreshTokenLifetime(Duration.ofDays(7));
        SecretKey key = JwtKeyFactory.buildKey(SECRET);
        JwtService jwtService = new JwtService(config, key);
        filter = new JwtAuthenticationFilter(jwtService);
        testFactory = JwtTestFactory.forKey(SECRET);
        chain = mock(FilterChain.class);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void doFilter_setsPrincipalOnValidToken() throws Exception {
        UUID publicId = UUID.randomUUID();
        AuthPrincipal principal = new AuthPrincipal(42L, publicId, "user@example.com", 0L, Role.USER);
        String token = testFactory.validAccessToken(principal);

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        AuthPrincipal set = (AuthPrincipal) SecurityContextHolder.getContext()
            .getAuthentication().getPrincipal();
        // userId is null after parse (resolved by filter in PT14); publicId and email round-trip
        assertThat(set.userPublicId()).isEqualTo(publicId);
        assertThat(set.email()).isEqualTo("user@example.com");
        assertThat(set.tokenVersion()).isEqualTo(0L);
        assertThat(set.role()).isEqualTo(Role.USER);
        verify(chain).doFilter(req, resp);
    }

    @Test
    void doFilter_clearsContextOnExpiredToken() throws Exception {
        AuthPrincipal p = new AuthPrincipal(1L, UUID.randomUUID(), "a@b.com", 0L, Role.USER);
        String token = testFactory.expiredAccessToken(p);

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(req, resp);
    }

    @Test
    void doFilter_clearsContextOnMalformedToken() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer not.a.jwt");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(req, resp);
    }

    @Test
    void doFilter_leavesContextUntouchedOnMissingHeader() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(req, resp);
    }

    @Test
    void doFilter_clearsContextOnBadSignature() throws Exception {
        String token = testFactory.tokenWithBadSignature();

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(req, resp);
    }

    @Test
    void doFilter_userToken_emitsRoleUserAuthority() throws Exception {
        AuthPrincipal principal = new AuthPrincipal(10L, UUID.randomUUID(), "u@x.com", 0L, Role.USER);
        String token = testFactory.validAccessToken(principal);

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        Collection<? extends GrantedAuthority> authorities =
            SecurityContextHolder.getContext().getAuthentication().getAuthorities();
        assertThat(authorities).extracting(GrantedAuthority::getAuthority)
            .containsExactly("ROLE_USER");
    }

    @Test
    void doFilter_adminToken_emitsRoleAdminAuthority() throws Exception {
        AuthPrincipal principal = new AuthPrincipal(11L, UUID.randomUUID(), "a@x.com", 0L, Role.ADMIN);
        String token = testFactory.validAccessToken(principal);

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        Collection<? extends GrantedAuthority> authorities =
            SecurityContextHolder.getContext().getAuthentication().getAuthorities();
        assertThat(authorities).extracting(GrantedAuthority::getAuthority)
            .containsExactly("ROLE_ADMIN");
    }
}
