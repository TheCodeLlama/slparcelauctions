package com.slparcelauctions.backend.escrow.command;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import com.slparcelauctions.backend.escrow.command.dto.PayoutResultRequest;
import com.slparcelauctions.backend.escrow.command.exception.UnknownTerminalCommandException;
import com.slparcelauctions.backend.escrow.exception.EscrowExceptionHandler;
import com.slparcelauctions.backend.escrow.exception.TerminalAuthException;
import com.slparcelauctions.backend.escrow.terminal.TerminalService;
import com.slparcelauctions.backend.sl.SlHeaderValidator;
import com.slparcelauctions.backend.sl.exception.InvalidSlHeadersException;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Slice tests for {@link PayoutResultController}. Stubs
 * {@link TerminalCommandService}, {@link TerminalService}, and
 * {@link SlHeaderValidator} to exercise the controller's three-layer
 * gate (SL headers, shared secret, body) plus the mapped ProblemDetail
 * responses for failure branches. Six scenarios per plan.
 */
@WebMvcTest(PayoutResultController.class)
@Import({SlImInternalConfig.class, SecurityConfig.class, JwtAuthenticationFilter.class,
        JwtAuthenticationEntryPoint.class, EscrowExceptionHandler.class})
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "jwt.secret=dGVzdC1zZWNyZXQtdGVzdC1zZWNyZXQtdGVzdC1zZWNyZXQtdGVzdA==",
        "jwt.access-token-lifetime=PT15M",
        "jwt.refresh-token-lifetime=P7D",
        "slpa.notifications.cleanup.enabled=false",
        "slpa.notifications.sl-im.cleanup.enabled=false"
})
class PayoutResultControllerSliceTest {

    private static final String SHARD = "Production";
    private static final String OWNER_KEY = "00000000-0000-0000-0000-000000000001";
    private static final String TERMINAL_ID = "terminal-slice-a";
    private static final String SHARED_SECRET = "dev-escrow-secret-do-not-use-in-prod";

    @Autowired MockMvc mockMvc;
    @MockitoBean TerminalCommandService terminalCommandService;
    @MockitoBean TerminalService terminalService;
    @MockitoBean SlHeaderValidator headerValidator;
    @MockitoBean JwtService jwtService;
    @MockitoBean UserRepository userRepository;
    @MockitoBean JwtConfig jwtConfig;
    // SecurityConfig depends on BotSharedSecretAuthorizer (Epic 06 Task 3).
    @MockitoBean BotSharedSecretAuthorizer botSharedSecretAuthorizer;

    private static String successBody(String idempotencyKey) {
        return String.format("""
                {
                  "idempotencyKey":"%s",
                  "success":true,
                  "slTransactionKey":"sl-txn-success-123",
                  "errorMessage":null,
                  "terminalId":"%s",
                  "sharedSecret":"%s"
                }
                """, idempotencyKey, TERMINAL_ID, SHARED_SECRET);
    }

    private static String failureBody(String idempotencyKey, String secret) {
        return String.format("""
                {
                  "idempotencyKey":"%s",
                  "success":false,
                  "slTransactionKey":null,
                  "errorMessage":"balance too low",
                  "terminalId":"%s",
                  "sharedSecret":"%s"
                }
                """, idempotencyKey, TERMINAL_ID, secret);
    }

