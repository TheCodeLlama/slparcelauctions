package com.slparcelauctions.backend.escrow.payment;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
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
import com.slparcelauctions.backend.escrow.EscrowService;
import com.slparcelauctions.backend.escrow.exception.EscrowExceptionHandler;
import com.slparcelauctions.backend.escrow.exception.TerminalAuthException;
import com.slparcelauctions.backend.escrow.payment.dto.EscrowPaymentRequest;
import com.slparcelauctions.backend.escrow.payment.dto.SlCallbackResponse;
import com.slparcelauctions.backend.sl.SlHeaderValidator;
import com.slparcelauctions.backend.sl.exception.InvalidSlHeadersException;

/**
 * Slice tests for {@link EscrowPaymentController}. Stubs {@link EscrowService}
 * + {@link SlHeaderValidator} and exercises the real Spring Security filter
 * chain plus the {@link EscrowExceptionHandler} so the 200/403 paths render
 * the advertised ProblemDetail / {@link SlCallbackResponse} shapes. Covers
 * the eight scenarios from spec §13.2: the happy path plus seven failure
 * modes spanning domain REFUND/ERROR variants and the two 403 auth gates.
 */
@WebMvcTest(EscrowPaymentController.class)
@Import({SlImInternalConfig.class, SecurityConfig.class, JwtAuthenticationFilter.class,
        JwtAuthenticationEntryPoint.class, EscrowExceptionHandler.class})
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "jwt.secret=dGVzdC1zZWNyZXQtdGVzdC1zZWNyZXQtdGVzdC1zZWNyZXQtdGVzdA==",
        "jwt.access-token-lifetime=PT15M",
        "jwt.refresh-token-lifetime=P7D",
        "slpa.notifications.cleanup.enabled=false"
})
class EscrowPaymentControllerSliceTest {

    private static final String SHARD = "Production";
    private static final String OWNER_KEY = "00000000-0000-0000-0000-000000000001";
    private static final String TERMINAL_ID = "11111111-1111-1111-1111-111111111111";
    private static final String PAYER_UUID = "22222222-2222-2222-2222-222222222222";
    private static final String TXN_KEY = "sl-txn-abc123";
    private static final String SHARED_SECRET = "dev-escrow-secret-do-not-use-in-prod";

    @Autowired MockMvc mockMvc;
    @MockitoBean EscrowService escrowService;
    @MockitoBean SlHeaderValidator headerValidator;
    @MockitoBean JwtService jwtService;
    @MockitoBean JwtConfig jwtConfig;
    // SecurityConfig depends on BotSharedSecretAuthorizer (Epic 06 Task 3).
    // Slice tests don't exercise /api/v1/bot/** so a mock bean satisfies the
    // context without pulling in the whole bot package.
    @MockitoBean BotSharedSecretAuthorizer botSharedSecretAuthorizer;

    private static String body(long auctionId, long amount) {
        return body(auctionId, PAYER_UUID, amount, TXN_KEY, TERMINAL_ID, SHARED_SECRET);
    }

    private static String body(long auctionId, String payerUuid, long amount,
                               String txnKey, String terminalId, String secret) {
        return String.format("""
                {
                  "auctionId":%d,
                  "payerUuid":"%s",
                  "amount":%d,
                  "slTransactionKey":"%s",
                  "terminalId":"%s",
                  "sharedSecret":"%s"
                }
                """, auctionId, payerUuid, amount, txnKey, terminalId, secret);
    }

