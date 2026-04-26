package com.slparcelauctions.backend.escrow.terminal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.slparcelauctions.backend.auth.JwtAuthenticationEntryPoint;
import com.slparcelauctions.backend.auth.JwtAuthenticationFilter;
import com.slparcelauctions.backend.auth.JwtService;
import com.slparcelauctions.backend.auth.config.JwtConfig;
import com.slparcelauctions.backend.bot.BotSharedSecretAuthorizer;
import com.slparcelauctions.backend.notification.slim.internal.SlImInternalConfig;
import com.slparcelauctions.backend.config.SecurityConfig;
import com.slparcelauctions.backend.escrow.exception.EscrowExceptionHandler;
import com.slparcelauctions.backend.escrow.exception.TerminalAuthException;
import com.slparcelauctions.backend.escrow.terminal.dto.TerminalRegisterRequest;
import com.slparcelauctions.backend.sl.SlHeaderValidator;
import com.slparcelauctions.backend.sl.exception.InvalidSlHeadersException;

/**
 * Slice tests for {@link TerminalRegistrationController}. Stubs the service and
 * header validator; exercises the real Spring Security filter chain plus the
 * escrow exception handler so the 400/403 paths render the advertised
 * ProblemDetail shapes.
 */
@WebMvcTest(TerminalRegistrationController.class)
@Import({SlImInternalConfig.class, SecurityConfig.class, JwtAuthenticationFilter.class,
        JwtAuthenticationEntryPoint.class, EscrowExceptionHandler.class})
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "jwt.secret=dGVzdC1zZWNyZXQtdGVzdC1zZWNyZXQtdGVzdC1zZWNyZXQtdGVzdA==",
        "jwt.access-token-lifetime=PT15M",
        "jwt.refresh-token-lifetime=P7D",
        "slpa.notifications.cleanup.enabled=false"
})
class TerminalRegistrationControllerSliceTest {

    private static final String TERMINAL_ID = "11111111-1111-1111-1111-111111111111";
    private static final String HTTP_IN_URL = "https://sim123.agni.lindenlab.com:12043/cap/abc";
    private static final String REGION_NAME = "Pooley";
    private static final String SHARED_SECRET = "dev-escrow-secret-do-not-use-in-prod";
    private static final String SHARD = "Production";
    private static final String OWNER_KEY = "00000000-0000-0000-0000-000000000001";

    @Autowired MockMvc mockMvc;
    @MockitoBean TerminalService terminalService;
    @MockitoBean SlHeaderValidator headerValidator;
    @MockitoBean JwtService jwtService;
    @MockitoBean JwtConfig jwtConfig;
    // SecurityConfig depends on BotSharedSecretAuthorizer (Epic 06 Task 3).
    @MockitoBean BotSharedSecretAuthorizer botSharedSecretAuthorizer;

    private static String validBody() {
        return String.format("""
                {
                  "terminalId":"%s",
                  "httpInUrl":"%s",
                  "regionName":"%s",
                  "sharedSecret":"%s"
                }
                """, TERMINAL_ID, HTTP_IN_URL, REGION_NAME, SHARED_SECRET);
    }

    private static Terminal stubTerminal(OffsetDateTime lastSeenAt) {
        return Terminal.builder()
                .terminalId(TERMINAL_ID)
                .httpInUrl(HTTP_IN_URL)
                .regionName(REGION_NAME)
                .active(true)
                .lastSeenAt(lastSeenAt)
                .build();
    }