    @Test
    void successPayoutCallback_returns200Ok_delegatesToService() throws Exception {
        doNothing().when(headerValidator).validate(SHARD, OWNER_KEY);
        doNothing().when(terminalService).assertSharedSecret(SHARED_SECRET);
        doNothing().when(terminalCommandService).applyCallback(any(PayoutResultRequest.class));

        mockMvc.perform(post("/api/v1/sl/escrow/payout-result")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-SecondLife-Shard", SHARD)
                        .header("X-SecondLife-Owner-Key", OWNER_KEY)
                        .content(successBody("ESC-42-PAYOUT-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.reason").doesNotExist());

        verify(terminalCommandService).applyCallback(any(PayoutResultRequest.class));
    }

    @Test
    void successRefundCallback_returns200Ok() throws Exception {
        doNothing().when(headerValidator).validate(SHARD, OWNER_KEY);
        doNothing().when(terminalService).assertSharedSecret(SHARED_SECRET);
        doNothing().when(terminalCommandService).applyCallback(any(PayoutResultRequest.class));

        mockMvc.perform(post("/api/v1/sl/escrow/payout-result")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-SecondLife-Shard", SHARD)
                        .header("X-SecondLife-Owner-Key", OWNER_KEY)
                        .content(successBody("ESC-42-REFUND-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"));
    }

    @Test
    void failureBelowCap_returns200Ok_serviceHandlesRetryInternally() throws Exception {
        // applyCallback is responsible for scheduling backoff; controller
        // returns OK regardless so the terminal doesn't re-try at the HTTP
        // layer. The service exercises its own retry state machine.
        doNothing().when(headerValidator).validate(SHARD, OWNER_KEY);
        doNothing().when(terminalService).assertSharedSecret(SHARED_SECRET);
        doNothing().when(terminalCommandService).applyCallback(any(PayoutResultRequest.class));

        mockMvc.perform(post("/api/v1/sl/escrow/payout-result")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-SecondLife-Shard", SHARD)
                        .header("X-SecondLife-Owner-Key", OWNER_KEY)
                        .content(failureBody("ESC-42-PAYOUT-1", SHARED_SECRET)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"));
    }

    @Test
    void duplicateIdempotencyKey_returns200Ok() throws Exception {
        // applyCallback short-circuits on a COMPLETED command — the test
        // validates the service is still invoked (it owns the idempotency
        // branch) and the response stays OK.
        doNothing().when(headerValidator).validate(SHARD, OWNER_KEY);
        doNothing().when(terminalService).assertSharedSecret(SHARED_SECRET);
        doNothing().when(terminalCommandService).applyCallback(any(PayoutResultRequest.class));

        mockMvc.perform(post("/api/v1/sl/escrow/payout-result")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-SecondLife-Shard", SHARD)
                        .header("X-SecondLife-Owner-Key", OWNER_KEY)
                        .content(successBody("ESC-42-PAYOUT-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"));

        verify(terminalCommandService).applyCallback(any(PayoutResultRequest.class));
    }

    @Test
    void unknownIdempotencyKey_returns404TerminalCommandNotFound() throws Exception {
        String unknownKey = "ESC-999-PAYOUT-1";
        doNothing().when(headerValidator).validate(SHARD, OWNER_KEY);
        doNothing().when(terminalService).assertSharedSecret(SHARED_SECRET);
        doThrow(new UnknownTerminalCommandException(unknownKey))
                .when(terminalCommandService).applyCallback(any(PayoutResultRequest.class));

        mockMvc.perform(post("/api/v1/sl/escrow/payout-result")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-SecondLife-Shard", SHARD)
                        .header("X-SecondLife-Owner-Key", OWNER_KEY)
                        .content(successBody(unknownKey)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TERMINAL_COMMAND_NOT_FOUND"))
                .andExpect(jsonPath("$.idempotencyKey").value(unknownKey));
    }

    @Test
    void badSharedSecret_returns403WithSecretMismatch() throws Exception {
        doNothing().when(headerValidator).validate(SHARD, OWNER_KEY);
        doThrow(new TerminalAuthException())
                .when(terminalService).assertSharedSecret("wrong-secret");

        mockMvc.perform(post("/api/v1/sl/escrow/payout-result")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-SecondLife-Shard", SHARD)
                        .header("X-SecondLife-Owner-Key", OWNER_KEY)
                        .content(failureBody("ESC-42-PAYOUT-1", "wrong-secret")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SECRET_MISMATCH"));

        verifyNoInteractions(terminalCommandService);
    }

    @Test
    void badSlHeaders_returns403WithInvalidHeaders() throws Exception {
        doThrow(new InvalidSlHeadersException("Shard mismatch"))
                .when(headerValidator).validate(any(), any());

        mockMvc.perform(post("/api/v1/sl/escrow/payout-result")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(successBody("ESC-42-PAYOUT-1")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SL_INVALID_HEADERS"));

        verifyNoInteractions(terminalService, terminalCommandService);
    }
}