    @Test
    void validPayment_returns200Ok() throws Exception {
        doNothing().when(headerValidator).validate(SHARD, OWNER_KEY);
        when(escrowService.acceptPayment(any(EscrowPaymentRequest.class)))
                .thenReturn(SlCallbackResponse.ok());

        mockMvc.perform(post("/api/v1/sl/escrow/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-SecondLife-Shard", SHARD)
                        .header("X-SecondLife-Owner-Key", OWNER_KEY)
                        .content(body(42L, 5_000L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.reason").doesNotExist())
                .andExpect(jsonPath("$.message").doesNotExist());
    }

    @Test
    void wrongPayer_returns200RefundWithWrongPayerReason() throws Exception {
        doNothing().when(headerValidator).validate(SHARD, OWNER_KEY);
        when(escrowService.acceptPayment(any(EscrowPaymentRequest.class)))
                .thenReturn(SlCallbackResponse.refund(
                        EscrowCallbackResponseReason.WRONG_PAYER,
                        "Payer does not match auction winner"));

        mockMvc.perform(post("/api/v1/sl/escrow/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-SecondLife-Shard", SHARD)
                        .header("X-SecondLife-Owner-Key", OWNER_KEY)
                        .content(body(42L, 5_000L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUND"))
                .andExpect(jsonPath("$.reason").value("WRONG_PAYER"))
                .andExpect(jsonPath("$.message").value("Payer does not match auction winner"));
    }

    @Test
    void wrongAmount_returns200RefundWithWrongAmountReason() throws Exception {
        doNothing().when(headerValidator).validate(SHARD, OWNER_KEY);
        when(escrowService.acceptPayment(any(EscrowPaymentRequest.class)))
                .thenReturn(SlCallbackResponse.refund(
                        EscrowCallbackResponseReason.WRONG_AMOUNT,
                        "Expected L$5000, got L$3000"));

        mockMvc.perform(post("/api/v1/sl/escrow/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-SecondLife-Shard", SHARD)
                        .header("X-SecondLife-Owner-Key", OWNER_KEY)
                        .content(body(42L, 3_000L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUND"))
                .andExpect(jsonPath("$.reason").value("WRONG_AMOUNT"))
                .andExpect(jsonPath("$.message").value("Expected L$5000, got L$3000"));
    }

    @Test
    void expiredEscrow_returns200RefundWithExpiredReason() throws Exception {
        doNothing().when(headerValidator).validate(SHARD, OWNER_KEY);
        when(escrowService.acceptPayment(any(EscrowPaymentRequest.class)))
                .thenReturn(SlCallbackResponse.refund(
                        EscrowCallbackResponseReason.ESCROW_EXPIRED,
                        "Payment deadline exceeded"));

        mockMvc.perform(post("/api/v1/sl/escrow/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-SecondLife-Shard", SHARD)
                        .header("X-SecondLife-Owner-Key", OWNER_KEY)
                        .content(body(42L, 5_000L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUND"))
                .andExpect(jsonPath("$.reason").value("ESCROW_EXPIRED"));
    }

    @Test
    void duplicateSlTransactionKey_returns200Ok() throws Exception {
        // Idempotent replay of a previously COMPLETED payment returns the
        // same OK response without reprocessing the domain.
        doNothing().when(headerValidator).validate(SHARD, OWNER_KEY);
        when(escrowService.acceptPayment(any(EscrowPaymentRequest.class)))
                .thenReturn(SlCallbackResponse.ok());

        mockMvc.perform(post("/api/v1/sl/escrow/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-SecondLife-Shard", SHARD)
                        .header("X-SecondLife-Owner-Key", OWNER_KEY)
                        .content(body(42L, 5_000L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"));
    }

    @Test
    void badSlHeaders_returns403WithInvalidHeadersCode() throws Exception {
        doThrow(new InvalidSlHeadersException("Owner key missing or malformed"))
                .when(headerValidator).validate(any(), any());

        mockMvc.perform(post("/api/v1/sl/escrow/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(42L, 5_000L)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SL_INVALID_HEADERS"));

        verifyNoInteractions(escrowService);
    }

    @Test
    void badSharedSecret_returns403WithSecretMismatch() throws Exception {
        doNothing().when(headerValidator).validate(SHARD, OWNER_KEY);
        when(escrowService.acceptPayment(any(EscrowPaymentRequest.class)))
                .thenThrow(new TerminalAuthException());

        mockMvc.perform(post("/api/v1/sl/escrow/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-SecondLife-Shard", SHARD)
                        .header("X-SecondLife-Owner-Key", OWNER_KEY)
                        .content(body(42L, PAYER_UUID, 5_000L, TXN_KEY, TERMINAL_ID, "wrong-secret")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SECRET_MISMATCH"));
    }

    @Test
    void unknownAuction_returns200ErrorWithUnknownAuctionReason() throws Exception {
        doNothing().when(headerValidator).validate(SHARD, OWNER_KEY);
        when(escrowService.acceptPayment(any(EscrowPaymentRequest.class)))
                .thenReturn(SlCallbackResponse.error(
                        EscrowCallbackResponseReason.UNKNOWN_AUCTION,
                        "No escrow for auction 999"));

        mockMvc.perform(post("/api/v1/sl/escrow/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-SecondLife-Shard", SHARD)
                        .header("X-SecondLife-Owner-Key", OWNER_KEY)
                        .content(body(999L, 5_000L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.reason").value("UNKNOWN_AUCTION"))
                .andExpect(jsonPath("$.message").value("No escrow for auction 999"));
    }

    @Test
    void malformedBody_returns400() throws Exception {
        // Spec coverage: missing required fields fail bean-validation before
        // reaching either the header validator or the service.
        doNothing().when(headerValidator).validate(anyString(), anyString());

        mockMvc.perform(post("/api/v1/sl/escrow/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-SecondLife-Shard", SHARD)
                        .header("X-SecondLife-Owner-Key", OWNER_KEY)
                        .content("""
                                {
                                  "auctionId":42,
                                  "payerUuid":"",
                                  "amount":5000,
                                  "slTransactionKey":"tx",
                                  "terminalId":"term",
                                  "sharedSecret":"secret"
                                }
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(escrowService);
    }
}
