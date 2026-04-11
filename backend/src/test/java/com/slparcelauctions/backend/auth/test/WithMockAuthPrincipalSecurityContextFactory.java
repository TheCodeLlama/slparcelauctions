package com.slparcelauctions.backend.auth.test;

import com.slparcelauctions.backend.auth.AuthPrincipal;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithSecurityContextFactory;
import java.util.List;

public class WithMockAuthPrincipalSecurityContextFactory
        implements WithSecurityContextFactory<WithMockAuthPrincipal> {

    @Override
    public SecurityContext createSecurityContext(WithMockAuthPrincipal annotation) {
        AuthPrincipal principal = new AuthPrincipal(
            annotation.userId(),
            annotation.email(),
            annotation.tokenVersion()
        );
        Authentication auth = new UsernamePasswordAuthenticationToken(
            principal, null, List.of()
        );
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        return context;
    }
}