    @Test
    void validRegistration_returns200WithTerminalBody() throws Exception {
        doNothing().when(headerValidator).validate(SHARD, OWNER_KEY);
        OffsetDateTime now = OffsetDateTime.now();
        when(terminalService.register(any(TerminalRegisterRequest.class)))
                .thenReturn(stubTerminal(now));

        mockMvc.perform(post("/api/v1/sl/terminal/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-SecondLife-Shard", SHARD)
                        .header("X-SecondLife-Owner-Key", OWNER_KEY)
                        .content(validBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.terminalId").value(TERMINAL_ID))
                .andExpect(jsonPath("$.httpInUrl").value(HTTP_IN_URL))
                .andExpect(jsonPath("$.lastSeenAt").exists());
    }

    @Test
    void missingSlHeaders_returns403() throws Exception {
        doThrow(new InvalidSlHeadersException("Owner key missing or malformed"))
                .when(headerValidator).validate(null, null);

        mockMvc.perform(post("/api/v1/sl/terminal/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SL_INVALID_HEADERS"));

        verifyNoInteractions(terminalService);
    }

    @Test
    void badSharedSecret_returns403WithSecretMismatch() throws Exception {
        doNothing().when(headerValidator).validate(SHARD, OWNER_KEY);
        when(terminalService.register(any(TerminalRegisterRequest.class)))
                .thenThrow(new TerminalAuthException());

        String badBody = String.format("""
                {
                  "terminalId":"%s",
                  "httpInUrl":"%s",
                  "regionName":"%s",
                  "sharedSecret":"wrong-secret"
                }
                """, TERMINAL_ID, HTTP_IN_URL, REGION_NAME);

        mockMvc.perform(post("/api/v1/sl/terminal/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-SecondLife-Shard", SHARD)
                        .header("X-SecondLife-Owner-Key", OWNER_KEY)
                        .content(badBody))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SECRET_MISMATCH"));
    }

    @Test
    void reRegistration_returns200WithUpdatedUrlAndTimestamp() throws Exception {
        doNothing().when(headerValidator).validate(SHARD, OWNER_KEY);
        OffsetDateTime newTimestamp = OffsetDateTime.now().plusHours(1);
        String newUrl = "https://sim999.agni.lindenlab.com:12043/cap/zzz";
        Terminal updated = Terminal.builder()
                .terminalId(TERMINAL_ID)
                .httpInUrl(newUrl)
                .regionName(REGION_NAME)
                .active(true)
                .lastSeenAt(newTimestamp)
                .build();
        when(terminalService.register(any(TerminalRegisterRequest.class)))
                .thenReturn(updated);

        String reRegBody = String.format("""
                {
                  "terminalId":"%s",
                  "httpInUrl":"%s",
                  "regionName":"%s",
                  "sharedSecret":"%s"
                }
                """, TERMINAL_ID, newUrl, REGION_NAME, SHARED_SECRET);

        mockMvc.perform(post("/api/v1/sl/terminal/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-SecondLife-Shard", SHARD)
                        .header("X-SecondLife-Owner-Key", OWNER_KEY)
                        .content(reRegBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.terminalId").value(TERMINAL_ID))
                .andExpect(jsonPath("$.httpInUrl").value(newUrl))
                // Test name promises a timestamp check: the returned lastSeenAt
                // must be the updated value the mocked service produced (and
                // therefore match the ISO-8601 serialization of newTimestamp).
                .andExpect(jsonPath("$.lastSeenAt").exists())
                .andExpect(jsonPath("$.lastSeenAt").value(
                        newTimestamp.format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)));
    }

    @Test
    void emptyTerminalId_returns400() throws Exception {
        // headerValidator may or may not be called depending on Spring's
        // ordering; either way the service must not fire.
        doNothing().when(headerValidator).validate(anyString(), anyString());

        String invalidBody = String.format("""
                {
                  "terminalId":"",
                  "httpInUrl":"%s",
                  "regionName":"%s",
                  "sharedSecret":"%s"
                }
                """, HTTP_IN_URL, REGION_NAME, SHARED_SECRET);

        mockMvc.perform(post("/api/v1/sl/terminal/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-SecondLife-Shard", SHARD)
                        .header("X-SecondLife-Owner-Key", OWNER_KEY)
                        .content(invalidBody))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(terminalService);
    }
}
