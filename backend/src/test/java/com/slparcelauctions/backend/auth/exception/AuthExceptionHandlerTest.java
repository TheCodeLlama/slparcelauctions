package com.slparcelauctions.backend.auth.exception;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import com.slparcelauctions.backend.auth.JwtService;
import com.slparcelauctions.backend.user.UserRepository;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(SpringExtension.class)
@WebMvcTest(controllers = AuthExceptionHandlerTest.FakeThrowingController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({AuthExceptionHandler.class, AuthExceptionHandlerTest.FakeThrowingController.class})
class AuthExceptionHandlerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtService jwtService;

    @MockitoBean
    @SuppressWarnings("unused")
    private UserRepository userRepository;

    @RestController
    static class FakeThrowingController {
        @GetMapping("/test/invalid-credentials")
        void invalidCredentials() { throw new InvalidCredentialsException(); }

        @GetMapping("/test/email-exists")
        void emailExists() { throw new UsernameAlreadyExistsException("user@example.com"); }

        @GetMapping("/test/token-expired")
        void tokenExpired() { throw new TokenExpiredException("expired"); }

        @GetMapping("/test/token-invalid")
        void tokenInvalid() { throw new TokenInvalidException("bad"); }

        @GetMapping("/test/refresh-reuse")
        void refreshReuse() { throw new RefreshTokenReuseDetectedException(42L); }

        @GetMapping("/test/stale")
        void stale() { throw new AuthenticationStaleException(); }
    }

    @Test
    void invalidCredentials_mapsTo401WithAuthInvalidCredentialsCode() throws Exception {
        mockMvc.perform(get("/test/invalid-credentials"))
            .andExpect(status().isUnauthorized())
            .andExpect(header().string("Content-Type", "application/problem+json"))
            .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"))
            .andExpect(jsonPath("$.type").value("https://slpa.example/problems/auth/invalid-credentials"));
    }

    @Test
    void emailExists_mapsTo409WithAuthEmailExistsCode() throws Exception {
        mockMvc.perform(get("/test/email-exists"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("AUTH_USERNAME_EXISTS"));
    }

    @Test
    void tokenExpired_mapsTo401WithAuthTokenExpiredCode() throws Exception {
        mockMvc.perform(get("/test/token-expired"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("AUTH_TOKEN_EXPIRED"));
    }

    @Test
    void tokenInvalid_mapsTo401WithAuthTokenInvalidCode() throws Exception {
        mockMvc.perform(get("/test/token-invalid"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("AUTH_TOKEN_INVALID"));
    }

    @Test
    void refreshReuse_mapsTo401WithAuthRefreshTokenReusedCode() throws Exception {
        mockMvc.perform(get("/test/refresh-reuse"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("AUTH_REFRESH_TOKEN_REUSED"));
    }

    @Test
    void stale_mapsTo401WithAuthStaleSessionCode() throws Exception {
        mockMvc.perform(get("/test/stale"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("AUTH_STALE_SESSION"));
    }
}
