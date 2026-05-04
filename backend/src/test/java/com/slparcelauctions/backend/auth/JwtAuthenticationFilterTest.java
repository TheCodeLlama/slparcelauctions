package com.slparcelauctions.backend.auth;

import com.slparcelauctions.backend.auth.config.JwtConfig;
import com.slparcelauctions.backend.auth.test.JwtTestFactory;
import com.slparcelauctions.backend.user.Role;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
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
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JwtAuthenticationFilterTest {

    private static final String SECRET = base64Of32RandomBytes();

    private JwtAuthenticationFilter filter;
    private JwtTestFactory testFactory;
    private FilterChain chain;
    private UserRepository userRepository;

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
        userRepository = mock(UserRepository.class);
        filter = new JwtAuthenticationFilter(jwtService, userRepository);
        testFactory = JwtTestFactory.forKey(SECRET);
        chain = mock(FilterChain.class);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /** Builds a Mockito-backed User stub whose getId / getPublicId return the supplied values. */
    private User stubUser(Long id, UUID publicId) {
        User user = mock(User.class);
        when(user.getId()).thenReturn(id);
        when(user.getPublicId()).thenReturn(publicId);
        return user;
    }

    @Test
    void doFilter_setsPrincipalOnValidToken() throws Exception {
        UUID publicId = UUID.randomUUID();
        AuthPrincipal principal = new AuthPrincipal(42L, publicId, "user@example.com", 0L, Role.USER);
        String token = testFactory.validAccessToken(principal);
        User userStub = stubUser(42L, publicId);
        when(userRepository.findByPublicId(publicId)).thenReturn(Optional.of(userStub));

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        AuthPrincipal set = (AuthPrincipal) SecurityContextHolder.getContext()
            .getAuthentication().getPrincipal();
        assertThat(set.userId()).isEqualTo(42L);
        assertThat(set.userPublicId()).isEqualTo(publicId);
        assertThat(set.email()).isEqualTo("user@example.com");
        assertThat(set.tokenVersion()).isEqualTo(0L);
        assertThat(set.role()).isEqualTo(Role.USER);
        verify(chain).doFilter(req, resp);
    }

    @Test
    void doFilter_clearsContextWhenUserNotFound() throws Exception {
        UUID publicId = UUID.randomUUID();
        AuthPrincipal principal = new AuthPrincipal(99L, publicId, "gone@example.com", 0L, Role.USER);
        String token = testFactory.validAccessToken(principal);
        when(userRepository.findByPublicId(publicId)).thenReturn(Optional.empty());

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
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
        UUID publicId = UUID.randomUUID();
        AuthPrincipal principal = new AuthPrincipal(10L, publicId, "u@x.com", 0L, Role.USER);
        String token = testFactory.validAccessToken(principal);
        User userStub = stubUser(10L, publicId);
        when(userRepository.findByPublicId(publicId)).thenReturn(Optional.of(userStub));

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
        UUID publicId = UUID.randomUUID();
        AuthPrincipal principal = new AuthPrincipal(11L, publicId, "a@x.com", 0L, Role.ADMIN);
        String token = testFactory.validAccessToken(principal);
        User userStub = stubUser(11L, publicId);
        when(userRepository.findByPublicId(publicId)).thenReturn(Optional.of(userStub));

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
